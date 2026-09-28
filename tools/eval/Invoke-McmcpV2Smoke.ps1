#requires -Version 7.4
[CmdletBinding()]
param(
    [string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,
    [string]$ArtifactDirectory,
    [ValidateSet('Core', 'ItemEntity', 'Cancel', 'Hazard')][string]$Phase = 'Core',
    [switch]$LibraryOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../mcp/McmcpClient.ps1')

# A deterministic smoke test, not a fresh LLM evaluation. Fixture setup is external.
function Invoke-V2SmokeTool([string]$Name, [object]$Arguments = @{}) {
    $reply = Invoke-McmcpClient -TokenPath $TokenPath -Endpoint $Endpoint -Tool $Name -Arguments $Arguments
    if (-not $reply.ok) {
        $script:SmokeEvents.Add(@{ tool = $Name; rejected = $reply })
        throw "MCP call failed: $Name / $($reply.diagnostic_code)"
    }
    return $reply.result
}

function Assert-V2Smoke([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-V2SmokeState {
    $control = Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state = Invoke-V2SmokeTool 'agent_get_state'
    Assert-V2Smoke ($state.schema_version -eq 2 -and @($state.hotbar.slots).Count -eq 9) 'Not a v2 world state'
    $p = $state.player.position
    Assert-V2Smoke ($p.x -ge 198 -and $p.x -le 210 -and $p.y -ge 200 -and $p.y -le 205 -and
        $p.z -ge 198 -and $p.z -le 206) 'Player is outside the isolated fixture'
    return $state
}

function Wait-V2SmokeJob([string]$Id, [int]$Seconds = 90) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    do {
        $terminal = Invoke-V2SmokeTool 'agent_get_action' @{
            action_id = $Id; wait_timeout_ms = 1000; include_result = $true
        }
        Assert-V2Smoke ($terminal.action_id -ceq $Id) 'Action ID mismatch'
        if ($terminal.state -cin @('succeeded', 'failed', 'cancelled')) {
            $script:SmokeActive = $null
            $script:SmokeEvents.Add(@{ terminal = $terminal })
            return $terminal
        }
    } while ($clock.Elapsed.TotalSeconds -lt $Seconds)
    throw 'Action wait expired; the existing job must be cancelled, never restarted'
}

function Start-V2SmokeJob([string]$Name, [object]$Arguments) {
    Assert-V2Smoke ($null -eq $script:SmokeActive) 'Previous job has not terminated'
    $receipt = Invoke-V2SmokeTool $Name $Arguments
    Assert-V2Smoke ($receipt.state -ceq 'queued' -and
        $receipt.action_id -cmatch '^[0-9a-f-]{36}$') 'Invalid action receipt'
    $script:SmokeActive = $receipt.action_id
    $script:SmokeEvents.Add(@{ tool = $Name; arguments = $Arguments; receipt = $receipt })
    return $receipt.action_id
}

function Invoke-V2SmokeJob([string]$Name, [object]$Arguments) {
    $id = Start-V2SmokeJob $Name $Arguments
    $terminal = Wait-V2SmokeJob $id
    Assert-V2Smoke ($terminal.state -ceq 'succeeded') "Job failed: $Name / $($terminal.failure)"
    return $terminal
}

function Get-V2SmokeInventory {
    $job = Invoke-V2SmokeJob 'agent_inventory' @{ operation = 'inspect' }
    return $job.result
}

function Get-V2SmokeItemCount([object]$Inventory, [string]$Item) {
    return [int](@($Inventory.slots | Where-Object item -CEQ $Item |
        Measure-Object -Property count -Sum)[0].Sum)
}

function Invoke-V2SmokeCore {
    $state = Get-V2SmokeState
    Assert-V2Smoke (@($state.PSObject.Properties.Name).Count -eq 3) 'Default state expanded unexpectedly'
    $observation = Invoke-V2SmokeTool 'agent_get_observation'
    Assert-V2Smoke (@($observation.records | Where-Object kind -CEQ 'block').Count -gt 0) 'No compact blocks'
    Assert-V2Smoke (@($observation.records | Where-Object kind -CEQ 'unknown_boundary').Count -eq 0) 'Unexpected boundary list'
    $script:SmokeEvents.Add(@{ compact_records = @($observation.records).Count; initial_state = $state })

    $before = Get-V2SmokeInventory
    Assert-V2Smoke ((Get-V2SmokeItemCount $before 'minecraft:black_wool') -eq 16) 'Fixture items missing'
    [void](Invoke-V2SmokeJob 'agent_inventory' @{
        operation = 'swap'; source_slot = 9; hotbar_slot = 4; item = 'minecraft:black_wool'
    })
    $swapped = Get-V2SmokeState
    Assert-V2Smoke ($swapped.hotbar.slots[4].item -ceq 'minecraft:black_wool' -and
        $swapped.hotbar.slots[4].count -eq 16) 'SWAP readback mismatch'
    [void](Invoke-V2SmokeJob 'agent_inventory' @{ operation = 'drop'; item = 'minecraft:black_wool'; count = 2; slot = 4 })
    Assert-V2Smoke ((Get-V2SmokeItemCount (Get-V2SmokeInventory) 'minecraft:black_wool') -eq 14) 'Drop count mismatch'

    $container = @{ target = 'container'; x = 199; y = 201; z = 200; advance = $false }
    $inspection = Invoke-V2SmokeJob 'agent_inventory' ($container + @{ operation = 'inspect' })
    $script:SmokeEvents.Add(@{ container = $inspection.result })
    $snowBefore = Get-V2SmokeItemCount (Get-V2SmokeInventory) 'minecraft:snow_block'
    [void](Invoke-V2SmokeJob 'agent_inventory' ($container + @{
        operation = 'transfer'; direction = 'take'; item = 'minecraft:snow_block'; count = 3
    }))
    Assert-V2Smoke ((Get-V2SmokeItemCount (Get-V2SmokeInventory) 'minecraft:snow_block') -eq $snowBefore + 3) 'Take count mismatch'
    [void](Invoke-V2SmokeJob 'agent_inventory' ($container + @{
        operation = 'transfer'; direction = 'store'; item = 'minecraft:snow_block'; count = 2
    }))
    Assert-V2Smoke ((Get-V2SmokeItemCount (Get-V2SmokeInventory) 'minecraft:snow_block') -eq $snowBefore + 1) 'Store count mismatch'

    $gate = @{ target = 'block'; x = 200; y = 201; z = 198; advance = $false
        block = 'minecraft:oak_fence_gate'; expected_after_properties = @{ open = 'true' } }
    [void](Invoke-V2SmokeJob 'agent_interact' $gate)
    [void](Invoke-V2SmokeJob 'agent_click' @{
        button = 'right'; x = 200; y = 201; z = 198; block = 'minecraft:oak_fence_gate'
    })
    # The next normal use can reach open=true only if the raw click closed the gate.
    [void](Invoke-V2SmokeJob 'agent_interact' $gate)

    $placed = Invoke-V2SmokeJob 'agent_place_block' @{
        x = 202; y = 201; z = 198; dx = 1; block = 'minecraft:black_wool'; advance = $true
    }
    Assert-V2Smoke ($placed.progress.placed_blocks -eq 2) 'Placement count mismatch'
    $broken = Invoke-V2SmokeJob 'agent_break_block' @{
        x = 202; y = 201; z = 198; dx = 1; include_blocks = @('minecraft:black_wool'); advance = $true
    }
    Assert-V2Smoke ($broken.progress.broken_blocks -eq 2) 'Break count mismatch'
    $mined = Invoke-V2SmokeJob 'agent_break_block' @{
        x = 202; y = 201; z = 200; dx = 4; dy = 1; include_blocks = @('minecraft:dirt'); advance = $true
    }
    Assert-V2Smoke ($mined.progress.broken_blocks -eq 10) 'Continuous mining count mismatch'
    [void](Invoke-V2SmokeJob 'agent_run_script' @{
        source = 'for(let i=0;i<2;i++){move(x=205+i*2,y=201,z=200);} inventory(operation="inspect");'
    })
    $after = Get-V2SmokeState
    $p = $after.player.position
    Assert-V2Smoke ([Math]::Abs($p.x - 207.5) -lt 0.5 -and [Math]::Abs($p.y - 201) -lt 0.2 -and
        [Math]::Abs($p.z - 200.5) -lt 0.5 -and $after.player.health -ge $state.player.health) 'Script arrival/health mismatch'
    [void](Invoke-V2SmokeJob 'agent_input_sequence' @{
        steps = @(@{ inputs = @('sneak'); hold_ticks = 3; gap_ticks = 2; repeat = 2 })
    })
}

function Invoke-V2SmokeItemEntity {
    $observation = Invoke-V2SmokeTool 'agent_get_observation'
    $cow = @($observation.records | Where-Object entity_type -CEQ 'minecraft:cow' -ErrorAction SilentlyContinue)
    Assert-V2Smoke ($cow.Count -eq 1 -and $null -ne $cow[0].entity_ref) 'One visible fixture cow is required'
    $milk = Invoke-V2SmokeJob 'agent_interact' @{
        target = 'entity'; entity_ref = $cow[0].entity_ref; entity_type = 'minecraft:cow'
        item = 'minecraft:bucket'; result_item = 'minecraft:milk_bucket'
    }
    Assert-V2Smoke ($milk.result.confirmation -ceq 'server_held_item' -and $milk.result.effect_confirmed) 'Milking was not confirmed'
    $use = Invoke-V2SmokeJob 'agent_interact' @{
        target = 'item'; item = 'minecraft:milk_bucket'; result_item = 'minecraft:bucket'
    }
    Assert-V2Smoke ($use.result.server_processed -and $use.result.effect_confirmed -and
        $use.result.held_after.item -ceq 'minecraft:bucket') 'Item consumption was not confirmed'
}

function Invoke-V2SmokeStop([bool]$Hazard) {
    $inputs = @('sneak')
    if (-not $Hazard) { $inputs += 'forward' }
    $id = Start-V2SmokeJob 'agent_input_sequence' @{
        steps = @(@{ inputs = $inputs; hold_ticks = 1200 })
    }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    do {
        $running = Invoke-V2SmokeTool 'agent_get_action' @{ action_id = $id; wait_timeout_ms = 1000 }
        if ($running.state -ceq 'running' -and $running.progress.completed_operations -gt 0) { break }
        Assert-V2Smoke ($running.state -cin @('unconfirmed', 'queued', 'running')) 'Stop job terminated before exercising inputs'
    } while ($clock.Elapsed.TotalSeconds -lt 3)
    Assert-V2Smoke ($running.state -ceq 'running' -and $running.progress.completed_operations -gt 0) 'Stop test needs an executing job'
    if ($Hazard) {
        # The independent fixture controller applies public-api-v2-water-stop now.
        # No admin token or world mutation endpoint is given to this MCP client.
        $eventPath = Join-Path $ArtifactDirectory 'hazard-ready.json'
        $eventTemporary = Join-Path $ArtifactDirectory 'hazard-ready.tmp'
        [IO.File]::WriteAllText($eventTemporary, (ConvertTo-CompactJson @{
            action_id = $id; running = $running; world_session_id = $ExpectedWorldSession
        }), [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($eventTemporary, $eventPath)
        $terminal = Wait-V2SmokeJob $id 30
        Assert-V2Smoke ($terminal.state -ceq 'failed' -and $terminal.failure -ceq 'safety_interrupted') 'Expected hazard stop did not occur'
    } else {
        [void](Invoke-V2SmokeTool 'agent_cancel_action' @{ action_id = $id })
        $terminal = Wait-V2SmokeJob $id 15
        Assert-V2Smoke ($terminal.state -ceq 'cancelled') 'Cancellation did not terminate the job'
        Start-Sleep -Milliseconds 500
        $first = Get-V2SmokeState
        Start-Sleep -Milliseconds 500
        $second = Get-V2SmokeState
        $a = $first.player.position; $b = $second.player.position
        Assert-V2Smoke ([Math]::Abs($a.x - $b.x) + [Math]::Abs($a.z - $b.z) -lt 0.02) 'Movement continued after cancellation'
        $script:SmokeEvents.Add(@{ after_cancel_first = $first; after_cancel_second = $second })
    }
}

function Invoke-McmcpV2Smoke {
    Assert-V2Smoke (-not [string]::IsNullOrWhiteSpace($ExpectedWorldSession)) 'Expected world session is required'
    Assert-V2Smoke (-not [string]::IsNullOrWhiteSpace($ArtifactDirectory)) 'Artifact directory is required'
    Assert-V2Smoke (-not (Test-Path -LiteralPath $ArtifactDirectory)) 'Use a new artifact directory'
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $script:SmokeActive = $null
    $script:SmokeEvents = [Collections.Generic.List[object]]::new()
    $failure = $null
    $finalControl = $null
    try {
        $checked = Invoke-McmcpClient -TokenPath $TokenPath -Endpoint $Endpoint -Check
        Assert-V2Smoke ($checked.ok -and $checked.tool_count -eq 13) 'Unexpected public tool surface'
        $control = Invoke-V2SmokeTool 'agent_get_mcp_status'
        Assert-V2Smoke ($control.control_mode -ceq 'ready' -and $null -eq $control.running_action_id) 'MCP must be idle and READY'
        [void](Get-V2SmokeState)
        switch ($Phase) {
            'Core' { Invoke-V2SmokeCore }
            'ItemEntity' { Invoke-V2SmokeItemEntity }
            'Cancel' { Invoke-V2SmokeStop $false }
            'Hazard' {
                $p = (Get-V2SmokeState).player.position
                Assert-V2Smoke ([Math]::Floor($p.x) -eq 207 -and [Math]::Floor($p.y) -eq 201 -and
                    [Math]::Floor($p.z) -eq 200) 'Hazard test requires the designated cell'
                Invoke-V2SmokeStop $true
            }
        }
    } catch { $failure = $_.Exception.Message }
    finally {
        if ($null -ne $script:SmokeActive) {
            try {
                [void](Invoke-V2SmokeTool 'agent_cancel_action' @{ action_id = $script:SmokeActive })
                [void](Wait-V2SmokeJob $script:SmokeActive 15)
            } catch { $failure = 'Cleanup remains unconfirmed; inspect the recorded action ID' }
        }
        try {
            $finalControl = Invoke-V2SmokeTool 'agent_get_mcp_status'
            Assert-V2Smoke ($finalControl.world_session_id -ceq $ExpectedWorldSession -and
                $null -eq $finalControl.running_action_id -and $finalControl.control_mode -cne 'agent') 'Final control is not released'
        } catch { if ($null -eq $failure) { $failure = 'Final control readback failed' } }
        $result = [ordered]@{ status = $(if ($null -eq $failure) { 'passed' } else { 'failed' })
            phase = $Phase; failure = $failure; active_action_id = $script:SmokeActive
            final_control = $finalControl; events = @($script:SmokeEvents.ToArray()) }
        [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'result.json'),
            ($result | ConvertTo-Json -Depth 80), [Text.UTF8Encoding]::new($false))
    }
    return $result
}

if (-not $LibraryOnly) {
    $result = Invoke-McmcpV2Smoke
    $result | Select-Object status, phase, failure, active_action_id | ConvertTo-Json -Compress
    if ($result.status -cne 'passed') { exit 1 }
}
