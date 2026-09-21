package com.p2p.client.ui;

import com.p2p.client.network.ChatClient;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public final class LoginFrame extends JFrame {
    private static final int SERVER_PORT = 5000;

    private final JTextField hostField = new JTextField("127.0.0.1", 18);
    private final JTextField nameField = new JTextField(18);
    private final JButton joinButton = Theme.primaryButton("Vào phòng chat");
    private ChatClient client;

    public LoginFrame() {
        super("MiniChat - Kết nối");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(920, 620));
        setSize(980, 660);
        setLocationRelativeTo(null);
        buildUi();
    }

    private void buildUi() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(Theme.APP_BACKGROUND);
        root.setBorder(Theme.padding(28, 28, 28, 28));

        GridBagConstraints cardConstraints = new GridBagConstraints();
        cardConstraints.gridx = 0;
        cardConstraints.gridy = 0;
        cardConstraints.weightx = 1;
        cardConstraints.weighty = 1;
        cardConstraints.fill = GridBagConstraints.BOTH;
        cardConstraints.anchor = GridBagConstraints.CENTER;
        JPanel card = Theme.roundedPanel(Theme.SURFACE, 26);
        card.setLayout(new GridLayout(1, 2, 0, 0));
        card.add(buildBrandPanel());
        card.add(buildFormPanel());
        root.add(card, cardConstraints);
        setContentPane(root);
        getRootPane().setDefaultButton(joinButton);
        joinButton.addActionListener(e -> joinChat());
    }

    private JPanel buildBrandPanel() {
        JPanel panel = Theme.roundedPanel(Theme.PRIMARY, 24);
        panel.setLayout(new GridBagLayout());
        panel.setBorder(Theme.padding(48, 42, 48, 42));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel mark = new JLabel("M");
        mark.setOpaque(true); mark.setBackground(Color.WHITE); mark.setForeground(Theme.PRIMARY);
        mark.setFont(Theme.font(Font.BOLD, 34)); mark.setHorizontalAlignment(SwingConstants.CENTER);
        mark.setPreferredSize(new Dimension(68, 68));
        mark.putClientProperty("FlatLaf.style", "arc:20");
        c.gridy = 0; panel.add(mark, c);
        JLabel title = new JLabel("MiniChat");
        title.setForeground(Color.WHITE); title.setFont(Theme.font(Font.BOLD, 34));
        c.gridy = 1; c.insets = new Insets(24, 0, 0, 0); panel.add(title, c);
        JLabel subtitle = new JLabel("Nói chuyện nhẹ nhàng.\nChia sẻ dễ dàng.");
        subtitle.setText("<html>Nói chuyện nhẹ nhàng.<br>Chia sẻ dễ dàng.</html>");
        subtitle.setForeground(new Color(219, 234, 254)); subtitle.setFont(Theme.font(Font.PLAIN, 18));
        c.gridy = 2; c.insets = new Insets(12, 0, 0, 0); panel.add(subtitle, c);
        JLabel note = new JLabel("Chat riêng tư trong mạng LAN");
        note.setForeground(new Color(191, 219, 254)); note.setFont(Theme.font(Font.PLAIN, 13));
        c.gridy = 3; c.insets = new Insets(32, 0, 0, 0); panel.add(note, c);
        c.gridy = 4; c.weighty = 1; panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(Theme.padding(42, 48, 42, 48));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JLabel welcome = new JLabel("Bắt đầu trò chuyện");
        welcome.setFont(Theme.font(Font.BOLD, 26)); welcome.setForeground(Theme.TEXT);
        c.gridy = 0; panel.add(welcome, c);
        JLabel hint = new JLabel("Nhập tên và IP của máy chạy server");
        hint.setFont(Theme.font(Font.PLAIN, 14)); hint.setForeground(Theme.MUTED);
        c.gridy = 1; c.insets = new Insets(8, 0, 24, 0); panel.add(hint, c);
        addField(panel, c, 2, "Tên hiển thị", nameField, "Nhập tên của bạn");
        addField(panel, c, 4, "IP Server", hostField, "127.0.0.1");
        c.gridy = 6; c.insets = new Insets(24, 0, 0, 0); panel.add(joinButton, c);
        JLabel tip = new JLabel("Cùng máy dùng 127.0.0.1 · Cổng mặc định 5000");
        tip.setFont(Theme.font(Font.PLAIN, 12)); tip.setForeground(Theme.MUTED);
        tip.setHorizontalAlignment(SwingConstants.CENTER);
        c.gridy = 7; c.insets = new Insets(22, 0, 0, 0); panel.add(tip, c);
        return panel;
    }

    private static void addField(JPanel panel, GridBagConstraints c, int row, String label, JComponent field, String placeholder) {
        JLabel title = new JLabel(label);
        title.setFont(Theme.font(Font.BOLD, 13)); title.setForeground(Theme.TEXT);
        c.gridy = row; c.insets = new Insets(row == 2 ? 0 : 14, 0, 6, 0); panel.add(title, c);
        field.setFont(Theme.font(Font.PLAIN, 14));
        field.setPreferredSize(new Dimension(250, 42));
        field.setToolTipText(placeholder);
        c.gridy = row + 1; c.insets = new Insets(0, 0, 0, 0); panel.add(field, c);
    }

    private void joinChat() {
        String host = hostField.getText().trim();
        String name = nameField.getText().trim();
        if (host.isBlank() || name.isBlank()) { showError("Vui lòng nhập tên và IP server."); return; }
        setJoinEnabled(false);
        client = new ChatClient(message -> SwingUtilities.invokeLater(() -> onMessage(message)));
        ChatClient connectingClient = client;
        Thread connectThread = new Thread(() -> {
            try {
                connectingClient.connect(host, SERVER_PORT);
                connectingClient.send(Message.of(MessageType.JOIN_REQUEST).put("name", name));
            } catch (IOException e) {
                connectingClient.close();
                SwingUtilities.invokeLater(() -> { setJoinEnabled(true); showError("Không kết nối được server: " + e.getMessage()); });
            }
        }, "chat-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void onMessage(Message message) {
        if (message.getType() == MessageType.ERROR) { setJoinEnabled(true); showError(message.string("message")); if (client != null) client.close(); return; }
        if (message.getType() != MessageType.JOIN_RESPONSE) return;
        if (!message.bool("success", false)) { setJoinEnabled(true); showError(message.string("message")); if (client != null) client.close(); return; }
        ChatFrame frame = new ChatFrame(message.string("name"), hostField.getText().trim() + ":" + SERVER_PORT, client);
        client.setMessageListener(m -> SwingUtilities.invokeLater(() -> frame.handle(m)));
        frame.requestUsers(); frame.setVisible(true); dispose();
    }

    private void setJoinEnabled(boolean enabled) { joinButton.setEnabled(enabled); }
    private void showError(String text) { JOptionPane.showMessageDialog(this, text == null ? "Có lỗi xảy ra." : text, "Lỗi", JOptionPane.ERROR_MESSAGE); }
}
