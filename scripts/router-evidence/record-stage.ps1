param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'migration', 'route_apply', 'restart', 'update', 'rollback', 'uninstall')]
    [string]$Stage,
    [Parameter(Mandatory = $true)]
    [ValidateSet('pass', 'fail', 'not_run')]
    [string]$Status,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9._-]{1,64}$')]
    [string]$Code,
    [Parameter(Mandatory = $true)]
    [string]$EvidenceFile
)

$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $EvidenceFile).Path
$record = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json -Depth 20
$item = @($record.stages | Where-Object name -eq $Stage)
if ($item.Count -ne 1) { throw "Evidence must contain exactly one '$Stage' stage" }
$item[0].status = $Status
$item[0].code = $Code
$item[0].observed_at = if ($Status -eq 'not_run') { $null } else { (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }
$record.generated_at = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$record.support_status = 'experimental'
$record | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $resolved -Encoding utf8NoBOM
& (Join-Path $PSScriptRoot 'verify-evidence.ps1') -EvidenceFile $resolved
