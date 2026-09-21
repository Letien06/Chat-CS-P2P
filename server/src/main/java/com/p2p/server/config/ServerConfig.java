package com.p2p.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ServerConfig {
    private final int port;
    private final Path databasePath;
    private final int maxClients;
    private final long maxFileSize;

    private ServerConfig(int port, Path databasePath, int maxClients, long maxFileSize) {
        this.port = port;
        this.databasePath = databasePath;
        this.maxClients = maxClients;
        this.maxFileSize = maxFileSize;
    }

    public static ServerConfig load(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        }
        int port = integer(p, "server.port", 5000);
        Path db = Path.of(p.getProperty("database.path", "data/p2p-chat.db"));
        int maxClients = integer(p, "max.clients", 100);
        long maxFileSize = longValue(p, "max.file.size", 2L * 1024 * 1024 * 1024);
        return new ServerConfig(port, db, maxClients, maxFileSize);
    }

    private static int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long longValue(Properties p, String key, long fallback) {
        try { return Long.parseLong(p.getProperty(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public int port() { return port; }
    public Path databasePath() { return databasePath; }
    public int maxClients() { return maxClients; }
    public long maxFileSize() { return maxFileSize; }
}
