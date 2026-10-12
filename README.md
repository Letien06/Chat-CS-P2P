# Kết Nối — Mạng xã hội Java Desktop

Ứng dụng mạng xã hội quy mô lớp học/LAN, phát triển từ bài tập MiniChat. Bản chạy mặc định dùng **Java 17, Swing/FlatLaf, Client–Server, TCP/IP và SQLite**. Client nhập địa chỉ IP máy chủ, TCP port, tài khoản và mật khẩu trên form đăng nhập.

Đã có tài khoản, hồ sơ/avatar, tìm người/kết bạn/chặn, bài viết có privacy, bảng tin, thích/bình luận, thông báo, chat riêng/nhóm, Broadcast tới tất cả client đang online, lịch sử offline và receipt, truyền file có checksum/resume, báo cáo và quản trị. Đối chiếu triển khai và bằng chứng kiểm thử trong [docs/implementation-status.md](docs/implementation-status.md). Các tài liệu [plan.md](plan.md) và `docs/01` đến `docs/08` giữ đặc tả nghiệp vụ để tiếp tục phát triển.

## Chạy trên Windows

Hướng dẫn server Windows + một client local + client máy ảo (Windows/Linux), đồng bộ Git và TCP LAN: [docs/09-run-windows-vm.md](docs/09-run-windows-vm.md).

Cần JDK 17+; Maven 3.9+ để build. Mở PowerShell tại thư mục này:

```powershell
.\scripts\build-all.ps1
.\scripts\run-server.ps1
```

Trong cửa sổ PowerShell khác:

```powershell
.\scripts\run-client.ps1
```

Client dùng `127.0.0.1:5000` khi server ở cùng máy. Bấm **Tạo tài khoản**, rồi đăng nhập. Có thể mở nhiều cửa sổ client và dùng các tài khoản khác nhau; mỗi tài khoản có một phiên hoạt động. Server có dashboard và có thể chạy không giao diện bằng `run-server.ps1 --no-ui`.

Server mở TCP port 5000; client nhập địa chỉ IP máy chủ và port trên form.

Build offline khi đã có Maven cache:

```powershell
mvn -Dmaven.repo.local=.m2-local -o package
```

Nếu Maven chưa có trong PATH, `build-all.ps1` tìm bản bundled của IntelliJ. Build chạy kiểm thử cả ba module, gồm bài kiểm tra tải, nên mất lâu hơn chỉ compile.

## Dữ liệu demo và admin

Dừng server trước khi seed. Seed yêu cầu database mới/chưa có user và không thay dữ liệu đang dùng:

```powershell
$env:SOCIAL_DEMO_PASSWORD = 'Demo-Password-123!'
.\scripts\seed-demo.ps1
.\scripts\run-server.ps1
```

Có sáu tài khoản giả lập: `demo_admin`, `demo_alice`, `demo_bob`, `demo_carol`, `demo_dave`, `demo_eve`. Tất cả dùng mật khẩu bạn vừa đặt, có sẵn bạn bè, bài viết, nhóm và tệp mẫu. Chỉ dùng mật khẩu ví dụ cho dữ liệu demo.

Tạo admin cho database riêng mà không seed:

```powershell
$env:SOCIAL_ADMIN_USERNAME = 'admin_class'
$env:SOCIAL_ADMIN_PASSWORD = 'Replace-With-Your-Password!'
.\scripts\run-server.ps1 --create-admin
.\scripts\run-server.ps1
```

Không tự cấp quyền admin cho tài khoản đăng ký thông thường.

## Chạy qua LAN hoặc Linux

Máy client chỉ cần source/JAR và địa chỉ IP của máy server. Nhập IP server, port, tài khoản và mật khẩu trên màn hình đăng nhập.

Mở cổng TCP `5000` theo cấu hình trên mạng Private. Client kết nối trực tiếp bằng IP/hostname và port trên form; không cần mở thêm cổng UDP.

Trên Linux có Java 17 và desktop environment:

```bash
bash scripts/run-client.sh 192.168.1.15 5000
```

Server Linux dùng `bash scripts/run-server.sh --no-ui`. Source Java và script Linux được cung cấp.

## Backup và restore

Dừng server; khóa vận hành sẽ từ chối backup/restore khi database đang được server sử dụng. Backup gồm database, media và manifest SHA-256; không chứa mật khẩu.

```powershell
.\scripts\backup-data.ps1 -Destination 'backups/demo-20261007'
.\scripts\restore-data.ps1 -Backup 'backups/demo-20261007' -NewDataRoot 'data-restored'
```

Đích backup/restore phải chưa tồn tại. Sau restore, chỉnh `database.path=data-restored/social-network.db`, `storage.root=data-restored/media` trong `server/config/social.properties` trước khi khởi động. Tool kiểm tra checksum, SQLite integrity và foreign keys. Giữ bản dữ liệu gốc cho đến khi xác minh bản phục hồi.

## Đóng gói

```powershell
.\scripts\package-release.ps1
```

Thư mục `release/social-v2-...` có hai JAR, cấu hình, script, tài liệu, source và `checksums.sha256`. Gói mặc định không chứa mật khẩu, database, upload hay Maven cache.

## Cấu trúc và vận hành

```text
common/                         Frame v2 và JSON
server/.../social/              Domain, SQLite, TCP runtime, media
server/.../network/social/      UDP và dashboard
server/.../ops/                 Seed, backup/restore, khóa vận hành
client/.../network/social/      Async request/event và UDP
client/.../ui/social/           Giao diện, truyền file, thumbnail cache
server/config/social.properties Cổng, database, storage, quota
scripts/                        Build/chạy/backup/release
```

Database mới là `data/social-network.db`, media trong `data/media`, log xoay vòng tại `logs`. DB MiniChat cũ không tự chuyển thành tài khoản v2. Mạng xã hội dùng giao thức v2; [docs/protocol.md](docs/protocol.md) chỉ mô tả bài tập MiniChat trước đây. Source legacy vẫn còn để tham khảo, không phải entry point của JAR mặc định.

Giới hạn mặc định: 50 kết nối kể cả chưa xác thực; ảnh 5 MiB và 20 megapixel; attachment 25 MiB; quota 200 MiB/user, 10 GiB/server. Có giới hạn thao tác, hàng đợi, timeout và kiểm tra privacy ở cả tải media/event. Xem [giao thức](docs/05-protocol-v2.md), [nghiệp vụ](docs/02-business-rules.md), [kiểm thử và demo](docs/08-testing-release.md).
