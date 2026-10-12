package com.p2p.server.social;
import com.p2p.common.social.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import java.security.MessageDigest;
class ProtocolRuntimeTest {
    @TempDir Path dir;
    SSLContext serverTls,clientTls;
    SocialServer server;
    ExecutorService acceptor;
    Future<?> running;
    static final char[] PASSWORD="test-keystore-password".toCharArray();
    @BeforeEach void setup()throws Exception {
        Path key=dir.resolve("server.p12"),cert=dir.resolve("cert.cer"),trust=dir.resolve("trust.p12");
        keytool("-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-keystore",key.toString(),"-storetype","PKCS12","-storepass:env","SOCIAL_TEST_PASSWORD");
        keytool("-exportcert","-alias","test","-keystore",key.toString(),"-storepass:env","SOCIAL_TEST_PASSWORD","-file",cert.toString());
        keytool("-importcert","-noprompt","-alias","test","-file",cert.toString(),"-keystore",trust.toString(),"-storetype","PKCS12","-storepass:env","SOCIAL_TEST_PASSWORD");
        serverTls=TlsContexts.server(key,PASSWORD);
        clientTls=TlsContexts.client(trust,PASSWORD);
        start();
    }
    void keytool(String...args)throws Exception {
        List<String> command=new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder=new ProcessBuilder(command);
        builder.environment().put("SOCIAL_TEST_PASSWORD",new String(PASSWORD));
        builder.redirectErrorStream(true);
        Process process=builder.start();
        String output=new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        assertEquals(0,process.exitValue(),output);
    }
    void start()throws Exception {
        server=new SocialServer(new SocialServer.Config(dir.resolve("social.db"),dir.resolve("media"),"127.0.0.1",0,0,0,10,serverTls,10*1024*1024,30*1024*1024));
        acceptor=Executors.newSingleThreadExecutor();
        running=acceptor.submit(()-> {
            try {
                server.start();
            }
            catch(Exception e) {
                throw new CompletionException(e);
            }
        }
        );
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(server.tcpPort()==0||server.typingPort()==0||server.discoveryPort()==0) {
            if(running.isDone())running.get();
            if(System.nanoTime()>end)fail("Server startup deadline");
            Thread.sleep(10);
        }
    }
    @AfterEach void stop()throws Exception {
        if(server!=null)server.close();
        if(acceptor!=null) {
            acceptor.shutdownNow();
            assertTrue(acceptor.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
    Client client()throws Exception {
        return new Client(clientTls,server.tcpPort());
    }
    static class Client implements AutoCloseable {
        final SSLSocket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final List<WireMessage> events=new ArrayList<>();
        Client(SSLContext tls,int port)throws Exception {
            socket=(SSLSocket)tls.getSocketFactory().createSocket("localhost",port);
            socket.setSoTimeout(5000);
            SSLParameters p=socket.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(p);
            socket.startHandshake();
            in=new DataInputStream(socket.getInputStream());
            out=new DataOutputStream(socket.getOutputStream());
        }
        WireMessage attempt(String type,Map<String,Object> p,String key)throws Exception {
            WireMessage r=WireMessage.request(type,p);
            r.setIdempotencyKey(key);
            return attempt(r);
        }
        WireMessage attempt(WireMessage request)throws Exception {
            WireCodec.write(out,request);
            for(;;) {
                WireMessage m=WireCodec.read(in);
                assertNotNull(m,"Transport closed before response");
                if("EVENT".equals(m.getKind()))events.add(m);
                else {
                    assertEquals(request.getRequestId(),m.getRequestId());
                    assertEquals(request.getType(),m.getType());
                    return m;
                }
            }
        }
        Map<String,Object> call(String type,Map<String,Object> p)throws Exception {
            return call(type,p,UUID.randomUUID().toString());
        }
        Map<String,Object> call(String type,Map<String,Object> p,String key)throws Exception {
            WireMessage response=attempt(type,p,key);
            assertEquals(Boolean.TRUE,response.getOk(),()->String.valueOf(response.getError()));
            return response.getPayload();
        }
        WireMessage event(String type)throws Exception {
            for(Iterator<WireMessage> iterator=events.iterator();iterator.hasNext();) {
                WireMessage e=iterator.next();
                if(type.equals(e.getType())) {
                    iterator.remove();
                    return e;
                }
            }
            for(;;) {
                WireMessage e=WireCodec.read(in);
                assertNotNull(e);
                assertEquals("EVENT",e.getKind());
                if(type.equals(e.getType()))return e;
                events.add(e);
            }
        }
        void hello()throws Exception {
            assertEquals(2,((Number)call("HELLO",Map.of("supportedVersions",List.of(2),"clientVersion","test")).get("selectedVersion")).intValue());
        }
        long register(String name)throws Exception {
            return ((Number)call("AUTH_REGISTER",Map.of("username",name,"displayName",name,"password","Runtime-Password-123")).get("userId")).longValue();
        }
        Map<String,Object> login(String name)throws Exception {
            return call("AUTH_LOGIN",Map.of("username",name,"password","Runtime-Password-123"));
        }
        public void close()throws IOException {
            socket.close();
        }
    }
    static void error(String code,WireMessage response) {
        assertEquals(Boolean.FALSE,response.getOk());
        assertEquals(code,response.getError().get("code"));
    }
    @Test void connectionLimitIncludesUnauthenticatedSocketsAndReleasesOnClose()throws Exception {
        List<Client> open=new ArrayList<>();
        try {
            for(int i=0;i<10;i++) {
                Client client=client();
                open.add(client);
                client.hello();
            }
            assertEquals(10,server.connectionCount());
            assertEquals(0,server.authenticatedCount());
            assertThrows(IOException.class,()-> {
                try(Client excess=client()) {
                    excess.hello();
                }
            }
            );
        }
        finally {
            for(Client client:open)client.close();
        }
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(server.connectionCount()!=0&&System.nanoTime()<end)Thread.sleep(10);
        assertEquals(0,server.connectionCount());
        try(Client replacement=client()) {
            replacement.hello();
        }
    }
    @Test void helloAuthVersionCorrelationAndDuplicateLogin()throws Exception {
        try(Client a=client();
        Client b=client()) {
            error("UNAUTHENTICATED",a.attempt("FEED_GET",Map.of(),null));
            a.hello();
            b.hello();
            a.register("alice");
            Map<String,Object> login=a.login("alice");
            assertNotNull(login.get("sessionId"));
            assertEquals(1,server.authenticatedCount());
            error("ALREADY_SIGNED_IN",b.attempt("AUTH_LOGIN",Map.of("username","alice","password","Runtime-Password-123"),null));
            error("INVALID_REQUEST",a.attempt("PROFILE_UPDATE",Map.of("actorId",900,"displayName","spoof","bio","","expectedVersion",1),UUID.randomUUID().toString()));
            assertEquals("hello",a.call("PING",Map.of("nonce","hello")).get("nonce"));
        }
        try(Client wrong=client()) {
            WireMessage r=WireMessage.request("HELLO",Map.of("supportedVersions",List.of(1)));
            r.setProtocolVersion(1);
            error("VERSION_UNSUPPORTED",wrong.attempt(r));
            assertNull(WireCodec.read(wrong.in));
        }
    }
    @Test void offlineChatRetryStoredAndRestartHistory()throws Exception {
        long conversation;
        String messageId=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        Map<String,Object> stored;
        try(Client a=client();
        Client b=client()) {
            a.hello();
            b.hello();
            long alice=a.register("alice"),bob=b.register("bob");
            a.login("alice");
            b.login("bob");
            Map<String,Object> relation=a.call("FRIEND_REQUEST",Map.of("targetUserId",bob));
            b.call("FRIEND_ACCEPT",Map.of("relationId",relation.get("relationId"),"expectedVersion",relation.get("version")));
            conversation=((Number)a.call("CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",bob)).get("conversationId")).longValue();
            Map<String,Object> post=a.call("POST_CREATE",Map.of("content","Friends only","visibility","FRIENDS","mediaIds",List.of()));
            assertEquals("Friends only",b.call("POST_GET",Map.of("postId",post.get("postId"))).get("content"));
            b.close();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(server.authenticatedCount()!=1&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals(1,server.authenticatedCount());
            Map<String,Object> request=Map.of("conversationId",conversation,"clientMessageId",messageId,"content","offline durable hello");
            stored=a.call("MESSAGE_SEND",request,key);
            assertEquals("STORED",stored.get("status"));
            Map<String,Object> retry=a.call("MESSAGE_SEND",request,key);
            assertEquals(stored.get("messageId"),retry.get("messageId"));
            error("IDEMPOTENCY_CONFLICT",a.attempt("MESSAGE_SEND",Map.of("conversationId",conversation,"clientMessageId",messageId,"content","changed"),key));
        }
        stop();
        start();
        try(Client b=client()) {
            b.hello();
            b.login("bob");
            Map<String,Object> history=b.call("MESSAGE_HISTORY",Map.of("conversationId",conversation,"afterSeq",0,"limit",100));
            List<?> items=(List<?>)history.get("items");
            assertEquals(1,items.size());
            Map<?,?> message=(Map<?,?>)items.get(0);
            assertEquals("offline durable hello",message.get("content"));
            assertEquals(((Number)stored.get("messageId")).longValue(),((Number)message.get("id")).longValue());
            b.call("MESSAGE_READ",Map.of("conversationId",conversation,"throughSeq",1));
        }
    }
    @Test void authenticatedUdpUsesCurrentRelationshipsAndRotatedSessionKey()throws Exception {
        try(Client alice=client();
        Client bob=client();
        DatagramSocket a=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
        DatagramSocket b=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) {
            a.setSoTimeout(300);
            b.setSoTimeout(300);
            alice.hello();
            bob.hello();
            alice.register("alice");
            long bobId=bob.register("bob");
            Map<String,Object> aliceUdp=(Map<String,Object>)alice.login("alice").get("udp"),bobUdp=(Map<String,Object>)bob.login("bob").get("udp");
            Map<String,Object> relation=alice.call("FRIEND_REQUEST",Map.of("targetUserId",bobId));
            bob.call("FRIEND_ACCEPT",Map.of("relationId",relation.get("relationId"),"expectedVersion",relation.get("version")));
            long conversation=((Number)alice.call("CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",bobId)).get("conversationId")).longValue();
            udpSend(a,aliceUdp,"UDP_REGISTER",1,Map.of());
            assertEquals("UDP_REGISTERED",udpRead(a,aliceUdp).get("type"));
            udpSend(b,bobUdp,"UDP_REGISTER",1,Map.of());
            udpRead(b,bobUdp);
            udpSend(a,aliceUdp,"TYPING",2,Map.of("conversationId",conversation,"typing",true));
            assertEquals(conversation,((Number)udpRead(b,bobUdp).get("conversationId")).longValue());
            Map<String,Object> refreshed=alice.call("UDP_SESSION_REFRESH",Map.of());
            assertTrue(((Number)refreshed.get("keyEpoch")).longValue()>((Number)aliceUdp.get("keyEpoch")).longValue());
            udpSend(a,aliceUdp,"UDP_REGISTER",3,Map.of());
            assertThrows(SocketTimeoutException.class,()->udpRead(a,aliceUdp));
            udpSend(a,refreshed,"UDP_REGISTER",1,Map.of());
            udpRead(a,refreshed);
            alice.call("BLOCK_SET",Map.of("targetUserId",bobId,"blocked",true));
            udpSend(a,refreshed,"TYPING",2,Map.of("conversationId",conversation,"typing",true));
            assertThrows(SocketTimeoutException.class,()->udpRead(b,bobUdp));
            alice.call("AUTH_LOGOUT",Map.of());
            udpSend(a,refreshed,"UDP_REGISTER",3,Map.of());
            assertThrows(SocketTimeoutException.class,()->udpRead(a,refreshed));
        }
    }
    void udpSend(DatagramSocket socket,Map<String,Object> config,String type,long seq,Map<String,Object> extras)throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(extras);
        body.put("v",2);
        body.put("sessionId",config.get("sessionId"));
        body.put("keyEpoch",config.get("keyEpoch"));
        body.put("seq",seq);
        body.put("ts",System.currentTimeMillis());
        body.put("type",type);
        byte[] raw=UdpCodec.sign(body,Base64.getDecoder().decode((String)config.get("udpKey")),false);
        socket.send(new DatagramPacket(raw,raw.length,InetAddress.getByName("127.0.0.1"),server.typingPort()));
    }
    Map<String,Object> udpRead(DatagramSocket socket,Map<String,Object> config)throws Exception {
        byte[] buffer=new byte[1201];
        DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
        socket.receive(packet);
        return UdpCodec.verify(Arrays.copyOf(buffer,packet.getLength()),Base64.getDecoder().decode((String)config.get("udpKey")),true);
    }
    @Test void fileDownloadWindowsAndSessionBoundResume()throws Exception {
        byte[] bytes=new byte[MediaService.CHUNK_BYTES*5+9];
        new Random(8).nextBytes(bytes);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String mediaId,oldTransfer;
        try(Client a=client()) {
            a.hello();
            a.register("alice");
            a.login("alice");
            Map<String,Object> t=a.call("MEDIA_UPLOAD_INIT",Map.of("purpose","CHAT_FILE","fileName","file.bin","size",bytes.length,"mimeType","application/octet-stream","sha256",hash));
            mediaId=(String)t.get("mediaId");
            oldTransfer=(String)t.get("transferId");
            a.call("MEDIA_UPLOAD_CHUNK",Map.of("transferId",oldTransfer,"offset",0,"data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,0,MediaService.CHUNK_BYTES))));
        }
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(server.authenticatedCount()!=0&&System.nanoTime()<end)Thread.sleep(10);
        try(Client a=client()) {
            a.hello();
            a.login("alice");
            a.call("MEDIA_UPLOAD_STATUS",Map.of("mediaId",mediaId));
            error("NOT_FOUND",a.attempt("MEDIA_UPLOAD_CHUNK",Map.of("transferId",oldTransfer,"offset",MediaService.CHUNK_BYTES,"data","AQ=="),null));
            Map<String,Object> resumed=a.call("MEDIA_UPLOAD_RESUME",Map.of("mediaId",mediaId,"size",bytes.length,"sha256",hash));
            String transfer=(String)resumed.get("transferId");
            assertNotEquals(oldTransfer,transfer);
            for(int offset=MediaService.CHUNK_BYTES;offset<bytes.length;offset+=MediaService.CHUNK_BYTES)a.call("MEDIA_UPLOAD_CHUNK",Map.of("transferId",transfer,"offset",offset,"data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(offset+MediaService.CHUNK_BYTES,bytes.length)))));
            assertEquals("READY",a.call("MEDIA_UPLOAD_COMMIT",Map.of("transferId",transfer)).get("state"));
            Map<String,Object> d=a.call("MEDIA_DOWNLOAD_INIT",Map.of("mediaId",mediaId));
            String download=(String)d.get("transferId");
            ByteArrayOutputStream received=new ByteArrayOutputStream();
            for(int i=0;i<4;i++) {
                Map<String,Object> p=a.event("MEDIA_DOWNLOAD_CHUNK").getPayload();
                assertEquals(received.size(),((Number)p.get("offset")).intValue());
                received.write(Base64.getDecoder().decode((String)p.get("data")));
            }
            assertEquals(MediaService.CHUNK_BYTES*4,received.size());
            a.call("PING",Map.of("nonce","control-during-transfer"));
            a.call("MEDIA_DOWNLOAD_ACK",Map.of("transferId",download,"throughOffset",received.size()));
            for(int i=0;i<2;i++)received.write(Base64.getDecoder().decode((String)a.event("MEDIA_DOWNLOAD_CHUNK").getPayload().get("data")));
            a.call("MEDIA_DOWNLOAD_ACK",Map.of("transferId",download,"throughOffset",received.size()));
            assertEquals(bytes.length,((Number)a.event("MEDIA_DOWNLOAD_COMPLETE").getPayload().get("size")).intValue());
            assertArrayEquals(bytes,received.toByteArray());
            assertEquals("VERIFIED",a.call("MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",download,"actualSize",bytes.length,"actualSha256",hash)).get("state"));
        }
    }
}
