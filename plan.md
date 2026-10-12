# Kế hoạch đồ án cuối kỳ: Mạng xã hội Java Desktop dùng Client–Server, TCP/TLS

Ngày phân tích: **07/10/2026**. Nền tảng xuất phát: thư mục `chat_message` hiện tại.

> Đây là đặc tả và kế hoạch xuất phát từ MiniChat. Bản mạng xã hội v2 đã được triển khai trong source; tiến độ, bằng chứng kiểm thử và phần nghiệm thu còn cần môi trường thực tế nằm tại [implementation-status.md](docs/implementation-status.md). Kế hoạch chat/P2P cũ được giữ nguyên trong [bản lưu](docs/archive/mini-chat-plan-2026-09.md). Khi mô tả cũ khác source, lấy source làm căn cứ.

## 1. Kết luận từ bài tập hiện tại

Có thể phát triển từ project này, không cần viết lại toàn bộ. Có sẵn Java 17, Maven multi-module common/server/client, Swing + FlatLaf, TCP JSON có length prefix, server nhiều kết nối, SQLite, chat cá nhân, logic phòng ở server và relay file. Phần đáng giữ nhất là khung kết nối, codec, module dùng chung và cách chạy hai chương trình.

Khoảng cách lớn nhất không nằm ở việc thêm vài màn hình. Hiện người dùng chỉ nhập tên, chưa có xác thực; dữ liệu và quyền hạn chưa đủ cho một mạng xã hội; chat chưa lấy lịch sử hay gửi offline; file chỉ relay khi hai bên online; chưa có UDP. Phải thêm lớp nghiệp vụ, transaction và kiểm tra quyền trước khi xây bảng tin.

**Sản phẩm đích:** mạng xã hội quy mô lớp học/LAN. Người dùng có tài khoản và hồ sơ, kết bạn, đăng bài có quyền riêng tư, xem bảng tin, bình luận/thích, nhận thông báo, nhắn tin riêng và gửi tệp lưu tại server. Bản hoàn chỉnh có nhóm chat, chặn người dùng và xử lý báo cáo cơ bản. Server quyết định danh tính, quyền và dữ liệu bền vững.

## 2. Bộ tài liệu để triển khai

| Đọc | Tài liệu | Trả lời câu hỏi |
|---|---|---|
| 1 | [Hiện trạng và thiếu sót](docs/01-current-state.md) | Source thật có gì, lỗi nào phải xử lý trước? |
| 2 | [Nghiệp vụ và phân quyền](docs/02-business-rules.md) | Mỗi chức năng được phép hoạt động thế nào? |
| 3 | [Kiến trúc và vận hành](docs/03-architecture.md) | Tách lớp, thread, TCP/UDP, storage, bảo mật ra sao? |
| 4 | [Mô hình dữ liệu](docs/04-data-model.md) | Bảng, khóa, index, transaction và migration nào cần có? |
| 5 | [Giao thức phiên bản 2](docs/05-protocol-v2.md) | Request/response/event, lỗi, ACK, retry, UDP thế nào? |
| 6 | [Giao diện Desktop](docs/06-desktop-ux.md) | Màn hình, trạng thái lỗi và luồng sử dụng thế nào? |
| 7 | [Backlog và thứ tự triển khai](docs/07-implementation-backlog.md) | Làm task nào trước, đầu ra và điều kiện xong là gì? |
| 8 | [Kiểm thử, đóng gói, demo](docs/08-testing-release.md) | Chứng minh đúng nghiệp vụ và đúng môn mạng thế nào? |

[docs/protocol.md](docs/protocol.md) tiếp tục mô tả MiniChat v1 đang có; không dùng nó làm hợp đồng cho mạng xã hội v2.

## 3. Giả định và quyết định phạm vi

- Giữ **Java 17 + Swing + FlatLaf + Maven**, một server trung tâm; không chuyển framework UI trong lúc nâng cấp nghiệp vụ.
- Giữ **SQLite** cho một server, mục tiêu nghiệm thu 20 client online và kiểm tra tải 50 connection. Đây là mục tiêu phải đo, chưa phải năng lực đã chứng minh.
- Dùng TCP/TLS cho toàn bộ nghiệp vụ: client nhập IP/hostname, TCP port, tài khoản và mật khẩu; request, event, Unicast, Group và Broadcast đều đi qua server TCP.
- File, avatar và ảnh bài đăng đi **Client → Server → Client**, lưu bền vững ở server; không tiếp tục P2P. Lưu tại server là thay đổi so với relay MiniChat.
- Một tài khoản có tối đa một phiên đang hoạt động. Thiết bị khác được báo ALREADY_SIGNED_IN; không âm thầm đá phiên cũ.
- Feed theo thời gian mới nhất, phân trang cursor; chưa làm gợi ý.
- Quan hệ **bạn bè hai chiều**, không triển khai đồng thời follow/follower.
- Bình luận một cấp, một loại reaction LIKE. Bài đăng văn bản và tối đa 4 ảnh. Không video, stories, livestream, thanh toán hay end-to-end encryption.
- Có admin ứng dụng với quyền riêng; dashboard server hiện có không thay thế phân quyền admin.
- Chưa có thời hạn/nhân lực/rubric giảng viên nên lộ trình theo mốc và phụ thuộc, không đặt ngày nộp giả định. Cần đối chiếu rubric trước khi mở rộng P2.

## 4. Mức độ ưu tiên

**P0 — bản cốt lõi chạy xuyên suốt:** protocol v2/TLS, tài khoản, hồ sơ, bạn bè, chặn, bài đăng và privacy, feed, like/comment, chat riêng có history/offline, file lưu server, thông báo bền vững, UDP discovery, kiểm thử và đóng gói.

