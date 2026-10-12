package com.p2p.server.ops;
import com.p2p.common.social.Json;
import com.p2p.server.social.MediaService;
import com.p2p.server.social.SocialDatabase;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
/** Offline SQLite/media snapshots. Every destination is new; no running database is copied. */
public final class BackupTool {
    public record ObjectEntry(String mediaId,String path,long size,String sha256,String state) {
    }
    public record Manifest(int formatVersion,long createdAt,String databasePath,String databaseSha256,long databaseBytes,int schemaVersion,String schemaChecksum,List<ObjectEntry> objects) {
    }
    private BackupTool() {
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!Set.of("--backup","--restore").contains(args[0]))throw new IllegalArgumentException("Usage: BackupTool --backup dataRoot NEW_backupDirectory | --restore backupDirectory NEW_dataRoot");
        Path result=args[0].equals("--backup")?backup(Path.of(args[1]),Path.of(args[2])):restore(Path.of(args[1]),Path.of(args[2]));
        System.out.println("Completed "+args[0]+": "+result);
    }
    public static Path backup(Path dataRoot,Path destination)throws Exception {
        Path root=dataRoot.toRealPath(),database=safe(root,"social-network.db");
        requireFile(database);
        Path target=newTarget(destination,root),stage=null;
        try(ApplicationLock ignored=ApplicationLock.acquire(root)) {
            List<ObjectEntry> entries=new ArrayList<>();
            int version;
            String schema;
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database)) {
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    if(r.next()&&r.getInt(1)!=0)throw new IOException("Database checkpoint busy; another process is using the database");
                }
                validateDatabase(c);
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version,checksum FROM schema_migrations ORDER BY version DESC LIMIT 1")) {
                    if(!r.next())throw new IOException("Missing database schema version");
                    version=r.getInt(1);
                    schema=r.getString(2);
                }
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,storage_key,actual_size,sha256,state FROM media WHERE state IN('READY','ATTACHED') ORDER BY id")) {
                    while(r.next()) {
                        String id=r.getString(1);
                        try {
                            UUID.fromString(id);
                        }
                        catch(IllegalArgumentException e) {
                            throw new IOException("Invalid stored media id",e);
                        }
                        String relative="media/"+r.getString(2);
                        if(!relative.equals("media/objects/"+id))throw new IOException("Media storage key is outside canonical objects");
                        Path source=safe(root,relative);
                        requireFile(source);
                        long size=r.getLong(3);
                        String hash=r.getString(4);
                        if(Files.size(source)!=size||!MediaService.checksum(source).equals(hash))throw new IOException("Stored media integrity failed: "+id);
                        entries.add(new ObjectEntry(id,relative,size,hash,r.getString(5)));
                    }
                }
            }
            stage=Files.createTempDirectory(target.getParent(),".social-backup-");
            copy(database,stage.resolve("social-network.db"));
            for(ObjectEntry e:entries)copy(safe(root,e.path()),stage.resolve(e.path()));
            Manifest manifest=new Manifest(1,System.currentTimeMillis(),"social-network.db",MediaService.checksum(stage.resolve("social-network.db")),Files.size(stage.resolve("social-network.db")),version,schema,List.copyOf(entries));
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(stage.resolve("manifest.json").toFile(),manifest);
            force(stage.resolve("manifest.json"));
            verify(stage);
            // Same-directory rename publishes the complete snapshot; no replacement option is supplied.
            Files.move(stage,target);
            stage=null;
            return target;
        }
        finally {
            if(stage!=null)deleteStage(stage,target.getParent());
        }
    }
    public static Path restore(Path backupRoot,Path destination)throws Exception {
        Path source=backupRoot.toRealPath();
        Manifest manifest=verify(source);
        Path target=newTarget(destination,source),stage=Files.createTempDirectory(target.getParent(),".social-backup-");
        try {
            copy(safe(source,manifest.databasePath()),stage.resolve("social-network.db"));
            for(ObjectEntry e:manifest.objects())copy(safe(source,e.path()),stage.resolve(e.path()));
            Files.createDirectories(stage.resolve("media/uploads"));
            Files.createDirectories(stage.resolve("media/objects"));
            try(ApplicationLock ignored=ApplicationLock.acquire(stage);SocialDatabase db=new SocialDatabase(stage.resolve("social-network.db"))) {
                db.write(c-> {
                    try(Statement s=c.createStatement()) {
                        s.executeUpdate("UPDATE media SET state='EXPIRED' WHERE state IN('UPLOADING','VERIFYING','RETIRED','FAILED','CANCELLED')"); s.executeUpdate("UPDATE file_transfers SET state='EXPIRED',failure_code='RESTORE_REQUIRES_NEW_TRANSFER' WHERE state NOT IN('VERIFIED','CANCELLED','FAILED','EXPIRED','REPLACED')"); s.executeUpdate("DELETE FROM idempotency_records WHERE operation LIKE 'MEDIA_%'"); s.executeUpdate("UPDATE outbox_events SET dispatched_at=NULL");
                    }
                    return null;
                }
                );
                db.read(c-> {
                    validateDatabase(c);return null;
                }
                );
            }
            Files.move(stage,target);
            stage=null;
            return target;
        }
        finally {
            if(stage!=null)deleteStage(stage,target.getParent());
        }
    }
    public static Manifest verify(Path directory)throws Exception {
        Path root=directory.toRealPath();
        Path manifestFile=safe(root,"manifest.json");
        requireFile(manifestFile);
        if(Files.size(manifestFile)>8*1024*1024)throw new IOException("Manifest exceeds size limit");
        Manifest m=Json.MAPPER.readValue(manifestFile.toFile(),Manifest.class);
        if(m==null||m.databaseBytes()<1||m.databaseSha256()==null||!m.databaseSha256().matches("[a-f0-9]{64}")||m.schemaVersion()<1||m.schemaChecksum()==null||m.formatVersion()!=1||!"social-network.db".equals(m.databasePath())||m.objects()==null||m.objects().size()>100000)throw new IOException("Unsupported or malformed backup manifest");
        Path database=safe(root,m.databasePath());
        requireFile(database);
        if(Files.size(database)!=m.databaseBytes()||!MediaService.checksum(database).equals(m.databaseSha256()))throw new IOException("Backup database checksum mismatch");
        Set<String> seen=new HashSet<>();
        for(ObjectEntry e:m.objects()) {
            if(e==null||e.mediaId()==null||!seen.add(e.mediaId())||!e.path().equals("media/objects/"+UUID.fromString(e.mediaId()))||!Set.of("READY","ATTACHED").contains(e.state())||e.size()<1)throw new IOException("Invalid object manifest");
            Path object=safe(root,e.path());
            requireFile(object);
            if(Files.size(object)!=e.size()||!MediaService.checksum(object).equals(e.sha256()))throw new IOException("Backup media checksum mismatch: "+e.mediaId());
        }
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database)) {
            validateDatabase(c);
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version,checksum FROM schema_migrations ORDER BY version DESC LIMIT 1")) {
                if(!r.next()||r.getInt(1)!=m.schemaVersion()||!Objects.equals(r.getString(2),m.schemaChecksum()))throw new IOException("Backup schema manifest mismatch");
            }
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,storage_key,actual_size,sha256,state FROM media WHERE state IN('READY','ATTACHED')")) {
                Set<String> actual=new HashSet<>();
                Map<String,ObjectEntry> inventory=new HashMap<>();
                for(ObjectEntry entry:m.objects())inventory.put(entry.mediaId(),entry);
                while(r.next()) {
                    String id=r.getString(1);
                    actual.add(id);
                    ObjectEntry entry=inventory.get(id);
                    if(entry==null||!entry.path().equals("media/"+r.getString(2))||entry.size()!=r.getLong(3)||!entry.sha256().equals(r.getString(4))||!entry.state().equals(r.getString(5)))throw new IOException("Media manifest disagrees with database");
                }
                if(!actual.equals(seen))throw new IOException("Backup media inventory is incomplete");
            }
        }
        return m;
    }
    static void validateDatabase(Connection c)throws Exception {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")) {
            if(!r.next()||!"ok".equalsIgnoreCase(r.getString(1)))throw new IOException("SQLite integrity check failed");
        }
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) {
            if(r.next())throw new IOException("SQLite foreign key check failed");
        }
    }
    static Path safe(Path root,String relative)throws IOException {
        Path candidate=root.resolve(relative).normalize();
        if(Path.of(relative).isAbsolute()||!candidate.startsWith(root)||candidate.equals(root))throw new IOException("Unsafe backup path");
        Path at=root;
        for(Path segment:root.relativize(candidate)) {
            at=at.resolve(segment);
            if(Files.isSymbolicLink(at))throw new IOException("Symlinks are not accepted in backup paths");
        }
        if(Files.exists(candidate)&&!candidate.toRealPath().startsWith(root))throw new IOException("Backup path escaped root");
        return candidate;
    }
    static void requireFile(Path p)throws IOException {
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Required regular file is missing: "+p.getFileName());
    }
    static Path newTarget(Path destination,Path source)throws IOException {
        Path target=destination.toAbsolutePath().normalize();
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());
        if(target.getParent()==null)throw new IOException("Destination needs a parent directory");
        Files.createDirectories(target.getParent());
        Path parent=target.getParent().toRealPath();
        target=parent.resolve(target.getFileName());
        if(target.startsWith(source)||source.startsWith(target))throw new IOException("Source and destination must be separate directory trees");
        return target;
    }
    static void copy(Path source,Path destination)throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source,destination);
        force(destination);
    }
    static void force(Path path)throws IOException {
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
    static void deleteStage(Path stage,Path parent)throws IOException {
        if(!stage.toAbsolutePath().normalize().getParent().equals(parent)||!stage.getFileName().toString().startsWith(".social-backup-"))throw new IOException("Refusing cleanup outside owned staging directory");
        Files.walkFileTree(stage,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path directory,IOException error)throws IOException {
                if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;
            }
        }
        );
    }
}
