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
$script:StairComponents = @(
    [ordered]@{
        name = 'outer'; ids = @('outer_corner', 'outer_companion')
        pivot = 'outer_companion'
    }
    [ordered]@{
        name = 'inner'; ids = @('inner_corner', 'inner_companion')
        pivot = 'inner_corner'
    }
    [ordered]@{ name = 'straight'; ids = @('straight'); pivot = 'straight' }
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

function New-DirectionalStairsPlanNode {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Sources,
        [Parameter(Mandatory)][object]$Foundation,
        [Parameter(Mandatory)][Collections.IDictionary]$Component,
        [Parameter(Mandatory)][string]$NodeId,
        [Parameter(Mandatory)]
        [ValidateSet('apply_known_block_plan', 'approach_known_placement')][string]$Op
    )

    $specs = @($script:StairEntries | Where-Object { $_.id -cin @($Component.ids) })
    if ($specs.Count -ne @($Component.ids).Count) {
        throw "Gate D component has an unknown or duplicate entry: $($Component.name)"
    }
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($spec in $specs) {
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
    return [ordered]@{
        id = $NodeId
        op = $Op
        anchor = $Foundation.anchor
        transform = [ordered]@{ rotation = 90; mirror = 'x' }
        entries = @($entries)
    }
}

function New-DirectionalStairsActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Sources,
        [Parameter(Mandatory)][object]$Foundation,
        [Parameter(Mandatory)][Collections.IDictionary]$Component
    )

    $node = New-DirectionalStairsPlanNode `
        -Sources $Sources -Foundation $Foundation -Component $Component `
        -NodeId "directional_stairs_$($Component.name)" -Op 'apply_known_block_plan'
    return New-ActionRequest -Name "capability_gate_directional_stairs_$($Component.name)" `
        -Capabilities @('camera', 'block_place') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 75000; max_ticks = 1500
            max_distance_blocks = 0; max_camera_degrees = 400
            max_interactions = 0; max_blocks_broken = 0
            max_blocks_placed = @($node.entries).Count
        })
}

function New-DirectionalStairsApproachRequest {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][Collections.IDictionary]$Sources,
        [Parameter(Mandatory)][object]$Foundation,
        [Parameter(Mandatory)][Collections.IDictionary]$Component
    )

    $node = New-DirectionalStairsPlanNode `
        -Sources $Sources -Foundation $Foundation -Component $Component `
        -NodeId "approach_stairs_$($Component.name)" -Op 'approach_known_placement'
    return New-PrimitiveRequest -Name "approach_stairs_$($Component.name)" `
        -Capabilities @('movement') -Node $node `
        -Distance (Get-PolicyDistanceBudget -State $State) -Camera 0
}

function New-DirectionalStairsFaceRequest {
    param(
        [Parameter(Mandatory)][object]$Foundation,
        [Parameter(Mandatory)][Collections.IDictionary]$Component
    )

    $aimSupport = $Foundation.supports[[string]$Component.pivot]
    $node = [ordered]@{
        id = "face_directional_stairs_$($Component.name)"
        op = 'face_known_block_face'
        target = Get-ObjectProperty $aimSupport 'position'
        face = Get-ObjectProperty $aimSupport 'face'
        expected_block = Get-ObjectProperty $aimSupport 'block'
    }
    return New-PrimitiveRequest -Name "face_directional_stairs_$($Component.name)" `
        -Capabilities @('camera') -Node $node -Duration 10000 -Ticks 200 `
        -Distance 0 -Camera 80
}

function Refresh-StairComponentFoundation {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Baseline,
        [Parameter(Mandatory)][Collections.IDictionary]$Component
    )

    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:smooth_stone' `
        -Bounds $script:StairSupportBounds -Faces @('up'))
    $byKey = [ordered]@{}
    foreach ($record in @(Get-UniqueRecordsByPosition -Records $records)) {
        $byKey[(Get-BlockPositionKey (Get-ObjectProperty $record 'position'))] = $record
    }
    $supports = [ordered]@{}
    foreach ($id in @($Component.ids)) {
        $baselineSupport = $Baseline.supports[[string]$id]
        $key = Get-BlockPositionKey (Get-ObjectProperty $baselineSupport 'position')
        if (-not $byKey.Contains($key)) {
            throw "Gate D component support is not freshly visible: $id at $key"
        }
        $fresh = $byKey[$key]
        if ((ConvertTo-CompactJson (Get-ObjectProperty $fresh 'state')) -cne
                (ConvertTo-CompactJson (Get-ObjectProperty $baselineSupport 'state'))) {
            throw "Gate D component support changed before placement: $id at $key"
        }
        $supports[[string]$id] = $fresh
    }
    return [pscustomobject]@{ anchor = $Baseline.anchor; supports = $supports }
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
    $expectedKeys = @($Expected | ForEach-Object { Get-BlockPositionKey $_.position })
    $unique = @($unique | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -cin $expectedKeys
        })
    if ($unique.Count -ne $Expected.Count) {
        throw "Gate D component visible target count mismatch; expected=$($Expected.Count) actual=$($unique.Count)"
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
    $events = @($script:GateEvents)
    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminal = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    $expectedActions = $script:StairComponents.Count * 3
    if ($accepted.Count -ne $expectedActions -or $terminal.Count -ne $expectedActions) {
        throw 'Gate D does not have exactly one approach/face/build lifecycle trio per component'
    }
    for ($index = 0; $index -lt $expectedActions; $index++) {
        if ((Get-ObjectProperty $accepted[$index] 'action_id') -cne
                (Get-ObjectProperty $terminal[$index] 'action_id') -or
            (Get-ObjectProperty $terminal[$index] 'state') -cne 'succeeded') {
            throw "Gate D component lifecycle mismatch at index $index"
        }
    }
    $barriers = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'observation_frame_advanced'
        })
    if ($barriers.Count -ne $script:StairComponents.Count * 2) {
        throw 'Gate D does not have exactly two fresh-observation barriers per component'
    }
    for ($componentIndex = 0; $componentIndex -lt $script:StairComponents.Count; $componentIndex++) {
        $base = $componentIndex * 3
        foreach ($transition in @(
                [ordered]@{ terminal = $terminal[$base]; accepted = $accepted[$base + 1] },
                [ordered]@{ terminal = $terminal[$base + 1]; accepted = $accepted[$base + 2] }
            )) {
            $terminalIndex = [Array]::IndexOf($events, $transition.terminal)
            $acceptedIndex = [Array]::IndexOf($events, $transition.accepted)
            $between = @($barriers | Where-Object {
                    $barrierIndex = [Array]::IndexOf($events, $_)
                    $barrierIndex -gt $terminalIndex -and $barrierIndex -lt $acceptedIndex
                })
            if ($terminalIndex -lt 0 -or $acceptedIndex -lt 0 -or $between.Count -ne 1) {
                throw "Gate D lifecycle lacks a fresh observation before component phase $base"
            }
        }
    }
    return [ordered]@{
        accepted = $accepted.Count; terminal = $terminal.Count
        action_ids = @($accepted | ForEach-Object { Get-ObjectProperty $_ 'action_id' })
        accepted_equals_terminal = $true
        fresh_observation_barriers = $barriers.Count
    }
}

