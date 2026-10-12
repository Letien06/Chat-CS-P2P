import com.formdev.flatlaf.FlatDarkLaf;
import com.p2p.client.network.social.*;
import com.p2p.client.ui.social.SocialWindow;
import com.p2p.common.social.*;
import com.p2p.server.social.SocialServer;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Packaged-JAR smoke: real TLS clients, real UDP and hidden actual Swing renders. */
public final class ReleaseSmoke {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();Path file=args.length>1?Path.of(args[1]):root.resolve("server/config/social.properties");
        SocialServer.Config base=SocialServer.Config.load(file);SocialServer.Config config=new SocialServer.Config(base.databasePath(),base.storageRoot(),"127.0.0.1",0,0,0,base.maxConnections(),base.tls(),base.userQuota(),base.serverQuota());
        String password=System.getenv("SOCIAL_DEMO_PASSWORD");if(password==null||password.length()<10)throw new IllegalArgumentException("Set SOCIAL_DEMO_PASSWORD for seeded demo accounts");
        AtomicReference<Throwable> serverError=new AtomicReference<>();SocialServer server=new SocialServer(config);Thread serving=new Thread(()->{try{server.start();}catch(Throwable e){serverError.set(e);}},"release-smoke-server");serving.start();
        SocialClient alice=null,bob=null;UdpClient aliceUdp=null,bobUdp=null;SocialWindow[] window={null};
        try{
            await(()->server.tcpPort()>0&&server.discoveryPort()>0&&server.typingPort()>0,5000);if(serverError.get()!=null)throw new RuntimeException(serverError.get());
            String storePassword=Objects.toString(System.getenv("SOCIAL_TRUSTSTORE_PASSWORD"),System.getenv("SOCIAL_TLS_PASSWORD"));if(storePassword==null)throw new IllegalArgumentException("Set SOCIAL_TLS_PASSWORD or SOCIAL_TRUSTSTORE_PASSWORD");
            char[] trustSecret=storePassword.toCharArray();javax.net.ssl.SSLContext tls;try{tls=TlsContexts.client(root.resolve("config/tls/truststore.p12"),trustSecret);}finally{Arrays.fill(trustSecret,'\0');}
            alice=new SocialClient("127.0.0.1",server.tcpPort(),tls);bob=new SocialClient("127.0.0.1",server.tcpPort(),tls);Map<String,Object> hello=alice.connect().get(8,TimeUnit.SECONDS);bob.connect().get(8,TimeUnit.SECONDS);check(number(hello,"selectedVersion")==2,"HELLO v2");
            Map<String,Object> aliceLogin=request(alice,"AUTH_LOGIN",Map.of("username","demo_alice","password",password)),bobLogin=request(bob,"AUTH_LOGIN",Map.of("username","demo_bob","password",password));Map<String,Object> a=user(aliceLogin),b=user(bobLogin);long aid=number(a,"id","userId"),bid=number(b,"id","userId");check(aid>0&&bid>0,"Seeded accounts authenticated");
            List<Map<String,Object>> posts=rows(request(alice,"FEED_GET",Map.of("limit",20)).get("items"));check(!posts.isEmpty(),"Seeded feed");
            List<Map<String,Object>> conversations=rows(request(alice,"CONVERSATION_LIST",Map.of("limit",20)).get("items"));check(conversations.stream().anyMatch(c->"GROUP".equals(c.get("kind"))),"Seeded group");
            long cid=number(alice.request("CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",bid),UUID.randomUUID().toString()).get(5,TimeUnit.SECONDS),"conversationId");check(!rows(request(alice,"MESSAGE_HISTORY",Map.of("conversationId",cid,"limit",20)).get("items")).isEmpty(),"Offline history");request(alice,"NOTIFICATION_LIST",Map.of("limit",20,"unreadOnly",false));
            String image=String.valueOf(a.get("avatarMediaId"));if(image.equals("null"))image=posts.stream().flatMap(p->((List<?>)p.getOrDefault("mediaIds",List.of())).stream()).map(String::valueOf).findFirst().orElseThrow();Map<String,Object> thumbnail=request(alice,"MEDIA_THUMBNAIL_GET",Map.of("mediaId",image));BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(String.valueOf(thumbnail.get("data")))));check(decoded!=null&&decoded.getWidth()<=256&&decoded.getHeight()<=256,"Authorized bounded thumbnail");
            List<UdpClient.Offer> offers=UdpClient.discover("127.0.0.1",server.discoveryPort()).get(5,TimeUnit.SECONDS);check(offers.stream().anyMatch(o->o.tcpPort()==server.tcpPort()&&o.serverId().equals(hello.get("serverId"))),"UDP discovery matches TLS serverId");
            aliceUdp=new UdpClient("127.0.0.1",server.typingPort());bobUdp=new UdpClient("127.0.0.1",server.typingPort());aliceUdp.configure(udp(aliceLogin),alice.serverTime());bobUdp.configure(udp(bobLogin),bob.serverTime());UdpClient ua=aliceUdp,ub=bobUdp;await(()->ua.isRegistered()&&ub.isRegistered(),5000);CountDownLatch typed=new CountDownLatch(1);bobUdp.onTyping(event->{if(number(event,"conversationId")==cid&&Boolean.TRUE.equals(event.get("typing")))typed.countDown();});aliceUdp.typing(cid,true);check(typed.await(3,TimeUnit.SECONDS),"Authenticated UDP typing");
            BlockingQueue<WireMessage> bobMessages=new LinkedBlockingQueue<>(),aliceReceipts=new LinkedBlockingQueue<>();bob.onEvent(e->{if("MESSAGE_CREATED".equals(e.getType()))bobMessages.add(e);});alice.onEvent(e->{if("RECEIPT_UPDATED".equals(e.getType()))aliceReceipts.add(e);});
            String clientId=UUID.randomUUID().toString(),idempotency=UUID.randomUUID().toString();Map<String,Object> send=Map.of("conversationId",cid,"clientMessageId",clientId,"content","Packaged release smoke · Unicode tiếng Việt 👋");Map<String,Object> ack=alice.request("MESSAGE_SEND",send,idempotency).get(5,TimeUnit.SECONDS),again=alice.request("MESSAGE_SEND",send,idempotency).get(5,TimeUnit.SECONDS);check(number(ack,"messageId")==number(again,"messageId"),"Retry deduplicated");
            WireMessage received=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<deadline){WireMessage event=bobMessages.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&clientId.equals(event.getPayload().get("clientMessageId"))){received=event;break;}}check(received!=null,"Committed chat delivered");long seq=number(ack,"conversationSeq");bob.request("MESSAGE_DELIVERED",Map.of("conversationId",cid,"throughSeq",seq),UUID.randomUUID().toString()).get(5,TimeUnit.SECONDS);bob.request("MESSAGE_READ",Map.of("conversationId",cid,"throughSeq",seq),UUID.randomUUID().toString()).get(5,TimeUnit.SECONDS);check(aliceReceipts.poll(4,TimeUnit.SECONDS)!=null,"Receipt event");request(alice,"MESSAGE_LOOKUP",Map.of("clientMessageId",clientId));
            Path qa=root.resolve("qa");Files.createDirectories(qa);SocialClient liveAlice=alice;Map<String,Object> direct=conversations.stream().filter(c->"DIRECT".equals(c.get("kind"))).findFirst().orElse(Map.of("conversationId",cid,"title","Demo Bob","kind","DIRECT"));
            SwingUtilities.invokeAndWait(()->{try{FlatDarkLaf.setup();SocialWindow w=new SocialWindow();window[0]=w;set(w,"client",liveAlice);set(w,"authenticated",true);set(w,"currentUser",new LinkedHashMap<>(a));set(w,"host","127.0.0.1");set(w,"port",server.tcpPort());Object cache=get(w,"thumbnails");Method bind=cache.getClass().getDeclaredMethod("bind",SocialClient.class);bind.setAccessible(true);bind.invoke(cache,liveAlice);liveAlice.onEvent(event->SwingUtilities.invokeLater(()->{try{call(w,"onEvent",new Class<?>[]{WireMessage.class},event);}catch(Exception e){throw new RuntimeException(e);}}));call(w,"showMain",new Class<?>[0]);call(w,"showFeed",new Class<?>[0]);w.addNotify();w.validate();}catch(Exception e){throw new RuntimeException(e);}});
            await(()->edtContains(window[0],"demonstration"),6000);Thread.sleep(400);SwingUtilities.invokeAndWait(()->{try{paint(window[0],qa.resolve("live-feed.png"));call(window[0],"openChat",new Class<?>[]{Map.class},direct);}catch(Exception e){throw new RuntimeException(e);}});
            await(()->edtContains(window[0],"Packaged release smoke"),6000);SwingUtilities.invokeAndWait(()->{try{paint(window[0],qa.resolve("live-chat.png"));}catch(Exception e){throw new RuntimeException(e);}});
            System.out.println("PASS packaged TLS auth/feed/group/history/notifications/thumbnail/chat/retry/receipt + UDP discovery/typing + live Swing paint");System.out.println("Screenshots: "+qa.resolve("live-feed.png")+" ; "+qa.resolve("live-chat.png"));
        }finally{
            if(window[0]!=null)SwingUtilities.invokeAndWait(()->{try{call(window[0],"shutdown",new Class<?>[0]);window[0].dispose();}catch(Exception ignored){}});if(aliceUdp!=null)aliceUdp.close();if(bobUdp!=null)bobUdp.close();if(alice!=null)alice.close();if(bob!=null)bob.close();server.close();serving.join(5000);check(!serving.isAlive(),"Server listener closed");
        }
    }
    private static Map<String,Object> request(SocialClient c,String type,Map<String,Object> p)throws Exception{return c.request(type,p).get(5,TimeUnit.SECONDS);}
    @SuppressWarnings("unchecked") private static Map<String,Object> user(Map<String,Object> m){return (Map<String,Object>)m.getOrDefault("currentUser",m.get("user"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> udp(Map<String,Object> m){return (Map<String,Object>)m.getOrDefault("udp",m);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object o){return o instanceof List<?> l?(List<Map<String,Object>>)(List<?>)l:List.of();}
    private static long number(Map<String,Object> m,String...keys){for(String k:keys)if(m.get(k) instanceof Number n)return n.longValue();return 0;}
    private static void check(boolean value,String description){if(!value)throw new AssertionError(description);}
    private static void await(BooleanSupplier condition,long millis)throws Exception{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(25);}throw new AssertionError("Timed out waiting for runtime/UI state");}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static boolean edtContains(SocialWindow w,String text){AtomicReference<Boolean> found=new AtomicReference<>(false);try{SwingUtilities.invokeAndWait(()->found.set(contains(w.getContentPane(),text)));return found.get();}catch(Exception e){throw new RuntimeException(e);}}
    private static boolean contains(Container c,String text){for(Component child:c.getComponents()){if(child instanceof JTextArea a&&a.getText().contains(text))return true;if(child instanceof Container nested&&contains(nested,text))return true;}return false;}
    private static void paint(SocialWindow w,Path target)throws Exception{w.validate();BufferedImage image=new BufferedImage(w.getContentPane().getWidth(),w.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();w.getContentPane().printAll(g);g.dispose();ImageIO.write(image,"png",target.toFile());}
}
