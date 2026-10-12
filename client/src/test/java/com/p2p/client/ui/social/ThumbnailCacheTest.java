package com.p2p.client.ui.social;

import com.p2p.common.social.WireMessage;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ThumbnailCacheTest {
    @Test void duplicateLoadsShareRequestAndCacheIsBounded()throws Exception{
        try(TransportStub transport=new TransportStub();ThumbnailCache cache=new ThumbnailCache()){
            cache.bind(transport.client);CountDownLatch first=new CountDownLatch(2);AtomicInteger wrongThread=new AtomicInteger();
            SwingUtilities.invokeAndWait(()->{for(int i=0;i<2;i++)cache.load("avatar",40,icon->{if(!SwingUtilities.isEventDispatchThread())wrongThread.incrementAndGet();assertTrue(icon.getIconWidth()<=40);first.countDown();});});
            WireMessage request=transport.take();assertEquals("MEDIA_THUMBNAIL_GET",request.getType());transport.send(WireMessage.response(request,png()));assertTrue(first.await(2,TimeUnit.SECONDS));assertEquals(0,wrongThread.get());assertEquals(1,cache.size());
            for(int i=0;i<65;i++){String id="photo-"+i;CountDownLatch loaded=new CountDownLatch(1);SwingUtilities.invokeAndWait(()->cache.load(id,128,icon->loaded.countDown()));transport.send(WireMessage.response(transport.take(),png()));assertTrue(loaded.await(2,TimeUnit.SECONDS));}
            assertEquals(64,cache.size());cache.bind(null);assertEquals(0,cache.size());
        }
    }
    @Test void permissionInvalidationDiscardsLateResponse()throws Exception{
        try(TransportStub transport=new TransportStub();ThumbnailCache cache=new ThumbnailCache()){
            cache.bind(transport.client);AtomicInteger displayed=new AtomicInteger();SwingUtilities.invokeAndWait(()->cache.load("private-photo",128,icon->displayed.incrementAndGet()));WireMessage request=transport.take();
            var pendingField=ThumbnailCache.class.getDeclaredField("pending");pendingField.setAccessible(true);CompletableFuture<?> pending=(CompletableFuture<?>)((Map<?,?>)pendingField.get(cache)).get("private-photo");
            cache.clear();transport.send(WireMessage.response(request,png()));pending.get(2,TimeUnit.SECONDS);SwingUtilities.invokeAndWait(()->{});Thread.sleep(30);SwingUtilities.invokeAndWait(()->{});
            assertEquals(0,displayed.get());assertEquals(0,cache.size());
        }
    }
    private Map<String,Object> png()throws Exception{BufferedImage image=new BufferedImage(64,40,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return Map.of("mimeType","image/png","data",Base64.getEncoder().encodeToString(out.toByteArray()));}
}
