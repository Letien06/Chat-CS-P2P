package com.p2p.client.network;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ChatClient implements AutoCloseable {
    private volatile Consumer<Message> messageListener;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-client-reader"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public ChatClient(Consumer<Message> listener) { this.messageListener = Objects.requireNonNull(listener); }
    public void setMessageListener(Consumer<Message> listener) { this.messageListener = Objects.requireNonNull(listener); }

    public void connect(String host, int port) throws IOException {
        if (!running.compareAndSet(false, true)) throw new IOException("Client is already connected");
        try {
            socket = new Socket(); socket.connect(new InetSocketAddress(host, port), 5000);
            input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream());
            reader.submit(this::readLoop);
        } catch (IOException e) { running.set(false); close(); throw e; }
    }

    public synchronized void send(Message message) throws IOException {
        if (!running.get() || output == null) throw new IOException("Client is not connected");
        FrameCodec.write(output, message);
    }
    public boolean isConnected() { return running.get(); }

    private void readLoop() {
        try {
            Message message;
            while (running.get() && (message = FrameCodec.read(input)) != null) messageListener.accept(message);
        } catch (IOException e) {
            if (running.get()) messageListener.accept(Message.of(MessageType.ERROR).put("message", "Mất kết nối tới server: " + e.getMessage()));
        } finally { running.set(false); }
    }

    @Override public void close() {
        running.set(false);
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        reader.shutdownNow();
    }
}
