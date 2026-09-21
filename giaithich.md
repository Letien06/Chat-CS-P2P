> CẬP NHẬT KIẾN TRÚC SAU KHI HOÀN THIỆN: Project đã có UI phòng chat và file directory. Server chỉ làm discovery/signaling; chat vẫn dùng kênh TCP control tới server, còn nội dung file đi trực tiếp qua peer socket TCP. `FILE_CHUNK` không còn được sử dụng.

1. Nhìn Tổng Quát
MiniChat là ứng dụng theo mô hình:
Nhiều Client
     │
     │ TCP port 5000
     ▼
Một Server trung tâm
     │
     ▼
SQLite Database
Ví dụ:
Client Windows: An ──┐
Client Windows: Bình ├── TCP ── Server ── SQLite
Client Ubuntu: Nam ──┘
Mỗi client chỉ kết nối với server. Khi An gửi tin cho Nam:
An → Server → Nam
File cũng đi theo đường:
Máy An → Server → Máy Nam
2. Ý Nghĩa Các Vùng Trong Hình
- Màu xanh dương – Client UI: giao diện nhập tên/IP và màn hình chat.
- Màu xanh lá – Server Runtime: nhận kết nối, quản lý client và chuyển tiếp dữ liệu.
- Màu cam – Shared Protocol: định dạng message dùng chung giữa client và server.
- Màu đỏ – Persistence: SQLite lưu người dùng, phòng, tin nhắn và thông tin file.
Luồng đầy đủ trong hình có thể hiểu là:
Người dùng
→ LoginFrame
→ ChatClient
→ Message
→ JsonCodec
→ FrameCodec
→ TCP Socket
→ ServerMain
→ ClientHandler
→ SessionManager / Database
→ ClientHandler của người nhận
→ TCP Socket
→ ChatClient người nhận
→ ChatFrame
3. P2P Trong Project Này Là Gì?
P2P thật sự thường có dạng:
Client A ─────────────── Client B
          kết nối trực tiếp
Nhưng project hiện tại hoạt động như sau:
Client A ── Server ── Client B
Do đó, đây chính xác hơn là mô hình client-server có chức năng giao tiếp giữa các peer, chứ chưa phải P2P trực tiếp hoàn toàn.
Server đang:
- Giữ kết nối của tất cả client.
- Biết client nào online.
- Nhận và chuyển tiếp tin nhắn.
- Nhận và chuyển tiếp từng phần của file.
- Lưu lịch sử tin nhắn vào SQLite.
Nếu server tắt thì các client không thể chat với nhau.
4. Module Và Package
Maven module
Project có ba module chính được khai báo tại D:\AVKU\Lập trình mạng\chat_message\pom.xml:14:
chat_message
├── common
├── server
└── client
- common: code mà cả server và client đều cần.
- server: chương trình server TCP.
- client: chương trình giao diện MiniChat.
Java package
Package là cách Java tổ chức class:
package com.p2p.client.ui;
Tương ứng thư mục:
com/p2p/client/ui
Các package chính:
com.p2p.common.protocol
com.p2p.server
com.p2p.server.config
com.p2p.server.database
com.p2p.server.session
com.p2p.client
com.p2p.client.network
com.p2p.client.ui
5. Module common
Đây là “ngôn ngữ chung” để client và server hiểu nhau.
MessageType.java
D:\AVKU\Lập trình mạng\chat_message\common\src\main\java\com\p2p\common\protocol\MessageType.java:4
Enum này liệt kê tất cả loại message:
JOIN_REQUEST
JOIN_RESPONSE
USER_LIST_REQUEST
PRIVATE_MESSAGE
FILE_OFFER
FILE_CHUNK
FILE_COMPLETE
ERROR
Các nhóm chính:
Nhóm	Message
Tham gia	JOIN_REQUEST, JOIN_RESPONSE
Online	USER_LIST_REQUEST, USER_LIST_RESPONSE, USER_STATUS_CHANGED
Chat	PRIVATE_MESSAGE, GROUP_MESSAGE
Phòng	CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, ROOM_UPDATED
File	FILE_OFFER, FILE_ACCEPT, FILE_CHUNK, FILE_COMPLETE
Chia sẻ file	FILE_SHARE, FILE_UNSHARE, FILE_SEARCH_REQUEST
Hệ thống	PING, PONG, ERROR


