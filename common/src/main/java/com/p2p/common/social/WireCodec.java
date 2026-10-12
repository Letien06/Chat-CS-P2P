package com.p2p.common.social;
import java.io.*;
public final class WireCodec {
    public static final int MAX_FRAME_BYTES=1048576;
    private WireCodec() {
    }
    public static byte[] encode(WireMessage m)throws IOException {
        byte[] b=Json.MAPPER.writeValueAsBytes(m);
        check(b.length);
        return b;
    }
    public static WireMessage decode(byte[] b)throws IOException {
        check(b.length);
        WireMessage m=Json.MAPPER.readValue(b,WireMessage.class);
        if(m==null||m.getKind()==null||m.getType()==null||m.getPayload()==null||!java.util.Set.of("REQUEST","RESPONSE","EVENT").contains(m.getKind()))throw new IOException("Invalid envelope");
        return m;
    }
    private static void check(int n)throws IOException {
        if(n<=0||n>MAX_FRAME_BYTES)throw new IOException("Invalid frame length");
    }
    public static WireMessage read(DataInputStream in)throws IOException {
        int first=in.read();
        if(first<0)return null;
        byte[] h=new byte[3];
        try {
            in.readFully(h);
        }
        catch(EOFException e) {
            throw new EOFException("Truncated frame header");
        }
        int n=(first<<24)|((h[0]&255)<<16)|((h[1]&255)<<8)|(h[2]&255);
        check(n);
        byte[] b=new byte[n];
        try {
            in.readFully(b);
        }
        catch(EOFException e) {
            throw new EOFException("Truncated frame payload");
        }
        return decode(b);
    }
    public static void write(DataOutputStream out,WireMessage m)throws IOException {
        byte[] b=encode(m);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }
}
