# MiniChat — Swing client

Client hiện là bản tối giản để kiểm tra kết nối với server trên cùng máy hoặc trong cùng Wi-Fi:

- Nhập tên hiển thị và IP server (cổng mặc định 5000).
- Tham gia ngay, không cần đăng ký hoặc mật khẩu.
- Xem danh sách người dùng online.
- Chat riêng giữa hai client.
- Gửi file qua Server theo chunk 48 KB.
- File đến hiển thị thành thẻ trong cuộc trò chuyện, không bật hộp thoại làm gián đoạn.
- Chỉ bắt đầu nhận khi người dùng bấm **Tải xuống**; hiển thị tiến độ và kiểm tra SHA-256.
- Sau khi tải thành công, nút **Mở file** xuất hiện ngay trên thẻ file.
- Lưu file nhận vào thư mục `downloads`.

## Giao diện

Client dùng FlatLaf và bố cục lấy cảm hứng từ các ứng dụng nhắn tin phổ biến:
sidebar bên trái có tài khoản, tìm kiếm và danh sách hội thoại; vùng giữa có header
người đang chat, trạng thái LAN, bong bóng tin nhắn trái/phải và composer ở đáy.
Các thao tác gửi tin, đính kèm, tải xuống, mở file và làm mới dùng icon vector
đồng nhất; các nút, ô nhập và thanh tiến độ được bo tròn mềm hơn.
Giao diện dùng tên và nhận diện riêng `MiniChat`, không sao chép logo hoặc tài sản
thương hiệu của Zalo/Messenger.

Chạy sau khi build:

    java -jar client/target/client-1.0.0-SNAPSHOT-shaded.jar

Trên Linux có thể chạy từ thư mục project bằng:

    ./scripts/run-client.sh

Trên cùng máy dùng 127.0.0.1. Trên máy khác dùng địa chỉ IPv4 LAN của máy chạy server, ví dụ 192.168.1.15, và mở TCP port 5000 trong Windows Firewall.
