#requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$TokenPath,[Parameter(Mandatory)][string]$ExpectedWorldSession,
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [ValidateSet('Entity','Conditions','Special','Menu','PathDefault','PathClear','Bridge','PathCancel','Construction')][string]$Phase,
    [string]$Endpoint='http://127.0.0.1:8765/mcp')
$trialPhase=$Phase
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -LibraryOnly -TokenPath $TokenPath -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -Endpoint $Endpoint
$Phase=$trialPhase
function Get-V2SmokeState {
    $control=Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state=Invoke-V2SmokeTool 'agent_get_state'
    Assert-V2Smoke ($state.schema_version -eq 2 -and @($state.hotbar.slots).Count -eq 9) 'Not a v2 state'
    $p=$state.player.position
    Assert-V2Smoke ($p.x -ge 196 -and $p.x -le 214 -and $p.y -ge 200 -and $p.y -le 205 -and
        $p.z -ge 196 -and $p.z -le 212) 'Player is outside the trial fixture'
    return $state
}
$script:SmokeEvents=[Collections.Generic.List[object]]::new()
$script:SmokeActive=$null
[void][IO.Directory]::CreateDirectory($ArtifactDirectory)
$passed=$false
try {
    $before=Get-V2SmokeState
    switch($Phase) {
        Entity {
            $observation=Invoke-V2SmokeTool 'agent_get_observation'
            $cow=@($observation.records | Where-Object entity_type -CEQ 'minecraft:cow' -ErrorAction SilentlyContinue)
            Assert-V2Smoke ($cow.Count -eq 1) 'One visible cow required'
            $arguments=@{target='entity';entity_ref=$cow[0].entity_ref;entity_type='minecraft:cow';item='minecraft:bucket';result_item='minecraft:milk_bucket';advance=$true}
            $milk=Invoke-V2SmokeJob 'agent_interact' $arguments
            Assert-V2Smoke ($milk.result.effect_confirmed) 'Milking unconfirmed'
            $after=Get-V2SmokeState
            Assert-V2Smoke ($after.player.position.z -gt $before.player.position.z+2) 'No entity approach'
            [void](Invoke-V2SmokeJob 'agent_interact' @{target='item';item='minecraft:milk_bucket';result_item='minecraft:bucket'})
        }
        Conditions {
            foreach($condition in @(
                @{type='item';item='minecraft:bucket';count=1;comparison='equals'},
                @{type='block';x=200;y=200;z=201;block='minecraft:smooth_stone'},
                @{type='screen';screen='none'})) {
                $job=Invoke-V2SmokeJob 'agent_input_sequence' @{steps=@(@{inputs=@('sneak');hold_ticks=400});stop_when=$condition}
                Assert-V2Smoke ($job.result.stop_condition_met -and $job.progress.completed_operations -eq 0) 'Condition did not stop before input'
            }
            $job=Invoke-V2SmokeJob 'agent_move' @{x=210;y=201;z=200;stop_when=@{type='item';item='minecraft:bucket';count=1}}
            Assert-V2Smoke ($job.result.stop_condition_met) 'Move condition missing'
            $job=Invoke-V2SmokeJob 'agent_input_sequence' @{steps=@(@{inputs=@('sneak');hold_ticks=3});stop_when=@{type='block';x=200;y=199;z=201;block='minecraft:air'}}
            Assert-V2Smoke ($job.progress.completed_operations -ge 3) 'Hidden air satisfied a block condition'
        }
        Special {
            foreach($args in @(
                @{x=204;y=201;z=202;block='minecraft:sunflower'},
                @{x=202;y=201;z=202;block='minecraft:red_bed'},
                @{x=203;y=201;z=200;block='minecraft:oak_door'})) {
                $job=Invoke-V2SmokeJob 'agent_place_block' ($args+@{advance=$true})
                Assert-V2Smoke ($job.result.confirmed_count -eq 1 -and @($job.result.confirmed_cells[0].companions).Count -eq 1) 'Two-cell placement missing confirmation'
            }
        }
        Menu {
            $args=@{target='menu';x=199;y=201;z=200;block='minecraft:chest';menu_type='minecraft:generic_9x3';advance=$true}
            $inspected=Invoke-V2SmokeJob 'agent_interact' $args
            Assert-V2Smoke ($inspected.result.slots[0].item -ceq 'minecraft:snow_block' -and $inspected.result.slots[0].count -eq 16) 'Menu content missing'
            $id=Start-V2SmokeJob 'agent_interact' ($args+@{clicks=@(@{type='quick_move';slot=0;item='minecraft:snow_block';count=15})})
            $rejected=Wait-V2SmokeJob $id
            Assert-V2Smoke ($rejected.state -ceq 'failed' -and $rejected.result.confirmed_clicks -eq 0 -and -not $rejected.result.unconfirmed_click) 'Mismatched slot count dispatched a click'
            $taken=Invoke-V2SmokeJob 'agent_interact' ($args+@{clicks=@(@{type='quick_move';slot=0;item='minecraft:snow_block';count=16})})
            Assert-V2Smoke ($taken.result.confirmed_clicks -eq 1 -and -not $taken.result.unconfirmed_click) 'Menu click unconfirmed'
            $inspected=Invoke-V2SmokeJob 'agent_interact' $args
            Assert-V2Smoke ($inspected.result.slots[0].count -eq 0) 'Menu readback not empty'
        }
        PathDefault {
            $id=Start-V2SmokeJob 'agent_move' @{x=200;y=201;z=207;max_ticks=200;max_distance=20}
            $job=Wait-V2SmokeJob $id
            Assert-V2Smoke ($job.state -ceq 'failed') 'Blocked default path unexpectedly passed'
            Assert-V2Smoke ((Get-V2SmokeState).player.position.z -lt 203) 'Default path crossed obstacle'
        }
        PathClear {
            $job=Invoke-V2SmokeJob 'agent_move' @{x=200;y=201;z=207;max_distance=20;clear_path=$true}
            Assert-V2Smoke ($job.result.path_changed_count -eq 2) 'Path did not confirm both removed blocks'
            Assert-V2Smoke ((Get-V2SmokeState).player.position.z -ge 207) 'Path did not arrive'
        }
        Bridge {
            $job=Invoke-V2SmokeJob 'agent_move' @{x=200;y=201;z=207;max_distance=20;clear_path=$true;bridge_block='minecraft:smooth_stone'}
            Assert-V2Smoke ($job.result.path_changed_count -eq 2) 'Bridge did not confirm both support blocks'
            Assert-V2Smoke (@($job.result.path_changes | Where-Object operation -NE 'place').Count -eq 0) 'Bridge unexpectedly removed blocks'
            Assert-V2Smoke ((Get-V2SmokeState).player.position.z -ge 207) 'Bridge did not arrive'
        }
        PathCancel {
            $id=Start-V2SmokeJob 'agent_move' @{x=200;y=201;z=207;max_distance=20;clear_path=$true}
            $deadline=[DateTime]::UtcNow.AddSeconds(15)
            do {
                $job=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;include_result=$true}
                if ($job.PSObject.Properties.Name -contains 'result' -and $job.result.PSObject.Properties.Name -contains 'unconfirmed_path_target') { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt $deadline -and $job.state -notin @('succeeded','failed','cancelled'))
            Assert-V2Smoke ($job.PSObject.Properties.Name -contains 'result' -and $job.result.PSObject.Properties.Name -contains 'unconfirmed_path_target') 'Path cancellation did not reach an active edit'
            $script:SmokeEvents.Add(@{before_cancel=$job})
            [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
            $job=Wait-V2SmokeJob $id
            Assert-V2Smoke ($job.state -ceq 'cancelled') 'Path did not cancel'
            Assert-V2Smoke ((Invoke-V2SmokeTool 'agent_get_mcp_status').running_action_id -eq $null) 'Path input ownership still active'
        }
        Construction {
            $planPath=Join-Path $ArtifactDirectory 'plan.json'
            $checkpointPath=Join-Path $ArtifactDirectory 'checkpoint.json'
            @{version=2;dimension='minecraft:overworld';steps=@(
                @{x=202;y=201;z=204;block='minecraft:black_wool';advance=$true},
                @{x=203;y=201;z=204;block='minecraft:black_wool';advance=$true})} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $planPath -Encoding utf8NoBOM
            $first=& (Join-Path $PSScriptRoot '../building/Invoke-McmcpV2Construction.ps1') -PlanPath $planPath -CheckpointPath $checkpointPath -TokenPath $TokenPath -Endpoint $Endpoint -MaxSteps 1
            Assert-V2Smoke ($first.next -eq 1 -and $first.status -ceq 'paused') 'Construction did not checkpoint'
            $second=& (Join-Path $PSScriptRoot '../building/Invoke-McmcpV2Construction.ps1') -PlanPath $planPath -CheckpointPath $checkpointPath -TokenPath $TokenPath -Endpoint $Endpoint
            Assert-V2Smoke ($second.next -eq 2 -and $second.status -ceq 'complete' -and $second.receipts[0].action_id -ceq $first.receipts[0].action_id) 'Construction did not resume without replay'
            $script:SmokeEvents.Add(@{first=$first;resumed=$second})
        }
        default { throw 'Unknown trial phase' }
    }
    $script:SmokeEvents.Add(@{after=(Get-V2SmokeState);control=(Invoke-V2SmokeTool 'agent_get_mcp_status')})
    $passed=$true
} finally {
    if($null -ne $script:SmokeActive) {
        try {$script:SmokeEvents.Add(@{cleanup=(Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$script:SmokeActive})})}catch{}
    }
    @{phase=$Phase;passed=$passed;world_session_id=$ExpectedWorldSession;events=$script:SmokeEvents.ToArray()} | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $ArtifactDirectory 'result.json') -Encoding utf8NoBOM
}
Write-Output "$Phase passed"
