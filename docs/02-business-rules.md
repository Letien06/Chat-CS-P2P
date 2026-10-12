# 02 — Đặc tả nghiệp vụ, quyền và trạng thái

**Trạng thái: thiết kế mục tiêu v2, chưa triển khai.** Requirement ID BR-* dùng trong backlog/test. Các giới hạn dưới đây là giá trị mặc định; thay đổi phải đồng bộ config/DTO/UI/test.

## 1. Tác nhân và nguyên tắc chung — BR-01

| Tác nhân | Quyền |
|---|---|
| Chưa xác thực | Discovery, HELLO, đăng ký, đăng nhập; không đọc dữ liệu người dùng |
| User ACTIVE | Hồ sơ, bạn bè, bài đăng và tương tác được policy cho phép; chat đúng quyền |
| User SUSPENDED | Không đăng nhập hoặc thao tác; phiên hiện có bị thu hồi |
| ADMIN ACTIVE | Quyền user + xử lý report, ẩn bài/comment, khóa/mở tài khoản với audit |
| Server operator | Cấu hình/chạy/backup server; không mặc nhiên là admin ứng dụng |

- Actor lấy từ session TCP đã xác thực; không tin actorId/username/role do client khai báo.
- userId bất biến là khóa nghiệp vụ; username là định danh đăng nhập; displayName được trùng và đổi.
- ID/timestamp/revision do server cấp. Thời gian lưu UTC, UI hiển thị múi giờ máy người dùng.
- Tất cả query, mutation, media download và event phải kiểm tra quyền; ID đoán được không mang lại quyền.
- Mutation đồng thời được quyết định theo thứ tự transaction commit. Permission check và write phải trong cùng transaction khi quyền có thể đổi.
- Tài nguyên không được phép đọc thường trả NOT_FOUND để tránh tiết lộ tồn tại. ACTION_FORBIDDEN dùng khi user đã có quyền nhìn tài nguyên nhưng không được sửa.

## 2. Tài khoản và phiên — BR-02

### Đăng ký

Input: username 3–32 ký tự ASCII `[a-zA-Z0-9_.]`, displayName 1–60 ký tự Unicode, password 10–128 ký tự. Trim username, chuyển lowercase bằng Locale.ROOT để tạo username_key. Password không trim/normalize; cấm vượt giới hạn byte bảo vệ hash CPU. DisplayName/bio trim và validate control character. Không yêu cầu email/OTP trong phạm vi bắt buộc, nên không đưa “quên mật khẩu qua email” vào UI.

Luồng: validate → hash ngoài transaction → INSERT users/profiles trong transaction → UNIQUE username_key chống race → trả userId; đăng ký xong chuyển sang đăng nhập, không tự tạo session ngầm. Hash có salt ngẫu nhiên và tham số; không lưu plaintext.

### Đăng nhập/đăng xuất

- Login qua TLS. Sai username/password dùng chung INVALID_CREDENTIALS; có rate limit theo IP và username_key.
- Một user một session. Sau password hợp lệ, kiểm tra ACTIVE và atomic reserve userId. Nếu phiên còn sống trả ALREADY_SIGNED_IN; nếu session đã timeout thì cleanup trước khi cho phiên mới.
- Auth state: CONNECTED → NEGOTIATED → AUTHENTICATED → CLOSING → CLOSED. AUTH bắt buộc sau HELLO v2; không giữ đường JOIN nickname bên cạnh auth.
- Logout/EOF/heartbeat timeout thu hồi session, UDP key và download/upload capability. Không xóa dữ liệu nghiệp vụ.
- Đổi mật khẩu: xác minh mật khẩu hiện tại, cập nhật hash, tăng auth_version, thu hồi toàn bộ session kể cả phiên thao tác; yêu cầu login lại.
- Không lưu password trên client. Reconnect mở connection mới và yêu cầu login lại; chưa có remember-me/resume token dài hạn.
- SUSPEND thu hồi phiên tức thời; admin UNSUSPEND là hành động riêng có log. Không cho tự khóa admin cuối cùng hoặc thu hồi vai trò qua payload user.

## 3. Hồ sơ, tìm user và presence — BR-03

