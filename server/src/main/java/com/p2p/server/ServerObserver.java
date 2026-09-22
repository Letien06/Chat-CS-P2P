package com.p2p.server;

import com.p2p.common.protocol.MessageType;

public interface ServerObserver {
    ServerObserver NONE = new ServerObserver() { };

    default void serverStarted(int port, String databasePath, int maxClients) { }
    default void clientConnected(String remoteAddress) { }
    default void clientJoined(String remoteAddress, String username) { }
    default void requestReceived(String username, MessageType type) { }
    default void clientDisconnected(String remoteAddress, String username) { }
    default void serverStopped() { }
    default void serverError(String message) { }
}
