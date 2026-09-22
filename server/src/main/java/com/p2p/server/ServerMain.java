package com.p2p.server;

import com.p2p.server.config.ServerConfig;
import com.p2p.server.database.Database;
import com.p2p.server.session.SessionManager;

import java.io.IOException;
import java.awt.GraphicsEnvironment;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
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
    private final ServerObserver observer;
    private final SessionManager sessions = new SessionManager();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;

    public ServerMain(ServerConfig config) throws Exception {
        this(config, ServerObserver.NONE);
    }

    public ServerMain(ServerConfig config, ServerObserver observer) throws Exception {
        this.config = config;
        this.observer = observer == null ? ServerObserver.NONE : observer;
        this.database = new Database(config.databasePath());
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port());
        running.set(true);
        LOG.info(() -> "MiniChat server listening on port " + config.port());
        observer.serverStarted(config.port(), config.databasePath().toAbsolutePath().toString(), config.maxClients());
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (sessions.usernames().size() >= config.maxClients()) { socket.close(); continue; }
                socket.setKeepAlive(true);
                clients.submit(new ClientHandler(socket, database, sessions, config, observer));
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "Could not accept client", e);
                    observer.serverError("Không thể nhận client: " + e.getMessage());
                }
            }
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        try { database.close(); } catch (Exception e) { LOG.log(Level.WARNING, "Could not close database", e); }
        observer.serverStopped();
    }

    public static void main(String[] args) throws Exception {
        configureLogging();
        String configArgument = Arrays.stream(args).filter(arg -> !"--no-ui".equalsIgnoreCase(arg)).findFirst().orElse("config/server.properties");
        Path configPath = Path.of(configArgument);
        ServerConfig config = ServerConfig.load(configPath);
        boolean showUi = !GraphicsEnvironment.isHeadless() && Arrays.stream(args).noneMatch("--no-ui"::equalsIgnoreCase);
        ServerDashboard dashboard = showUi ? ServerDashboard.create() : null;
        ServerMain server = new ServerMain(config, dashboard == null ? ServerObserver.NONE : dashboard);
        if (dashboard != null) {
            dashboard.setStopAction(server::close);
            dashboard.showWindow();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
        try {
            server.start();
        } catch (IOException exception) {
            if (dashboard != null) dashboard.serverError("Không thể khởi động server: " + exception.getMessage());
            server.close();
            if (dashboard == null) throw exception;
        }
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        if (root.getHandlers().length == 0) root.addHandler(new ConsoleHandler());
    }
}
