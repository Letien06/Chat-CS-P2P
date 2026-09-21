package com.p2p.server;

import com.p2p.server.config.ServerConfig;
import com.p2p.server.database.Database;
import com.p2p.server.session.SessionManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ServerMain implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());
    private final ServerConfig config;
    private final Database database;
    private final SessionManager sessions = new SessionManager();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;

    public ServerMain(ServerConfig config) throws Exception {
        this.config = config;
        this.database = new Database(config.databasePath());
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port());
        running.set(true);
        LOG.info(() -> "P2P Chat server listening on port " + config.port());
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (sessions.usernames().size() >= config.maxClients()) { socket.close(); continue; }
                socket.setKeepAlive(true);
                clients.submit(new ClientHandler(socket, database, sessions, config));
            } catch (IOException e) {
                if (running.get()) LOG.log(Level.WARNING, "Could not accept client", e);
            }
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        try { database.close(); } catch (Exception e) { LOG.log(Level.WARNING, "Could not close database", e); }
    }

    public static void main(String[] args) throws Exception {
        configureLogging();
        Path configPath = Path.of(args.length == 0 ? "config/server.properties" : args[0]);
        ServerConfig config = ServerConfig.load(configPath);
        ServerMain server = new ServerMain(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
        server.start();
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        if (root.getHandlers().length == 0) root.addHandler(new ConsoleHandler());
    }
}
