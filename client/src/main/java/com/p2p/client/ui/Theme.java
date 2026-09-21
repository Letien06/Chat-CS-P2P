package com.p2p.client.ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

public final class Theme {
    public static final Color PRIMARY = new Color(92, 102, 235);
    public static final Color PRIMARY_DARK = new Color(72, 82, 205);
    public static final Color PRIMARY_SOFT = new Color(55, 58, 93);
    public static final Color APP_BACKGROUND = new Color(24, 24, 28);
    public static final Color SURFACE = new Color(34, 34, 39);
    public static final Color NAV_BACKGROUND = new Color(28, 28, 33);
    public static final Color CHAT_BACKGROUND = new Color(39, 39, 44);
    public static final Color COMPOSER_BACKGROUND = new Color(54, 54, 60);
    public static final Color FILE_CARD = new Color(48, 48, 54);
    public static final Color BORDER = new Color(58, 58, 66);
    public static final Color TEXT = new Color(244, 244, 247);
    public static final Color MUTED = new Color(154, 154, 166);
    public static final Color ONLINE = new Color(34, 197, 94);
    public static final Color DANGER = new Color(239, 68, 68);

    private Theme() { }

    public static Font font(int style, float size) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    public static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 14));
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setBorder(padding(10, 18, 10, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton ghostButton(String text) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 13));
        button.setForeground(PRIMARY);
        button.setBackground(PRIMARY_SOFT);
        button.setBorder(padding(9, 14, 9, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton iconButton(Icon icon, String tooltip, boolean primary) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(42, 42));
        button.setMinimumSize(new Dimension(42, 42));
        button.setFocusable(false);
        button.setForeground(primary ? Color.WHITE : PRIMARY);
        button.setBackground(primary ? PRIMARY : PRIMARY_SOFT);
        button.setBorder(padding(10, 10, 10, 10));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton navButton(Icon icon, String tooltip, boolean selected) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(46, 46));
        button.setMinimumSize(new Dimension(46, 46));
        button.setFocusable(false);
        button.setForeground(selected ? Color.WHITE : MUTED);
        button.setBackground(selected ? PRIMARY_SOFT : NAV_BACKGROUND);
        button.setBorder(padding(11, 11, 11, 11));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton actionButton(String text, Icon icon, boolean primary) {
        JButton button = primary ? primaryButton(text) : ghostButton(text);
        button.setIcon(icon);
        button.setIconTextGap(7);
        button.setFocusable(false);
        return button;
    }

    public static JPanel roundedPanel(Color background, int arc) {
        return new RoundedPanel(background, arc);
    }

    public static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final int arc;

        private RoundedPanel(Color fill, int arc) {
            this.fill = fill;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    public static final class Avatar extends JComponent {
        private String name;
        private final int size;
        private boolean online;

        public Avatar(String name, int size, boolean online) {
            this.name = name == null ? "?" : name;
            this.size = size;
            this.online = online;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
        }

        public void setOnline(boolean online) {
            this.online = online;
            repaint();
        }

        public void setName(String name) {
            this.name = name == null ? "?" : name;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(colorFor(name));
            g.fill(new Ellipse2D.Float(1, 1, size - 5, size - 5));
            g.setFont(font(Font.BOLD, size * 0.36f));
            g.setColor(Color.WHITE);
            String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
            FontMetrics metrics = g.getFontMetrics();
            int x = (size - 4 - metrics.stringWidth(initial)) / 2;
            int y = (size - 4 - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(initial, x, y);
            if (online) {
                int dot = Math.max(10, size / 4);
                int dx = size - dot - 2;
                int dy = size - dot - 2;
                g.setColor(Color.WHITE);
                g.fillOval(dx - 2, dy - 2, dot + 4, dot + 4);
                g.setColor(ONLINE);
                g.fillOval(dx, dy, dot, dot);
            }
            g.dispose();
        }

        private static Color colorFor(String value) {
            Color[] colors = {
                    new Color(59, 130, 246), new Color(139, 92, 246),
                    new Color(14, 165, 233), new Color(236, 72, 153),
                    new Color(20, 184, 166), new Color(249, 115, 22)
            };
            return colors[Math.floorMod(value.hashCode(), colors.length)];
        }
    }
}
