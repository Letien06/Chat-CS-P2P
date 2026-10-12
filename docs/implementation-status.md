# Trạng thái triển khai v2 và bằng chứng

Cập nhật ngày 07/10/2026. Đây là bản triển khai trong working tree trên baseline Git `cf36ff6`, không phải một release commit đã được gắn tag. Các tài liệu 01–07 ban đầu mô tả thiết kế; source và các kết quả dưới đây xác định phần đã làm. Checkbox trong backlog không tự trở thành bằng chứng nghiệm thu.

## Phần có source chạy được

| Phạm vi / backlog | Triển khai hiện tại | Bằng chứng tự động liên quan |
|---|---|---|
| B03–B07: transport/session | WireMessage v2, framing tối đa 1 MiB, TLS truststore + hostname, HELLO, future correlation/deadline, heartbeat, cap mọi socket, writer queue 256 frame/8 MiB và ưu tiên control | SocialProtocolTest, TlsContextsTest, ProtocolRuntimeTest, SocialTransportTest; slow reader/load PASS trong full package, có gate riêng |
| B08–B11: database/auth | SQLite schema có checksum/FK, writer executor tuần tự và 2 snapshot reader; PBKDF2-HMAC-SHA256 600.000 vòng, username_key unique, một active session, revoke theo auth_version | SocialDatabaseTest, SocialServiceTest, ProtocolRuntimeTest |
| B13–B15/B22–B24/B26–B29/B31 | Profile/version, quan hệ và block hai chiều, privacy post/feed/media, keyset cursor có context, comment/like, direct chat durable/seq/dedup/outbox, recipient receipt, notification | SocialServiceTest và ProtocolRuntimeTest; không coi mọi tổ hợp race/ACL trong ma trận 08 đã được chạy |
| B17–B21 | Upload có quota/offset/fsync/hash/magic/dimensions; READY/ATTACHED binding; download window 4 chunk, verified hash; resume gắn session; recovery VERIFYING; thumbnail PNG ≤256 px có ACL | MediaServiceTest, ProtocolRuntimeTest, MediaTransferStateTest; disk-full/symlink/GC dài hạn chưa có toàn bộ drill |
| B34–B38/B40 | Discovery nonce/bounded OFFER; signed registration, HMAC direction, epoch/seq/time/endpoint; recipient-key typing; group invitation/membership period/owner; report/admin/audit | UdpServiceTest, ProtocolRuntimeTest, SocialServiceTest |
| B12/B16/B25/B30/B32/B33/B39/B41 | Swing SocialWindow có auth, friends/profile/settings, feed/composer/comment, direct/group chat, notifications, report/admin; background media/thumbnail; TLS reconnect bounded rồi yêu cầu đăng nhập lại | Client compile và state/transport tests; các test fixture chưa chứng minh toàn checklist UI hoặc đa máy |
| B43–B46 | Load harness, offline backup/restore có application lock và inventory/hash, seed 6 account giả, TLS/launch/ops/release scripts | BackupToolTest và full LoadRuntimeTest PASS; packaged Windows/restore smoke PASS, chi tiết bên dưới |

V2 dùng envelope bean và `Map<String,Object>` cho payload/result, có whitelist schema/validation và authority lấy từ session ở server. Logic nghiệp vụ/policy tập trung trong SocialService; đây chưa phải cây DTO/service/repository riêng cho từng aggregate như sơ đồ dự kiến. Mô tả này phản ánh code thực tế, không gọi các map tùy ý là typed DTO đầy đủ. MiniChat v1 còn trong source để tham chiếu; main class trong shaded JAR mặc định chạy v2.

## Bằng chứng tự động và cách chạy

Môi trường quan sát: Windows 11 (PowerShell báo build 10.0.26300), JBR/OpenJDK 17.0.14, CPU identifier `Intel64 Family 6 Model 154 Stepping 3` và 16 logical processor. Các socket test dùng localhost/ephemeral ports, SQLite/storage tạm; không suy ra kết quả LAN hoặc Internet từ số đo này.

Lệnh build của phiên triển khai dùng Maven bundled trong IntelliJ 2026.1.1, repository `.m2-local` và offline khi dependency đã có:

