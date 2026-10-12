# Kế hoạch dự án Chat và truyền file P2P bằng Java

## Trạng thái triển khai

- Đã bắt đầu triển khai phần server theo kế hoạch.
- Đã tạo cấu trúc Maven multi-module gồm `common`, `server` và `client`.
- Đã tạo giao thức JSON length-prefixed, SQLite database, password hashing PBKDF2, TCP server đa client, session/presence, chat cá nhân/nhóm và file signaling/file registry.
- Đã tạo client Swing: kết nối server, đăng ký, đăng nhập, danh sách online và chat riêng.
- Đã triển khai truyền file qua Server: offer/accept/reject, chunk Base64 48 KB, progress, lưu downloads và SHA-256.
- Đã xác nhận JDK 17 và Maven đi kèm IntelliJ, sửa lỗi compile trong JSON codec, build/package thành công.
- Đã thêm integration test chạy server và hai client trên `127.0.0.1`, xác nhận đăng ký, đăng nhập và chat thành công.

### Các file server đã tạo

- `pom.xml`, `common/pom.xml`, `server/pom.xml`.
- `common/.../MessageType.java`, `Message.java`, `JsonCodec.java`, `FrameCodec.java`.
- `server/.../ServerMain.java`, `ClientHandler.java`.
- `server/.../ServerConfig.java`, `PasswordHasher.java`.
- `server/.../Database.java`.
- `server/.../Session.java`, `SessionManager.java`.
- Test cho frame protocol và password hashing.
- `server/config/server.properties`, `README.md`, `docs/protocol.md` và script build/run.

Việc cần làm tiếp theo: kiểm tra giao diện thủ công bằng hai cửa sổ client, sau đó thử nghiệm trên hai máy cùng Wi-Fi và xử lý các tình huống mất kết nối khi truyền file.

## 1. Mục tiêu cuối cùng

Xây dựng một ứng dụng chat và truyền file trong mạng LAN bằng Java.

Ứng dụng sẽ có:

- Đăng ký tài khoản.
- Đăng nhập.
- Danh sách người dùng đang online.
- Chat cá nhân.
- Chat nhóm.
- Gửi file.
- Nhận file.
- Hiển thị tiến trình truyền.
- Từ chối hoặc chấp nhận file.
- Kiểm tra file sau khi truyền.
- Lịch sử chat cơ bản.
- Danh sách file đã chia sẻ.
- Tìm kiếm file.
- Ghi log.
- Xử lý mất kết nối.
- Chạy được trên hai máy tính khác nhau.

Mô hình được chọn là mô hình kết hợp Client–Server và P2P:

```text
                ┌──────────────────────┐
                │       SERVER         │
                │                      │
                │ - Đăng nhập          │
                │ - Danh sách online   │
                │ - Chat                │
                │ - Báo hiệu truyền file│
                │ - Lưu dữ liệu         │
                └──────────┬───────────┘
                           │
             ┌─────────────┴─────────────┐
             │                           │
      ┌──────▼──────┐              ┌─────▼─────┐
      │   CLIENT A  │◄── truyền ──►│ CLIENT B  │
      │              │   file P2P   │           │
      └──────────────┘              └───────────┘
```

Server quản lý và điều phối. Hai client truyền file trực tiếp cho nhau khi có thể.

## 2. Giả định kỹ thuật

- Ngôn ngữ: Java 17 hoặc cao hơn.
- Công cụ build: Maven.
- Giao diện: Java Swing.
- Cơ sở dữ liệu: SQLite.
- Giao thức điều khiển: TCP.
- Truyền file: TCP trực tiếp giữa hai client.
- Môi trường demo chính: hai máy trong cùng mạng LAN hoặc Wi-Fi.
- Server chạy trên một máy cố định.
- Một máy có thể chạy đồng thời server và client.
- Client kết nối đến địa chỉ IP LAN của server, không dùng `localhost` khi chạy khác máy.

Swing được chọn vì có sẵn trong JDK, ít lỗi môi trường hơn khi gửi chương trình cho thành viên cùng nhóm.

## 3. Chức năng chi tiết

### 3.1. Tài khoản

