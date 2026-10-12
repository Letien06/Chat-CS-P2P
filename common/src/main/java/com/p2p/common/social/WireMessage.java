package com.p2p.common.social;
import java.util.*;
public class WireMessage {
    private int protocolVersion=2;
    private String kind,type,requestId,idempotencyKey,eventId;
    private Boolean ok;
    private Long serverTime;
    private Map<String,Object> error,payload=new LinkedHashMap<>();
    public WireMessage() {
    }
    public static WireMessage request(String type,Map<String,Object> payload) {
        WireMessage m=new WireMessage();
        m.kind="REQUEST";
        m.type=type;
        m.requestId=UUID.randomUUID().toString();
        m.payload=payload==null?new LinkedHashMap<>():payload;
        return m;
    }
    public static WireMessage response(WireMessage r,Map<String,Object> payload) {
        WireMessage m=new WireMessage();
        m.kind="RESPONSE";
        m.type=r.type;
        m.requestId=r.requestId;
        m.ok=true;
        m.serverTime=System.currentTimeMillis();
        m.payload=payload==null?new LinkedHashMap<>():payload;
        return m;
    }
    public static WireMessage failure(WireMessage r,String code,String message) {
        WireMessage m=response(r,null);
        m.ok=false;
        m.error=Map.of("code",code,"message",message,"retryable",false);
        return m;
    }
    public static WireMessage event(String type,Map<String,Object> payload) {
        WireMessage m=new WireMessage();
        m.kind="EVENT";
        m.type=type;
        m.eventId=UUID.randomUUID().toString();
        m.serverTime=System.currentTimeMillis();
        m.payload=payload==null?new LinkedHashMap<>():payload;
        return m;
    }
    public int getProtocolVersion() {
        return protocolVersion;
    }
    public void setProtocolVersion(int v) {
        protocolVersion=v;
    }
    public String getKind() {
        return kind;
    }
    public void setKind(String v) {
        kind=v;
    }
    public String getType() {
        return type;
    }
    public void setType(String v) {
        type=v;
    }
    public String getRequestId() {
        return requestId;
    }
    public void setRequestId(String v) {
        requestId=v;
    }
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    public void setIdempotencyKey(String v) {
        idempotencyKey=v;
    }
    public String getEventId() {
        return eventId;
    }
    public void setEventId(String v) {
        eventId=v;
    }
    public Boolean getOk() {
        return ok;
    }
    public void setOk(Boolean v) {
        ok=v;
    }
    public Long getServerTime() {
        return serverTime;
    }
    public void setServerTime(Long v) {
        serverTime=v;
    }
    public Map<String,Object> getError() {
        return error;
    }
    public void setError(Map<String,Object> v) {
        error=v;
    }
    public Map<String,Object> getPayload() {
        return payload;
    }
    public void setPayload(Map<String,Object> v) {
        payload=v;
    }
}
