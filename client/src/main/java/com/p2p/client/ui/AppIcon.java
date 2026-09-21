package com.p2p.client.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Small vector icons so controls look consistent on every operating system. */
public final class AppIcon implements Icon {
    public enum Type { SEND, ATTACHMENT, DOWNLOAD, OPEN_FILE, REFRESH, FILE, CHAT, FOLDER, SETTINGS, LOGOUT }

    private final Type type;
    private final Color color;
    private final int size;

    public AppIcon(Type type, Color color, int size) {
        this.type = type;
        this.color = color;
        this.size = size;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(1.7f, size / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double scale = size / 24d;
        g.scale(scale, scale);
        switch (type) {
            case SEND -> paintSend(g);
            case ATTACHMENT -> paintAttachment(g);
            case DOWNLOAD -> paintDownload(g);
            case OPEN_FILE -> paintOpen(g);
            case REFRESH -> paintRefresh(g);
            case FILE -> paintFile(g);
            case CHAT -> paintChat(g);
            case FOLDER -> paintFolder(g);
            case SETTINGS -> paintSettings(g);
            case LOGOUT -> paintLogout(g);
        }
        g.dispose();
    }

    private static void paintSend(Graphics2D g) {
        Path2D path = new Path2D.Double();
        path.moveTo(3, 11.2); path.lineTo(20.5, 3.5); path.lineTo(14.1, 20.5);
        path.lineTo(10.6, 13.5); path.closePath();
        g.draw(path);
        line(g, 10.6, 13.5, 20.5, 3.5);
    }

    private static void paintAttachment(Graphics2D g) {
        Path2D path = new Path2D.Double();
        path.moveTo(8.2, 12.7); path.lineTo(14.4, 6.5);
        path.curveTo(16.2, 4.7, 19.1, 4.7, 20.9, 6.5);
        path.curveTo(22.7, 8.3, 22.7, 11.2, 20.9, 13);
        path.lineTo(11.5, 22.4);
        path.curveTo(8.8, 25.1, 4.5, 25.1, 1.8, 22.4);
        path.curveTo(-0.9, 19.7, -0.9, 15.4, 1.8, 12.7);
        path.lineTo(11.2, 3.3);
        g.draw(path);
    }

    private static void paintDownload(Graphics2D g) {
        line(g, 12, 3, 12, 15);
        line(g, 7.5, 10.7, 12, 15.2);
        line(g, 16.5, 10.7, 12, 15.2);
        line(g, 5, 20, 19, 20);
    }

    private static void paintOpen(Graphics2D g) {
        Path2D folder = new Path2D.Double();
        folder.moveTo(3, 7); folder.lineTo(9, 7); folder.lineTo(11, 9); folder.lineTo(21, 9);
        folder.lineTo(19, 20); folder.lineTo(3, 20); folder.closePath();
        g.draw(folder);
        line(g, 13, 15, 21, 7);
        line(g, 16.5, 7, 21, 7);
        line(g, 21, 7, 21, 11.5);
    }

    private static void paintRefresh(Graphics2D g) {
        g.draw(new Arc2D.Double(4, 4, 16, 16, 35, 285, Arc2D.OPEN));
        line(g, 16.5, 4.5, 20.5, 4.5);
        line(g, 20.5, 4.5, 20.5, 8.5);
    }

    private static void paintFile(Graphics2D g) {
        g.draw(new RoundRectangle2D.Double(5, 2.5, 14, 19, 2, 2));
        line(g, 14, 3, 14, 8);
        line(g, 14, 8, 19, 8);
        line(g, 8.5, 13, 15.5, 13);
        line(g, 8.5, 17, 14, 17);
    }

    private static void paintChat(Graphics2D g) {
        Path2D bubble = new Path2D.Double();
        bubble.moveTo(4, 5); bubble.lineTo(20, 5); bubble.lineTo(20, 16);
        bubble.lineTo(14, 16); bubble.lineTo(9, 21); bubble.lineTo(9, 16); bubble.lineTo(4, 16); bubble.closePath();
        g.draw(bubble);
    }

    private static void paintFolder(Graphics2D g) {
        Path2D folder = new Path2D.Double();
        folder.moveTo(3, 7); folder.lineTo(9, 7); folder.lineTo(11, 9); folder.lineTo(21, 9);
        folder.lineTo(20, 19); folder.lineTo(4, 19); folder.closePath();
        g.draw(folder);
    }

    private static void paintSettings(Graphics2D g) {
        g.draw(new java.awt.geom.Ellipse2D.Double(8, 8, 8, 8));
        g.draw(new java.awt.geom.Ellipse2D.Double(3, 10.5, 3, 3));
        g.draw(new java.awt.geom.Ellipse2D.Double(18, 10.5, 3, 3));
        line(g, 6, 12, 8, 12); line(g, 16, 12, 18, 12);
        line(g, 12, 6, 12, 8); line(g, 12, 16, 12, 18);
    }

    private static void paintLogout(Graphics2D g) {
        line(g, 4, 12, 16, 12); line(g, 12, 8, 16, 12); line(g, 12, 16, 16, 12);
        line(g, 5, 5, 5, 19); line(g, 5, 5, 11, 5);
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }
}
