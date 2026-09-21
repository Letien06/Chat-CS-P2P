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

Các request chính: `JOIN_REQUEST`, `USER_LIST_REQUEST`, `PRIVATE_MESSAGE`, `CREATE_ROOM`, `JOIN_ROOM`, `LEAVE_ROOM`, `ROOM_LIST_REQUEST`, `GROUP_MESSAGE`, `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_CANCEL`, `FILE_COMPLETE`, `FILE_FAILED`, `FILE_SHARE`, `FILE_UNSHARE` và `FILE_SEARCH_REQUEST`.

Server là signaling/discovery server. Client gửi `JOIN_REQUEST` kèm `peerPort`
và danh sách `peerHosts`; server dùng thông tin đó để chuyển endpoint cho peer.

Client gửi `FILE_OFFER` trước; người nhận trả lời `FILE_ACCEPT` hoặc `FILE_REJECT`
qua server. Sau khi chấp nhận, người nhận mở một TCP connection trực tiếp tới
`peerHosts:peerPort` và gửi message `PEER_FILE_REQUEST` chứa `accessToken`.
Peer gửi `PEER_FILE_RESPONSE`, sau đó truyền raw file bytes trên chính socket đó.
Không còn `FILE_CHUNK` đi qua server. Client nhận kiểm tra kích thước và SHA-256;
`FILE_COMPLETE` hoặc `FILE_FAILED` chỉ là trạng thái truyền qua server.

File directory dùng `FILE_SHARE`, `FILE_UNSHARE`, `FILE_SEARCH_REQUEST` và
`FILE_SEARCH_RESPONSE`. Server lưu metadata và endpoint của peer online, còn
việc tải file từ kết quả tìm kiếm cũng dùng kết nối P2P trực tiếp.