```powershell
mvn '-Dmaven.repo.local=.m2-local' -o test
mvn '-Dmaven.repo.local=.m2-local' -o package
```

Bằng chứng gốc nằm trong `common/target/surefire-reports`, `server/target/surefire-reports`, `client/target/surefire-reports`. Full reactor `package` đã PASS với `-DargLine=-Xmx512m`: **76 test = common 6 + server 59 + client 11; 0 failure, 0 error, 0 skipped**. Sau thay đổi TTL typing, targeted client/common package đã PASS **client 12 + common 6**, không skipped/failure/error. Tổng coverage hiện tại là **77 test duy nhất = common 6 + server 59 + client 12** qua các run này; không nói 77 test đã chạy trong một full-reactor run duy nhất. Shaded JAR client mới đã được re-smoke với restored database/media; server production không đổi sau run full.

Các coverage đã có gồm TLS trusted SAN thành công và wrong-host/untrusted cert bị từ chối; truncated/oversize frame; auth/session/schema spoofing; SQLite migration/version/FK/rollback/snapshot concurrency; friendship/privacy/version/idempotency; offline message/restart/receipt; group member period; moderation audit; file owner/offset/hash/quota/recovery/window/verified; signed UDP replay/reflection/rebind/rotate/revoke; backup/restore/corrupt manifest/inventory/no overwrite. Đây là coverage cụ thể, không đồng nghĩa mọi QA-* trong [08](08-testing-release.md) đều PASS.

## Tải đo được

`LoadRuntimeTest` tạo 20 account với PBKDF2 thật, 10.000 post nhiều privacy và 50.000 message theo sequence. Nó đo feed/history/chat/receipt burst của 20 TLS client, chạy 2 file ×25 MiB upload rồi download theo cửa sổ bằng buffer 48 KiB, đồng thời gửi MESSAGE_SEND/PING; kiểm cap 50 connection và refusal socket 51, slow reader, shutdown và thread leak. Report máy đọc được: `server/target/qa/performance.json`.

`LoadRuntimeTest` trong full reactor ngày 07/10/2026, runAt `2026-10-07T00:00:07Z` (07:00:07 Asia/Saigon) **PASS: 1 test, 0 failure/error**, chạy với `-Xmx512m`. Môi trường có 16 logical processor và 16.002,5 MiB physical RAM. Số đo thực tế:

| Metric | Actual | Gate |
|---|---:|---|
| Login 20 client | 3.548 ms | Ghi nhận, không có gate riêng |
| Feed page p95 | 74,62 ms | ≤1.000 ms |
| Chat STORED p95 | 71,25 ms | ≤500 ms |
| Chat STORED p95 khi 2 file đang truyền | 23,71 ms | <1.500 ms |
| 2×25 MiB upload và streaming download | 5.640 ms | Đúng size/SHA-256 + VERIFIED |
| 50 connection / socket 51 | Cap enforced / refused | Kể cả chưa auth |
| Slow reader | Disconnect; sampled max 184 frame / 826.712 byte | Queue ≤256 frame / 8 MiB |
| Owned/live thread tại 50 socket | 128 / 143 | Ghi nhận; sau shutdown không còn owned worker |
| Used heap sample | 84,35 MiB | JVM max heap 512 MiB |
| Server shutdown với peers còn mở | 17 ms; không còn owned worker | <5 s; threadsBeforePeerClose=[] |

Sampler 20 ms có thể bỏ lỡ thời điểm queue chạm giới hạn; giá trị 184 không được gọi là peak tuyệt đối. Pressure tạo 5.000 durable outbox event để chứng minh stalled reader; user bị loại trước khi tất cả event được dispatch. Kiểm thử này đã phát hiện close TLS bị chặn khi writer chờ peer; abortive close cho backpressure/shutdown và graceful close cho response cuối đã được sửa rồi rerun PASS. Kết quả này nằm trong full package PASS, không lấy số đo từ các run debug thất bại trước đó.

