#requires -Version 7.4
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Construction.ps1') -LibraryOnly
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-v2-ledger-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
function Assert-V2Ledger($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Assert-V2LedgerThrows([scriptblock]$Action){$failed=$false;try{$null=& $Action}catch{$failed=$true};Assert-V2Ledger $failed "Expected rejection: $Action"}
$planFile=Join-Path $testRoot 'plan.json'
$ledgerFile=Join-Path $testRoot 'ledger.json'
try {
    [IO.File]::WriteAllText($planFile,'{"version":2,"dimension":"minecraft:overworld","steps":[{"x":1,"y":65,"z":0,"block":"minecraft:stone"},{"x":2,"y":65,"z":0,"block":"minecraft:stone"}]}')
    $plan=Read-V2ConstructionPlan $planFile
    $cp=Read-V2ConstructionCheckpoint $ledgerFile $plan
    $id='550e8400-e29b-41d4-a716-446655440000'
    $cp.pending=@{index=0;action_id=$id;terminal=$null}
    Save-BuildingCheckpoint $ledgerFile $cp
    $restored=Read-V2ConstructionCheckpoint $ledgerFile $plan
    Assert-V2Ledger ($restored.pending.action_id -ceq $id -and $restored.next -eq 0) 'Pending dispatch was lost'
    Assert-V2Ledger (Complete-V2ConstructionStep $restored @{action_id=$id;state='succeeded';result=@{confirmed_count=1}}) 'Confirmed placement not retained'
    Save-BuildingCheckpoint $ledgerFile $restored
    $resumed=Read-V2ConstructionCheckpoint $ledgerFile $plan
    Assert-V2Ledger ($resumed.next -eq 1 -and $null -eq $resumed.pending) 'Resume did not skip completed step'
    $resumed.pending=@{index=1;action_id=$id;terminal=$null}
    Assert-V2Ledger (-not (Complete-V2ConstructionStep $resumed @{action_id=$id;state='succeeded';result=@{}})) 'A mismatched skipped cell was counted'
    Assert-V2Ledger ($resumed.status -ceq 'needs_review' -and $resumed.next -eq 1) 'Uncertain placement was replayed'
    Resolve-V2ConstructionPending $resumed Retry
    Assert-V2Ledger ($resumed.next -eq 1 -and $null -eq $resumed.pending -and $resumed.resolutions.Count -eq 1) 'Explicit retry did not preserve history'
    $resumed.pending=@{index=1;action_id=$id;terminal=$null}
    Assert-V2Ledger (Complete-V2ConstructionStep $resumed @{action_id=$id;state='succeeded';result=@{observed_count=1}}) 'Existing visible placement not retained'
    Assert-V2LedgerThrows {Complete-V2ConstructionStep $resumed @{action_id=$id;state='succeeded';result=@{confirmed_count=1}}}
    $changed=@{hash='wrong';steps=$plan.steps}
    Assert-V2LedgerThrows {Read-V2ConstructionCheckpoint $ledgerFile $changed}
    [IO.File]::WriteAllText($ledgerFile,'{"sha256":"bad","data":"{}"}')
    Assert-V2LedgerThrows {Read-V2ConstructionCheckpoint $ledgerFile $plan}
    [IO.File]::Delete($ledgerFile)
    $PlanPath=$planFile; $CheckpointPath=$ledgerFile
    $script:mockPlacements=0; $script:mockSession='session-one'
    function Invoke-V2ConstructionTool([string]$Name,$Arguments=@{}) {
        switch($Name) {
            agent_get_mcp_status { return @{world_session_id=$script:mockSession;running_action_id=$null} }
            agent_get_state { return @{player=@{dimension='minecraft:overworld'}} }
            agent_place_block { $script:mockPlacements++; throw 'simulated_lost_dispatch_response' }
            default { throw "unexpected_mock_call:$Name" }
        }
    }
    Assert-V2LedgerThrows { Invoke-V2ConstructionRun }
    $lost=Read-V2ConstructionCheckpoint $ledgerFile $plan
    Assert-V2Ledger ($script:mockPlacements -eq 1 -and $lost.pending.index -eq 0 -and $null -eq $lost.pending.action_id) 'Lost receipt did not preserve intent'
    Assert-V2LedgerThrows { Invoke-V2ConstructionRun }
    Assert-V2Ledger ($script:mockPlacements -eq 1) 'Lost dispatch was automatically replayed'
    $script:mockSession='session-two'
    Assert-V2LedgerThrows { Invoke-V2ConstructionRun }
    $AcceptWorldSessionChange=$true
    Assert-V2LedgerThrows { Invoke-V2ConstructionRun }
    Assert-V2Ledger ($script:mockPlacements -eq 1 -and (Read-V2ConstructionCheckpoint $ledgerFile $plan).session_id -ceq 'session-one') 'Session change discarded unresolved intent'
    $heldLock=[IO.File]::Open($ledgerFile+'.lock',[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try { Assert-V2LedgerThrows { Invoke-V2ConstructionRun } } finally { $heldLock.Dispose() }
    Assert-V2Ledger ($script:mockPlacements -eq 1) 'Concurrent runner dispatched work'
    Write-Output 'v2 construction ledger: 9 base checks and 4 dispatch/session/lock checks passed'
} finally {
    # Only individually named files created in this test's exact temporary directory are removed.
    foreach($path in @($planFile,$ledgerFile,($ledgerFile+'.lock'))){if([IO.File]::Exists($path)){[IO.File]::Delete($path)}}
    [IO.Directory]::Delete($testRoot)
}
