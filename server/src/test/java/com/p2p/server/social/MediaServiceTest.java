package com.p2p.server.social;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

class MediaServiceTest {
 @TempDir Path dir;
 SocialDatabase db;SocialService social;MediaService media;long alice,bob;
 @BeforeEach void setup()throws Exception{db=new SocialDatabase(dir.resolve("social.db"));social=new SocialService(db);alice=register("alice");bob=register("bob");media=new MediaService(db,social,dir.resolve("storage"),1024*1024,2*1024*1024);}
 @AfterEach void close()throws Exception{if(media!=null)media.close();if(db!=null)db.close();}
 long register(String name)throws Exception{return ((Number)social.register(Map.of("username",name,"displayName",name,"password","Strong-Password-123")).get("userId")).longValue();}
 Map<String,Object> app(long actor,String type,Map<String,Object> p)throws Exception{return social.execute(actor,type,p,UUID.randomUUID().toString());}
 Map<String,Object> call(long actor,String type,Map<String,Object> p)throws Exception{return media.execute(actor,type,p);}
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 Map<String,Object> init(long actor,byte[] b,String purpose,String mime)throws Exception{return call(actor,"MEDIA_UPLOAD_INIT",Map.of("purpose",purpose,"fileName",purpose.equals("CHAT_FILE")?"demo.txt":"demo.png","size",b.length,"mimeType",mime,"sha256",hash(b)));}
 void chunk(long actor,String id,long offset,byte[] bytes)throws Exception{call(actor,"MEDIA_UPLOAD_CHUNK",Map.of("transferId",id,"offset",offset,"data",Base64.getEncoder().encodeToString(bytes)));}
 Map<String,Object> upload(long actor,byte[] b,String purpose,String mime)throws Exception{Map<String,Object> t=init(actor,b,purpose,mime);String transfer=(String)t.get("transferId");for(int offset=0;offset<b.length;offset+=MediaService.CHUNK_BYTES)chunk(actor,transfer,offset,Arrays.copyOfRange(b,offset,Math.min(offset+MediaService.CHUNK_BYTES,b.length)));call(actor,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",transfer));return t;}
 static void code(String expected,Executable action){assertEquals(expected,assertThrows(SocialException.class,action).code());}
 long friendConversation()throws Exception{Map<String,Object> r=app(alice,"FRIEND_REQUEST",Map.of("targetUserId",bob));app(bob,"FRIEND_ACCEPT",Map.of("relationId",r.get("relationId"),"expectedVersion",r.get("version")));return ((Number)app(alice,"CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",bob)).get("conversationId")).longValue();}
 String attachedChat(byte[] bytes)throws Exception{Map<String,Object> t=upload(alice,bytes,"CHAT_FILE","text/plain");long conversation=friendConversation();app(alice,"MESSAGE_SEND",Map.of("conversationId",conversation,"clientMessageId",UUID.randomUUID().toString(),"content","attachment","mediaId",t.get("mediaId")));return (String)t.get("mediaId");}
 byte[] png()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}

