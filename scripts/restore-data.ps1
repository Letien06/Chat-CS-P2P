param([Parameter(Mandatory=$true)][string]$Backup,[Parameter(Mandatory=$true)][string]$NewDataRoot)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
java '-Dfile.encoding=UTF-8' -cp 'server/target/server-1.0.0-SNAPSHOT-shaded.jar' com.p2p.server.ops.BackupTool --restore $Backup $NewDataRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
