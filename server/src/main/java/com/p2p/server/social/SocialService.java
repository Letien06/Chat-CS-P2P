package com.p2p.server.social;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
public final class SocialService {
    private final SocialDatabase db;
    private final ObjectMapper json=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(DeserializationFeature.USE_LONG_FOR_INTS,true);
    private final byte[] cursorSecret=new byte[32];
    private volatile java.util.function.LongPredicate presence=id->false;
    public void setPresenceProvider(java.util.function.LongPredicate provider) {
        presence=Objects.requireNonNull(provider);
    }
    public SocialService(SocialDatabase db) {
        this.db=db;
        new SecureRandom().nextBytes(cursorSecret);
    }
    static SocialException error(String code) {
        return new SocialException(code,code.replace('_',' '));
    }
    static Map<String,Object> map(Object... kv) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1] instanceof Integer n?n.longValue():kv[i+1]);
        return m;
    }
    static long number(Map<String,Object> p,String k)throws SocialException {
        Object v=p.get(k);
        if(!(v instanceof Number n)||n.longValue()<0||n.doubleValue()!=n.longValue())throw error("INVALID_REQUEST");
        return n.longValue();
    }
    static String text(Map<String,Object> p,String k,int max,boolean empty)throws SocialException {
        Object v=p.get(k);
        if(!(v instanceof String s))throw error("INVALID_REQUEST");
        s=s.trim();
        if(s.length()>max||(!empty&&s.isEmpty())||s.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw error("INVALID_REQUEST");
        return s;
    }
    static boolean bool(Map<String,Object> p,String k)throws SocialException {
        if(!(p.get(k) instanceof Boolean b))throw error("INVALID_REQUEST");
        return b;
    }
    static String password(Map<String,Object> p,String key)throws SocialException {
        Object v=p.get(key);
        if(!(v instanceof String s)||s.length()<10||s.length()>128||s.getBytes(StandardCharsets.UTF_8).length>512)throw error("INVALID_REQUEST");
        return s;
    }
    static void fields(Map<String,Object> p,String allowed)throws SocialException {
        Set<String> keys=new HashSet<>(Arrays.asList(allowed.split(",")));
        if(!keys.containsAll(p.keySet()))throw error("INVALID_REQUEST");
    }
    static int update(Connection c,String sql,Object... args)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            bind(s,args);
            return s.executeUpdate();
        }
    }
    static void bind(PreparedStatement s,Object... args)throws SQLException {
        for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);
    }
    static List<Map<String,Object>> rows(Connection c,String sql,Object... args)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            bind(s,args);
            try(ResultSet r=s.executeQuery()) {
                List<Map<String,Object>> all=new ArrayList<>();
                while(r.next()) {
                    Map<String,Object> m=new LinkedHashMap<>();
                    for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
                        String key=r.getMetaData().getColumnLabel(i);
                        StringBuilder camel=new StringBuilder();
                        boolean upper=false;
                        for(char ch:key.toCharArray()) {
                            if(ch=='_') {
                                upper=true;
                                continue;
                            }
                            camel.append(upper?Character.toUpperCase(ch):ch);
                            upper=false;
                        }
                        m.put(camel.toString(),r.getObject(i) instanceof Integer n?n.longValue():r.getObject(i));
                    }
                    all.add(m);
                }
                return all;
            }
        }
    }
    static Map<String,Object> one(Connection c,String sql,Object... args)throws Exception {
        List<Map<String,Object>> list=rows(c,sql,args);
        if(list.isEmpty())throw error("NOT_FOUND");
        return list.get(0);
    }
    static long lng(Map<String,Object> p,String k) {
        return ((Number)p.get(k)).longValue();
    }
    static long insert(Connection c,String sql,Object... args)throws Exception {
        update(c,sql,args);
        return lng(one(c,"SELECT last_insert_rowid() AS id"),"id");
    }
    static long now() {
        return System.currentTimeMillis();
    }
    String hash(Object p)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(p)));
    }
    Map<String,Object> current(Connection c,long actor)throws Exception {
        return one(c,"SELECT u.id,u.username,p.display_name,p.bio,p.version,u.role,u.status,u.auth_version,u.relationships_version,(SELECT media_id FROM media_bindings WHERE avatar_user_id=u.id) AS avatar_media_id FROM users u JOIN profiles p ON p.user_id=u.id WHERE u.id=?",actor);
    }
    void active(Connection c,long actor)throws Exception {
        Map<String,Object> u=current(c,actor);
        if(!"ACTIVE".equals(u.get("status")))throw error("ACCOUNT_SUSPENDED");
    }
    public Map<String,Object> user(long id)throws Exception {
        return db.read(c->current(c,id));
    }
    public Map<String,Object> register(Map<String,Object> p)throws Exception {
        fields(p,"username,displayName,password");
        String username=text(p,"username",32,false);
        if(!username.matches("[a-zA-Z0-9_.]{3,32}"))throw error("INVALID_REQUEST");
        String display=text(p,"displayName",60,false),pw=password(p,"password"),salt=Passwords.salt(),hash=Passwords.hash(pw,salt,Passwords.ITERATIONS);
        return db.write(c-> {
            try {
                long id=insert(c,"INSERT INTO users(username,username_key,password_hash,salt,hash_algorithm,hash_iterations,created_at) VALUES(?,?,?,?,?,?,?)",username,username.toLowerCase(Locale.ROOT),hash,salt,"PBKDF2WithHmacSHA256",Passwords.ITERATIONS,now());update(c,"INSERT INTO profiles(user_id,display_name,updated_at) VALUES(?,?,?)",id,display,now());return map("userId",id);
            }
            catch(SQLException e) {
                if(e.getMessage().contains("username_key"))throw error("USERNAME_TAKEN");throw e;
            }
        }
        );
    }
    public Map<String,Object> login(Map<String,Object> p)throws Exception {
        fields(p,"username,password");
        String username=text(p,"username",32,false);
        Object raw=p.get("password");
        if(!(raw instanceof String password)||password.length()>128||password.getBytes(StandardCharsets.UTF_8).length>512)throw error("INVALID_CREDENTIALS");
        List<Map<String,Object>> users=db.read(c->rows(c,"SELECT * FROM users WHERE username_key=?",username.toLowerCase(Locale.ROOT)));
        if(users.isEmpty()) {
            Passwords.hash(password,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",Passwords.ITERATIONS);
            throw error("INVALID_CREDENTIALS");
        }
        Map<String,Object> snapshot=users.get(0);
        if(!Passwords.verify(password,(String)snapshot.get("salt"),((Number)snapshot.get("hashIterations")).intValue(),(String)snapshot.get("passwordHash")))throw error("INVALID_CREDENTIALS");
        return db.write(c-> {
            long id=lng(snapshot,"id");Map<String,Object> current=current(c,id);if(lng(current,"authVersion")!=lng(snapshot,"authVersion"))throw error("INVALID_CREDENTIALS");active(c,id);update(c,"UPDATE users SET last_login_at=? WHERE id=?",now(),id);return current;
        }
        );
    }
    public Map<String,Object> changePassword(long actor,Map<String,Object> p)throws Exception {
        fields(p,"currentPassword,newPassword");
        String old=password(p,"currentPassword"),next=password(p,"newPassword");
        Map<String,Object> snapshot=db.read(c-> {
            active(c,actor);return one(c,"SELECT * FROM users WHERE id=?",actor);
        }
        );
        if(!Passwords.verify(old,(String)snapshot.get("salt"),((Number)snapshot.get("hashIterations")).intValue(),(String)snapshot.get("passwordHash")))throw error("INVALID_CREDENTIALS");
        String salt=Passwords.salt(),hash=Passwords.hash(next,salt,Passwords.ITERATIONS);
        return db.write(c-> {
            active(c,actor);Map<String,Object> current=current(c,actor);if(lng(current,"authVersion")!=lng(snapshot,"authVersion"))throw error("INVALID_CREDENTIALS");update(c,"UPDATE users SET salt=?,password_hash=?,auth_version=auth_version+1 WHERE id=?",salt,hash,actor);long version=lng(snapshot,"authVersion")+1;event(c,"SESSION_REVOKED","USER",actor,version,List.of(actor));return map("authVersion",version);
        }
        );
    }
    public Map<String,Object> bootstrapAdmin(String username,String displayName,String password)throws Exception {
        Map<String,Object> r=register(map("username",username,"displayName",displayName,"password",password));
        long id=lng(r,"userId");
        db.write(c-> {
            update(c,"UPDATE users SET role='ADMIN' WHERE id=?",id);return null;
        }
        );
        return user(id);
    }
    boolean blocked(Connection c,long a,long b)throws Exception {
        return !rows(c,"SELECT 1 FROM blocks WHERE (blocker_id=? AND blocked_id=?) OR(blocker_id=? AND blocked_id=?)",a,b,b,a).isEmpty();
    }
    boolean friends(Connection c,long a,long b)throws Exception {
        return !blocked(c,a,b)&&!rows(c,"SELECT 1 FROM relationships WHERE low_user_id=? AND high_user_id=? AND status='ACCEPTED'",Math.min(a,b),Math.max(a,b)).isEmpty();
    }
    void friendship(Connection c,long a,long b)throws Exception {
        if(a==b)throw error("INVALID_REQUEST");
        active(c,b);
        if(!friends(c,a,b))throw error("FRIENDSHIP_REQUIRED");
    }
    void version(Map<String,Object> resource,Map<String,Object> p)throws Exception {
        if(lng(resource,"version")!=number(p,"expectedVersion"))throw new SocialException("VERSION_CONFLICT","Resource changed",map("currentVersion",resource.get("version")));
    }
    String postPolicy(long actor) {
        return "p.status='ACTIVE' AND u.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM blocks b WHERE(b.blocker_id="+actor+" AND b.blocked_id=p.author_id) OR(b.blocked_id="+actor+" AND b.blocker_id=p.author_id)) AND(p.author_id="+actor+" OR p.visibility='PUBLIC' OR(p.visibility='FRIENDS' AND EXISTS(SELECT 1 FROM relationships r WHERE r.status='ACCEPTED' AND r.low_user_id=min("+actor+",p.author_id) AND r.high_user_id=max("+actor+",p.author_id))))";
    }
    Map<String,Object> post(Connection c,long actor,long id)throws Exception {
        Map<String,Object> p=one(c,"SELECT p.* FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND "+postPolicy(actor),id);
        p.put("postId",id);
        Map<String,Object> author=current(c,lng(p,"authorId"));
        p.put("authorName",author.get("displayName"));
        p.put("authorUsername",author.get("username"));
        p.put("authorAvatarMediaId",author.get("avatarMediaId"));
        p.put("media",rows(c,"SELECT m.id AS media_id,m.original_name AS file_name,m.mime_type,m.actual_size AS size,m.sha256,b.slot FROM media m JOIN media_bindings b ON b.media_id=m.id WHERE b.post_id=? ORDER BY b.slot",id));
        p.put("mediaIds",rows(c,"SELECT media_id FROM media_bindings WHERE post_id=? ORDER BY slot",id).stream().map(m->m.get("mediaId")).toList());
        p.put("likeCount",lng(one(c,"SELECT count(*) AS n FROM post_likes l JOIN users u ON u.id=l.user_id WHERE l.post_id=? AND u.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM blocks b WHERE(b.blocker_id=? AND b.blocked_id=l.user_id) OR(b.blocked_id=? AND b.blocker_id=l.user_id))",id,actor,actor),"n"));
        p.put("liked",!rows(c,"SELECT 1 FROM post_likes WHERE post_id=? AND user_id=?",id,actor).isEmpty());
        p.put("commentCount",lng(one(c,"SELECT count(*) AS n FROM comments x JOIN users u ON u.id=x.author_id WHERE x.post_id=? AND x.status='ACTIVE' AND u.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM blocks b WHERE(b.blocker_id=? AND b.blocked_id=x.author_id) OR(b.blocked_id=? AND b.blocker_id=x.author_id))",id,actor,actor),"n"));
        return p;
    }
    void attach(Connection c,long actor,String media,String purpose,String column,long target,int slot)throws Exception {
        Map<String,Object> m=one(c,"SELECT * FROM media WHERE id=?",media);
        if(lng(m,"ownerId")!=actor||!purpose.equals(m.get("purpose"))||!"READY".equals(m.get("state"))||lng(m,"expiresAt")<now())throw error("INVALID_STATE");
        if(lng(m,"declaredSize")>(purpose.equals("CHAT_FILE")?25L:5L)*1024*1024)throw error("FILE_TOO_LARGE");
        update(c,"INSERT INTO media_bindings(media_id,"+column+",slot,bound_at) VALUES(?,?,?,?)",media,target,slot,now());
        update(c,"UPDATE media SET state='ATTACHED' WHERE id=?",media);
    }
    void event(Connection c,String type,String aggregate,long id,long revision,List<Long> audience)throws Exception {
        update(c,"INSERT INTO outbox_events(event_id,type,aggregate_type,aggregate_id,aggregate_version,audience_json,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),type,aggregate,id,revision,json.writeValueAsString(audience),now());
    }
    void notify(Connection c,long recipient,long actor,String type,String key,String column,long id,long seq)throws Exception {
        if(recipient==actor)return;
        update(c,"INSERT INTO notifications(recipient_id,actor_id,type,source_key,"+column+",latest_seq,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(recipient_id,source_key) DO UPDATE SET latest_seq=excluded.latest_seq,updated_at=excluded.updated_at,is_read=CASE WHEN excluded.type='MESSAGE' THEN 0 ELSE notifications.is_read END",recipient,actor,type,key,id,seq,now(),now());
        event(c,"NOTIFICATION_CHANGED","NOTIFICATION",id,seq,List.of(recipient));
    }
    public Map<String,Object> execute(long actor,String type,Map<String,Object> p,String key)throws Exception {
        if(type.equals("AUTH_PASSWORD_CHANGE"))return changePassword(actor,p);
        SocialDatabase.SqlWork<Map<String,Object>> work=c-> {
            active(c,actor);String schema=SCHEMAS.get(type);if(schema==null)throw error("UNSUPPORTED_OPERATION");fields(p,schema);boolean mutation=MUTATIONS.contains(type);String fingerprint=hash(p);if(mutation) {
                try {
                    UUID.fromString(key);
                }
                catch(Exception e) {
                    throw error("INVALID_REQUEST");
                }
                List<Map<String,Object>> old=rows(c,"SELECT * FROM idempotency_records WHERE actor_id=? AND operation=? AND key=? AND expires_at>?",actor,type,key,now());if(!old.isEmpty()) {
                    if(!fingerprint.equals(old.get(0).get("payloadHash")))throw error("IDEMPOTENCY_CONFLICT");Map<String,Object> replay=json.readValue((String)old.get(0).get("resultJson"),new TypeReference<Map<String,Object>>() {
                    }
                    );return replay(c,actor,type,p,replay);
                }
            }
            Map<String,Object> result=dispatch(c,actor,type,p);if(mutation)update(c,"INSERT OR REPLACE INTO idempotency_records(actor_id,operation,key,payload_hash,result_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",actor,type,key,fingerprint,json.writeValueAsString(result),now(),now()+604800000L);return result;
        };
        return MUTATIONS.contains(type)||Set.of("ADMIN_REPORT_GET","GROUP_INVITE_LIST").contains(type)?db.write(work):db.read(work);
    }
    private static final Map<String,String> SCHEMAS=new HashMap<>();
    private static final Set<String>MUTATIONS=new HashSet<>();
    static {
        String[] specs= {
            "AUTH_PASSWORD_CHANGE:currentPassword,newPassword","PROFILE_GET:targetUserId","PROFILE_UPDATE*:displayName,bio,expectedVersion","AVATAR_SET*:mediaId,expectedVersion","USER_SEARCH:query,cursor,limit","RELATION_LIST:kind,cursor,limit","PRESENCE_GET:userIds","FRIEND_REQUEST*:targetUserId","FRIEND_ACCEPT*:relationId,expectedVersion","FRIEND_REJECT*:relationId,expectedVersion","FRIEND_CANCEL*:relationId,expectedVersion","FRIEND_REMOVE*:relationId","BLOCK_SET*:targetUserId,blocked","BLOCK_LIST:cursor,limit","POST_CREATE*:content,visibility,mediaIds","POST_GET:postId","POST_UPDATE*:postId,content,visibility,expectedVersion","POST_DELETE*:postId,expectedVersion","FEED_GET:cursor,limit","PROFILE_POSTS_GET:authorId,cursor,limit","COMMENT_LIST:postId,cursor,limit","COMMENT_CREATE*:postId,content","COMMENT_DELETE*:commentId","LIKE_SET*:postId,liked","CONVERSATION_DIRECT_GET_OR_CREATE*:targetUserId","CONVERSATION_LIST:cursor,limit","MESSAGE_SEND*:conversationId,clientMessageId,content,mediaId","MESSAGE_HISTORY:conversationId,beforeSeq,afterSeq,limit","MESSAGE_LOOKUP:clientMessageId","MESSAGE_DELIVERED*:conversationId,throughSeq","MESSAGE_READ*:conversationId,throughSeq","GROUP_CREATE*:title","GROUP_INVITE*:conversationId,targetUserId","GROUP_INVITE_LIST:cursor,limit","GROUP_INVITE_ACCEPT*:inviteId","GROUP_INVITE_REJECT*:inviteId","GROUP_INVITE_CANCEL*:inviteId","GROUP_MEMBERS_GET:conversationId,cursor,limit","GROUP_RENAME*:conversationId,expectedVersion,title","GROUP_KICK*:conversationId,expectedVersion,userId","GROUP_OWNER_TRANSFER*:conversationId,expectedVersion,userId","GROUP_LEAVE*:conversationId,expectedVersion","GROUP_DISBAND*:conversationId,expectedVersion","NOTIFICATION_LIST:cursor,limit,unreadOnly","NOTIFICATION_READ*:notificationIds","NOTIFICATION_READ_THROUGH*:throughCursor","NOTIFICATION_COUNT:","REPORT_CREATE*:targetType,targetId,reason,description","ADMIN_REPORT_LIST:filter,cursor,limit","ADMIN_REPORT_GET:reportId","ADMIN_REPORT_CLAIM*:reportId,expectedVersion","ADMIN_REPORT_RESOLVE*:reportId,action,reason,expectedVersion","ADMIN_AUDIT_LIST:cursor,limit,filter","ADMIN_AUDIT_GET:auditId","ADMIN_USER_UNSUSPEND*:targetUserId,reason","ADMIN_GROUP_OWNER_TRANSFER*:conversationId,targetUserId,expectedVersion,reason","ADMIN_GROUP_CLOSE*:conversationId,expectedVersion,reason","STATE_SYNC:conversationsCursor,notificationsCursor,includeConversations,includeNotifications,limit"
        }
        ;
        for(String spec:specs) {
            int colon=spec.indexOf(':');
            String name=spec.substring(0,colon);
            if(name.endsWith("*")) {
                name=name.substring(0,name.length()-1);
                MUTATIONS.add(name);
            }
            SCHEMAS.put(name,spec.substring(colon+1));
        }
    }
    private Map<String,Object> dispatch(Connection c,long a,String t,Map<String,Object> p)throws Exception {
        switch(t) {
            case "PROFILE_GET": {
                long id=number(p,"targetUserId");
                active(c,id);
                if(blocked(c,a,id))throw error("NOT_FOUND");
                Map<String,Object> u=current(c,id);
                u.remove("authVersion");
                u.remove("role");
                u.remove("relationshipsVersion");
                return u;
            }
            case "PROFILE_UPDATE":case "AVATAR_SET": {
                Map<String,Object> u=current(c,a);
                version(u,p);
                if(t.equals("PROFILE_UPDATE"))update(c,"UPDATE profiles SET display_name=?,bio=?,version=version+1,updated_at=? WHERE user_id=?",text(p,"displayName",60,false),text(p,"bio",500,true),now(),a);
                else {
                    String mid=text(p,"mediaId",100,false);
                    List<Map<String,Object>> old=rows(c,"SELECT media_id FROM media_bindings WHERE avatar_user_id=?",a);
                    update(c,"DELETE FROM media_bindings WHERE avatar_user_id=?",a);
                    for(Map<String,Object> b:old)update(c,"UPDATE media SET state='RETIRED',retired_at=? WHERE id=?",now(),b.get("mediaId"));
                    attach(c,a,mid,"AVATAR","avatar_user_id",a,0);
                    update(c,"UPDATE profiles SET version=version+1,updated_at=? WHERE user_id=?",now(),a);
                }
                event(c,"PROFILE_INVALIDATED","USER",a,lng(u,"version")+1,publicAudience(c));
                return current(c,a);
            }
            case "USER_SEARCH": {
                String q=text(p,"query",60,false);
                if(q.length()<2)throw error("INVALID_REQUEST");
                String pattern="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
                return page(c,a,t+q,p,"SELECT u.id,u.username,p.display_name FROM users u JOIN profiles p ON p.user_id=u.id WHERE u.status='ACTIVE' AND(u.username LIKE ? ESCAPE '!' OR p.display_name LIKE ? ESCAPE '!') AND NOT EXISTS(SELECT 1 FROM blocks b WHERE(b.blocker_id=? AND b.blocked_id=u.id) OR(b.blocked_id=? AND b.blocker_id=u.id)) AND u.id<? ORDER BY u.id DESC",List.of(pattern,pattern,a,a));
            }
            case "PRESENCE_GET": {
                Object ids=p.get("userIds");
                if(!(ids instanceof List<?> list)||list.size()>50)throw error("INVALID_REQUEST");
                List<Map<String,Object>> items=new ArrayList<>();
                for(Object id:list) {
                    if(!(id instanceof Number n))throw error("INVALID_REQUEST");
                    if(n.longValue()==a||friends(c,a,n.longValue()))items.add(map("userId",n.longValue(),"online",presence.test(n.longValue()),"asOf",now()));
                }
                return map("items",items);
            }
            case "FRIEND_REQUEST": {
                long b=number(p,"targetUserId");
                if(a==b)throw error("INVALID_REQUEST");
                active(c,b);
                if(blocked(c,a,b))throw error("ACTION_FORBIDDEN");
                List<Map<String,Object>> old=rows(c,"SELECT * FROM relationships WHERE low_user_id=? AND high_user_id=? AND status IN('PENDING','ACCEPTED')",Math.min(a,b),Math.max(a,b));
                if(!old.isEmpty()) {
                    Map<String,Object> r=old.get(0);
                    if(r.get("status").equals("ACCEPTED"))throw error("ALREADY_FRIENDS");
                    if(lng(r,"requesterId")!=a)throw error("INCOMING_REQUEST_EXISTS");
                    return relation(r);
                }
                long id=insert(c,"INSERT INTO relationships(low_user_id,high_user_id,requester_id,addressee_id,status,requested_at) VALUES(?,?,?,?,'PENDING',?)",Math.min(a,b),Math.max(a,b),a,b,now());
                relationsChanged(c,a,b,id,1);
                notify(c,b,a,"FRIEND_REQUEST","request:"+id,"relation_id",id,0);
                return relation(one(c,"SELECT * FROM relationships WHERE id=?",id));
            }
            case "FRIEND_ACCEPT":case "FRIEND_REJECT":case "FRIEND_CANCEL":case "FRIEND_REMOVE": {
                long id=number(p,"relationId");
                Map<String,Object> r=one(c,"SELECT * FROM relationships WHERE id=?",id);
                long sender=lng(r,"requesterId"),receiver=lng(r,"addresseeId");
                if(a!=sender&&a!=receiver)throw error("NOT_FOUND");
                String status=(String)r.get("status");
                if(t.equals("FRIEND_REMOVE")) {
                    if(!status.equals("ACCEPTED"))throw error("INVALID_STATE");
                }
                else {
                    version(r,p);
                    if(!status.equals("PENDING"))throw error("INVALID_STATE");
                    if(a!=(t.equals("FRIEND_CANCEL")?sender:receiver))throw error("ACTION_FORBIDDEN");
                }
                if(t.equals("FRIEND_ACCEPT")) {
                    active(c,sender);
                    active(c,receiver);
                    if(blocked(c,sender,receiver))throw error("ACTION_FORBIDDEN");
                }
                String next=switch(t) {
                    case "FRIEND_ACCEPT"->"ACCEPTED";
                    case "FRIEND_REJECT"->"REJECTED";
                    case "FRIEND_CANCEL"->"CANCELLED";
                    default->"REMOVED";
                }
                ;
                update(c,"UPDATE relationships SET status=?,version=version+1,resolved_at=? WHERE id=?",next,now(),id);
                relationsChanged(c,sender,receiver,id,lng(r,"version")+1);
                if(next.equals("ACCEPTED"))notify(c,sender,a,"FRIEND_ACCEPT","accept:"+id,"relation_id",id,0);
                return relation(one(c,"SELECT * FROM relationships WHERE id=?",id));
            }
            case "RELATION_LIST": {
                String kind=text(p,"kind",20,false);
                String predicate=switch(kind) {
                    case "INCOMING"->"addressee_id=? AND status='PENDING'";
                    case "OUTGOING"->"requester_id=? AND status='PENDING'";
                    case "FRIENDS"->"(low_user_id=? OR high_user_id=?) AND status='ACCEPTED'";
                    default->throw error("INVALID_REQUEST");
                }
                ;
                Map<String,Object> result=page(c,a,t+kind,p,"SELECT * FROM relationships WHERE "+predicate+" AND id<? ORDER BY id DESC",kind.equals("FRIENDS")?List.of(a,a):List.of(a));
                for(Object raw:(List<?>)result.get("items")) {
                    Map<String,Object> relation=(Map<String,Object>)raw;
                    long other=lng(relation,"lowUserId")==a?lng(relation,"highUserId"):lng(relation,"lowUserId");
                    Map<String,Object> u=current(c,other);
                    relation.put("userId",other);
                    relation.put("displayName",u.get("displayName"));
                    relation.put("username",u.get("username"));
                    relation.put("avatarMediaId",u.get("avatarMediaId"));
                    relation.put("relationId",relation.get("id"));
                    if(kind.equals("FRIENDS")) {
                        relation.put("online",presence.test(other));
                        relation.put("asOf",now());
                    }
                }
                return result;
            }
            case "BLOCK_SET": {
                long b=number(p,"targetUserId");
                if(a==b)throw error("INVALID_REQUEST");
                one(c,"SELECT id FROM users WHERE id=?",b);
                boolean value=bool(p,"blocked");
                if(value) {
                    update(c,"INSERT OR IGNORE INTO blocks VALUES(?,?,?)",a,b,now());
                    update(c,"UPDATE relationships SET status=CASE status WHEN 'PENDING' THEN 'CANCELLED' ELSE 'REMOVED' END,version=version+1,resolved_at=? WHERE low_user_id=? AND high_user_id=? AND status IN('PENDING','ACCEPTED')",now(),Math.min(a,b),Math.max(a,b));
                }
                else update(c,"DELETE FROM blocks WHERE blocker_id=? AND blocked_id=?",a,b);
                relationsChanged(c,a,b,0,1);
                return map("targetUserId",b,"blocked",value);
            }
            case "BLOCK_LIST":return page(c,a,t,p,"SELECT u.id,u.username,p.display_name FROM blocks b JOIN users u ON u.id=b.blocked_id JOIN profiles p ON p.user_id=u.id WHERE b.blocker_id=? AND u.id<? ORDER BY u.id DESC",List.of(a));
            case "POST_CREATE": {
                String content=p.containsKey("content")?text(p,"content",5000,true):"";
                String visibility=visibility(p);
                List<String> media=mediaIds(p);
                if(content.isEmpty()&&media.isEmpty())throw error("INVALID_REQUEST");
                long id=insert(c,"INSERT INTO posts(author_id,content,visibility,created_at,updated_at) VALUES(?,?,?,?,?)",a,content,visibility,now(),now());
                for(int i=0;i<media.size();i++)attach(c,a,media.get(i),"POST_IMAGE","post_id",id,i);
                event(c,"POST_INVALIDATED","POST",id,1,publicAudience(c));
                return map("postId",id,"version",1);
            }
            case "POST_GET":return postDetails(c,a,number(p,"postId"));
            case "POST_UPDATE":case "POST_DELETE": {
                long id=number(p,"postId");
                Map<String,Object> r=post(c,a,id);
                if(lng(r,"authorId")!=a)throw error("ACTION_FORBIDDEN");
                version(r,p);
                if(t.equals("POST_DELETE"))update(c,"UPDATE posts SET status='DELETED',version=version+1,deleted_at=?,updated_at=? WHERE id=?",now(),now(),id);
                else {
                    String content=text(p,"content",5000,true);
                    if(content.isEmpty()&&rows(c,"SELECT 1 FROM media_bindings WHERE post_id=?",id).isEmpty())throw error("INVALID_REQUEST");
                    update(c,"UPDATE posts SET content=?,visibility=?,version=version+1,updated_at=? WHERE id=?",content,visibility(p),now(),id);
                }
                event(c,"POST_INVALIDATED","POST",id,lng(r,"version")+1,publicAudience(c));
                return map("postId",id,"version",lng(r,"version")+1);
            }
            case "FEED_GET":case "PROFILE_POSTS_GET": {
                long author=t.equals("PROFILE_POSTS_GET")?number(p,"authorId"):0;
                String scope=t+author;
                Map<String,Object> result=orderedPage(c,a,scope,p,"SELECT p.* FROM posts p JOIN users u ON u.id=p.author_id WHERE "+(author==a?"("+postPolicy(a)+" OR(p.author_id="+a+" AND p.status='HIDDEN'))":postPolicy(a))+(author!=0?" AND p.author_id="+author:""),"p.created_at","createdAt","p.id",List.of());
                List<Map<String,Object>> enhanced=new ArrayList<>();
                for(Object obj:(List<?>)result.get("items"))enhanced.add(postDetails(c,a,lng((Map<String,Object>)obj,"id")));
                result.put("items",enhanced);
                return result;
            }
            case "COMMENT_CREATE": {
                long id=number(p,"postId");
                Map<String,Object> post=post(c,a,id);
                long comment=insert(c,"INSERT INTO comments(post_id,author_id,content,created_at) VALUES(?,?,?,?)",id,a,text(p,"content",1000,false),now());
                notify(c,lng(post,"authorId"),a,"COMMENT","comment:"+comment,"comment_id",comment,0);
                event(c,"COMMENT_INVALIDATED","POST",id,lng(post,"version"),publicAudience(c));
                return map("commentId",comment);
            }
            case "COMMENT_DELETE": {
                long id=number(p,"commentId");
                Map<String,Object> comment=one(c,"SELECT * FROM comments WHERE id=?",id),post=post(c,a,lng(comment,"postId"));
                if(a!=lng(comment,"authorId")&&a!=lng(post,"authorId"))throw error("ACTION_FORBIDDEN");
                update(c,"UPDATE comments SET status='DELETED',deleted_at=? WHERE id=?",now(),id);
                event(c,"COMMENT_INVALIDATED","POST",lng(comment,"postId"),lng(post,"version"),publicAudience(c));
                return map("commentId",id,"status","DELETED");
            }
            case "COMMENT_LIST": {
                long id=number(p,"postId");
                post(c,a,id);
                Map<String,Object> result=ascendingComments(c,a,id,p);
                for(Object obj:(List<?>)result.get("items")) {
                    Map<String,Object> x=(Map<String,Object>)obj;
                    if(!"ACTIVE".equals(x.get("status"))||blocked(c,a,lng(x,"authorId"))||!"ACTIVE".equals(current(c,lng(x,"authorId")).get("status"))) {
                        x.remove("content");
                        x.remove("authorId");
                        x.put("tombstone",true);
                    } else { x.put("authorName",current(c,lng(x,"authorId")).get("displayName")); }
                }
                return result;
            }
            case "LIKE_SET": {
                long id=number(p,"postId");
                Map<String,Object> r=post(c,a,id);
                boolean liked=bool(p,"liked");
                if(liked) {
                    update(c,"INSERT OR IGNORE INTO post_likes VALUES(?,?,?)",id,a,now());
                    notify(c,lng(r,"authorId"),a,"LIKE","like:"+id+":"+a+":"+(now()/86400000),"post_id",id,0);
                }
                else update(c,"DELETE FROM post_likes WHERE post_id=? AND user_id=?",id,a);
                Map<String,Object> updated=post(c,a,id);
                event(c,"LIKE_INVALIDATED","POST",id,lng(updated,"version"),publicAudience(c));
                return map("liked",liked,"count",updated.get("likeCount"));
            }
            default:return chatDispatch(c,a,t,p);
        }
    }
    Map<String,Object> relation(Map<String,Object> r) {
        r.put("relationId",r.get("id"));
        return r;
    }
    void relationsChanged(Connection c,long a,long b,long id,long version)throws Exception {
        update(c,"UPDATE users SET relationships_version=relationships_version+1 WHERE id IN(?,?)",a,b);
        event(c,"RELATION_CHANGED","RELATION",id,version,List.of(a,b));
    }
    String visibility(Map<String,Object> p)throws Exception {
        String v=p.containsKey("visibility")?text(p,"visibility",20,false):"FRIENDS";
        if(!Set.of("PUBLIC","FRIENDS","ONLY_ME").contains(v))throw error("INVALID_REQUEST");
        return v;
    }
    List<String> mediaIds(Map<String,Object> p)throws Exception {
        Object raw=p.getOrDefault("mediaIds",List.of());
        if(!(raw instanceof List<?> list)||list.size()>4)throw error("INVALID_REQUEST");
        List<String> result=new ArrayList<>();
        for(Object o:list)if(o instanceof String s&&s.length()<100&&!result.contains(s))result.add(s);
        else throw error("INVALID_REQUEST");
        return result;
    }
    String cursor(long actor,String scope,long anchor)throws Exception {
        String body=actor+"|"+hash(scope)+"|"+anchor;
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(cursorSecret,"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString((body+"|"+signature).getBytes(StandardCharsets.UTF_8));
    }
    long anchor(long actor,String scope,Object cursor)throws Exception {
        if(cursor==null)return Long.MAX_VALUE;
        try {
            String decoded=new String(Base64.getUrlDecoder().decode((String)cursor),StandardCharsets.UTF_8);
            String[] bits=decoded.split("\\|");
            long n=Long.parseLong(bits[2]);
            if(!cursor(actor,scope,n).equals(cursor))throw error("INVALID_REQUEST");
            return n;
        }
        catch(Exception e) {
            throw error("INVALID_REQUEST");
        }
    }
    int limit(Map<String,Object> p,int max)throws Exception {
        long n=p.containsKey("limit")?number(p,"limit"):20;
        if(n<1||n>max)throw error("INVALID_REQUEST");
        return (int)n;
    }
    Map<String,Object> page(Connection c,long actor,String scope,Map<String,Object> p,String sql,List<?> params)throws Exception {
        int limit=limit(p,50);
        List<Object> args=new ArrayList<>(params);
        args.add(anchor(actor,scope,p.get("cursor")));
        args.add(limit+1);
        List<Map<String,Object>> list=rows(c,sql+" LIMIT ?",args.toArray());
        boolean more=list.size()>limit;
        if(more)list.remove(list.size()-1);
        return map("items",list,"nextCursor",more?cursor(actor,scope,lng(list.get(list.size()-1),"id")):null,"hasMore",more);
    }
    Map<String,Object> member(Connection c,long a,long id)throws Exception {
        return one(c,"SELECT m.*,v.kind,v.status,v.last_seq,v.version,v.owner_id FROM conversation_members m JOIN conversations v ON v.id=m.conversation_id WHERE m.user_id=? AND m.conversation_id=? AND m.left_at IS NULL AND v.status='ACTIVE'",a,id);
    }
    void sendPolicy(Connection c,long a,long id)throws Exception {
        Map<String,Object> m=member(c,a,id);
        if("DIRECT".equals(m.get("kind"))) {
            Map<String,Object> other=one(c,"SELECT user_id FROM conversation_members WHERE conversation_id=? AND user_id<>?",id,a);
            friendship(c,a,lng(other,"userId"));
        }
        else if(lng(one(c,"SELECT count(*) n FROM conversation_members m JOIN users u ON u.id=m.user_id WHERE m.conversation_id=? AND m.left_at IS NULL AND u.status='ACTIVE'",id),"n")<2)throw error("INVALID_STATE");
    }
    public boolean canSendToConversation(long actor,long conversation)throws Exception {
        return db.read(c-> {
            try {
                active(c,actor);sendPolicy(c,actor,conversation);return true;
            }
            catch(SocialException e) {
                return false;
            }
        }
        );
    }
    public List<Long> activeRecipientIds(long actor,long conversation)throws Exception {
        return db.read(c-> {
            member(c,actor,conversation);List<Long> ids=new ArrayList<>();for(Map<String,Object> r:rows(c,"SELECT m.user_id FROM conversation_members m JOIN users u ON u.id=m.user_id WHERE m.conversation_id=? AND m.left_at IS NULL AND u.status='ACTIVE'",conversation))ids.add(lng(r,"userId"));return ids;
        }
        );
    }
    List<Long> recipients(Connection c,long id)throws Exception {
        return rows(c,"SELECT user_id FROM conversation_members WHERE conversation_id=? AND left_at IS NULL",id).stream().map(r->lng(r,"userId")).toList();
    }
    Map<String,Object> message(Connection c,long a,long id)throws Exception {
        Map<String,Object> r=one(c,"SELECT x.*,(SELECT media_id FROM media_bindings WHERE message_id=x.id) AS media_id FROM messages x WHERE x.id=?",id);
        Map<String,Object> m=member(c,a,lng(r,"conversationId"));
        if(lng(r,"conversationSeq")<lng(m,"visibleFromSeq"))throw error("NOT_FOUND");
        r.put("messageId",id);
        r.put("status","STORED");
        receipts(c,r);
        return r;
    }
    Map<String,Object> chatDispatch(Connection c,long a,String t,Map<String,Object> p)throws Exception {
        switch(t) {
            case "CONVERSATION_DIRECT_GET_OR_CREATE": {
                long b=number(p,"targetUserId");
                friendship(c,a,b);
                List<Map<String,Object>> existing=rows(c,"SELECT * FROM conversations WHERE kind='DIRECT' AND direct_low_id=? AND direct_high_id=?",Math.min(a,b),Math.max(a,b));
                long id;
                if(existing.isEmpty()) {
                    id=insert(c,"INSERT INTO conversations(kind,direct_low_id,direct_high_id,created_at,last_activity_at) VALUES('DIRECT',?,?,?,?)",Math.min(a,b),Math.max(a,b),now(),now());
                    join(c,id,a,"MEMBER",1);
                    join(c,id,b,"MEMBER",1);
                }
                else id=lng(existing.get(0),"id");
                return map("conversationId",id);
            }
            case "CONVERSATION_LIST":return conversations(c,a,p);
            case "MESSAGE_SEND": {
                long id=number(p,"conversationId");
                String client=text(p,"clientMessageId",100,false);
                try {
                    UUID.fromString(client);
                }
                catch(Exception e) {
                    throw error("INVALID_REQUEST");
                }
                String content=p.containsKey("content")?text(p,"content",4000,true):"";
                String media=p.get("mediaId")==null?null:text(p,"mediaId",100,false);
                if(content.isEmpty()&&media==null)throw error("INVALID_REQUEST");
                String fingerprint=hash(map("conversationId",id,"content",content,"mediaId",media));
                List<Map<String,Object>> old=rows(c,"SELECT * FROM messages WHERE sender_id=? AND client_message_id=?",a,client);
                if(!old.isEmpty()) {
                    if(!fingerprint.equals(old.get(0).get("payloadHash")))throw error("IDEMPOTENCY_CONFLICT");
                    Map<String,Object> r=old.get(0);
                    return map("messageId",r.get("id"),"conversationSeq",r.get("conversationSeq"),"status","STORED");
                }
                sendPolicy(c,a,id);
                update(c,"UPDATE conversations SET last_seq=last_seq+1,last_activity_at=? WHERE id=?",now(),id);
                long seq=lng(one(c,"SELECT last_seq FROM conversations WHERE id=?",id),"lastSeq");
                long mid=insert(c,"INSERT INTO messages(conversation_id,conversation_seq,sender_id,client_message_id,payload_hash,kind,content,created_at) VALUES(?,?,?,?,?,?,?,?)",id,seq,a,client,fingerprint,media==null?"TEXT":"FILE",content,now());
                if(media!=null)attach(c,a,media,"CHAT_FILE","message_id",mid,0);
                List<Long> audience=recipients(c,id);
                for(long recipient:audience)notify(c,recipient,a,"MESSAGE","message:"+id,"conversation_id",id,seq);
                event(c,"MESSAGE_CREATED","MESSAGE",mid,seq,audience);
                return map("messageId",mid,"conversationSeq",seq,"status","STORED");
            }
            case "MESSAGE_LOOKUP": {
                String client=text(p,"clientMessageId",100,false);
                Map<String,Object> r=one(c,"SELECT id FROM messages WHERE sender_id=? AND client_message_id=?",a,client);
                return message(c,a,lng(r,"id"));
            }
            case "MESSAGE_HISTORY": {
                long id=number(p,"conversationId");
                Map<String,Object> m=member(c,a,id);
                if(p.containsKey("afterSeq")&&p.containsKey("beforeSeq"))throw error("INVALID_REQUEST");
                int limit=limit(p,100);
                boolean after=p.containsKey("afterSeq");
                long bound=after?number(p,"afterSeq"):p.containsKey("beforeSeq")?number(p,"beforeSeq"):Long.MAX_VALUE;
                List<Map<String,Object>> list=rows(c,"SELECT x.*,(SELECT media_id FROM media_bindings WHERE message_id=x.id) AS media_id FROM messages x WHERE x.conversation_id=? AND x.conversation_seq>=? AND x.conversation_seq"+(after?">":"<")+"? ORDER BY x.conversation_seq "+(after?"ASC":"DESC")+" LIMIT ?",id,lng(m,"visibleFromSeq"),bound,limit+1);
                boolean more=list.size()>limit;
                if(more)list.remove(list.size()-1);
                for(Map<String,Object> x:list)receipts(c,x);
                return map("items",list,"hasMore",more,"latestSeq",m.get("lastSeq"),"visibleFromSeq",m.get("visibleFromSeq"),"nextSeq",list.isEmpty()?null:list.get(list.size()-1).get("conversationSeq"));
            }
            case "MESSAGE_READ":case "MESSAGE_DELIVERED": {
                long id=number(p,"conversationId"),seq=number(p,"throughSeq");
                Map<String,Object> m=member(c,a,id);
                if(seq>lng(m,"lastSeq")||seq<lng(m,"visibleFromSeq")-1)throw error("INVALID_REQUEST");
                if(seq>0) {
                    List<Map<String,Object>> target=rows(c,"SELECT sender_id FROM messages WHERE conversation_id=? AND conversation_seq=?",id,seq);
                    if(target.isEmpty())throw error("INVALID_REQUEST");
                }
                update(c,"UPDATE conversation_members SET last_delivered_seq=max(last_delivered_seq,?)"+(t.equals("MESSAGE_READ")?",last_read_seq=max(last_read_seq,?)":"")+" WHERE id=?",t.equals("MESSAGE_READ")?new Object[] {
                    seq,seq,m.get("id")
                }
                :new Object[] {
                    seq,m.get("id")
                }
                );
                event(c,"RECEIPT_UPDATED","CONVERSATION",id,seq,recipients(c,id));
                return map("conversationId",id,"throughSeq",seq);
            }
            case "GROUP_CREATE": {
                String title=text(p,"title",60,false);
                long id=insert(c,"INSERT INTO conversations(kind,title,owner_id,created_at,last_activity_at) VALUES('GROUP',?,?,?,?)",title,a,now(),now());
                join(c,id,a,"OWNER",1);
                return map("conversationId",id,"title",title,"version",1);
            }
            case "GROUP_INVITE": {
                long id=number(p,"conversationId"),b=number(p,"targetUserId");
                owner(c,a,id);
                friendship(c,a,b);
                if(!rows(c,"SELECT 1 FROM conversation_members WHERE conversation_id=? AND user_id=? AND left_at IS NULL",id,b).isEmpty())throw error("INVALID_STATE");
                update(c,"UPDATE group_invites SET status='EXPIRED' WHERE conversation_id=? AND expires_at<? AND status='PENDING'",id,now());
                List<Map<String,Object>> old=rows(c,"SELECT * FROM group_invites WHERE conversation_id=? AND invitee_id=? AND status='PENDING'",id,b);
                if(!old.isEmpty())return map("inviteId",old.get(0).get("id"),"expiresAt",old.get(0).get("expiresAt"));
                if(lng(one(c,"SELECT count(*) n FROM conversation_members WHERE conversation_id=? AND left_at IS NULL",id),"n")>=20||lng(one(c,"SELECT count(*) n FROM group_invites WHERE conversation_id=? AND status='PENDING'",id),"n")>=19)throw error("QUOTA_EXCEEDED");
                long expiry=now()+604800000;
                long invite=insert(c,"INSERT INTO group_invites(conversation_id,inviter_id,invitee_id,status,expires_at,created_at) VALUES(?,?,?,'PENDING',?,?)",id,a,b,expiry,now());
                notify(c,b,a,"GROUP_INVITE","invite:"+invite,"group_invite_id",invite,0);
                return map("inviteId",invite,"expiresAt",expiry);
            }
            case "GROUP_INVITE_LIST": {
                update(c,"UPDATE group_invites SET status='EXPIRED' WHERE status='PENDING' AND expires_at<=? AND(invitee_id=? OR conversation_id IN(SELECT id FROM conversations WHERE owner_id=?))",now(),a,a);
                return page(c,a,t,p,"SELECT i.*,v.title FROM group_invites i JOIN conversations v ON v.id=i.conversation_id WHERE(i.invitee_id=? OR v.owner_id=?) AND i.id<? ORDER BY i.id DESC",List.of(a,a));
            }
            case "GROUP_INVITE_ACCEPT":case "GROUP_INVITE_REJECT":case "GROUP_INVITE_CANCEL": {
                long id=number(p,"inviteId");
                Map<String,Object> i=one(c,"SELECT * FROM group_invites WHERE id=?",id);
                long group=lng(i,"conversationId");
                if(t.equals("GROUP_INVITE_CANCEL"))owner(c,a,group);
                else if(lng(i,"inviteeId")!=a)throw error("NOT_FOUND");
                if(!"PENDING".equals(i.get("status"))||lng(i,"expiresAt")<=now())throw error("INVALID_STATE");
                String next=t.equals("GROUP_INVITE_ACCEPT")?"ACCEPTED":t.equals("GROUP_INVITE_REJECT")?"REJECTED":"CANCELLED";
                if(next.equals("ACCEPTED")) {
                    Map<String,Object> v=one(c,"SELECT * FROM conversations WHERE id=? AND status='ACTIVE'",group);
                    friendship(c,a,lng(v,"ownerId"));
                    if(lng(one(c,"SELECT count(*) n FROM conversation_members WHERE conversation_id=? AND left_at IS NULL",group),"n")>=20)throw error("QUOTA_EXCEEDED");
                    join(c,group,a,"MEMBER",lng(v,"lastSeq")+1);
                    update(c,"UPDATE conversations SET version=version+1 WHERE id=?",group);
                }
                update(c,"UPDATE group_invites SET status=? WHERE id=?",next,id);
                event(c,"GROUP_INVITE_CHANGED","GROUP_INVITE",id,1,List.of(a,lng(i,"inviterId")));
                return map("inviteId",id,"status",next,"conversationId",group);
            }
            case "GROUP_MEMBERS_GET": {
                long id=number(p,"conversationId");
                member(c,a,id);
                return page(c,a,t+id,p,"SELECT m.id,m.user_id,m.role,p.display_name FROM conversation_members m JOIN profiles p ON p.user_id=m.user_id WHERE m.conversation_id=? AND m.left_at IS NULL AND m.id<? ORDER BY m.id DESC",List.of(id));
            }
            case "GROUP_RENAME":case "GROUP_KICK":case "GROUP_OWNER_TRANSFER":case "GROUP_LEAVE":case "GROUP_DISBAND": {
                long id=number(p,"conversationId");
                Map<String,Object> v=one(c,"SELECT * FROM conversations WHERE id=? AND kind='GROUP' AND status='ACTIVE'",id);
                member(c,a,id);
                version(v,p);
                List<Long> audience=recipients(c,id);
                if(!t.equals("GROUP_LEAVE"))owner(c,a,id);
                switch(t) {
                    case "GROUP_RENAME"->update(c,"UPDATE conversations SET title=? WHERE id=?",text(p,"title",60,false),id);
                    case "GROUP_KICK"-> {
                        long target=number(p,"userId");
                        if(target==a)throw error("INVALID_REQUEST");
                        member(c,target,id);
                        update(c,"UPDATE conversation_members SET left_at=? WHERE conversation_id=? AND user_id=? AND left_at IS NULL",now(),id,target);
                    }
                    case "GROUP_OWNER_TRANSFER"->transferOwner(c,id,a,number(p,"userId"));
                    case "GROUP_LEAVE"-> {
                        if(lng(v,"ownerId")==a) {
                            if(audience.size()>1)throw error("INVALID_STATE");
                            closeGroup(c,id);
                        }
                        else update(c,"UPDATE conversation_members SET left_at=? WHERE conversation_id=? AND user_id=? AND left_at IS NULL",now(),id,a);
                    }
                    case "GROUP_DISBAND"->closeGroup(c,id);
                }
                update(c,"UPDATE conversations SET version=version+1 WHERE id=?",id);
                event(c,"CONVERSATION_INVALIDATED","CONVERSATION",id,lng(v,"version")+1,audience);
                return map("conversationId",id,"version",lng(v,"version")+1);
            }
            default:return moderationDispatch(c,a,t,p);
        }
    }
    void join(Connection c,long group,long user,String role,long seq)throws Exception {
        update(c,"INSERT INTO conversation_members(conversation_id,user_id,role,joined_at,visible_from_seq,last_delivered_seq,last_read_seq) VALUES(?,?,?,?,?,?,?)",group,user,role,now(),seq,seq-1,seq-1);
    }
    Map<String,Object> owner(Connection c,long actor,long group)throws Exception {
        Map<String,Object> m=member(c,actor,group);
        if(!"GROUP".equals(m.get("kind"))||!"OWNER".equals(m.get("role")))throw error("ACTION_FORBIDDEN");
        return m;
    }
    void closeGroup(Connection c,long id)throws Exception {
        update(c,"UPDATE conversations SET status='CLOSED' WHERE id=?",id);
        update(c,"UPDATE conversation_members SET left_at=? WHERE conversation_id=? AND left_at IS NULL",now(),id);
        update(c,"UPDATE group_invites SET status='CANCELLED' WHERE conversation_id=? AND status='PENDING'",id);
    }
    void transferOwner(Connection c,long id,long previous,long target)throws Exception {
        if(previous==target)throw error("INVALID_REQUEST");
        active(c,target);
        member(c,target,id);
        update(c,"UPDATE conversation_members SET role='MEMBER' WHERE conversation_id=? AND user_id=? AND left_at IS NULL",id,previous);
        update(c,"UPDATE conversation_members SET role='OWNER' WHERE conversation_id=? AND user_id=? AND left_at IS NULL",id,target);
        update(c,"UPDATE conversations SET owner_id=? WHERE id=?",target,id);
    }
    Map<String,Object> conversations(Connection c,long a,Map<String,Object> p)throws Exception {
        Map<String,Object> result=orderedPage(c,a,"CONVERSATION_LIST",p,"SELECT v.*,m.role,m.visible_from_seq,m.last_read_seq,m.last_delivered_seq,(SELECT count(*) FROM messages unread_message WHERE unread_message.conversation_id=v.id AND unread_message.sender_id<>m.user_id AND unread_message.conversation_seq>m.last_read_seq AND unread_message.conversation_seq>=m.visible_from_seq) AS unread FROM conversations v JOIN conversation_members m ON m.conversation_id=v.id WHERE m.user_id=? AND m.left_at IS NULL AND v.status='ACTIVE'","v.last_activity_at","lastActivityAt","v.id",List.of(a));
        for(Object raw:(List<?>)result.get("items")) {
            Map<String,Object> v=(Map<String,Object>)raw;
            long id=lng(v,"id");
            v.put("conversationId",id);
            try {
                sendPolicy(c,a,id);
                v.put("canSend",true);
            }
            catch(SocialException e) {
                v.put("canSend",false);
            }
            if("DIRECT".equals(v.get("kind"))) {
                long other=lng(v,"directLowId")==a?lng(v,"directHighId"):lng(v,"directLowId");
                Map<String,Object> u=current(c,other);
                v.put("otherUserId",other);
                v.put("title",u.get("displayName"));
                v.put("otherUsername",u.get("username"));
            }
        }
        return result;
    }
    void admin(Connection c,long actor)throws Exception {
        if(!"ADMIN".equals(current(c,actor).get("role")))throw error("ACTION_FORBIDDEN");
    }
    void audit(Connection c,long admin,Long report,String action,String target,long id,String reason,Object before,Object after)throws Exception {
        update(c,"INSERT INTO audit_logs(admin_id,report_id,action,target_type,target_id,reason,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",admin,report,action,target,id,reason,json.writeValueAsString(before),json.writeValueAsString(after),now());
    }
    Map<String,Object> moderationDispatch(Connection c,long a,String t,Map<String,Object> p)throws Exception {
        if(t.startsWith("ADMIN_"))admin(c,a);
        switch(t) {
            case "NOTIFICATION_LIST":return notifications(c,a,p);
            case "NOTIFICATION_COUNT":return map("unreadCount",lng(one(c,"SELECT count(*) n FROM notifications WHERE recipient_id=? AND is_read=0",a),"n"));
            case "NOTIFICATION_READ": {
                Object raw=p.get("notificationIds");
                if(!(raw instanceof List<?> list)||list.size()>50)throw error("INVALID_REQUEST");
                for(Object id:list) {
                    if(!(id instanceof Number n))throw error("INVALID_REQUEST");
                    update(c,"UPDATE notifications SET is_read=1 WHERE recipient_id=? AND id=?",a,n.longValue());
                }
                return map("unreadCount",lng(one(c,"SELECT count(*) n FROM notifications WHERE recipient_id=? AND is_read=0",a),"n"));
            }
            case "NOTIFICATION_READ_THROUGH": {
                long bound=anchor(a,"NOTIFICATION_THROUGH",p.get("throughCursor"));
                if(bound==Long.MAX_VALUE)throw error("INVALID_REQUEST");
                update(c,"UPDATE notifications SET is_read=1 WHERE recipient_id=? AND id<=?",a,bound);
                return map("read",true);
            }
            case "STATE_SYNC": {
                Map<String,Object> result=map("currentProfileVersion",current(c,a).get("version"),"relationshipsVersion",current(c,a).get("relationshipsVersion"));
                if(!Boolean.FALSE.equals(p.get("includeConversations")))result.put("conversations",conversations(c,a,map("cursor",p.get("conversationsCursor"),"limit",p.getOrDefault("limit",20))));
                if(!Boolean.FALSE.equals(p.get("includeNotifications")))result.put("notifications",notifications(c,a,map("cursor",p.get("notificationsCursor"),"limit",p.getOrDefault("limit",20))));
                return result;
            }
            case "REPORT_CREATE": {
                String target=text(p,"targetType",20,false);
                long id=number(p,"targetId");
                Map<String,Object> snapshot;
                switch(target) {
                    case "USER"-> {
                        snapshot=current(c,id);
                        if(!"ACTIVE".equals(snapshot.get("status"))||blocked(c,a,id))throw error("NOT_FOUND");
                        snapshot=map("id",id,"username",snapshot.get("username"),"displayName",snapshot.get("displayName"),"bio",snapshot.get("bio"));
                    }
                    case "POST"->snapshot=post(c,a,id);
                    case "COMMENT"-> {
                        snapshot=one(c,"SELECT * FROM comments WHERE id=? AND status='ACTIVE'",id);
                        post(c,a,lng(snapshot,"postId"));
                        if(blocked(c,a,lng(snapshot,"authorId")))throw error("NOT_FOUND");
                    }
                    default->throw error("INVALID_REQUEST");
                }
                String reason=text(p,"reason",60,false);
                if(!Set.of("SPAM","HARASSMENT","HATE_SPEECH","INAPPROPRIATE","IMPERSONATION","OTHER").contains(reason))throw error("INVALID_REQUEST");
                List<Map<String,Object>> old=rows(c,"SELECT id FROM reports WHERE reporter_id=? AND target_type=? AND target_id=? AND status IN('OPEN','IN_REVIEW')",a,target,id);
                if(!old.isEmpty())return map("reportId",old.get(0).get("id"));
                long report=insert(c,"INSERT INTO reports(reporter_id,target_type,target_id,reason,description,snapshot_json,created_at) VALUES(?,?,?,?,?,?,?)",a,target,id,reason,p.containsKey("description")?text(p,"description",1000,true):"",json.writeValueAsString(snapshot),now());
                return map("reportId",report);
            }
            case "ADMIN_REPORT_LIST": {
                String filter=p.containsKey("filter")?text(p,"filter",30,true):"";
                if(!filter.isEmpty()&&!Set.of("OPEN","IN_REVIEW","RESOLVED","DISMISSED").contains(filter))throw error("INVALID_REQUEST");
                return page(c,a,t+filter,p,"SELECT id,reporter_id,target_type,target_id,reason,status,version,assigned_admin_id,created_at FROM reports WHERE "+(filter.isEmpty()?"1=1":"status='"+filter+"'")+" AND id<? ORDER BY id DESC",List.of());
            }
            case "ADMIN_REPORT_GET": {
                long id=number(p,"reportId");
                Map<String,Object> r=one(c,"SELECT * FROM reports WHERE id=?",id);
                audit(c,a,id,"VIEW_REPORT","REPORT",id,"Report review",Map.of(),Map.of());
                return r;
            }
            case "ADMIN_REPORT_CLAIM": {
                long id=number(p,"reportId");
                Map<String,Object> r=one(c,"SELECT * FROM reports WHERE id=?",id);
                version(r,p);
                if(!"OPEN".equals(r.get("status")))throw error("INVALID_STATE");
                update(c,"UPDATE reports SET status='IN_REVIEW',assigned_admin_id=?,version=version+1 WHERE id=?",a,id);
                audit(c,a,id,"CLAIM_REPORT","REPORT",id,"Claim",map("status","OPEN"),map("status","IN_REVIEW"));
                return map("reportId",id,"status","IN_REVIEW","version",lng(r,"version")+1);
            }
            case "ADMIN_REPORT_RESOLVE": {
                long id=number(p,"reportId");
                Map<String,Object> r=one(c,"SELECT * FROM reports WHERE id=?",id);
                version(r,p);
                if(!"IN_REVIEW".equals(r.get("status"))||lng(r,"assignedAdminId")!=a)throw error("INVALID_STATE");
                String action=text(p,"action",40,false),reason=text(p,"reason",1000,false),target=(String)r.get("targetType");
                long targetId=lng(r,"targetId");
                String targetTable=target.equals("USER")?"users":target.equals("POST")?"posts":"comments";
                Map<String,Object> beforeState=one(c,"SELECT id,status FROM "+targetTable+" WHERE id=?",targetId);
                switch(action) {
                    case "HIDE_POST"-> {
                        if(!target.equals("POST"))throw error("INVALID_REQUEST");
                        update(c,"UPDATE posts SET status='HIDDEN',version=version+1,updated_at=? WHERE id=?",now(),targetId);
                        event(c,"POST_INVALIDATED","POST",targetId,0,List.of(lng(one(c,"SELECT author_id FROM posts WHERE id=?",targetId),"authorId")));
                    }
                    case "HIDE_COMMENT"-> {
                        if(!target.equals("COMMENT"))throw error("INVALID_REQUEST");
                        update(c,"UPDATE comments SET status='HIDDEN' WHERE id=?",targetId);
                    }
                    case "SUSPEND_USER"-> {
                        if(!target.equals("USER"))throw error("INVALID_REQUEST");
                        Map<String,Object> u=current(c,targetId);
                        if("ADMIN".equals(u.get("role"))&&lng(one(c,"SELECT count(*) n FROM users WHERE role='ADMIN' AND status='ACTIVE'"),"n")<=1)throw error("ACTION_FORBIDDEN");
                        update(c,"UPDATE users SET status='SUSPENDED',auth_version=auth_version+1 WHERE id=?",targetId);
                        event(c,"SESSION_REVOKED","USER",targetId,lng(u,"authVersion")+1,List.of(targetId));
                    }
                    case "NO_ACTION"-> {
                    }
                    default->throw error("INVALID_REQUEST");
                }
                String status=action.equals("NO_ACTION")?"DISMISSED":"RESOLVED";
                update(c,"UPDATE reports SET status=?,resolution=?,version=version+1,resolved_at=? WHERE id=?",status,action,now(),id);
                audit(c,a,id,action,target,targetId,reason,map("reportStatus",r.get("status"),"target",beforeState),map("reportStatus",status,"action",action,"target",one(c,"SELECT id,status FROM "+targetTable+" WHERE id=?",targetId)));
                notify(c,lng(r,"reporterId"),a,"REPORT_RESOLVED","report:"+id,"report_id",id,0);
                if(!action.equals("NO_ACTION")) {
                    long affected=target.equals("USER")?targetId:lng(one(c,"SELECT author_id FROM "+(target.equals("POST")?"posts":"comments")+" WHERE id=?",targetId),"authorId");
                    notify(c,affected,a,"MODERATION","moderation:"+id,target.equals("POST")?"post_id":target.equals("COMMENT")?"comment_id":"target_user_id",targetId,0);
                    update(c,"UPDATE notifications SET report_id=? WHERE recipient_id=? AND source_key=?",id,affected,"moderation:"+id);
                }
                return map("reportId",id,"status",status,"version",lng(r,"version")+1);
            }
            case "ADMIN_USER_UNSUSPEND": {
                long id=number(p,"targetUserId");
                Map<String,Object> before=current(c,id);
                String reason=text(p,"reason",1000,false);
                update(c,"UPDATE users SET status='ACTIVE',auth_version=auth_version+1 WHERE id=?",id);
                audit(c,a,null,"UNSUSPEND_USER","USER",id,reason,map("status",before.get("status")),map("status","ACTIVE"));
                notify(c,id,a,"MODERATION","unsuspend:"+id+":"+now(),"target_user_id",id,0);
                return map("targetUserId",id,"status","ACTIVE");
            }
            case "ADMIN_GROUP_OWNER_TRANSFER":case "ADMIN_GROUP_CLOSE": {
                long id=number(p,"conversationId");
                Map<String,Object> v=one(c,"SELECT * FROM conversations WHERE id=? AND kind='GROUP' AND status='ACTIVE'",id);
                version(v,p);
                long old=lng(v,"ownerId");
                if(!"SUSPENDED".equals(current(c,old).get("status")))throw error("INVALID_STATE");
                List<Long> recipients=recipients(c,id);
                if(t.equals("ADMIN_GROUP_CLOSE"))closeGroup(c,id);
                else transferOwner(c,id,old,number(p,"targetUserId"));
                update(c,"UPDATE conversations SET version=version+1 WHERE id=?",id);
                audit(c,a,null,t,"CONVERSATION",id,text(p,"reason",1000,false),map("ownerId",old,"status",v.get("status")),map("status",t.equals("ADMIN_GROUP_CLOSE")?"CLOSED":"ACTIVE"));
                event(c,"CONVERSATION_INVALIDATED","CONVERSATION",id,lng(v,"version")+1,recipients);
                return map("conversationId",id,"version",lng(v,"version")+1);
            }
            case "ADMIN_AUDIT_LIST":{String filter=p.containsKey("filter")?text(p,"filter",60,true):"";if(!filter.isEmpty()&&!filter.matches("[A-Z_]{1,60}"))throw error("INVALID_REQUEST");return page(c,a,t+filter,p,"SELECT * FROM audit_logs WHERE "+(filter.isEmpty()?"1=1":"action=?")+" AND id<? ORDER BY id DESC",filter.isEmpty()?List.of():List.of(filter));}
            case "ADMIN_AUDIT_GET":return one(c,"SELECT * FROM audit_logs WHERE id=?",number(p,"auditId"));
            default:throw error("UNSUPPORTED_OPERATION");
        }
    }
    Map<String,Object> notifications(Connection c,long a,Map<String,Object> p)throws Exception {
        boolean unread=Boolean.TRUE.equals(p.get("unreadOnly"));
        Map<String,Object> result=page(c,a,"NOTIFICATION_LIST"+unread,p,"SELECT * FROM notifications WHERE recipient_id=? "+(unread?"AND is_read=0 ":"")+"AND id<? ORDER BY id DESC",List.of(a));
        for(Object raw:(List<?>)result.get("items")) {
            Map<String,Object> n=(Map<String,Object>)raw;
            boolean available=true;
            try {
                if(n.get("postId")!=null){if("MODERATION".equals(n.get("type")))postDetails(c,a,lng(n,"postId"));else post(c,a,lng(n,"postId"));}
                if(n.get("commentId")!=null) {
                    Map<String,Object> x=one(c,"SELECT * FROM comments WHERE id=? AND status='ACTIVE'",n.get("commentId"));
                    post(c,a,lng(x,"postId"));
                    n.put("postId",x.get("postId"));
                    if(blocked(c,a,lng(x,"authorId")))throw error("NOT_FOUND");
                }
                if(n.get("conversationId")!=null)member(c,a,lng(n,"conversationId"));
                if(n.get("actorId")!=null&&n.get("conversationId")==null&&(blocked(c,a,lng(n,"actorId"))||!"ACTIVE".equals(current(c,lng(n,"actorId")).get("status"))))throw error("NOT_FOUND");
            }
            catch(SocialException e) {
                available=false;
            }
            n.put("available",available);
            if(n.get("reportId")!=null){Map<String,Object> report=one(c,"SELECT resolution,description FROM reports WHERE id=?",n.get("reportId"));n.put("resolution",report.get("resolution"));List<Map<String,Object>> reason=rows(c,"SELECT reason FROM audit_logs WHERE report_id=? AND action<>'CLAIM_REPORT' AND action<>'VIEW_REPORT' ORDER BY id DESC LIMIT 1",n.get("reportId"));if(!reason.isEmpty())n.put("moderationReason",reason.get(0).get("reason"));}
            if(!available) {
                n.remove("actorId");
                n.remove("postId");
                n.remove("commentId");
                n.remove("relationId");
                n.remove("groupInviteId");
            }
        }
        long max=lng(one(c,"SELECT coalesce(max(id),0) n FROM notifications WHERE recipient_id=?",a),"n");
        result.put("throughCursor",cursor(a,"NOTIFICATION_THROUGH",max));
        return result;
    }
    public boolean canAccessMedia(long actor,String media)throws Exception {
        return db.read(c-> {
            try {
                active(c,actor);Map<String,Object> m=one(c,"SELECT * FROM media WHERE id=?",media);if("READY".equals(m.get("state")))return lng(m,"ownerId")==actor&&lng(m,"expiresAt")>now();if(!"ATTACHED".equals(m.get("state")))return false;Map<String,Object> b=one(c,"SELECT * FROM media_bindings WHERE media_id=?",media);if(b.get("postId")!=null) {
                    post(c,actor,lng(b,"postId"));return true;
                }
                if(b.get("messageId")!=null) {
                    message(c,actor,lng(b,"messageId"));return true;
                }
                long owner=lng(b,"avatarUserId");active(c,owner);return !blocked(c,actor,owner);
            }
            catch(SocialException e) {
                return false;
            }
        }
        );
    }
    public List<Map<String,Object>> pendingEvents(int limit)throws Exception {
        return db.read(c-> {
            List<Map<String,Object>> list=rows(c,"SELECT * FROM outbox_events WHERE dispatched_at IS NULL ORDER BY created_at,event_id LIMIT ?",Math.max(1,Math.min(100,limit)));for(Map<String,Object> r:list)r.put("audience",json.readValue((String)r.get("audienceJson"),new TypeReference<List<Long>>() {
            }
            ));return list;
        }
        );
    }
    public void markEventsDispatched(Collection<String> ids)throws Exception {
        db.write(c-> {
            for(String id:ids)update(c,"UPDATE outbox_events SET dispatched_at=?,attempts=attempts+1 WHERE event_id=?",now(),id);return null;
        }
        );
    }
    public Map<String,Object> eventPayload(long recipient,String type,long id,long revision)throws Exception {
        return db.read(c-> {
            if(type.equals("SESSION_REVOKED"))return map("userId",id,"authVersion",revision);active(c,recipient);if(type.equals("MESSAGE_CREATED"))return message(c,recipient,id);if(type.equals("RECEIPT_UPDATED")) {
                member(c,recipient,id);List<Map<String,Object>> receipts=rows(c,"SELECT user_id AS actor_id,last_delivered_seq AS delivered_through_seq,last_read_seq AS read_through_seq FROM conversation_members WHERE conversation_id=? AND left_at IS NULL",id);Map<String,Object> payload=map("conversationId",id,"receipts",receipts,"conversationSeq",revision);for(Map<String,Object> r:receipts)if(lng(r,"actorId")!=recipient) {
                    payload.putAll(r);break;
                }
                return payload;
            }
            String key=switch(type) {
                case "POST_INVALIDATED","COMMENT_INVALIDATED","LIKE_INVALIDATED"->"postId";case "RELATION_CHANGED"->"relationId";case "GROUP_INVITE_CHANGED"->"inviteId";case "CONVERSATION_INVALIDATED","RECEIPT_UPDATED"->"conversationId";case "PROFILE_INVALIDATED","SESSION_REVOKED"->"userId";default->"resourceId";
            }
            ;return map(key,id,"version",revision);
        }
        );
    }
    Map<String,Object> orderedPage(Connection c,long actor,String scope,Map<String,Object> p,String sql,String timeColumn,String timeKey,String idColumn,List<?> params)throws Exception {
        long time=Long.MAX_VALUE,id=Long.MAX_VALUE;
        Object raw=p.get("cursor");
        if(raw!=null) {
            try {
                Map<String,Object> decoded=json.readValue(Base64.getUrlDecoder().decode((String)raw),new TypeReference<Map<String,Object>>() {
                }
                );
                time=lng(decoded,"time");
                id=lng(decoded,"id");
                if(!cursor(actor,scope+":"+time,id).equals(decoded.get("signature")))throw error("INVALID_REQUEST");
            }
            catch(Exception e) {
                throw error("INVALID_REQUEST");
            }
        }
        int count=limit(p,50);
        List<Object> args=new ArrayList<>(params);
        args.add(time);
        args.add(time);
        args.add(id);
        args.add(count+1);
        List<Map<String,Object>> list=rows(c,sql+" AND ("+timeColumn+"<? OR("+timeColumn+"=? AND "+idColumn+"<?)) ORDER BY "+timeColumn+" DESC,"+idColumn+" DESC LIMIT ?",args.toArray());
        boolean more=list.size()>count;
        if(more)list.remove(list.size()-1);
        String next=null;
        if(more) {
            Map<String,Object> last=list.get(list.size()-1);
            long timestamp=lng(last,timeKey),lastId=lng(last,"id");
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(map("time",timestamp,"id",lastId,"signature",cursor(actor,scope+":"+timestamp,lastId))));
        }
        return map("items",list,"nextCursor",next,"hasMore",more);
    }
    Map<String,Object> ascendingComments(Connection c,long actor,long post,Map<String,Object> p)throws Exception {
        String scope="COMMENT_LIST"+post;
        long after=p.get("cursor")==null?0:anchor(actor,scope,p.get("cursor"));
        int count=limit(p,50);
        List<Map<String,Object>> items=rows(c,"SELECT * FROM comments WHERE post_id=? AND id>? ORDER BY created_at ASC,id ASC LIMIT ?",post,after,count+1);
        boolean more=items.size()>count;
        if(more)items.remove(items.size()-1);
        return map("items",items,"nextCursor",more?cursor(actor,scope,lng(items.get(items.size()-1),"id")):null,"hasMore",more);
    }
    List<Long> publicAudience(Connection c)throws Exception {
        return rows(c,"SELECT id FROM users WHERE status='ACTIVE'").stream().map(r->lng(r,"id")).toList();
    }
    void receipts(Connection c,Map<String,Object> message)throws Exception {
        long conv=lng(message,"conversationId"),seq=lng(message,"conversationSeq"),sender=lng(message,"senderId");
        message.put("senderName",current(c,sender).get("displayName"));
        List<Map<String,Object>> attachment=rows(c,"SELECT m.id,m.original_name,m.mime_type,m.actual_size,m.sha256 FROM media m JOIN media_bindings b ON b.media_id=m.id WHERE b.message_id=?",message.get("id"));
        if(!attachment.isEmpty()){Map<String,Object> file=attachment.get(0);message.put("media",file);message.put("fileName",file.get("originalName"));message.put("size",file.get("actualSize"));}
        Map<String,Object> counts=one(c,"SELECT count(*) AS recipient_count,sum(CASE WHEN last_delivered_seq>=? THEN 1 ELSE 0 END) AS delivered_by_count,sum(CASE WHEN last_read_seq>=? THEN 1 ELSE 0 END) AS read_by_count FROM conversation_members WHERE conversation_id=? AND user_id<>? AND left_at IS NULL AND visible_from_seq<=?",seq,seq,conv,sender,seq);
        message.putAll(counts);
        long read=counts.get("readByCount")==null?0:lng(counts,"readByCount"),delivered=counts.get("deliveredByCount")==null?0:lng(counts,"deliveredByCount");
        message.put("status",read>0?"READ":delivered>0?"DELIVERED":"STORED");
    }
    Map<String,Object> replay(Connection c,long actor,String type,Map<String,Object> request,Map<String,Object> result)throws Exception {
        boolean allowed=true;
        try {
            if(request.get("postId") instanceof Number id)post(c,actor,id.longValue());
            if(type.startsWith("GROUP_")&&result.get("conversationId") instanceof Number id)member(c,actor,id.longValue());
        }
        catch(SocialException e) {
            allowed=false;
        }
        if(!allowed) {
            for(String key:List.of("content","mediaIds","title","count","likeCount","commentCount"))result.remove(key);
            result.put("resourceUnavailable",true);
        }
        return result;
    }

    Map<String,Object> postDetails(Connection c,long actor,long id)throws Exception {
        try{return post(c,actor,id);}catch(SocialException e){if(!e.code().equals("NOT_FOUND"))throw e;}
        Map<String,Object> metadata=one(c,"SELECT id,id AS post_id,author_id,visibility,status,version,created_at,updated_at FROM posts WHERE id=? AND author_id=? AND status='HIDDEN'",id,actor);
        List<Map<String,Object>> audit=rows(c,"SELECT reason FROM audit_logs WHERE action='HIDE_POST' AND target_type='POST' AND target_id=? ORDER BY id DESC LIMIT 1",id);
        metadata.put("unavailable",true);metadata.put("authorName",current(c,actor).get("displayName"));if(!audit.isEmpty())metadata.put("moderationReason",audit.get(0).get("reason"));return metadata;
    }
}





