param(
    [string]$ServerHost = '',
    [ValidateRange(1,65535)][int]$ServerPort = 5000
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if ([string]::IsNullOrWhiteSpace($ServerHost)) { $ServerHost = $env:SOCIAL_SERVER_HOST }
if ([string]::IsNullOrWhiteSpace($ServerHost)) { $ServerHost = 'localhost' }
if (-not (Test-Path -LiteralPath 'client/target/client-1.0.0-SNAPSHOT-shaded.jar')) {
    throw 'Chưa có client JAR. Chạy scripts/build-all.ps1 trước.'
}
Write-Host "Client TCP kết nối ${ServerHost}:$ServerPort."
& "$PSScriptRoot/run-client.ps1" $ServerHost $ServerPort