Workload tự động này là burst localhost ngắn, chưa phải chạy LAN dài hạn 1 message/client/10 s hoặc 30 chu kỳ connect/login/logout/cancel. Heap sử dụng trong JSON là sample, không phải peak heap hoặc toàn RSS của process.

## Vận hành thực tế

- TLS scripts tạo private `server.p12`, public truststore và SAN localhost/127.0.0.1/LAN theo input. Private key/password/data/cache không được đưa vào release; truststore phân phối qua kênh tin cậy, không lấy trust từ UDP OFFER.
- DemoSeedTool yêu cầu database rỗng và SOCIAL_DEMO_PASSWORD ≥10 ký tự. Sáu account demo gồm admin, friends/pending/block, PUBLIC/FRIENDS/ONLY_ME, ảnh/avatar/comment/like, offline direct text/file và group. Không có mật khẩu thật hoặc default credential trong seed.
- BackupTool nhận `--backup dataRoot NEW_BACKUP_DIR` hoặc `--restore BACKUP_DIR NEW_DATA_ROOT`. Layout backup hiện hỗ trợ `social-network.db` và `media` bên trong dataRoot. Server/seed/backup giữ `.server.lock` để từ chối snapshot khi app đang chạy.
- Backup checkpoint/close SQLite rồi copy DB và READY/ATTACHED objects, manifest schema/hash/size, kiểm integrity/FK/inventory. Restore vào root mới và expire transfer/temp không snapshot; thumbnail được tái tạo. Không overwrite database đang sử dụng.
- `scripts/package-release.ps1` xuất JAR/source/config/docs/scripts/checksums; public truststore chỉ thêm theo lựa chọn operator. Packaged scripts đã smoke từ thư mục mới ở Windows; chưa chứng minh source build trên máy sạch/Linux.

## Packaged smoke đã xác nhận

Sau full package, server JAR chạy từ thư mục tạm mới với config/TLS riêng; init/seed/admin CLI, real TLS HELLO/login và UDP discovery/registration/typing smoke PASS. Evidence ở `.tmp/package-smoke-1791331232567/qa/live-feed.png` và `.tmp/package-smoke-1791331232567/qa/live-chat.png`: Swing client thật nối packaged server, tải dữ liệu demo và render feed/chat. Ảnh này chứng minh màn hình kết nối dữ liệu thật; không chứng minh mọi thao tác UI01–07 hoặc rằng người dùng đã đi toàn luồng bằng mouse/keyboard.

`backup-data.ps1` và `restore-data.ps1` đã chạy CLI từ packaged server JAR, tạo `.tmp/package-smoke-1791331232567/backups/release-drill` và `.tmp/package-smoke-1791331232567/data-restored`. Shaded client mới sau TTL change đã nối server sử dụng restored DB/media, real TLS + UDP + live Swing feed/chat smoke PASS; hai PNG ở trên được ghi lại từ run mới. Kết quả CLI/restore này tách với unit/integration suite, không gộp ngầm vào 76 test trước đó.

## Truy vết requirement → B00–B47 → QA → trạng thái

“Source + test” nghĩa là chức năng đã có và các test nêu tên PASS; không nghĩa mọi checklist nghiệm thu tương ứng đã được thực hiện. “Một phần” chỉ rõ đầu ra còn thiếu. Những việc yêu cầu giảng viên, Linux/LAN, thao tác người dùng hoặc báo cáo không được tick hoàn thành từ Maven.

