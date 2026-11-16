[CmdletBinding()]
param(
    [ValidateSet('Focused', 'Jvm', 'Gl', 'UiJvm', 'Ui', 'UiDemos', 'UiConsumers', 'UiSoak', 'Extended')]
    [string] $Mode = 'Focused',
    [ValidateRange(1, 1800)]
    [int] $TimeoutSeconds = 900
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
    'Gl' { 'glLeafVerification' }
    'UiJvm' { 'uiCompositorJvmVerification' }
    'Ui' { 'uiCompositorVerification' }
    'UiDemos' { 'uiCompositorDemoVerification' }
    'UiConsumers' { 'uiCompositorConsumerVerification' }
    'UiSoak' { 'uiCompositorCacheSoak' }
    'Extended' { 'releaseReadiness' }
    default { 'acceptance' }
}
# Use an isolated single-use Gradle daemon, so a deadline never kills another build's shared daemon.
$arguments = @($task, '--no-daemon', '--console=plain')
# GL acceptance is GPU evidence: a cached UP-TO-DATE task would replay stale XML,
# so this mode always asks Gradle for a fresh execution.
if ($Mode -eq 'Gl') { $arguments += '--rerun-tasks' }
if ($Mode -eq 'UiSoak') { $arguments += '-PextendedVerification=true' }
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
function Stop-VerificationProcess([System.Diagnostics.Process] $Target) {
    if ($null -eq $Target) { return }
    try {
        if (-not $Target.HasExited) {
            & "$env:SystemRoot\System32\taskkill.exe" /PID $Target.Id /T /F 2>&1 | Out-Null
        }
    } catch {
        # The tree can exit between HasExited and taskkill; timeout reporting must continue.
    }
}
@(
    "`$ErrorActionPreference = 'Stop'"
    $command
    'exit $LASTEXITCODE'
) | Set-Content -LiteralPath $worker -Encoding UTF8

$shellPath = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $shellPath)) { $shellPath = Join-Path $PSHOME 'powershell.exe' }

function Get-ContentSha256([string] $Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { return $sha.ComputeHash($stream) } finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

# Content fingerprint of the candidate: commit plus edit count cannot distinguish
# two different edits of the same file, so hash every tracked/untracked path and
# its bytes. Must stay stable across runs with identical content.
function Get-WorkingTreeFingerprint {
    $args = @('-c', "safe.directory=$($workspace.Replace('\', '/'))", 'ls-files', '-z',
              '--cached', '--others', '--exclude-standard')
    $raw = & git @args 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $raw) { return $null }
    $paths = ($raw -join "`n").Split([char]0, [System.StringSplitOptions]::RemoveEmptyEntries) |
            Sort-Object -Unique
    $outer = [System.Security.Cryptography.SHA256]::Create()
    try {
        $nul = [byte[]]@(0)
        foreach ($relative in $paths) {
            $full = Join-Path $workspace $relative
            $pathBytes = [System.Text.Encoding]::UTF8.GetBytes($relative)
            $null = $outer.TransformBlock($pathBytes, 0, $pathBytes.Length, $null, 0)
            $null = $outer.TransformBlock($nul, 0, 1, $null, 0)
            $digestBytes = if (Test-Path -LiteralPath $full -PathType Leaf) {
                Get-ContentSha256 $full
            } else {
                [System.Text.Encoding]::UTF8.GetBytes('MISSING')
            }
            $null = $outer.TransformBlock($digestBytes, 0, $digestBytes.Length, $null, 0)
            $null = $outer.TransformBlock($nul, 0, 1, $null, 0)
        }
        $null = $outer.TransformFinalBlock([byte[]]@(), 0, 0)
        $hex = ($outer.Hash | ForEach-Object { $_.ToString('x2') }) -join ''
        return [pscustomobject]@{ sha256 = $hex; fileCount = @($paths).Count }
    } finally { $outer.Dispose() }
}