- Đăng ký tài khoản.
- Đăng nhập.
- Đăng xuất.
- Kiểm tra username trùng.
- Mật khẩu không lưu dạng văn bản thuần.
- Mật khẩu được băm bằng PBKDF2 hoặc BCrypt.
- Không cho một tài khoản đăng nhập nhiều phiên nếu chưa cho phép.

### 3.2. Quản lý người dùng

- Hiển thị danh sách online.
- Hiển thị trạng thái online/offline.
- Thông báo khi người dùng tham gia.
- Thông báo khi người dùng rời đi.
- Có thể chọn một người để chat hoặc gửi file.

### 3.3. Chat cá nhân

- Gửi tin nhắn đến một người dùng.
- Nhận tin nhắn theo thời gian thực.
- Hiển thị thời gian gửi.
- Hiển thị người gửi.
- Báo lỗi nếu người nhận offline.
- Lưu lịch sử chat vào database.

### 3.4. Chat nhóm

- Tạo phòng chat.
- Tham gia phòng.
- Rời phòng.
- Gửi tin nhắn cho toàn bộ thành viên.
- Hiển thị danh sách thành viên.
- Chủ phòng có thể đóng phòng.

### 3.5. Truyền file

- Chọn file từ máy.
- Gửi file cho người dùng khác.
- Gửi file vào phòng nhóm nếu cần.
- Hiển thị tên file.
- Hiển thị kích thước file.
- Hiển thị phần trăm hoàn thành.
- Hiển thị tốc độ truyền.
- Cho phép chấp nhận hoặc từ chối.
- Lưu file vào thư mục `downloads`.
- Đổi tên nếu file trùng.
- Kiểm tra kích thước sau khi nhận.
- Kiểm tra mã SHA-256 sau khi nhận.
- Thông báo truyền thành công hoặc thất bại.

### 3.6. Quản lý file

Server có thể lưu thông tin file, không nhất thiết phải lưu toàn bộ nội dung file:

- Tên file.
- Người sở hữu.
- Kích thước.
- Mã SHA-256.
- Thời gian chia sẻ.
- Trạng thái online của người sở hữu.

Client có thể:

- Đăng ký file muốn chia sẻ.
- Xóa file khỏi danh sách chia sẻ.
- Tìm file theo tên.
- Xem người đang sở hữu file.
- Gửi yêu cầu tải file.

### 3.7. Xử lý lỗi

Chương trình cần xử lý:

- Server chưa chạy.
- Sai địa chỉ IP.
- Sai cổng.
- Mất kết nối giữa chừng.
- Client thoát đột ngột.
- File không tồn tại.
- Không đủ quyền đọc file.
- File đích đã tồn tại.
- Người gửi offline.
- Người nhận từ chối.
- File bị thay đổi trong lúc truyền.
- Không đủ dung lượng ổ đĩa.

## 4. Kiến trúc project

```text
p2p-chat/
├── pom.xml
├── common/
│   ├── pom.xml
│   └── src/main/java/
│       └── com/p2p/common/
│           ├── protocol/
│           ├── model/
│           ├── codec/
│           └── constants/
│
├── server/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── com/p2p/server/
│       │       ├── ServerMain.java
│       │       ├── config/
│       │       ├── network/
│       │       ├── auth/
│       │       ├── session/
│       │       ├── chat/
│       │       ├── file/
│       │       ├── database/
│       │       └── logging/
│       └── test/
│
├── client/
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       │   └── com/p2p/client/
│       │       ├── ClientMain.java
│       │       ├── config/
│       │       ├── network/
│       │       ├── auth/
│       │       ├── chat/
│       │       ├── file/
│       │       ├── ui/
│       │       └── state/
│       └── test/
│
├── docs/
│   ├── architecture.md
│   ├── protocol.md
│   ├── database.md
│   ├── setup.md
│   └── demo-script.md
│
└── scripts/
    ├── run-server.sh
    ├── run-client.sh
    └── build-all.sh
```

Module `common` dùng chung giữa server và client, bao gồm loại message, cấu trúc message, tên lệnh, cách mã hóa dữ liệu, thông tin file và quy tắc phản hồi.

## 5. Giao thức mạng

