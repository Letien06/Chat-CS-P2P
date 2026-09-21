package com.p2p.server;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;
import com.p2p.server.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalConnectionIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void twoLocalClientsCanJoinChatAndTransferFile() throws Exception {
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

                byte[] fileBytes = new byte[120_000];
                for (int i = 0; i < fileBytes.length; i++) fileBytes[i] = (byte) (i * 31);
                String transferId = UUID.randomUUID().toString();
                alice.send(Message.of(MessageType.FILE_OFFER).put("receiver", "bob")
                        .put("transferId", transferId).put("fileName", "demo.bin")
                        .put("fileSize", fileBytes.length).put("sha256", "test-hash"));
                Message offer = bob.readUntil(message -> message.getType() == MessageType.FILE_OFFER
                        && transferId.equals(message.string("transferId")));
                assertEquals("alice", offer.string("sender"));

                bob.send(Message.of(MessageType.FILE_ACCEPT).put("receiver", "alice")
                        .put("transferId", transferId));
                alice.readUntil(message -> message.getType() == MessageType.FILE_ACCEPT
                        && "bob".equals(message.string("from"))
                        && transferId.equals(message.string("transferId")));

                int chunkSize = 48 * 1024;
                for (int offset = 0, sequence = 0; offset < fileBytes.length; offset += chunkSize, sequence++) {
                    int length = Math.min(chunkSize, fileBytes.length - offset);
                    byte[] chunk = java.util.Arrays.copyOfRange(fileBytes, offset, offset + length);
                    alice.send(Message.of(MessageType.FILE_CHUNK).put("receiver", "bob")
                            .put("transferId", transferId).put("sequence", sequence)
                            .put("data", Base64.getEncoder().encodeToString(chunk)));
                }
                alice.send(Message.of(MessageType.FILE_COMPLETE).put("receiver", "bob")
                        .put("transferId", transferId).put("fileSize", fileBytes.length));

                ByteArrayOutputStream receivedFile = new ByteArrayOutputStream();
                while (true) {
                    Message event = bob.readUntil(message -> transferId.equals(message.string("transferId"))
                            && (message.getType() == MessageType.FILE_CHUNK
                            || message.getType() == MessageType.FILE_COMPLETE));
                    if (event.getType() == MessageType.FILE_COMPLETE) break;
                    receivedFile.write(Base64.getDecoder().decode(event.string("data")));
                }
                assertArrayEquals(fileBytes, receivedFile.toByteArray());
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
            Message join = Message.of(MessageType.JOIN_REQUEST).put("name", name);
            send(join);
            Message joined = readUntil(responseTo(join, MessageType.JOIN_RESPONSE));
            assertTrue(joined.bool("success", false), joined.string("message"));
        }

        private void joinExpectingFailure(String name) throws Exception {
            Message join = Message.of(MessageType.JOIN_REQUEST).put("name", name);
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
