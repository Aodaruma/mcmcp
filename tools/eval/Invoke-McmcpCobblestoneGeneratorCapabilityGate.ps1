[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Transport, fixed-five enforcement, long-poll Action waiting, and cleanup are shared only
# with the ordinary acceptance runner. This file owns the generator admission and oracle.
$cobbleArtifactDirectory = $ArtifactDirectory
$cobbleTokenPath = $TokenPath
$cobbleEndpoint = $Endpoint
$cobbleLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $cobbleArtifactDirectory `
    -TokenPath $cobbleTokenPath -Endpoint $cobbleEndpoint -LibraryOnly
$ArtifactDirectory = $cobbleArtifactDirectory
$TokenPath = $cobbleTokenPath
$Endpoint = $cobbleEndpoint
$LibraryOnly = $cobbleLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

$script:CobbleTargetBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 199; min_y = 201; min_z = 200
    max_x = 199; max_y = 201; max_z = 200
}
$script:CobbleWorkspaceBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 196; min_y = 199; min_z = 197
    max_x = 201; max_y = 202; max_z = 201
}
$script:CobbleExpectedPosition = [ordered]@{
    dimension = 'minecraft:overworld'; x = 199; y = 201; z = 200
}
$script:CobbleExpectedStand = [ordered]@{ x = 199.5; y = 201.0; z = 199.5 }
$script:CobbleBreakCount = 8
$script:CobbleMaximumAttempts = 16
$script:CobbleGeneratorDurationTicks = 3600
$script:CobbleRegenerationWaitTicks = 100

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-cobblestone-generator-capability-gate'; version = '1'
        }
    }
}

function Assert-CobbleFixedFive {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) {
        throw 'cobblestone gate requires exactly one fixed-five verification event'
    }
    $tools = @((Get-ObjectProperty $events[0] 'tools'))
    if ((Get-ObjectProperty $events[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'cobblestone gate fixed-five protocol or tool count mismatch'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "cobblestone gate fixed-five tool order mismatch at index $index"
        }
    }
    return [ordered]@{ protocol_version = $script:ProtocolVersion; tools = $tools }
}

function Assert-CobblePlayerState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase
    )

    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    foreach ($axis in @('x', 'y', 'z')) {
        if ([Math]::Abs([double](Get-ObjectProperty $position $axis) -
                [double]$script:CobbleExpectedStand[$axis]) -gt 0.0001) {
            throw "$Phase changed the stationary player $axis coordinate"
        }
    }
    if ([Math]::Abs([double](Get-ObjectProperty $world 'health') - $ExpectedHealth) -gt 0.0001) {
        throw "$Phase changed player health"
    }
}

function Get-OnlyGeneratedCobblestoneSurface {
    param([Parameter(Mandatory)][object]$State, [switch]$AllowMissing)

    # The player stands immediately north for deterministic Vanilla pickup, so bind the exact
    # player-facing surface instead of accepting an omnidirectional top-face record.
    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:cobblestone' `
        -Bounds $script:CobbleTargetBounds -Faces @('north') -AllowMissing:$AllowMissing)
    if ($records.Count -eq 0 -and $AllowMissing) { return $null }
    $unique = [ordered]@{}
    foreach ($record in $records) {
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if (-not $unique.Contains($key)) { $unique[$key] = $record }
    }
    if ($unique.Count -ne 1) {
        throw "cobblestone gate requires one generated target cell; found=$($unique.Count)"
    }
    $surface = @($unique.Values)[0]
    $position = Get-ObjectProperty $surface 'position'
    $state = Get-ObjectProperty $surface 'state'
    if ((ConvertTo-CompactJson $position) -cne
            (ConvertTo-CompactJson $script:CobbleExpectedPosition) -or
        (Get-ObjectProperty $state 'block') -cne 'minecraft:cobblestone') {
        throw 'generated cobblestone evidence has the wrong target or block state'
    }
    if ((ConvertTo-CompactJson (Get-ObjectProperty $state 'properties')) -cne '{}') {
        throw 'generated cobblestone evidence is not one complete property-empty state'
    }
    $face = [string](Get-ObjectProperty $surface 'face')
    if ($face -cnotin @('down', 'up', 'north', 'south', 'west', 'east')) {
        throw 'generated cobblestone evidence omitted a valid visible face'
    }
    return $surface
}

function Wait-FreshGeneratedCobblestone {
    param(
        [AllowNull()][string]$PreviousFrameId,
        [ValidateRange(1, 160)][int]$MaximumPolls = 120,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50,
        [switch]$SuppressBreakObservationEvent
    )

    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $state = Get-FreshState
        $frameId = Get-ObservationFrameId -State $state
        if (($null -eq $PreviousFrameId -or $frameId -cne $PreviousFrameId)) {
            $surface = Get-OnlyGeneratedCobblestoneSurface -State $state -AllowMissing
            if ($null -ne $surface) {
                if (-not $SuppressBreakObservationEvent) {
                    Add-GateEvent -Event 'cobblestone_break_observation' -Detail ([ordered]@{
                            frame_id = $frameId
                            observed_tick = Get-ObjectProperty $surface 'observed_tick'
                            world_revision = Get-ObjectProperty $surface 'world_revision'
                            poll = $poll
                        })
                }
                return [pscustomobject]@{
                    state = $state; surface = $surface; frame_id = $frameId; poll = $poll
                }
            }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
        }
    }
    throw 'Vanilla fluid updates did not expose a fresh regenerated cobblestone in time'
}

