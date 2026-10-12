# Chạy server Windows, client local và client máy ảo

Code: https://github.com/Letien06/Chat-CS-P2P, nhánh `main`. Mỗi máy cần JDK 17+; máy build source cần Maven. Máy ảo Linux cần môi trường desktop để mở Swing.

## Đồng bộ và build

Lần đầu trên máy ảo:

```sh
git clone https://github.com/Letien06/Chat-CS-P2P.git
cd Chat-CS-P2P
```

Những lần sau, tại thư mục repository:

```sh
git pull --ff-only origin main
```

Windows build bằng `powershell -ExecutionPolicy Bypass -File scripts/build-all.ps1`; Linux build bằng `bash scripts/build-all.sh`. Sau mỗi lần pull có đổi source, build lại. JAR không nằm trên Git; có thể chép `client/target/client-1.0.0-SNAPSHOT-shaded.jar` từ máy Windows sang cùng đường dẫn trên máy ảo thay cho build.

## Máy Windows chạy server

Xem IP máy Windows bằng `ipconfig`; ví dụ `192.168.1.15`. Máy ảo phải truy cập được địa chỉ này (Bridged hoặc Host-only khi host có địa chỉ trên mạng đó). Dùng đúng địa chỉ có trong SAN của chứng chỉ server.

Nếu chưa khởi tạo TLS:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-local.ps1 -LanHost 192.168.1.15
```

Nếu đã có TLS local, không chạy lại `init-local.ps1`. Kiểm tra SAN hiện tại (keytool sẽ hỏi mật khẩu keystore):

```powershell
keytool -list -v -keystore config/tls/server.p12
```

Nếu chưa có IP cần dùng, dừng server, tạo chứng chỉ mới ở thư mục riêng:

```powershell
$env:SOCIAL_TLS_PASSWORD = Read-Host 'Mật khẩu cho TLS LAN mới (ít nhất 6 ký tự)'
.\scripts\setup-tls.ps1 -LanHost 192.168.1.15 -OutputDirectory config/tls-lan
```

Đổi `tls.key.store` trong `server/config/social.properties` thành `config/tls-lan/server.p12`. Giữ biến `SOCIAL_TLS_PASSWORD` trong cửa sổ chạy server; mỗi cửa sổ mới cần đặt lại. Client local và máy ảo cùng dùng `config/tls-lan/truststore.p12` và mật khẩu mới. Chứng chỉ cũ và database được giữ nguyên. Các file `.p12` không được đưa lên Git.

Chạy server (giữ cửa sổ này mở):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-server-windows.ps1
```

Có thể thêm `-NoUi` để chạy server không dashboard.

Nếu Windows Firewall chặn kết nối, mở PowerShell bằng quyền Administrator và tạo rule trong mạng đang dùng:

```powershell
New-NetFirewallRule -DisplayName 'VKU Social TCP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000 -Profile Private
```

TCP 5000 dùng cho đăng nhập, chat, Broadcast và file. Bản đồ án mặc định không cần UDP; client nhập IP và port trực tiếp trên form.

## Một client trên Windows

Mở PowerShell khác, vào thư mục repository:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-client.ps1 localhost 5000
```

Nếu dùng chứng chỉ ở `tls-lan`, chạy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-client.ps1 localhost 5000 config/tls-lan/truststore.p12
```

Nhập mật khẩu mới trong **Thiết lập TLS** nếu dùng `tls-lan`; script chỉ tự đọc mật khẩu của bộ TLS local mặc định.

## Client trên máy ảo

Chép **truststore.p12 của bộ chứng chỉ đang dùng trên server** vào `config/tls/truststore.p12` của máy ảo; có thể dùng Shared Folder. Máy ảo không cần `server.p12` hay database. Khi đăng nhập, bấm **Thiết lập TLS**, nhập mật khẩu truststore đã tạo ở server.

Máy ảo Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-client-vm.ps1 -ServerHost 192.168.1.15
```

Máy ảo Linux (Ubuntu, v.v.):

```bash
bash scripts/run-client-vm.sh 192.168.1.15
```

Đường dẫn truststore tùy chọn: Windows thêm `-Truststore 'đường-dẫn/truststore.p12'`; Linux dùng `bash scripts/run-client-vm.sh 192.168.1.15 5000 /đường/dẫn/truststore.p12`.

Dùng tài khoản khác nhau cho hai client. Mỗi tài khoản chỉ có một phiên đang đăng nhập. Kiểm tra TCP từ Windows VM bằng `Test-NetConnection 192.168.1.15 -Port 5000`; Linux dùng `nc -vz 192.168.1.15 5000` nếu có `nc`.
