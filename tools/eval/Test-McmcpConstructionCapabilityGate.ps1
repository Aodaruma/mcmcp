[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $runner -Gate navigation -ArtifactDirectory (Join-Path $PSScriptRoot '.mock-unused') `
    -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "capability gate mock test failed: $Message" }
}

function New-MockState {
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{ mode = 'ready'; ready_expires_at = $null; game_paused = $false }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 10L; world_revision = 1L
            position = [pscustomobject]@{ x = -9.3; y = 56.0; z = -8.1 }
            yaw = 0.0; pitch = 0.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @()
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0123456789abcdef' }
        action = $null
    }
}

function New-MockActionSnapshot {
    param([Parameter(Mandatory)][string]$ActionId, [Parameter(Mandatory)][string]$State)
    [pscustomobject]@{
        schema_version = 1
        action_id = $ActionId
        state = $State
        progress = [pscustomobject]@{}
        failure = $null
        trace = @()
    }
}

function New-MockSurface {
    param(
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][int]$X,
        [Parameter(Mandatory)][int]$Y,
        [Parameter(Mandatory)][int]$Z,
        [string]$Face = 'up',
        [AllowNull()][object]$State = $null,
        [AllowNull()][string]$PlacementItem = $null,
        [AllowNull()][string]$PlacementStateRef = $null,
        [long]$WorldRevision = 1L
    )
    if ($null -eq $State) {
        $State = [pscustomobject]@{ block = $Block; properties = [pscustomobject]@{} }
    }
    [pscustomobject]@{
        kind = 'visible_surface'
        block = $Block
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z
        }
        face = $Face
        state = $State
        placement_item = $PlacementItem
        placement_state_ref = $PlacementStateRef
        observed_tick = 10L
        world_revision = $WorldRevision
    }
}

function New-MockTraversability {
    param(
        [Parameter(Mandatory)][object]$Target,
        [string]$Status = 'PROBE_ALLOWED',
        [long]$WorldRevision = 1L
    )
    [pscustomobject]@{
        kind = 'traversability'
        navigation_target = $Target
        status = $Status
        target_support = 'confirmed'
        transition_clearance = 'confirmed'
        fluid = 'none'
        world_revision = $WorldRevision
    }
}

function New-MockVisibleItem {
    param([Parameter(Mandatory)][object]$Position)
    [pscustomobject]@{
        kind = 'visible_entity'
        entity_type = 'minecraft:item'
        displayed_item = 'minecraft:oak_log'
        position = $Position
    }
}

# A navigate_to_known target must be the exact delivered object, not a value derived
# from traversability.from/to.
$target = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -15; y = 56; z = -6
}
$navigationRecord = [pscustomobject]@{
    kind = 'traversability'
    navigation_target = $target
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$navigationRequest = New-NavigationActionRequest -NavigationRecord $navigationRecord `
    -State (New-MockState)
$requestTarget = $navigationRequest.program.body[0].target
Assert-True ([object]::ReferenceEquals($target, $requestTarget)) `
    'navigate_to_known did not retain the delivered navigation_target object'
Assert-True ((ConvertTo-CompactJson $target) -ceq (ConvertTo-CompactJson $requestTarget)) `
    'navigate_to_known changed delivered target values'
Assert-True ($navigationRequest.budget.max_distance_blocks -eq 32) `
    'navigation request did not use the advertised shared distance budget'

# A route-edge change may exhaust the immutable budget of the current Action.
# Only the exact movement-only terminal proof may be converted into a fresh
# observation and a new action_id; mutation-bearing or unrelated failures remain fatal.
$r14Terminal = [pscustomobject]@{
    schema_version = 1
    action_id = '550e8400-e29b-41d4-a716-446655440014'
    state = 'failed'
    progress = [pscustomobject]@{
        interactions = 0; blocks_broken = 0; blocks_placed = 0
    }
    failure = [pscustomobject]@{
        code = 'BUDGET_EXCEEDED'; recoverable = $false
        evidence = @('primitive_replanned_route')
    }
    trace = @([pscustomobject]@{
            tick = 6; event = 'REPLANNING'; detail = 'route_edge_changed'
        })
}
Assert-True (Test-NavigationTerminalRequiresFreshSlice -Terminal $r14Terminal) `
    'r14 route-edge terminal was not recognized as requiring a fresh Action slice'
$newDiagnosticTerminal = $r14Terminal.PSObject.Copy()
$newDiagnosticTerminal.failure = [pscustomobject]@{
    code = 'BUDGET_EXCEEDED'; recoverable = $false
    evidence = @('replanned_route_remaining_occurrence')
}
Assert-True (Test-NavigationTerminalRequiresFreshSlice -Terminal $newDiagnosticTerminal) `
    'new fixed route-budget diagnostic was not recognized for fresh Action slicing'
$mutationTerminal = $r14Terminal.PSObject.Copy()
$mutationTerminal.progress = [pscustomobject]@{
    interactions = 0; blocks_broken = 0; blocks_placed = 1
}
Assert-True (-not (Test-NavigationTerminalRequiresFreshSlice -Terminal $mutationTerminal)) `
    'a mutation-bearing terminal was admitted for navigation reslicing'
$unrelatedTerminal = $r14Terminal.PSObject.Copy()
$unrelatedTerminal.failure = [pscustomobject]@{
    code = 'BUDGET_EXCEEDED'; recoverable = $false; evidence = @('ticks')
}
Assert-True (-not (Test-NavigationTerminalRequiresFreshSlice -Terminal $unrelatedTerminal)) `
    'an unrelated budget failure was admitted for navigation reslicing'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_start_action' {
            [pscustomobject]@{
                schema_version = 1; action_id = $r14Terminal.action_id; state = 'queued'
            }
        }
        'agent_get_action' { $r14Terminal }
        default { throw "unexpected reslice mock tool: $Tool" }
    }
}
$returnedFailure = Invoke-ActionRequest -Request $navigationRequest `
    -WallTimeoutSeconds 2 -ReturnFailure
Assert-True ([object]::ReferenceEquals($r14Terminal, $returnedFailure)) `
    'ReturnFailure did not preserve the exact terminal snapshot'
Assert-True ($null -eq $script:ActiveActionId) `
    'ReturnFailure retained Action ownership after terminal input release'

# A hidden fixture surface may be approached only through an unchanged target
# from a fresh traversability record. The fixture bounds are merely the ranking
# goal and never become an Action target.
$towardTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -11; y = 56; z = -4
}
$awayTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -6; y = 56; z = -10
}
$towardRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $towardTarget
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$nearTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -10; y = 56; z = -7
}
$nearRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $nearTarget
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$awayRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $awayTarget
    status = 'CONFIRMED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$selectedToward = Select-NavigationRecordTowardBounds `
    -Records @($awayRecord, $towardRecord, $nearRecord) `
    -WorldPosition (New-MockState).world.position -Bounds $script:ChestBounds
Assert-True ([object]::ReferenceEquals($towardRecord, $selectedToward)) `
    'bounded surface approach did not choose the record making the most safe progress'
$towardRequest = New-NavigationActionRequest -NavigationRecord $selectedToward `
    -State (New-MockState)
Assert-True ([object]::ReferenceEquals(
        $towardTarget, $towardRequest.program.body[0].target)) `
    'bounded surface approach transformed the delivered navigation_target'

# Missing visible surfaces are an expected pre-approach condition, not a
# filtering success and not an exception when explicitly requested.
$script:ToolTransport = {
    param($Tool, $Arguments)
    [pscustomobject]@{
        schema_version = 1; frame_id = 'obs-0123456789abcdef'
        records = @(); next_cursor = $null
    }
}
$missingSurface = Get-VisibleSurface -State (New-MockState) `
    -Block 'minecraft:chest' -Bounds $script:ChestBounds -Faces $null -AllowMissing
Assert-True ($null -eq $missingSurface) `
    'explicit missing-surface probe did not return null'

$singleSurface = New-MockSurface -Block 'minecraft:chest' -X -11 -Y 55 -Z 3
$script:ToolTransport = {
    param($Tool, $Arguments)
    [pscustomobject]@{
        schema_version = 1; frame_id = 'obs-0123456789abcdef'
        records = @($singleSurface); next_cursor = $null
    }
}
$selectedSurface = Get-VisibleSurface -State (New-MockState) `
    -Block 'minecraft:chest' -Bounds $script:ChestBounds -Faces $null
Assert-True ([object]::ReferenceEquals($singleSurface, $selectedSurface)) `
    'single visible_surface selection replaced the delivered record'

$script:NearbyArguments = $null
$script:ToolTransport = {
    param($Tool, $Arguments)
    $script:NearbyArguments = $Arguments
    [pscustomobject]@{
        schema_version = 1; frame_id = 'obs-0123456789abcdef'
        records = @(); next_cursor = $null
    }
}
[void](Get-NearbyTraversabilityRecords -State (New-MockState))
$nearbyBounds = $script:NearbyArguments.filter.position_bounds
Assert-True ($nearbyBounds.min_x -eq -12 -and $nearbyBounds.max_x -eq -8 -and
    $nearbyBounds.min_z -eq -11 -and $nearbyBounds.max_z -eq -7) `
    'surface approach did not bound fresh traversability pagination near the player'

# The transport boundary must reject every name outside the fixed five before the
# injected transport is reached.
$script:MockTransportReached = $false
$script:ToolTransport = { param($Tool, $Arguments) $script:MockTransportReached = $true; @{} }
$forbiddenRejected = $false
try { [void](Invoke-GateTool -Tool 'raw_key' -Arguments ([ordered]@{})) } catch {
    $forbiddenRejected = $_.Exception.Message -match 'non-public tool'
}
Assert-True $forbiddenRejected 'non-public tool was not rejected'
Assert-True (-not $script:MockTransportReached) 'forbidden tool reached the transport'

# Terminal waiting is event-driven long-polling with the maximum public wait.
$actionId = '550e8400-e29b-41d4-a716-446655440000'
$pollStates = [Collections.Generic.Queue[string]]::new()
$pollStates.Enqueue('running')
$pollStates.Enqueue('succeeded')
$pollCalls = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    $pollCalls.Add([pscustomobject]@{ tool = $Tool; arguments = $Arguments })
    New-MockActionSnapshot -ActionId $actionId -State $pollStates.Dequeue()
}
$terminal = Wait-McmcpActionTerminal -ActionId $actionId -WallTimeoutSeconds 2
Assert-True ($terminal.state -ceq 'succeeded') 'long-poll did not return terminal success'
Assert-True ($pollCalls.Count -eq 2) 'long-poll did not repeat until terminal'
Assert-True (@($pollCalls | Where-Object {
            $_.tool -cne 'agent_get_action' -or $_.arguments.wait_timeout_ms -ne 25000
        }).Count -eq 0) 'terminal polling did not use agent_get_action(25000) exclusively'

# Cleanup observes, cancels only a live action, waits terminal, then proves the
# strongest input-release fact available through the fixed public surface.
$cleanupCalls = [Collections.Generic.List[string]]::new()
$script:MockCleanupPoll = 0
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:GateEvents.Add([pscustomobject]@{
        event = 'action_accepted'; action_id = $actionId
    })
$script:ActiveActionId = $actionId
$script:ToolTransport = {
    param($Tool, $Arguments)
    $cleanupCalls.Add($Tool)
    switch ($Tool) {
        'agent_get_action' {
            $script:MockCleanupPoll++
            if ($script:MockCleanupPoll -eq 1) {
                return New-MockActionSnapshot -ActionId $actionId -State 'running'
            }
            return New-MockActionSnapshot -ActionId $actionId -State 'cancelled'
        }
        'agent_cancel_action' {
            return [pscustomobject]@{
                schema_version = 1; action_id = $actionId
                cancel_requested = $true; state_at_request = 'running'
            }
        }
        'agent_get_state' { return New-MockState }
        default { throw "unexpected mock tool: $Tool" }
    }
}
$release = Invoke-GateCleanup
Assert-True $release.control_ready 'cleanup did not prove ready control'
Assert-True $release.all_actions_terminal 'cleanup did not prove terminal Action state'
Assert-True ($cleanupCalls -join ',' -ceq
    'agent_get_action,agent_cancel_action,agent_get_action,agent_get_state') `
    'cleanup call order changed'
$cleanupTerminalEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'action_terminal' -and $_.action_id -ceq $actionId
    })
Assert-True ($cleanupTerminalEvents.Count -eq 1 -and
    $cleanupTerminalEvents[0].state -ceq 'cancelled' -and
    $cleanupTerminalEvents[0].terminal_source -ceq 'cleanup_recovery') `
    'cleanup did not close the accepted-to-terminal evidence ledger'

# Waiting beyond the observation-handle TTL performs no Tool calls and permanently
# forbids another source lookup in the state-ref gate.
$script:MockDelaySeconds = 0
$script:MockWaitToolCalls = 0
$script:SourceObservationCount = 1
$script:SourceObservationForbidden = $false
$script:ToolTransport = { param($Tool, $Arguments) $script:MockWaitToolCalls++; throw 'unexpected Tool call' }
$script:DelayTransport = { param($Seconds) $script:MockDelaySeconds = $Seconds }
Wait-StateRefRetentionWindow -Seconds 61
Assert-True ($script:MockDelaySeconds -eq 61) 'state-ref retention wait was shortened'
Assert-True ($script:MockWaitToolCalls -eq 0) 'a Tool call occurred inside the retention wait'
$sourceRejected = $false
try { Assert-SourceObservationAllowed } catch {
    $sourceRejected = $_.Exception.Message -match 'forbidden'
}
Assert-True $sourceRejected 'source re-observation remained possible after the retention wait'
Assert-True ($script:SourceObservationCount -eq 1) 'source observation count changed during wait'

# A fresh state call may still announce the pre-mutation frame. The explicit
# barrier is bounded and must fail rather than relabel that stale frame as fresh.
$script:MockBarrierState = New-MockState
$script:MockBarrierState.observation.latest_frame_id = 'obs-00000000000000aa'
$script:MockBarrierPolls = 0
$script:MockBarrierDelays = 0
$script:ToolTransport = {
    param($Tool, $Arguments)
    if ($Tool -cne 'agent_get_state') { throw "unexpected barrier mock tool: $Tool" }
    $script:MockBarrierPolls++
    return $script:MockBarrierState
}
$script:DelayTransport = { param($Seconds) $script:MockBarrierDelays++ }
$barrierTimedOut = $false
try {
    Wait-ForObservationFrameAdvance -PreviousFrameId 'obs-00000000000000aa' `
        -MaximumPolls 3 -DelayMilliseconds 1
} catch {
    $barrierTimedOut = $_.Exception.Message -match 'did not advance'
}
Assert-True $barrierTimedOut 'unchanged observation frame did not fail closed'
Assert-True ($script:MockBarrierPolls -eq 3 -and $script:MockBarrierDelays -eq 2) `
    'observation frame barrier did not retain its exact poll and delay bounds'

# Scaffold traversability may arrive one observation after a placement. The
# exact-target wait must return the refreshed state and the original record.
$exactWaitTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 57; z = 13
}
$exactWaitRecord = New-MockTraversability -Target $exactWaitTarget -Status 'CONFIRMED'
$staleExactWaitRecord = New-MockTraversability -Target $exactWaitTarget `
    -Status 'CONFIRMED' -WorldRevision 0L