# Copies the JUnit XML that backs this run and hashes the archived bytes.
function Save-JunitEvidence([string[]] $TaskNames) {
    $archive = Join-Path $reportDirectory 'junit'
    $lines = New-Object System.Collections.Generic.List[string]
    $files = 0
    $tests = 0
    $skipped = 0
    $failures = 0
    $reused = $false
    $startInstant = [DateTimeOffset]::Parse($startedAt)
    foreach ($name in $TaskNames) {
        $source = Join-Path $workspace "build/test-results/$name"
        if (-not (Test-Path -LiteralPath $source -PathType Container)) { continue }
        $target = Join-Path $archive $name
        $null = New-Item -ItemType Directory -Path $target -Force
        Get-ChildItem -LiteralPath $source -Filter '*.xml' -File | ForEach-Object {
            if ($_.LastWriteTimeUtc -lt $startInstant.UtcDateTime) { $reused = $true }
            Copy-Item -LiteralPath $_.FullName -Destination $target -Force
            $copy = Join-Path $target $_.Name
            $digest = Get-ContentSha256 $copy
            $hex = ($digest | ForEach-Object { $_.ToString('x2') }) -join ''
            $lines.Add("$name/$($_.Name)`0$hex")
            $files++
            $text = Get-Content -LiteralPath $copy -Raw
            $m = [regex]::Match($text, '<testsuite\b[^>]*\btests="(\d+)"[^>]*\bskipped="(\d+)"[^>]*\bfailures="(\d+)"')
            if ($m.Success) {
                $tests += [int]$m.Groups[1].Value
                $skipped += [int]$m.Groups[2].Value
                $failures += [int]$m.Groups[3].Value
            }
        }
    }
    if ($files -eq 0) { return $null }
    $joined = ($lines | Sort-Object) -join "`n"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($joined)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $evidenceHash = ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '' }
    finally { $sha.Dispose() }
    return [pscustomobject]@{
        archive = $archive; files = $files; tests = $tests
        executed = $tests - $skipped; skipped = $skipped; failures = $failures
        sha256 = $evidenceHash; reused = $reused
    }
}

$evidenceTasks = switch ($Mode) {
    'Focused' { @('focusedRender3dJvmTest', 'focusedRender3dGlTest') }
    'Jvm' { @('focusedRender3dJvmTest') }
    'Gl' { @('glLeafVerification') }
    'UiJvm' { @('uiCompositorJvmVerification') }
    'Ui' { @('uiCompositorGlVerification', 'uiCompositorJvmVerification') }
    'UiDemos' { @('uiCompositorGlVerification', 'uiCompositorJvmVerification') }
    'UiConsumers' { @('uiCompositorGlVerification', 'uiCompositorJvmVerification') }
    'UiSoak' { @('uiCompositorCacheSoak') }
    default { @('test') }
}

