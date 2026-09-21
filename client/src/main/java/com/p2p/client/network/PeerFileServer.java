package com.p2p.client.network;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PeerFileServer implements AutoCloseable {
    public static final int FIRST_PORT = 55_000;
    public static final int LAST_PORT = 55_099;
    public interface Listener {
        default void started(String key, String requester) { }
        default void progress(String key, long sent, long total) { }
        default void completed(String key) { }
        default void failed(String key, Exception error) { }
    }

    private record Entry(Path path, long size, boolean singleUse, Listener listener) { }

    private final ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "peer-file-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public PeerFileServer() throws IOException {
        ServerSocket selected = null;
        IOException failure = null;
        for (int port = FIRST_PORT; port <= LAST_PORT && selected == null; port++) {
            ServerSocket candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            try {
                candidate.bind(new InetSocketAddress(port));
                selected = candidate;
            } catch (IOException error) {
                failure = error;
                try { candidate.close(); } catch (IOException ignored) { }
            }
        }
        if (selected == null) throw new IOException("No P2P port is available in " + FIRST_PORT + "-" + LAST_PORT, failure);
        serverSocket = selected;
        Thread acceptor = new Thread(this::acceptLoop, "peer-file-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return serverSocket.getLocalPort(); }

    public static List<String> localAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) addresses.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return addresses;
    }

    public void register(String key, Path path, boolean singleUse, Listener listener) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) throw new IOException("File does not exist: " + path);
        entries.put(key, new Entry(path, Files.size(path), singleUse, listener == null ? new Listener() { } : listener));
    }

    public void unregister(String key) { if (key != null) entries.remove(key); }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                workers.submit(() -> serve(socket));
            }
            catch (IOException error) { if (running) break; }
        }
    }

    private void serve(Socket socket) {
        String key = null;
        try (socket; DataInputStream input = new DataInputStream(socket.getInputStream()); DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            Message request = FrameCodec.read(input);
            if (request == null || request.getType() != MessageType.PEER_FILE_REQUEST) return;
            key = request.string("fileKey");
            Entry entry = entries.get(key);
            if (entry == null) {
                FrameCodec.write(output, Message.of(MessageType.PEER_FILE_RESPONSE).error("File is no longer shared"));
                return;
            }
            String requester = request.string("requester");
            entry.listener().started(key, requester);
            FrameCodec.write(output, Message.of(MessageType.PEER_FILE_RESPONSE).success(true, "OK")
                    .put("fileName", entry.path().getFileName().toString()).put("fileSize", entry.size()));
            try (BufferedInputStream file = new BufferedInputStream(Files.newInputStream(entry.path()))) {
                byte[] buffer = new byte[64 * 1024];
                long sent = 0;
                int read;
                while ((read = file.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                    sent += read;
                    entry.listener().progress(key, sent, entry.size());
                }
            }
            if (entry.singleUse()) entries.remove(key, entry);
            entry.listener().completed(key);
        } catch (Exception error) {
            if (key != null && !key.isBlank()) {
                Entry entry = entries.get(key);
                if (entry != null) entry.listener().failed(key, error);
            }
        }
    }

    @Override public void close() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) { }
        entries.clear();
        workers.shutdownNow();
    }
}
