# P2P Chat Server

Đây là module server của dự án chat và truyền file. Server cung cấp kênh TCP để người dùng tham gia bằng tên, quản lý trạng thái online, chat cá nhân/nhóm và chuyển tiếp file theo các chunk Base64 giữa hai client.

## Yêu cầu

- JDK 17+
- Maven 3.9+
- Hai máy cùng mạng LAN/Wi-Fi khi chạy demo

## Chạy từ source

Từ thư mục `chat_message`:

```bash
mvn clean package
java -jar server/target/server-1.0.0-SNAPSHOT-shaded.jar server/config/server.properties
```

Maven Shade Plugin cũng tạo một file `server-1.0.0-SNAPSHOT-shaded.jar`; có thể dùng file này nếu muốn chạy độc lập.

Nếu không truyền đường dẫn cấu hình, server dùng mặc định `config/server.properties` trong thư mục hiện tại.

## Cấu hình

File mẫu nằm tại `server/config/server.properties`. Khi chạy từ module server, có thể dùng:

```bash
cd server
java -jar target/server-1.0.0-SNAPSHOT-shaded.jar config/server.properties
```

Server tự tạo thư mục `data` và database SQLite khi khởi động.

## Kết nối từ máy khác hoặc máy ảo Linux

Client phải dùng địa chỉ IP LAN của máy chạy server, ví dụ `192.168.1.10:5000`, không dùng `localhost`. Nếu firewall chặn TCP port 5000, cần tạo rule cho port này trước khi demo.

Server dùng `new ServerSocket(5000)` nên lắng nghe trên mọi interface mạng của
máy chạy server. Khi VM và server cùng nằm trên một laptop, nên bật thêm
**Host-only Adapter** và dùng IP Host-only của Windows, thường là
`192.168.56.1`. Nếu muốn VM nằm trực tiếp trong cùng mạng Wi-Fi, có thể dùng
**Bridged Adapter**. Trên Linux, chạy client bằng:

```bash
./scripts/run-client.sh
```

Sau đó nhập IP của máy chạy server vào ô **IP Server**. Không nhập `127.0.0.1`
trừ khi server cũng chạy bên trong chính máy ảo Linux đó.

## Giao thức

Mỗi message là JSON UTF-8 có frame `[4 byte độ dài][JSON]`. Các loại message được định nghĩa trong module `common`.
