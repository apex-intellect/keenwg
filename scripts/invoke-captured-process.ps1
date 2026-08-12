param(
    [Parameter(Mandatory = $true)][string]$FileName,
    [string[]]$Arguments = @()
)

$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $FileName
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }

$process = [Diagnostics.Process]::new()
$process.StartInfo = $startInfo
try {
    if (-not $process.Start()) { throw 'Native process did not start' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    [pscustomobject]@{
        ExitCode = $process.ExitCode
        StandardOutput = $stdout.GetAwaiter().GetResult()
        StandardError = $stderr.GetAwaiter().GetResult()
    }
} finally {
    $process.Dispose()
}
