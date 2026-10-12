package com.p2p.server.social;

import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connections are confined to a bounded writer or two snapshot reader workers. */
public final class SocialDatabase implements AutoCloseable {
    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws Exception; }
    private final Connection connection;
    private final ArrayBlockingQueue<Connection> readers=new ArrayBlockingQueue<>(2);
    private final ThreadPoolExecutor writer=pool(1,"social-db-write");
    private final ThreadPoolExecutor reader=pool(2,"social-db-read");
    private final ThreadLocal<Connection> context=new ThreadLocal<>();
    private final AtomicBoolean closing=new AtomicBoolean();
    private static ThreadPoolExecutor pool(int threads,String name) {
        return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),task->{Thread t=new Thread(task,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    private static Connection open(Path path,boolean queryOnly) throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=10000");s.execute("PRAGMA synchronous=FULL");
            if(queryOnly)s.execute("PRAGMA query_only=ON");
        } catch(SQLException failure) {c.close();throw failure;}
        return c;
    }
    public SocialDatabase(Path path)throws Exception {
        Path absolute=path.toAbsolutePath();if(absolute.getParent()!=null)Files.createDirectories(absolute.getParent());
        connection=open(absolute,false);
        try {
            try(Statement s=connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL,applied_at INTEGER NOT NULL)");
            }
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SCHEMA.getBytes(StandardCharsets.UTF_8)));
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT version,checksum FROM schema_migrations")) {
                while(r.next())if(r.getInt(1)!=1||!checksum.equals(r.getString(2)))throw new SQLException("Unsupported or modified social schema");
            }
            write(c->{
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM schema_migrations")) {
                    if(r.next()&&r.getInt(1)==0) {
                        for(String sql:SCHEMA.split(";"))if(!sql.isBlank())try(Statement migration=c.createStatement()){migration.execute(sql);}
                        try(PreparedStatement p=c.prepareStatement("INSERT INTO schema_migrations VALUES(1,?,?)")){p.setString(1,checksum);p.setLong(2,System.currentTimeMillis());p.executeUpdate();}
                    }
                }
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new SQLException("Foreign key integrity failed");}
                return null;
            });
            readers.add(open(absolute,true));readers.add(open(absolute,true));
        } catch(Exception failure) {
            writer.shutdownNow();reader.shutdownNow();for(Connection c:readers)try{c.close();}catch(SQLException closeFailure){failure.addSuppressed(closeFailure);}
            try{connection.close();}catch(SQLException closeFailure){failure.addSuppressed(closeFailure);}throw failure;
        }
    }
    private <T>T submit(ThreadPoolExecutor executor,Callable<T> work)throws Exception {
        if(closing.get())throw new SocialException("SERVER_BUSY","Database is closing");
        Future<T> future;
        try{future=executor.submit(work);}catch(RejectedExecutionException e){throw new SocialException("SERVER_BUSY","Database queue is full",Map.of("retryAfterMs",100L));}
        boolean interrupted=false;
        try {
            for(;;)try{return future.get();}catch(InterruptedException e){interrupted=true;}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception failure)throw failure;if(cause instanceof Error fatal)throw fatal;throw new RuntimeException(cause);}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    public <T>T read(SqlWork<T> work)throws Exception {
        Connection nested=context.get();if(nested!=null)return work.run(nested);
        return submit(reader,()->{Connection c=readers.take();context.set(c);try{c.setAutoCommit(false);try{return work.run(c);}finally{c.rollback();c.setAutoCommit(true);}}finally{context.remove();readers.put(c);}});
    }
    public <T>T write(SqlWork<T> work)throws Exception {
        Connection nested=context.get();if(nested!=null){if(nested!=connection)throw new IllegalStateException("Cannot write from a read snapshot");return work.run(nested);}
        return submit(writer,()->{context.set(connection);connection.setAutoCommit(false);try{T result=work.run(connection);connection.commit();return result;}catch(Exception|Error failure){connection.rollback();throw failure;}finally{connection.setAutoCommit(true);context.remove();}});
    }
    public synchronized void close()throws SQLException {
        if(context.get()!=null)throw new IllegalStateException("Close outside database callbacks");
        if(!closing.compareAndSet(false,true))return;
        writer.shutdown();reader.shutdown();boolean interrupted=false;
        try {
            for(ThreadPoolExecutor executor:List.of(writer,reader)) {
                for(;;)try{if(executor.awaitTermination(10,TimeUnit.SECONDS))break;}catch(InterruptedException e){interrupted=true;}
            }
            try(Statement s=connection.createStatement()){s.execute("PRAGMA wal_checkpoint(TRUNCATE)");}
        } finally {
            SQLException failure=null;for(Connection c:readers)try{c.close();}catch(SQLException e){failure=e;}
            try{connection.close();}catch(SQLException e){failure=e;}
            if(interrupted)Thread.currentThread().interrupt();if(failure!=null)throw failure;
        }
    }
 private static final String SCHEMA="""
 CREATE TABLE users(id INTEGER PRIMARY KEY,username TEXT NOT NULL,username_key TEXT NOT NULL UNIQUE,password_hash TEXT NOT NULL CHECK(length(password_hash)>0),salt TEXT NOT NULL,hash_algorithm TEXT NOT NULL,hash_iterations INTEGER NOT NULL,role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN('USER','ADMIN')),status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','SUSPENDED')),auth_version INTEGER NOT NULL DEFAULT 1,relationships_version INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,last_login_at INTEGER);
 CREATE TABLE profiles(user_id INTEGER PRIMARY KEY REFERENCES users(id),display_name TEXT NOT NULL,bio TEXT NOT NULL DEFAULT '',version INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL);
 CREATE TABLE relationships(id INTEGER PRIMARY KEY,low_user_id INTEGER NOT NULL REFERENCES users(id),high_user_id INTEGER NOT NULL REFERENCES users(id),requester_id INTEGER NOT NULL REFERENCES users(id),addressee_id INTEGER NOT NULL REFERENCES users(id),status TEXT NOT NULL CHECK(status IN('PENDING','ACCEPTED','REJECTED','CANCELLED','REMOVED')),version INTEGER NOT NULL DEFAULT 1,requested_at INTEGER NOT NULL,resolved_at INTEGER,CHECK(low_user_id<high_user_id),CHECK((requester_id=low_user_id AND addressee_id=high_user_id) OR(requester_id=high_user_id AND addressee_id=low_user_id)));
 CREATE UNIQUE INDEX uq_relation_active_pair ON relationships(low_user_id,high_user_id) WHERE status IN('PENDING','ACCEPTED');
 CREATE INDEX ix_relation_incoming ON relationships(addressee_id,status,requested_at DESC,id DESC);
 CREATE INDEX ix_relation_low ON relationships(low_user_id,status);
 CREATE INDEX ix_relation_high ON relationships(high_user_id,status);
 CREATE TABLE blocks(blocker_id INTEGER REFERENCES users(id),blocked_id INTEGER REFERENCES users(id),created_at INTEGER NOT NULL,PRIMARY KEY(blocker_id,blocked_id),CHECK(blocker_id<>blocked_id));
 CREATE INDEX ix_blocks_reverse ON blocks(blocked_id,blocker_id);
 CREATE TABLE posts(id INTEGER PRIMARY KEY,author_id INTEGER NOT NULL REFERENCES users(id),content TEXT NOT NULL,visibility TEXT NOT NULL CHECK(visibility IN('PUBLIC','FRIENDS','ONLY_ME')),status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DELETED','HIDDEN')),version INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER);
 CREATE INDEX ix_posts_feed ON posts(status,created_at DESC,id DESC);
 CREATE INDEX ix_posts_author ON posts(author_id,status,created_at DESC,id DESC);
 CREATE TABLE comments(id INTEGER PRIMARY KEY,post_id INTEGER NOT NULL REFERENCES posts(id),author_id INTEGER NOT NULL REFERENCES users(id),content TEXT NOT NULL CHECK(length(content)>0),status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DELETED','HIDDEN')),created_at INTEGER NOT NULL,deleted_at INTEGER);
 CREATE INDEX ix_comments_post ON comments(post_id,created_at,id);
 CREATE TABLE post_likes(post_id INTEGER REFERENCES posts(id),user_id INTEGER REFERENCES users(id),created_at INTEGER NOT NULL,PRIMARY KEY(post_id,user_id));
 CREATE INDEX ix_likes_user ON post_likes(user_id,post_id);
 CREATE TABLE conversations(id INTEGER PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN('DIRECT','GROUP')),direct_low_id INTEGER REFERENCES users(id),direct_high_id INTEGER REFERENCES users(id),title TEXT,owner_id INTEGER REFERENCES users(id),status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','CLOSED')),last_seq INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,last_activity_at INTEGER NOT NULL,version INTEGER NOT NULL DEFAULT 1,CHECK((kind='DIRECT' AND direct_low_id IS NOT NULL AND direct_high_id IS NOT NULL AND direct_low_id<direct_high_id AND owner_id IS NULL AND title IS NULL) OR(kind='GROUP' AND direct_low_id IS NULL AND direct_high_id IS NULL AND owner_id IS NOT NULL AND title IS NOT NULL)));
 CREATE INDEX ix_conversations_activity ON conversations(status,last_activity_at DESC,id DESC);
 CREATE UNIQUE INDEX uq_direct_pair ON conversations(direct_low_id,direct_high_id) WHERE kind='DIRECT';
 CREATE TABLE conversation_members(id INTEGER PRIMARY KEY,conversation_id INTEGER NOT NULL REFERENCES conversations(id),user_id INTEGER NOT NULL REFERENCES users(id),role TEXT NOT NULL CHECK(role IN('OWNER','MEMBER')),joined_at INTEGER NOT NULL,left_at INTEGER,visible_from_seq INTEGER NOT NULL,last_delivered_seq INTEGER NOT NULL DEFAULT 0,last_read_seq INTEGER NOT NULL DEFAULT 0,CHECK(last_read_seq<=last_delivered_seq));
 CREATE UNIQUE INDEX uq_active_member ON conversation_members(conversation_id,user_id) WHERE left_at IS NULL;
 CREATE UNIQUE INDEX uq_active_owner ON conversation_members(conversation_id) WHERE role='OWNER' AND left_at IS NULL;
 CREATE INDEX ix_members_user ON conversation_members(user_id,left_at,conversation_id);
 CREATE TABLE group_invites(id INTEGER PRIMARY KEY,conversation_id INTEGER REFERENCES conversations(id),inviter_id INTEGER REFERENCES users(id),invitee_id INTEGER REFERENCES users(id),status TEXT NOT NULL CHECK(status IN('PENDING','ACCEPTED','REJECTED','CANCELLED','EXPIRED')),expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL);
 CREATE UNIQUE INDEX uq_pending_invite ON group_invites(conversation_id,invitee_id) WHERE status='PENDING';
 CREATE TABLE messages(id INTEGER PRIMARY KEY,conversation_id INTEGER REFERENCES conversations(id),conversation_seq INTEGER NOT NULL,sender_id INTEGER REFERENCES users(id),client_message_id TEXT NOT NULL,payload_hash TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('TEXT','FILE')),content TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(conversation_id,conversation_seq),UNIQUE(sender_id,client_message_id));
 CREATE TABLE media(id TEXT PRIMARY KEY,owner_id INTEGER NOT NULL REFERENCES users(id),purpose TEXT NOT NULL CHECK(purpose IN('AVATAR','POST_IMAGE','CHAT_FILE')),original_name TEXT NOT NULL,mime_type TEXT NOT NULL,declared_size INTEGER NOT NULL CHECK(declared_size>0),actual_size INTEGER,sha256 TEXT NOT NULL,storage_key TEXT NOT NULL UNIQUE,state TEXT NOT NULL CHECK(state IN('UPLOADING','VERIFYING','READY','ATTACHED','RETIRED','CANCELLED','FAILED','EXPIRED')),created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,retired_at INTEGER,CHECK(state NOT IN('READY','ATTACHED') OR (actual_size IS NOT NULL AND actual_size=declared_size AND length(sha256)=64)));
 CREATE INDEX ix_media_owner ON media(owner_id,state);
 CREATE INDEX ix_media_expiry ON media(state,expires_at);
 CREATE TABLE media_bindings(media_id TEXT PRIMARY KEY REFERENCES media(id),avatar_user_id INTEGER REFERENCES profiles(user_id),post_id INTEGER REFERENCES posts(id),message_id INTEGER REFERENCES messages(id),slot INTEGER NOT NULL DEFAULT 0 CHECK(slot BETWEEN 0 AND 3),bound_at INTEGER NOT NULL,CHECK((avatar_user_id IS NOT NULL)+(post_id IS NOT NULL)+(message_id IS NOT NULL)=1));
 CREATE UNIQUE INDEX uq_avatar_binding ON media_bindings(avatar_user_id) WHERE avatar_user_id IS NOT NULL;
 CREATE UNIQUE INDEX uq_message_attachment ON media_bindings(message_id) WHERE message_id IS NOT NULL;
 CREATE UNIQUE INDEX uq_post_image_slot ON media_bindings(post_id,slot) WHERE post_id IS NOT NULL;
 CREATE TABLE file_transfers(id TEXT PRIMARY KEY,media_id TEXT REFERENCES media(id),user_id INTEGER REFERENCES users(id),direction TEXT NOT NULL CHECK(direction IN('UPLOAD','DOWNLOAD')),state TEXT NOT NULL CHECK(state IN('ACTIVE','UPLOADING','DOWNLOADING','VERIFYING','READY','REPLACED','COMPLETE','COMPLETED','VERIFIED','CANCELLED','FAILED','EXPIRED')),acknowledged_offset INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged_offset>=0),failure_code TEXT,session_id TEXT,expires_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
 CREATE INDEX ix_transfer_expiry ON file_transfers(state,expires_at);
 CREATE TABLE notifications(id INTEGER PRIMARY KEY,recipient_id INTEGER REFERENCES users(id),actor_id INTEGER REFERENCES users(id),type TEXT NOT NULL,source_key TEXT NOT NULL,post_id INTEGER REFERENCES posts(id),comment_id INTEGER REFERENCES comments(id),relation_id INTEGER REFERENCES relationships(id),conversation_id INTEGER REFERENCES conversations(id),group_invite_id INTEGER REFERENCES group_invites(id),target_user_id INTEGER REFERENCES users(id),report_id INTEGER REFERENCES reports(id),latest_seq INTEGER,is_read INTEGER NOT NULL DEFAULT 0 CHECK(is_read IN(0,1)),created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(recipient_id,source_key));
 CREATE INDEX ix_notifications_recipient ON notifications(recipient_id,is_read,created_at DESC,id DESC);
 CREATE TABLE reports(id INTEGER PRIMARY KEY,reporter_id INTEGER REFERENCES users(id),target_type TEXT NOT NULL CHECK(target_type IN('USER','POST','COMMENT')),target_id INTEGER NOT NULL,reason TEXT NOT NULL,description TEXT NOT NULL,snapshot_json TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','IN_REVIEW','RESOLVED','DISMISSED')),version INTEGER NOT NULL DEFAULT 1,assigned_admin_id INTEGER REFERENCES users(id),resolution TEXT,created_at INTEGER NOT NULL,resolved_at INTEGER);
 CREATE UNIQUE INDEX uq_active_report ON reports(reporter_id,target_type,target_id) WHERE status IN('OPEN','IN_REVIEW');
 CREATE INDEX ix_reports_queue ON reports(status,created_at DESC,id DESC);
 CREATE TABLE audit_logs(id INTEGER PRIMARY KEY,admin_id INTEGER REFERENCES users(id),report_id INTEGER REFERENCES reports(id),action TEXT NOT NULL,target_type TEXT,target_id INTEGER,reason TEXT NOT NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE idempotency_records(actor_id INTEGER REFERENCES users(id),operation TEXT NOT NULL,key TEXT NOT NULL,payload_hash TEXT NOT NULL,result_json TEXT NOT NULL,resource_id INTEGER,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(actor_id,operation,key));
 CREATE TABLE outbox_events(event_id TEXT PRIMARY KEY,type TEXT NOT NULL,aggregate_type TEXT NOT NULL,aggregate_id INTEGER NOT NULL,aggregate_version INTEGER NOT NULL,audience_json TEXT NOT NULL,created_at INTEGER NOT NULL,dispatched_at INTEGER,attempts INTEGER NOT NULL DEFAULT 0);
 CREATE INDEX ix_outbox_dispatch ON outbox_events(dispatched_at,created_at);
 """;
}








