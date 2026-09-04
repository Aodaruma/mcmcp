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
        $record = New-Surface -Block 'minecraft:oak_stairs' `
            -Position (New-Position $_.x $_.y $_.z) `
            -State (New-StairState $_.facing $_.shape) -Face 'up' `
            -PlacementItem 'minecraft:oak_stairs' `
            -PlacementStateRef ('psr_' + ('8' * 32))
        $record | Add-Member -NotePropertyName gate_id -NotePropertyValue $_.id
        $record
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
        observation = [pscustomobject]@{
            latest_frame_id = 'obs-{0:x16}' -f $script:MockFrameCounter
        }
        action = $null
    }
}

function Select-MockObservationRecords {
    param([Parameter(Mandatory)][object]$Arguments)
    $all = @($sourceRecords) + @($supportRecords)
    $all += @($targetRecords | Where-Object { $_.gate_id -cin @($script:PlacedIds) })
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
$script:ActionSequence = 0
$script:MockActions = @{}
$script:PlacedIds = @()
$script:MockFrameCounter = 1
$actionIds = @(
    '550e8400-e29b-41d4-a716-446655440070'
    '550e8400-e29b-41d4-a716-446655440071'
    '550e8400-e29b-41d4-a716-446655440072'
    '550e8400-e29b-41d4-a716-446655440073'
    '550e8400-e29b-41d4-a716-446655440074'
    '550e8400-e29b-41d4-a716-446655440075'
    '550e8400-e29b-41d4-a716-446655440076'
    '550e8400-e29b-41d4-a716-446655440077'
    '550e8400-e29b-41d4-a716-446655440078'
)
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            $script:MockFrameCounter++
            New-MockStairsState -Completed:$script:ActionCompleted
        }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-{0:x16}' -f $script:MockFrameCounter
                frame_completed_tick = 10L; visible_entities_truncated = $false
                records = @(Select-MockObservationRecords -Arguments $Arguments)
                next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $componentIndex = [Math]::Floor($script:ActionSequence / 3)
            $phase = $script:ActionSequence % 3
            $isApproach = $phase -eq 0
            $isFace = $phase -eq 1
            $expected = $script:StairComponents[$componentIndex]
            $expectedCount = @($expected.ids).Count
            $submitted = $Arguments.program.body[0]
            $pivot = @($targetDefinitions | Where-Object {
                    $_.id -ceq [string]$expected.pivot
                })[0]
            if ($isApproach) {
                if ($Arguments.program.body.Count -ne 1 -or
                    $submitted.op -cne 'approach_known_surface' -or
                    $submitted.id -cne "approach_stairs_$($expected.name)" -or
                    $submitted.id.Length -gt 32 -or
                    $submitted.expected_block -cne 'minecraft:smooth_stone' -or
                    [int]$submitted.target.x -ne [int]$pivot.x -or
                    [int]$submitted.target.y -ne ([int]$pivot.y - 1) -or
                    [int]$submitted.target.z -ne [int]$pivot.z) {
                    throw 'mock received an invalid Gate D approach Action'
                }
            } elseif ($isFace) {
                if ($Arguments.program.body.Count -ne 1 -or
                    $submitted.op -cne 'face_known_block_face' -or
                    $submitted.id -cne "face_directional_stairs_$($expected.name)" -or
                    $submitted.expected_block -cne 'minecraft:smooth_stone' -or
                    $submitted.face -cne 'up' -or
                    [int]$submitted.target.x -ne [int]$pivot.x -or
                    [int]$submitted.target.y -ne ([int]$pivot.y - 1) -or
                    [int]$submitted.target.z -ne [int]$pivot.z) {
                    throw 'mock received an invalid Gate D face Action'
                }
            } elseif ($Arguments.program.body.Count -ne 1 -or
                $submitted.op -cne 'apply_known_block_plan' -or
                $submitted.id -cne "directional_stairs_$($expected.name)" -or
                $submitted.entries.Count -ne $expectedCount -or
                $submitted.transform.rotation -ne 90 -or
                $submitted.transform.mirror -cne 'x') {
                throw 'mock received an invalid Gate D build Action'
            }
            $actionId = $actionIds[$script:ActionSequence]
            $script:MockActions[$actionId] = [pscustomobject]@{
                kind = if ($isApproach) { 'approach' } elseif ($isFace) { 'face' } else { 'build' }
                count = if ($isApproach -or $isFace) { 0 } else { $expectedCount }
                node = $submitted.id
                ids = if ($isApproach -or $isFace) { @() } else { @($expected.ids) }
            }
            $script:ActionSequence++
            [pscustomobject]@{ schema_version = 1; action_id = $actionId; state = 'queued' }
        }
        'agent_get_action' {
            $actionId = [string](Get-ObjectProperty $Arguments 'action_id')
            $action = $script:MockActions[$actionId]
            if ($null -eq $action) { throw 'mock received an unknown Gate D action id' }
            if ($script:ActionSequence -eq ($script:StairComponents.Count * 3) -and
                $actionId -ceq $actionIds[-1]) { $script:ActionCompleted = $true }
            if ($action.kind -ceq 'build') {
                $script:PlacedIds = @($script:PlacedIds) + @($action.ids) | Select-Object -Unique
            }
            $count = [int]$action.count
            $nodeId = [string]$action.node
            $isApproach = $action.kind -ceq 'approach'
            $isFace = $action.kind -ceq 'face'
            [pscustomobject]@{
                schema_version = 1; action_id = $actionId; state = 'succeeded'
                progress = [pscustomobject]@{
                    executed_nodes = 1; total_node_upper_bound = 1
                    distance_travelled = if ($isApproach) { 3.25 } else { 0 }
                    camera_degrees = if ($isApproach) { 0 } elseif ($isFace) { 30 } else { 160 }
                    interactions = 0
                    blocks_broken = 0; blocks_placed = $count
                }
                failure = $null
                trace = if ($isApproach -or $isFace) {
                    @(
                        [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = $nodeId }
                        [pscustomobject]@{ tick = 8; event = 'NODE_COMPLETED'; detail = $nodeId }
                        [pscustomobject]@{ tick = 8; event = 'SUCCEEDED'; detail = 'succeeded' }
                    )
                } else {
                    @(
                        [pscustomobject]@{ tick = 8; event = 'NODE_STARTED'; detail = $nodeId }
                        [pscustomobject]@{
                            tick = 300; event = 'NODE_EVIDENCE'
                            detail = "construction_complete=$count,server_confirmed=$count"
                        }
                        [pscustomobject]@{ tick = 300; event = 'NODE_COMPLETED'; detail = $nodeId }
                        [pscustomobject]@{ tick = 300; event = 'SUCCEEDED'; detail = 'succeeded' }
                    )
                }
            }
        }
        default { throw "unexpected Gate D mock tool: $Tool" }
    }
}