function New-CobblestoneBreakRequest {
    param(
        [Parameter(Mandatory)][object]$Surface,
        [ValidateRange(1, 8)][int]$MinimumInventoryCount
    )

    $target = Get-ObjectProperty $Surface 'position'
    $expectedState = Get-ObjectProperty $Surface 'state'
    $face = [string](Get-ObjectProperty $Surface 'face')
    $node = [ordered]@{
        id = "break_cobblestone_$MinimumInventoryCount"
        op = 'break_known_block'
        target = $target
        face = $face
        expected_state = $expectedState
        tool_item = 'minecraft:iron_pickaxe'
        expected_drop = 'minecraft:cobblestone'
        minimum_inventory_count = $MinimumInventoryCount
    }
    if (-not [object]::ReferenceEquals($target, $node.target) -or
        -not [object]::ReferenceEquals($expectedState, $node.expected_state) -or
        $node.face -cne $face) {
        throw 'cobblestone request changed delivery-backed target evidence'
    }
    return New-ActionRequest -Name "capability_gate_cobblestone_$MinimumInventoryCount" `
        -Capabilities @('camera', 'block_break') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 15000; max_ticks = 300
            max_distance_blocks = 0; max_camera_degrees = 360
            max_interactions = 0; max_blocks_broken = 1; max_blocks_placed = 0
        })
}

function New-KnownCobblestoneGeneratorFaceRequest {
    param([Parameter(Mandatory)][object]$Surface)

    $target = Get-ObjectProperty $Surface 'position'
    $node = [ordered]@{
        id = 'face_cobblestone_generator'
        op = 'face_known_position'
        target = $target
    }
    if (-not [object]::ReferenceEquals($target, $node.target)) {
        throw 'known generator face request changed its delivery-backed target'
    }
    return New-PrimitiveRequest -Name 'capability_gate_face_cobblestone_generator' `
        -Capabilities @('camera') -Node $node -Duration 15000 -Ticks 300 `
        -Distance 0 -Camera 360
}

function New-KnownCobblestoneGeneratorRequest {
    param([Parameter(Mandatory)][object]$Surface)

    $target = Get-ObjectProperty $Surface 'position'
    $expectedState = Get-ObjectProperty $Surface 'state'
    $face = [string](Get-ObjectProperty $Surface 'face')
    $node = [ordered]@{
        id = 'operate_cobblestone_generator'
        op = 'operate_known_cobblestone_generator'
        target = $target
        face = $face
        expected_state = $expectedState
        tool_item = 'minecraft:iron_pickaxe'
        expected_drop = 'minecraft:cobblestone'
        minimum_inventory_count = $script:CobbleBreakCount
        max_breaks = $script:CobbleBreakCount
        regeneration_wait_ticks = $script:CobbleRegenerationWaitTicks
        max_operation_duration_ticks = $script:CobbleGeneratorDurationTicks
    }
    if (-not [object]::ReferenceEquals($target, $node.target) -or
        -not [object]::ReferenceEquals($expectedState, $node.expected_state) -or
        $node.face -cne $face) {
        throw 'known generator request changed delivery-backed target evidence'
    }
    return New-ActionRequest -Name 'capability_gate_operate_known_cobblestone_generator' `
        -Capabilities @('block_break') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 50 * $script:CobbleGeneratorDurationTicks
            max_ticks = $script:CobbleGeneratorDurationTicks
            max_distance_blocks = 0; max_camera_degrees = 0
            max_interactions = 0; max_blocks_broken = $script:CobbleBreakCount
            max_blocks_placed = 0
        })
}

function Assert-KnownCobblestoneGeneratorFaceTerminal {
    param([Parameter(Mandatory)][object]$Terminal)

    $progress = Get-ObjectProperty $Terminal 'progress'
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 360 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        @((Get-ObjectProperty $Terminal 'effects')).Count -ne 0) {
        throw 'known generator face terminal violates its camera-only budget'
    }
}

function Assert-KnownCobblestoneGeneratorTerminal {
    param([Parameter(Mandatory)][object]$Terminal)

    $progress = Get-ObjectProperty $Terminal 'progress'
    $effects = @((Get-ObjectProperty $Terminal 'effects'))
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne $script:CobbleBreakCount -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        $effects.Count -ne $script:CobbleBreakCount) {
        throw 'known generator terminal violates its one-Action stationary budget'
    }
    $proofs = [Collections.Generic.List[object]]::new()
    $lastInventoryCount = 0
    for ($index = 0; $index -lt $effects.Count; $index++) {
        $effect = $effects[$index]
        $before = Get-ObjectProperty $effect 'observed_before'
        $after = Get-ObjectProperty $effect 'observed_after'
        $cycle = $index + 1
        $inventoryCount = [int](Get-ObjectProperty $after 'inventory_count')
        if ([int](Get-ObjectProperty $effect 'seq') -ne $cycle -or
            (Get-ObjectProperty $effect 'node_id') -cne 'operate_cobblestone_generator' -or
            (Get-ObjectProperty $effect 'kind') -cne 'block_break' -or
            (Get-ObjectProperty $effect 'subject') -cne
                'block:minecraft:overworld:199,201,200' -or
            (Get-ObjectProperty $effect 'verification') -cne 'confirmed' -or
            (Get-ObjectProperty $before 'block') -cne 'minecraft:cobblestone' -or
            (ConvertTo-CompactJson (Get-ObjectProperty $before 'properties')) -cne '{}' -or
            [int](Get-ObjectProperty $before 'cycle') -ne $cycle -or
            (Get-ObjectProperty $after 'block') -cne 'minecraft:air' -or
            (ConvertTo-CompactJson (Get-ObjectProperty $after 'properties')) -cne '{}' -or
            $inventoryCount -lt $lastInventoryCount -or
            $inventoryCount -gt $script:CobbleBreakCount) {
            throw "known generator cycle $cycle lacks one confirmed stationary checkpoint"
        }
        $proofs.Add([ordered]@{
                cycle = $cycle; inventory_count = $inventoryCount
                verification = Get-ObjectProperty $effect 'verification'
                client_tick = Get-ObjectProperty $effect 'client_tick'
                world_revision = Get-ObjectProperty $effect 'world_revision'
            })
        $lastInventoryCount = $inventoryCount
    }
    if ($lastInventoryCount -ne $script:CobbleBreakCount) {
        throw 'known generator final checkpoint does not prove its absolute inventory goal'
    }
    return @($proofs)
}

