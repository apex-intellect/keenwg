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
$apksignerJar = Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'build-tools') -Directory |
    Sort-Object Name -Descending |
    ForEach-Object {
        $candidate = Join-Path $_.FullName 'lib/apksigner.jar'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate }
    } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($apksignerJar)) { throw 'apksigner.jar is missing' }
$java = (Get-Command java -CommandType Application -ErrorAction Stop).Source
$result = & (Join-Path $PSScriptRoot 'invoke-captured-process.ps1') -FileName $java -Arguments @(
    '-jar', $apksignerJar, 'verify', '--verbose', '--print-certs', $apkPath
)
if ($result.ExitCode -ne 0) { throw 'APK signature verification failed' }
$actual = & (Join-Path $PSScriptRoot 'parse-apksigner-certificate.ps1') -OutputText ($result.StandardOutput + "`n" + $result.StandardError)
$expected = ($ExpectedCertificateSha256 -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
if ($actual -ne $expected) { throw 'APK signing identity does not match the pinned certificate' }
Write-Host "Verified APK signer certificate: $actual"
