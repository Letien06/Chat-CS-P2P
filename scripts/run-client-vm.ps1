param(
    [string]$ServerHost = '',
    [ValidateRange(1,65535)][int]$ServerPort = 5000,
    [string]$Truststore = 'config/tls/truststore.p12'
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if ([string]::IsNullOrWhiteSpace($ServerHost)) { $ServerHost = $env:SOCIAL_SERVER_HOST }
if ([string]::IsNullOrWhiteSpace($ServerHost)) { $ServerHost = Read-Host 'Nhập IP hoặc hostname của máy chạy server' }
if ([string]::IsNullOrWhiteSpace($ServerHost)) { throw 'Cần nhập địa chỉ server.' }
if (-not (Test-Path -LiteralPath 'client/target/client-1.0.0-SNAPSHOT-shaded.jar')) {
    throw 'Chưa có client JAR. Chạy scripts/build-all.ps1 trước.'
}
if (-not (Test-Path -LiteralPath $Truststore)) {
    throw "Thiếu truststore: $Truststore. Chép truststore.p12 từ server hoặc chọn -Truststore <đường-dẫn>."
}
Write-Host "Client kết nối ${ServerHost}:$ServerPort. Nhập mật khẩu truststore trong Thiết lập TLS khi đăng nhập."
& "$PSScriptRoot/run-client.ps1" $ServerHost $ServerPort $Truststore