| Task | Requirement / QA | Trạng thái và evidence thực tế |
|---|---|---|
| B00 | Baseline / QA-BASE | Một phần: baseline Git cf36ff6, legacy FrameCodecTest/LocalConnectionIntegrationTest PASS; chưa có walkthrough hai GUI v1 trước thay đổi. |
| B01 | Rubric/phạm vi | Chưa xác nhận bên ngoài source: quyết định TCP/TLS+UDP và P0/P1 có trong plan; chưa có rubric/ngày nộp/đội nhóm mới được xác nhận. |
| B02 | Tracker/JDK/build | Có mapping tại bảng này, UTF-8/JDK 17; full package 76 test PASS và latest client 12/common 6 PASS; source rebuild trên máy sạch còn thuộc gate release. |
| B03 | BR-01 / QA-NET01–02 | Source + SocialProtocolTest; bean envelope/map whitelist, chưa tách class DTO riêng cho mọi command như thiết kế. |
| B04 | QA-NET03 | Source + SocialTransportTest: event/out-of-order response không resolve sai future, RemoteException, disconnect cleanup; runtime HELLO/version test. |
| B05 | QA-SEC01 | Source + TlsContextsTest: valid trust/SAN, wrong host và untrusted cert; packaged TLS smoke. Cert/firewall nhiều máy còn chờ. |
| B06–B07 | QA-LIFE01–03 | Source + ProtocolRuntimeTest/LoadRuntimeTest: 50 tổng socket, refusal socket 51, slow-reader queue bound, shutdown 17 ms khi peer còn mở, no owned worker; không claim toàn fault bind/heartbeat drill. |
| B08 | QA-DATA01–02 | Source + 7 SocialDatabaseTest: fresh schema/FK, checksum/future schema reject, rollback, 2 snapshot reader, close/drain. Chỉ có schema version hiện tại, chưa có lịch sử nâng cấp nhiều version. |
| B09 | QA-DATA03 | Source idempotency/outbox/policy và restart/dedup tests PASS; service tập trung thay vì đủ lớp repository/DTO; kill-process tại từng điểm outbox chưa đủ drill. |
| B10–B11 | BR-02 / QA-AUTH01–03 | Source + SocialServiceTest/ProtocolRuntimeTest/SessionPrivacyRuntimeTest; PBKDF2 600k, generic error, normalized username rate key, one session, auth_version/revocation. |
| B12 | QA-UI01 | Auth Swing source, client state tests và packaged live login/feed/chat smoke; chưa toàn keyboard/cert/error/password-change checklist thực tế. |
| B13–B15 | BR-03–05 / QA-SOC01–03,PRIV01 | Source + SocialServiceTest/SessionPrivacyRuntimeTest: profile/version, relation direction/race, block, pending-relation presence không leak, direct-history exception. |
| B16 | QA-UI02 | Friends/search/profile/settings source; chưa có walkthrough toàn hướng/empty/stale/block trong checklist UI02. |
| B17–B19 | BR-10 / QA-FILE01–05 | Source + 17 MediaServiceTest/ProtocolRuntimeTest/MediaTransferStateTest: owner/offset/hash/quota/image/ACL/window/terminal/verification; file 25 MiB thực tế PASS. |
| B20 | QA-FILE06–07 | Source + resume/session/restart/expiry/clamp/physical reservation tests; chưa disk-full và retention/orphan dài hạn đủ matrix. |
| B21 | BR-03,10 / QA-SOC01,PRIV02 | Source + bounded thumbnail/current ACL/cache regression tests; visual avatar/render có fixture, scaling/Linux UI còn chờ. |
| B22–B24 | BR-06–07 / QA-POST01–04,PRIV02 | Source + SocialServiceTest: privacy/current ACL/version/cursor/attachment/like/comment/unavailable. Chưa mọi combinatorial race/feed navigation thủ công. |
| B25 | QA-UI03 | Feed/composer/detail/comment source; live-feed PNG packaged fixture là bằng chứng kết nối/render, chưa full edit/conflict/privacy keyboard walkthrough. |
| B26–B29 | BR-08 / QA-CHAT01–05 | Source + policy/idempotency/receipt/offline/restart tests; 20-client burst và chat ACK trong bulk transfer PASS. |
| B30 | QA-UI04 | Chat/file/UNKNOWN retry source và state regressions; live-chat PNG; checksum/upload/download thực tế qua TLS. Mọi cancel/error/progress/scaling thao tác thật còn chờ. |
| B31–B32 | BR-11 / QA-NOTI01–02,UI05 | Notification service/source UI + source dedup/read/unavailable tests PASS; full notification UI navigation/badge checklist còn chờ. |
| B33 | QA-RES01–02 | Source TLS reconnect/backoff bounded, login lại không lưu password, stable pending ID/transfer resume, cleanup tests; chưa đủ fault mất mạng nhiều máy/30 chu kỳ. |
| B34–B36 | QA-UDP01–05 | Source + UdpServiceTest/ProtocolRuntimeTest/packaged UDP smoke: nonce, HMAC/key/domain/seq/time/rebind/rotate/revoke/current policy. LAN broadcast/firewall và loss/reorder demo thực tế chưa xác minh. |
| B37–B38 | BR-09 / QA-GROUP01–03 | Source + SocialServiceTest: invite expiry, membership period/rejoin bounds, owner/admin policy. Chưa mọi race cap20/block/owner-suspension walkthrough. |
| B39 | QA-UI06 | Group/invite/member/admin controls source, state/render fixture; chưa full multi-client owner transfer/kick/typing/read-summary UI checklist. |
| B40–B41 | BR-12 / QA-ADMIN01–02,UI07 | Source service/report/admin/audit + moderation tests; bootstrap/demo admin CLI smoke. Toàn admin UI claim/resolve/reason/role invalidation walkthrough chưa chứng minh. |
| B42 | QA matrix | 77 test duy nhất PASS qua full + targeted run; current coverage được nêu cụ thể, không đánh mọi ID ở tài liệu 08 là PASS. |
| B43 | QA-PERF01–02 | Full load PASS dưới max heap 512 MiB: 20 auth, 10k/50k data, 2×25 MiB, 50 sockets, slow reader, shutdown; LAN steady workload/30 chu kỳ/peak-RSS chưa đo. |
| B44 | QA-OPS01–02 | BackupToolTest 5 case PASS: lock, roundtrip, integrity/inventory/hash, corruption/no overwrite. Packaged backup/restore CLI + restored real TLS/UDP/Swing smoke PASS ở release-drill/data-restored; GC dài hạn/disk-full chưa đủ drill. |
| B45 | QA-REL01–02 | Shaded JAR/source/scripts/config/docs/checksum có packaging, packaged Windows TLS/UDP + restored media smoke; Linux desktop/đa máy chưa có môi trường thực tế. |
| B46 | QA-DEMO | Seed 6 fictional accounts + generated image/file, live packaged + restored-data feed/chat screenshots; chưa complete kịch bản A/B/C/admin nhiều máy và Wireshark capture. |
| B47 | Báo cáo/gate cuối | Docs 03–08/plan và trạng thái cập nhật; UML/ERD/sequence hiện có ở tài liệu thiết kế. Báo cáo nộp cuối/rubric/capture/LAN/UI gates chưa được nghiệm thu đầy đủ. |

