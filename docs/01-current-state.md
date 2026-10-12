# 01 — Hiện trạng source và khoảng cách tới mạng xã hội

Kiểm tra ngày 07/10/2026 bằng source đang có, bao gồm thay đổi cục bộ trong `ClientHandler.java`. Đây là audit tĩnh; không khẳng định đã chạy lại build, GUI hay benchmark. Không chỉnh source Java trong đợt viết kế hoạch này.

## 1. Những phần có thật

Đường dẫn dưới đây tính từ thư mục `chat_message`; số dòng thuộc thời điểm audit.

| Thành phần | Có thật | Bằng chứng |
|---|---|---|
| Build | Maven common/server/client, Java 17, shaded JAR | `pom.xml` và pom từng module |
| UI | Swing + FlatLaf, vào bằng tên/IP, chat riêng/file | `client/.../ui/LoginFrame.java:109–119`, `ChatFrame.java:428–444` |
| TCP | Socket/ServerSocket, reader background, một handler/socket | `ChatClient.java:30–35`, `ServerMain.java:41–50` |
| Frame | 4-byte big-endian length + JSON UTF-8; max frame 10 MiB | `common/.../FrameCodec.java:9–30` |
| Correlation | Message type, UUID requestId, payload map | `common/.../Message.java:9–24` |
| Danh tính | JOIN tên, tìm hoặc tạo user, chặn tên trùng online | `ClientHandler.java:76–91` |
| Presence | Danh sách session, broadcast online/offline | `ClientHandler.java:91,94–96,210–215` |
| Private chat | Lưu DB rồi gửi user online; offline bị từ chối | `ClientHandler.java:99–109` |
| Room backend | Create/join/leave/group message; gửi phải là member | `ClientHandler.java:112–143` |
| File | Offer/accept/reject, Base64 chunk 48 KiB, relay qua server | `ClientHandler.java:146–177`, `ChatFrame.java:339–356` |
| Nhận file | Worker ghi file, kiểm size và hash nếu được cung cấp | `ChatFrame.java:372–411` |
| SQLite | FK + WAL + prepared statements, method synchronized | `Database.java:18–22,40–134` |
| Test source | Unicode roundtrip; 2 socket join/chat/relay 120 KB | `FrameCodecTest.java:14–22`, `LocalConnectionIntegrationTest.java:34–116` |
| Dashboard | Port, DB, client/log; có chế độ no-ui | `ServerMain.java:68–86`, `ServerDashboard.java` |

Tên file nguồn đầy đủ nằm trong các thư mục `server/src/main/java/com/p2p/server`, `client/src/main/java/com/p2p/client`, `common/src/main/java/com/p2p/common/protocol`. Test nằm dưới `src/test/java`.

## 2. Phần chưa có hoặc chỉ có một phần

- **Không có auth thật:** `findOrCreateUser` ghi password hash/salt rỗng (`Database.java:49–65`). Người khác dùng lại tên user đang offline sẽ dùng cùng user record. Không coi đây là đăng nhập tài khoản.
- **Không có UDP/P2P implementation:** chưa thấy DatagramSocket/UDP receiver hay socket client-client. Package `com.p2p` không chứng minh P2P.
- **Room chưa thành sản phẩm GUI:** enum/server có group nhưng dispatcher GUI chưa có màn hình/luồng group. File share/search cũng chưa hoàn thiện giao diện.
- **Lịch sử chưa tải được:** DB lưu message nhưng chưa API truy vấn history; GUI giữ conversation map trong RAM (`ChatFrame.java:53,252`).
- **File chưa bền vững ở server:** server relay, không có kho media để tải khi người gửi offline. Bảng file_transfers được tạo nhưng chưa có CRUD sử dụng.
- **Chưa có:** profile, friendship/block, post/feed, privacy, comment/like, notification, báo cáo, admin phân quyền.
- **Test file chưa chứng minh SHA-256 receiver GUI:** integration test dùng hash mẫu `test-hash`, so bytes relay. Không được mô tả như test end-to-end xác minh checksum thật.
- `plan.md` cũ nói PBKDF2, PasswordHasher và P2P; các phần đó không hiện diện trong bộ source audit. Bản cũ chỉ dùng làm lịch sử.

## 3. Lỗi nền và tác động

