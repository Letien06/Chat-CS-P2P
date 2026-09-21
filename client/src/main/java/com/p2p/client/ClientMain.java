package com.p2p.client;

import com.p2p.client.ui.LoginFrame;
import com.p2p.client.ui.Theme;
import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class ClientMain {
    private ClientMain() { }
    public static void main(String[] args) {
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 18);
        UIManager.put("Button.arc", 999);
        UIManager.put("TextComponent.arc", 20);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.focusColor", Theme.PRIMARY);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("TextField.background", Theme.COMPOSER_BACKGROUND);
        UIManager.put("TextField.foreground", Theme.TEXT);
        UIManager.put("TextField.placeholderForeground", Theme.MUTED);
        UIManager.put("Panel.background", Theme.SURFACE);
        UIManager.put("ToolTip.background", Theme.FILE_CARD);
        UIManager.put("ToolTip.foreground", Theme.TEXT);
        SwingUtilities.invokeLater(() -> {
            new LoginFrame().setVisible(true);
        });
    }
}