$missingWaitFillerRecord = New-MockTraversability -Target ([pscustomobject]@{
        dimension = 'minecraft:overworld'; x = -22; y = 56; z = 9
    }) -Status 'CONFIRMED'
$exactWaitInitialState = New-MockState
$exactWaitInitialState.observation.latest_frame_id = 'obs-0000000000000101'
$exactWaitFreshState = New-MockState
$exactWaitFreshState.observation.latest_frame_id = 'obs-0000000000000102'
$script:ExactWaitObservationCalls = 0
$script:ExactWaitStateCalls = 0
$script:ExactWaitDelayCalls = 0
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_observation' {
            $script:ExactWaitObservationCalls++
            $records = if ($script:ExactWaitObservationCalls -eq 1) {
                @($staleExactWaitRecord)
            } else { @($exactWaitRecord) }
            return [pscustomobject]@{
                schema_version = 1; frame_id = $Arguments.frame_id
                records = $records; next_cursor = $null
            }
        }
        'agent_get_state' {
            $script:ExactWaitStateCalls++
            return $exactWaitFreshState
        }
        default { throw "unexpected exact scaffold wait tool: $Tool" }
    }
}
$script:DelayTransport = { param($Seconds) $script:ExactWaitDelayCalls++ }
$exactWaitResult = Wait-ForExactScaffoldNavigationRecord `
    -InitialState $exactWaitInitialState -ExpectedTarget $exactWaitTarget `
    -MaximumPolls 3 -DelayMilliseconds 1
Assert-True ($exactWaitResult.polls -eq 2 -and
    [object]::ReferenceEquals($exactWaitFreshState, $exactWaitResult.state) -and
    [object]::ReferenceEquals($exactWaitRecord, $exactWaitResult.record)) `
    'exact scaffold wait did not preserve its second-poll state and record'
Assert-True ($script:ExactWaitObservationCalls -eq 2 -and
    $script:ExactWaitStateCalls -eq 1 -and $script:ExactWaitDelayCalls -eq 1) `
    'exact scaffold wait did not reject stale-revision evidence before retrying'

# The adjacent-target wait must evaluate adjacency from the refreshed player
# pose. The same record is two blocks away initially and one block away later.
$adjacentWaitColumn = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 56; z = 13
}
$adjacentWaitTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 57; z = 13
}
$adjacentWaitRecord = New-MockTraversability `
    -Target $adjacentWaitTarget -Status 'CONFIRMED'
$adjacentWaitInitialState = New-MockState
$adjacentWaitInitialState.observation.latest_frame_id = 'obs-0000000000000201'
$adjacentWaitInitialState.world.position = [pscustomobject]@{
    x = -17.5; y = 56.0; z = 13.5
}
$adjacentWaitFreshState = New-MockState
$adjacentWaitFreshState.observation.latest_frame_id = 'obs-0000000000000202'
$adjacentWaitFreshState.world.position = [pscustomobject]@{
    x = -18.5; y = 56.0; z = 13.5
}
$script:AdjacentWaitObservationCalls = 0
$script:AdjacentWaitStateCalls = 0
$script:AdjacentWaitDelayCalls = 0
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_observation' {
            $script:AdjacentWaitObservationCalls++
            return [pscustomobject]@{
                schema_version = 1; frame_id = $Arguments.frame_id
                records = @($adjacentWaitRecord); next_cursor = $null
            }
        }
        'agent_get_state' {
            $script:AdjacentWaitStateCalls++
            return $adjacentWaitFreshState
        }
        default { throw "unexpected adjacent scaffold wait tool: $Tool" }
    }
}
$script:DelayTransport = { param($Seconds) $script:AdjacentWaitDelayCalls++ }
$adjacentWaitResult = Wait-ForAdjacentScaffoldNavigationRecord `
    -InitialState $adjacentWaitInitialState -TargetColumn $adjacentWaitColumn `
    -TargetY 57 -MaximumPolls 3 -DelayMilliseconds 1
Assert-True ($adjacentWaitResult.polls -eq 2 -and
    [object]::ReferenceEquals($adjacentWaitFreshState, $adjacentWaitResult.state) -and
    [object]::ReferenceEquals($adjacentWaitRecord, $adjacentWaitResult.record)) `
    'adjacent scaffold wait did not select from the refreshed player pose'
Assert-True ($script:AdjacentWaitObservationCalls -eq 2 -and
    $script:AdjacentWaitStateCalls -eq 1 -and $script:AdjacentWaitDelayCalls -eq 1) `
    'adjacent scaffold wait did not perform one bounded fresh-state retry'

# Both waits fail closed after their default forty polls when no safe record is
# ever delivered. A mocked delay keeps this upper-bound test instantaneous.
foreach ($waitMode in @('exact', 'adjacent')) {
    $missingWaitState = New-MockState
    $missingWaitState.observation.latest_frame_id = if ($waitMode -ceq 'exact') {
        'obs-0000000000000301'
    } else { 'obs-0000000000000302' }
    $script:MissingWaitObservationCalls = 0
    $script:MissingWaitStateCalls = 0
    $script:MissingWaitDelayCalls = 0
    $script:ToolTransport = {
        param($Tool, $Arguments)
        switch ($Tool) {
            'agent_get_observation' {
                $script:MissingWaitObservationCalls++
                return [pscustomobject]@{
                    schema_version = 1; frame_id = $Arguments.frame_id
                    records = @($missingWaitFillerRecord); next_cursor = $null
                }
            }
            'agent_get_state' {
                $script:MissingWaitStateCalls++
                return $missingWaitState
            }
            default { throw "unexpected missing scaffold wait tool: $Tool" }
        }
    }
    $script:DelayTransport = { param($Seconds) $script:MissingWaitDelayCalls++ }
    $missingWaitThrew = $false
    try {
        if ($waitMode -ceq 'exact') {
            [void](Wait-ForExactScaffoldNavigationRecord `
                    -InitialState $missingWaitState -ExpectedTarget $exactWaitTarget `
                    -DelayMilliseconds 1)
        } else {
            [void](Wait-ForAdjacentScaffoldNavigationRecord `
                    -InitialState $missingWaitState -TargetColumn $adjacentWaitColumn `
                    -TargetY 57 -DelayMilliseconds 1)
        }
    } catch {
        $missingWaitThrew = $_.Exception.Message -cmatch `
            'no delivered (adjacent )?scaffold navigation .*became safe within the bounded wait'
    }
    Assert-True $missingWaitThrew `
        "$waitMode scaffold wait did not throw after its bounded polling limit"
    Assert-True ($script:MissingWaitObservationCalls -eq 40 -and
        $script:MissingWaitStateCalls -eq 39 -and
        $script:MissingWaitDelayCalls -eq 39) `
        "$waitMode scaffold wait did not stop at forty polls"
}

# The high scaffold stands immediately in front of the wall-row center, so its
# horizontal faces can all be occluded even though its UP face is current and
# exact. Reorientation accepts that policy-visible face, retains the delivered
# object, and still rejects stale-revision copies before selecting it.
$reorientationPosition = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 57; z = 9
}
$reorientationState = [pscustomobject]@{
    block = 'minecraft:oak_log'; properties = [pscustomobject]@{ axis = 'y' }
}
$staleReorientationUp = New-MockSurface -Block 'minecraft:oak_log' `
    -X -20 -Y 57 -Z 9 -Face 'up' -State $reorientationState -WorldRevision 1L
$currentReorientationSouth = New-MockSurface -Block 'minecraft:oak_log' `
    -X -20 -Y 57 -Z 9 -Face 'south' -State $reorientationState -WorldRevision 2L
$currentReorientationUp = New-MockSurface -Block 'minecraft:oak_log' `
    -X -20 -Y 57 -Z 9 -Face 'up' -State $reorientationState -WorldRevision 2L
$reorientationInitialState = New-MockState
$reorientationInitialState.observation.latest_frame_id = 'obs-0000000000000351'
$reorientationInitialState.world.world_revision = 2L
$reorientationFreshState = New-MockState
$reorientationFreshState.observation.latest_frame_id = 'obs-0000000000000352'
$reorientationFreshState.world.world_revision = 2L
$script:ReorientationObservationCalls = 0
$script:ReorientationStateCalls = 0
$script:ReorientationDelayCalls = 0
$script:ReorientationArguments = [Collections.Generic.List[object]]::new()
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_observation' {
            $script:ReorientationObservationCalls++
            $script:ReorientationArguments.Add($Arguments)
            $records = if ($script:ReorientationObservationCalls -eq 1) {
                @($staleReorientationUp)
            } else {
                @($currentReorientationSouth, $currentReorientationUp)
            }
            return [pscustomobject]@{
                schema_version = 1; frame_id = $Arguments.frame_id
                records = $records; next_cursor = $null
            }
        }
        'agent_get_state' {
            $script:ReorientationStateCalls++
            return $reorientationFreshState
        }
        default { throw "unexpected wall reorientation wait tool: $Tool" }
    }
}
$script:DelayTransport = { param($Seconds) $script:ReorientationDelayCalls++ }
$reorientationResult = Wait-ForCurrentWallReorientationSurface `
    -InitialState $reorientationInitialState -Position $reorientationPosition `
    -ExpectedState $reorientationState
Assert-True ($reorientationResult.polls -eq 2 -and
    [object]::ReferenceEquals($reorientationFreshState, $reorientationResult.state) -and
    [object]::ReferenceEquals($currentReorientationUp, $reorientationResult.surface)) `
    'wall reorientation did not retain the current exact UP surface'
Assert-True ($script:ReorientationObservationCalls -eq 2 -and
    $script:ReorientationStateCalls -eq 1 -and $script:ReorientationDelayCalls -eq 1) `
    'wall reorientation did not reject its stale exact UP surface before retrying'
$reorientationFilter = $script:ReorientationArguments[0].filter
Assert-True (@($reorientationFilter.faces).Count -eq 5 -and
    @($reorientationFilter.faces) -ccontains 'up' -and
    $reorientationFilter.position_bounds.min_x -eq -20 -and
    $reorientationFilter.position_bounds.max_x -eq -20 -and
    $reorientationFilter.position_bounds.min_y -eq 57 -and
    $reorientationFilter.position_bounds.max_y -eq 57 -and
    $reorientationFilter.position_bounds.min_z -eq 9 -and
    $reorientationFilter.position_bounds.max_z -eq 9) `
    'wall reorientation did not query the exact target with UP and horizontal faces'

# Wall construction is derived entirely from fresh visible surfaces: choose a
# contiguous three-cell white-wool UP row within stationary reach.
$wallPlayer = [pscustomobject]@{ x = -18.5; y = 56.0; z = 11.5 }
$foundation = @(
    (New-MockSurface -Block 'minecraft:white_wool' -X -21 -Y 55 -Z 11),
    (New-MockSurface -Block 'minecraft:white_wool' -X -20 -Y 55 -Z 11),
    (New-MockSurface -Block 'minecraft:white_wool' -X -19 -Y 55 -Z 11),
    (New-MockSurface -Block 'minecraft:white_wool' -X -18 -Y 55 -Z 11),
    (New-MockSurface -Block 'minecraft:white_wool' -X -22 -Y 55 -Z 15)
)
$selectedFoundation = @(Select-ContiguousWallFoundation -Records $foundation `
    -PlayerPosition $wallPlayer)
Assert-True ($selectedFoundation.Count -eq 3) `
    'wall foundation selection did not return exactly three supports'
Assert-True ((Get-BlockPositionKey $selectedFoundation[0].position) -ceq
    'minecraft:overworld|-20|55|11') `
    'wall foundation was not selected deterministically from delivered records'
Assert-True ([object]::ReferenceEquals($foundation[1], $selectedFoundation[0])) `
    'wall foundation selection replaced a delivered visible_surface record'

# r1 stopped because the runner imposed an undocumented 4.0-block filter even
# though the product reach contract is 4.5. Preserve the real r1 geometry so
# the harness cannot narrow that contract again.
$r1Player = [pscustomobject]@{ x = -14.6192777; y = 56.0; z = 8.6406432 }
$r1Foundation = @(
    (New-MockSurface -Block 'minecraft:white_wool' -X -18 -Y 55 -Z 9),
    (New-MockSurface -Block 'minecraft:white_wool' -X -18 -Y 55 -Z 10),
    (New-MockSurface -Block 'minecraft:white_wool' -X -18 -Y 55 -Z 11)
)
$r1Selected = @(Select-ContiguousWallFoundation -Records $r1Foundation `
    -PlayerPosition $r1Player)
Assert-True ($r1Selected.Count -eq 3) `
    'wall foundation selection narrowed the product 4.5-block reach contract'
Assert-True ((Get-BlockPositionKey $r1Selected[2].position) -ceq
    'minecraft:overworld|-18|55|11') `
    'wall foundation selection lost the r1 regression row'

# r3 selected the far end of a five-wide row and combined it with an
# unnecessarily strict 0.1 arrival tolerance. Preserve that geometry and prove
# that the selector now chooses the central delivered cell with enough reach
# margin for every pose accepted by the ordinary construction tolerance.
$r3Foundation = @(9..13 | ForEach-Object {
        New-MockSurface -Block 'minecraft:white_wool' -X -18 -Y 55 -Z $_
    })
$r3EndTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -19; y = 56; z = 9
}
$r3CenterTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -19; y = 56; z = 11
}
$r3FarEndTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -19; y = 56; z = 13
}
$r3EndNavigation = New-MockTraversability -Target $r3EndTarget -Status 'CONFIRMED'
$r3CenterNavigation = New-MockTraversability `
    -Target $r3CenterTarget -Status 'CONFIRMED'
$r3FarEndNavigation = New-MockTraversability `
    -Target $r3FarEndTarget -Status 'CONFIRMED'
$r3Site = Select-WallStagingNavigationSite -WallFoundation $r3Foundation `
    -TraversabilityRecords @(
        $r3EndNavigation, $r3CenterNavigation, $r3FarEndNavigation)
Assert-True ([object]::ReferenceEquals(
        $r3CenterNavigation, $r3Site.navigation_record)) `
    'r3 staging regression did not select the central delivered target'
Assert-True ([Math]::Sqrt(
        [double]$r3Site.maximum_tolerance_bound_squared) -le 4.5) `
    'r3 staging regression did not include navigation tolerance in its reach proof'

# One delivery-backed state reference is copied verbatim into a stationary
# three-entry phase; support positions/states remain the delivered objects.
$oakState = [pscustomobject]@{
    block = 'minecraft:oak_log'; properties = [pscustomobject]@{ axis = 'y' }
}
$sourcePosition = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 56; z = 1
}
$source = [pscustomobject]@{
    kind = 'visible_surface'; block = 'minecraft:oak_log'
    position = $sourcePosition; face = 'up'; state = $oakState
    placement_item = 'minecraft:oak_log'
    placement_state_ref = 'psr_0123456789abcdef0123456789abcdef'
}
$wallPhase = New-WallRowActionPhase -Source $source `
    -Supports $selectedFoundation -RowIndex 0
$wallNode = $wallPhase.request.program.body[0]
Assert-True ($wallNode.op -ceq 'apply_known_block_plan') `
    'wall row did not use apply_known_block_plan'
Assert-True (@($wallNode.entries).Count -eq 3) `
    'wall row did not contain exactly three entries'
Assert-True (@($wallNode.entries).Count -le 8) `
    'wall row exceeded the eight-entry phase bound'
Assert-True ($wallPhase.request.budget.max_distance_blocks -eq 0) `
    'wall placement phase is not stationary'
Assert-True ($wallPhase.request.budget.max_blocks_placed -eq 3) `
    'wall row placement budget is not exactly three'
Assert-True ($wallPhase.request.budget.max_duration_ms -eq 45000) `
    'wall row duration budget does not cover three fixed-cost entries'
Assert-True ($wallPhase.request.budget.max_ticks -eq 900) `
    'wall row tick budget does not cover three fixed-cost entries'
Assert-True ($wallPhase.request.budget.max_camera_degrees -eq 240) `
    'wall row camera budget is not the exact three-entry fixed cost'
Assert-True (@($wallNode.entries | Where-Object {
            $_.placement_state_ref -cne $source.placement_state_ref
        }).Count -eq 0) 'wall row changed the delivered placement_state_ref'
for ($column = 0; $column -lt 3; $column++) {
    Assert-True ([object]::ReferenceEquals(
            $selectedFoundation[$column].position,
            $wallNode.entries[$column].support.position)) `
        "wall row transformed delivered support position $column"
    Assert-True ([object]::ReferenceEquals(
            $selectedFoundation[$column].state,
            $wallNode.entries[$column].support.expected_state)) `
        "wall row transformed delivered support state $column"
}

# The fresh post-row observation must supply the exact next supports. Extra
# policy-visible cells cannot enter the following row.
$placedRecords = @($wallPhase.targets | ForEach-Object {
        New-MockSurface -Block 'minecraft:oak_log' `
            -X $_.x -Y $_.y -Z $_.z -State $oakState
    })
