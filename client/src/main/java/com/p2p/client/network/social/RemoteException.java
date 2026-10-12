package com.p2p.client.network.social;
import java.util.Map;
public class RemoteException extends RuntimeException {
    private final String code;
    private final Map<String,Object> details;
    public RemoteException(String code,String message,Map<String,Object> details) {
        super(message);
        this.code=code;
        this.details=details;
    }
    public String getCode() {
        return code;
    }
    public Map<String,Object> getDetails() {
        return details;
    }
}
