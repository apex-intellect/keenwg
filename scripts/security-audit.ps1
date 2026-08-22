param(
    [Parameter(Mandatory = $true)]
    [string]$LinuxGoExecutable,
    [string]$WindowsGoExecutable = 'go',
    [string]$GradleExecutable = '',
    [int]$FuzzSeconds = 10,
    [switch]$RequireArtifacts
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$module = Join-Path $repo 'xkeen-control'
if ([string]::IsNullOrWhiteSpace($GradleExecutable)) {
    $GradleExecutable = Join-Path $repo 'gradlew.bat'
}
$buildText = Get-Content -LiteralPath (Join-Path $repo 'app\build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $versionMatch.Success) { throw 'Cannot determine application version' }
$version = $versionMatch.Groups[1].Value

function Invoke-Checked([string]$Label, [scriptblock]$Command) {
    Write-Host "== $Label =="
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE" }
}
function Wsl-Path([string]$Path) {
    $result = (& wsl.exe -e wslpath -a $Path).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($result)) { throw "Cannot map path into WSL: $Path" }
    $result
}
function Quote-Sh([string]$Value) {
    if ($Value.Contains("'")) { throw 'Single quotes are not supported in audit paths' }
    "'$Value'"
}

$goVersion = (& wsl.exe -e $LinuxGoExecutable version).Trim()
if ($goVersion -notmatch '^go version go1\.26\.7\s') { throw "Go 1.26.7 is required for the Go 1.26.6 security fixes and the Go 1.26.7 net/http fixes; found: $goVersion" }
$quotedGo = Quote-Sh $LinuxGoExecutable
$wslCompanion = Wsl-Path $module
foreach ($goModule in @(
    @{ Name = 'Companion'; Path = $module; WslPath = $wslCompanion },
    @{ Name = 'Collector'; Path = (Join-Path $repo 'collector') }
)) {
    $wslModule = if ($goModule.WslPath) { $goModule.WslPath } else { Wsl-Path $goModule.Path }
    Invoke-Checked "$($goModule.Name) race tests and vet" {
        & wsl.exe -e sh -lc "cd $(Quote-Sh $wslModule) && $quotedGo test -race ./... -count=1 && $quotedGo vet ./..."
    }
    Invoke-Checked "$($goModule.Name) vulnerability scan" {
        & wsl.exe -e sh -lc "cd $(Quote-Sh $wslModule) && $quotedGo run golang.org/x/vuln/cmd/govulncheck@v1.6.0 ./..."
    }
}
foreach ($target in @(
    @('internal/subscription', 'FuzzParseNeverPanicsOrLeaksInput'),
    @('internal/domainpolicy', 'FuzzCanonicalizeRuleNeverPanics'),
    @('internal/config', 'FuzzDecodeFailsClosedWithoutLeakingInput')
)) {
    Invoke-Checked "Fuzz $($target[1])" {
        & wsl.exe -e sh -lc "cd $(Quote-Sh $wslCompanion) && $quotedGo test ./$($target[0]) -run='^$' -fuzz=$($target[1]) -fuzztime=${FuzzSeconds}s"
    }
}
$wslPackaging = Wsl-Path (Join-Path $module 'packaging')
Invoke-Checked 'Installer rollback and path tests' {
    & wsl.exe -e sh -lc "cd $(Quote-Sh $wslPackaging) && sh ./install-companion_test.sh"
}

Invoke-Checked 'Secure-only current runtime policy' { & (Join-Path $repo 'scripts\verify-current-runtime.ps1') }

$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Invoke-Checked 'Android tests, lint, locale and provider policy' {
    & $GradleExecutable -p $repo :app:testDebugUnitTest :app:lintDebug :app:verifyFileProviderPolicy :app:verifyLocaleResources :app:verifyUiResources --console=plain --no-daemon
}
Invoke-Checked 'Evidence policy self-test' { & (Join-Path $repo 'scripts\router-evidence\self-test.ps1') }
$wslEvidence = Wsl-Path (Join-Path $repo 'scripts\router-evidence')
Invoke-Checked 'Router inventory collector tests' {
    & wsl.exe -e sh -lc "cd $(Quote-Sh $wslEvidence) && sh ./collect-inventory_test.sh"
}
Invoke-Checked 'CycloneDX SBOM' {
    & (Join-Path $repo 'scripts\generate-sbom.ps1') -GoExecutable $WindowsGoExecutable -GradleExecutable $GradleExecutable -Version $version
}
$sbom = Join-Path $repo "dist\keenwg-$version.cdx.json"
$bom = Get-Content -LiteralPath $sbom -Raw | ConvertFrom-Json -Depth 20
if ($bom.bomFormat -ne 'CycloneDX' -or $bom.metadata.component.version -ne $version -or @($bom.components).Count -lt 20) {
    throw 'SBOM is incomplete or does not match the application version'
}
foreach ($required in @('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md', 'SECURITY.md', 'PRIVACY.md', 'docs\SECURITY-MODEL.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repo $required) -PathType Leaf)) { throw "Required public policy is missing: $required" }
}

Write-Host '== Tracked secret and unsafe-listener scan =='
$privateKey = [regex]'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
$privateSubscription = [regex]'https?://[^\s"'']+/sub/[0-9a-fA-F-]{20,}'
$forbiddenExtensions = @('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key')
$tracked = @(& git -C $repo ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tracked files' }
foreach ($relative in $tracked) {
    if ($forbiddenExtensions -contains [IO.Path]::GetExtension($relative).ToLowerInvariant()) { throw "Credential-like file is tracked: $relative" }
    $path = Join-Path $repo $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $path).Length -gt 8MB) { continue }
    $content = Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue
    if ($null -ne $content -and ($privateKey.IsMatch($content) -or $privateSubscription.IsMatch($content))) {
        throw "Potential private credential is tracked: $relative"
    }
}
$configText = Get-Content -LiteralPath (Join-Path $module 'internal\config\config.go') -Raw
if ($configText -notmatch '!isPrivateListener\(addr\)' -or $configText -notmatch '!address\.IsLoopback\(\)') {
    throw 'Private-listener or loopback-adapter enforcement is missing'
}

if ($RequireArtifacts) {
    Write-Host '== APK and ARM64 archive content =='
    $apkCandidates = @(
        (Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'),
        (Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'),
        (Join-Path $repo 'app\build\outputs\apk\release\app-release-unsigned.apk')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
    if ($apkCandidates.Count -lt 2) { throw 'Debug and release APKs are required for artifact audit' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($apk in $apkCandidates) {
        $zip = [IO.Compression.ZipFile]::OpenRead($apk)
        try {
            $bad = @($zip.Entries | Where-Object { $_.FullName -match '(?i)(^|/)([^/]+\.(?:jks|keystore|p12|pfx|pem|key))$' })
            if ($bad.Count -ne 0) { throw "APK contains credential-like files: $($bad.FullName -join ', ')" }
            if ($null -eq $zip.GetEntry('AndroidManifest.xml')) { throw "Invalid APK: $apk" }
        } finally { $zip.Dispose() }
    }
    $arm64 = Join-Path $repo "dist\keenwg-companion-arm64-$version.tar.gz"
    if (-not (Test-Path -LiteralPath $arm64 -PathType Leaf)) { throw "ARM64 companion archive is missing: $arm64" }
    $entries = @(& tar -tzf $arm64)
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) { throw 'Cannot inspect companion archive' }
    if (@($entries | Where-Object { $_ -match '(^/|(^|/)\.\.(/|$)|(?i)\.(?:jks|keystore|p12|pfx|pem|key)$)' }).Count -ne 0) {
        throw 'Companion archive contains an unsafe path or credential-like file'
    }
}
Write-Host "Security audit completed for KeenWG $version; components=$(@($bom.components).Count)"