$placedRecords += New-MockSurface -Block 'minecraft:oak_log' `
    -X -18 -Y 56 -Z 12 -State $oakState
$nextSupports = @(Select-ExactWallSupportRow -Records $placedRecords `
    -ExpectedPositions @($wallPhase.targets) -ExpectedState $oakState)
Assert-True ($nextSupports.Count -eq 3) `
    'fresh exact wall support selection returned the wrong count'
for ($column = 0; $column -lt 3; $column++) {
    Assert-True ([object]::ReferenceEquals($placedRecords[$column], $nextSupports[$column])) `
        "fresh exact wall support selection replaced record $column"
}

# Before row 2, one outside-footprint white-wool UP surface must join a
# direct-above traversability target from the same fresh state. Every Action
# builder retains the delivered coordinate/state object instead of rebuilding it.
$rowOnePhase = New-WallRowActionPhase -Source $source `
    -Supports $nextSupports -RowIndex 1
$temporarySupport = $foundation[0]
$temporaryTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -21; y = 56; z = 11
}
$temporaryNavigation = New-MockTraversability -Target $temporaryTarget
$duplicateTemporaryTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -21; y = 56; z = 11
}
$duplicateTemporaryNavigation = New-MockTraversability `
    -Target $duplicateTemporaryTarget -Status 'CONFIRMED'
$temporarySite = Select-TemporaryPillarSite -WhiteWoolRecords $foundation `
    -TraversabilityRecords @($temporaryNavigation, $duplicateTemporaryNavigation) `
    -WallFoundation $selectedFoundation `
    -RowOneTargets @($rowOnePhase.targets)
Assert-True ([object]::ReferenceEquals($temporarySupport, $temporarySite.support)) `
    'temporary pillar selection replaced the delivered white-wool support'
Assert-True ([object]::ReferenceEquals(
        $duplicateTemporaryNavigation, $temporarySite.navigation_record)) `
    'temporary pillar selection did not retain the preferred confirmed duplicate target record'
$farToNear = @(Sort-WallSupportsFarToNear -Supports $nextSupports `
    -ObserverPosition $temporaryTarget)
Assert-True (($farToNear | ForEach-Object {
            (Get-ObjectProperty (Get-ObjectProperty $_ 'position') 'x')
        }) -join ',' -ceq '-18,-19,-20') `
    'raised top-row supports were not ordered far-to-near deterministically'
$temporaryNavigationRequest = New-NavigationActionRequest `
    -NavigationRecord $temporarySite.navigation_record -State (New-MockState) `
    -Tolerance 0.1
Assert-True ([object]::ReferenceEquals(
        $duplicateTemporaryTarget, $temporaryNavigationRequest.program.body[0].target)) `
    'temporary pillar centering transformed the delivered navigation_target'
Assert-True ($temporaryNavigationRequest.program.body[0].tolerance -eq 0.1) `
    'temporary pillar centering did not use the required tight tolerance'
$temporaryPillarRequest = New-TemporaryPillarActionRequest `
    -Source $source -Support $temporarySupport
$temporaryPillarNode = $temporaryPillarRequest.program.body[0]
Assert-True ($temporaryPillarRequest.program.body.Count -eq 1 -and
    $temporaryPillarNode.op -ceq 'pillar_up_known') `
    'temporary pillar Action is not exclusive pillar_up_known'
Assert-True ([object]::ReferenceEquals(
        $temporarySupport.position, $temporaryPillarNode.support)) `
    'temporary pillar transformed the delivered support position'
Assert-True ([object]::ReferenceEquals(
        $temporarySupport.state, $temporaryPillarNode.expected_support)) `
    'temporary pillar transformed the delivered support state'
Assert-True ($temporaryPillarNode.placement_state_ref -ceq $source.placement_state_ref) `
    'temporary pillar changed the retained placement_state_ref'
Assert-True ($temporaryPillarRequest.budget.max_duration_ms -eq 15000 -and
    $temporaryPillarRequest.budget.max_ticks -eq 300 -and
    $temporaryPillarRequest.budget.max_distance_blocks -eq 2 -and
    $temporaryPillarRequest.budget.max_camera_degrees -eq 360 -and
    $temporaryPillarRequest.budget.max_blocks_placed -eq 1) `
    'temporary pillar budget does not match the exclusive primitive contract'

$descentTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -21; y = 56; z = 14
}
$descentNavigation = New-MockTraversability -Target $descentTarget -Status 'CONFIRMED'
$selectedDescent = Select-TemporaryPillarDescentRecord `
    -Records @($temporaryNavigation, $descentNavigation) `
    -TemporaryPosition $temporaryTarget -WallFoundation $selectedFoundation
Assert-True ([object]::ReferenceEquals($descentNavigation, $selectedDescent)) `
    'temporary pillar descent did not retain the fresh safe traversability record'
$descentRequest = New-NavigationActionRequest -NavigationRecord $selectedDescent `
    -State (New-MockState) -Tolerance 0.1
Assert-True ([object]::ReferenceEquals(
        $descentTarget, $descentRequest.program.body[0].target)) `
    'temporary pillar descent transformed the delivered navigation_target'

$freshTemporaryPosition = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -21; y = 56; z = 11
}
$freshTemporarySurface = New-MockSurface -Block 'minecraft:oak_log' `
    -X -21 -Y 56 -Z 11 -State $oakState -PlacementItem 'minecraft:oak_log' `
    -PlacementStateRef $source.placement_state_ref
$freshTemporarySurface.position = $freshTemporaryPosition
$clearRequest = New-TemporaryClearActionRequest -Surface $freshTemporarySurface
$clearNode = $clearRequest.program.body[0]
Assert-True ([object]::ReferenceEquals($freshTemporaryPosition, $clearNode.anchor)) `
    'temporary clear transformed the freshly delivered block position'
Assert-True ([object]::ReferenceEquals(
        $oakState, $clearNode.entries[0].expected_before)) `
    'temporary clear transformed the freshly delivered exact state'
Assert-True ($clearRequest.budget.max_blocks_broken -eq 1 -and
    $clearRequest.budget.max_blocks_placed -eq 0 -and
    $clearRequest.budget.max_distance_blocks -eq 0) `
    'temporary clear budget is not one stationary break'
$settleRequest = New-TemporaryDropSettleActionRequest
$settleNode = $settleRequest.program.body[0]
Assert-True ($settleNode.op -ceq 'wait_ticks' -and $settleNode.ticks -eq 40 -and
    @($settleRequest.program.capabilities).Count -eq 0 -and
    $settleRequest.budget.max_duration_ms -eq 3000 -and
    $settleRequest.budget.max_ticks -eq 40 -and
    $settleRequest.budget.max_distance_blocks -eq 0 -and
    $settleRequest.budget.max_camera_degrees -eq 0) `
    'temporary drop settle Action is not a bounded input-free wait'

$dropPosition = [pscustomobject]@{
    # Continuous x lies beyond max_x=-20, while floor(x)=-20 remains inside the
    # visible_entity position_bounds contract. The Action target stays unrounded.
    dimension = 'minecraft:overworld'; x = -19.93; y = 56.2; z = 10.2
}
$dropRecord = New-MockVisibleItem -Position $dropPosition
$collectRequest = New-TemporaryDropCollectionRequest -Record $dropRecord `
    -State (New-MockState)
$collectNode = $collectRequest.program.body[0]
Assert-True ($collectNode.displayed_item -ceq 'minecraft:oak_log' -and
    [object]::ReferenceEquals($dropPosition, $collectNode.target)) `
    'temporary drop collection did not copy fresh visible_entity evidence verbatim'

# A recoverable admission error happens before an Action exists. Keep its
# structured code so orchestration can choose a new policy-delivered viewpoint
# without inventing an action_id or parsing exception text.
$savedToolTransport = $script:ToolTransport
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    if ($Tool -cne 'agent_start_action') { throw "unexpected test tool: $Tool" }
    return [pscustomobject]@{
        __domain_error = [pscustomobject]@{
            code = 'TARGET_UNKNOWN'
            message = 'No known safe pickup cell overlaps the visible item'
            recoverable = $true
        }
    }
}
$rejectedCollect = Invoke-ActionRequest -Request $collectRequest `
    -ReturnStartDomainError
Assert-True ($rejectedCollect.state -ceq 'rejected' -and
    $rejectedCollect.start_domain_error.code -ceq 'TARGET_UNKNOWN' -and
    $rejectedCollect.start_domain_error.recoverable -and
    @($script:GateEvents | Where-Object { $_.event -ceq 'action_accepted' }).Count -eq 0 -and
    @($script:GateEvents | Where-Object {
            $_.event -ceq 'tool_call_domain_error' -and $_.code -ceq 'TARGET_UNKNOWN'
        }).Count -eq 1) `
    'recoverable collect admission did not remain structured and action-free'
$script:ToolTransport = $savedToolTransport

# Recovery is decided from two independent policy-visible ledgers. Passive
# pickup is the only valid one-item inventory increase with no remaining drop;
# unchanged inventory requires exactly one delivered drop and an active collect.
$passiveRecovery = Resolve-TemporaryDropRecovery `
    -InventoryBeforeClear 33 -InventoryAfterSettle 34 -VisibleDrops @()
Assert-True ($passiveRecovery.recovery_mode -ceq 'passive_pickup' -and
    $passiveRecovery.inventory_delta -eq 1 -and
    $passiveRecovery.visible_drop_count -eq 0 -and $null -eq $passiveRecovery.drop) `
    'temporary cleanup did not classify passive pickup from its exact ledger'
$activeRecovery = Resolve-TemporaryDropRecovery `
    -InventoryBeforeClear 33 -InventoryAfterSettle 33 -VisibleDrops @($dropRecord)
Assert-True ($activeRecovery.recovery_mode -ceq 'active_collect' -and
    $activeRecovery.inventory_delta -eq 0 -and
    $activeRecovery.visible_drop_count -eq 1 -and
    [object]::ReferenceEquals($dropRecord, $activeRecovery.drop)) `
    'temporary cleanup did not retain the one delivered active-collect drop'

# Item visibility and pickup traversability are separate policy evidence. The
# runner publishes only current safe records and leaves pickup-cell selection to
# the product planner.
$pickupRevision = 73L
$pickupState = New-MockState
$pickupState.world.world_revision = $pickupRevision
$pickupTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -19; y = 57; z = 10
}
$pickupSafe = New-MockTraversability -Target $pickupTarget `
    -Status 'CONFIRMED' -WorldRevision $pickupRevision
$pickupStale = New-MockTraversability -Target ([pscustomobject]@{
        dimension = 'minecraft:overworld'; x = -18; y = 56; z = 10
    }) -WorldRevision ($pickupRevision - 1)
$savedGetRecordsFromStateForPickup = ${function:Get-RecordsFromState}
$script:MockPickupQueryBounds = $null
$script:MockPickupRecords = @($pickupSafe, $pickupStale)
function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    $script:MockPickupQueryBounds = $Filter.position_bounds
    return @($pickupSafe, $pickupStale)
}
$pickupRecords = @(Get-CurrentTemporaryDropPickupTraversabilityRecords `
        -State $pickupState -Drop $dropRecord)
Assert-True ($pickupRecords.Count -eq 1 -and
    [object]::ReferenceEquals($pickupRecords[0], $pickupSafe)) `
    'temporary pickup delivery did not retain only current safe traversability'
Assert-True ($script:MockPickupQueryBounds.min_x -eq -24 -and
    $script:MockPickupQueryBounds.max_x -eq -16 -and
    $script:MockPickupQueryBounds.min_y -eq 53 -and
    $script:MockPickupQueryBounds.max_y -eq 59 -and
    $script:MockPickupQueryBounds.min_z -eq 6 -and
    $script:MockPickupQueryBounds.max_z -eq 14) `
    'temporary pickup traversability was not bounded around the continuous drop position'
$pickupDelivery = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_drop_pickup_traversability_current'
    } | Select-Object -Last 1)
