param([Parameter(Mandatory = $true)][string]$Archive)

$ErrorActionPreference = "Stop"
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$Archive = [IO.Path]::GetFullPath($Archive)
if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) { throw "Companion archive is unavailable" }
if ([IO.Path]::GetFileName($Archive) -notmatch '^keenwg-companion-arm64-([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\.tar\.gz$') {
    throw "Unexpected companion archive name"
}
$version = $Matches[1]
$entries = @(& tar -tf $Archive)
if ($LASTEXITCODE -ne 0) { throw "Companion archive cannot be read" }
$required = @("VERSION", "SHA256SUMS", "keenwg-companion", "S96keenwg-companion", "install-companion.sh", "uninstall-companion.sh", "cleanup-obsolete-controller.sh", "companion.config.example.json")
foreach ($name in $required) {
    if ($entries -notcontains $name) { throw "Companion archive is missing $name" }
}
$assetDirectory = Join-Path $repoRoot "app\src\main\assets\companion"
New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null
$assetName = "keenwg-companion-arm64.tgz"
$assetPath = Join-Path $assetDirectory $assetName
Copy-Item -LiteralPath $Archive -Destination $assetPath -Force
$item = Get-Item -LiteralPath $assetPath
$inspect = Join-Path ([IO.Path]::GetTempPath()) ("keenwg-companion-inspect-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $inspect | Out-Null
try {
    & tar -xf $Archive -C $inspect keenwg-companion
    if ($LASTEXITCODE -ne 0) { throw "Companion binary cannot be extracted" }
    $binary = Join-Path $inspect "keenwg-companion"
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Companion binary is missing" }
    $binaryHash = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant()
} finally {
    if (Test-Path -LiteralPath $inspect) { Remove-Item -LiteralPath $inspect -Recurse -Force }
}
$manifest = [ordered]@{
    schema_version = 1
    version = $version
    architecture = "arm64"
    asset = $assetName
    sha256 = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    binary_sha256 = $binaryHash
    size = $item.Length
}
$json = ($manifest | ConvertTo-Json -Compress) + "`n"
[IO.File]::WriteAllText((Join-Path $assetDirectory "manifest.json"), $json, [Text.UTF8Encoding]::new($false))
Write-Output (Join-Path $assetDirectory "manifest.json")
