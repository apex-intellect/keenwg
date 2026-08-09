$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '..\..\docs\evidence\records\netcraze-nc3812-5-01c1.json'
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("keenwg-evidence-{0}.json" -f [guid]::NewGuid().ToString('N'))
try {
    Copy-Item -LiteralPath $source -Destination $temporary
    & (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $temporary | Out-Null
    $record = Get-Content -LiteralPath $temporary -Raw | ConvertFrom-Json -Depth 20
    $record.support_status = 'supported'
    $record.device.architecture = 'unverified'
    $record.device.entware = 'unverified'
    $record.stages[0].status = 'not_run'
    $record.stages[0].code = 'not_verified'
    $record.stages[0].observed_at = $null
    $record | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
    $failed = $false
    try { & (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $temporary | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Unverified supported record was accepted' }

    $record.device.architecture = 'arm64'
    $record.device.entware = 'present'
    foreach ($stage in $record.stages) {
        $stage.status = 'pass'
        $stage.code = 'verified_readback'
        $stage.observed_at = '2026-08-09T01:00:00Z'
    }
    $record | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
    & (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $temporary | Out-Null

    $record.notes = @('Forbidden network identifier 192.0.2.1')
    $record | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
    $failed = $false
    try { & (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $temporary | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Record containing a full IP address was accepted' }
    Write-Output 'Router evidence self-test passed'
} finally {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
}