| Mức | Vấn đề | Tác động | Hướng xử lý |
|---|---|---|---|
| P0 | JOIN không xác thực | Chiếm danh tính, không có owner đáng tin | AuthService, hash, TLS, userId từ session |
| P0 | File event forward bất kỳ theo tên | Giả chunk/cancel, không ràng buộc sender/receiver | Transfer FSM, server storage và ACL |
| P0 | maxClients đếm user đã JOIN | Socket chưa JOIN vô hạn, pool cached không giới hạn | Registry mọi socket, permit, deadline handshake |
| P0 | `FrameCodec.write` static synchronized | Một stream chậm chặn mọi stream cùng JVM | Codec stateless; writer riêng mỗi connection |
| P0 | Session.send trực tiếp, broadcast tuần tự | Recipient chậm chặn sender/server | Queue outbound hữu hạn, backpressure |
| P0 | DB nhiều thao tác chưa transaction | Tạo room có thể không có owner member; race nghiệp vụ | Transaction ở service, constraints |
| P0 | Plain TCP | Khi thêm password sẽ truyền credential không được bảo vệ | TLS trước khi đưa auth vào demo LAN |
| P1 | ACK “Delivered” sau ghi socket | UI báo nhận dù recipient chưa xử lý; retry nhân đôi | STORED ACK + recipient ACK + dedup |
| P1 | Stop chỉ shutdownNow rồi close DB | Blocking read còn sống; DB đóng trước handler | Close sockets, await workers, close DB cuối |
| P1 | close guard running false | Lỗi bind/close trước start có thể giữ DB | Lifecycle NEW/RUNNING/STOPPING/CLOSED |
| P1 | GUI gửi đồng bộ trên EDT | Treo khi mạng chậm/file cùng socket | Client writer queue, worker ngoài EDT |
| P1 | File chunk đi qua EDT | Queue UI giữ nhiều Base64, lag/bộ nhớ tăng | Network dispatch/service riêng, UI throttle |
| P1 | EOF không phát disconnect rõ | UI còn gửi/không có reconnect | Connection state machine và event |
| P1 | PING/PONG có enum nhưng chưa heartbeat client | Session idle/stale; JOIN chờ mãi | Scheduler, timeout response, lifecycle |
| P1 | Nhận file không kiểm sequence/byte limit từng chunk | Ghi vượt offer, phát hiện sai muộn | Offset/window/size/quota kiểm server và client |
| P1 | FILE_CANCEL/cleanup GUI thiếu | File stream/temp/worker sót khi đóng | Cleanup tập trung mọi trạng thái terminal |
| P1 | Config thiếu range, relative path phụ thuộc cwd | Port/số âm sai; chạy nơi khác dùng nhầm DB | Fail-fast validation, resolved base path |

Bằng chứng tương ứng: `ServerMain.java:26,48–64`; `Session.java:21–32`; `SessionManager.java:39–48`; `FrameCodec.java:34`; `ClientHandler.java:105–109,120–143,161–185`; `ChatClient.java:39–57`; `ChatFrame.java:261–270,379–404,428–444,565–569`; `LoginFrame.java:113–134`; `ServerConfig.java:22–41`.

Client đã có basename sanitizer và CREATE_NEW tránh overwrite; cần **bổ sung** reserved filename, invalid path, giới hạn và kiểm containment. Không kết luận sai rằng chưa có chống traversal nào.

## 4. Ràng buộc dữ liệu hiện tại

Sáu bảng: users, chat_rooms, room_members, chat_messages, shared_files, file_transfers (`Database.java:27–34`). Thiếu migration version, index phục vụ feed/history, soft delete, trạng thái tài khoản, key idempotency, cursor đã đọc, attachment ACL. chat_messages chưa CHECK đúng một đích receiver/room. Room cho JOIN theo ID, chưa invitation/role. Group content chưa cùng limit private.

Một JDBC connection và synchronized hợp lý cho bài tập nhỏ nhưng serialize mọi CRUD; service transaction nhiều method chưa atomic. Với SQLite, phải kiểm soát writer trước khi tăng tải, chưa cần đổi DB để giải quyết lỗi nghiệp vụ.

## 5. Tái dùng và thay thế

**Giữ:** Maven modules, FrameCodec format, Jackson, request correlation, server/client entry points, Theme/AppIcon, bubble/file card, Observer/Dashboard và cấu trúc integration test.

**Refactor:** ClientHandler thành transport dispatcher; Database thành migration/transaction/repository; ChatFrame thành view/controller/services; Session thành lifecycle + queue + authenticated identity.

**Thay:** JOIN nickname, routing theo username, file relay không registry, history RAM là nguồn duy nhất, user list broadcast toàn mạng, ACK ghi socket, schema giả password rỗng.

## 6. Ghi nhận baseline khi bắt đầu lập trình

1. Lưu snapshot Git của source và thay đổi hiện có; không ghi đè ClientHandler hoặc file cá nhân ngoài phạm vi.
2. Chạy Maven test/package và ghi JDK/Maven/commit/kết quả; test hiện có chỉ là baseline v1.
3. Chạy hai client thật: chat/file, đóng client/server, reconnect; ghi lỗi GUI và tài nguyên.
4. Audit v1 không phải nghiệm thu mạng xã hội. Bộ test mục tiêu nằm trong [08](08-testing-release.md).
