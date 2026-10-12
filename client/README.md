# Kết Nối — client mạng xã hội v2

`ClientMain` mở Swing / FlatLaf với tài khoản thật trên TCP/TLS. Chạy từ thư mục gốc sau khi build và chuẩn bị chứng chỉ theo [hướng dẫn gốc](../README.md):

```text
java -jar client/target/client-1.0.0-SNAPSHOT-shaded.jar
```

PowerShell: `./scripts/run-client.ps1`; Linux: `./scripts/run-client.sh`. Script Linux có sẵn nhưng Linux/VM/DPI chưa được nghiệm thu trực tiếp; xem [bằng chứng và giới hạn kiểm thử](../docs/implementation-status.md).

Nhập host/TCP port (mặc định 5000), tên đăng nhập và mật khẩu. **Thiết lập TLS** mở truststore/mật khẩu truststore; mặc định `config/tls/truststore.p12`. `SOCIAL_TRUSTSTORE_PASSWORD` ưu tiên hơn `SOCIAL_TLS_PASSWORD`; mật khẩu truststore demo mặc định `changeit`. Có thể đổi đường dẫn bằng `-Dsocial.truststore=...`. Chứng chỉ do người vận hành phân phối trước; không có tùy chọn bỏ xác minh. Tìm server LAN qua UDP 5001 chỉ gợi ý địa chỉ, sau đó vẫn xác minh TLS và đối chiếu serverId. Khi broadcast bị chặn, nhập IP/port thủ công.

Đăng ký cần username, tên hiển thị và password 10–128 ký tự; không tự đăng nhập. Tài khoản demo (`demo_alice`, `demo_bob`…) dùng mật khẩu người vận hành đặt trong `SOCIAL_DEMO_PASSWORD` lúc seed; không có mật khẩu tài khoản cố định trong source. Client không lưu password. Mất mạng sẽ thử lại TLS theo backoff rồi yêu cầu login lại. Logout xóa state riêng và cache thumbnail trong bộ nhớ.

Điều hướng có bảng tin, bạn bè/lời mời/tìm người, tin nhắn/nhóm, thông báo, hồ sơ/cài đặt; ADMIN có hàng đợi báo cáo và nhật ký. Bài mặc định Bạn bè, tối đa 4 ảnh; quyền xem do server quyết định. Thumbnail chạy nền, cache tối đa 64 ảnh theo phiên. Bảng tin báo có cập nhật để người dùng làm mới, tránh chèn bài mới làm vị trí đọc nhảy.

Chat hiện đã lưu sau ACK server, phân biệt đã nhận/đã đọc và số thành viên đọc trong nhóm. Enter gửi; Shift+Enter xuống dòng. Tin chưa xác định kết quả giữ `clientMessageId` và idempotency key; thử lại tra cứu trước để tránh gửi trùng. Đổi password thu hồi phiên và yêu cầu login lại. Nhóm chỉ hiển thị lịch sử từ lần tham gia; chặn không ẩn tin trong nhóm chung. Chủ nhóm chuyển quyền hoặc giải tán trước khi rời nhóm còn thành viên khác.

Tệp/ảnh/avatar được upload lên server trước khi gắn vào nội dung. Chat tối đa 25 MiB; ảnh/avatar 5 MiB, tối đa 20 megapixel. Progress và SHA-256 chạy nền. **Tiếp tục tải tệp** phục hồi upload trong cùng lần chạy ứng dụng, kể cả INIT có kết quả chưa biết. Download lưu `.part` và metadata cạnh tệp đích; chọn lại cùng tệp để tiếp tục sau gián đoạn. Chỉ đổi tên thành tệp đích sau kiểm tra kích thước, SHA-256 và ACK VERIFIED. Tải xong có nút mở thư mục; client không tự chạy tệp. Tệp chủ động lưu vẫn giữ sau logout. Xem ảnh dùng cache tạm theo phiên.

Kiểm thử: `mvn -pl client -am test` kiểm correlation/event interleave, invalid session, EOF cleanup, retry attachment với caption/mã cố định, logout, cache hữu hạn, checksum và paint Swing ở 1120×780 / 900×620. [ReleaseSmoke.java](../scripts/qa/ReleaseSmoke.java) dùng hai JAR đã đóng gói, server seed thật, TLS/UDP thật và paint UI kết nối dữ liệu thật; không thay thế walkthrough nhiều máy hay Linux/DPI.