Assert-True ($pickupDelivery.Count -eq 1 -and
    $pickupDelivery[0].records_delivered_for_product_planner_selection -and
    -not $pickupDelivery[0].pickup_cell_selected) `
    'pickup evidence claimed planner selection before collect admission'
function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    return @($pickupStale)
}
$missingCurrentPickupRejected = $false
try {
    [void]@(Get-CurrentTemporaryDropPickupTraversabilityRecords `
            -State $pickupState -Drop $dropRecord)
} catch {
    $missingCurrentPickupRejected = $_.Exception.Message -ceq
        'no current safe traversability was delivered for temporary drop pickup'
}
Assert-True $missingCurrentPickupRejected `
    'temporary pickup delivery accepted only stale traversability'
${function:Get-RecordsFromState} = $savedGetRecordsFromStateForPickup
$invalidRecoveryCases = @(
    [pscustomobject]@{ before = 33; after = 32; drops = @() },
    [pscustomobject]@{ before = 33; after = 35; drops = @() },
    [pscustomobject]@{ before = 33; after = 34; drops = @($dropRecord) },
    [pscustomobject]@{ before = 33; after = 33; drops = @() },
    [pscustomobject]@{ before = 33; after = 33; drops = @($dropRecord, $dropRecord) }
)
foreach ($invalidRecovery in $invalidRecoveryCases) {
    $invalidRecoveryRejected = $false
    try {
        [void](Resolve-TemporaryDropRecovery `
                -InventoryBeforeClear $invalidRecovery.before `
                -InventoryAfterSettle $invalidRecovery.after `
                -VisibleDrops @($invalidRecovery.drops))
    } catch {
        $invalidRecoveryRejected = $_.Exception.Message -cmatch
            '^temporary pillar recovery evidence is inconsistent:'
    }
    Assert-True $invalidRecoveryRejected `
        "temporary cleanup accepted contradictory recovery evidence $($invalidRecovery.before)/$($invalidRecovery.after)/$(@($invalidRecovery.drops).Count)"
}

# A newly broken item can legitimately be absent from the first fresh entity
# frame even though it has not entered inventory yet. The orchestration waits
# across distinct frames without issuing an Action, then binds to the first
# supported recovery ledger. The wait remains bounded and contradictory
# evidence still fails immediately through Resolve-TemporaryDropRecovery.
$savedGetFreshState = ${function:Get-FreshState}
$savedGetTemporaryDropRecords = ${function:Get-TemporaryDropRecords}
$savedDelayTransport = $script:DelayTransport
$recoveryPending1 = New-MockState
$recoveryPending1.observation.latest_frame_id = 'obs-0000000000000111'
$recoveryPending1.inventory = @([pscustomobject]@{
        item = 'minecraft:oak_log'; count = 33L
    })
$recoveryPending2 = New-MockState
$recoveryPending2.observation.latest_frame_id = 'obs-0000000000000112'
$recoveryPending2.inventory = @([pscustomobject]@{
        item = 'minecraft:oak_log'; count = 33L
    })
$recoveryVisible = New-MockState
$recoveryVisible.observation.latest_frame_id = 'obs-0000000000000113'
$recoveryVisible.inventory = @([pscustomobject]@{
        item = 'minecraft:oak_log'; count = 33L
    })
$script:RecoveryWaitStates = @($recoveryPending2, $recoveryVisible)
$script:RecoveryWaitStateIndex = 0
$script:RecoveryWaitDelayCalls = 0
$script:GateEvents = [Collections.Generic.List[object]]::new()
function Get-FreshState {
    $state = $script:RecoveryWaitStates[$script:RecoveryWaitStateIndex]
    $script:RecoveryWaitStateIndex++
    return $state
}
function Get-TemporaryDropRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    if ((Get-ObservationFrameId -State $State) -ceq 'obs-0000000000000113') {
        return @($dropRecord)
    }
    return @()
}
$script:DelayTransport = { param($Seconds) $script:RecoveryWaitDelayCalls++ }
$waitedRecovery = Wait-ForTemporaryDropRecoveryEvidence `
    -InitialState $recoveryPending1 -TemporaryPosition $freshTemporaryPosition `
    -InventoryBeforeClear 33 -MaximumPolls 3 -DelayMilliseconds 1
Assert-True ($waitedRecovery.recovery.recovery_mode -ceq 'active_collect' -and
    $waitedRecovery.polls -eq 3 -and $waitedRecovery.observed_frames -eq 3 -and
    $waitedRecovery.pending_empty_observations -eq 2 -and
    $script:RecoveryWaitDelayCalls -eq 2 -and
    [object]::ReferenceEquals($waitedRecovery.recovery.drop, $dropRecord)) `
    'temporary recovery did not wait for delayed fresh entity evidence'
$recoveryReadyEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_drop_recovery_evidence_ready'
    })
Assert-True ($recoveryReadyEvents.Count -eq 1 -and
    $recoveryReadyEvents[0].polls -eq 3 -and
    $recoveryReadyEvents[0].pending_empty_observations -eq 2) `
    'temporary recovery did not record its bounded observation wait'

$script:RecoveryWaitStates = @($recoveryPending2)
$script:RecoveryWaitStateIndex = 0
$script:RecoveryWaitDelayCalls = 0
$boundedRecoveryRejected = $false
try {
    [void](Wait-ForTemporaryDropRecoveryEvidence `
        -InitialState $recoveryPending1 -TemporaryPosition $freshTemporaryPosition `
        -InventoryBeforeClear 33 -MaximumPolls 2 -DelayMilliseconds 1)
} catch {
    $boundedRecoveryRejected = $_.Exception.Message -ceq
        'temporary pillar recovery evidence remained unavailable after 2 bounded poll(s): observed_frames=2, pending_empty_observations=2'
}
Assert-True ($boundedRecoveryRejected -and $script:RecoveryWaitDelayCalls -eq 1) `
    'temporary recovery did not fail closed at its exact bounded limit'

$script:RecoveryWaitStates = @($recoveryPending2)
$script:RecoveryWaitStateIndex = 0
$script:RecoveryWaitDelayCalls = 0
$script:GateEvents = [Collections.Generic.List[object]]::new()
$unavailableRecovery = Wait-ForTemporaryDropRecoveryEvidence `
    -InitialState $recoveryPending1 -TemporaryPosition $freshTemporaryPosition `
    -InventoryBeforeClear 33 -MaximumPolls 2 -DelayMilliseconds 1 -AllowUnavailable
Assert-True ($null -eq $unavailableRecovery.recovery -and
    $unavailableRecovery.polls -eq 2 -and
    $unavailableRecovery.pending_empty_observations -eq 2 -and
    @($script:GateEvents | Where-Object {
            $_.event -ceq 'temporary_drop_recovery_evidence_unavailable'
        }).Count -eq 1) `
    'temporary recovery did not expose bounded unavailability for passive approach'
${function:Get-FreshState} = $savedGetFreshState
${function:Get-TemporaryDropRecords} = $savedGetTemporaryDropRecords
$script:DelayTransport = $savedDelayTransport

$approachRevision = 81L
$approachState = New-MockState
$approachState.world.world_revision = $approachRevision
$approachState.world.position = [pscustomobject]@{ x = -18.0; y = 56.0; z = 11.0 }
$approachGoal = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 58; z = 10
}
$approachTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 58; z = 10
}
$approachSafe = New-MockTraversability -Target $approachTarget `
    -Status 'CONFIRMED' -WorldRevision $approachRevision
$approachStale = New-MockTraversability -Target ([pscustomobject]@{
        dimension = 'minecraft:overworld'; x = -19; y = 57; z = 10
    }) -Status 'CONFIRMED' -WorldRevision ($approachRevision - 1)
$savedGetWallScaffoldTraversabilityRecords = ${function:Get-WallScaffoldTraversabilityRecords}
function Get-WallScaffoldTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State, [int]$AdditionalHeight = 4)
    return @($approachStale, $approachSafe)
}
$currentApproachRecords = @(Get-CurrentSafeWallTraversabilityRecords -State $approachState)
$selectedRecoveryApproach = Select-TemporaryDropRecoveryApproachRecord `
    -Records $currentApproachRecords -State $approachState `
    -TemporaryPosition $approachGoal
Assert-True ($currentApproachRecords.Count -eq 1 -and
    [object]::ReferenceEquals($selectedRecoveryApproach, $approachSafe) -and
    [object]::ReferenceEquals(
        $approachTarget, (Get-ObjectProperty $selectedRecoveryApproach 'navigation_target'))) `
    'temporary passive recovery did not retain the current policy-delivered progress target'
${function:Get-WallScaffoldTraversabilityRecords} = $savedGetWallScaffoldTraversabilityRecords

# The offline MCA comparison contract enumerates nine unique expected-air
# cells and rejects every unlisted source/destination/work-area mutation.
$oracleTargets = [Collections.Generic.List[object]]::new()
$rowSupports = @($selectedFoundation)
for ($row = 0; $row -lt 3; $row++) {
    $phase = New-WallRowActionPhase -Source $source -Supports $rowSupports -RowIndex $row
    foreach ($targetPosition in @($phase.targets)) { $oracleTargets.Add($targetPosition) }
    $rowSupports = @($phase.targets | ForEach-Object {
            New-MockSurface -Block 'minecraft:oak_log' `
                -X $_.x -Y $_.y -Z $_.z -State $oakState
        })
}
$oracle = New-WallExternalOracleManifest -Targets @($oracleTargets) `
    -ExpectedState $oakState -SourcePosition $sourcePosition `
    -TemporaryPositions @($temporaryTarget)
Assert-True ($oracle.expected_changed_cell_count -eq 9) `
    'wall oracle manifest did not require exactly nine changed cells'
Assert-True (@($oracle.expected_changed_cells).Count -eq 9) `
    'wall oracle manifest did not enumerate nine target cells'
Assert-True (@($oracle.expected_changed_cells | Where-Object {
            $_.before_state.block -cne 'minecraft:air' -or
            $_.after_state.block -cne 'minecraft:oak_log'
        }).Count -eq 0) `
    'wall oracle manifest lost expected-air or expected-state checks'
Assert-True $oracle.reject_unlisted_changes `
    'wall oracle manifest does not reject extra mutations'
Assert-True (-not $oracle.expected_source.changed) `
    'wall oracle manifest does not require an unchanged source'
Assert-True ($oracle.temporary_scaffold.before_state.block -ceq 'minecraft:air' -and
    $oracle.temporary_scaffold.after_state.block -ceq 'minecraft:air' -and
    -not $oracle.temporary_scaffold.included_in_expected_changed_cells -and
    $oracle.temporary_scaffold.cleanup_required -and
    $oracle.temporary_scaffold.drop_collection_required -ceq 'minecraft:oak_log') `
    'wall oracle manifest does not require complete temporary-pillar cleanup'
