# 06 — Màn hình Desktop và luồng trải nghiệm

Thiết kế Swing mục tiêu; tái dùng FlatLaf/Theme/AppIcon nhưng tách ChatFrame thành panel/controller/service. Mỗi màn hình cần trạng thái loading, empty, error, disconnected và không được treo EDT.

## 1. Cấu trúc điều hướng

```text
Kết nối server → Đăng nhập / Đăng ký → MainFrame
                                         ├─ Bảng tin
                                         ├─ Bạn bè / Lời mời / Tìm người
                                         ├─ Tin nhắn / Nhóm
                                         ├─ Thông báo
                                         ├─ Hồ sơ của tôi / Cài đặt
                                         └─ Quản trị (chỉ ADMIN)
```

MainFrame: navigation trái, nội dung giữa, khu vực phụ tùy màn hình, trạng thái kết nối và unread badge. Kích thước ban đầu khoảng 1.100×750, minimum khoảng 900×600; layout manager co giãn, tránh absolute bounds cho content động. UI tiếng Việt thống nhất; thời gian theo máy người dùng, tooltip có ngày giờ đầy đủ.

## 2. Màn hình và hành động

| View | Nội dung/hành động | Điều kiện và trạng thái cần xử lý |
|---|---|---|
| ServerConnectionPanel | Host, port, tìm server LAN, chọn kết nối | Loading/không tìm thấy/IP sai/timeout/cert không tin; luôn nhập tay được |
| LoginPanel | Username, password, đăng nhập, chuyển đăng ký | Disable khi request pending, lỗi auth chung, đang đăng nhập máy khác, server offline |
| RegisterPanel | Username, tên hiển thị, password/confirm | Validation trước gửi, duplicate, rate limit; success trở lại login |
| FeedPanel | Composer, post cards, tải thêm/refresh | Empty feed/loading page/error retry; không nhảy scroll khi bài mới |
| PostComposerDialog | Text, privacy selector, 0–4 ảnh, progress | Disable submit đến ảnh READY; lỗi upload cho remove/retry; mặc định FRIENDS |
| PostCard/PostDetail | Author, thời gian, privacy, text, ảnh, like/count/comment | Menu edit/delete của author, report; privacy đổi thì unavailable |
| CommentPanel | List bình luận phẳng, nhập, delete đúng quyền | Tombstone actor block; pagination; không render HTML tùy ý |
| FriendsPanel | Tabs bạn bè/nhận/đã gửi, tìm user | Pending/accepted/removed; accept/reject/cancel riêng, không auto-accept |
| ProfilePanel | Avatar/bio, timeline, friend/block/report/chat | Action theo relation; profile bị block không lộ qua màn hình cũ |
| SettingsPanel | Sửa profile/avatar, đổi password, danh sách block, logout | VERSION_CONFLICT; password change logout; không có email reset giả |
| ConversationListPanel | Direct/groups, latest preview, unread | Preview kiểm quyền; user unfriend giữ conversation với composer disabled |
| ChatPanel | History, bubble, trạng thái STORED/DELIVERED/READ, attachment | Offline banner, retry stable ID, tải cũ giữ scroll anchor, không gửi trùng |
| GroupInfoDialog | Member, invites, role, rename/kick/transfer/leave | OWNER/MEMBER actions, join visibility warning, owner phải transfer/disband |
| NotificationPanel | Unread/read, mark read, mở target | Nội dung không còn khả dụng; badge refresh server; không rò preview cũ |
| ReportDialog | Reason + mô tả | Pending/success/duplicate; report không tự xóa nội dung |
| AdminPanel | Report queue/detail/action/reason, audit | Chỉ ADMIN, claim/version conflict, không màn hình đọc private chat |

## 3. Luồng chính cần chạy xuyên suốt

### Đăng ký và vào ứng dụng

Chọn server → kiểm kết nối tin cậy → đăng ký → login → load current profile/relationships/conversation/notifications metadata → main screen. Discovery OFFER không cho quyền tự trust certificate; phân phối truststore/fingerprint trước demo theo hướng dẫn kỹ thuật.

Không giữ login button pending vô hạn. Timeout cho retry có giải thích ngắn. Password field dùng JPasswordField; bỏ char[] sau request, không ghi debug. Tránh thông báo kỹ thuật stacktrace/SQL cho user.

### Bạn bè và bài đăng

A tìm B → gửi lời mời → B tab Nhận ACCEPT → hai bên friend → A post FRIENDS → B feed/detail thấy → B comment/like → A notification mở post. Với C chưa friend, A post không xuất hiện và mở ID cũ/ảnh vẫn bị từ chối.

