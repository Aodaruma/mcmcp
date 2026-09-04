[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpDirectionalStairsCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-stairs-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "directional stairs gate mock failed: $Message" }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$Message)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

function New-Position {
    param([int]$X, [int]$Y, [int]$Z)
    [pscustomobject]@{ dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z }
}

function New-StairState {
    param([string]$Facing, [string]$Shape)
    [pscustomobject]@{
        block = 'minecraft:oak_stairs'
        properties = [pscustomobject]@{
            facing = $Facing; half = 'bottom'; shape = $Shape; waterlogged = 'false'
        }
    }
}

function New-Surface {
    param(
        [string]$Block,
        [object]$Position,
        [object]$State,
        [string]$Face,
        [AllowNull()][string]$PlacementItem,
        [AllowNull()][string]$PlacementStateRef
    )
    [pscustomobject]@{
        kind = 'visible_surface'; block = $Block; position = $Position; face = $Face
        state = $State; placement_item = $PlacementItem
        placement_state_ref = $PlacementStateRef
        observed_tick = 10L; world_revision = 1L
    }
}

$northStraightRef = 'psr_' + ('1' * 32)
$northInnerRef = 'psr_' + ('2' * 32)
$northOuterRef = 'psr_' + ('3' * 32)
$westStraightRef = 'psr_' + ('4' * 32)
$sourceRecords = @(
    (New-Surface -Block 'minecraft:oak_stairs' -Position (New-Position 199 203 192) `
        -State (New-StairState 'north' 'straight') -Face 'up' `
        -PlacementItem 'minecraft:oak_stairs' -PlacementStateRef $northStraightRef)
    (New-Surface -Block 'minecraft:oak_stairs' -Position (New-Position 201 203 192) `
        -State (New-StairState 'north' 'inner_left') -Face 'up' `
        -PlacementItem 'minecraft:oak_stairs' -PlacementStateRef $northInnerRef)
    (New-Surface -Block 'minecraft:oak_stairs' -Position (New-Position 201 203 193) `
        -State (New-StairState 'west' 'straight') -Face 'up' `
        -PlacementItem 'minecraft:oak_stairs' -PlacementStateRef $westStraightRef)
    (New-Surface -Block 'minecraft:oak_stairs' -Position (New-Position 203 203 193) `
        -State (New-StairState 'north' 'outer_left') -Face 'up' `
        -PlacementItem 'minecraft:oak_stairs' -PlacementStateRef $northOuterRef)
    (New-Surface -Block 'minecraft:oak_stairs' -Position (New-Position 203 203 192) `
        -State (New-StairState 'west' 'straight') -Face 'up' `
        -PlacementItem 'minecraft:oak_stairs' -PlacementStateRef $westStraightRef)
)
$supportCoordinates = @(
    [pscustomobject]@{ x = 202; y = 199; z = 196 }
    [pscustomobject]@{ x = 202; y = 199; z = 194 }
    [pscustomobject]@{ x = 201; y = 199; z = 194 }
    [pscustomobject]@{ x = 201; y = 199; z = 192 }
    [pscustomobject]@{ x = 202; y = 199; z = 192 }
)
$supportRecords = @($supportCoordinates | ForEach-Object {
        New-Surface -Block 'minecraft:smooth_stone' `
            -Position (New-Position $_.x $_.y $_.z) `
            -State ([pscustomobject]@{
                block = 'minecraft:smooth_stone'; properties = [pscustomobject]@{}
            }) -Face 'up' -PlacementItem 'minecraft:smooth_stone' `
            -PlacementStateRef ('psr_' + ('9' * 32))
    })
$targetDefinitions = @(
    [pscustomobject]@{
        id = 'straight'; x = 202; y = 200; z = 196; facing = 'east'; shape = 'straight'
    }
    [pscustomobject]@{
        id = 'inner_corner'; x = 202; y = 200; z = 194
        facing = 'east'; shape = 'inner_right'
    }
    [pscustomobject]@{
        id = 'inner_companion'; x = 201; y = 200; z = 194
        facing = 'south'; shape = 'straight'
    }
    [pscustomobject]@{
        id = 'outer_corner'; x = 201; y = 200; z = 192
        facing = 'east'; shape = 'outer_right'
    }
    [pscustomobject]@{
        id = 'outer_companion'; x = 202; y = 200; z = 192
        facing = 'south'; shape = 'straight'
    }
)
$targetRecords = @($targetDefinitions | ForEach-Object {
        New-Surface -Block 'minecraft:oak_stairs' `
            -Position (New-Position $_.x $_.y $_.z) `
            -State (New-StairState $_.facing $_.shape) -Face 'up' `
            -PlacementItem 'minecraft:oak_stairs' `
            -PlacementStateRef ('psr_' + ('8' * 32))
    })

function New-MockStairsState {
    param([Parameter(Mandatory)][bool]$Completed)
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 10L; world_revision = 1L
            position = [pscustomobject]@{ x = 201.5; y = 200.0; z = 197.5 }
            yaw = 180.0; pitch = 24.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @([pscustomobject]@{
                item = 'minecraft:oak_stairs'; count = if ($Completed) { 3 } else { 8 }
            })
        standard_potions = @()
        recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-1234567890abcdef' }
        action = $null
    }
}

function Select-MockObservationRecords {
    param([Parameter(Mandatory)][object]$Arguments)
    $all = @($sourceRecords) + @($supportRecords)
    if ($script:ActionCompleted) { $all += @($targetRecords) }
    $filter = Get-ObjectProperty $Arguments 'filter'
    if ($null -eq $filter) { return $all }
    $ids = @((Get-ObjectProperty $filter 'block_ids'))
    $bounds = Get-ObjectProperty $filter 'position_bounds'
    $faceFilter = Get-ObjectProperty $filter 'faces'
    [object[]]$faces = @()
    if ($null -ne $faceFilter) { $faces = @($faceFilter) }
    $selected = @($all | Where-Object {
            $record = $_
            $position = Get-ObjectProperty $record 'position'
            ((Get-ObjectProperty $record 'block') -cin $ids) -and
            [int](Get-ObjectProperty $position 'x') -ge [int](Get-ObjectProperty $bounds 'min_x') -and
            [int](Get-ObjectProperty $position 'x') -le [int](Get-ObjectProperty $bounds 'max_x') -and
            [int](Get-ObjectProperty $position 'y') -ge [int](Get-ObjectProperty $bounds 'min_y') -and
            [int](Get-ObjectProperty $position 'y') -le [int](Get-ObjectProperty $bounds 'max_y') -and
            [int](Get-ObjectProperty $position 'z') -ge [int](Get-ObjectProperty $bounds 'min_z') -and
            [int](Get-ObjectProperty $position 'z') -le [int](Get-ObjectProperty $bounds 'max_z') -and
            ($faces.Count -eq 0 -or (Get-ObjectProperty $record 'face') -cin $faces)
        })
    return $selected
}

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ActionCompleted = $false
$actionId = '550e8400-e29b-41d4-a716-446655440070'
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' { New-MockStairsState -Completed:$script:ActionCompleted }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1; frame_id = 'obs-1234567890abcdef'
                frame_completed_tick = 10L; visible_entities_truncated = $false
                records = @(Select-MockObservationRecords -Arguments $Arguments)
                next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $submitted = $Arguments.program.body[0]
            if ($submitted.op -cne 'apply_known_block_plan' -or
                $submitted.entries.Count -ne 5 -or
                $submitted.transform.rotation -ne 90 -or
                $submitted.transform.mirror -cne 'x') {
                throw 'mock received an invalid Gate D Action'
            }
            [pscustomobject]@{ schema_version = 1; action_id = $actionId; state = 'queued' }
        }
        'agent_get_action' {
            $script:ActionCompleted = $true
            [pscustomobject]@{
                schema_version = 1; action_id = $actionId; state = 'succeeded'
                progress = [pscustomobject]@{
                    executed_nodes = 1; total_node_upper_bound = 1
                    distance_travelled = 0; camera_degrees = 320; interactions = 0
                    blocks_broken = 0; blocks_placed = 5
                }
                failure = $null
                trace = @(
                    [pscustomobject]@{
                        tick = 0; event = 'NODE_STARTED'; detail = 'directional_stairs_matrix'
                    }
                    [pscustomobject]@{
                        tick = 300; event = 'NODE_EVIDENCE'
                        detail = 'construction_complete=5,server_confirmed=5'
                    }
                    [pscustomobject]@{
                        tick = 300; event = 'NODE_COMPLETED'; detail = 'directional_stairs_matrix'
                    }
                    [pscustomobject]@{ tick = 300; event = 'SUCCEEDED'; detail = 'succeeded' }
                )
            }
        }
        default { throw "unexpected Gate D mock tool: $Tool" }
    }
}

$initial = New-MockStairsState -Completed:$false
$sources = Get-DirectionalStairSources -State $initial
$foundation = Select-StairMatrixFoundation -State $initial
$request = New-DirectionalStairsActionRequest -Sources $sources -Foundation $foundation
$node = $request.program.body[0]
Assert-True ($node.op -ceq 'apply_known_block_plan') `
    'gate did not use the normal construction Action'
Assert-True ($node.transform.rotation -eq 90 -and $node.transform.mirror -ceq 'x') `
    'rotation/mirror contract changed'
Assert-True ($node.entries.Count -eq 5) 'plan does not contain exactly five stairs'
Assert-True ($node.entries[0].placement_state_ref -ceq $northStraightRef) `
    'straight placement reference was transformed'
Assert-True ($node.entries[1].placement_state_ref -ceq $northInnerRef) `
    'inner placement reference was transformed'
Assert-True ($node.entries[3].placement_state_ref -ceq $northOuterRef) `
    'outer placement reference was transformed'
Assert-True ($node.entries[2].placement_state_ref -ceq $westStraightRef -and
    $node.entries[4].placement_state_ref -ceq $westStraightRef) `
    'companion placement reference changed'
Assert-True ($request.budget.max_duration_ms -eq 75000 -and
    $request.budget.max_ticks -eq 1500 -and
    $request.budget.max_camera_degrees -eq 400 -and
    $request.budget.max_blocks_placed -eq 5 -and
    $request.budget.max_distance_blocks -eq 0) `
    'five-entry construction budget is not exact'
foreach ($entry in $node.entries) {
    $support = $foundation.supports[$entry.id]
    Assert-True ([object]::ReferenceEquals(
            (Get-ObjectProperty $support 'position'), $entry.support.position)) `
        "support position was transformed for $($entry.id)"
    Assert-True ([object]::ReferenceEquals(
            (Get-ObjectProperty $support 'state'), $entry.support.expected_state)) `
        "support state was transformed for $($entry.id)"
}

$validTerminal = & $script:ToolTransport 'agent_get_action' ([ordered]@{})
[void](Assert-DirectionalStairsTerminalProof -Terminal $validTerminal)
$emptyTrace = $validTerminal.PSObject.Copy()
$emptyTrace.trace = @()
Assert-Throws { Assert-DirectionalStairsTerminalProof -Terminal $emptyTrace } `
    'empty trace was accepted as server acknowledgement proof'
$wrongCount = $validTerminal.PSObject.Copy()
$wrongCount.progress = $validTerminal.progress.PSObject.Copy()
$wrongCount.progress.blocks_placed = 4
Assert-Throws { Assert-DirectionalStairsTerminalProof -Terminal $wrongCount } `
    'four placements were accepted as Gate D completion'
$script:ActionCompleted = $false

try {
    $result = Invoke-McmcpDirectionalStairsCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'building-gate-d-directional-stairs') `
        'Gate D did not pass'
    Assert-True ([bool]$result.gate_result.lifecycle.accepted_equals_terminal) `
        'accepted==terminal was not proven'
    Assert-True ($result.gate_result.terminal_proof.server_confirmed_placements -eq 5) `
        'server acknowledgement count was not retained'
    Assert-True ($result.gate_result.inventory_delta -eq -5) `
        'inventory delta was not exactly -5'
    Assert-True ($result.gate_result.exact_targets.Count -eq 5) `
        'exact target oracle lost a stair cell'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) `
        'input release was not proven'
    foreach ($name in @(
            'gate-events.jsonl', 'gate-result.json', 'external-oracle-manifest.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "missing artifact: $name"
    }
    $oracle = Get-Content -LiteralPath `
        (Join-Path $artifactDirectory 'external-oracle-manifest.json') -Raw |
        ConvertFrom-Json
    Assert-True ($oracle.expected_changed_cell_count -eq 5 -and
        [bool]$oracle.reject_unlisted_changes) `
        'offline exact-state oracle is incomplete'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP directional stairs capability gate mock tests passed.'
