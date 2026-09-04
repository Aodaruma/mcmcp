[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpCobblestoneGeneratorCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-cobblestone-generator-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "cobblestone generator gate mock failed: $Message" }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$Message)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

function New-MockCobblestoneSurface {
    param([Parameter(Mandatory)][long]$Revision)
    [pscustomobject]@{
        kind = 'visible_surface'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 199; y = 201; z = 200
        }
        face = 'up'
        block = 'minecraft:cobblestone'
        state = [pscustomobject]@{
            block = 'minecraft:cobblestone'; properties = [pscustomobject]@{}
        }
        placement_item = 'minecraft:cobblestone'
        placement_state_ref = 'psr_' + ('a' * 32)
        shape_class = 'opaque'
        eye_origin = [pscustomobject]@{ x = 199.5; y = 202.62; z = 199.5 }
        observed_tick = 100L + $Revision
        world_revision = $Revision
        provenance = 'visual'
    }
}

function New-MockCobblestoneDrop {
    param([Parameter(Mandatory)][long]$Revision)
    $position = [pscustomobject]@{
        dimension = 'minecraft:overworld'; x = 199.5; y = 201.1; z = 200.1
    }
    [pscustomobject]@{
        kind = 'visible_entity'; entity_type = 'minecraft:item'
        entity_ref = 'abcdefghijklmnopqrstuvwx'
        displayed_item = 'minecraft:cobblestone'; position = $position
        velocity = [pscustomobject]@{ x = 0.0; y = 0.0; z = 0.0 }
        aabb = [pscustomobject]@{
            min_x = 199.375; min_y = 201.0; min_z = 199.975
            max_x = 199.625; max_y = 201.25; max_z = 200.225
        }
        hazard_class = 'passive'
        eye_origin = [pscustomobject]@{ x = 199.5; y = 202.62; z = 199.5 }
        observed_tick = 100L + $Revision; world_revision = $Revision
        provenance = 'OMNIDIRECTIONAL_VISUAL'
    }
}

function New-MockCobblestoneState {
    param(
        [Parameter(Mandatory)][int]$CobblestoneCount,
        [long]$FrameSequence = $CobblestoneCount
    )
    $inventory = [Collections.Generic.List[object]]::new()
    $inventory.Add([pscustomobject]@{ item = 'minecraft:iron_pickaxe'; count = 1 })
    if ($CobblestoneCount -gt 0) {
        $inventory.Add([pscustomobject]@{
                item = 'minecraft:cobblestone'; count = $CobblestoneCount
            })
    }
    $frameId = 'obs-' + $FrameSequence.ToString('x16')
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'
            client_tick = 100L + $CobblestoneCount
            world_revision = 20L + $CobblestoneCount
            position = [pscustomobject]@{ x = 199.5; y = 201.0; z = 199.5 }
            yaw = 0.0; pitch = 8.0; health = 20.0; absorption = 0.0
            hunger = 17; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @($inventory)
        standard_potions = @(); recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = $frameId }
        action = $null
    }
}

$surface = New-MockCobblestoneSurface -Revision 20
$request = New-CobblestoneBreakRequest -Surface $surface -MinimumInventoryCount 1
$node = $request.program.body[0]
Assert-True ([object]::ReferenceEquals($surface.position, $node.target)) `
    'request did not retain delivered position'
Assert-True ([object]::ReferenceEquals($surface.state, $node.expected_state)) `
    'request did not retain delivered exact state'
Assert-True ($node.op -ceq 'break_known_block') 'request used the wrong opcode'
Assert-True ($node.tool_item -ceq 'minecraft:iron_pickaxe') 'request used the wrong tool'
Assert-True ($node.expected_drop -ceq 'minecraft:cobblestone') `
    'request used the wrong pickup goal'
Assert-True ($request.program.capabilities.Count -eq 2 -and
    $request.program.capabilities[0] -ceq 'camera' -and
    $request.program.capabilities[1] -ceq 'block_break') `
    'request did not declare exactly camera+block_break'
