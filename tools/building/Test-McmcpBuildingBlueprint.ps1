#requires -Version 7.4
$ErrorActionPreference='Stop'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-blueprint-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
function Assert-Blueprint($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Assert-BlueprintThrows([scriptblock]$Action){$failed=$false;try{$null=& $Action}catch{$failed=$true};Assert-Blueprint $failed 'Expected blueprint rejection'}
try {
    $inputFile=Join-Path $testRoot 'input.json'
    [IO.File]::WriteAllText($inputFile,'{"size":[2,1,2],"palette":["minecraft:stone"],"blocks":[0,0,0,0]}')
    $project=Join-Path $testRoot 'project'
    $progress=Join-Path $testRoot 'progress'
    $null=& python (Join-Path $PSScriptRoot 'compile_v2_blueprint.py') $inputFile $project --chunk-size 2
    if($LASTEXITCODE -ne 0){throw 'Blueprint compiler failed'}
    $manifest=Join-Path $project 'manifest.json'
    $runner=Join-Path $PSScriptRoot 'Invoke-McmcpV2Blueprint.ps1'
    $status=& $runner -ManifestPath $manifest -CheckpointDirectory $progress -StatusOnly
    Assert-Blueprint ($status.total -eq 4 -and $status.completed -eq 0 -and $status.next_chunk -ceq 'plan-00000.json') 'New project status mismatch'
    Assert-BlueprintThrows {& $runner -ManifestPath $manifest -CheckpointDirectory $project -StatusOnly}
    $chunk=Join-Path $project 'plan-00000.json'
    $original=[IO.File]::ReadAllText($chunk)
    [IO.File]::WriteAllText($chunk,$original.Replace('minecraft:stone','minecraft:dirt'))
    Assert-BlueprintThrows {& $runner -ManifestPath $manifest -CheckpointDirectory $progress -StatusOnly}
    [IO.File]::WriteAllText($chunk,$original)
    Assert-BlueprintThrows {& (Join-Path $PSScriptRoot 'Invoke-McmcpV2Construction.ps1') -PlanPath $chunk -CheckpointPath (Join-Path $progress 'plan-00000.json') -ExpectedPlanHash ('a'*64) -StatusOnly}
    $originalManifest=[IO.File]::ReadAllText($manifest)
    [IO.File]::WriteAllText($manifest,$originalManifest+"`n")
    Assert-BlueprintThrows {& $runner -ManifestPath $manifest -CheckpointDirectory $progress -StatusOnly}
    Write-Output 'v2 blueprint: status, overwrite guard, shard hash, dispatch hash and checkpoint binding passed'
} finally {
    # Validate the absolute directory created by this test before recursive cleanup.
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('/','\')+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -cnotmatch '^mcmcp-blueprint-[0-9a-f]{32}$'){throw 'Unsafe test cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
