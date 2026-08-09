param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceFile
)

$ErrorActionPreference = 'Stop'
$record = Get-Content -LiteralPath $EvidenceFile -Raw | ConvertFrom-Json -Depth 20
$requiredStages = @('install', 'migration', 'route_apply', 'restart', 'update', 'rollback', 'uninstall')
if ($record.schema_version -ne 1) { throw 'Unsupported evidence schema' }
if ($record.release -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version' }
if (@($record.stages).Count -ne $requiredStages.Count) { throw 'Evidence requires seven lifecycle stages' }
foreach ($name in $requiredStages) {
    if (@($record.stages | Where-Object name -eq $name).Count -ne 1) { throw "Missing or duplicate stage: $name" }
}

$raw = Get-Content -LiteralPath $EvidenceFile -Raw
$forbidden = @(
    '(?i)https?://',
    '(?i)vless://',
    '(?i)(password|passwd|token|private[_ -]?key)\s*[:=]\s*[^\s\",}]+',
    '(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)',
    '(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}'
)
foreach ($pattern in $forbidden) {
    if ($raw -match $pattern) { throw "Evidence contains forbidden secret or network identifier pattern: $pattern" }
}
foreach ($name in @('credentials', 'subscription_urls', 'connection_keys', 'full_ips', 'mac_addresses', 'hostnames')) {
    if ($record.sanitization.$name -ne $false) { throw "Sanitization declaration must be false for: $name" }
}

$allPassed = @($record.stages | Where-Object status -ne 'pass').Count -eq 0
$eligible = $allPassed -and $record.device.architecture -eq 'arm64' -and $record.device.entware -eq 'present'
if ($record.support_status -eq 'supported' -and -not $eligible) {
    throw 'Supported status requires ARM64, Entware, and seven passing physical stages'
}
if ($eligible -and $record.support_status -ne 'supported') {
    Write-Warning 'All physical gates pass; support_status may now be changed to supported after maintainer review.'
}
Write-Output "Evidence valid: $($record.device.evidence_id) [$($record.support_status)]"