- Profile: displayName ≤60, bio ≤500 ký tự, avatar mediaId hợp lệ do chính user upload. Username không đổi trong bản bắt buộc.
- Xem người chưa block: id, username, displayName, avatar, bio; không public hash, đường dẫn file, IP, session hoặc thông tin admin nội bộ.
- Tìm theo username/displayName, tối thiểu 2 ký tự, limit mặc định 20/tối đa 50. Loại SUSPENDED và cặp có block; không phát tán toàn bộ danh sách user như v1.
- Online authoritative từ TCP session/heartbeat. Chỉ bạn hiện tại thấy presence của nhau; block luôn loại. User có thể xem trạng thái mình. Không đo online bằng UDP typing.
- Khi người dùng đổi avatar, version/cache key đổi; media avatar của user bị block không tải lại được qua ID cũ.

## 4. Bạn bè hai chiều — BR-04

Dùng một cặp chuẩn `(lowUserId, highUserId)` cho quan hệ, UNIQUE. Pending có requester/addressee rõ ràng, khác nhau. Mỗi cặp có tối đa một trạng thái đang hiệu lực: PENDING hoặc ACCEPTED; trạng thái kết thúc REJECTED/CANCELLED/REMOVED được lưu để trả lời request cũ và audit tối thiểu.

| Hành động | Điều kiện | Kết quả |
|---|---|---|
| REQUEST | Khác mình, cả hai ACTIVE, không block, chưa ACCEPTED | NONE/terminal → PENDING, requester là actor |
| REQUEST lại cùng chiều | Pending của mình | Trả pending hiện có, không nhân đôi |
| REQUEST ngược chiều | Pending từ đối phương | INCOMING_REQUEST_EXISTS; phải ACCEPT rõ ràng |
| ACCEPT | Actor là addressee, đúng requestId nghiệp vụ còn pending, không block | ACCEPTED; thông báo requester |
| REJECT | Actor addressee, còn pending | REJECTED |
| CANCEL | Actor requester, còn pending | CANCELLED |
| REMOVE | Actor thuộc quan hệ ACCEPTED | REMOVED, không gửi chat mới/đọc FRIENDS từ đối phương |

`relationId` mới mỗi lần gửi lời mời sau terminal; không tái dùng ID lời mời cũ. Unique pair được quản lý bằng index trạng thái active hoặc bảng pair + request lịch sử như data model. Client cũ ACCEPT request đã cancel/reject nhận INVALID_STATE. Hai bên request đồng thời không tự kết bạn. Retry cùng idempotencyKey trả kết quả cũ; request mới có thể nhận ALREADY_FRIENDS.

Accept và cancel đua nhau: chỉ một transition commit, transaction sau thấy trạng thái mới. Xóa bạn không xóa chat/lịch sử đã nhận, không tự xóa comments/likes trước đó.

## 5. Block — BR-05

Block có hướng A → B; tương tác giữa A/B bị cấm khi **một trong hai hướng** tồn tại. Self-block không hợp lệ.

Trong một transaction: insert block nếu chưa có → kết thúc mọi pending → chuyển friendship ACCEPTED thành REMOVED → phát invalidation sau commit. Bỏ block chỉ xóa hướng của actor; nếu còn hướng ngược vẫn bị chặn. Unblock không tự khôi phục bạn bè.

Sau block, hai bên không gửi lời mời, chat/file mới, like/comment lên nội dung nhau; không xem profile chi tiết, post PUBLIC/FRIENDS, avatar mới, presence hoặc typing của nhau. PUBLIC vẫn chịu block. Danh sách người bị mình chặn được hiển thị tối thiểu để unblock.

**Ngoại lệ được chọn:** lịch sử chat riêng và attachment đã gắn trước block vẫn đọc/tải bởi hai thành viên cố định. Nội dung đã tải hoặc đã nhìn không thể thu hồi khỏi máy client. Block không tự đưa user ra khỏi nhóm chung; trong nhóm còn quyền có thể thấy message của nhau, phải ghi rõ ở UI.

## 6. Bài đăng, feed, privacy — BR-06

Post gồm text tối đa 5.000 ký tự, 0–4 ảnh READY do author sở hữu, mỗi ảnh ≤5 MiB. Cần text không rỗng hoặc ít nhất một ảnh. Mặc định FRIENDS để giảm chia sẻ ngoài ý muốn. Trạng thái ACTIVE/DELETED/HIDDEN; version tăng khi sửa/privacy/moderation.