$gitHead = $null
$workingTreeEntries = $null
$workingTreeSha256 = $null
$workingTreeFileCount = $null
$buildVersion = $null
try {
    $versionMatch = [regex]::Match((Get-Content (Join-Path $workspace 'build.gradle') -Raw),
        "(?m)^version\s*=\s*'([0-9]+\.[0-9]+\.[0-9]+)'\s*$")
    if ($versionMatch.Success) { $buildVersion = $versionMatch.Groups[1].Value }
    $gitDirectory = $workspace.Replace('\', '/')
    $head = & git -c "safe.directory=$gitDirectory" rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0) { $gitHead = ($head | Select-Object -First 1).Trim() }
    $statusLines = & git -c "safe.directory=$gitDirectory" status --porcelain=v1 --untracked-files=all 2>$null
    if ($LASTEXITCODE -eq 0) { $workingTreeEntries = @($statusLines).Count }
    $fingerprint = Get-WorkingTreeFingerprint
    if ($null -ne $fingerprint) {
        $workingTreeSha256 = $fingerprint.sha256
        $workingTreeFileCount = $fingerprint.fileCount
    }
} catch {
    # Identity enrichment is best-effort; it must not turn a run into START_FAILED.
}
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
            Stop-VerificationProcess $process
            $null = $process.WaitForExit(5000)
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
    Stop-VerificationProcess $process
    $env:GRADLE_USER_HOME = $previousGradleHome
    $watch.Stop()
    $uiEvidencePath = $null
    $uiEvidenceStatus = 'NOT_APPLICABLE'
    if ($Mode.StartsWith('Ui')) {
        $uiEvidenceStatus = 'NOT_WRITTEN'
        if (Test-Path -LiteralPath $stdout) {
            $uiRun = [regex]::Match((Get-Content -LiteralPath $stdout -Raw), 'UI_COMPOSITOR_RUN_ID=([0-9a-f-]{36})')
            if ($uiRun.Success) {
                $uiEvidencePath = Join-Path $workspace ('build/reports/ui-compositor-v0260/' + $uiRun.Groups[1].Value + '/summary.json')
                if (Test-Path -LiteralPath $uiEvidencePath) {
                    $uiEvidenceStatus = (Get-Content -LiteralPath $uiEvidencePath -Raw | ConvertFrom-Json).status
                }
            }
        }
        if ($status -eq 'PASS' -and $uiEvidenceStatus -ne 'PASS') { $status = 'FAIL'; $exitCode = 1 }
    }
    if ($status -eq 'FAIL' -and (Test-Path -LiteralPath $stderr)) {
        $stderrText = Get-Content -LiteralPath $stderr -Raw
        if ($stderrText -match 'Unrecognized (VM )?option|Could not create the Java Virtual Machine|Error occurred during initialization of VM') {
            $status = 'INVALID'
            $exitCode = 2
            if ($null -eq $failure) { $failure = 'Verification JVM could not start; see stderr.log' }
        }
    }
    $evidence = Save-JunitEvidence $evidenceTasks
    if ($Mode -ne 'Extended') {
        $evidenceProblem = $null
        if ($null -eq $evidence) {
            $evidenceProblem = 'No JUnit XML was archived; a run without executed cases cannot pass'
        } elseif ($evidence.executed -le 0) {
            $evidenceProblem = "Archived evidence has no executed case (tests=$($evidence.tests), skipped=$($evidence.skipped))"
        } elseif ($Mode -eq 'Gl' -and $evidence.skipped -gt 0) {
            $evidenceProblem = "GL evidence skipped $($evidence.skipped) of $($evidence.tests) cases"
        } elseif ($Mode -eq 'Gl' -and $evidence.reused) {
            $evidenceProblem = 'GL evidence is stale (task was up-to-date); rerun with fresh execution'
        }
        if ($null -ne $evidenceProblem) {
            if ($null -eq $failure) { $failure = $evidenceProblem }
            if ($status -eq 'PASS') { $status = 'FAIL'; $exitCode = 1 }
        }
    }
    # The candidate must not change while it is being verified: compare the
    # content fingerprint taken before the run with one taken now.
    $endFingerprint = $null
    try { $endFingerprint = Get-WorkingTreeFingerprint } catch { $endFingerprint = $null }
    $workingTreeSha256End = if ($null -ne $endFingerprint) { $endFingerprint.sha256 } else { $null }
    $workingTreeFileCountEnd = if ($null -ne $endFingerprint) { $endFingerprint.fileCount } else { $null }
    $candidateStable = $null
    if ($null -ne $workingTreeSha256 -and $null -ne $workingTreeSha256End) {
        $candidateStable = ($workingTreeSha256 -eq $workingTreeSha256End)
        if (-not $candidateStable) {
            $changed = 'Candidate content changed during verification ' +
                       "(before=$($workingTreeSha256.Substring(0, 12)), after=$($workingTreeSha256End.Substring(0, 12)))"
            if ($null -eq $failure) { $failure = $changed }
            if ($status -eq 'PASS') { $status = 'FAIL'; $exitCode = 1 }
        }
    }
    $result = [ordered]@{
        schemaVersion = 1; runId = $runId; mode = $Mode; task = $task
        buildVersion = $buildVersion
        gitHead = $gitHead
        workingTreeEntries = $workingTreeEntries
        workingTreeSha256 = $workingTreeSha256
        workingTreeFileCount = $workingTreeFileCount
        workingTreeSha256After = $workingTreeSha256End
        workingTreeFileCountAfter = $workingTreeFileCountEnd
        candidateStable = $candidateStable
        startedAt = $startedAt; finishedAt = [DateTimeOffset]::Now.ToString('o')
        deadlineSeconds = $TimeoutSeconds; elapsedSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 3)
        status = $status; success = ($status -eq 'PASS'); exitCode = $exitCode
        performance = $(if ($Mode -eq 'Extended') { 'REQUIRED_BY_LEGACY_RELEASE' } else { 'NOT_REQUIRED' })
        releaseReady = ($Mode -eq 'Extended' -and $status -eq 'PASS')
        error = $failure; stdout = $stdout; stderr = $stderr
        scopedEvidence = $uiEvidencePath; scopedEvidenceStatus = $uiEvidenceStatus
        evidenceFreshness = $(if ($null -eq $evidence) { 'MISSING' }
                              elseif ($evidence.reused) { 'REUSED_TASK_OUTPUT' } else { 'FRESH' })
        evidence = $evidence
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
}
if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Tail 12 }
if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Tail 12 }
Write-Host "Verification ${status}: $reportPath"
exit $exitCode
