package com.p2p.common.social;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.StreamReadConstraints;
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {
        MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(1048576).build());
    }
    private Json() {
    }
}
