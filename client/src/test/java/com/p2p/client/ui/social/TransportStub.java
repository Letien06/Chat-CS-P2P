package com.p2p.client.ui.social;

import com.p2p.client.network.social.SocialClient;
import com.p2p.common.social.*;
import java.net.Socket;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Exercises the real asynchronous reader/writer with controlled in-memory framed streams. */
final class TransportStub implements AutoCloseable {
    final SocialClient client=new SocialClient("localhost",5000);
    private final PipedInputStream clientInput=new PipedInputStream(1<<20);
    private final DataOutputStream serverOutput=new DataOutputStream(new PipedOutputStream(clientInput));
    private final PipedInputStream serverInput=new PipedInputStream(1<<20);
    private final PipedOutputStream clientOutput=new PipedOutputStream(serverInput);
    private final ExecutorService threads=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"test-transport");t.setDaemon(true);return t;});
    TransportStub()throws Exception {
        Field socket=SocialClient.class.getDeclaredField("socket");socket.setAccessible(true);socket.set(client,new StreamSocket());
        for(String method:new String[]{"reader","writer"}){Method m=SocialClient.class.getDeclaredMethod(method);m.setAccessible(true);threads.execute(()->{try{m.invoke(client);}catch(Exception ignored){}});}
    }
    WireMessage take()throws Exception{return CompletableFuture.supplyAsync(()->{try{return WireCodec.read(new DataInputStream(serverInput));}catch(IOException e){throw new CompletionException(e);}}).get(3,TimeUnit.SECONDS);}
    void send(WireMessage message)throws IOException{WireCodec.write(serverOutput,message);}
    void dropServer()throws IOException{serverOutput.close();}
    public void close(){client.close();threads.shutdownNow();try{serverOutput.close();serverInput.close();}catch(IOException ignored){}}
    private final class StreamSocket extends Socket{
        public InputStream getInputStream(){return clientInput;}public OutputStream getOutputStream(){return clientOutput;}
        public void close()throws IOException{clientInput.close();clientOutput.close();}
    }
}
