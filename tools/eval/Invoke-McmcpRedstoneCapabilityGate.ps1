[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [Parameter(Mandatory)][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$saved = @($ArtifactDirectory, $TokenPath, $Endpoint, [bool]$LibraryOnly)
. (Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1') `
    -Gate navigation -ArtifactDirectory $ArtifactDirectory -TokenPath $TokenPath `
    -Endpoint $Endpoint -LibraryOnly
$ArtifactDirectory, $TokenPath, $Endpoint, $LibraryOnly = $saved

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-redstone-capability-gate'
            version = '1'
        }
    }
}

function Assert-RedstoneSupportRecord {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Record,
        [Parameter(Mandatory)][string]$ExpectedBlock,
        [Parameter(Mandatory)][string]$Label
    )

    if ((Get-ObjectProperty $Record 'kind') -cne 'visible_surface' -or
        (Get-ObjectProperty $Record 'block') -cne $ExpectedBlock -or
        (Get-ObjectProperty $Record 'face') -cne 'up') {
        throw "$Label is not the expected visible $ExpectedBlock UP support"
    }

    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $Record 'position'
    if ($null -eq $position -or
        (Get-ObjectProperty $position 'dimension') -cne
            (Get-ObjectProperty $world 'dimension')) {
        throw "$Label is outside the current dimension"
    }
    foreach ($axis in @('x', 'y', 'z')) {
        $coordinate = Get-ObjectProperty $position $axis
        if ($coordinate -isnot [sbyte] -and $coordinate -isnot [byte] -and
            $coordinate -isnot [int16] -and $coordinate -isnot [uint16] -and
            $coordinate -isnot [int32] -and $coordinate -isnot [uint32] -and
            $coordinate -isnot [int64] -and $coordinate -isnot [uint64]) {
            throw "$Label has a non-integer $axis coordinate"
        }
    }

    $recordRevision = Get-ObjectProperty $Record 'world_revision'
    if ($recordRevision -isnot [sbyte] -and $recordRevision -isnot [byte] -and
        $recordRevision -isnot [int16] -and $recordRevision -isnot [uint16] -and
        $recordRevision -isnot [int32] -and $recordRevision -isnot [uint32] -and
        $recordRevision -isnot [int64] -and $recordRevision -isnot [uint64]) {
        throw "$Label has no integer world_revision"
    }
    $currentRevision = Get-CurrentWorldRevision -State $State
    if ([long]$recordRevision -ne $currentRevision) {
        throw "$Label is not from the current world_revision"
    }

    $recordState = Get-ObjectProperty $Record 'state'
    $properties = Get-ObjectProperty $recordState 'properties'
    if ($null -eq $recordState -or
        (Get-ObjectProperty $recordState 'block') -cne $ExpectedBlock -or
        $null -eq $properties -or
        @($properties.PSObject.Properties).Count -ne 0) {
        throw "$Label does not carry the expected complete inert block state"
    }
}

function Resolve-RedstoneFixtureGeometry {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$LampSupport,
        [Parameter(Mandatory)][object]$LeverSupport
    )

    Assert-RedstoneSupportRecord -State $State -Record $LampSupport `
        -ExpectedBlock 'minecraft:smooth_stone' -Label 'lamp support'
    Assert-RedstoneSupportRecord -State $State -Record $LeverSupport `
        -ExpectedBlock 'minecraft:glass' -Label 'lever support'

    $lampPosition = Get-ObjectProperty $LampSupport 'position'
    $leverPosition = Get-ObjectProperty $LeverSupport 'position'
    if ((Get-ObjectProperty $lampPosition 'dimension') -cne
            (Get-ObjectProperty $leverPosition 'dimension') -or
        [int](Get-ObjectProperty $lampPosition 'y') -ne
            [int](Get-ObjectProperty $leverPosition 'y')) {
        throw 'redstone supports are not in one horizontal current-world plane'
    }

    $dx = [int](Get-ObjectProperty $leverPosition 'x') -
        [int](Get-ObjectProperty $lampPosition 'x')
    $dz = [int](Get-ObjectProperty $leverPosition 'z') -
        [int](Get-ObjectProperty $lampPosition 'z')
    if ([Math]::Abs($dx) + [Math]::Abs($dz) -ne 1) {
        throw 'redstone supports are not horizontally adjacent'
    }

    $rotation = if ($dx -eq 1) { 0 }
        elseif ($dz -eq 1) { 90 }
        elseif ($dx -eq -1) { 180 }
        else { 270 }
    return [ordered]@{
        world_revision = Get-CurrentWorldRevision -State $State
        lamp_target = Get-TargetAboveSupport $lampPosition
        lever_target = Get-TargetAboveSupport $leverPosition
        rotation = $rotation
    }
}

function Get-RedstoneFixtureEvidence {
    $state = Get-FreshState
    Assert-ReadyState -State $state -Phase 'redstone fixture acquisition'
    $lampCount = Get-InventoryCount -State $state -Item 'minecraft:redstone_lamp'
    $leverCount = Get-InventoryCount -State $state -Item 'minecraft:lever'
    if ($lampCount -ne 1 -or $leverCount -ne 1) {
        throw 'redstone fixture requires exactly one lamp and one lever'
    }

    # These fixture coordinates constrain observation only. Action coordinates below are derived
    # exclusively from the fresh records returned through these filters.
    $lampBounds = [ordered]@{
        dimension = 'minecraft:overworld'
        min_x = 201; min_y = 199; min_z = 194
        max_x = 201; max_y = 199; max_z = 194
    }
    $leverBounds = [ordered]@{
        dimension = 'minecraft:overworld'
        min_x = 202; min_y = 199; min_z = 194
        max_x = 202; max_y = 199; max_z = 194
    }
    $lamp = @(Get-VisibleSurfaceRecords -State $state `
            -Block 'minecraft:smooth_stone' -Bounds $lampBounds -Faces @('up'))
    $lever = @(Get-VisibleSurfaceRecords -State $state `
            -Block 'minecraft:glass' -Bounds $leverBounds -Faces @('up'))
    if ($lamp.Count -ne 1 -or $lever.Count -ne 1) {
        throw 'redstone fixture support surfaces were not delivered exactly once'
    }

    $geometry = Resolve-RedstoneFixtureGeometry -State $state `
        -LampSupport $lamp[0] -LeverSupport $lever[0]
    $evidence = [ordered]@{
        state = $state
        lamp_support = $lamp[0]
        lever_support = $lever[0]
        lamp_target = $geometry.lamp_target
        lever_target = $geometry.lever_target
        rotation = $geometry.rotation
        world_revision = $geometry.world_revision
        inventory_before = [ordered]@{
            'minecraft:redstone_lamp' = [long]$lampCount
            'minecraft:lever' = [long]$leverCount
        }
    }
    Add-GateEvent -Event 'redstone_fixture_evidence_verified' -Detail ([ordered]@{
            frame_id = Get-ObservationFrameId -State $state
            world_revision = $geometry.world_revision
            lamp_support = Get-ObjectProperty $lamp[0] 'position'
            lever_support = Get-ObjectProperty $lever[0] 'position'
            lamp_target = $geometry.lamp_target
            lever_target = $geometry.lever_target
            rotation = $geometry.rotation
        })
    return $evidence
}

function New-RedstoneActionRequest {
    param([Parameter(Mandatory)][object]$Evidence)

    $node = [ordered]@{
        id = 'redstone_fixture_identity'
        op = 'apply_known_redstone_spec'
        anchor = Get-ObjectProperty $Evidence 'lamp_target'
        rotation = [int](Get-ObjectProperty $Evidence 'rotation')
        components = @(
            [ordered]@{ id = 'input'; role = 'input'; block = 'minecraft:lever' },
            [ordered]@{
                id = 'output'; role = 'output'; block = 'minecraft:redstone_lamp'
            })
        truth_table = @(
            [ordered]@{
                inputs = [ordered]@{ input = $false }
                outputs = [ordered]@{ output = $false }
            },
            [ordered]@{
                inputs = [ordered]@{ input = $true }
                outputs = [ordered]@{ output = $true }
            })
        footprint = [ordered]@{ x = 2; y = 1; z = 1 }
        timing = [ordered]@{ settle_ticks = 5 }
    }
    return New-ActionRequest -Name 'capability_gate_redstone_identity' `
        -Capabilities @('camera', 'block_interact', 'block_place') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 20750; max_ticks = 415; max_distance_blocks = 0
            max_camera_degrees = 720; max_interactions = 2
            max_blocks_broken = 0; max_blocks_placed = 2
        })
}

function Assert-RedstoneTerminal {
    param([Parameter(Mandatory)][object]$Terminal)

    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'redstone Action did not succeed cleanly'
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 2 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 2) {
        throw 'redstone terminal progress violated the direct identity budget'
    }
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $observations = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'redstone_identity_observations=3'
        })
    $completed = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'redstone_fixture_identity'
        })
    if ($observations.Count -ne 1 -or $completed.Count -ne 1 -or
        [Array]::IndexOf($trace, $completed[0]) -le
            [Array]::IndexOf($trace, $observations[0])) {
        throw 'redstone trace did not prove OFF/ON/OFF before node completion'
    }
    return [ordered]@{
        output_observations = 3
        interactions = 2
        blocks_placed = 2
        blocks_broken = 0
        distance_travelled = 0
    }
}

function Assert-RedstoneFixedFiveEvidence {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) {
        throw 'redstone gate requires exactly one fixed-five verification event'
    }
    $tools = @(Get-ObjectProperty $events[0] 'tools')
    if ($tools.Count -ne $script:AllowedTools.Count) {
        throw 'redstone fixed-five tool count mismatch'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw 'redstone fixed-five tool order mismatch'
        }
    }
    return [ordered]@{
        protocol_version = Get-ObjectProperty $events[0] 'protocol_version'
        tools = $tools
    }
}

function Get-RedstoneInventoryLedger {
    param(
        [Parameter(Mandatory)][object]$Evidence,
        [Parameter(Mandatory)][object]$FinalState
    )

    $initialState = Get-ObjectProperty $Evidence 'state'
    $initialWorld = Get-ObjectProperty $initialState 'world'
    $finalWorld = Get-ObjectProperty $FinalState 'world'
    if ((Get-ObjectProperty $finalWorld 'dimension') -cne
            (Get-ObjectProperty $initialWorld 'dimension') -or
        [long](Get-ObjectProperty $finalWorld 'world_revision') -lt
            [long](Get-ObjectProperty $Evidence 'world_revision') -or
        [long](Get-ObjectProperty $finalWorld 'client_tick') -lt
            [long](Get-ObjectProperty $initialWorld 'client_tick')) {
        throw 'redstone post-Action state regressed from the admitted world state'
    }

    $lampAfter = Get-InventoryCount -State $FinalState -Item 'minecraft:redstone_lamp'
    $leverAfter = Get-InventoryCount -State $FinalState -Item 'minecraft:lever'
    if ($lampAfter -ne 0 -or $leverAfter -ne 0) {
        throw 'redstone Action did not consume exactly the admitted lamp and lever'
    }
    $before = Get-ObjectProperty $Evidence 'inventory_before'
    return [ordered]@{
        before = $before
        after = [ordered]@{
            'minecraft:redstone_lamp' = [long]$lampAfter
            'minecraft:lever' = [long]$leverAfter
        }
        delta = [ordered]@{
            'minecraft:redstone_lamp' = $lampAfter -
                [long](Get-ObjectProperty $before 'minecraft:redstone_lamp')
            'minecraft:lever' = $leverAfter -
                [long](Get-ObjectProperty $before 'minecraft:lever')
        }
        exact_consumption = $true
        post_client_tick = [long](Get-ObjectProperty $finalWorld 'client_tick')
        post_world_revision = [long](Get-ObjectProperty $finalWorld 'world_revision')
    }
}

function Get-VanillaFloorPlacementFacing {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Target
    )

    $player = Get-ObjectProperty (Get-ObjectProperty $State 'world') 'position'
    $dx = ([double](Get-ObjectProperty $Target 'x') + 0.5) -
        [double](Get-ObjectProperty $player 'x')
    $dz = ([double](Get-ObjectProperty $Target 'z') + 0.5) -
        [double](Get-ObjectProperty $player 'z')
    if ([Math]::Abs($dx) -lt 0.000000001 -and
        [Math]::Abs($dz) -lt 0.000000001) {
        throw 'lever facing cannot be derived from a coincident horizontal player position'
    }
    $yaw = [Math]::Atan2($dz, $dx) * 180.0 / [Math]::PI - 90.0
    $quarter = [int][Math]::Floor($yaw / 90.0 + 0.5)
    $index = (($quarter % 4) + 4) % 4
    return @('south', 'west', 'north', 'east')[$index]
}

function New-RedstoneExternalOracleManifest {
    param(
        [Parameter(Mandatory)][object]$Evidence,
        [Parameter(Mandatory)][object]$InventoryLedger
    )

    $lampTarget = Get-ObjectProperty $Evidence 'lamp_target'
    $leverTarget = Get-ObjectProperty $Evidence 'lever_target'
    $leverFacing = Get-VanillaFloorPlacementFacing `
        -State (Get-ObjectProperty $Evidence 'state') -Target $leverTarget
    return [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        inspector = 'tools/eval/Inspect-McmcpRegion.py'
        dimension = [string](Get-ObjectProperty $lampTarget 'dimension')
        expected_changed_cell_count = 2
        expected_changed_cells = @(
            [ordered]@{
                id = 'output'
                position = $lampTarget
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                after_state = [ordered]@{
                    block = 'minecraft:redstone_lamp'
                    properties = [ordered]@{ lit = 'false' }
                }
            },
            [ordered]@{
                id = 'input'
                position = $leverTarget
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                after_state = [ordered]@{
                    block = 'minecraft:lever'
                    properties = [ordered]@{
                        face = 'floor'; facing = $leverFacing; powered = 'false'
                    }
                }
            })
        expected_unchanged_supports = @(
            [ordered]@{
                position = Get-ObjectProperty `
                    (Get-ObjectProperty $Evidence 'lamp_support') 'position'
                state = Get-ObjectProperty `
                    (Get-ObjectProperty $Evidence 'lamp_support') 'state'
            },
            [ordered]@{
                position = Get-ObjectProperty `
                    (Get-ObjectProperty $Evidence 'lever_support') 'position'
                state = Get-ObjectProperty `
                    (Get-ObjectProperty $Evidence 'lever_support') 'state'
            })
        expected_inventory = [ordered]@{
            before = Get-ObjectProperty $InventoryLedger 'before'
            after = Get-ObjectProperty $InventoryLedger 'after'
            delta = Get-ObjectProperty $InventoryLedger 'delta'
        }
        reject_unlisted_changes = $true
        expected_extra_mutations = 0
    }
}

function Assert-RedstoneLifecycle {
    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $closed = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    if ($accepted.Count -ne 1 -or $closed.Count -ne 1 -or
        (Get-ObjectProperty $accepted[0] 'action_id') -cne
            (Get-ObjectProperty $closed[0] 'action_id')) {
        throw 'redstone accepted/terminal lifecycle mismatch'
    }
    return [ordered]@{
        accepted = 1
        terminal = 1
        accepted_equals_terminal = $true
    }
}

function Invoke-RedstoneGateCore {
    $fixedFive = Assert-RedstoneFixedFiveEvidence
    $evidence = Get-RedstoneFixtureEvidence
    $terminal = Invoke-ActionRequest `
        -Request (New-RedstoneActionRequest -Evidence $evidence) `
        -WallTimeoutSeconds 90
    $terminalProof = Assert-RedstoneTerminal -Terminal $terminal
    $finalState = Get-FreshState
    $inventoryLedger = Get-RedstoneInventoryLedger `
        -Evidence $evidence -FinalState $finalState
    $oracle = New-RedstoneExternalOracleManifest `
        -Evidence $evidence -InventoryLedger $inventoryLedger

    return [ordered]@{
        gate = 'phase5-redstone-direct'
        fixture_precondition = '/mcmcp_fixture phase5 redstone'
        fixed_five_surface = $fixedFive
        lifecycle = Assert-RedstoneLifecycle
        delivered_evidence = [ordered]@{
            frame_id = Get-ObservationFrameId -State $evidence.state
            world_revision = $evidence.world_revision
            lamp_support = $evidence.lamp_support
            lever_support = $evidence.lever_support
            lamp_target = $evidence.lamp_target
            lever_target = $evidence.lever_target
            rotation = $evidence.rotation
            coordinates_derived_from_fresh_supports = $true
        }
        inventory_ledger = $inventoryLedger
        terminal_proof = $terminalProof
        external_oracle_status = 'pending'
        external_oracle = $oracle
    }
}

function Write-RedstoneArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )

    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }),
        $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'phase5-redstone-direct'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools)
        fixed_five_only = $true
        normal_player_actions_only = $true
        public_input_release = $InputRelease
        result = $GateResult
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{
                type = $Failure.Exception.GetType().FullName
                message = $Failure.Exception.Message
            }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($null -ne $GateResult) {
        [IO.File]::WriteAllText(
            (Join-Path $ArtifactDirectory 'external-oracle-manifest.json'),
            (ConvertTo-Json $GateResult.external_oracle -Depth 100),
            $script:Utf8NoBom)
    }
}

function Invoke-McmcpRedstoneCapabilityGate {
    $failure = $null
    $release = $null
    $result = $null
    try {
        $result = Invoke-RedstoneGateCore
    } catch {
        $failure = $_
    } finally {
        try {
            $release = Invoke-GateCleanup
        } catch {
            if ($null -eq $failure) { $failure = $_ }
        }
    }
    Write-RedstoneArtifacts -GateResult $result -InputRelease $release -Failure $failure
    if ($null -ne $failure) { throw $failure }
    return [ordered]@{ gate_result = $result; input_release = $release }
}

if (-not $LibraryOnly) {
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText(
        (Resolve-Path -LiteralPath $TokenPath)).Trim()
    Assert-FixedFiveToolSurface
    ConvertTo-Json (Invoke-McmcpRedstoneCapabilityGate) -Depth 100
}
