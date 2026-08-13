param(
    [string]$RepositoryRoot = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Join-Path $PSScriptRoot '..'
}
$repo = [IO.Path]::GetFullPath($RepositoryRoot)

$sources = [Collections.Generic.List[string]]::new()
function Add-SourceTree([string]$RelativePath, [string[]]$Extensions) {
    $root = Join-Path $repo $RelativePath
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { return }
    Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
        $Extensions -contains $_.Extension.ToLowerInvariant() -and
        $_.Name -notmatch '_test\.go$' -and
        $_.FullName -notmatch '[\\/]internal[\\/]configupgrade[\\/]'
    } | ForEach-Object { $sources.Add($_.FullName) }
}
function Add-SourceFile([string]$RelativePath) {
    $path = Join-Path $repo $RelativePath
    if (Test-Path -LiteralPath $path -PathType Leaf) { $sources.Add($path) }
}

Add-SourceTree 'app\src\main' @('.kt', '.kts', '.java', '.xml', '.json')
Add-SourceTree 'xkeen-control\cmd' @('.go')
Add-SourceTree 'xkeen-control\internal' @('.go')
foreach ($file in @(
    'xkeen-control\packaging\S96keenwg-companion',
    'xkeen-control\packaging\install-companion.sh',
    'xkeen-control\packaging\uninstall-companion.sh',
    'xkeen-control\packaging\companion.config.example.json',
    'README.md',
    'SECURITY.md',
    'PRIVACY.md',
    'CONTRIBUTING.md',
    'docs\SECURITY-MODEL.md',
    'docs\COMPATIBILITY.md',
    'docs\COMPANION-SETUP.md',
    'docs\RELEASE-NOTES-2.0.0.md',
    'design-system\keenwg\MASTER.md',
    'xkeen-control\README.md'
)) { Add-SourceFile $file }

# These two files are the bounded Android schema-1 reader and the one-shot
# obsolete preference cleanup. They intentionally know the retired field names.
$allowedMigrationFiles = @(
    [IO.Path]::GetFullPath((Join-Path $repo 'app\src\main\java\ru\anisimov\keenwg\data\store\RouterProfileCodec.kt')),
    [IO.Path]::GetFullPath((Join-Path $repo 'app\src\main\java\ru\anisimov\keenwg\data\store\RouterProfileStore.kt'))
)

# Companion's bounded history proxy reads this one Collector field from the
# router-local config. The client rejects hostnames, public/wildcard addresses,
# redirects, non-HTTP schemes and oversized responses before forwarding data.
$localCollectorConfigReader = [IO.Path]::GetFullPath((Join-Path $repo 'xkeen-control\internal\historyproxy\client.go'))

$rules = [ordered]@{
    'obsolete cleartext port'        = '(?<!\d)18778(?!\d)'
    'obsolete service or binary'     = '(?:S96)?keenwg-xkeen-control'
    'removed legacy runtime entry'   = '\bRunLegacy\b'
    'removed legacy API flag'        = '\bLegacyAPIEnabled\b|legacy_api_enabled'
    'removed Android endpoint mirror'= 'xkeen_controller_(?:url|token)|legacyXkeen(?:Url|Token)|\bmigrateLegacy\b'
    'removed cleartext listen field' = '"listen_address"'
}

$violations = [Collections.Generic.List[string]]::new()
foreach ($path in @($sources | Sort-Object -Unique)) {
    $fullPath = [IO.Path]::GetFullPath($path)
    if ($allowedMigrationFiles -contains $fullPath) { continue }
    $content = Get-Content -LiteralPath $path -Raw
    foreach ($entry in $rules.GetEnumerator()) {
        if ($entry.Key -eq 'removed cleartext listen field' -and $fullPath -eq $localCollectorConfigReader) { continue }
        if ($content -match $entry.Value) {
            $relative = [IO.Path]::GetRelativePath($repo, $path).Replace('\', '/')
            $violations.Add("${relative}: $($entry.Key)")
        }
    }
}
if ($violations.Count -ne 0) {
    throw "Retired standalone-controller references reached the current runtime:`n$($violations -join "`n")"
}

Write-Host "Current runtime is free of the retired standalone controller ($($sources.Count) files checked)"
