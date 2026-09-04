[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$stairsArtifactDirectory = $ArtifactDirectory
$stairsTokenPath = $TokenPath
$stairsEndpoint = $Endpoint
$stairsLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $stairsArtifactDirectory `
    -TokenPath $stairsTokenPath -Endpoint $stairsEndpoint -LibraryOnly
$ArtifactDirectory = $stairsArtifactDirectory
$TokenPath = $stairsTokenPath
$Endpoint = $stairsEndpoint
$LibraryOnly = $stairsLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

# Fixture coordinates belong only to this evaluation runner, never production code.
$script:StairSourceBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 199; min_y = 203; min_z = 192
    max_x = 203; max_y = 203; max_z = 193
}
$script:StairSupportBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 201; min_y = 199; min_z = 192
    max_x = 202; max_y = 199; max_z = 196
}
$script:StairTargetBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 201; min_y = 200; min_z = 192
    max_x = 202; max_y = 200; max_z = 196
}
$script:StairEntries = @(
    [ordered]@{
        id = 'straight'; source_role = 'north_straight'
        offset = [ordered]@{ x = -2; y = 0; z = 0 }
        expected = [ordered]@{
            block = 'minecraft:oak_stairs'; properties = [ordered]@{
                facing = 'east'; half = 'bottom'; shape = 'straight'; waterlogged = 'false'
            }
        }
    }
    [ordered]@{
        id = 'inner_corner'; source_role = 'north_inner_left'
        offset = [ordered]@{ x = 0; y = 0; z = 0 }
        expected = [ordered]@{
            block = 'minecraft:oak_stairs'; properties = [ordered]@{
                facing = 'east'; half = 'bottom'; shape = 'inner_right'; waterlogged = 'false'
            }
        }
    }
    [ordered]@{
        id = 'inner_companion'; source_role = 'west_straight'
        offset = [ordered]@{ x = 0; y = 0; z = 1 }
        expected = [ordered]@{
            block = 'minecraft:oak_stairs'; properties = [ordered]@{
                facing = 'south'; half = 'bottom'; shape = 'straight'; waterlogged = 'false'
            }
        }
    }
    [ordered]@{
        id = 'outer_corner'; source_role = 'north_outer_left'
        offset = [ordered]@{ x = 2; y = 0; z = 1 }
        expected = [ordered]@{
            block = 'minecraft:oak_stairs'; properties = [ordered]@{
                facing = 'east'; half = 'bottom'; shape = 'outer_right'; waterlogged = 'false'
            }
        }
    }
    [ordered]@{
        id = 'outer_companion'; source_role = 'west_straight'
        offset = [ordered]@{ x = 2; y = 0; z = 0 }
        expected = [ordered]@{
            block = 'minecraft:oak_stairs'; properties = [ordered]@{
                facing = 'south'; half = 'bottom'; shape = 'straight'; waterlogged = 'false'
            }
        }
    }
)

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-directional-stairs-capability-gate'; version = '1'
        }
    }
}

function Get-StairStateRole {
    param([Parameter(Mandatory)][object]$State)

    if ((Get-ObjectProperty $State 'block') -cne 'minecraft:oak_stairs') { return $null }
    $properties = Get-ObjectProperty $State 'properties'
    if ((Get-ObjectProperty $properties 'half') -cne 'bottom' -or
        (Get-ObjectProperty $properties 'waterlogged') -cne 'false') { return $null }
    $facing = [string](Get-ObjectProperty $properties 'facing')
    $shape = [string](Get-ObjectProperty $properties 'shape')
    if ($facing -ceq 'north' -and $shape -ceq 'straight') { return 'north_straight' }
    if ($facing -ceq 'north' -and $shape -ceq 'inner_left') { return 'north_inner_left' }
    if ($facing -ceq 'north' -and $shape -ceq 'outer_left') { return 'north_outer_left' }
    if ($facing -ceq 'west' -and $shape -ceq 'straight') { return 'west_straight' }
    return $null
}

function Get-UniqueRecordsByPosition {
    param([Parameter(Mandatory)][object[]]$Records)

    $byPosition = [ordered]@{}
    foreach ($record in $Records) {
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if (-not $byPosition.Contains($key)) {
            $byPosition[$key] = $record
            continue
        }
        $existing = $byPosition[$key]
        if ((ConvertTo-CompactJson (Get-ObjectProperty $existing 'state')) -cne
                (ConvertTo-CompactJson (Get-ObjectProperty $record 'state')) -or
            (Get-ObjectProperty $existing 'placement_state_ref') -cne
                (Get-ObjectProperty $record 'placement_state_ref')) {
            throw "conflicting visible-surface evidence at $key"
        }
    }
    return @($byPosition.Values)
}

function Get-DirectionalStairSources {
    param([Parameter(Mandatory)][object]$State)

    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:oak_stairs' `
        -Bounds $script:StairSourceBounds -Faces $null)
    $unique = @(Get-UniqueRecordsByPosition -Records $records)
    if ($unique.Count -ne 5) {
        throw "Gate D requires exactly five visible source stair cells; found=$($unique.Count)"
    }
    $byRole = [ordered]@{}
    foreach ($record in $unique) {
        $state = Get-ObjectProperty $record 'state'
        $role = Get-StairStateRole -State $state
        $ref = [string](Get-ObjectProperty $record 'placement_state_ref')
        if ($null -eq $role -or
            (Get-ObjectProperty $record 'placement_item') -cne 'minecraft:oak_stairs' -or
            $ref -cnotmatch '^psr_[0-9a-f]{32}$') {
            throw 'Gate D received an ineligible or incomplete source stair record'
        }
        if (-not $byRole.Contains($role)) {
            $byRole[$role] = [Collections.Generic.List[object]]::new()
        }
        $byRole[$role].Add($record)
    }
    foreach ($role in @('north_straight', 'north_inner_left', 'north_outer_left')) {
        if (-not $byRole.Contains($role) -or $byRole[$role].Count -ne 1) {
            throw "Gate D source role must occur once: $role"
        }
    }
    if (-not $byRole.Contains('west_straight') -or $byRole['west_straight'].Count -ne 2) {
        throw 'Gate D requires exactly two west-facing straight companion sources'
    }
    return [ordered]@{
        records = $unique
        refs = [ordered]@{
            north_straight = Get-ObjectProperty $byRole['north_straight'][0] 'placement_state_ref'
            north_inner_left = Get-ObjectProperty $byRole['north_inner_left'][0] 'placement_state_ref'
            north_outer_left = Get-ObjectProperty $byRole['north_outer_left'][0] 'placement_state_ref'
            west_straight = Get-ObjectProperty $byRole['west_straight'][0] 'placement_state_ref'
        }
    }
}

