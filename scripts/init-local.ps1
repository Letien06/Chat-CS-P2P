param([string]$LanHost)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if ((Test-Path -LiteralPath 'config/tls/server.p12') -or (Test-Path -LiteralPath 'config/tls/truststore.p12')) {
    throw 'TLS đã có. Script không ghi đè chứng chỉ; dùng setup-tls.ps1 với thư mục mới nếu cần đổi SAN.'
}
New-Item -ItemType Directory -Force -Path 'config/tls' | Out-Null
$localTlsSecret = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:SOCIAL_TLS_PASSWORD = $localTlsSecret
& (Join-Path $PSScriptRoot 'setup-tls.ps1') -LanHost $LanHost
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Không tạo được chứng chỉ TLS' }
[IO.File]::WriteAllText((Join-Path (Get-Location) 'config/tls/password.local'), $localTlsSecret, [Text.UTF8Encoding]::new($false))
Write-Output 'TLS local đã sẵn sàng. password.local chỉ dùng trên máy này, không chia sẻ cùng server.p12.'
