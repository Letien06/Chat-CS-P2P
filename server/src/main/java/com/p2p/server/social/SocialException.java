package com.p2p.server.social;
import java.util.Map;
public final class SocialException extends Exception {
    private final String code;
    private final Map<String,Object> details;
    public SocialException(String code,String message) {
        this(code,message,Map.of());
    }
    public SocialException(String code,String message,Map<String,Object> details) {
        super(message);
        this.code=code;
        this.details=Map.copyOf(details);
    }
    public String code() {
        return code;
    }
    public Map<String,Object> details() {
        return details;
    }
}
