package com.p2p.client.network.social;
import com.p2p.common.social.*;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
/** One-shot transport. All socket operations run off the caller thread; listeners run on reader thread, and must not block. */
public final class SocialClient implements AutoCloseable {
    private final String host;
    private final int port;
    private final SSLContext tls;
    private volatile SSLSocket socket;
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String,CompletableFuture<Map<String,Object>>> pending=new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<WireMessage>> events=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Throwable>> disconnects=new CopyOnWriteArrayList<>();
    private final ArrayDeque<byte[]> high=new ArrayDeque<>(),low=new ArrayDeque<>();
    private int queuedBytes;
    private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
    private volatile long lastRead=System.nanoTime();
    private volatile Map<String,Object> hello=Map.of(),session=Map.of();
    private volatile long serverOffset;
    public SocialClient(String host,int port,SSLContext tls) {
        this.host=host;
        this.port=port;
        this.tls=tls;
    }
    public CompletableFuture<Map<String,Object>> connect() {
        CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
        if(closed.get()||!started.compareAndSet(false,true)) {
            result.completeExceptionally(new IOException("Transport already started or closed"));
            return result;
        }
        io.execute(()-> {
            try {
                SSLSocket s=(SSLSocket)tls.getSocketFactory().createSocket();socket=s;s.connect(new InetSocketAddress(host,port),5000);s.setSoTimeout(5000);SSLParameters p=s.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");p.setProtocols(new String[] {
                    "TLSv1.3","TLSv1.2"
                }
                );s.setSSLParameters(p);s.startHandshake();s.setSoTimeout(0);io.execute(this::reader);io.execute(this::writer);request("HELLO",Map.of("supportedVersions",List.of(2),"clientVersion","2.0")).whenComplete((v,e)-> {
                    if(e!=null) {
                        result.completeExceptionally(e);fail(e);
                    }
                    else {
                        hello=v;result.complete(v);
                    }
                }
                );timer.scheduleAtFixedRate(()-> {
                    if(System.nanoTime()-lastRead>TimeUnit.SECONDS.toNanos(45)) {
                        fail(new TimeoutException("Heartbeat expired"));return;
                    }
                    request("PING",Map.of("nonce",UUID.randomUUID().toString()));
                }
                ,15,15,TimeUnit.SECONDS);
            }
            catch(Throwable e) {
                result.completeExceptionally(e);fail(e);
            }
        }
        );
        return result;
    }
    public CompletableFuture<Map<String,Object>> request(String type,Map<String,Object> payload) {
        return request(type,payload,null);
    }
    public CompletableFuture<Map<String,Object>> request(String type,Map<String,Object> payload,String idem) {
        CompletableFuture<Map<String,Object>> f=new CompletableFuture<>();
        if(closed.get()||socket==null) {
            f.completeExceptionally(new IOException("Disconnected"));
            return f;
        }
        if(pending.size()>=256) {
            f.completeExceptionally(new IOException("Too many pending requests"));
            return f;
        }
        WireMessage m=WireMessage.request(type,payload);
        m.setIdempotencyKey(idem);
        pending.put(m.getRequestId(),f);
        long seconds=type.equals("MEDIA_UPLOAD_COMMIT")?60:10;
        ScheduledFuture<?> deadline;
        try {
            deadline=timer.schedule(()-> {
                if(pending.remove(m.getRequestId(),f))f.completeExceptionally(new TimeoutException("Request outcome unknown: "+type));
            }
            ,seconds,TimeUnit.SECONDS);
        }
        catch(RejectedExecutionException e) {
            pending.remove(m.getRequestId());
            f.completeExceptionally(new IOException("Disconnected",e));
            return f;
        }
        f.whenComplete((v,e)->deadline.cancel(false));
        try {
            byte[] b=WireCodec.encode(m);
            synchronized(high) {
                if(closed.get())throw new IOException("Disconnected");
                if(high.size()+low.size()>=256||queuedBytes+b.length+4>8388608)throw new IOException("Outbound queue full");
                (type.equals("MEDIA_UPLOAD_CHUNK")?low:high).add(b);
                queuedBytes+=b.length+4;
                high.notifyAll();
            }
        }
        catch(Throwable e) {
            pending.remove(m.getRequestId());
            f.completeExceptionally(e);
        }
        return f;
    }
    private void writer() {
        try {
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            int burst=0;
            while(!closed.get()) {
                byte[] b;
                synchronized(high) {
                    while(high.isEmpty()&&low.isEmpty()&&!closed.get())high.wait();
                    if(closed.get())return;
                    b=(!high.isEmpty()&&(burst++<8||low.isEmpty()))?high.remove():low.remove();
                    if(burst>8)burst=0;
                    queuedBytes-=b.length+4;
                }
                out.writeInt(b.length);
                out.write(b);
                out.flush();
            }
        }
        catch(Throwable e) {
            fail(e);
        }
    }
    private void reader() {
        try {
            DataInputStream in=new DataInputStream(socket.getInputStream());
            while(!closed.get()) {
                WireMessage m=WireCodec.read(in);
                if(m==null)throw new EOFException("Server disconnected");
                if(m.getProtocolVersion()!=2)throw new IOException("Protocol version mismatch");
                lastRead=System.nanoTime();
                if(m.getServerTime()!=null)serverOffset=m.getServerTime()-System.currentTimeMillis();
                if("RESPONSE".equals(m.getKind())) {
                    CompletableFuture<Map<String,Object>> f=pending.remove(m.getRequestId());
                    if(f!=null) {
                        if(Boolean.TRUE.equals(m.getOk())) {
                            if("AUTH_LOGIN".equals(m.getType()))session=Map.copyOf(m.getPayload());
                            f.complete(m.getPayload());
                        }
                        else {
                            Map<String,Object> e=m.getError()==null?Map.of():m.getError();
                            f.completeExceptionally(new RemoteException(String.valueOf(e.getOrDefault("code","INTERNAL_ERROR")),String.valueOf(e.getOrDefault("message","Request failed")),m.getPayload()));
                        }
                    }
                }
                else if("EVENT".equals(m.getKind()))for(Consumer<WireMessage> c:events)try {
                    c.accept(m);
                }
                catch(RuntimeException ignored) {
                }
            }
        }
        catch(Throwable e) {
            fail(e);
        }
    }
    public void onEvent(Consumer<WireMessage> c) {
        events.add(c);
    }
    public void onDisconnect(Consumer<Throwable> c) {
        disconnects.add(c);
    }
    public Map<String,Object> getHello() {
        return hello;
    }
    public Map<String,Object> getSession() {
        return session;
    }
    public long serverTime() {
        return System.currentTimeMillis()+serverOffset;
    }
    public boolean isConnected() {
        return socket!=null&&!closed.get();
    }
    private void fail(Throwable e) {
        if(!closed.compareAndSet(false,true))return;
        try {
            if(socket!=null)socket.close();
        }
        catch(IOException ignored) {
        }
        synchronized(high) {
            high.clear();
            low.clear();
            queuedBytes=0;
            high.notifyAll();
        }
        pending.forEach((id,f)->f.completeExceptionally(e));
        pending.clear();
        timer.shutdownNow();
        io.shutdownNow();
        for(Consumer<Throwable> c:disconnects)try {
            c.accept(e);
        }
        catch(RuntimeException ignored) {
        }
    }
    public void close() {
        fail(new IOException("Client closed"));
    }
}
