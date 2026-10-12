package com.p2p.server.ops;
import com.p2p.server.social.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** Explicit disposable demonstration fixture. Credentials come from the operator environment. */
public final class DemoSeedTool {
    private DemoSeedTool() {
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[0].equals("--config"))throw new IllegalArgumentException("Usage: DemoSeedTool --config server/config/social.properties; requires SOCIAL_DEMO_PASSWORD");
        String password=System.getenv("SOCIAL_DEMO_PASSWORD");
        if(password==null||password.length()<10)throw new IllegalArgumentException("Set SOCIAL_DEMO_PASSWORD to a demonstration-only password of at least 10 characters");
        Path file=Path.of(args[1]).toAbsolutePath();
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(file)) {
            p.load(in);
        }
        Path base=file.getParent().resolve(p.getProperty("app.root","../..")).normalize();
        Map<String,Object> result=seed(base.resolve(p.getProperty("database.path","data/social-network.db")),base.resolve(p.getProperty("storage.root","data/media")),password);
        System.out.println("Seeded disposable demo accounts: "+result.get("usernames")+" (password supplied through SOCIAL_DEMO_PASSWORD)");
    }
    public static Map<String,Object> seed(Path databasePath,Path storageRoot,String password)throws Exception {
        if(password==null||password.length()<10||password.length()>128)throw new IllegalArgumentException("Use a demo-only password between 10 and 128 characters");
        Path parent=databasePath.toAbsolutePath().normalize().getParent();
        try(ApplicationLock ignored=ApplicationLock.acquire(parent);SocialDatabase db=new SocialDatabase(databasePath)) {
            if(db.read(c-> {
                try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM users")) {
                    return r.next()&&r.getLong(1)>0;
                }
            }
            ))throw new IllegalStateException("Demo seed requires an empty database; use a separate demo data directory");
            SocialService social=new SocialService(db);
            Map<String,Long> users=new LinkedHashMap<>();
            users.put("demo_admin",((Number)social.bootstrapAdmin("demo_admin","Demo Administrator",password).get("id")).longValue());
            for(String name:List.of("alice","bob","carol","dave","eve")) {
                String username="demo_"+name;
                users.put(username,((Number)social.register(Map.of("username",username,"displayName","Demo "+Character.toUpperCase(name.charAt(0))+name.substring(1),"password",password)).get("userId")).longValue());
            }
            long alice=users.get("demo_alice"),bob=users.get("demo_bob"),carol=users.get("demo_carol"),dave=users.get("demo_dave"),eve=users.get("demo_eve");
            friend(social,alice,bob);
            friend(social,alice,carol);
            friend(social,bob,carol);
            command(social,alice,"FRIEND_REQUEST",Map.of("targetUserId",dave));
            command(social,eve,"FRIEND_REQUEST",Map.of("targetUserId",bob));
            command(social,dave,"BLOCK_SET",Map.of("targetUserId",eve,"blocked",true));
            byte[] image=image();
            String postImage,file,avatar;
            long postId,conversation,group;
            try(MediaService media=new MediaService(db,social,storageRoot,200L*1024*1024,1024L*1024*1024)) {
                avatar=upload(media,alice,image,"AVATAR","demo-avatar.png","image/png");
                command(social,alice,"AVATAR_SET",Map.of("mediaId",avatar,"expectedVersion",1));
                postImage=upload(media,alice,image,"POST_IMAGE","demo-campus.png","image/png");
                Map<String,Object> post=command(social,alice,"POST_CREATE",Map.of("content","Welcome to the local VKU Social demonstration. These accounts and images are fictional.","visibility","PUBLIC","mediaIds",List.of(postImage)));
                postId=((Number)post.get("postId")).longValue();
                command(social,bob,"COMMENT_CREATE",Map.of("postId",postId,"content","The sample file is available in our direct conversation."));
                command(social,carol,"LIKE_SET",Map.of("postId",postId,"liked",true));
                command(social,bob,"POST_CREATE",Map.of("content","A friends-only planning post for the networking assignment.","visibility","FRIENDS","mediaIds",List.of()));
                command(social,carol,"POST_CREATE",Map.of("content","A private draft that other demo users cannot see.","visibility","ONLY_ME","mediaIds",List.of()));
                command(social,eve,"POST_CREATE",Map.of("content","A public demo account outside Alice's friend list.","visibility","PUBLIC","mediaIds",List.of()));
                conversation=((Number)command(social,alice,"CONVERSATION_DIRECT_GET_OR_CREATE",Map.of("targetUserId",bob)).get("conversationId")).longValue();
                command(social,alice,"MESSAGE_SEND",Map.of("conversationId",conversation,"clientMessageId",UUID.randomUUID().toString(),"content","Offline sample message: it remains available after server restart."));
                file=upload(media,bob,"VKU Social demo file\nThis is fictional sample content for upload/download verification.\n".getBytes(StandardCharsets.UTF_8),"CHAT_FILE","demo-notes.txt","text/plain");
                command(social,bob,"MESSAGE_SEND",Map.of("conversationId",conversation,"clientMessageId",UUID.randomUUID().toString(),"content","Sample notes attachment","mediaId",file));
                group=((Number)command(social,alice,"GROUP_CREATE",Map.of("title","Networking Demo Team")).get("conversationId")).longValue();
                for(long member:List.of(bob,carol)) {
                    Map<String,Object> invite=command(social,alice,"GROUP_INVITE",Map.of("conversationId",group,"targetUserId",member));
                    command(social,member,"GROUP_INVITE_ACCEPT",Map.of("inviteId",invite.get("inviteId")));
                }
                command(social,carol,"MESSAGE_SEND",Map.of("conversationId",group,"clientMessageId",UUID.randomUUID().toString(),"content","Welcome to the fictional demonstration group."));
            }
            return Map.of("usernames",List.copyOf(users.keySet()),"userIds",Map.copyOf(users),"postId",postId,"conversationId",conversation,"groupId",group,"postImageMediaId",postImage,"chatFileMediaId",file,"avatarMediaId",avatar);
        }
    }
    private static Map<String,Object> command(SocialService s,long actor,String type,Map<String,Object> payload)throws Exception {
        return s.execute(actor,type,payload,UUID.randomUUID().toString());
    }
    private static void friend(SocialService s,long a,long b)throws Exception {
        Map<String,Object> request=command(s,a,"FRIEND_REQUEST",Map.of("targetUserId",b));
        command(s,b,"FRIEND_ACCEPT",Map.of("relationId",request.get("relationId"),"expectedVersion",request.get("version")));
    }
    private static String upload(MediaService m,long actor,byte[] data,String purpose,String name,String mime)throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        Map<String,Object> t=m.execute(actor,"MEDIA_UPLOAD_INIT",Map.of("purpose",purpose,"fileName",name,"size",data.length,"mimeType",mime,"sha256",hash));
        for(int offset=0;offset<data.length;offset+=MediaService.CHUNK_BYTES)m.execute(actor,"MEDIA_UPLOAD_CHUNK",Map.of("transferId",t.get("transferId"),"offset",offset,"data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(data,offset,Math.min(offset+MediaService.CHUNK_BYTES,data.length)))));
        m.execute(actor,"MEDIA_UPLOAD_COMMIT",Map.of("transferId",t.get("transferId")));
        return (String)t.get("mediaId");
    }
    private static byte[] image()throws IOException {
        BufferedImage image=new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<180;y++)for(int x=0;x<320;x++) {
            int r=45+x*100/320,g=90+y*90/180,b=160;
            image.setRGB(x,y,(r<<16)|(g<<8)|b);
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"png",out))throw new IOException("PNG encoder unavailable");
        return out.toByteArray();
    }
}
