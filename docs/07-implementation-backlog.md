# 07 — Backlog và cách chuyển kế hoạch thành source

Tất cả checkbox dưới đây **chưa thực hiện trong đợt viết tài liệu**. P0/P1 là bắt buộc để hoàn thành phạm vi plan; P2 ngoài phạm vi. Không estimate deadline khi chưa biết ngày nộp và số thành viên.

## 1. M0 — Baseline và chuẩn bị

- [ ] **B00 — P0:** Lưu snapshot source hiện có, kiểm tra thay đổi người dùng; chạy test/package v1, hai GUI client, ghi baseline. Đầu ra: baseline report có môi trường/commit/result, không tự đánh dấu feature từ README. QA-BASE.
- [ ] **B01 — P0:** Chốt rubric với giảng viên, xác nhận dùng cả TCP và UDP, số thành viên/ngày nộp; cập nhật phạm vi nếu có yêu cầu khác. Đầu ra: quyết định ghi trong plan, không tự thêm video/P2P.
- [ ] **B02 — P0:** Tạo tracker requirement BR-* ↔ task B* ↔ test QA-*; chuẩn UTF-8, JDK17, formatter và Maven test command. Đầu ra: người khác checkout build được.

## 2. M1 — Nền tảng (phải trước auth/social)

- [ ] **B03 — P0:** Common v2 envelope, typed DTO, error catalog, 1 MiB frame limit; codec stateless bỏ static global lock. QA-NET01/02.
- [ ] **B04 — P0:** HELLO/version/capabilities, client request futures/deadline, tách response/event. Đầu ra: event chen giữa response không nhầm request. QA-NET03.
- [ ] **B05 — P0:** TLS server/client, keystore/truststore/pin ngoài discovery; config fail-fast. Đầu ra: cert đúng chạy, cert giả bị reject trước password. QA-SEC01.
- [ ] **B06 — P0:** Registry mọi socket + semaphore/pool bound + auth deadline + heartbeat. Đầu ra: idle chưa login cũng chiếm permit và bị timeout; connection sau cap bị từ chối có kiểm soát. QA-LIFE01.
- [ ] **B07 — P0:** Per-connection writer queue giới hạn item/byte, ưu tiên control, xử lý slow client; close sockets/await/db close đúng lifecycle. QA-LIFE02/03.
- [ ] **B08 — P0:** Database migrations + schema v2 mới + writer/read executors + transaction abstraction/FK/index. Đầu ra: fresh install/nâng version/rollback failure pass. QA-DATA01/02.
- [ ] **B09 — P0:** Service/repository/handler interfaces, Policy skeleton, idempotency records và outbox dispatcher. Đầu ra: crash sau commit trước push không mất durable state; không network I/O trong transaction. QA-DATA03.

Không thêm post/group trước khi B03–B09 đạt. Refactor từng phần; giữ v1 tag/snapshot để so sánh, không buộc runtime v2 giữ nickname JOIN.

## 3. M2 — Tài khoản, hồ sơ và quan hệ

- [ ] **B10 — P0:** AuthService REGISTER/LOGIN, PBKDF2 hash có tham số/salt, UNIQUE username, generic auth error/rate limit. BR-02; QA-AUTH01/02.
- [ ] **B11 — P0:** Session một user một phiên atomic, logout/password change/revocation/status ACTIVE. Đầu ra: login đồng thời không hai session; khóa account ngắt request/UDP. QA-AUTH03.
- [ ] **B12 — P0:** Server connection/login/register UI và authenticated MainFrame; xóa nickname auth; port tùy cấu hình. QA-UI01.
- [ ] **B13 — P0:** Profile GET/UPDATE/version + user search/DTO sanitization. BR-03; QA-SOC01. Avatar nối sau B19–B21.
- [ ] **B14 — P0:** Relationship FSM request/accept/reject/cancel/remove, unique active pair, partial indexes, notification/outbox. BR-04; QA-SOC02/03.
- [ ] **B15 — P0:** Block SET/list + hủy friend/pending atomic + policy hai chiều. BR-05; QA-PRIV01.
- [ ] **B16 — P0:** Friends/search/profile/settings screens nối server, empty/error/refresh state. Đầu ra: A/B/C thao tác thật đúng hướng và retry. QA-UI02.

