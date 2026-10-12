package com.p2p.client.ui.social;

import com.p2p.client.network.social.RemoteException;
import com.p2p.common.social.WireMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SocialTransportTest {
    @Test void interleavedEventsAndOutOfOrderResponsesDoNotResolveWrongRequest()throws Exception{
        try(TransportStub transport=new TransportStub()){
            BlockingQueue<WireMessage> events=new LinkedBlockingQueue<>();transport.client.onEvent(events::add);
            var first=transport.client.request("POST_GET",Map.of("postId",1));var second=transport.client.request("PROFILE_GET",Map.of("targetUserId",2));
            WireMessage a=transport.take(),b=transport.take();transport.send(WireMessage.event("POST_INVALIDATED",Map.of("postId",1)));
            assertNotNull(events.poll(2,TimeUnit.SECONDS));assertFalse(first.isDone());assertFalse(second.isDone());
            transport.send(WireMessage.response(b,Map.of("profile",2)));transport.send(WireMessage.response(a,Map.of("post",1)));
            assertEquals(1,first.get(2,TimeUnit.SECONDS).get("post"));assertEquals(2,second.get(2,TimeUnit.SECONDS).get("profile"));
        }
    }
    @Test void invalidSessionFailsRequestWithCodeAndDetails()throws Exception{
        try(TransportStub transport=new TransportStub()){
            var pending=transport.client.request("POST_CREATE",Map.of("content","Xin chào"),UUID.randomUUID().toString());WireMessage request=transport.take();
            WireMessage failure=WireMessage.failure(request,"UNAUTHENTICATED","Phiên hết hạn");failure.setPayload(Map.of("authVersion",2));transport.send(failure);
            ExecutionException error=assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));RemoteException remote=assertInstanceOf(RemoteException.class,error.getCause());
            assertEquals("UNAUTHENTICATED",remote.getCode());assertEquals(2,remote.getDetails().get("authVersion"));
        }
    }
    @Test void disconnectClosesEveryPendingAndFiresOnce()throws Exception{
        try(TransportStub transport=new TransportStub()){
            AtomicInteger disconnects=new AtomicInteger();CountDownLatch disconnected=new CountDownLatch(1);transport.client.onDisconnect(e->{disconnects.incrementAndGet();disconnected.countDown();});
            List<CompletableFuture<Map<String,Object>>> requests=new ArrayList<>();for(int i=0;i<20;i++)requests.add(transport.client.request("POST_GET",Map.of("postId",i+1)));
            transport.dropServer();assertTrue(disconnected.await(2,TimeUnit.SECONDS));for(var future:requests)assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));
            transport.client.close();assertEquals(1,disconnects.get());assertFalse(transport.client.isConnected());assertTrue(transport.client.request("PING",Map.of()).isCompletedExceptionally());
        }
    }
}
