package com.p2p.client.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PeerFileTransferTest {
    @TempDir
    Path tempDir;

    @Test
    void downloadsFileDirectlyFromPeerSocket() throws Exception {
        byte[] expected = new byte[180_000];
        for (int index = 0; index < expected.length; index++) expected[index] = (byte) (index * 37);
        Path source = tempDir.resolve("source.bin");
        Path destination = tempDir.resolve("destination.bin");
        Files.write(source, expected);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
        AtomicLong received = new AtomicLong();

        try (PeerFileServer server = new PeerFileServer()) {
            server.register("secret-token", source, true, null);
            PeerFileClient.download(java.util.List.of("127.0.0.1"), server.port(), "secret-token", "bob",
                    destination, expected.length, sha256, received::set);
        }

        assertArrayEquals(expected, Files.readAllBytes(destination));
        assertEquals(expected.length, received.get());
    }
}
