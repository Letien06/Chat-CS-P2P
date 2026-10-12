package com.p2p.client.ui.social;

import com.p2p.client.network.social.SocialClient;
import com.p2p.common.social.*;
import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Exercises the real asynchronous reader/writer with controlled in-memory framed streams. */
final class TransportStub implements AutoCloseable {
    final SocialClient client=new SocialClient("localhost",5000,null);
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
    private final class StreamSocket extends SSLSocket{
        public InputStream getInputStream(){return clientInput;}public OutputStream getOutputStream(){return clientOutput;}
        public void close()throws IOException{clientInput.close();clientOutput.close();}
        public String[] getSupportedCipherSuites(){return new String[0];}public String[] getEnabledCipherSuites(){return new String[0];}public void setEnabledCipherSuites(String[] s){}
        public String[] getSupportedProtocols(){return new String[0];}public String[] getEnabledProtocols(){return new String[0];}public void setEnabledProtocols(String[] s){}
        public SSLSession getSession(){return null;}public void addHandshakeCompletedListener(HandshakeCompletedListener l){}public void removeHandshakeCompletedListener(HandshakeCompletedListener l){}
        public void startHandshake(){}public void setUseClientMode(boolean b){}public boolean getUseClientMode(){return true;}public void setNeedClientAuth(boolean b){}public boolean getNeedClientAuth(){return false;}
        public void setWantClientAuth(boolean b){}public boolean getWantClientAuth(){return false;}public void setEnableSessionCreation(boolean b){}public boolean getEnableSessionCreation(){return false;}
    }
}
