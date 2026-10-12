package com.p2p.server.social;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;
final class Passwords {
    static final int ITERATIONS=600000;
    static String salt() {
        byte[] b=new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }
    static String hash(String password,String salt,int iterations)throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),iterations,256);
        try {
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        }
        finally {
            spec.clearPassword();
        }
    }
    static boolean verify(String password,String salt,int iterations,String hash)throws Exception {
        return MessageDigest.isEqual(Base64.getDecoder().decode(hash(password,salt,iterations)),Base64.getDecoder().decode(hash));
    }
}