Assert-True ($oracle.expected_air_violations -eq 0 -and
    $oracle.expected_extra_mutations -eq 0) `
    'wall oracle manifest does not pin both violation counts to zero'

# A fully mocked wall run verifies the result ledger and the three terminal,
# freshly observed row phases without opening Minecraft or using the network.
$script:MockWallPlaced = 0
$script:MockWallInventory = 64
$script:MockWallAction = 0
$script:MockWallRowFace = 0
$script:MockWallCleanupFace = 0
$script:MockWallNavigation = 0
$script:MockWallPillar = 0
$script:MockWallClear = 0
$script:MockWallCollect = 0
$script:MockWallWait = 0
$script:MockTemporaryCleared = $false
$script:MockWallPlacedTargets = @()
$script:MockWallTopTargetKeys = [Collections.Generic.List[string]]::new()
$script:MockWallPlacementRequests = [Collections.Generic.List[object]]::new()
$script:MockTemporaryClearKeys = [Collections.Generic.List[string]]::new()
$script:MockWallDropProbes = 0
$script:MockPassivePickup = $true
$script:MockRejectNextCollectStart = $false
$script:MockPendingPassiveRecoveryPickup = $false
$script:MockFrameCounter = 0
$script:MockLastFaceFrame = -1
$script:MockLastPlacementFrame = -1
$script:MockPendingStaleStateCalls = 0
$script:MockWorldRevision = 1L
$script:MockPendingStaleSurfaceDeliveries = 0
$script:MockFrameAdvanceDelayCalls = 0
$script:MockSurfaceDeliveryFrame = @{}
$script:MockWallPlayer = [pscustomobject]@{ x = -18.5; y = 56.0; z = 13.5 }
$script:MockWallFoundation = @($foundation)
$script:MockWallTraversability = @($temporaryNavigation, $descentNavigation)
$script:MockPillarNavigationTarget = $temporaryTarget
$script:MockPillarNavigationTargetKeys = @((Get-BlockPositionKey $temporaryTarget))
$script:MockRequireAdjacentDescent = $false
$script:MockTemporarySurfaces = @{}
$script:MockTemporaryPositions = @()
$script:MockCurrentDropRecord = $null
$script:MockPlayerPoseEvents = [Collections.Generic.List[object]]::new()
$script:MockNavigationEdges = [Collections.Generic.List[object]]::new()
$script:GateEvents = [Collections.Generic.List[object]]::new()
function Acquire-OakLogFromChest { return 64L }
function Move-NearDestinationSupport {
    param([ValidateSet(3, 5)][int]$Width = 3)
    if ($Width -eq 5) { Invoke-WallStagingNavigation -Width $Width }
}
function Get-FreshState {
    if ($script:MockPendingStaleStateCalls -gt 0) {
        $script:MockPendingStaleStateCalls--
    } else {
        $script:MockFrameCounter++
    }
    $state = New-MockState
    $state.observation.latest_frame_id = 'obs-{0:x16}' -f $script:MockFrameCounter
    $state.world.world_revision = $script:MockWorldRevision
    $state.world.position = $script:MockWallPlayer
    $state.inventory = @([pscustomobject]@{
            item = 'minecraft:oak_log'; count = [long]$script:MockWallInventory
        })
    return $state
}
function Get-OakLogPlacementSource {
    param([Parameter(Mandatory)][object]$State)
    Assert-SourceObservationAllowed
    $script:SourceObservationCount++
    return $source
}
function Get-VisibleSurfaceRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [switch]$AllowMissing
    )
    Assert-True ((Get-ObjectProperty (Get-ObjectProperty $State 'observation') `
                'latest_frame_id') -ceq ('obs-{0:x16}' -f $script:MockFrameCounter)) `
        'wall observation did not use the latest fresh frame'
    if ($script:MockLastFaceFrame -ge 0) {
        Assert-True ($script:MockFrameCounter -gt $script:MockLastFaceFrame) `
            'wall support was not refreshed after its face Action'
    }
    $records = @()
    if ($Block -ceq 'minecraft:white_wool') {
        $records = @($script:MockWallFoundation)
    }
    $exactTemporary = $Bounds.min_x -eq $Bounds.max_x -and
        $Bounds.min_y -eq $Bounds.max_y -and $Bounds.min_z -eq $Bounds.max_z
    if ($Block -ceq 'minecraft:oak_log' -and $exactTemporary) {
        $temporaryKey = ('minecraft:overworld|{0}|{1}|{2}' -f
            [int]$Bounds.min_x, [int]$Bounds.min_y, [int]$Bounds.min_z)
        if ($script:MockTemporarySurfaces.ContainsKey($temporaryKey)) {
            $records = @($script:MockTemporarySurfaces[$temporaryKey])
        } else {
            $records = @($script:MockWallPlacedTargets | Where-Object {
                    [int]$_.x -eq [int]$Bounds.min_x -and
                    [int]$_.y -eq [int]$Bounds.min_y -and
                    [int]$_.z -eq [int]$Bounds.min_z
                } | ForEach-Object {
                    $surfaceFace = if ($Faces -contains 'east') { 'east' } else { 'up' }
                    New-MockSurface -Block 'minecraft:oak_log' `
                        -X $_.x -Y $_.y -Z $_.z -Face $surfaceFace -State $oakState
                })
        }
    }
    if ($Block -ceq 'minecraft:oak_log' -and $records.Count -eq 0 -and
        -not $exactTemporary) {
        $records = @($script:MockWallPlacedTargets | ForEach-Object {
                New-MockSurface -Block 'minecraft:oak_log' `
                    -X $_.x -Y $_.y -Z $_.z -State $oakState
            })
    }
    if ($ExcludePlayerFeetAbove) {
        $feetX = [Math]::Floor([double]$script:MockWallPlayer.x)
        $feetY = [Math]::Floor([double]$script:MockWallPlayer.y)
        $feetZ = [Math]::Floor([double]$script:MockWallPlayer.z)
        $records = @($records | Where-Object {
                $position = Get-ObjectProperty $_ 'position'
                -not ([int]$position.x -eq $feetX -and
                    [int]$position.y + 1 -eq $feetY -and
                    [int]$position.z -eq $feetZ)
            })
    }
    if ($records.Count -eq 0 -and -not $AllowMissing) {
        throw "mock delivered no eligible $Block surface"
    }
    if ($records.Count -gt 0) {
        $surfaceRevision = $script:MockWorldRevision
        if ($script:MockPendingStaleSurfaceDeliveries -gt 0) {
            $script:MockPendingStaleSurfaceDeliveries--
            $surfaceRevision = [Math]::Max(1L, $script:MockWorldRevision - 1L)
        }
        foreach ($record in $records) {
            $record.world_revision = $surfaceRevision
        }
    }
    foreach ($record in $records) {
        $script:MockSurfaceDeliveryFrame[
            (Get-BlockPositionKey (Get-ObjectProperty $record 'position'))] =
                $script:MockFrameCounter
    }
    return @($records)
}
function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    Assert-True ((Get-ObjectProperty (Get-ObjectProperty $State 'observation') `
                'latest_frame_id') -ceq ('obs-{0:x16}' -f $script:MockFrameCounter)) `
        'wall record query did not use the latest fresh frame'
    if ($Kinds.Count -eq 1 -and $Kinds[0] -ceq 'traversability') {
        $records = @($script:MockWallTraversability)
        foreach ($record in $records) {
            $record.world_revision = $script:MockWorldRevision
        }
        if ($null -ne $Filter -and $Filter.Contains('position_bounds')) {
            $bounds = $Filter.position_bounds
            $records = @($records | Where-Object {
                    $target = Get-ObjectProperty $_ 'navigation_target'
                    [int]$target.x -ge [int]$bounds.min_x -and
                    [int]$target.x -le [int]$bounds.max_x -and
                    [int]$target.y -ge [int]$bounds.min_y -and
                    [int]$target.y -le [int]$bounds.max_y -and
                    [int]$target.z -ge [int]$bounds.min_z -and
                    [int]$target.z -le [int]$bounds.max_z
                })
        }
        return @($records | Where-Object {
                $target = Get-ObjectProperty $_ 'navigation_target'
                if ([int]$target.y -le 56) { return $true }
                $support = [pscustomobject]@{
                    dimension = $target.dimension
                    x = [int]$target.x
                    y = [int]$target.y - 1
                    z = [int]$target.z
                }
                return $script:MockTemporarySurfaces.ContainsKey(
                    (Get-BlockPositionKey $support))
            })
    }
    if ($Kinds.Count -eq 1 -and $Kinds[0] -ceq 'visible_entity') {
        $script:MockWallDropProbes++
        if ($null -ne $script:MockCurrentDropRecord) {
            return @($script:MockCurrentDropRecord)
        }
    }
    return @()
}
function Test-MockScaffoldRouteToGround {
    param(
        [Parameter(Mandatory)][int]$FromX,
        [Parameter(Mandatory)][int]$FromY,
        [Parameter(Mandatory)][int]$FromZ,
        [Parameter(Mandatory)][object]$Target
    )
    $groundY = [int]$script:DestinationSupportBounds.min_y + 1
    $columnTops = @{}
    foreach ($surface in @($script:MockTemporarySurfaces.Values)) {
        $position = Get-ObjectProperty $surface 'position'
        $columnKey = '{0}|{1}' -f [int]$position.x, [int]$position.z
        $topY = [int]$position.y + 1
        if (-not $columnTops.ContainsKey($columnKey) -or
            $topY -gt [int]$columnTops[$columnKey]) {
            $columnTops[$columnKey] = $topY
        }
    }
    $nodes = @{}
    foreach ($columnKey in $columnTops.Keys) {
        $parts = $columnKey -split '\|'
        $key = '{0}|{1}|{2}' -f [int]$parts[0], [int]$columnTops[$columnKey], [int]$parts[1]
        $nodes[$key] = $true
    }
    foreach ($record in @($script:MockWallTraversability)) {
        $position = Get-ObjectProperty $record 'navigation_target'
        if ([int]$position.y -ne $groundY) { continue }
        $columnKey = '{0}|{1}' -f [int]$position.x, [int]$position.z
        if ($columnTops.ContainsKey($columnKey)) { continue }
        $key = '{0}|{1}|{2}' -f [int]$position.x, [int]$position.y, [int]$position.z
        $nodes[$key] = $true
    }
    $startKey = '{0}|{1}|{2}' -f $FromX, $FromY, $FromZ
    $targetKey = '{0}|{1}|{2}' -f [int]$Target.x, [int]$Target.y, [int]$Target.z
    $nodes[$startKey] = $true
    $nodes[$targetKey] = $true
    $queue = [Collections.Generic.Queue[string]]::new()
    $visited = @{}
    $queue.Enqueue($startKey)
    $visited[$startKey] = $true
    while ($queue.Count -gt 0) {
        $key = $queue.Dequeue()
        if ($key -ceq $targetKey) { return $true }
        $parts = $key -split '\|'
        $x = [int]$parts[0]
        $y = [int]$parts[1]
        $z = [int]$parts[2]
        foreach ($candidateKey in @($nodes.Keys)) {
            if ($visited.ContainsKey($candidateKey)) { continue }
            $candidate = $candidateKey -split '\|'
            $dx = [Math]::Abs([int]$candidate[0] - $x)
            $dy = [Math]::Abs([int]$candidate[1] - $y)
            $dz = [Math]::Abs([int]$candidate[2] - $z)
            if ($dx + $dz -eq 1 -and $dy -le 1) {
                $visited[$candidateKey] = $true
                $queue.Enqueue($candidateKey)
            }
        }
    }
    return $false
}
function Invoke-ActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Request,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180,
        [switch]$ReturnFailure,
        [switch]$ReturnStartDomainError
    )
    $node = $Request.program.body[0]
    if ($node.op -ceq 'collect_visible_item' -and
        $script:MockRejectNextCollectStart) {
        $script:MockRejectNextCollectStart = $false
        $script:MockPendingPassiveRecoveryPickup = $true
        Assert-True $ReturnStartDomainError `
            'mock collect admission rejection was not requested as structured data'
        return [pscustomobject]@{
            state = 'rejected'
            start_domain_error = [pscustomobject]@{
                code = 'TARGET_UNKNOWN'
                message = 'No known safe pickup cell overlaps the visible item'
                recoverable = $true
            }
        }
    }
    $script:MockWallAction++
    $actionId = '550e8400-e29b-41d4-a716-{0:d12}' -f $script:MockWallAction
    Add-GateEvent -Event 'action_accepted' -Detail ([ordered]@{
            action_id = $actionId
            program = [string](Get-ObjectProperty $Request.program 'name')
        })
    switch ($node.op) {
        'apply_known_block_plan' {
            $script:MockWallPlacementRequests.Add($Request)
            $newTargets = @($node.entries | ForEach-Object {
                    [pscustomobject]@{
                        dimension = $node.anchor.dimension
                        x = [int]$node.anchor.x + [int]$_.offset.x
                        y = [int]$node.anchor.y + [int]$_.offset.y
                        z = [int]$node.anchor.z + [int]$_.offset.z
                    }
                })
            $script:MockWallPlacedTargets += $newTargets
            if (@($node.entries).Count -eq 1) {
                $script:MockWallTopTargetKeys.Add((Get-BlockPositionKey $newTargets[0]))
            }
            $script:MockWallPlaced += [int]$Request.budget.max_blocks_placed
            $script:MockWallInventory -= [int]$Request.budget.max_blocks_placed
            $script:MockLastPlacementFrame = $script:MockFrameCounter
            $script:MockPendingStaleStateCalls = 1
            $script:MockWorldRevision++
            $script:MockPendingStaleSurfaceDeliveries = 1
        }
        'navigate_to_known' {
            $script:MockWallNavigation++
            $targetKey = Get-BlockPositionKey $node.target
            if ($node.tolerance -eq $script:PillarNavigationTolerance) {
                Assert-True ($targetKey -cin $script:MockPillarNavigationTargetKeys) `
                    'tight pillar navigation did not target a delivered staircase base'
            } else {
                Assert-True ($node.tolerance -eq $script:ConstructionNavigationTolerance) `
                    'construction navigation did not retain its purpose-specific tolerance'
            }
            Assert-True (@($script:MockWallTraversability | Where-Object {
                        [object]::ReferenceEquals(
                            $node.target, (Get-ObjectProperty $_ 'navigation_target'))
                    }).Count -gt 0) `
                'temporary navigation target did not originate in a delivered record'
            $fromX = [Math]::Floor([double]$script:MockWallPlayer.x)
            $fromY = [Math]::Floor([double]$script:MockWallPlayer.y)
            $fromZ = [Math]::Floor([double]$script:MockWallPlayer.z)
            $dx = [Math]::Abs([int]$node.target.x - $fromX)
            $dy = [Math]::Abs([int]$node.target.y - $fromY)
            $dz = [Math]::Abs([int]$node.target.z - $fromZ)
            $safeDescentProved = $true
            if ($script:MockRequireAdjacentDescent -and
                [int]$node.target.y -lt $fromY) {
                $safeDescentProved = ($dx + $dz -eq 1 -and $dy -le 1) -or
                    (Test-MockScaffoldRouteToGround -FromX $fromX -FromY $fromY `
                        -FromZ $fromZ -Target $node.target)
                Assert-True $safeDescentProved `
                    "mock found no one-step-safe scaffold route $fromX,$fromY,$fromZ -> $($node.target.x),$($node.target.y),$($node.target.z)"
            }
            $script:MockNavigationEdges.Add([pscustomobject]@{
                    from = [pscustomobject]@{ x = $fromX; y = $fromY; z = $fromZ }
                    target = $node.target
                    horizontal_manhattan = $dx + $dz
                    absolute_y_delta = $dy
                    safe_descent_proved = $safeDescentProved
                })
            $script:MockWallPlayer = [pscustomobject]@{
                x = [double]$node.target.x + 0.5
                y = [double]$node.target.y
                z = [double]$node.target.z + 0.5
            }
            $script:MockPlayerPoseEvents.Add([pscustomobject]@{
                    op = 'navigate'; position = $script:MockWallPlayer
                })
            if ($script:MockPendingPassiveRecoveryPickup -and
                $null -ne $script:MockCurrentDropRecord) {
                $script:MockWallInventory++
                $script:MockCurrentDropRecord = $null
                $script:MockPendingPassiveRecoveryPickup = $false
            }
        }
        'pillar_up_known' {
            $script:MockWallPillar++
            $script:MockWallInventory--
            Assert-True ([Math]::Floor([double]$script:MockWallPlayer.x) -eq
                    [int]$node.support.x -and
                [Math]::Floor([double]$script:MockWallPlayer.y) -eq
                    ([int]$node.support.y + 1) -and
                [Math]::Floor([double]$script:MockWallPlayer.z) -eq
                    [int]$node.support.z) `
                'mock pillar began away from the delivered support-above feet cell'
            $deliveredSupports = @($script:MockWallFoundation) +
                @($script:MockTemporarySurfaces.Values)
            Assert-True (@($deliveredSupports | Where-Object {
                        [object]::ReferenceEquals(
                            $node.support, (Get-ObjectProperty $_ 'position')) -and
                        [object]::ReferenceEquals(
                            $node.expected_support, (Get-ObjectProperty $_ 'state'))
                    }).Count -eq 1) `
                'mock pillar did not retain delivered support evidence'
            $placedPosition = Get-TargetAboveSupport $node.support
            $knownNavigationPosition = @($script:MockWallTraversability | ForEach-Object {
                    Get-ObjectProperty $_ 'navigation_target'
                } | Where-Object {
                    (Get-BlockPositionKey $_) -ceq (Get-BlockPositionKey $placedPosition)
                } | Select-Object -First 1)
            if ($knownNavigationPosition.Count -eq 1) {
                $placedPosition = $knownNavigationPosition[0]
            }
            $placedSurface = New-MockSurface -Block 'minecraft:oak_log' `
                -X $placedPosition.x -Y $placedPosition.y -Z $placedPosition.z `
                -State $oakState -PlacementItem 'minecraft:oak_log' `
                -PlacementStateRef $source.placement_state_ref
            $placedSurface.position = $placedPosition
            $placedKey = Get-BlockPositionKey $placedPosition
            $script:MockTemporarySurfaces[$placedKey] = $placedSurface
            $script:MockTemporaryPositions += $placedPosition
            $script:MockWallPlayer = [pscustomobject]@{
                x = [double]$placedPosition.x + 0.5
                y = [double]$placedPosition.y + 1.0
                z = [double]$placedPosition.z + 0.5
            }
            $script:MockPlayerPoseEvents.Add([pscustomobject]@{
                    op = 'pillar'; position = $script:MockWallPlayer
                })
            $script:MockLastPlacementFrame = $script:MockFrameCounter
            $script:MockPendingStaleStateCalls = 1
            $script:MockWorldRevision++
            $script:MockPendingStaleSurfaceDeliveries = 1
        }
        'clear_known_block_plan' {
            $script:MockWallClear++
            $script:MockTemporaryCleared = $true
            $clearKey = Get-BlockPositionKey $node.anchor
            $script:MockTemporaryClearKeys.Add($clearKey)
            Assert-True (-not (
                    [Math]::Floor([double]$script:MockWallPlayer.x) -eq
                        [int]$node.anchor.x -and
                    [Math]::Floor([double]$script:MockWallPlayer.y) -eq
                        ([int]$node.anchor.y + 1) -and
                    [Math]::Floor([double]$script:MockWallPlayer.z) -eq
                        [int]$node.anchor.z)) `
                'mock clear attempted to break the temporary block below player feet'
            Assert-True ($script:MockTemporarySurfaces.ContainsKey($clearKey)) `
                'mock clear did not retain the fresh temporary position'
            [void]$script:MockTemporarySurfaces.Remove($clearKey)
            $dropPositionForClear = [pscustomobject]@{
                dimension = [string]$node.anchor.dimension
                x = [double]$node.anchor.x + 0.2
                y = [double]$node.anchor.y + 0.2
                z = [double]$node.anchor.z + 0.2
            }
            if ($clearKey -ceq (Get-BlockPositionKey $temporaryTarget)) {
                $dropPositionForClear = $dropPosition
            }
            $script:MockCurrentDropRecord = New-MockVisibleItem `
                -Position $dropPositionForClear
            $script:MockPendingStaleStateCalls = 1
            $script:MockWorldRevision++
            $script:MockPendingStaleSurfaceDeliveries = 1
        }
        'collect_visible_item' {
            $script:MockWallCollect++
            $script:MockWallInventory++
            Assert-True ($null -ne $script:MockCurrentDropRecord -and
                [object]::ReferenceEquals(
                    $node.target,
                    (Get-ObjectProperty $script:MockCurrentDropRecord 'position'))) `
                'mock collect did not retain the fresh visible_entity position'
            $pickupTarget = Get-ObjectProperty $script:MockCurrentDropRecord 'position'
            $script:MockWallPlayer = [pscustomobject]@{
                x = [Math]::Floor([double]$pickupTarget.x) + 0.5
                y = [Math]::Floor([double]$pickupTarget.y)
                z = [Math]::Floor([double]$pickupTarget.z) + 0.5
            }
            $script:MockPlayerPoseEvents.Add([pscustomobject]@{
                    op = 'collect'; position = $script:MockWallPlayer
                })
            $script:MockCurrentDropRecord = $null
        }
        'wait_ticks' {
            $script:MockWallWait++
            Assert-True ($node.ticks -eq 40 -and
                @($Request.program.capabilities).Count -eq 0) `
                'mock settle Action was not the bounded input-free wait'
            if ($script:MockPassivePickup -and
                $null -ne $script:MockCurrentDropRecord) {
                $script:MockWallInventory++
                $script:MockCurrentDropRecord = $null
            }
        }
        'face_known_position' {
            Assert-True ($null -ne $node.target) `
                'wall face Action did not retain a delivered target'
            $faceKey = Get-BlockPositionKey $node.target
            if ($script:MockLastPlacementFrame -ge 0) {
                Assert-True ($script:MockFrameCounter -gt $script:MockLastPlacementFrame -and
                    $script:MockSurfaceDeliveryFrame.ContainsKey($faceKey) -and
                    [int]$script:MockSurfaceDeliveryFrame[$faceKey] -eq
                        $script:MockFrameCounter) `
                    'wall face reused surface evidence invalidated by the previous placement'
            }
            if ((Get-BlockPositionKey $node.target) -cin
                @($script:MockTemporaryPositions | ForEach-Object {
                        Get-BlockPositionKey $_
                    })) {
                $script:MockWallCleanupFace++
            } else {
                $script:MockWallRowFace++
            }
            $script:MockLastFaceFrame = $script:MockFrameCounter
        }
        default { throw "unexpected wall mock op: $($node.op)" }
    }
    Add-GateEvent -Event 'action_terminal' -Detail ([ordered]@{
            action_id = $actionId; state = 'succeeded'
        })
    return New-MockActionSnapshot -ActionId $actionId -State 'succeeded'
}
$script:DelayTransport = {
    param($Seconds)
    Assert-True ([Math]::Abs([double]$Seconds - 0.05) -lt 0.000001) `
        'wall frame barrier changed its 50ms bounded retry interval'
    $script:MockFrameAdvanceDelayCalls++
}
$script:SourceObservationCount = 0
$script:SourceObservationForbidden = $false
$wallResult = Invoke-Wall3x3Gate
Assert-True ($wallResult.source_observations -eq 1 -and
    -not $wallResult.source_reobserved) `
    'wall result did not preserve the one-source-observation contract'
Assert-True ($wallResult.row_phase_count -eq 3 -and
    @($wallResult.row_actions).Count -eq 3) `
    'wall result did not record three row phases'
Assert-True ($wallResult.wall_placement_action_count -eq 5) `
    'wall result did not distinguish five placement Actions from three rows'
Assert-True ($wallResult.maximum_entries_per_action -eq 3) `
    '3x3 result lost its maximum three-entry batch fact'
Assert-True (@($wallResult.row_actions | Where-Object {
            $_.entry_count -ne 3 -or -not $_.stationary -or
            @($_.terminal_states | Where-Object { $_ -cne 'succeeded' }).Count -ne 0
        }).Count -eq 0) `
    'wall result contains a non-terminal, moving, or non-three-cell row'
Assert-True ($wallResult.row_actions[0].action_count -eq 1 -and
    $wallResult.row_actions[1].action_count -eq 1 -and
    $wallResult.row_actions[2].action_count -eq 3 -and
    $wallResult.row_actions[2].maximum_entries_per_action -eq 1 -and
    $wallResult.row_actions[2].order -ceq 'far_to_near') `
    'wall result did not retain the safe top-row split contract'
Assert-True ((@($wallResult.row_actions[0].targets | ForEach-Object {
                Get-BlockPositionKey $_
            }) -join ',') -ceq
        'minecraft:overworld|-18|56|11,minecraft:overworld|-20|56|11,minecraft:overworld|-19|56|11' -and
    (@($wallResult.row_actions[1].targets | ForEach-Object {
                Get-BlockPositionKey $_
            }) -join ',') -ceq
        'minecraft:overworld|-18|57|11,minecraft:overworld|-20|57|11,minecraft:overworld|-19|57|11') `
    '3x3 lower rows did not retain fresh-pose far-to-near order'
$wallHeadingEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_row_heading_admitted'
    })
