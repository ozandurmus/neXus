param(
    [string]$Target = "",
    [string[]]$Targets = @(),
    [string]$LogPath = "pytest_result.log",
    [string]$FailedPath = "pytest_failed.txt",
    [switch]$VerboseOutput,
    [switch]$Serial
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# dev_python_env_tooling_friction: bare `py` resolves to whatever the launcher's
# default is, which has drifted to a newer interpreter without dev deps on at
# least one validated dev box (`py` -> 3.14 without pytest/pytest-xdist). Prefer
# the pinned 3.12 baseline via `py -V:3.12` (real-environment validated: works
# with `-m pytest`, though `-V:3.12 <script>` alone is known to mis-parse on
# that box) when a 3.12 interpreter is actually registered with the launcher;
# otherwise fall back to bare `py` rather than hard-failing on a box where the
# `-V:3.12` tag isn't installed.
$pythonSelector = @()
try {
    $installed = & py -0p 2>$null
    if ($installed -match "-V:3\.12") {
        $pythonSelector = @("-V:3.12")
    } else {
        Write-Host "Warning: no py -V:3.12 interpreter registered; falling back to the default 'py' launcher target. Dev deps (pytest/pytest-xdist) must already be installed there."
    }
} catch {
    Write-Host "Warning: could not query 'py -0p'; falling back to the default 'py' launcher target."
}

$pytestArgs = @("-m", "pytest", "-q")

# Parallel by default for the full-suite one-shot (requires requirements-dev.txt:
# pytest-xdist). Pass -Serial when debugging a single failure so output stays in
# order and tracebacks are easy to read.
if (-not $Serial) {
    $pytestArgs += @("-n", "auto", "--dist", "worksteal")
}

$allTargets = @()
if ($Target -and $Target.Trim().Length -gt 0) {
    $allTargets += @(
        ($Target -split '\s+') |
            Where-Object { $_ -and $_.Trim().Length -gt 0 }
    )
}
if ($Targets.Count -gt 0) {
    $allTargets += @(
        $Targets |
            Where-Object { $_ -and $_.Trim().Length -gt 0 }
    )
}
if ($allTargets.Count -gt 0) {
    $pytestArgs += $allTargets
}
if ($VerboseOutput) {
    $pytestArgs += "-vv"
}

Write-Host "== pytest one-shot start =="
Write-Host ("Command: py " + (($pythonSelector + $pytestArgs) -join " "))

# Run once: stream output to console and save UTF-8 log.
$rawOutput = & py @pythonSelector @pytestArgs 2>&1
$rawOutput | Tee-Object -FilePath $LogPath | Out-Host
$exitCode = $LASTEXITCODE

# Normalize saved log encoding to UTF-8 for deterministic reads.
$logText = ($rawOutput | Out-String)
[System.IO.File]::WriteAllText((Resolve-Path -LiteralPath ".").Path + "\" + $LogPath, $logText, [System.Text.UTF8Encoding]::new($false))

$lines = @()
if (Test-Path -LiteralPath $LogPath) {
    $lines = Get-Content -LiteralPath $LogPath -Encoding utf8
}

$summaryLine = ""
$summaryPattern = "^\d+\s+failed,.*|^\d+\s+passed,.*|^=+ .* in .* =+$"
$summaryCandidates = @(
    $lines |
        Where-Object { $_ -match $summaryPattern }
)
if ($summaryCandidates.Count -gt 0) {
    $summaryLine = $summaryCandidates[-1]
}

$failedNodeIds = @(
    $lines |
        Where-Object { $_ -match "^FAILED\s+\S+" } |
        ForEach-Object { [regex]::Match($_, "^FAILED\s+(\S+)").Groups[1].Value } |
        Where-Object { $_ -and $_.Trim().Length -gt 0 } |
        Sort-Object -Unique
)

if ($failedNodeIds.Count -gt 0) {
    Set-Content -LiteralPath $FailedPath -Value ($failedNodeIds -join [Environment]::NewLine) -Encoding utf8
} else {
    [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath ".").Path + "\\" + $FailedPath, "", [System.Text.UTF8Encoding]::new($false))
}

Write-Host ""
Write-Host "== pytest one-shot summary =="
if ($summaryLine) {
    Write-Host $summaryLine
} else {
    Write-Host "Summary line not detected; check full log."
}
Write-Host ("Failed tests: " + $failedNodeIds.Count)
Write-Host ("Log: " + $LogPath)
Write-Host ("Failed list: " + $FailedPath)

if ($failedNodeIds.Count -gt 0) {
    Write-Host ""
    Write-Host "Rerun failed only command:"
    Write-Host ("py " + (($pythonSelector + @("-m", "pytest", "-q") + $failedNodeIds + @("-vv")) -join " "))
}

exit $exitCode
