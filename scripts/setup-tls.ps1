param([string]$LanHost,[string]$OutputDirectory)
$ErrorActionPreference='Stop'
if (-not $OutputDirectory) { Set-Location (Join-Path $PSScriptRoot '..'); $OutputDirectory='config/tls' }
if (-not $env:SOCIAL_TLS_PASSWORD) { throw 'Set SOCIAL_TLS_PASSWORD before generating local demo stores (at least 6 characters).' }
if ($env:SOCIAL_TLS_PASSWORD.Length -lt 6) { throw 'Password needs at least 6 characters.' }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$server=Join-Path $OutputDirectory 'server.p12'
$trust=Join-Path $OutputDirectory 'truststore.p12'
$cert=Join-Path $OutputDirectory 'server.cer'
if ((Test-Path $server) -or (Test-Path $trust)) { throw 'Stores already exist. Choose a new output directory to avoid replacing trusted credentials.' }
$san='SAN=dns:localhost,ip:127.0.0.1'
if ($LanHost) { $address=$null; if ([System.Net.IPAddress]::TryParse($LanHost,[ref]$address)) { $san+=",ip:$LanHost" } else { if ($LanHost -notmatch '^[A-Za-z0-9.-]+$') { throw 'Invalid LAN hostname' }; $san+=",dns:$LanHost" } }
Push-Location -LiteralPath $OutputDirectory
try {
& keytool -genkeypair -alias social-server -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 -dname 'CN=Local Social Demo' -ext $san -ext 'EKU=serverAuth' -storetype PKCS12 -keystore 'server.p12' -storepass:env SOCIAL_TLS_PASSWORD -keypass:env SOCIAL_TLS_PASSWORD
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
& keytool -exportcert -alias social-server -keystore 'server.p12' -storepass:env SOCIAL_TLS_PASSWORD -file 'server.cer'
if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed' }
& keytool -importcert -noprompt -alias social-server -file 'server.cer' -storetype PKCS12 -keystore 'truststore.p12' -storepass:env SOCIAL_TLS_PASSWORD
if ($LASTEXITCODE -ne 0) { throw 'Trust store import failed' }
} finally { Pop-Location }
Write-Output "Created local TLS stores in $OutputDirectory. Share only truststore.p12 out of band; keep server.p12 private."
