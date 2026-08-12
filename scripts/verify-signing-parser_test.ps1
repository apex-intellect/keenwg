$ErrorActionPreference = 'Stop'
$parser = Join-Path $PSScriptRoot 'parse-apksigner-certificate.ps1'
$processRunner = Join-Path $PSScriptRoot 'invoke-captured-process.ps1'
$javaResolver = Join-Path $PSScriptRoot 'resolve-java-executable.ps1'
$certificateBytes = [Text.Encoding]::UTF8.GetBytes('KeenWG certificate parser fixture')
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $digest = [Convert]::ToHexString($sha256.ComputeHash($certificateBytes)).ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
$certificatePem = "-----BEGIN CERTIFICATE-----`n$([Convert]::ToBase64String($certificateBytes))`n-----END CERTIFICATE-----"
$encodedPem = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($certificatePem))

$pwsh = (Get-Process -Id $PID).Path
$captured = & $processRunner -FileName $pwsh -Arguments @(
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    "[Console]::Out.WriteLine('stdout-marker'); [Console]::Error.Write([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encodedPem')))"
)
if ($captured.ExitCode -ne 0 -or $captured.StandardOutput.Trim() -ne 'stdout-marker') {
    throw 'Native stdout was not captured'
}
if ($captured.StandardError.Trim() -ne $certificatePem) { throw 'Native stderr was not captured as raw text' }

$windowsResult = & $parser -OutputText (@(
    'Verifies', ($certificatePem -replace "`n", "`r`n")
) -join "`r`n")
if ($windowsResult -ne $digest) { throw 'Windows apksigner output was not parsed' }

$linuxResult = & $parser -OutputText $captured.StandardError
if ($linuxResult -ne $digest) { throw 'Linux native stderr output was not parsed' }

$secondCertificate = $certificatePem -replace [Convert]::ToBase64String($certificateBytes), [Convert]::ToBase64String([byte[]](1, 2, 3))
$multipleSignersRejected = $false
try {
    & $parser -OutputText "$certificatePem`n$secondCertificate" | Out-Null
} catch {
    $multipleSignersRejected = $true
}
if (-not $multipleSignersRejected) { throw 'Multiple APK signers were accepted' }

$resolvedCandidate = & $javaResolver -JavaHome '' -CandidatePaths @($pwsh, $pwsh)
if ($resolvedCandidate -isnot [string] -or $resolvedCandidate -ne (Resolve-Path $pwsh).Path) {
    throw 'Java resolver did not select exactly one executable'
}

Write-Host 'apksigner certificate parser tests passed'
