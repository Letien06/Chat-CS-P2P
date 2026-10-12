package com.p2p.server.social;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SocialDatabaseTest {
    @TempDir Path temp;
    @Test void freshSchemaEnforcesForeignKeysAndReadySize() throws Exception {
        try(var db=new SocialDatabase(temp.resolve("fresh.db"))) {
            var service=new SocialService(db);
            long id=((Number)service.register(Map.of("username","alice","displayName","Alice","password","password12345")).get("userId")).longValue();
            assertThrows(SQLException.class,()->db.write(c->{SocialService.update(c,"INSERT INTO blocks VALUES(?,?,0)",id,99999);return null;}));
            assertThrows(SQLException.class,()->db.write(c->{SocialService.update(c,"INSERT INTO media(id,owner_id,purpose,original_name,mime_type,declared_size,sha256,storage_key,state,created_at,expires_at) VALUES('bad',?,'CHAT_FILE','f','text/plain',10,?,'bad','READY',0,100)",id,"0".repeat(64));return null;}));
            assertEquals(0,db.<Integer>read(c->SocialService.rows(c,"PRAGMA foreign_key_check").size()).intValue());
        }
    }
    @Test void transactionFailureRollsBackEveryStatement() throws Exception {
        try(var db=new SocialDatabase(temp.resolve("rollback.db"))) {
            assertThrows(Exception.class,()->db.write(c->{SocialService.update(c,"CREATE TABLE transient_table(value TEXT)");SocialService.update(c,"INSERT INTO transient_table VALUES('partial')");throw new Exception("injected");}));
            assertEquals(0,db.<Integer>read(c->SocialService.rows(c,"SELECT name FROM sqlite_master WHERE name='transient_table'").size()).intValue());
            assertEquals(1,db.<Integer>read(c->SocialService.rows(c,"SELECT version FROM schema_migrations").size()).intValue());
        }
    }
    @Test void modifiedMigrationChecksumRejectedOnReopen() throws Exception {
        Path path=temp.resolve("checksum.db");try(var db=new SocialDatabase(path)){db.write(c->{SocialService.update(c,"UPDATE schema_migrations SET checksum='modified'");return null;});}
        assertThrows(SQLException.class,()->new SocialDatabase(path));
    }
    @Test void futureSchemaRejectedOnReopen() throws Exception {
        Path path=temp.resolve("future.db");try(var db=new SocialDatabase(path)){db.write(c->{SocialService.update(c,"UPDATE schema_migrations SET version=99");return null;});}
        assertThrows(SQLException.class,()->new SocialDatabase(path));
    }

    @Test void twoReadersRunInParallelAndSeeStableSnapshotDuringWrite() throws Exception {
        try(var db=new SocialDatabase(temp.resolve("snapshots.db"))) {
            db.write(c->{SocialService.update(c,"CREATE TABLE snapshot_value(value INTEGER)");SocialService.update(c,"INSERT INTO snapshot_value VALUES(1)");return null;});
            var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
            var started=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Long> read=()->db.read(c->{long before=SocialService.lng(SocialService.one(c,"SELECT value FROM snapshot_value"),"value");started.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));long after=SocialService.lng(SocialService.one(c,"SELECT value FROM snapshot_value"),"value");assertEquals(before,after);return after;});
            try {
                var first=executor.submit(read);var second=executor.submit(read);
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                db.write(c->{SocialService.update(c,"UPDATE snapshot_value SET value=2");return null;});release.countDown();
                assertEquals(1L,first.get().longValue());assertEquals(1L,second.get().longValue());
                assertEquals(2L,db.<Long>read(c->SocialService.lng(SocialService.one(c,"SELECT value FROM snapshot_value"),"value")).longValue());
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test void nestedReadUsesCallbackConnectionWithoutDeadlockAndReadCannotWrite() throws Exception {
        try(var db=new SocialDatabase(temp.resolve("nested.db"))) {
            assertTrue(db.<Boolean>read(c->db.read(nested->nested==c)).booleanValue());
            assertTrue(db.<Boolean>write(c->db.read(nested->nested==c)).booleanValue());
            assertThrows(IllegalStateException.class,()->db.read(c->db.write(writer->null)));
            assertThrows(SQLException.class,()->db.read(c->{SocialService.update(c,"CREATE TABLE forbidden(value TEXT)");return null;}));
        }
    }
    @Test void closeDrainsAcceptedWorkAndRejectsNewCallbacks() throws Exception {
        var db=new SocialDatabase(temp.resolve("closing.db"));var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.CountDownLatch(1);
        try {
            var running=executor.submit(()->db.write(c->{started.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));SocialService.update(c,"CREATE TABLE committed_before_close(value TEXT)");return true;}));
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var shutdown=executor.submit(()->{db.close();closed.countDown();return true;});
            assertFalse(closed.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();assertTrue(running.get());assertTrue(shutdown.get());
            assertThrows(SocialException.class,()->db.read(c->null));
            try(var reopened=new SocialDatabase(temp.resolve("closing.db"))){assertEquals(1,reopened.<Integer>read(c->SocialService.rows(c,"SELECT name FROM sqlite_master WHERE name='committed_before_close'").size()).intValue());}
        }finally{release.countDown();db.close();executor.shutdownNow();}
    }
}