Assert-True ($request.budget.max_blocks_broken -eq 1 -and
    $request.budget.max_distance_blocks -eq 0 -and
    $request.budget.max_interactions -eq 0 -and
    $request.budget.max_blocks_placed -eq 0) `
    'request budget is not a stationary single break'
Assert-True ($script:CobbleMaximumAttempts -eq 16) 'attempt cap is not exactly sixteen'

$generatorFaceRequest = New-KnownCobblestoneGeneratorFaceRequest -Surface $surface
$generatorFaceNode = $generatorFaceRequest.program.body[0]
Assert-True ($generatorFaceNode.op -ceq 'face_known_position' -and
    [object]::ReferenceEquals($surface.position, $generatorFaceNode.target) -and
    $generatorFaceRequest.program.capabilities.Count -eq 1 -and
    $generatorFaceRequest.program.capabilities[0] -ceq 'camera' -and
    $generatorFaceRequest.budget.max_camera_degrees -eq 360) `
    'known generator face request is not delivery-backed and camera-only'
$generatorRequest = New-KnownCobblestoneGeneratorRequest -Surface $surface
$generatorNode = $generatorRequest.program.body[0]
Assert-True ($generatorNode.op -ceq 'operate_known_cobblestone_generator' -and
    [object]::ReferenceEquals($surface.position, $generatorNode.target) -and
    [object]::ReferenceEquals($surface.state, $generatorNode.expected_state)) `
    'known generator request did not retain its exact delivered target evidence'
Assert-True ($generatorNode.minimum_inventory_count -eq 8 -and
    $generatorNode.max_breaks -eq 8 -and
    $generatorNode.regeneration_wait_ticks -eq 100 -and
    $generatorNode.max_operation_duration_ticks -eq 3600) `
    'known generator request does not expose the finite eight-break acceptance slice'
Assert-True ($generatorRequest.program.capabilities.Count -eq 1 -and
    $generatorRequest.program.capabilities[0] -ceq 'block_break' -and
    $generatorRequest.budget.max_ticks -eq 3600 -and
    $generatorRequest.budget.max_duration_ms -eq 180000 -and
    $generatorRequest.budget.max_blocks_broken -eq 8 -and
    $generatorRequest.budget.max_camera_degrees -eq 0) `
    'known generator request budget or capability declaration is not closed'

function New-MockBreakTerminal {
    param(
        [ValidateRange(1, 8)][int]$MinimumInventoryCount,
        [ValidateRange(1, 16)][int]$Attempt = $MinimumInventoryCount,
        [ValidateRange(1, 32)][int]$ActionSequence = $Attempt,
        [string]$Verification = 'confirmed'
    )
    $actionId = '550e8400-e29b-41d4-a716-' + $ActionSequence.ToString('000000000000')
    [pscustomobject]@{
        schema_version = 1; action_id = $actionId; state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 3
            interactions = 0; blocks_broken = 1; blocks_placed = 0
        }
        failure = $null
        trace = @(
            [pscustomobject]@{
                tick = 0; event = 'NODE_STARTED'; detail = "break_cobblestone_$MinimumInventoryCount"
            },
            [pscustomobject]@{
                tick = 4; event = 'NODE_COMPLETED'; detail = "break_cobblestone_$MinimumInventoryCount"
            },
            [pscustomobject]@{ tick = 4; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @([pscustomobject]@{
                seq = 1; node_id = "break_cobblestone_$MinimumInventoryCount"; kind = 'block_break'
                subject = 'block:minecraft:overworld:199,201,200'
                observed_before = [pscustomobject]@{
                    block = 'minecraft:cobblestone'; properties = [pscustomobject]@{}
                    expected_drop = 'minecraft:cobblestone'
                    minimum_inventory_count = $MinimumInventoryCount
                }
                observed_after = [pscustomobject]@{
                    block = 'minecraft:air'; properties = [pscustomobject]@{}
                    inventory_count = $MinimumInventoryCount
                }
                verification = $Verification
                client_tick = 100L + $Attempt; world_revision = 20L + $Attempt
            })
        partial = [pscustomobject]@{
            has_confirmed_effects = $true; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}
        template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

function New-MockKnownGeneratorFaceTerminal {
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-000000000001'
        state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 42
            interactions = 0; blocks_broken = 0; blocks_placed = 0
        }
        failure = $null; trace = @(); effects = @()
        partial = [pscustomobject]@{
            has_confirmed_effects = $false; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

function New-MockKnownGeneratorTerminal {
    $effects = [Collections.Generic.List[object]]::new()
    for ($cycle = 1; $cycle -le 8; $cycle++) {
        $effects.Add([pscustomobject]@{
                seq = $cycle
                node_id = 'operate_cobblestone_generator'
                kind = 'block_break'
                subject = 'block:minecraft:overworld:199,201,200'
                observed_before = [pscustomobject]@{
                    block = 'minecraft:cobblestone'
                    properties = [pscustomobject]@{}
                    cycle = $cycle
                }
                observed_after = [pscustomobject]@{
                    block = 'minecraft:air'
                    properties = [pscustomobject]@{}
                    inventory_count = $cycle
                }
                verification = 'confirmed'
                client_tick = 100L + 5L * $cycle
                world_revision = 20L + $cycle
            })
    }
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-000000000002'
        state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 0
            interactions = 0; blocks_broken = 8; blocks_placed = 0
        }
        failure = $null
        trace = @(
            [pscustomobject]@{
                tick = 0; event = 'NODE_STARTED'; detail = 'operate_cobblestone_generator'
            },
            [pscustomobject]@{
                tick = 40; event = 'NODE_COMPLETED'; detail = 'operate_cobblestone_generator'
            },
            [pscustomobject]@{ tick = 40; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @($effects)
        partial = [pscustomobject]@{
            has_confirmed_effects = $true; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}
        template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

function New-MockLostDropTerminal {
    param(
        [ValidateRange(1, 8)][int]$MinimumInventoryCount,
        [ValidateRange(1, 16)][int]$Attempt,
        [ValidateRange(1, 32)][int]$ActionSequence = $Attempt
    )
    $terminal = New-MockBreakTerminal -MinimumInventoryCount $MinimumInventoryCount `
        -Attempt $Attempt -ActionSequence $ActionSequence
    $terminal.state = 'failed'
    $terminal.progress.executed_nodes = 0
    $terminal.progress.blocks_broken = 0
    $terminal.failure = [pscustomobject]@{
        code = 'SERVER_DENIED_OR_DESYNC'; recoverable = $true
        evidence = @('break_not_server_confirmed')
    }
    $terminal.trace = @(
        [pscustomobject]@{
            tick = 0; event = 'NODE_STARTED'; detail = "break_cobblestone_$MinimumInventoryCount"
        },
        [pscustomobject]@{
            tick = 8; event = 'FAILED'; detail = 'break_not_server_confirmed'
        }
    )
    $terminal.effects[0].observed_after = [pscustomobject]@{
        block = 'minecraft:air'; properties = [pscustomobject]@{}
    }
    $terminal.partial.remaining_node_upper_bound = 1
    $terminal.partial.interrupted_node_id = "break_cobblestone_$MinimumInventoryCount"
    $terminal.partial.resume_requires_reobservation = $true
    return $terminal
}

