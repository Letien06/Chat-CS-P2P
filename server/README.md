# Server mạng xã hội v2

Entry point của JAR là `com.p2p.server.social.SocialServer`. Từ thư mục gốc chạy `scripts/run-server.ps1`. `--no-ui` chạy không dashboard; `--create-admin` tạo admin bằng `SOCIAL_ADMIN_USERNAME` và `SOCIAL_ADMIN_PASSWORD` rồi thoát.

Cấu hình tại `server/config/social.properties`. Đường dẫn dữ liệu tương đối với `app.root` tính từ vị trí file cấu hình. Database v2 riêng `data/social-network.db`; không tự nhận tài khoản từ DB MiniChat cũ.

TCP phục vụ request/response/event có length prefix và correlation ID. Bản chạy mặc định đặt `network.udp.enabled=false`, chỉ mở TCP port 5000; client nhập IP/port trực tiếp. UDP cũ chỉ giữ trong mã tương thích kiểm thử.

`SocialService` kiểm tra quyền và thực hiện transaction; `SocialDatabase` có một writer và hai reader, WAL/FULL, migrations và indexes. Domain events được ghi outbox cùng mutation. `MediaService` quản lý quota, transfer, SHA-256, chunk/ACK/resume, thumbnail và ACL khi tải. Filesystem không nằm trong SQLite transaction; commit/recovery có state và ID ổn định cho retry.

Để backup/restore/seed, dừng server và dùng các script từ thư mục gốc. `ApplicationLock` ngăn tool và server cùng mở dữ liệu đang vận hành. Server đóng socket và worker trước database; logs không ghi password hay nội dung chat.

Chi tiết chạy LAN, tạo bản release nằm tại [README gốc](../README.md). Hợp đồng giao thức ở [docs/05-protocol-v2.md](../docs/05-protocol-v2.md), bằng chứng thực tế ở [docs/implementation-status.md](../docs/implementation-status.md).

Source `ServerMain`, `ClientHandler`, `ChatDatabase` cũ thuộc MiniChat v1, được giữ làm nền lịch sử và có kiểm thử tương thích riêng.