| Viewer | PUBLIC | FRIENDS | ONLY_ME |
|---|---|---|---|
| Author ACTIVE | Có | Có | Có |
| Bạn hiện tại, không block | Có | Có | Không |
| User khác ACTIVE, không block | Có | Không | Không |
| Chưa auth hoặc có block | Không | Không | Không |

Post DELETED/HIDDEN hoặc author SUSPENDED không xuất hiện trong feed/detail/interaction/media. Author có thể xem metadata moderation/lý do trong mục bài của tôi; không có đường tương tác lên bài hidden. Admin đọc nội dung được report bằng luồng xử lý có audit, không có quyền duyệt toàn bộ ONLY_ME/chat riêng.

Hàm `canViewPost(viewer, post)` dùng chung cho feed, profile timeline, detail, comments, reaction count, search và media. Không lọc ở UI sau khi server đã trả nội dung.

- Feed: bài của mình + PUBLIC hợp lệ của user khác + FRIENDS từ bạn hiện tại; loại blocked/hidden/deleted/suspended. Sắp xếp `(createdAt DESC, id DESC)`.
- Cursor có query context/viewer và anchor thời gian/ID, limit 20/max50. Client refresh để thấy bài mới; không chèn hàng loạt event gây nhảy vị trí đang đọc.
- Friendship xét **hiện tại**, không snapshot lúc đăng. Hủy bạn mất quyền FRIENDS ngay ở lần đọc kế tiếp.
- Sửa/xóa chỉ author (moderation là API khác). Optimistic concurrency `expectedVersion`; client cũ gặp VERSION_CONFLICT cần tải lại.
- Đổi privacy giữ like/comment trong DB nhưng mọi viewer mất quyền bị từ chối tiếp. Push invalidation tối thiểu, không push nội dung riêng tư cho watcher cũ.
- Xóa bài soft-delete, không nhận tương tác mới; comment/reaction không hiển thị; media unreferenced được dọn theo retention.
- Tìm toàn văn bài là P2; không được vô tình mở API tìm mọi bài không policy.

## 7. Comment và like — BR-07

- Bình luận phẳng ≤1.000 ký tự, không rỗng, chỉ vào post ACTIVE đang xem được, author/commenter không bị block với nhau. Thứ tự `(createdAt ASC,id ASC)`, phân trang.
- Author comment được xóa của mình; author post được xóa comment trên post của mình; admin ẩn theo report. Bản bắt buộc không sửa comment, giảm xung đột; sửa comment là P2.
- Nếu post vẫn xem được nhưng người viết comment bị viewer block, trả tombstone “Bình luận không hiển thị” không text/avatar. Count chỉ tính comment/reaction hợp lệ đối với viewer, tránh lộ identity bị block.
- LIKE unique `(postId,userId)`; API `set liked=true/false`, không toggle. Retry không đảo trạng thái và không tạo notification lặp.
- Author được self-like/comment nhưng không nhận notification của mình. Tương tác phải kiểm tra quyền lúc commit.
- Like/comments cũ giữ trong DB khi unfriend; sau quyền bị thu hồi không trả text/user list cho viewer đó.

## 8. Chat riêng và lịch sử — BR-08

Conversation DIRECT có đúng hai member cố định và một cặp user unique. Tạo/get khi hai bên là bạn và không block. Đọc lịch sử đã có không cần tình bạn còn tồn tại; gửi tin mới yêu cầu hai bên ACTIVE, vẫn là bạn, không block tại commit.

- Text ≤4.000 ký tự hoặc một attachment READY ≤25 MiB; một message có ít nhất một trong hai. Attachment chỉ một conversation và chỉ do sender upload.
- Server kiểm quyền → insert message + idempotency + notification → commit → ACK STORED → push. Recipient offline vẫn lưu; lỗi push không rollback hoặc ngắt sender.
- `clientMessageId` UUID ổn định do client giữ khi retry, UNIQUE theo sender. Cùng ID khác payload trả IDEMPOTENCY_CONFLICT.
- Message UI: PENDING → STORED → DELIVERED → READ; hoặc FAILED/UNKNOWN. Timeout chưa có ACK là UNKNOWN, không kết luận chưa lưu. Retry/lookup cùng clientMessageId trước tạo ID mới.
- DELIVERED chỉ sau recipient ACK xác nhận message vào client state; READ chỉ sau mở hội thoại và message thực sự hiển thị. Cursor read tăng đơn điệu, không vượt message cuối thuộc conversation.
- History phân trang `(conversationSeq)` ổn định; đồng bộ sau reconnect bằng `afterSeq`, tải cũ bằng `beforeSeq`. Không dùng tên người làm conversation key.
- Bản bắt buộc không thu hồi/sửa/xóa tin cho cả hai phía. Nếu cần xóa local view hoặc unsend phải thiết kế riêng P2, không tự hard-delete row chung.

