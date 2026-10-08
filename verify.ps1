[CmdletBinding()]
param(
    [ValidateSet('Focused', 'Jvm', 'Extended')]
    [string] $Mode = 'Focused',
    [ValidateRange(1, 1800)]
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$workspace = $PSScriptRoot
$runId = [Guid]::NewGuid().ToString()
$reportDirectory = Join-Path $workspace "build/reports/bounded-verification/$runId"
$null = New-Item -ItemType Directory -Path $reportDirectory -Force
$stdout = Join-Path $reportDirectory 'stdout.log'
$stderr = Join-Path $reportDirectory 'stderr.log'
$worker = Join-Path $reportDirectory 'worker.ps1'
$reportPath = Join-Path $reportDirectory 'result.json'
$task = switch ($Mode) {
    'Jvm' { 'logicVerification' }
    'Extended' { 'releaseReadiness' }
    default { 'acceptance' }
}
# Use an isolated single-use Gradle daemon, so a deadline never kills another build's shared daemon.
$arguments = @($task, '--no-daemon', '--console=plain')
if ($Mode -eq 'Extended') {
    $versionMatch = [regex]::Match((Get-Content (Join-Path $workspace 'build.gradle') -Raw),
        "(?m)^version\s*=\s*'([0-9]+\.[0-9]+\.[0-9]+)'\s*$")
    if (-not $versionMatch.Success) { throw 'Cannot determine the release version from build.gradle.' }
    $arguments += '-PextendedVerification=true'
    $arguments += '-PreleaseVersion=' + $versionMatch.Groups[1].Value
}
function Quote-PowerShellLiteral([string] $Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}
$command = '& ' + (Quote-PowerShellLiteral (Join-Path $workspace 'gradlew.bat')) + ' ' +
    (($arguments | ForEach-Object { Quote-PowerShellLiteral $_ }) -join ' ')
@(
    "`$ErrorActionPreference = 'Stop'"
    $command
    'exit $LASTEXITCODE'
) | Set-Content -LiteralPath $worker -Encoding UTF8

$shellPath = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $shellPath)) { $shellPath = Join-Path $PSHOME 'powershell.exe' }
$previousGradleHome = $env:GRADLE_USER_HOME
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle'
$process = $null
$exitCode = 1
$status = 'START_FAILED'
$failure = $null
$startedAt = [DateTimeOffset]::Now.ToString('o')
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    $process = Start-Process -FilePath $shellPath -WindowStyle Hidden -PassThru `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-File', ('"' + $worker + '"')) `
        -WorkingDirectory $workspace -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null = $process.Handle
    Write-Host "Verification $Mode started; deadline ${TimeoutSeconds}s. Logs: $reportDirectory"
    while (-not $process.WaitForExit(250)) {
        if ($watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
            $status = 'TIMEOUT'
            $exitCode = 124
            # Only the process tree created by this invocation; never kill Java by name.
            & "$env:SystemRoot\System32\taskkill.exe" /PID $process.Id /T /F 2>&1 | Out-Null
            break
        }
    }
    if ($status -ne 'TIMEOUT') {
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        if ($null -eq $exitCode) { $exitCode = 1 }
        $status = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
    }
} catch {
    $failure = $_.Exception.Message
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $process.Id /T /F 2>&1 | Out-Null
    }
    $env:GRADLE_USER_HOME = $previousGradleHome
    $watch.Stop()
    $result = [ordered]@{
        schemaVersion = 1; runId = $runId; mode = $Mode; task = $task
        startedAt = $startedAt; finishedAt = [DateTimeOffset]::Now.ToString('o')
        deadlineSeconds = $TimeoutSeconds; elapsedSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 3)
        status = $status; success = ($status -eq 'PASS'); exitCode = $exitCode
        performance = $(if ($Mode -eq 'Extended') { 'REQUIRED_BY_LEGACY_RELEASE' } else { 'NOT_REQUIRED' })
        releaseReady = ($Mode -eq 'Extended' -and $status -eq 'PASS')
        error = $failure; stdout = $stdout; stderr = $stderr
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
}
if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Tail 12 }
if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Tail 12 }
Write-Host "Verification ${status}: $reportPath"
exit $exitCode