ClientHandler nhìn vào trường này để biết phải xử lý yêu cầu bằng hàm nào.
Message.java
D:\AVKU\Lập trình mạng\chat_message\common\src\main\java\com\p2p\common\protocol\Message.java:8
Mọi dữ liệu trao đổi đều được bọc trong một Message:
private MessageType type;
private String requestId;
private Map<String, Object> payload;
Ví dụ gửi tin nhắn:
{
  "type": "PRIVATE_MESSAGE",
  "requestId": "uuid-ngau-nhien",
  "payload": {
    "receiver": "Nam",
    "content": "Xin chào"
  }
}
Ý nghĩa:
- type: message dùng để làm gì.
- requestId: định danh request.
- payload: nội dung thực tế.
Cách tạo message:
Message.of(MessageType.PRIVATE_MESSAGE)
    .put("receiver", "Nam")
    .put("content", "Xin chào");
Các hàm string(), longValue() và bool() giúp lấy dữ liệu từ payload đúng kiểu.
JsonCodec.java
D:\AVKU\Lập trình mạng\chat_message\common\src\main\java\com\p2p\common\protocol\JsonCodec.java:10
Class này dùng Jackson để:
Message Java → JSON bytes
JSON bytes → Message Java
Client và server phải dùng cùng class này để không bị khác định dạng JSON.
FrameCodec.java
D:\AVKU\Lập trình mạng\chat_message\common\src\main\java\com\p2p\common\protocol\FrameCodec.java:10
TCP chỉ truyền một dòng byte liên tục. Nó không tự biết message kết thúc ở đâu.
Vì vậy mỗi message được đóng gói:
[4 byte độ dài][JSON payload]
Ví dụ JSON dài 120 byte:
[00 00 00 78][120 byte JSON]
Khi đọc:
1. Đọc 4 byte đầu để biết độ dài.
2. Đọc đúng số byte đó.
3. Dùng JsonCodec chuyển thành Message.
Giới hạn một frame là 10 MB. File lớn không gửi trong một frame mà được chia thành các chunk nhỏ.
6. Server Hoạt Động Như Thế Nào?
ServerMain.java
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ServerMain.java:18
Đây là điểm bắt đầu của server.
Server khởi tạo:
Database database;
SessionManager sessions;
ExecutorService clients;
ServerSocket serverSocket;
Tại D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ServerMain.java:32, server mở cổng:
serverSocket = new ServerSocket(config.port());
Vì không chỉ định một địa chỉ IP cụ thể, server lắng nghe trên mọi network interface của máy.
Sau đó:
serverSocket.accept()
→ nhận client mới
→ tạo ClientHandler
→ đưa ClientHandler vào thread pool
Mỗi client có một ClientHandler riêng nên nhiều client có thể kết nối cùng lúc.
ServerConfig.java
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\config\ServerConfig.java:9
Class này đọc cấu hình server:
- Port.
- Đường dẫn database.
- Số client tối đa.
- Kích thước file tối đa.
Cấu hình thực tế nằm tại:
D:\AVKU\Lập trình mạng\chat_message\server\config\server.properties:1
server.port=5000
database.path=data/p2p-chat.db
max.clients=100
max.file.size=2147483648
Giới hạn file 2147483648 byte tương đương 2 GB.
Session.java
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\session\Session.java:13
Một Session đại diện cho một client đang kết nối.
Nó giữ:
Socket socket;
DataInputStream input;
DataOutputStream output;
Database.User user;
Ví dụ:
Session 1 → socket của An
Session 2 → socket của Bình
Session 3 → socket của Nam
read() đọc message từ client:
return FrameCodec.read(input);
send() gửi message xuống client:
FrameCodec.write(output, message);
Sau khi client gửi JOIN_REQUEST, Session.user mới được gán. Trước đó client đã kết nối TCP nhưng chưa được phép chat.
SessionManager.java
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\session\SessionManager.java:10
Đây là danh bạ những client đang online:
ConcurrentHashMap<Long, Session> byId;
Nó hỗ trợ:
- add(): thêm client online.
- remove(): xóa client đã ngắt kết nối.
- byId(): tìm client theo ID.
- byUsername(): tìm client theo tên.
- usernames(): lấy danh sách người đang online.
- broadcast(): gửi message cho mọi client.
Ví dụ An gửi cho Nam:
sessions.byUsername("Nam")
→ lấy Session của Nam
→ target.send(message)
ConcurrentHashMap được dùng vì nhiều ClientHandler có thể chạy đồng thời trên nhiều thread.
ClientHandler.java
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:17
Đây là class quan trọng nhất của server.
Mỗi kết nối client chạy vòng lặp:
while ((message = session.read()) != null) {
    handle(message);
}
Tại D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:43, server phân loại request:
switch (request.getType()) {
    case JOIN_REQUEST -> join(request);
    case PRIVATE_MESSAGE -> privateMessage(request);
    case CREATE_ROOM -> createRoom(request);
    case FILE_OFFER -> fileOffer(request);
    case FILE_CHUNK -> fileEvent(request);
}
Vì vậy các hộp trong hình như:
Private Chat
Presence Handling
Room Chat
File Directory
File Relay
không phải năm file Java riêng. Chúng đều là các nhóm hàm nằm bên trong ClientHandler.java.
7. Luồng Tham Gia Chat
Client gửi:
{
  "type": "JOIN_REQUEST",
  "payload": {
    "name": "Nam"
  }
}
Server xử lý tại D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:70:
1. Kiểm tra client đã tham gia chưa.
2. Kiểm tra tên hợp lệ.
3. Tìm hoặc tạo user trong database.
4. Gắn user vào Session.
5. Thêm session vào SessionManager.
6. Kiểm tra tên đó có đang online không.
7. Trả JOIN_RESPONSE.
8. Broadcast trạng thái online.
Luồng:
LoginFrame
→ JOIN_REQUEST(name)
→ ClientHandler.join()
→ Database.findOrCreateUser()
→ Session.join()
→ SessionManager.add()
→ JOIN_RESPONSE
→ LoginFrame mở ChatFrame
Tên được giới hạn 1–32 ký tự. Hai người không thể online cùng lúc bằng cùng một tên.
Không còn username/password/register. Các cột password trong database chỉ là cấu trúc cũ và đang được lưu chuỗi rỗng.
8. Presence – Trạng Thái Online
Khi user tham gia, server broadcast:
{
  "type": "USER_STATUS_CHANGED",
  "payload": {
    "username": "Nam",
    "online": true
  }
}
Khi user tắt client hoặc mất kết nối:
{
  "type": "USER_STATUS_CHANGED",
  "payload": {
    "username": "Nam",
    "online": false
  }
}
Việc xóa session và thông báo offline nằm tại:
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:203
Client có thể gửi USER_LIST_REQUEST. Server lấy danh sách từ SessionManager.usernames() và trả USER_LIST_RESPONSE.
9. Luồng Chat Riêng
Khi An chọn Nam và nhập "Xin chào":
Bước 1: UI tạo message
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:266
client.send(
    Message.of(MessageType.PRIVATE_MESSAGE)
        .put("receiver", receiver)
        .put("content", content)
);
Bước 2: ChatClient gửi qua TCP
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\network\ChatClient.java:39
Message
→ FrameCodec.write()
→ JSON
→ Socket
Bước 3: Server xử lý
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:92
Server:
1. Lấy receiver.
2. Lấy content.
3. Tìm session của Nam.
4. Lưu tin nhắn vào SQLite.
5. Gửi message đến session của Nam.
6. Trả xác nhận cho An.
An
→ PRIVATE_MESSAGE
→ ClientHandler
→ Database.savePrivateMessage()
→ SessionManager.byUsername("Nam")
→ Session Nam
→ Nam
Bước 4: Client Nam hiển thị
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:433
ChatFrame.handle() nhận PRIVATE_MESSAGE rồi thêm nó vào đúng cuộc trò chuyện.
10. Luồng Gửi File
Giai đoạn 1: Chuẩn bị file
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:286
Client gửi tính:
- Tên file.
- Kích thước.
- SHA-256.
- transferId.
Sau đó gửi FILE_OFFER.
An → FILE_OFFER → Server → Nam
Giai đoạn 2: Người nhận chấp nhận
Nam nhấn “Tải xuống”:
Nam → FILE_ACCEPT → Server → An
Code nằm tại:
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:318
File sẽ được lưu vào thư mục:
downloads/
Giai đoạn 3: Chia file thành chunk
Khi nhận FILE_ACCEPT, máy gửi bắt đầu đọc file tại:
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:344
Mỗi chunk có kích thước:
48 * 1024 byte
Tức khoảng 48 KB.
Mỗi chunk được chuyển thành Base64:
Base64.getEncoder().encodeToString(chunk)
Sau đó gửi:
{
  "type": "FILE_CHUNK",
  "payload": {
    "receiver": "Nam",
    "transferId": "...",
    "sequence": 0,
    "data": "base64..."
  }
}
Giai đoạn 4: Server chuyển tiếp
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:154
Server không lưu nội dung chunk. Nó tìm người nhận và chuyển tiếp:
target.send(request);
Luồng thực tế:
An đọc file
→ FILE_CHUNK
→ Server
→ FILE_CHUNK
→ Nam ghi xuống ổ đĩa
Giai đoạn 5: Hoàn thành
An gửi FILE_COMPLETE.
Nam:
1. Đóng file.
2. Kiểm tra số byte nhận được.
3. Tính lại SHA-256.
4. So sánh với SHA-256 ban đầu.
5. Nếu đúng thì báo tải thành công.
6. Nếu sai thì xóa file lỗi.
Phần kiểm tra nằm tại:
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:397
11. Database Và Các Bảng
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\database\Database.java:12
Server sử dụng SQLite JDBC. File database mặc định:
data/p2p-chat.db
users
Lưu người dùng:
id
username
password_hash
password_salt
created_at
last_login
Hiện tại không còn đăng nhập bằng mật khẩu nên password_hash và password_salt được lưu rỗng.
chat_rooms
Lưu phòng chat:
id
name
owner_id
created_at
room_members
Quan hệ giữa user và phòng:
room_id
user_id
Một phòng có nhiều user, một user có thể vào nhiều phòng.
chat_messages
Lưu tin nhắn:
sender_id
receiver_id
room_id
content
created_at
Chat riêng:
receiver_id có giá trị
room_id = null
Chat nhóm:
receiver_id = null
room_id có giá trị
shared_files
Lưu thông tin file được công bố để tìm kiếm:
owner_id
file_name
file_size
sha256
peer_ip
peer_port
file_transfers
Được tạo để lưu lịch sử truyền file, nhưng code hiện tại chưa có hàm ghi dữ liệu vào bảng này.
Vì vậy mũi tên “records transfers” trong hình mới thể hiện thiết kế dự kiến, chưa phải hành vi thực tế.
12. Phần Client
ClientMain.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ClientMain.java:9
Đây là điểm chạy đầu tiên của client.
Nó:
1. Khởi tạo giao diện FlatLaf.
2. Cài màu, bo góc, scrollbar.
3. Chạy Swing trên Event Dispatch Thread.
4. Mở LoginFrame.
LoginFrame.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\LoginFrame.java:11
Đây không còn là màn hình đăng nhập tài khoản. Nó chỉ nhận:
- Tên hiển thị.
- IP server.
Port được cố định:
private static final int SERVER_PORT = 5000;
Khi nhấn “Vào phòng chat”:
Tạo ChatClient
→ connect(IP, 5000)
→ gửi JOIN_REQUEST
→ chờ JOIN_RESPONSE
→ mở ChatFrame
ChatClient.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\network\ChatClient.java:17
Đây là lớp mạng của client, tách riêng khỏi giao diện.
Nó giữ:
Socket
DataInputStream
DataOutputStream
reader thread
messageListener
connect() tạo TCP socket tới server.
send() dùng FrameCodec.write().
readLoop() liên tục chờ message từ server:
while (...) {
    message = FrameCodec.read(input);
    messageListener.accept(message);
}
Reader chạy trên thread riêng để giao diện Swing không bị đứng khi chờ dữ liệu mạng.
ChatFrame.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\ChatFrame.java:28
Đây là màn hình chat chính.
Nó quản lý:
- Danh sách user online.
- Người đang được chọn.
- Lịch sử tạm thời của từng cuộc chat.
- Tin nhắn bubble.
- Chọn file.
- File offer.
- Tiến trình gửi/nhận.
- Thread xử lý file.
- Thư mục download.
Các map quan trọng:
outgoing
incoming
pendingOffers
fileCards
conversationHistory
Ý nghĩa:
- outgoing: file đang chờ gửi.
- incoming: file đang nhận.
- pendingOffers: lời mời tải file chưa được chấp nhận.
- fileCards: card giao diện của từng file.
- conversationHistory: lịch sử hiển thị trong lần chạy hiện tại.
Lưu ý: conversationHistory chỉ nằm trong RAM của client. Khi tắt client, giao diện chưa tải lại lịch sử từ database dù server đã lưu tin nhắn.
Theme.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\Theme.java:10
Chứa thiết kế dùng chung:
- Màu nền.
- Màu chính.
- Màu chữ.
- Font.
- Button.
- Panel bo góc.
- Avatar.
- Trạng thái online.
Nhờ đó LoginFrame và ChatFrame không phải tự khai báo lại màu và style nhiều lần.
AppIcon.java
D:\AVKU\Lập trình mạng\chat_message\client\src\main\java\com\p2p\client\ui\AppIcon.java:16
Tự vẽ icon bằng Java Graphics:
SEND
ATTACHMENT
DOWNLOAD
OPEN_FILE
REFRESH
CHAT
FOLDER
SETTINGS
LOGOUT
Không cần tải file PNG cho từng icon.
13. Room Chat Và File Directory
Server đã có code nền tảng cho:
- Tạo phòng.
- Vào phòng.
- Rời phòng.
- Gửi tin nhắn nhóm.
- Đăng ký file chia sẻ.
- Tìm kiếm file.
Code server nằm tại:
D:\AVKU\Lập trình mạng\chat_message\server\src\main\java\com\p2p\server\ClientHandler.java:105
Tuy nhiên, ChatFrame hiện tại chủ yếu sử dụng:
- Danh sách online.
- Chat riêng.
- Gửi file trực tiếp qua server.
UI chưa có đầy đủ màn hình để tạo phòng, tham gia phòng hoặc tìm file chia sẻ. Vì vậy các hộp “Room Chat” và “File Directory” trong sơ đồ là chức năng đã có một phần ở protocol/server nhưng chưa hoàn chỉnh ở giao diện.
14. Những Điểm Sơ Đồ Chưa Hoàn Toàn Chính Xác
- Private Chat, Presence Handling, Room Chat, File Directory và File Relay không phải các file riêng; chúng đều nằm trong ClientHandler.java.
- “Login Screen” thực chất hiện là màn hình nhập tên và IP, không có mật khẩu.
- File hiện được relay qua server, không truyền trực tiếp giữa hai client.
- Bảng file_transfers tồn tại nhưng chưa được ghi dữ liệu.
- Room và file search đã có protocol/server nhưng chưa có UI hoàn chỉnh.
- Database lưu tin nhắn nhưng client chưa có chức năng tải lịch sử cũ.
- Tên project chứa P2P, nhưng kết nối hiện tại vẫn phụ thuộc hoàn toàn vào server.
15. Cách Trình Bày Ngắn Gọn Với Thầy
Bạn có thể nói:
MiniChat là ứng dụng chat Java chạy trên TCP theo mô hình một server và nhiều client. Client sử dụng Java Swing để hiển thị giao diện, còn ChatClient quản lý socket TCP. Dữ liệu được đóng gói dưới dạng Message, chuyển thành JSON và thêm 4 byte độ dài bằng FrameCodec. Server nhận từng kết nối trong ServerMain, tạo một ClientHandler cho mỗi client và quản lý người đang online bằng SessionManager. Tin nhắn riêng và file đều được gửi lên server, sau đó server tìm Session của người nhận để chuyển tiếp. SQLite được dùng để lưu user, phòng và lịch sử tin nhắn. Mặc dù tên project là P2P, phiên bản hiện tại là server-mediated P2P vì dữ liệu vẫn đi qua server trung tâm.



