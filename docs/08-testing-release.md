# 08 — Chiến lược kiểm thử, đóng gói và bảo vệ đồ án

> Phạm vi hiện tại (12/10/2026): TCP/IP thường; không dùng TLS. UDP tắt mặc định, các phần UDP bên dưới chỉ là mô tả tương thích/tuỳ chọn.

Runtime v2 và các bộ test tự động đã có source. Ma trận dưới đây vẫn là yêu cầu nghiệm thu; không tự đánh dấu mọi hàng PASS vì Maven chạy thành công. Kết quả thực tế, các test liên quan và giới hạn môi trường được ghi riêng tại [implementation-status](implementation-status.md). Mỗi kết quả phải ghi revision, thời gian, môi trường và evidence; chỉ khi cả kiểm thử tự động lẫn các gate nhiều máy/UI đạt mới tuyên bố hoàn thành nghiệm thu.

## Kết quả thực tế ngày 07/10/2026

Kết quả cũ ngày 07/10/2026 nằm trong [bản lưu](archive/implementation-status-2026-10-07.md). Kết quả bản TCP hiện tại và giới hạn nghiệm thu nằm trong [implementation-status.md](implementation-status.md).

Những kết quả trên chưa đánh dấu các checkbox UI/đa máy/Linux/firewall/crash/30cycles bên dưới. Chỉ claim phần thực sự có report/capture.

## 1. Các lớp kiểm thử

1. Unit có giá trị: validator, policy/privacy matrix, state transitions, cursor, HMAC/replay, chunk offset/quota. Không ưu tiên test getter/setter hay UI màu sắc.
2. Repository/transaction integration: SQLite temp DB, migration, UNIQUE/FK, race, rollback, seq/idempotency/outbox. Dùng constraint thật, không mock mất tính chất atomic.
3. Protocol integration: server trên port ngẫu nhiên + 2–3 test client sockets/TCP, UDP socket thật, request/event interleaving, slow reader, abrupt close. Không ngủ cố định dài; await có deadline và assert.
4. End-to-end Desktop: Swing hai/ba client thật, Windows/Linux, mọi trạng thái UI/transfer/disconnect/permission. Test localhost chưa thay được thử LAN/VM.
5. Release/ops: chạy từ thư mục mới, TCP connect, firewall, backup/restore, restart/crash, storage recovery và tải bounded.

Chia unit/integration profile nếu cần để test nhanh; CI chạy cả bộ bắt buộc trước release. Không bỏ integration vì cần socket; bind loopback/ephemeral ports và close tài nguyên.

## 2. Ma trận test ID (khớp backlog)