## 9. Nhóm chat — BR-09, P1

- Owner tạo nhóm ban đầu có một owner; nhóm tối đa 20 member active, bắt đầu trao đổi khi có người khác ACCEPT. Mời tối đa 19 bạn ACTIVE không block với owner; người được mời phải ACCEPT. Invitation expire sau 7 ngày, có cancel/reject. Khi accept phải kiểm cap20 lại trong transaction; pending invite không giữ chỗ vĩnh viễn.
- Vai trò OWNER/MEMBER, đúng một OWNER active. Owner mời/kick/đổi tên/chuyển owner/disband; member gửi và rời.
- Mời không tạo quyền đọc. Khi accept, ghi `visibleFromSeq = currentLastSeq + 1`; chỉ đọc message từ lần join hiện tại.
- Member rời/kick mất quyền history/media server ngay. Rejoin tạo membership period mới, không khôi phục lịch sử cũ theo mặc định.
- Owner chưa được leave nếu còn member; phải chuyển owner cho member ACTIVE hoặc disband. Nhóm không còn member được CLOSED.
- Block không tự kick hoặc lọc message nhóm; typing/message trong nhóm vẫn theo membership. Đây là ngoại lệ được nêu rõ cho user.
- Message push/download/read receipt kiểm member **tại thời điểm thao tác**, không chỉ lúc connection được auth. Trạng thái group và owner transfer chạy transaction.

## 10. Media và tệp — BR-10

Phân biệt avatar, ảnh post, attachment chat. Mỗi media chỉ gắn một resource; không tái sử dụng media PRIVATE sang PUBLIC. Sao chép phải upload/create bản riêng theo quyền.

Luồng: INIT → UPLOADING → VERIFYING → READY → ATTACHED; khi bỏ reference cuối chuyển RETIRED, không quay lại READY và không bind lại. Các nhánh CANCELLED/FAILED/EXPIRED. READY chưa attach chỉ owner được truy cập. Client không gửi storage path; server cấp mediaId và lưu file tên UUID trong root riêng.

- Avatar/ảnh: JPEG/PNG, kiểm magic bytes, decode được, ≤5 MiB và ≤20 megapixel. Avatar tạo thumbnail server; không nhận SVG/executable dưới MIME ảnh.
- File chat: ≤25 MiB, tên hiển thị ≤120, basename và filename hợp lệ; không tự mở/chạy file nhận. SHA-256 bắt buộc; checksum kiểm integrity, TLS/session xác thực bên gửi.
- Quota mặc định 200 MiB/user, 2 upload hoạt động/user, 20 upload/server, 10 GiB storage server; reserve quota khi init, release terminal. Không tiếp tục mặc định relay 2 GiB.
- Chunk decoded ≤48 KiB, exact offset, max byte theo offer; ACK chỉ offset đã ghi. Commit kiểm size/hash thực tế rồi atomic move cùng filesystem.
- Upload mất mạng giữ `.part` tối đa 24 giờ; sau login lại owner gọi STATUS/RESUME lấy offset đã ghi, hash metadata phải khớp. Không tin offset client tự khai. READY chưa attach dọn sau 24 giờ.
- Post media ACL theo post hiện tại; avatar theo profile/block; direct chat media theo member và quy tắc history; group media theo active membership + visibleFromSeq.
- Mỗi lần download và trước mỗi chunk/window kiểm quyền; thu hồi quyền giữa download dừng transfer. Byte đã đến client không thu hồi được.
- Không có auto-download file chat. Hiển thị size/tên rồi user chọn nơi lưu, ghi `.part`, verify và rename, tránh overwrite.

## 11. Thông báo — BR-11

Các nguồn: friend request/accept, comment/like, message mới, group invite, moderation. Persist trong transaction nguồn (hoặc durable outbox cùng transaction), push chỉ tăng tốc UI. Không tạo notification tự tương tác.

