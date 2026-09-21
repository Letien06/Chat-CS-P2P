# Control protocol

Kết nối điều khiển dùng TCP. Mỗi message được đóng gói:

```text
[4-byte big-endian payload length][UTF-8 JSON payload]
```

JSON có các trường envelope:

```json
{
  "type": "JOIN_REQUEST",
  "requestId": "uuid",
  "payload": {}
}
```

Client gửi `JOIN_REQUEST` với trường `name` trước khi dùng các chức năng chat. Server trả `JOIN_RESPONSE`; không cần tài khoản, mật khẩu hoặc token.

Các request chính: `JOIN_REQUEST`, `USER_LIST_REQUEST`, `PRIVATE_MESSAGE`, `CREATE_ROOM`, `JOIN_ROOM`, `LEAVE_ROOM`, `GROUP_MESSAGE`, `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_CANCEL`, `FILE_PROGRESS`, `FILE_COMPLETE`, `FILE_FAILED`, `FILE_SHARE`, `FILE_UNSHARE` và `FILE_SEARCH_REQUEST`.

Truyền file qua Server dùng thêm `FILE_CHUNK`. Client gửi `FILE_OFFER` trước;
người nhận trả lời `FILE_ACCEPT` hoặc `FILE_REJECT`. Khi được chấp nhận, client gửi
từng chunk trong field `data` dạng Base64, kèm `transferId`, `sequence` và `receiver`.
Server chuyển tiếp nguyên message đến người nhận. Message `FILE_COMPLETE` kết thúc
transfer; client nhận kiểm tra kích thước và SHA-256 trước khi báo thành công.