function Assert-CobblestoneBreakTerminal {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [ValidateRange(1, 16)][int]$Attempt,
        [ValidateRange(1, 8)][int]$MinimumInventoryCount
    )

    $progress = Get-ObjectProperty $Terminal 'progress'
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 1 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 360) {
        throw "cobblestone attempt $Attempt terminal violates the one-break stationary budget"
    }
    $effects = @((Get-ObjectProperty $Terminal 'effects'))
    if ($effects.Count -ne 1) {
        throw "cobblestone attempt $Attempt must publish exactly one effect"
    }
    $effect = $effects[0]
    $before = Get-ObjectProperty $effect 'observed_before'
    $after = Get-ObjectProperty $effect 'observed_after'
    if ([int](Get-ObjectProperty $effect 'seq') -ne 1 -or
        (Get-ObjectProperty $effect 'node_id') -cne "break_cobblestone_$MinimumInventoryCount" -or
        (Get-ObjectProperty $effect 'kind') -cne 'block_break' -or
        (Get-ObjectProperty $effect 'subject') -cne
            'block:minecraft:overworld:199,201,200' -or
        (Get-ObjectProperty $effect 'verification') -cne 'confirmed' -or
        (Get-ObjectProperty $before 'block') -cne 'minecraft:cobblestone' -or
        (Get-ObjectProperty $before 'expected_drop') -cne 'minecraft:cobblestone' -or
        [int](Get-ObjectProperty $before 'minimum_inventory_count') -ne $MinimumInventoryCount -or
        (Get-ObjectProperty $after 'block') -cne 'minecraft:air' -or
        [int](Get-ObjectProperty $after 'inventory_count') -ne $MinimumInventoryCount) {
        throw "cobblestone attempt $Attempt lacks one confirmed break/pickup effect"
    }
    return [ordered]@{
        attempt = $Attempt
        minimum_inventory_count = $MinimumInventoryCount
        node_id = Get-ObjectProperty $effect 'node_id'
        verification = Get-ObjectProperty $effect 'verification'
        inventory_count = Get-ObjectProperty $after 'inventory_count'
        client_tick = Get-ObjectProperty $effect 'client_tick'
        world_revision = Get-ObjectProperty $effect 'world_revision'
    }
}

function Test-CobblestoneLostDropTerminal {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [ValidateRange(1, 8)][int]$MinimumInventoryCount
    )

    $failure = Get-ObjectProperty $Terminal 'failure'
    $progress = Get-ObjectProperty $Terminal 'progress'
    $effects = @((Get-ObjectProperty $Terminal 'effects'))
    $evidence = @((Get-ObjectProperty $failure 'evidence'))
    if ((Get-ObjectProperty $Terminal 'state') -cne 'failed' -or
        (Get-ObjectProperty $failure 'code') -cne 'SERVER_DENIED_OR_DESYNC' -or
        -not [bool](Get-ObjectProperty $failure 'recoverable') -or
        $evidence.Count -ne 1 -or $evidence[0] -cne 'break_not_server_confirmed' -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 0 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt 360 -or
        $effects.Count -ne 1) {
        return $false
    }
    $effect = $effects[0]
    $before = Get-ObjectProperty $effect 'observed_before'
    $after = Get-ObjectProperty $effect 'observed_after'
    if ($null -eq $before -or $null -eq $after) { return $false }
    $inventoryProperty = @($after.PSObject.Properties | Where-Object Name -CEQ 'inventory_count')
    return [int](Get-ObjectProperty $effect 'seq') -eq 1 -and
        (Get-ObjectProperty $effect 'node_id') -ceq "break_cobblestone_$MinimumInventoryCount" -and
        (Get-ObjectProperty $effect 'kind') -ceq 'block_break' -and
        (Get-ObjectProperty $effect 'subject') -ceq 'block:minecraft:overworld:199,201,200' -and
        (Get-ObjectProperty $effect 'verification') -ceq 'confirmed' -and
        (Get-ObjectProperty $before 'block') -ceq 'minecraft:cobblestone' -and
        (Get-ObjectProperty $before 'expected_drop') -ceq 'minecraft:cobblestone' -and
        [int](Get-ObjectProperty $before 'minimum_inventory_count') -eq $MinimumInventoryCount -and
        (Get-ObjectProperty $after 'block') -ceq 'minecraft:air' -and
        $inventoryProperty.Count -eq 0
}

function Get-CobblestoneWorkspaceLooseItems {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:item')
            position_bounds = $script:CobbleWorkspaceBounds
        }))
    foreach ($record in $records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_entity' -or
            (Get-ObjectProperty $record 'entity_type') -cne 'minecraft:item') {
            throw 'cobblestone loose-item filter returned an out-of-filter record'
        }
    }
    return @($records)
}

function Resolve-CobblestoneLooseDropRecovery {
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$VisibleItems)
    $items = @($VisibleItems)
    if ($items.Count -eq 0) {
        return [pscustomobject]@{ mode = 'lost_drop_retry'; item = $null }
    }
    if ($items.Count -eq 1 -and
        (Get-ObjectProperty $items[0] 'displayed_item') -ceq 'minecraft:cobblestone') {
        return [pscustomobject]@{ mode = 'active_collect'; item = $items[0] }
    }
    throw "cobblestone recovery exposed unsupported loose items: count=$($items.Count)"
}

function Assert-NoVisibleLooseItems {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-CobblestoneWorkspaceLooseItems -State $State)
    if ($records.Count -ne 0) {
        throw "cobblestone gate left $($records.Count) visible loose item entities"
    }
}

