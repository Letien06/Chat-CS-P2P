package com.p2p.client.ui.social;

import com.p2p.client.network.social.*;
import com.p2p.common.social.*;
import com.p2p.client.ui.Theme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import static com.p2p.client.ui.social.Values.*;

/** Social desktop v2. All commands, TLS, hashing and file IO run outside Swing's EDT. */
public final class SocialWindow extends JFrame {
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"social-ui-worker");t.setDaemon(true);return t;});
    private final JPanel root=new JPanel(new BorderLayout());
    private final JPanel body=new JPanel(new BorderLayout());
    private final JLabel connection=new JLabel("Chưa kết nối");
    private final JLabel identity=new JLabel();
    private JPanel ownAvatarHolder;
    private JButton notificationNav;
    private JButton broadcastNav;
    private JButton currentActionSource;
    private volatile SocialClient client;
    private javax.net.ssl.SSLContext connectionTlsContext;
    private volatile long reconnectToken;
    private long pendingOwnerId;
    private long udpExpiresAt;
    private final javax.swing.Timer udpRefresh=new javax.swing.Timer(60000,e->refreshUdp());
    private UdpClient udp;
    private MediaTransfers media=new MediaTransfers(()->client,this::showError);
    private final ThumbnailCache thumbnails=new ThumbnailCache();
    private Map<String,Object> currentUser=new LinkedHashMap<>();
    private String host=System.getProperty("social.host","localhost");private int port=Integer.getInteger("social.port",5000);
    private String truststore=System.getProperty("social.truststore","config/tls/truststore.p12");
    private String view="";
    private Runnable refreshCurrent=()->{};
    private boolean authenticated;
    private long generation;
    private JPanel chatMessages;private JScrollPane chatScroll;private long activeConversation;private long earliestSeq,latestSeq;
    private Map<String,Object> activeConversationData=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Map<String,Object>> messages=new LinkedHashMap<>();
    private final LinkedHashMap<String,PendingMessage> pendingMessages=new LinkedHashMap<>();
    private JLabel typingLabel;
    private JTextArea chatInput;
    private JPanel broadcastMessages;
    private JTextArea broadcastInput;
    private JScrollPane broadcastScroll;
    private String broadcastPendingKey,broadcastPendingContent;
    private boolean broadcastSending;
    private final LinkedHashMap<String,Map<String,Object>> broadcasts=new LinkedHashMap<>();
    private int broadcastUnread;
    private final javax.swing.Timer typingExpiry=new javax.swing.Timer(5000,e->{if(typingLabel!=null)typingLabel.setText(" ");});
    private final javax.swing.Timer countRefresh=new javax.swing.Timer(20000,e->{if(authenticated)refreshCount();});
    private final javax.swing.Timer eventRefresh=new javax.swing.Timer(400,e->{if(authenticated)refreshCurrent.run();});

    public SocialWindow(){
        super("Kết Nối · Mạng xã hội lớp học");setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setSize(1120,780);setMinimumSize(new Dimension(900,620));setLocationRelativeTo(null);
        root.setBorder(new EmptyBorder(0,0,0,0));setContentPane(root);typingExpiry.setRepeats(false);eventRefresh.setRepeats(false);
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){shutdown();dispose();}});showAuth(false);
        addWindowFocusListener(new WindowAdapter(){public void windowGainedFocus(WindowEvent e){if(authenticated&&activeConversation>0)ackMessages();}});
    }
    private void shutdown(){reconnectToken++;authenticated=false;SocialClient closing=client;client=null;countRefresh.stop();udpRefresh.stop();eventRefresh.stop();typingExpiry.stop();if(udp!=null)udp.close();thumbnails.close();media.close();if(closing!=null)closing.close();workers.shutdownNow();}
    private JButton button(String text,Runnable action){JButton b=Theme.ghostButton(text);b.setForeground(Theme.TEXT);b.setBackground(Theme.COMPOSER_BACKGROUND);b.putClientProperty("html.disable",true);b.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));b.setAlignmentX(Component.LEFT_ALIGNMENT);b.addActionListener(e->runAction(b,action));return b;}
    private JButton primary(String text,Runnable action){JButton b=Theme.primaryButton(text);b.addActionListener(e->runAction(b,action));return b;}
    private void runAction(JButton source,Runnable action){JButton previous=currentActionSource;currentActionSource=source;try{action.run();}finally{currentActionSource=previous;}}
    private JPanel horizontal(Component... components){JPanel p=new JPanel(new WrapLayout());p.setAlignmentX(Component.LEFT_ALIGNMENT);for(Component c:components)p.add(c);return p;}
    private JPanel vertical(){return new StackPanel();}
    private JPanel card(){JPanel p=new CardPanel();p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),new EmptyBorder(16,18,16,18)));p.setAlignmentX(Component.LEFT_ALIGNMENT);return p;}
    private static final class CardPanel extends JPanel{CardPanel(){super(new BorderLayout(12,12));}public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}}
    private JLabel label(String text,boolean heading){JLabel l=new JLabel(text);l.setAlignmentX(Component.LEFT_ALIGNMENT);l.setBorder(new EmptyBorder(0,0,0,6));l.putClientProperty("html.disable",true);l.setFont(Theme.font(heading?Font.BOLD:Font.PLAIN,heading?23:14));return l;}
    private JTextArea text(String value,int rows){JTextArea a=new JTextArea(value,rows,35);a.setLineWrap(true);a.setWrapStyleWord(true);a.setFont(Theme.font(Font.PLAIN,14));a.setBorder(new EmptyBorder(8,10,8,10));return a;}
    private JTextArea readable(String value){JTextArea a=text(value,Math.min(8,Math.max(1,value.length()/70+1)));a.setEditable(false);a.setOpaque(false);return a;}
    private JScrollPane scroll(Component c){JScrollPane s=new JScrollPane(c);s.setBorder(null);s.setAlignmentX(Component.LEFT_ALIGNMENT);s.getVerticalScrollBar().setUnitIncrement(20);return s;}
    private static final class StackPanel extends JPanel implements Scrollable{
        StackPanel(){setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setAlignmentX(Component.LEFT_ALIGNMENT);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 20;}public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(20,r.height-30);}public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return false;}
        @Override protected void addImpl(Component c,Object constraints,int index){if(c instanceof JComponent jc)jc.setAlignmentX(Component.LEFT_ALIGNMENT);super.addImpl(c,constraints,index);}
    }
    private static final class WrapLayout extends FlowLayout{
        WrapLayout(){super(FlowLayout.LEFT,8,4);}
        public Dimension preferredLayoutSize(Container target){synchronized(target.getTreeLock()){
            int width=target.getWidth();if(width<=0&&target.getParent()!=null)width=target.getParent().getWidth();if(width<=0)width=620;Insets in=target.getInsets();int available=Math.max(1,width-in.left-in.right-2*getHgap());int x=0,y=0,rowHeight=0,max=0;
            for(Component c:target.getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();if(x>0&&x+getHgap()+d.width>available){max=Math.max(max,x);y+=rowHeight+getVgap();x=0;rowHeight=0;}if(x>0)x+=getHgap();x+=d.width;rowHeight=Math.max(rowHeight,d.height);}return new Dimension(Math.max(max,x)+in.left+in.right+2*getHgap(),y+rowHeight+in.top+in.bottom+2*getVgap());
        }}
    }
    private JComponent avatar(Map<String,Object> user,int size){JPanel panel=new JPanel(new BorderLayout());panel.setOpaque(false);panel.setPreferredSize(new Dimension(size,size));panel.setMaximumSize(new Dimension(size,size));panel.add(new Theme.Avatar(name(user),size,bool(user,"online")));String mid=str(user,"avatarMediaId");if(!mid.isBlank())thumbnails.load(mid,size,icon->{panel.removeAll();JLabel image=new JLabel(icon);image.setHorizontalAlignment(SwingConstants.CENTER);panel.add(image);panel.revalidate();panel.repaint();});return panel;}
    private JButton photo(String mediaId,String fileName){JButton b=button(fileName.isBlank()?"Xem ảnh":"Xem ảnh · "+fileName,()->media.preview(this,mediaId));b.setPreferredSize(new Dimension(205,205));b.setHorizontalTextPosition(SwingConstants.CENTER);b.setVerticalTextPosition(SwingConstants.BOTTOM);thumbnails.load(mediaId,180,icon->{b.setIcon(icon);b.setText("Xem ảnh");b.revalidate();b.repaint();});return b;}
    private JPanel titled(String title,Component... actions){JPanel p=new JPanel(new BorderLayout(12,12));p.setBorder(new EmptyBorder(22,26,14,26));p.add(label(title,true),BorderLayout.NORTH);if(actions.length>0)p.add(horizontal(actions),BorderLayout.CENTER);return p;}
    private void setView(String name,JPanel panel,Runnable refresh){generation++;view=name;activeConversation=0;messages.clear();body.removeAll();body.add(panel);refreshCurrent=refresh;body.revalidate();body.repaint();}
    private void invoke(Runnable r){SwingUtilities.invokeLater(r);}
    private Map<String,Object> blocking(String type,Map<String,Object> payload,String key){SocialClient c=client;if(c==null)throw new IllegalStateException("Chưa kết nối server");return (key==null?c.request(type,payload):c.request(type,payload,key)).join();}
    private Map<String,Object> blocking(SocialClient c,String type,Map<String,Object> payload,String key){if(c==null)throw new IllegalStateException("Chưa kết nối server");return (key==null?c.request(type,payload):c.request(type,payload,key)).join();}
    private void query(String type,Map<String,Object> payload,Consumer<Map<String,Object>> success){command(type,payload,null,null,success,false);}
    private void mutate(String type,Map<String,Object> payload,JButton source,Consumer<Map<String,Object>> success){command(type,payload,UUID.randomUUID().toString(),source==null?currentActionSource:source,success,true);}
    private void command(String type,Map<String,Object> payload,String key,JButton source,Consumer<Map<String,Object>> success,boolean retry){
        if(source!=null)source.setEnabled(false);SocialClient expected=client;
        CompletableFuture.supplyAsync(()->blocking(expected,type,payload,key),workers).whenComplete((result,error)->invoke(()->{
            if(source!=null)source.setEnabled(authenticated||type.startsWith("AUTH_"));if(client!=expected)return;
            if(error!=null){Throwable cause=unwrap(error);if(retry&&(cause instanceof java.io.IOException||cause instanceof TimeoutException)){
                    Object[] choices={"Thử lại cùng thao tác","Đóng"};int answer=JOptionPane.showOptionDialog(this,"Chưa xác định kết quả. Thử lại dùng cùng mã để tránh tạo bản sao.\n"+friendly(cause),"Kết nối gián đoạn",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,choices,choices[0]);
                    if(answer==0)command(type,payload,key,source,success,true);else showError(cause);
                }else showError(cause);return;}success.accept(result);
        }));
    }
    private Throwable unwrap(Throwable t){while((t instanceof CompletionException||t instanceof ExecutionException)&&t.getCause()!=null)t=t.getCause();return t;}
    private String friendly(Throwable error){Throwable e=unwrap(error);if(e instanceof RemoteException remote){String code=remote.getCode();return switch(code){
        case "INVALID_CREDENTIALS"->"Tên đăng nhập hoặc mật khẩu không đúng.";case "ALREADY_SIGNED_IN"->"Tài khoản đang đăng nhập trên thiết bị khác.";
        case "VERSION_CONFLICT"->"Dữ liệu đã thay đổi. Tải lại trước khi áp dụng nội dung của bạn.";case "NOT_FOUND"->"Nội dung không còn khả dụng hoặc bạn không có quyền xem.";
        case "FRIENDSHIP_REQUIRED"->"Cần kết bạn trước khi thực hiện thao tác này.";case "UNAUTHENTICATED"->"Phiên hết hạn. Vui lòng đăng nhập lại.";
        case "RATE_LIMITED"->"Bạn thao tác quá nhanh. Vui lòng chờ rồi thử lại.";case "USERNAME_TAKEN"->"Tên đăng nhập đã được sử dụng.";
        case "ACCOUNT_SUSPENDED"->"Tài khoản đã bị tạm khóa.";case "ACTION_FORBIDDEN"->"Bạn không có quyền thực hiện thao tác này.";
        case "INVALID_STATE"->"Trạng thái đã thay đổi. Hãy tải lại danh sách.";case "INVALID_REQUEST"->"Thông tin chưa hợp lệ. Kiểm tra các trường và giới hạn nội dung.";
        case "INCOMING_REQUEST_EXISTS"->"Người này đã gửi lời mời cho bạn. Hãy chấp nhận từ tab Đã nhận.";case "ALREADY_FRIENDS"->"Hai bạn đã kết bạn.";
        case "QUOTA_EXCEEDED"->"Đã đạt giới hạn dung lượng hoặc số thành viên.";case "FILE_TOO_LARGE"->"Tệp vượt giới hạn: chat 25 MiB; ảnh/avatar 5 MiB.";
        case "HASH_MISMATCH"->"Tệp không vượt qua kiểm tra SHA-256. Hãy chọn lại tệp và thử tiếp tục.";case "OFFSET_MISMATCH"->"Vị trí tải chưa khớp. Hãy tiếp tục theo vị trí được server xác nhận.";
        case "TRANSFER_EXPIRED"->"Lượt tải đã hết hạn. Hãy chọn tiếp tục để lấy lượt tải mới.";case "SERVER_BUSY"->"Server đang bận. Chờ một chút rồi thử lại.";
        case "IDEMPOTENCY_CONFLICT"->"Mã thao tác đã gắn với nội dung khác. Tra cứu kết quả cũ trước khi thay đổi nội dung.";case "VERSION_UNSUPPORTED"->"Phiên bản client không tương thích server.";
        case "UNSUPPORTED_OPERATION"->"Server chưa hỗ trợ thao tác này.";case "INTERNAL_ERROR"->"Server không xử lý được thao tác. Hãy thử lại sau.";default->code+" · "+Objects.toString(e.getMessage(),"Không thể thực hiện");};}
        if(e instanceof javax.net.ssl.SSLException)return "Không xác minh được chứng chỉ TLS. Kiểm tra host và truststore do người vận hành cung cấp.";
        return Objects.toString(e.getMessage(),"Không thể kết nối. Hãy thử lại.");}
    private void showError(Throwable e){if(!SwingUtilities.isEventDispatchThread()){invoke(()->showError(e));return;}JOptionPane.showMessageDialog(this,friendly(e),"Không thể thực hiện",JOptionPane.WARNING_MESSAGE);}
    private void info(String s){JOptionPane.showMessageDialog(this,s,"Kết Nối",JOptionPane.INFORMATION_MESSAGE);}
    private boolean confirm(String s){return JOptionPane.showConfirmDialog(this,s,"Xác nhận",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}

    private void showAuth(boolean register){
        reconnectToken++;authenticated=false;generation++;activeConversation=0;messages.clear();currentUser.clear();thumbnails.bind(null);countRefresh.stop();udpRefresh.stop();for(Window window:getOwnedWindows())window.dispose();root.removeAll();
        JPanel center=new JPanel(new GridBagLayout());center.setBorder(new EmptyBorder(20,20,20,20));JPanel panel=card();panel.setPreferredSize(new Dimension(600,650));
        JPanel fields=vertical();fields.add(label("Kết Nối",true));fields.add(label(register?"Tạo tài khoản · Mạng xã hội lớp học":"Chào bạn · Đăng nhập để tiếp tục",false));fields.add(Box.createVerticalStrut(20));
        JTextField hostField=new JTextField(host),portField=new JTextField(String.valueOf(port)),trustField=new JTextField(truststore);
        JPasswordField trustPassword=new JPasswordField(Objects.toString(System.getenv("SOCIAL_TRUSTSTORE_PASSWORD"),Objects.toString(System.getenv("SOCIAL_TLS_PASSWORD"),"changeit")));
        JTextField user=new JTextField(),display=new JTextField();JPasswordField password=new JPasswordField(),confirmPassword=new JPasswordField();
        String[] offeredServerId={null};String[] offeredHost={null};int[] offeredPort={0};
        addField(fields,"Máy chủ / TCP port",horizontal(hostField,portField));hostField.setColumns(23);portField.setColumns(6);
        JPanel tlsOptions=vertical();addField(tlsOptions,"Truststore PKCS12",trustField);addField(tlsOptions,"Mật khẩu truststore",trustPassword);tlsOptions.add(horizontal(button("Chọn truststore",()->{JFileChooser c=new JFileChooser();if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)trustField.setText(c.getSelectedFile().getAbsolutePath());})));tlsOptions.setVisible(false);
        fields.add(horizontal(button("Tìm máy chủ LAN",()->{
            connection.setText("Đang tìm server trong LAN…");
            UdpClient.discover(null,5001).whenComplete((offers,error)->invoke(()->{
                if(error!=null){showError(error);return;}if(offers.isEmpty()){info("Không tìm thấy server. Bạn vẫn có thể nhập địa chỉ IP và port.");return;}
                String[] options=offers.stream().map(o->o.serverName()+" · "+o.host()+":"+o.tcpPort()).toArray(String[]::new);
                String selected=(String)JOptionPane.showInputDialog(this,"Chọn máy chủ (TLS vẫn phải xác minh)","Máy chủ LAN",JOptionPane.QUESTION_MESSAGE,null,options,options[0]);
                if(selected!=null){int i=Arrays.asList(options).indexOf(selected);hostField.setText(offers.get(i).host());portField.setText(""+offers.get(i).tcpPort());offeredServerId[0]=offers.get(i).serverId();offeredHost[0]=offers.get(i).host();offeredPort[0]=offers.get(i).tcpPort();}
            }));
        }),button("Thiết lập TLS",()->{tlsOptions.setVisible(!tlsOptions.isVisible());fields.revalidate();fields.repaint();})));
        fields.add(tlsOptions);
        fields.add(Box.createVerticalStrut(12));if(register){JPanel names=new JPanel(new GridLayout(1,2,12,0));JPanel usernameColumn=vertical(),displayColumn=vertical();addField(usernameColumn,"Tên đăng nhập",user);addField(displayColumn,"Tên hiển thị",display);names.add(usernameColumn);names.add(displayColumn);names.setMaximumSize(new Dimension(Integer.MAX_VALUE,names.getPreferredSize().height));fields.add(names);}else addField(fields,"Tên đăng nhập",user);
        addField(fields,"Mật khẩu",password);if(register)addField(fields,"Xác nhận mật khẩu",confirmPassword);
        JLabel state=label("TLS xác minh chứng chỉ · Không lưu mật khẩu",false);fields.add(state);
        JButton submit=Theme.primaryButton(register?"Đăng ký":"Đăng nhập");
        submit.addActionListener(e->{char[] secret=password.getPassword(),repeat=confirmPassword.getPassword(),trust=trustPassword.getPassword();
            if(user.getText().trim().isEmpty()||secret.length<10||secret.length>128){Arrays.fill(secret,'\0');Arrays.fill(repeat,'\0');Arrays.fill(trust,'\0');info("Nhập tên đăng nhập và mật khẩu từ 10–128 ký tự.");return;}
            if(register&&(!Arrays.equals(secret,repeat)||display.getText().isBlank())){Arrays.fill(secret,'\0');Arrays.fill(repeat,'\0');Arrays.fill(trust,'\0');info("Điền tên hiển thị và xác nhận mật khẩu trùng khớp.");return;}
            Arrays.fill(repeat,'\0');String username=user.getText().trim(),name=display.getText().trim(),serverHost=hostField.getText().trim(),store=trustField.getText().trim();
            int serverPort;try{serverPort=Integer.parseInt(portField.getText());if(serverPort<1||serverPort>65535)throw new NumberFormatException();}catch(NumberFormatException ex){Arrays.fill(secret,'\0');Arrays.fill(trust,'\0');info("Port cần nằm trong 1–65535.");return;}
            submit.setEnabled(false);state.setText("Đang kết nối TLS và xác thực…");reconnectToken++;
            String expectedServer=serverHost.equals(offeredHost[0])&&serverPort==offeredPort[0]?offeredServerId[0]:null;
            CompletableFuture.supplyAsync(()->{
                SocialClient c=null;try{if(client!=null)client.close();connectionTlsContext=TlsContexts.client(Path.of(store),trust);c=new SocialClient(serverHost,serverPort,connectionTlsContext);Map<String,Object> hello=c.connect().join();
                    if(expectedServer!=null&&!expectedServer.equals(str(hello,"serverId")))throw new SecurityException("ServerId TLS không khớp máy chủ LAN đã chọn.");
                    Map<String,Object> login=c.request(register?"AUTH_REGISTER":"AUTH_LOGIN",of("username",username,"password",new String(secret),"displayName",register?name:null)).join();
                    client=c;return login;
                }catch(Throwable ex){if(c!=null)c.close();throw new CompletionException(ex);}finally{Arrays.fill(secret,'\0');Arrays.fill(trust,'\0');}
            },workers).whenComplete((login,error)->invoke(()->{
                password.setText("");confirmPassword.setText("");submit.setEnabled(true);host=serverHost;port=serverPort;truststore=store;
                if(error!=null){state.setText("Chưa kết nối · có thể thử lại");showError(error);return;}
                if(register){info("Tạo tài khoản thành công. Hãy đăng nhập.");client.close();client=null;showAuth(false);return;}
                currentUser=map(login.get("currentUser"));if(currentUser.isEmpty())currentUser=map(login.get("user"));
                if(pendingOwnerId!=0&&pendingOwnerId!=id(currentUser,"user")){pendingMessages.clear();media.close();media=new MediaTransfers(()->client,this::showError);}pendingOwnerId=id(currentUser,"user");
                media.bind(client);
                thumbnails.bind(client);
                authenticated=true;SocialClient logged=client;logged.onEvent(event->{media.event(event);invoke(()->{if(client==logged)onEvent(event);});});
                logged.onDisconnect(errorCause->invoke(()->{if(client==logged&&authenticated)disconnected(errorCause);}));
                setupUdp(login);showMain();query("STATE_SYNC",of("limit",20),r->refreshCount());showFeed();
            }));
        });
        panel.add(scroll(fields));panel.add(horizontal(submit,button(register?"Đã có tài khoản":"Tạo tài khoản",()->showAuth(!register))),BorderLayout.SOUTH);panel.setMinimumSize(new Dimension(550,400));GridBagConstraints constraints=new GridBagConstraints();constraints.fill=GridBagConstraints.VERTICAL;constraints.weighty=1;center.add(panel,constraints);root.add(center);root.revalidate();root.repaint();getRootPane().setDefaultButton(submit);
    }
    private void addField(JPanel p,String name,Component input){if(input instanceof JTextField||input instanceof JComboBox<?>){input.setPreferredSize(new Dimension(input.getPreferredSize().width,32));}JPanel row=new JPanel(new BorderLayout(4,4));row.setBorder(new EmptyBorder(2,0,3,0));row.add(label(name,false),BorderLayout.NORTH);row.add(input);row.setAlignmentX(Component.LEFT_ALIGNMENT);row.setMaximumSize(new Dimension(Integer.MAX_VALUE,row.getPreferredSize().height));p.add(row);}
    private void setupUdp(Map<String,Object> login){
        if(udp!=null)udp.close();Map<String,Object> config=map(login.get("udp"));if(config.isEmpty())config=new LinkedHashMap<>(login);
        if(!config.containsKey("sessionId"))config.put("sessionId",login.get("sessionId"));udpExpiresAt=num(config,"keyExpiresAt");SocialClient expected=client;Map<String,Object> finalConfig=config;
        workers.execute(()->{try{UdpClient created=new UdpClient(host,(int)(num(finalConfig,"serverUdpPort")==0?5002:num(finalConfig,"serverUdpPort")));created.onTyping(p->invoke(()->{
                    if(!authenticated||client!=expected||udp!=created)return;if(activeConversation==num(p,"conversationId")&&typingLabel!=null){long remaining=num(p,"expiresAt")-expected.serverTime();if(bool(p,"typing")&&remaining>0){typingLabel.setText("Bạn cùng trò chuyện đang nhập…");typingExpiry.setInitialDelay((int)Math.min(5000,remaining));typingExpiry.restart();}else{typingLabel.setText(" ");typingExpiry.stop();}}
                }));created.configure(finalConfig,expected.serverTime());invoke(()->{if(client==expected&&authenticated){udp=created;udpRefresh.start();}else created.close();});
        }catch(Exception ignored){/* UDP loss never prevents reliable TLS chat. */}});
    }
    private void refreshUdp(){if(!authenticated||udp==null||client==null||client.serverTime()<udpExpiresAt-120000)return;query("UDP_SESSION_REFRESH",of(),p->{setupUdp(p);});}
    private void showMain(){
        root.removeAll();JPanel sidebar=vertical();sidebar.setPreferredSize(new Dimension(205,600));sidebar.setBackground(Theme.NAV_BACKGROUND);sidebar.setBorder(new EmptyBorder(28,15,20,15));
        sidebar.add(label("Kết Nối",true));sidebar.add(Box.createVerticalStrut(8));ownAvatarHolder=(JPanel)avatar(currentUser,48);sidebar.add(ownAvatarHolder);sidebar.add(Box.createVerticalStrut(6));identity.setText(name(currentUser));identity.putClientProperty("html.disable",true);sidebar.add(identity);sidebar.add(Box.createVerticalStrut(22));
        sidebar.add(button("Bảng tin",this::showFeed));sidebar.add(Box.createVerticalStrut(10));sidebar.add(button("Bạn bè & tìm người",this::showPeople));sidebar.add(Box.createVerticalStrut(10));sidebar.add(button("Tin nhắn & nhóm",this::showConversations));sidebar.add(Box.createVerticalStrut(10));
        broadcastNav=button("Broadcast",this::showBroadcast);sidebar.add(broadcastNav);sidebar.add(Box.createVerticalStrut(10));
        notificationNav=button("Thông báo",this::showNotifications);sidebar.add(notificationNav);sidebar.add(Box.createVerticalStrut(10));sidebar.add(button("Hồ sơ & cài đặt",this::showSettings));
        if("ADMIN".equals(str(currentUser,"role"))){sidebar.add(Box.createVerticalStrut(10));sidebar.add(button("Quản trị",this::showAdmin));}
        sidebar.add(Box.createVerticalGlue());sidebar.add(button("Tiếp tục tải tệp",()->media.resumeUploads(this)));sidebar.add(Box.createVerticalStrut(10));sidebar.add(button("Đăng xuất",this::logout));
        JPanel footer=horizontal(connection,button("Đồng bộ",()->{query("STATE_SYNC",of("limit",20),r->{refreshCurrent.run();refreshCount();});}),button("Đăng nhập lại",()->{reconnectToken++;if(client!=null)client.close();showAuth(false);}));
        connection.setText("● Đã kết nối TLS · "+host+":"+port);root.add(sidebar,BorderLayout.WEST);root.add(body);root.add(footer,BorderLayout.SOUTH);root.revalidate();root.repaint();getRootPane().setDefaultButton(null);countRefresh.start();refreshCount();
    }
    private void logout(){query("AUTH_LOGOUT",of(),r->{clearSession();showAuth(false);});}
    private void clearSession(){reconnectToken++;authenticated=false;thumbnails.bind(null);udpRefresh.stop();for(Window window:getOwnedWindows())window.dispose();if(udp!=null){udp.close();udp=null;}if(client!=null){client.close();client=null;}media.close();media=new MediaTransfers(()->client,this::showError);messages.clear();pendingMessages.clear();resetBroadcast();pendingOwnerId=0;currentUser.clear();body.removeAll();}
    private void disconnected(Throwable error){authenticated=false;connection.setText("● Mất kết nối · dữ liệu đang xem có thể cũ · đang thử kết nối lại");if(udp!=null)udp.close();udpRefresh.stop();media.disconnected();if(chatInput!=null)chatInput.setEnabled(false);if(typingLabel!=null)typingLabel.setText(" ");countRefresh.stop();disableButtons(body);long token=++reconnectToken;
        if(error==null)return;workers.execute(()->{for(int attempt=0;attempt<6&&reconnectToken==token;attempt++){
            try{Thread.sleep(Math.min(30000,1000L<<attempt)+ThreadLocalRandom.current().nextInt(250));if(reconnectToken!=token)return;SocialClient candidate=new SocialClient(host,port,connectionTlsContext);
                try{candidate.connect().join();if(reconnectToken!=token){candidate.close();return;}client=candidate;invoke(()->{if(reconnectToken==token){showAuth(false);info("Đã nối lại TLS. Đăng nhập lại; tin chưa xác định vẫn giữ mã cũ khi bạn trở lại cuộc trò chuyện.");}});return;}catch(Throwable e){candidate.close();}
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }invoke(()->{if(reconnectToken==token)connection.setText("● Chưa kết nối · bấm Đăng nhập lại để thử tiếp");});});
    }
    private void disableButtons(Container c){for(Component child:c.getComponents()){if(child instanceof JButton b)b.setEnabled(false);if(child instanceof Container nested)disableButtons(nested);}}
    private void refreshCount(){if(!authenticated)return;query("NOTIFICATION_COUNT",of(),r->{if(notificationNav!=null)notificationNav.setText("Thông báo ("+num(r,"unreadCount","count")+")");});}
    private void onEvent(WireMessage event){if(!authenticated)return;String type=event.getType();Map<String,Object> p=event.getPayload();
        if(Set.of("PROFILE_INVALIDATED","RELATION_CHANGED","POST_INVALIDATED","CONVERSATION_INVALIDATED").contains(type))thumbnails.clear();
        if("PROFILE_INVALIDATED".equals(type)&&num(p,"userId")==id(currentUser,"user")&&authenticated)query("PROFILE_GET",of("targetUserId",id(currentUser,"user")),this::updateSelf);
        if("SESSION_REVOKED".equals(type)||"SERVER_STOPPING".equals(type)){disconnected(null);return;}
        if("NOTIFICATION_CHANGED".equals(type))refreshCount();
        if("BROADCAST_MESSAGE".equals(type)){receiveBroadcast(p);return;}
        if("PRESENCE_CHANGED".equals(type)&&"people".equals(view)){updatePresence(body,num(p,"userId"),bool(p,"online"),num(p,"asOf"));return;}
        if("feed".equals(view)&&Set.of("POST_INVALIDATED","COMMENT_INVALIDATED","LIKE_INVALIDATED").contains(type)){
            if("POST_INVALIDATED".equals(type))invalidateVisiblePost(body,num(p,"postId"));
            connection.setText("● Đã kết nối TLS · bảng tin có cập nhật · bấm Đồng bộ / Làm mới để xem");return;
        }
        if("MESSAGE_CREATED".equals(type)){Map<String,Object> m=map(p.get("message"));if(m.isEmpty())m=p;if(activeConversation==num(m,"conversationId")){upsertMessage(m);renderMessages(false);ackMessages();}return;}
        if("RECEIPT_UPDATED".equals(type)&&activeConversation==num(p,"conversationId")){List<Map<String,Object>> receipts=rows(p.get("receipts"));if(receipts.isEmpty())receipts=List.of(p);for(Map<String,Object> m:messages.values()){
            if(num(m,"senderId")!=id(currentUser,"user"))continue;long seq=num(m,"conversationSeq","seq"),read=0,delivered=0;
            for(Map<String,Object> receipt:receipts){if(num(receipt,"actorId")==id(currentUser,"user"))continue;if(seq<=num(receipt,"readThroughSeq","readSeq"))read++;if(seq<=num(receipt,"deliveredThroughSeq","deliveredSeq"))delivered++;}
            m.put("readByCount",read);m.put("deliveredByCount",delivered);if(read>0)m.put("status","READ");else if(delivered>0)m.put("status","DELIVERED");
        }renderMessages(false);return;}
        if(Set.of("RELATION_CHANGED","POST_INVALIDATED","COMMENT_INVALIDATED","LIKE_INVALIDATED","PROFILE_INVALIDATED","CONVERSATION_INVALIDATED","GROUP_INVITE_CHANGED","PRESENCE_CHANGED","NOTIFICATION_CHANGED").contains(type)){
            if("chat".equals(view)&&("RELATION_CHANGED".equals(type)||"CONVERSATION_INVALIDATED".equals(type))){
                long cid=activeConversation;messages.clear();chatMessages.removeAll();chatMessages.add(label("Đang kiểm tra quyền truy cập cuộc trò chuyện…",false));chatMessages.revalidate();chatMessages.repaint();chatInput.setEnabled(false);
                query("CONVERSATION_LIST",of("limit",50),r->{Map<String,Object> found=rows(r.get("items")).stream().filter(c->id(c,"conversation")==cid).findFirst().orElse(null);if(found==null)showConversations();else openChat(found);});return;
            }
            // Drop visible content before rechecking ACL: old event previews must not leak after block/privacy changes.
            if("POST_INVALIDATED".equals(type)||"RELATION_CHANGED".equals(type)||"CONVERSATION_INVALIDATED".equals(type)){if("feed".equals(view)||"post".equals(view)||"profile".equals(view)){body.removeAll();body.add(label("Đang kiểm tra quyền và cập nhật…",false));body.revalidate();body.repaint();}}
            eventRefresh.restart();
        }
    }
    private void updateSelf(Map<String,Object> profile){currentUser.putAll(profile);identity.setText(name(currentUser));if(ownAvatarHolder!=null){ownAvatarHolder.removeAll();ownAvatarHolder.add(avatar(currentUser,48));ownAvatarHolder.revalidate();ownAvatarHolder.repaint();}}
    private void invalidateVisiblePost(Container container,long postId){for(Component component:container.getComponents()){
        if(component instanceof JPanel panel&&Objects.equals(panel.getClientProperty("postId"),postId)){int oldHeight=panel.getHeight();panel.removeAll();panel.add(readable("Bài viết đã thay đổi. Làm mới bảng tin để kiểm tra quyền và xem nội dung mới."));panel.setMinimumSize(new Dimension(0,Math.min(oldHeight,100)));panel.revalidate();panel.repaint();}
        else if(component instanceof Container child)invalidateVisiblePost(child,postId);
    }}

    /** Every page has a finite component count and carries the opaque server cursor. */
    private final class Page {
        final JPanel panel=new JPanel(new BorderLayout(12,12));final JPanel list=vertical();final JLabel status=label("Đang tải…",false);final JButton more;
        final String type;final Map<String,Object> base;final Function<Map<String,Object>,JComponent> render;String cursor;boolean loading;int epoch;
        Page(String title,String type,Map<String,Object> base,Function<Map<String,Object>,JComponent> render,Component... actions){this.type=type;this.base=base;this.render=render;
            panel.add(titled(title,actions),BorderLayout.NORTH);list.setBorder(new EmptyBorder(4,26,16,26));panel.add(scroll(list));more=button("Tải trang tiếp",()->load(false));panel.add(horizontal(status,button("Làm mới",()->load(true)),more),BorderLayout.SOUTH);}
        void load(boolean reset){if(loading)return;if("USER_SEARCH".equals(type)&&str(base,"query").trim().length()<2){status.setText("Nhập ít nhất 2 ký tự để tìm người.");return;}loading=true;more.setEnabled(false);int token=++epoch;long session=generation;
            if(reset){cursor=null;list.removeAll();status.setText("Đang tải…");}
            Map<String,Object> request=new LinkedHashMap<>(base);request.put("limit",20);if(cursor!=null&&!cursor.isBlank())request.put("cursor",cursor);
            SocialClient requestedClient=client;CompletableFuture.supplyAsync(()->blocking(requestedClient,type,request,null),workers).whenComplete((result,error)->invoke(()->{
                if(token!=epoch||session!=generation)return;loading=false;if(error!=null){status.setText("Không tải được · bấm Làm mới");more.setEnabled(true);showError(error);return;}
                // Keep at most 60 rows; paging still offers every page instead of growing indefinitely.
                if(list.getComponentCount()>=120)list.removeAll();List<Map<String,Object>> items=rows(result.get("items"));
                for(Map<String,Object> row:items){JComponent rendered=render.apply(row);rendered.setAlignmentX(Component.LEFT_ALIGNMENT);rendered.setMaximumSize(new Dimension(Integer.MAX_VALUE,rendered.getPreferredSize().height));list.add(rendered);list.add(Box.createVerticalStrut(12));}
                if("RELATION_LIST".equals(type)&&"FRIENDS".equals(base.get("kind"))&&!items.isEmpty()){List<Long> ids=items.stream().map(r->num(relationUser(r),"userId","otherUserId","targetUserId")).filter(id->id>0).toList();query("PRESENCE_GET",of("userIds",ids),presence->{if(session!=generation)return;for(Map<String,Object> state:rows(presence.get("items")))updatePresence(list,num(state,"userId"),bool(state,"online"),num(state,"asOf"));});}
                cursor=str(result,"nextCursor");more.setEnabled(bool(result,"hasMore")||!cursor.isBlank());status.setText(items.isEmpty()&&list.getComponentCount()==0?"Chưa có nội dung.":"Đã tải "+items.size()+" mục · thời gian theo máy của bạn");list.revalidate();list.repaint();
            }));}
    }
    private void showFeed(){Page p=new Page("Bảng tin","FEED_GET",of(),this::postCard,primary("Chia sẻ bài viết",()->composePost(null)));setView("feed",p.panel,this::showFeed);p.load(true);}
    private JComponent postCard(Map<String,Object> post){
        JPanel c=card();long postId=id(post,"post");c.putClientProperty("postId",postId);Map<String,Object> author=map(post.get("author"));if(author.isEmpty())author=of("userId",num(post,"authorId"),"displayName",str(post,"authorName","displayName"),"avatarMediaId",str(post,"authorAvatarMediaId"));
        Map<String,Object> profile=author;c.add(horizontal(avatar(author,40),button(name(author),()->showProfile(id(profile,"user"))),label(time(post)+" · "+visibility(str(post,"visibility")),false)),BorderLayout.NORTH);
        if(bool(post,"unavailable")||"HIDDEN".equals(str(post,"status"))){c.add(readable("Bài viết đã bị ẩn.\n"+str(post,"moderationReason")));return c;}
        JPanel content=vertical();content.add(readable(str(post,"content")));List<Map<String,Object>> images=rows(post.get("media"));if(images.isEmpty())images=rows(post.get("images"));
        List<Component> imageButtons=new ArrayList<>();for(Map<String,Object> image:images){String mid=str(image,"mediaId","id");imageButtons.add(photo(mid,str(image,"fileName")));}
        if(imageButtons.isEmpty()&&post.get("mediaIds") instanceof Collection<?> ids)for(Object raw:ids){String mid=String.valueOf(raw);imageButtons.add(photo(mid,""));}
        if(!imageButtons.isEmpty())content.add(horizontal(imageButtons.toArray(Component[]::new)));c.add(content);
        JButton like=button((bool(post,"liked")?"♥ Đã thích":"♡ Thích")+" ("+num(post,"likeCount")+")",()->{});like.addActionListener(e->mutate("LIKE_SET",of("postId",postId,"liked",!bool(post,"liked")),like,r->refreshCurrent.run()));
        JPanel actions=horizontal(like,button("Bình luận ("+num(post,"commentCount")+")",()->showPost(postId)),button("Báo cáo",()->report("POST",postId)));
        if(num(post,"authorId")==id(currentUser,"user")||id(author,"user")==id(currentUser,"user")){
            actions.add(button("Sửa",()->composePost(post)));actions.add(button("Xóa",()->{if(confirm("Xóa bài viết này?"))mutate("POST_DELETE",of("postId",postId,"expectedVersion",num(post,"version")),null,r->refreshCurrent.run());}));}
        c.add(actions,BorderLayout.SOUTH);return c;
    }
    private String visibility(String code){return switch(code){case "PUBLIC"->"Công khai";case "ONLY_ME"->"Chỉ mình tôi";default->"Bạn bè";};}
    private void composePost(Map<String,Object> existing){
        Map<String,Object> editing=existing==null?null:new LinkedHashMap<>(existing);
        JDialog d=dialog(existing==null?"Chia sẻ bài viết":"Sửa bài viết",650,560);JPanel p=vertical();JTextArea content=text(existing==null?"":str(existing,"content"),10);JComboBox<String> privacy=new JComboBox<>(new String[]{"FRIENDS","PUBLIC","ONLY_ME"});privacy.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean selected,boolean focus){return super.getListCellRendererComponent(l,visibility(String.valueOf(v)),i,selected,focus);}});
        if(existing!=null)privacy.setSelectedItem(str(existing,"visibility"));p.add(label("Bạn muốn chia sẻ điều gì?",true));p.add(scroll(content));addField(p,"Ai có thể xem?",privacy);
        List<String> ids=new ArrayList<>();JPanel attachments=vertical();p.add(attachments);JButton submit=Theme.primaryButton(existing==null?"Đăng bài":"Lưu thay đổi");int[] uploading={0};
        JButton image=button("Thêm ảnh (tối đa 4)",()->{
            if(ids.size()+uploading[0]>=4){info("Tối đa 4 ảnh.");return;}JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(d)!=JFileChooser.APPROVE_OPTION)return;uploading[0]++;submit.setEnabled(false);
            JLabel pending=label("Đang tải ảnh…",false);attachments.add(pending);attachments.revalidate();media.upload(d,chooser.getSelectedFile().toPath(),"POST_IMAGE",mediaId->{uploading[0]--;ids.add(mediaId);attachments.remove(pending);attachments.add(horizontal(label("Ảnh #"+mediaId+" đã sẵn sàng",false),button("Bỏ",()->{ids.remove(mediaId);pending.setText("Đã bỏ ảnh #"+mediaId);attachments.removeAll();for(String id:ids)attachments.add(label("Ảnh #"+id,false));attachments.revalidate();})));attachments.revalidate();submit.setEnabled(uploading[0]==0);});
        });if(existing!=null)image.setVisible(false);
        submit.addActionListener(e->{if(content.getText().isBlank()&&ids.isEmpty()&&existing==null){info("Nhập nội dung hoặc chọn ảnh.");return;}if(content.getText().length()>5000){info("Bài viết tối đa 5.000 ký tự.");return;}
            Map<String,Object> payload=existing==null?of("content",content.getText(),"visibility",privacy.getSelectedItem(),"mediaIds",List.copyOf(ids)):of("postId",id(editing,"post"),"content",content.getText(),"visibility",privacy.getSelectedItem(),"expectedVersion",num(editing,"version"));
            mutate(existing==null?"POST_CREATE":"POST_UPDATE",payload,submit,r->{d.dispose();showFeed();});});
        JButton review=button("Xem phiên bản server (giữ bản đang sửa)",()->query("POST_GET",of("postId",id(editing,"post")),r->{Map<String,Object> latest=map(r.get("post"));if(latest.isEmpty())latest=r;if(bool(latest,"unavailable")){info("Bài viết không còn khả dụng để chỉnh sửa.");return;}JPanel preview=vertical();preview.add(label("Phiên bản server "+num(latest,"version")+" · "+visibility(str(latest,"visibility")),false));preview.add(scroll(readable(str(latest,"content"))));int choice=JOptionPane.showConfirmDialog(d,preview,"Đối chiếu rồi dùng phiên bản này để áp dụng bản đang sửa?",JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE);if(choice==JOptionPane.OK_OPTION){editing.clear();editing.putAll(latest);info("Đã cập nhật phiên bản đối chiếu. Nội dung và privacy đang sửa được giữ để bạn quyết định lưu.");}}));review.setVisible(existing!=null);
        p.add(horizontal(image,review,submit,button("Đóng",d::dispose)));d.add(p);d.setVisible(true);
    }
    private void showPost(long postId){
        JPanel panel=new JPanel(new BorderLayout());JPanel top=vertical();top.add(label("Đang tải bài viết…",false));panel.add(top,BorderLayout.NORTH);
        long[] postOwner={0};Page comments=new Page("Bình luận","COMMENT_LIST",of("postId",postId),comment->{JPanel c=card();c.add(label(str(comment,"authorName","displayName")+" · "+time(comment),false),BorderLayout.NORTH);c.add(readable(bool(comment,"tombstone")?"Nội dung không khả dụng":str(comment,"content")));if(bool(comment,"tombstone"))return c;JPanel actions=horizontal(button("Báo cáo",()->report("COMMENT",id(comment,"comment"))));if(num(comment,"authorId")==id(currentUser,"user")||postOwner[0]==id(currentUser,"user"))actions.add(button("Xóa",()->{if(confirm("Xóa bình luận?"))mutate("COMMENT_DELETE",of("commentId",id(comment,"comment")),null,r->showPost(postId));}));c.add(actions,BorderLayout.SOUTH);return c;});
        panel.add(comments.panel);JTextArea input=text("",2);JButton send=Theme.primaryButton("Bình luận");send.addActionListener(e->{if(!input.getText().isBlank())mutate("COMMENT_CREATE",of("postId",postId,"content",input.getText()),send,r->{input.setText("");showPost(postId);});});panel.add(horizontal(scroll(input),send),BorderLayout.SOUTH);
        setView("post",panel,()->showPost(postId));long token=generation;query("POST_GET",of("postId",postId),r->{if(token!=generation)return;Map<String,Object> post=map(r.get("post"));if(post.isEmpty())post=r;postOwner[0]=num(post,"authorId");top.removeAll();top.add(postCard(post));top.revalidate();top.repaint();if(bool(post,"unavailable")||"HIDDEN".equals(str(post,"status"))){input.setEnabled(false);send.setEnabled(false);panel.remove(comments.panel);panel.add(label("Bình luận và tương tác không khả dụng.",false));panel.revalidate();}else comments.load(true);});
    }
    private JDialog dialog(String title,int width,int height){JDialog d=new JDialog(this,title,Dialog.ModalityType.MODELESS);d.setSize(width,height);d.setLocationRelativeTo(this);((JComponent)d.getContentPane()).setBorder(new EmptyBorder(18,20,18,20));d.getRootPane().registerKeyboardAction(e->d.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);return d;}

    private void showPeople(){
        JPanel panel=new JPanel(new BorderLayout());JTabbedPane tabs=new JTabbedPane();panel.add(titled("Bạn bè & tìm người",button("Tải lại",this::showPeople)),BorderLayout.NORTH);panel.add(tabs);
        List<Page> pages=new ArrayList<>();for(String kind:List.of("FRIENDS","INCOMING","OUTGOING")){
            Page page=new Page(switch(kind){case "FRIENDS"->"Bạn bè";case "INCOMING"->"Lời mời nhận được";default->"Lời mời đã gửi";},"RELATION_LIST",of("kind",kind),r->relationCard(r,kind));pages.add(page);tabs.addTab(switch(kind){case "FRIENDS"->"Bạn bè";case "INCOMING"->"Đã nhận";default->"Đã gửi";},page.panel);
        }
        JTextField search=new JTextField(30);Page results=new Page("Kết quả tìm kiếm","USER_SEARCH",of("query",""),this::userCard);results.status.setText("Nhập ít nhất 2 ký tự để tìm người.");results.more.setEnabled(false);JPanel find=new JPanel(new BorderLayout());find.add(horizontal(search,primary("Tìm người",()->{
            if(search.getText().trim().length()<2){info("Nhập ít nhất 2 ký tự.");return;}results.base.put("query",search.getText().trim());results.load(true);
        })),BorderLayout.NORTH);find.add(results.panel);tabs.addTab("Tìm người",find);
        setView("people",panel,this::showPeople);for(Page page:pages)page.load(true);
    }
    private Map<String,Object> relationUser(Map<String,Object> r){Map<String,Object> u=map(r.get("user"));if(u.isEmpty())u=map(r.get("otherUser"));if(u.isEmpty())u=map(r.get("targetUser"));if(u.isEmpty())u=r;return u;}
    private JComponent relationCard(Map<String,Object> r,String kind){
        JPanel c=card();Map<String,Object> u=relationUser(r);long uid=num(u,"userId","id","otherUserId","targetUserId");if(uid==0){long requester=num(r,"requesterId"),addressee=num(r,"addresseeId");uid=requester==id(currentUser,"user")?addressee:requester;}long target=uid;
        JLabel presence=label("FRIENDS".equals(kind)?"Đang cập nhật trạng thái…":"Lời mời kết bạn",false);presence.putClientProperty("presenceUserId",target);c.add(horizontal(avatar(u,42),button(name(u),()->showProfile(target)),presence),BorderLayout.NORTH);
        long relation=id(r,"relation");JPanel actions=horizontal();if("INCOMING".equals(kind)){
            actions.add(button("Chấp nhận",()->mutate("FRIEND_ACCEPT",of("relationId",relation,"expectedVersion",num(r,"version")),null,x->showPeople())));
            actions.add(button("Từ chối",()->mutate("FRIEND_REJECT",of("relationId",relation,"expectedVersion",num(r,"version")),null,x->showPeople())));
        }else if("OUTGOING".equals(kind))actions.add(button("Hủy lời mời",()->mutate("FRIEND_CANCEL",of("relationId",relation,"expectedVersion",num(r,"version")),null,x->showPeople())));
        else{actions.add(button("Nhắn tin",()->openDirect(target)));actions.add(button("Hủy kết bạn",()->{if(confirm("Hủy kết bạn? Bạn không thể gửi tin nhắn mới cho đến khi kết bạn lại."))mutate("FRIEND_REMOVE",of("relationId",relation),null,x->showPeople());}));}
        actions.add(button("Chặn",()->block(target,true)));c.add(actions,BorderLayout.SOUTH);return c;
    }
    private void updatePresence(Container parent,long userId,boolean online,long asOf){for(Component c:parent.getComponents()){if(c instanceof JLabel label&&Objects.equals(label.getClientProperty("presenceUserId"),userId)){label.setText(online?"● Đang online":"○ Offline");label.setToolTipText("Trạng thái TCP · "+Values.time(of("createdAt",asOf)));}else if(c instanceof Container child)updatePresence(child,userId,online,asOf);}}
    private JComponent userCard(Map<String,Object> user){JPanel c=card();long uid=id(user,"user");c.add(horizontal(avatar(user,40),button(name(user),()->showProfile(uid)),label("@"+str(user,"username"),false)),BorderLayout.NORTH);c.add(readable(str(user,"bio")));c.add(horizontal(button("Kết bạn",()->mutate("FRIEND_REQUEST",of("targetUserId",uid),null,r->{info("Đã gửi lời mời kết bạn.");})),button("Nhắn tin",()->openDirect(uid))),BorderLayout.SOUTH);return c;}
    private void block(long uid,boolean blocked){if(blocked&&!confirm("Chặn người này? Hai bạn vẫn có thể gặp nhau trong nhóm chung."))return;mutate("BLOCK_SET",of("targetUserId",uid,"blocked",blocked),null,r->{showPeople();});}
    private void showProfile(long uid){
        Page timeline=new Page("Bài viết của người dùng","PROFILE_POSTS_GET",of("authorId",uid),this::postCard);JPanel panel=new JPanel(new BorderLayout());JPanel profile=vertical();profile.add(label("Đang tải hồ sơ…",false));panel.add(profile,BorderLayout.NORTH);panel.add(timeline.panel);setView("profile",panel,()->showProfile(uid));long token=generation;
        query("PROFILE_GET",of("targetUserId",uid),r->{if(token!=generation)return;Map<String,Object> u=map(r.get("profile"));if(u.isEmpty())u=r;profile.removeAll();JPanel header=card();String avatar=str(u,"avatarMediaId");header.add(horizontal(avatar(u,64),label(name(u),true),label("@"+str(u,"username"),false)),BorderLayout.NORTH);header.add(readable(str(u,"bio")));JPanel actions=horizontal();if(!avatar.isBlank())actions.add(button("Xem avatar",()->media.preview(this,avatar)));if(uid!=id(currentUser,"user")){actions.add(button("Kết bạn",()->mutate("FRIEND_REQUEST",of("targetUserId",uid),null,x->showPeople())));actions.add(button("Nhắn tin",()->openDirect(uid)));actions.add(button("Chặn",()->block(uid,true)));actions.add(button("Báo cáo",()->report("USER",uid)));}else actions.add(button("Sửa hồ sơ",this::showSettings));header.add(actions,BorderLayout.SOUTH);profile.add(header);profile.revalidate();profile.repaint();});timeline.load(true);
    }
    private void showSettings(){
        JPanel panel=new JPanel(new BorderLayout());panel.add(titled("Hồ sơ & cài đặt"),BorderLayout.NORTH);JTabbedPane tabs=new JTabbedPane();panel.add(tabs);JPanel settings=vertical();settings.setBorder(new EmptyBorder(15,26,20,26));
        JTextField nameField=new JTextField(name(currentUser));JTextArea bio=text(str(currentUser,"bio"),4);addField(settings,"Tên hiển thị",nameField);addField(settings,"Giới thiệu",scroll(bio));JLabel version=label("Đang tải hồ sơ…",false);settings.add(version);Map<String,Object> profile=new LinkedHashMap<>(currentUser);
        nameField.setEnabled(false);bio.setEnabled(false);
        JButton save=Theme.primaryButton("Lưu hồ sơ");save.addActionListener(e->mutate("PROFILE_UPDATE",of("displayName",nameField.getText(),"bio",bio.getText(),"expectedVersion",num(profile,"version")),save,r->{Map<String,Object> u=map(r.get("profile"));if(u.isEmpty())u=r;updateSelf(u);showSettings();}));
        settings.add(horizontal(save,button("Đổi avatar",()->{JFileChooser c=new JFileChooser();if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)media.upload(this,c.getSelectedFile().toPath(),"AVATAR",mid->mutate("AVATAR_SET",of("mediaId",mid,"expectedVersion",num(profile,"version")),null,r->showSettings()));}),button("Xem hồ sơ của tôi",()->showProfile(id(currentUser,"user")))));
        settings.add(horizontal(button("Đối chiếu bản mới, giữ nội dung đang sửa",()->query("PROFILE_GET",of("targetUserId",id(currentUser,"user")),r->{Map<String,Object> latest=map(r.get("profile"));if(latest.isEmpty())latest=r;if(confirm("Phiên bản "+num(latest,"version")+" trên server:\n"+name(latest)+"\n"+str(latest,"bio")+"\n\nDùng phiên bản này để áp dụng nội dung bạn đang sửa?")){profile.clear();profile.putAll(latest);version.setText("Phiên bản đối chiếu "+num(latest,"version")+" · bản đang sửa được giữ");}}))));
        settings.add(Box.createVerticalStrut(25));settings.add(label("Đổi mật khẩu",true));JPasswordField old=new JPasswordField(),next=new JPasswordField(),repeat=new JPasswordField();addField(settings,"Mật khẩu hiện tại",old);addField(settings,"Mật khẩu mới (10–128 ký tự)",next);addField(settings,"Xác nhận mật khẩu mới",repeat);
        JButton change=Theme.primaryButton("Đổi mật khẩu & đăng nhập lại");change.addActionListener(e->{char[] a=old.getPassword(),b=next.getPassword(),c=repeat.getPassword();if(b.length<10||b.length>128||!Arrays.equals(b,c)){Arrays.fill(a,'\0');Arrays.fill(b,'\0');Arrays.fill(c,'\0');info("Kiểm tra mật khẩu mới và phần xác nhận.");return;}Map<String,Object> payload=of("currentPassword",new String(a),"newPassword",new String(b));Arrays.fill(a,'\0');Arrays.fill(b,'\0');Arrays.fill(c,'\0');old.setText("");next.setText("");repeat.setText("");command("AUTH_PASSWORD_CHANGE",payload,null,change,r->{clearSession();showAuth(false);info("Đã đổi mật khẩu. Vui lòng đăng nhập lại.");},false);});settings.add(horizontal(change));tabs.addTab("Hồ sơ / bảo mật",scroll(settings));
        Page blocked=new Page("Người đã chặn","BLOCK_LIST",of(),u->{JPanel card=card();card.add(label(name(u),false));card.add(button("Bỏ chặn",()->block(id(u,"user"),false)),BorderLayout.EAST);return card;});tabs.addTab("Đã chặn",blocked.panel);
        setView("settings",panel,this::showSettings);long token=generation;query("PROFILE_GET",of("targetUserId",id(currentUser,"user")),r->{if(token!=generation)return;Map<String,Object> u=map(r.get("profile"));if(u.isEmpty())u=r;profile.clear();profile.putAll(u);updateSelf(u);nameField.setText(name(u));bio.setText(str(u,"bio"));version.setText("Phiên bản hồ sơ "+num(u,"version"));nameField.setEnabled(true);bio.setEnabled(true);});blocked.load(true);
    }
    private void openDirect(long target){mutate("CONVERSATION_DIRECT_GET_OR_CREATE",of("targetUserId",target),null,r->{Map<String,Object> conv=map(r.get("conversation"));if(conv.isEmpty())conv=new LinkedHashMap<>(r);conv.putIfAbsent("title","Trò chuyện với người dùng #"+target);conv.putIfAbsent("kind","DIRECT");conv.putIfAbsent("otherUserId",target);openChat(conv);});}
    private void receiveBroadcast(Map<String,Object> p){
        String key=str(p,"broadcastId");if(key.isBlank()||broadcasts.containsKey(key))return;
        broadcasts.put(key,new LinkedHashMap<>(p));while(broadcasts.size()>100)broadcasts.remove(broadcasts.keySet().iterator().next());
        if(!"broadcast".equals(view))broadcastUnread=Math.min(100,broadcastUnread+1);
        if(broadcastNav!=null)broadcastNav.setText("Broadcast"+(broadcastUnread>0?" ("+broadcastUnread+")":""));
        if("broadcast".equals(view))renderBroadcasts();
    }
    private void resetBroadcast(){broadcasts.clear();broadcastUnread=0;broadcastPendingKey=null;broadcastPendingContent=null;broadcastSending=false;broadcastInput=null;broadcastMessages=null;broadcastScroll=null;}
    private void showBroadcast(){
        broadcastUnread=0;if(broadcastNav!=null)broadcastNav.setText("Broadcast");
        JPanel panel=new JPanel(new BorderLayout(8,8));JPanel heading=titled("Broadcast · gửi cho tất cả");
        heading.add(readable("Kênh công khai cho mọi tài khoản đang online, kể cả người đã chặn. Chỉ giữ 100 tin trong phiên; không nhận lại tin khi offline."),BorderLayout.SOUTH);panel.add(heading,BorderLayout.NORTH);
        broadcastMessages=vertical();broadcastMessages.setBorder(new EmptyBorder(15,24,15,24));broadcastScroll=scroll(broadcastMessages);panel.add(broadcastScroll);
        JTextArea input=text(Objects.toString(broadcastPendingContent,""),3);broadcastInput=input;
        JButton send=Theme.primaryButton("Gửi cho tất cả");send.setEnabled(authenticated&&!broadcastSending);input.setEnabled(authenticated&&!broadcastSending);
        JLabel status=label(broadcastPendingKey==null?"Tối đa 4.000 ký tự": "Chưa xác định kết quả · gửi lại cùng nội dung để dùng lại mã",false);
        send.addActionListener(e->sendBroadcast(input,send,status));
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"broadcast-send");input.getActionMap().put("broadcast-send",new AbstractAction(){public void actionPerformed(ActionEvent e){send.doClick();}});
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"insert-break");
        JPanel composer=new JPanel(new BorderLayout(8,8));composer.setBorder(new EmptyBorder(10,24,15,24));composer.add(scroll(input));composer.add(horizontal(send,status),BorderLayout.SOUTH);panel.add(composer,BorderLayout.SOUTH);
        setView("broadcast",panel,this::renderBroadcasts);renderBroadcasts();
    }
    private void sendBroadcast(JTextArea input,JButton send,JLabel status){
        if(!authenticated||broadcastSending)return;
        String content=input.getText().trim();if(content.isBlank()||content.length()>4000){status.setText("Nhập từ 1 đến 4.000 ký tự.");return;}
        if(!content.equals(broadcastPendingContent)){broadcastPendingKey=UUID.randomUUID().toString();broadcastPendingContent=content;}
        broadcastSending=true;send.setEnabled(false);input.setEnabled(false);status.setText("Đang gửi…");SocialClient expected=client;String key=broadcastPendingKey;
        CompletableFuture.supplyAsync(()->blocking(expected,"BROADCAST_SEND",of("content",content),key),workers).whenComplete((ack,error)->invoke(()->{
            if(client!=expected||!authenticated)return;
            broadcastSending=false;send.setEnabled(true);input.setEnabled(true);
            if(error!=null){status.setText(unwrap(error) instanceof RemoteException?friendly(error):"Chưa xác định kết quả · gửi lại cùng nội dung để dùng lại mã");}
            else{receiveBroadcast(ack);broadcastPendingKey=null;broadcastPendingContent=null;input.setText("");status.setText("Server đã xếp hàng gửi tới "+num(ack,"recipientCount")+" phiên online.");}
            if("broadcast".equals(view)&&broadcastInput!=input)showBroadcast();
        }));
    }
    private void renderBroadcasts(){
        if(broadcastMessages==null)return;JScrollBar bar=broadcastScroll.getVerticalScrollBar();boolean bottom=bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-40;
        broadcastMessages.removeAll();if(broadcasts.isEmpty())broadcastMessages.add(label("Chưa có broadcast trong phiên này.",false));
        for(Map<String,Object> b:broadcasts.values()){JPanel c=card();c.add(label(str(b,"senderName")+" · "+time(b),false),BorderLayout.NORTH);c.add(readable(str(b,"content")));broadcastMessages.add(c);broadcastMessages.add(Box.createVerticalStrut(10));}
        broadcastMessages.revalidate();broadcastMessages.repaint();if(bottom)invoke(()->bar.setValue(bar.getMaximum()));
    }
    private void showConversations(){
        Page conversations=new Page("Tin nhắn","CONVERSATION_LIST",of(),conv->{JPanel c=card();c.add(label(name(conv),true),BorderLayout.NORTH);String preview=str(conv,"latestPreview","preview");Map<String,Object> latest=map(conv.get("latestMessage"));if(preview.isBlank())preview=str(latest,"content");c.add(readable(preview));c.add(horizontal(button("Mở · "+num(conv,"unreadCount","unread")+" chưa đọc",()->openChat(conv)),label("#"+id(conv,"conversation")+" · "+str(conv,"type","kind"),false)),BorderLayout.SOUTH);return c;},primary("Tạo nhóm",this::createGroup));
        Page invites=new Page("Lời mời vào nhóm","GROUP_INVITE_LIST",of(),invite->{JPanel c=card();c.add(label(str(invite,"title","groupTitle")+" · #"+num(invite,"conversationId")+" · "+str(invite,"status"),false));JPanel actions=horizontal();long inviteId=id(invite,"invite");if(num(invite,"inviteeId")!=id(currentUser,"user"))actions.add(button("Hủy",()->mutate("GROUP_INVITE_CANCEL",of("inviteId",inviteId),null,r->showConversations())));else{actions.add(button("Tham gia",()->mutate("GROUP_INVITE_ACCEPT",of("inviteId",inviteId),null,r->{info("Bạn có thể xem lịch sử từ lần tham gia nhóm này.");showConversations();})));actions.add(button("Từ chối",()->mutate("GROUP_INVITE_REJECT",of("inviteId",inviteId),null,r->showConversations())));}if(!"PENDING".equals(str(invite,"status")))disableButtons(actions);c.add(actions,BorderLayout.SOUTH);return c;});
        JPanel panel=new JPanel(new BorderLayout());JTabbedPane tabs=new JTabbedPane();tabs.addTab("Cuộc trò chuyện",conversations.panel);tabs.addTab("Lời mời nhóm",invites.panel);panel.add(tabs);setView("conversations",panel,this::showConversations);conversations.load(true);invites.load(true);
    }
    private void createGroup(){String title=JOptionPane.showInputDialog(this,"Tên nhóm","Tạo nhóm",JOptionPane.QUESTION_MESSAGE);if(title==null||title.isBlank())return;mutate("GROUP_CREATE",of("title",title),null,r->{Map<String,Object> c=map(r.get("conversation"));if(c.isEmpty())c=new LinkedHashMap<>(r);c.putIfAbsent("kind","GROUP");c.putIfAbsent("ownerId",id(currentUser,"user"));openChat(c);});}
    private void openChat(Map<String,Object> conv){
        long cid=id(conv,"conversation");if(cid==0){showError(new IllegalArgumentException("Server chưa trả conversationId"));return;}
        JPanel panel=new JPanel(new BorderLayout(8,8));JPanel heading=titled(name(conv),button("Danh sách",this::showConversations),button("Tải lịch sử cũ",()->loadHistory(true)),button("Thông tin nhóm",()->groupInfo(conv)),button("Tệp đang tải",()->media.resumeUploads(this)));
        typingLabel=label(" ",false);heading.add(typingLabel,BorderLayout.SOUTH);panel.add(heading,BorderLayout.NORTH);chatMessages=vertical();chatMessages.setBorder(new EmptyBorder(15,24,15,24));chatScroll=scroll(chatMessages);panel.add(chatScroll);
        chatInput=text("",3);JButton send=Theme.primaryButton("Gửi");send.addActionListener(e->sendMessage(cid,null,chatInput.getText()));JButton attach=button("Đính kèm tệp",()->{JFileChooser c=new JFileChooser();if(c.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;String caption=chatInput.getText();String filename=c.getSelectedFile().getName();media.upload(this,c.getSelectedFile().toPath(),"CHAT_FILE",mid->sendMessage(cid,mid,caption.isBlank()?filename:caption));});
        chatInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"send-message");chatInput.getActionMap().put("send-message",new AbstractAction(){public void actionPerformed(ActionEvent e){send.doClick();}});
        chatInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"insert-break");
        chatInput.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){typing();}public void removeUpdate(DocumentEvent e){typing();}public void changedUpdate(DocumentEvent e){typing();}private void typing(){if(udp!=null&&activeConversation==cid)udp.typing(cid,!chatInput.getText().isBlank());}});
        JPanel composer=new JPanel(new BorderLayout(8,8));composer.setBorder(new EmptyBorder(10,24,15,24));composer.add(scroll(chatInput));composer.add(horizontal(attach,send),BorderLayout.SOUTH);
        if(Boolean.FALSE.equals(conv.get("canSend"))||Boolean.FALSE.equals(conv.get("active"))){chatInput.setEnabled(false);send.setEnabled(false);attach.setEnabled(false);typingLabel.setText("Chỉ đọc lịch sử · bạn không còn quyền gửi tin mới");}
        panel.add(composer,BorderLayout.SOUTH);setView("chat",panel,()->syncChat(cid));activeConversation=cid;activeConversationData=new LinkedHashMap<>(conv);earliestSeq=0;latestSeq=0;loadHistory(false);
    }
    private void loadHistory(boolean older){
        long cid=activeConversation;if(cid==0)return;Map<String,Object> p=of("conversationId",cid,"limit",50);if(older&&earliestSeq>0)p.put("beforeSeq",earliestSeq);
        query("MESSAGE_HISTORY",p,r->{if(activeConversation!=cid)return;for(Map<String,Object> m:rows(r.get("items")))upsertMessage(m);latestSeq=Math.max(latestSeq,num(r,"latestSeq"));renderMessages(!older);ackMessages();});
    }
    private void syncChat(long cid){if(cid!=activeConversation)return;query("MESSAGE_HISTORY",of("conversationId",cid,"afterSeq",latestSeq,"limit",100),r->{if(cid!=activeConversation)return;for(Map<String,Object> m:rows(r.get("items")))upsertMessage(m);renderMessages(false);ackMessages();});}
    private void upsertMessage(Map<String,Object> m){long seq=num(m,"conversationSeq","seq"),mid=id(m,"message");if(mid==0)mid=seq;if(mid==0)return;Map<String,Object> merged=new LinkedHashMap<>(messages.getOrDefault(mid,Map.of()));String previousStatus=str(merged,"status");merged.putAll(m);if(statusRank(previousStatus)>statusRank(str(merged,"status")))merged.put("status",previousStatus);messages.put(mid,merged);if(earliestSeq==0||seq<earliestSeq)earliestSeq=seq;latestSeq=Math.max(latestSeq,seq);String clientId=str(m,"clientMessageId");pendingMessages.remove(clientId);while(messages.size()>200)messages.remove(messages.keySet().iterator().next());}
    private int statusRank(String s){return switch(s){case "READ"->3;case "DELIVERED"->2;case "STORED"->1;default->0;};}
    private void ackMessages(){if(!authenticated||latestSeq<=0||activeConversation<=0)return;long cid=activeConversation;long seq=messages.values().stream().mapToLong(m->num(m,"conversationSeq","seq")).max().orElse(0);if(seq==0)return;mutate("MESSAGE_DELIVERED",of("conversationId",cid,"throughSeq",seq),null,r->{});if(isActive())mutate("MESSAGE_READ",of("conversationId",cid,"throughSeq",seq),null,r->{});}
    private void renderMessages(boolean bottom){
        if(chatMessages==null)return;JScrollBar bar=chatScroll.getVerticalScrollBar();int old=bar.getValue(),height=bar.getMaximum();boolean nearBottom=old+bar.getVisibleAmount()>=height-40;
        chatMessages.removeAll();List<Map<String,Object>> sorted=new ArrayList<>(messages.values());sorted.sort(Comparator.comparingLong(m->num(m,"conversationSeq","seq")));
        for(Map<String,Object> m:sorted){JPanel c=card();String sender=str(m,"senderName","displayName");if(sender.isBlank())sender=num(m,"senderId")==id(currentUser,"user")?"Bạn":"Người gửi #"+num(m,"senderId");
            String receipt="GROUP".equals(str(activeConversationData,"kind","type"))?"Đã lưu · "+num(m,"deliveredByCount")+" đã nhận / "+num(m,"readByCount")+" đã đọc":messageStatus(str(m,"status"));
            c.add(label(sender+" · "+time(m)+" · "+receipt,false),BorderLayout.NORTH);c.add(readable(str(m,"content")));Map<String,Object> attachment=map(m.get("media"));if(attachment.isEmpty())attachment=map(m.get("attachment"));String mid=str(m,"mediaId");if(mid.isBlank())mid=str(attachment,"mediaId","id");if(!mid.isBlank()){String mediaId=mid;String fileName=str(attachment,"fileName","originalName");if(fileName.isBlank())fileName=str(m,"fileName");String suggestedName=fileName;c.add(horizontal(button("Tải tệp · "+(fileName.isBlank()?"#"+mid:fileName),()->media.download(this,mediaId,suggestedName))),BorderLayout.SOUTH);}chatMessages.add(c);chatMessages.add(Box.createVerticalStrut(10));}
        for(PendingMessage pending:pendingMessages.values())if(pending.conversationId==activeConversation){JPanel c=card();c.add(label("Bạn · "+pending.status,false),BorderLayout.NORTH);c.add(readable(pending.content));if(!"Đang gửi…".equals(pending.status))c.add(button("Thử lại cùng mã tin nhắn",()->retryMessage(pending)),BorderLayout.SOUTH);chatMessages.add(c);}
        if(chatMessages.getComponentCount()==0)chatMessages.add(label("Chưa có tin nhắn. Gửi lời chào đầu tiên!",false));chatMessages.revalidate();chatMessages.repaint();invoke(()->{if(bottom||nearBottom)bar.setValue(bar.getMaximum());else bar.setValue(old+Math.max(0,bar.getMaximum()-height));});
    }
    private String messageStatus(String status){return switch(status){case "READ"->"Đã đọc";case "DELIVERED"->"Đã nhận";case "STORED"->"Đã lưu trên server";default->status.isBlank()?"Đã lưu trên server":status;};}
    private void sendMessage(long cid,String mediaId,String content){if(content.length()>4000){info("Tin nhắn tối đa 4.000 ký tự.");return;}if(content.isBlank()&&mediaId==null)return;if(!authenticated){info("Đăng nhập lại để gửi tin.");return;}if(pendingMessages.size()>=50){info("Có 50 tin chưa xác định kết quả. Tra cứu / thử lại các tin đó trước khi gửi thêm.");return;}String stableId=UUID.randomUUID().toString();PendingMessage p=new PendingMessage(cid,stableId,UUID.randomUUID().toString(),content,mediaId);pendingMessages.put(stableId,p);if(activeConversation==cid){chatInput.setText("");renderMessages(true);}submitMessage(p);}
    private void submitMessage(PendingMessage p){p.status="Đang gửi…";SocialClient requestedClient=client;CompletableFuture.supplyAsync(()->blocking(requestedClient,"MESSAGE_SEND",of("conversationId",p.conversationId,"clientMessageId",p.clientId,"content",p.content,"mediaId",p.mediaId),p.key),workers).whenComplete((ack,error)->invoke(()->{
        if(client!=requestedClient)return;
        if(error!=null){p.status=(unwrap(error) instanceof RemoteException?"Không gửi được · ":"Chưa xác định kết quả · ")+friendly(error);if(activeConversation==p.conversationId)renderMessages(false);return;}
        Map<String,Object> msg=map(ack.get("message"));if(msg.isEmpty())msg=new LinkedHashMap<>(ack);msg.putIfAbsent("content",p.content);msg.putIfAbsent("senderId",id(currentUser,"user"));msg.putIfAbsent("conversationId",p.conversationId);msg.putIfAbsent("clientMessageId",p.clientId);if(p.mediaId!=null)msg.putIfAbsent("mediaId",p.mediaId);msg.putIfAbsent("status","STORED");pendingMessages.remove(p.clientId);if(activeConversation==p.conversationId){upsertMessage(msg);renderMessages(false);}
    }));}
    private void retryMessage(PendingMessage p){SocialClient requestedClient=client;CompletableFuture.supplyAsync(()->blocking(requestedClient,"MESSAGE_LOOKUP",of("clientMessageId",p.clientId),null),workers).whenComplete((found,error)->invoke(()->{
        if(client!=requestedClient)return;
        if(error==null){Map<String,Object> m=map(found.get("message"));if(m.isEmpty())m=found;pendingMessages.remove(p.clientId);if(activeConversation==p.conversationId){upsertMessage(m);renderMessages(false);}}
        else if(unwrap(error) instanceof RemoteException r&&"NOT_FOUND".equals(r.getCode()))submitMessage(p);else{p.status="Tra cứu thất bại · "+friendly(error);renderMessages(false);}
    }));}
    private static final class PendingMessage{final long conversationId;final String clientId,key,content;final String mediaId;String status="Đang gửi…";PendingMessage(long c,String id,String key,String content,String mediaId){conversationId=c;clientId=id;this.key=key;this.content=content;this.mediaId=mediaId;}}

    private void groupInfo(Map<String,Object> original){
        long cid=id(original,"conversation");if(!"GROUP".equals(str(original,"type","kind"))){info("Đây là cuộc trò chuyện cá nhân.");return;}
        boolean owner=num(original,"ownerId")==id(currentUser,"user")||"OWNER".equals(str(original,"role"));
        JDialog d=dialog("Thông tin nhóm",720,600);JPanel p=new JPanel(new BorderLayout(8,8));JPanel header=vertical();header.add(label(name(original),true));header.add(readable("Lịch sử hiển thị từ lần tham gia. Chặn người dùng không ẩn tin nhắn của họ trong nhóm chung."));
        JTextField title=new JTextField(name(original));if(owner)header.add(horizontal(title,button("Đổi tên",()->mutate("GROUP_RENAME",of("conversationId",cid,"title",title.getText(),"expectedVersion",num(original,"version")),null,r->{d.dispose();showConversations();}))));
        JPanel groupActions=horizontal();if(owner)groupActions.add(button("Mời bạn bè",()->selectFriend(uid->mutate("GROUP_INVITE",of("conversationId",cid,"targetUserId",uid),null,r->info("Đã gửi lời mời vào nhóm.")))));
        groupActions.add(button("Rời nhóm",()->{if(confirm(owner?"Chủ nhóm cần chuyển quyền trước khi rời (trừ khi là thành viên duy nhất). Tiếp tục?":"Rời nhóm? Bạn sẽ mất quyền đọc lịch sử của nhóm."))mutate("GROUP_LEAVE",of("conversationId",cid,"expectedVersion",num(original,"version")),null,r->{d.dispose();showConversations();});}));
        if(owner)groupActions.add(button("Giải tán nhóm",()->{if(confirm("Giải tán nhóm cho tất cả thành viên?"))mutate("GROUP_DISBAND",of("conversationId",cid,"expectedVersion",num(original,"version")),null,r->{d.dispose();showConversations();});}));header.add(groupActions);
        p.add(header,BorderLayout.NORTH);JPanel members=vertical();p.add(scroll(members));JLabel status=label("Đang tải thành viên…",false);String[] cursor={null};JButton more=button("Thêm thành viên",()->{});
        Runnable load=()->{Map<String,Object> request=of("conversationId",cid,"limit",20,"cursor",cursor[0]);query("GROUP_MEMBERS_GET",request,r->{
            if(members.getComponentCount()>=60)members.removeAll();for(Map<String,Object> member:rows(r.get("items"))){Map<String,Object> user=map(member.get("user"));if(user.isEmpty())user=member;long uid=id(user,"user");JPanel row=card();row.add(label(name(user)+" · "+str(member,"role"),false));JPanel actions=horizontal();if(owner&&uid!=id(currentUser,"user")){
                actions.add(button("Chuyển quyền chủ nhóm",()->{if(confirm("Chuyển quyền chủ nhóm cho người này?"))mutate("GROUP_OWNER_TRANSFER",of("conversationId",cid,"userId",uid,"expectedVersion",num(original,"version")),null,x->{d.dispose();showConversations();});}));
                actions.add(button("Mời ra khỏi nhóm",()->{if(confirm("Mời thành viên này ra khỏi nhóm?"))mutate("GROUP_KICK",of("conversationId",cid,"userId",uid,"expectedVersion",num(original,"version")),null,x->{d.dispose();showConversations();});}));}
                row.add(actions,BorderLayout.SOUTH);members.add(row);}
            cursor[0]=str(r,"nextCursor");more.setEnabled(bool(r,"hasMore")||!cursor[0].isBlank());status.setText("Chỉ chủ nhóm được đổi tên / quản lý thành viên.");members.revalidate();members.repaint();});};more.addActionListener(e->load.run());p.add(horizontal(status,more),BorderLayout.SOUTH);d.add(p);d.setVisible(true);load.run();
    }
    private void selectFriend(LongConsumer selection){
        JDialog d=dialog("Chọn bạn bè",550,500);Page page=new Page("Mời một người bạn","RELATION_LIST",of("kind","FRIENDS"),rel->{Map<String,Object> u=relationUser(rel);long uid=id(u,"user");JPanel row=card();row.add(button(name(u),()->{d.dispose();selection.accept(uid);}));return row;});d.add(page.panel);d.setVisible(true);page.load(true);
    }
    private void showNotifications(){
        Page page=new Page("Thông báo","NOTIFICATION_LIST",of("unreadOnly",false),n->{JPanel c=card();long nid=id(n,"notification");boolean available=!Boolean.FALSE.equals(n.get("available"));boolean read=bool(n,"read")||bool(n,"isRead")||num(n,"isRead")>0;c.add(label((read?"":"● ")+notificationTitle(str(n,"kind","title","type"))+" · "+time(n),false),BorderLayout.NORTH);c.add(readable(available?str(n,"content","body","preview"):"Nội dung không còn khả dụng."));JPanel actions=horizontal(button("Đánh dấu đã đọc",()->mutate("NOTIFICATION_READ",of("notificationIds",List.of(nid)),null,r->{refreshCount();showNotifications();})));if(available)actions.add(button("Mở nội dung",()->{
                mutate("NOTIFICATION_READ",of("notificationIds",List.of(nid)),null,r->refreshCount());Map<String,Object> target=map(n.get("target"));String type=str(n,"targetType");if(type.isBlank())type=str(target,"type","targetType");long id=num(n,"targetId");if(id==0)id=num(target,"id","targetId");switch(type){case "POST","COMMENT"->showPost(num(n,"postId")>0?num(n,"postId"):id);case "USER","PROFILE"->showProfile(id);case "CONVERSATION","MESSAGE","GROUP"->{long cid=num(n,"conversationId");if(cid==0)cid=id;openChat(of("conversationId",cid,"title","Cuộc trò chuyện #"+cid));}case "RELATION","FRIEND_REQUEST"->showPeople();default->showConversations();}
                if(type.isBlank()){if(num(n,"postId")>0)showPost(num(n,"postId"));else if(num(n,"relationId")>0)showPeople();else if(num(n,"conversationId")>0)openChat(of("conversationId",num(n,"conversationId"),"title","Cuộc trò chuyện #"+num(n,"conversationId")));else showConversations();}
            }));c.add(actions,BorderLayout.SOUTH);return c;});setView("notifications",page.panel,this::showNotifications);page.load(true);refreshCount();
    }
    private String notificationTitle(String type){return switch(type){case "FRIEND_REQUEST"->"Bạn có lời mời kết bạn";case "FRIEND_ACCEPT"->"Lời mời kết bạn được chấp nhận";case "LIKE"->"Có người thích bài viết của bạn";case "COMMENT"->"Có bình luận mới";case "MESSAGE"->"Bạn có tin nhắn mới";case "GROUP_INVITE"->"Bạn được mời vào nhóm";default->type;};}
    private void report(String type,long target){
        JDialog d=dialog("Báo cáo nội dung",510,370);JPanel p=vertical();JComboBox<String> reason=new JComboBox<>(new String[]{"SPAM","HARASSMENT","INAPPROPRIATE","OTHER"});JTextArea description=text("",5);p.add(label("Báo cáo "+type+" #"+target,true));addField(p,"Lý do",reason);addField(p,"Mô tả thêm",scroll(description));JButton submit=Theme.primaryButton("Gửi báo cáo");submit.addActionListener(e->mutate("REPORT_CREATE",of("targetType",type,"targetId",target,"reason",reason.getSelectedItem(),"description",description.getText()),submit,r->{d.dispose();info("Đã ghi nhận báo cáo. Quản trị viên sẽ xem xét.");}));p.add(horizontal(submit,button("Đóng",d::dispose)));d.add(p);d.setVisible(true);
    }
    private void showAdmin(){
        Page reports=new Page("Hàng đợi báo cáo","ADMIN_REPORT_LIST",of("filter",""),r->{JPanel c=card();c.add(label("#"+id(r,"report")+" · "+str(r,"targetType")+" · "+str(r,"status"),false),BorderLayout.NORTH);c.add(readable(str(r,"reason")+"\n"+str(r,"description")));c.add(button("Xem & xử lý",()->reviewReport(id(r,"report"))),BorderLayout.SOUTH);return c;});
        Page audit=new Page("Nhật ký quản trị","ADMIN_AUDIT_LIST",of(),r->{JPanel c=card();c.add(label("#"+id(r,"audit")+" · "+str(r,"action")+" · "+time(r),false),BorderLayout.NORTH);c.add(readable(str(r,"reason","summary")));c.add(button("Chi tiết",()->query("ADMIN_AUDIT_GET",of("auditId",id(r,"audit")),data->showRecord("Nhật ký quản trị",data))),BorderLayout.SOUTH);return c;});
        JPanel controls=vertical();controls.setBorder(new EmptyBorder(25,25,25,25));controls.add(label("Khôi phục tài khoản / nhóm",true));JTextField user=new JTextField(),conversation=new JTextField(),newOwner=new JTextField(),version=new JTextField("1");JTextArea reason=text("",3);addField(controls,"User ID cần mở khóa",user);addField(controls,"Conversation ID",conversation);addField(controls,"User ID chủ nhóm mới",newOwner);addField(controls,"Phiên bản nhóm hiện tại",version);addField(controls,"Lý do (bắt buộc)",scroll(reason));
        controls.add(horizontal(button("Mở khóa tài khoản",()->{try{mutate("ADMIN_USER_UNSUSPEND",of("targetUserId",Long.parseLong(user.getText()),"reason",reason.getText()),null,r->info("Đã mở khóa và ghi nhật ký."));}catch(NumberFormatException ex){info("Nhập ID số hợp lệ.");}}),button("Chuyển chủ nhóm bị khóa",()->{try{mutate("ADMIN_GROUP_OWNER_TRANSFER",of("conversationId",Long.parseLong(conversation.getText()),"targetUserId",Long.parseLong(newOwner.getText()),"expectedVersion",Long.parseLong(version.getText()),"reason",reason.getText()),null,r->info("Đã chuyển quyền và ghi nhật ký."));}catch(NumberFormatException ex){info("Nhập ID và phiên bản số hợp lệ.");}}),button("Đóng nhóm bị khóa chủ",()->{try{if(confirm("Đóng nhóm này bằng quyền quản trị?"))mutate("ADMIN_GROUP_CLOSE",of("conversationId",Long.parseLong(conversation.getText()),"expectedVersion",Long.parseLong(version.getText()),"reason",reason.getText()),null,r->info("Đã đóng nhóm và ghi nhật ký."));}catch(NumberFormatException ex){info("Nhập ID và phiên bản số hợp lệ.");}})));
        JPanel panel=new JPanel(new BorderLayout());JTabbedPane tabs=new JTabbedPane();tabs.addTab("Báo cáo",reports.panel);tabs.addTab("Nhật ký",audit.panel);tabs.addTab("Khôi phục",scroll(controls));panel.add(tabs);setView("admin",panel,this::showAdmin);reports.load(true);audit.load(true);
    }
    private void reviewReport(long reportId){query("ADMIN_REPORT_GET",of("reportId",reportId),data->{
        Map<String,Object> report=map(data.get("report"));if(report.isEmpty())report=data;Map<String,Object> r=report;JDialog d=dialog("Báo cáo #"+reportId,650,620);JPanel p=vertical();p.add(label(str(r,"targetType")+" #"+num(r,"targetId")+" · "+str(r,"status"),true));p.add(scroll(readable(recordText(data))));
        JComboBox<String> action=new JComboBox<>(new String[]{"NO_ACTION","HIDE_POST","HIDE_COMMENT","SUSPEND_USER"});JTextArea reason=text("",3);addField(p,"Hành động",action);addField(p,"Lý do quyết định",scroll(reason));JButton claim=Theme.primaryButton("Nhận xử lý");JButton resolve=Theme.primaryButton("Xác nhận xử lý");
        claim.setEnabled("OPEN".equals(str(r,"status")));resolve.setEnabled("IN_REVIEW".equals(str(r,"status"))&&num(r,"assignedAdminId")==id(currentUser,"user"));
        claim.addActionListener(e->mutate("ADMIN_REPORT_CLAIM",of("reportId",reportId,"expectedVersion",num(r,"version")),claim,updated->{d.dispose();reviewReport(reportId);}));resolve.addActionListener(e->{if(reason.getText().isBlank()){info("Nhập lý do quyết định.");return;}if(confirm("Áp dụng quyết định quản trị và ghi nhật ký?"))mutate("ADMIN_REPORT_RESOLVE",of("reportId",reportId,"action",action.getSelectedItem(),"reason",reason.getText(),"expectedVersion",num(r,"version")),resolve,updated->{d.dispose();showAdmin();});});p.add(horizontal(claim,resolve,button("Đóng",d::dispose)));d.add(p);d.setVisible(true);
    });}
    private void showRecord(String title,Map<String,Object> record){JDialog d=dialog(title,650,500);d.add(scroll(readable(recordText(record))));d.setVisible(true);}
    private String recordText(Map<String,Object> data){StringBuilder b=new StringBuilder();data.forEach((k,v)->{if(!Set.of("udpKey","password","passwordHash").contains(k))b.append(k).append(": ").append(v).append('\n');});return b.toString();}
}
