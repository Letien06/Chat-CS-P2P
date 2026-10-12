package com.p2p.client.ui.social;

import com.p2p.client.network.social.SocialClient;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Small session cache. Clearing advances an epoch so late responses cannot restore revoked images. */
final class ThumbnailCache implements AutoCloseable {
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"social-thumbnail");t.setDaemon(true);return t;});
    private final LinkedHashMap<String,ImageIcon> cache=new LinkedHashMap<>(64,.75f,true);
    private final Map<String,CompletableFuture<ImageIcon>> pending=new HashMap<>();
    private SocialClient session;private long epoch;private boolean closed;
    synchronized void bind(SocialClient client){clear();session=client;}
    synchronized void clear(){epoch++;cache.clear();pending.clear();}
    void load(String mediaId,int size,Consumer<ImageIcon> ready){
        CompletableFuture<ImageIcon> future;long token;SocialClient expected;
        synchronized(this){if(closed||session==null||mediaId==null||mediaId.isBlank())return;token=epoch;expected=session;ImageIcon cached=cache.get(mediaId);if(cached!=null){ready.accept(scale(cached,size));return;}
            future=pending.get(mediaId);if(future==null){if(pending.size()>=64)return;try{future=CompletableFuture.supplyAsync(()->{
                    Map<String,Object> result=expected.request("MEDIA_THUMBNAIL_GET",Map.of("mediaId",mediaId)).join();try{byte[] bytes=Base64.getDecoder().decode(Values.str(result,"data"));if(bytes.length>512*1024||!"image/png".equals(Values.str(result,"mimeType")))throw new IOException("Thumbnail không hợp lệ");try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Thumbnail không phải ảnh");var reader=readers.next();try{reader.setInput(input);if(reader.getWidth(0)>256||reader.getHeight(0)>256||!"png".equalsIgnoreCase(reader.getFormatName()))throw new IOException("Thumbnail không hợp lệ");return new ImageIcon(reader.read(0));}finally{reader.dispose();}}}catch(IOException e){throw new CompletionException(e);}
                },workers);pending.put(mediaId,future);}catch(RejectedExecutionException e){return;}}
        }
        future.whenComplete((icon,error)->SwingUtilities.invokeLater(()->{synchronized(this){if(closed||session!=expected||epoch!=token)return;pending.remove(mediaId);if(error!=null)return;cache.put(mediaId,icon);while(cache.size()>64)cache.remove(cache.keySet().iterator().next());}ready.accept(scale(icon,size));}));
    }
    private static ImageIcon scale(ImageIcon source,int size){int w=source.getIconWidth(),h=source.getIconHeight();double factor=Math.min(1,Math.min((double)size/w,(double)size/h));if(factor==1)return source;return new ImageIcon(source.getImage().getScaledInstance(Math.max(1,(int)(w*factor)),Math.max(1,(int)(h*factor)),Image.SCALE_SMOOTH));}
    synchronized int size(){return cache.size();}
    public synchronized void close(){if(closed)return;closed=true;clear();session=null;workers.shutdownNow();}
}