## 4. M3 — Storage, nội dung và bảng tin

Media nền được làm trước ảnh post để không dựng card ảnh giả. B17–B21 là dependency dùng chung M3/M4.

- [ ] **B17 — P0:** Storage root/path UUID/containment, quota reservation, media/bindings/transfer registry FSM. BR-10; QA-FILE01.
- [ ] **B18 — P0:** Upload init/chunk/status/commit/cancel, cumulative durable offset, mandatory hash, magic bytes/dimensions; crash recovery READY. QA-FILE02/03.
- [ ] **B19 — P0:** Download init/ACK/window/verified, ACL mỗi chunk/window, `.part`/checksum/rename client, queue ưu tiên control. QA-FILE04/05.
- [ ] **B20 — P1:** Upload/download resume sau login, expiry/cleanup/orphan GC, server storage disk-full behavior. QA-FILE06/07.
- [ ] **B21 — P0:** Avatar thumbnail/cache, AVATAR_SET binding/version; profile/block ACL. QA-SOC01/PRIV02.
- [ ] **B22 — P0:** Post CREATE/GET/UPDATE/DELETE với privacy và expectedVersion, bind max4 ảnh, SQL policy trước LIMIT. BR-06; QA-POST01/02.
- [ ] **B23 — P0:** Feed/profile timeline keyset cursor, filter friend/block/status; invalidation events không private payload. QA-POST03/PRIV02.
- [ ] **B24 — P0:** Comment list/create/delete/tombstone + LIKE_SET unique/dedup notification. BR-07; QA-POST04.
- [ ] **B25 — P0:** Feed/composer/detail/comment UI, upload trước publish, conflict/unavailable/scroll state. QA-UI03.

M3 gate: A post FRIENDS, B friend thấy, C không thấy qua feed/detail/media; đổi ONLY_ME và block/unfriend thu hồi mọi lần đọc tiếp. Không chuyển mốc khi chỉ feed UI lọc đúng nhưng server GET/media vẫn hở.

## 5. M4 — Chat riêng và notification bền vững

- [ ] **B26 — P0:** Direct conversation unique pair + fixed members, get/create/list, policy history vs send mới. BR-08; QA-CHAT01.
- [ ] **B27 — P0:** MESSAGE_SEND transaction seq/dedup/payload hash/media binding/notification/outbox, ACK STORED. QA-CHAT02/03.
- [ ] **B28 — P0:** History before/after seq, lookup clientMessageId, offline/restart sync; recipient push failure không disconnect sender. QA-CHAT04.
- [ ] **B29 — P1:** DELIVERED/READ cursor monotonic/per-member, unread authoritative; read only message đã hiển thị. QA-CHAT05.
- [ ] **B30 — P0:** Chat UI service state PENDING/STORED/UNKNOWN/FAILED, stable retry, history scroll, file card/download. QA-UI04.
- [ ] **B31 — P0:** Notification persist/dedup/read/count + quyền target hiện tại + source coalescing. BR-11; QA-NOTI01/02.
- [ ] **B32 — P0:** Notification/badge UI, unavailable target, self-action không báo, count refetch. QA-UI05.
- [ ] **B33 — P1:** Reconnect state machine/backoff/login/sync/caches invalidate/transfer resume; close cleanup. QA-RES01/02.

M4 gate: B offline, A gửi text + attachment, server restart, B login nhận đúng một message/file/notification; A mất ACK rồi retry không nhân đôi.

## 6. M5 — UDP, nhóm và quản trị

- [ ] **B34 — P0:** UDP DISCOVER/OFFER nonce/rate-limit/bounded size, multi-interface broadcast + nhập IP/unicast fallback. QA-UDP01/02.
- [ ] **B35 — P1:** Per-session UDP key TLS cấp/refresh/revoke, signed register/endpoint binding/direction HMAC/seq/time check. QA-UDP03/04.
- [ ] **B36 — P1:** Typing UDP debounce/TTL/fan-out policy; packet loss không ảnh hưởng chat/presence. QA-UDP05.
- [ ] **B37 — P1:** GROUP_CREATE/invite/accept/reject/cancel/list, period visibleFromSeq, owner invariant/cap20. BR-09; QA-GROUP01.
- [ ] **B38 — P1:** Group rename/kick/leave/transfer/disband, history/media/read policy theo period; block exception rõ. QA-GROUP02/03.
- [ ] **B39 — P1:** Group UI và invite notification; member roles/leave warnings; typing/receipt summary theo group. QA-UI06.
- [ ] **B40 — P1:** Report create/snapshot/dedup + admin bootstrap/role policy/list/claim/resolve/suspend/unsuspend/audit. BR-12; QA-ADMIN01/02.
- [ ] **B41 — P1:** Admin/report UI, action reason/outcome; không tạo màn hình đọc private chat. QA-UI07.