- Payload lưu actorId/resourceId/event type, hạn chế snapshot text. Read API kiểm quyền hiện tại và trả “Nội dung không còn khả dụng” nếu đã mất quyền; không để text cũ rò post ONLY_ME.
- Source key UNIQUE theo recipient + event + resource/occurrence. Like notification tối đa một/actor/post/ngày, lần unlike/re-like không spam; comment theo commentId; chat unread có thể gộp theo conversation và cập nhật seq mới nhất.
- Đánh dấu read chỉ notification của actor; bulk read giới hạn đến cursor server được cấp, không mark notification đến sau thao tác.
- Badge lấy từ DB; event mất hoặc trùng không làm counter drift. Friendship và block không được suy ra chỉ từ notification.

## 12. Report và admin — BR-12, P1

- Report USER/POST/COMMENT, reason enum + mô tả ≤1.000; reporter phải đang có quyền xem target lúc gửi. Không report private message trong phạm vi này.
- Lưu targetId + snapshot tối thiểu cần xử lý, reporter, thời điểm; snapshot nằm khu vực admin/report có audit, không feed.
- Mỗi reporter/target tối đa một report OPEN/IN_REVIEW. Report không tự ẩn/khóa; tránh vote số lượng làm quyết định.
- Trạng thái OPEN → IN_REVIEW → RESOLVED/DISMISSED. Admin claim, nhập lý do, áp action HIDE_POST/HIDE_COMMENT/SUSPEND_USER hoặc NO_ACTION; transaction ghi audit và outcome.
- Admin không sửa text của người khác, đọc mọi chat/ONLY_ME hoặc tự gán role qua UI. Chỉ nội dung được report và snapshot hợp lệ được xem trong chức năng này.
- Bootstrap admin bằng công cụ server local, password hash; không có tài khoản/password admin hardcode. Không ghi credential vào Git.
- Suspension ngăn login/gửi mới/đọc nội dung của user đó trên mạng xã hội. Lịch sử direct đã lưu giữ cho bên còn lại; nhóm không nhận message mới từ suspended user. Group owner suspended phải được admin chuyển owner hoặc đóng nhóm bằng action có audit.

## 13. Thao tác sau thu hồi quyền và sự kiện

Mọi event có eventId; event của aggregate có version/seq, lifecycle event SESSION_REVOKED/SERVER_STOPPING không cần revision. Event không phải bằng chứng quyền hiện tại. Đối với post/profile, server gửi invalidation ID tối thiểu, client refetch với ACL thay vì push text đến watcher cũ. Presence/typing chỉ fan-out đến người còn quyền.

Direct message commit trước block/unfriend vẫn thuộc lịch sử và có thể giao sau đó; gửi commit sau phải bị từ chối. Group message chỉ giao đến active member có visibleFromSeq phù hợp ở dispatch. Admin lock/logout thu hồi connection trước các request kế tiếp.

## 14. Tình huống phải nghiệm thu

| ID | Tình huống | Kết quả bắt buộc |
|---|---|---|
| BR-T01 | Đăng ký trùng đồng thời | Đúng một user, một lỗi duplicate |
| BR-T02 | Hai chiều friend request cùng lúc | Một pending, không tự accept |
| BR-T03 | Cancel đua accept | Một transition cuối, không pending + friend song song |
| BR-T04 | C biết ID bài/ảnh FRIENDS của A | Không đọc nếu C không là bạn |
| BR-T05 | A đổi ONLY_ME khi B đang mở | B không refresh/comment/like/download tiếp |
| BR-T06 | Unfriend/block khi chat | History cũ giữ; gửi mới bị từ chối |
| BR-T07 | Message commit nhưng ACK mất | Retry một clientMessageId chỉ một row |
| BR-T08 | B offline, server restart | B login thấy chat/notification/tệp đã commit |
| BR-T09 | File giả chunk/offset/quota | Từ chối, không ghi vượt hoặc attach file lỗi |
| BR-T10 | Bị kick/rejoin group | Mất quyền cũ; chỉ lịch sử period mới |
| BR-T11 | Notification của bài bị thu hẹp | Không trả preview/text ngoài quyền |
| BR-T12 | UDP giả/trễ/trùng/mất | Không sửa DB, typing hết TTL, dữ liệu TCP đúng |
| BR-T13 | Admin action/role giả | User bị từ chối; admin thật có audit |

Đặc tả này là nguồn quy tắc chung cho [data model](04-data-model.md), [protocol](05-protocol-v2.md) và [QA](08-testing-release.md).
