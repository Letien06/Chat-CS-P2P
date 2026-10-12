package com.p2p.server.social;
import com.p2p.common.social.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
/** Reproducible local LAN acceptance workload; reports measured values, never estimates. */
class LoadRuntimeTest {
    @TempDir Path dir;
    static final String PASSWORD="Runtime-Password-123";
    @Test void twentyClientsLargeHistoryAndTwoFullSizeTransfersRemainResponsive()throws Exception {
        Set<Long> baseline=new HashSet<>();
        for(Thread t:Thread.getAllStackTraces().keySet())baseline.add(t.getId());
        ProtocolRuntimeTest certificates=new ProtocolRuntimeTest();
        certificates.dir=dir;
        certificates.setup();
        certificates.stop();
        SocialServer server=new SocialServer(new SocialServer.Config(dir.resolve("social.db"),dir.resolve("media"),"127.0.0.1",0,0,0,50,certificates.serverTls,200L*1024*1024,1024L*1024*1024));
        ExecutorService acceptor=Executors.newSingleThreadExecutor(),work=Executors.newFixedThreadPool(20);
        List<ProtocolRuntimeTest.Client> clients=new ArrayList<>();
        List<ProtocolRuntimeTest.Client> idle=new ArrayList<>();
        ConcurrentLinkedQueue<Long> normal=new ConcurrentLinkedQueue<>(),duringBulk=new ConcurrentLinkedQueue<>(),feedSamples=new ConcurrentLinkedQueue<>(),chatSamples=new ConcurrentLinkedQueue<>(),bulkChatSamples=new ConcurrentLinkedQueue<>();
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("runAt",java.time.Instant.now().toString());
        metrics.put("javaVersion",System.getProperty("java.version"));
        metrics.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        metrics.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        metrics.put("maxHeapMiB",Runtime.getRuntime().maxMemory()/1024.0/1024.0);
        metrics.put("physicalMemoryMiB",((com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize()/1024.0/1024.0);
        javax.net.ssl.SSLSocket slowReader=null;
        Future<?> start=null;
        boolean slowDropped=false;
        long shutdownMs=0; boolean workStopped=false,acceptorStopped=false; List<String> serverThreadsBeforePeerClose=List.of();
        try {
            seed(server.database());
            start=acceptor.submit(()-> {
                try {
                    server.start();
                }
                catch(Exception e) {
                    throw new CompletionException(e);
                }
            }
            );
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(server.typingPort()==0||server.tcpPort()==0) {
                if(start.isDone())start.get();
                if(System.nanoTime()>deadline)fail("Load server startup timed out");
                Thread.sleep(10);
            }
            long loginStarted=System.nanoTime();
            for(int i=0;i<20;i++) {
                ProtocolRuntimeTest.Client client=new ProtocolRuntimeTest.Client(certificates.clientTls,server.tcpPort());
                clients.add(client);
                client.hello();
                client.login(String.format("load_%02d",i));
            }
            assertEquals(20,server.authenticatedCount());
            metrics.put("login20Ms",ms(System.nanoTime()-loginStarted));
            List<Future<?>> requests=new ArrayList<>();
            for(int i=0;i<20;i++) {
                final int index=i;
                requests.add(work.submit(()-> {
                    try {
                        ProtocolRuntimeTest.Client c=clients.get(index);
                        long conversation=index/2+1;
                        feedSamples.add(timed(normal,()->c.call("FEED_GET",Map.of("limit",20))));
                        timed(normal,()->c.call("MESSAGE_HISTORY",Map.of("conversationId",conversation,"beforeSeq",5001,"limit",100)));
                        chatSamples.add(timed(normal,()->c.call("MESSAGE_SEND",Map.of("conversationId",conversation,"clientMessageId",UUID.randomUUID().toString(),"content","load burst "+index))));
                        List<?> messages=(List<?>)c.call("MESSAGE_HISTORY",Map.of("conversationId",conversation,"afterSeq",5000,"limit",10)).get("items");
                        for(Object raw:messages) {
                            Map<?,?> message=(Map<?,?>)raw;
                            if(((Number)message.get("senderId")).longValue()!=index+1) {
                                long seq=((Number)message.get("conversationSeq")).longValue();
                                timed(normal,()->c.call("MESSAGE_READ",Map.of("conversationId",conversation,"throughSeq",seq)));
                            }
                        }
                    }
                    catch(Exception e) {
                        throw new CompletionException(e);
                    }
                }
                ));
            }
            for(Future<?> future:requests)future.get(30,TimeUnit.SECONDS);
            assertTrue(p95(chatSamples)<=500,"Chat STORED p95 exceeded 500 ms: "+p95(chatSamples));
            assertTrue(p95(feedSamples)<=1000,"Feed p95 exceeded 1000 ms: "+p95(feedSamples));
            Path source=dir.resolve("large-source.bin");
            long size=25L*1024*1024;
            String hash=generate(source,size);
            long bulkStarted=System.nanoTime();
            CountDownLatch gate=new CountDownLatch(1);
            List<Future<?>> bulk=new ArrayList<>();
            for(int i=0;i<2;i++) {
                final int index=i;
                bulk.add(work.submit(()-> {
                    try {
                        gate.await();
                        roundTrip(clients.get(index),source,size,hash);
                    }
                    catch(Exception e) {
                        throw new CompletionException(e);
                    }
                }
                ));
            }
            for(int i=2;i<20;i++) {
                final int index=i;
                bulk.add(work.submit(()-> {
                    try {
                        gate.await();
                        for(int n=0;n<10;n++) {
                            if(n%2==0)bulkChatSamples.add(timed(duringBulk,()->clients.get(index).call("MESSAGE_SEND",Map.of("conversationId",index/2+1,"clientMessageId",UUID.randomUUID().toString(),"content","chat during full-size transfer"))));
                            else timed(duringBulk,()->clients.get(index).call("PING",Map.of("nonce",UUID.randomUUID().toString())));
                            Thread.sleep(20);
                        }
                    }
                    catch(Exception e) {
                        throw new CompletionException(e);
                    }
                }
                ));
            }
            gate.countDown();
            for(Future<?> future:bulk)future.get(90,TimeUnit.SECONDS);
            metrics.put("two25MiBUploadDownloadMs",ms(System.nanoTime()-bulkStarted));
            metrics.put("transferredFileBytes",size*2);
            assertTrue(p95(duringBulk)<1500,"Control p95 under transfers exceeded 1500 ms: "+p95(duringBulk));
            // Thirty additional TLS peers fill the shared cap even without authentication.
            for(int i=0;i<30;i++) {
                ProtocolRuntimeTest.Client c=new ProtocolRuntimeTest.Client(certificates.clientTls,server.tcpPort());
                idle.add(c);
                c.hello();
            }
            assertEquals(50,server.connectionCount());metrics.put("liveThreadsAt50Connections",Thread.getAllStackTraces().size());metrics.put("ownedThreadsAt50Connections",ownedThreads(baseline).size());
            assertThrows(IOException.class,()-> {
                try(ProtocolRuntimeTest.Client excess=new ProtocolRuntimeTest.Client(certificates.clientTls,server.tcpPort())) {
                    excess.hello();
                }
            }
            );
            for(ProtocolRuntimeTest.Client c:idle)c.close();
            idle.clear();
            clients.get(0).close();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(server.authenticatedCount()==20&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(19,server.authenticatedCount());
            slowReader=(javax.net.ssl.SSLSocket)certificates.clientTls.getSocketFactory().createSocket();
            slowReader.setReceiveBufferSize(1024);
            slowReader.setSoTimeout(5000);
            slowReader.connect(new java.net.InetSocketAddress("localhost",server.tcpPort()),5000);
            javax.net.ssl.SSLParameters parameters=slowReader.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            slowReader.setSSLParameters(parameters);
            slowReader.startHandshake();
            rawRequest(slowReader,"HELLO",Map.of("supportedVersions",List.of(2),"clientVersion","slow-reader-test"));
            rawRequest(slowReader,"AUTH_LOGIN",Map.of("username","load_00","password",PASSWORD));
            assertEquals(20,server.authenticatedCount());
            queueSlowReaderPressure(server.database());
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            int maximumQueuedFrames=0,maximumQueuedBytes=0;
            while(activeUser(server,1)&&System.nanoTime()<deadline) {
                int[] queued=queueSnapshot(server,1);
                maximumQueuedFrames=Math.max(maximumQueuedFrames,queued[0]);
                maximumQueuedBytes=Math.max(maximumQueuedBytes,queued[1]);
                assertTrue(queued[0]<=256&&queued[1]<=8*1024*1024,"Outbound queue escaped configured bounds");
                Thread.sleep(20);
            }
            metrics.put("slowReaderMaxObservedQueueFrames",maximumQueuedFrames);
            metrics.put("slowReaderMaxObservedQueueBytes",maximumQueuedBytes);
            metrics.put("slowReaderEventsDispatched",pressureDispatched(server.database()));
            metrics.put("slowReaderEventsProduced",5000);
            slowDropped=!activeUser(server,1);
            assertTrue(slowDropped,"Slow reader was not disconnected; observedFrames="+maximumQueuedFrames+", dispatched="+metrics.get("slowReaderEventsDispatched"));
            metrics.put("fixturePosts",10000);
            metrics.put("fixtureMessages",50000);
            metrics.put("authenticatedClients",20);
            metrics.put("connectionCap",50);
            metrics.put("normalControlSamples",normal.size());
            metrics.put("normalControlP95Ms",p95(normal));
            metrics.put("feedP95Ms",p95(feedSamples));
            metrics.put("chatStoredP95Ms",p95(chatSamples));
            metrics.put("chatStoredDuringTransfersP95Ms",p95(bulkChatSamples));
            metrics.put("duringTransfersControlSamples",duringBulk.size());
            metrics.put("duringTransfersControlP95Ms",p95(duringBulk));
            metrics.put("slowReaderDisconnected",slowDropped);
            metrics.put("workloadComplete",true);
            metrics.put("maxHeapMiB",Runtime.getRuntime().maxMemory()/1024.0/1024.0);
            metrics.put("usedHeapMiB",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024.0/1024.0);
        }
        finally {
            work.shutdownNow();
            long before=System.nanoTime();
            try {
                // Server shutdown must release blocking TLS I/O while peers remain open.
                server.close();
                acceptor.shutdownNow();
                workStopped=work.awaitTermination(5,TimeUnit.SECONDS);
                acceptorStopped=acceptor.awaitTermination(5,TimeUnit.SECONDS);
                shutdownMs=ms(System.nanoTime()-before);
                serverThreadsBeforePeerClose=ownedThreads(baseline);
            } finally {
                if(slowReader!=null)try{slowReader.close();}catch(IOException ignored){}
                for(ProtocolRuntimeTest.Client c:clients)try{c.close();}catch(IOException ignored){}
                for(ProtocolRuntimeTest.Client c:idle)try{c.close();}catch(IOException ignored){}
                metrics.put("shutdownMs",shutdownMs);
                metrics.put("threadsBeforePeerClose",serverThreadsBeforePeerClose);
                metrics.put("shutdownClean",workStopped&&acceptorStopped&&serverThreadsBeforePeerClose.isEmpty());
                metrics.put("normalControlP95Ms",p95(normal));
                metrics.put("feedP95Ms",p95(feedSamples));
                metrics.put("chatStoredP95Ms",p95(chatSamples));
                metrics.put("chatStoredDuringTransfersP95Ms",p95(bulkChatSamples));
                metrics.put("duringTransfersControlP95Ms",p95(duringBulk));
                Path report=Path.of("target/qa/performance.json").toAbsolutePath();
                Files.createDirectories(report.getParent());
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),metrics);
            }
        }
        assertTrue(workStopped&&acceptorStopped,"Workload or acceptor did not terminate");
        assertTrue(serverThreadsBeforePeerClose.isEmpty(),"Server workers only released when peers closed: "+serverThreadsBeforePeerClose);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        List<String> leaked;
        do {
            leaked=new ArrayList<>();
            for(Thread thread:Thread.getAllStackTraces().keySet())if(!baseline.contains(thread.getId())&&thread.isAlive()&&(thread.getName().startsWith("social-db-")||thread.getName().matches("pool-\\d+-thread-\\d+")))leaked.add(thread.getName());
            if(leaked.isEmpty())break;
            Thread.sleep(20);
        }
        while(System.nanoTime()<deadline);
        assertTrue(leaked.isEmpty(),"Server/executor threads survived shutdown: "+leaked);
        assertTrue(shutdownMs<5000,"Shutdown exceeded 5 seconds");
    }
    static List<String> ownedThreads(Set<Long> baseline) {
        List<String> names=new ArrayList<>();
        for(Thread thread:Thread.getAllStackTraces().keySet())
            if(!baseline.contains(thread.getId())&&thread.isAlive()&&(thread.getName().startsWith("social-db-")||thread.getName().matches("pool-\\d+-thread-\\d+")))names.add(thread.getName());
        return names;
    }
    @FunctionalInterface interface Request {
        Object run()throws Exception;
    }
    static long timed(Queue<Long> samples,Request request)throws Exception {
        long start=System.nanoTime();
        request.run();
        long elapsed=System.nanoTime()-start;
        samples.add(elapsed);
        return elapsed;
    }
    static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
    static double p95(Collection<Long> samples) {
        if(samples.isEmpty())return 0;
        List<Long> sorted=new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*.95)-1))/1000000.0;
    }
    static String generate(Path file,long size)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[MediaService.CHUNK_BYTES];
        Random random=new Random(300);
        try(OutputStream out=Files.newOutputStream(file)) {
            for(long offset=0;offset<size;) {
                random.nextBytes(buffer);
                int count=(int)Math.min(buffer.length,size-offset);
                out.write(buffer,0,count);
                hash.update(buffer,0,count);
                offset+=count;
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static void roundTrip(ProtocolRuntimeTest.Client c,Path source,long size,String hash)throws Exception {
        Map<String,Object> upload=c.call("MEDIA_UPLOAD_INIT",Map.of("purpose","CHAT_FILE","fileName","load-25MiB.bin","size",size,"mimeType","application/octet-stream","sha256",hash));
        String transfer=(String)upload.get("transferId");
        try(InputStream in=Files.newInputStream(source)) {
            byte[] buffer=new byte[MediaService.CHUNK_BYTES];
            long offset=0;
            for(int count;(count=in.read(buffer))!=-1;) {
                c.call("MEDIA_UPLOAD_CHUNK",Map.of("transferId",transfer,"offset",offset,"data",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,count))));
                offset+=count;
            }
            assertEquals(size,offset);
        }
        c.call("MEDIA_UPLOAD_COMMIT",Map.of("transferId",transfer));
        String download=(String)c.call("MEDIA_DOWNLOAD_INIT",Map.of("mediaId",upload.get("mediaId"))).get("transferId");
        long offset=0;
        MessageDigest received=MessageDigest.getInstance("SHA-256");
        while(offset<size) {
            for(int n=0;n<4&&offset<size;n++) {
                Map<String,Object> chunk=c.event("MEDIA_DOWNLOAD_CHUNK").getPayload();
                assertEquals(offset,((Number)chunk.get("offset")).longValue());
                byte[] bytes=Base64.getDecoder().decode((String)chunk.get("data"));
                received.update(bytes);
                offset+=bytes.length;
            }
            c.call("MEDIA_DOWNLOAD_ACK",Map.of("transferId",download,"throughOffset",offset));
        }
        assertEquals(size,((Number)c.event("MEDIA_DOWNLOAD_COMPLETE").getPayload().get("size")).longValue());
        assertEquals(hash,HexFormat.of().formatHex(received.digest()));
        assertEquals("VERIFIED",c.call("MEDIA_DOWNLOAD_VERIFIED",Map.of("transferId",download,"actualSize",size,"actualSha256",hash)).get("state"));
    }
    static void seed(SocialDatabase db)throws Exception {
        String salt=Passwords.salt(),hash=Passwords.hash(PASSWORD,salt,Passwords.ITERATIONS);
        long now=System.currentTimeMillis();
        SocialService fingerprints=new SocialService(db);
        db.write(c-> {
            try(PreparedStatement users=c.prepareStatement("INSERT INTO users(id,username,username_key,password_hash,salt,hash_algorithm,hash_iterations,created_at) VALUES(?,?,?,?,?,'PBKDF2WithHmacSHA256',?,?)");
            PreparedStatement profiles=c.prepareStatement("INSERT INTO profiles(user_id,display_name,updated_at) VALUES(?,?,?)")) {
                for(int i=1;i<=20;i++) {
                    users.setInt(1,i);
                    users.setString(2,String.format("load_%02d",i-1));
                    users.setString(3,String.format("load_%02d",i-1));
                    users.setString(4,hash);
                    users.setString(5,salt);
                    users.setInt(6,Passwords.ITERATIONS);
                    users.setLong(7,now);
                    users.addBatch();
                    profiles.setInt(1,i);
                    profiles.setString(2,"Load user "+i);
                    profiles.setLong(3,now);
                    profiles.addBatch();
                }
                users.executeBatch();
                profiles.executeBatch();
            }
            try(PreparedStatement relation=c.prepareStatement("INSERT INTO relationships(low_user_id,high_user_id,requester_id,addressee_id,status,requested_at,resolved_at) VALUES(?,?,?,?,'ACCEPTED',?,?)");
            PreparedStatement conversation=c.prepareStatement("INSERT INTO conversations(id,kind,direct_low_id,direct_high_id,last_seq,created_at,last_activity_at) VALUES(?,'DIRECT',?,?,5000,?,?)");
            PreparedStatement member=c.prepareStatement("INSERT INTO conversation_members(conversation_id,user_id,role,joined_at,visible_from_seq) VALUES(?,?,'MEMBER',?,1)")) {
                for(int pair=1;pair<=10;pair++) {
                    int a=pair*2-1,b=pair*2;
                    relation.setInt(1,a);
                    relation.setInt(2,b);
                    relation.setInt(3,a);
                    relation.setInt(4,b);
                    relation.setLong(5,now);
                    relation.setLong(6,now);
                    relation.addBatch();
                    conversation.setInt(1,pair);
                    conversation.setInt(2,a);
                    conversation.setInt(3,b);
                    conversation.setLong(4,now);
                    conversation.setLong(5,now);
                    conversation.addBatch();
                    for(int user:List.of(a,b)) {
                        member.setInt(1,pair);
                        member.setInt(2,user);
                        member.setLong(3,now);
                        member.addBatch();
                    }
                }
                relation.executeBatch();
                conversation.executeBatch();
                member.executeBatch();
            }
            try(PreparedStatement post=c.prepareStatement("INSERT INTO posts(author_id,content,visibility,created_at,updated_at) VALUES(?,?,?,?,?)")) {
                for(int i=0;i<10000;i++) {
                    post.setInt(1,i%20+1);
                    post.setString(2,"Fixture post "+i);
                    post.setString(3,i%10==0?"ONLY_ME":i%5==0?"FRIENDS":"PUBLIC");
                    post.setLong(4,now-i);
                    post.setLong(5,now-i);
                    post.addBatch();
                }
                post.executeBatch();
            }
            try(PreparedStatement message=c.prepareStatement("INSERT INTO messages(conversation_id,conversation_seq,sender_id,client_message_id,payload_hash,kind,content,created_at) VALUES(?,?,?,?,?,'TEXT',?,?)")) {
                for(int pair=1;pair<=10;pair++)for(int seq=1;seq<=5000;seq++) {
                    message.setInt(1,pair);
                    message.setInt(2,seq);
                    message.setInt(3,pair*2-(seq%2));
                    message.setString(4,UUID.randomUUID().toString());
                    message.setString(5,fingerprints.hash(SocialService.map("conversationId",pair,"content","Fixture history "+seq,"mediaId",null)));
                    message.setString(6,"Fixture history "+seq);
                    message.setLong(7,now-5000+seq);
                    message.addBatch();
                    if(seq%500==0)message.executeBatch();
                }
                message.executeBatch();
            }
            return null;
        }
        );
    }
    static boolean activeUser(SocialServer server,long user)throws Exception {
        java.lang.reflect.Field field=SocialServer.class.getDeclaredField("users");
        field.setAccessible(true);
        return ((Map<?,?>)field.get(server)).containsKey(user);
    }
    static int[] queueSnapshot(SocialServer server,long user)throws Exception {
        java.lang.reflect.Field field=SocialServer.class.getDeclaredField("users");
        field.setAccessible(true);
        Object connection=((Map<?,?>)field.get(server)).get(user);
        if(connection==null)return new int[] {
            0,0
        }
        ;
        java.lang.reflect.Field high=connection.getClass().getDeclaredField("high"),low=connection.getClass().getDeclaredField("low"),bytes=connection.getClass().getDeclaredField("bytes");
        high.setAccessible(true);
        low.setAccessible(true);
        bytes.setAccessible(true);
        Object queue=high.get(connection);
        synchronized(queue) {
            return new int[] {
                ((Collection<?>)queue).size()+((Collection<?>)low.get(connection)).size(),bytes.getInt(connection)
            }
            ;
        }
    }
    static long pressureDispatched(SocialDatabase db)throws Exception {
        return db.read(c-> {
            try(var statement=c.createStatement();
            var r=statement.executeQuery("SELECT count(*) FROM outbox_events WHERE aggregate_type='MESSAGE' AND aggregate_id=1 AND dispatched_at IS NOT NULL")) {
                r.next();
                return r.getLong(1);
            }
        }
        );
    }
    static void rawRequest(javax.net.ssl.SSLSocket socket,String type,Map<String,Object> payload)throws Exception {
        com.p2p.common.social.WireMessage request=com.p2p.common.social.WireMessage.request(type,payload);
        com.p2p.common.social.WireCodec.write(new DataOutputStream(socket.getOutputStream()),request);
        DataInputStream input=new DataInputStream(socket.getInputStream());
        for(;;) {
            com.p2p.common.social.WireMessage response=com.p2p.common.social.WireCodec.read(input);
            assertNotNull(response);
            if("EVENT".equals(response.getKind()))continue;
            assertEquals(request.getRequestId(),response.getRequestId());
            assertEquals(Boolean.TRUE,response.getOk(),()->String.valueOf(response.getError()));
            return;
        }
    }
    static void queueSlowReaderPressure(SocialDatabase db)throws Exception {
        db.write(c-> {
            try(PreparedStatement message=c.prepareStatement("UPDATE messages SET content=? WHERE id=1")) {
                message.setString(1,"x".repeat(3900));
                message.executeUpdate();
            }
            try(PreparedStatement events=c.prepareStatement("INSERT INTO outbox_events(event_id,type,aggregate_type,aggregate_id,aggregate_version,audience_json,created_at) VALUES(?,'MESSAGE_CREATED','MESSAGE',1,1,'[1]',?)")) {
                for(int i=0;i<5000;i++) {
                    events.setString(1,UUID.randomUUID().toString());
                    events.setLong(2,System.currentTimeMillis());
                    events.addBatch();
                }
                events.executeBatch();
            }
            return null;
        }
        );
    }
}

