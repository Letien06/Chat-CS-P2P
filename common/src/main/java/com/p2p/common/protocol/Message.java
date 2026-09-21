package com.p2p.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A small envelope shared by server and client. The payload is JSON-friendly. */
public class Message {
    private MessageType type;
    private String requestId;
    private Map<String, Object> payload;

    public Message() {
        // Jackson constructor
    }

    public Message(MessageType type) {
        this(type, UUID.randomUUID().toString());
    }

    public Message(MessageType type, String requestId) {
        this.type = type;
        this.requestId = requestId;
        this.payload = new LinkedHashMap<>();
    }

    public static Message of(MessageType type) {
        return new Message(type);
    }

    public Message put(String key, Object value) {
        if (payload == null) payload = new LinkedHashMap<>();
        payload.put(key, value);
        return this;
    }

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String string(String key) {
        Object value = get(key);
        return value == null ? null : String.valueOf(value);
    }

    public long longValue(String key, long defaultValue) {
        Object value = get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public boolean bool(String key, boolean defaultValue) {
        Object value = get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    public Message success(boolean success, String text) {
        return put("success", success).put("message", text);
    }

    public Message error(String text) {
        return success(false, text);
    }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
