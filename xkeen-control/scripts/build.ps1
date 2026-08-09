param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    $pwsh = Get-Command pwsh.exe -ErrorAction Stop
    & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Version $Version
    exit $LASTEXITCODE
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$controlRoot = Join-Path $repoRoot 'xkeen-control'
$distRoot = Join-Path $repoRoot 'dist\xkeen-control'
$stage = Join-Path $distRoot 'stage'
$archiveName = "keenwg-xkeen-control-$Version-linux-arm64.tar.gz"
$archivePath = Join-Path $distRoot $archiveName
$expectedEntries = @(
    'BUILDINFO', 'S96keenwg-xkeen-control', 'SHA256SUMS', 'VERSION',
    'config.example.json', 'install.sh', 'keenwg-xkeen-control', 'uninstall.sh', 'xkeen-country'
)

function Invoke-Native {
    param([Parameter(Mandatory = $true)][string]$FilePath, [string[]]$Arguments = @())
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')" }
}

function Write-LF {
    param([string]$Path, [string]$Text)
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($Path, $normalized, [Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$dirty = & git -C $repoRoot status --porcelain -- xkeen-control
if ($LASTEXITCODE -ne 0) { throw 'git status failed' }
if ($dirty -and $env:KEENWG_ALLOW_DIRTY_BUILD -ne '1') {
    throw "xkeen-control tree must be clean before release:`n$($dirty -join "`n")"
}
$commit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'cannot determine release commit' }

$goCandidates = @()
if ($env:KEENWG_GO) { $goCandidates += $env:KEENWG_GO }
$goCommand = Get-Command go.exe -ErrorAction SilentlyContinue
if ($goCommand) { $goCandidates += $goCommand.Source }
if ($env:LOCALAPPDATA) { $goCandidates += (Join-Path $env:LOCALAPPDATA 'Temp\keenwg-go1.26.5\sdk-complete\go\bin\go.exe') }
$go = $goCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $go) { throw 'Go 1.26.5 executable not found; set KEENWG_GO' }
$goVersion = (& $go version).Trim()
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch '^go version go1\.26\.5\s') { throw "exact Go 1.26.5 required, found: $goVersion" }

$bash = 'C:\Program Files\Git\bin\bash.exe'
$tar = 'C:\Program Files\Git\usr\bin\tar.exe'
$gzip = 'C:\Program Files\Git\usr\bin\gzip.exe'
foreach ($tool in @($bash, $tar, $gzip)) { if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "required build tool missing: $tool" } }

Write-Host 'Checking shell syntax and staged lifecycle...'
foreach ($script in @('install.sh', 'uninstall.sh', 'S96keenwg-xkeen-control', 'xkeen-country', 'install_test.sh')) {
    Invoke-Native -FilePath $bash -Arguments @('-n', (Join-Path $controlRoot "packaging\$script"))
}
Invoke-Native -FilePath $bash -Arguments @((Join-Path $controlRoot 'packaging\install_test.sh'))

Write-Host 'Running Go tests and vet...'
Push-Location $controlRoot
try {
    Invoke-Native -FilePath $go -Arguments @('test', './...', '-count=1')
    Invoke-Native -FilePath $go -Arguments @('vet', './...')
} finally { Pop-Location }

Write-Host 'Running Linux race detector...'
$wsl = Get-Command wsl.exe -ErrorAction Stop
$raceTarget = "/tmp/keenwg-release-race-$([guid]::NewGuid().ToString('N'))"
& $tar -cf - -C $repoRoot xkeen-control | & $wsl.Source -d docker-desktop sh -lc "set -eu; mkdir -p '$raceTarget'; trap 'rm -rf `"'$raceTarget'`"' EXIT; tar -xf - -C '$raceTarget'; cd '$raceTarget/xkeen-control'; CGO_ENABLED=1 /tmp/keenwg-go/bin/go test -race ./... -count=1"
if ($LASTEXITCODE -ne 0) { throw 'Linux race tests failed' }

if (Test-Path -LiteralPath $distRoot) {
    $resolvedDist = (Resolve-Path -LiteralPath $distRoot).Path
    $expectedDist = [IO.Path]::GetFullPath($distRoot)
    if ($resolvedDist -ne $expectedDist -or -not $resolvedDist.StartsWith([IO.Path]::GetFullPath($repoRoot), [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe dist path' }
    Remove-Item -LiteralPath $distRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$temporary = Join-Path $distRoot 'repro'
New-Item -ItemType Directory -Path $temporary -Force | Out-Null

$binaryOne = Join-Path $temporary 'keenwg-xkeen-control.one'
$binaryTwo = Join-Path $temporary 'keenwg-xkeen-control.two'
$oldCGO = $env:CGO_ENABLED; $oldGOOS = $env:GOOS; $oldGOARCH = $env:GOARCH
try {
    $env:CGO_ENABLED = '0'; $env:GOOS = 'linux'; $env:GOARCH = 'arm64'
    Push-Location $controlRoot
    try {
        $ldflags = "-s -w -X main.version=$Version -X main.commit=$commit"
        Invoke-Native -FilePath $go -Arguments @('build', '-trimpath', '-buildvcs=false', '-ldflags', $ldflags, '-o', $binaryOne, './cmd/keenwg-xkeen-control')
        Invoke-Native -FilePath $go -Arguments @('build', '-trimpath', '-buildvcs=false', '-ldflags', $ldflags, '-o', $binaryTwo, './cmd/keenwg-xkeen-control')
    } finally { Pop-Location }
} finally {
    $env:CGO_ENABLED = $oldCGO; $env:GOOS = $oldGOOS; $env:GOARCH = $oldGOARCH
}
if ((Get-Sha256 $binaryOne) -ne (Get-Sha256 $binaryTwo)) { throw 'two controller builds are not identical' }
Copy-Item -LiteralPath $binaryOne -Destination (Join-Path $stage 'keenwg-xkeen-control')

foreach ($name in @('S96keenwg-xkeen-control', 'install.sh', 'uninstall.sh', 'xkeen-country', 'config.example.json')) {
    Write-LF (Join-Path $stage $name) ([IO.File]::ReadAllText((Join-Path $controlRoot "packaging\$name")))
}
Write-LF (Join-Path $stage 'VERSION') "$Version`n"
Write-LF (Join-Path $stage 'BUILDINFO') "version=$Version`ncommit=$commit`ngo=$goVersion`ntarget=linux/arm64`n"
$sumNames = $expectedEntries | Where-Object { $_ -ne 'SHA256SUMS' } | Sort-Object
$sumLines = foreach ($name in $sumNames) { "$(Get-Sha256 (Join-Path $stage $name))  $name" }
Write-LF (Join-Path $stage 'SHA256SUMS') (($sumLines -join "`n") + "`n")

$actualEntries = Get-ChildItem -LiteralPath $stage -File | Select-Object -ExpandProperty Name | Sort-Object
if (($actualEntries -join "`n") -ne (($expectedEntries | Sort-Object) -join "`n")) { throw "stage manifest mismatch: $($actualEntries -join ', ')" }

$tarOne = Join-Path $temporary 'release.one.tar'; $tarTwo = Join-Path $temporary 'release.two.tar'
$gzipOne = Join-Path $temporary 'release.one.tar.gz'; $gzipTwo = Join-Path $temporary 'release.two.tar.gz'
$sortedEntries = $expectedEntries | Sort-Object
foreach ($tarPath in @($tarOne, $tarTwo)) {
    $arguments = @('--force-local', '--sort=name', '--format=ustar', '--mtime=@0', '--owner=0', '--group=0', '--numeric-owner', '--mode=0755', '-cf', $tarPath, '-C', $stage) + $sortedEntries
    Invoke-Native -FilePath $tar -Arguments $arguments
}
& $gzip -n -9 -c $tarOne > $gzipOne
if ($LASTEXITCODE -ne 0) { throw 'first gzip failed' }
& $gzip -n -9 -c $tarTwo > $gzipTwo
if ($LASTEXITCODE -ne 0) { throw 'second gzip failed' }
if ((Get-Sha256 $gzipOne) -ne (Get-Sha256 $gzipTwo)) { throw 'two release archives are not identical' }
Copy-Item -LiteralPath $gzipOne -Destination $archivePath

$listed = & $gzip -d -c $archivePath | & $tar -tf -
if ($LASTEXITCODE -ne 0 -or (($listed | Sort-Object) -join "`n") -ne (($sortedEntries | Sort-Object) -join "`n")) { throw "archive manifest mismatch: $($listed -join ', ')" }
$archiveHash = Get-Sha256 $archivePath
Write-LF (Join-Path $distRoot 'SHA256SUMS') "$archiveHash  $archiveName`n"
Remove-Item -LiteralPath $temporary -Recurse -Force
Write-Host "Archive: $archivePath"
Write-Host "SHA-256: $archiveHash"
