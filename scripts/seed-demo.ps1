param([string]$ConfigPath = 'server/config/social.properties')
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not $env:SOCIAL_DEMO_PASSWORD) { throw 'Đặt SOCIAL_DEMO_PASSWORD (>=10 ký tự, chỉ dành cho tài khoản demo).' }
java '-Dfile.encoding=UTF-8' -cp 'server/target/server-1.0.0-SNAPSHOT-shaded.jar' com.p2p.server.ops.DemoSeedTool --config $ConfigPath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
