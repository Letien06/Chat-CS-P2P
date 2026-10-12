# Hỏi - đáp bảo vệ MiniChat

Tài liệu này dùng để trả lời nhanh khi thầy hỏi về mô hình Client-Server,
địa chỉ IP, port, đăng nhập bằng tên, nhắn tin và truyền file.

## 1. Tóm tắt dự án trong 30 giây

MiniChat là ứng dụng chat Java Swing theo mô hình **Client-Server**:

```text
Client A  ── TCP ──>  Server  ── TCP ──>  Client B
Client B  ── TCP ──>  Server
```

- Server mở một TCP port, chờ nhiều client kết nối.
- Mỗi client chỉ kết nối đến server, không mở kết nối trực tiếp đến client khác.
- Server nhận message, kiểm tra dữ liệu, tìm đúng người nhận rồi chuyển tiếp.
- Tin nhắn và file đều đi qua server:
  `Client A -> Server -> Client B`.
- Người dùng không cần username/password; chỉ nhập tên hiển thị và IP server.

## 2. Nếu thầy hỏi: "Code Client-Server nằm ở đâu?"

Trả lời:

- Code server nằm trong `server/src/main/java/com/p2p/server/`.
- Code client nằm trong `client/src/main/java/com/p2p/client/`.
- Giao thức message dùng chung nằm trong `common/src/main/java/com/p2p/common/protocol/`.
- Server khởi động từ `ServerMain.java`.
- Client khởi động từ `ClientMain.java`, sau đó mở `LoginFrame.java`.
- Kết nối mạng phía client nằm trong `network/ChatClient.java`.
- Một client sau khi kết nối được server xử lý bởi `ClientHandler.java`.

Các file quan trọng:

| Thành phần | File | Vai trò |
|---|---|---|
| Server mở port | `server/.../ServerMain.java` | Tạo `ServerSocket`, nhận socket client |
| Server xử lý client | `server/.../ClientHandler.java` | Đọc request và xử lý từng loại message |
| Socket server | `server/.../session/Session.java` | Đọc/ghi message trên một TCP socket |
| Quản lý client | `server/.../session/SessionManager.java` | Lưu các client đang online |
| UI nhập IP/tên | `client/.../ui/LoginFrame.java` | Nhập thông tin và bấm vào phòng chat |
| Socket client | `client/.../network/ChatClient.java` | Kết nối, gửi và nhận message |
| Protocol chung | `common/.../Message.java` | Cấu trúc message |
| Loại request | `common/.../MessageType.java` | Tên các loại message |

## 3. Nếu thầy hỏi: "Server mở port ở đâu?"

Chỉ vào `server/src/main/java/com/p2p/server/ServerMain.java`:

```java
serverSocket = new ServerSocket(config.port());
```

Ở đây server tạo `ServerSocket`, nghĩa là bắt đầu lắng nghe kết nối TCP.
Sau đó vòng lặp:

```java
Socket socket = serverSocket.accept();
```

đứng chờ client kết nối. Khi có client, server tạo một `ClientHandler` riêng
để xử lý client đó.

Các vị trí chính:

- `ServerMain.java:40-44`: mở port và báo server đã chạy.
- `ServerMain.java:45-50`: nhận socket client và giao cho `ClientHandler`.
- `ServerMain.java:60-66`: đóng server socket, các client và database.

## 4. Port server được cấu hình ở đâu?

File cấu hình là:

```text
server/config/server.properties
```

Nội dung chính:

```properties
server.port=5000
```

Code đọc giá trị này tại `server/.../config/ServerConfig.java:22-31`.
Nếu không tìm thấy file hoặc không có `server.port`, chương trình dùng port mặc
định `5000`:

```java
int port = integer(p, "server.port", 5000);
```

Khi chạy server, log tương ứng là:

```text
MiniChat server listening on port 5000
```

## 5. Client điền IP và port server ở đâu?

### Ô nhập IP

Trong `client/src/main/java/com/p2p/client/ui/LoginFrame.java`:

```java
private final JTextField hostField = new JTextField("127.0.0.1", 18);
```

Đây là ô **IP Server**. UI đặt nhãn tại `LoginFrame.java:90`.

