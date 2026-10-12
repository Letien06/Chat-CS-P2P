package com.p2p.server.social;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.p2p.common.social.Json;
import com.fasterxml.jackson.core.type.TypeReference;

/** Server-owned persistent media, checkpoints and bounded transfer windows. */
public final class MediaService implements AutoCloseable {
    public static final int CHUNK_BYTES = 48 * 1024;
    private static final long IDLE=30000;
    private static final long EXPIRY = 24 * 60 * 60 * 1000L;
    private final SocialDatabase db;
    private final SocialService policy;
    private final Path root;
    private final long userQuota, serverQuota;
    private final Map<String, Download> downloads = new ConcurrentHashMap<>();
    private final Queue<Map<String,Object>> failedDownloads=new ArrayDeque<>();

    public MediaService(SocialDatabase db, SocialService policy, Path directory,
                        long userQuota, long serverQuota) throws Exception {
        this.db = db; this.policy = policy; this.userQuota = userQuota; this.serverQuota = serverQuota;
        Files.createDirectories(directory);
        root = directory.toRealPath();
        Files.createDirectories(root.resolve("uploads"));
        Files.createDirectories(root.resolve("objects"));
        recover();
    }

    public synchronized Map<String, Object> execute(long actor, String type, Map<String, Object> p) throws Exception {
        validateFields(type,p);
        db.read(c->{requireActive(c,actor);return null;});
        return switch (type) {
            case "MEDIA_UPLOAD_INIT" -> init(actor, p, null);
            case "MEDIA_UPLOAD_CHUNK" -> chunk(actor, p);
            case "MEDIA_UPLOAD_STATUS" -> status(actor, text(p, "mediaId"));
            case "MEDIA_UPLOAD_RESUME" -> resume(actor, p, null);
            case "MEDIA_UPLOAD_COMMIT" -> commit(actor, p);
            case "MEDIA_UPLOAD_CANCEL" -> cancel(actor, text(p, "mediaId"));
            case "MEDIA_DOWNLOAD_INIT" -> downloadInit(actor, p, null);
            case "MEDIA_DOWNLOAD_ACK" -> acknowledge(actor, p);
            case "MEDIA_DOWNLOAD_VERIFIED" -> verified(actor, p);
            case "MEDIA_DOWNLOAD_CANCEL" -> downloadCancel(actor, p);
            case "MEDIA_THUMBNAIL_GET" -> thumbnail(actor,text(p,"mediaId"));
            default -> throw error("UNSUPPORTED_OPERATION", "Lệnh media không được hỗ trợ");
        };
    }