function New-MockCobblestoneCollectTerminal {
    param(
        [ValidateRange(1, 16)][int]$Attempt,
        [ValidateRange(1, 32)][int]$ActionSequence,
        [ValidateRange(0, 7)][int]$InventoryBefore
    )
    $inventoryAfter = $InventoryBefore + 1
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-' + $ActionSequence.ToString('000000000000')
        state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 0
            interactions = 0; blocks_broken = 0; blocks_placed = 0
        }
        failure = $null
        trace = @(
            [pscustomobject]@{
                tick = 0; event = 'NODE_STARTED'; detail = "collect_cobble_$Attempt"
            },
            [pscustomobject]@{
                tick = 3; event = 'NODE_EVIDENCE'
                detail = "item_pickup=minecraft:cobblestone,inventory_before=$InventoryBefore,inventory_after=$inventoryAfter"
            },
            [pscustomobject]@{ tick = 3; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @()
        partial = [pscustomobject]@{
            has_confirmed_effects = $false; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

[void](Assert-CobblestoneBreakTerminal `
        -Terminal (New-MockBreakTerminal -MinimumInventoryCount 1) `
        -Attempt 1 -MinimumInventoryCount 1)
$noEffect = New-MockBreakTerminal -MinimumInventoryCount 1
$noEffect.effects = @()
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $noEffect `
        -Attempt 1 -MinimumInventoryCount 1 } `
    'terminal without an effect was accepted'
$unknownEffect = New-MockBreakTerminal -MinimumInventoryCount 1 -Verification 'unknown'
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $unknownEffect `
        -Attempt 1 -MinimumInventoryCount 1 } `
    'unknown break effect was accepted'
$moved = New-MockBreakTerminal -MinimumInventoryCount 1
$moved.progress.distance_travelled = 0.1
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $moved `
        -Attempt 1 -MinimumInventoryCount 1 } `
    'moving break terminal was accepted'
$lostDrop = New-MockLostDropTerminal -MinimumInventoryCount 1 -Attempt 1
Assert-True (Test-CobblestoneLostDropTerminal -Terminal $lostDrop `
        -MinimumInventoryCount 1) 'exact lost-drop terminal was rejected'
$lostWithInventory = New-MockLostDropTerminal -MinimumInventoryCount 1 -Attempt 1
$lostWithInventory.effects[0].observed_after | Add-Member -NotePropertyName inventory_count `
    -NotePropertyValue 1
Assert-True (-not (Test-CobblestoneLostDropTerminal -Terminal $lostWithInventory `
            -MinimumInventoryCount 1)) `
    'lost-drop terminal with pickup proof was accepted for retry'
$wrongFailure = New-MockLostDropTerminal -MinimumInventoryCount 1 -Attempt 1
$wrongFailure.failure.evidence = @('pickup_unconfirmed')
Assert-True (-not (Test-CobblestoneLostDropTerminal -Terminal $wrongFailure `
            -MinimumInventoryCount 1)) `
    'unrelated failure was accepted for lost-drop retry'
$drop = New-MockCobblestoneDrop -Revision 21
$noLooseRecovery = Resolve-CobblestoneLooseDropRecovery -VisibleItems @()
Assert-True ($noLooseRecovery.mode -ceq 'lost_drop_retry') `
    'zero loose items did not retain the qualified lost-drop retry'
$oneLooseRecovery = Resolve-CobblestoneLooseDropRecovery -VisibleItems @($drop)
Assert-True ($oneLooseRecovery.mode -ceq 'active_collect' -and
    [object]::ReferenceEquals($drop, $oneLooseRecovery.item)) `
    'one cobblestone drop did not select its delivered record for collection'
$wrongDrop = $drop.PSObject.Copy()
$wrongDrop.displayed_item = 'minecraft:diamond'
Assert-Throws { Resolve-CobblestoneLooseDropRecovery -VisibleItems @($wrongDrop) } `
    'a different loose item was accepted for cobblestone recovery'
Assert-Throws { Resolve-CobblestoneLooseDropRecovery -VisibleItems @($drop, $drop) } `
    'multiple loose items were accepted for cobblestone recovery'
$script:ToolTransport = {
    param($Tool, $Arguments)
    if ($Tool -cne 'agent_get_observation') { throw "unexpected loose-item tool: $Tool" }
    [pscustomobject]@{
        frame_id = $Arguments.frame_id; records = @($drop); next_cursor = $null
    }
}
Assert-Throws { Assert-NoVisibleLooseItems `
        -State (New-MockCobblestoneState -CobblestoneCount 1) } `
    'a normal successful cycle accepted a remaining loose item'
$script:ToolTransport = $null
$collectRequest = New-CobblestoneDropCollectionRequest -Record $drop -Attempt 1
Assert-True ($collectRequest.program.body[0].op -ceq 'collect_visible_item' -and
    $collectRequest.program.body[0].displayed_item -ceq 'minecraft:cobblestone' -and
    [object]::ReferenceEquals($drop.position, $collectRequest.program.body[0].target) -and
    $collectRequest.budget.max_distance_blocks -eq 0) `
    'loose-drop collection did not retain exact delivery evidence or stationarity'
[void](Assert-CobblestoneCollectionTerminal `
        -Terminal (New-MockCobblestoneCollectTerminal -Attempt 1 `
            -ActionSequence 2 -InventoryBefore 0) `
        -Attempt 1 -InventoryBefore 0 -InventoryAfter 1)

$script:ToolTransport = {
    param($Tool, $Arguments)
    if ($Tool -cne 'agent_get_observation') { throw "unexpected empty-page tool: $Tool" }
    [pscustomobject]@{
        frame_id = $Arguments.frame_id; records = $null; next_cursor = $null
    }
}
$emptyRecords = @(Get-RecordsFromState -State (New-MockCobblestoneState `
            -CobblestoneCount 0) -Kinds @('visible_surface') -Filter $null)
Assert-True ($emptyRecords.Count -eq 0) `
    'a Windows PowerShell null materialization was not treated as an empty page'
$emptySurfaces = @(Get-VisibleSurfaceRecords -State (New-MockCobblestoneState `
            -CobblestoneCount 0) -Block 'minecraft:cobblestone' `
        -Bounds $script:CobbleTargetBounds -Faces @('up') -AllowMissing)
Assert-True ($emptySurfaces.Count -eq 0) `
    'an allowed missing surface emitted a null pipeline element'
$script:ToolTransport = $null

[void](Assert-KnownCobblestoneGeneratorFaceTerminal `
        -Terminal (New-MockKnownGeneratorFaceTerminal))
$knownGeneratorProofs = @(Assert-KnownCobblestoneGeneratorTerminal `
        -Terminal (New-MockKnownGeneratorTerminal))
Assert-True ($knownGeneratorProofs.Count -eq 8 -and
    $knownGeneratorProofs[7].inventory_count -eq 8) `
    'known generator terminal did not retain eight cycle checkpoints'
$movingKnownGenerator = New-MockKnownGeneratorTerminal
$movingKnownGenerator.progress.distance_travelled = 0.1
Assert-Throws { Assert-KnownCobblestoneGeneratorTerminal `
        -Terminal $movingKnownGenerator } `
    'moving known generator terminal was accepted'
$unknownKnownGenerator = New-MockKnownGeneratorTerminal
$unknownKnownGenerator.effects[4].verification = 'unknown'
Assert-Throws { Assert-KnownCobblestoneGeneratorTerminal `
        -Terminal $unknownKnownGenerator } `
    'unknown known-generator checkpoint was accepted'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:MockCompleted = 0
$script:MockBreakAttempt = 0
$script:MockActionSequence = 0
$script:MockPendingKind = $null
$script:MockPendingMinimum = 0
$script:MockPendingBreakAttempt = 0
$script:MockPendingActionSequence = 0
$script:MockObservationSerial = 0L
$script:MockLooseDrop = $false
$script:MockLooseMode = 'none'
$script:MockLoosePollsRemaining = 0
$script:MockDelayedPickupMinimum = 0
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            $script:MockObservationSerial++
            if ($script:MockLooseMode -ceq 'transient') {
                if ($script:MockLoosePollsRemaining -gt 0) {
                    $script:MockLoosePollsRemaining--
                    $script:MockLooseDrop = $true
                } else {
                    $script:MockLooseMode = 'none'
                    $script:MockLooseDrop = $false
                }
            } elseif ($script:MockLooseMode -ceq 'delayed_pickup') {
                if ($script:MockLoosePollsRemaining -gt 0) {
                    $script:MockLoosePollsRemaining--
                    $script:MockLooseDrop = $true
                } else {
                    $script:MockCompleted = $script:MockDelayedPickupMinimum
                    $script:MockLooseMode = 'none'
                    $script:MockLooseDrop = $false
                }
            } elseif ($script:MockLooseMode -ceq 'stable') {
                $script:MockLooseDrop = $true
            } else {
                $script:MockLooseDrop = $false
            }
            New-MockCobblestoneState -CobblestoneCount $script:MockCompleted `
                -FrameSequence $script:MockObservationSerial
        }
        'agent_get_observation' {
            $kinds = @($Arguments.kinds)
            $records = if ($kinds.Count -eq 1 -and $kinds[0] -ceq 'visible_surface') {
                @(New-MockCobblestoneSurface -Revision (20L + $script:MockObservationSerial))
            } elseif ($kinds.Count -eq 1 -and $kinds[0] -ceq 'visible_entity' -and
                $script:MockLooseDrop) {
                @(New-MockCobblestoneDrop -Revision (20L + $script:MockObservationSerial))
            } else { @() }
            [pscustomobject]@{
                schema_version = 1
                frame_id = $Arguments.frame_id
                frame_completed_tick = 100L + $script:MockObservationSerial
                visible_entities_truncated = $false
                records = $records; next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $body = @($Arguments.program.body)
            $submitted = $body[0]
            $script:MockActionSequence++
            $script:MockPendingActionSequence = $script:MockActionSequence
            if ($submitted.op -ceq 'face_known_position') {
                if ($body.Count -ne 1 -or
                    $submitted.id -cne 'face_cobblestone_generator' -or
                    $submitted.target.x -ne 199 -or $submitted.target.y -ne 201 -or
                    $submitted.target.z -ne 200 -or
                    $Arguments.program.capabilities.Count -ne 1 -or
                    $Arguments.program.capabilities[0] -cne 'camera' -or
                    [int]$Arguments.budget.max_camera_degrees -ne 360) {
                    throw 'mock received a stale or malformed generator face Action'
                }
                $script:MockPendingKind = 'generator_face'
                $script:MockPendingMinimum = 1
            } elseif ($submitted.op -ceq 'operate_known_cobblestone_generator') {
                if ($body.Count -ne 1 -or
                    $submitted.id -cne 'operate_cobblestone_generator' -or
                    [int]$submitted.minimum_inventory_count -ne 8 -or
                    [int]$submitted.max_breaks -ne 8 -or
                    [int]$submitted.regeneration_wait_ticks -ne 100 -or
                    [int]$submitted.max_operation_duration_ticks -ne 3600 -or
                    $submitted.target.x -ne 199 -or $submitted.target.y -ne 201 -or
                    $submitted.target.z -ne 200 -or
                    $Arguments.program.capabilities.Count -ne 1 -or
                    $Arguments.program.capabilities[0] -cne 'block_break' -or
                    [int]$Arguments.budget.max_blocks_broken -ne 8 -or
                    [int]$Arguments.budget.max_camera_degrees -ne 0 -or
                    [int]$Arguments.budget.max_ticks -ne 3600) {
                    throw 'mock received a stale or malformed known generator Action'
                }
                $script:MockPendingKind = 'known_generator'
                $script:MockPendingMinimum = 8
            } elseif ($submitted.op -ceq 'break_known_block') {
                $expected = $script:MockCompleted + 1
                if ([int]$submitted.minimum_inventory_count -ne $expected -or
                    $submitted.target.x -ne 199 -or $submitted.target.y -ne 201 -or
                    $submitted.target.z -ne 200) {
                    throw 'mock received a stale or malformed cobblestone break'
                }
                $script:MockBreakAttempt++
                $script:MockPendingKind = 'break'
                $script:MockPendingMinimum = $expected
                $script:MockPendingBreakAttempt = $script:MockBreakAttempt
            } elseif ($submitted.op -ceq 'collect_visible_item') {
                if (-not $script:MockLooseDrop -or
                    $submitted.displayed_item -cne 'minecraft:cobblestone' -or
                    $submitted.target.x -ne 199.5 -or $submitted.target.y -ne 201.1 -or
                    $submitted.target.z -ne 200.1) {
                    throw 'mock received a stale or malformed cobblestone collection'
                }
                $script:MockPendingKind = 'collect'
                $script:MockPendingMinimum = $script:MockCompleted + 1
                $script:MockPendingBreakAttempt = $script:MockBreakAttempt
            } else {
                throw "unexpected cobblestone Action op: $($submitted.op)"
            }
            [pscustomobject]@{
                schema_version = 1
                action_id = '550e8400-e29b-41d4-a716-' + `
                    $script:MockActionSequence.ToString('000000000000')
                state = 'queued'
            }
        }
        'agent_get_action' {
            if ($null -eq $script:MockPendingKind -or $script:MockPendingMinimum -lt 1) {
                throw 'mock has no pending Action'
            }
            $kind = $script:MockPendingKind
            $minimum = $script:MockPendingMinimum
            $breakAttempt = $script:MockPendingBreakAttempt
            $actionSequence = $script:MockPendingActionSequence
            $script:MockPendingKind = $null
            $script:MockPendingMinimum = 0
            if ($kind -ceq 'generator_face') {
                New-MockKnownGeneratorFaceTerminal
            } elseif ($kind -ceq 'known_generator') {
                $script:MockCompleted = 8
                $script:MockLooseMode = 'none'
                $script:MockLooseDrop = $false
                New-MockKnownGeneratorTerminal
            } elseif ($kind -ceq 'collect') {
                $before = $script:MockCompleted
                $script:MockCompleted = $minimum
                $script:MockLooseMode = 'none'
                $script:MockLooseDrop = $false
                New-MockCobblestoneCollectTerminal -Attempt $breakAttempt `
                    -ActionSequence $actionSequence -InventoryBefore $before
            } elseif ($breakAttempt -eq 2) {
                $script:MockLooseMode = 'stable'
                $script:MockLooseDrop = $true
                New-MockLostDropTerminal -MinimumInventoryCount $minimum `
                    -Attempt $breakAttempt -ActionSequence $actionSequence
            } elseif ($breakAttempt -eq 4) {
                $script:MockLooseMode = 'none'
                $script:MockLooseDrop = $false
                New-MockLostDropTerminal -MinimumInventoryCount $minimum `
                    -Attempt $breakAttempt -ActionSequence $actionSequence
            } elseif ($breakAttempt -eq 6) {
                $script:MockLooseMode = 'delayed_pickup'
                $script:MockLoosePollsRemaining = 1
                $script:MockDelayedPickupMinimum = $minimum
                $script:MockLooseDrop = $true
                New-MockLostDropTerminal -MinimumInventoryCount $minimum `
                    -Attempt $breakAttempt -ActionSequence $actionSequence
            } else {
                $script:MockCompleted = $minimum
                if ($breakAttempt -eq 1) {
                    $script:MockLooseMode = 'transient'
                    $script:MockLoosePollsRemaining = 2
                    $script:MockLooseDrop = $true
                } else {
                    $script:MockLooseMode = 'none'
                    $script:MockLooseDrop = $false
                }
                New-MockBreakTerminal -MinimumInventoryCount $minimum `
                    -Attempt $breakAttempt -ActionSequence $actionSequence
            }
        }
        'agent_cancel_action' { throw 'mock should not cancel a successful break' }
        default { throw "unexpected cobblestone mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpCobblestoneGeneratorCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase9-cobblestone-generator') `
        'gate result name is wrong'
    Assert-True ($result.gate_result.lifecycle.accepted -eq 2 -and
        $result.gate_result.lifecycle.terminal -eq 2 -and
        $result.gate_result.lifecycle.successful_pickups -eq 8 -and
        $result.gate_result.lifecycle.confirmed_break_effects -eq 8 -and
        $result.gate_result.lifecycle.total_actions -eq 2 -and
        $result.gate_result.lifecycle.expected_pickaxe_damage -eq 8) `
        'camera then finite known-generator Action lifecycle was not proven'
    Assert-True ($result.gate_result.terminal_effects.Count -eq 8) `
        'eight confirmed break effects were not retained'
    Assert-True ($result.gate_result.online_oracle.cobblestone_delta -eq 8) `
        'online inventory oracle is not +8'
    Assert-True ($result.gate_result.total_attempts -eq 8 -and
        $result.gate_result.maximum_attempts -eq 8 -and
        $result.gate_result.expected_pickaxe_damage -eq 8 -and
        $result.gate_result.action_boundary -ceq
            'camera_action_then_fresh_evidence_then_finite_generator_action' -and
        $result.gate_result.lost_drop_effects.Count -eq 0 -and
        $result.gate_result.recovered_drop_effects.Count -eq 0) `
        'finite known-generator attempt or effect accounting is wrong'
    Assert-True ($result.gate_result.external_oracle.player.health -eq 20.0) `
        'offline oracle did not bind the observed health baseline'
    Assert-True ($result.gate_result.external_oracle.player.iron_pickaxe_damage -eq 8 -and
        $result.gate_result.external_oracle.total_attempts -eq 8 -and
        $result.gate_result.external_oracle.maximum_attempts -eq 8 -and
        $result.gate_result.external_oracle.lost_drops -eq 0 -and
        $result.gate_result.external_oracle.recovered_loose_drops -eq 0 -and
        $result.gate_result.external_oracle.active_collection_actions -eq 0 -and
        $result.gate_result.external_oracle.delayed_passive_pickups -eq 0 -and
        $result.gate_result.external_oracle.inspector_arguments[1] -ceq '8') `
        'offline oracle did not bind attempts to pickaxe damage'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) `
        'input release was not proven'
    foreach ($name in @('gate-events.jsonl', 'gate-result.json',
            'external-oracle-manifest.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "missing artifact $name"
    }
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed' -and [bool]$manifest.fixed_five_only -and
        [bool]$manifest.normal_player_actions_only) `
        'artifact manifest weakened the acceptance boundary'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP cobblestone generator capability gate mock tests passed.'
