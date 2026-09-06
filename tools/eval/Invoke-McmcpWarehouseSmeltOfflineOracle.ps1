[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$WorldDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$EvaluationArtifactDirectory,

    [string]$PythonExecutable = 'python',

    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$world = [IO.Path]::GetFullPath($WorldDirectory)
$artifact = [IO.Path]::GetFullPath($EvaluationArtifactDirectory)
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat') -PathType Leaf)) {
    throw 'warehouse-smelt oracle requires an exact closed-world directory'
}
$manifestPath = Join-Path $artifact 'manifest.json'
$auditPath = Join-Path $artifact 'audit.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $auditPath -PathType Leaf)) {
    throw 'warehouse-smelt oracle requires the completed fresh-evaluation manifest and audit'
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$audit = Get-Content -LiteralPath $auditPath -Raw | ConvertFrom-Json
$lease = $manifest.evaluation_lease
if ($manifest.prompt_profile -cne 'warehouse-smelt' -or
    -not [bool]$manifest.trace_audit_passed -or $null -ne $manifest.runner_failure -or
    -not [bool]$audit.passed -or $audit.prompt_profile -cne 'warehouse-smelt' -or
    -not [bool]$lease.acquired -or -not [bool]$lease.release_http_confirmed -or
    -not [bool]$lease.inputs_released -or -not [bool]$lease.input_owner_none -or
    -not [bool]$lease.all_actions_terminal -or $null -eq $lease.released_utc) {
    throw 'fresh warehouse-smelt evaluation is not safely terminal'
}

$sessionLock = Join-Path $world 'session.lock'
if (-not (Test-Path -LiteralPath $sessionLock -PathType Leaf)) {
    throw 'world session lock is absent; closed-world status cannot be checked'
}
$lockProbe = $null
try {
    $lockProbe = [IO.File]::Open($sessionLock, [IO.FileMode]::Open,
        [IO.FileAccess]::Read, [IO.FileShare]::None)
} catch {
    throw 'world is still open; save and quit before running the offline oracle'
} finally {
    if ($null -ne $lockProbe) { $lockProbe.Dispose() }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $artifact 'offline-warehouse-smelt-oracle.json'
}
$oracle = Join-Path $PSScriptRoot 'Inspect-McmcpWarehouseSmeltOracle.py'
& $PythonExecutable $oracle $world --output ([IO.Path]::GetFullPath($OutputPath))
if ($LASTEXITCODE -ne 0) {
    throw "warehouse-smelt offline oracle failed with exit code $LASTEXITCODE"
}
$result = Get-Content -LiteralPath $OutputPath -Raw | ConvertFrom-Json
if (-not [bool]$result.passed) {
    throw 'warehouse-smelt offline oracle did not pass'
}
$result | ConvertTo-Json -Depth 100
