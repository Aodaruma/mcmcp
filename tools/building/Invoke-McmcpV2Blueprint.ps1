#requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ManifestPath,
    [Parameter(Mandatory)][string]$CheckpointDirectory,
    [string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [ValidateRange(1,147456)][int]$MaxSteps=256,
    [ValidateRange(1,86400)][int]$MaxSeconds=900,
    [switch]$AcceptWorldSessionChange,[switch]$StatusOnly)
$ErrorActionPreference='Stop'
$manifestFile=Get-Item -LiteralPath $ManifestPath
if($manifestFile.Length -gt 4194304){throw 'blueprint_manifest_too_large'}
$manifest=Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json -AsHashtable -Depth 30
if($manifest.version -ne 1 -or $manifest.chunks -isnot [array] -or $manifest.chunks.Count -lt 1){throw 'invalid_blueprint_manifest'}
$manifestHash=(Get-FileHash -LiteralPath $manifestFile.FullName -Algorithm SHA256).Hash
$planRoot=$manifestFile.DirectoryName
$names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$total=0L
# Verify every shard before submitting any job. File content is never evaluated as PowerShell.
foreach($chunk in $manifest.chunks) {
    if($chunk.path -cnotmatch '^plan-[0-9]{5,8}\.json$' -or -not $names.Add($chunk.path) -or
        $chunk.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $chunk.placements -lt 1 -or $chunk.placements -gt 4096){throw 'invalid_blueprint_chunk'}
    $file=Get-Item -LiteralPath (Join-Path $planRoot $chunk.path)
    if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -gt 2097152 -or
        (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ine $chunk.sha256){throw 'blueprint_chunk_hash_mismatch'}
    $plan=Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable -Depth 30
    if($plan.dimension -cne $manifest.dimension -or $plan.steps.Count -ne $chunk.placements){throw 'blueprint_chunk_metadata_mismatch'}
    $total+=$chunk.placements
}
if($total -ne $manifest.placements){throw 'blueprint_placement_count_mismatch'}
$checkpointRoot=[IO.Path]::GetFullPath($CheckpointDirectory)
if($checkpointRoot.TrimEnd('/','\') -ieq $planRoot.TrimEnd('/','\')){throw 'checkpoints_must_not_replace_blueprint'}
if(-not (Test-Path -LiteralPath $checkpointRoot)){New-Item -ItemType Directory -Path $checkpointRoot | Out-Null}
$lock=[IO.File]::Open((Join-Path $checkpointRoot 'project.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $binding=Join-Path $checkpointRoot 'manifest.sha256'
    if(Test-Path -LiteralPath $binding) {
        if([IO.File]::ReadAllText($binding).Trim() -cne $manifestHash){throw 'checkpoint_blueprint_mismatch'}
    } else { [IO.File]::WriteAllText($binding,$manifestHash) }
    $clock=[Diagnostics.Stopwatch]::StartNew()
    $executed=0
    $completed=0L
    $nextChunk=$null
    foreach($chunk in $manifest.chunks) {
        $parameters=@{PlanPath=(Join-Path $planRoot $chunk.path);CheckpointPath=(Join-Path $checkpointRoot $chunk.path);TokenPath=$TokenPath;Endpoint=$Endpoint;ExpectedPlanHash=$chunk.sha256}
        $state=& (Join-Path $PSScriptRoot 'Invoke-McmcpV2Construction.ps1') @parameters -StatusOnly
        if($state.next -lt $chunk.placements -and -not $StatusOnly -and $executed -lt $MaxSteps -and $clock.Elapsed.TotalSeconds -lt $MaxSeconds) {
            $before=$state.next
            $seconds=[Math]::Max(1,[Math]::Floor($MaxSeconds-$clock.Elapsed.TotalSeconds))
            $steps=[Math]::Min(4096,$MaxSteps-$executed)
            $state=& (Join-Path $PSScriptRoot 'Invoke-McmcpV2Construction.ps1') @parameters -MaxSteps $steps -MaxSeconds $seconds -AcceptWorldSessionChange:$AcceptWorldSessionChange
            $executed+=$state.next-$before
        }
        $completed+=$state.next
        if($state.next -lt $chunk.placements) {
            if($null -eq $nextChunk){$nextChunk=$chunk.path}
            # Later layers never start while an earlier shard remains incomplete.
            $StatusOnly=$true
        }
    }
    [ordered]@{status=$(if($completed -eq $total){'complete'}else{'paused'});completed=$completed;total=$total;executed=$executed;next_chunk=$nextChunk}
} finally { $lock.Dispose() }
