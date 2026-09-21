package com.p2p.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** JSON conversion kept in common so wire format stays identical. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonCodec() { }

    public static byte[] encode(Message message) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(message);
    }

    public static Message decode(byte[] bytes) throws IOException {
        return MAPPER.readValue(bytes, Message.class);
    }
}
