package com.p2p.common.social;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
/** Authenticated UDP framing; signs exact body bytes and separates each direction. */
public final class UdpCodec {
    public static final int MAX_BYTES=1200;
    private UdpCodec() {
    }
    public static byte[] sign(Map<String,Object> body,byte[] key,boolean server)throws Exception {
        byte[] raw=Json.MAPPER.writeValueAsBytes(body);
        byte[] result=Json.MAPPER.writeValueAsBytes(Map.of("sessionId",body.get("sessionId"),"keyEpoch",body.get("keyEpoch"),"body",Base64.getUrlEncoder().withoutPadding().encodeToString(raw),"mac",Base64.getUrlEncoder().withoutPadding().encodeToString(mac(raw,key,server))));
        if(result.length>MAX_BYTES)throw new IOException("UDP oversize");
        return result;
    }
    private static byte[] mac(byte[] raw,byte[] key,boolean server)throws Exception {
        Mac m=Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key,"HmacSHA256"));
        m.update(("social-udp-v2:"+(server?"S2C:":"C2S:")).getBytes(StandardCharsets.UTF_8));
        return m.doFinal(raw);
    }
    public static Map<String,Object> outer(byte[] bytes)throws IOException {
        if(bytes.length>MAX_BYTES||bytes.length<1)throw new IOException("UDP size");
        return Json.MAPPER.readValue(bytes,Map.class);
    }
    public static Map<String,Object> verify(byte[] bytes,byte[] key,boolean server)throws Exception {
        Map<String,Object> o=outer(bytes);
        byte[] b=Base64.getUrlDecoder().decode((String)o.get("body"));
        byte[] signature=Base64.getUrlDecoder().decode((String)o.get("mac"));
        if(!MessageDigest.isEqual(signature,mac(b,key,server)))throw new IOException("UDP authentication failed");
        Map<String,Object> body=Json.MAPPER.readValue(b,Map.class);
        if(!Objects.equals(o.get("sessionId"),body.get("sessionId"))||number(o.get("keyEpoch"))!=number(body.get("keyEpoch"))||number(body.get("v"))!=2)throw new IOException("UDP envelope mismatch");
        return body;
    }
    public static long number(Object n) {
        return n instanceof Number?((Number)n).longValue():Long.MIN_VALUE;
    }
}
