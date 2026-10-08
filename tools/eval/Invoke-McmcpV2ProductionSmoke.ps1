#requires -Version 7.4
[CmdletBinding()]
param(
    [string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,
    [string]$ArtifactDirectory,
    [ValidateSet('Craft','Cake','Smelt','Brew','BrewFilled','MoveStop','Cancel','BrewCancel','Script')]
    [string]$ProductionCase = 'Craft',
    [switch]$LibraryOnly
)
$ErrorActionPreference = 'Stop'
$runProduction = -not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -Phase Core -LibraryOnly

# Reuse the existing one-shot dispatch, bounded polling, cleanup and receipt persistence.
function Get-V2SmokeState {
    $control = Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state = Invoke-V2SmokeTool 'agent_get_state'
    $p = $state.player.position
    Assert-V2Smoke ($p.x -ge 300 -and $p.x -le 320 -and $p.y -ge 101 -and $p.y -le 108 -and
        $p.z -ge 300 -and $p.z -le 320) 'Player is outside the production fixture'
    return $state
}

function Get-V2ProductionRecipe([string]$Item, [string]$Screen, [string]$Ingredient) {
    $query = Invoke-V2SmokeTool 'agent_get_state' @{ query = @{ kind = 'result_item'; item = $Item }; max_results = 64 }
    $matches = @($query.recipe_query.recipes | Where-Object {
        if ($Screen -eq 'furnace') {
            $_.supported -and $_.required_screen -ceq $Screen -and
                $Ingredient -cin @($_.ingredients[0].alternatives | ForEach-Object { $_.item })
        } else {
            $_.v2_craft_supported -and (!$Ingredient -or $Ingredient -cin @($_.ingredients | ForEach-Object {
                $_.alternatives | ForEach-Object { $_.item }
            }))
        }
    })
    Assert-V2Smoke ($matches.Count -eq 1) 'Expected one suitable fixture recipe'
    return $matches[0]
}

function Invoke-V2ProductionCraft([string]$Item, [int]$Count, [hashtable]$Station, [int]$Crafts) {
    $ingredient = @{'minecraft:oak_planks'='minecraft:oak_log';'minecraft:stick'='minecraft:oak_planks';
        'minecraft:furnace'='minecraft:cobblestone';'minecraft:cake'='minecraft:milk_bucket'}[$Item]
    $recipe = Get-V2ProductionRecipe $Item '' $ingredient
    $arguments = @{ operation = 'execute'; station = $Station; count = $Count; max_crafts = $Crafts
        recipe_ref = $recipe.recipe_ref; recipe_fingerprint = $recipe.fingerprint; max_ticks = 1200 }
    $plan = $arguments.Clone(); $plan.operation = 'plan'
    [void](Invoke-V2SmokeJob 'agent_craft' $plan)
    $done = Invoke-V2SmokeJob 'agent_craft' $arguments
    Assert-V2Smoke ($done.result.confirmed_count -eq $Count -and -not $done.result.unconfirmed -and
        -not $done.result.release_pending) 'Craft quantity/release mismatch'
    return $done
}

function Invoke-V2SmokeCore {
    switch ($ProductionCase) {
        'Script' {
            $recipe = Get-V2ProductionRecipe 'minecraft:oak_planks' '' 'minecraft:oak_log'
            $source = "craft(operation='execute', station={kind:'inventory_2x2'}, recipe_ref='$($recipe.recipe_ref)', recipe_fingerprint='$($recipe.fingerprint)', count=4, max_crafts=1); inventory(operation='inspect');"
            [void](Invoke-V2SmokeJob 'agent_run_script' @{source=$source})
            $inventory = Get-V2SmokeInventory
            Assert-V2Smoke ((Get-V2SmokeItemCount $inventory 'minecraft:oak_log') -eq 15 -and
                (Get-V2SmokeItemCount $inventory 'minecraft:oak_planks') -eq 4) 'Script craft result mismatch'
        }
        'Craft' {
            [void](Invoke-V2ProductionCraft 'minecraft:oak_planks' 8 @{kind='inventory_2x2'} 2)
            [void](Invoke-V2ProductionCraft 'minecraft:stick' 8 @{kind='inventory_2x2'} 2)
            [void](Invoke-V2ProductionCraft 'minecraft:furnace' 2 @{kind='crafting_table';x=305;y=101;z=303} 2)
            $inventory = Get-V2SmokeInventory
            foreach ($pair in @(@('minecraft:oak_log',14),@('minecraft:oak_planks',4),@('minecraft:stick',8),@('minecraft:cobblestone',8),@('minecraft:furnace',2))) {
                Assert-V2Smoke ((Get-V2SmokeItemCount $inventory $pair[0]) -eq $pair[1]) 'Craft inventory mismatch'
            }
        }
        'Cake' {
            $done = Invoke-V2ProductionCraft 'minecraft:cake' 2 @{kind='crafting_table';x=305;y=101;z=303} 2
            $inventory = Get-V2SmokeInventory
            Assert-V2Smoke ($done.result.produced_remainders.'minecraft:bucket' -eq 6 -and
                (Get-V2SmokeItemCount $inventory 'minecraft:cake') -eq 2 -and
                (Get-V2SmokeItemCount $inventory 'minecraft:bucket') -eq 6 -and
                (Get-V2SmokeItemCount $inventory 'minecraft:milk_bucket') -eq 0) 'Remainder recovery mismatch'
        }
        'Smelt' {
            $recipe = Get-V2ProductionRecipe 'minecraft:iron_ingot' 'furnace' 'minecraft:raw_iron'
            $done = Invoke-V2SmokeJob 'agent_interact' @{target='block';operation='smelt';x=307;y=101;z=303
                block='minecraft:furnace';recipe_ref=$recipe.recipe_ref;recipe_fingerprint=$recipe.fingerprint
                count=4;fuel=@{item='minecraft:coal';max_count=1};max_ticks=2400}
            Assert-V2Smoke ($done.result.confirmed_count -eq 4 -and $done.result.consumed.'minecraft:raw_iron' -eq 4 -and
                $done.result.consumed.'minecraft:coal' -eq 1) 'Smelt quantities mismatch'
            Assert-V2Smoke ((Get-V2SmokeItemCount (Get-V2SmokeInventory) 'minecraft:iron_ingot') -eq 4) 'Smelt readback mismatch'
        }
        { $_ -in @('Brew','BrewFilled','BrewCancel') } {
            $filled = $ProductionCase -eq 'BrewFilled'
            $arguments = @{target='block';operation='brew';x=309;y=101;z=303;block='minecraft:brewing_stand'
                input=@{item='minecraft:potion';potion=$(if($filled){'minecraft:awkward'}else{'minecraft:water'})}
                output=@{item='minecraft:potion';potion=$(if($filled){'minecraft:strength'}else{'minecraft:awkward'})}
                ingredient=$(if($filled){'minecraft:blaze_powder'}else{'minecraft:nether_wart'})
                count=3;fuel=@{item='minecraft:blaze_powder';max_count=1};max_ticks=2400}
            if ($ProductionCase -eq 'BrewCancel') {
                $id = Start-V2SmokeJob 'agent_interact' $arguments
                $clock = [Diagnostics.Stopwatch]::StartNew()
                do {
                    $progress = Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;include_result=$true;wait_timeout_ms=1000}
                    Assert-V2Smoke ($progress.state -in @('queued','running') -and $clock.Elapsed.TotalSeconds -lt 30) 'Brewing did not reach loaded checkpoint'
                    $details = Get-PropertyValue $progress 'result'
                    $stage = Get-PropertyValue $details 'progress'
                } while ((Get-PropertyValue $stage 'loaded_readback') -ne $true)
                [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
                $done = Wait-V2SmokeJob $id
                Assert-V2Smoke ($done.state -eq 'cancelled' -and $done.result.processing_may_continue -and
                    -not $done.result.release_pending) 'Brewing cancellation mismatch'
            } else {
                $done = Invoke-V2SmokeJob 'agent_interact' $arguments
                Assert-V2Smoke ($done.result.confirmed_count -eq 3 -and $done.result.confirmed) 'Brewing quantity mismatch'
            }
            [void](Get-V2SmokeInventory)
        }
        'MoveStop' {
            $id = Start-V2SmokeJob 'agent_inventory' @{operation='move';source_slot=0;destination_slot=10;item='minecraft:oak_log';count=1;max_ticks=3}
            $done = Wait-V2SmokeJob $id
            Assert-V2Smoke ($done.state -eq 'failed' -and $done.failure -eq 'duration_limit' -and
                $done.result.confirmed_count -eq 1 -and -not $done.result.unconfirmed) 'Partial transfer result mismatch'
            $inventory = Get-V2SmokeInventory
            Assert-V2Smoke ((Get-V2SmokeItemCount $inventory 'minecraft:oak_log') -eq 64) 'Cursor recovery lost materials'
        }
        'Cancel' {
            $recipe = Get-V2ProductionRecipe 'minecraft:oak_planks' '' 'minecraft:oak_log'
            $id = Start-V2SmokeJob 'agent_craft' @{operation='execute';station=@{kind='inventory_2x2'}
                recipe_ref=$recipe.recipe_ref;recipe_fingerprint=$recipe.fingerprint;count=256;max_crafts=64;max_ticks=1200}
            Start-Sleep -Milliseconds 200
            [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
            $done = Wait-V2SmokeJob $id
            Assert-V2Smoke ($done.state -eq 'cancelled' -and -not $done.result.release_pending) 'Craft cancellation mismatch'
            $inventory = Get-V2SmokeInventory
            $logs = Get-V2SmokeItemCount $inventory 'minecraft:oak_log'
            $planks = Get-V2SmokeItemCount $inventory 'minecraft:oak_planks'
            Assert-V2Smoke ($logs * 4 + $planks -eq 256 -and $planks -eq $done.result.confirmed_count) 'Cancelled craft conservation mismatch'
        }
    }
}

if ($runProduction) {
    $result = Invoke-McmcpV2Smoke
    [pscustomobject]$result | Select-Object status, failure, active_action_id | ConvertTo-Json -Compress
    if ($result.status -ne 'passed') { exit 1 }
}