function Wait-CobblestoneSuccessfulPickupSettlement {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [ValidateRange(1, 8)][int]$ExpectedInventoryCount,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase,
        [ValidateRange(1, 40)][int]$MaximumPolls = 20,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        Assert-CobblePlayerState -State $state -ExpectedHealth $ExpectedHealth -Phase $Phase
        $inventoryCount = Get-InventoryCount -State $state -Item 'minecraft:cobblestone'
        if ($inventoryCount -ne $ExpectedInventoryCount) {
            throw "$Phase inventory count is $inventoryCount; expected=$ExpectedInventoryCount"
        }
        $looseItems = @(Get-CobblestoneWorkspaceLooseItems -State $state)
        if ($looseItems.Count -eq 0) {
            Add-GateEvent -Event 'cobblestone_pickup_settled' -Detail ([ordered]@{
                    phase = $Phase
                    frame_id = Get-ObservationFrameId -State $state
                    polls = $poll
                    inventory_count = $inventoryCount
                    visible_loose_items = 0
                })
            return [pscustomobject]@{ state = $state; polls = $poll }
        }
        # Only one transient cobblestone rendering can be a delayed entity-removal ACK.
        [void](Resolve-CobblestoneLooseDropRecovery -VisibleItems $looseItems)
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "$Phase loose cobblestone did not disappear within $MaximumPolls bounded polls"
}

function Wait-CobblestoneFailedBreakStabilization {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [ValidateRange(0, 7)][int]$InventoryBefore,
        [ValidateRange(1, 8)][int]$InventoryAfter,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase,
        [ValidateRange(2, 5)][int]$MaximumPolls = 3,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    $initialFrameId = Get-ObservationFrameId -State $state
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        Assert-CobblePlayerState -State $state -ExpectedHealth $ExpectedHealth -Phase $Phase
        $inventoryCount = Get-InventoryCount -State $state -Item 'minecraft:cobblestone'
        $looseItems = @(Get-CobblestoneWorkspaceLooseItems -State $state)
        if ($inventoryCount -eq $InventoryAfter) {
            $settled = Wait-CobblestoneSuccessfulPickupSettlement -InitialState $state `
                -ExpectedInventoryCount $InventoryAfter -ExpectedHealth $ExpectedHealth `
                -Phase "$Phase delayed passive pickup"
            return [pscustomobject]@{
                mode = 'delayed_passive_pickup'; state = $settled.state
                item = $null; stabilization_polls = $poll; settlement_polls = $settled.polls
            }
        }
        if ($inventoryCount -ne $InventoryBefore) {
            throw "$Phase inventory changed to an unsupported count: $inventoryCount"
        }
        $recovery = Resolve-CobblestoneLooseDropRecovery -VisibleItems $looseItems
        if ((Get-ObjectProperty $recovery 'mode') -ceq 'lost_drop_retry') {
            return [pscustomobject]@{
                mode = 'lost_drop_retry'; state = $state; item = $null
                stabilization_polls = $poll; settlement_polls = 0
            }
        }
        if ($poll -eq $MaximumPolls) {
            if ((Get-ObservationFrameId -State $state) -ceq $initialFrameId) {
                throw "$Phase did not obtain a fresh stabilization frame"
            }
            return [pscustomobject]@{
                mode = 'active_collect'; state = $state
                item = Get-ObjectProperty $recovery 'item'
                stabilization_polls = $poll; settlement_polls = 0
            }
        }
        Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
        $state = Get-FreshState
    }
    throw "$Phase stabilization exhausted without a closed result"
}

function New-CobblestoneDropCollectionRequest {
    param(
        [Parameter(Mandatory)][object]$Record,
        [ValidateRange(1, 16)][int]$Attempt
    )
    $target = Get-ObjectProperty $Record 'position'
    $node = [ordered]@{
        id = "collect_cobble_$Attempt"
        op = 'collect_visible_item'
        displayed_item = Get-ObjectProperty $Record 'displayed_item'
        target = $target
    }
    if ($node.displayed_item -cne 'minecraft:cobblestone' -or
        -not [object]::ReferenceEquals($target, $node.target)) {
        throw 'cobblestone collection changed its delivery-backed item evidence'
    }
    return New-PrimitiveRequest -Name "capability_gate_collect_cobblestone_$Attempt" `
        -Capabilities @('movement') -Node $node -Duration 30000 -Ticks 600 `
        -Distance 0 -Camera 0
}

function Assert-CobblestoneCollectionTerminal {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [ValidateRange(1, 16)][int]$Attempt,
        [ValidateRange(0, 7)][int]$InventoryBefore,
        [ValidateRange(1, 8)][int]$InventoryAfter
    )
    $progress = Get-ObjectProperty $Terminal 'progress'
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $expectedEvidence = "item_pickup=minecraft:cobblestone,inventory_before=$InventoryBefore,inventory_after=$InventoryAfter"
    $pickupEvidence = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq $expectedEvidence
        })
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        @((Get-ObjectProperty $Terminal 'effects')).Count -ne 0 -or
        $pickupEvidence.Count -ne 1) {
        throw "cobblestone attempt $Attempt collection terminal lacks exact stationary pickup proof"
    }
}

