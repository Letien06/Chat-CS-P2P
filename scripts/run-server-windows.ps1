param([switch]$NoUi)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path -LiteralPath 'server/target/server-1.0.0-SNAPSHOT-shaded.jar')) {
    throw 'Chưa có server JAR. Chạy scripts/build-all.ps1 trước.'
}
if (-not (Test-Path -LiteralPath 'config/tls/server.p12') -and -not $env:SOCIAL_TLS_PASSWORD) {
    throw 'Chưa có chứng chỉ. Chạy scripts/init-local.ps1 -LanHost <IP-máy-Windows> trước.'
}
if (-not $env:SOCIAL_TLS_PASSWORD -and -not (Test-Path -LiteralPath 'config/tls/password.local')) {
    throw 'Cần đặt SOCIAL_TLS_PASSWORD cho keystore server.'
}
if ($NoUi) { & "$PSScriptRoot/run-server.ps1" --no-ui }
else { & "$PSScriptRoot/run-server.ps1" }