Assert-True ($wallHeadingEvents.Count -eq 2 -and
    @($wallHeadingEvents | Where-Object {
            $first = $wallResult.row_actions[[int]$_.row].targets[0]
            $pivot = $wallResult.row_actions[[int]$_.row].targets[2]
            $_.proof -cne 'post_face_frame_exact_ordered_row' -or
            $_.heading_strategy -cne 'center_pivot_batch' -or
            [int]$_.face_target.x -ne [int]$pivot.x -or
            [int]$_.face_target.y + 1 -ne [int]$pivot.y -or
            [int]$_.face_target.z -ne [int]$pivot.z -or
            [int]$_.first_execution_support.x -ne [int]$first.x -or
            [int]$_.first_execution_support.y + 1 -ne [int]$first.y -or
            [int]$_.first_execution_support.z -ne [int]$first.z
        }).Count -eq 0) `
    '3x3 row heading did not preserve its center pivot and fresh execution order'
Assert-True ($wallResult.exact_target_count -eq 9 -and
    @($wallResult.exact_targets).Count -eq 9) `
    'wall result did not retain exactly nine target cells'
Assert-True ($wallResult.inventory_before_placement -eq 64 -and
    $wallResult.inventory_after_placement -eq 55 -and
    $wallResult.inventory_delta -eq -9) `
    'wall result inventory ledger is not exactly minus nine'
Assert-True ($wallResult.expected_air_violations -eq 0 -and
    $wallResult.expected_extra_mutations -eq 0 -and
    $wallResult.external_oracle_status -ceq 'pending') `
    'wall result confused expected oracle values with completed measurements'
Assert-True ($script:MockWallAction -eq 17 -and $script:MockWallPlaced -eq 9) `
    'mock wall orchestration did not execute five placement Actions plus reorientation and the bounded scaffold lifecycle'
Assert-True ($wallResult.total_action_count -eq 17) `
    'wall result did not aggregate all accepted Actions'
Assert-True ($script:MockWallPillar -eq 1 -and $script:MockWallClear -eq 1 -and
    $script:MockWallCollect -eq 0 -and $script:MockWallWait -eq 1 -and
    $script:MockWallNavigation -eq 2) `
    'mock wall orchestration did not execute one passive-pickup scaffold lifecycle'
Assert-True ($script:MockWallRowFace -eq 6) `
    'mock wall orchestration did not execute row, reorientation, and top-cell face Actions'
Assert-True ($script:MockWallCleanupFace -eq 1) `
    'mock wall orchestration did not face the fresh temporary block before clear'
$semanticBarrierEvents = @($script:GateEvents | Where-Object {
        $_.event -cin @('wall_support_revision_current',
            'visible_surfaces_revision_current', 'temporary_surface_revision_current') -and
        $_.polls -eq 2
    })
Assert-True ($script:MockFrameAdvanceDelayCalls -eq 13 -and
    @($script:GateEvents | Where-Object {
            $_.event -ceq 'observation_frame_advanced' -and $_.polls -eq 2
        }).Count -eq 7 -and $semanticBarrierEvents.Count -eq 6) `
    "3x3 revision barriers mismatch: delays=$($script:MockFrameAdvanceDelayCalls), frame_events=$(@($script:GateEvents | Where-Object { $_.event -ceq 'observation_frame_advanced' -and $_.polls -eq 2 }).Count), semantic_events=$($semanticBarrierEvents.Count)"
Assert-True ($script:MockWallDropProbes -eq 2) `
    'mock wall orchestration did not prove zero pre-existing drops then one cleanup drop'
Assert-True ((@($script:MockPlayerPoseEvents | ForEach-Object { $_.op }) -join ',') -ceq
        'navigate,pillar,navigate' -and
    (@($script:MockPlayerPoseEvents | ForEach-Object { $_.position.y }) -join ',') -ceq
        '56,57,56') `
    '3x3 mock did not retain ascent and safe descent before passive pickup'
Assert-True (($script:MockWallTopTargetKeys -join ',') -ceq
    'minecraft:overworld|-18|58|11,minecraft:overworld|-19|58|11,minecraft:overworld|-20|58|11') `
    'mock wall orchestration did not place the top row far-to-near'
Assert-True ($wallResult.temporary_scaffold.expected_cleanup -and
    $wallResult.temporary_scaffold.expected_drop_collection -ceq 'minecraft:oak_log' -and
    -not $wallResult.temporary_scaffold.included_in_expected_changed_cells) `
    'wall result did not record the required temporary cleanup and drop collection'
Assert-True ([object]::ReferenceEquals(
        $wallResult.temporary_scaffold.position, $temporaryTarget) -and
    [object]::ReferenceEquals(
        $wallResult.temporary_scaffold.descent_target, $descentTarget) -and
    $wallResult.temporary_scaffold.recovery_mode -ceq 'passive_pickup' -and
    $wallResult.temporary_scaffold.inventory_delta -eq 1 -and
    $wallResult.temporary_scaffold.visible_drop_count -eq 0 -and
    $null -eq $wallResult.temporary_scaffold.collected_drop_target -and
    $null -eq $wallResult.temporary_scaffold.collect_action_id) `
    'wall result lost Action-coordinate provenance for the temporary lifecycle'
Assert-True ($wallResult.temporary_scaffold.settle_ticks -eq 40 -and
    $wallResult.temporary_scaffold.settle_action_id -cmatch `
        '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') `
    'wall result did not retain the bounded drop-settle Action'
$passiveRecoveryEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_temporary_drop_recovery_selected'
    })
Assert-True ($passiveRecoveryEvents.Count -eq 1 -and
    $passiveRecoveryEvents[0].recovery_mode -ceq 'passive_pickup' -and
    $passiveRecoveryEvents[0].inventory_delta -eq 1 -and
    $passiveRecoveryEvents[0].visible_drop_count -eq 0 -and
    $null -eq $passiveRecoveryEvents[0].position) `
    '3x3 cleanup did not emit its passive-pickup evidence ledger'

# The 5x5 profile reuses the same orchestration with two delivered temporary
# scaffold levels. Every cell uses a one-entry, freshly observed far-to-near
# Action because the five-wide row cannot share the 40-degree admission heading.
$wall5Player = [pscustomobject]@{ x = -14.6192777; y = 56.0; z = 8.6406432 }
$wall5Foundation = @(-22..-18 | ForEach-Object {
        New-MockSurface -Block 'minecraft:white_wool' -X $_ -Y 55 -Z 11
    })
$wall5Foundation += New-MockSurface -Block 'minecraft:white_wool' `
    -X -20 -Y 55 -Z 13
$wall5Foundation += New-MockSurface -Block 'minecraft:white_wool' `
    -X -20 -Y 55 -Z 14
$wall5Foundation += New-MockSurface -Block 'minecraft:white_wool' `
    -X -20 -Y 55 -Z 15
$wall5Foundation += New-MockSurface -Block 'minecraft:white_wool' `
    -X -21 -Y 55 -Z 15
$wall5TemporaryTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 56; z = 13
}
$wall5StagingTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 56; z = 14
}
$wall5LowTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 56; z = 15
}
$wall5DescentTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -21; y = 56; z = 15
}
$wall5LowTopTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 57; z = 15
}
$wall5MediumTopTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 58; z = 14
}
$wall5TemporaryNavigation = New-MockTraversability -Target $wall5TemporaryTarget
$wall5StagingNavigation = New-MockTraversability `
    -Target $wall5StagingTarget -Status 'CONFIRMED'
$wall5LowNavigation = New-MockTraversability `
    -Target $wall5LowTarget -Status 'CONFIRMED'
$wall5DescentNavigation = New-MockTraversability `
    -Target $wall5DescentTarget -Status 'CONFIRMED'
$wall5LowTopNavigation = New-MockTraversability `
    -Target $wall5LowTopTarget -Status 'CONFIRMED'
$wall5MediumTopNavigation = New-MockTraversability `
    -Target $wall5MediumTopTarget -Status 'CONFIRMED'

# An exact scaffold coordinate can be delivered more than once with different
# safety statuses. Selection prefers CONFIRMED and then compact JSON, preserves
# the original record, and is independent of delivery order.
$duplicateTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -20; y = 57; z = 13
}
$duplicateProbe = [pscustomobject][ordered]@{
    selector_key = 'a-probe'
    kind = 'traversability'; navigation_target = $duplicateTarget
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$duplicateConfirmedFirst = [pscustomobject][ordered]@{
    selector_key = 'b-confirmed'
    kind = 'traversability'; navigation_target = $duplicateTarget
    status = 'CONFIRMED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$duplicateConfirmedLast = [pscustomobject][ordered]@{
    selector_key = 'z-confirmed'
    kind = 'traversability'; navigation_target = $duplicateTarget
    status = 'CONFIRMED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
Assert-True ((ConvertTo-CompactJson $duplicateProbe) -clt
        (ConvertTo-CompactJson $duplicateConfirmedFirst) -and
    (ConvertTo-CompactJson $duplicateConfirmedFirst) -clt
        (ConvertTo-CompactJson $duplicateConfirmedLast)) `
    'duplicate fixture does not isolate status priority and compact JSON tie-breaking'
$duplicateRecords = @(
    $duplicateConfirmedLast, $duplicateProbe, $duplicateConfirmedFirst)
$selectedDuplicate = Select-ExactScaffoldNavigationRecord `
    -Records $duplicateRecords -ExpectedTarget $duplicateTarget
Assert-True ([object]::ReferenceEquals(
        $duplicateConfirmedFirst, $selectedDuplicate)) `
    'exact scaffold selector did not prefer CONFIRMED then compact JSON'
$reversedDuplicateRecords = @($duplicateRecords)
[Array]::Reverse($reversedDuplicateRecords)
$selectedReversedDuplicate = Select-ExactScaffoldNavigationRecord `
    -Records $reversedDuplicateRecords -ExpectedTarget $duplicateTarget
Assert-True ([object]::ReferenceEquals(
        $duplicateConfirmedFirst, $selectedReversedDuplicate)) `
    'exact scaffold selector changed the original preferred record after reversal'