- Nếu client và server cùng máy: nhập `127.0.0.1`.
- Nếu server chạy trên máy Windows khác: nhập IPv4 của máy Windows đó,
  ví dụ `192.168.1.3`.
- Nếu client chạy trong Linux VM và server chạy trên Windows host-only:
  nhập IP của adapter Host-only trên Windows, thường là `192.168.56.1`.

### Port client dùng để kết nối

Port được khai báo tại `LoginFrame.java:12`:

```java
private static final int SERVER_PORT = 5000;
```

Khi bấm nút **Vào phòng chat**, code lấy IP ở `LoginFrame.java:109-112`,
sau đó gọi:

```java
connectingClient.connect(host, SERVER_PORT);
```

Vị trí chính: `LoginFrame.java:116-126`.

Lưu ý: UI hiện chỉ cho nhập IP; port client dùng mặc định `5000` để khớp
với `server.properties`.

## 6. Client kết nối đến server như thế nào?

Luồng thực tế:

1. Người dùng điền tên và IP server.
2. `LoginFrame.joinChat()` lấy hai giá trị này.
3. `ChatClient.connect(host, SERVER_PORT)` tạo TCP socket.
4. Client kết nối đến `IP:5000` của server.
5. Client tạo input/output stream trên socket.
6. Client chạy `readLoop()` ở thread riêng để luôn nghe server.

Code socket nằm tại `client/src/main/java/com/p2p/client/network/ChatClient.java`:

```java
socket = new Socket();
socket.connect(new InetSocketAddress(host, port), 5000);
input = new DataInputStream(socket.getInputStream());
output = new DataOutputStream(socket.getOutputStream());
```

- `ChatClient.java:30-37`: kết nối đến server.
- `ChatClient.java:39-42`: gửi message lên server.
- `ChatClient.java:45-52`: đọc message server gửi về.

Số `5000` ở tham số `socket.connect(..., 5000)` là **timeout 5 giây**, không
phải port. Port thật là biến `SERVER_PORT` có giá trị `5000`.

## 7. Server nhận kết nối client như thế nào?

Server không cần nhập IP của từng client. Server chỉ mở port và chờ:

```java
Socket socket = serverSocket.accept();
```

Khi nhận được socket:

```java
clients.submit(new ClientHandler(socket, database, sessions, config, observer));
```

Mỗi client được xử lý bởi một `ClientHandler` riêng, nên nhiều client có thể
chat đồng thời.

Địa chỉ của client được Java lấy tự động từ socket tại:

```java
this.remoteAddress = socket.getRemoteSocketAddress().toString();
```

Vị trí: `server/.../session/Session.java:21-25`.

Vì vậy server biết client nào kết nối mà không cần client tự nhập IP của mình.
Port phía client thường là port tạm thời do hệ điều hành cấp, ví dụ
`/127.0.0.1:50848`.

## 8. Sau khi kết nối, vì sao nhập tên là vào được chat?

Tên hiển thị được gửi trong request `JOIN_REQUEST`.

### Phía client

Tại `LoginFrame.java:118-119`:

```java
connectingClient.send(
    Message.of(MessageType.JOIN_REQUEST).put("name", name)
);
```

### Phía server

`ClientHandler.handle()` nhận loại request tại `ClientHandler.java:46-67` và
chuyển `JOIN_REQUEST` vào hàm `join()`.

Hàm `join()` tại `ClientHandler.java:76-92` thực hiện:

1. Kiểm tra client chưa tham gia trước đó.
2. Lấy tên từ field `name`.
3. Kiểm tra tên dài tối đa 32 ký tự và không chứa ký tự không hợp lệ.
4. Tìm hoặc tạo user trong database.
5. Gắn user vào session hiện tại.
6. Kiểm tra trùng tên đang online.
7. Gửi `JOIN_RESPONSE` về client.
8. Broadcast trạng thái online cho các client khác.

Client nhận `JOIN_RESPONSE` tại `LoginFrame.java:129-135`. Nếu thành công,
client đóng cửa sổ kết nối và mở `ChatFrame`.

Không có bước kiểm tra password hay đăng ký tài khoản trong luồng này.

## 9. Nếu thầy hỏi: "Server gửi dữ liệu ngược lại client ở đâu?"

