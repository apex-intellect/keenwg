param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$OutputLines
)

$text = (@($OutputLines) | ForEach-Object { $_.ToString() }) -join "`n"
$matches = [regex]::Matches(
    $text,
    '(?im)^\s*Signer\s+#([0-9]+)\s+certificate\s+SHA-256\s+digest:\s*([0-9a-f]{64})\s*$'
)
if ($matches.Count -ne 1 -or $matches[0].Groups[1].Value -ne '1') {
    throw 'APK must have exactly one signer certificate'
}
$matches[0].Groups[2].Value.ToLowerInvariant()
