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
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )

    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $state = Get-FreshState
        $frameId = Get-ObservationFrameId -State $state
        if (($null -eq $PreviousFrameId -or $frameId -cne $PreviousFrameId)) {
            $surface = Get-OnlyGeneratedCobblestoneSurface -State $state -AllowMissing
            if ($null -ne $surface) {
                Add-GateEvent -Event 'cobblestone_break_observation' -Detail ([ordered]@{
                        frame_id = $frameId
                        observed_tick = Get-ObjectProperty $surface 'observed_tick'
                        world_revision = Get-ObjectProperty $surface 'world_revision'
                        poll = $poll
                    })
                return [pscustomobject]@{ state = $state; surface = $surface; frame_id = $frameId }
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

function Assert-NoVisibleCobblestoneDrops {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:item')
            displayed_items = @('minecraft:cobblestone')
            position_bounds = $script:CobbleWorkspaceBounds
        }))
    if ($records.Count -ne 0) {
        throw "cobblestone gate left $($records.Count) visible loose cobblestone entities"
    }
}

function Assert-CobblestoneLifecycle {
    param(
        [ValidateRange(8, 16)][int]$TotalAttempts,
        [ValidateRange(0, 8)][int]$LostDrops
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
    if ($accepted.Count -ne $TotalAttempts -or
        $terminal.Count -ne $TotalAttempts -or
        $observations.Count -ne $TotalAttempts -or
        $lostDropEvents.Count -ne $LostDrops) {
        throw 'cobblestone gate lifecycle count does not match its bounded attempts'
    }
    $frameIds = @($observations | ForEach-Object {
            [string](Get-ObjectProperty $_ 'frame_id')
        } | Sort-Object -Unique)
    if ($frameIds.Count -ne $TotalAttempts) {
        throw 'cobblestone gate reused an observation frame between breaks'
    }
    $succeeded = @($terminal | Where-Object { (Get-ObjectProperty $_ 'state') -ceq 'succeeded' })
    $failed = @($terminal | Where-Object { (Get-ObjectProperty $_ 'state') -ceq 'failed' })
    if ($succeeded.Count -ne $script:CobbleBreakCount -or $failed.Count -ne $LostDrops) {
        throw 'cobblestone gate terminal states do not match pickup and lost-drop counts'
    }
    for ($index = 0; $index -lt $TotalAttempts; $index++) {
        if ((Get-ObjectProperty $accepted[$index] 'action_id') -cne
                (Get-ObjectProperty $terminal[$index] 'action_id') -or
            [Array]::IndexOf($events, $observations[$index]) -gt
                [Array]::IndexOf($events, $accepted[$index])) {
            throw "cobblestone gate lifecycle mismatch at break $($index + 1)"
        }
        if ($index -gt 0 -and
            [Array]::IndexOf($events, $observations[$index]) -lt
                [Array]::IndexOf($events, $terminal[$index - 1])) {
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
        total_attempts = $TotalAttempts
        expected_pickaxe_damage = $TotalAttempts
        accepted_equals_terminal = $true
        stale_frame_reuse = $false
    }
}

function New-CobblestoneOfflineOracleManifest {
    param(
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [ValidateRange(8, 16)][int]$ExpectedAttempts,
        [ValidateRange(0, 8)][int]$LostDrops
    )
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
        maximum_attempts = $script:CobbleMaximumAttempts
        lost_drops = $LostDrops
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
            $inventoryCount = $minimumInventoryCount
            $previousFrameId = $fresh.frame_id
            $fresh = $null
            continue
        }
        if (-not (Test-CobblestoneLostDropTerminal -Terminal $terminal `
                    -MinimumInventoryCount $minimumInventoryCount)) {
            throw "cobblestone attempt $attempt failed outside the qualified lost-drop boundary"
        }

        # A confirmed block mutation is never replayed. First obtain a new frame proving that
        # fluid regeneration completed and that pickup count did not advance.
        $reobserved = Wait-FreshGeneratedCobblestone -PreviousFrameId $fresh.frame_id
        Assert-CobblePlayerState -State $reobserved.state -ExpectedHealth $initialHealth `
            -Phase "attempt $attempt lost-drop reobservation"
        $reobservedCount = Get-InventoryCount -State $reobserved.state `
            -Item 'minecraft:cobblestone'
        if ($reobservedCount -ne $inventoryCount) {
            throw "cobblestone attempt $attempt did not prove an unchanged inventory after the lost drop"
        }
        Assert-NoVisibleCobblestoneDrops -State $reobserved.state
        $lostDrop = [ordered]@{
            attempt = $attempt
            minimum_inventory_count = $minimumInventoryCount
            action_id = Get-ObjectProperty $terminal 'action_id'
            failure_code = 'SERVER_DENIED_OR_DESYNC'
            failure_evidence = 'break_not_server_confirmed'
            inventory_count = $reobservedCount
            visible_loose_cobblestone = 0
            reobserved_frame_id = $reobserved.frame_id
        }
        $lostDropProofs.Add($lostDrop)
        Add-GateEvent -Event 'cobblestone_lost_drop_reobserved' -Detail $lostDrop
        $previousFrameId = $fresh.frame_id
        $fresh = $reobserved
    }

    $final = Get-FreshState
    Assert-CobblePlayerState -State $final -ExpectedHealth $initialHealth -Phase 'final state'
    $finalCobblestone = Get-InventoryCount -State $final -Item 'minecraft:cobblestone'
    if ($finalCobblestone -ne $script:CobbleBreakCount) {
        throw "cobblestone inventory delta is not +8; actual=$finalCobblestone"
    }
    [void](Get-OnlyGeneratedCobblestoneSurface -State $final)
    Assert-NoVisibleCobblestoneDrops -State $final
    $lifecycle = Assert-CobblestoneLifecycle -TotalAttempts $attempt `
        -LostDrops $lostDropProofs.Count
    return [ordered]@{
        gate = 'phase9-cobblestone-generator'
        fixture_precondition = '/mcmcp_fixture phase5 cobblestone_generator'
        fixed_five_surface = $fixedFive
        normal_player_actions_only = $true
        break_count = $script:CobbleBreakCount
        total_attempts = $attempt
        lost_drops = $lostDropProofs.Count
        maximum_attempts = $script:CobbleMaximumAttempts
        expected_pickaxe_damage = $attempt
        action_boundary = 'fresh_observation_then_one_break_per_action_with_qualified_lost_drop_retry'
        lifecycle = $lifecycle
        terminal_effects = @($proofs)
        lost_drop_effects = @($lostDropProofs)
        online_oracle = [ordered]@{
            cobblestone_before = 0; cobblestone_after = $finalCobblestone
            cobblestone_delta = $finalCobblestone
            confirmed_break_effects = $proofs.Count
            total_break_attempts = $attempt
            qualified_lost_drops = $lostDropProofs.Count
            expected_pickaxe_damage = $attempt
            player_position_unchanged = $true
            player_health_unchanged = $true
            visible_loose_cobblestone = 0
        }
        external_oracle_status = 'pending_world_close'
        external_oracle = New-CobblestoneOfflineOracleManifest -ExpectedHealth $initialHealth `
            -ExpectedAttempts $attempt -LostDrops $lostDropProofs.Count
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
    try { $gateResult = Invoke-CobblestoneGeneratorGateCore } catch { $primaryFailure = $_ }
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
