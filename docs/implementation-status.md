# Trạng thái triển khai hiện tại — TCP/IP

Cập nhật 12/10/2026. Server và client dùng Socket/ServerSocket TCP thường, mặc định port 5000; UDP tắt. Thành phần mã hóa TLS và script chứng chỉ đã được gỡ khỏi source hiện tại. Form client có IP/hostname, port, tài khoản và mật khẩu.

## Chạy trên Windows

Build lần đầu hoặc sau khi đổi source bằng `.\scripts\build-all.ps1`, sau đó chạy server `.\scripts\run-server.ps1` và client `.\scripts\run-client.ps1` ở hai cửa sổ. Máy ảo Windows dùng cùng script client và nhập IP máy server. Chi tiết: [09-run-windows-vm.md](09-run-windows-vm.md).

## Phần đã triển khai

| Phần | Source và kiểm thử |
|---|---|
| TCP framing/session | WireCodec/WireMessage v2, HELLO, request correlation, heartbeat, giới hạn kết nối, writer queue, timeout/cleanup; SocialProtocolTest, ProtocolRuntimeTest, SocialTransportTest |
| Tài khoản và quyền | PBKDF2, phiên đơn, auth_version/revoke, SQLite transaction/outbox, profile/friends/block/privacy; SocialServiceTest, SessionPrivacyRuntimeTest, SocialDatabaseTest |
| Mạng xã hội | Bài viết/feed/like/comment, media ACL, thông báo, nhóm theo membership period, quản trị/report/audit |
| Ba chế độ chat TCP | Unicast qua direct conversation; Group theo thành viên; BROADCAST_SEND phát BROADCAST_MESSAGE tới các tài khoản online |
| Truyền tệp | Upload/download, quota, SHA-256, ACK/resume, thumbnail; MediaServiceTest, MediaTransferStateTest, ThumbnailCacheTest |
| Desktop | Swing/FlatLaf, form IP/port/auth, feed/people/chat/group/broadcast/notifications/settings/admin; SocialDesktopStateTest |
| Vận hành | Seed, admin bootstrap, backup/restore có lock/manifest/hash; BackupToolTest; script chạy và đóng gói Windows/Linux |

## Bằng chứng kiểm thử

Maven `clean` rồi `package` đã PASS ngày 12/10/2026: **75 test = common 4 + server 59 + client 12; 0 failure/error/skipped**, chạy với `-DargLine=-Xmx512m`. Hai kiểm thử mã hóa cũ đã bị xoá nên tổng giảm từ 77 xuống 75. Các kiểm thử mạng server đã chuyển sang Socket TCP; không dùng chứng chỉ. UDP chỉ bật rõ ràng trong fixture cũ chuyên kiểm thử UDP, không bật trong cấu hình chạy đồ án.

LoadRuntimeTest vẫn kiểm 20 tài khoản, 10.000 bài viết, 50.000 tin nhắn, 2 tệp 25 MiB, giới hạn 50 kết nối, socket dư bị đóng, slow reader và shutdown/thread cleanup. Báo cáo lần chạy mới ở `server/target/qa/performance.json`; không áp số đo bản cũ cho TCP.

Bằng chứng ngày 07/10/2026 được giữ riêng ở [bản lưu](archive/implementation-status-2026-10-07.md). Các smoke UDP/bản cũ và ảnh cũ chỉ chứng minh phiên bản tại thời điểm đó, không thay thế kết quả bản TCP hiện tại.

## Phần cần môi trường thực tế

Chạy nhiều máy/VM và walkthrough giao diện do người dùng thực hiện. Linux desktop/DPI, mất mạng dài, crash từng điểm storage, disk-full và retention dài hạn chưa được xác nhận đầy đủ. Không đánh toàn bộ checklist nghiệp vụ/UI/LAN là hoàn thành từ kết quả Maven localhost.
