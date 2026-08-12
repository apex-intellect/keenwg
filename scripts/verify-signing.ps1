param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$ExpectedCertificateSha256 = $env:KEENWG_SIGNING_CERT_SHA256,
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
)

$ErrorActionPreference = 'Stop'
$apkPath = [IO.Path]::GetFullPath($Apk)
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw 'APK is missing' }
if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
    $pinnedDigest = Join-Path $PSScriptRoot '..\docs\release-signing-cert.sha256'
    if (Test-Path -LiteralPath $pinnedDigest -PathType Leaf) {
        $ExpectedCertificateSha256 = (Get-Content -LiteralPath $pinnedDigest -Raw).Trim()
    }
}
if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) { throw 'Pinned signing certificate SHA-256 is required' }
$apksignerNames = if ($IsWindows -or $env:OS -eq 'Windows_NT') { @('apksigner.bat', 'apksigner') } else { @('apksigner', 'apksigner.bat') }
$apksigner = Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'build-tools') -Directory |
    Sort-Object Name -Descending |
    ForEach-Object {
        foreach ($name in $apksignerNames) {
            $candidate = Join-Path $_.FullName $name
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate }
        }
    } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($apksigner)) { throw 'apksigner is missing' }
$output = & $apksigner verify --verbose --print-certs $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$line = $output | Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } | Select-Object -First 1
if ($null -eq $line) { throw 'APK signer certificate digest is missing' }
$actual = ([regex]::Match([string]$line, '([0-9a-fA-F]{64})')).Groups[1].Value.ToLowerInvariant()
$expected = ($ExpectedCertificateSha256 -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
if ($actual -ne $expected) { throw 'APK signing identity does not match the pinned certificate' }
Write-Host "Verified APK signer certificate: $actual"
