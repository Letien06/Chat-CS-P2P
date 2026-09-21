package com.p2p.server.session;

import com.p2p.common.protocol.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private final ConcurrentHashMap<Long, Session> byId = new ConcurrentHashMap<>();

    public boolean add(Session session) {
        if (session.user() == null) return false;
        return byId.putIfAbsent(session.user().id(), session) == null;
    }

    public void remove(Session session) {
        if (session.user() != null) byId.remove(session.user().id(), session);
    }

    public Session byId(long id) { return byId.get(id); }

    public Session byUsername(String username) {
        if (username == null) return null;
        for (Session session : byId.values()) {
            if (session.user() != null && session.user().username().equalsIgnoreCase(username)) return session;
        }
        return null;
    }

    public List<String> usernames() {
        List<String> names = new ArrayList<>();
        for (Session session : byId.values()) if (session.user() != null) names.add(session.user().username());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void broadcast(Message message) {
        for (Session session : byId.values()) sendQuietly(session, message);
    }

    public void broadcastExcept(Session excluded, Message message) {
        for (Session session : byId.values()) if (session != excluded) sendQuietly(session, message);
    }

    private void sendQuietly(Session session, Message message) {
        try { session.send(message); } catch (IOException ignored) { session.close(); }
    }
}