function Get-TransformedStairOffset {
    param([Parameter(Mandatory)][object]$Offset)

    # Gate D transform is mirror=x followed by clockwise rotation=90.
    $x = -[int](Get-ObjectProperty $Offset 'x')
    $y = [int](Get-ObjectProperty $Offset 'y')
    $z = [int](Get-ObjectProperty $Offset 'z')
    return [ordered]@{ x = -$z; y = $y; z = $x }
}

function Get-PositionAbove {
    param([Parameter(Mandatory)][object]$Position)
    return [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        x = [int](Get-ObjectProperty $Position 'x')
        y = [int](Get-ObjectProperty $Position 'y') + 1
        z = [int](Get-ObjectProperty $Position 'z')
    }
}

function Select-StairMatrixFoundation {
    param([Parameter(Mandatory)][object]$State)

    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:smooth_stone' `
        -Bounds $script:StairSupportBounds -Faces @('up'))
    $unique = @(Get-UniqueRecordsByPosition -Records $records)
    $byKey = [ordered]@{}
    foreach ($record in $unique) {
        $stateValue = Get-ObjectProperty $record 'state'
        if ((Get-ObjectProperty $stateValue 'block') -cne 'minecraft:smooth_stone') {
            throw 'Gate D foundation omitted a complete smooth-stone state'
        }
        $byKey[(Get-BlockPositionKey (Get-ObjectProperty $record 'position'))] = $record
    }
    $candidates = [Collections.Generic.List[object]]::new()
    foreach ($anchorSupport in $unique) {
        $anchorPosition = Get-ObjectProperty $anchorSupport 'position'
        $selected = [ordered]@{}
        $valid = $true
        foreach ($spec in $script:StairEntries) {
            $offset = Get-TransformedStairOffset -Offset $spec.offset
            $position = [ordered]@{
                dimension = [string](Get-ObjectProperty $anchorPosition 'dimension')
                x = [int](Get-ObjectProperty $anchorPosition 'x') + [int]$offset.x
                y = [int](Get-ObjectProperty $anchorPosition 'y')
                z = [int](Get-ObjectProperty $anchorPosition 'z') + [int]$offset.z
            }
            $key = Get-BlockPositionKey $position
            if (-not $byKey.Contains($key)) { $valid = $false; break }
            $selected[$spec.id] = $byKey[$key]
        }
        if ($valid) {
            $candidates.Add([pscustomobject]@{
                    anchor = Get-PositionAbove -Position $anchorPosition
                    supports = $selected
                })
        }
    }
    if ($candidates.Count -ne 1) {
        throw "Gate D requires one unique observation-derived foundation; found=$($candidates.Count)"
    }
    return $candidates[0]
}

