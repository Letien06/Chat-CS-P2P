package com.p2p.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Modern UI reusable helper components for client.
 */
public final class ModernUI {
    private ModernUI() { }

    public static JLabel badge(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(Theme.font(Font.BOLD, 11));
        l.setForeground(fg);
        l.setBackground(bg);
        l.setOpaque(false);
        l.setBorder(new EmptyBorder(3, 8, 3, 8));
        return l;
    }

    public static JPanel emptyState(String iconEmoji, String title, String subtitle) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));
        p.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel icon = new JLabel(iconEmoji);
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel t = new JLabel(title);
        t.setFont(Theme.font(Font.BOLD, 16));
        t.setForeground(Theme.TEXT);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel s = new JLabel(subtitle);
        s.setFont(Theme.font(Font.PLAIN, 13));
        s.setForeground(Theme.MUTED);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(icon);
        p.add(Box.createVerticalStrut(10));
        p.add(t);
        p.add(Box.createVerticalStrut(4));
        p.add(s);
        return p;
    }

    public static JPanel alertBox(String text, Color bg, Color border, Color fg) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(bg);
        p.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(border, 1),
                new EmptyBorder(10, 14, 10, 14)
        ));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(Font.PLAIN, 13));
        l.setForeground(fg);
        p.add(l, BorderLayout.CENTER);
        return p;
    }

    public static JLabel checklistItem(String text) {
        JLabel l = new JLabel("○  " + text);
        l.setFont(Theme.font(Font.PLAIN, 12));
        l.setForeground(Theme.MUTED);
        return l;
    }

    public static void updateChecklist(JLabel l, boolean valid, String text) {
        if (valid) {
            l.setText("✔  " + text);
            l.setForeground(Theme.SUCCESS);
        } else {
            l.setText("○  " + text);
            l.setForeground(Theme.MUTED);
        }
    }
}
