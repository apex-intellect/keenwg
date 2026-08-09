[CmdletBinding()]
param(
    [string]$GoRoot = $env:KEENWG_GO_ROOT,
    [string]$Version = "0.3.0"
)

$ErrorActionPreference = "Stop"
if ($Version -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') {
    throw "Version must be 1-64 ASCII letters, digits, dots, underscores, or hyphens and start with a letter or digit."
}
$collectorRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repoRoot = (Resolve-Path (Join-Path $collectorRoot "..")).Path
$distRoot = Join-Path $repoRoot "dist"
$stageRoot = Join-Path $distRoot "stage"

function Assert-CleanCollector {
    $dirty = @(& git -C $repoRoot status --porcelain=v1 -- collector)
    if ($LASTEXITCODE -ne 0) { throw "cannot inspect collector Git status" }
    if ($dirty.Count -ne 0) {
        throw "collector-relevant sources are dirty; commit or stash them before a release build:`n$($dirty -join "`n")"
    }
}

function Write-AsciiLf {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Value
    )
    $encoding = New-Object -TypeName System.Text.ASCIIEncoding
    [IO.File]::WriteAllText($Path, "$Value`n", $encoding)
}

function Copy-TextLf {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )
    $encoding = New-Object -TypeName System.Text.ASCIIEncoding
    $content = [IO.File]::ReadAllText($Source).Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($Destination, $content, $encoding)
}

Assert-CleanCollector

$candidates = @()
if ($GoRoot) { $candidates += (Join-Path $GoRoot "bin\go.exe") }
$candidates += (Join-Path $env:TEMP "keenwg-go1.26.5\sdk-complete\go\bin\go.exe")
$installed = Get-Command go.exe -ErrorAction SilentlyContinue
if ($installed) { $candidates += $installed.Source }
$go = $null
foreach ($candidate in $candidates | Select-Object -Unique) {
    if ((Test-Path -LiteralPath $candidate) -and ((& $candidate version) -match '^go version go1\.26\.5 ')) {
        $go = $candidate
        break
    }
}
if (-not $go) {
    throw "Go 1.26.5 was not found. Set KEENWG_GO_ROOT to an official verified Go 1.26.5 SDK directory."
}

$tar = "C:\Program Files\Git\usr\bin\tar.exe"
$gzip = "C:\Program Files\Git\usr\bin\gzip.exe"
$cygpath = "C:\Program Files\Git\usr\bin\cygpath.exe"
$bash = "C:\Program Files\Git\bin\bash.exe"
if (-not (Test-Path -LiteralPath $tar) -or -not (Test-Path -LiteralPath $gzip) -or -not (Test-Path -LiteralPath $cygpath) -or -not (Test-Path -LiteralPath $bash)) {
    throw "Git for Windows Bash, GNU tar, gzip, and cygpath are required for verified deterministic packaging."
}

function New-DeterministicArchive {
    param(
        [Parameter(Mandatory)][string]$OutputPath,
        [Parameter(Mandatory)][string]$TarPath
    )
    if (Test-Path -LiteralPath $OutputPath) { Remove-Item -LiteralPath $OutputPath -Force }
    if (Test-Path -LiteralPath $TarPath) { Remove-Item -LiteralPath $TarPath -Force }
    $tarUnixPath = (& $cygpath -u $TarPath).Trim()
    Push-Location $stageRoot
    try {
        & $tar --sort=name --mtime="@0" --owner=0 --group=0 --numeric-owner --format=ustar -cf $tarUnixPath .
        if ($LASTEXITCODE -ne 0) { throw "deterministic tar creation failed" }
    } finally {
        Pop-Location
    }
    try {
        $process = Start-Process -FilePath $gzip -ArgumentList @("-n", "-9", "-c", $tarUnixPath) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $OutputPath
        if ($process.ExitCode -ne 0) { throw "deterministic gzip creation failed" }
    } finally {
        if (Test-Path -LiteralPath $TarPath) { Remove-Item -LiteralPath $TarPath -Force }
    }
}