function New-DirectionalStairsActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Sources,
        [Parameter(Mandatory)][object]$Foundation
    )

    $entries = [Collections.Generic.List[object]]::new()
    foreach ($spec in $script:StairEntries) {
        $support = $Foundation.supports[$spec.id]
        $ref = $Sources.refs[$spec.source_role]
        $entry = [ordered]@{
            id = $spec.id
            offset = $spec.offset
            placement_state_ref = $ref
            support = [ordered]@{
                position = Get-ObjectProperty $support 'position'
                face = 'up'
                expected_state = Get-ObjectProperty $support 'state'
                dependency_entry_id = $null
            }
        }
        if ($entry.placement_state_ref -cne $ref -or
            -not [object]::ReferenceEquals(
                (Get-ObjectProperty $support 'position'), $entry.support.position) -or
            -not [object]::ReferenceEquals(
                (Get-ObjectProperty $support 'state'), $entry.support.expected_state)) {
            throw 'Gate D changed delivery-backed placement or support evidence'
        }
        $entries.Add($entry)
    }
    $aimSupport = $Foundation.supports['inner_corner']
    $faceNode = [ordered]@{
        id = 'face_directional_stairs_matrix'
        op = 'face_known_position'
        target = Get-ObjectProperty $aimSupport 'position'
    }
    $node = [ordered]@{
        id = 'directional_stairs_matrix'
        op = 'apply_known_block_plan'
        anchor = $Foundation.anchor
        transform = [ordered]@{ rotation = 90; mirror = 'x' }
        entries = @($entries)
    }
    return New-ActionRequest -Name 'capability_gate_directional_stairs_matrix' `
        -Capabilities @('camera', 'block_place') -Body @($faceNode, $node) `
        -Budget ([ordered]@{
            max_duration_ms = 75000; max_ticks = 1500
            max_distance_blocks = 0; max_camera_degrees = 440
            max_interactions = 0; max_blocks_broken = 0; max_blocks_placed = 5
        })
}

function Get-ExpectedStairTargets {
    param([Parameter(Mandatory)][object]$Foundation)

    return @($script:StairEntries | ForEach-Object {
            $spec = $_
            $offset = Get-TransformedStairOffset -Offset $spec.offset
            [ordered]@{
                id = $spec.id
                position = [ordered]@{
                    dimension = [string](Get-ObjectProperty $Foundation.anchor 'dimension')
                    x = [int](Get-ObjectProperty $Foundation.anchor 'x') + [int]$offset.x
                    y = [int](Get-ObjectProperty $Foundation.anchor 'y') + [int]$offset.y
                    z = [int](Get-ObjectProperty $Foundation.anchor 'z') + [int]$offset.z
                }
                state = $spec.expected
            }
        })
}