| ID | Kiểm tra | Kết quả cần chứng minh |
|---|---|---|
| QA-BASE | V1 compile/test/package + 2 GUI | Ghi baseline trung thực, không coi P2P/auth đã có |
| QA-NET01 | Unicode/emoji, frame split/coalesced, nhiều frame liên tiếp | Decode đúng boundary, giữ requestId |
| QA-NET02 | Length âm/0/oversize, EOF nửa header/payload, JSON hỏng/deep/field sai | Reject bounded, close malformed, không crash server/allocate lớn |
| QA-NET03 | Response/event xen kẽ, type lạ, timeout, version sai | Pending đúng correlation; lỗi chuẩn; capabilities đúng |
| QA-SEC01 | TCP thường, HELLO/auth/session, malformed/oversize frame | Không vượt quyền session; frame lỗi bị đóng; không yêu cầu chứng chỉ |
| QA-LIFE01 | 50 socket chưa auth, socket 51, idle deadline, heartbeat mất | Cap mọi connection; cleanup permit/thread/online đúng |
| QA-LIFE02 | Một recipient không đọc + broadcast/file/chat | Queue bounded, user khác vẫn hoạt động, slow connection bị close có kiểm soát |
| QA-LIFE03 | Stop/new/failed bind/repeated close, client abrupt close | Socket/workers/temp/DB đóng đúng; process exit bounded |
| QA-DATA01 | Fresh DB + từng migration + checksum/version sai | Đúng schema/index/FK; từ chối không tương thích |
| QA-DATA02 | Lỗi giữa transaction + concurrent CRUD | Không row nửa chừng, không connection transaction lẫn nhau |
| QA-DATA03 | Crash sau commit trước push, outbox retry, expired dedup | Durable state giữ; event/notification không nhân đôi; retention đúng |
| QA-AUTH01 | Register valid/invalid/duplicate concurrent | Một username_key một user/profile; password chỉ hash/salt có tham số |
| QA-AUTH02 | Password sai/nonexistent/rate-limit/payload actor giả | Lỗi chung, không leak/privilege escalation, hash executor bounded |
| QA-AUTH03 | Login cùng user race, logout/change password/suspend | Một session; ALREADY_SIGNED_IN; revoked UDP/request bị từ chối |
| QA-SOC01 | Profile/search/version/avatar update | DTO không secret; filter block/suspend; stale version conflict |
| QA-SOC02 | Request/accept/reject/cancel/remove sai hướng/self/trùng | Chỉ receiver accept; terminal request không hồi sinh |
| QA-SOC03 | Hai chiều request, cancel vs accept, block vs accept | Một active pair/state; thứ tự commit giải thích được |
| QA-PRIV01 | Block hai chiều/unblock, chat history ngoại lệ | Không friend/post/chat mới; history direct cũ giữ; không tự refriend |
| QA-PRIV02 | PUBLIC/FRIENDS/ONLY_ME trên feed/detail/timeline/media/event/count | C biết ID vẫn không vượt quyền; revoke đang download dừng |
| QA-POST01 | Text/image limits, empty, owner edit/delete, spoof author | Đúng author/READY media/purpose/count, transaction attach |
| QA-POST02 | ExpectedVersion race, privacy đổi, soft delete/admin hide | Conflict không overwrite; mọi đường đọc/tương tác mất quyền |
| QA-POST03 | Cursor nhiều trang khi insert/delete/edit/friend đổi | Không lặp item trong hướng tải cũ; quyền lọc trước limit; context cursor đúng |
| QA-POST04 | Like set retry/unlike/self, comment delete quyền/tombstone | Không toggle/trùng notification; không lộ blocked commenter |
| QA-FILE01 | Traversal/reserved filename/symlink/path giả/quota/concurrency cap | Không ghi ngoài root, không ghi vượt reserve, không auto-execute |
| QA-FILE02 | Chưa INIT, owner khác, skip/repeat/overlap/oversize chunk | Registry/offset đúng; không forward giả như v1 |
| QA-FILE03 | Hash/size/magic ảnh sai, crash VERIFYING/move/READY | Không attach file lỗi, recovery nhất quán, hash thực tế |
| QA-FILE04 | Download media của post/chat/group/READY owner; revoke giữa chừng | ACL theo resource/period, window bounded, FAILED event/cleanup, không treo progress |
| QA-FILE05 | COMPLETE nhưng verify fail, trùng tên/file `.part` | Chỉ VERIFIED thành công; không overwrite; cleanup lỗi |
| QA-FILE06 | Disconnect upload/download, login mới, STATUS/RESUME | Offset durable đúng, metadata/hash match, không double bytes |
| QA-FILE07 | Disk full/expired/orphan/deleted binding/server cap | Không corruption/leak quota; GC không xóa còn reference |
| QA-CHAT01 | Direct pair concurrent, unfriend/block sau tạo | Một conversation; gửi mới đúng quyền, đọc lịch sử cũ đúng |
| QA-CHAT02 | 20 concurrent send + sequence/history | Seq unique tăng, chat content đúng sender/session |
| QA-CHAT03 | Commit xong mất ACK, retry key/clientMessageId khác payload | Một message; IDEMPOTENCY_CONFLICT nếu payload khác |
| QA-CHAT04 | Recipient offline/slow, restart, history after/before/lookup | Giữ text/file/notification; sender không mất session vì push fail |
| QA-CHAT05 | Delivered/read fake/seq cross-conversation/out-of-order | Chỉ recipient/member ACK; cursor monotonic và bounded |
| QA-NOTI01 | Source retry/self/like spam/read own/through cursor | Đúng một/gộp theo rule, badge DB đúng, không mark tương lai |
| QA-NOTI02 | Notification cũ của post privatized/deleted/block | Không preview/text rò, target unavailable |
| QA-RES01 | EOF/mất mạng/network trở lại/heartbeat | Banner/disable/relogin/sync đúng, retry bounded, không lưu password |
| QA-RES02 | Logout/close khi file/hash/request pending | Cleanup worker/future/keys/streams/cache, không update disposed UI |
| QA-UDP01 | Broadcast/unicast discovery, nonce wrong/oversize/rate limit | OFFER đúng source/nonce/version, bounded không secret |
| QA-UDP02 | Firewall chặn UDP/VM broadcast không qua | Kết nối host/port tay + chat TCP vẫn được |
| QA-UDP03 | REGISTER HMAC wrong/session wrong/endpoint thay/replay | Không bind/fan-out gói giả; signed rebind đúng |
| QA-UDP04 | Direction reflection, seq cũ, ts sai, key rotate/logout/suspend | Drop epoch cũ đang bay, register lại/reset seq epoch mới, không dùng key revoked |
| QA-UDP05 | Typing lost/duplicate/reorder, rights đổi, TTL | Hết 5s, đúng member, không lưu message/online từ UDP |
| QA-GROUP01 | Invite accept/expire/self/not friend/group20 race | Không vượt cap; active member mới đúng visibleFromSeq |
| QA-GROUP02 | Kick/leave/rejoin/owner transfer/disband | Mất quyền server ngay, period mới không lấy lịch sử cũ, một owner |
| QA-GROUP03 | Block trong group, owner suspended/admin recovery | Ngoại lệ group rõ, admin action/audit đúng, không orphan owner |
| QA-ADMIN01 | User giả ADMIN/report invalid target/duplicate/snapshot | Deny role giả, snapshot đủ nhưng không lộ ngoài admin |
| QA-ADMIN02 | Claim/resolve race/hide/suspend/unsuspend/audit | Version đúng, target đổi + audit atomic; không đọc private chat toàn cục |
| QA-UI01..07 | View auth/friends/feed/chat/notification/group/admin | Luồng phần 3 dưới đây, EDT responsive, error/empty/loading |
| QA-PERF01 | Load20/50 và dữ liệu seed | Số đo throughput/p95/heap/thread/queue; không claim khi chưa đo |
| QA-PERF02 | Bulk 2×25MiB + chat, slow reader, leak repeated cycles | Không linear memory/file, control vẫn được phục vụ |
| QA-OPS01 | Stop backup/restore DB+media inventory/hash | Restore riêng giữ tất cả quan hệ/data/ảnh/tệp |
| QA-OPS02 | Recovery/retention/log rotate/config range/cwd thay | Không nhầm DB, không secret log, không xóa reference còn dùng |
| QA-REL01 | JAR/source/config trên máy sạch | Có prerequisites và launch đúng, error hỗ trợ được |
| QA-REL02 | Windows + Linux + no-ui + LAN/VM/firewall | Ít nhất hai môi trường thật dùng cùng server |
| QA-DEMO | Luồng A/B/C/admin và capture mạng | Minh chứng end-to-end TCP+UDP+persistence+ACL |

