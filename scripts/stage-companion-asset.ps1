param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$SignedManifest
)

$ErrorActionPreference = "Stop"
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$Archive = [IO.Path]::GetFullPath($Archive)
$SignedManifest = [IO.Path]::GetFullPath($SignedManifest)
if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) { throw "Companion archive is unavailable" }
if (-not (Test-Path -LiteralPath $SignedManifest -PathType Leaf)) { throw "Signed update manifest is unavailable" }
if ([IO.Path]::GetFileName($Archive) -notmatch '^keenwg-companion-arm64-([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\.tar\.gz$') {
    throw "Unexpected companion archive name"
}
$version = $Matches[1]
$signed = Get-Content -LiteralPath $SignedManifest -Raw | ConvertFrom-Json
$signedProperties = @($signed.PSObject.Properties.Name)
$requiredProperties = @("schema_version", "version", "architecture", "archive_sha256", "archive_size", "binary_sha256", "key_id", "signature")
$actualProperties = (($signedProperties | Sort-Object) -join "`n")
$expectedProperties = (($requiredProperties | Sort-Object) -join "`n")
if ($actualProperties -ne $expectedProperties) { throw "Signed update manifest has an unsupported schema" }
if ([int]$signed.schema_version -ne 1 -or [string]$signed.version -ne $version -or [string]$signed.architecture -ne "arm64") { throw "Signed update manifest identity does not match archive" }
if ([string]$signed.archive_sha256 -notmatch '^[0-9a-f]{64}$' -or [string]$signed.binary_sha256 -notmatch '^[0-9a-f]{64}$' -or [string]$signed.key_id -notmatch '^[a-z0-9][a-z0-9-]{2,63}$' -or [string]$signed.signature -notmatch '^[A-Za-z0-9+/]{86}$') { throw "Signed update manifest fields are invalid" }
$archiveItem = Get-Item -LiteralPath $Archive
$archiveHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ([long]$signed.archive_size -ne $archiveItem.Length -or [string]$signed.archive_sha256 -ne $archiveHash) { throw "Signed update manifest does not match archive" }
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
if ($binaryHash -ne [string]$signed.binary_sha256) { throw "Signed update manifest does not match companion binary" }
$manifest = [ordered]@{
    schema_version = 1
    version = $version
    architecture = "arm64"
    asset = $assetName
    sha256 = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    binary_sha256 = $binaryHash
    size = $item.Length
    key_id = [string]$signed.key_id
    signature = [string]$signed.signature
}
$json = ($manifest | ConvertTo-Json -Compress) + "`n"
[IO.File]::WriteAllText((Join-Path $assetDirectory "manifest.json"), $json, [Text.UTF8Encoding]::new($false))
Write-Output (Join-Path $assetDirectory "manifest.json")
