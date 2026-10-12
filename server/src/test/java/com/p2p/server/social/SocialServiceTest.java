package com.p2p.server.social;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SocialServiceTest {
    @TempDir Path dir;
    SocialDatabase db;
    SocialService service;
    long a,b,c;
    @BeforeEach void setup()throws Exception {
        db=new SocialDatabase(dir.resolve("social.db"));
        service=new SocialService(db);
        a=register("alice");
        b=register("bobby");
        c=register("carol");
    }
    @AfterEach void close()throws Exception {
        db.close();
    }
    long register(String name)throws Exception {
        return ((Number)service.register(Map.of("username",name,"displayName",name,"password","password12345")).get("userId")).longValue();
    }
    Map<String,Object> call(long actor,String type,Map<String,Object> p)throws Exception {
        return service.execute(actor,type,p,UUID.randomUUID().toString());
    }
    void friends()throws Exception {
        Map<String,Object> r=call(a,"FRIEND_REQUEST",Map.of("targetUserId",b));
        call(b,"FRIEND_ACCEPT",Map.of("relationId",r.get("relationId"),"expectedVersion",1));
    }
    @Test void authAndForgedRole()throws Exception {
        assertEquals(a,((Number)service.login(Map.of("username","ALICE","password","password12345")).get("id")).longValue());
        assertCode("USERNAME_TAKEN",()->register("Alice"));
        assertCode("INVALID_CREDENTIALS",()->service.login(Map.of("username","alice","password","incorrect123")));
        assertCode("INVALID_REQUEST",()->call(a,"PROFILE_UPDATE",Map.of("displayName","A","bio","","expectedVersion",1,"role","ADMIN")));
        assertCode("ACTION_FORBIDDEN",()->call(a,"ADMIN_REPORT_LIST",Map.of()));
    }
    @Test void friendRaceAndStaleRequest()throws Exception {
        Map<String,Object> r=call(a,"FRIEND_REQUEST",Map.of("targetUserId",b));
        assertCode("INCOMING_REQUEST_EXISTS",()->call(b,"FRIEND_REQUEST",Map.of("targetUserId",a)));
        call(a,"FRIEND_CANCEL",Map.of("relationId",r.get("id"),"expectedVersion",1));
        assertCode("VERSION_CONFLICT",()->call(b,"FRIEND_ACCEPT",Map.of("relationId",r.get("id"),"expectedVersion",1)));
    }
    @Test void privacyRevokesMediaAndInteractions()throws Exception {
        friends();
        Map<String,Object> p=call(a,"POST_CREATE",Map.of("content","private","visibility","FRIENDS"));
        long id=((Number)p.get("postId")).longValue();
        call(b,"POST_GET",Map.of("postId",id));
        assertCode("NOT_FOUND",()->call(c,"POST_GET",Map.of("postId",id)));
        call(a,"POST_UPDATE",Map.of("postId",id,"content","private","visibility","ONLY_ME","expectedVersion",1));
        assertCode("NOT_FOUND",()->call(b,"COMMENT_CREATE",Map.of("postId",id,"content","test")));
    }
    @Test void durableMessageDedupAndBlockHistory()throws Exception {
        friends();
        long id=((Number)call(a,"CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",b)).get("conversationId")).longValue();
        String client=UUID.randomUUID().toString();
        Map<String,Object> payload=Map.of("conversationId",id,"clientMessageId",client,"content","offline");
        Map<String,Object> first=call(a,"MESSAGE_SEND",payload),retry=call(a,"MESSAGE_SEND",payload);
        assertEquals(first.get("messageId"),retry.get("messageId"));
        call(b,"BLOCK_SET",Map.of("targetUserId",a,"blocked",true));
        assertCode("FRIENDSHIP_REQUIRED",()->call(a,"MESSAGE_SEND",Map.of("conversationId",id,"clientMessageId",UUID.randomUUID().toString(),"content","blocked")));
        assertEquals(1,((List<?>)call(b,"MESSAGE_HISTORY",Map.of("conversationId",id)).get("items")).size());
        db.close();
        db=new SocialDatabase(dir.resolve("social.db"));
        service=new SocialService(db);
        assertEquals(1,((List<?>)call(b,"MESSAGE_HISTORY",Map.of("conversationId",id)).get("items")).size());
    }
    @Test void idempotencyPayloadConflictAndVersion()throws Exception {
        String key=UUID.randomUUID().toString();
        Map<String,Object> p=Map.of("content","hello");
        assertEquals(service.execute(a,"POST_CREATE",p,key),service.execute(a,"POST_CREATE",p,key));
        assertCode("IDEMPOTENCY_CONFLICT",()->service.execute(a,"POST_CREATE",Map.of("content","changed"),key));
    }
    @Test void groupPeriodDoesNotRestoreOldHistory()throws Exception {
        friends();
        long id=((Number)call(a,"GROUP_CREATE",Map.of("title","group")).get("conversationId")).longValue();
        long invite=((Number)call(a,"GROUP_INVITE",Map.of("conversationId",id,"targetUserId",b)).get("inviteId")).longValue();
        call(b,"GROUP_INVITE_ACCEPT",Map.of("inviteId",invite));
        call(a,"MESSAGE_SEND",Map.of("conversationId",id,"clientMessageId",UUID.randomUUID().toString(),"content","old"));
        call(a,"GROUP_KICK",Map.of("conversationId",id,"userId",b,"expectedVersion",2));
        assertCode("NOT_FOUND",()->call(b,"MESSAGE_HISTORY",Map.of("conversationId",id)));
        long second=((Number)call(a,"GROUP_INVITE",Map.of("conversationId",id,"targetUserId",b)).get("inviteId")).longValue();
        call(b,"GROUP_INVITE_ACCEPT",Map.of("inviteId",second));
        assertTrue(((List<?>)call(b,"MESSAGE_HISTORY",Map.of("conversationId",id)).get("items")).isEmpty());
    }
    @Test void attachmentBindingAtomicAndAcl()throws Exception {
        friends();
        String media=UUID.randomUUID().toString();
        db.write(conn-> {
            SocialService.update(conn,"INSERT INTO media(id,owner_id,purpose,original_name,mime_type,declared_size,actual_size,sha256,storage_key,state,created_at,expires_at) VALUES(?,?,'POST_IMAGE','a.png','image/png',10,10,?,?,'READY',?,?)",media,a,"0".repeat(64),media,System.currentTimeMillis(),System.currentTimeMillis()+60000);return null;
        }
        );
        Map<String,Object> post=call(a,"POST_CREATE",Map.of("content","","visibility","FRIENDS","mediaIds",List.of(media)));
        assertTrue(service.canAccessMedia(b,media));
        assertFalse(service.canAccessMedia(c,media));
        assertCode("INVALID_STATE",()->call(a,"POST_CREATE",Map.of("content","","mediaIds",List.of(media))));
        assertEquals(1,db.<Integer>read(conn->SocialService.rows(conn,"SELECT id FROM posts").size()).intValue());
        call(a,"POST_UPDATE",Map.of("postId",post.get("postId"),"content","","visibility","ONLY_ME","expectedVersion",1));
        assertFalse(service.canAccessMedia(b,media));
    }
    @Test void moderationAuditedAndSuspensionCheckedPerRequest()throws Exception {
        long admin=((Number)service.bootstrapAdmin("admin","Administrator","adminpassword123").get("id")).longValue();
        long report=((Number)call(a,"REPORT_CREATE",Map.of("targetType","USER","targetId",b,"reason","SPAM","description","Spam profile")).get("reportId")).longValue();
        call(admin,"ADMIN_REPORT_CLAIM",Map.of("reportId",report,"expectedVersion",1));
        call(admin,"ADMIN_REPORT_RESOLVE",Map.of("reportId",report,"expectedVersion",2,"action","SUSPEND_USER","reason","Reviewed spam"));
        assertCode("ACCOUNT_SUSPENDED",()->call(b,"FEED_GET",Map.of()));
        assertEquals(2,((List<?>)call(admin,"ADMIN_AUDIT_LIST",Map.of()).get("items")).size());
    }
    @Test void paginationCursorBoundToViewerAndScope()throws Exception {
        for(int i=0;i<3;i++)call(a,"POST_CREATE",Map.of("content","post "+i,"visibility","PUBLIC"));
        Map<String,Object> first=call(b,"FEED_GET",Map.of("limit",1));
        Object cursor=first.get("nextCursor");
        assertNotNull(cursor);
        Map<String,Object> second=call(b,"FEED_GET",Map.of("limit",1,"cursor",cursor));
        assertNotEquals(((Map<?,?>)((List<?>)first.get("items")).get(0)).get("id"),((Map<?,?>)((List<?>)second.get("items")).get(0)).get("id"));
        assertCode("INVALID_REQUEST",()->call(c,"FEED_GET",Map.of("limit",1,"cursor",cursor)));
    }
    @Test void receiptCursorDoesNotForgePeerRead()throws Exception {
        friends();
        long id=((Number)call(a,"CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",b)).get("conversationId")).longValue();
        call(a,"MESSAGE_SEND",Map.of("conversationId",id,"clientMessageId",UUID.randomUUID().toString(),"content","one"));
        call(a,"MESSAGE_READ",Map.of("conversationId",id,"throughSeq",1));
        Map<?,?> first=(Map<?,?>)((List<?>)call(a,"MESSAGE_HISTORY",Map.of("conversationId",id)).get("items")).get(0);
        assertEquals("STORED",first.get("status"));
        call(b,"MESSAGE_READ",Map.of("conversationId",id,"throughSeq",1));
        call(b,"MESSAGE_READ",Map.of("conversationId",id,"throughSeq",0));
        Map<?,?> second=(Map<?,?>)((List<?>)call(a,"MESSAGE_HISTORY",Map.of("conversationId",id)).get("items")).get(0);
        assertEquals("READ",second.get("status"));
        assertCode("INVALID_REQUEST",()->call(b,"MESSAGE_READ",Map.of("conversationId",id,"throughSeq",2)));
    }
    @Test void passwordChangeReauthAndVersion()throws Exception {
        long version=((Number)call(a,"AUTH_PASSWORD_CHANGE",Map.of("currentPassword","password12345","newPassword","changedpassword123")).get("authVersion")).longValue();
        assertEquals(2,version);
        assertCode("INVALID_CREDENTIALS",()->service.login(Map.of("username","alice","password","password12345")));
        assertEquals(a,((Number)service.login(Map.of("username","alice","password","changedpassword123")).get("id")).longValue());
        assertTrue(service.pendingEvents(100).stream().anyMatch(e->e.get("type").equals("SESSION_REVOKED")));
    }
    @Test void notificationReadDoesNotLeakRevokedPostOrDuplicateLike()throws Exception {
        friends();
        long post=((Number)call(a,"POST_CREATE",Map.of("content","friends","visibility","FRIENDS")).get("postId")).longValue();
        call(b,"LIKE_SET",Map.of("postId",post,"liked",true));
        call(b,"LIKE_SET",Map.of("postId",post,"liked",false));
        call(b,"LIKE_SET",Map.of("postId",post,"liked",true));
        assertEquals(2,((Number)call(a,"NOTIFICATION_COUNT",Map.of()).get("unreadCount")).intValue());
        Map<String,Object> list=call(a,"NOTIFICATION_LIST",Map.of());
        call(a,"NOTIFICATION_READ_THROUGH",Map.of("throughCursor",list.get("throughCursor")));
        assertEquals(0,((Number)call(a,"NOTIFICATION_COUNT",Map.of()).get("unreadCount")).intValue());
        String key=UUID.randomUUID().toString();
        service.execute(b,"LIKE_SET",Map.of("postId",post,"liked",true),key);
        call(a,"POST_UPDATE",Map.of("postId",post,"content","friends","visibility","ONLY_ME","expectedVersion",1));
        Map<String,Object> replay=service.execute(b,"LIKE_SET",Map.of("postId",post,"liked",true),key);
        assertTrue((Boolean)replay.get("resourceUnavailable"));
        assertFalse(replay.containsKey("count"));
    }
    @Test void expiredInviteNotAcceptedOrShownPending()throws Exception {
        friends();
        long group=((Number)call(a,"GROUP_CREATE",Map.of("title","group")).get("conversationId")).longValue();
        long invite=((Number)call(a,"GROUP_INVITE",Map.of("conversationId",group,"targetUserId",b)).get("inviteId")).longValue();
        db.write(conn-> {
            SocialService.update(conn,"UPDATE group_invites SET expires_at=0 WHERE id=?",invite);return null;
        }
        );
        assertCode("INVALID_STATE",()->call(b,"GROUP_INVITE_ACCEPT",Map.of("inviteId",invite)));
        Map<?,?> row=(Map<?,?>)((List<?>)call(b,"GROUP_INVITE_LIST",Map.of()).get("items")).get(0);
        assertEquals("EXPIRED",row.get("status"));
    }
    @Test void simultaneousReverseFriendRequestDoesNotAccept()throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> first=()-> {
                gate.await();
                try {
                    call(a,"FRIEND_REQUEST",Map.of("targetUserId",b));
                    return "PENDING";
                }
                catch(SocialException ex) {
                    return ex.code();
                }
            }
            ;
            java.util.concurrent.Callable<String> second=()-> {
                gate.await();
                try {
                    call(b,"FRIEND_REQUEST",Map.of("targetUserId",a));
                    return "PENDING";
                }
                catch(SocialException ex) {
                    return ex.code();
                }
            }
            ;
            var left=executor.submit(first);
            var right=executor.submit(second);
            gate.countDown();
            assertEquals(Set.of("PENDING","INCOMING_REQUEST_EXISTS"),Set.of(left.get(),right.get()));
            assertEquals(1,db.<Integer>read(conn->SocialService.rows(conn,"SELECT id FROM relationships WHERE status='PENDING'").size()).intValue());
        }
        finally {
            executor.shutdownNow();
        }
    }
    @Test void hiddenPostAuthorSeesOnlyModerationMetadata()throws Exception {
        long admin=((Number)service.bootstrapAdmin("admin","Administrator","adminpassword123").get("id")).longValue();
        long post=((Number)call(a,"POST_CREATE",Map.of("content","reported body","visibility","PUBLIC")).get("postId")).longValue();
        long report=((Number)call(b,"REPORT_CREATE",Map.of("targetType","POST","targetId",post,"reason","SPAM")).get("reportId")).longValue();
        call(admin,"ADMIN_REPORT_CLAIM",Map.of("reportId",report,"expectedVersion",1));
        call(admin,"ADMIN_REPORT_RESOLVE",Map.of("reportId",report,"expectedVersion",2,"action","HIDE_POST","reason","Spam verified"));
        Map<String,Object> own=call(a,"POST_GET",Map.of("postId",post));
        assertEquals("HIDDEN",own.get("status"));assertEquals("Spam verified",own.get("moderationReason"));assertFalse(own.containsKey("content"));assertFalse(own.containsKey("mediaIds"));
        assertCode("NOT_FOUND",()->call(b,"POST_GET",Map.of("postId",post)));
        assertCode("NOT_FOUND",()->call(a,"LIKE_SET",Map.of("postId",post,"liked",true)));
        assertEquals(1,((List<?>)call(a,"PROFILE_POSTS_GET",Map.of("authorId",a)).get("items")).size());
        assertTrue(((List<?>)call(a,"FEED_GET",Map.of()).get("items")).isEmpty());
    }
    interface Work {
        void run()throws Exception;
    }
    void assertCode(String expected,Work work) {
        SocialException e=assertThrows(SocialException.class,work::run);
        assertEquals(expected,e.code());
    }
}