$initial = New-MockStairsState -Completed:$false
$sources = Get-DirectionalStairSources -State $initial
$foundation = Select-StairMatrixFoundation -State $initial
$component = $script:StairComponents[1]
$componentFoundation = Refresh-StairComponentFoundation `
    -State $initial -Baseline $foundation -Component $component
$request = New-DirectionalStairsActionRequest `
    -Sources $sources -Foundation $componentFoundation -Component $component
$approachRequest = New-DirectionalStairsApproachRequest `
    -State $initial -Foundation $componentFoundation -Component $component
$faceRequest = New-DirectionalStairsFaceRequest `
    -Foundation $componentFoundation -Component $component
$approachNode = $approachRequest.program.body[0]
$faceNode = $faceRequest.program.body[0]
$node = $request.program.body[0]
Assert-True ($approachNode.op -ceq 'approach_known_surface' -and
    $approachNode.target.x -eq 202 -and $approachNode.target.y -eq 199 -and
    $approachNode.target.z -eq 194 -and
    $approachNode.expected_block -ceq 'minecraft:smooth_stone') `
    'gate did not approach the delivered pivot support'
Assert-True ($approachRequest.program.capabilities.Count -eq 1 -and
    $approachRequest.program.capabilities[0] -ceq 'movement' -and
    $approachRequest.budget.max_duration_ms -eq 30000 -and
    $approachRequest.budget.max_ticks -eq 600 -and
    $approachRequest.budget.max_distance_blocks -eq 32 -and
    $approachRequest.budget.max_camera_degrees -eq 0 -and
    $approachRequest.budget.max_interactions -eq 0 -and
    $approachRequest.budget.max_blocks_broken -eq 0 -and
    $approachRequest.budget.max_blocks_placed -eq 0) `
    'approach Action is not bounded to movement-only policy limits'
Assert-True ($faceNode.op -ceq 'face_known_block_face' -and
    $faceNode.target.x -eq 202 -and $faceNode.target.y -eq 199 -and
    $faceNode.target.z -eq 194 -and $faceNode.face -ceq 'up' -and
    $faceNode.expected_block -ceq 'minecraft:smooth_stone') `
    'gate did not pre-aim at the delivered support face'
Assert-True ($node.op -ceq 'apply_known_block_plan') `
    'gate did not use the normal construction Action'
Assert-True ($node.transform.rotation -eq 90 -and $node.transform.mirror -ceq 'x') `
    'rotation/mirror contract changed'
Assert-True ($node.entries.Count -eq 2) 'inner component does not contain exactly two stairs'
Assert-True ($node.entries[0].placement_state_ref -ceq $northInnerRef) `
    'inner placement reference was transformed'
