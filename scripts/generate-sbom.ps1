param(
    [string]$GoExecutable = 'go',
    [string]$GradleExecutable = 'gradle',
    [string]$Version = '',
    [string]$Output = ''
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($Version)) {
    $buildText = Get-Content -LiteralPath (Join-Path $repo 'app\build.gradle.kts') -Raw
    $match = [regex]::Match($buildText, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
    if (-not $match.Success) { throw 'Cannot determine application version' }
    $Version = $match.Groups[1].Value
}
$releaseVersion = $Version
if ([string]::IsNullOrWhiteSpace($Output)) { $Output = "dist/keenwg-$releaseVersion.cdx.json" }
$outputPath = [IO.Path]::GetFullPath((Join-Path $repo $Output))
$components = [Collections.Generic.List[object]]::new()
$goModules = & $GoExecutable -C (Join-Path $repo 'xkeen-control') list -m -json all | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Go modules' }
$decoder = [Text.Json.JsonDocument]::Parse('[' + (($goModules -replace '}\s*{', '},{')) + ']')
foreach ($item in $decoder.RootElement.EnumerateArray()) {
    $path = $item.GetProperty('Path').GetString()
    $versionProperty = $item.EnumerateObject() | Where-Object Name -eq 'Version' | Select-Object -First 1
    $version = if ($null -ne $versionProperty) { $versionProperty.Value.GetString() } else { 'workspace' }
    $components.Add([ordered]@{ type='library'; name=$path; version=$version; purl="pkg:golang/$path@$version" })
}
$gradleLines = & $GradleExecutable -p $repo :app:dependencies --configuration releaseRuntimeClasspath --console=plain | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate resolved Android release dependencies' }
$matches = [regex]::Matches($gradleLines, '(?m)^[|\s]*[+\\]---\s+([^:\s]+):([^:\s]+):([^\s]+)(?:\s+->\s+([^\s]+))?')
foreach ($match in $matches) {
    $group = $match.Groups[1].Value; $name = $match.Groups[2].Value
    $version = if ($match.Groups[4].Success) { $match.Groups[4].Value } else { $match.Groups[3].Value }
    $components.Add([ordered]@{ type='library'; group=$group; name=$name; version=$version; purl="pkg:maven/$group/$name@$version" })
}
$document = [ordered]@{
    bomFormat='CycloneDX'; specVersion='1.5'; version=1
    metadata=[ordered]@{ component=[ordered]@{ type='application'; name='KeenWG'; version=$releaseVersion } }
    components=@($components | Sort-Object { $_['purl'] } -Unique)
}
$directory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$document | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputPath -Encoding utf8NoBOM
Write-Host $outputPath
