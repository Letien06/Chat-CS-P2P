package com.p2p.client.ui.social;

import com.formdev.flatlaf.FlatDarkLaf;
import com.p2p.common.social.WireMessage;
import org.junit.jupiter.api.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.p2p.client.ui.social.Values.*;

/** State regressions and actual component painting; no desktop input or Robot automation. */
class SocialDesktopStateTest {
    SocialWindow window;
    @BeforeEach void setup()throws Exception {Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());edt(()->{FlatDarkLaf.setup();window=new SocialWindow();return null;});}
    @AfterEach void cleanup()throws Exception {if(window!=null)edt(()->{invoke("shutdown",new Class<?>[0]);window.dispose();return null;});}
    @Test void ackAfterEventPreservesReceiptAndCreatedTimeAndCacheIsBounded()throws Exception{
        edt(()->{invoke("upsertMessage",new Class<?>[]{Map.class},of("messageId",1,"conversationSeq",1,"status","READ","createdAt",1791331200000L,"content","Xin chào 👋"));
            invoke("upsertMessage",new Class<?>[]{Map.class},of("messageId",1,"conversationSeq",1,"status","STORED"));
            Map<Long,Map<String,Object>> messages=field("messages");assertEquals("READ",messages.get(1L).get("status"));assertEquals(1791331200000L,messages.get(1L).get("createdAt"));
            for(long i=2;i<=220;i++)invoke("upsertMessage",new Class<?>[]{Map.class},of("messageId",i,"conversationSeq",i,"content","Tin "+i));assertEquals(200,messages.size());return null;});
    }
    @Test void attachmentRetryKeepsCaptionClientIdAndIdempotencyKey()throws Exception{
        try(TransportStub transport=new TransportStub()){
            edt(()->{set("client",transport.client);set("authenticated",true);set("currentUser",of("id",7,"displayName","Tiến"));invoke("sendMessage",new Class<?>[]{long.class,String.class,String.class},42L,"d51d9eec-9f0a-47c3-863f-a52eb36da653","Chú thích tài liệu 📎");return null;});
            WireMessage original=transport.take();assertEquals("MESSAGE_SEND",original.getType());assertEquals("Chú thích tài liệu 📎",original.getPayload().get("content"));
            transport.send(WireMessage.failure(original,"SERVER_BUSY","Bận"));await(()->{try{return edt(()->!fieldMap("pendingMessages").isEmpty()&&!pendingStatus().equals("Đang gửi…"));}catch(Exception e){throw new RuntimeException(e);}});
            edt(()->{Object pending=fieldMap("pendingMessages").values().iterator().next();invoke("retryMessage",new Class<?>[]{pending.getClass()},pending);return null;});
            WireMessage lookup=transport.take();assertEquals("MESSAGE_LOOKUP",lookup.getType());assertEquals(original.getPayload().get("clientMessageId"),lookup.getPayload().get("clientMessageId"));transport.send(WireMessage.failure(lookup,"NOT_FOUND","Chưa có"));
            WireMessage retried=transport.take();assertEquals(original.getPayload(),retried.getPayload());assertEquals(original.getIdempotencyKey(),retried.getIdempotencyKey());assertNotEquals(original.getRequestId(),retried.getRequestId());
            transport.send(WireMessage.response(retried,of("messageId",91,"conversationSeq",1,"status","STORED")));await(()->{try{return edt(()->fieldMap("pendingMessages").isEmpty());}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    @Test void sessionClearRemovesPrivateStateAndWindows()throws Exception{
        edt(()->{set("currentUser",of("id",9,"bio","Riêng tư"));invoke("upsertMessage",new Class<?>[]{Map.class},of("messageId",1,"conversationSeq",1,"content","Bí mật"));JDialog d=new JDialog(window);d.add(new JLabel("Ảnh riêng"));d.addNotify();
            invoke("clearSession",new Class<?>[0]);assertTrue(fieldMap("messages").isEmpty());assertTrue(fieldMap("currentUser").isEmpty());assertTrue(fieldMap("pendingMessages").isEmpty());assertFalse(d.isDisplayable());return null;});
    }
    @Test void attachmentCardRetainsServerOriginalFileName()throws Exception{
        edt(()->{JPanel list=new JPanel();list.setLayout(new BoxLayout(list,BoxLayout.Y_AXIS));set("chatMessages",list);set("chatScroll",new JScrollPane(list));set("activeConversation",42L);set("currentUser",of("id",7));set("activeConversationData",of("kind","DIRECT"));
            invoke("upsertMessage",new Class<?>[]{Map.class},of("messageId",2,"conversationId",42,"conversationSeq",2,"senderId",8,"content","Sample notes attachment","mediaId","opaque-uuid","media",of("mediaId","opaque-uuid","originalName","demo-notes.txt")));
            invoke("renderMessages",new Class<?>[]{boolean.class},true);assertTrue(containsButton(list,"demo-notes.txt"));return null;});
    }
    @Test void paintAuthAndMainCardsAtNormalAndMinimumSize()throws Exception{
        Path output=Path.of("..",".tmp","ui-qa").toAbsolutePath().normalize();Files.createDirectories(output);
        try(TransportStub transport=new TransportStub()){
        edt(()->{window.addNotify();window.validate();paint(output.resolve("auth-1120.png"));window.setSize(900,620);window.validate();paint(output.resolve("auth-900.png"));invoke("showAuth",new Class<?>[]{boolean.class},true);window.validate();paint(output.resolve("register-900.png"));window.setSize(1120,780);set("currentUser",of("id",7,"displayName","Lê Cao Sơn Tiến","role","USER","avatarMediaId","avatar-fixture"));ThumbnailCache cache=field("thumbnails");cache.bind(transport.client);
            // Main chrome uses fixture data; thumbnail responses go through the real framed client.
            set("authenticated",false);invoke("showMain",new Class<?>[0]);JLabel status=field("connection");status.setText("Kiểm tra bố cục · dữ liệu mẫu · chưa kết nối");JPanel content=(JPanel)invoke("vertical",new Class<?>[0]);
            Map<String,Object> post=of("postId",10,"authorId",7,"authorName","Lê Cao Sơn Tiến","authorAvatarMediaId","avatar-fixture","mediaIds",java.util.List.of("photo-fixture"),"content","<html>Đây là văn bản thuần, không được render HTML.\nCùng nhau học lập trình mạng! 👋\n"+"Dòng dài tiếng Việt để kiểm tra xuống dòng. ".repeat(9),"visibility","FRIENDS","createdAt",1791331200000L,"version",2,"likeCount",12,"commentCount",3);
            JComponent card=(JComponent)invoke("postCard",new Class<?>[]{Map.class},post);card.setMaximumSize(new Dimension(Integer.MAX_VALUE,card.getPreferredSize().height));content.add(card);JPanel p=new JPanel(new BorderLayout());p.add(new JScrollPane(content));invoke("setView",new Class<?>[]{String.class,JPanel.class,Runnable.class},"feed",p,(Runnable)()->{});
            window.validate();assertEquals("<html>Đây là văn bản thuần, không được render HTML.\nCùng nhau học lập trình mạng! 👋\n"+"Dòng dài tiếng Việt để kiểm tra xuống dòng. ".repeat(9),post.get("content"));return null;});
        BufferedImage fixture=new BufferedImage(256,160,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=fixture.createGraphics();graphics.setPaint(new GradientPaint(0,0,new Color(72,82,205),256,160,new Color(20,184,166)));graphics.fillRect(0,0,256,160);graphics.setColor(Color.WHITE);graphics.drawString("Ảnh mẫu kiểm tra bố cục",28,80);graphics.dispose();java.io.ByteArrayOutputStream png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(fixture,"png",png);Map<String,Object> response=of("mimeType","image/png","data",Base64.getEncoder().encodeToString(png.toByteArray()));
        for(int i=0;i<2;i++)transport.send(WireMessage.response(transport.take(),response));await(()->{try{return edt(()->((ThumbnailCache)field("thumbnails")).size()==2);}catch(Exception e){throw new RuntimeException(e);}});
        edt(()->{window.validate();paint(output.resolve("main-cards-1120.png"));window.setSize(900,620);window.validate();paint(output.resolve("main-cards-900.png"));return null;});
        }
    }
    private void paint(Path output)throws Exception{BufferedImage image=new BufferedImage(window.getContentPane().getWidth(),window.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();window.getContentPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",output.toFile());}
    private static boolean containsButton(Container parent,String text){for(Component c:parent.getComponents()){if(c instanceof JButton b&&b.getText().contains(text))return true;if(c instanceof Container nested&&containsButton(nested,text))return true;}return false;}
    private String pendingStatus()throws Exception{Object p=fieldMap("pendingMessages").values().iterator().next();Field status=p.getClass().getDeclaredField("status");status.setAccessible(true);return (String)status.get(p);}
    @SuppressWarnings("unchecked") private Map<Object,Object> fieldMap(String name)throws Exception{return field(name);}
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{Field f=SocialWindow.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(window);}
    private void set(String name,Object value)throws Exception{Field f=SocialWindow.class.getDeclaredField(name);f.setAccessible(true);f.set(window,value);}
    private Object invoke(String name,Class<?>[] types,Object... arguments)throws Exception{Method m=SocialWindow.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(window,arguments);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    private static <T>T edt(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);if(SwingUtilities.isEventDispatchThread())task.run();else SwingUtilities.invokeAndWait(task);return task.get();}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(10);}fail("UI state did not settle");}
}
