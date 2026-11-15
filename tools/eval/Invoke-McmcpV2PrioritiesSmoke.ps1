#requires -Version 7.4
[CmdletBinding()]
param([string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,
    [string]$ArtifactDirectory,
    [ValidateSet('Components','TableCancel','Inventory','Water','WaterAir','Boat','Minecart','Horse','ModBreak','Resources')]
    [string]$PriorityCase='Components',[switch]$LibraryOnly)
$ErrorActionPreference='Stop'
$runPriorities=-not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2ProductionSmoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -LibraryOnly

function Invoke-V2SmokeCore {
    switch ($PriorityCase) {
        'Components' {
            [void](Invoke-V2ProductionCraft 'minecraft:oak_planks' 8 @{kind='inventory_2x2'} 2)
            [void](Invoke-V2ProductionCraft 'minecraft:stick' 8 @{kind='inventory_2x2'} 2)
            [void](Invoke-V2ProductionCraft 'minecraft:furnace' 2 @{kind='crafting_table';x=305;y=101;z=303} 2)
            [void](Invoke-V2ProductionCraft 'minecraft:carrot_on_a_stick' 1 @{kind='inventory_2x2'} 1)
            $inventory=Get-V2SmokeInventory
            foreach($pair in @(@('minecraft:oak_log',14),@('minecraft:oak_planks',4),@('minecraft:stick',8),
                    @('minecraft:cobblestone',0),@('minecraft:furnace',2),@('minecraft:carrot_on_a_stick',1),
                    @('minecraft:fishing_rod',0),@('minecraft:carrot',0))) {
                Assert-V2Smoke ((Get-V2SmokeItemCount $inventory $pair[0]) -eq $pair[1]) "Component craft count mismatch: $($pair[0])"
            }
        }
        'Inventory' {
            # These occupied slots have distinct data and no spare slot in the full inventory.
            $sort=Invoke-V2SmokeJob 'agent_inventory' @{operation='sort';slots=@(2..8);max_ticks=1200}
            Assert-V2Smoke ($sort.result.confirmed_transfers -gt 0) 'Expected full inventory exchanges'
            [void](Invoke-V2SmokeJob 'agent_inventory' @{operation='consolidate';slots=@(9,10)})
            foreach($move in @(@{source_slot=0;destination_slot=39;item='minecraft:iron_helmet'},
                    @{source_slot=1;destination_slot=40;item='minecraft:shield'})) {
                [void](Invoke-V2SmokeJob 'agent_inventory' ($move+@{operation='move';count=1}))
            }
            [void](Invoke-V2SmokeJob 'agent_inventory' @{operation='sort'})
            $inventory=Get-V2SmokeInventory
            Assert-V2Smoke ((Get-V2SmokeItemCount $inventory 'minecraft:stone') -eq 32 -and
                (Get-V2SmokeItemCount $inventory 'minecraft:oak_planks') -eq 24) 'Inventory conservation mismatch'
            Assert-V2Smoke (@($inventory.slots | Where-Object { $_.slot -eq 9 -and $_.item -ceq 'minecraft:oak_planks' -and $_.count -eq 24 }).Count -eq 1) 'Consolidated stack missing'
        }
        'TableCancel' {
            $recipe=Get-V2ProductionRecipe 'minecraft:furnace' '' 'minecraft:cobblestone'
            $id=Start-V2SmokeJob 'agent_craft' @{operation='execute';station=@{kind='crafting_table';x=305;y=101;z=303};
                recipe_ref=$recipe.recipe_ref;recipe_fingerprint=$recipe.fingerprint;count=2;max_crafts=2;max_ticks=1200}
            $clock=[Diagnostics.Stopwatch]::StartNew()
            do {
                $running=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;wait_timeout_ms=100;include_result=$true}
                Assert-V2Smoke ($running.state -cin @('queued','running') -and $clock.Elapsed.TotalSeconds -lt 10) 'Workbench craft did not reach grid preparation'
            } while(-not (Get-PropertyValue (Get-PropertyValue $running 'result') 'unconfirmed'))
            Start-Sleep -Milliseconds 200
            [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
            $done=Wait-V2SmokeJob $id
            Assert-V2Smoke ($done.state -ceq 'cancelled' -and -not $done.result.release_pending) 'Workbench craft cancellation did not release its cursor and menu'
            $inventory=Get-V2SmokeInventory
            $furnaces=Get-V2SmokeItemCount $inventory 'minecraft:furnace'
            Assert-V2Smoke ((Get-V2SmokeItemCount $inventory 'minecraft:cobblestone')+8*$furnaces -eq 16 -and
                $furnaces -eq $done.result.confirmed_count) 'Workbench craft cancellation lost materials or output'
        }
        'Water' {
            foreach($point in @(@(311,102,310),@(311,104,310),@(311,101,310),@(315,105,310))) {
                [void](Invoke-V2SmokeJob 'agent_move' @{x=$point[0];y=$point[1];z=$point[2];max_ticks=300})
            }
            Assert-V2Smoke ((Get-V2SmokeState).player.health -eq 20) 'Water travel caused damage'
        }
        'WaterAir' {
            $id=Start-V2SmokeJob 'agent_move' @{x=312;y=101;z=310;max_ticks=300}
            $done=Wait-V2SmokeJob $id
            Assert-V2Smoke ($done.state -ceq 'failed' -and $done.failure -ceq 'air_supply_retreat' -and
                $done.result.diverted -and $done.result.diversion_reason -ceq 'air_supply_retreat') 'Low air did not cause a confirmed surface retreat'
            $state=Get-V2SmokeState
            # After releasing input, vanilla resumes sinking; verify the recovered oxygen
            # rather than requiring an ongoing swim input after the job has terminated.
            $script:SmokeEvents.Add(@{oxygen_retreat=@{health=$state.player.health;air=$state.player.air;position=$state.player.position}})
            Assert-V2Smoke ($state.player.health -eq 20 -and $state.player.air -ge 160) 'Surface retreat caused damage or did not replenish oxygen'
        }
        {$_ -in @('Boat','Minecart','Horse')} {
            $type=@{Boat='minecraft:oak_boat';Minecart='minecraft:minecart';Horse='minecraft:horse'}[$PriorityCase]
            $frame=(Invoke-V2SmokeTool 'agent_get_mcp_status').latest_frame_id
            $observation=Invoke-V2SmokeTool 'agent_get_observation' @{schema_version=1;frame_id=$frame;
                kinds=@('visible_entity');cursor=$null;limit=256;filter=@{entity_types=@($type)}}
            $vehicle=@($observation.records | Where-Object entity_type -CEQ $type -ErrorAction SilentlyContinue)
            Assert-V2Smoke ($vehicle.Count -eq 1) 'Expected one observed fixture vehicle'
            [void](Invoke-V2SmokeJob 'agent_interact' @{target='entity';operation='mount';entity_ref=$vehicle[0].entity_ref;entity_type=$type})
            $x=@{Boat=309;Minecart=314;Horse=310}[$PriorityCase]
            [void](Invoke-V2SmokeJob 'agent_move' @{x=$x;y=101;z=306;max_ticks=500;tolerance=0.49})
            if($PriorityCase -eq 'Boat') {
                # This destination is open water, with no dry landing for normal dismount.
                $id=Start-V2SmokeJob 'agent_interact' @{target='vehicle';operation='dismount'}
                $done=Wait-V2SmokeJob $id
                Assert-V2Smoke ($done.state -ceq 'failed' -and $done.result.failure -ceq 'dismount_landing_unverified' -and
                    -not $done.result.unconfirmed) 'Boat dismount into open water was not rejected before dispatch'
            } else { [void](Invoke-V2SmokeJob 'agent_interact' @{target='vehicle';operation='dismount'}) }
        }
        'ModBreak' {
            $frame=(Invoke-V2SmokeTool 'agent_get_mcp_status').latest_frame_id
            $observation=Invoke-V2SmokeTool 'agent_get_observation' @{schema_version=1;frame_id=$frame;
                kinds=@('visible_surface');cursor=$null;limit=256;filter=@{block_ids=@('sophisticatedbackpacks:backpack')}}
            $block=@($observation.records | Where-Object { (Get-PropertyValue $_ 'block') -ceq 'sophisticatedbackpacks:backpack' })
            Assert-V2Smoke ($block.Count -ge 1) 'Expected an observed MOD backpack block'
            $done=Invoke-V2SmokeJob 'agent_break_block' @{x=305;y=101;z=303;include_blocks=@($block[0].block);max_ticks=400}
            Assert-V2Smoke ($done.progress.broken_blocks -eq 1) 'MOD block break unconfirmed'
        }
        'Resources' {
            $done=Invoke-V2SmokeJob 'agent_run_script' @{source_file='priorities-probe.mcscript';
                data_files=@{blueprint=@{path='priorities-probe.csv';format='csv'}}}
            Assert-V2Smoke ($done.result.completed_commands -eq 1 -and
                $done.result.resources.blueprint.format -ceq 'csv') 'External data script did not execute its branch'
            $source='let p=observe(kind="player"); let c=observe(kind="condition",condition={type:"all",conditions:[{type:"player",property:"health",comparison:"at_least",value:1},{type:"not",condition:{type:"player",property:"on_fire",value:true}}]}); if(p.known && c.known && c.value){inventory(operation="inspect");}'
            $condition=Invoke-V2SmokeJob 'agent_run_script' @{source=$source}
            Assert-V2Smoke ($condition.result.completed_commands -eq 1) 'Observed condition branch did not execute'
        }
    }
}
if($runPriorities) {
    $result=Invoke-McmcpV2Smoke
    [pscustomobject]$result | Select-Object status,failure,active_action_id | ConvertTo-Json -Compress
    if($result.status -cne 'passed'){exit 1}
}
