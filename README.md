# Kết Nối — Mạng xã hội Java Desktop

Ứng dụng mạng xã hội quy mô lớp học/LAN, phát triển từ bài tập MiniChat. Bản chạy mặc định dùng **Java 17, Swing/FlatLaf, Client–Server, TCP/TLS, UDP và SQLite**. Mọi dữ liệu và quyền truy cập được quyết định tại server.

Đã có tài khoản, hồ sơ/avatar, tìm người/kết bạn/chặn, bài viết có privacy, bảng tin, thích/bình luận, thông báo, chat riêng/nhóm, Broadcast tới tất cả client đang online, lịch sử offline và receipt, truyền file có checksum/resume, báo cáo và quản trị. Đối chiếu triển khai và bằng chứng kiểm thử trong [docs/implementation-status.md](docs/implementation-status.md). Các tài liệu [plan.md](plan.md) và `docs/01` đến `docs/08` giữ đặc tả nghiệp vụ để tiếp tục phát triển.

## Chạy trên Windows

Hướng dẫn server Windows + một client local + client máy ảo (Windows/Linux), đồng bộ Git và TLS LAN: [docs/09-run-windows-vm.md](docs/09-run-windows-vm.md).

Cần JDK 17+; Maven 3.9+ để build. Mở PowerShell tại thư mục này:

```powershell
.\scripts\build-all.ps1
.\scripts\init-local.ps1
.\scripts\run-server.ps1
```

Trong cửa sổ PowerShell khác:

```powershell
.\scripts\run-client.ps1
```

Client dùng `localhost:5000`. Bấm **Tạo tài khoản**, rồi đăng nhập. Có thể mở nhiều cửa sổ client và dùng các tài khoản khác nhau; mỗi tài khoản có một phiên hoạt động. Server có dashboard và có thể chạy không giao diện bằng `run-server.ps1 --no-ui`.

`init-local.ps1` tạo chứng chỉ tự ký cho demo local, truststore và mật khẩu ngẫu nhiên tại `config/tls/password.local`. Các script chạy tự đọc mật khẩu trên cùng máy. Script không ghi đè chứng chỉ đã có. Không đưa `server.p12`, `password.local` hoặc dữ liệu thật vào source/release.

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

Địa chỉ client nhập phải nằm trong SAN của chứng chỉ. Trước lần khởi tạo đầu tiên, ví dụ máy server dùng `192.168.1.15`:

```powershell
.\scripts\init-local.ps1 -LanHost '192.168.1.15'
```

Máy client nhận **chỉ** `config/tls/truststore.p12` qua kênh tin cậy và mật khẩu truststore. Nhập IP server, chọn truststore và nhập mật khẩu ở màn hình đăng nhập, hoặc đặt `SOCIAL_TRUSTSTORE_PASSWORD`. Nếu SAN/hostname hoặc truststore không khớp, TLS từ chối kết nối.

Mở cổng theo cấu hình trên mạng Private: TCP `5000`, UDP discovery `5001`, UDP typing `5002`. UDP bị chặn vẫn dùng được ứng dụng bằng IP nhập tay; typing/discovery không làm mất chat đã lưu. Không tự thay firewall trong script.

Trên Linux có Java 17 và desktop environment:

```bash
export SOCIAL_TRUSTSTORE_PASSWORD='your-truststore-password'
bash scripts/run-client.sh
```

Server Linux dùng `SOCIAL_TLS_PASSWORD`, `bash scripts/run-server.sh --no-ui`; tạo chứng chỉ bằng `scripts/setup-tls.sh` trước. Source Java và script Linux được cung cấp; kết quả chạy thực tế trên Windows, Linux và nhiều máy được ghi riêng trong tài liệu kiểm thử, không suy từ compile.

## Backup và restore

Dừng server; khóa vận hành sẽ từ chối backup/restore khi database đang được server sử dụng. Backup gồm database, media và manifest SHA-256; không chứa mật khẩu/chứng chỉ TLS.

```powershell
.\scripts\backup-data.ps1 -Destination 'backups/demo-20261007'
.\scripts\restore-data.ps1 -Backup 'backups/demo-20261007' -NewDataRoot 'data-restored'
```

Đích backup/restore phải chưa tồn tại. Sau restore, chỉnh `database.path=data-restored/social-network.db`, `storage.root=data-restored/media` trong `server/config/social.properties` trước khi khởi động. Tool kiểm tra checksum, SQLite integrity và foreign keys. Giữ bản dữ liệu gốc cho đến khi xác minh bản phục hồi.

## Đóng gói

```powershell
.\scripts\package-release.ps1
```

Thư mục `release/social-v2-...` có hai JAR, cấu hình, script, tài liệu, source và `checksums.sha256`. Gói mặc định không chứa private key, truststore, mật khẩu, database, upload hay Maven cache. Khởi tạo TLS tại máy server đích; phân phối truststore cho máy client sau đó. `-IncludeClientTruststore` chỉ dùng khi muốn kèm truststore công khai đã có cho client.

## Cấu trúc và vận hành

```text
common/                         Frame v2, JSON, TLS, UDP HMAC
server/.../social/              Domain, SQLite, TCP/TLS runtime, media
server/.../network/social/      UDP và dashboard
server/.../ops/                 Seed, backup/restore, khóa vận hành
client/.../network/social/      Async request/event và UDP
client/.../ui/social/           Giao diện, truyền file, thumbnail cache
server/config/social.properties Cổng, database, storage, quota
scripts/                        Build/chạy/TLS/backup/release
```

Database mới là `data/social-network.db`, media trong `data/media`, log xoay vòng tại `logs`. DB MiniChat cũ không tự chuyển thành tài khoản v2. Mạng xã hội dùng giao thức v2; [docs/protocol.md](docs/protocol.md) chỉ mô tả bài tập MiniChat trước đây. Source legacy vẫn còn để tham khảo, không phải entry point của JAR mặc định.

Giới hạn mặc định: 50 kết nối kể cả chưa xác thực; ảnh 5 MiB và 20 megapixel; attachment 25 MiB; quota 200 MiB/user, 10 GiB/server. Có giới hạn thao tác, hàng đợi, timeout và kiểm tra privacy ở cả tải media/event. Xem [giao thức](docs/05-protocol-v2.md), [nghiệp vụ](docs/02-business-rules.md), [kiểm thử và demo](docs/08-testing-release.md).
