$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$jar = 'server/target/server-1.0.0-SNAPSHOT-shaded.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Chưa có server JAR. Hãy chạy scripts/build-all.ps1 trước.'
}

java -jar $jar 'server/config/server.properties'
