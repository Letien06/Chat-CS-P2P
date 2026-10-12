package com.p2p.client.network.social;
import com.p2p.common.social.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.io.*;
public final class UdpClient implements AutoCloseable {
    public record Offer(String host,int tcpPort,String serverId,String serverName) {
    }
    private final String host;
    private int port;
    private final DatagramSocket socket;
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2);
    private final CopyOnWriteArrayList<Consumer<Map<String,Object>>> listeners=new CopyOnWriteArrayList<>();
    private final Map<Long,Long> sent=new HashMap<>();
    private String sessionId;
    private byte[] key;
    private long epoch,expiry,seq,highest,offset;
    private boolean registered;
    private volatile boolean closed;
    public UdpClient(String host,int port)throws SocketException {
        this.host=host;
        this.port=port;
        socket=new DatagramSocket();
        executor.execute(this::receive);
        executor.scheduleAtFixedRate(()-> {
            synchronized(this) {
                if(key!=null&&!registered&&System.currentTimeMillis()+offset<expiry)send("UDP_REGISTER",Map.of());
            }
        }
        ,0,1,TimeUnit.SECONDS);
    }
    public synchronized void configure(Map<String,Object> udp,long serverTime) {
        sessionId=(String)udp.get("sessionId");
        epoch=UdpCodec.number(udp.get("keyEpoch"));
        key=Base64.getDecoder().decode((String)udp.get("udpKey"));
        if(key.length!=32)throw new IllegalArgumentException("Invalid UDP key");
        expiry=UdpCodec.number(udp.get("keyExpiresAt"));
        if(udp.get("serverUdpPort") instanceof Number n)port=n.intValue();
        offset=serverTime-System.currentTimeMillis();
        seq=highest=0;
        registered=false;
        sent.clear();
        executor.execute(()-> {
            synchronized(this) {
                send("UDP_REGISTER",Map.of());
            }
        }
        );
    }
    public synchronized void typing(long conversationId,boolean typing) {
        long now=System.currentTimeMillis();
        if(!registered||now-sent.getOrDefault(conversationId,0L)<500)return;
        sent.put(conversationId,now);
        executor.execute(()-> {
            synchronized(this) {
                send("TYPING",Map.of("conversationId",conversationId,"typing",typing));
            }
        }
        );
    }
    private void send(String type,Map<String,Object> payload) {
        if(closed||key==null||System.currentTimeMillis()+offset>=expiry)return;
        try {
            Map<String,Object> b=new LinkedHashMap<>(payload);
            b.put("v",2);
            b.put("type",type);
            b.put("sessionId",sessionId);
            b.put("keyEpoch",epoch);
            b.put("seq",++seq);
            b.put("ts",System.currentTimeMillis()+offset);
            byte[] raw=UdpCodec.sign(b,key,false);
            socket.send(new DatagramPacket(raw,raw.length,InetAddress.getByName(host),port));
        }
        catch(Exception ignored) {
        }
    }
    private void receive() {
        while(!closed) {
            try {
                byte[] bytes=new byte[1201];
                DatagramPacket p=new DatagramPacket(bytes,bytes.length);
                socket.receive(p);
                if(p.getLength()>1200||p.getPort()!=port||!p.getAddress().equals(InetAddress.getByName(host)))continue;
                Map<String,Object> b;
                synchronized(this) {
                    if(key==null||System.currentTimeMillis()+offset>=expiry)continue;
                    b=UdpCodec.verify(Arrays.copyOf(bytes,p.getLength()),key,true);
                    if(!sessionId.equals(b.get("sessionId"))||epoch!=UdpCodec.number(b.get("keyEpoch"))||(UdpCodec.number(b.get("ts"))<System.currentTimeMillis()+offset-30000||UdpCodec.number(b.get("ts"))>System.currentTimeMillis()+offset+30000)||UdpCodec.number(b.get("seq"))<=highest)continue;
                    highest=UdpCodec.number(b.get("seq"));
                    if("UDP_REGISTERED".equals(b.get("type"))) {
                        registered=true;
                        continue;
                    }
                    if(!registered||!"TYPING".equals(b.get("type"))||UdpCodec.number(b.get("expiresAt"))<System.currentTimeMillis()+offset)continue;
                }
                for(Consumer<Map<String,Object>> c:listeners)try {
                    c.accept(b);
                }
                catch(RuntimeException ignored) {
                }
            }
            catch(Exception ignored) {
                if(closed)return;
            }
        }
    }
    public void onTyping(Consumer<Map<String,Object>> c) {
        listeners.add(c);
    }
    public synchronized boolean isRegistered() {
        return registered;
    }
    public static CompletableFuture<List<Offer>> discover(String host,int port) {
        return CompletableFuture.supplyAsync(()-> {
            Map<String,Offer> offers=new LinkedHashMap<>();try(DatagramSocket s=new DatagramSocket()) {
                s.setBroadcast(true);s.setSoTimeout(100);String nonce=UUID.randomUUID().toString().replace("-","");byte[] request=Json.MAPPER.writeValueAsBytes(Map.of("protocolVersion",2,"type","DISCOVER","nonce",nonce,"padding"," ".repeat(300)));List<InetAddress> targets=new ArrayList<>();if(host!=null&&!host.isBlank())targets.add(InetAddress.getByName(host));else {
                    for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ni.isUp()&&!ni.isLoopback())for(InterfaceAddress a:ni.getInterfaceAddresses())if(a.getBroadcast()!=null)targets.add(a.getBroadcast());
                }
                for(int attempt=0;attempt<3;attempt++) {
                    for(InetAddress target:targets)s.send(new DatagramPacket(request,request.length,target,port));long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500+ThreadLocalRandom.current().nextInt(100));while(System.nanoTime()<end) {
                        try {
                            byte[] raw=new byte[1201];DatagramPacket p=new DatagramPacket(raw,raw.length);s.receive(p);if(p.getLength()>1200)continue;Map<String,Object> b=UdpCodec.outer(Arrays.copyOf(raw,p.getLength()));if(!"OFFER".equals(b.get("type"))||!nonce.equals(b.get("nonce"))||UdpCodec.number(b.get("protocolVersion"))!=2)continue;int tcp=(int)UdpCodec.number(b.get("tcpPort"));if(tcp<1||tcp>65535)continue;Offer o=new Offer(p.getAddress().getHostAddress(),tcp,String.valueOf(b.get("serverId")),String.valueOf(b.get("serverName")));offers.put(o.host()+":"+tcp,o);
                        }
                        catch(SocketTimeoutException ignored) {
                        }
                    }
                }
            }
            catch(IOException ignored) {
            }
            return List.copyOf(offers.values());
        }
        );
    }
    public void close() {
        closed=true;
        socket.close();
        executor.shutdownNow();
        synchronized(this) {
            key=null;
            registered=false;
        }
    }
}
