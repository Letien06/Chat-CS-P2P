package com.p2p.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.jupiter.api.Test;

class FrameCodecTest {
    @Test
    void roundTripPreservesUnicodePayload() throws Exception {
        Message original = Message.of(MessageType.PRIVATE_MESSAGE)
                .put("receiver", "bob")
                .put("content", "Xin chào 👋");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.write(new DataOutputStream(bytes), original);
        Message decoded = FrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(original.getType(), decoded.getType());
        assertEquals("Xin chào 👋", decoded.string("content"));
    }
}
