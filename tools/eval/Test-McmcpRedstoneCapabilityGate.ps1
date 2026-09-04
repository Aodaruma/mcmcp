[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-redstone-gate-' + [Guid]::NewGuid().ToString('N'))
$runnerPath = Join-Path $PSScriptRoot 'Invoke-McmcpRedstoneCapabilityGate.ps1'
. $runnerPath -ArtifactDirectory $artifactDirectory -TokenPath mock -LibraryOnly

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (-not $threw) { throw $Message }
}

function New-MockState {
    param(
        [bool]$Consumed = $false,
        [long]$WorldRevision = 7L,
        [long]$ClientTick = 10L,
        [string]$FrameId = 'obs-0123456789abcdef'
    )

    $inventory = if ($Consumed) { @() } else {
        @(
            [pscustomobject]@{ item = 'minecraft:redstone_lamp'; count = 1 },
            [pscustomobject]@{ item = 'minecraft:lever'; count = 1 }
        )
    }
    return [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'
            client_tick = $ClientTick
            world_revision = $WorldRevision
            position = [pscustomobject]@{ x = 201.5; y = 200.0; z = 193.5 }
            yaw = 0.0; pitch = 25.0
            health = 20.0; absorption = 0.0; hunger = 20
            air = 300; max_air = 300; on_fire = $false; submerged = $false
            status_effects = @()
        }
        inventory = $inventory
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = $FrameId }
        action = $null
    }
}

function New-MockSupport {
    param(
        [string]$Block,
        [int]$X,
        [int]$Y = 199,
        [int]$Z = 194,
        [long]$WorldRevision = 7L,
        [AllowNull()][object]$Properties = ([pscustomobject]@{})
    )

    return [pscustomobject]@{
        kind = 'visible_surface'
        block = $Block
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z
        }
        face = 'up'
        state = [pscustomobject]@{ block = $Block; properties = $Properties }
        observed_tick = 10L
        world_revision = $WorldRevision
    }
}

# The Action builder must remain coordinate-agnostic even though the live fixture query is bounded.
$tokens = $null
$parseErrors = $null
$runnerAst = [Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path -LiteralPath $runnerPath), [ref]$tokens, [ref]$parseErrors)
Assert-True (@($parseErrors).Count -eq 0) 'runner has PowerShell parse errors'
$builderAst = @($runnerAst.FindAll({
            param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq 'New-RedstoneActionRequest'
        }, $true))
Assert-True ($builderAst.Count -eq 1) 'Action builder AST was not unique'
Assert-True ($builderAst[0].Extent.Text -cnotmatch '\b(194|200|201|202)\b') `
    'Action builder contains a fixture coordinate literal'

$syntheticEvidence = [ordered]@{
    lamp_target = [ordered]@{
        dimension = 'minecraft:overworld'; x = 17; y = 81; z = -23
    }
    rotation = 270
}
$syntheticRequest = New-RedstoneActionRequest -Evidence $syntheticEvidence
$syntheticNode = @($syntheticRequest.program.body)[0]
Assert-True ($syntheticNode.anchor.x -eq 17 -and $syntheticNode.anchor.y -eq 81 -and
    $syntheticNode.anchor.z -eq -23 -and $syntheticNode.rotation -eq 270) `
    'Action builder did not preserve a delivered synthetic target and rotation'