function Assert-ExactFinalStairTargets {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object[]]$Expected
    )

    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:oak_stairs' `
        -Bounds $script:StairTargetBounds -Faces $null)
    $unique = @(Get-UniqueRecordsByPosition -Records $records)
    if ($unique.Count -ne $Expected.Count) {
        throw "Gate D final visible target count mismatch; expected=$($Expected.Count) actual=$($unique.Count)"
    }
    $byKey = [ordered]@{}
    foreach ($record in $unique) {
        $byKey[(Get-BlockPositionKey (Get-ObjectProperty $record 'position'))] = $record
    }
    foreach ($target in $Expected) {
        $key = Get-BlockPositionKey $target.position
        if (-not $byKey.Contains($key) -or
            (ConvertTo-CompactJson (Get-ObjectProperty $byKey[$key] 'state')) -cne
                (ConvertTo-CompactJson $target.state)) {
            throw "Gate D final exact BlockState mismatch at $key"
        }
    }
}

function Assert-DirectionalStairsFixedFive {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) { throw 'Gate D requires one fixed-five verification event' }
    $tools = @((Get-ObjectProperty $events[0] 'tools'))
    if ((Get-ObjectProperty $events[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'Gate D fixed-five evidence has the wrong protocol or tool count'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "Gate D fixed-five tool order mismatch at index $index"
        }
    }
    return [ordered]@{ protocol_version = $script:ProtocolVersion; tools = $tools }
}

function Assert-DirectionalStairsLifecycle {
    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminal = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    if ($accepted.Count -ne 1 -or $terminal.Count -ne 1 -or
        (Get-ObjectProperty $accepted[0] 'action_id') -cne
            (Get-ObjectProperty $terminal[0] 'action_id') -or
        (Get-ObjectProperty $terminal[0] 'state') -cne 'succeeded') {
        throw 'Gate D Action lifecycle is not exactly accepted==succeeded-terminal'
    }
    return [ordered]@{
        accepted = 1; terminal = 1
        action_id = Get-ObjectProperty $accepted[0] 'action_id'
        accepted_equals_terminal = $true
    }
}

function Assert-DirectionalStairsTerminalProof {
    param([Parameter(Mandatory)][object]$Terminal)

    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'Gate D terminal is not succeeded without failure'
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 2 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 2 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 5 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 440) {
        throw 'Gate D terminal progress violates the stationary five-placement budget'
    }
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $evidence = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq
                'construction_complete=5,server_confirmed=5'
        })
    $completed = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'directional_stairs_matrix'
        })
    if ($evidence.Count -ne 1 -or $completed.Count -ne 1 -or
        [Array]::IndexOf($trace, $completed[0]) -le [Array]::IndexOf($trace, $evidence[0])) {
        throw 'Gate D trace does not prove five server-confirmed placements before completion'
    }
    return [ordered]@{
        completion_evidence = Get-ObjectProperty $evidence[0] 'detail'
        completed_node = Get-ObjectProperty $completed[0] 'detail'
        server_confirmed_placements = 5
    }
}

function New-DirectionalStairsOracle {
    param(
        [Parameter(Mandatory)][object[]]$Targets,
        [Parameter(Mandatory)][object[]]$Sources
    )

    return [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        inspector = 'tools/eval/Inspect-McmcpRegion.py'
        dimension = 'minecraft:overworld'
        expected_changed_cell_count = $Targets.Count
        expected_changed_cells = @($Targets | ForEach-Object {
                [ordered]@{
                    id = $_.id; position = $_.position
                    before_state = [ordered]@{ block = 'minecraft:air'; properties = [ordered]@{} }
                    after_state = $_.state
                }
            })
        expected_source = [ordered]@{
            position = Get-ObjectProperty $Sources[0] 'position'
            state = Get-ObjectProperty $Sources[0] 'state'
            changed = $false
        }
        expected_unchanged_sources = @($Sources | ForEach-Object {
                [ordered]@{
                    position = Get-ObjectProperty $_ 'position'
                    state = Get-ObjectProperty $_ 'state'
                }
            })
        reject_unlisted_changes = $true
        expected_extra_mutations = 0
    }
}