Privacy label cạnh bài và trong composer. Khi edit ràng buộc expectedVersion conflict, tải state mới và cho người dùng áp lại nội dung của mình; không auto-overwrite thay đổi từ nơi khác.

### Chat và attachment

Từ profile/bạn bè chọn Nhắn tin → get/create conversation → tải history trang cuối → gửi text với bubble PENDING → server ACK STORED → recipient receipt đổi status. Tải history cũ giữ vị trí bằng message anchor, không kéo về cuối nếu user đang đọc cũ.

Chọn file → hiển thị tên/size → upload progress → READY → MESSAGE_SEND → recipient thấy file card. Không cần recipient online/accept để server lưu. Người nhận bấm Tải → chọn folder → progress → verify → “Đã lưu” và mở thư mục; không tự chạy file. Upload READY nhưng send fail do unfriend cho hủy/tạo lại sau khi quyền hợp lệ, không attach lén.

### Nhóm và quản trị

Owner tạo group → invite → recipient accept → thấy lịch sử từ lần join, có dòng giải thích. Khi rời/kick, xóa cache group đang xem, disable composer và tải server. Block user trong group hiển thị cảnh báo ngắn rằng vẫn gặp nội dung trong nhóm chung.

User report nội dung thấy được → admin claim → review snapshot → chọn action/lý do → commit → target status đổi, reporter/owner nhận outcome tối thiểu. Không dùng modal báo cáo để thay text của tác giả.

## 4. Trạng thái kết nối và lỗi

ConnectionState: DISCONNECTED/CONNECTING/NEGOTIATING/AUTHENTICATED/RECONNECTING/CLOSED. State service authoritative, views subscribe. Mất mạng:

1. Show banner, disable hành động cần server; vẫn có thể đọc cache đã có với dấu dữ liệu cũ.
2. Clear typing sau 5s; presence hiển thị chưa cập nhật thay vì khẳng định mọi người offline.
3. Retry connection backoff 1/2/4/8/16s, max30s + jitter; cho dừng/thử ngay. Connection phục hồi yêu cầu login, không tự lưu/replay password.
4. Sync authoritative lists/history/unread, invalidate post/media cần kiểm quyền; không báo “đã gửi” cho bubble UNKNOWN.
5. User chọn retry message thì dùng clientMessageId cũ/lookup. Nếu quyền mất, show reason, không tự tạo tin mới.

Lỗi notification/post unavailable xóa preview/text khỏi state view liên quan; cache media theo session và permission version. Logout clear private state, pending futures, UDP key, in-memory caches; xóa temp không resume được theo policy. File đã user chủ động lưu không tự xóa.

## 5. Refactor UI hiện có

- LoginFrame hiện nhập nickname/IP, port hardcode: tách server connection và account auth.
- ChatFrame hiện vừa render, gửi socket, file I/O, history RAM: chuyển message/transfer state sang service, giữ view nhận DTO.
- ChatClient listener không invokeLater tất cả frame; response/event decode ngoài EDT, chỉ UI patch invokeLater.
- Bubble/file card/Theme tái dùng; online list thay thành friend presence, không toàn user session.
- Post list/thumbnail không tạo component vô hạn; paging và cache bounded, dispose listener khi chuyển/đóng view.

## 6. Chất lượng UI cần nghiệm thu

- Keyboard: Tab đúng thứ tự, Enter gửi chat, Shift+Enter xuống dòng, Esc đóng dialog; tránh Enter gửi post khi user nhập nhiều dòng.
- Text Unicode/emoji/dấu tiếng Việt, dòng dài wrap; không parse text người dùng như HTML. UI scaling 100%/150%/200%, Windows/Linux.
- Có icon/text trạng thái cùng nhau, không dựa màu đơn thuần; font đủ đọc, dialog không mất action dưới mép màn hình.
- Slow network/file lớn vẫn kéo cửa sổ, click navigation, typing mượt. Progress cập nhật có throttle.
- Submit pending chống double-click nhưng không thay dedup server. Delete/block/disband có xác nhận trong ứng dụng vì ảnh hưởng nghiệp vụ; đây là UX của sản phẩm, không phải yêu cầu phê duyệt việc viết tài liệu.
- Empty/error có nút phù hợp: feed chưa có bài, chưa có bạn, search không kết quả, file lỗi, chưa có notification.

Screenshots/walkthrough nghiệm thu cần lấy từ app đã chạy; wireframe/tài liệu không chứng minh GUI được triển khai.