# Geometry is derived from current visible supports for every supported horizontal direction.
$geometryState = New-MockState
$rotationCases = @(
    [pscustomobject]@{ dx = 1; dz = 0; rotation = 0 },
    [pscustomobject]@{ dx = 0; dz = 1; rotation = 90 },
    [pscustomobject]@{ dx = -1; dz = 0; rotation = 180 },
    [pscustomobject]@{ dx = 0; dz = -1; rotation = 270 }
)
foreach ($case in $rotationCases) {
    $lampCase = New-MockSupport -Block 'minecraft:smooth_stone' -X 50 -Z 60
    $leverCase = New-MockSupport -Block 'minecraft:glass' `
        -X (50 + $case.dx) -Z (60 + $case.dz)
    $geometry = Resolve-RedstoneFixtureGeometry -State $geometryState `
        -LampSupport $lampCase -LeverSupport $leverCase
    Assert-True ($geometry.rotation -eq $case.rotation -and
        $geometry.lamp_target.x -eq 50 -and $geometry.lamp_target.y -eq 200 -and
        $geometry.lamp_target.z -eq 60 -and
        $geometry.lever_target.x -eq (50 + $case.dx) -and
        $geometry.lever_target.y -eq 200 -and
        $geometry.lever_target.z -eq (60 + $case.dz)) `
        "support-derived geometry failed for rotation $($case.rotation)"
}

$validLamp = New-MockSupport -Block 'minecraft:smooth_stone' -X 201
$validLever = New-MockSupport -Block 'minecraft:glass' -X 202
$staleLever = New-MockSupport -Block 'minecraft:glass' -X 202 -WorldRevision 6
Assert-Throws {
    Resolve-RedstoneFixtureGeometry -State $geometryState `
        -LampSupport $validLamp -LeverSupport $staleLever
} 'stale support revision was accepted'
$distantLever = New-MockSupport -Block 'minecraft:glass' -X 203
Assert-Throws {
    Resolve-RedstoneFixtureGeometry -State $geometryState `
        -LampSupport $validLamp -LeverSupport $distantLever
} 'non-adjacent supports were accepted'
$wrongStateLever = New-MockSupport -Block 'minecraft:glass' -X 202 `
    -Properties ([pscustomobject]@{ waterlogged = 'false' })
Assert-Throws {
    Resolve-RedstoneFixtureGeometry -State $geometryState `
        -LampSupport $validLamp -LeverSupport $wrongStateLever
} 'support carrying a non-exact state was accepted'

$script:ActionFinished = $false
$script:SubmittedRequest = $null
$script:ObservationCalls = [Collections.Generic.List[object]]::new()
$actionId = '550e8400-e29b-41d4-a716-446655440060'
$initialState = New-MockState
$finalState = New-MockState -Consumed $true -WorldRevision 9L -ClientTick 40L `
    -FrameId 'obs-fedcba9876543210'
$lampSupport = New-MockSupport -Block 'minecraft:smooth_stone' -X 201
$leverSupport = New-MockSupport -Block 'minecraft:glass' -X 202

$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            if ($script:ActionFinished) { return $finalState }
            return $initialState
        }
        'agent_get_observation' {
            $script:ObservationCalls.Add($Arguments)
            $block = [string]$Arguments.filter.block_ids[0]
            $bounds = $Arguments.filter.position_bounds
            $record = if ($block -ceq 'minecraft:glass') {
                if ($bounds.min_x -ne 202 -or $bounds.max_x -ne 202) {
                    throw 'lever fixture observation bounds changed'
                }
                $leverSupport
            } else {
                if ($block -cne 'minecraft:smooth_stone' -or
                    $bounds.min_x -ne 201 -or $bounds.max_x -ne 201) {
                    throw 'lamp fixture observation bounds changed'
                }
                $lampSupport
            }
            return [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-0123456789abcdef'
                frame_completed_tick = 10L
                visible_entities_truncated = $false
                records = @($record)
                next_cursor = $null
                sampling_coverage = 1.0
            }
        }
        'agent_start_action' {
            $script:SubmittedRequest = $Arguments
            return [pscustomobject]@{
                schema_version = 1; action_id = $actionId; state = 'queued'
            }
        }
        'agent_get_action' {
            $script:ActionFinished = $true
            return [pscustomobject]@{
                schema_version = 1
                action_id = $actionId
                state = 'succeeded'
                progress = [pscustomobject]@{
                    executed_nodes = 1; total_node_upper_bound = 1
                    distance_travelled = 0.0; camera_degrees = 100.0
                    interactions = 2; blocks_broken = 0; blocks_placed = 2
                }
                failure = $null
                trace = @(
                    [pscustomobject]@{
                        event = 'NODE_EVIDENCE'
                        detail = 'redstone_identity_observations=3'
                    },
                    [pscustomobject]@{
                        event = 'NODE_COMPLETED'
                        detail = 'redstone_fixture_identity'
                    }
                )
            }
        }
        default { throw "unexpected tool $Tool" }
    }
}

Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion
        tools = @($script:AllowedTools)
    })