## Gate còn phải nghiệm thu bằng môi trường thật

1. Ít nhất một Linux desktop/VM và một Windows client cùng server LAN, UDP broadcast/unicast/fallback/firewall thực tế. Môi trường hiện chỉ thấy WSL docker-desktop không có Java/bash và không có Linux Docker engine hoạt động; chưa có bằng chứng để đánh PASS QA-REL02/QA-UDP02 đa máy.
2. Checklist UI01–07 đầy đủ bằng thao tác thật: resize/100–200% scaling, tiếng Việt/emoji/keyboard, cancellation/conflict/unavailable, reconnect/logout/account switch. State tests và ảnh cửa sổ chỉ chứng minh phần được thực hiện, không thay thế checklist. Trên UI Windows hiện tại đã quan sát một số emoji hiện ô vuông (font tofu); đây là giới hạn hiển thị cần kiểm tra và sửa trong gate UI.
3. Crash injection tại mọi điểm transaction/storage, disk full, symlink/reparse-point escape, retention dài hạn, orphan GC và 30 chu kỳ leak. Các test hiện có rollback/restart/VERIFYING recovery nhưng chưa phủ mọi fault trong plan.
4. Chốt rubric/ngày nộp và hoàn thiện báo cáo UML/ERD/sequence, requirement → backlog → QA → evidence; capture TCP/TLS và UDP đã mask trên demo nhiều máy. Phần này cần điều kiện bên ngoài source.

Không đánh dấu gate phát hành hoặc toàn P0/P1 là đã nghiệm thu khi các bằng chứng trên chưa có.