UDP discovery có thể làm song song sau B05/B06 để có minh chứng môn học sớm. Nhóm/admin phải dựa trên auth/policy/transaction/storage đã đúng, không copy JOIN_ROOM không invitation của v1.

## 7. M6 — Chất lượng, release và bảo vệ

- [ ] **B42 — P0:** Bộ integration contract/policy/race/restart từ [08](08-testing-release.md), fixture/capture logs redacted. Không chỉ happy path.
- [ ] **B43 — P1:** Load20 online/50 sockets, slow reader, bulk transfer + chat latency, invalid frame/repeated auth, leak/shutdown; lưu kết quả đo. QA-PERF01/02.
- [ ] **B44 — P1:** Backup offline nhất quán DB+media, inventory/hash/restore drill; log rotate + safe cleanup retention. QA-OPS01/02.
- [ ] **B45 — P0:** Release JAR/source/config example/truststore/client instructions/server no-ui/firewall/VM instructions; smoke từ thư mục mới. QA-REL01/02.
- [ ] **B46 — P0:** Seed demo + kịch bản 3 user/admin nhiều máy; screenshot/capture TCP TLS & UDP, chứng cứ persistence và privacy. QA-DEMO.
- [ ] **B47 — P0:** Báo cáo cuối, UML/ERD/sequence, bảng yêu cầu–test–kết quả, giới hạn trung thực, kiểm link/docs theo implementation. Gate cuối P0/P1 hoàn tất.

## 8. Phụ thuộc và chia việc nếu có nhóm

```text
B03-B09 → B10-B16 → B22-B25 (post/feed)
                  ↘ B26-B33 (chat/notification)
B17-B21 ───────────→ cả post và chat
B05-B06 → B34 → B35-B36
B26-B31 → B37-B39 (group)
B10-B11/B22-B24/B31 → B40-B41 (admin)
Tất cả → B42-B47
```

Nếu 3 người: A common/server/auth/data/relations; B Swing/social screens/post/feed; C chat/media/UDP/QA. Đây chỉ là gợi ý, không phân công thật khi chưa biết team. Phải chốt DTO/policy và migration owner trước khi làm song song. Không để hai người sửa một migration version hoặc giant ChatFrame cùng lúc.

## 9. Mẫu một task triển khai

```text
Task: B14 FRIEND_ACCEPT
Requirement: BR-04
Dependencies: B08/B09/B10/B11
Server: DTO -> handler -> service transaction -> relationship repo
Client: incoming request view/controller state, pending/error refresh
Protocol: relationId + expectedVersion + idempotencyKey
Data: partial UNIQUE active pair; notification/outbox
Tests: đúng receiver, request terminal, block race, cancel race, retry
Done evidence: test results + A/B GUI walkthrough + docs khớp
```

Mỗi task phải có kiểm tra quyền âm tính và cleanup lỗi cần thiết. Không viết test đơn thuần mirror getter/setter; ưu tiên invariant/contract/race. Đổi priority hay cắt scope phải cập nhật định nghĩa “hoàn thành” ở plan, không tick bỏ task rồi tuyên bố đủ.

## 10. P2 để riêng sau nghiệm thu

- [ ] Full-text content search với ACL/index.
- [ ] Nhiều reaction, nested/sửa comment, sửa/thu hồi message với semantics rõ.
- [ ] Offline drafts/lưu pending qua client restart được bảo vệ theo user.
- [ ] Dark mode và tinh chỉnh chuyển cảnh.
- [ ] Multi-session, PostgreSQL/multiple server, thoại/video, Internet/NAT deployment.

Không dùng P2 để thay thế báo cáo/test/đóng gói; phần này không thuộc Definition of Done P0/P1.