function Invoke-DirectionalStairsGateCore {
    $fixedFive = Assert-DirectionalStairsFixedFive
    $initial = Get-FreshState
    $inventoryBefore = Get-InventoryCount -State $initial -Item 'minecraft:oak_stairs'
    if ($inventoryBefore -ne 8) { throw "Gate D fixture requires 8 oak stairs; found=$inventoryBefore" }
    $sources = Get-DirectionalStairSources -State $initial
    $foundation = Select-StairMatrixFoundation -State $initial
    $expectedTargets = @(Get-ExpectedStairTargets -Foundation $foundation)
    $request = New-DirectionalStairsActionRequest -Sources $sources -Foundation $foundation
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 150

    $final = Get-FreshState
    $inventoryAfter = Get-InventoryCount -State $final -Item 'minecraft:oak_stairs'
    if ($inventoryAfter -ne 3) { throw "Gate D inventory delta is not exactly -5; after=$inventoryAfter" }
    Assert-ExactFinalStairTargets -State $final -Expected $expectedTargets
    $finalSources = Get-DirectionalStairSources -State $final
    foreach ($source in $sources.records) {
        $key = Get-BlockPositionKey (Get-ObjectProperty $source 'position')
        $match = @($finalSources.records | Where-Object {
                (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq $key
            })
        if ($match.Count -ne 1 -or
            (ConvertTo-CompactJson (Get-ObjectProperty $match[0] 'state')) -cne
                (ConvertTo-CompactJson (Get-ObjectProperty $source 'state'))) {
            throw "Gate D source changed at $key"
        }
    }
    $lifecycle = Assert-DirectionalStairsLifecycle
    $terminalProof = Assert-DirectionalStairsTerminalProof -Terminal $terminal
    $oracle = New-DirectionalStairsOracle `
        -Targets $expectedTargets -Sources $sources.records
    return [ordered]@{
        gate = 'building-gate-d-directional-stairs'
        fixture_precondition = '/mcmcp_fixture phase4 directional_stairs_matrix'
        fixed_five_surface = $fixedFive
        normal_player_action = 'face_known_position + apply_known_block_plan'
        lifecycle = $lifecycle
        terminal_proof = $terminalProof
        placement_identity = 'delivery_backed_placement_state_ref'
        placement_state_refs_copied_verbatim = $true
        support_evidence_copied_verbatim = $true
        transform = [ordered]@{ rotation = 90; mirror = 'x' }
        exact_targets = $expectedTargets
        inventory_before = $inventoryBefore
        inventory_after = $inventoryAfter
        inventory_delta = $inventoryAfter - $inventoryBefore
        external_oracle_status = 'pending'
        external_oracle = $oracle
    }
}

function Write-DirectionalStairsArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )

    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'building-gate-d-directional-stairs'
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
            (ConvertTo-Json $GateResult.external_oracle -Depth 100), $script:Utf8NoBom)
    }
}

function Invoke-McmcpDirectionalStairsCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-DirectionalStairsGateCore } catch { $primaryFailure = $_ }
    finally { try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ } }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-DirectionalStairsArtifacts -GateResult $gateResult -InputRelease $release `
        -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return [ordered]@{ gate_result = $gateResult; input_release = $release }
}

if (-not $LibraryOnly) {
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    if ([string]::IsNullOrWhiteSpace($script:Bearer) -or
        $script:Bearer.Contains("`r") -or $script:Bearer.Contains("`n")) {
        throw 'MCP token file is empty or malformed'
    }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    Assert-FixedFiveToolSurface
    $result = Invoke-McmcpDirectionalStairsCapabilityGate
    ConvertTo-Json $result -Depth 100
}
