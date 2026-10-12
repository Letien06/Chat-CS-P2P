package com.p2p.server.network.social;

import com.formdev.flatlaf.FlatDarkLaf;
import com.p2p.server.social.SocialServer;
import com.p2p.server.social.SocialServer.ClientSnapshot;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Modern Server Dashboard for Java Social Network.
 * Pure presentation layer: no business DB queries, network IO, or hashing on Swing EDT.
 */
public final class ServerMonitor {
    private static final Color PRIMARY = new Color(99, 102, 241); // Indigo
    private static final Color PRIMARY_SOFT = new Color(49, 46, 129);
    private static final Color BG_DARK = new Color(15, 23, 42); // Slate 900
    private static final Color CARD_BG = new Color(30, 41, 59); // Slate 800
    private static final Color CARD_BORDER = new Color(51, 65, 85); // Slate 700
    private static final Color TEXT_MAIN = new Color(248, 250, 252);
    private static final Color TEXT_MUTED = new Color(148, 163, 184);
    private static final Color STATUS_GREEN = new Color(16, 185, 129);
    private static final Color STATUS_RED = new Color(239, 68, 68);
    private static final Color STATUS_YELLOW = new Color(245, 158, 11);
    private static final Color STATUS_BLUE = new Color(56, 189, 248);

    private ServerMonitor() { }

