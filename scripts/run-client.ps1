$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Cần cài JDK 17+ và thêm java vào PATH.' }
if (-not $env:SOCIAL_TRUSTSTORE_PASSWORD -and (Test-Path -LiteralPath 'config/tls/password.local')) {
    $env:SOCIAL_TRUSTSTORE_PASSWORD = (Get-Content -LiteralPath 'config/tls/password.local' -Raw).Trim()
}
$hostName = if ($args.Count -ge 1) { $args[0] } elseif ($env:SOCIAL_SERVER_HOST) { $env:SOCIAL_SERVER_HOST } else { 'localhost' }
$serverPort = if ($args.Count -ge 2) { $args[1] } elseif ($env:SOCIAL_SERVER_PORT) { $env:SOCIAL_SERVER_PORT } else { '5000' }
$truststorePath = if ($args.Count -ge 3) { $args[2] } elseif ($env:SOCIAL_TRUSTSTORE) { $env:SOCIAL_TRUSTSTORE } else { 'config/tls/truststore.p12' }
$parsedPort = 0
if (-not [int]::TryParse([string]$serverPort, [ref]$parsedPort) -or $parsedPort -lt 1 -or $parsedPort -gt 65535) { throw 'Port phải từ 1 đến 65535.' }
$jar = 'client/target/client-1.0.0-SNAPSHOT-shaded.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Chưa có client JAR. Chạy scripts/build-all.ps1 trước.' }
java '-Dfile.encoding=UTF-8' ("-Dsocial.host=$hostName") ("-Dsocial.port=$parsedPort") ("-Dsocial.truststore=$truststorePath") -jar $jar
if ($LASTEXITCODE -ne 0) { throw "Client dừng với mã lỗi $LASTEXITCODE" }
