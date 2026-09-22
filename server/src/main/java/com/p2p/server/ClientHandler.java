package com.p2p.server;

import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;
import com.p2p.server.config.ServerConfig;
import com.p2p.server.database.Database;
import com.p2p.server.session.Session;
import com.p2p.server.session.SessionManager;

import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ClientHandler implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());
    private final Session session;
    private final Database database;
    private final SessionManager sessions;
    private final ServerConfig config;
    private final ServerObserver observer;

    ClientHandler(Socket socket, Database database, SessionManager sessions, ServerConfig config, ServerObserver observer) throws IOException {
        this.session = new Session(socket);
        this.database = database;
        this.sessions = sessions;
        this.config = config;
        this.observer = observer == null ? ServerObserver.NONE : observer;
    }

    @Override public void run() {
        LOG.info(() -> "Client connected: " + session.remoteAddress());
        observer.clientConnected(session.remoteAddress());
        try {
            Message message;
            while ((message = session.read()) != null) handle(message);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Client disconnected: " + session.remoteAddress(), e);
        } finally {
            disconnect();
        }
    }

    private void handle(Message request) throws IOException {
        if (request.getType() == null) { sendError(request, "Message type is required"); return; }
        if (request.getType() != MessageType.FILE_CHUNK && request.getType() != MessageType.FILE_PROGRESS) {
            observer.requestReceived(session.user() == null ? "Chưa đặt tên" : session.user().username(), request.getType());
        }
        try {
            switch (request.getType()) {
                case JOIN_REQUEST -> join(request);
                case PING -> send(Message.of(MessageType.PONG).put("time", System.currentTimeMillis()));
                case USER_LIST_REQUEST -> userList(request);
                case PRIVATE_MESSAGE -> privateMessage(request);
                case CREATE_ROOM -> createRoom(request);
                case JOIN_ROOM -> joinRoom(request);
                case LEAVE_ROOM -> leaveRoom(request);
                case GROUP_MESSAGE -> groupMessage(request);
                case FILE_OFFER -> fileOffer(request);
                case FILE_ACCEPT, FILE_REJECT, FILE_CANCEL, FILE_CHUNK, FILE_PROGRESS, FILE_COMPLETE, FILE_FAILED -> fileEvent(request);
                case FILE_SHARE -> shareFile(request);
                case FILE_UNSHARE -> unshareFile(request);
                case FILE_SEARCH_REQUEST -> searchFiles(request);
                default -> sendError(request, "Unsupported request: " + request.getType());
            }
        } catch (IllegalArgumentException e) {
            sendError(request, e.getMessage());
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Database error while handling " + request.getType(), e);
            sendError(request, "Database error");
        }
    }

    private void join(Message request) throws SQLException, IOException {
        if (session.isJoined()) { sendError(request, "Bạn đã tham gia phòng chat"); return; }
        String name = required(request, "name").trim();
        if (name.length() > 32 || !name.matches("[\\p{L}\\p{N} _.-]+")) {
            throw new IllegalArgumentException("Tên phải có 1-32 ký tự và không chứa ký tự đặc biệt");
        }
        var user = database.findOrCreateUser(name);
        session.join(user);
        if (!sessions.add(session)) {
            session.leave();
            send(response(MessageType.JOIN_RESPONSE, request).error("Tên này đang được sử dụng"));
            return;
        }
        send(response(MessageType.JOIN_RESPONSE, request).success(true, "Đã tham gia phòng chat").put("name", user.username()));
        observer.clientJoined(session.remoteAddress(), user.username());
        sessions.broadcast(Message.of(MessageType.USER_STATUS_CHANGED).put("username", user.username()).put("online", true));
    }

    private void userList(Message request) throws IOException {
        requireJoined();
        send(response(MessageType.USER_LIST_RESPONSE, request).success(true, "OK").put("users", sessions.usernames()));
    }

    private void privateMessage(Message request) throws SQLException, IOException {
        requireJoined();
        String receiver = required(request, "receiver");
        String content = required(request, "content");
        if (content.length() > 10_000) throw new IllegalArgumentException("Message is too long");
        Session target = sessions.byUsername(receiver);
        if (target == null) { send(response(MessageType.ERROR, request).error("Recipient is offline")); return; }
        database.savePrivateMessage(session.user().id(), target.user().id(), content);
        Message event = Message.of(MessageType.PRIVATE_MESSAGE).put("sender", session.user().username()).put("receiver", receiver).put("content", content).put("time", System.currentTimeMillis());
        target.send(event);
        send(response(MessageType.PRIVATE_MESSAGE, request).success(true, "Delivered").put("delivered", true));
    }

    private void createRoom(Message request) throws SQLException, IOException {
        requireJoined();
        String name = required(request, "name");
        if (name.length() > 80) throw new IllegalArgumentException("Room name is too long");
        long roomId = database.createRoom(session.user().id(), name);
        send(response(MessageType.ROOM_UPDATED, request).success(true, "Room created").put("roomId", roomId).put("name", name));
    }

    private void joinRoom(Message request) throws SQLException, IOException {
        requireJoined();
        long roomId = requiredLong(request, "roomId");
        database.joinRoom(roomId, session.user().id());
        send(response(MessageType.ROOM_UPDATED, request).success(true, "Joined room").put("roomId", roomId).put("username", session.user().username()));
    }

    private void leaveRoom(Message request) throws SQLException, IOException {
        requireJoined();
        long roomId = requiredLong(request, "roomId");
        database.leaveRoom(roomId, session.user().id());
        send(response(MessageType.ROOM_UPDATED, request).success(true, "Left room").put("roomId", roomId).put("username", session.user().username()));
    }

    private void groupMessage(Message request) throws SQLException, IOException {
        requireJoined();
        long roomId = requiredLong(request, "roomId");
        String content = required(request, "content");
        List<Long> members = database.roomMembers(roomId);
        if (!members.contains(session.user().id())) throw new IllegalArgumentException("You are not a member of this room");
        database.saveGroupMessage(session.user().id(), roomId, content);
        Message event = Message.of(MessageType.GROUP_MESSAGE).put("roomId", roomId).put("sender", session.user().username()).put("content", content).put("time", System.currentTimeMillis());
        for (Long id : members) { Session target = sessions.byId(id); if (target != null) target.send(event); }
        send(response(MessageType.GROUP_MESSAGE, request).success(true, "Delivered"));
    }

    private void fileOffer(Message request) throws IOException {
        requireJoined();
        String receiver = required(request, "receiver");
        String name = required(request, "fileName");
        long size = requiredLong(request, "fileSize");
        String transferId = request.string("transferId");
        if (transferId == null || transferId.isBlank()) transferId = request.getRequestId();
        if (size < 0 || size > config.maxFileSize()) throw new IllegalArgumentException("File size is not allowed");
        Session target = sessions.byUsername(receiver);
        if (target == null) { send(response(MessageType.FILE_FAILED, request).error("Recipient is offline")); return; }
        Message offer = Message.of(MessageType.FILE_OFFER).put("transferId", transferId).put("sender", session.user().username()).put("receiver", receiver).put("fileName", name).put("fileSize", size).put("sha256", request.string("sha256"));
        target.send(offer);
        send(response(MessageType.FILE_OFFER, request).success(true, "Offer sent").put("transferId", transferId));
    }

    private void fileEvent(Message request) throws IOException {
        requireJoined();
        String receiver = request.string("receiver");
        if (receiver == null) receiver = request.string("sender");
        Session target = sessions.byUsername(receiver);
        if (target == null) {
            if (request.getType() != MessageType.FILE_CHUNK && request.getType() != MessageType.FILE_PROGRESS) {
                send(response(MessageType.FILE_FAILED, request).error("Recipient is offline"));
            }
            return;
        }
        target.send(request.put("from", session.user().username()));
        // Chunks and progress are data-plane messages; do not echo an acknowledgement
        // for every chunk back to the sender.
        if (request.getType() != MessageType.FILE_CHUNK && request.getType() != MessageType.FILE_PROGRESS) {
            send(response(request.getType(), request).success(true, "Forwarded"));
        }
    }

    private void shareFile(Message request) throws SQLException, IOException {
        requireJoined();
        String name = required(request, "fileName");
        long size = requiredLong(request, "fileSize");
        int port = (int) requiredLong(request, "peerPort");
        database.shareFile(session.user().id(), name, size, request.string("sha256"), request.string("peerIp"), port);
        send(response(MessageType.FILE_SHARE, request).success(true, "File shared"));
    }

    private void unshareFile(Message request) throws SQLException, IOException {
        requireJoined();
        database.unshareFile(session.user().id(), required(request, "fileName"));
        send(response(MessageType.FILE_UNSHARE, request).success(true, "File unshared"));
    }

    private void searchFiles(Message request) throws SQLException, IOException {
        requireJoined();
        List<Database.SharedFile> files = database.searchFiles(request.string("query") == null ? "" : request.string("query"));
        List<java.util.Map<String, Object>> result = files.stream().map(f -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("fileName", f.name());
            item.put("fileSize", f.size());
            item.put("sha256", f.sha256() == null ? "" : f.sha256());
            item.put("peerIp", f.ip() == null ? "" : f.ip());
            item.put("peerPort", f.port());
            return item;
        }).toList();
        send(response(MessageType.FILE_SEARCH_RESPONSE, request).success(true, "OK").put("files", result));
    }

    private void disconnect() {
        String username = session.user() == null ? null : session.user().username();
        if (session.user() != null) {
            sessions.remove(session);
            sessions.broadcast(Message.of(MessageType.USER_STATUS_CHANGED).put("username", username).put("online", false));
        }
        observer.clientDisconnected(session.remoteAddress(), username);
        session.close();
    }

    private void requireJoined() {
        if (!session.isJoined()) throw new IllegalArgumentException("Bạn cần nhập tên trước khi sử dụng phòng chat");
    }

    private String required(Message request, String key) {
        String value = request.string(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private long requiredLong(Message request, String key) {
        String value = required(request, key);
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be a number"); }
    }

    private void send(Message message) throws IOException { session.send(message); }
    private void sendError(Message request, String text) throws IOException { send(response(MessageType.ERROR, request).error(text)); }
    private Message response(MessageType type, Message request) {
        Message response = Message.of(type);
        response.setRequestId(request.getRequestId());
        return response;
    }
}