 @Test void offsetHashAndUploadOwnershipAreEnforced()throws Exception{
  byte[] bytes="hello media".getBytes(StandardCharsets.UTF_8);Map<String,Object> t=init(alice,bytes,"CHAT_FILE","text/plain");String id=(String)t.get("transferId");
  code("NOT_FOUND",()->chunk(bob,id,0,bytes));
  code("NOT_FOUND",()->call(bob,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",t.get("mediaId"))));
  code("OFFSET_MISMATCH",()->chunk(alice,id,1,bytes));
  code("OFFSET_MISMATCH",()->call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",id)));
  chunk(alice,id,0,"wrong bytes".getBytes(StandardCharsets.UTF_8));
  code("HASH_MISMATCH",()->call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",id)));
  assertEquals("FAILED",call(alice,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",t.get("mediaId"))).get("state"));
  assertFalse(Files.exists(dir.resolve("storage/objects").resolve((String)t.get("mediaId"))));
 }
 @Test void quotaReservationAndCancelReleaseAreDurable()throws Exception{
  media.close();media=new MediaService(db,social,dir.resolve("storage"),12,20);byte[] b=new byte[8];Map<String,Object> one=init(alice,b,"CHAT_FILE","application/octet-stream");
  code("QUOTA_EXCEEDED",()->init(alice,b,"CHAT_FILE","application/octet-stream"));
  Map<String,Object> two=init(bob,b,"CHAT_FILE","application/octet-stream");
  code("QUOTA_EXCEEDED",()->init(bob,b,"CHAT_FILE","application/octet-stream"));
  call(alice,"MEDIA_UPLOAD_CANCEL",Map.of("mediaId",one.get("mediaId")));
  assertNotNull(init(alice,b,"CHAT_FILE","application/octet-stream").get("transferId"));
  code("INVALID_REQUEST",()->call(alice,"MEDIA_UPLOAD_INIT",Map.of("purpose","CHAT_FILE","fileName","../escape","size",1,"sha256",hash(b))));
 }
 @Test void restartResumeUsesDurableOffsetAndReplacesOldTransfer()throws Exception{
  byte[] bytes="abcdefghij".getBytes(StandardCharsets.UTF_8);Map<String,Object> t=init(alice,bytes,"CHAT_FILE","text/plain");String old=(String)t.get("transferId"),id=(String)t.get("mediaId");chunk(alice,old,0,Arrays.copyOfRange(bytes,0,4));
  Files.write(dir.resolve("storage/uploads").resolve(id+".part"),new byte[]{99,99},StandardOpenOption.APPEND);
  media.close();db.close();db=new SocialDatabase(dir.resolve("social.db"));social=new SocialService(db);media=new MediaService(db,social,dir.resolve("storage"),1024*1024,2*1024*1024);
  code("HASH_MISMATCH",()->call(alice,"MEDIA_UPLOAD_RESUME",Map.of("mediaId",id,"size",bytes.length,"sha256","0".repeat(64))));
  Map<String,Object> resumed=call(alice,"MEDIA_UPLOAD_RESUME",Map.of("mediaId",id,"size",bytes.length,"sha256",hash(bytes)));assertEquals(4L,((Number)resumed.get("acknowledgedOffset")).longValue());assertEquals(4,Files.size(dir.resolve("storage/uploads").resolve(id+".part")));assertNotEquals(old,resumed.get("transferId"));
  code("INVALID_STATE",()->chunk(alice,old,4,Arrays.copyOfRange(bytes,4,10)));chunk(alice,(String)resumed.get("transferId"),4,Arrays.copyOfRange(bytes,4,10));assertEquals("READY",call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",resumed.get("transferId"))).get("state"));
 }
 @Test void downloadWindowNeedsAcknowledgementAndVerifiedHash()throws Exception{
  byte[] bytes=new byte[MediaService.CHUNK_BYTES*5+7];new Random(42).nextBytes(bytes);String id=attachedChat(bytes);assertTrue(social.canAccessMedia(bob,id));long stranger=register("stranger");code("NOT_FOUND",()->call(stranger,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)));
  Map<String,Object> d=call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id));String transfer=(String)d.get("transferId");code("NOT_FOUND",()->media.downloadWindow(alice,transfer));List<Map<String,Object>> first=media.downloadWindow(bob,transfer);assertEquals(4,first.size());assertTrue(media.downloadWindow(bob,transfer).isEmpty());assertTrue(media.completion(bob,transfer).isEmpty());
  code("OFFSET_MISMATCH",()->call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",transfer,"throughOffset",bytes.length)));
  call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",transfer,"throughOffset",MediaService.CHUNK_BYTES*4));List<Map<String,Object>> second=media.downloadWindow(bob,transfer);assertEquals(2,second.size());ByteArrayOutputStream received=new ByteArrayOutputStream();for(Map<String,Object> c:first)received.write(Base64.getDecoder().decode((String)c.get("data")));for(Map<String,Object> c:second)received.write(Base64.getDecoder().decode((String)c.get("data")));assertArrayEquals(bytes,received.toByteArray());
  call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",transfer,"throughOffset",bytes.length));assertFalse(media.completion(bob,transfer).isEmpty());code("HASH_MISMATCH",()->call(bob,"MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",transfer,"actualSize",bytes.length,"actualSha256","0".repeat(64))));assertEquals("VERIFIED",call(bob,"MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",transfer,"actualSize",bytes.length,"actualSha256",hash(bytes))).get("state"));
 }
 @Test void imagePolicyIsRecheckedAfterVisibilityChanges()throws Exception{
  byte[] bytes=png();Map<String,Object> t=upload(alice,bytes,"POST_IMAGE","image/png");String id=(String)t.get("mediaId");code("NOT_FOUND",()->call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)));Map<String,Object> post=app(alice,"POST_CREATE",Map.of("content","image","visibility","PUBLIC","mediaIds",List.of(id)));String transfer=(String)call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)).get("transferId");app(alice,"POST_UPDATE",Map.of("postId",post.get("postId"),"content","private image","visibility","ONLY_ME","expectedVersion",1));assertFalse(social.canAccessMedia(bob,id));code("NOT_FOUND",()->media.downloadWindow(bob,transfer));code("INVALID_STATE",()->call(alice,"MEDIA_UPLOAD_CANCEL",Map.of("mediaId",id)));
 }
 @Test void imagesMustMatchContentAndAttachmentOwner()throws Exception{
  byte[] bad="this is not an image".getBytes(StandardCharsets.UTF_8);Map<String,Object> t=init(alice,bad,"POST_IMAGE","image/png");chunk(alice,(String)t.get("transferId"),0,bad);code("INVALID_REQUEST",()->call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",t.get("transferId"))));
  Map<String,Object> good=upload(alice,png(),"POST_IMAGE","image/png");code("INVALID_STATE",()->app(bob,"POST_CREATE",Map.of("content","forged image","visibility","PUBLIC","mediaIds",List.of(good.get("mediaId")))));assertEquals("READY",call(alice,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",good.get("mediaId"))).get("state"));
 }
 @Test void interruptedFinalizeRecoversOnlyMatchingObjects()throws Exception{
  byte[] bytes=new byte[]{1,2,3};Map<String,Object> good=init(alice,bytes,"CHAT_FILE","application/octet-stream"),bad=init(bob,bytes,"CHAT_FILE","application/octet-stream");chunk(alice,(String)good.get("transferId"),0,bytes);chunk(bob,(String)bad.get("transferId"),0,bytes);
  Path objects=dir.resolve("storage/objects");Files.move(dir.resolve("storage/uploads").resolve(good.get("mediaId")+".part"),objects.resolve((String)good.get("mediaId")));Files.move(dir.resolve("storage/uploads").resolve(bad.get("mediaId")+".part"),objects.resolve((String)bad.get("mediaId")));Files.write(objects.resolve((String)bad.get("mediaId")),new byte[]{9,9,9});
  db.write(c->{try(var statement=c.prepareStatement("UPDATE media SET state='VERIFYING',actual_size=declared_size WHERE id IN(?,?)")){statement.setObject(1,good.get("mediaId"));statement.setObject(2,bad.get("mediaId"));statement.executeUpdate();}return null;});media.close();media=new MediaService(db,social,dir.resolve("storage"),1024*1024,2*1024*1024);
  assertEquals("READY",call(alice,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",good.get("mediaId"))).get("state"));assertEquals("FAILED",call(bob,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",bad.get("mediaId"))).get("state"));assertFalse(social.canAccessMedia(bob,(String)bad.get("mediaId")));
 } @Test void expiredUploadCannotFinalize()throws Exception{
  byte[] bytes=new byte[]{1,2,3};Map<String,Object> t=init(alice,bytes,"CHAT_FILE","application/octet-stream");chunk(alice,(String)t.get("transferId"),0,bytes);db.write(c->{try(var s=c.prepareStatement("UPDATE media SET expires_at=0 WHERE id=?")){s.setObject(1,t.get("mediaId"));s.executeUpdate();}try(var s=c.prepareStatement("UPDATE file_transfers SET expires_at=0 WHERE id=?")){s.setObject(1,t.get("transferId"));s.executeUpdate();}return null;});code("TRANSFER_EXPIRED",()->call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",t.get("transferId"))));
 }

 @Test void sameResumeIntentSurvivesMissingIdempotencyRecord()throws Exception{
  byte[] bytes="resume durable".getBytes(StandardCharsets.UTF_8);Map<String,Object> initial=init(alice,bytes,"CHAT_FILE","text/plain");chunk(alice,(String)initial.get("transferId"),0,Arrays.copyOf(bytes,4));
  String key=UUID.randomUUID().toString();Map<String,Object> payload=Map.of("mediaId",initial.get("mediaId"),"size",bytes.length,"sha256",hash(bytes));
  Map<String,Object> first=media.execute(alice,"MEDIA_UPLOAD_RESUME",payload,key);
  db.write(c->{SocialService.update(c,"DELETE FROM idempotency_records WHERE actor_id=? AND operation='MEDIA_UPLOAD_RESUME' AND key=?",alice,key);return null;});
  Map<String,Object> retry=media.execute(alice,"MEDIA_UPLOAD_RESUME",payload,key);
  assertEquals(first.get("transferId"),retry.get("transferId"));assertEquals(2,db.<Integer>read(c->SocialService.rows(c,"SELECT id FROM file_transfers WHERE media_id=?",initial.get("mediaId")).size()).intValue());
 }
 @Test void downloadReplayAfterDisconnectRequiresFreshIntentAndOldCloseCannotKillNewSession()throws Exception{
  byte[] bytes="download durable".getBytes(StandardCharsets.UTF_8);String id=attachedChat(bytes);String key=UUID.randomUUID().toString();Map<String,Object> payload=Map.of("mediaId",id);
  Map<String,Object> original=media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,key);String transfer=(String)original.get("transferId");assertTrue(media.bindSession(bob,transfer,"old-session"));media.disconnected(bob,"old-session");
  Map<String,Object> replay=media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,key);assertEquals(Boolean.TRUE,replay.get("requiresResume"));assertEquals("FAILED",replay.get("state"));
  Map<String,Object> current=media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,UUID.randomUUID().toString());String next=(String)current.get("transferId");assertTrue(media.bindSession(bob,next,"new-session"));media.disconnected(bob,"old-session");assertFalse(media.downloadWindow(bob,next).isEmpty());
 }
 @Test void downloadIntentGapDoesNotDuplicateAndRestartReplayIsControlled()throws Exception{
  String id=attachedChat("recover".getBytes(StandardCharsets.UTF_8));String key=UUID.randomUUID().toString();Map<String,Object> payload=Map.of("mediaId",id);Map<String,Object> first=media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,key);
  db.write(c->{SocialService.update(c,"DELETE FROM idempotency_records WHERE actor_id=? AND operation='MEDIA_DOWNLOAD_INIT' AND key=?",bob,key);return null;});
  assertEquals(first.get("transferId"),media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,key).get("transferId"));
  media.close();media=new MediaService(db,social,dir.resolve("storage"),1024*1024,2*1024*1024);Map<String,Object> replay=media.execute(bob,"MEDIA_DOWNLOAD_INIT",payload,key);assertEquals(Boolean.TRUE,replay.get("requiresResume"));assertEquals("FAILED",replay.get("state"));
 }
 @Test void suspendedActorCannotUploadOrReplayCachedMetadata()throws Exception{
  byte[] bytes="suspend".getBytes(StandardCharsets.UTF_8);String key=UUID.randomUUID().toString();Map<String,Object> payload=Map.of("purpose","CHAT_FILE","fileName","s.txt","mimeType","text/plain","size",bytes.length,"sha256",hash(bytes));Map<String,Object> init=media.execute(alice,"MEDIA_UPLOAD_INIT",payload,key);
  db.write(c->{SocialService.update(c,"UPDATE users SET status='SUSPENDED',auth_version=auth_version+1 WHERE id=?",alice);return null;});
  code("ACCOUNT_SUSPENDED",()->media.execute(alice,"MEDIA_UPLOAD_INIT",payload,key));code("ACCOUNT_SUSPENDED",()->chunk(alice,(String)init.get("transferId"),0,bytes));
  assertEquals(0L,db.<Long>read(c->SocialService.lng(SocialService.one(c,"SELECT acknowledged_offset FROM file_transfers WHERE id=?",init.get("transferId")),"acknowledgedOffset")).longValue());
 }
 @Test void thumbnailHasBoundedDimensionsChecksumAndCurrentAcl()throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();BufferedImage source=new BufferedImage(800,400,BufferedImage.TYPE_INT_RGB);ImageIO.write(source,"png",out);String id=(String)upload(alice,out.toByteArray(),"POST_IMAGE","image/png").get("mediaId");
  code("NOT_FOUND",()->call(bob,"MEDIA_THUMBNAIL_GET",Map.of("mediaId",id)));Map<String,Object> post=app(alice,"POST_CREATE",Map.of("content","thumbnail","visibility","PUBLIC","mediaIds",List.of(id)));
  Map<String,Object> thumbnail=call(bob,"MEDIA_THUMBNAIL_GET",Map.of("mediaId",id));byte[] bytes=Base64.getDecoder().decode((String)thumbnail.get("data"));assertEquals(hash(bytes),thumbnail.get("sha256"));assertEquals(bytes.length,((Number)thumbnail.get("size")).intValue());assertTrue(bytes.length<=300000);BufferedImage small=ImageIO.read(new ByteArrayInputStream(bytes));assertEquals(256,small.getWidth());assertEquals(128,small.getHeight());
  app(alice,"POST_UPDATE",Map.of("postId",post.get("postId"),"content","private","visibility","ONLY_ME","expectedVersion",1));code("NOT_FOUND",()->call(bob,"MEDIA_THUMBNAIL_GET",Map.of("mediaId",id)));
 }
 @Test void duplicateAcknowledgmentDoesNotRenewDeadlineOrReopenWindow()throws Exception{
  String id=attachedChat(new byte[MediaService.CHUNK_BYTES*5]);String transfer=(String)call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)).get("transferId");media.downloadWindow(bob,transfer);
  Map<String,Object> advanced=call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",transfer,"throughOffset",MediaService.CHUNK_BYTES));assertEquals(Boolean.TRUE,advanced.get("advanced"));Map<String,Object> duplicate=call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",transfer,"throughOffset",MediaService.CHUNK_BYTES));assertEquals(Boolean.FALSE,duplicate.get("advanced"));assertTrue(media.downloadWindow(bob,transfer).isEmpty());
 }

 @Test void idleUploadExpiresTransferButKeepsMediaForResume()throws Exception{
  byte[] bytes="idle upload".getBytes(StandardCharsets.UTF_8);Map<String,Object> original=init(alice,bytes,"CHAT_FILE","text/plain");String transfer=(String)original.get("transferId"),id=(String)original.get("mediaId");chunk(alice,transfer,0,Arrays.copyOf(bytes,4));
  db.write(c->{SocialService.update(c,"UPDATE file_transfers SET expires_at=0 WHERE id=?",transfer);return null;});media.cleanup();Map<String,Object> status=call(alice,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",id));assertEquals("UPLOADING",status.get("state"));assertEquals("EXPIRED",status.get("transferState"));assertEquals(Boolean.TRUE,status.get("requiresResume"));code("TRANSFER_EXPIRED",()->chunk(alice,transfer,4,Arrays.copyOfRange(bytes,4,bytes.length)));
  Map<String,Object> resumed=call(alice,"MEDIA_UPLOAD_RESUME",Map.of("mediaId",id,"size",bytes.length,"sha256",hash(bytes)));assertEquals(4L,((Number)resumed.get("acknowledgedOffset")).longValue());chunk(alice,(String)resumed.get("transferId"),4,Arrays.copyOfRange(bytes,4,bytes.length));assertEquals("READY",call(alice,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",resumed.get("transferId"))).get("state"));
 }
 @Test void recoveryClampsShortTempAndFailedHashReleasesPhysicalReservation()throws Exception{
  byte[] bytes="recover short".getBytes(StandardCharsets.UTF_8);Map<String,Object> initial=init(alice,bytes,"CHAT_FILE","text/plain");String id=(String)initial.get("mediaId");chunk(alice,(String)initial.get("transferId"),0,Arrays.copyOf(bytes,4));
  try(var channel=java.nio.channels.FileChannel.open(dir.resolve("storage/uploads").resolve(id+".part"),StandardOpenOption.WRITE)){channel.truncate(2);channel.force(true);}
  media.close();media=new MediaService(db,social,dir.resolve("storage"),1024*1024,2*1024*1024);assertEquals(2L,((Number)call(alice,"MEDIA_UPLOAD_STATUS",Map.of("mediaId",id)).get("acknowledgedOffset")).longValue());
  Map<String,Object> bad=init(bob,new byte[]{1,2,3},"CHAT_FILE","application/octet-stream");chunk(bob,(String)bad.get("transferId"),0,new byte[]{9,9,9});code("HASH_MISMATCH",()->call(bob,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",bad.get("transferId"))));assertFalse(Files.exists(dir.resolve("storage/uploads").resolve(bad.get("mediaId")+".part")));
 }

 @Test void lateWorkerFailurePreservesCancelledAndVerifiedTerminalState()throws Exception{
  byte[] bytes="terminal download".getBytes(StandardCharsets.UTF_8);String id=attachedChat(bytes);
  String cancelled=(String)call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)).get("transferId");call(bob,"MEDIA_DOWNLOAD_CANCEL",Map.of("transferId",cancelled,"reason","USER_CANCEL"));assertFalse(media.abortDownload(bob,cancelled,"NOT_FOUND"));assertEquals("CANCELLED",db.read(c->SocialService.one(c,"SELECT state FROM file_transfers WHERE id=?",cancelled).get("state")));
  String verified=(String)call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)).get("transferId");media.downloadWindow(bob,verified);call(bob,"MEDIA_DOWNLOAD_ACK",Map.of("transferId",verified,"throughOffset",bytes.length));media.completion(bob,verified);call(bob,"MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",verified,"actualSize",bytes.length,"actualSha256",hash(bytes)));assertFalse(media.abortDownload(bob,verified,"NOT_FOUND"));assertEquals("VERIFIED",db.read(c->SocialService.one(c,"SELECT state FROM file_transfers WHERE id=?",verified).get("state")));
  String active=(String)call(bob,"MEDIA_DOWNLOAD_INIT",Map.of("mediaId",id)).get("transferId");assertTrue(media.abortDownload(bob,active,"IO_ERROR"));assertFalse(media.abortDownload(bob,active,"IO_ERROR"));assertEquals("FAILED",db.read(c->SocialService.one(c,"SELECT state FROM file_transfers WHERE id=?",active).get("state")));
 }
}
