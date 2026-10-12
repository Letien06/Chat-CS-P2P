package com.p2p.common.social;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.*;
import java.io.*;
public final class TlsContexts {
    private TlsContexts() {
    }
    private static KeyStore store(Path p,char[] password)throws Exception {
        KeyStore k=KeyStore.getInstance("PKCS12");
        try(InputStream i=Files.newInputStream(p)) {
            k.load(i,password);
        }
        return k;
    }
    public static SSLContext server(Path p,char[] password)throws Exception {
        KeyManagerFactory k=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        k.init(store(p,password),password);
        SSLContext c=SSLContext.getInstance("TLS");
        c.init(k.getKeyManagers(),null,new SecureRandom());
        return c;
    }
    public static SSLContext client(Path p,char[] password)throws Exception {
        TrustManagerFactory t=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        t.init(store(p,password));
        SSLContext c=SSLContext.getInstance("TLS");
        c.init(null,t.getTrustManagers(),new SecureRandom());
        return c;
    }
}
