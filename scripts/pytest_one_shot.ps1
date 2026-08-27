param(
    [string]$Target = "",
    [string[]]$Targets = @(),
    [string]$LogPath = "pytest_result.log",
    [string]$FailedPath = "pytest_failed.txt",
    [switch]$VerboseOutput
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$pytestArgs = @("-m", "pytest", "-q")

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
Write-Host ("Command: py " + ($pytestArgs -join " "))

# Run once: stream output to console and save UTF-8 log.
$rawOutput = & py @pytestArgs 2>&1
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
    Write-Host ("py -m pytest -q " + ($failedNodeIds -join " ") + " -vv")
}

exit $exitCode
