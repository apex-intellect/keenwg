param([switch]$FailOnFindings)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$uiRoot = Join-Path $repo 'app\src\main\java\ru\anisimov\keenwg\ui'
$findings = [Collections.Generic.List[string]]::new()
Get-ChildItem -LiteralPath $uiRoot -Recurse -Filter '*.kt' | Sort-Object FullName | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath($repo, $_.FullName)
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $_.FullName) {
        $lineNumber++
        if ($line -match '[А-Яа-яЁё]') {
            $findings.Add("${relative}:$lineNumber`t$($line.Trim())")
        }
    }
}
$findings | ForEach-Object { Write-Host $_ }
Write-Host "Cyrillic text outside Android locale resources: $($findings.Count)"
if ($FailOnFindings -and $findings.Count -ne 0) { throw 'User-visible UI literals remain outside Android resources' }
