$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not $env:SOCIAL_TLS_PASSWORD -and (Test-Path -LiteralPath 'config/tls/password.local')) {
    $env:SOCIAL_TLS_PASSWORD = (Get-Content -LiteralPath 'config/tls/password.local' -Raw).Trim()
}

$jar = 'server/target/server-1.0.0-SNAPSHOT-shaded.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Chưa có server JAR. Hãy chạy scripts/build-all.ps1 trước.'
}

java '-Dfile.encoding=UTF-8' -jar $jar 'server/config/social.properties' @args
if ($LASTEXITCODE -ne 0) { throw "Server dừng với mã lỗi $LASTEXITCODE" }
