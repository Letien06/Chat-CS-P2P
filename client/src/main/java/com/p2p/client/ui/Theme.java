package com.p2p.client.ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

public final class Theme {
    // Core brand palette (Modern Indigo & Slate)
    public static final Color PRIMARY = new Color(92, 102, 235);
    public static final Color PRIMARY_DARK = new Color(72, 82, 205);
    public static final Color PRIMARY_HOVER = new Color(79, 70, 229);
    public static final Color PRIMARY_LIGHT = new Color(129, 140, 248);
    public static final Color PRIMARY_SOFT = new Color(49, 46, 129);

    // Background & Surfaces
    public static final Color APP_BACKGROUND = new Color(15, 23, 42); // Slate 900
    public static final Color SURFACE = new Color(30, 41, 59);        // Slate 800
    public static final Color NAV_BACKGROUND = new Color(11, 15, 25);   // Slate 950
    public static final Color CHAT_BACKGROUND = new Color(22, 27, 38);
    public static final Color COMPOSER_BACKGROUND = new Color(30, 41, 59);
    public static final Color CARD_BACKGROUND = new Color(30, 41, 59);
    public static final Color CARD_BORDER = new Color(51, 65, 85);     // Slate 700
    public static final Color FILE_CARD = new Color(39, 45, 60);
    public static final Color BORDER = new Color(51, 65, 85);

    // Typography
    public static final Color TEXT = new Color(248, 250, 252);         // Slate 50
    public static final Color TEXT_SECONDARY = new Color(203, 213, 225);// Slate 300
    public static final Color MUTED = new Color(148, 163, 184);        // Slate 400

    // Semantic status
    public static final Color ONLINE = new Color(16, 185, 129);       // Emerald 500
    public static final Color SUCCESS = new Color(16, 185, 129);
    public static final Color DANGER = new Color(239, 68, 68);         // Rose 500
    public static final Color DANGER_SOFT = new Color(69, 26, 26);
    public static final Color WARNING = new Color(245, 158, 11);       // Amber 500
    public static final Color INFO = new Color(56, 189, 248);          // Sky 400

    // Chat bubbles
    public static final Color BUBBLE_OWN = new Color(79, 70, 229);     // Indigo 600
    public static final Color BUBBLE_OTHER = new Color(39, 45, 60);

    private Theme() { }

    public static Font font(int style, float size) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    public static Border cardBorder() {
        return new CompoundBorder(new LineBorder(CARD_BORDER, 1), new EmptyBorder(14, 16, 14, 16));
    }

    public static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 13));
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setBorder(padding(9, 18, 9, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton ghostButton(String text) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 13));
        button.setForeground(TEXT);
        button.setBackground(SURFACE);
        button.setBorder(new CompoundBorder(new LineBorder(BORDER, 1), padding(8, 14, 8, 14)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton dangerButton(String text) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 13));
        button.setForeground(Color.WHITE);
        button.setBackground(DANGER);
        button.setBorder(padding(9, 16, 9, 16));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton iconButton(Icon icon, String tooltip, boolean primary) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.setMinimumSize(new Dimension(40, 40));
        button.setFocusable(false);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setBackground(primary ? PRIMARY : SURFACE);
        button.setBorder(padding(8, 8, 8, 8));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton navButton(Icon icon, String tooltip, boolean selected) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(44, 44));
        button.setMinimumSize(new Dimension(44, 44));
        button.setFocusable(false);
        button.setForeground(selected ? Color.WHITE : MUTED);
        button.setBackground(selected ? PRIMARY : NAV_BACKGROUND);
        button.setBorder(padding(10, 10, 10, 10));
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

    public static JLabel badge(String text, Color bg, Color fg) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.BOLD, 11));
        label.setForeground(fg);
        label.setBackground(bg);
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(3, 8, 3, 8));
        return label;
    }

    public static JPanel roundedPanel(Color background, int arc) {
        return new RoundedPanel(background, arc);
    }

    public static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final int arc;

        public RoundedPanel(Color fill, int arc) {
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
            g.fill(new Ellipse2D.Float(1, 1, size - 4, size - 4));
            g.setFont(font(Font.BOLD, size * 0.38f));
            g.setColor(Color.WHITE);
            String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
            FontMetrics metrics = g.getFontMetrics();
            int x = (size - 4 - metrics.stringWidth(initial)) / 2 + 1;
            int y = (size - 4 - metrics.getHeight()) / 2 + metrics.getAscent() + 1;
            g.drawString(initial, x, y);
            if (online) {
                int dot = Math.max(9, size / 4);
                int dx = size - dot - 2;
                int dy = size - dot - 2;
                g.setColor(new Color(15, 23, 42)); // Border matching dark bg
                g.fillOval(dx - 2, dy - 2, dot + 4, dot + 4);
                g.setColor(ONLINE);
                g.fillOval(dx, dy, dot, dot);
            }
            g.dispose();
        }

        private static Color colorFor(String value) {
            Color[] colors = {
                    new Color(79, 70, 229),  // Indigo
                    new Color(147, 51, 234), // Purple
                    new Color(14, 165, 233), // Sky
                    new Color(236, 72, 153), // Pink
                    new Color(16, 185, 129), // Emerald
                    new Color(249, 115, 22)  // Orange
            };
            return colors[Math.floorMod(value.hashCode(), colors.length)];
        }
    }
}
