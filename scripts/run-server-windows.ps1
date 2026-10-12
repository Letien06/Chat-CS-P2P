param([switch]$NoUi)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path -LiteralPath 'server/target/server-1.0.0-SNAPSHOT-shaded.jar')) {
    throw 'Chưa có server JAR. Chạy scripts/build-all.ps1 trước.'
}
if ($NoUi) { & "$PSScriptRoot/run-server.ps1" --no-ui }
else { & "$PSScriptRoot/run-server.ps1" }