try {
    $result = Invoke-McmcpRedstoneCapabilityGate
    $gateResult = $result.gate_result
    Assert-True $gateResult.lifecycle.accepted_equals_terminal 'lifecycle did not close exactly'
    Assert-True ($gateResult.terminal_proof.output_observations -eq 3) `
        'terminal trace did not prove three output observations'
    Assert-True ($script:ObservationCalls.Count -eq 2) `
        'fixture supports were not acquired through exactly two filtered observations'

    $submittedNode = @($script:SubmittedRequest.program.body)[0]
    Assert-True ($submittedNode.anchor.x -eq $lampSupport.position.x -and
        $submittedNode.anchor.y -eq ($lampSupport.position.y + 1) -and
        $submittedNode.anchor.z -eq $lampSupport.position.z -and
        $submittedNode.rotation -eq 0) `
        'live Action target was not derived from delivered lamp support'
    Assert-True ($gateResult.delivered_evidence.lever_target.x -eq $leverSupport.position.x -and
        $gateResult.delivered_evidence.lever_target.y -eq ($leverSupport.position.y + 1) -and
        $gateResult.delivered_evidence.coordinates_derived_from_fresh_supports) `
        'result did not retain the derived lever target proof'
    Assert-True ($gateResult.delivered_evidence.world_revision -eq 7 -and
        $gateResult.delivered_evidence.lamp_support.world_revision -eq 7 -and
        $gateResult.delivered_evidence.lever_support.world_revision -eq 7) `
        'result did not retain one current support revision'

    $ledger = $gateResult.inventory_ledger
    Assert-True ($ledger.before.'minecraft:redstone_lamp' -eq 1 -and
        $ledger.before.'minecraft:lever' -eq 1 -and
        $ledger.after.'minecraft:redstone_lamp' -eq 0 -and
        $ledger.after.'minecraft:lever' -eq 0 -and
        $ledger.delta.'minecraft:redstone_lamp' -eq -1 -and
        $ledger.delta.'minecraft:lever' -eq -1 -and
        $ledger.exact_consumption -and $ledger.post_world_revision -eq 9) `
        'fresh post-Action inventory ledger is incomplete'

    $oracle = $gateResult.external_oracle
    $changed = @($oracle.expected_changed_cells)
    Assert-True ($oracle.expected_changed_cell_count -eq 2 -and
        $changed.Count -eq 2 -and $oracle.reject_unlisted_changes -and
        $oracle.expected_extra_mutations -eq 0) `
        'offline oracle mutation envelope is incomplete'
    $lampExpected = @($changed | Where-Object { $_.id -ceq 'output' })[0]
    $leverExpected = @($changed | Where-Object { $_.id -ceq 'input' })[0]
    Assert-True ($lampExpected.after_state.block -ceq 'minecraft:redstone_lamp' -and
        $lampExpected.after_state.properties.lit -ceq 'false') `
        'offline oracle does not require an unlit lamp'
    Assert-True ($leverExpected.after_state.block -ceq 'minecraft:lever' -and
        $leverExpected.after_state.properties.face -ceq 'floor' -and
        $leverExpected.after_state.properties.facing -ceq 'south' -and
        $leverExpected.after_state.properties.powered -ceq 'false') `
        'offline oracle does not carry the complete final lever state'
    Assert-True (@($oracle.expected_unchanged_supports).Count -eq 2 -and
        $oracle.expected_inventory.after.'minecraft:redstone_lamp' -eq 0 -and
        $oracle.expected_inventory.after.'minecraft:lever' -eq 0) `
        'offline oracle omitted support or inventory invariants'

    foreach ($name in @(
            'gate-events.jsonl', 'gate-result.json', 'external-oracle-manifest.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "artifact was not written: $name"
    }
    $serializedOracle = Get-Content `
        (Join-Path $artifactDirectory 'external-oracle-manifest.json') -Raw |
        ConvertFrom-Json
    Assert-True ($serializedOracle.expected_changed_cell_count -eq 2) `
        'serialized external oracle is not readable'

    $badFinalState = New-MockState -WorldRevision 9L -ClientTick 40L `
        -FrameId 'obs-fedcba9876543210'
    $ledgerEvidence = [ordered]@{
        state = $initialState
        world_revision = 7L
        inventory_before = [ordered]@{
            'minecraft:redstone_lamp' = 1L; 'minecraft:lever' = 1L
        }
    }
    Assert-Throws {
        Get-RedstoneInventoryLedger -Evidence $ledgerEvidence -FinalState $badFinalState
    } 'post-Action inventory residue was accepted'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

'MCMCP redstone capability gate mock tests passed.'
