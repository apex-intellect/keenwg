param(
    [string]$GoExecutable = "go",
    [string]$LinuxGoExecutable = "",
    [string]$GradleExecutable = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$moduleRoot = Join-Path $repoRoot "xkeen-control"
if ([string]::IsNullOrWhiteSpace($GradleExecutable)) {
    $GradleExecutable = Join-Path $repoRoot 'gradlew.bat'
}
$assetManifestPath = Join-Path $repoRoot "app\src\main\assets\companion\manifest.json"
$buildText = Get-Content -LiteralPath (Join-Path $repoRoot 'app\build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $versionMatch.Success) { throw 'Cannot determine application version' }
$releaseVersion = $versionMatch.Groups[1].Value

function Invoke-Checked([string]$Label, [scriptblock]$Command) {
    Write-Host "== $Label =="
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE" }
}

function Get-WslPath([string]$WindowsPath) {
    $result = (& wsl.exe -e wslpath -a $WindowsPath).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($result)) { throw "WSL path conversion failed" }
    return $result
}

function Quote-Sh([string]$Value) {
    if ($Value.Contains("'")) { throw "Single quotes are not supported in WSL verification paths" }
    return "'$Value'"
}

if ([string]::IsNullOrWhiteSpace($LinuxGoExecutable)) {
    $LinuxGoExecutable = (& wsl.exe -e sh -lc 'command -v go || true').Trim()
}
if ([string]::IsNullOrWhiteSpace($LinuxGoExecutable)) {
    throw "A Linux Go executable is required for the race detector"
}
foreach ($goModule in @(
    @{ Name = 'Companion'; Path = $moduleRoot },
    @{ Name = 'Collector'; Path = (Join-Path $repoRoot 'collector') }
)) {
    $wslModule = Get-WslPath $goModule.Path
    $raceCommand = "cd $(Quote-Sh $wslModule) && $(Quote-Sh $LinuxGoExecutable) test ./... -race -count=1"
    Invoke-Checked "$($goModule.Name) race tests" { & wsl.exe -e sh -lc $raceCommand }
    Invoke-Checked "$($goModule.Name) vet" { & $GoExecutable -C $goModule.Path vet ./... }
}

$wslPackaging = Get-WslPath (Join-Path $moduleRoot "packaging")
$packagingCommand = "cd $(Quote-Sh $wslPackaging) && sh ./install-companion_test.sh"
Invoke-Checked "Packaging tests in fake roots" { & wsl.exe -e sh -lc $packagingCommand }
Invoke-Checked "Secure-only current runtime policy" { & (Join-Path $PSScriptRoot 'verify-current-runtime.ps1') }

$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$signingConfigured = @(
    $env:KEENWG_KEYSTORE_FILE,
    $env:KEENWG_KEYSTORE_PASSWORD,
    $env:KEENWG_KEY_ALIAS,
    $env:KEENWG_KEY_PASSWORD
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$signingConfigured = $signingConfigured.Count -eq 4
$releaseOutput = Join-Path $repoRoot "app\build\outputs\apk\release"
foreach ($stale in @("app-release.apk", "app-release-unsigned.apk")) {
    $candidate = Join-Path $releaseOutput $stale
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { Remove-Item -LiteralPath $candidate -Force }
}
Invoke-Checked "Android verification" {
    & $GradleExecutable -p $repoRoot :app:testDebugUnitTest :app:lintDebug :app:verifyFileProviderPolicy :app:verifyLocaleResources :app:verifyUiResources :app:assembleDebug :app:assembleRelease --console=plain --no-daemon
}
Invoke-Checked "CycloneDX SBOM" { & (Join-Path $PSScriptRoot 'generate-sbom.ps1') -GoExecutable $GoExecutable -GradleExecutable $GradleExecutable }

function Get-StreamSha256([IO.Stream]$Stream) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Stream))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-CompatibleRelativePath([string]$BasePath, [string]$TargetPath) {
    $baseUri = [Uri]($BasePath.TrimEnd('\') + '\')
    $targetUri = [Uri]$TargetPath
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString()).Replace('/', '\')
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$manifest = Get-Content -LiteralPath $assetManifestPath -Raw | ConvertFrom-Json
if ([string]$manifest.version -ne $releaseVersion) { throw "Companion version does not match Android version" }
$signedRelease = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
$unsignedRelease = Join-Path $repoRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
$releaseApk = if ($signingConfigured) { $signedRelease } else { $unsignedRelease }
foreach ($apk in @((Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"), $releaseApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK is missing: $apk" }
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $entryName = "assets/companion/$($manifest.asset)"
        $entry = $zip.GetEntry($entryName)
        if ($null -eq $entry) { throw "APK is missing $entryName" }
        if ($entry.Length -ne [long]$manifest.size) { throw "APK companion asset size mismatch" }
        $stream = $entry.Open()
        try { $actual = Get-StreamSha256 $stream } finally { $stream.Dispose() }
        if ($actual -ne [string]$manifest.sha256) { throw "APK companion asset hash mismatch" }
    } finally {
        $zip.Dispose()
    }
}
if ($releaseApk -eq $signedRelease) {
    Invoke-Checked "Pinned APK signing identity" { & (Join-Path $PSScriptRoot 'verify-signing.ps1') -Apk $releaseApk }
} else {
    Write-Warning "Permanent signing authority was not supplied; release APK is unsigned and must not be published as signed."
}

Write-Host "== Secret scan =="
$forbiddenExtensions = @('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key')
$textExtensions = @('.kt', '.kts', '.java', '.go', '.md', '.json', '.xml', '.yml', '.yaml', '.toml', '.properties', '.ps1', '.sh', '.txt')
$privateKeyPattern = [regex]('-----BEGIN ' + '(?:RSA |EC |OPENSSH )?' + 'PRIVATE KEY-----')
$subscriptionPattern = [regex]'https?://[^\s"'']+/sub/[0-9a-fA-F-]{20,}'
$tracked = @(& git -C $repoRoot ls-files)
if ($LASTEXITCODE -ne 0) { throw "Cannot enumerate tracked files" }
foreach ($relative in $tracked) {
    $extension = [IO.Path]::GetExtension($relative).ToLowerInvariant()
    if ($forbiddenExtensions -contains $extension) { throw "Forbidden credential file is tracked: $relative" }
    if ($textExtensions -notcontains $extension) { continue }
    $path = Join-Path $repoRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $content = Get-Content -LiteralPath $path -Raw
    if ($privateKeyPattern.IsMatch($content) -or $subscriptionPattern.IsMatch($content)) {
        throw "Potential private credential in tracked file: $relative"
    }
}

$debugApk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$archive = Join-Path $repoRoot "dist\keenwg-companion-arm64-$releaseVersion.tar.gz"
$sbom = Join-Path $repoRoot "dist\keenwg-$releaseVersion.cdx.json"
foreach ($artifact in @($debugApk, $releaseApk, $archive, $sbom)) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Release artifact is missing: $artifact" }
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "$hash  $(Get-CompatibleRelativePath $repoRoot $artifact)"
}

Write-Host "Release verification completed"