Server gửi qua chính TCP socket đã được client mở từ trước.

Trong `server/.../session/Session.java`:

```java
public synchronized void send(Message message) throws IOException {
    FrameCodec.write(output, message);
}
```

Vị trí: `Session.java:30-33`.

Phía client luôn có `readLoop()` đọc socket:

```java
while (running.get() && (message = FrameCodec.read(input)) != null) {
    messageListener.accept(message);
}
```

Vị trí: `client/.../network/ChatClient.java:45-52`.

Như vậy server không gọi `new Socket()` đến client. Server dùng output stream
của session tương ứng để gửi ngược trên kết nối đã có.

## 10. Luồng nhắn tin riêng

### Client gửi tin

Trong `client/.../ui/ChatFrame.java`, khi bấm gửi:

```java
client.send(Message.of(MessageType.PRIVATE_MESSAGE)
    .put("receiver", receiver)
    .put("content", content));
```

Vị trí: `ChatFrame.java:267`.

### Server chuyển tiếp

Trong `ClientHandler.privateMessage()` tại `ClientHandler.java:99-110`:

1. Kiểm tra người gửi đã join.
2. Đọc tên người nhận và nội dung.
3. Tìm session của người nhận bằng `sessions.byUsername(receiver)`.
4. Lưu tin nhắn vào database.
5. Tạo event `PRIVATE_MESSAGE`.
6. Gửi event cho session người nhận bằng `target.send(event)`.
7. Gửi phản hồi `Delivered` cho người gửi.

Đường đi là:

```text
Client A ChatFrame
  -> ChatClient.send()
  -> Server ClientHandler.privateMessage()
  -> target.send()
  -> Client B ChatClient.readLoop()
  -> ChatFrame.handle()
```

## 11. Luồng truyền file hiện tại

File cũng là Client-Server, không phải truyền trực tiếp P2P.

### Bước 1: gửi lời mời file

Client A gửi `FILE_OFFER` từ `ChatFrame.java:293`.

Server xử lý tại `ClientHandler.fileOffer()` ở `ClientHandler.java:146-159`:

- Kiểm tra kích thước file.
- Tìm client nhận.
- Gửi lời mời file cho client nhận bằng `target.send(offer)`.

### Bước 2: chuyển từng chunk

Client A đọc file và gửi các message `FILE_CHUNK` tại
`ChatFrame.java:345-355`.

Server nhận và chuyển tiếp tại `ClientHandler.fileEvent()` ở
`ClientHandler.java:161-177`:

```java
target.send(request.put("from", session.user().username()));
```

Client B đọc các chunk từ `ChatClient.readLoop()` rồi ghi xuống file local.

Sơ đồ:

```text
Client A đọc file
  -> FILE_OFFER / FILE_CHUNK / FILE_COMPLETE
  -> TCP socket A - Server
  -> ClientHandler.fileEvent()
  -> TCP socket Server - Client B
  -> Client B ghi file vào thư mục downloads
```

Khi thầy hỏi có `new Socket()` giữa hai client không, trả lời: **không**.
Trong source client chỉ có `new Socket()` ở `ChatClient.connect()`, và hàm đó
chỉ nhận `host` của server.

### Nếu thầy thấy `FILE_SHARE`, `peerIp`, `peerPort` thì trả lời sao?

Đây là phần protocol/database được chuẩn bị cho chức năng tìm kiếm file, nhưng
UI client hiện tại không gọi `FILE_SHARE` và không mở peer server. Luồng gửi
file đang được sử dụng trong `ChatFrame` là `FILE_OFFER -> FILE_CHUNK ->
FILE_COMPLETE`, tất cả đều được `ClientHandler` chuyển tiếp qua server.

Vì vậy kiến trúc đang chạy và đem đi demo vẫn là Client-Server. Các field
`peerIp/peerPort` chỉ là phần mở rộng chưa được kích hoạt, không tạo kết nối
trực tiếp giữa hai client.

## 12. Nếu thầy hỏi: "Server biết client nào đang online ở đâu?"