**P1 — bản hoàn chỉnh của kế hoạch này:** nhóm chat theo lời mời và vai trò, read receipt, UDP typing có xác thực, báo cáo/admin, phục hồi kết nối và file, backup/restore, tải và kiểm tra quyền đầy đủ. Muốn tuyên bố hoàn thành theo tài liệu này phải xong cả P0 và P1.

**P2 — nâng cao tùy thời gian:** tìm nội dung toàn văn, nhiều reaction, comment nhiều cấp, offline draft, dark mode, PostgreSQL, nhiều phiên, gọi thoại/video. Không trì hoãn P0/P1 để làm P2.

## 5. Lộ trình theo cổng nghiệm thu

| Mốc | Kết quả review được | Điều kiện chuyển mốc |
|---|---|---|
| M0 | Snapshot bài tập, audit, source/test hiện tại được ghi nhận | Phân biệt tính năng có thật với kế hoạch cũ |
| M1 | Protocol v2, TLS, lifecycle socket, migration DB, transaction, queue | Frame lỗi/connection idle/stop server không làm treo hoặc rò tài nguyên |
| M2 | Đăng ký/đăng nhập, profile, friend/block | Không chiếm danh tính, request giả không vượt quyền |
| M3 | Posts/feed/comments/likes/ảnh | Privacy đúng trên feed, detail, search, event và tải ảnh |
| M4 | Chat riêng/history/offline, notification, attachment | Restart giữ dữ liệu, retry không tạo bản sao, file đúng SHA-256 |
| M5 | UDP discovery + typing, reconnect, nhóm, admin/report | UDP mất/giả mạo không phá dữ liệu; quyền nhóm/admin đúng |
| M6 | QA cuối, release, backup/restore, báo cáo và demo nhiều máy | Test bắt buộc và test âm tính đạt |

Mốc là kế hoạch, không phải báo cáo tiến độ. Cập nhật checkbox trong backlog theo bằng chứng thực tế.

## 6. Các lỗi nền phải sửa sớm

1. Thay JOIN nhập tên bằng password hash; không tự chuyển tên cũ thành tài khoản có thể chiếm.
2. Đếm mọi socket, giới hạn worker/queue, timeout trước đăng nhập và heartbeat; đóng socket trước DB.
3. Tách ClientHandler khỏi CRUD/nghiệp vụ; mỗi mutation kiểm tra quyền và transaction.
4. ACK chat có ID tin đã commit; thêm idempotency và history. Ghi socket thành công chưa nghĩa người nhận đã nhận/đọc.
5. Bỏ forward file tùy ý theo username; quản lý transfer, participant, offset, quota, checksum và trạng thái.
6. Dùng cùng policy privacy cho mọi đường đọc, media, notification và realtime event.

Chi tiết và dẫn chứng source nằm trong tài liệu hiện trạng.

## 7. Cách triển khai

- Làm một lát cắt đầy đủ DTO → handler → service → transaction/repository → response → client UI → test; tránh làm tất cả màn hình trước rồi mới nối server.
- Task gắn requirement/test ID. Không tick xong chỉ vì compile được hoặc một happy path chạy.
- Thay nghiệp vụ phải sửa business rules, data model, protocol và test liên quan cùng đợt.
- Server kiểm tra mọi quyền. Ẩn nút client chỉ hỗ trợ trải nghiệm.
- Giữ một bản demo ổn định mỗi mốc. Không đổi toàn bộ package com.p2p chỉ để đẹp tên trước khi chức năng hoàn thành.

## 8. Định nghĩa hoàn thành cuối kỳ

- Hai client trên hai môi trường/máy khác nhau dùng cùng server; dữ liệu giữ sau restart.
- Đăng nhập có xác thực, user không sửa/xem trái phép dữ liệu của người khác.
- Luồng đầy đủ: đăng ký → kết bạn → đăng bài → like/comment → thông báo → chat offline → tải attachment.
- Có chương trình UDP thật, capture/log, demo mất gói và fallback; không chỉ ghi UDP trong báo cáo.
- Retry, ngắt mạng, offline, privacy đổi, block, session hết hạn có hành vi thống nhất.
- Release chạy theo hướng dẫn, có migration/backup/restore, test, dữ liệu demo giả lập, báo cáo kiến trúc và giao thức.
- P0 + P1 trong backlog và tiêu chí bắt buộc trong tài liệu kiểm thử đạt; P2 ghi rõ ngoài phạm vi.

## 9. Rủi ro và cách giảm

| Rủi ro | Cách xử lý |
|---|---|
| Quá nhiều chức năng | Hoàn thiện P0 theo luồng end-to-end; P1 sau; bỏ P2 khi chậm |
| SQLite lock/transaction sai | Writer tuần tự hóa, service transaction atomic, index và test race |
| UI treo khi tải ảnh/gửi file | Worker nền, tải theo trang, thumbnail cache, cập nhật EDT |
| File lớn nghẽn chat | Chunk nhỏ, ACK theo cửa sổ, ưu tiên control, giới hạn 25 MiB |
| Firewall/VM không nhận broadcast | IP nhập tay, unicast probe; typing có thể bỏ qua |
| Rò nội dung qua cache/event | Policy server từng lần đọc và tại dispatch, invalidate UI khi quyền đổi |
| Tài liệu cũ gây nhầm | Archive, đánh dấu v1/v2, checklist theo source |

**Việc bắt đầu khi triển khai:** M1 nền tảng, rồi M2 auth/profile/friend/block. Không xây feed lên cơ chế JOIN tên hiện tại.
