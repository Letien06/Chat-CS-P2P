package com.p2p.client.network;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.function.LongConsumer;

public final class PeerFileClient {
    private PeerFileClient() { }

    public static void download(Collection<String> hosts, int port, String fileKey, String requester,
                                Path destination, long expectedSize, String expectedSha256,
                                LongConsumer progress) throws Exception {
        Exception last = null;
        for (String host : new LinkedHashSet<>(hosts)) {
            try {
                downloadFrom(host, port, fileKey, requester, destination, expectedSize, expectedSha256, progress);
                return;
            } catch (Exception error) {
                last = error;
                Files.deleteIfExists(destination);
            }
        }
        throw last == null ? new IOException("No peer address was provided") : last;
    }

    private static void downloadFrom(String host, int port, String fileKey, String requester,
                                     Path destination, long expectedSize, String expectedSha256,
                                     LongConsumer progress) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(30_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            FrameCodec.write(output, Message.of(MessageType.PEER_FILE_REQUEST).put("fileKey", fileKey).put("requester", requester));
            Message response = FrameCodec.read(input);
            if (response == null || !response.bool("success", false)) throw new IOException(response == null ? "Peer closed the connection" : response.string("message"));
            long actualExpectedSize = response.longValue("fileSize", expectedSize);
            if (actualExpectedSize != expectedSize) throw new IOException("Peer file size does not match offer");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Files.createDirectories(destination.toAbsolutePath().getParent());
            try (BufferedOutputStream file = new BufferedOutputStream(Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))) {
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                while (received < expectedSize) {
                    int wanted = (int) Math.min(buffer.length, expectedSize - received);
                    int read = input.read(buffer, 0, wanted);
                    if (read < 0) throw new IOException("Peer closed before file completed");
                    file.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    received += read;
                    if (progress != null) progress.accept(received);
                }
                file.flush();
                String actualSha256 = hex(digest.digest());
                if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actualSha256)) {
                    throw new IOException("SHA-256 does not match");
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