### 5.1. Kết nối điều khiển

Client kết nối đến server bằng TCP, ví dụ:

```text
SERVER_IP = 192.168.1.10
SERVER_PORT = 5000
```

Mọi message điều khiển sử dụng dạng JSON có đánh độ dài ở đầu message:

```text
[4 byte độ dài][JSON UTF-8]
```

Cách này tránh lỗi khi nội dung có ký tự xuống dòng, đọc chính xác từng message, hỗ trợ Unicode và dễ mở rộng.

### 5.2. Các loại message chính

```text
REGISTER_REQUEST
REGISTER_RESPONSE

LOGIN_REQUEST
LOGIN_RESPONSE

LOGOUT_REQUEST
LOGOUT_RESPONSE

USER_LIST_REQUEST
USER_LIST_RESPONSE
USER_STATUS_CHANGED

PRIVATE_MESSAGE
GROUP_MESSAGE

CREATE_ROOM
JOIN_ROOM
LEAVE_ROOM
ROOM_UPDATED

FILE_OFFER
FILE_ACCEPT
FILE_REJECT
FILE_CANCEL
FILE_PROGRESS
FILE_COMPLETE
FILE_FAILED

FILE_SEARCH_REQUEST
FILE_SEARCH_RESPONSE
FILE_SHARE
FILE_UNSHARE

PING
PONG
ERROR
```

Ví dụ request đăng nhập:

```json
{
  "type": "LOGIN_REQUEST",
  "requestId": "abc-123",
  "username": "alice",
  "password": "..."
}
```

Ví dụ response:

```json
{
  "type": "LOGIN_RESPONSE",
  "requestId": "abc-123",
  "success": true,
  "sessionToken": "...",
  "message": "Login successful"
}
```

### 5.3. Kết nối truyền file

Kênh chat và kênh truyền file được tách riêng:

```text
Kết nối 1: Client ↔ Server
- Đăng nhập
- Chat
- Presence
- Báo hiệu file

Kết nối 2: Client A ↔ Client B
- Truyền dữ liệu file nhị phân
```

File được truyền theo block, không chuyển thành Base64. Sau khi truyền xong, bên nhận tính SHA-256 và so sánh với mã do bên gửi cung cấp.

## 6. Cơ sở dữ liệu server

SQLite được dùng vì không cần cài database server riêng, dễ đóng gói và phù hợp bài lab.

Các bảng dự kiến:

```text
users
├── id
├── username
├── password_hash
├── password_salt
├── created_at
└── last_login

sessions
├── id
├── user_id
├── token
├── client_ip
├── client_port
├── login_time
└── last_seen

chat_messages
├── id
├── sender_id
├── receiver_id
├── room_id
├── content
└── created_at

chat_rooms
├── id
├── name
├── owner_id
└── created_at

room_members
├── room_id
└── user_id

shared_files
├── id
├── owner_id
├── file_name
├── file_size
├── sha256
├── peer_ip
├── peer_port
└── created_at

file_transfers
├── id
├── sender_id
├── receiver_id
├── file_name
├── file_size
├── status
├── sha256
├── started_at
└── completed_at
```

## 7. Phân chia công việc cho 2 người

### Thành viên 1: Server và giao thức

- Thiết kế giao thức.
- Viết `ServerMain`.
- Xử lý nhiều client cùng lúc.
- Quản lý danh sách người dùng.
- Chuyển tiếp tin nhắn.
- Quản lý thông tin file.
- Kiểm thử kết nối mạng.

### Thành viên 2: Client và giao diện

- Viết chương trình client.
- Làm giao diện Swing hoặc JavaFX.
- Hiển thị danh sách người dùng.
- Gửi và nhận tin nhắn.
- Chọn file.
- Hiển thị tiến trình truyền file.
- Lưu file nhận được.

### Hai người cùng làm

- Thống nhất giao thức trước khi lập trình.
- Tích hợp server và client.
- Kiểm thử trên hai máy thật.
- Viết báo cáo.
- Chuẩn bị slide và phần demo.

## 8. Lộ trình thực hiện

### Giai đoạn 0: Khóa thiết kế

