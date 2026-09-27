#requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-observations-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
try {
    . (Join-Path $PSScriptRoot 'Invoke-McmcpBuilding.ps1') -BlueprintPath (Join-Path $PSScriptRoot 'example-small-floor.json') -CheckpointPath (Join-Path $temp 'checkpoint.json') -MaxCells 2 -LibraryOnly
    $script:OriginalState=${function:Get-BuildingState}
    function Get-BuildingState {
        $state=& $script:OriginalState
        if($script:BeforeStart){
            $script:BeforeStart=$false
            $script:PreStartChecks++
            if($script:Scenario -cin @('operator_stop_requested','qr_health_or_water_stop')){throw $script:Scenario}
        }
        return $state
    }
    function Get-BuildingSurface($Position,$Expected,[string[]]$Faces=@()) {
        if($null -ne $script:Checkpoint.pending){
            $script:StrictReconciliations++
            throw 'test_unresolved_surface'
        }
        $script:Surfaces.Add(@{position=$Position;state=$Expected;faces=$Faces})
        $script:BeforeStart=$true
        return @{position=$Position;state=$Expected}
    }
    function Invoke-GateTool {
        param($Tool,$Arguments)
        switch -CaseSensitive ($Tool) {
            'agent_get_state' {
                $inventory=@(foreach($item in @('minecraft:snow_block','minecraft:black_wool','minecraft:torch')){
                    $used=if($script:Checkpoint.consumed.Contains($item)){$script:Checkpoint.consumed[$item]}else{0}
                    @{item=$item;count=64-$used}
                })
                $materials=@(foreach($material in $script:Plan.blueprint.palette.Values){
                    @{item=$material.item;count=64;state=$material.state;placement_state_ref='psr_0123456789abcdef0123456789abcdef'}
                })
                return @{world=@{session_id='test-session';dimension='minecraft:overworld';position=$script:Position;health=20}
                    control=@{mode='ready';game_paused=$false};observation=@{latest_frame_id='obs-0000000000000001'}
                    action=$null;inventory=$inventory;placement_materials=$materials}
            }
            'agent_start_action' {
                $index=$script:Checkpoint.next
                $stand=Get-BuildingStand ($index-1)
                $last=$script:Surfaces[$script:Surfaces.Count-1]
                foreach($key in @('dimension','x','y','z')){
                    if($last.position[$key] -cne $stand.position[$key] -or
                        $Arguments.program.body[0].support[$key] -cne $last.position[$key]){throw 'next_support_not_delivered'}
                }
                if($last.faces.Count -ne 1 -or $last.faces[0] -cne 'up' -or
                    -not (Test-BuildingStateEqual $last.state $stand.state)){throw 'incorrect_support_state'}
                if($script:Surfaces.Count -ne $script:Starts+1){throw 'redundant_or_missing_support_observation'}
                if($script:PreStartChecks -ne $script:Starts+1){throw 'pre_start_state_check_missing'}
                $disk=Read-BuildingCheckpoint $CheckpointPath $script:Plan
                if($null -eq $disk.pending -or $disk.pending.nonce -cne $Arguments.program.name){throw 'intent_not_durable'}
                $script:Starts++
                $cell=$script:Plan.cells[$index];$target=$cell.position
                $effect=@{kind='block_place';subject="block:$($target.dimension):$($target.x),$($target.y),$($target.z)"
                    verification='confirmed';observed_after=$cell.material.state;seq=1;client_tick=10;world_revision=1}
                $terminalState=if($script:Scenario -cin @('failed','cancelled')){$script:Scenario}else{'succeeded'}
                switch -CaseSensitive ($script:Scenario) {
                    'unknown' {$effect.verification='unknown'}
                    'wrong_target' {$effect.subject='block:minecraft:overworld:999,60,6'}
                    'wrong_state' {$effect.observed_after=@{block='minecraft:stone';properties=@{}}}
                }
                $script:MockTerminal=@{action_id=[guid]::NewGuid().ToString();state=$terminalState;effects=@($effect)}
                $script:Position=@{x=$target.x+0.5;y=$target.y+1;z=$target.z+0.5}
                return @{action_id=$script:MockTerminal.action_id}
            }
            'agent_get_action' {
                if($Arguments.action_id -cne $script:MockTerminal.action_id){throw 'unexpected_action_id'}
                return $script:MockTerminal
            }
            default {throw 'unexpected_tool'}
        }
    }
    function Invoke-ObservationScenario([string]$Name) {
        $script:Scenario=$Name;$script:Starts=0;$script:PreStartChecks=0;$script:BeforeStart=$false
        $script:StrictReconciliations=0;$script:MockTerminal=$null
        $script:Surfaces=[Collections.Generic.List[object]]::new()
        $script:Plan=Read-BuildingBlueprint $BlueprintPath
        $script:Position=@{x=$script:Plan.start.x+0.5;y=$script:Plan.start.y+1;z=$script:Plan.start.z+0.5}
        $CheckpointPath=Join-Path $temp ($Name+'.json')
        $result=Invoke-BuildingRun
        $disk=Read-BuildingCheckpoint $CheckpointPath $script:Plan
        if($disk.next -ne $result.next -or $disk.status -cne $result.status){throw 'final_checkpoint_not_saved'}
        return $result
    }

    $result=Invoke-ObservationScenario 'success'
    if($result.next -ne 2 -or $script:Starts -ne 2 -or $script:Surfaces.Count -ne 2 -or
        $script:PreStartChecks -ne 2 -or $null -ne $result.pending -or $null -ne $result.diagnostic -or
        $result.cells[0] -cne 'confirmed' -or $result.cells[1] -cne 'confirmed'){
        throw 'continuous_floor_did_not_use_one_fresh_support_per_action'
    }

    foreach($scenario in @('unknown','wrong_target','wrong_state')){
        $result=Invoke-ObservationScenario $scenario
        if($script:Starts -ne 1 -or $result.next -ne 0 -or $null -eq $result.pending -or
            $script:StrictReconciliations -ne 1 -or $result.status -cne 'unknown'){
            throw 'unconfirmed_placement_skipped_strict_reconciliation'
        }
    }
    foreach($scenario in @('failed','cancelled')){
        $result=Invoke-ObservationScenario $scenario
        if($script:Starts -ne 1 -or $result.next -ne 1 -or $result.status -cne 'paused' -or
            $result.diagnostic -cne 'action_requires_reconciliation' -or $result.consumed['minecraft:snow_block'] -ne 1){
            throw 'failed_or_cancelled_placement_continued'
        }
    }
    foreach($scenario in @('operator_stop_requested','qr_health_or_water_stop')){
        $result=Invoke-ObservationScenario $scenario
        if($script:Starts -ne 0 -or $result.next -ne 0 -or $null -ne $result.pending -or
            $result.diagnostic -cne $scenario -or $script:PreStartChecks -ne 1){throw 'pre_start_stop_ignored'}
    }

    # Generic navigation still checks the actual destination support, including return trips.
    $script:Scenario='success';$script:BeforeStart=$false
    $goal=Get-BuildingStand -1
    $script:Position=@{x=$goal.position.x+0.5;y=$goal.position.y+1;z=$goal.position.z+0.5}
    $before=$script:Surfaces.Count
    Move-BuildingTo -1
    if($script:Surfaces.Count -ne $before+1){throw 'generic_move_lost_support_observation'}
    'MCMCP building observation tests passed (continuous support delivery, exact effects, strict unknown, terminal stop, pre-start checks, generic move).'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -cmatch '^mcmcp-observations-[a-f0-9]{32}$'){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