function Assert-CobblestoneLifecycle {
    param(
        [ValidateRange(8, 16)][int]$TotalAttempts,
        [ValidateRange(0, 8)][int]$LostDrops,
        [ValidateRange(0, 8)][int]$RecoveredDrops,
        [ValidateRange(0, 8)][int]$ActiveCollections
    )
    if ($TotalAttempts -ne ($script:CobbleBreakCount + $LostDrops)) {
        throw 'cobblestone attempt count does not equal pickups plus qualified lost drops'
    }
    $events = @($script:GateEvents)
    $accepted = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminal = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    $observations = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'cobblestone_break_observation'
        })
    $lostDropEvents = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'cobblestone_lost_drop_reobserved'
        })
    $recoveredDropEvents = @($events | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'cobblestone_loose_drop_recovered'
        })
    $breakAccepted = @($accepted | Where-Object {
            (Get-ObjectProperty $_ 'program') -cmatch '^capability_gate_cobblestone_[1-8]$'
        })
    $collectAccepted = @($accepted | Where-Object {
            (Get-ObjectProperty $_ 'program') -cmatch '^capability_gate_collect_cobblestone_([1-9]|1[0-6])$'
        })
    $terminalByActionId = @{}
    foreach ($entry in $terminal) {
        $actionId = [string](Get-ObjectProperty $entry 'action_id')
        if ($terminalByActionId.ContainsKey($actionId)) {
            throw 'cobblestone gate published duplicate terminal action ids'
        }
        $terminalByActionId[$actionId] = $entry
    }
    if ($ActiveCollections -gt $RecoveredDrops -or
        $accepted.Count -ne ($TotalAttempts + $ActiveCollections) -or
        $terminal.Count -ne ($TotalAttempts + $ActiveCollections) -or
        $breakAccepted.Count -ne $TotalAttempts -or
        $collectAccepted.Count -ne $ActiveCollections -or
        $observations.Count -ne $TotalAttempts -or
        $lostDropEvents.Count -ne $LostDrops -or
        $recoveredDropEvents.Count -ne $RecoveredDrops) {
        throw 'cobblestone gate lifecycle count does not match its bounded attempts'
    }
    $frameIds = @($observations | ForEach-Object {
            [string](Get-ObjectProperty $_ 'frame_id')
        } | Sort-Object -Unique)
    if ($frameIds.Count -ne $TotalAttempts) {
        throw 'cobblestone gate reused an observation frame between breaks'
    }
    $breakTerminals = @($breakAccepted | ForEach-Object {
            $terminalByActionId[[string](Get-ObjectProperty $_ 'action_id')]
        })
    $collectTerminals = @($collectAccepted | ForEach-Object {
            $terminalByActionId[[string](Get-ObjectProperty $_ 'action_id')]
        })
    $breakSucceeded = @($breakTerminals | Where-Object {
            (Get-ObjectProperty $_ 'state') -ceq 'succeeded'
        })
    $breakFailed = @($breakTerminals | Where-Object {
            (Get-ObjectProperty $_ 'state') -ceq 'failed'
        })
    $collectSucceeded = @($collectTerminals | Where-Object {
            (Get-ObjectProperty $_ 'state') -ceq 'succeeded'
        })
    if ($breakSucceeded.Count -ne ($script:CobbleBreakCount - $RecoveredDrops) -or
        $breakFailed.Count -ne ($LostDrops + $RecoveredDrops) -or
        $collectSucceeded.Count -ne $ActiveCollections) {
        throw 'cobblestone gate terminal states do not match pickup and lost-drop counts'
    }
    for ($index = 0; $index -lt $TotalAttempts; $index++) {
        $breakActionId = [string](Get-ObjectProperty $breakAccepted[$index] 'action_id')
        if (-not $terminalByActionId.ContainsKey($breakActionId) -or
            [Array]::IndexOf($events, $observations[$index]) -gt
                [Array]::IndexOf($events, $breakAccepted[$index])) {
            throw "cobblestone gate lifecycle mismatch at break $($index + 1)"
        }
        if ($index -gt 0 -and
            [Array]::IndexOf($events, $observations[$index]) -lt
                [Array]::IndexOf($events, $breakTerminals[$index - 1])) {
            throw "cobblestone break $($index + 1) was not reobserved after the prior terminal"
        }
    }
    return [ordered]@{
        accepted = $accepted.Count
        terminal = $terminal.Count
        fresh_observations = $observations.Count
        unique_frame_ids = $frameIds.Count
        successful_pickups = $script:CobbleBreakCount
        lost_drops = $LostDrops
        recovered_loose_drops = $RecoveredDrops
        active_collection_actions = $ActiveCollections
        delayed_passive_pickups = $RecoveredDrops - $ActiveCollections
        total_attempts = $TotalAttempts
        total_actions = $accepted.Count
        expected_pickaxe_damage = $TotalAttempts
        accepted_equals_terminal = $true
        stale_frame_reuse = $false
    }
}

function New-CobblestoneOfflineOracleManifest {
    param(
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [ValidateRange(8, 16)][int]$ExpectedAttempts,
        [ValidateRange(8, 16)][int]$MaximumAttempts = $script:CobbleMaximumAttempts,
        [ValidateRange(0, 8)][int]$LostDrops,
        [ValidateRange(0, 8)][int]$RecoveredDrops,
        [ValidateRange(0, 8)][int]$ActiveCollections
    )
    if ($ActiveCollections -gt $RecoveredDrops) {
        throw 'active collection count exceeds recovered drop count'
    }
    return [ordered]@{
        schema_version = 1
        oracle = 'offline-cobblestone-generator-world'
        inspector = 'tools/eval/Inspect-McmcpCobblestoneGeneratorOracle.py'
        inspector_arguments = @('--expected-attempts', [string]$ExpectedAttempts)
        world_closed_required = $true
        dimension = 'minecraft:overworld'
        workspace = $script:CobbleWorkspaceBounds
        generation_cell = $script:CobbleExpectedPosition
        water_source = [ordered]@{ x = 197; y = 201; z = 200; block = 'minecraft:water'; level = '0' }
        lava_source = [ordered]@{ x = 200; y = 201; z = 200; block = 'minecraft:lava'; level = '0' }
        player = [ordered]@{
            position = $script:CobbleExpectedStand; health = $ExpectedHealth
            cobblestone_count = 8; iron_pickaxe_count = 1
            iron_pickaxe_damage = $ExpectedAttempts
            iron_pickaxe_enchanted = $false
        }
        total_attempts = $ExpectedAttempts
        maximum_attempts = $MaximumAttempts
        lost_drops = $LostDrops
        recovered_loose_drops = $RecoveredDrops
        active_collection_actions = $ActiveCollections
        delayed_passive_pickups = $RecoveredDrops - $ActiveCollections
        loose_item_count = 0
        allowed_dynamic_cells = @(
            [ordered]@{ x = 198; y = 200; z = 200 },
            [ordered]@{ x = 198; y = 201; z = 200 }
        )
        only_generation_cell_may_change_among_static_cells = $true
        fixture_tick_mutation_after_t0 = $false
    }
}