$missingExactRecordThrew = $false
try {
    [void](Select-ExactScaffoldNavigationRecord `
            -Records @($wall5TemporaryNavigation) `
            -ExpectedTarget $duplicateTarget)
} catch {
    $missingExactRecordThrew = $_.Exception.Message -cmatch `
        'no delivered scaffold navigation target matches the requested step'
}
Assert-True $missingExactRecordThrew `
    'exact scaffold selector did not throw when no safe coordinate matched'

# The selector must stay comfortably inside the observation-frame TTL even when
# a dense delivery contains irrelevant traversability records. It also keeps the
# exact preferred records rather than rebuilding coordinate objects.
$wall5NoiseNavigation = @(0..255 | ForEach-Object {
        New-MockTraversability -Target ([pscustomobject]@{
                dimension = 'minecraft:overworld'; x = 100 + $_; y = 56; z = 100
            })
    })
$wall5SelectorTraversability = @(
    $wall5TemporaryNavigation, $wall5StagingNavigation, $wall5LowNavigation,
    $wall5DescentNavigation, $wall5LowTopNavigation, $wall5MediumTopNavigation) +
    $wall5NoiseNavigation
$wall5SelectorRaisedSupports = @($wall5Foundation[0..4] | ForEach-Object {
        Get-TargetAboveSupport (
            Get-TargetAboveSupport (Get-ObjectProperty $_ 'position'))
    })
$wall5SelectorWatch = [Diagnostics.Stopwatch]::StartNew()
$wall5Plan = Select-TemporaryStaircasePlan `
    -WhiteWoolRecords $wall5Foundation `
    -TraversabilityRecords $wall5SelectorTraversability `
    -WallFoundation @($wall5Foundation[0..4]) `
    -RowOneTargets $wall5SelectorRaisedSupports
$wall5SelectorWatch.Stop()
Assert-True ($wall5SelectorWatch.Elapsed.TotalSeconds -lt 5) `
    "linear staircase selection exceeded five seconds: $($wall5SelectorWatch.Elapsed)"
Assert-True ([object]::ReferenceEquals(
        $wall5Foundation[5], $wall5Plan.high.support) -and
    [object]::ReferenceEquals(
        $wall5TemporaryNavigation, $wall5Plan.high.navigation_record) -and
    [object]::ReferenceEquals(
        $wall5TemporaryTarget, $wall5Plan.high.target) -and
    [object]::ReferenceEquals(
        $wall5StagingNavigation, $wall5Plan.medium.navigation_record) -and
    [object]::ReferenceEquals(
        $wall5LowNavigation, $wall5Plan.low.navigation_record) -and
    [object]::ReferenceEquals(
        $wall5DescentNavigation, $wall5Plan.ground_record)) `
    'linear staircase selector replaced one or more policy-delivered records'
$wall5ReversedFoundation = @($wall5Foundation)
$wall5ReversedTraversability = @($wall5SelectorTraversability)
[Array]::Reverse($wall5ReversedFoundation)
[Array]::Reverse($wall5ReversedTraversability)
$wall5ReversedPlan = Select-TemporaryStaircasePlan `
    -WhiteWoolRecords $wall5ReversedFoundation `
    -TraversabilityRecords $wall5ReversedTraversability `
    -WallFoundation @($wall5Foundation[0..4]) `
    -RowOneTargets $wall5SelectorRaisedSupports
Assert-True ($wall5ReversedPlan.key -ceq $wall5Plan.key) `
    'linear staircase selector changed its result under stable delivered inputs'

$script:MockWallPlaced = 0
$script:MockWallInventory = 64
$script:MockWallAction = 0
$script:MockWallRowFace = 0
$script:MockWallCleanupFace = 0
$script:MockWallNavigation = 0
$script:MockWallPillar = 0
$script:MockWallClear = 0
$script:MockWallCollect = 0
$script:MockWallWait = 0
$script:MockTemporaryCleared = $false
$script:MockWallPlacedTargets = @()
$script:MockWallTopTargetKeys = [Collections.Generic.List[string]]::new()
$script:MockWallPlacementRequests = [Collections.Generic.List[object]]::new()
$script:MockTemporaryClearKeys = [Collections.Generic.List[string]]::new()
$script:MockWallDropProbes = 0
$script:MockPassivePickup = $false
$script:MockRejectNextCollectStart = $true
$script:MockPendingPassiveRecoveryPickup = $false
$script:MockFrameCounter = 0
$script:MockLastFaceFrame = -1
$script:MockLastPlacementFrame = -1
$script:MockPendingStaleStateCalls = 0
$script:MockWorldRevision = 1L
$script:MockPendingStaleSurfaceDeliveries = 0
$script:MockFrameAdvanceDelayCalls = 0
$script:MockSurfaceDeliveryFrame = @{}
$script:MockWallPlayer = $wall5Player
$script:MockWallFoundation = @($wall5Foundation)
$script:MockWallTraversability = @(
    $wall5TemporaryNavigation, $wall5StagingNavigation, $wall5LowNavigation,
    $wall5DescentNavigation, $wall5LowTopNavigation, $wall5MediumTopNavigation)
$script:MockPillarNavigationTarget = $wall5StagingTarget
$script:MockPillarNavigationTargetKeys = @(
    (Get-BlockPositionKey $wall5TemporaryTarget),
    (Get-BlockPositionKey $wall5StagingTarget),
    (Get-BlockPositionKey $wall5LowTarget))
$script:MockRequireAdjacentDescent = $true
$script:MockTemporarySurfaces = @{}
$script:MockTemporaryPositions = @()
$script:MockCurrentDropRecord = $null
$script:MockPlayerPoseEvents = [Collections.Generic.List[object]]::new()
$script:MockNavigationEdges = [Collections.Generic.List[object]]::new()
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:SourceObservationCount = 0
$script:SourceObservationForbidden = $false

$wall5Result = Invoke-Wall5x5Gate
Assert-True ($wall5Result.gate -ceq 'wall-5x5' -and
    $wall5Result.wall_dimensions.width -eq 5 -and
    $wall5Result.wall_dimensions.height -eq 5) `
    '5x5 result did not retain its exact audited profile'
Assert-True ($wall5Result.source_observations -eq 1 -and
    -not $wall5Result.source_reobserved) `
    '5x5 result did not preserve the one-source-observation contract'
Assert-True ($wall5Result.row_phase_count -eq 5 -and
    @($wall5Result.row_actions).Count -eq 5 -and
    $wall5Result.wall_placement_action_count -eq 25) `
    '5x5 result did not record twenty-five single-cell placement Actions'
Assert-True ($wall5Result.maximum_entries_per_action -eq 1) `
    '5x5 result did not report its maximum one-entry Action size'
Assert-True (@($wall5Result.row_actions | Where-Object {
            $_.action_count -ne 5 -or $_.entry_count -ne 5 -or
            $_.maximum_entries_per_action -ne 1 -or
            $_.order -cne 'far_to_near' -or -not $_.stationary
        }).Count -eq 0) `
    '5x5 row contracts are not five freshly proved one-entry Actions per row'
Assert-True ((@($wall5Result.row_actions[0].targets | ForEach-Object {
                Get-BlockPositionKey $_
            }) -join ',') -ceq
        'minecraft:overworld|-18|56|11,minecraft:overworld|-22|56|11,minecraft:overworld|-19|56|11,minecraft:overworld|-21|56|11,minecraft:overworld|-20|56|11' -and
    (@($wall5Result.row_actions[1].targets | ForEach-Object {
                Get-BlockPositionKey $_
            }) -join ',') -ceq
        'minecraft:overworld|-18|57|11,minecraft:overworld|-22|57|11,minecraft:overworld|-19|57|11,minecraft:overworld|-21|57|11,minecraft:overworld|-20|57|11') `
    '5x5 lower rows did not retain fresh-pose far-to-near order'
$wall5HeadingEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_row_heading_admitted'
    })
Assert-True ($wall5HeadingEvents.Count -eq 10 -and
    @($wall5HeadingEvents | Where-Object {
            $target = $wall5Result.row_actions[[int]$_.row].targets[[int]$_.entry]
            $_.proof -cne 'post_face_frame_exact_ordered_row' -or
            $_.heading_strategy -cne 'first_entry_singleton' -or
            (Get-BlockPositionKey $_.face_target) -cne
                (Get-BlockPositionKey $_.first_execution_support) -or
            [int]$_.face_target.x -ne [int]$target.x -or
            [int]$_.face_target.y + 1 -ne [int]$target.y -or
            [int]$_.face_target.z -ne [int]$target.z
        }).Count -eq 0) `
    '5x5 row heading did not face the first freshly reproved execution support'
$wall5ReorientationEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_elevated_row_reoriented'
    })
Assert-True ($wall5ReorientationEvents.Count -eq 3 -and
    (@($wall5ReorientationEvents | ForEach-Object { $_.row }) -join ',') -ceq '2,3,4' -and
    @($wall5ReorientationEvents | Where-Object {
            $_.proof -cne 'current_exact_surface_before_up_surface_scan' -or
            $_.face -cnotin @('up', 'north', 'south', 'east', 'west')
        }).Count -eq 0) `
    '5x5 elevated rows did not retain a current exact-surface reorientation proof'
Assert-True (@($script:MockWallPlacementRequests).Count -eq 25) `
    '5x5 mock did not retain every placement request'
Assert-True (@($script:MockWallPlacementRequests | Where-Object {
            @($_.program.body[0].entries).Count -ne 1 -or
            $_.budget.max_duration_ms -ne 15000 -or
            $_.budget.max_ticks -ne 300 -or
            $_.budget.max_distance_blocks -ne 0 -or
            $_.budget.max_camera_degrees -ne 80 -or
            $_.budget.max_blocks_placed -ne 1
        }).Count -eq 0) `
    '5x5 request budget is not the exact one-entry fixed cost'
Assert-True ($wall5Result.exact_target_count -eq 25 -and
    @($wall5Result.exact_targets).Count -eq 25 -and
    @($wall5Result.exact_targets | ForEach-Object {
            Get-BlockPositionKey $_
        } | Select-Object -Unique).Count -eq 25) `
    '5x5 result did not retain exactly 25 unique permanent targets'
Assert-True ($wall5Result.inventory_before_placement -eq 64 -and
    $wall5Result.inventory_after_placement -eq 39 -and
    $wall5Result.inventory_delta -eq -25) `
    '5x5 material ledger did not recover every scaffold before proving net minus 25'
Assert-True ($script:MockWallAction -eq 99 -and
    $wall5Result.total_action_count -eq 99 -and
    $script:MockWallPlaced -eq 25) `
    "5x5 action lifecycle mismatch: actions=$($script:MockWallAction), result=$($wall5Result.total_action_count), placed=$($script:MockWallPlaced)"
Assert-True ($script:MockWallPillar -eq 6 -and $script:MockWallClear -eq 6 -and
    $script:MockWallCollect -eq 5 -and $script:MockWallWait -eq 6 -and
    $script:MockWallNavigation -eq 17) `
    '5x5 orchestration did not execute six complete temporary scaffold lifecycles'
Assert-True ($script:MockWallRowFace -eq 28 -and
    $script:MockWallCleanupFace -eq 6 -and $script:MockWallDropProbes -eq 14) `
    "5x5 orchestration lost fresh evidence: row_face=$($script:MockWallRowFace), cleanup_face=$($script:MockWallCleanupFace), drop_probes=$($script:MockWallDropProbes)"
Assert-True (@($script:GateEvents | Where-Object {
            $_.event -ceq 'wall_temporary_drop_collect_admission_deferred' -and
            $_.code -ceq 'TARGET_UNKNOWN' -and $_.old_drop_reuse_allowed -eq $false
        }).Count -eq 1 -and
    @($script:GateEvents | Where-Object {
            $_.event -ceq 'wall_temporary_drop_passive_approach_selected' -and
            $_.reason -ceq 'collect_target_unknown' -and
            $_.target_from_current_policy_delivery
        }).Count -eq 1) `
    '5x5 orchestration did not recover one rejected collect through a fresh bounded approach'
$semanticBarrierEvents = @($script:GateEvents | Where-Object {
        $_.event -cin @('wall_support_revision_current',
            'visible_surfaces_revision_current', 'temporary_surface_revision_current') -and
        $_.polls -eq 2
    })
Assert-True ($script:MockFrameAdvanceDelayCalls -eq 70 -and
    @($script:GateEvents | Where-Object {
            $_.event -ceq 'observation_frame_advanced' -and $_.polls -eq 2
        }).Count -eq 37 -and $semanticBarrierEvents.Count -eq 33) `
    "5x5 revision barriers mismatch: delays=$($script:MockFrameAdvanceDelayCalls), frame_events=$(@($script:GateEvents | Where-Object { $_.event -ceq 'observation_frame_advanced' -and $_.polls -eq 2 }).Count), semantic_events=$($semanticBarrierEvents.Count)"
$downwardEdges = @($script:MockNavigationEdges | Where-Object {
        [int]$_.target.y -lt [int]$_.from.y
    })
Assert-True ($downwardEdges.Count -eq 8 -and
    @($downwardEdges | Where-Object {
            -not $_.safe_descent_proved
        }).Count -eq 0) `
    "5x5 downward edges mismatch: count=$($downwardEdges.Count)"
Assert-True ($wall5Result.descent_action_count -eq 3 -and
    @($wall5Result.descent_route).Count -eq 3 -and
    (@($wall5Result.descent_route | ForEach-Object { $_.step }) -join ',') -ceq
        'high_top_to_medium_top,medium_top_to_low_top,low_top_to_ground' -and
    @($wall5Result.descent_route | Where-Object {
            $_.horizontal_manhattan -ne 1 -or $_.absolute_y_delta -ne 1 -or
            -not $_.target_from_policy_delivery
        }).Count -eq 0) `
    '5x5 result did not retain the delivered high-medium-low-ground descent proof'
$acceptedActions = @($script:GateEvents | Where-Object { $_.event -ceq 'action_accepted' })
$terminalActions = @($script:GateEvents | Where-Object { $_.event -ceq 'action_terminal' })
Assert-True ($acceptedActions.Count -eq 99 -and $terminalActions.Count -eq 99 -and
    @($terminalActions | Where-Object { $_.state -cne 'succeeded' }).Count -eq 0 -and
    (@($acceptedActions | ForEach-Object { $_.action_id }) -join ',') -ceq
        (@($terminalActions | ForEach-Object { $_.action_id }) -join ',')) `
    '5x5 did not drive every accepted Action to its matching terminal success'
$stagingEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_staging_navigation_selected'
    })
Assert-True ($stagingEvents.Count -eq 1 -and
    [object]::ReferenceEquals($stagingEvents[0].target, $wall5TemporaryTarget) -and
    [double]$stagingEvents[0].maximum_support_distance_with_tolerance -le 4.5 -and
    [double]$stagingEvents[0].navigation_tolerance -eq
        $script:ConstructionNavigationTolerance) `
    '5x5 staging did not retain the central delivered target and tolerance-aware reach proof'
Assert-True ($wall5Result.temporary_scaffold_count -eq 6 -and
    @($wall5Result.temporary_scaffolds).Count -eq 6 -and
    $wall5Result.temporary_shape -ceq '3-2-1 staircase' -and
    $wall5Result.temporary_column_count -eq 3 -and
    (@($wall5Result.temporary_columns | ForEach-Object { $_.role }) -join ',') -ceq
        'low,medium,high' -and
    (@($wall5Result.temporary_columns | ForEach-Object { $_.height }) -join ',') -ceq
        '1,2,3' -and
    @($wall5Result.temporary_scaffolds | ForEach-Object {
            Get-BlockPositionKey $_.position
        } | Select-Object -Unique).Count -eq 6) `
    '5x5 result did not retain the exact policy-selected 3-2-1 staircase'
$cleanupByOrder = @($wall5Result.temporary_scaffolds | Sort-Object {
        [int](Get-ObjectProperty $_ 'cleanup_order')
    })
Assert-True ((@($cleanupByOrder | ForEach-Object { $_.cleanup_order }) -join ',') -ceq
        '1,2,3,4,5,6' -and
    (@($cleanupByOrder | ForEach-Object { Get-BlockPositionKey $_.position }) -join ',') -ceq
        ($script:MockTemporaryClearKeys -join ',') -and
    (@($cleanupByOrder | ForEach-Object { $_.column_role }) -join ',') -ceq
        'high,medium,high,low,medium,high' -and
    (@($cleanupByOrder | ForEach-Object { $_.position.y }) -join ',') -ceq
        '58,57,57,56,56,56') `
    "5x5 cleanup mismatch: order=$(@($cleanupByOrder | ForEach-Object { $_.cleanup_order }) -join ','), y=$(@($cleanupByOrder | ForEach-Object { $_.position.y }) -join ','), result=$(@($cleanupByOrder | ForEach-Object { Get-BlockPositionKey $_.position }) -join ','), actual=$($script:MockTemporaryClearKeys -join ',')"
