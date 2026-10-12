package com.p2p.server.social;
import com.p2p.common.social.*;
import com.p2p.server.network.social.UdpService;
import com.p2p.server.network.social.ServerMonitor;
import com.p2p.server.ops.ApplicationLock;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;
/** Central authenticated TCP runtime. Legacy MiniChat remains separately runnable. */
public final class SocialServer implements AutoCloseable, UdpService.Host {
    private static final Logger LOG = Logger.getLogger(SocialServer.class.getName());
    private final Config config;
    private final SocialDatabase db;
    private final SocialService service;
    private final MediaService media;
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Connection> users = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UdpService.Session> udpSessions = new ConcurrentHashMap<>();
    private final Semaphore permits;
    private final Semaphore authWorkers = new Semaphore(4);
    private final ExecutorService io, storage;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private final RateLimits limits = new RateLimits();
    private final ApplicationLock applicationLock;
    private ServerSocket listener;
    private UdpService udp;
    public record Config(Path databasePath, Path storageRoot, String bind, int tcpPort, int discoveryPort, int typingPort, int maxConnections, long userQuota, long serverQuota, boolean udpEnabled) {
        public static Config load(Path file) throws Exception {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            Path base = file.toAbsolutePath().getParent().resolve(p.getProperty("app.root", "../..")).normalize();
            boolean udp=Boolean.parseBoolean(p.getProperty("network.udp.enabled","false"));
            return new Config(base.resolve(p.getProperty("database.path", "data/social-network.db")), base.resolve(p.getProperty("storage.root", "data/media")), p.getProperty("server.bind", "0.0.0.0"), integer(p,"server.tcp.port",5000,1,65535),integer(p,"server.udp.discovery.port",5001,1,65535), integer(p,"server.udp.realtime.port",5002,1,65535),integer(p,"server.max.connections",50,1,100), longValue(p,"media.user.quota.bytes",200L*1024*1024),longValue(p,"media.server.quota.bytes",10L*1024*1024*1024),udp);
        }
        private static int integer(Properties p,String key,int fallback,int min,int max) {
            int v = Integer.parseInt(p.getProperty(key,String.valueOf(fallback)));
            if(v<min||v>max)throw new IllegalArgumentException("Invalid "+key);
            return v;
        }
        private static long longValue(Properties p,String key,long fallback) {
            long v=Long.parseLong(p.getProperty(key,String.valueOf(fallback)));
            if(v<=0)throw new IllegalArgumentException("Invalid "+key);
            return v;
        }
    }
    public SocialServer(Config config) throws Exception {
        this.config = config;
        applicationLock=ApplicationLock.acquire(config.databasePath().toAbsolutePath().getParent());
        permits = new Semaphore(config.maxConnections());
        io = new ThreadPoolExecutor(config.maxConnections()*2,config.maxConnections()*2,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxConnections()*2),new ThreadPoolExecutor.AbortPolicy());
        storage = new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128), r -> new Thread(r,"social-storage"),new ThreadPoolExecutor.AbortPolicy());
        SocialDatabase opened=null;
        try {
            db = opened = new SocialDatabase(config.databasePath());
            service = new SocialService(db);
            service.setPresenceProvider(id->users.containsKey(id));
            media = new MediaService(db,service,config.storageRoot(),config.userQuota(),config.serverQuota());
        }
        catch(Exception e) {
            if(opened!=null)opened.close();
            applicationLock.close();
            io.shutdownNow();
            storage.shutdownNow();
            scheduler.shutdownNow();
            throw e;
        }
    }
    public SocialService service() {
        return service;
    }
    public SocialDatabase database() {
        return db;
    }
    public int tcpPort() {
        return listener == null ? config.tcpPort() : listener.getLocalPort();
    }
    public int discoveryPort() {
        return udp == null ? (config.udpEnabled()?config.discoveryPort():0) : udp.getDiscoveryPort();
    }
    public int typingPort() {
        return udp == null ? (config.udpEnabled()?config.typingPort():0) : udp.getTypingPort();
    }
    public int connectionCount() {
        return connections.size();
    }
    public int authenticatedCount() {
        return users.size();
    }
    public boolean isClosed() {
        return closed.get();
    }
    public void start() throws Exception {
        if (!started.compareAndSet(false,true) || closed.get()) throw new IllegalStateException("Server already started/closed");
        try {
            listener=new ServerSocket();
            listener.bind(new InetSocketAddress(config.bind(),config.tcpPort()));
            if(config.udpEnabled()) {
                udp=new UdpService(this,"social-java-v2","VKU Social",tcpPort(),config.discoveryPort(),config.typingPort());
                udp.start();
            }
            scheduler.scheduleAtFixedRate(this::dispatchEvents,50,100,TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::monitor,1,1,TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(()-> {
                try {
                    media.cleanup();
                    for(Map<String,Object> failure:media.drainFailedDownloads()) {
                        Connection c=users.get(((Number)failure.get("userId")).longValue());
                        if(c!=null)safeSend(c,WireMessage.event("MEDIA_DOWNLOAD_FAILED",Map.of("transferId",failure.get("transferId"),"errorCode",failure.get("errorCode"))),Map.of());
                    }
                }
                catch(Exception e) {
                    LOG.log(Level.WARNING,"Media cleanup failed",e);
                }
            }
            ,5,5,TimeUnit.SECONDS);
            LOG.info("VKU Social TCP="+tcpPort()+" UDP="+(config.udpEnabled()?"enabled":"disabled")+" database="+config.databasePath().toAbsolutePath());
            while(!closed.get()) {
                try {
                    Socket socket=listener.accept();
                    if(!permits.tryAcquire()) {
                        socket.close();
                        continue;
                    }
                    Connection c=new Connection(socket);
                    connections.put(c.id,c);
                    try {
                        io.execute(c::read);
                    }
                    catch(RejectedExecutionException e) {
                        c.close();
                    }
                }
                catch(SocketException e) {
                    if(!closed.get())throw e;
                }
            }
        }
        catch(Exception e) {
            close();
            throw e;
        }
    }
    private Map<String,Object> hello() {
        Map<String,Boolean> capabilities=new LinkedHashMap<>();
        for(String s:List.of("auth","profiles","relations","posts","directChat","media","notifications","groups","receipts","moderation","transferResume","broadcastChat"))capabilities.put(s,true);
        if(config.udpEnabled()) { capabilities.put("udpDiscovery",true); capabilities.put("udpTyping",true); }
        return Map.of("selectedVersion",2,"serverId","social-java-v2","serverTime",System.currentTimeMillis(),"heartbeatIntervalMs",15000, "capabilities",capabilities,"limits",Map.of("maxFrameBytes",WireCodec.MAX_FRAME_BYTES,"pageSize",20,"maxPageSize",50,"imageMaxBytes",5242880,"fileMaxBytes",26214400));
    }
    private void handle(Connection c,WireMessage request) {
        try {
            if(!"REQUEST".equals(request.getKind())||request.getRequestId()==null||request.getType()==null)throw new SocialException("INVALID_REQUEST","Invalid request envelope");
            UUID.fromString(request.getRequestId());
            if(request.getProtocolVersion()!=2) {
                c.sendLast(WireMessage.failure(request,"VERSION_UNSUPPORTED","Chỉ hỗ trợ giao thức v2"));
                return;
            }
            Map<String,Object> p=request.getPayload();
            String type=request.getType();
            if(type.equals("HELLO")) {
                if(c.negotiated)throw new SocialException("INVALID_STATE","Đã thương lượng phiên bản");
                if(!(p.get("supportedVersions") instanceof List<?> versions)||versions.stream().noneMatch(v->v instanceof Number n&&n.intValue()==2)) {
                    c.sendLast(WireMessage.failure(request,"VERSION_UNSUPPORTED","Không có phiên bản chung"));
                    return;
                }
                c.negotiated=true;
                c.send(WireMessage.response(request,hello()));
                return;
            }
            if(!c.negotiated)throw new SocialException("UNAUTHENTICATED","Cần HELLO trước");
            if(type.equals("PING")) {
                c.send(WireMessage.response(request,Map.of("nonce",String.valueOf(p.getOrDefault("nonce","")),"serverTime",System.currentTimeMillis())));
                return;
            }
            if(type.equals("AUTH_REGISTER")||type.equals("AUTH_LOGIN")) {
                if(c.userId!=0)throw new SocialException("INVALID_STATE","Đã đăng nhập");
                String ip=c.socket.getInetAddress().getHostAddress(), username=String.valueOf(p.getOrDefault("username","")).trim().toLowerCase(Locale.ROOT);
                limits.check("auth-ip:"+ip,30,60000);
                limits.check("auth-user:"+username,5,60000);
                if(!authWorkers.tryAcquire())throw new SocialException("SERVER_BUSY","Đang xử lý nhiều lượt đăng nhập, vui lòng thử lại");
                Map<String,Object> user;
                try {
                    if(type.equals("AUTH_REGISTER")) {
                        c.send(WireMessage.response(request,service.register(p)));
                        return;
                    }
                    user=service.login(p);
                }
                finally {
                    authWorkers.release();
                }
                long id=((Number)user.get("id")).longValue();
                synchronized(users) {
                    if(c.dead.get()||c.revoked.get())throw new EOFException("Connection closed during authentication");
                    Connection previous=users.get(id);
                    if(previous!=null&&!previous.dead.get())throw new SocialException("ALREADY_SIGNED_IN","Tài khoản đang đăng nhập ở phiên khác");
                    c.userId=id;
                    c.authVersion=((Number)user.get("authVersion")).longValue();
                    users.put(id,c);
                }
                c.socket.setSoTimeout(45000);
                Map<String,Object> login=new LinkedHashMap<>(config.udpEnabled()?rotateUdp(c):Map.of());
                login.put("user",user);
                if(config.udpEnabled())login.put("udp",new LinkedHashMap<>(login));
                login.put("serverTime",System.currentTimeMillis());
                c.send(WireMessage.response(request,login));
                presenceChanged(id);
                return;
            }
            if(c.userId==0)throw new SocialException("UNAUTHENTICATED","Cần đăng nhập");
            Map<String,Object> current=service.user(c.userId);
            if(!"ACTIVE".equals(current.get("status"))||((Number)current.get("authVersion")).longValue()!=c.authVersion) {
                c.sendLast(WireMessage.failure(request,"UNAUTHENTICATED","Phiên đã bị thu hồi"));
                revoke(c);
                return;
            }
            if(type.equals("AUTH_LOGOUT")) {
                revoke(c);
                c.sendLast(WireMessage.response(request,Map.of("loggedOut",true)));
                return;
            }
            if(type.equals("UDP_SESSION_REFRESH")) {
                if(!config.udpEnabled())throw new SocialException("UNSUPPORTED_OPERATION","Server này chỉ bật TCP");
                c.send(WireMessage.response(request,rotateUdp(c)));
                return;
            }
            if(!type.equals("MEDIA_UPLOAD_CHUNK")&&!type.equals("MEDIA_DOWNLOAD_ACK"))limits.check("requests:"+c.userId,180,60000);
            if(type.equals("MESSAGE_SEND"))limits.check("messages:"+c.userId,60,60000);
            if(type.equals("BROADCAST_SEND")) {
                limits.check("broadcasts:"+c.userId,30,60000);
                broadcast(c,request);
                return;
            }
            if(type.equals("POST_CREATE"))limits.check("posts:"+c.userId,10,60000);
            if(type.equals("COMMENT_CREATE"))limits.check("comments:"+c.userId,30,60000);
            if(type.equals("FRIEND_REQUEST"))limits.check("friends:"+c.userId,20,3600000);
            if(type.equals("REPORT_CREATE"))limits.check("reports:"+c.userId,10,3600000);
            if(type.startsWith("MEDIA_")) {
                handleMedia(c,request);
                return;
            }
            Map<String,Object> result=service.execute(c.userId,type,p,request.getIdempotencyKey());
            decoratePresence(c.userId,type,result);
            watch(c,type,result);
            c.send(WireMessage.response(request,result));
            if(type.equals("AUTH_PASSWORD_CHANGE")) {
                revoke(c);
                c.drainAndClose();
            }
        }
        catch(SocialException e) {
            safeSend(c,WireMessage.failure(request,e.code(),e.getMessage()),e.details());
        }
        catch(IllegalArgumentException e) {
            safeSend(c,WireMessage.failure(request,"INVALID_REQUEST","Dữ liệu không hợp lệ"),Map.of());
        }
        catch(Exception e) {
            LOG.log(Level.WARNING,"Request failed type="+request.getType()+" requestId="+request.getRequestId(),e);
            safeSend(c,WireMessage.failure(request,"INTERNAL_ERROR","Không thể xử lý yêu cầu"),Map.of());
        }
    }

    /** Broadcast is an ephemeral public channel fan-out to authenticated sessions. */
    private void broadcast(Connection sender,WireMessage request)throws Exception {
        Map<String,Object> p=request.getPayload();
        if(p.size()!=1||!(p.get("content") instanceof String content))throw new SocialException("INVALID_REQUEST","Nội dung broadcast không hợp lệ");
        content=content.trim();
        if(content.isBlank()||content.length()>4000||content.codePoints().anyMatch(ch->Character.isISOControl(ch)&&ch!='\n'&&ch!='\t'))throw new SocialException("INVALID_REQUEST","Nội dung broadcast phải từ 1 đến 4.000 ký tự");
        String key=request.getIdempotencyKey();
        if(key==null)throw new SocialException("INVALID_REQUEST","Thiếu mã thao tác");
        UUID.fromString(key);
        Map<String,Object> previous=sender.broadcastResults.get(key);
        if(previous!=null) {
            if(!content.equals(previous.get("content")))throw new SocialException("IDEMPOTENCY_CONFLICT","Mã thao tác đã dùng với nội dung khác");
            sender.send(WireMessage.response(request,previous));
            return;
        }
        Map<String,Object> profile=service.user(sender.userId);
        // Resolve account/session validity before any fan-out; suspended sessions may await cleanup.
        List<Connection> audience=new ArrayList<>();
        for(Connection recipient:users.values()) {
            long uid=recipient.userId;
            if(uid==0||recipient.dead.get()||recipient.revoked.get()||recipient.draining)continue;
            Map<String,Object> account=service.user(uid);
            if("ACTIVE".equals(account.get("status"))&&((Number)account.get("authVersion")).longValue()==recipient.authVersion)audience.add(recipient);
        }
        long now=System.currentTimeMillis();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("broadcastId",UUID.randomUUID().toString());
        payload.put("senderId",sender.userId);
        payload.put("senderName",profile.get("displayName"));
        payload.put("content",content);
        payload.put("createdAt",now);
        int queued=0;
        for(Connection recipient:audience) {
            if(recipient.userId==0||recipient.dead.get()||recipient.revoked.get()||recipient.draining)continue;
            try {
                recipient.send(WireMessage.event("BROADCAST_MESSAGE",payload));
                queued++;
            } catch(IOException e) {
                recipient.close();
            }
        }
        Map<String,Object> result=new LinkedHashMap<>(payload);
        result.put("status","QUEUED");
        result.put("recipientCount",queued);
        sender.broadcastResults.put(key,result);
        while(sender.broadcastResults.size()>256)sender.broadcastResults.remove(sender.broadcastResults.keySet().iterator().next());
        sender.send(WireMessage.response(request,result));
    }
    private void handleMedia(Connection c,WireMessage r) throws Exception {
        String type=r.getType();
        Map<String,Object> p=r.getPayload();
        if(type.contains("CHUNK")||type.equals("MEDIA_UPLOAD_COMMIT")||type.equals("MEDIA_DOWNLOAD_ACK")||type.equals("MEDIA_DOWNLOAD_VERIFIED")||type.equals("MEDIA_DOWNLOAD_CANCEL")) {
            Object id=p.get("transferId");
            if(!(id instanceof String s)||!c.transfers.contains(s))throw new SocialException("NOT_FOUND","Lượt truyền không thuộc phiên này");
        }
        Map<String,Object> result=media.execute(c.userId,type,p,r.getIdempotencyKey());
        if(!Boolean.TRUE.equals(result.get("requiresResume"))&&Set.of("MEDIA_UPLOAD_INIT","MEDIA_UPLOAD_RESUME","MEDIA_DOWNLOAD_INIT").contains(type)&&result.get("transferId") instanceof String id) {
            if(media.bindSession(c.userId,id,c.id))c.transfers.add(id);
            else {
                result=new LinkedHashMap<>(result);
                result.put("requiresResume",true);
            }
        }
        c.send(WireMessage.response(r,result));
        if(type.equals("MEDIA_DOWNLOAD_VERIFIED")||type.equals("MEDIA_DOWNLOAD_CANCEL"))c.pumps.remove(String.valueOf(p.get("transferId")));
        if(!Boolean.TRUE.equals(result.get("requiresResume"))&&(type.equals("MEDIA_DOWNLOAD_INIT")||type.equals("MEDIA_DOWNLOAD_ACK")&&!Boolean.FALSE.equals(result.get("advanced")))) {
            String id=String.valueOf(type.equals("MEDIA_DOWNLOAD_INIT")?result.get("transferId"):p.get("transferId"));
            schedulePump(c,id);
        }
    }
    /** Coalesce duplicate ACKs: at most one queued/running worker per session transfer. */
    private void schedulePump(Connection c,String id) {
        PumpWork work=c.pumps.computeIfAbsent(id,k->new PumpWork());
        work.pending.set(true);
        if(!work.scheduled.compareAndSet(false,true))return;
        try {
            storage.execute(()-> {
                try {
                    while(work.pending.getAndSet(false)&&!c.dead.get()&&!c.revoked.get())pump(c,id);
                }
                finally {
                    work.scheduled.set(false);
                    if(work.pending.get()&&!c.dead.get()&&!c.revoked.get())schedulePump(c,id);
                }
            }
            );
        }
        catch(RejectedExecutionException e) {
            work.pending.set(false);
            work.scheduled.set(false);
            try {
                if(media.abortDownload(c.userId,id,"SERVER_BUSY"))safeSend(c,WireMessage.event("MEDIA_DOWNLOAD_FAILED",Map.of("transferId",id,"errorCode","SERVER_BUSY")),Map.of());
            }catch(Exception ignored){}
        }
    }
    private void pump(Connection c,String id) {
        try {
            if(c.dead.get()||c.userId==0)return;
            for(Map<String,Object> chunk:media.downloadWindow(c.userId,id))c.send(WireMessage.event("MEDIA_DOWNLOAD_CHUNK",chunk));
            Map<String,Object> complete=media.completion(c.userId,id);
            if(!complete.isEmpty())c.send(WireMessage.event("MEDIA_DOWNLOAD_COMPLETE",complete));
        }
        catch(Exception e) {
            String code=e instanceof SocialException se?se.code():"IO_ERROR";
            try {
                if(media.abortDownload(c.userId,id,code))safeSend(c,WireMessage.event("MEDIA_DOWNLOAD_FAILED",Map.of("transferId",id,"errorCode",code)),Map.of());
            }catch(Exception ignored){}
        }
    }
    private Map<String,Object> rotateUdp(Connection c) throws SocialException {
        synchronized(users) {
            if(c.revoked.get()||c.dead.get()||c.userId==0)throw new SocialException("UNAUTHENTICATED","Phiên đã bị thu hồi");
            UdpService.Session previous=udpSessions.remove(c.id);
            if(previous!=null)previous.active=false;
            byte[] key=new byte[32];
            new SecureRandom().nextBytes(key);
            long epoch=++c.udpEpoch,expires=System.currentTimeMillis()+1800000;
            UdpService.Session session=new UdpService.Session(c.id,c.userId,c.socket.getInetAddress(),epoch,key,expires);
            udpSessions.put(c.id,session);
            return Map.of("sessionId",c.id,"keyEpoch",epoch,"udpKey",Base64.getEncoder().encodeToString(key),"keyExpiresAt",expires,"serverUdpPort",typingPort(),"serverTime",System.currentTimeMillis());
        }
    }
    @Override public UdpService.Session session(String id) {
        return id==null?null:udpSessions.get(id);
    }
    @Override public Collection<UdpService.Session> recipients(UdpService.Session sender,long conversation) {
        try {
            if(!service.canSendToConversation(sender.userId,conversation))return List.of();
            List<UdpService.Session> recipients=new ArrayList<>();
            for(long user:service.activeRecipientIds(sender.userId,conversation)) {
                Connection c=users.get(user);
                if(c!=null) {
                    UdpService.Session s=udpSessions.get(c.id);
                    if(s!=null&&s.active)recipients.add(s);
                }
            }
            return recipients;
        }
        catch(Exception e) {
            return List.of();
        }
    }
    private void decoratePresence(long actor,String type,Map<String,Object> result) throws Exception {
        if(type.equals("PRESENCE_GET")&&result.get("items") instanceof List<?> items)for(Object o:items)if(o instanceof Map<?,?> raw) {
            @SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)raw;
            m.put("online",users.containsKey(((Number)m.get("userId")).longValue()));
        }
        if(type.equals("RELATION_LIST")&&result.get("items") instanceof List<?> items)for(Object o:items)if(o instanceof Map<?,?> raw) {
            @SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)raw;
            long a=((Number)m.get("lowUserId")).longValue(),b=((Number)m.get("highUserId")).longValue(),target=a==actor?b:a;
            if("ACCEPTED".equals(m.get("status"))) {
                m.put("online",users.containsKey(target));
                m.put("asOf",System.currentTimeMillis());
            }
            try {
                m.put("otherUser",service.execute(actor,"PROFILE_GET",Map.of("targetUserId",target),null));
            }
            catch(SocialException ignored) {
            }
        }
    }
    private void watch(Connection c,String type,Map<String,Object> result) {
        if(type.equals("POST_GET"))addWatch(c,result);
        else if((type.equals("FEED_GET")||type.equals("PROFILE_POSTS_GET"))&&result.get("items") instanceof List<?> list)for(Object o:list)if(o instanceof Map<?,?> map)addWatch(c,map);
    }
    private void addWatch(Connection c,Map<?,?> p) {
        Object id=p.containsKey("postId")?p.get("postId"):p.get("id");
        if(id instanceof Number n) {
            if(c.postWatches.size()>=500)c.postWatches.clear();
            c.postWatches.add(n.longValue());
        }
    }
    private void dispatchEvents() {
        if(closed.get())return;
        try {
            List<Map<String,Object>> events=service.pendingEvents(100);
            List<String> done=new ArrayList<>();
            for(Map<String,Object> e:events) {
                String type=String.valueOf(e.get("type")),eventId=String.valueOf(e.get("eventId"));
                long resource=((Number)e.get("aggregateId")).longValue(),revision=((Number)e.get("aggregateVersion")).longValue();
                Set<Long> audience=new HashSet<>();
                if(e.get("audience") instanceof List<?> ids)for(Object id:ids)audience.add(((Number)id).longValue());
                if(Set.of("POST_INVALIDATED","COMMENT_INVALIDATED","LIKE_INVALIDATED").contains(type))for(Connection c:users.values())if(c.postWatches.contains(resource))audience.add(c.userId);
                for(long actor:audience) {
                    Connection c=users.get(actor);
                    if(c==null)continue;
                    if(type.equals("SESSION_REVOKED")) {
                        if(c.authVersion<revision) {
                            safeSend(c,WireMessage.event(type,Map.of("userId",actor,"authVersion",revision)),Map.of());
                            revoke(c);
                            c.drainAndClose();
                        }
                        continue;
                    }
                    try {
                        Map<String,Object> p=service.eventPayload(actor,type,resource,revision);
                        WireMessage event=WireMessage.event(type,p);
                        event.setEventId(eventId);
                        c.send(event);
                    }
                    catch(SocialException ignored) {
                    }
                    catch(IOException deliveryFailure) {
                        c.close();
                    }
                }
                done.add(eventId);
            }
            if(!done.isEmpty())service.markEventsDispatched(done);
        }
        catch(Exception e) {
            if(!closed.get())LOG.log(Level.WARNING,"Outbox dispatch failed",e);
        }
    }
    private void presenceChanged(long user) {
        if(closed.get())return;
        for(Connection c:users.values()) {
            try {
                Map<String,Object> result=service.execute(c.userId,"PRESENCE_GET",Map.of("userIds",List.of(user)),null);
                if(result.get("items") instanceof List<?> list&&!list.isEmpty())c.send(WireMessage.event("PRESENCE_CHANGED",Map.of("userId",user,"online",users.containsKey(user),"asOf",System.currentTimeMillis())));
            }
            catch(Exception ignored) {
            }
        }
    }
    private void monitor() {
        long now=System.nanoTime();
        for(Connection c:connections.values()) {
            if(c.draining) {
                if(now-c.drainStarted>TimeUnit.SECONDS.toNanos(2))c.close();
                continue;
            }
            if(c.userId==0&&now-c.created>TimeUnit.SECONDS.toNanos(15)||now-c.lastRead>TimeUnit.SECONDS.toNanos(45)) {
                c.close();
                continue;
            }
            if(c.userId!=0)try {
                Map<String,Object> u=service.user(c.userId);
                if(!"ACTIVE".equals(u.get("status"))||((Number)u.get("authVersion")).longValue()!=c.authVersion) {
                    safeSend(c,WireMessage.event("SESSION_REVOKED",Map.of("reason","AUTH_CHANGED")),Map.of());
                    revoke(c);
                    c.drainAndClose();
                }
            }
            catch(Exception e) {
                c.close();
            }
        }
    }
    private void revoke(Connection c) {
        long id;
        synchronized(users) {
            if(!c.revoked.compareAndSet(false,true))return;
            id=c.userId;
            c.userId=0;
            if(id!=0)users.remove(id,c);
        }
        UdpService.Session s=udpSessions.remove(c.id);
        if(s!=null)s.active=false;
        c.transfers.clear();
        c.pumps.clear();
        c.postWatches.clear();
        if(id!=0) {
            media.disconnected(id,c.id);
            presenceChanged(id);
        }
    }
    private void safeSend(Connection c,WireMessage message,Map<String,Object> details) {
        if(!details.isEmpty())message.setPayload(details);
        try {
            c.send(message);
        }
        catch(IOException e) {
            c.close();
        }
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        try {
            if(listener!=null)listener.close();
        }
        catch(IOException ignored) {
        }
        if(udp!=null)udp.close();
        scheduler.shutdownNow();
        for(Connection c:connections.values())c.close();
        storage.shutdownNow();
        io.shutdownNow();
        try {
            storage.awaitTermination(5,TimeUnit.SECONDS);
            io.awaitTermination(5,TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        media.close();
        try {
            db.close();
        }
        catch(Exception e) {
            LOG.log(Level.WARNING,"Database close failed",e);
        }
        finally {
            try {
                applicationLock.close();
            }
            catch(Exception e) {
                LOG.log(Level.WARNING,"Application lock close failed",e);
            }
        }
    }
    private final class Connection implements AutoCloseable {
        final String id=UUID.randomUUID().toString();
        final Socket socket;
        final long created=System.nanoTime();
        volatile long lastRead=created,userId,authVersion,udpEpoch,drainStarted;
        volatile boolean negotiated,draining;
        final AtomicBoolean dead=new AtomicBoolean(),revoked=new AtomicBoolean();
        final Set<String> transfers=ConcurrentHashMap.newKeySet();
        final Set<Long> postWatches=ConcurrentHashMap.newKeySet();
        final ConcurrentMap<String,PumpWork> pumps=new ConcurrentHashMap<>();
        final LinkedHashMap<String,Map<String,Object>> broadcastResults=new LinkedHashMap<>();
        final ArrayDeque<byte[]> high=new ArrayDeque<>(),low=new ArrayDeque<>();
        int bytes;
        Connection(Socket socket) {
            this.socket=socket;
        }
        void read() {
            try {
                socket.setSoTimeout(15000);
                socket.setTcpNoDelay(true);
                io.execute(this::write);
                DataInputStream input=new DataInputStream(socket.getInputStream());
                while(!dead.get()&&!draining) {
                    WireMessage r=WireCodec.read(input);
                    if(r==null)break;
                    lastRead=System.nanoTime();
                    handle(this,r);
                }
            }
            catch(Exception e) {
                if(!dead.get())LOG.fine("Connection ended "+id+" "+e.getClass().getSimpleName());
            }
            finally {
                if(!draining)close();
            }
        }
        void send(WireMessage m)throws IOException {
            byte[] encoded=WireCodec.encode(m);
            boolean overflow;
            synchronized(high) {
                if(dead.get())throw new EOFException("Closed connection");
                overflow=high.size()+low.size()>=256||bytes+encoded.length+4>8*1024*1024;
                if(!overflow) {
                    (m.getType().equals("MEDIA_DOWNLOAD_CHUNK")?low:high).add(encoded);
                    bytes+=encoded.length+4;
                    high.notifyAll();
                }
            }
            if(overflow) {
                close();
                throw new IOException("Slow client queue exceeded");
            }
        }
        void sendLast(WireMessage m)throws IOException {
            send(m);
            drainAndClose();
        }
        void drainAndClose() {
            synchronized(high) {
                if(!draining){drainStarted=System.nanoTime();draining=true;}
                high.notifyAll();
            }
        }
        void write() {
            boolean flushed=false;
            try {
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                int burst=0;
                while(!dead.get()) {
                    byte[] b;
                    synchronized(high) {
                        while(high.isEmpty()&&low.isEmpty()&&!dead.get()&&!draining)high.wait();
                        if(dead.get())return;
                        if(high.isEmpty()&&low.isEmpty()&&draining)break;
                        b=!high.isEmpty()&&(burst++<8||low.isEmpty())?high.remove():low.remove();
                        if(burst>8)burst=0;
                        bytes-=b.length+4;
                    }
                    out.writeInt(b.length);
                    out.write(b);
                    out.flush();
                }
                flushed=draining;
            }
            catch(Exception ignored) {
            }
            finally {
                close(!flushed);
            }
        }
        @Override public void close() {
            close(true);
        }
        void close(boolean abortive) {
            if(!dead.compareAndSet(false,true)) {
                if(abortive)closeTransport(true);
                return;
            }
            synchronized(high) {
                high.clear();
                low.clear();
                bytes=0;
                high.notifyAll();
            }
            revoke(this);
            try { closeTransport(abortive); }
            finally { connections.remove(id,this);permits.release(); }
        }
        private void closeTransport(boolean abortive) {
            try {
                // Abort stalled readers promptly; drained final replies use graceful close.
                if(abortive)socket.setSoLinger(true,0);
                socket.close();
            }catch(IOException ignored){}
        }
    }
    private static final class PumpWork {
        final AtomicBoolean scheduled=new AtomicBoolean(),pending=new AtomicBoolean();
    }
    private static final class RateLimits {
        final Map<String,Window> windows=new HashMap<>();
        synchronized void check(String key,int cap,long period)throws SocialException {
            long now=System.currentTimeMillis();
            if(windows.size()>10000)windows.entrySet().removeIf(e->now-e.getValue().start>3600000);
            Window w=windows.get(key);
            if(w==null||now-w.start>=period) {
                w=new Window(now);
                windows.put(key,w);
            }
            if(++w.count>cap)throw new SocialException("RATE_LIMITED","Bạn thao tác quá nhanh",Map.of("retryAfterMs",Math.max(1,period-(now-w.start))));
        }
        static final class Window {
            final long start;
            int count;
            Window(long s) {
                start=s;
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Path configFile=Path.of(args.length>0&&!args[0].startsWith("--")?args[0]:"server/config/social.properties");
        Config config=Config.load(configFile);
        Path logs=config.databasePath().toAbsolutePath().getParent().getParent().resolve("logs");
        Files.createDirectories(logs);
        FileHandler logFile=new FileHandler(logs.resolve("social-%g.log").toString(),10*1024*1024,5,true);
        logFile.setEncoding("UTF-8");
        logFile.setFormatter(new SimpleFormatter());
        Logger.getLogger("").addHandler(logFile);
        try(SocialServer server=new SocialServer(config)) {
            if(Arrays.asList(args).contains("--create-admin")) {
                String password=System.getenv("SOCIAL_ADMIN_PASSWORD"),username=System.getenv("SOCIAL_ADMIN_USERNAME");
                if(password==null||username==null)throw new IllegalArgumentException("Set SOCIAL_ADMIN_USERNAME and SOCIAL_ADMIN_PASSWORD");
                Map<String,Object> u=server.service.bootstrapAdmin(username,"Quản trị viên",password);
                LOG.info("Created admin id="+u.get("id"));
                return;
            }
            if(!Arrays.asList(args).contains("--no-ui"))ServerMonitor.open(server,config);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close,"social-shutdown"));
            server.start();
        }
        finally {
            Logger.getLogger("").removeHandler(logFile);
            logFile.close();
        }
    }
}
