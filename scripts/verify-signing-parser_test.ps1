$ErrorActionPreference = 'Stop'
$parser = Join-Path $PSScriptRoot 'parse-apksigner-certificate.ps1'
$digest = '5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a'
$certificateLine = "Signer #1 certificate SHA-256 digest: $digest"

$windowsResult = & $parser -OutputLines @(
    'Verifies',
    $certificateLine,
    'Signer #1 public key SHA-256 digest: ' + ('a' * 64)
)
if ($windowsResult -ne $digest) { throw 'Windows apksigner output was not parsed' }

$stderrRecord = [Management.Automation.ErrorRecord]::new(
    [Exception]::new($certificateLine),
    'apksigner',
    [Management.Automation.ErrorCategory]::NotSpecified,
    $null
)
$linuxResult = & $parser -OutputLines @($stderrRecord)
if ($linuxResult -ne $digest) { throw 'Linux native stderr output was not parsed' }

$secondSigner = "Signer #2 certificate SHA-256 digest: $('b' * 64)"
$multipleSignersRejected = $false
try {
    & $parser -OutputLines @($certificateLine, $secondSigner) | Out-Null
} catch {
    $multipleSignersRejected = $true
}
if (-not $multipleSignersRejected) { throw 'Multiple APK signers were accepted' }

Write-Host 'apksigner certificate parser tests passed'
