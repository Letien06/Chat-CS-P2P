package com.p2p.server.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(Path path) throws SQLException, IOException {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        initialize();
    }

    private void initialize() throws SQLException {
        String[] tables = {
            "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE, password_hash TEXT NOT NULL, password_salt TEXT NOT NULL, created_at TEXT NOT NULL, last_login TEXT)",
            "CREATE TABLE IF NOT EXISTS chat_rooms (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, owner_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS room_members (room_id INTEGER NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, PRIMARY KEY(room_id,user_id))",
            "CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, sender_id INTEGER NOT NULL REFERENCES users(id), receiver_id INTEGER REFERENCES users(id), room_id INTEGER REFERENCES chat_rooms(id), content TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS shared_files (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, file_name TEXT NOT NULL, file_size INTEGER NOT NULL, sha256 TEXT, share_token TEXT, peer_ip TEXT, peer_port INTEGER, created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS file_transfers (id INTEGER PRIMARY KEY AUTOINCREMENT, sender_id INTEGER, receiver_id INTEGER, file_name TEXT NOT NULL, file_size INTEGER NOT NULL, sha256 TEXT, status TEXT NOT NULL, started_at TEXT NOT NULL, completed_at TEXT)"
        };
        try (Statement statement = connection.createStatement()) {
            for (String table : tables) statement.execute(table);
        }
        ensureColumn("shared_files", "share_token", "TEXT");
    }

    private void ensureColumn(String table, String column, String type) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    public synchronized Optional<User> findUser(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,username FROM users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new User(rs.getLong(1), rs.getString(2))) : Optional.empty();
            }
        }
    }

    public synchronized User findOrCreateUser(String username) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO users(username,password_hash,password_salt,created_at,last_login) VALUES (?,?,?,?,?)")) {
            insert.setString(1, username);
            insert.setString(2, "");
            insert.setString(3, "");
            insert.setString(4, now);
            insert.setString(5, now);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE users SET last_login=? WHERE username=?")) {
            update.setString(1, now);
            update.setString(2, username);
            update.executeUpdate();
        }
        return findUser(username).orElseThrow(() -> new SQLException("Could not create user"));
    }

    public synchronized List<String> usernames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT username FROM users ORDER BY username")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    public synchronized void savePrivateMessage(long senderId, long receiverId, String content) throws SQLException {
        saveMessage(senderId, receiverId, null, content);
    }

    public synchronized void saveGroupMessage(long senderId, long roomId, String content) throws SQLException {
        saveMessage(senderId, null, roomId, content);
    }

    private void saveMessage(long sender, Long receiver, Long room, String content) throws SQLException {
        String sql = "INSERT INTO chat_messages(sender_id,receiver_id,room_id,content,created_at) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sender); if (receiver == null) ps.setNull(2, Types.INTEGER); else ps.setLong(2, receiver);
            if (room == null) ps.setNull(3, Types.INTEGER); else ps.setLong(3, room);
            ps.setString(4, content); ps.setString(5, Instant.now().toString()); ps.executeUpdate();
        }
    }

    public synchronized long createRoom(long ownerId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO chat_rooms(name,owner_id,created_at) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name); ps.setLong(2, ownerId); ps.setString(3, Instant.now().toString()); ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { keys.next(); long id = keys.getLong(1); joinRoom(id, ownerId); return id; }
        }
    }

    public synchronized void joinRoom(long roomId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO room_members(room_id,user_id) VALUES (?,?)")) { ps.setLong(1, roomId); ps.setLong(2, userId); ps.executeUpdate(); }
    }

    public synchronized void leaveRoom(long roomId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM room_members WHERE room_id=? AND user_id=?")) { ps.setLong(1, roomId); ps.setLong(2, userId); ps.executeUpdate(); }
    }

    public synchronized List<Long> roomMembers(long roomId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT user_id FROM room_members WHERE room_id=?")) {
            ps.setLong(1, roomId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
        }
        return ids;
    }

    public synchronized List<Room> rooms(long userId) throws SQLException {
        String sql = "SELECT r.id,r.name,u.username," +
                "EXISTS(SELECT 1 FROM room_members mine WHERE mine.room_id=r.id AND mine.user_id=?)," +
                "COUNT(m.user_id) FROM chat_rooms r JOIN users u ON u.id=r.owner_id " +
                "LEFT JOIN room_members m ON m.room_id=r.id GROUP BY r.id,r.name,u.username ORDER BY r.name COLLATE NOCASE";
        List<Room> rooms = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rooms.add(new Room(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5)));
            }
        }
        return rooms;
    }

    public synchronized void shareFile(long ownerId, String name, long size, String sha256, String shareToken, String ip, int port) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM shared_files WHERE owner_id=? AND file_name=?")) {
            delete.setLong(1, ownerId);
            delete.setString(2, name);
            delete.executeUpdate();
        }
        String sql = "INSERT INTO shared_files(owner_id,file_name,file_size,sha256,share_token,peer_ip,peer_port,created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, ownerId); ps.setString(2, name); ps.setLong(3, size); ps.setString(4, sha256); ps.setString(5, shareToken); ps.setString(6, ip); ps.setInt(7, port); ps.setString(8, Instant.now().toString()); ps.executeUpdate();
        }
    }

    public synchronized void unshareFile(long ownerId, String shareToken) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM shared_files WHERE owner_id=? AND share_token=?")) { ps.setLong(1, ownerId); ps.setString(2, shareToken); ps.executeUpdate(); }
    }

    public synchronized void clearSharedFiles(long ownerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM shared_files WHERE owner_id=?")) { ps.setLong(1, ownerId); ps.executeUpdate(); }
    }

    public synchronized List<SharedFile> searchFiles(String query) throws SQLException {
        List<SharedFile> files = new ArrayList<>();
        String sql = "SELECT f.owner_id,u.username,f.file_name,f.file_size,f.sha256,f.share_token " +
                "FROM shared_files f JOIN users u ON u.id=f.owner_id WHERE f.file_name LIKE ? ORDER BY f.file_name COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + query + "%");
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) files.add(new SharedFile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6))); }
        }
        return files;
    }

    @Override public void close() throws SQLException { connection.close(); }

    public record User(long id, String username) { }
    public record Room(long id, String name, String owner, boolean joined, int memberCount) { }
    public record SharedFile(long ownerId, String owner, String name, long size, String sha256, String shareToken) { }
}
