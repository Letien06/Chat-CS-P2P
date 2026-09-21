package com.p2p.client.ui;

import com.p2p.client.network.ChatClient;
import com.p2p.client.network.PeerFileClient;
import com.p2p.client.network.PeerFileServer;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatFrame extends JFrame {
    private final String username;
    private final String serverAddress;
    private final ChatClient client;
    private final PeerFileServer peerFileServer;
    private final DefaultListModel<String> users = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(users);
    private final MessageListPanel transcript = new MessageListPanel();
    private final JTextField messageField = new JTextField();
    private final ToastNotification transferToast = new ToastNotification();
    private final Timer toastTimer = new Timer(2800, e -> transferToast.setVisible(false));
    private final JLabel chatTitle = new JLabel("Chọn một cuộc trò chuyện");
    private final JLabel chatStatus = new JLabel("");
    private final Theme.Avatar chatAvatar = new Theme.Avatar("?", 42, false);
    private final ExecutorService transfers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "file-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, OutgoingTransfer> outgoing = new ConcurrentHashMap<>();
    private final Map<String, PendingFileOffer> pendingOffers = new ConcurrentHashMap<>();
    private final Map<String, FileCard> fileCards = new ConcurrentHashMap<>();
    private final Map<String, List<ChatEntry>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<Long, List<ChatEntry>> roomHistory = new ConcurrentHashMap<>();
    private final List<String> onlineUsers = new ArrayList<>();
    private String activePeer;
    private Long activeRoomId;
    private String activeRoomName;

    private RoomDialog roomDialog;
    private FileSearchDialog fileSearchDialog;

    public ChatFrame(String username, String serverAddress, ChatClient client, PeerFileServer peerFileServer) {
        super("MiniChat - " + username);
        this.username = username;
        this.serverAddress = serverAddress;
        this.client = client;
        this.peerFileServer = peerFileServer;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1180, 760);
        setLocationRelativeTo(null);
        toastTimer.setRepeats(false);
        buildUi();
    }

    private void buildUi() {
        transcript.setBackground(Theme.CHAT_BACKGROUND);
        transcript.setBorder(Theme.padding(24, 34, 24, 34));

        JPanel navigation = Theme.roundedPanel(Theme.NAV_BACKGROUND, 18);
        navigation.setLayout(new BorderLayout(0, 14));
        navigation.setBorder(Theme.padding(18, 10, 14, 10));

        JPanel navTop = new JPanel();
        navTop.setOpaque(false);
        navTop.setLayout(new BoxLayout(navTop, BoxLayout.Y_AXIS));
        Theme.Avatar profileAvatar = new Theme.Avatar(username, 48, true);
        profileAvatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        navTop.add(profileAvatar);
        navTop.add(Box.createVerticalStrut(22));
        JButton chats = Theme.navButton(new AppIcon(AppIcon.Type.CHAT, Theme.PRIMARY, 21), "Tin nhắn", true);
        chats.setAlignmentX(Component.CENTER_ALIGNMENT);
        navTop.add(chats);
        navTop.add(Box.createVerticalStrut(10));
        JButton rooms = Theme.navButton(new AppIcon(AppIcon.Type.CHAT, Theme.MUTED, 21), "Phòng chat", false);
        rooms.setAlignmentX(Component.CENTER_ALIGNMENT);
        rooms.addActionListener(e -> openRooms());
        navTop.add(rooms);
        navTop.add(Box.createVerticalStrut(10));
        JButton files = Theme.navButton(new AppIcon(AppIcon.Type.FOLDER, Theme.MUTED, 21), "Tìm và chia sẻ file P2P", false);
        files.setAlignmentX(Component.CENTER_ALIGNMENT);
        files.addActionListener(e -> openFileSearch());
        navTop.add(files);
        navigation.add(navTop, BorderLayout.NORTH);

        userList.setFixedCellHeight(58);
        userList.setBorder(Theme.padding(6, 4, 6, 4));
        userList.setBackground(Theme.SURFACE);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserCellRenderer(username));
        userList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectPeer(userList.getSelectedValue()); });
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createEmptyBorder());
        userScroll.getViewport().setBackground(Theme.SURFACE);
        userScroll.getVerticalScrollBar().setUnitIncrement(12);

        JTextField search = new JTextField();
        search.setPreferredSize(new Dimension(0, 38));
        search.setBorder(Theme.padding(0, 12, 0, 12));
        search.putClientProperty("JTextField.placeholderText", "Tìm người dùng...");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
        });
        JPanel contacts = Theme.roundedPanel(Theme.SURFACE, 18);
        contacts.setLayout(new BorderLayout(0, 12));
        contacts.setBorder(Theme.padding(18, 14, 14, 14));
        JPanel contactsHeader = new JPanel();
        contactsHeader.setOpaque(false);
        contactsHeader.setLayout(new BoxLayout(contactsHeader, BoxLayout.Y_AXIS));
        JLabel appName = new JLabel("MiniChat");
        appName.setFont(Theme.font(Font.BOLD, 19));
        appName.setForeground(Theme.TEXT);
        JLabel onlineLabel = new JLabel("Người dùng đang online");
        onlineLabel.setFont(Theme.font(Font.PLAIN, 12));
        onlineLabel.setForeground(Theme.MUTED);
        contactsHeader.add(appName);
        contactsHeader.add(Box.createVerticalStrut(4));
        contactsHeader.add(onlineLabel);
        contactsHeader.add(Box.createVerticalStrut(14));
        contactsHeader.add(search);
        contacts.add(contactsHeader, BorderLayout.NORTH);
        contacts.add(userScroll, BorderLayout.CENTER);

        JPanel navBottom = new JPanel();
        navBottom.setOpaque(false);
        navBottom.setLayout(new BoxLayout(navBottom, BoxLayout.Y_AXIS));
        JButton refresh = Theme.navButton(new AppIcon(AppIcon.Type.REFRESH, Theme.MUTED, 20), "Làm mới danh sách", false);
        refresh.setAlignmentX(Component.CENTER_ALIGNMENT);
        refresh.addActionListener(e -> refreshUsers());
        JButton exit = Theme.navButton(new AppIcon(AppIcon.Type.LOGOUT, Theme.MUTED, 20), "Thoát", false);
        exit.setAlignmentX(Component.CENTER_ALIGNMENT);
        exit.addActionListener(e -> dispose());
        navBottom.add(refresh);
        navBottom.add(Box.createVerticalStrut(9));
        navBottom.add(exit);
        navigation.add(navBottom, BorderLayout.SOUTH);

        JPanel side = new JPanel(new BorderLayout(8, 0));
        side.setOpaque(false);
        side.add(navigation, BorderLayout.WEST);
        side.add(contacts, BorderLayout.CENTER);

        JPanel chat = Theme.roundedPanel(Theme.SURFACE, 18);
        chat.setLayout(new BorderLayout());
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(Theme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                Theme.padding(15, 22, 15, 22)));
        header.add(chatAvatar, BorderLayout.WEST);
        JPanel headerText = new JPanel(); headerText.setOpaque(false); headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        chatTitle.setFont(Theme.font(Font.BOLD, 17)); chatTitle.setForeground(Theme.TEXT);
        chatStatus.setFont(Theme.font(Font.PLAIN, 12)); chatStatus.setForeground(Theme.MUTED);
        headerText.add(chatTitle); headerText.add(Box.createVerticalStrut(4)); headerText.add(chatStatus); header.add(headerText, BorderLayout.CENTER);
        JLabel secure = new JLabel("●  File P2P trực tiếp"); secure.setFont(Theme.font(Font.PLAIN, 12)); secure.setForeground(Theme.ONLINE); header.add(secure, BorderLayout.EAST);
        chat.add(header, BorderLayout.NORTH);
        JScrollPane transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        transcriptScroll.getViewport().setBackground(Theme.CHAT_BACKGROUND);
        transcriptScroll.getVerticalScrollBar().setUnitIncrement(14);
        chat.add(transcriptScroll, BorderLayout.CENTER);

        JPanel composer = Theme.roundedPanel(Theme.COMPOSER_BACKGROUND, 28);
        composer.setLayout(new BorderLayout(8, 0));
        composer.setBorder(Theme.padding(7, 10, 7, 10));
        JButton sendFile = Theme.iconButton(new AppIcon(AppIcon.Type.ATTACHMENT, Theme.MUTED, 18), "Đính kèm file", false);
        sendFile.setBackground(Theme.COMPOSER_BACKGROUND);
        sendFile.addActionListener(e -> chooseAndSendFile());
        JButton send = Theme.iconButton(new AppIcon(AppIcon.Type.SEND, Theme.MUTED, 19), "Gửi tin nhắn", false);
        send.setBackground(Theme.COMPOSER_BACKGROUND);
        send.addActionListener(e -> sendMessage());
        composer.add(sendFile, BorderLayout.WEST);
        composer.add(messageField, BorderLayout.CENTER);
        composer.add(send, BorderLayout.EAST);
        messageField.addActionListener(e -> sendMessage());
        messageField.setPreferredSize(new Dimension(0, 44));
        messageField.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        messageField.setOpaque(false);
        messageField.setForeground(Theme.TEXT);
        messageField.setCaretColor(Theme.TEXT);
        messageField.putClientProperty("JTextField.placeholderText", "Nhập tin nhắn... (Enter để gửi)");

        JLabel connection = new JLabel("●  Sẵn sàng · " + serverAddress);
        connection.setFont(Theme.font(Font.PLAIN, 11));
        connection.setForeground(Theme.ONLINE);
        connection.setBorder(Theme.padding(7, 8, 1, 8));
        JPanel lower = new JPanel(new BorderLayout());
        lower.setBackground(Theme.SURFACE);
        lower.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                Theme.padding(12, 18, 9, 18)));
        lower.add(composer, BorderLayout.CENTER);
        lower.add(connection, BorderLayout.SOUTH);
        chat.add(lower, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(8, 0));
        root.setBackground(Theme.APP_BACKGROUND);
        root.setBorder(Theme.padding(8, 8, 8, 8));
        navigation.setPreferredSize(new Dimension(82, 0));
        side.setPreferredSize(new Dimension(326, 0));
        root.add(side, BorderLayout.WEST);
        root.add(chat, BorderLayout.CENTER);
        setContentPane(root);
        installTransferToast();
    }

    private void installTransferToast() {
        JLayeredPane layeredPane = getLayeredPane();
        transferToast.setVisible(false);
        layeredPane.add(transferToast, JLayeredPane.POPUP_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { positionTransferToast(); }
        });
    }

    private void positionTransferToast() {
        Dimension size = transferToast.getPreferredSize();
        int x = Math.max(16, (getLayeredPane().getWidth() - size.width) / 2);
        int y = Math.max(16, getLayeredPane().getHeight() - size.height - 88);
        transferToast.setBounds(x, y, size.width, size.height);
    }

    private void refreshUsers() {
        try { client.send(Message.of(MessageType.USER_LIST_REQUEST)); }
        catch (IOException e) { showError(e.getMessage()); }
    }
    public void requestUsers() { refreshUsers(); }
    private void requestRooms() {
        try { client.send(Message.of(MessageType.ROOM_LIST_REQUEST)); }
        catch (IOException e) { showError(e.getMessage()); }
    }

    private void openRooms() {
        if (roomDialog == null || !roomDialog.isDisplayable()) roomDialog = new RoomDialog();
        roomDialog.setVisible(true);
        requestRooms();
    }

    private void openFileSearch() {
        if (fileSearchDialog == null || !fileSearchDialog.isDisplayable()) fileSearchDialog = new FileSearchDialog();
        fileSearchDialog.setVisible(true);
        fileSearchDialog.search();
    }

    private void selectPeer(String peer) {
        // Refreshing the online-user model briefly clears the JList selection.
        // Keep the active conversation instead of treating that as a user action.
        if (peer == null || peer.equals(username)) {
            if (activePeer != null || activeRoomId != null) return;
            chatTitle.setText("Chọn một cuộc trò chuyện");
            chatStatus.setText("Chọn một người dùng để bắt đầu");
            chatAvatar.setName("?");
            chatAvatar.setOnline(false);
            return;
        }
        if (peer.equals(activePeer)) return;
        activeRoomId = null;
        activeRoomName = null;
        activePeer = peer;
        chatTitle.setText(peer);
        chatStatus.setText("● Đang hoạt động trong mạng LAN");
        chatAvatar.setName(peer);
        chatAvatar.setOnline(true);
        transcript.removeAll();
        for (ChatEntry entry : conversationHistory.getOrDefault(peer, List.of())) {
            if (entry.fileCard() != null) addFileCard(entry.fileCard(), entry.mine());
            else renderLine(entry.text());
        }
        transcript.revalidate();
        transcript.repaint();
        scrollTranscriptToBottom();
    }

    private void sendMessage() {
        String content = messageField.getText().trim();
        if (content.isBlank()) return;
        try {
            if (activeRoomId != null) {
                client.send(Message.of(MessageType.GROUP_MESSAGE).put("roomId", activeRoomId).put("content", content));
            } else {
                String receiver = selectedPeer();
                if (receiver == null) return;
                client.send(Message.of(MessageType.PRIVATE_MESSAGE).put("receiver", receiver).put("content", content));
                appendToConversation(receiver, "Bạn → " + receiver + ": " + content);
            }
            messageField.setText("");
        } catch (IOException e) { showError(e.getMessage()); }
    }

    private void chooseAndSendFile() {
        String receiver = selectedPeer();
        if (receiver == null) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        transfers.submit(() -> prepareFile(receiver, chooser.getSelectedFile().toPath()));
    }

    private void prepareFile(String receiver, Path file) {
        try {
            long size = Files.size(file);
            if (size > 2L * 1024 * 1024 * 1024) { SwingUtilities.invokeLater(() -> showError("File vượt quá giới hạn 2 GB.")); return; }
            String transferId = UUID.randomUUID().toString();
            String accessToken = UUID.randomUUID().toString();
            String sha256 = sha256(file);
            outgoing.put(transferId, new OutgoingTransfer(receiver, file, size, sha256, accessToken));
            SwingUtilities.invokeAndWait(() -> {
                FileCard card = new FileCard(file.getFileName().toString(), formatBytes(size) + " · Đang chờ người nhận");
                fileCards.put(transferId, card);
                addFileCardToConversation(receiver, card, true);
            });
            peerFileServer.register(accessToken, file, true, new PeerFileServer.Listener() {
                @Override public void started(String key, String requester) {
                    SwingUtilities.invokeLater(() -> {
                        FileCard card = fileCards.get(transferId);
                        if (card != null) card.markSending(0);
                    });
                }
                @Override public void progress(String key, long sent, long total) {
                    int value = percent(sent, total);
                    setProgress(value, "Đang gửi P2P...");
                    SwingUtilities.invokeLater(() -> {
                        FileCard card = fileCards.get(transferId);
                        if (card != null) card.markSending(value);
                    });
                }
                @Override public void completed(String key) {
                    outgoing.remove(transferId);
                    SwingUtilities.invokeLater(() -> {
                        FileCard card = fileCards.get(transferId);
                        if (card != null) card.markSent();
                        setProgress(100, "Đã gửi file P2P thành công");
                    });
                }
                @Override public void failed(String key, Exception error) {
                    SwingUtilities.invokeLater(() -> {
                        FileCard card = fileCards.get(transferId);
                        if (card != null) card.markFailed("Gửi P2P thất bại");
                        showError("Gửi file P2P thất bại: " + error.getMessage());
                    });
                }
            });
            client.send(Message.of(MessageType.FILE_OFFER).put("receiver", receiver).put("fileName", file.getFileName().toString())
                    .put("fileSize", size).put("sha256", sha256).put("transferId", transferId).put("accessToken", accessToken));
            setProgress(0, "Đang chờ người nhận...");
        } catch (Exception e) { SwingUtilities.invokeLater(() -> showError("Không thể chuẩn bị file P2P: " + e.getMessage())); }
    }

    private void offerReceived(Message message) {
        String transferId = message.string("transferId");
        String sender = message.string("sender");
        String fileName = safeFileName(message.string("fileName"));
        long fileSize = message.longValue("fileSize", -1);
        if (transferId == null || sender == null || fileSize < 0) { showError("Đề nghị file không hợp lệ."); return; }
        List<String> hosts = strings(message.get("peerHosts"));
        int port = (int) message.longValue("peerPort", 0);
        String accessToken = message.string("accessToken");
        if (hosts.isEmpty() || port <= 0 || accessToken == null) { showError("Peer gửi file không có địa chỉ P2P hợp lệ."); return; }
        PendingFileOffer offer = new PendingFileOffer(transferId, sender, fileName, fileSize, message.string("sha256"), hosts, port, accessToken);
        pendingOffers.put(transferId, offer);
        FileCard card = new FileCard(fileName, formatBytes(fileSize) + " · từ " + sender);
        card.showDownload(() -> downloadOffer(offer, card));
        fileCards.put(transferId, card);
        addFileCardToConversation(sender, card, false);
    }

    private void downloadOffer(PendingFileOffer offer, FileCard card) {
        if (!pendingOffers.remove(offer.transferId(), offer)) return;
        card.markPreparing();
        transfers.submit(() -> {
            Path destination = null;
            try {
                Path directory = Path.of("downloads");
                Files.createDirectories(directory);
                destination = uniqueDestination(directory.resolve(offer.fileName()));
                Path finalDestination = destination;
                client.send(Message.of(MessageType.FILE_ACCEPT).put("receiver", offer.sender()).put("transferId", offer.transferId()));
                SwingUtilities.invokeLater(() -> {
                    card.markDownloading(0);
                    setProgress(0, "Đang nhận file P2P...");
                });
                PeerFileClient.download(offer.peerHosts(), offer.peerPort(), offer.accessToken(), username,
                        destination, offer.fileSize(), offer.sha256(), received -> {
                            int value = percent(received, offer.fileSize());
                            setProgress(value, "Đang nhận file P2P...");
                            SwingUtilities.invokeLater(() -> card.markDownloading(value));
                        });
                client.send(Message.of(MessageType.FILE_COMPLETE).put("receiver", offer.sender()).put("transferId", offer.transferId()));
                SwingUtilities.invokeLater(() -> {
                    card.markDownloaded(finalDestination, () -> openFile(finalDestination));
                    setProgress(100, "Nhận file P2P thành công");
                });
            } catch (Exception e) {
                if (destination != null) try { Files.deleteIfExists(destination); } catch (IOException ignored) { }
                try { client.send(Message.of(MessageType.FILE_FAILED).put("receiver", offer.sender()).put("transferId", offer.transferId()).put("message", e.getMessage())); } catch (IOException ignored) { }
                SwingUtilities.invokeLater(() -> {
                    card.markFailed("Tải P2P thất bại");
                    showError("Nhận file P2P thất bại: " + e.getMessage());
                });
            }
        });
    }

    public void handle(Message message) {
        if (message.getType() == null) return;
        switch (message.getType()) {
            case USER_LIST_RESPONSE -> { onlineUsers.clear(); Object value = message.get("users"); if (value instanceof java.util.Collection<?> list) for (Object item : list) { String user = String.valueOf(item); if (!user.equalsIgnoreCase(username)) onlineUsers.add(user); } filterUsers(""); }
            case USER_STATUS_CHANGED -> { append("[Hệ thống] " + message.string("username") + (message.bool("online", false) ? " đã online." : " đã offline.")); refreshUsers(); }
            case PRIVATE_MESSAGE -> {
                if (message.string("content") != null && message.string("sender") != null)
                    appendToConversation(message.string("sender"), message.string("sender") + ": " + message.string("content"));
            }
            case FILE_OFFER -> { if (message.string("sender") != null) offerReceived(message); }
            case FILE_ACCEPT -> { if (message.string("from") != null) { FileCard card = fileCards.get(message.string("transferId")); if (card != null) card.markSending(0); setProgress(0, "Đang chờ kết nối P2P..."); } }
            case FILE_REJECT -> { if (message.string("from") != null) { String transferId = message.string("transferId"); OutgoingTransfer transfer = outgoing.remove(transferId); if (transfer != null) peerFileServer.unregister(transfer.accessToken()); FileCard card = fileCards.get(transferId); if (card != null) card.markFailed("Người nhận đã từ chối"); setProgress(0, "File bị từ chối"); } }
            case FILE_COMPLETE -> { if (message.string("from") != null) { String transferId = message.string("transferId"); OutgoingTransfer transfer = outgoing.remove(transferId); if (transfer != null) peerFileServer.unregister(transfer.accessToken()); FileCard card = fileCards.get(transferId); if (card != null) card.markSent(); setProgress(100, "Đã gửi file P2P thành công"); } }
            case FILE_FAILED -> { String transferId = message.string("transferId"); OutgoingTransfer transfer = outgoing.remove(transferId); if (transfer != null) peerFileServer.unregister(transfer.accessToken()); FileCard card = fileCards.get(transferId); if (card != null) card.markFailed("Truyền P2P thất bại"); showError("Truyền file P2P thất bại: " + message.string("message")); }
            case ROOM_LIST_RESPONSE -> { if (roomDialog != null) roomDialog.handle(message); }
            case ROOM_UPDATED -> { requestRooms(); }
            case GROUP_MESSAGE -> groupMessageReceived(message);
            case FILE_SEARCH_RESPONSE, FILE_SHARE, FILE_UNSHARE -> { if (fileSearchDialog != null) fileSearchDialog.handle(message); }
            case ERROR -> showError(message.string("message"));
            default -> { }
        }
    }

    private void selectRoom(RoomInfo room) {
        if (!room.joined()) { showError("Bạn cần tham gia phòng trước khi mở chat."); return; }
        activePeer = null;
        userList.clearSelection();
        activeRoomId = room.id();
        activeRoomName = room.name();
        chatTitle.setText("# " + room.name());
        chatStatus.setText(room.memberCount() + " thành viên · Chủ phòng: " + room.owner());
        chatAvatar.setName(room.name());
        chatAvatar.setOnline(true);
        transcript.removeAll();
        for (ChatEntry entry : roomHistory.getOrDefault(room.id(), List.of())) renderLine(entry.text());
        transcript.revalidate();
        transcript.repaint();
        scrollTranscriptToBottom();
        if (roomDialog != null) roomDialog.setVisible(false);
    }

    private void groupMessageReceived(Message message) {
        if (message.string("sender") == null || message.string("content") == null) return;
        long roomId = message.longValue("roomId", -1);
        if (roomId < 0) return;
        String sender = message.string("sender");
        String line = sender.equalsIgnoreCase(username)
                ? "Bạn → " + (activeRoomName == null ? "phòng" : activeRoomName) + ": " + message.string("content")
                : sender + ": " + message.string("content");
        roomHistory.computeIfAbsent(roomId, ignored -> new ArrayList<>()).add(new ChatEntry(line, null, sender.equalsIgnoreCase(username)));
        if (activeRoomId != null && activeRoomId == roomId) renderLine(line);
    }

    private String selectedPeer() {
        String receiver = activePeer != null ? activePeer : userList.getSelectedValue();
        if (receiver == null || receiver.equals(username)) { showError("Hãy chọn một người dùng khác."); return null; }
        return receiver;
    }
    private void setProgress(int value, String text) {
        SwingUtilities.invokeLater(() -> {
            String detail = text + (value > 0 ? " (" + value + "%)" : "");
            transferToast.setMessage(detail, value >= 100);
            positionTransferToast();
            transferToast.setVisible(true);
            transferToast.repaint();
            toastTimer.restart();
        });
    }
    private void addFileCard(FileCard card, boolean mine) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(Theme.padding(5, 0, 5, 0));
        Theme.Avatar avatar = new Theme.Avatar(mine ? username : activePeer, 30, true);
        JPanel group = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 0));
        group.setOpaque(false);
        if (mine) { group.add(card); group.add(avatar); }
        else { group.add(avatar); group.add(card); }
        row.add(group, mine ? BorderLayout.EAST : BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        scrollTranscriptToBottom();
    }

    private void addFileCardToConversation(String peer, FileCard card, boolean mine) {
        conversationHistory.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(new ChatEntry(null, card, mine));
        if (activePeer == null) {
            userList.setSelectedValue(peer, true);
            if (activePeer == null) selectPeer(peer);
            return;
        }
        if (peer.equals(activePeer)) addFileCard(card, mine);
    }

    private void openFile(Path path) {
        if (!Files.exists(path)) { showError("Không tìm thấy file: " + path.toAbsolutePath()); return; }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showError("Máy này không hỗ trợ mở file tự động. File nằm tại: " + path.toAbsolutePath());
            return;
        }
        try { Desktop.getDesktop().open(path.toFile()); }
        catch (IOException | SecurityException e) { showError("Không thể mở file: " + e.getMessage()); }
    }

    private void scrollTranscriptToBottom() {
        SwingUtilities.invokeLater(() -> {
            Container parent = transcript.getParent();
            if (parent instanceof JViewport viewport && viewport.getParent() instanceof JScrollPane scroll)
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        });
    }
    private static int percent(long current, long total) { return total <= 0 ? 100 : (int) Math.min(100, current * 100 / total); }
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }
    private static String safeFileName(String name) { if (name == null || name.isBlank()) return "received-file"; return Path.of(name).getFileName().toString(); }
    private static Path uniqueDestination(Path path) { if (!Files.exists(path)) return path; String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : ""; int index = 1; Path candidate; do { candidate = path.resolveSibling(base + " (" + index++ + ")" + ext); } while (Files.exists(candidate)); return candidate; }
    private static String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024 * 1024) return (bytes / 1024) + " KB"; return String.format("%.1f MB", bytes / 1024d / 1024d); }
    private static String sha256(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read); } StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format("%02x", value)); return result.toString(); }
    private void filterUsers(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        users.clear();
        for (String user : onlineUsers) if (user.toLowerCase().contains(normalized)) users.addElement(user);
        if (activePeer != null && users.contains(activePeer)) userList.setSelectedValue(activePeer, true);
    }
    private void append(String line) {
        if (activePeer != null) appendToConversation(activePeer, line);
    }
    private void appendToConversation(String peer, String line) {
        if (peer == null || peer.equals(username)) return;
        conversationHistory.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(new ChatEntry(line, null, false));
        if (activePeer == null) {
            userList.setSelectedValue(peer, true);
            if (activePeer == null) selectPeer(peer);
            return;
        }
        if (!peer.equals(activePeer)) return;
        renderLine(line);
    }
    private void renderLine(String line) {
        boolean mine = line.startsWith("Bạn →");
        boolean system = line.startsWith("[Hệ thống]") || line.startsWith("[File]");
        String visible = line.replaceFirst("^Bạn → [^:]+: ", "");
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(Theme.padding(5, 0, 5, 0));
        MessageBubble bubble;
        if (system) {
            bubble = new MessageBubble(visible, Theme.FILE_CARD, Theme.MUTED);
            row.add(bubble, BorderLayout.CENTER);
        } else if (mine) {
            bubble = new MessageBubble(visible, Theme.PRIMARY, Color.WHITE);
            JPanel group = messageGroup(bubble, new Theme.Avatar(username, 30, true), true);
            row.add(group, BorderLayout.EAST);
        } else {
            bubble = new MessageBubble(visible, Theme.FILE_CARD, Theme.TEXT);
            String author = activePeer;
            if (author == null) {
                int separator = visible.indexOf(':');
                author = separator > 0 ? visible.substring(0, separator) : "Phòng";
            }
            JPanel group = messageGroup(bubble, new Theme.Avatar(author, 30, true), false);
            row.add(group, BorderLayout.WEST);
        }
        transcript.add(row); transcript.revalidate(); transcript.repaint();
        scrollTranscriptToBottom();
    }

    private JPanel messageGroup(MessageBubble bubble, Theme.Avatar avatar, boolean mine) {
        JPanel group = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 0));
        group.setOpaque(false);
        if (mine) { group.add(bubble); group.add(avatar); }
        else { group.add(avatar); group.add(bubble); }
        return group;
    }
    private static String escapeHtml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private void showError(String text) { JOptionPane.showMessageDialog(this, text == null ? "Có lỗi xảy ra." : text, "Lỗi", JOptionPane.ERROR_MESSAGE); }
    @Override public void dispose() { toastTimer.stop(); transfers.shutdownNow(); if (roomDialog != null) roomDialog.dispose(); if (fileSearchDialog != null) fileSearchDialog.dispose(); peerFileServer.close(); client.close(); super.dispose(); }
    private record ChatEntry(String text, FileCard fileCard, boolean mine) { }
    private record OutgoingTransfer(String receiver, Path file, long size, String sha256, String accessToken) { }
    private record PendingFileOffer(String transferId, String sender, String fileName, long fileSize, String sha256, List<String> peerHosts, int peerPort, String accessToken) { }
    private record RoomInfo(long id, String name, String owner, boolean joined, int memberCount) {
        @Override public String toString() { return (joined ? "✓ " : "  ") + name + "  ·  " + memberCount + " thành viên  ·  " + owner; }
    }
    private record SharedFileInfo(String owner, String fileName, long fileSize, String sha256, String shareToken, List<String> peerHosts, int peerPort) {
        @Override public String toString() { return fileName + "  ·  " + formatBytes(fileSize) + "  ·  từ " + owner; }
    }

    private final class RoomDialog extends JDialog {
        private final DefaultListModel<RoomInfo> model = new DefaultListModel<>();
        private final JList<RoomInfo> list = new JList<>(model);
        private final JLabel status = new JLabel("Chọn phòng để tham gia hoặc mở chat.");
        private final JButton join = new JButton("Tham gia");
        private final JButton leave = new JButton("Rời phòng");
        private final JButton open = new JButton("Mở chat");

        private RoomDialog() {
            super(ChatFrame.this, "Phòng chat", false);
            setSize(620, 470);
            setLocationRelativeTo(ChatFrame.this);
            JPanel root = new JPanel(new BorderLayout(12, 12));
            root.setBorder(Theme.padding(18, 18, 18, 18));
            JLabel title = new JLabel("Phòng chat");
            title.setFont(Theme.font(Font.BOLD, 22));
            title.setForeground(Theme.TEXT);
            root.add(title, BorderLayout.NORTH);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setFixedCellHeight(42);
            list.addListSelectionListener(event -> updateButtons());
            root.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JButton create = Theme.primaryButton("Tạo phòng");
            create.addActionListener(event -> createRoom());
            JButton refresh = Theme.ghostButton("Làm mới");
            refresh.addActionListener(event -> requestRooms());
            join.addActionListener(event -> changeMembership(true));
            leave.addActionListener(event -> changeMembership(false));
            open.addActionListener(event -> { RoomInfo room = list.getSelectedValue(); if (room != null) selectRoom(room); });
            controls.add(create); controls.add(refresh); controls.add(join); controls.add(leave); controls.add(open);
            JPanel south = new JPanel(new BorderLayout(0, 8));
            south.add(controls, BorderLayout.NORTH);
            status.setForeground(Theme.MUTED);
            south.add(status, BorderLayout.SOUTH);
            root.add(south, BorderLayout.SOUTH);
            setContentPane(root);
            updateButtons();
        }

        private void createRoom() {
            String name = JOptionPane.showInputDialog(this, "Tên phòng:", "Tạo phòng", JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            try { client.send(Message.of(MessageType.CREATE_ROOM).put("name", name.trim())); }
            catch (IOException error) { showError(error.getMessage()); }
        }

        private void changeMembership(boolean joining) {
            RoomInfo room = list.getSelectedValue();
            if (room == null) return;
            try {
                client.send(Message.of(joining ? MessageType.JOIN_ROOM : MessageType.LEAVE_ROOM).put("roomId", room.id()));
            } catch (IOException error) { showError(error.getMessage()); }
        }

        private void updateButtons() {
            RoomInfo room = list.getSelectedValue();
            join.setEnabled(room != null && !room.joined());
            leave.setEnabled(room != null && room.joined());
            open.setEnabled(room != null && room.joined());
        }

        private void handle(Message message) {
            if (message.getType() != MessageType.ROOM_LIST_RESPONSE) return;
            Long selectedId = list.getSelectedValue() == null ? null : list.getSelectedValue().id();
            model.clear();
            Object value = message.get("rooms");
            if (value instanceof Collection<?> rooms) for (Object item : rooms) {
                if (!(item instanceof Map<?, ?> map)) continue;
                RoomInfo room = new RoomInfo(number(map.get("roomId")), String.valueOf(map.get("name")),
                        String.valueOf(map.get("owner")), Boolean.parseBoolean(String.valueOf(map.get("joined"))),
                        (int) number(map.get("memberCount")));
                model.addElement(room);
                if (activeRoomId != null && activeRoomId == room.id()) {
                    if (room.joined()) {
                        activeRoomName = room.name();
                        chatStatus.setText(room.memberCount() + " thành viên · Chủ phòng: " + room.owner());
                    } else {
                        activeRoomId = null;
                        activeRoomName = null;
                        chatTitle.setText("Chọn một cuộc trò chuyện");
                        chatStatus.setText("Chọn người dùng hoặc phòng chat");
                        chatAvatar.setName("?");
                        chatAvatar.setOnline(false);
                        transcript.removeAll();
                        transcript.revalidate();
                        transcript.repaint();
                    }
                }
            }
            if (selectedId != null) for (int index = 0; index < model.size(); index++) if (model.get(index).id() == selectedId) { list.setSelectedIndex(index); break; }
            updateButtons();
            status.setText(model.isEmpty() ? "Chưa có phòng nào." : "Đã tải " + model.size() + " phòng.");
        }
    }

    private final class FileSearchDialog extends JDialog {
        private final JTextField query = new JTextField();
        private final DefaultListModel<SharedFileInfo> model = new DefaultListModel<>();
        private final JList<SharedFileInfo> list = new JList<>(model);
        private final JLabel status = new JLabel("Tìm file được chia sẻ trong mạng.");
        private final JProgressBar progress = new JProgressBar(0, 100);
        private final Map<String, Path> localShares = new ConcurrentHashMap<>();

        private FileSearchDialog() {
            super(ChatFrame.this, "File directory P2P", false);
            setSize(760, 500);
            setLocationRelativeTo(ChatFrame.this);
            JPanel root = new JPanel(new BorderLayout(12, 12));
            root.setBorder(Theme.padding(18, 18, 18, 18));
            JLabel title = new JLabel("File directory P2P");
            title.setFont(Theme.font(Font.BOLD, 22));
            title.setForeground(Theme.TEXT);
            list.setFixedCellHeight(42);
            root.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel searchBar = new JPanel(new BorderLayout(8, 0));
            query.putClientProperty("JTextField.placeholderText", "Tên file cần tìm...");
            query.addActionListener(event -> search());
            JButton search = Theme.primaryButton("Tìm");
            search.addActionListener(event -> search());
            searchBar.add(query, BorderLayout.CENTER); searchBar.add(search, BorderLayout.EAST);
            JButton share = Theme.ghostButton("Chia sẻ file của tôi");
            share.addActionListener(event -> shareFile());
            JPanel north = new JPanel(new BorderLayout(0, 8));
            north.add(searchBar, BorderLayout.NORTH); north.add(share, BorderLayout.SOUTH);
            JPanel header = new JPanel(new BorderLayout(0, 12));
            header.add(title, BorderLayout.NORTH);
            header.add(north, BorderLayout.SOUTH);
            root.add(header, BorderLayout.NORTH);
            JPanel actions = new JPanel(new BorderLayout(8, 0));
            JButton download = Theme.primaryButton("Tải trực tiếp P2P");
            download.addActionListener(event -> downloadSelected());
            JButton unshare = Theme.ghostButton("Bỏ chia sẻ");
            unshare.addActionListener(event -> unshareSelected());
            progress.setVisible(false);
            actions.add(download, BorderLayout.WEST); actions.add(unshare, BorderLayout.CENTER); actions.add(progress, BorderLayout.EAST);
            JPanel south = new JPanel(new BorderLayout(0, 8));
            south.add(actions, BorderLayout.NORTH); status.setForeground(Theme.MUTED); south.add(status, BorderLayout.SOUTH);
            root.add(south, BorderLayout.SOUTH);
            setContentPane(root);
        }

        private void search() {
            try { client.send(Message.of(MessageType.FILE_SEARCH_REQUEST).put("query", query.getText().trim())); }
            catch (IOException error) { showError(error.getMessage()); }
        }

        private void shareFile() {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path file = chooser.getSelectedFile().toPath();
            transfers.submit(() -> {
                try {
                    long size = Files.size(file);
                    String sha256 = sha256(file);
                    String shareToken = UUID.randomUUID().toString();
                    peerFileServer.register(shareToken, file, false, null);
                    localShares.put(shareToken, file);
                    client.send(Message.of(MessageType.FILE_SHARE).put("fileName", file.getFileName().toString())
                            .put("fileSize", size).put("sha256", sha256).put("shareToken", shareToken));
                    SwingUtilities.invokeLater(() -> status.setText("Đã chia sẻ: " + file.getFileName()));
                } catch (Exception error) { SwingUtilities.invokeLater(() -> showError("Không thể chia sẻ file: " + error.getMessage())); }
            });
        }

        private void downloadSelected() {
            SharedFileInfo file = list.getSelectedValue();
            if (file == null || file.owner().equalsIgnoreCase(username)) return;
            transfers.submit(() -> {
                Path destination = null;
                try {
                    Path directory = Path.of("downloads");
                    Files.createDirectories(directory);
                    destination = uniqueDestination(directory.resolve(safeFileName(file.fileName())));
                    Path finalDestination = destination;
                    SwingUtilities.invokeLater(() -> { progress.setVisible(true); progress.setValue(0); status.setText("Đang tải trực tiếp từ " + file.owner()); });
                    PeerFileClient.download(file.peerHosts(), file.peerPort(), file.shareToken(), username, destination,
                            file.fileSize(), file.sha256(), received -> SwingUtilities.invokeLater(() -> progress.setValue(percent(received, file.fileSize()))));
                    SwingUtilities.invokeLater(() -> { progress.setVisible(false); status.setText("Đã tải P2P: " + finalDestination.toAbsolutePath()); });
                } catch (Exception error) {
                    if (destination != null) try { Files.deleteIfExists(destination); } catch (IOException ignored) { }
                    SwingUtilities.invokeLater(() -> { progress.setVisible(false); showError("Tải file P2P thất bại: " + error.getMessage()); });
                }
            });
        }

        private void unshareSelected() {
            SharedFileInfo file = list.getSelectedValue();
            if (file == null || !file.owner().equalsIgnoreCase(username)) return;
            try { client.send(Message.of(MessageType.FILE_UNSHARE).put("shareToken", file.shareToken())); }
            catch (IOException error) { showError(error.getMessage()); }
        }

        private void handle(Message message) {
            if (message.getType() == MessageType.FILE_SEARCH_RESPONSE) {
                model.clear();
                Object value = message.get("files");
                if (value instanceof Collection<?> files) for (Object item : files) {
                    if (!(item instanceof Map<?, ?> map)) continue;
                    model.addElement(new SharedFileInfo(String.valueOf(map.get("owner")), String.valueOf(map.get("fileName")),
                            number(map.get("fileSize")), String.valueOf(map.get("sha256")), String.valueOf(map.get("shareToken")),
                            strings(map.get("peerHosts")), (int) number(map.get("peerPort"))));
                }
                status.setText(model.size() + " file đang được chia sẻ bởi các peer online.");
            } else if (message.getType() == MessageType.FILE_SHARE) {
                if (!message.bool("success", false)) {
                    String token = message.string("shareToken");
                    if (token != null) { peerFileServer.unregister(token); localShares.remove(token); }
                    showError(message.string("message"));
                } else search();
            } else if (message.getType() == MessageType.FILE_UNSHARE) {
                if (message.bool("success", false)) {
                    String token = message.string("shareToken");
                    if (token != null) { peerFileServer.unregister(token); localShares.remove(token); }
                    search();
                }
            }
        }

        @Override public void dispose() {
            for (String token : localShares.keySet()) peerFileServer.unregister(token);
            localShares.clear();
            super.dispose();
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    private static final class FileCard extends JPanel {
        private final JLabel status = new JLabel();
        private final JPanel actionSlot = new JPanel(new BorderLayout());
        private final JProgressBar progress = new JProgressBar(0, 100);

        private FileCard(String fileName, String details) {
            setOpaque(false);
            setLayout(new BorderLayout(12, 9));
            setBorder(Theme.padding(13, 14, 12, 14));
            setPreferredSize(new Dimension(430, 96));

            JLabel icon = new JLabel(new AppIcon(AppIcon.Type.FILE, Theme.PRIMARY, 27));
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            icon.setPreferredSize(new Dimension(42, 42));
            add(icon, BorderLayout.WEST);

            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(ellipsize(fileName, 34));
            name.setToolTipText(fileName);
            name.setFont(Theme.font(Font.BOLD, 14));
            name.setForeground(Theme.TEXT);
            status.setText(details);
            status.setFont(Theme.font(Font.PLAIN, 12));
            status.setForeground(Theme.MUTED);
            copy.add(name);
            copy.add(Box.createVerticalStrut(5));
            copy.add(status);
            add(copy, BorderLayout.CENTER);

            actionSlot.setOpaque(false);
            add(actionSlot, BorderLayout.EAST);
            progress.setVisible(false);
            progress.setPreferredSize(new Dimension(0, 5));
            progress.setStringPainted(false);
            progress.putClientProperty("JProgressBar.largeHeight", false);
            add(progress, BorderLayout.SOUTH);
        }

        private void showDownload(Runnable action) {
            JButton button = Theme.actionButton("Tải xuống", new AppIcon(AppIcon.Type.DOWNLOAD, Theme.PRIMARY, 16), false);
            button.setToolTipText("Tải file về thư mục downloads");
            button.addActionListener(e -> action.run());
            setAction(button);
        }

        private void markPreparing() {
            status.setText("Đang chuẩn bị tải xuống...");
            Component action = actionSlot.getComponentCount() == 0 ? null : actionSlot.getComponent(0);
            if (action != null) action.setEnabled(false);
        }

        private void markDownloading(int value) {
            progress.setVisible(true);
            progress.setValue(value);
            status.setText("Đang tải xuống · " + value + "%");
            actionSlot.removeAll();
            actionSlot.revalidate();
            actionSlot.repaint();
            revalidate();
        }

        private void markSending(int value) {
            progress.setVisible(true);
            progress.setValue(value);
            status.setText("Đang gửi · " + value + "%");
            revalidate();
        }

        private void markSent() {
            progress.setVisible(false);
            status.setText("Đã gửi");
            status.setForeground(Theme.ONLINE);
            revalidate();
            repaint();
        }

        private void markDownloaded(Path path, Runnable openAction) {
            progress.setValue(100);
            progress.setVisible(false);
            status.setText("Đã tải xuống · " + path.toAbsolutePath());
            status.setToolTipText(path.toAbsolutePath().toString());
            JButton button = Theme.actionButton("Mở file", new AppIcon(AppIcon.Type.OPEN_FILE, Theme.PRIMARY, 16), false);
            button.setToolTipText("Mở file đã tải xuống");
            button.addActionListener(e -> openAction.run());
            setAction(button);
        }

        private void markFailed(String message) {
            progress.setVisible(false);
            status.setText(message);
            status.setForeground(Theme.DANGER);
            actionSlot.removeAll();
            actionSlot.revalidate();
            actionSlot.repaint();
            revalidate();
            repaint();
        }

        private void setAction(JButton button) {
            actionSlot.removeAll();
            actionSlot.add(button, BorderLayout.CENTER);
            actionSlot.revalidate();
            actionSlot.repaint();
        }

        private static String ellipsize(String value, int max) {
            if (value == null || value.length() <= max) return value;
            int dot = value.lastIndexOf('.');
            String extension = dot > 0 && value.length() - dot <= 8 ? value.substring(dot) : "";
            int keep = Math.max(8, max - extension.length() - 1);
            return value.substring(0, keep) + "…" + extension;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.FILE_CARD);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setColor(Theme.BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
    private static final class UserCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final Theme.Avatar avatar = new Theme.Avatar("?", 42, true);
        private final JLabel name = new JLabel();
        private final JLabel status = new JLabel("Đang hoạt động");
        private final String currentUser;
        private UserCellRenderer(String currentUser) { this.currentUser = currentUser; setLayout(new BorderLayout(10, 0)); setBorder(Theme.padding(7, 9, 7, 9)); JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS)); name.setFont(Theme.font(Font.BOLD, 14)); status.setFont(Theme.font(Font.PLAIN, 11)); status.setForeground(Theme.MUTED); text.add(name); text.add(Box.createVerticalStrut(3)); text.add(status); add(avatar, BorderLayout.WEST); add(text, BorderLayout.CENTER); }
        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value, int index, boolean selected, boolean focus) { name.setText(value.equals(currentUser) ? value + " (bạn)" : value); avatar.setName(value); avatar.setOnline(true); setBackground(selected ? Theme.PRIMARY_SOFT : Theme.SURFACE); setOpaque(true); name.setForeground(selected ? Color.WHITE : Theme.TEXT); status.setForeground(selected ? new Color(205, 208, 255) : Theme.MUTED); return this; }
    }
    private static final class ToastNotification extends JPanel {
        private final JLabel message = new JLabel();
        private boolean success;

        private ToastNotification() {
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(Theme.padding(11, 16, 12, 16));
            JLabel icon = new JLabel(new AppIcon(AppIcon.Type.FILE, Color.WHITE, 19));
            add(icon, BorderLayout.WEST);
            message.setFont(Theme.font(Font.BOLD, 13));
            message.setForeground(Color.WHITE);
            add(message, BorderLayout.CENTER);
        }

        private void setMessage(String text, boolean success) {
            this.success = success;
            message.setText(text);
            revalidate();
        }

        @Override public Dimension getPreferredSize() {
            Dimension content = super.getPreferredSize();
            return new Dimension(Math.max(210, content.width), Math.max(46, content.height));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(15, 23, 42, 48));
            g.fillRoundRect(3, 4, getWidth() - 6, getHeight() - 5, 22, 22);
            g.setColor(success ? new Color(22, 101, 52) : new Color(30, 41, 59));
            g.fillRoundRect(1, 1, getWidth() - 4, getHeight() - 5, 22, 22);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
    private static final class MessageBubble extends JPanel {
        private final Color fill;
        private MessageBubble(String text, Color fill, Color foreground) { this.fill = fill; setOpaque(false); setBorder(Theme.padding(10, 14, 10, 14)); JLabel label = new JLabel("<html><body style='width:330px'>" + escapeHtml(text) + "</body></html>"); label.setFont(Theme.font(Font.PLAIN, 14)); label.setForeground(foreground); add(label); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20); g.dispose(); super.paintComponent(graphics); }
    }
    private static final class MessageListPanel extends JPanel implements Scrollable {
        private MessageListPanel() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
