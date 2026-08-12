param(
    [AllowEmptyString()][string]$JavaHome = $env:JAVA_HOME,
    [string[]]$CandidatePaths = @()
)

$executableName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }
$paths = [Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $paths.Add((Join-Path $JavaHome "bin/$executableName"))
}
foreach ($path in $CandidatePaths) {
    if (-not [string]::IsNullOrWhiteSpace($path)) { $paths.Add($path) }
}
if ($paths.Count -eq 0) {
    Get-Command java -CommandType Application -ErrorAction Stop | ForEach-Object {
        $paths.Add($_.Source)
    }
}

foreach ($path in $paths) {
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        return (Resolve-Path -LiteralPath $path).Path
    }
}
throw 'Java executable is missing'
