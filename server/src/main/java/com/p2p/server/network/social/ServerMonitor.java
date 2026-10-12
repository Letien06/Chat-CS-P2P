package com.p2p.server.network.social;

import com.p2p.server.social.SocialServer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Lightweight operator view: no business data queries run on the Swing EDT. */
public final class ServerMonitor {
    private ServerMonitor() { }
    public static void open(SocialServer server, SocialServer.Config config) {
        if(GraphicsEnvironment.isHeadless())return;
        SwingUtilities.invokeLater(()->{
            JFrame frame=new JFrame("Kết Nối · Server");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel panel=new JPanel(new BorderLayout(16,16));panel.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));
            JLabel title=new JLabel("Server mạng xã hội Java");title.setFont(title.getFont().deriveFont(Font.BOLD,22));panel.add(title,BorderLayout.NORTH);
            JTextArea info=new JTextArea();info.setEditable(false);info.setLineWrap(true);info.setWrapStyleWord(true);info.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));panel.add(info);
            JButton stop=new JButton("Dừng server an toàn");panel.add(stop,BorderLayout.SOUTH);
            Runnable shutdown=()->{stop.setEnabled(false);new Thread(()->{server.close();SwingUtilities.invokeLater(frame::dispose);},"operator-stop").start();};
            stop.addActionListener(e->shutdown.run());frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){shutdown.run();}});
            Timer timer=new Timer(1000,e->{
                info.setText("TCP/TLS: "+server.tcpPort()+"\nUDP tìm server: "+server.discoveryPort()+"\nUDP đang nhập: "+server.typingPort()+"\n\nKết nối: "+server.connectionCount()+" / "+config.maxConnections()+"\nTài khoản online: "+server.authenticatedCount()+"\n\nDatabase: "+config.databasePath().toAbsolutePath()+"\nMedia: "+config.storageRoot().toAbsolutePath()+"\n\nLog chi tiết ở terminal và thư mục logs.");
                if(server.isClosed()){((Timer)e.getSource()).stop();frame.dispose();}
            });timer.start();frame.setContentPane(panel);frame.setSize(630,420);frame.setLocationByPlatform(true);frame.setVisible(true);
        });
    }
}
