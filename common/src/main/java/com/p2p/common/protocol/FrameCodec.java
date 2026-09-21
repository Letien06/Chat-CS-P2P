package com.p2p.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/** Length-prefixed UTF-8 JSON framing: [4-byte big-endian length][payload]. */
public final class FrameCodec {
    public static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    private FrameCodec() { }

    public static Message read(DataInputStream input) throws IOException {
        final int length;
        try {
            length = input.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete frame");
        try {
            return JsonCodec.decode(bytes);
        } catch (Exception ex) {
            throw new IOException("Invalid JSON message", ex);
        }
    }

    public static synchronized void write(DataOutputStream output, Message message) throws IOException {
        Objects.requireNonNull(message, "message");
        byte[] bytes;
        try {
            bytes = JsonCodec.encode(message);
        } catch (Exception ex) {
            throw new IOException("Could not encode message", ex);
        }
        if (bytes.length > MAX_FRAME_SIZE) throw new IOException("Message is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
}