Push-Location $collectorRoot
try {
    foreach ($script in @("install.sh", "uninstall.sh", "S95keenwg", "95-keenwg-signal", "install_test.sh")) {
        & $bash -n (Join-Path $collectorRoot "packaging\$script")
        if ($LASTEXITCODE -ne 0) { throw "$script failed shell syntax validation" }
    }
    $previousMsys = $env:MSYS
    $env:MSYS = "winsymlinks:nativestrict"
    try {
        & $bash (Join-Path $collectorRoot "packaging\install_test.sh")
        if ($LASTEXITCODE -ne 0) { throw "Entware packaging lifecycle tests failed" }
    } finally {
        $env:MSYS = $previousMsys
    }

    & $go test ./... -count=1
    if ($LASTEXITCODE -ne 0) { throw "collector tests failed" }
    & $go vet ./...
    if ($LASTEXITCODE -ne 0) { throw "collector vet failed" }

    Assert-CleanCollector
    $commit = (& git -C $repoRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $commit) { throw "cannot determine Git commit" }

    New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
    if (Test-Path -LiteralPath $stageRoot) {
        $resolvedStage = (Resolve-Path -LiteralPath $stageRoot).Path
        if (-not $resolvedStage.StartsWith($distRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "refusing to replace stage outside dist: $resolvedStage"
        }
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
    New-Item -ItemType Directory -Path $stageRoot | Out-Null

    $binary = Join-Path $stageRoot "keenwg-collector"
    $previous = @{
        CGO_ENABLED = $env:CGO_ENABLED
        GOOS = $env:GOOS
        GOARCH = $env:GOARCH
    }
    $env:CGO_ENABLED = "0"
    $env:GOOS = "linux"
    $env:GOARCH = "arm64"
    try {
        $ldflags = "-s -w -X main.version=$Version -X main.commit=$commit"
        & $go build -trimpath -buildvcs=true -ldflags $ldflags -o $binary ./cmd/keenwg-collector
        if ($LASTEXITCODE -ne 0) { throw "linux/arm64 build failed" }
        $reproBinary = Join-Path $stageRoot ".keenwg-collector.repro"
        & $go build -trimpath -buildvcs=true -ldflags $ldflags -o $reproBinary ./cmd/keenwg-collector
        if ($LASTEXITCODE -ne 0) { throw "reproducibility build failed" }
        $firstBinaryHash = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash
        $secondBinaryHash = (Get-FileHash -LiteralPath $reproBinary -Algorithm SHA256).Hash
        Remove-Item -LiteralPath $reproBinary -Force
        if ($firstBinaryHash -cne $secondBinaryHash) { throw "two clean-snapshot collector builds were not reproducible" }
    } finally {
        $env:CGO_ENABLED = $previous.CGO_ENABLED
        $env:GOOS = $previous.GOOS
        $env:GOARCH = $previous.GOARCH
    }

    foreach ($name in @("install.sh", "uninstall.sh", "S95keenwg", "95-keenwg-signal", "config.example.json")) {
        Copy-TextLf -Source (Join-Path $collectorRoot "packaging\$name") -Destination (Join-Path $stageRoot $name)
    }
    $binaryHash = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-AsciiLf -Path (Join-Path $stageRoot "SHA256SUMS") -Value "$binaryHash  keenwg-collector"
    Write-AsciiLf -Path (Join-Path $stageRoot "VERSION") -Value $Version
    $buildInfo = "version=$Version`ncommit=$commit`nbinary_sha256=$binaryHash"
    Write-AsciiLf -Path (Join-Path $stageRoot "BUILDINFO") -Value $buildInfo
    if ((Get-Content -Raw -LiteralPath (Join-Path $stageRoot "VERSION")).Trim() -cne $Version) { throw "staged VERSION does not match build parameter" }
    if ((Get-Content -Raw -LiteralPath (Join-Path $stageRoot "BUILDINFO")).Trim() -cne $buildInfo) { throw "staged BUILDINFO does not match the verified build" }

    $metadata = (& $go version -m $binary) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "cannot inspect collector build metadata" }
    foreach ($requiredMetadata in @("GOOS=linux", "GOARCH=arm64", "CGO_ENABLED=0", "-trimpath=true")) {
        if ($metadata.IndexOf($requiredMetadata, [StringComparison]::Ordinal) -lt 0) {
            throw "collector build metadata is missing $requiredMetadata"
        }
    }

    $archiveName = "keenwg-collector-$Version-linux-arm64.tar.gz"
    $archive = Join-Path $distRoot $archiveName
    $tarPath = Join-Path $distRoot ".keenwg-release.tar"
    New-DeterministicArchive -OutputPath $archive -TarPath $tarPath
    $archiveUnixPath = (& $cygpath -u $archive).Trim()
    $inspectTar = Join-Path $distRoot ".keenwg-release-inspect.tar"
    try {
        $process = Start-Process -FilePath $gzip -ArgumentList @("-d", "-c", $archiveUnixPath) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $inspectTar
        if ($process.ExitCode -ne 0) { throw "cannot decompress release archive for inspection" }
        $inspectTarUnixPath = (& $cygpath -u $inspectTar).Trim()
        $archiveEntries = @(& $tar -tf $inspectTarUnixPath | ForEach-Object { $_.TrimStart([char[]]"./") } | Where-Object { $_ })
        if ($LASTEXITCODE -ne 0) { throw "cannot inspect release archive" }
    } finally {
        if (Test-Path -LiteralPath $inspectTar) { Remove-Item -LiteralPath $inspectTar -Force }
    }
    $expectedEntries = @("95-keenwg-signal", "BUILDINFO", "S95keenwg", "SHA256SUMS", "VERSION", "config.example.json", "install.sh", "keenwg-collector", "uninstall.sh")
    if (@(Compare-Object -ReferenceObject $expectedEntries -DifferenceObject $archiveEntries).Count -ne 0) {
        throw "release archive contents do not match the required package manifest"
    }

    $reproArchive = Join-Path $distRoot ".keenwg-release-repro.tar.gz"
    $reproTar = Join-Path $distRoot ".keenwg-release-repro.tar"
    try {
        New-DeterministicArchive -OutputPath $reproArchive -TarPath $reproTar
        $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        $reproArchiveHash = (Get-FileHash -LiteralPath $reproArchive -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($archiveHash -cne $reproArchiveHash) { throw "two clean-snapshot release archives were not reproducible" }
    } finally {
        if (Test-Path -LiteralPath $reproArchive) { Remove-Item -LiteralPath $reproArchive -Force }
        if (Test-Path -LiteralPath $reproTar) { Remove-Item -LiteralPath $reproTar -Force }
    }

    Assert-CleanCollector
    $finalCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $finalCommit -cne $commit) { throw "Git HEAD changed during release build" }
    Write-AsciiLf -Path (Join-Path $distRoot "SHA256SUMS") -Value "$archiveHash  $archiveName"
    Write-Host "Built $archive"
    Write-Host "SHA256 $archiveHash"
    & $go version -m $binary
} finally {
    Pop-Location
}