Thống nhất Java version, Maven, Swing, SQLite, cổng server, giao thức JSON, cách truyền file, cấu trúc thư mục, quy tắc đặt tên và kế hoạch test.

Kết quả cần có: sơ đồ kiến trúc, danh sách message, database schema, README ban đầu và kế hoạch kiểm thử.

### Giai đoạn 1: Tạo module dùng chung

- Model người dùng.
- Model tin nhắn.
- Model file.
- Enum loại message.
- JSON codec.
- Message framing.
- Các hằng số cổng.
- Exception chung.
- Test encode/decode message.

### Giai đoạn 2: Làm server trước

#### Server core

- Mở `ServerSocket`.
- Chấp nhận nhiều client.
- Xử lý mỗi client bằng thread hoặc thread pool.
- Đọc message theo frame.
- Gửi response đúng request.
- Ghi log kết nối.

#### Đăng ký và đăng nhập

- Kết nối database SQLite.
- Tạo bảng tự động.
- Đăng ký tài khoản.
- Kiểm tra username trùng.
- Băm mật khẩu.
- Đăng nhập.
- Sinh session token.
- Đăng xuất.
- Hủy session khi mất kết nối.

#### Quản lý session

- Lưu user online.
- Gắn username với socket.
- Lưu IP và cổng file của client.
- Gửi thông báo online/offline.
- Xử lý heartbeat.
- Ngắt phiên bị treo.

#### Chat

- Chat cá nhân.
- Chat nhóm.
- Chuyển tiếp tin nhắn.
- Lưu lịch sử.
- Lấy lịch sử chat.
- Báo lỗi người nhận offline.

#### File signaling

Server sẽ nhận yêu cầu gửi file, thông báo cho người nhận, chuyển metadata và thông tin IP/cổng của peer, theo dõi trạng thái truyền và lưu lịch sử truyền. Server không giữ toàn bộ nội dung file trong bộ nhớ.

#### File registry

- Đăng ký file đang chia sẻ.
- Xóa file.
- Tìm kiếm file.
- Xóa file khi client offline.
- Kiểm tra file owner còn online hay không.

#### Server test

- Nhiều client kết nối.
- Đăng ký.
- Đăng nhập sai và đúng.
- Hai client chat.
- Client thoát bất ngờ.
- Gửi message Unicode.
- File offer bị thiếu trường.
- Người nhận offline.
- Session hết hạn.
- Database lỗi.

#### Đóng gói server

```text
server-dist/
├── p2p-server.jar
├── config/
│   └── server.properties
├── data/
│   └── .gitkeep
├── logs/
├── run-server.bat
├── run-server.sh
└── README.txt
```

Cấu hình dự kiến:

```properties
server.port=5000
file.signaling.port=5001
database.path=data/p2p-chat.db
max.clients=100
max.file.size=2147483648
```

### Giai đoạn 3: Kiểm tra server độc lập

Tạo client giả dạng dòng lệnh để kiểm tra giao thức và server mà chưa phụ thuộc giao diện. Server phải chạy độc lập, hỗ trợ nhiều client đăng nhập, chat, file offer/accept/reject, database và không crash khi client mất kết nối.

### Giai đoạn 4: Làm client

#### Client network layer

- Kết nối server.
- Đăng nhập.
- Nhận message bất đồng bộ.
- Gửi request.
- Theo dõi request ID.
- Tự reconnect khi mất mạng.
- Heartbeat.
- Logout sạch sẽ.

#### Giao diện

```text
LoginFrame
RegisterFrame
MainFrame
ChatPanel
UsersPanel
RoomsPanel
FileTransferPanel
SettingsPanel
```

#### Chat

- Chọn người dùng.
- Hiển thị lịch sử.
- Gửi tin nhắn.
- Nhận tin nhắn realtime.
- Hiển thị trạng thái online.
- Chat nhóm.

#### Truyền file

- Chọn file.
- Gửi file offer.
- Hiển thị hộp thoại chấp nhận.
- Mở cổng file tạm.
- Kết nối peer.
- Truyền file theo block.
- Cập nhật progress bar.
- Tính SHA-256.
- Lưu file.
- Xử lý file trùng tên.

#### Tìm kiếm file

