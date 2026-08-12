param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$OutputText
)

$matches = [regex]::Matches(
    $OutputText,
    '(?ms)^-----BEGIN CERTIFICATE-----\s*(?<base64>[A-Za-z0-9+/=\r\n]+?)\s*-----END CERTIFICATE-----\s*$'
)
if ($matches.Count -ne 1) {
    throw 'APK must have exactly one signer certificate'
}
$base64 = $matches[0].Groups['base64'].Value -replace '\s', ''
try {
    $certificate = [Convert]::FromBase64String($base64)
} catch {
    throw 'APK signer certificate is not valid PEM'
}
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    [Convert]::ToHexString($sha256.ComputeHash($certificate)).ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
