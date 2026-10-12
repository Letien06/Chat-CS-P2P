package com.p2p.server.ops;
import com.p2p.common.social.Json;
import com.p2p.server.social.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
class BackupToolTest {
    @TempDir Path dir;
    Path data;
    Map<String,Object> fixture;
    @BeforeEach void seed()throws Exception {
        data=dir.resolve("data");
        fixture=DemoSeedTool.seed(data.resolve("social-network.db"),data.resolve("media"),"Disposable-Demo-Password-123");
    }
    @Test void backupRestoreRoundTripPreservesPolicyAndExpiresUnbackedTransfers()throws Exception {
        long owner=((Number)((Map<?,?>)fixture.get("userIds")).get("demo_alice")).longValue();
        String pending;
        try(SocialDatabase db=new SocialDatabase(data.resolve("social-network.db"));MediaService media=new MediaService(db,new SocialService(db),data.resolve("media"),1024*1024,10*1024*1024)) {
            pending=(String)media.execute(owner,"MEDIA_UPLOAD_INIT",Map.of("purpose","CHAT_FILE","fileName","unfinished.txt","size",4,"mimeType","text/plain","sha256","0".repeat(64))).get("mediaId");
        }
        Path backup=BackupTool.backup(data,dir.resolve("backup"));
        BackupTool.Manifest manifest=BackupTool.verify(backup);
        assertEquals(3,manifest.objects().size());
        assertFalse(Files.exists(backup.resolve("media/uploads")));
        Path restored=BackupTool.restore(backup,dir.resolve("restored"));
        try(SocialDatabase db=new SocialDatabase(restored.resolve("social-network.db"))) {
            SocialService s=new SocialService(db);
            assertEquals("ADMIN",s.login(Map.of("username","demo_admin","password","Disposable-Demo-Password-123")).get("role"));
            assertEquals("Demo Alice",s.login(Map.of("username","demo_alice","password","Disposable-Demo-Password-123")).get("displayName"));
            assertEquals(6L,count(db,"users"));
            assertEquals(4L,count(db,"posts"));
            assertEquals(3L,count(db,"messages"));
            assertTrue(s.canAccessMedia(owner,(String)fixture.get("postImageMediaId")));
            assertEquals("EXPIRED",db.read(c-> {
                try(var statement=c.prepareStatement("SELECT state FROM media WHERE id=?")) {
                    statement.setString(1,pending);try(var r=statement.executeQuery()) {
                        assertTrue(r.next());return r.getString(1);
                    }
                }
            }
            ));
            assertEquals(0L,(long)db.read(c-> {
                try(var statement=c.createStatement();var r=statement.executeQuery("SELECT count(*) FROM file_transfers WHERE state IN('UPLOADING','DOWNLOADING','READY')")) {
                    r.next();return r.getLong(1);
                }
            }
            ));
        }
        for(BackupTool.ObjectEntry object:manifest.objects())assertArrayEquals(Files.readAllBytes(backup.resolve(object.path())),Files.readAllBytes(restored.resolve(object.path())));
        assertTrue(Files.isDirectory(restored.resolve("media/uploads")));
    }
    static long count(SocialDatabase db,String table)throws Exception {
        return db.read(c-> {
            try(var statement=c.createStatement();var r=statement.executeQuery("SELECT count(*) FROM "+table)) {
                r.next();return r.getLong(1);
            }
        }
        );
    }
    @Test void applicationLockRefusesLiveBackupAndSeed()throws Exception {
        try(ApplicationLock lock=ApplicationLock.acquire(data)) {
            assertThrows(IOException.class,()->BackupTool.backup(data,dir.resolve("backup")));
            assertThrows(IOException.class,()->DemoSeedTool.seed(data.resolve("social-network.db"),data.resolve("media"),"Disposable-Demo-Password-123"));
        }
        assertFalse(Files.exists(dir.resolve("backup")));
        assertNotNull(BackupTool.backup(data,dir.resolve("backup")));
    }
    @Test void corruptManifestAndMediaAreRejectedBeforeRestorePublishes()throws Exception {
        Path backup=BackupTool.backup(data,dir.resolve("backup"));
        Map<String,Object> manifest=Json.MAPPER.readValue(backup.resolve("manifest.json").toFile(),Map.class);
        String original=Files.readString(backup.resolve("manifest.json"));
        manifest.put("databaseSha256","0".repeat(64));
        Json.MAPPER.writeValue(backup.resolve("manifest.json").toFile(),manifest);
        assertThrows(IOException.class,()->BackupTool.restore(backup,dir.resolve("restore-bad-manifest")));
        assertFalse(Files.exists(dir.resolve("restore-bad-manifest")));
        Files.writeString(backup.resolve("manifest.json"),original);
        BackupTool.ObjectEntry object=BackupTool.verify(backup).objects().get(0);
        Files.write(backup.resolve(object.path()),new byte[] {
            1,2,3
        }
        );
        assertThrows(IOException.class,()->BackupTool.restore(backup,dir.resolve("restore-bad-object")));
        assertFalse(Files.exists(dir.resolve("restore-bad-object")));
    }
    @Test void inventoryOmissionsAndPathTraversalAreRejected()throws Exception {
        Path backup=BackupTool.backup(data,dir.resolve("backup"));
        Map<String,Object> manifest=Json.MAPPER.readValue(backup.resolve("manifest.json").toFile(),Map.class);
        List<Map<String,Object>> objects=(List<Map<String,Object>>)manifest.get("objects");
        Map<String,Object> removed=objects.remove(0);
        Json.MAPPER.writeValue(backup.resolve("manifest.json").toFile(),manifest);
        assertThrows(IOException.class,()->BackupTool.verify(backup));
        objects.add(removed);
        removed.put("path","../../outside");
        Json.MAPPER.writeValue(backup.resolve("manifest.json").toFile(),manifest);
        assertThrows(IOException.class,()->BackupTool.verify(backup));
    }
    @Test void destinationIsNeverOverwrittenAndSeedIsExplicitlyOneTime()throws Exception {
        Path existing=dir.resolve("existing");
        Files.createDirectory(existing);
        Files.writeString(existing.resolve("marker"),"keep me");
        assertThrows(FileAlreadyExistsException.class,()->BackupTool.backup(data,existing));
        assertEquals("keep me",Files.readString(existing.resolve("marker")));
        assertThrows(IOException.class,()->BackupTool.backup(data,data.resolve("nested-backup")));
        assertThrows(IllegalStateException.class,()->DemoSeedTool.seed(data.resolve("social-network.db"),data.resolve("media"),"Disposable-Demo-Password-123"));
        try(SocialDatabase db=new SocialDatabase(data.resolve("social-network.db"))) {
            assertEquals(6L,count(db,"users"));
        }
    }
}