    public static void open(SocialServer server, SocialServer.Config config) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (!(UIManager.getLookAndFeel() instanceof FlatDarkLaf)) {
                    FlatDarkLaf.setup();
                }
            } catch (Exception ignored) {}

            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("Table.arc", 8);

            JFrame frame = new JFrame("VKU Social Server · Dashboard V2");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setSize(1120, 750);
            frame.setMinimumSize(new Dimension(920, 620));
            frame.setLocationRelativeTo(null);

            JPanel root = new JPanel(new BorderLayout(0, 14));
            root.setBackground(BG_DARK);
            root.setBorder(new EmptyBorder(16, 20, 16, 20));

            // ================= HEADER =================
            JPanel header = new JPanel(new BorderLayout(16, 10));
            header.setOpaque(false);

            JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            titleBox.setOpaque(false);
            JLabel logo = new JLabel("🛡️");
            logo.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
            titleBox.add(logo);

            JPanel titleTexts = new JPanel();
            titleTexts.setLayout(new BoxLayout(titleTexts, BoxLayout.Y_AXIS));
            titleTexts.setOpaque(false);
            JLabel appTitle = new JLabel("VKU SOCIAL NETWORK · SERVER DASHBOARD");
            appTitle.setFont(new Font("Segoe UI", Font.BOLD, 19));
            appTitle.setForeground(TEXT_MAIN);
            JLabel appSubtitle = new JLabel("Giám sát thời gian thực kiến trúc Client–Server TCP Raw Sockets (Cổng 5000)");
            appSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            appSubtitle.setForeground(TEXT_MUTED);
            titleTexts.add(appTitle);
            titleTexts.add(Box.createVerticalStrut(2));
            titleTexts.add(appSubtitle);
            titleBox.add(titleTexts);

            JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            headerActions.setOpaque(false);

            JLabel serverStatusBadge = createBadge("● ĐANG CHẠY", STATUS_GREEN, Color.WHITE);
            JLabel portBadge = createBadge("TCP: " + server.tcpPort(), PRIMARY_SOFT, Color.WHITE);

            JButton btnRefresh = new JButton("Làm mới 🔄");
            btnRefresh.setFont(new Font("Segoe UI", Font.BOLD, 12));
            btnRefresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JButton btnStop = new JButton("Dừng Server ⏹");
            btnStop.setFont(new Font("Segoe UI", Font.BOLD, 12));
            btnStop.setBackground(STATUS_RED);
            btnStop.setForeground(Color.WHITE);
            btnStop.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            headerActions.add(serverStatusBadge);
            headerActions.add(portBadge);
            headerActions.add(btnRefresh);
            headerActions.add(btnStop);

            header.add(titleBox, BorderLayout.WEST);
            header.add(headerActions, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            // ================= METRIC STAT CARDS =================
            JPanel metricsGrid = new JPanel(new GridLayout(1, 4, 14, 0));
            metricsGrid.setOpaque(false);

            StatCard cardConn = new StatCard("🌐 KẾT NỐI TCP HIỆN TẠI", "0 / " + config.maxConnections(), "Tải kết nối TCP Socket");
            StatCard cardUsers = new StatCard("👥 TÀI KHOẢN ONLINE", "0", "Người dùng đã xác thực");
            StatCard cardUptime = new StatCard("⏱️ THỜI GIAN HOẠT ĐỘNG", "00:00:00", "Bắt đầu từ lúc khởi động");
            StatCard cardConfig = new StatCard("⚙️ GIAO THỨC & CẤU HÌNH", "TCP: " + server.tcpPort(), "UDP: " + (config.udpEnabled() ? "Bật" : "Tắt (Mặc định)"));

            metricsGrid.add(cardConn);
            metricsGrid.add(cardUsers);
            metricsGrid.add(cardUptime);
            metricsGrid.add(cardConfig);

            // ================= MAIN TABS =================
            JTabbedPane tabs = new JTabbedPane();
            tabs.setFont(new Font("Segoe UI", Font.BOLD, 13));

            // --- TAB 1: CONNECTED CLIENTS ---
            JPanel tabClients = new JPanel(new BorderLayout(0, 10));
            tabClients.setOpaque(false);
            tabClients.setBorder(new EmptyBorder(12, 12, 12, 12));

            JPanel clientsToolbar = new JPanel(new BorderLayout(10, 0));
            clientsToolbar.setOpaque(false);
            JLabel lblClientsTitle = new JLabel("Danh sách các phiên kết nối Client qua TCP");
            lblClientsTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
            lblClientsTitle.setForeground(TEXT_MAIN);

            JPanel filterBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            filterBox.setOpaque(false);
            filterBox.add(new JLabel("Tìm kiếm: "));
            JTextField txtSearchClient = new JTextField(18);
            txtSearchClient.putClientProperty("JTextField.placeholderText", "IP, username hoặc ID...");
            filterBox.add(txtSearchClient);

            clientsToolbar.add(lblClientsTitle, BorderLayout.WEST);
            clientsToolbar.add(filterBox, BorderLayout.EAST);
            tabClients.add(clientsToolbar, BorderLayout.NORTH);

            String[] clientCols = {"STT", "Mã kết nối (ID)", "Địa chỉ IP Client", "User ID", "Tên đăng nhập", "Xác thực", "Thời gian kết nối", "Trạng thái"};
            DefaultTableModel clientModel = new DefaultTableModel(clientCols, 0) {
                @Override public boolean isCellEditable(int row, int col) { return false; }
            };
            JTable clientTable = new JTable(clientModel);
            clientTable.setRowHeight(32);
            clientTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            clientTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
            clientTable.getTableHeader().setReorderingAllowed(false);
            TableRowSorter<DefaultTableModel> clientSorter = new TableRowSorter<>(clientModel);
            clientTable.setRowSorter(clientSorter);

            txtSearchClient.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                private void filter() {
                    String query = txtSearchClient.getText().trim();
                    if (query.isEmpty()) clientSorter.setRowFilter(null);
                    else clientSorter.setRowFilter(RowFilter.regexFilter("(?i)" + query));
                }
            });

            // Center alignment for some columns
            DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
            centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
            clientTable.getColumnModel().getColumn(0).setPreferredWidth(50);
            clientTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
            clientTable.getColumnModel().getColumn(3).setPreferredWidth(70);
            clientTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
            clientTable.getColumnModel().getColumn(5).setPreferredWidth(110);
            clientTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object val, boolean isSel, boolean hasFocus, int r, int c) {
                    JLabel l = (JLabel) super.getTableCellRendererComponent(table, val, isSel, hasFocus, r, c);
                    l.setHorizontalAlignment(SwingConstants.CENTER);
                    if ("Authenticated".equals(val)) {
                        l.setForeground(STATUS_GREEN);
                        l.setFont(l.getFont().deriveFont(Font.BOLD));
                    } else {
                        l.setForeground(STATUS_YELLOW);
                    }
                    return l;
                }
            });

            JScrollPane clientScroll = new JScrollPane(clientTable);
            clientScroll.setBorder(new LineBorder(CARD_BORDER, 1));
            tabClients.add(clientScroll, BorderLayout.CENTER);

            JLabel lblClientSummary = new JLabel("Tổng số phiên: 0 · Cập nhật tự động mỗi giây");
            lblClientSummary.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            lblClientSummary.setForeground(TEXT_MUTED);
            tabClients.add(lblClientSummary, BorderLayout.SOUTH);

            tabs.addTab("👥 Client Đang Kết Nối", tabClients);

            // --- TAB 2: REAL-TIME LOG VIEWER ---
            JPanel tabLogs = new JPanel(new BorderLayout(0, 10));
            tabLogs.setOpaque(false);
            tabLogs.setBorder(new EmptyBorder(12, 12, 12, 12));

            JPanel logToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            logToolbar.setOpaque(false);

            JLabel lblLogLevel = new JLabel("Lọc cấp độ:");
            JComboBox<String> cmbLogLevel = new JComboBox<>(new String[]{"TẤT CẢ (ALL)", "INFO", "WARNING", "SEVERE / ERROR"});
            JTextField txtSearchLog = new JTextField(15);
            txtSearchLog.putClientProperty("JTextField.placeholderText", "Tìm từ khóa log...");
            JCheckBox chkAutoScroll = new JCheckBox("Tự động cuộn", true);
            chkAutoScroll.setOpaque(false);
            JButton btnClearLog = new JButton("Xóa nhật ký");

            logToolbar.add(lblLogLevel);
            logToolbar.add(cmbLogLevel);
            logToolbar.add(new JLabel("Tìm kiếm:"));
            logToolbar.add(txtSearchLog);
            logToolbar.add(chkAutoScroll);
            logToolbar.add(btnClearLog);
            tabLogs.add(logToolbar, BorderLayout.NORTH);

            JTextPane logPane = new JTextPane();
            logPane.setEditable(false);
            logPane.setBackground(new Color(15, 23, 42));
            logPane.setFont(new Font("Cascadia Code", Font.PLAIN, 12));
            JScrollPane logScroll = new JScrollPane(logPane);
            logScroll.setBorder(new LineBorder(CARD_BORDER, 1));
            tabLogs.add(logScroll, BorderLayout.CENTER);

            JLabel lblLogNotice = new JLabel("🔒 Bảo mật: Mật khẩu, private token và dữ liệu nhạy cảm được tự động che chắn khỏi khung log.");
            lblLogNotice.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            lblLogNotice.setForeground(TEXT_MUTED);
            tabLogs.add(lblLogNotice, BorderLayout.SOUTH);

            tabs.addTab("📋 Nhật Ký Hệ Thống", tabLogs);

            // --- TAB 3: SYSTEM HEALTH & DIAGNOSTICS ---
            JPanel tabHealth = new JPanel(new BorderLayout(0, 14));
            tabHealth.setOpaque(false);
            tabHealth.setBorder(new EmptyBorder(16, 16, 16, 16));

            JPanel healthGrid = new JPanel(new GridLayout(3, 2, 14, 14));
            healthGrid.setOpaque(false);

            HealthItem itemServer = new HealthItem("Trạng thái Socket TCP", "🟢 Đang lắng nghe trên cổng " + server.tcpPort() + " (0.0.0.0)", "Kết nối TCP Raw Sockets hoạt động bình thường.");
            HealthItem itemDatabase = new HealthItem("Cơ sở dữ liệu SQLite", "🟢 Sẵn sàng và toàn vẹn", config.databasePath().toAbsolutePath().toString());
            HealthItem itemStorage = new HealthItem("Lưu trữ tệp đa phương tiện", "🟢 Thư mục khả dụng", config.storageRoot().toAbsolutePath().toString());
            HealthItem itemLimits = new HealthItem("Giới hạn kết nối TCP", "🟢 An toàn: tối đa " + config.maxConnections() + " kết nối", "Hạn ngạch người dùng: " + (config.userQuota() / (1024 * 1024)) + " MiB");
            HealthItem itemSecurity = new HealthItem("Chính sách truyền thông", "🛡️ TCP Plain Socket (Chế độ đồ án sinh viên)", "Không dùng TLS/SSL, UDP disabled mặc định.");
            HealthItem itemJvm = new HealthItem("Bộ nhớ JVM (Java Runtime)", "🟢 Đang tải thông số...", "Java " + System.getProperty("java.version"));

            healthGrid.add(itemServer);
            healthGrid.add(itemDatabase);
            healthGrid.add(itemStorage);
            healthGrid.add(itemLimits);
            healthGrid.add(itemSecurity);
            healthGrid.add(itemJvm);

            tabHealth.add(healthGrid, BorderLayout.NORTH);

            JPanel jvmControl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            jvmControl.setOpaque(false);
            JButton btnGc = new JButton("Thu gom rác JVM (Run GC) 🧹");
            btnGc.addActionListener(e -> {
                System.gc();
                JOptionPane.showMessageDialog(frame, "Đã gửi yêu cầu System.gc() tới JVM.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            });
            jvmControl.add(btnGc);
            tabHealth.add(jvmControl, BorderLayout.SOUTH);

            tabs.addTab("🩺 Chẩn Đoán & Vận Hành", tabHealth);

            // Compose center area
            JPanel centerPanel = new JPanel(new BorderLayout(0, 14));
            centerPanel.setOpaque(false);
            centerPanel.add(metricsGrid, BorderLayout.NORTH);
            centerPanel.add(tabs, BorderLayout.CENTER);
            root.add(centerPanel, BorderLayout.CENTER);

            // ================= LOG STREAM LOGIC =================
            StyledDocument doc = logPane.getStyledDocument();
            Style defaultStyle = doc.addStyle("default", null);
            StyleConstants.setForeground(defaultStyle, TEXT_MAIN);

            Style timeStyle = doc.addStyle("time", null);
            StyleConstants.setForeground(timeStyle, TEXT_MUTED);

            Style infoStyle = doc.addStyle("info", null);
            StyleConstants.setForeground(infoStyle, STATUS_BLUE);
            StyleConstants.setBold(infoStyle, true);

            Style warnStyle = doc.addStyle("warn", null);
            StyleConstants.setForeground(warnStyle, STATUS_YELLOW);
            StyleConstants.setBold(warnStyle, true);

            Style errorStyle = doc.addStyle("error", null);
            StyleConstants.setForeground(errorStyle, STATUS_RED);
            StyleConstants.setBold(errorStyle, true);

            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");
            List<LogEntry> logBuffer = new CopyOnWriteArrayList<>();

            Consumer<LogRecord> logConsumer = record -> {
                String rawMsg = record.getMessage();
                if (rawMsg == null) rawMsg = "";
                // Security filter: mask password and secret values
                String safeMsg = maskSensitive(rawMsg);
                if (record.getThrown() != null) {
                    safeMsg += " [" + record.getThrown().getClass().getSimpleName() + ": " + record.getThrown().getMessage() + "]";
                }
                LogEntry entry = new LogEntry(record.getMillis(), record.getLevel(), record.getLoggerName(), safeMsg);
                logBuffer.add(entry);
                while (logBuffer.size() > 800) {
                    logBuffer.remove(0);
                }

                SwingUtilities.invokeLater(() -> {
                    String selectedLevel = (String) cmbLogLevel.getSelectedItem();
                    String query = txtSearchLog.getText().trim().toLowerCase(Locale.ROOT);
                    if (matchesFilter(entry, selectedLevel, query)) {
                        appendLogEntry(doc, entry, timeFmt, defaultStyle, timeStyle, infoStyle, warnStyle, errorStyle);
                        if (chkAutoScroll.isSelected()) {
                            logPane.setCaretPosition(doc.getLength());
                        }
                    }
                });
            };

            SocialServer.addLogListener(logConsumer);

            Runnable rebuildLogs = () -> {
                try {
                    doc.remove(0, doc.getLength());
                    String selectedLevel = (String) cmbLogLevel.getSelectedItem();
                    String query = txtSearchLog.getText().trim().toLowerCase(Locale.ROOT);
                    for (LogEntry entry : logBuffer) {
                        if (matchesFilter(entry, selectedLevel, query)) {
                            appendLogEntry(doc, entry, timeFmt, defaultStyle, timeStyle, infoStyle, warnStyle, errorStyle);
                        }
                    }
                    if (chkAutoScroll.isSelected()) {
                        logPane.setCaretPosition(doc.getLength());
                    }
                } catch (Exception ignored) {}
            };

            cmbLogLevel.addActionListener(e -> rebuildLogs.run());
            txtSearchLog.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildLogs.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildLogs.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildLogs.run(); }
            });
            btnClearLog.addActionListener(e -> {
                logBuffer.clear();
                try { doc.remove(0, doc.getLength()); } catch (Exception ignored) {}
            });

            // ================= REFRESH & TIMER =================
            Runnable updateUi = () -> {
                if (server.isClosed()) {
                    serverStatusBadge.setText("● ĐÃ DỪNG");
                    serverStatusBadge.setBackground(STATUS_RED);
                    cardConn.setValue("0 / " + config.maxConnections());
                    cardUsers.setValue("0");
                    itemServer.setDetail("🔴 Server đã dừng hoặc cổng socket đã đóng.");
                    btnStop.setEnabled(false);
                    return;
                }

                int currentConns = server.connectionCount();
                int authedUsers = server.authenticatedCount();
                long uptimeSec = server.uptimeMs() / 1000;
                long hours = uptimeSec / 3600;
                long mins = (uptimeSec % 3600) / 60;
                long secs = uptimeSec % 60;
                String uptimeText = String.format("%02d:%02d:%02d", hours, mins, secs);

                cardConn.setValue(currentConns + " / " + config.maxConnections());
                cardUsers.setValue(String.valueOf(authedUsers));
                cardUptime.setValue(uptimeText);

                // Update clients table
                List<ClientSnapshot> clients = server.clientSnapshots();
                clientModel.setRowCount(0);
                int idx = 1;
                SimpleDateFormat connDateFmt = new SimpleDateFormat("HH:mm:ss");
                for (ClientSnapshot cs : clients) {
                    String shortId = cs.id().length() > 8 ? cs.id().substring(0, 8) + "…" : cs.id();
                    String timeStr = connDateFmt.format(new Date(cs.connectedAt()));
                    clientModel.addRow(new Object[]{
                            idx++,
                            shortId,
                            cs.remoteAddress(),
                            cs.userId() > 0 ? cs.userId() : "-",
                            cs.username(),
                            cs.authenticated() ? "Authenticated" : "Handshake",
                            timeStr,
                            cs.status()
                    });
                }
                lblClientSummary.setText("Tổng số phiên: " + clients.size() + " (" + authedUsers + " đã xác thực) · Cập nhật tự động mỗi giây");

                // Update JVM info
                long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                long usedMem = totalMem - freeMem;
                itemJvm.setDetail("🟢 Sử dụng: " + usedMem + " MB / " + totalMem + " MB RAM (JVM " + System.getProperty("java.version") + ")");

                // Verify DB and Media directory exist
                if (!Files.exists(config.databasePath())) {
                    itemDatabase.setDetail("⚠️ Tệp DB chưa tạo hoặc đường dẫn không hợp lệ.");
                } else {
                    itemDatabase.setDetail("🟢 SQLite OK: " + config.databasePath().toAbsolutePath());
                }
                if (!Files.isDirectory(config.storageRoot())) {
                    itemStorage.setDetail("⚠️ Thư mục lưu trữ media chưa tồn tại.");
                } else {
                    itemStorage.setDetail("🟢 Sẵn sàng: " + config.storageRoot().toAbsolutePath());
                }
            };

            javax.swing.Timer timer = new javax.swing.Timer(1000, e -> {
                if (server.isClosed()) {
                    ((javax.swing.Timer) e.getSource()).stop();
                    serverStatusBadge.setText("● ĐÃ DỪNG");
                    serverStatusBadge.setBackground(STATUS_RED);
                    btnStop.setEnabled(false);
                } else {
                    updateUi.run();
                }
            });
            timer.start();

            btnRefresh.addActionListener(e -> updateUi.run());

            Runnable shutdown = () -> {
                int confirm = JOptionPane.showConfirmDialog(
                        frame,
                        "Bạn có chắc muốn dừng Server mạng xã hội?\nTất cả kết nối client TCP sẽ bị ngắt an toàn.",
                        "Xác nhận dừng Server",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (confirm != JOptionPane.YES_OPTION) return;

                btnStop.setEnabled(false);
                serverStatusBadge.setText("● ĐANG DỪNG…");
                serverStatusBadge.setBackground(STATUS_YELLOW);
                new Thread(() -> {
                    try {
                        server.close();
                    } finally {
                        SocialServer.removeLogListener(logConsumer);
                        SwingUtilities.invokeLater(() -> {
                            timer.stop();
                            frame.dispose();
                        });
                    }
                }, "server-operator-stop").start();
            };

            btnStop.addActionListener(e -> shutdown.run());
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    shutdown.run();
                }
            });

            updateUi.run();
            frame.setContentPane(root);
            frame.setVisible(true);
        });
    }

    private static JLabel createBadge(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(fg);
        l.setBackground(bg);
        l.setOpaque(true);
        l.setBorder(new EmptyBorder(5, 12, 5, 12));
        return l;
    }

    private static String maskSensitive(String message) {
        if (message == null) return "";
        return message
                .replaceAll("(?i)(password|secret|pass|token)=[^,;\\s]+", "$1=******")
                .replaceAll("(?i)(\"password\"\\s*:\\s*\")[^\"]+(\")", "$1******$2");
    }

    private static boolean matchesFilter(LogEntry e, String selectedLevel, String query) {
        if (selectedLevel != null && !selectedLevel.startsWith("TẤT CẢ")) {
            if ("INFO".equals(selectedLevel) && e.level.intValue() != Level.INFO.intValue()) return false;
            if ("WARNING".equals(selectedLevel) && e.level.intValue() != Level.WARNING.intValue()) return false;
            if (selectedLevel.contains("ERROR") && e.level.intValue() < Level.SEVERE.intValue()) return false;
        }
        if (!query.isEmpty()) {
            return e.message.toLowerCase(Locale.ROOT).contains(query) ||
                    (e.logger != null && e.logger.toLowerCase(Locale.ROOT).contains(query));
        }
        return true;
    }

    private static void appendLogEntry(StyledDocument doc, LogEntry e, SimpleDateFormat fmt,
                                       Style defaultStyle, Style timeStyle, Style infoStyle, Style warnStyle, Style errorStyle) {
        try {
            // Trim if doc is too huge
            if (doc.getLength() > 100_000) {
                doc.remove(0, 20_000);
            }
            doc.insertString(doc.getLength(), "[" + fmt.format(new Date(e.millis)) + "] ", timeStyle);
            if (e.level.intValue() >= Level.SEVERE.intValue()) {
                doc.insertString(doc.getLength(), "[ERROR] ", errorStyle);
            } else if (e.level.intValue() >= Level.WARNING.intValue()) {
                doc.insertString(doc.getLength(), "[WARN]  ", warnStyle);
            } else {
                doc.insertString(doc.getLength(), "[INFO]  ", infoStyle);
            }
            String shortLogger = e.logger != null ? e.logger.substring(e.logger.lastIndexOf('.') + 1) : "Server";
            doc.insertString(doc.getLength(), shortLogger + ": " + e.message + "\n", defaultStyle);
        } catch (BadLocationException ignored) {}
    }

    private record LogEntry(long millis, Level level, String logger, String message) {}

    private static final class StatCard extends JPanel {
        private final JLabel valueLabel;
        private final JLabel subLabel;

        StatCard(String title, String initialVal, String subtitle) {
            super(new BorderLayout(0, 6));
            setBackground(CARD_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(CARD_BORDER, 1),
                    new EmptyBorder(12, 14, 12, 14)
            ));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            titleLabel.setForeground(TEXT_MUTED);

            valueLabel = new JLabel(initialVal);
            valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
            valueLabel.setForeground(TEXT_MAIN);

            subLabel = new JLabel(subtitle);
            subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            subLabel.setForeground(TEXT_MUTED);

            add(titleLabel, BorderLayout.NORTH);
            add(valueLabel, BorderLayout.CENTER);
            add(subLabel, BorderLayout.SOUTH);
        }

        void setValue(String val) {
            valueLabel.setText(val);
        }
    }

    private static final class HealthItem extends JPanel {
        private final JLabel detailLabel;

        HealthItem(String name, String status, String detail) {
            super(new BorderLayout(0, 4));
            setBackground(CARD_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(CARD_BORDER, 1),
                    new EmptyBorder(10, 12, 10, 12)
            ));

            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            nameLabel.setForeground(TEXT_MAIN);

            JLabel statusLabel = new JLabel(status);
            statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            statusLabel.setForeground(STATUS_GREEN);

            detailLabel = new JLabel(detail);
            detailLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            detailLabel.setForeground(TEXT_MUTED);

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(nameLabel, BorderLayout.WEST);
            top.add(statusLabel, BorderLayout.EAST);

            add(top, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.CENTER);
        }

        void setDetail(String text) {
            detailLabel.setText(text);
        }
    }
}