function Invoke-CobblestoneGeneratorGateCore {
    $fixedFive = Assert-CobbleFixedFive
    $initial = Get-FreshState
    $initialHealth = [double](Get-ObjectProperty (Get-ObjectProperty $initial 'world') 'health')
    if ([double]::IsNaN($initialHealth) -or [double]::IsInfinity($initialHealth) -or
        $initialHealth -le 0.0 -or
        $initialHealth -gt 20.0) {
        throw "cobblestone fixture initial health is invalid: $initialHealth"
    }
    Assert-CobblePlayerState -State $initial -ExpectedHealth $initialHealth -Phase 'initial state'
    if ((Get-InventoryCount -State $initial -Item 'minecraft:cobblestone') -ne 0 -or
        (Get-InventoryCount -State $initial -Item 'minecraft:iron_pickaxe') -ne 1) {
        throw 'cobblestone fixture requires zero cobblestone and one iron pickaxe'
    }

    $previousFrameId = $null
    $proofs = [Collections.Generic.List[object]]::new()
    $lostDropProofs = [Collections.Generic.List[object]]::new()
    $recoveredDropProofs = [Collections.Generic.List[object]]::new()
    $activeCollectionCount = 0
    $delayedPassivePickupCount = 0
    $fresh = $null
    $inventoryCount = 0
    $attempt = 0
    while ($inventoryCount -lt $script:CobbleBreakCount) {
        if ($attempt -ge $script:CobbleMaximumAttempts) {
            throw "cobblestone gate exhausted $($script:CobbleMaximumAttempts) attempts before collecting eight blocks"
        }
        if ($null -eq $fresh) {
            $fresh = Wait-FreshGeneratedCobblestone -PreviousFrameId $previousFrameId
        }
        $attempt++
        $minimumInventoryCount = $inventoryCount + 1
        Assert-CobblePlayerState -State $fresh.state -ExpectedHealth $initialHealth `
            -Phase "attempt $attempt admission"
        $beforeCount = Get-InventoryCount -State $fresh.state -Item 'minecraft:cobblestone'
        if ($beforeCount -ne $inventoryCount) {
            throw "cobblestone inventory before attempt $attempt is $beforeCount; expected=$inventoryCount"
        }
        $request = New-CobblestoneBreakRequest -Surface $fresh.surface `
            -MinimumInventoryCount $minimumInventoryCount
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 45 `
            -ReturnFailure
        if ((Get-ObjectProperty $terminal 'state') -ceq 'succeeded') {
            $proofs.Add((Assert-CobblestoneBreakTerminal -Terminal $terminal `
                        -Attempt $attempt -MinimumInventoryCount $minimumInventoryCount))
            $postSuccessInitial = Wait-ForObservationFrameAdvance `
                -PreviousFrameId $fresh.frame_id
            $settled = Wait-CobblestoneSuccessfulPickupSettlement `
                -InitialState $postSuccessInitial `
                -ExpectedInventoryCount $minimumInventoryCount `
                -ExpectedHealth $initialHealth `
                -Phase "attempt $attempt pickup reobservation"
            $postSuccess = Get-ObjectProperty $settled 'state'
            $inventoryCount = $minimumInventoryCount
            $previousFrameId = Get-ObservationFrameId -State $postSuccess
            $fresh = $null
            continue
        }
        if (-not (Test-CobblestoneLostDropTerminal -Terminal $terminal `
                    -MinimumInventoryCount $minimumInventoryCount)) {
            throw "cobblestone attempt $attempt failed outside the qualified lost-drop boundary"
        }

        # A confirmed block mutation is never replayed. Stabilize fresh inventory/entity
        # evidence before deciding between delayed pickup, active collection, or a new break.
        $reobserved = Wait-FreshGeneratedCobblestone -PreviousFrameId $fresh.frame_id `
            -SuppressBreakObservationEvent
        $stabilized = Wait-CobblestoneFailedBreakStabilization `
            -InitialState $reobserved.state -InventoryBefore $inventoryCount `
            -InventoryAfter $minimumInventoryCount -ExpectedHealth $initialHealth `
            -Phase "attempt $attempt failed-break stabilization"
        $recoveryMode = [string](Get-ObjectProperty $stabilized 'mode')
        $recoveryState = Get-ObjectProperty $stabilized 'state'
        if ($recoveryMode -ceq 'delayed_passive_pickup') {
            $failedEffect = @((Get-ObjectProperty $terminal 'effects'))[0]
            $recovered = [ordered]@{
                attempt = $attempt
                minimum_inventory_count = $minimumInventoryCount
                break_action_id = Get-ObjectProperty $terminal 'action_id'
                collect_action_id = $null
                node_id = Get-ObjectProperty $failedEffect 'node_id'
                verification = Get-ObjectProperty $failedEffect 'verification'
                inventory_count = $minimumInventoryCount
                pickup_mode = 'delayed_passive_pickup'
                observed_item_position = $null
                stabilization_polls = Get-ObjectProperty $stabilized 'stabilization_polls'
                settlement_polls = Get-ObjectProperty $stabilized 'settlement_polls'
                reobserved_frame_id = Get-ObservationFrameId -State $recoveryState
            }
            $proofs.Add($recovered)
            $recoveredDropProofs.Add($recovered)
            $delayedPassivePickupCount++
            Add-GateEvent -Event 'cobblestone_loose_drop_recovered' -Detail $recovered
            $inventoryCount = $minimumInventoryCount
            $previousFrameId = Get-ObservationFrameId -State $recoveryState
            $fresh = $null
            continue
        }
        if ($recoveryMode -ceq 'active_collect') {
            $looseItem = Get-ObjectProperty $stabilized 'item'
            Add-GateEvent -Event 'cobblestone_loose_drop_observation' -Detail ([ordered]@{
                    frame_id = Get-ObservationFrameId -State $recoveryState
                    observed_tick = Get-ObjectProperty $looseItem 'observed_tick'
                    world_revision = Get-ObjectProperty $looseItem 'world_revision'
                    loose_item_count = 1
                })
            $collectRequest = New-CobblestoneDropCollectionRequest `
                -Record $looseItem -Attempt $attempt
            $collectTerminal = Invoke-ActionRequest -Request $collectRequest `
                -WallTimeoutSeconds 45
            Assert-CobblestoneCollectionTerminal -Terminal $collectTerminal `
                -Attempt $attempt -InventoryBefore $inventoryCount `
                -InventoryAfter $minimumInventoryCount
            $afterCollectInitial = Wait-ForObservationFrameAdvance `
                -PreviousFrameId (Get-ObservationFrameId -State $recoveryState)
            $collectSettled = Wait-CobblestoneSuccessfulPickupSettlement `
                -InitialState $afterCollectInitial `
                -ExpectedInventoryCount $minimumInventoryCount `
                -ExpectedHealth $initialHealth `
                -Phase "attempt $attempt active collection reobservation"
            $afterCollect = Get-ObjectProperty $collectSettled 'state'
            $failedEffect = @((Get-ObjectProperty $terminal 'effects'))[0]
            $recovered = [ordered]@{
                attempt = $attempt
                minimum_inventory_count = $minimumInventoryCount
                break_action_id = Get-ObjectProperty $terminal 'action_id'
                collect_action_id = Get-ObjectProperty $collectTerminal 'action_id'
                node_id = Get-ObjectProperty $failedEffect 'node_id'
                verification = Get-ObjectProperty $failedEffect 'verification'
                inventory_count = $minimumInventoryCount
                pickup_mode = 'delivery_backed_collect_visible_item'
                observed_item_position = Get-ObjectProperty $looseItem 'position'
                stabilization_polls = Get-ObjectProperty $stabilized 'stabilization_polls'
                settlement_polls = Get-ObjectProperty $collectSettled 'polls'
                reobserved_frame_id = Get-ObservationFrameId -State $afterCollect
            }
            $proofs.Add($recovered)
            $recoveredDropProofs.Add($recovered)
            $activeCollectionCount++
            Add-GateEvent -Event 'cobblestone_loose_drop_recovered' -Detail $recovered
            $inventoryCount = $minimumInventoryCount
            $previousFrameId = Get-ObservationFrameId -State $afterCollect
            $fresh = $null
            continue
        }
        if ($recoveryMode -cne 'lost_drop_retry') {
            throw "cobblestone attempt $attempt returned unsupported recovery mode $recoveryMode"
        }
        $retrySurface = Get-OnlyGeneratedCobblestoneSurface -State $recoveryState
        $retryFrameId = Get-ObservationFrameId -State $recoveryState
        Add-GateEvent -Event 'cobblestone_break_observation' -Detail ([ordered]@{
                frame_id = $retryFrameId
                observed_tick = Get-ObjectProperty $retrySurface 'observed_tick'
                world_revision = Get-ObjectProperty $retrySurface 'world_revision'
                poll = Get-ObjectProperty $stabilized 'stabilization_polls'
            })
        $lostDrop = [ordered]@{
            attempt = $attempt
            minimum_inventory_count = $minimumInventoryCount
            action_id = Get-ObjectProperty $terminal 'action_id'
            failure_code = 'SERVER_DENIED_OR_DESYNC'
            failure_evidence = 'break_not_server_confirmed'
            inventory_count = $inventoryCount
            visible_loose_cobblestone = 0
            reobserved_frame_id = $retryFrameId
        }
        $lostDropProofs.Add($lostDrop)
        Add-GateEvent -Event 'cobblestone_lost_drop_reobserved' -Detail $lostDrop
        $previousFrameId = $fresh.frame_id
        $fresh = [pscustomobject]@{
            state = $recoveryState; surface = $retrySurface
            frame_id = $retryFrameId
            poll = Get-ObjectProperty $stabilized 'stabilization_polls'
        }
    }

    # The eighth pickup can settle one client frame before the ordinary fluid update recreates
    # the generator target.  Bind the final oracle to a later delivered cobblestone surface.
    $finalProof = Wait-FreshGeneratedCobblestone -PreviousFrameId $previousFrameId `
        -SuppressBreakObservationEvent
    $final = $finalProof.state
    Assert-CobblePlayerState -State $final -ExpectedHealth $initialHealth -Phase 'final state'
    $finalCobblestone = Get-InventoryCount -State $final -Item 'minecraft:cobblestone'
    if ($finalCobblestone -ne $script:CobbleBreakCount) {
        throw "cobblestone inventory delta is not +8; actual=$finalCobblestone"
    }
    Assert-NoVisibleLooseItems -State $final
    $lifecycle = Assert-CobblestoneLifecycle -TotalAttempts $attempt `
        -LostDrops $lostDropProofs.Count -RecoveredDrops $recoveredDropProofs.Count `
        -ActiveCollections $activeCollectionCount
    return [ordered]@{
        gate = 'phase9-cobblestone-generator'
        fixture_precondition = '/mcmcp_fixture phase5 cobblestone_generator'
        fixed_five_surface = $fixedFive
        normal_player_actions_only = $true
        break_count = $script:CobbleBreakCount
        total_attempts = $attempt
        lost_drops = $lostDropProofs.Count
        recovered_loose_drops = $recoveredDropProofs.Count
        active_collection_actions = $activeCollectionCount
        delayed_passive_pickups = $delayedPassivePickupCount
        maximum_attempts = $script:CobbleMaximumAttempts
        expected_pickaxe_damage = $attempt
        action_boundary = 'fresh_observation_then_one_break_per_action_with_qualified_lost_drop_retry'
        lifecycle = $lifecycle
        terminal_effects = @($proofs)
        lost_drop_effects = @($lostDropProofs)
        recovered_drop_effects = @($recoveredDropProofs)
        online_oracle = [ordered]@{
            cobblestone_before = 0; cobblestone_after = $finalCobblestone
            cobblestone_delta = $finalCobblestone
            confirmed_break_effects = $proofs.Count
            total_break_attempts = $attempt
            qualified_lost_drops = $lostDropProofs.Count
            recovered_loose_drops = $recoveredDropProofs.Count
            active_collection_actions = $activeCollectionCount
            delayed_passive_pickups = $delayedPassivePickupCount
            expected_pickaxe_damage = $attempt
            player_position_unchanged = $true
            player_health_unchanged = $true
            visible_loose_cobblestone = 0
        }
        external_oracle_status = 'pending_world_close'
        external_oracle = New-CobblestoneOfflineOracleManifest -ExpectedHealth $initialHealth `
            -ExpectedAttempts $attempt -LostDrops $lostDropProofs.Count `
            -RecoveredDrops $recoveredDropProofs.Count `
            -ActiveCollections $activeCollectionCount
    }
}

function Invoke-KnownCobblestoneGeneratorGateCore {
    $fixedFive = Assert-CobbleFixedFive
    $initial = Get-FreshState
    $initialHealth = [double](Get-ObjectProperty (Get-ObjectProperty $initial 'world') 'health')
    if ([double]::IsNaN($initialHealth) -or [double]::IsInfinity($initialHealth) -or
        $initialHealth -le 0.0 -or $initialHealth -gt 20.0) {
        throw "cobblestone fixture initial health is invalid: $initialHealth"
    }
    Assert-CobblePlayerState -State $initial -ExpectedHealth $initialHealth -Phase 'initial state'
    if ((Get-InventoryCount -State $initial -Item 'minecraft:cobblestone') -ne 0 -or
        (Get-InventoryCount -State $initial -Item 'minecraft:iron_pickaxe') -ne 1) {
        throw 'cobblestone fixture requires zero cobblestone and one iron pickaxe'
    }

    $fresh = Wait-FreshGeneratedCobblestone -PreviousFrameId $null
    Assert-CobblePlayerState -State $fresh.state -ExpectedHealth $initialHealth `
        -Phase 'known generator face admission'
    $faceRequest = New-KnownCobblestoneGeneratorFaceRequest -Surface $fresh.surface
    $faceTerminal = Invoke-ActionRequest -Request $faceRequest -WallTimeoutSeconds 45
    Assert-KnownCobblestoneGeneratorFaceTerminal -Terminal $faceTerminal

    # Camera completion does not extend mutation evidence. Obtain a later delivered exact
    # surface and build the standalone generator Action only from that fresh target/state/face.
    $refaced = Wait-FreshGeneratedCobblestone -PreviousFrameId $fresh.frame_id `
        -SuppressBreakObservationEvent
    Assert-CobblePlayerState -State $refaced.state -ExpectedHealth $initialHealth `
        -Phase 'known generator operation admission'
    $request = New-KnownCobblestoneGeneratorRequest -Surface $refaced.surface
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 210
    $proofs = @(Assert-KnownCobblestoneGeneratorTerminal -Terminal $terminal)

    # The final break may reach its inventory goal before Vanilla recreates the target cell.
    # Rebind the online oracle to a later delivered frame that sees the generator ready again.
    $finalProof = Wait-FreshGeneratedCobblestone -PreviousFrameId $refaced.frame_id `
        -SuppressBreakObservationEvent
    $final = $finalProof.state
    Assert-CobblePlayerState -State $final -ExpectedHealth $initialHealth -Phase 'final state'
    $finalCobblestone = Get-InventoryCount -State $final -Item 'minecraft:cobblestone'
    if ($finalCobblestone -ne $script:CobbleBreakCount) {
        throw "cobblestone inventory delta is not +8; actual=$finalCobblestone"
    }
    Assert-NoVisibleLooseItems -State $final

    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminalEvents = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    if ($accepted.Count -ne 2 -or $terminalEvents.Count -ne 2 -or
        (Get-ObjectProperty $accepted[0] 'program') -cne
            'capability_gate_face_cobblestone_generator' -or
        (Get-ObjectProperty $accepted[1] 'program') -cne
            'capability_gate_operate_known_cobblestone_generator') {
        throw 'known generator gate must complete its face and operation Actions'
    }
    $lifecycle = [ordered]@{
        accepted = 2; terminal = 2; total_actions = 2
        successful_pickups = $script:CobbleBreakCount
        confirmed_break_effects = $proofs.Count
        expected_pickaxe_damage = $script:CobbleBreakCount
        accepted_equals_terminal = $true
        stale_frame_reuse = $false
    }
    return [ordered]@{
        gate = 'phase9-cobblestone-generator'
        fixture_precondition = '/mcmcp_fixture phase5 cobblestone_generator'
        fixed_five_surface = $fixedFive
        normal_player_actions_only = $true
        break_count = $script:CobbleBreakCount
        total_attempts = $script:CobbleBreakCount
        maximum_attempts = $script:CobbleBreakCount
        expected_pickaxe_damage = $script:CobbleBreakCount
        action_boundary = 'camera_action_then_fresh_evidence_then_finite_generator_action'
        lifecycle = $lifecycle
        terminal_effects = $proofs
        lost_drops = 0
        recovered_loose_drops = 0
        active_collection_actions = 0
        delayed_passive_pickups = 0
        lost_drop_effects = @()
        recovered_drop_effects = @()
        online_oracle = [ordered]@{
            cobblestone_before = 0; cobblestone_after = $finalCobblestone
            cobblestone_delta = $finalCobblestone
            confirmed_break_effects = $proofs.Count
            total_break_attempts = $script:CobbleBreakCount
            qualified_lost_drops = 0; recovered_loose_drops = 0
            active_collection_actions = 0; delayed_passive_pickups = 0
            expected_pickaxe_damage = $script:CobbleBreakCount
            player_position_unchanged = $true
            player_health_unchanged = $true
            visible_loose_cobblestone = 0
        }
        external_oracle_status = 'pending_world_close'
        external_oracle = New-CobblestoneOfflineOracleManifest `
            -ExpectedHealth $initialHealth -ExpectedAttempts $script:CobbleBreakCount `
            -MaximumAttempts $script:CobbleBreakCount `
            -LostDrops 0 -RecoveredDrops 0 -ActiveCollections 0
    }
}

function Write-CobblestoneGeneratorArtifacts {
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
        gate = 'phase9-cobblestone-generator'
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

function Invoke-McmcpCobblestoneGeneratorCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-KnownCobblestoneGeneratorGateCore } catch { $primaryFailure = $_ }
    finally { try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ } }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-CobblestoneGeneratorArtifacts -GateResult $gateResult -InputRelease $release `
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
    $result = Invoke-McmcpCobblestoneGeneratorCapabilityGate
    ConvertTo-Json $result -Depth 100
}
