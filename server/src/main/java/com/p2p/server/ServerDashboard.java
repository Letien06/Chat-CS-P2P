package com.p2p.server;

import com.formdev.flatlaf.FlatDarkLaf;
import com.p2p.common.protocol.MessageType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

final class ServerDashboard implements ServerObserver {
    private static final Color BACKGROUND = new Color(24, 24, 28);
    private static final Color SURFACE = new Color(34, 34, 39);
    private static final Color TEXT = new Color(244, 244, 247);
    private static final Color MUTED = new Color(154, 154, 166);
    private static final Color GREEN = new Color(34, 197, 94);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame frame = new JFrame("MiniChat Server");
    private final JLabel status = label("Đang khởi động...", 14, MUTED);
    private final JLabel port = label("-", 24, TEXT);
    private final JLabel clients = label("0", 24, TEXT);
    private final JLabel database = label("-", 12, MUTED);
    private final javax.swing.DefaultListModel<String> clientModel = new javax.swing.DefaultListModel<>();
    private final JList<String> clientList = new JList<>(clientModel);
    private final JTextArea log = new JTextArea();
    private final Map<String, String> connectedClients = new LinkedHashMap<>();
    private Runnable stopAction = () -> { };

    private ServerDashboard() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(880, 580));
        frame.setSize(1000, 660);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildUi());
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                stopAction.run();
                frame.dispose();
            }
        });
    }

    static ServerDashboard create() {
        FlatDarkLaf.setup();
        return new ServerDashboard();
    }

    void setStopAction(Runnable action) { stopAction = action == null ? () -> { } : action; }

    void showWindow() { SwingUtilities.invokeLater(() -> frame.setVisible(true)); }

    private JPanel buildUi() {
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = label("MiniChat Server", 26, TEXT);
        JLabel subtitle = label("Bảng điều khiển server · Client-Server", 13, MUTED);
        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new javax.swing.BoxLayout(titleBox, javax.swing.BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(javax.swing.Box.createVerticalStrut(4));
        titleBox.add(subtitle);
        header.add(titleBox, BorderLayout.WEST);
        JButton stop = new JButton("Dừng server");
        stop.setForeground(Color.WHITE);
        stop.setBackground(new Color(185, 28, 28));
        stop.setFocusPainted(false);
        stop.setMargin(new Insets(9, 16, 9, 16));
        stop.addActionListener(event -> { stopAction.run(); frame.dispose(); });
        header.add(stop, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel metrics = new JPanel(new GridLayout(1, 3, 10, 0));
        metrics.setOpaque(false);
        metrics.add(metric("TCP PORT", port));
        metrics.add(metric("CLIENT ONLINE", clients));
        JPanel dbCard = metric("DATABASE", database);
        metrics.add(dbCard);

        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setBackground(SURFACE);
        clientList.setForeground(TEXT);
        clientList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel clientsPanel = panel("Client đang kết nối", new JScrollPane(clientList));

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setBackground(new Color(18, 18, 22));
        log.setForeground(new Color(210, 210, 218));
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel logPanel = panel("Server log", new JScrollPane(log));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, clientsPanel, logPanel);
        split.setResizeWeight(0.34);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(metrics, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        return root;
    }

    private JPanel metric(String name, JLabel value) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createEmptyBorder(13, 15, 13, 15));
        card.add(label(name, 11, MUTED), BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JPanel panel(String title, java.awt.Component content) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(58, 58, 66)),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        panel.add(label(title, 13, TEXT), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    @Override public void serverStarted(int serverPort, String databasePath, int maxClients) {
        onEdt(() -> {
            port.setText(String.valueOf(serverPort));
            database.setText(databasePath);
            status.setText("Đang chạy · tối đa " + maxClients + " client");
            status.setForeground(GREEN);
            append("Server started on TCP port " + serverPort);
            append("Database: " + databasePath);
        });
    }

    @Override public void clientConnected(String remoteAddress) {
        onEdt(() -> {
            connectedClients.put(remoteAddress, "Đang kết nối");
            refreshClients();
            append("Client connected: " + remoteAddress);
        });
    }

    @Override public void clientJoined(String remoteAddress, String username) {
        onEdt(() -> {
            connectedClients.put(remoteAddress, username);
            refreshClients();
            append("Client joined: " + username + " (" + remoteAddress + ")");
        });
    }

    @Override public void requestReceived(String username, MessageType type) {
        onEdt(() -> append(username + " → " + type));
    }

    @Override public void clientDisconnected(String remoteAddress, String username) {
        onEdt(() -> {
            connectedClients.remove(remoteAddress);
            refreshClients();
            append("Client disconnected: " + (username == null ? remoteAddress : username + " (" + remoteAddress + ")"));
        });
    }

    @Override public void serverStopped() {
        onEdt(() -> {
            status.setText("Đã dừng");
            status.setForeground(new Color(248, 113, 113));
            append("Server stopped");
        });
    }

    @Override public void serverError(String message) { onEdt(() -> append("ERROR: " + message)); }

    private void refreshClients() {
        clientModel.clear();
        connectedClients.forEach((address, username) -> clientModel.addElement(username + "  ·  " + address));
        clients.setText(String.valueOf(connectedClients.size()));
    }

    private void append(String message) {
        log.append("[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }
}
