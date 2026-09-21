$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) {
    $bundled = Get-ChildItem 'C:\Program Files\JetBrains' -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
        Where-Object FullName -Like '*\plugins\maven\lib\maven3\bin\mvn.cmd' |
        Select-Object -First 1
    if (-not $bundled) {
        throw 'Không tìm thấy Maven. Hãy chọn Maven bundled trong IntelliJ hoặc cài Maven.'
    }
    $maven = $bundled.FullName
}

& $maven '-Dmaven.repo.local=.m2-local' clean package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