- `SessionManager` lưu các session đang hoạt động.
- `sessions.add(session)` được gọi khi client join thành công.
- `sessions.byUsername(receiver)` tìm session theo tên.
- `sessions.usernames()` lấy danh sách tên online.
- `Session.remoteAddress()` giữ địa chỉ socket của client.
- `ClientHandler.disconnect()` xóa session khi client đóng kết nối.

Dashboard server nhận các sự kiện từ `ServerObserver` và hiển thị trong
`server/.../ServerDashboard.java`:

- `serverStarted(...)`: port và database.
- `clientConnected(...)`: socket vừa kết nối.
- `clientJoined(...)`: tên client sau khi join.
- `requestReceived(...)`: loại request đang xử lý.
- `clientDisconnected(...)`: client ngắt kết nối.

## 13. Nếu thầy hỏi: "Protocol nằm ở đâu?"

Protocol dùng chung nằm trong module `common`:

- `Message.java`: object chứa `type`, `requestId`, `success`, `message` và data.
- `MessageType.java`: các loại như `JOIN_REQUEST`, `PRIVATE_MESSAGE`,
  `FILE_CHUNK`, `FILE_COMPLETE`.
- `JsonCodec.java`: chuyển message thành JSON và ngược lại.
- `FrameCodec.java`: đóng gói frame để gửi qua TCP.

Client và server dùng cùng protocol nên hai bên hiểu cùng một cấu trúc message.

## 14. Nếu thầy hỏi: "Tại sao dùng TCP?"

TCP phù hợp vì chat và truyền file cần:

- Kết nối ổn định giữa client và server.
- Đảm bảo message đến đúng thứ tự.
- Tự phát hiện mất kết nối.
- Có stream để truyền nhiều frame liên tục.

Trong project, `Socket`, `DataInputStream`, `DataOutputStream` và
`FrameCodec` thực hiện phần truyền TCP.

## 15. Các câu trả lời ngắn nên nhớ

### Server chạy ở đâu?

`ServerMain.java` tạo `ServerSocket` tại port đọc từ
`server/config/server.properties`, mặc định là `5000`.

### Client nhập IP ở đâu?

Ô `hostField` trong `LoginFrame.java`. Khi bấm vào phòng, giá trị này được
truyền vào `ChatClient.connect(host, SERVER_PORT)`.

### Client nhập port ở đâu?

Port hiện được cố định ở `LoginFrame.SERVER_PORT = 5000`, không phải nhập trên
UI. Port phải giống `server.port=5000` của server.

### Client có nhập IP của chính nó không?

Không. Client chỉ nhập IP của máy chạy server. Server tự lấy địa chỉ client từ
`socket.getRemoteSocketAddress()`.

### Client A có kết nối trực tiếp Client B không?

Không. Client A và Client B đều kết nối đến server. Server chuyển tiếp tin
nhắn và file qua hai socket TCP đó.

### Hàm nào gửi message?

Client dùng `ChatClient.send()`. Server dùng `Session.send()`.

### Hàm nào nhận message?

Client dùng `ChatClient.readLoop()`. Server dùng `Session.read()` trong vòng
lặp của `ClientHandler.run()`.

## 16. Kịch bản chỉ code khi demo

1. Mở `server/config/server.properties` và chỉ vào `server.port=5000`.
2. Mở `ServerMain.java:41` và chỉ vào `new ServerSocket(config.port())`.
3. Chạy server, chỉ vào log `MiniChat server listening on port 5000`.
4. Mở `LoginFrame.java:14` và chỉ vào ô IP mặc định.
5. Mở `LoginFrame.java:12` và chỉ vào port client `5000`.
6. Bấm vào phòng chat, chỉ vào `LoginFrame.java:118-119`.
7. Chỉ vào `ChatClient.java:33` để giải thích client tạo TCP socket.
8. Chỉ vào `ServerMain.java:47` để giải thích server nhận socket.
9. Chỉ vào `ClientHandler.java:53` và `join()` để giải thích client gửi tên.
10. Nhắn tin, chỉ vào `ClientHandler.privateMessage()` để cho thấy server
    nhận, tìm người nhận và chuyển tiếp.
11. Gửi file, chỉ vào `ClientHandler.fileEvent()` để chứng minh chunk đi qua
    server, không đi trực tiếp giữa hai client.
