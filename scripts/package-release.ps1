param()
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$releaseName = 'social-v2-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$releaseDirectory = Join-Path (Get-Location) ('release/' + $releaseName)
if (Test-Path -LiteralPath $releaseDirectory) { throw 'Thư mục release đã tồn tại.' }
$artifacts = @('server/target/server-1.0.0-SNAPSHOT-shaded.jar','client/target/client-1.0.0-SNAPSHOT-shaded.jar')
foreach ($artifact in $artifacts) { if (-not (Test-Path -LiteralPath $artifact)) { throw 'Build package trước khi tạo release.' } }
New-Item -ItemType Directory -Path $releaseDirectory | Out-Null
foreach ($folder in @('server/target','server/config','client/target','scripts','docs','source')) {
    New-Item -ItemType Directory -Path (Join-Path $releaseDirectory $folder) -Force | Out-Null
}
Copy-Item -LiteralPath $artifacts[0] -Destination (Join-Path $releaseDirectory 'server/target/server-1.0.0-SNAPSHOT-shaded.jar')
Copy-Item -LiteralPath $artifacts[1] -Destination (Join-Path $releaseDirectory 'client/target/client-1.0.0-SNAPSHOT-shaded.jar')
Copy-Item -LiteralPath 'server/config/social.properties' -Destination (Join-Path $releaseDirectory 'server/config/social.properties')
Copy-Item -LiteralPath 'README.md' -Destination (Join-Path $releaseDirectory 'README.md')
Copy-Item -LiteralPath 'plan.md' -Destination (Join-Path $releaseDirectory 'plan.md')
Copy-Item -LiteralPath 'pom.xml','.gitignore' -Destination $releaseDirectory
Get-ChildItem -LiteralPath 'scripts' -File | Where-Object Name -NE 'package-release.ps1' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $releaseDirectory ('scripts/' + $_.Name))
}
Get-ChildItem -LiteralPath 'scripts' -Directory | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $releaseDirectory ('scripts/' + $_.Name)) -Recurse
}
Get-ChildItem -LiteralPath 'docs' -Filter '*.md' -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $releaseDirectory ('docs/' + $_.Name)) }
Copy-Item -LiteralPath 'docs/archive' -Destination (Join-Path $releaseDirectory 'docs/archive') -Recurse
Copy-Item -LiteralPath 'pom.xml','plan.md','.gitignore' -Destination (Join-Path $releaseDirectory 'source')
foreach ($module in @('common','server','client')) {
    New-Item -ItemType Directory -Path (Join-Path $releaseDirectory $module) -Force | Out-Null
    Copy-Item -LiteralPath ($module + '/pom.xml') -Destination (Join-Path $releaseDirectory ($module + '/pom.xml'))
    Copy-Item -LiteralPath ($module + '/src') -Destination (Join-Path $releaseDirectory ($module + '/src')) -Recurse
    if (Test-Path -LiteralPath ($module + '/README.md')) {
        Copy-Item -LiteralPath ($module + '/README.md') -Destination (Join-Path $releaseDirectory ($module + '/README.md'))
    }
    New-Item -ItemType Directory -Path (Join-Path $releaseDirectory ('source/' + $module)) -Force | Out-Null
    Copy-Item -LiteralPath ($module + '/pom.xml') -Destination (Join-Path $releaseDirectory ('source/' + $module + '/pom.xml'))
    Copy-Item -LiteralPath ($module + '/src') -Destination (Join-Path $releaseDirectory ('source/' + $module + '/src')) -Recurse
    if (Test-Path -LiteralPath ($module + '/README.md')) {
        Copy-Item -LiteralPath ($module + '/README.md') -Destination (Join-Path $releaseDirectory ('source/' + $module + '/README.md'))
    }
}
Copy-Item -LiteralPath 'scripts','docs' -Destination (Join-Path $releaseDirectory 'source') -Recurse
New-Item -ItemType Directory -Path (Join-Path $releaseDirectory 'source/server/config') -Force | Out-Null
Copy-Item -LiteralPath 'server/config/social.properties' -Destination (Join-Path $releaseDirectory 'source/server/config/social.properties')
Copy-Item -LiteralPath 'README.md' -Destination (Join-Path $releaseDirectory 'source/README.md')
New-Item -ItemType Directory -Path (Join-Path $releaseDirectory 'qa') -Force | Out-Null
if (Test-Path -LiteralPath 'server/target/qa/performance.json') {
    Copy-Item -LiteralPath 'server/target/qa/performance.json' -Destination (Join-Path $releaseDirectory 'qa/performance.json')
}
$releasePrefix = $releaseDirectory.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
$manifestLines = Get-ChildItem -LiteralPath $releaseDirectory -File -Recurse | Sort-Object FullName | ForEach-Object {
    if (-not $_.FullName.StartsWith($releasePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Artifact path outside release root.' }
    $relativeArtifact = $_.FullName.Substring($releasePrefix.Length).Replace('\','/')
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relativeArtifact
}
Set-Content -LiteralPath (Join-Path $releaseDirectory 'checksums.sha256') -Value $manifestLines -Encoding utf8
Write-Output ('Release created: ' + $releaseDirectory)
Write-Output 'databases, uploads and Maven cache were excluded.'
