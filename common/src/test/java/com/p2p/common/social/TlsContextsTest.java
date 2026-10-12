package com.p2p.common.social;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import javax.net.ssl.*;
class TlsContextsTest {
 @TempDir Path dir;
 private void keytool(String... args)throws Exception{List<String> command=new ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString());command.addAll(List.of(args));ProcessBuilder pb=new ProcessBuilder(command);pb.environment().put("SOCIAL_TEST_TLS_PASSWORD","local-test-password");pb.redirectErrorStream(true);Process p=pb.start();String result=new String(p.getInputStream().readAllBytes());assertTrue(p.waitFor(30,TimeUnit.SECONDS),"keytool timed out");assertEquals(0,p.exitValue(),result);}
 @Test void acceptsTrustedSanAndRejectsUnknownTrustAndWrongHostname()throws Exception{
  Path store=dir.resolve("server.p12"),cert=dir.resolve("server.cer"),trust=dir.resolve("trust.p12");char[] password="local-test-password".toCharArray();
  keytool("-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-keystore",store.toString(),"-storetype","PKCS12","-storepass:env","SOCIAL_TEST_TLS_PASSWORD");
  keytool("-exportcert","-alias","test","-keystore",store.toString(),"-storepass:env","SOCIAL_TEST_TLS_PASSWORD","-file",cert.toString());
  keytool("-importcert","-noprompt","-alias","test","-file",cert.toString(),"-keystore",trust.toString(),"-storetype","PKCS12","-storepass:env","SOCIAL_TEST_TLS_PASSWORD");
  SSLContext client=TlsContexts.client(trust,password);
  try(SSLServerSocket server=(SSLServerSocket)TlsContexts.server(store,password).getServerSocketFactory().createServerSocket(0,10,InetAddress.getLoopbackAddress())){
   ExecutorService worker=Executors.newSingleThreadExecutor();Future<?> future=worker.submit(()->{for(int i=0;i<3;i++)try(SSLSocket s=(SSLSocket)server.accept()){s.setSoTimeout(5000);s.startHandshake();}catch(Exception expected){}});
   try{
    try(SSLSocket s=(SSLSocket)client.getSocketFactory().createSocket("localhost",server.getLocalPort())){s.setSoTimeout(5000);SSLParameters parameters=s.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");s.setSSLParameters(parameters);s.startHandshake();}
    try(Socket raw=new Socket("localhost",server.getLocalPort());SSLSocket wrong=(SSLSocket)client.getSocketFactory().createSocket(raw,"wrong.example",server.getLocalPort(),true)){wrong.setSoTimeout(5000);SSLParameters parameters=wrong.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");wrong.setSSLParameters(parameters);assertThrows(SSLHandshakeException.class,wrong::startHandshake);}
    SSLContext untrusted=SSLContext.getInstance("TLS");untrusted.init(null,null,null);try(SSLSocket s=(SSLSocket)untrusted.getSocketFactory().createSocket("localhost",server.getLocalPort())){s.setSoTimeout(5000);assertThrows(SSLHandshakeException.class,s::startHandshake);}
    future.get(15,TimeUnit.SECONDS);
   }finally{worker.shutdownNow();}
  }
 }
}