P1-specific test (group/admin/receipt/resume/typing/ops tải) thực hiện ở M5/M6; các test nền quyền, data, auth, file cơ bản là gate P0. Tất cả ma trận áp dụng khi tuyên bố bản hoàn chỉnh.

## 3. Checklist UI thủ công

- [ ] QA-UI01: connect/timeout/đăng ký/login/password change; button không pending mãi.
- [ ] QA-UI02: friend requests đúng hướng/block/unblock/profile stale/search empty.
- [ ] QA-UI03: composer ảnh/privacy/feed pages/like/comment/edit conflict/unavailable khi quyền đổi.
- [ ] QA-UI04: chat history scroll/pending unknown/retry stable/file progress+cancel/checksum/receiver offline.
- [ ] QA-UI05: unread badge/refetch/read/noti target đã mất quyền, không hiện text cũ.
- [ ] QA-UI06: group invite/owner transfer/leave/kick/rejoin/typing TTL/read summary.
- [ ] QA-UI07: admin claim/report/action lý do/role invalidation/audit; non-admin không có đường gọi thành công.
- [ ] Resize/scale100/150/200%, tiếng Việt/emoji/line dài, keyboard Tab/Enter/Shift+Enter/Esc, Windows/Linux.
- [ ] UI thao tác được khi có upload/download/hash/mạng chậm; không đưa chunk Base64 qua EDT.
- [ ] Đóng/mở lại nhiều lần, logout đổi account không lộ state/cache/preview của phiên trước.

## 4. Mục tiêu tải có thể đo

Ghi CPU/RAM/JDK/OS/network/DB size; đo trên cấu hình demo thật. `LoadRuntimeTest` tạo 20 tài khoản, 10.000 post, 50.000 message, chạy burst feed/history/chat/receipt, 2×25 MiB upload/download streaming cùng MESSAGE_SEND/PING control, cap 50 TCP socket, slow reader và shutdown. Nó ghi số đo tại `server/target/qa/performance.json`. Đây là localhost acceptance workload; chưa thay thế workload LAN kéo dài, workload chat đều đặn kéo dài hoặc 30 chu kỳ cleanup. Các mục tiêu dưới đây vẫn giữ nguyên:

