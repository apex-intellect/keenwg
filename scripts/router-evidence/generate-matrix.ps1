param(
    [string]$RecordsDirectory = (Join-Path $PSScriptRoot '..\..\docs\evidence\records'),
    [string]$OutputFile = (Join-Path $PSScriptRoot '..\..\docs\COMPATIBILITY.md')
)

$ErrorActionPreference = 'Stop'
$records = Get-ChildItem -LiteralPath $RecordsDirectory -Filter '*.json' | Sort-Object Name | ForEach-Object {
    & (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $_.FullName | Out-Null
    Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -Depth 20
}
$lines = @(
    '# KeenWG 1.0 compatibility matrix',
    '',
    'Support is model-specific. `Supported` requires a sanitized physical record with all seven lifecycle stages passing. Source tests or architecture detection alone are not physical evidence.',
    '',
    '| Model / evidence | KeeneticOS | Architecture | Entware | Engines | Physical lifecycle | Status |',
    '|---|---|---|---|---|---|---|'
)
foreach ($record in $records) {
    $engine = @('xkeen', 'xray', 'sing_box', 'awg_manager') | ForEach-Object {
        $value = $record.device.engines.$_
        if ($null -ne $value -and $value -ne '') { "$_ $value" }
    }
    $passed = @($record.stages | Where-Object status -eq 'pass').Count
    $lines += "| $($record.device.model) / ``$($record.device.evidence_id)`` | $($record.device.keenetic_os) | $($record.device.architecture) | $($record.device.entware) | $($engine -join '; ') | $passed/7 | $($record.support_status) |"
}
$lines += @(
    '',
    'Always experimental until a matching physical record passes: standalone sing-box, AWG Manager, MIPS/MIPSel, and any model/firmware combination absent above.',
    '',
    'Unsupported in every release: public/WAN companion listeners and automatic route or country switching.',
    '',
    'Evidence excludes credentials, subscription URLs, connection keys, full IP addresses, MAC addresses, and hostnames. Run `scripts/router-evidence/verify-evidence.ps1` before submitting a record.'
)
$lines -join "`n" | Set-Content -LiteralPath $OutputFile -Encoding utf8NoBOM
