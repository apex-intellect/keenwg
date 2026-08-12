$ErrorActionPreference = 'Stop'
$parser = Join-Path $PSScriptRoot 'parse-apksigner-certificate.ps1'
$processRunner = Join-Path $PSScriptRoot 'invoke-captured-process.ps1'
$javaResolver = Join-Path $PSScriptRoot 'resolve-java-executable.ps1'
$digest = '5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a'
$certificateLine = "Signer #1 certificate SHA-256 digest: $digest"

$pwsh = (Get-Process -Id $PID).Path
$captured = & $processRunner -FileName $pwsh -Arguments @(
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    "[Console]::Out.WriteLine('stdout-marker'); [Console]::Error.WriteLine('$certificateLine')"
)
if ($captured.ExitCode -ne 0 -or $captured.StandardOutput.Trim() -ne 'stdout-marker') {
    throw 'Native stdout was not captured'
}
if ($captured.StandardError.Trim() -ne $certificateLine) { throw 'Native stderr was not captured as raw text' }

$windowsResult = & $parser -OutputText (@(
    'Verifies', $certificateLine, 'Signer #1 public key SHA-256 digest: ' + ('a' * 64)
) -join "`r`n")
if ($windowsResult -ne $digest) { throw 'Windows apksigner output was not parsed' }

$linuxResult = & $parser -OutputText $captured.StandardError
if ($linuxResult -ne $digest) { throw 'Linux native stderr output was not parsed' }

$secondSigner = "Signer #2 certificate SHA-256 digest: $('b' * 64)"
$multipleSignersRejected = $false
try {
    & $parser -OutputText "$certificateLine`n$secondSigner" | Out-Null
} catch {
    $multipleSignersRejected = $true
}
if (-not $multipleSignersRejected) { throw 'Multiple APK signers were accepted' }

$resolvedCandidate = & $javaResolver -JavaHome '' -CandidatePaths @($pwsh, $pwsh)
if ($resolvedCandidate -isnot [string] -or $resolvedCandidate -ne (Resolve-Path $pwsh).Path) {
    throw 'Java resolver did not select exactly one executable'
}

Write-Host 'apksigner certificate parser tests passed'