12:22








đùa bạn ơi, p2p sao đi qua server nhỉ, có cần ko, dự án của tôi đang làm p2p như nào







12:25






Bạn đã dừng sau 30 giây







Bạn nói đúng: code hiện tại chưa phải P2P trực tiếp. Mình gọi chính xác lại như sau:
Project hiện tại là mô hình client-server, có ý tưởng P2P nhưng dữ liệu vẫn đi qua server.

Hiện tại dự án đang chạy thế nào?
Chat
Client A → Server → Client B
Trong ClientHandler.java, server nhận:
case PRIVATE_MESSAGE -> privateMessage(request);
Sau đó tìm người nhận:
Session target = sessions.byUsername(receiver);
target.send(event);
Vì vậy tin nhắn không đi trực tiếp từ A sang B.
File
Hiện tại cũng đi qua server:
A đọc file
→ FILE_CHUNK
→ Server
→ FILE_CHUNK
→ B ghi file
Trong ClientHandler.java, hàm fileEvent() nhận chunk rồi gọi:
target.send(request);
Đây là relay server, không phải P2P trực tiếp.
Vậy tại sao project tên là P2P?
Tên P2P Chat hiện phản ánh ý tưởng ban đầu hoặc mục tiêu của project, nhưng implementation hiện tại mới là:
Server-mediated P2P
Có nghĩa là:
- Các client là các peer.
- Server biết các peer đang online.
- Nhưng server vẫn trung chuyển dữ liệu giữa các peer.
Đây còn được gọi là hybrid P2P hoặc P2P có server điều phối.
Một số hệ thống P2P thực tế vẫn cần server để:
- Tìm peer.
- Trao đổi IP và port.
- Xác thực.
- Báo trạng thái online.
- Vượt NAT hoặc firewall.
- Tạo phòng.
Nhưng điểm quan trọng là server chỉ làm nhiệm vụ điều phối, còn dữ liệu chính phải đi trực tiếp giữa hai client.
P2P thật sự nên hoạt động thế nào?
Kiến trúc đúng hơn:
                    JOIN / DISCOVERY
