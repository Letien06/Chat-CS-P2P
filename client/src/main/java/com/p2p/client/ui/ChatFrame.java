package com.p2p.client.ui;

import com.p2p.client.network.ChatClient;
import com.p2p.common.protocol.Message;
import com.p2p.common.protocol.MessageType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatFrame extends JFrame {
    private static final int FILE_CHUNK_SIZE = 48 * 1024;

    private final String username;
    private final String serverAddress;
    private final ChatClient client;
    private final DefaultListModel<String> users = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(users);
    private final MessageListPanel transcript = new MessageListPanel();
    private final JTextField messageField = new JTextField();
    private final ToastNotification transferToast = new ToastNotification();
    private final Timer toastTimer = new Timer(2800, e -> transferToast.setVisible(false));
    private final JLabel chatTitle = new JLabel("Chọn một cuộc trò chuyện");
    private final JLabel chatStatus = new JLabel("");
    private final Theme.Avatar chatAvatar = new Theme.Avatar("?", 42, false);
    private final ExecutorService transfers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "file-transfer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, OutgoingTransfer> outgoing = new ConcurrentHashMap<>();
    private final Map<String, IncomingTransfer> incoming = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> incomingWorkers = new ConcurrentHashMap<>();
    private final Map<String, PendingFileOffer> pendingOffers = new ConcurrentHashMap<>();
    private final Map<String, FileCard> fileCards = new ConcurrentHashMap<>();
    private final Map<String, List<ChatEntry>> conversationHistory = new ConcurrentHashMap<>();
    private final List<String> onlineUsers = new ArrayList<>();
    private String activePeer;

    public ChatFrame(String username, String serverAddress, ChatClient client) {
        super("MiniChat - " + username);
        this.username = username;
        this.serverAddress = serverAddress;
        this.client = client;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1180, 760);
        setLocationRelativeTo(null);
        toastTimer.setRepeats(false);
        buildUi();
    }

    private void buildUi() {
        transcript.setBackground(Theme.CHAT_BACKGROUND);
        transcript.setBorder(Theme.padding(24, 34, 24, 34));

        JPanel navigation = Theme.roundedPanel(Theme.NAV_BACKGROUND, 18);
        navigation.setLayout(new BorderLayout(0, 14));
        navigation.setBorder(Theme.padding(18, 10, 14, 10));

        JPanel navTop = new JPanel();
        navTop.setOpaque(false);
        navTop.setLayout(new BoxLayout(navTop, BoxLayout.Y_AXIS));
        Theme.Avatar profileAvatar = new Theme.Avatar(username, 48, true);
        profileAvatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        navTop.add(profileAvatar);
        navTop.add(Box.createVerticalStrut(22));
        JButton chats = Theme.navButton(new AppIcon(AppIcon.Type.CHAT, Theme.PRIMARY, 21), "Tin nhắn", true);
        chats.setAlignmentX(Component.CENTER_ALIGNMENT);
        navTop.add(chats);
        navTop.add(Box.createVerticalStrut(10));
        JButton files = Theme.navButton(new AppIcon(AppIcon.Type.FOLDER, Theme.MUTED, 21), "Gửi file", false);
        files.setAlignmentX(Component.CENTER_ALIGNMENT);
        files.addActionListener(e -> chooseAndSendFile());
        navTop.add(files);
        navigation.add(navTop, BorderLayout.NORTH);

        userList.setFixedCellHeight(58);
        userList.setBorder(Theme.padding(6, 4, 6, 4));
        userList.setBackground(Theme.SURFACE);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserCellRenderer(username));
        userList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectPeer(userList.getSelectedValue()); });
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createEmptyBorder());
        userScroll.getViewport().setBackground(Theme.SURFACE);
        userScroll.getVerticalScrollBar().setUnitIncrement(12);

        JTextField search = new JTextField();
        search.setPreferredSize(new Dimension(0, 38));
        search.setBorder(Theme.padding(0, 12, 0, 12));
        search.putClientProperty("JTextField.placeholderText", "Tìm người dùng...");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterUsers(search.getText()); }
        });
        JPanel contacts = Theme.roundedPanel(Theme.SURFACE, 18);
        contacts.setLayout(new BorderLayout(0, 12));
        contacts.setBorder(Theme.padding(18, 14, 14, 14));
        JPanel contactsHeader = new JPanel();
        contactsHeader.setOpaque(false);
        contactsHeader.setLayout(new BoxLayout(contactsHeader, BoxLayout.Y_AXIS));
        JLabel appName = new JLabel("MiniChat");
        appName.setFont(Theme.font(Font.BOLD, 19));
        appName.setForeground(Theme.TEXT);
        JLabel onlineLabel = new JLabel("Người dùng đang online");
        onlineLabel.setFont(Theme.font(Font.PLAIN, 12));
        onlineLabel.setForeground(Theme.MUTED);
        contactsHeader.add(appName);
        contactsHeader.add(Box.createVerticalStrut(4));
        contactsHeader.add(onlineLabel);
        contactsHeader.add(Box.createVerticalStrut(14));
        contactsHeader.add(search);
        contacts.add(contactsHeader, BorderLayout.NORTH);
        contacts.add(userScroll, BorderLayout.CENTER);

        JPanel navBottom = new JPanel();
        navBottom.setOpaque(false);
        navBottom.setLayout(new BoxLayout(navBottom, BoxLayout.Y_AXIS));
        JButton refresh = Theme.navButton(new AppIcon(AppIcon.Type.REFRESH, Theme.MUTED, 20), "Làm mới danh sách", false);
        refresh.setAlignmentX(Component.CENTER_ALIGNMENT);
        refresh.addActionListener(e -> refreshUsers());
        JButton exit = Theme.navButton(new AppIcon(AppIcon.Type.LOGOUT, Theme.MUTED, 20), "Thoát", false);
        exit.setAlignmentX(Component.CENTER_ALIGNMENT);
        exit.addActionListener(e -> dispose());
        navBottom.add(refresh);
        navBottom.add(Box.createVerticalStrut(9));
        navBottom.add(exit);
        navigation.add(navBottom, BorderLayout.SOUTH);

        JPanel side = new JPanel(new BorderLayout(8, 0));
        side.setOpaque(false);
        side.add(navigation, BorderLayout.WEST);
        side.add(contacts, BorderLayout.CENTER);

        JPanel chat = Theme.roundedPanel(Theme.SURFACE, 18);
        chat.setLayout(new BorderLayout());
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(Theme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                Theme.padding(15, 22, 15, 22)));
        header.add(chatAvatar, BorderLayout.WEST);
        JPanel headerText = new JPanel(); headerText.setOpaque(false); headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        chatTitle.setFont(Theme.font(Font.BOLD, 17)); chatTitle.setForeground(Theme.TEXT);
        chatStatus.setFont(Theme.font(Font.PLAIN, 12)); chatStatus.setForeground(Theme.MUTED);
        headerText.add(chatTitle); headerText.add(Box.createVerticalStrut(4)); headerText.add(chatStatus); header.add(headerText, BorderLayout.CENTER);
        JLabel secure = new JLabel("●  P2P Relay"); secure.setFont(Theme.font(Font.PLAIN, 12)); secure.setForeground(Theme.MUTED); header.add(secure, BorderLayout.EAST);
        chat.add(header, BorderLayout.NORTH);
        JScrollPane transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        transcriptScroll.getViewport().setBackground(Theme.CHAT_BACKGROUND);
        transcriptScroll.getVerticalScrollBar().setUnitIncrement(14);
        chat.add(transcriptScroll, BorderLayout.CENTER);

        JPanel composer = Theme.roundedPanel(Theme.COMPOSER_BACKGROUND, 28);
        composer.setLayout(new BorderLayout(8, 0));
        composer.setBorder(Theme.padding(7, 10, 7, 10));
        JButton sendFile = Theme.iconButton(new AppIcon(AppIcon.Type.ATTACHMENT, Theme.MUTED, 18), "Đính kèm file", false);
        sendFile.setBackground(Theme.COMPOSER_BACKGROUND);
        sendFile.addActionListener(e -> chooseAndSendFile());
        JButton send = Theme.iconButton(new AppIcon(AppIcon.Type.SEND, Theme.MUTED, 19), "Gửi tin nhắn", false);
        send.setBackground(Theme.COMPOSER_BACKGROUND);
        send.addActionListener(e -> sendMessage());
        composer.add(sendFile, BorderLayout.WEST);
        composer.add(messageField, BorderLayout.CENTER);
        composer.add(send, BorderLayout.EAST);
        messageField.addActionListener(e -> sendMessage());
        messageField.setPreferredSize(new Dimension(0, 44));
        messageField.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        messageField.setOpaque(false);
        messageField.setForeground(Theme.TEXT);
        messageField.setCaretColor(Theme.TEXT);
        messageField.putClientProperty("JTextField.placeholderText", "Nhập tin nhắn... (Enter để gửi)");

        JLabel connection = new JLabel("●  Sẵn sàng · " + serverAddress);
        connection.setFont(Theme.font(Font.PLAIN, 11));
        connection.setForeground(Theme.ONLINE);
        connection.setBorder(Theme.padding(7, 8, 1, 8));
        JPanel lower = new JPanel(new BorderLayout());
        lower.setBackground(Theme.SURFACE);
        lower.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                Theme.padding(12, 18, 9, 18)));
        lower.add(composer, BorderLayout.CENTER);
        lower.add(connection, BorderLayout.SOUTH);
        chat.add(lower, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(8, 0));
        root.setBackground(Theme.APP_BACKGROUND);
        root.setBorder(Theme.padding(8, 8, 8, 8));
        navigation.setPreferredSize(new Dimension(82, 0));
        side.setPreferredSize(new Dimension(326, 0));
        root.add(side, BorderLayout.WEST);
        root.add(chat, BorderLayout.CENTER);
        setContentPane(root);
        installTransferToast();
    }

    private void installTransferToast() {
        JLayeredPane layeredPane = getLayeredPane();
        transferToast.setVisible(false);
        layeredPane.add(transferToast, JLayeredPane.POPUP_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { positionTransferToast(); }
        });
    }

    private void positionTransferToast() {
        Dimension size = transferToast.getPreferredSize();
        int x = Math.max(16, (getLayeredPane().getWidth() - size.width) / 2);
        int y = Math.max(16, getLayeredPane().getHeight() - size.height - 88);
        transferToast.setBounds(x, y, size.width, size.height);
    }

    private void refreshUsers() {
        try { client.send(Message.of(MessageType.USER_LIST_REQUEST)); }
        catch (IOException e) { showError(e.getMessage()); }
    }
    public void requestUsers() { refreshUsers(); }
    private void selectPeer(String peer) {
        // Refreshing the online-user model briefly clears the JList selection.
        // Keep the active conversation instead of treating that as a user action.
        if (peer == null || peer.equals(username)) {
            if (activePeer != null) return;
            chatTitle.setText("Chọn một cuộc trò chuyện");
            chatStatus.setText("Chọn một người dùng để bắt đầu");
            chatAvatar.setName("?");
            chatAvatar.setOnline(false);
            return;
        }
        if (peer.equals(activePeer)) return;
        activePeer = peer;
        chatTitle.setText(peer);
        chatStatus.setText("● Đang hoạt động trong mạng LAN");
        chatAvatar.setName(peer);
        chatAvatar.setOnline(true);
        transcript.removeAll();
        for (ChatEntry entry : conversationHistory.getOrDefault(peer, List.of())) {
            if (entry.fileCard() != null) addFileCard(entry.fileCard(), entry.mine());
            else renderLine(entry.text());
        }
        transcript.revalidate();
        transcript.repaint();
        scrollTranscriptToBottom();
    }

    private void sendMessage() {
        String receiver = selectedPeer();
        if (receiver == null) return;
        String content = messageField.getText().trim();
        if (content.isBlank()) return;
        try {
            client.send(Message.of(MessageType.PRIVATE_MESSAGE).put("receiver", receiver).put("content", content));
            appendToConversation(receiver, "Bạn → " + receiver + ": " + content);
            messageField.setText("");
        } catch (IOException e) { showError(e.getMessage()); }
    }

    private void chooseAndSendFile() {
        String receiver = selectedPeer();
        if (receiver == null) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        transfers.submit(() -> prepareFile(receiver, chooser.getSelectedFile().toPath()));
    }

    private void prepareFile(String receiver, Path file) {
        try {
            long size = Files.size(file);
            if (size > 2L * 1024 * 1024 * 1024) { SwingUtilities.invokeLater(() -> showError("File vượt quá giới hạn 2 GB.")); return; }
            String transferId = UUID.randomUUID().toString();
            String sha256 = sha256(file);
            outgoing.put(transferId, new OutgoingTransfer(receiver, file, size, sha256));
            SwingUtilities.invokeAndWait(() -> {
                FileCard card = new FileCard(file.getFileName().toString(), formatBytes(size) + " · Đang chờ người nhận");
                fileCards.put(transferId, card);
                addFileCardToConversation(receiver, card, true);
            });
            client.send(Message.of(MessageType.FILE_OFFER).put("receiver", receiver).put("fileName", file.getFileName().toString())
                    .put("fileSize", size).put("sha256", sha256).put("transferId", transferId));
            setProgress(0, "Đang chờ người nhận...");
        } catch (Exception e) { SwingUtilities.invokeLater(() -> showError("Không thể chuẩn bị file: " + e.getMessage())); }
    }

    private void offerReceived(Message message) {
        String transferId = message.string("transferId");
        String sender = message.string("sender");
        String fileName = safeFileName(message.string("fileName"));
        long fileSize = message.longValue("fileSize", -1);
        if (transferId == null || sender == null || fileSize < 0) { showError("Đề nghị file không hợp lệ."); return; }
        PendingFileOffer offer = new PendingFileOffer(transferId, sender, fileName, fileSize, message.string("sha256"));
        pendingOffers.put(transferId, offer);
        FileCard card = new FileCard(fileName, formatBytes(fileSize) + " · từ " + sender);
        card.showDownload(() -> downloadOffer(offer, card));
        fileCards.put(transferId, card);
        addFileCardToConversation(sender, card, false);
    }

    private void downloadOffer(PendingFileOffer offer, FileCard card) {
        if (!pendingOffers.remove(offer.transferId(), offer)) return;
        card.markPreparing();
        try {
            Path directory = Path.of("downloads");
            Files.createDirectories(directory);
            Path destination = uniqueDestination(directory.resolve(offer.fileName()));
            incoming.put(offer.transferId(), new IncomingTransfer(destination, offer.fileSize(), offer.sha256()));
            incomingWorkers.put(offer.transferId(), Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "incoming-file-" + offer.transferId());
                thread.setDaemon(true);
                return thread;
            }));
            client.send(Message.of(MessageType.FILE_ACCEPT).put("receiver", offer.sender()).put("transferId", offer.transferId()));
            card.markDownloading(0);
            setProgress(0, "Đang nhận file...");
        } catch (Exception e) {
            incoming.remove(offer.transferId());
            ExecutorService worker = incomingWorkers.remove(offer.transferId());
            if (worker != null) worker.shutdownNow();
            card.markFailed("Không thể tải file");
            try { client.send(Message.of(MessageType.FILE_REJECT).put("receiver", offer.sender()).put("transferId", offer.transferId())); } catch (IOException ignored) { }
            showError("Không thể nhận file: " + e.getMessage());
        }
    }

    private void startOutgoing(String transferId) {
        OutgoingTransfer transfer = outgoing.remove(transferId);
        if (transfer == null) return;
        transfers.submit(() -> {
            try (InputStream input = Files.newInputStream(transfer.file)) {
                byte[] buffer = new byte[FILE_CHUNK_SIZE]; int read; long sent = 0; long sequence = 0;
                while ((read = input.read(buffer)) != -1) {
                    byte[] chunk = Arrays.copyOf(buffer, read);
                    client.send(Message.of(MessageType.FILE_CHUNK).put("receiver", transfer.receiver).put("transferId", transferId)
                            .put("sequence", sequence++).put("data", Base64.getEncoder().encodeToString(chunk)));
                    sent += read;
                    int progress = percent(sent, transfer.size);
                    setProgress(progress, "Đang gửi file...");
                    FileCard card = fileCards.get(transferId);
                    if (card != null) SwingUtilities.invokeLater(() -> card.markSending(progress));
                }
                client.send(Message.of(MessageType.FILE_COMPLETE).put("receiver", transfer.receiver).put("transferId", transferId)
                        .put("fileSize", transfer.size).put("sha256", transfer.sha256));
                SwingUtilities.invokeLater(() -> {
                    FileCard card = fileCards.get(transferId);
                    if (card != null) card.markSent();
                    setProgress(100, "Đã gửi file thành công");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    FileCard card = fileCards.get(transferId);
                    if (card != null) card.markFailed("Gửi file thất bại");
                    showError("Gửi file thất bại: " + e.getMessage());
                });
            }
        });
    }

    private void chunkReceived(Message message) {
        String transferId = message.string("transferId");
        IncomingTransfer transfer = incoming.get(transferId);
        ExecutorService worker = incomingWorkers.get(transferId);
        if (transfer == null || worker == null) return;
        worker.submit(() -> {
            try {
                byte[] bytes = Base64.getDecoder().decode(message.string("data"));
                synchronized (transfer) {
                    if (transfer.output == null) transfer.output = Files.newOutputStream(transfer.destination, StandardOpenOption.CREATE_NEW);
                    transfer.output.write(bytes); transfer.received += bytes.length;
                }
                int progress = percent(transfer.received, transfer.size);
                setProgress(progress, "Đang nhận file...");
                FileCard card = fileCards.get(transferId);
                if (card != null) SwingUtilities.invokeLater(() -> card.markDownloading(progress));
            } catch (Exception e) { failIncoming(transferId, transfer, e); }
        });
    }

    private void completeIncoming(Message message) {
        String transferId = message.string("transferId");
        IncomingTransfer transfer = incoming.remove(transferId);
        ExecutorService worker = incomingWorkers.get(transferId);
        if (transfer == null || worker == null) return;
        worker.submit(() -> {
            try {
                synchronized (transfer) {
                    if (transfer.output == null) Files.createFile(transfer.destination); else transfer.output.close();
                }
                String actualSha256 = sha256(transfer.destination);
                boolean hashMatches = transfer.sha256 == null || transfer.sha256.isBlank() || transfer.sha256.equalsIgnoreCase(actualSha256);
                if (transfer.received != transfer.size || !hashMatches) { Files.deleteIfExists(transfer.destination); throw new IOException("Kích thước hoặc SHA-256 không khớp"); }
                SwingUtilities.invokeLater(() -> {
                    FileCard card = fileCards.get(transferId);
                    if (card != null) card.markDownloaded(transfer.destination, () -> openFile(transfer.destination));
                    setProgress(100, "Nhận file thành công");
                });
            } catch (Exception e) { failIncoming(transferId, transfer, e); }
            finally { incomingWorkers.remove(transferId); worker.shutdown(); }
        });
    }

    private void failIncoming(String transferId, IncomingTransfer transfer, Exception error) {
        try { synchronized (transfer) { if (transfer.output != null) transfer.output.close(); } Files.deleteIfExists(transfer.destination); }
        catch (IOException ignored) { }
        incoming.remove(transferId);
        ExecutorService worker = incomingWorkers.remove(transferId);
        if (worker != null) worker.shutdownNow();
        SwingUtilities.invokeLater(() -> {
            FileCard card = fileCards.get(transferId);
            if (card != null) card.markFailed("Tải xuống thất bại");
            showError("Nhận file thất bại: " + error.getMessage());
        });
    }

    public void handle(Message message) {
        if (message.getType() == null) return;
        switch (message.getType()) {
            case USER_LIST_RESPONSE -> { onlineUsers.clear(); Object value = message.get("users"); if (value instanceof java.util.Collection<?> list) for (Object item : list) { String user = String.valueOf(item); if (!user.equalsIgnoreCase(username)) onlineUsers.add(user); } filterUsers(""); }
            case USER_STATUS_CHANGED -> { append("[Hệ thống] " + message.string("username") + (message.bool("online", false) ? " đã online." : " đã offline.")); refreshUsers(); }
            case PRIVATE_MESSAGE -> {
                if (message.string("content") != null && message.string("sender") != null)
                    appendToConversation(message.string("sender"), message.string("sender") + ": " + message.string("content"));
            }
            case FILE_OFFER -> { if (message.string("sender") != null) offerReceived(message); }
            case FILE_ACCEPT -> { if (message.string("from") != null) startOutgoing(message.string("transferId")); }
            case FILE_REJECT -> { if (message.string("from") != null) { outgoing.remove(message.string("transferId")); FileCard card = fileCards.get(message.string("transferId")); if (card != null) card.markFailed("Người nhận đã từ chối"); setProgress(0, "File bị từ chối"); } }
            case FILE_CHUNK -> chunkReceived(message);
            case FILE_COMPLETE -> { if (message.string("from") != null) completeIncoming(message); }
            case FILE_FAILED -> { outgoing.remove(message.string("transferId")); FileCard card = fileCards.get(message.string("transferId")); if (card != null) card.markFailed("Truyền file thất bại"); showError("Truyền file thất bại: " + message.string("message")); }
            case ERROR -> showError(message.string("message"));
            default -> { }
        }
    }

    private String selectedPeer() {
        String receiver = activePeer != null ? activePeer : userList.getSelectedValue();
        if (receiver == null || receiver.equals(username)) { showError("Hãy chọn một người dùng khác."); return null; }
        return receiver;
    }
    private void setProgress(int value, String text) {
        SwingUtilities.invokeLater(() -> {
            String detail = text + (value > 0 ? " (" + value + "%)" : "");
            transferToast.setMessage(detail, value >= 100);
            positionTransferToast();
            transferToast.setVisible(true);
            transferToast.repaint();
            toastTimer.restart();
        });
    }
    private void addFileCard(FileCard card, boolean mine) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(Theme.padding(5, 0, 5, 0));
        Theme.Avatar avatar = new Theme.Avatar(mine ? username : activePeer, 30, true);
        JPanel group = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 0));
        group.setOpaque(false);
        if (mine) { group.add(card); group.add(avatar); }
        else { group.add(avatar); group.add(card); }
        row.add(group, mine ? BorderLayout.EAST : BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        scrollTranscriptToBottom();
    }

    private void addFileCardToConversation(String peer, FileCard card, boolean mine) {
        conversationHistory.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(new ChatEntry(null, card, mine));
        if (activePeer == null) {
            userList.setSelectedValue(peer, true);
            if (activePeer == null) selectPeer(peer);
            return;
        }
        if (peer.equals(activePeer)) addFileCard(card, mine);
    }

    private void openFile(Path path) {
        if (!Files.exists(path)) { showError("Không tìm thấy file: " + path.toAbsolutePath()); return; }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showError("Máy này không hỗ trợ mở file tự động. File nằm tại: " + path.toAbsolutePath());
            return;
        }
        try { Desktop.getDesktop().open(path.toFile()); }
        catch (IOException | SecurityException e) { showError("Không thể mở file: " + e.getMessage()); }
    }

    private void scrollTranscriptToBottom() {
        SwingUtilities.invokeLater(() -> {
            Container parent = transcript.getParent();
            if (parent instanceof JViewport viewport && viewport.getParent() instanceof JScrollPane scroll)
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        });
    }
    private static int percent(long current, long total) { return total <= 0 ? 100 : (int) Math.min(100, current * 100 / total); }
    private static String safeFileName(String name) { if (name == null || name.isBlank()) return "received-file"; return Path.of(name).getFileName().toString(); }
    private static Path uniqueDestination(Path path) { if (!Files.exists(path)) return path; String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : ""; int index = 1; Path candidate; do { candidate = path.resolveSibling(base + " (" + index++ + ")" + ext); } while (Files.exists(candidate)); return candidate; }
    private static String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024 * 1024) return (bytes / 1024) + " KB"; return String.format("%.1f MB", bytes / 1024d / 1024d); }
    private static String sha256(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read); } StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format("%02x", value)); return result.toString(); }
    private void filterUsers(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        users.clear();
        for (String user : onlineUsers) if (user.toLowerCase().contains(normalized)) users.addElement(user);
        if (activePeer != null && users.contains(activePeer)) userList.setSelectedValue(activePeer, true);
    }
    private void append(String line) {
        if (activePeer != null) appendToConversation(activePeer, line);
    }
    private void appendToConversation(String peer, String line) {
        if (peer == null || peer.equals(username)) return;
        conversationHistory.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(new ChatEntry(line, null, false));
        if (activePeer == null) {
            userList.setSelectedValue(peer, true);
            if (activePeer == null) selectPeer(peer);
            return;
        }
        if (!peer.equals(activePeer)) return;
        renderLine(line);
    }
    private void renderLine(String line) {
        boolean mine = line.startsWith("Bạn →");
        boolean system = line.startsWith("[Hệ thống]") || line.startsWith("[File]");
        String visible = line.replaceFirst("^Bạn → [^:]+: ", "");
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(Theme.padding(5, 0, 5, 0));
        MessageBubble bubble;
        if (system) {
            bubble = new MessageBubble(visible, Theme.FILE_CARD, Theme.MUTED);
            row.add(bubble, BorderLayout.CENTER);
        } else if (mine) {
            bubble = new MessageBubble(visible, Theme.PRIMARY, Color.WHITE);
            JPanel group = messageGroup(bubble, new Theme.Avatar(username, 30, true), true);
            row.add(group, BorderLayout.EAST);
        } else {
            bubble = new MessageBubble(visible, Theme.FILE_CARD, Theme.TEXT);
            JPanel group = messageGroup(bubble, new Theme.Avatar(activePeer, 30, true), false);
            row.add(group, BorderLayout.WEST);
        }
        transcript.add(row); transcript.revalidate(); transcript.repaint();
        scrollTranscriptToBottom();
    }

    private JPanel messageGroup(MessageBubble bubble, Theme.Avatar avatar, boolean mine) {
        JPanel group = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 0));
        group.setOpaque(false);
        if (mine) { group.add(bubble); group.add(avatar); }
        else { group.add(avatar); group.add(bubble); }
        return group;
    }
    private static String escapeHtml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private void showError(String text) { JOptionPane.showMessageDialog(this, text == null ? "Có lỗi xảy ra." : text, "Lỗi", JOptionPane.ERROR_MESSAGE); }
    @Override public void dispose() { toastTimer.stop(); transfers.shutdownNow(); client.close(); super.dispose(); }
    private record ChatEntry(String text, FileCard fileCard, boolean mine) { }
    private record OutgoingTransfer(String receiver, Path file, long size, String sha256) { }
    private record PendingFileOffer(String transferId, String sender, String fileName, long fileSize, String sha256) { }
    private static final class IncomingTransfer { private final Path destination; private final long size; private final String sha256; private long received; private OutputStream output; private IncomingTransfer(Path destination, long size, String sha256) { this.destination = destination; this.size = size; this.sha256 = sha256; } }
    private static final class FileCard extends JPanel {
        private final JLabel status = new JLabel();
        private final JPanel actionSlot = new JPanel(new BorderLayout());
        private final JProgressBar progress = new JProgressBar(0, 100);

        private FileCard(String fileName, String details) {
            setOpaque(false);
            setLayout(new BorderLayout(12, 9));
            setBorder(Theme.padding(13, 14, 12, 14));
            setPreferredSize(new Dimension(430, 96));

            JLabel icon = new JLabel(new AppIcon(AppIcon.Type.FILE, Theme.PRIMARY, 27));
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            icon.setPreferredSize(new Dimension(42, 42));
            add(icon, BorderLayout.WEST);

            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(ellipsize(fileName, 34));
            name.setToolTipText(fileName);
            name.setFont(Theme.font(Font.BOLD, 14));
            name.setForeground(Theme.TEXT);
            status.setText(details);
            status.setFont(Theme.font(Font.PLAIN, 12));
            status.setForeground(Theme.MUTED);
            copy.add(name);
            copy.add(Box.createVerticalStrut(5));
            copy.add(status);
            add(copy, BorderLayout.CENTER);

            actionSlot.setOpaque(false);
            add(actionSlot, BorderLayout.EAST);
            progress.setVisible(false);
            progress.setPreferredSize(new Dimension(0, 5));
            progress.setStringPainted(false);
            progress.putClientProperty("JProgressBar.largeHeight", false);
            add(progress, BorderLayout.SOUTH);
        }

        private void showDownload(Runnable action) {
            JButton button = Theme.actionButton("Tải xuống", new AppIcon(AppIcon.Type.DOWNLOAD, Theme.PRIMARY, 16), false);
            button.setToolTipText("Tải file về thư mục downloads");
            button.addActionListener(e -> action.run());
            setAction(button);
        }

        private void markPreparing() {
            status.setText("Đang chuẩn bị tải xuống...");
            Component action = actionSlot.getComponentCount() == 0 ? null : actionSlot.getComponent(0);
            if (action != null) action.setEnabled(false);
        }

        private void markDownloading(int value) {
            progress.setVisible(true);
            progress.setValue(value);
            status.setText("Đang tải xuống · " + value + "%");
            actionSlot.removeAll();
            actionSlot.revalidate();
            actionSlot.repaint();
            revalidate();
        }

        private void markSending(int value) {
            progress.setVisible(true);
            progress.setValue(value);
            status.setText("Đang gửi · " + value + "%");
            revalidate();
        }

        private void markSent() {
            progress.setVisible(false);
            status.setText("Đã gửi");
            status.setForeground(Theme.ONLINE);
            revalidate();
            repaint();
        }

        private void markDownloaded(Path path, Runnable openAction) {
            progress.setValue(100);
            progress.setVisible(false);
            status.setText("Đã tải xuống · " + path.toAbsolutePath());
            status.setToolTipText(path.toAbsolutePath().toString());
            JButton button = Theme.actionButton("Mở file", new AppIcon(AppIcon.Type.OPEN_FILE, Theme.PRIMARY, 16), false);
            button.setToolTipText("Mở file đã tải xuống");
            button.addActionListener(e -> openAction.run());
            setAction(button);
        }

        private void markFailed(String message) {
            progress.setVisible(false);
            status.setText(message);
            status.setForeground(Theme.DANGER);
            actionSlot.removeAll();
            actionSlot.revalidate();
            actionSlot.repaint();
            revalidate();
            repaint();
        }

        private void setAction(JButton button) {
            actionSlot.removeAll();
            actionSlot.add(button, BorderLayout.CENTER);
            actionSlot.revalidate();
            actionSlot.repaint();
        }

        private static String ellipsize(String value, int max) {
            if (value == null || value.length() <= max) return value;
            int dot = value.lastIndexOf('.');
            String extension = dot > 0 && value.length() - dot <= 8 ? value.substring(dot) : "";
            int keep = Math.max(8, max - extension.length() - 1);
            return value.substring(0, keep) + "…" + extension;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.FILE_CARD);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setColor(Theme.BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
    private static final class UserCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final Theme.Avatar avatar = new Theme.Avatar("?", 42, true);
        private final JLabel name = new JLabel();
        private final JLabel status = new JLabel("Đang hoạt động");
        private final String currentUser;
        private UserCellRenderer(String currentUser) { this.currentUser = currentUser; setLayout(new BorderLayout(10, 0)); setBorder(Theme.padding(7, 9, 7, 9)); JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS)); name.setFont(Theme.font(Font.BOLD, 14)); status.setFont(Theme.font(Font.PLAIN, 11)); status.setForeground(Theme.MUTED); text.add(name); text.add(Box.createVerticalStrut(3)); text.add(status); add(avatar, BorderLayout.WEST); add(text, BorderLayout.CENTER); }
        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value, int index, boolean selected, boolean focus) { name.setText(value.equals(currentUser) ? value + " (bạn)" : value); avatar.setName(value); avatar.setOnline(true); setBackground(selected ? Theme.PRIMARY_SOFT : Theme.SURFACE); setOpaque(true); name.setForeground(selected ? Color.WHITE : Theme.TEXT); status.setForeground(selected ? new Color(205, 208, 255) : Theme.MUTED); return this; }
    }
    private static final class ToastNotification extends JPanel {
        private final JLabel message = new JLabel();
        private boolean success;

        private ToastNotification() {
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(Theme.padding(11, 16, 12, 16));
            JLabel icon = new JLabel(new AppIcon(AppIcon.Type.FILE, Color.WHITE, 19));
            add(icon, BorderLayout.WEST);
            message.setFont(Theme.font(Font.BOLD, 13));
            message.setForeground(Color.WHITE);
            add(message, BorderLayout.CENTER);
        }

        private void setMessage(String text, boolean success) {
            this.success = success;
            message.setText(text);
            revalidate();
        }

        @Override public Dimension getPreferredSize() {
            Dimension content = super.getPreferredSize();
            return new Dimension(Math.max(210, content.width), Math.max(46, content.height));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(15, 23, 42, 48));
            g.fillRoundRect(3, 4, getWidth() - 6, getHeight() - 5, 22, 22);
            g.setColor(success ? new Color(22, 101, 52) : new Color(30, 41, 59));
            g.fillRoundRect(1, 1, getWidth() - 4, getHeight() - 5, 22, 22);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
    private static final class MessageBubble extends JPanel {
        private final Color fill;
        private MessageBubble(String text, Color fill, Color foreground) { this.fill = fill; setOpaque(false); setBorder(Theme.padding(10, 14, 10, 14)); JLabel label = new JLabel("<html><body style='width:330px'>" + escapeHtml(text) + "</body></html>"); label.setFont(Theme.font(Font.PLAIN, 14)); label.setForeground(foreground); add(label); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20); g.dispose(); super.paintComponent(graphics); }
    }
    private static final class MessageListPanel extends JPanel implements Scrollable {
        private MessageListPanel() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