function Assert-DirectionalStairsApproachTerminalProof {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [Parameter(Mandatory)][double]$MaximumDistance
    )

    $progress = Get-ObjectProperty $Terminal 'progress'
    $distance = [double](Get-ObjectProperty $progress 'distance_travelled')
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        $distance -lt 0 -or $distance -gt $MaximumDistance -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        throw 'Gate D approach terminal violates the movement-only policy budget'
    }
    return [ordered]@{
        distance_travelled = $distance
        maximum_distance = $MaximumDistance
        proof_scope = 'succeeded_terminal_and_movement_budget_only'
        reach_inferred_from_distance = $false
        fresh_support_admission_required = $true
    }
}

function Assert-DirectionalStairsFaceTerminalProof {
    param([Parameter(Mandatory)][object]$Terminal)

    $progress = Get-ObjectProperty $Terminal 'progress'
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 80) {
        throw 'Gate D face terminal violates the camera-only budget'
    }
    return [ordered]@{ camera_degrees = Get-ObjectProperty $progress 'camera_degrees' }
}

function Assert-DirectionalStairsTerminalProof {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [Parameter(Mandatory)][int]$ExpectedPlacements,
        [Parameter(Mandatory)][string]$ExpectedNodeId
    )

    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'Gate D terminal is not succeeded without failure'
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne $ExpectedPlacements -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 400) {
        throw 'Gate D terminal progress violates the stationary five-placement budget'
    }
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $evidence = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq
                "construction_complete=$ExpectedPlacements,server_confirmed=$ExpectedPlacements"
        })
    $completed = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq $ExpectedNodeId
        })
    if ($evidence.Count -ne 1 -or $completed.Count -ne 1 -or
        [Array]::IndexOf($trace, $completed[0]) -le [Array]::IndexOf($trace, $evidence[0])) {
        throw 'Gate D trace does not prove five server-confirmed placements before completion'
    }
    return [ordered]@{
        completion_evidence = Get-ObjectProperty $evidence[0] 'detail'
        completed_node = Get-ObjectProperty $completed[0] 'detail'
        server_confirmed_placements = $ExpectedPlacements
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
    $initialSources = Get-DirectionalStairSources -State $initial
    $foundation = Select-StairMatrixFoundation -State $initial
    $expectedTargets = @(Get-ExpectedStairTargets -Foundation $foundation)
    $approachProofs = [Collections.Generic.List[object]]::new()
    $faceProofs = [Collections.Generic.List[object]]::new()
    $terminalProofs = [Collections.Generic.List[object]]::new()
    foreach ($component in $script:StairComponents) {
        $preApproachState = Get-FreshState
        $preApproachFoundation = Refresh-StairComponentFoundation `
            -State $preApproachState -Baseline $foundation -Component $component
        $approachRequest = New-DirectionalStairsApproachRequest `
            -State $preApproachState -Sources $initialSources `
            -Foundation $preApproachFoundation -Component $component
        $approachTerminal = Invoke-ActionRequest `
            -Request $approachRequest -WallTimeoutSeconds 90
        $maximumApproachDistance = Get-PolicyDistanceBudget -State $preApproachState
        $approachProofs.Add((Assert-DirectionalStairsApproachTerminalProof `
                    -Terminal $approachTerminal -MaximumDistance $maximumApproachDistance))
        $approachBarrierState = Get-FreshState
        $approachBarrier = Get-ObservationFrameId -State $approachBarrierState
        $preFaceState = Wait-ForObservationFrameAdvance -PreviousFrameId $approachBarrier
        $preFaceFoundation = Refresh-StairComponentFoundation `
            -State $preFaceState -Baseline $foundation -Component $component
        $faceRequest = New-DirectionalStairsFaceRequest `
            -Foundation $preFaceFoundation -Component $component
        $faceTerminal = Invoke-ActionRequest -Request $faceRequest -WallTimeoutSeconds 30
        $faceProofs.Add((Assert-DirectionalStairsFaceTerminalProof -Terminal $faceTerminal))
        $faceBarrierState = Get-FreshState
        $faceBarrier = Get-ObservationFrameId -State $faceBarrierState
        $componentState = Wait-ForObservationFrameAdvance -PreviousFrameId $faceBarrier
        $componentFoundation = Refresh-StairComponentFoundation `
            -State $componentState -Baseline $foundation -Component $component
        $request = New-DirectionalStairsActionRequest `
            -Sources $initialSources -Foundation $componentFoundation -Component $component
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 150
        $terminalProofs.Add((Assert-DirectionalStairsTerminalProof `
                    -Terminal $terminal -ExpectedPlacements @($component.ids).Count `
                    -ExpectedNodeId "directional_stairs_$($component.name)"))
        $componentFinal = Get-FreshState
        $componentExpected = @($expectedTargets | Where-Object {
                $_.id -cin @($component.ids)
            })
        Assert-ExactFinalStairTargets -State $componentFinal -Expected $componentExpected
    }

    $final = Get-FreshState
    $inventoryAfter = Get-InventoryCount -State $final -Item 'minecraft:oak_stairs'
    if ($inventoryAfter -ne 3) { throw "Gate D inventory delta is not exactly -5; after=$inventoryAfter" }
    $lifecycle = Assert-DirectionalStairsLifecycle
    $oracle = New-DirectionalStairsOracle `
        -Targets $expectedTargets -Sources $initialSources.records
    return [ordered]@{
        gate = 'building-gate-d-directional-stairs'
        fixture_precondition = '/mcmcp_fixture phase4 directional_stairs_matrix'
        fixed_five_surface = $fixedFive
        normal_player_action = 'three observation-derived approach + face + component block-plan trios'
        lifecycle = $lifecycle
        approach_terminal_proof = @($approachProofs)
        face_terminal_proof = @($faceProofs)
        terminal_proof = @($terminalProofs)
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
