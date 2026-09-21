package com.p2p.server.session;

import com.p2p.common.protocol.FrameCodec;
import com.p2p.common.protocol.Message;
import com.p2p.server.database.Database;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;

public final class Session implements Closeable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final String remoteAddress;
    private final String remoteHost;
    private volatile Database.User user;
    private volatile int peerPort;
    private volatile List<String> peerHosts = List.of();
    private volatile boolean closed;

    public Session(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = new DataOutputStream(socket.getOutputStream());
        this.remoteAddress = socket.getRemoteSocketAddress().toString();
        this.remoteHost = socket.getInetAddress().getHostAddress();
    }

    public Message read() throws IOException { return FrameCodec.read(input); }

    public synchronized void send(Message message) throws IOException {
        if (closed) throw new IOException("Session is closed");
        FrameCodec.write(output, message);
    }

    public Database.User user() { return user; }
    public void join(Database.User user, int peerPort, List<String> advertisedHosts) {
        this.user = user;
        this.peerPort = peerPort;
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        hosts.add(remoteHost);
        hosts.addAll(advertisedHosts);
        this.peerHosts = List.copyOf(hosts);
    }
    public void leave() { this.user = null; this.peerPort = 0; this.peerHosts = List.of(); }
    public String remoteAddress() { return remoteAddress; }
    public int peerPort() { return peerPort; }
    public List<String> peerHosts() { return peerHosts; }
    public boolean isJoined() { return user != null; }
    public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
