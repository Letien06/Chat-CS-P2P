# Chạy server Windows và client TCP/IP

Mặc định đồ án chỉ mở TCP port `5000`; không cần chứng chỉ hoặc UDP.

## Máy Windows chạy server

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
.\scripts\build-all.ps1
.\scripts\run-server.ps1
```

Server lắng nghe trên mọi địa chỉ mạng ở TCP port 5000. Lấy IP bằng `ipconfig`, ví dụ `192.168.1.15`. Nếu Windows Firewall hỏi, cho phép Java trong mạng Private; hoặc mở port:

```powershell
# Chạy trong PowerShell Administrator nếu cần tạo rule
New-NetFirewallRule -DisplayName 'VKU Social TCP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000 -Profile Private
```

## Client Windows cùng máy

```powershell
cd "D:\AVKU\Lập trình mạng\chat_message"
.\scripts\run-client.ps1
```

Form client hiện ra. Nhập `localhost` hoặc IP server, port `5000`, sau đó nhập tài khoản và mật khẩu.

## Client trên máy ảo Windows

Lần đầu đồng bộ code:

```powershell
git clone https://github.com/Letien06/Chat-CS-P2P.git
cd Chat-CS-P2P
git pull --ff-only origin main
```

Máy build cần JDK 17+ và Maven (script Windows cũng tìm Maven bundled trong IntelliJ). Sau mỗi lần pull có đổi source, build lại; JAR không được lưu trên Git. Build rồi chạy:

```powershell
.\scripts\build-all.ps1
.\scripts\run-client-vm.ps1 -ServerHost 192.168.1.15
```

Hoặc chỉ cần chạy script chính, sau đó điền IP trên form:

```powershell
.\scripts\run-client.ps1
```

## Client trên máy ảo Linux

```bash
git clone https://github.com/Letien06/Chat-CS-P2P.git
cd Chat-CS-P2P
git pull --ff-only origin main
bash scripts/build-all.sh
bash scripts/run-client-vm.sh 192.168.1.15 5000
```

Kiểm tra kết nối trước khi mở client:

```powershell
Test-NetConnection 192.168.1.15 -Port 5000
```

Hoặc trên Linux:

```bash
nc -vz 192.168.1.15 5000
```

Mỗi client đăng nhập bằng tài khoản riêng. Unicast, Group và Broadcast đều đi qua server TCP.
