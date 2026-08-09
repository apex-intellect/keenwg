param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$GoExecutable = "go",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$') {
    throw "Version must be a semantic version"
}
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$moduleRoot = Join-Path $repoRoot "xkeen-control"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "dist"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$stage = Join-Path ([IO.Path]::GetTempPath()) ("keenwg-companion-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    $commit = (& git -C $repoRoot rev-parse --short=12 HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{12}$') { throw "Git commit is unavailable" }
    $binary = Join-Path $stage "keenwg-companion"
    $previousGoos = $env:GOOS
    $previousGoarch = $env:GOARCH
    $previousCgo = $env:CGO_ENABLED
    try {
        $env:GOOS = "linux"
        $env:GOARCH = "arm64"
        $env:CGO_ENABLED = "0"
        & $GoExecutable -C $moduleRoot build -trimpath -ldflags "-s -w -X main.version=$Version -X main.commit=$commit" -o $binary ./cmd/keenwg-companion
        if ($LASTEXITCODE -ne 0) { throw "Companion cross-build failed" }
    } finally {
        $env:GOOS = $previousGoos
        $env:GOARCH = $previousGoarch
        $env:CGO_ENABLED = $previousCgo
    }
    foreach ($name in @("S96keenwg-companion", "install-companion.sh", "uninstall-companion.sh", "companion.config.example.json")) {
        Copy-Item -LiteralPath (Join-Path $moduleRoot "packaging\$name") -Destination (Join-Path $stage $name)
    }
    [IO.File]::WriteAllText((Join-Path $stage "VERSION"), "$Version`n", [Text.UTF8Encoding]::new($false))
    $checksumLines = Get-ChildItem -LiteralPath $stage -File | Sort-Object Name | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    }
    [IO.File]::WriteAllText((Join-Path $stage "SHA256SUMS"), (($checksumLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $archive = Join-Path $OutputDirectory "keenwg-companion-arm64-$Version.tar.gz"
    & $GoExecutable -C $moduleRoot run ./cmd/keenwg-makebundle -input $stage -output $archive
    if ($LASTEXITCODE -ne 0) { throw "Deterministic archive creation failed" }
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "$archive $archiveHash"
} finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