| Workload | Target |
|---|---|
| 20 authenticated client, mỗi client 1 chat/10s, feed refresh30s | Chat STORED p95 ≤500ms LAN, feed page20 p95 ≤1s |
| 2 file25MiB song song + workload trên | Chat ACK p95 ≤1,5s, UI responsive, queue bounded |
| 50 connection tổng (kể cả chưa auth), connection51 | Giới hạn enforce; idle timeout không giữ permit |
| 10.000 posts/50.000 messages | Query dùng index, page bounded, không load toàn history |
| 30 chu kỳ connect/login/logout/transfer cancel | Thread/heap/socket trở về nền sau cleanup, không tăng đơn điệu |
| Server stop | Hết worker/socket trong ≤10s khi bình thường; lỗi timeout log rõ |

Mục tiêu server heap ổn định dưới budget 512MiB trên workload bounded; streaming không cấp byte[] cả file. Không lấy throughput Internet hay máy khác để tuyên bố. Nếu không đạt, phân tích bottleneck và điều chỉnh có chứng cứ; không hạ tiêu chí âm thầm.

## 5. Release và setup

### Build và entry point hiện tại

Từ root `chat_message`, lệnh có sẵn: `mvn test`, `mvn package` khi Maven trong PATH; Windows có `scripts/build-all.ps1` tìm Maven bundled và package, Linux có `scripts/build-all.sh`. Hai JAR hiện đặt ở server/client target với classifier shaded. Shaded JAR mặc định dùng `com.p2p.server.social.SocialServer` và `com.p2p.client.ClientMain`/SocialWindow; MiniChat v1 vẫn còn trong source. Bằng chứng package/smoke được ghi riêng trong implementation-status; không suy ra Linux/LAN pass từ một build Windows.

### Artifact release v2

```text
release/social-v2-<timestamp>/
  README.md                  yêu cầu JDK17 và cách chạy
  server/target/server-1.0.0-SNAPSHOT-shaded.jar
  client/target/client-1.0.0-SNAPSHOT-shaded.jar
  server/config/social.properties
  scripts/                   launch Windows/Linux
  source/                    Maven source/tests, không cache/data
  checksums.sha256
```

Database và dữ liệu người dùng không chia trong release hoặc Git. Server admin/bootstrap có hướng dẫn riêng, không hardcode credential.

Lựa chọn packaging bắt buộc: shaded JAR + JDK17 cài trước, rõ cách chạy/no-ui. `jpackage` runtime bundle là P2, không tự tạo installer Windows rồi gọi đó là Linux compatible. Test release từ thư mục sạch, port/config/data path không phụ thuộc checkout.

### Mạng và VM

- Chỉ cần TCP5000 trên private LAN theo cấu hình mặc định; UDP đã tắt. Ghi port override nếu đổi.
- Server bind đúng interface; client máy khác không dùng127.0.0.1. Firewall rule theo port thực tế/private profile và operator tự áp.
- VM Host-only/Bridged cho demo; broadcast có thể không qua NAT, luôn có host/IP tay và unicast discovery.
- Lệnh kiểm TCP như Test-NetConnection/nc không chứng minh UDP hoạt động. UDP chứng minh bằng datagram capture/log/app event.
- Khi Internet/NAT nằm ngoài phạm vi, ghi rõ LAN deployment; không hứa port-forward/traversal đã giải quyết.

## 6. Backup/restore và crash

Offline backup/restore đã có `BackupTool` và wrapper PowerShell. Layout hỗ trợ: `dataRoot/social-network.db` và `dataRoot/media`. Runtime, seed và backup dùng cùng lock `dataRoot/.server.lock`; tool từ chối khi server còn giữ lock. Destination phải là thư mục mới, không ghi đè. Manifest gồm schema version/checksum, SHA-256 và kích thước database cùng inventory READY/ATTACHED; cả metadata inventory lẫn file thật được so với database. Thumbnail có thể tái tạo, không cần snapshot. Restore expire upload/transfer chưa hoàn tất và bỏ media idempotency cũ, không khôi phục `.part` hoặc UDP session.

```powershell
.\scripts\backup-data.ps1 -Destination D:\backups\social-snapshot
.\scripts\restore-data.ps1 -Backup D:\backups\social-snapshot -NewDataRoot D:\demo\restored-data
```

