# Lumi Chat - P2P Chat Message

Ứng dụng chat Java chạy trong mạng LAN, gồm một server TCP và nhiều client
Windows/Linux. Người dùng chỉ cần nhập tên hiển thị và IP server, không cần đăng
ký tài khoản hoặc mật khẩu.

## Chức năng

- Nhiều client kết nối cùng lúc.
- Hiển thị danh sách người dùng online.
- Chat riêng giữa hai client.
- Gửi và nhận file qua server.
- Chạy trên Windows và Linux với Java 17+.
- Server dùng port TCP `5000` và lắng nghe trên mọi network interface.

## Cấu trúc project

```text
chat_message/
├── common/                 Protocol và codec dùng chung
├── server/                 TCP server và SQLite database
├── client/                 Giao diện Java Swing
├── scripts/                Script build/chạy Windows và Linux
├── downloads/              File client nhận được
└── pom.xml                 Maven project gốc
```

## Yêu cầu

- JDK 17 trở lên.
- Maven 3.9+ nếu muốn build lại source.
- Các máy phải nhìn thấy IP của máy chạy server.
- TCP port `5000` không bị firewall chặn.

Kiểm tra Java trên Windows:

```powershell
java -version
javac -version
```

Kiểm tra Java trên Linux:

```bash
java -version
javac -version
```

## Build trên Windows

Mở PowerShell tại thư mục `chat_message`:

```powershell
cd "D:\duong-dan-den\chat_message"
.\scripts\build-all.ps1
```

Nếu Maven đã có trong `PATH`:

```powershell
mvn clean test
mvn clean package
```

Script `build-all.ps1` tự tìm Maven đi kèm IntelliJ IDEA nếu máy chưa có `mvn`
trong `PATH`.

Sau khi build thành công, hai file chạy chính là:

```text
server/target/server-1.0.0-SNAPSHOT-shaded.jar
client/target/client-1.0.0-SNAPSHOT-shaded.jar
```

## Build trên Linux