$firstElevatedCellEvent = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_elevated_cell_terminal'
    } | Select-Object -First 1)
$thirdPillarEvent = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_temporary_pillar_terminal' -and $_.level -eq 3
    })
Assert-True ($firstElevatedCellEvent.Count -eq 1 -and $thirdPillarEvent.Count -eq 1 -and
    $script:GateEvents.IndexOf($thirdPillarEvent[0]) -lt
        $script:GateEvents.IndexOf($firstElevatedCellEvent[0])) `
    '5x5 did not finish its three-level scaffold before the first elevated wall mutation'
for ($row = 2; $row -lt 5; $row++) {
    $observer = $wall5Result.temporary_columns[2].top_position
    $previousDistanceSquared = [double]::PositiveInfinity
    foreach ($targetPosition in @($wall5Result.row_actions[$row].targets)) {
        $dx = ([double]$targetPosition.x + 0.5) - ([double]$observer.x + 0.5)
        $dz = ([double]$targetPosition.z + 0.5) - ([double]$observer.z + 0.5)
        $distanceSquared = $dx * $dx + $dz * $dz
        Assert-True ($distanceSquared -le $previousDistanceSquared) `
            "5x5 row $row was not emitted far-to-near"
        $previousDistanceSquared = $distanceSquared
    }
}
Assert-True (@($wall5Result.temporary_scaffolds | Where-Object {
            -not $_.expected_cleanup -or
            $_.expected_drop_collection -cne 'minecraft:oak_log' -or
            $_.included_in_expected_changed_cells -or
            $_.settle_ticks -ne 40
        }).Count -eq 0) `
    '5x5 result did not prove top-down cleanup and drop recovery for all scaffolds'
$passiveScaffolds = @($wall5Result.temporary_scaffolds | Where-Object {
        $_.recovery_mode -ceq 'passive_pickup'
    })
$activeScaffolds = @($wall5Result.temporary_scaffolds | Where-Object {
        $_.recovery_mode -ceq 'active_collect'
    })
Assert-True ($passiveScaffolds.Count -eq 1 -and
    $passiveScaffolds[0].inventory_delta -eq 1 -and
    $passiveScaffolds[0].visible_drop_count -eq 0 -and
    $null -eq $passiveScaffolds[0].collected_drop_target -and
    $null -eq $passiveScaffolds[0].collect_action_id -and
    $passiveScaffolds[0].collect_admission_deferrals -eq 1 -and
    $passiveScaffolds[0].recovery_approach_action_count -eq 1 -and
    $activeScaffolds.Count -eq 5 -and
    @($activeScaffolds | Where-Object {
            $_.inventory_delta -ne 0 -or $_.visible_drop_count -ne 1 -or
            $null -eq $_.collected_drop_target -or $null -eq $_.collect_action_id -or
            $_.collect_admission_deferrals -ne 0
        }).Count -eq 0) `
    '5x5 result did not distinguish deferred passive recovery from five admitted collects'
$activeRecoveryEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_temporary_drop_recovery_selected'
    })
Assert-True ($activeRecoveryEvents.Count -eq 6 -and
    @($activeRecoveryEvents | Where-Object {
            $_.recovery_mode -ceq 'passive_pickup' -and $_.inventory_delta -eq 1 -and
            $_.visible_drop_count -eq 0 -and $null -eq $_.position
        }).Count -eq 1 -and
    @($activeRecoveryEvents | Where-Object {
            $_.recovery_mode -ceq 'active_collect' -and $_.inventory_delta -eq 0 -and
            $_.visible_drop_count -eq 1 -and $null -ne $_.position
        }).Count -eq 5) `
    '5x5 cleanup did not emit one deferred-passive and five active recovery ledgers'
$pickupDeliveryEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_drop_pickup_traversability_current'
    })
Assert-True ($pickupDeliveryEvents.Count -eq 7 -and
    @($pickupDeliveryEvents | Where-Object {
            $_.world_revision -lt 1 -or $_.record_count -lt 1 -or
            -not $_.records_delivered_for_product_planner_selection -or
            $_.pickup_cell_selected -or $null -eq $_.query_bounds -or
            $null -eq $_.drop_position
        }).Count -eq 0) `
    '5x5 pickup traversability delivery overstated selection or was not current evidence'
$admissionDeferralEvents = @($script:GateEvents | Where-Object {
        $_.event -ceq 'wall_temporary_drop_collect_admission_deferred'
    })
Assert-True ($admissionDeferralEvents.Count -eq 1 -and
    $admissionDeferralEvents[0].fresh_observation_required -and
    -not $admissionDeferralEvents[0].old_drop_reuse_allowed -and
    $admissionDeferralEvents[0].fresh_frame_id -cne
        $admissionDeferralEvents[0].rejected_frame_id) `
    'collect admission recovery did not prove a post-rejection observation frame'
Assert-True ($wall5Result.external_oracle.expected_changed_cell_count -eq 25 -and
    @($wall5Result.external_oracle.expected_changed_cells).Count -eq 25 -and
    $wall5Result.external_oracle.temporary_scaffold_count -eq 6 -and
    @($wall5Result.external_oracle.temporary_scaffolds).Count -eq 6 -and
    $wall5Result.external_oracle.reject_unlisted_changes -and
    $wall5Result.external_oracle.expected_air_violations -eq 0 -and
    $wall5Result.external_oracle.expected_extra_mutations -eq 0) `
    '5x5 external oracle does not require only 25 permanent cells and six cleared scaffolds'

$savedMockPlayer = $script:MockWallPlayer
$occupiedFoundation = $wall5Foundation[0].position
$script:MockWallPlayer = [pscustomobject]@{
    x = [double]$occupiedFoundation.x + 0.5
    y = [double]$occupiedFoundation.y + 1.0
    z = [double]$occupiedFoundation.z + 0.5
}
$feetFilteredState = Get-FreshState
$feetFilteredFoundations = @(Get-VisibleSurfaceRecords -State $feetFilteredState `
    -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
    -Faces @('up') -ExcludePlayerFeetAbove)
Assert-True (@($feetFilteredFoundations | Where-Object {
            (Get-BlockPositionKey $_.position) -ceq
                (Get-BlockPositionKey $occupiedFoundation)
        }).Count -eq 0) `
    'mock visible-surface delivery did not exclude the support below player feet'
$script:MockWallPlayer = $savedMockPlayer

# A movement-only route-replan budget terminal is retried only as a bounded new
# Action slice. The retry must use a new state and an exact-coordinate record
# delivered from that state; neither the old record nor a synthesized target is
# eligible for the second request.
function New-MockRetryableNavigationFailure {
    param([Parameter(Mandatory)][string]$ActionId)
    [pscustomobject]@{
        schema_version = 1; action_id = $ActionId; state = 'failed'
        progress = [pscustomobject]@{
            interactions = 0; blocks_broken = 0; blocks_placed = 0
        }
        failure = [pscustomobject]@{
            code = 'BUDGET_EXCEEDED'; recoverable = $false
            evidence = @('replanned_route_shape_exceeds_occurrence')
        }
        trace = @([pscustomobject]@{
                tick = 10; event = 'REPLANNING'; detail = 'route_edge_changed'
            })
    }
}
function Get-FreshState {
    $state = $script:ResliceStates[$script:ResliceStateCalls]
    $script:ResliceStateCalls++
    return $state
}
function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    Assert-True ([object]::ReferenceEquals(
            $State, $script:ResliceRecordStates[$script:ResliceRecordCalls])) `
        'scaffold reslice queried records from a state other than its fresh state'
    $record = $script:ResliceRecords[$script:ResliceRecordCalls]
    $script:ResliceRecordCalls++
    return @($record)
}
function Invoke-ActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Request,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180,
        [switch]$ReturnFailure
    )
    Assert-True $ReturnFailure `
        'temporary scaffold navigation did not request its terminal failure snapshot'
    $script:ResliceRequests.Add($Request)
    $terminal = $script:ResliceTerminals[$script:ResliceActionCalls]
    $script:ResliceActionCalls++
    return $terminal
}

$resliceOldTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -18; y = 56; z = 10
}
$resliceFreshTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -18; y = 56; z = 10
}
$resliceOldRecord = New-MockTraversability -Target $resliceOldTarget `
    -Status 'PROBE_ALLOWED'
$resliceFreshRecord = New-MockTraversability -Target $resliceFreshTarget `
    -Status 'CONFIRMED'
$resliceInitialState = New-MockState
$resliceInitialState.observation.latest_frame_id = 'obs-0000000000000401'
$resliceFreshState = New-MockState
$resliceFreshState.observation.latest_frame_id = 'obs-0000000000000402'
$resliceFinalState = New-MockState
$resliceFinalState.observation.latest_frame_id = 'obs-0000000000000403'
$script:ResliceStates = @($resliceFreshState, $resliceFinalState)
$script:ResliceRecords = @($resliceFreshRecord)
$script:ResliceRecordStates = @($resliceFreshState)
$script:ResliceTerminals = @(
    (New-MockRetryableNavigationFailure `
        -ActionId '550e8400-e29b-41d4-a716-446655440101'),
    (New-MockActionSnapshot `
        -ActionId '550e8400-e29b-41d4-a716-446655440102' -State 'succeeded'))
$script:ResliceStateCalls = 0
$script:ResliceRecordCalls = 0
$script:ResliceActionCalls = 0
$script:ResliceRequests = [Collections.Generic.List[object]]::new()
$script:GateEvents = [Collections.Generic.List[object]]::new()
$resliceResult = Invoke-TemporaryScaffoldNavigation `
    -State $resliceInitialState -NavigationRecord $resliceOldRecord `
    -Tolerance $script:PillarNavigationTolerance -Step 'reslice_success' `
    -Event 'mock_scaffold_navigation_terminal' -MaximumSlices 3
Assert-True ($resliceResult.slices -eq 2 -and
    [object]::ReferenceEquals($resliceFinalState, $resliceResult.state)) `
    'scaffold navigation did not succeed on its bounded second Action slice'
Assert-True ($script:ResliceActionCalls -eq 2 -and
    $script:ResliceStateCalls -eq 2 -and $script:ResliceRecordCalls -eq 1) `
    'scaffold navigation did not use one fresh state and delivery before its retry'
Assert-True ([object]::ReferenceEquals(
        $script:ResliceRequests[0].program.body[0].target, $resliceOldTarget) -and
    [object]::ReferenceEquals(
        $script:ResliceRequests[1].program.body[0].target, $resliceFreshTarget) -and
    -not [object]::ReferenceEquals(
        $script:ResliceRequests[1].program.body[0].target, $resliceOldTarget)) `
    'scaffold reslice reused the old record target or synthesized a replacement'
$resliceFailures = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_scaffold_navigation_slice_failed'
    })
$resliceSelections = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_scaffold_navigation_reslice_selected'
    })
$resliceSuccess = @($script:GateEvents | Where-Object {
        $_.event -ceq 'mock_scaffold_navigation_terminal'
    })
Assert-True ($resliceFailures.Count -eq 1 -and
    $resliceFailures[0].failed_action_id -ceq
        '550e8400-e29b-41d4-a716-446655440101' -and
    $resliceFailures[0].new_action_id -and
    $resliceFailures[0].reslice_allowed -and
    $resliceFailures[0].slices_remaining -eq 2 -and
    -not $resliceFailures[0].old_record_reuse_allowed -and
    -not $resliceFailures[0].synthetic_target_allowed) `
    'scaffold navigation did not retain its first retryable failure event'
Assert-True ($resliceSelections.Count -eq 1 -and
    $resliceSelections[0].previous_action_id -ceq
        '550e8400-e29b-41d4-a716-446655440101' -and
    [object]::ReferenceEquals($resliceSelections[0].target, $resliceFreshTarget) -and
    $resliceSelections[0].target_from_fresh_delivery) `
    'scaffold navigation did not record its new delivery-backed retry selection'
Assert-True ($resliceSuccess.Count -eq 1 -and $resliceSuccess[0].slice -eq 2 -and
    $resliceSuccess[0].maximum_slices -eq 3 -and $resliceSuccess[0].resliced -and
    @($resliceSuccess[0].action_ids).Count -eq 2) `
    'scaffold navigation did not prove distinct bounded Action slices on success'

# Repeated qualifying failures must stop at the explicit bound while recording
# every failed Action. The final failure cannot trigger another observation.
$resliceExhaustedFreshTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -18; y = 56; z = 10
}
$resliceExhaustedFreshRecord = New-MockTraversability `
    -Target $resliceExhaustedFreshTarget -Status 'CONFIRMED'
$resliceExhaustedFreshState = New-MockState
$resliceExhaustedFreshState.observation.latest_frame_id = 'obs-0000000000000502'
$script:ResliceStates = @($resliceExhaustedFreshState)
$script:ResliceRecords = @($resliceExhaustedFreshRecord)
$script:ResliceRecordStates = @($resliceExhaustedFreshState)
$script:ResliceTerminals = @(
    (New-MockRetryableNavigationFailure `
        -ActionId '550e8400-e29b-41d4-a716-446655440201'),
    (New-MockRetryableNavigationFailure `
        -ActionId '550e8400-e29b-41d4-a716-446655440202'))
$script:ResliceStateCalls = 0
$script:ResliceRecordCalls = 0
$script:ResliceActionCalls = 0
$script:ResliceRequests = [Collections.Generic.List[object]]::new()
$script:GateEvents = [Collections.Generic.List[object]]::new()
$resliceExhausted = $false
try {
    [void](Invoke-TemporaryScaffoldNavigation `
            -State $resliceInitialState -NavigationRecord $resliceOldRecord `
            -Tolerance $script:PillarNavigationTolerance -Step 'reslice_exhausted' `
            -Event 'mock_scaffold_navigation_terminal' -MaximumSlices 2)
} catch {
    $resliceExhausted = $_.Exception.Message -ceq
        'temporary scaffold navigation exhausted its bounded 2 Action slices'
}
$exhaustedFailures = @($script:GateEvents | Where-Object {
        $_.event -ceq 'temporary_scaffold_navigation_slice_failed'
    })
Assert-True $resliceExhausted `
    'scaffold navigation did not fail closed at its explicit Action-slice bound'
Assert-True ($script:ResliceActionCalls -eq 2 -and
    $script:ResliceStateCalls -eq 1 -and $script:ResliceRecordCalls -eq 1 -and
    $exhaustedFailures.Count -eq 2 -and
    $exhaustedFailures[0].failed_action_id -cne
        $exhaustedFailures[1].failed_action_id -and
    $exhaustedFailures[1].slices_remaining -eq 0) `
    'scaffold navigation exceeded its bound or lost one of its failure events'

$script:ToolTransport = $null
$script:DelayTransport = $null
$script:ActiveActionId = $null
'MCMCP construction capability gate mock tests passed.'
