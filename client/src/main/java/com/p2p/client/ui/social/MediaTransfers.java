package com.p2p.client.ui.social;

import com.p2p.client.network.social.SocialClient;
import com.p2p.common.social.WireMessage;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import static com.p2p.client.ui.social.Values.*;

/** Bounded, background transfer controller. Completed bytes are never advertised as verified. */
final class MediaTransfers implements AutoCloseable {
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"social-media");t.setDaemon(true);return t;});
    private final ExecutorService uploadWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"social-upload");t.setDaemon(true);return t;});
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"social-transfer-watchdog");t.setDaemon(true);return t;});
    private final Supplier<SocialClient> client;
    private volatile SocialClient boundClient;
    private final Consumer<Throwable> errors;
    private final Map<String,Download> downloads=new HashMap<>();
    private final Map<String,Upload> uploads=new ConcurrentHashMap<>();
    private volatile boolean closed;
    MediaTransfers(Supplier<SocialClient> client,Consumer<Throwable> errors){this.client=client;this.errors=errors;watchdog.scheduleAtFixedRate(()->{if(!closed)io.execute(this::expireDownloads);},1,1,TimeUnit.SECONDS);}
    void bind(SocialClient session){boundClient=session;}
    private Map<String,Object> call(String type,Map<String,Object> p,boolean mutation){
        return call(type,p,mutation?UUID.randomUUID().toString():null);
    }
    private Map<String,Object> call(String type,Map<String,Object> p,String key){
        if(closed)throw new CancellationException("Phiên đã đóng");
        SocialClient c=boundClient; if(c==null)throw new IllegalStateException("Chưa kết nối server");
        return (key!=null?c.request(type,p,key):c.request(type,p)).join();
    }
    void upload(Component owner,Path path,String purpose,Consumer<String> ready){
        Progress progress=new Progress(owner,"Tải lên · "+path.getFileName());
        if(uploads.size()>=8){errors.accept(new IOException("Tối đa 8 tệp đang tải. Hủy hoặc tiếp tục tệp hiện tại."));progress.finish("Chưa bắt đầu");return;}
        Upload job=new Upload(path,purpose,progress,ready);uploads.put(job.jobId,job);uploadWorker.execute(()->upload(job,false));
    }
    private void upload(Upload u,boolean resume){
        try{
            if(closed)throw new IOException("Phiên đã đóng");
            if(u.progress.cancelled)throw new CancellationException();
            if(u.sha==null){u.size=Files.size(u.path);if(u.size>(u.purpose.equals("CHAT_FILE")?25L:5L)*1024*1024)throw new IOException("Tệp vượt giới hạn kích thước");u.sha=hash(u.path);}
            Map<String,Object> init;
            if(resume&&u.mediaId!=null){Map<String,Object> status=call("MEDIA_UPLOAD_STATUS",of("mediaId",u.mediaId),false);if("READY".equals(str(status,"state"))){uploads.remove(u.jobId);u.progress.finish("Đã kiểm tra · READY");edt(()->{if(!closed)u.ready.accept(u.mediaId);});return;}
                if(u.resumeKey==null)u.resumeKey=UUID.randomUUID().toString();init=call("MEDIA_UPLOAD_RESUME",of("mediaId",u.mediaId,"size",u.size,"sha256",u.sha),u.resumeKey);u.resumeKey=null;
            }else init=call("MEDIA_UPLOAD_INIT",of("purpose",u.purpose,"fileName",u.path.getFileName().toString(),"size",u.size,"mimeType",mime(u.path),"sha256",u.sha),u.initKey);
            u.mediaId=str(init,"mediaId");
            if(bool(init,"requiresResume")){u.resumeKey=UUID.randomUUID().toString();init=call("MEDIA_UPLOAD_RESUME",of("mediaId",u.mediaId,"size",u.size,"sha256",u.sha),u.resumeKey);u.resumeKey=null;}
            if(u.progress.cancelled){call("MEDIA_UPLOAD_CANCEL",of("mediaId",u.mediaId),true);throw new CancellationException();}
            String transfer=str(init,"transferId");long offset=num(init,"offset","acknowledgedOffset");
            int chunk=(int)num(init,"chunkBytes");if(chunk<=0||chunk>49152)chunk=49152;
            try(RandomAccessFile file=new RandomAccessFile(u.path.toFile(),"r")){
                file.seek(offset);byte[] buffer=new byte[chunk];
                while(offset<u.size){
                    if(u.progress.cancelled){call("MEDIA_UPLOAD_CANCEL",of("mediaId",u.mediaId),true);throw new CancellationException();}
                    int n=file.read(buffer);if(n<0)throw new EOFException("Tệp thay đổi trong lúc tải");
                    Map<String,Object> ack=call("MEDIA_UPLOAD_CHUNK",of("transferId",transfer,"offset",offset,"data",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,n))),false);
                    offset+=n;u.progress.update(offset,u.size,"Đang tải lên");
                    long expected=num(ack,"expectedOffset");if(expected>0&&expected!=offset){offset=expected;file.seek(offset);}
                }
            }
            String commitKey=u.commitKeys.computeIfAbsent(transfer,k->UUID.randomUUID().toString());
            Map<String,Object> result=call("MEDIA_UPLOAD_COMMIT",of("transferId",transfer),commitKey);
            for(int attempts=0;"VERIFYING".equals(str(result,"state"))&&attempts<20;attempts++){
                Thread.sleep(Math.min(2000,200L*(attempts+1)));result=call("MEDIA_UPLOAD_STATUS",of("mediaId",u.mediaId),false);
            }
            if(!"READY".equals(str(result,"state","status")))throw new IOException("Tệp chưa READY. Có thể thử tiếp tục từ mục Tệp đang tải.");
            uploads.remove(u.jobId);u.progress.finish("Đã tải lên và kiểm tra SHA-256");edt(()->{if(!closed)u.ready.accept(u.mediaId);});
        }catch(CancellationException e){uploads.remove(u.jobId);u.progress.finish("Đã hủy");}
        catch(Throwable e){if(closed){u.progress.finish("Phiên đã đóng");return;}u.progress.failed("Tải lên gián đoạn · chọn Tiếp tục",()->{u.progress.dialog.dispose();u.progress=new Progress(u.progress.dialog.getOwner(),"Tiếp tục tải lên");uploadWorker.execute(()->upload(u,true));});edt(()->errors.accept(e));}
    }
    void resumeUploads(Component owner){
        if(uploads.isEmpty()){JOptionPane.showMessageDialog(owner,"Không có tệp đang tải dở trong phiên này.");return;}
        for(Upload u:List.copyOf(uploads.values())){u.progress.dialog.dispose();u.progress=new Progress(owner,"Tiếp tục · "+u.path.getFileName());uploadWorker.execute(()->upload(u,true));}
    }
    void download(Component owner,String mediaId,String suggested){
        JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new File(suggested.isBlank()?"attachment-"+mediaId:safeName(suggested)));
        if(chooser.showSaveDialog(owner)!=JFileChooser.APPROVE_OPTION)return;
        Path target=chooser.getSelectedFile().toPath().toAbsolutePath();
        io.execute(()->{boolean exists=Files.exists(target);edt(()->{if(exists&&JOptionPane.showConfirmDialog(owner,"Thay thế tệp đã tồn tại?","Lưu tệp",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;startDownload(owner,mediaId,target,null);});});
    }
    void preview(Component owner,String mediaId){
        io.execute(()->{try{
            Path tmp=Files.createTempFile("social-preview-",".img");Files.deleteIfExists(tmp);
            edt(()->startDownload(owner,mediaId,tmp,path->io.execute(()->{
                try{java.awt.image.BufferedImage img;try(var input=javax.imageio.ImageIO.createImageInputStream(path.toFile())){var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Không phải ảnh hợp lệ");var reader=readers.next();try{reader.setInput(input);if((long)reader.getWidth(0)*reader.getHeight(0)>20_000_000)throw new IOException("Ảnh quá lớn để xem trước (tối đa 20 megapixel)");img=reader.read(0);}finally{reader.dispose();}}
                    int w=img.getWidth(),h=img.getHeight();double scale=Math.min(1,Math.min(800.0/w,600.0/h));
                    ImageIcon icon=new ImageIcon(img.getScaledInstance((int)(w*scale),(int)(h*scale),Image.SCALE_SMOOTH));
                    edt(()->JOptionPane.showMessageDialog(owner,new JLabel(icon),"Ảnh",JOptionPane.PLAIN_MESSAGE));
                }catch(Throwable e){edt(()->errors.accept(e));}finally{try{Files.deleteIfExists(path);}catch(IOException ignored){}}
            })));
        }catch(Throwable e){edt(()->errors.accept(e));}});
    }
    private void startDownload(Component owner,String mediaId,Path target,Consumer<Path> complete){
        Progress progress=new Progress(owner,"Tải xuống · "+target.getFileName());
        io.execute(()->{try{
            if(downloads.size()>=4)throw new IOException("Tối đa 4 tệp tải xuống đồng thời.");
            Path part=target.resolveSibling(target.getFileName()+".social-"+safeName(mediaId)+".part");
            Path meta=part.resolveSibling(part.getFileName()+".metadata");
            long offset=Files.exists(part)&&Files.exists(meta)?Files.size(part):0;
            Map<String,Object> init=call("MEDIA_DOWNLOAD_INIT",of("mediaId",mediaId,"offset",offset),true);
            if(bool(init,"requiresResume"))init=call("MEDIA_DOWNLOAD_INIT",of("mediaId",mediaId,"offset",offset),true);
            if(bool(init,"requiresResume"))throw new IOException("Lượt tải cũ đã hết hạn. Hãy chọn tiếp tục lại.");
            long size=num(init,"size");String sha=str(init,"sha256");
            String fingerprint=mediaId+":"+size+":"+sha;
            if(offset>0&&!fingerprint.equals(Files.readString(meta))){call("MEDIA_DOWNLOAD_CANCEL",of("transferId",str(init,"transferId"),"reason","IO_ERROR"),true);Files.deleteIfExists(part);Files.deleteIfExists(meta);throw new IOException("Metadata tệp đã thay đổi. Hãy tải lại.");}
            Files.writeString(meta,fingerprint);RandomAccessFile file=new RandomAccessFile(part.toFile(),"rw");file.setLength(offset);file.seek(offset);
            Download d=new Download(str(init,"transferId"),part,meta,target,file,size,sha,offset,progress,complete);downloads.put(d.id,d);
            progress.update(offset,size,"Đang tải xuống");
        }catch(Throwable e){progress.failed("Không thể tải xuống");edt(()->errors.accept(e));}});
    }
    void event(WireMessage event){if(!closed&&event.getType()!=null&&event.getType().startsWith("MEDIA_DOWNLOAD_"))try{io.execute(()->handle(event));}catch(RejectedExecutionException ignored){}}
    private void handle(WireMessage e){
        Map<String,Object> p=e.getPayload();Download d=downloads.get(str(p,"transferId"));if(d==null)return;
        d.lastActivity=System.nanoTime();
        try{
            if(d.progress.cancelled){call("MEDIA_DOWNLOAD_CANCEL",of("transferId",d.id,"reason","USER_CANCEL"),true);finish(d,false);return;}
            switch(e.getType()){
                case "MEDIA_DOWNLOAD_CHUNK"->{
                    byte[] bytes=Base64.getDecoder().decode(str(p,"data"));long offset=num(p,"offset");
                    if(bytes.length>49152||offset!=d.offset||offset+bytes.length>d.size)throw new IOException("Offset/chunk không hợp lệ");
                    d.file.write(bytes);d.offset+=bytes.length;d.file.getFD().sync();
                    call("MEDIA_DOWNLOAD_ACK",of("transferId",d.id,"throughOffset",d.offset),false);d.progress.update(d.offset,d.size,"Đang tải xuống");
                }
                case "MEDIA_DOWNLOAD_COMPLETE"->{
                    d.file.close();String actual;
                    try{if(d.offset!=d.size)throw new HashMismatchException();actual=verify(d.part,d.size,d.sha);}catch(HashMismatchException mismatch){
                        call("MEDIA_DOWNLOAD_CANCEL",of("transferId",d.id,"reason","HASH_MISMATCH"),true);
                        Files.deleteIfExists(d.part);Files.deleteIfExists(d.meta);throw mismatch;
                    }
                    call("MEDIA_DOWNLOAD_VERIFIED",of("transferId",d.id,"actualSize",d.offset,"actualSha256",actual),true);
                    Files.move(d.part,d.target,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(d.meta);downloads.remove(d.id);
                    if(d.complete==null)d.progress.saved(d.target);else d.progress.finish("Đã tải ảnh · SHA-256 hợp lệ");if(d.complete!=null)edt(()->{if(!closed)d.complete.accept(d.target);else CompletableFuture.runAsync(()->{try{Files.deleteIfExists(d.target);}catch(IOException ignored){}});});
                }
                case "MEDIA_DOWNLOAD_FAILED"->throw new IOException("Tải xuống thất bại: "+str(p,"errorCode"));
                default->{ }
            }
        }catch(Throwable error){try{finish(d,true);}catch(Exception ignored){}d.progress.failed("Tải gián đoạn · chọn lại cùng tệp để tiếp tục");edt(()->errors.accept(error));}
    }
    private void finish(Download d,boolean retain)throws IOException{retain=retain&&d.complete==null;downloads.remove(d.id);d.file.close();if(!retain){Files.deleteIfExists(d.part);Files.deleteIfExists(d.meta);if(d.complete!=null)Files.deleteIfExists(d.target);}d.progress.finish(retain?"Đã tạm dừng":"Đã hủy");}
    private void expireDownloads(){for(Download d:List.copyOf(downloads.values()))if(d.progress.cancelled||System.nanoTime()-d.lastActivity>TimeUnit.SECONDS.toNanos(30)){
        try{call("MEDIA_DOWNLOAD_CANCEL",of("transferId",d.id,"reason",d.progress.cancelled?"USER_CANCEL":"IO_ERROR"),true);}catch(Throwable ignored){}
        try{finish(d,!d.progress.cancelled);}catch(IOException ignored){}d.progress.failed("Đã dừng · chọn lại cùng tệp để tiếp tục");
    }}
    void disconnected(){if(io.isShutdown())return;io.execute(()->{for(Download d:List.copyOf(downloads.values()))try{finish(d,true);}catch(IOException ignored){}});}
    public synchronized void close(){if(closed)return;closed=true;watchdog.shutdownNow();disconnected();io.shutdown();uploadWorker.shutdownNow();uploads.clear();boundClient=null;}
    private static String mime(Path path)throws IOException{String m=Files.probeContentType(path);return m==null?"application/octet-stream":m;}
    private static String safeName(String s){return s.replaceAll("[\\\\/:*?\"<>|]","_");}
    private static String hash(Path path)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    static String verify(Path path,long size,String sha)throws Exception{if(Files.size(path)!=size)throw new HashMismatchException();String actual=hash(path);if(!actual.equalsIgnoreCase(sha))throw new HashMismatchException();return actual;}
    static final class HashMismatchException extends IOException{HashMismatchException(){super("Kích thước hoặc SHA-256 không khớp; tệp chưa được lưu.");}}
    private static void edt(Runnable r){SwingUtilities.invokeLater(r);}
    private static final class Upload{
        final Path path;final String purpose;final Consumer<String> ready;final String jobId=UUID.randomUUID().toString(),initKey=UUID.randomUUID().toString();final Map<String,String> commitKeys=new HashMap<>();String resumeKey;volatile Progress progress;long size;String mediaId,sha;
        Upload(Path p,String purpose,Progress progress,Consumer<String> ready){path=p;this.purpose=purpose;this.progress=progress;this.ready=ready;}
    }
    private static final class Download{
        final String id;final Path part,meta,target;final RandomAccessFile file;final long size;final String sha;long offset;long lastActivity=System.nanoTime();final Progress progress;final Consumer<Path> complete;
        Download(String id,Path part,Path meta,Path target,RandomAccessFile file,long size,String sha,long offset,Progress progress,Consumer<Path> complete){this.id=id;this.part=part;this.meta=meta;this.target=target;this.file=file;this.size=size;this.sha=sha;this.offset=offset;this.progress=progress;this.complete=complete;}
    }
    private static final class Progress{
        final JDialog dialog;final JProgressBar bar=new JProgressBar(0,1000);final JLabel label=new JLabel("Đang chuẩn bị…");final JButton cancel=new JButton("Hủy");volatile boolean cancelled;long last;
        Progress(Component owner,String title){dialog=new JDialog(owner instanceof Window w?w:SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);JPanel p=new JPanel(new BorderLayout(12,12));p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));p.add(label,BorderLayout.NORTH);p.add(bar);cancel.addActionListener(e->cancelled=true);p.add(cancel,BorderLayout.SOUTH);dialog.setContentPane(p);dialog.setSize(440,180);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);}
        void update(long n,long total,String text){long now=System.nanoTime();if(now-last<100_000_000&&n!=total)return;last=now;edt(()->{bar.setValue(total==0?1000:(int)(n*1000/total));label.setText(text+" · "+n/1024+" / "+total/1024+" KiB");});}
        void finish(String text){edt(()->{label.setText(text);bar.setValue(1000);javax.swing.Timer timer=new javax.swing.Timer(1800,e->dialog.dispose());timer.setRepeats(false);timer.start();});}
        void failed(String text){failed(text,dialog::dispose);}
        void failed(String text,Runnable retry){edt(()->{label.setText(text);for(var l:cancel.getActionListeners())cancel.removeActionListener(l);cancel.setText("Tiếp tục / đóng");cancel.addActionListener(e->retry.run());});}
        void saved(Path target){edt(()->{label.setText("Đã lưu · SHA-256 hợp lệ · "+target.getFileName());label.setToolTipText(target.toString());bar.setValue(1000);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));JButton folder=new JButton("Mở thư mục"),close=new JButton("Đóng");folder.addActionListener(e->CompletableFuture.runAsync(()->{try{if(!Desktop.isDesktopSupported())throw new IOException("Hệ điều hành không hỗ trợ mở thư mục");Desktop.getDesktop().open(target.getParent().toFile());}catch(Exception error){edt(()->JOptionPane.showMessageDialog(dialog,error.getMessage(),"Không thể mở thư mục",JOptionPane.WARNING_MESSAGE));}}));close.addActionListener(e->dialog.dispose());actions.add(folder);actions.add(close);dialog.getContentPane().add(actions,BorderLayout.SOUTH);dialog.getContentPane().revalidate();dialog.repaint();});}
    }
}