    /** Media mutations retain their original result across TCP reconnects. */
    public synchronized Map<String, Object> execute(long actor, String type, Map<String, Object> p, String key) throws Exception {
        validateFields(type,p);
        db.read(c->{requireActive(c,actor);return null;});
        if (key == null || !Set.of("MEDIA_UPLOAD_INIT", "MEDIA_UPLOAD_RESUME", "MEDIA_UPLOAD_COMMIT", "MEDIA_UPLOAD_CANCEL", "MEDIA_DOWNLOAD_INIT", "MEDIA_DOWNLOAD_VERIFIED", "MEDIA_DOWNLOAD_CANCEL").contains(type)) return execute(actor, type, p);
        try { UUID.fromString(key); } catch (IllegalArgumentException e) { throw error("INVALID_REQUEST", "Idempotency key không hợp lệ"); }
        byte[] payloadBytes = Json.MAPPER.writeValueAsBytes(new TreeMap<>(p));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payloadBytes));
        Map<String, Object> old = db.read(c -> row(c, "SELECT * FROM idempotency_records WHERE actor_id=? AND operation=? AND key=? AND expires_at>?", actor, type, key, System.currentTimeMillis()));
        if (!old.isEmpty()) {
            if (!hash.equals(old.get("payload_hash"))) throw error("IDEMPOTENCY_CONFLICT", "Idempotency key đã dùng với dữ liệu khác");
            Map<String,Object> result=Json.MAPPER.readValue(String.valueOf(old.get("result_json")),new TypeReference<Map<String,Object>>(){});
            if(type.equals("MEDIA_DOWNLOAD_INIT")){String id=String.valueOf(result.get("transferId"));Map<String,Object> saved=transfer(actor,id,"DOWNLOAD");if(!policy.canAccessMedia(actor,String.valueOf(saved.get("media_id"))))throw error("NOT_FOUND","Không còn quyền tải tệp");if(!downloads.containsKey(id)||!"DOWNLOADING".equals(saved.get("state"))){result.put("requiresResume",true);result.put("acknowledgedOffset",saved.get("acknowledged_offset"));result.put("offset",saved.get("acknowledged_offset"));result.put("state",saved.get("state"));}}
            return result;
        }
        Map<String,Object> result=switch(type){case "MEDIA_UPLOAD_INIT"->init(actor,p,key);case "MEDIA_UPLOAD_RESUME"->resume(actor,p,key);case "MEDIA_DOWNLOAD_INIT"->downloadInit(actor,p,key);default->execute(actor,type,p);};
        db.write(c -> { requireActive(c,actor); update(c, "INSERT OR REPLACE INTO idempotency_records(actor_id,operation,key,payload_hash,result_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?)", actor, type, key, hash, Json.MAPPER.writeValueAsString(result), System.currentTimeMillis(), System.currentTimeMillis()+7L*24*60*60*1000); return null; });
        return result;
    }

    private static void validateFields(String type,Map<String,Object> p) throws SocialException {
        String fields=switch(type) {
            case "MEDIA_UPLOAD_INIT" -> "purpose,fileName,size,mimeType,sha256";
            case "MEDIA_UPLOAD_CHUNK" -> "transferId,offset,data";
            case "MEDIA_UPLOAD_STATUS","MEDIA_UPLOAD_CANCEL" -> "mediaId";
            case "MEDIA_UPLOAD_RESUME" -> "mediaId,size,sha256";
            case "MEDIA_UPLOAD_COMMIT" -> "transferId";
            case "MEDIA_DOWNLOAD_INIT" -> "mediaId,offset";
            case "MEDIA_DOWNLOAD_ACK" -> "transferId,throughOffset";
            case "MEDIA_DOWNLOAD_VERIFIED" -> "transferId,actualSize,actualSha256";
            case "MEDIA_DOWNLOAD_CANCEL" -> "transferId,reason";
            case "MEDIA_THUMBNAIL_GET" -> "mediaId";
            default -> throw error("UNSUPPORTED_OPERATION","Lệnh media không được hỗ trợ");
        };
        if(!Set.of(fields.split(",")).containsAll(p.keySet()))throw error("INVALID_REQUEST","Trường media không được hỗ trợ");
    }

    public synchronized boolean bindSession(long actor,String transferId,String sessionId) throws Exception {
        return db.write(c -> { requireActive(c,actor);
            Map<String,Object> t=row(c,"SELECT * FROM file_transfers WHERE id=? AND user_id=?",transferId,actor);
            if(t.isEmpty())throw error("NOT_FOUND","Không tìm thấy lượt truyền");
            if(t.get("session_id")!=null&&!sessionId.equals(t.get("session_id")))return false;
            if(!Set.of("UPLOADING","DOWNLOADING","READY").contains(String.valueOf(t.get("state"))))return false;
            update(c,"UPDATE file_transfers SET session_id=? WHERE id=?",sessionId,transferId);Download download=downloads.get(transferId);if(download!=null)download.sessionId=sessionId;return true;
        });
    }

    private Map<String, Object> init(long actor, Map<String, Object> p,String key) throws Exception {
        String purpose = text(p, "purpose"), name = filename(text(p, "fileName"));
        if (!Set.of("AVATAR", "POST_IMAGE", "CHAT_FILE").contains(purpose)) throw error("INVALID_REQUEST", "Loại tệp không hợp lệ");
        long size = number(p, "size"), maximum = purpose.equals("CHAT_FILE") ? 25L * 1024 * 1024 : 5L * 1024 * 1024;
        if (size <= 0 || size > maximum) throw error("FILE_TOO_LARGE", "Kích thước tệp không hợp lệ");
        String hash = text(p, "sha256").toLowerCase(Locale.ROOT);
        if (!hash.matches("[a-f0-9]{64}")) throw error("INVALID_REQUEST", "Cần mã SHA-256 hợp lệ");
        String mime = String.valueOf(p.getOrDefault("mimeType", "application/octet-stream"));
        if(mime.length()>100||!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))throw error("INVALID_REQUEST","MIME không hợp lệ");
        if (!purpose.equals("CHAT_FILE") && !Set.of("image/png", "image/jpeg").contains(mime)) throw error("INVALID_REQUEST", "Ảnh chỉ hỗ trợ PNG/JPEG");
        String mediaId = key==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes(("media-init:"+actor+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Map<String,Object> existing=db.read(c->row(c,"SELECT * FROM media WHERE id=?",mediaId));
        if(!existing.isEmpty()) {
            if(((Number)existing.get("owner_id")).longValue()!=actor||!purpose.equals(existing.get("purpose"))||!name.equals(existing.get("original_name"))||size!=((Number)existing.get("declared_size")).longValue()||!hash.equals(existing.get("sha256"))||!mime.equals(existing.get("mime_type")))throw error("IDEMPOTENCY_CONFLICT","Idempotency key đã dùng với media khác");
            Map<String,Object> t=db.read(c->row(c,"SELECT * FROM file_transfers WHERE media_id=? AND direction='UPLOAD' ORDER BY updated_at DESC LIMIT 1",mediaId));
            if("UPLOADING".equals(existing.get("state"))&&((Number)t.get("acknowledged_offset")).longValue()==0&&!Files.exists(part(mediaId)))Files.createFile(part(mediaId));
            return transferResult(mediaId,String.valueOf(t.get("id")),((Number)t.get("acknowledged_offset")).longValue(),((Number)existing.get("expires_at")).longValue());
        }
        String transferId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.write(c -> { requireActive(c,actor);
            long used = scalar(c, "SELECT COALESCE(sum(declared_size),0) FROM media WHERE owner_id=? AND state IN('UPLOADING','VERIFYING','READY','ATTACHED','RETIRED')", actor);
            long total = scalar(c, "SELECT COALESCE(sum(declared_size),0) FROM media WHERE state IN('UPLOADING','VERIFYING','READY','ATTACHED','RETIRED')");
            long active=scalar(c,"SELECT count(*) FROM file_transfers WHERE user_id=? AND direction='UPLOAD' AND state='UPLOADING' AND expires_at>?",actor,now);
            long all=scalar(c,"SELECT count(*) FROM file_transfers WHERE direction='UPLOAD' AND state='UPLOADING' AND expires_at>?",now);
            if (active >= 2 || all >= 20) throw error("SERVER_BUSY", "Đã đạt giới hạn tải tệp đồng thời");
            if (used + size > userQuota || total + size > serverQuota) throw error("QUOTA_EXCEEDED", "Đã đạt hạn mức lưu trữ");
            update(c, "INSERT INTO media(id,owner_id,purpose,original_name,mime_type,declared_size,sha256,storage_key,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,'UPLOADING',?,?)",
                    mediaId, actor, purpose, name, mime, size, hash, "objects/" + mediaId, now, now + EXPIRY);
            update(c, "INSERT INTO file_transfers(id,media_id,user_id,direction,state,acknowledged_offset,expires_at,updated_at) VALUES(?,?,?,'UPLOAD','UPLOADING',0,?,?)", transferId,mediaId,actor,now+IDLE,now);
            return null;
        });
        try { Files.createFile(part(mediaId)); }
        catch (IOException e) { failUpload(mediaId, "IO_ERROR"); throw error("SERVER_BUSY", "Không tạo được tệp tạm"); }
        return transferResult(mediaId,transferId,0,now+IDLE);
    }

    private Map<String, Object> chunk(long actor, Map<String, Object> p) throws Exception {
        String transferId = text(p, "transferId");
        Map<String, Object> t = transfer(actor, transferId, "UPLOAD"), m = media(String.valueOf(t.get("media_id")));
        if (((Number)t.get("expires_at")).longValue() < System.currentTimeMillis()) throw error("TRANSFER_EXPIRED", "Lượt tải đã hết hạn");
        if (!"UPLOADING".equals(t.get("state")) || !"UPLOADING".equals(m.get("state"))) throw error("INVALID_STATE", "Tệp không ở trạng thái tải lên");
        String encoded = text(p, "data");
        if (encoded.length() > 65536) throw error("FILE_TOO_LARGE", "Chunk vượt giới hạn");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException e) { throw error("INVALID_REQUEST", "Base64 không hợp lệ"); }
        if (bytes.length == 0 || bytes.length > CHUNK_BYTES) throw error("INVALID_REQUEST", "Chunk không hợp lệ");
        long offset = number(p, "offset"), expected = ((Number)t.get("acknowledged_offset")).longValue();
        long size = ((Number)m.get("declared_size")).longValue();
        if (offset < 0 || offset != expected) throw new SocialException("OFFSET_MISMATCH", "Offset không khớp", Map.of("expectedOffset", expected));
        if (offset > size - bytes.length) throw error("FILE_TOO_LARGE", "Chunk vượt kích thước khai báo");
        try (FileChannel out = FileChannel.open(part(String.valueOf(m.get("id"))), StandardOpenOption.WRITE)) {
            out.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        } catch (IOException e) { failUpload(String.valueOf(m.get("id")), "IO_ERROR"); throw error("SERVER_BUSY", "Không ghi được tệp"); }
        long through = offset + bytes.length;
        db.write(c -> { requireActive(c,actor); update(c,"UPDATE file_transfers SET acknowledged_offset=?,expires_at=?,updated_at=? WHERE id=?",through,System.currentTimeMillis()+IDLE,System.currentTimeMillis(),transferId); return null; });
        return Map.of("transferId", transferId, "acknowledgedOffset", through);
    }

    private Map<String, Object> status(long actor, String id) throws Exception {
        Map<String, Object> m = media(id);
        if (((Number)m.get("owner_id")).longValue() != actor) throw error("NOT_FOUND", "Không tìm thấy media");
        Map<String, Object> result = publicMedia(m);
        Map<String, Object> t = db.read(c -> row(c, "SELECT * FROM file_transfers WHERE media_id=? AND direction='UPLOAD' ORDER BY updated_at DESC LIMIT 1", id));
        if (!t.isEmpty()) { result.put("transferId",t.get("id"));result.put("acknowledgedOffset",t.get("acknowledged_offset"));result.put("transferState",t.get("state"));result.put("requiresResume",Set.of("EXPIRED","REPLACED").contains(String.valueOf(t.get("state")))); }
        return result;
    }

    private Map<String,Object> resume(long actor,Map<String,Object> p,String key)throws Exception {
        String id = text(p, "mediaId"); Map<String, Object> m = media(id);
        if (((Number)m.get("owner_id")).longValue() != actor) throw error("NOT_FOUND", "Không tìm thấy media");
        if (!"UPLOADING".equals(m.get("state"))) throw error("INVALID_STATE", "Không thể tiếp tục media này");
        if (((Number)m.get("expires_at")).longValue() < System.currentTimeMillis()) throw error("TRANSFER_EXPIRED", "Tệp tạm hết hạn");
        if (number(p, "size") != ((Number)m.get("declared_size")).longValue() || !text(p, "sha256").equalsIgnoreCase(String.valueOf(m.get("sha256")))) throw error("HASH_MISMATCH", "Tệp để tiếp tục không khớp");
        long checkpoint = db.read(c -> scalar(c, "SELECT COALESCE(MAX(acknowledged_offset),0) FROM file_transfers WHERE media_id=? AND direction='UPLOAD'", id));
        Path part = part(id); if (!Files.isRegularFile(part)) throw error("INVALID_STATE", "Tệp tạm không còn");
        long offset = Math.min(checkpoint, Files.size(part));
        try (FileChannel out = FileChannel.open(part, StandardOpenOption.WRITE)) { out.truncate(offset); out.force(true); }
        String transferId=stableTransfer(actor,"upload-resume",key);
        Map<String,Object> existingTransfer=db.read(c->row(c,"SELECT * FROM file_transfers WHERE id=?",transferId));
        if(!existingTransfer.isEmpty()){if(!id.equals(existingTransfer.get("media_id"))||((Number)existingTransfer.get("user_id")).longValue()!=actor)throw error("IDEMPOTENCY_CONFLICT","Lượt tiếp tục không khớp");return transferResult(id,transferId,((Number)existingTransfer.get("acknowledged_offset")).longValue(),((Number)existingTransfer.get("expires_at")).longValue());}
        long now=System.currentTimeMillis(),expiry=now+IDLE;
        db.write(c -> { requireActive(c,actor);
            long userActive=scalar(c,"SELECT count(*) FROM file_transfers WHERE direction='UPLOAD' AND state='UPLOADING' AND user_id=? AND media_id<>? AND expires_at>?",actor,id,now);
            long serverActive=scalar(c,"SELECT count(*) FROM file_transfers WHERE direction='UPLOAD' AND state='UPLOADING' AND media_id<>? AND expires_at>?",id,now);
            if(userActive>=2||serverActive>=20)throw error("SERVER_BUSY","Đã đạt giới hạn tải tệp đồng thời");
            update(c,"UPDATE file_transfers SET state='REPLACED' WHERE media_id=? AND direction='UPLOAD' AND state IN('UPLOADING','EXPIRED')",id);
            update(c, "INSERT INTO file_transfers(id,media_id,user_id,direction,state,acknowledged_offset,expires_at,updated_at) VALUES(?,?,?,'UPLOAD','UPLOADING',?,?,?)", transferId, id, actor, offset, expiry, now);
            return null;
        });
        return transferResult(id, transferId, offset, expiry);
    }

    private Map<String, Object> commit(long actor, Map<String, Object> p) throws Exception {
        Map<String, Object> t = transfer(actor, text(p, "transferId"), "UPLOAD"), m = media(String.valueOf(t.get("media_id")));
        if (Set.of("READY", "ATTACHED").contains(String.valueOf(m.get("state")))) return publicMedia(m);
        if (((Number)m.get("expires_at")).longValue() < System.currentTimeMillis() || ((Number)t.get("expires_at")).longValue() < System.currentTimeMillis()) throw error("TRANSFER_EXPIRED", "Lượt tải đã hết hạn");
        if (!"UPLOADING".equals(m.get("state")) || !"UPLOADING".equals(t.get("state"))) throw error("INVALID_STATE", "Tệp không thể hoàn tất");
        String id = String.valueOf(m.get("id")); Path part = part(id); long size = ((Number)m.get("declared_size")).longValue();
        if (!Files.isRegularFile(part) || Files.size(part) != size || ((Number)t.get("acknowledged_offset")).longValue() != size) throw error("OFFSET_MISMATCH", "Chưa nhận đủ dữ liệu");
        if (!checksum(part).equals(m.get("sha256"))) { failUpload(id, "HASH_MISMATCH"); throw error("HASH_MISMATCH", "Mã SHA-256 không khớp"); }
        if (!"CHAT_FILE".equals(m.get("purpose"))) {
            try { validateImage(part, String.valueOf(m.get("mime_type"))); }
            catch (Exception e) { failUpload(id, "INVALID_IMAGE"); throw e; }
        }
        db.write(c -> { requireActive(c,actor); update(c, "UPDATE media SET state='VERIFYING',actual_size=? WHERE id=?", size, id); return null; });
        try { Files.move(part, object(m), StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { throw error("SERVER_BUSY", "Không thể hoàn tất tệp; có thể kiểm tra trạng thái và thử lại"); }
        db.write(c -> { requireActive(c,actor);
            update(c, "UPDATE media SET state='READY' WHERE id=?", id);
            update(c, "UPDATE file_transfers SET state='READY',updated_at=? WHERE media_id=? AND direction='UPLOAD'", System.currentTimeMillis(), id);
            return null;
        });
        if(!"CHAT_FILE".equals(m.get("purpose")))createThumbnail(media(id));
        return publicMedia(media(id));
    }

    private Map<String, Object> cancel(long actor, String id) throws Exception {
        Map<String, Object> m = media(id);
        if (((Number)m.get("owner_id")).longValue() != actor) throw error("NOT_FOUND", "Không tìm thấy media");
        if ("ATTACHED".equals(m.get("state"))) throw error("INVALID_STATE", "Media đã gắn nội dung");
        db.write(c -> { requireActive(c,actor); update(c, "UPDATE media SET state='CANCELLED' WHERE id=?", id); update(c, "UPDATE file_transfers SET state='CANCELLED' WHERE media_id=?", id); return null; });
        Files.deleteIfExists(part(id)); Files.deleteIfExists(object(m));Files.deleteIfExists(thumbnailPath(id));
        return Map.of("mediaId", id, "state", "CANCELLED");
    }

    private Map<String,Object> downloadInit(long actor,Map<String,Object> p,String key)throws Exception {
        String mediaId = text(p, "mediaId");
        if (!policy.canAccessMedia(actor, mediaId)) throw error("NOT_FOUND", "Không tìm thấy tệp");
        Map<String, Object> m = media(mediaId); Path path = object(m);
        long size = ((Number)m.get("actual_size")).longValue(), offset = p.containsKey("offset") ? number(p, "offset") : 0;
        if (offset < 0 || offset > size || !Files.isRegularFile(path) || Files.size(path) != size) throw error("INVALID_STATE", "Tệp không khả dụng");
        if (downloads.values().stream().filter(d -> d.actor == actor).count() >= 2 || downloads.size() >= 20) throw error("SERVER_BUSY", "Quá nhiều lượt tải");
        String transfer=stableTransfer(actor,"download-init",key);long now=System.currentTimeMillis();
        Map<String,Object> existingTransfer=db.read(c->row(c,"SELECT * FROM file_transfers WHERE id=?",transfer));
        if(!existingTransfer.isEmpty()){if(!mediaId.equals(existingTransfer.get("media_id"))||((Number)existingTransfer.get("user_id")).longValue()!=actor)throw error("IDEMPOTENCY_CONFLICT","Lượt tải không khớp");Map<String,Object> result=publicMedia(m);result.put("transferId",transfer);result.put("offset",existingTransfer.get("acknowledged_offset"));result.put("acknowledgedOffset",existingTransfer.get("acknowledged_offset"));result.put("requiresResume",!downloads.containsKey(transfer));result.put("chunkBytes",CHUNK_BYTES);result.put("window",4);return result;}
        db.write(c -> { requireActive(c,actor);requireDownloadAccess(actor,mediaId);update(c, "INSERT INTO file_transfers(id,media_id,user_id,direction,state,acknowledged_offset,expires_at,updated_at) VALUES(?,?,?,'DOWNLOAD','DOWNLOADING',?,?,?)", transfer, mediaId, actor, offset, now + 30000, now); return null; });
        downloads.put(transfer, new Download(actor, mediaId, path, size, String.valueOf(m.get("sha256")), offset));
        Map<String, Object> result = publicMedia(m); result.put("transferId", transfer); result.put("offset", offset); result.put("chunkBytes", CHUNK_BYTES); result.put("window", 4);
        return result;
    }

    /** Called on a storage worker, never the reader/EDT. Policy is rechecked per chunk. */
    public synchronized List<Map<String, Object>> downloadWindow(long actor, String transferId) throws Exception {
        Download d = download(actor, transferId); List<Map<String, Object>> result = new ArrayList<>();
        if (d.sent > d.acknowledged || d.completeSent) return result;
        for (int n = 0; n < 4 && d.sent < d.size; n++) {
            if (!policy.canAccessMedia(actor, d.mediaId)) throw error("NOT_FOUND", "Quyền tải tệp đã bị thu hồi");
            int count = (int)Math.min(CHUNK_BYTES, d.size - d.sent); byte[] bytes = new byte[count];
            try (RandomAccessFile in = new RandomAccessFile(d.path.toFile(), "r")) { in.seek(d.sent); in.readFully(bytes); }
            result.add(Map.of("transferId", transferId, "offset", d.sent, "data", Base64.getEncoder().encodeToString(bytes)));
            d.sent += count;
        }
        d.lastActivity = System.currentTimeMillis();
        return result;
    }

    public synchronized Map<String, Object> completion(long actor, String transferId) throws Exception {
        Download d = download(actor, transferId);
        if (d.sent == d.size && d.acknowledged == d.size && !d.completeSent) {
            d.completeSent = true;
            return Map.of("transferId", transferId, "size", d.size, "sha256", d.hash);
        }
        return Map.of();
    }

    private Map<String, Object> acknowledge(long actor, Map<String, Object> p) throws Exception {
        String id = text(p, "transferId"); Download d = download(actor, id); long through = number(p, "throughOffset");
        if (through < d.acknowledged || through > d.sent) throw error("OFFSET_MISMATCH", "ACK không hợp lệ");
        boolean advanced=through>d.acknowledged;
        long activity=advanced?System.currentTimeMillis():d.lastActivity;
        db.write(c -> { requireActive(c,actor);requireDownloadAccess(actor,d.mediaId);update(c,"UPDATE file_transfers SET acknowledged_offset=?,expires_at=?,updated_at=? WHERE id=?",through,activity+30000,activity,id);return null; });
        d.acknowledged=through;d.lastActivity=activity;
        return Map.of("transferId",id,"acknowledgedOffset",through,"advanced",advanced);
    }

    private Map<String, Object> verified(long actor, Map<String, Object> p) throws Exception {
        String id = text(p, "transferId");
        Map<String,Object> saved = transfer(actor, id, "DOWNLOAD");
        if ("VERIFIED".equals(saved.get("state"))) {
            Map<String,Object> m = media(String.valueOf(saved.get("media_id")));
            if (number(p,"actualSize") != ((Number)m.get("actual_size")).longValue() || !text(p,"actualSha256").equalsIgnoreCase(String.valueOf(m.get("sha256")))) throw error("HASH_MISMATCH", "Xác minh tệp không khớp");
            return Map.of("transferId",id,"state","VERIFIED");
        }
        Download d = download(actor, id);
        if (!d.completeSent || number(p, "actualSize") != d.size || !text(p, "actualSha256").equalsIgnoreCase(d.hash)) throw error("HASH_MISMATCH", "Xác minh tệp không khớp");
        db.write(c -> { requireActive(c,actor);requireDownloadAccess(actor,d.mediaId);update(c, "UPDATE file_transfers SET state='VERIFIED',updated_at=? WHERE id=?", System.currentTimeMillis(), id); return null; });
        downloads.remove(id);
        return Map.of("transferId", id, "state", "VERIFIED");
    }

    private Map<String, Object> downloadCancel(long actor, Map<String, Object> p) throws Exception {
        String id = text(p, "transferId"); Map<String,Object> t = transfer(actor, id, "DOWNLOAD");
        if ("VERIFIED".equals(t.get("state"))) return Map.of("transferId",id,"state","VERIFIED");
        downloads.remove(id);
        db.write(c -> { requireActive(c,actor); update(c, "UPDATE file_transfers SET state='CANCELLED',failure_code=?,updated_at=? WHERE id=?",String.valueOf(p.getOrDefault("reason","USER_CANCEL")),System.currentTimeMillis(),id); return null; });
        return Map.of("transferId", id, "state", "CANCELLED");
    }

    public synchronized boolean abortDownload(long actor, String id, String reason) throws Exception {
        transfer(actor,id,"DOWNLOAD");
        boolean changed=db.write(c->{
            try(PreparedStatement statement=c.prepareStatement("UPDATE file_transfers SET state='FAILED',failure_code=?,updated_at=? WHERE id=? AND state='DOWNLOADING'")){
                bind(statement,reason,System.currentTimeMillis(),id);return statement.executeUpdate()==1;
            }
        });
        if(changed)downloads.remove(id);
        return changed;
    }

    public synchronized void disconnected(long actor){disconnected(actor,null);}
    public synchronized void disconnected(long actor,String sessionId) {
        downloads.entrySet().removeIf(e->e.getValue().actor==actor&&(sessionId==null||sessionId.equals(e.getValue().sessionId)));
        try{db.write(c->{update(c,"UPDATE file_transfers SET state='FAILED',failure_code='DISCONNECTED',updated_at=? WHERE user_id=? AND direction='DOWNLOAD' AND state='DOWNLOADING'"+(sessionId==null?"":" AND session_id=?"),sessionId==null?new Object[]{System.currentTimeMillis(),actor}:new Object[]{System.currentTimeMillis(),actor,sessionId});return null;});}catch(Exception ignored){}
    }
    public synchronized void cleanup() throws Exception {
        long now = System.currentTimeMillis();
        db.write(c->{update(c,"UPDATE file_transfers SET state='EXPIRED',failure_code='TRANSFER_EXPIRED' WHERE direction='UPLOAD' AND state='UPLOADING' AND expires_at<?",now);return null;});
        for (Map.Entry<String, Download> e : List.copyOf(downloads.entrySet())) {
            if (now - e.getValue().lastActivity > 30000) {
                failedDownloads.add(Map.of("userId",e.getValue().actor,"transferId",e.getKey(),"errorCode","TRANSFER_EXPIRED"));
                abortDownload(e.getValue().actor, e.getKey(), "TRANSFER_EXPIRED");
            }
        }
        List<Map<String, Object>> expired = db.read(c -> rows(c, "SELECT * FROM media WHERE state IN('UPLOADING','READY','FAILED','CANCELLED','EXPIRED') AND expires_at<?", now));
        for (Map<String, Object> m : expired) {
            String id = String.valueOf(m.get("id"));
            Files.deleteIfExists(part(id)); Files.deleteIfExists(object(m));Files.deleteIfExists(thumbnailPath(id));
            db.write(c -> { update(c, "UPDATE media SET state='EXPIRED' WHERE id=? AND state<>'ATTACHED'", id); return null; });
        }
        long retention=now-7L*24*60*60*1000;
        db.write(c->{
            List<Map<String,Object>> abandoned=rows(c,"SELECT b.media_id,p.deleted_at FROM media_bindings b JOIN posts p ON p.id=b.post_id WHERE p.status='DELETED' AND p.deleted_at<?",retention);
            for(Map<String,Object> m:abandoned){update(c,"DELETE FROM media_bindings WHERE media_id=?",m.get("media_id"));update(c,"UPDATE media SET state='RETIRED',retired_at=? WHERE id=?",m.get("deleted_at"),m.get("media_id"));}
            return null;
        });
        List<Map<String, Object>> retired = db.read(c -> rows(c, "SELECT * FROM media WHERE state='RETIRED' AND COALESCE(retired_at,created_at)<?", retention));
        for (Map<String, Object> m : retired) {
            if (db.read(c -> scalar(c, "SELECT count(*) FROM media_bindings WHERE media_id=?", m.get("id"))) == 0) {
                Files.deleteIfExists(object(m));
                Files.deleteIfExists(thumbnailPath(String.valueOf(m.get("id"))));
                db.write(c -> { update(c, "UPDATE media SET state='EXPIRED' WHERE id=?", m.get("id")); return null; });
            }
        }
    }

    public synchronized List<Map<String,Object>> drainFailedDownloads(){List<Map<String,Object>> result=new ArrayList<>(failedDownloads);failedDownloads.clear();return result;}

    private Map<String,Object> thumbnail(long actor,String id) throws Exception {
        if(!policy.canAccessMedia(actor,id))throw error("NOT_FOUND","Không tìm thấy ảnh");
        Map<String,Object> m=media(id);if("CHAT_FILE".equals(m.get("purpose")))throw error("INVALID_REQUEST","Tệp không phải ảnh");
        Path path=thumbnailPath(id);if(!Files.isRegularFile(path))createThumbnail(m);
        if(!policy.canAccessMedia(actor,id))throw error("NOT_FOUND","Quyền xem ảnh đã thay đổi");
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>300000)throw error("FILE_TOO_LARGE","Thumbnail vượt giới hạn");
        requireDownloadAccess(actor,id);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return Map.of("mediaId",id,"mimeType","image/png","data",Base64.getEncoder().encodeToString(bytes),"size",bytes.length,"sha256",digest);
    }
    private void createThumbnail(Map<String,Object> m) throws Exception {
        BufferedImage source=ImageIO.read(object(m).toFile());if(source==null)throw error("INVALID_REQUEST","Ảnh không đọc được");
        double scale=Math.min(1.0,256.0/Math.max(source.getWidth(),source.getHeight()));
        BufferedImage thumb=new BufferedImage(Math.max(1,(int)(source.getWidth()*scale)),Math.max(1,(int)(source.getHeight()*scale)),BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=thumb.createGraphics();try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(source,0,0,thumb.getWidth(),thumb.getHeight(),null);}finally{graphics.dispose();}
        Path target=thumbnailPath(String.valueOf(m.get("id"))),temporary=target.resolveSibling(target.getFileName()+".part");
        if(!ImageIO.write(thumb,"png",temporary.toFile()))throw new IOException("PNG encoder unavailable");
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    private void recover() throws Exception {
        for(Map<String,Object> upload:db.read(c->rows(c,"SELECT * FROM media WHERE state='UPLOADING'"))) {
            String id=String.valueOf(upload.get("id"));Path temporary=part(id);
            long declared=((Number)upload.get("declared_size")).longValue();
            Map<String,Object> transfer=db.read(c->row(c,"SELECT * FROM file_transfers WHERE media_id=? AND direction='UPLOAD' AND state='UPLOADING' ORDER BY updated_at DESC,id DESC LIMIT 1",id));
            if(transfer.isEmpty()){failUpload(id,"MISSING_TRANSFER");continue;}
            long durable=((Number)transfer.get("acknowledged_offset")).longValue();
            if(!Files.isRegularFile(temporary)){if(durable==0)Files.createFile(temporary);else{failUpload(id,"MISSING_STORAGE");continue;}}
            long offset=Math.min(declared,Math.min(durable,Files.size(temporary)));
            try(FileChannel out=FileChannel.open(temporary,StandardOpenOption.WRITE)){out.truncate(offset);out.force(true);}
            db.write(c->{update(c,"UPDATE file_transfers SET acknowledged_offset=? WHERE id=?",offset,transfer.get("id"));return null;});
        }
        db.write(c->{update(c,"UPDATE file_transfers SET state='FAILED',failure_code='SERVER_RESTART' WHERE direction='DOWNLOAD' AND state='DOWNLOADING'");return null;});
        List<Map<String, Object>> verifying = db.read(c -> rows(c, "SELECT * FROM media WHERE state='VERIFYING'"));
        for (Map<String, Object> m : verifying) {
            String id = String.valueOf(m.get("id")); Path target = object(m), temporary = part(id);
            Path existing = Files.isRegularFile(target) ? target : temporary;
            if (Files.isRegularFile(existing) && Files.size(existing) == ((Number)m.get("declared_size")).longValue() && checksum(existing).equals(m.get("sha256"))) {
                if (!existing.equals(target)) Files.move(existing, target, StandardCopyOption.ATOMIC_MOVE);
                db.write(c -> { update(c, "UPDATE media SET state='READY',actual_size=declared_size WHERE id=?", id); update(c, "UPDATE file_transfers SET state='READY' WHERE media_id=? AND direction='UPLOAD'", id); return null; });
            } else failUpload(id, "HASH_MISMATCH");
        }
        cleanup();
    }

    private Map<String, Object> media(String id) throws Exception {
        Map<String, Object> result = db.read(c -> row(c, "SELECT * FROM media WHERE id=?", id));
        if (result.isEmpty()) throw error("NOT_FOUND", "Không tìm thấy media"); return result;
    }
    private Map<String, Object> transfer(long actor, String id, String direction) throws Exception {
        Map<String, Object> t = db.read(c -> row(c, "SELECT * FROM file_transfers WHERE id=? AND user_id=? AND direction=?", id, actor, direction));
        if (t.isEmpty()) throw error("NOT_FOUND", "Không tìm thấy lượt truyền"); return t;
    }
    private Download download(long actor, String id) throws SocialException {
        Download d = downloads.get(id); if (d == null || d.actor != actor) throw error("NOT_FOUND", "Không tìm thấy lượt tải"); return d;
    }
    private void failUpload(String id, String code) throws Exception {
        db.write(c -> { update(c, "UPDATE media SET state='FAILED' WHERE id=?", id); update(c, "UPDATE file_transfers SET state='FAILED',failure_code=? WHERE media_id=? AND direction='UPLOAD'", code, id); return null; });
        // A failed reservation must release the physical bytes, not only its quota row.
        Map<String,Object> metadata=media(id);
        Files.deleteIfExists(part(id));Files.deleteIfExists(object(metadata));Files.deleteIfExists(thumbnailPath(id));
    }
    private Path part(String id) throws IOException { return contained("uploads/" + UUID.fromString(id) + ".part"); }
    private Path thumbnailPath(String id)throws IOException{return contained("objects/"+UUID.fromString(id)+".thumb.png");}
    private Path object(Map<String, Object> m) throws IOException { return contained(String.valueOf(m.get("storage_key"))); }
    private Path contained(String key) throws IOException {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || !p.getParent().toRealPath().startsWith(root) || (Files.exists(p) && !p.toRealPath().startsWith(root))) throw new IOException("Storage containment violated");
        return p;
    }
    private static Map<String, Object> transferResult(String media, String transfer, long offset, long expiry) {
        return Map.of("mediaId", media, "transferId", transfer, "acknowledgedOffset", offset, "chunkBytes", CHUNK_BYTES, "window", 4, "expiresAt", expiry, "state", "UPLOADING");
    }
    private static Map<String, Object> publicMedia(Map<String, Object> m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mediaId", m.get("id")); p.put("fileName", m.get("original_name")); p.put("mimeType", m.get("mime_type")); p.put("purpose", m.get("purpose"));
        p.put("size", m.get("actual_size") == null ? m.get("declared_size") : m.get("actual_size")); p.put("sha256", m.get("sha256")); p.put("state", m.get("state")); p.put("expiresAt", m.get("expires_at")); return p;
    }
    public static String checksum(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(path)) { int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void validateImage(Path path, String mime) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw error("INVALID_REQUEST", "Ảnh không đọc được");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input); String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(mime.equals("image/png") && format.equals("png") || mime.equals("image/jpeg") && Set.of("jpeg", "jpg").contains(format))) throw error("INVALID_REQUEST", "Nội dung ảnh không khớp loại");
                long pixels = (long)reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 20_000_000) throw error("FILE_TOO_LARGE", "Ảnh vượt 20 megapixel");
                BufferedImage image = reader.read(0); if (image == null) throw error("INVALID_REQUEST", "Ảnh không hợp lệ");
            } finally { reader.dispose(); }
        }
    }
    private void requireDownloadAccess(long actor,String id)throws Exception{if(!policy.canAccessMedia(actor,id))throw error("NOT_FOUND","Quyền tải tệp đã bị thu hồi");}
    private static String stableTransfer(long actor,String operation,String key){return key==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes((operation+":"+actor+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    private static void requireActive(Connection c,long actor)throws Exception{Map<String,Object> user=row(c,"SELECT status FROM users WHERE id=?",actor);if(user.isEmpty())throw error("UNAUTHENTICATED","Cần đăng nhập");if(!"ACTIVE".equals(user.get("status")))throw error("ACCOUNT_SUSPENDED","Tài khoản đã bị khóa");}
    private static String filename(String value) throws SocialException {
        if (value.isBlank() || value.length() > 120 || value.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || value.endsWith(".") || value.endsWith(" ") || value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) throw error("INVALID_REQUEST", "Tên tệp không hợp lệ"); return value;
    }
    private static String text(Map<String, Object> p, String name) throws SocialException {
        Object value = p.get(name); if (!(value instanceof String s) || s.isBlank()) throw error("INVALID_REQUEST", "Thiếu " + name); return s;
    }
    private static long number(Map<String, Object> p, String name) throws SocialException {
        Object value = p.get(name); if (!(value instanceof Number n) || n.doubleValue() != n.longValue()) throw error("INVALID_REQUEST", "Số không hợp lệ: " + name); return n.longValue();
    }
    private static SocialException error(String code, String message) { return new SocialException(code, message); }
    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); s.executeUpdate(); }
    }
    private static long scalar(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; } }
    }
    private static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); try (ResultSet r = s.executeQuery()) { ResultSetMetaData md = r.getMetaData(); while (r.next()) { Map<String, Object> row = new LinkedHashMap<>(); for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), r.getObject(i)); rows.add(row); } } } return rows;
    }
    private static Map<String, Object> row(Connection c, String sql, Object... args) throws SQLException { List<Map<String, Object>> rows = rows(c, sql, args); return rows.isEmpty() ? Map.of() : rows.get(0); }
    private static void bind(PreparedStatement s, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]); }
    @Override public synchronized void close() { downloads.clear(); }
    private static final class Download {
        final long actor, size; final String mediaId, hash; final Path path;
        String sessionId; long sent, acknowledged, lastActivity = System.currentTimeMillis(); boolean completeSent;
        Download(long actor, String mediaId, Path path, long size, String hash, long offset) { this.actor = actor; this.mediaId = mediaId; this.path = path; this.size = size; this.hash = hash; this.sent = offset; this.acknowledged = offset; }
    }
}