Assert-True ($node.entries[1].placement_state_ref -ceq $westStraightRef) `
    'companion placement reference changed'
Assert-True ($request.budget.max_duration_ms -eq 75000 -and
    $request.budget.max_ticks -eq 1500 -and
    $request.budget.max_camera_degrees -eq 400 -and
    $request.budget.max_blocks_placed -eq 2 -and
    $request.budget.max_distance_blocks -eq 0) `
    'five-entry construction budget is not exact'
foreach ($entry in $node.entries) {
    $support = $componentFoundation.supports[$entry.id]
    Assert-True ([object]::ReferenceEquals(
            (Get-ObjectProperty $support 'position'), $entry.support.position)) `
        "support position was transformed for $($entry.id)"
    Assert-True ([object]::ReferenceEquals(
            (Get-ObjectProperty $support 'state'), $entry.support.expected_state)) `
        "support state was transformed for $($entry.id)"
}

$script:MockActions[$actionIds[0]] = [pscustomobject]@{
    kind = 'approach'; count = 0; node = 'approach_stairs_inner'; ids = @()
}
$validApproachTerminal = & $script:ToolTransport 'agent_get_action' `
    ([ordered]@{ action_id = $actionIds[0] })
$approachProof = Assert-DirectionalStairsApproachTerminalProof `
    -Terminal $validApproachTerminal -MaximumDistance 32
Assert-True ($approachProof.proof_scope -ceq 'succeeded_terminal_and_movement_budget_only' -and
    -not [bool]$approachProof.reach_inferred_from_distance -and
    [bool]$approachProof.fresh_support_admission_required) `
    'approach distance was incorrectly represented as interaction-reach proof'
$movingCamera = $validApproachTerminal.PSObject.Copy()
$movingCamera.progress = $validApproachTerminal.progress.PSObject.Copy()
$movingCamera.progress.camera_degrees = 1
Assert-Throws { Assert-DirectionalStairsApproachTerminalProof `
        -Terminal $movingCamera -MaximumDistance 32 } `
    'camera motion was accepted in the movement-only approach Action'

$script:MockActions[$actionIds[0]] = [pscustomobject]@{
    kind = 'build'; count = 2; node = 'directional_stairs_inner'
    ids = @('inner_corner', 'inner_companion')
}
$validTerminal = & $script:ToolTransport 'agent_get_action' `
    ([ordered]@{ action_id = $actionIds[0] })
[void](Assert-DirectionalStairsTerminalProof -Terminal $validTerminal `
        -ExpectedPlacements 2 -ExpectedNodeId 'directional_stairs_inner')
$emptyTrace = $validTerminal.PSObject.Copy()
$emptyTrace.trace = @()
Assert-Throws { Assert-DirectionalStairsTerminalProof -Terminal $emptyTrace `
        -ExpectedPlacements 2 -ExpectedNodeId 'directional_stairs_inner' } `
    'empty trace was accepted as server acknowledgement proof'
$wrongCount = $validTerminal.PSObject.Copy()
$wrongCount.progress = $validTerminal.progress.PSObject.Copy()
$wrongCount.progress.blocks_placed = 4
Assert-Throws { Assert-DirectionalStairsTerminalProof -Terminal $wrongCount `
        -ExpectedPlacements 2 -ExpectedNodeId 'directional_stairs_inner' } `
    'four placements were accepted as Gate D completion'
$script:ActionCompleted = $false
$script:ActionSequence = 0
$script:MockActions = @{}
$script:PlacedIds = @()
$script:MockFrameCounter = 1

try {
    $result = Invoke-McmcpDirectionalStairsCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'building-gate-d-directional-stairs') `
        'Gate D did not pass'
    Assert-True ([bool]$result.gate_result.lifecycle.accepted_equals_terminal) `
        'accepted==terminal was not proven'
    Assert-True ($result.gate_result.lifecycle.accepted -eq 9 -and
        $result.gate_result.lifecycle.fresh_observation_barriers -eq 6 -and
        $result.gate_result.approach_terminal_proof.Count -eq 3 -and
        $result.gate_result.face_terminal_proof.Count -eq 3) `
        'Gate D did not prove all three approach/face/build lifecycle trios'
    Assert-True (@($result.gate_result.approach_terminal_proof | Where-Object {
                [bool]$_.reach_inferred_from_distance -or
                -not [bool]$_.fresh_support_admission_required -or
                $_.proof_scope -cne 'succeeded_terminal_and_movement_budget_only'
            }).Count -eq 0) `
        'Gate D promoted approach distance into an unsupported reach guarantee'
    Assert-True ((@($result.gate_result.terminal_proof | ForEach-Object {
                    $_.server_confirmed_placements }) | Measure-Object -Sum).Sum -eq 5) `
        'server acknowledgement counts do not sum to five'
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
