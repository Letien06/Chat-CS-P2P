package com.p2p.server.social;

import com.p2p.common.social.WireMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Regression coverage at the authenticated TLS boundary, beyond service-only policy. */
class SessionPrivacyRuntimeTest {
    @TempDir Path dir;
    ProtocolRuntimeTest fixture;

    @BeforeEach void start() throws Exception {
        fixture=new ProtocolRuntimeTest();fixture.dir=dir;fixture.setup();
    }
    @AfterEach void stop() throws Exception { fixture.stop(); }

    @Test void pendingFriendRequestCannotRevealOnlinePresence() throws Exception {
        try(var a=fixture.client();var b=fixture.client()) {
            a.hello();b.hello();a.register("alice");long bob=b.register("bob");a.login("alice");b.login("bob");
            Map<String,Object> relation=a.call("FRIEND_REQUEST",Map.of("targetUserId",bob));
            for(var c:List.of(a,b)) {
                String kind=c==a?"OUTGOING":"INCOMING";
                List<?> items=(List<?>)c.call("RELATION_LIST",Map.of("kind",kind)).get("items");
                assertEquals(1,items.size());
                assertFalse(((Map<?,?>)items.get(0)).containsKey("online"));
                assertFalse(((Map<?,?>)items.get(0)).containsKey("asOf"));
            }
            assertTrue(((List<?>)a.call("PRESENCE_GET",Map.of("userIds",List.of(bob))).get("items")).isEmpty());
            b.call("FRIEND_ACCEPT",Map.of("relationId",relation.get("relationId"),"expectedVersion",relation.get("version")));
            Map<?,?> friend=(Map<?,?>)((List<?>)a.call("RELATION_LIST",Map.of("kind","FRIENDS")).get("items")).get(0);
            assertEquals(Boolean.TRUE,friend.get("online"));
        }
    }

    @Test void lateRevokeOfOldSocketPreservesNewSessionDownload() throws Exception {
        byte[] bytes=new byte[MediaService.CHUNK_BYTES*5+1];new Random(17).nextBytes(bytes);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String mediaId;Object oldConnection;
        Field users=SocialServer.class.getDeclaredField("users");users.setAccessible(true);
        try(var a=fixture.client()) {
            a.hello();long actor=a.register("alice");a.login("alice");
            oldConnection=((Map<?,?>)users.get(fixture.server)).get(actor);
            Map<String,Object> upload=a.call("MEDIA_UPLOAD_INIT",Map.of("purpose","CHAT_FILE","fileName","test.bin","size",bytes.length,"mimeType","application/octet-stream","sha256",hash));
            for(int offset=0;offset<bytes.length;offset+=MediaService.CHUNK_BYTES) {
                byte[] chunk=Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+MediaService.CHUNK_BYTES));
                a.call("MEDIA_UPLOAD_CHUNK",Map.of("transferId",upload.get("transferId"),"offset",offset,"data",Base64.getEncoder().encodeToString(chunk)));
            }
            a.call("MEDIA_UPLOAD_COMMIT",Map.of("transferId",upload.get("transferId")));
            mediaId=(String)upload.get("mediaId");a.call("AUTH_LOGOUT",Map.of());
        }
        try(var a=fixture.client()) {
            a.hello();a.login("alice");Map<String,Object> download=a.call("MEDIA_DOWNLOAD_INIT",Map.of("mediaId",mediaId));
            long offset=0;
            for(int n=0;n<4;n++)offset+=Base64.getDecoder().decode((String)a.event("MEDIA_DOWNLOAD_CHUNK").getPayload().get("data")).length;
            // Deterministically interleave the old reader/writer's finally-revoke with a new login.
            Method revoke=SocialServer.class.getDeclaredMethod("revoke",oldConnection.getClass());revoke.setAccessible(true);revoke.invoke(fixture.server,oldConnection);
            assertEquals(1,fixture.server.authenticatedCount());
            a.call("MEDIA_DOWNLOAD_ACK",Map.of("transferId",download.get("transferId"),"throughOffset",offset));
            for(int n=0;n<2;n++)offset+=Base64.getDecoder().decode((String)a.event("MEDIA_DOWNLOAD_CHUNK").getPayload().get("data")).length;
            a.call("MEDIA_DOWNLOAD_ACK",Map.of("transferId",download.get("transferId"),"throughOffset",offset));
            assertEquals(bytes.length,((Number)a.event("MEDIA_DOWNLOAD_COMPLETE").getPayload().get("size")).intValue());
            a.call("MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",download.get("transferId"),"actualSize",bytes.length,"actualSha256",hash));
        }
    }

    @Test void stalePasswordChangeEventDoesNotRevokeNewAuthenticationVersion() throws Exception {
        try(var a=fixture.client()) {
            a.hello();long actor=a.register("alice");a.login("alice");String eventId=UUID.randomUUID().toString();
            fixture.server.database().write(c->{
                try(var s=c.prepareStatement("INSERT INTO outbox_events(event_id,type,aggregate_type,aggregate_id,aggregate_version,audience_json,created_at) VALUES(?,'SESSION_REVOKED','USER',?,1,?,?)")) {
                    s.setString(1,eventId);s.setLong(2,actor);s.setString(3,"["+actor+"]");s.setLong(4,System.currentTimeMillis());s.executeUpdate();
                }return null;
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            boolean dispatched=false;
            while(System.nanoTime()<deadline) {
                dispatched=fixture.server.database().read(c->{try(var s=c.prepareStatement("SELECT dispatched_at FROM outbox_events WHERE event_id=?")){s.setString(1,eventId);try(var r=s.executeQuery()){return r.next()&&r.getObject(1)!=null;}}});
                if(dispatched)break;Thread.sleep(10);
            }
            assertTrue(dispatched);assertEquals("still-online",a.call("PING",Map.of("nonce","still-online")).get("nonce"));
            assertEquals(1,fixture.server.authenticatedCount());
        }
    }

    @Test void normalizedUsernameAttemptsShareRateLimitAcrossWhitespace() throws Exception {
        try(var a=fixture.client()) {
            a.hello();
            for(int n=0;n<5;n++)ProtocolRuntimeTest.error("INVALID_CREDENTIALS",a.attempt("AUTH_LOGIN",Map.of("username"," ".repeat(n)+"missing","password","Wrong-Password-123"),null));
            WireMessage blocked=a.attempt("AUTH_LOGIN",Map.of("username","MISSING ","password","Wrong-Password-123"),null);
            ProtocolRuntimeTest.error("RATE_LIMITED",blocked);
        }
    }

    @Test void unsupportedHelloOfferClosesAfterCorrelatedError() throws Exception {
        try(var client=fixture.client()) {
            ProtocolRuntimeTest.error("VERSION_UNSUPPORTED",client.attempt("HELLO",Map.of("supportedVersions",List.of(1)),null));
            assertNull(com.p2p.common.social.WireCodec.read(client.in));
        }
    }
}