Client A ───────────────────────────┐
                                    ▼
                                  Server
                                    ▲
Client B ───────────────────────────┘

                 DATA CONNECTION
Client A ◄────────────────────────► Client B
Luồng gửi tin:
1. A kết nối server và báo A đang online.
2. B kết nối server và báo B đang online.
3. A hỏi server thông tin của B.
4. Server trả về IP và port của B.
5. A mở socket trực tiếp đến B.
6. A gửi tin trực tiếp cho B.
7. Server không nhận nội dung tin nhắn.
Luồng gửi file:
A → Server: "Tôi muốn gửi file cho B"
Server → B: "A muốn gửi file"
B → Server: "B đồng ý"
Server → A: "B đã đồng ý"
A ───────────────► B
   truyền file trực tiếp
Server lúc này chỉ làm signaling server, không relay nội dung.
Dự án hiện tại đã có một phần nền tảng P2P chưa?
Có một phần. Trong ClientHandler.java, chức năng FILE_SHARE lưu:
peerIp
peerPort
fileName
fileSize
sha256
Nó nằm trong bảng shared_files của SQLite.
Điều này cho thấy project đã có ý tưởng:
Server lưu thông tin:
File X nằm ở IP nào, port nào
Nhưng hiện tại client chưa thực sự mở một server socket để peer khác kết nối trực tiếp lấy file.
Ngoài ra, chức năng gửi file chính vẫn dùng:
FILE_OFFER
FILE_ACCEPT
FILE_CHUNK
FILE_COMPLETE
và các FILE_CHUNK vẫn đi qua server.
Vì vậy:
FILE_SHARE + peerIp + peerPort
mới chỉ là thông tin định vị peer, chưa phải truyền P2P hoàn chỉnh.
Có cần server không?
Trường hợp 1: P2P hoàn toàn, không cần server
Các client tự tìm nhau bằng:
- UDP broadcast.
- UDP multicast.
- Nhập IP thủ công.
- Quét các máy trong mạng LAN.
Ví dụ:
Client A broadcast: "Tôi là An"
Client B nhận được: "Tôi là Bình"
A và B kết nối trực tiếp.
Nhược điểm:
- Khó quản lý danh sách online.
- Khó tạo phòng.
- Khó lưu lịch sử.
- Khó chạy qua Internet.
- Khó xử lý firewall/NAT.
- Mỗi client phải vừa là client vừa là server.
Trường hợp 2: Có server điều phối, dữ liệu P2P
Đây là phương án phù hợp nhất với dự án của bạn:
Server:
- Danh sách online.
- Trao đổi IP/port.
- Xác nhận kết nối.
- Tạo phòng.
- Có thể lưu lịch sử.

Client:
- Tự mở port lắng nghe.
- Chat trực tiếp với client khác.
- Gửi file trực tiếp.
Server không chuyển tiếp nội dung chat hoặc file; server chỉ làm nhiệm vụ điều phối.

Đây là mô hình phù hợp với yêu cầu “Client-Server kết hợp” hoặc mô hình hybrid P2P.

## 16. Kết Luận Về Phiên Bản Hiện Tại

Code hiện tại đang là:

```text
Client-Server + chat + truyền file qua server
```

Nó chưa phải P2P trực tiếp vì `PRIVATE_MESSAGE` và `FILE_CHUNK` đều đi qua `ClientHandler` trên server.

Nếu muốn biến thành P2P thật hơn, cần thêm một kết nối trực tiếp giữa các client:

```text
Server: discovery, online, signaling
Client A <──────── TCP trực tiếp ────────> Client B
```

Khi đó server chỉ trao đổi IP/port và trạng thái kết nối; nội dung tin nhắn hoặc file sẽ truyền trực tiếp giữa hai peer.

Với project hiện tại, phương án an toàn nhất là giữ server để quản lý danh sách online và signaling, sau đó chuyển riêng phần truyền file sang socket trực tiếp giữa hai client.
