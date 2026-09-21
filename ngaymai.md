# Chạy lại MiniChat vào ngày mai

Tài liệu này dùng cho mô hình:

- Server chạy trên Windows.
- Một hoặc nhiều client chạy trên Windows.
- Một client chạy trong Ubuntu VirtualBox.
- Client Windows cùng máy server dùng `127.0.0.1`.
- Client Ubuntu dùng IP VirtualBox Host-only của Windows, thường là
  `192.168.56.1`.

## Tắt chương trình đúng cách hôm nay

### 1. Tắt client Ubuntu

Đóng cửa sổ MiniChat trong Ubuntu, sau đó mở Terminal và chạy:

```bash
sudo poweroff
```

Chờ VirtualBox hiển thị trạng thái **Powered Off** rồi mới đóng VirtualBox.
Không dùng **Power Off the machine** trừ khi Ubuntu bị treo.

### 2. Tắt client Windows

Đóng tất cả cửa sổ MiniChat đang chạy trên Windows.

### 3. Tắt server Windows

Quay lại terminal đang chạy server và nhấn:

```text
Ctrl+C
```

## Ngày mai: cập nhật source trên Windows

Mở PowerShell:

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
git pull origin main
```

Nếu Git tải về source mới, build lại:

```powershell
.\scripts\build-all.ps1
```

Nếu source không thay đổi và JAR đã tồn tại thì có thể bỏ qua bước build.

## Ngày mai: mở server Windows

Chạy server trước tất cả client:

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
.\scripts\run-server.ps1
```

Khi thấy dòng sau là server đã sẵn sàng:

```text
P2P Chat server listening on port 5000
```

Giữ nguyên terminal server, không đóng trong lúc chat.

## Ngày mai: mở client Windows

Mở PowerShell mới:

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
.\scripts\run-client.ps1
```

Nếu muốn nhiều client Windows, chạy lệnh trên trong nhiều terminal khác nhau.

Thông tin kết nối cho client Windows cùng máy server:

```text
Tên: đặt khác nhau cho từng client
IP Server: 127.0.0.1
```

## Ngày mai: bật Ubuntu VirtualBox

1. Mở Oracle VirtualBox.
2. Chọn máy ảo Ubuntu.
3. Nhấn **Start**.
4. Đăng nhập Ubuntu.
5. Mở Terminal.

## Cập nhật source trong Ubuntu VM

```bash
cd ~/Chat-CS-P2P
git pull origin main
```

Sau khi pull source mới, build lại:

```bash
chmod +x scripts/*.sh
./scripts/build-all.sh
```

Nếu Git báo `Already up to date` và đã build trước đó thì có thể bỏ qua build.

## Mở client Ubuntu

```bash
cd ~/Chat-CS-P2P
./scripts/run-client.sh
```

Thông tin kết nối:

```text
Tên: Linux-VM
IP Server: 192.168.56.1
```

## Cách xác định IP `192.168.56.1`

Trên Windows chạy:

```powershell
ipconfig
```

Tìm adapter có tên tương tự:

```text
Ethernet adapter VirtualBox Host-Only Network
```

Lấy giá trị ở dòng `IPv4 Address`. Ví dụ:

```text
192.168.56.1
```

Nếu IPv4 khác thì nhập IP mới đó trong client Ubuntu.

## Kiểm tra kết nối từ Ubuntu

Kiểm tra Windows có phản hồi:

```bash
ping -c 4 192.168.56.1
```

Kiểm tra server port `5000`:

```bash
nc -vz 192.168.56.1 5000
```

Kết quả đúng có chữ:

```text
succeeded
```

Nếu `ping` được nhưng port `5000` không kết nối được:

1. Kiểm tra server Windows đã chạy chưa.
2. Kiểm tra Windows Firewall có cho phép TCP port `5000` chưa.
3. Kiểm tra VirtualBox vẫn bật Host-only Adapter.

## Lệnh ngắn dùng mỗi ngày

### Windows server

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
git pull origin main
.\scripts\build-all.ps1
.\scripts\run-server.ps1
```

### Windows client

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
.\scripts\run-client.ps1
```

### Ubuntu client

```bash
cd ~/Chat-CS-P2P
git pull origin main
./scripts/build-all.sh
./scripts/run-client.sh
```

## Lưu ý

- Luôn chạy server trước client.
- Mỗi client phải nhập một tên khác nhau.
- Không dùng `127.0.0.1` trong Ubuntu VM vì IP đó trỏ tới chính Ubuntu.
- IP Host-only thường cố định, nhưng vẫn có thể kiểm tra lại bằng `ipconfig`.
- Chỉ cần build lại khi source thay đổi hoặc thư mục `target` bị xóa.