Cài Java và Maven trên Ubuntu/Debian:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven
```

Build project:

```bash
cd ~/chat_message
chmod +x scripts/*.sh
./scripts/build-all.sh
```

Hoặc:

```bash
mvn clean test
mvn clean package
```

## Chạy server trên Windows

Mở PowerShell tại thư mục project:

```powershell
cd "D:\duong-dan-den\chat_message"
.\scripts\run-server.ps1
```

Hoặc chạy JAR trực tiếp:

```powershell
java -jar server\target\server-1.0.0-SNAPSHOT-shaded.jar server\config\server.properties
```

Khi thành công, terminal hiển thị:

```text
P2P Chat server listening on port 5000
```

Không đóng terminal server trong lúc các client đang chat.

## Chạy server trên Linux

```bash
cd ~/chat_message
chmod +x scripts/run-server.sh
./scripts/run-server.sh
```

Hoặc:

```bash
java -jar server/target/server-1.0.0-SNAPSHOT-shaded.jar server/config/server.properties
```

## Chạy client trên Windows

Mở một PowerShell mới cho mỗi client:

```powershell
cd "D:\duong-dan-den\chat_message"
.\scripts\run-client.ps1
```

Hoặc:

```powershell
java -jar client\target\client-1.0.0-SNAPSHOT-shaded.jar
```

Muốn thử nhiều người trên cùng một máy Windows, chạy lệnh client nhiều lần.
Mỗi cửa sổ nhập một tên khác nhau.

## Chạy client trên Linux

```bash
cd ~/chat_message
chmod +x scripts/run-client.sh
./scripts/run-client.sh
```

Hoặc:

```bash
java -jar client/target/client-1.0.0-SNAPSHOT-shaded.jar
```

Nếu Linux báo không mở được giao diện, kiểm tra máy có desktop environment và
biến `DISPLAY`:

```bash
echo $DISPLAY
```

## Chọn IP server

### Server và client trên cùng máy

Nhập:

```text
127.0.0.1
```

### Client ở máy Windows khác trong cùng Wi-Fi

Trên máy chạy server, tìm IPv4:

```powershell
ipconfig
```

Tìm dòng `IPv4 Address` của Wi-Fi hoặc Ethernet. Ví dụ:

```text
192.168.1.3
```

Các client ở máy khác nhập IP này vào ô **IP Server**.

### Client Linux trong VirtualBox

Cấu hình ổn định để demo trên cùng laptop:

1. Tắt máy ảo Linux.
2. Mở VirtualBox, chọn máy ảo và vào **Settings > Network**.
3. Giữ **Adapter 1** ở chế độ **NAT** để Linux có Internet.
4. Bật **Adapter 2**.
5. Chọn **Attached to: Host-only Adapter**.
6. Khởi động lại máy ảo.

Trên Windows, xem IP của VirtualBox Host-only Adapter:

```powershell
ipconfig
```

Thông thường IP này là:

```text
192.168.56.1
```

Client Linux nhập `192.168.56.1` vào ô **IP Server**.

Kiểm tra từ Linux VM:

```bash
ping -c 4 192.168.56.1
```

Kiểm tra port server từ Linux:

```bash
nc -vz 192.168.56.1 5000
```

Nếu chưa có `nc`:

```bash
sudo apt update
sudo apt install -y netcat-openbsd
```

Sau đó chạy client Linux:

```bash
cd ~/chat_message
./scripts/run-client.sh
```

### Dùng Bridged Adapter

Có thể chọn **Bridged Adapter** nếu muốn Linux VM xuất hiện như một máy riêng
trong cùng mạng Wi-Fi. Trong Linux, lấy IP bằng:

```bash
ip addr
```

Khi dùng Bridged, client Linux vẫn nhập IP Wi-Fi/Ethernet của máy chạy server,
ví dụ `192.168.1.3`.

## Kịch bản demo đề xuất

1. Build project trên Windows.
2. Chạy server Windows:

```powershell
.\scripts\run-server.ps1
```

3. Chạy hai client Windows, mỗi client ở một PowerShell:

```powershell
.\scripts\run-client.ps1
```

4. Client Windows nhập các tên khác nhau, ví dụ `Windows-A` và `Windows-B`, IP:

```text
127.0.0.1
```

5. Trong Linux VM chạy:

```bash
cd ~/chat_message
./scripts/run-client.sh
```

6. Client Linux nhập tên `Linux-VM` và IP Host-only của Windows:

```text
192.168.56.1
```

7. Chọn từng người trong danh sách online để chat và gửi file qua lại.

## Mở Windows Firewall cho port 5000

Có thể chọn **Allow access** nếu Windows hỏi quyền mạng khi chạy Java. Nếu vẫn
bị chặn, mở PowerShell bằng quyền Administrator và chạy:

```powershell
New-NetFirewallRule `
  -DisplayName "Lumi Chat TCP 5000" `
  -Direction Inbound `
  -Protocol TCP `
  -LocalPort 5000 `
  -Action Allow `
  -Profile Private
```

Kiểm tra server có đang lắng nghe:

```powershell
netstat -ano | findstr :5000
```

Kết quả đúng có dạng:

```text
TCP    0.0.0.0:5000    0.0.0.0:0    LISTENING
```

Xóa rule firewall nếu không còn cần:

```powershell
Remove-NetFirewallRule -DisplayName "Lumi Chat TCP 5000"
```

## Chỉ gửi file JAR cho bạn bè

Nếu không muốn gửi toàn bộ source, gửi các file sau và giữ đúng cấu trúc:

```text
LumiChat/
├── client-1.0.0-SNAPSHOT-shaded.jar
├── server-1.0.0-SNAPSHOT-shaded.jar
└── server.properties
```

Chạy server Windows trong thư mục chứa các file:

```powershell
java -jar .\server-1.0.0-SNAPSHOT-shaded.jar .\server.properties
```

Chạy client Windows:

```powershell
java -jar .\client-1.0.0-SNAPSHOT-shaded.jar
```

Chạy client Linux:

```bash
java -jar ./client-1.0.0-SNAPSHOT-shaded.jar
```

File `server.properties` mẫu:

```properties
server.port=5000
database.path=data/p2p-chat.db
max.clients=100
max.file.size=2147483648
```

## Lỗi thường gặp

### `java` không được nhận diện

Cài JDK 17 và mở lại terminal, sau đó kiểm tra:

```bash
java -version
```

### `mvn` không được nhận diện trên Windows

Dùng script tự tìm Maven của IntelliJ:

```powershell
.\scripts\build-all.ps1
```

### Client báo không kết nối được server

Kiểm tra lần lượt:

```text
1. Server vẫn đang chạy.
2. IP Server nhập đúng.
3. Client không dùng 127.0.0.1 khi server nằm trên máy khác.
4. Port TCP 5000 không bị firewall chặn.
5. Linux VM ping được IP Windows.
6. Hai client không nhập trùng tên đang online.
```

Trên Windows kiểm tra port:

```powershell
Test-NetConnection 192.168.1.3 -Port 5000
```

Trên Linux kiểm tra port:

```bash
nc -vz 192.168.56.1 5000
```

### Không gửi hoặc nhận được file

- Giữ server hoạt động trong suốt quá trình truyền.
- Không đóng client khi file đang truyền.
- Kiểm tra quyền ghi thư mục `downloads`.
- File nhận được lưu tại thư mục `downloads` tính từ nơi chạy client.

## Dừng chương trình

- Đóng cửa sổ client để ngắt client.
- Trong terminal server nhấn `Ctrl+C` để dừng server.

## Tài liệu thêm

- `server/README.md`: thông tin module server.
- `client/README.md`: thông tin giao diện client.
- `docs/protocol.md`: giao thức message giữa client và server.
