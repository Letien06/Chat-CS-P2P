package com.p2p.server.network.social;
import com.p2p.common.social.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
public final class UdpService implements AutoCloseable {
    public interface Host {
        Session session(String id);
        Collection<Session> recipients(Session sender,long conversationId);
    }
    public static final class Session {
        public final String id;
        public final long userId,keyEpoch,keyExpiresAt;
        public final InetAddress peerAddress;
        public final byte[] key;
        public volatile boolean active=true;
        private InetSocketAddress endpoint;
        private long highestSeq,outSeq;
        private final Map<Long,Long> typingAt=new HashMap<>();
        public Session(String id,long userId,InetAddress peerAddress,long keyEpoch,byte[] key,long keyExpiresAt) {
            this.id=id;
            this.userId=userId;
            this.peerAddress=peerAddress;
            this.keyEpoch=keyEpoch;
            this.key=key.clone();
            this.keyExpiresAt=keyExpiresAt;
        }
    }
    private final Host host;
    private final String id,name;
    private final int tcp,discoveryPort,typingPort;
    private DatagramSocket discovery,typing;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Map<String,Long> discoveryRates=new HashMap<>();
    private long globalWindow;
    private int globalCount;
    private volatile boolean closed;
    public UdpService(Host host,String serverId,String serverName,int tcpPort,int discoveryPort,int typingPort) {
        this.host=host;
        id=serverId;
        name=serverName;
        tcp=tcpPort;
        this.discoveryPort=discoveryPort;
        this.typingPort=typingPort;
    }
    public void start()throws IOException {
        try {
            discovery=new DatagramSocket(discoveryPort);
            typing=new DatagramSocket(typingPort);
            workers.execute(()->loop(discovery,true));
            workers.execute(()->loop(typing,false));
        }
        catch(IOException e) {
            close();
            throw e;
        }
    }
    public int getDiscoveryPort() {
        return discovery==null?discoveryPort:discovery.getLocalPort();
    }
    public int getTypingPort() {
        return typing==null?typingPort:typing.getLocalPort();
    }
    private void loop(DatagramSocket socket,boolean discover) {
        while(!closed) {
            try {
                byte[] b=new byte[1201];
                DatagramPacket p=new DatagramPacket(b,b.length);
                socket.receive(p);
                if(p.getLength()>1200)continue;
                byte[] raw=Arrays.copyOf(b,p.getLength());
                if(discover)offer(p,raw);
                else receive(p,raw);
            }
            catch(Exception ignored) {
                if(closed)return;
            }
        }
    }
    private void offer(DatagramPacket p,byte[] raw)throws Exception {
        Map<String,Object> b=UdpCodec.outer(raw);
        if(raw.length<256||UdpCodec.number(b.get("protocolVersion"))!=2||!"DISCOVER".equals(b.get("type"))||!(b.get("nonce") instanceof String nonce)||!nonce.matches("[a-zA-Z0-9_-]{20,64}"))return;
        long now=System.currentTimeMillis();
        if(now-globalWindow>1000) {
            globalWindow=now;
            globalCount=0;
        }
        if(++globalCount>100)return;
        String ip=p.getAddress().getHostAddress();
        if(now-discoveryRates.getOrDefault(ip,0L)<200)return;
        if(discoveryRates.size()>4096)discoveryRates.clear();
        discoveryRates.put(ip,now);
        byte[] out=Json.MAPPER.writeValueAsBytes(Map.of("protocolVersion",2,"type","OFFER","nonce",nonce,"serverId",id,"serverName",name,"tcpPort",tcp));
        if(out.length<=raw.length)discovery.send(new DatagramPacket(out,out.length,p.getSocketAddress()));
    }
    private void receive(DatagramPacket p,byte[] raw)throws Exception {
        Map<String,Object> outer=UdpCodec.outer(raw);
        Session s=host.session((String)outer.get("sessionId"));
        if(s==null||!s.active||s.keyExpiresAt<System.currentTimeMillis()||UdpCodec.number(outer.get("keyEpoch"))!=s.keyEpoch)return;
        Map<String,Object> b=UdpCodec.verify(raw,s.key,false);
        long now=System.currentTimeMillis();
        long ts=UdpCodec.number(b.get("ts"));
        if(ts<now-30000||ts>now+30000)return;
        long seq=UdpCodec.number(b.get("seq"));
        synchronized(s) {
            if(seq<=s.highestSeq)return;
            if("UDP_REGISTER".equals(b.get("type"))) {
                if(!s.peerAddress.equals(p.getAddress()))return;
                s.highestSeq=seq;
                s.endpoint=(InetSocketAddress)p.getSocketAddress();
                send(s,Map.of("type","UDP_REGISTERED","serverTime",now));
                return;
            }
            if(!"TYPING".equals(b.get("type"))||s.endpoint==null||!s.endpoint.equals(p.getSocketAddress()))return;
            long conversation=UdpCodec.number(b.get("conversationId"));
            if(conversation<1||!(b.get("typing") instanceof Boolean))return;
            s.highestSeq=seq;
            if(now-s.typingAt.getOrDefault(conversation,0L)<500)return;
            if(s.typingAt.size()>100)s.typingAt.clear();
            s.typingAt.put(conversation,now);
            Collection<Session> recipients=host.recipients(s,conversation);
            for(Session recipient:recipients)if(recipient!=s)send(recipient,Map.of("type","TYPING","actorId",s.userId,"conversationId",conversation,"typing",b.get("typing"),"expiresAt",now+5000));
        }
    }
    private void send(Session s,Map<String,Object> fields)throws Exception {
        synchronized(s) {
            if(!s.active||s.keyExpiresAt<System.currentTimeMillis()||s.endpoint==null)return;
            Map<String,Object> b=new LinkedHashMap<>(fields);
            b.put("v",2);
            b.put("sessionId",s.id);
            b.put("keyEpoch",s.keyEpoch);
            b.put("seq",++s.outSeq);
            b.put("ts",System.currentTimeMillis());
            byte[] raw=UdpCodec.sign(b,s.key,true);
            typing.send(new DatagramPacket(raw,raw.length,s.endpoint));
        }
    }
    public void close() {
        closed=true;
        if(discovery!=null)discovery.close();
        if(typing!=null)typing.close();
        workers.shutdownNow();
    }
}