Linux hoặc chạy trực tiếp: `java -cp server/target/server-1.0.0-SNAPSHOT-shaded.jar com.p2p.server.ops.BackupTool --backup data NEW_BACKUP_DIR`; thay `--backup` bằng `--restore` với backup root và data root mới. Quy trình nghiệm thu:

1. Ngừng accept, hoàn tất/abort transaction và stop server theo lifecycle; xác nhận workers/socket đóng.
2. Checkpoint WAL sạch rồi đóng DB; copy snapshot DB + media READY/ATTACHED + inventory hash/version vào backup riêng có timestamp.
3. Không copy mỗi `.db` của SQLite đang chạy và bỏ qua WAL. `.part` nếu giữ để resume phải có transfer metadata cùng snapshot; an toàn hơn expire pending khi restore, ghi rõ.
4. Restore vào data root mới, cấu hình test port riêng; integrity_check/foreign_key_check/media hash, login giả lập đọc post/history/file.
5. Chỉ chuyển server về snapshot sau restore drill thành công; không ghi đè bản gốc khi đang thử.

Crash drills tại: trước commit, sau commit trước push, sau ACK storage checkpoint, VERIFYING trước/sau move, trước READY DB commit, sau READY trước attach. Assert không private media sai ACL, không message duplicate, không quota leak hoặc file reference missing không báo lỗi.

## 7. Kịch bản demo 10–15 phút

Chuẩn bị A/B/C + ADMIN, một client Linux/VM và một Windows, seed PUBLIC/FRIENDS/ONLY_ME/file nhỏ; có backup release ổn định.

1. Chạy server/dashboard/no-ui và clients; nhập IP/port trên form rồi đăng nhập qua TCP.
2. Login A/B/C. A gửi friend request, B ACCEPT. C không friend.
3. A post FRIENDS + ảnh; B thấy và like/comment; A notification, C không đọc bằng direct ID/media.
4. A đổi ONLY_ME khi B đang mở, B refresh/comment/download nhận unavailable. A post PUBLIC khác để C xem bình thường.
5. A/B chat TCP, kiểm STORED/DELIVERED/READ; nhóm chỉ phát cho thành viên hiện tại và Broadcast cho tất cả tài khoản online.
6. B logout, A gửi text + file upload READY; restart server; B login lấy history/download và verify SHA-256.
7. Mô phỏng A mất ACK sau commit, retry cùng clientMessageId; chỉ một message.
8. A block B; lời mời/chat mới/post view bị chặn, history direct cũ còn. Nhóm P1 minh họa invite/owner/kick/visibleFromSeq ngắn.
9. C report post được xem; ADMIN claim/hide hoặc suspend user demo; audit record/outcome tồn tại, không đọc private chat.
10. Client trên máy ảo nhập IP server và đăng nhập TCP. Kiểm Unicast, Group, Broadcast; ghi kết quả thực tế.

Để chứng minh framing: test codec và log metadata frame size/type/requestId; có thể bắt TCP trên dữ liệu demo để đối chiếu 4-byte length và JSON.

## 8. Báo cáo và bằng chứng nộp

- Bài toán/phạm vi/use cases/actors, BR privacy/friend/block và state diagrams.
- Architecture/modules/thread/queues, TCP framing, UDP HMAC/TTL/fallback, ERD/schema/migration.
- Sequence auth/post/send/offline/file, transaction/dedup và xử lý crash.
- Requirement BR-* → backlog B* → test QA-* → actual result/evidence.
- Môi trường LAN/VM/OS/JDK, số đo tải, ảnh UI thật/capture log redacted, file release checksum.
- README setup/demo, source + tests, backup/restore hướng dẫn, hạn chế và P2 ghi trung thực.

Mẫu result record:

```text
Test ID / requirement / commit:
Môi trường / thời điểm / data fixture:
Steps và input:
Expected:
Actual và PASS/FAIL:
Evidence (log/capture/screenshot đã mask):
Bug liên quan nếu FAIL:
```

## 9. Gate phát hành

- [ ] Không còn lỗi vượt quyền/chiếm danh tính/rò private text/media.
- [ ] Toàn P0/P1 backlog và test bắt buộc pass với evidence; flaky/race được xử lý.
- [ ] Windows/Linux đa máy và UDP fallback đã thử, không chỉ localhost.
- [ ] Restart/crash/backup restore thực tế pass, không mất durable data hoặc tạo duplicate.
- [ ] Release từ thư mục mới chạy, config/admin setup rõ; không secret trong repo/log.
- [ ] Tài liệu khớp source, tính năng chưa làm ghi ngoài phạm vi, báo cáo không dùng kế hoạch làm bằng chứng.