- Nhập tên file.
- Gửi truy vấn server.
- Hiển thị kết quả.
- Chọn file.
- Gửi yêu cầu tải.
- Báo lỗi nếu owner offline.

### Giai đoạn 5: Tích hợp hai máy

```text
Máy 1:
- Server
- Client A

Máy 2:
- Client B
```

Kiểm tra hai máy cùng Wi-Fi, IP máy chạy server, firewall, kết nối bằng IP LAN, chat hai chiều, gửi file A → B, gửi file B → A, file lớn, file Unicode, mất kết nối, server restart và client reconnect.

### Giai đoạn 6: Đóng gói client để gửi cho bạn

```text
client-dist/
├── p2p-client.jar
├── config/
│   └── client.properties
├── downloads/
├── logs/
├── run-client.bat
├── run-client.sh
└── README.txt
```

Có thể gửi cho thành viên cùng nhóm file `.jar`, cấu hình, hướng dẫn chạy và địa chỉ IP server. Không cần gửi database hoặc log server.

### Giai đoạn 7: Báo cáo và demo

Chuẩn bị giới thiệu đề tài, phân tích yêu cầu, mô hình mạng, sơ đồ kiến trúc, protocol, database, quy trình chat, quy trình truyền file, phân chia công việc, kết quả, hạn chế và hướng phát triển.

Kịch bản demo:

1. Khởi động server.
2. Khởi động Client A.
3. Khởi động Client B.
4. Đăng nhập hai tài khoản.
5. Chứng minh hai người online.
6. Chat A → B và B → A.
7. Tạo phòng chat.
8. Gửi file A → B.
9. Chấp nhận file ở B.
10. Hiển thị tiến trình.
11. Kiểm tra SHA-256.
12. Gửi file ngược lại.
13. Cho một client thoát.
14. Chứng minh server cập nhật offline.

## 9. Thứ tự code chính xác

```text
Thiết kế
  ↓
Common protocol/model
  ↓
Server core
  ↓
Database và authentication
  ↓
Session và user presence
  ↓
Chat
  ↓
File signaling
  ↓
Server test
  ↓
Server packaging
  ↓
Client network
  ↓
Client UI
  ↓
P2P file transfer
  ↓
Integration test
  ↓
Client packaging
  ↓
README + báo cáo + demo
```

## 10. Tiêu chí hoàn thành server

Chỉ chuyển sang client sau khi server đạt các điều kiện:

- Build thành công bằng Maven.
- Chạy được bằng file `.jar`.
- Có database tự tạo.
- Hai client giả lập đăng nhập được.
- Có danh sách online.
- Chat cá nhân hoạt động.
- Chat nhóm hoạt động.
- File offer/accept/reject hoạt động.
- Có kiểm tra quyền và dữ liệu đầu vào.
- Không crash khi client mất kết nối.
- Có log rõ ràng.
- Có test tự động cho chức năng chính.
- Có README hướng dẫn chạy.

## 11. Giới hạn và hướng mở rộng

Phiên bản đầu tiên ưu tiên mạng LAN. Truyền trực tiếp qua Internet có thể gặp NAT, firewall, port forwarding, IP thay đổi và router chặn kết nối ngang hàng. Nếu muốn mở rộng qua Internet, có thể thêm relay server ở giai đoạn sau.

Các chức năng như mã hóa đầu cuối, tải file tiếp tục sau khi mất mạng, chống virus, gửi file qua NAT và triển khai cloud là phần nâng cao, chỉ thực hiện sau khi chức năng cốt lõi hoàn thành.

## 12. Thứ tự triển khai thực tế

Sau khi phê duyệt kế hoạch, tiến trình sẽ là:

1. Tạo cấu trúc Maven.
2. Tạo module `common`.
3. Định nghĩa protocol.
4. Tạo database SQLite.
5. Viết server core.
6. Viết authentication.
7. Viết quản lý session.
8. Viết chat.
9. Viết file signaling.
10. Chạy test và báo cáo kết quả.
11. Chỉ sau đó mới viết client.

Phần client cuối cùng sẽ được đóng gói riêng để gửi cho thành viên cùng nhóm.
