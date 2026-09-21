package com.p2p.server;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;
import com.p2p.server.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalConnectionIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void twoLocalClientsCanJoinChatUseRoomsAndDiscoverPeerFiles() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        Path configFile = tempDir.resolve("server.properties");
        String databasePath = tempDir.resolve("chat.db").toString().replace('\\', '/');
        Files.writeString(configFile, "server.port=" + port + System.lineSeparator()
                + "database.path=" + databasePath + System.lineSeparator()
                + "max.clients=10" + System.lineSeparator());

        ServerMain server = new ServerMain(ServerConfig.load(configFile));
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable failure) {
                serverFailure.set(failure);
            }
        }, "local-integration-server");
        serverThread.start();

        try {
            waitUntilListening(port, serverFailure);
            try (TestPeer alice = new TestPeer(port); TestPeer bob = new TestPeer(port)) {
                alice.join("alice");
                bob.join("bob");
                try (TestPeer duplicateAlice = new TestPeer(port)) {
                    duplicateAlice.joinExpectingFailure("alice");
                }

                Message chat = Message.of(MessageType.PRIVATE_MESSAGE)
                        .put("receiver", "bob")
                        .put("content", "Xin chào từ máy A");
                alice.send(chat);

                Message received = bob.readUntil(message ->
                        message.getType() == MessageType.PRIVATE_MESSAGE
                                && "alice".equals(message.string("sender")));
                assertEquals("Xin chào từ máy A", received.string("content"));

                Message delivered = alice.readUntil(message ->
                        message.getType() == MessageType.PRIVATE_MESSAGE
                                && chat.getRequestId().equals(message.getRequestId()));
                assertTrue(delivered.bool("success", false));

                Message createRoom = Message.of(MessageType.CREATE_ROOM).put("name", "Nhóm mạng");
                alice.send(createRoom);
                Message created = alice.readUntil(TestPeer.responseTo(createRoom, MessageType.ROOM_UPDATED));
                long roomId = created.longValue("roomId", -1);
                assertTrue(roomId > 0);

                Message joinRoom = Message.of(MessageType.JOIN_ROOM).put("roomId", roomId);
                bob.send(joinRoom);
                bob.readUntil(TestPeer.responseTo(joinRoom, MessageType.ROOM_UPDATED));

                alice.send(Message.of(MessageType.GROUP_MESSAGE).put("roomId", roomId).put("content", "Chào cả phòng"));
                Message groupMessage = bob.readUntil(message -> message.getType() == MessageType.GROUP_MESSAGE
                        && "alice".equals(message.string("sender")));
                assertEquals("Chào cả phòng", groupMessage.string("content"));

                String transferId = "direct-transfer";
                alice.send(Message.of(MessageType.FILE_OFFER).put("receiver", "bob")
                        .put("transferId", transferId).put("fileName", "demo.bin")
                        .put("fileSize", 120_000).put("sha256", "test-hash").put("accessToken", "offer-token"));
                Message offer = bob.readUntil(message -> message.getType() == MessageType.FILE_OFFER
                        && transferId.equals(message.string("transferId")));
                assertEquals("alice", offer.string("sender"));
                assertEquals("offer-token", offer.string("accessToken"));
                assertEquals(41_001, offer.longValue("peerPort", 0));

                Message share = Message.of(MessageType.FILE_SHARE).put("fileName", "linux.iso")
                        .put("fileSize", 42_000).put("sha256", "shared-hash").put("shareToken", "share-token");
                alice.send(share);
                alice.readUntil(TestPeer.responseTo(share, MessageType.FILE_SHARE));
                Message search = Message.of(MessageType.FILE_SEARCH_REQUEST).put("query", "linux");
                bob.send(search);
                Message searchResult = bob.readUntil(TestPeer.responseTo(search, MessageType.FILE_SEARCH_RESPONSE));
                Collection<?> files = (Collection<?>) searchResult.get("files");
                assertEquals(1, files.size());
                Map<?, ?> file = (Map<?, ?>) files.iterator().next();
                assertEquals("alice", file.get("owner"));
                assertEquals("share-token", file.get("shareToken"));
            }
        } finally {
            server.close();
            serverThread.join(Duration.ofSeconds(5).toMillis());
        }

        if (serverFailure.get() != null) {
            fail("Server failed during local integration test", serverFailure.get());
        }
    }

    private static void waitUntilListening(int port, AtomicReference<Throwable> serverFailure) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (serverFailure.get() != null) fail("Server could not start", serverFailure.get());
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception ignored) {
                Thread.sleep(25);
            }
        }
        fail("Server did not listen on 127.0.0.1:" + port);
    }

    private static final class TestPeer implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        private TestPeer(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        private void join(String name) throws Exception {
            int peerPort = "alice".equals(name) ? 41_001 : 41_002;
            Message join = Message.of(MessageType.JOIN_REQUEST).put("name", name)
                    .put("peerPort", peerPort).put("peerHosts", java.util.List.of("127.0.0.1"));
            send(join);
            Message joined = readUntil(responseTo(join, MessageType.JOIN_RESPONSE));
            assertTrue(joined.bool("success", false), joined.string("message"));
        }

        private void joinExpectingFailure(String name) throws Exception {
            Message join = Message.of(MessageType.JOIN_REQUEST).put("name", name)
                    .put("peerPort", 41_003).put("peerHosts", java.util.List.of("127.0.0.1"));
            send(join);
            Message response = readUntil(responseTo(join, MessageType.JOIN_RESPONSE));
            assertFalse(response.bool("success", true));
        }

        private static Predicate<Message> responseTo(Message request, MessageType type) {
            return message -> message.getType() == type
                    && request.getRequestId().equals(message.getRequestId());
        }

        private void send(Message message) throws Exception {
            FrameCodec.write(output, message);
        }

        private Message readUntil(Predicate<Message> predicate) throws Exception {
            while (true) {
                Message message = FrameCodec.read(input);
                if (message == null) fail("Server closed the connection unexpectedly");
                if (predicate.test(message)) return message;
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
