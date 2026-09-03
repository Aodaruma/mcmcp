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
        [AllowNull()][string]$PlacementStateRef = $null
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
    }
}

function New-MockTraversability {
    param(
        [Parameter(Mandatory)][object]$Target,
        [string]$Status = 'PROBE_ALLOWED'
    )
    [pscustomobject]@{
        kind = 'traversability'
        navigation_target = $Target
        status = $Status
        target_support = 'confirmed'
        transition_clearance = 'confirmed'
        fluid = 'none'
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
Assert-True ([object]::ReferenceEquals($nearRecord, $selectedToward)) `
    'bounded surface approach did not choose the nearest progress-making record'
$towardRequest = New-NavigationActionRequest -NavigationRecord $selectedToward `
    -State (New-MockState)
Assert-True ([object]::ReferenceEquals(
        $nearTarget, $towardRequest.program.body[0].target)) `
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
    -TemporaryPosition $temporaryTarget
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
$script:MockWallDropProbes = 0
$script:MockFrameCounter = 0
$script:MockLastFaceFrame = -1
$script:GateEvents = [Collections.Generic.List[object]]::new()
function Acquire-OakLogFromChest { return 64L }
function Move-NearDestinationSupport {}
function Get-FreshState {
    $script:MockFrameCounter++
    $state = New-MockState
    $state.observation.latest_frame_id = 'obs-{0:x16}' -f $script:MockFrameCounter
    $state.world.position = $wallPlayer
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
    if ($Block -ceq 'minecraft:oak_log' -and $script:MockLastFaceFrame -ge 0) {
        Assert-True ($script:MockFrameCounter -gt $script:MockLastFaceFrame) `
            'wall support was not refreshed after its face Action'
    }
    if ($Block -ceq 'minecraft:white_wool') { return @($foundation) }
    $exactTemporary = $Bounds.min_x -eq $Bounds.max_x -and
        $Bounds.min_y -eq $Bounds.max_y -and $Bounds.min_z -eq $Bounds.max_z -and
        $Bounds.min_x -eq $temporaryTarget.x -and
        $Bounds.min_y -eq $temporaryTarget.y -and
        $Bounds.min_z -eq $temporaryTarget.z
    if ($exactTemporary -and -not $script:MockTemporaryCleared) {
        return @($freshTemporarySurface)
    }
    return @($script:MockWallPlacedTargets | ForEach-Object {
            New-MockSurface -Block 'minecraft:oak_log' `
                -X $_.x -Y $_.y -Z $_.z -State $oakState
        })
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
        return @($temporaryNavigation, $descentNavigation)
    }
    if ($Kinds.Count -eq 1 -and $Kinds[0] -ceq 'visible_entity' -and
        $script:MockWallCollect -eq 0) {
        $script:MockWallDropProbes++
        if ($script:MockTemporaryCleared) { return @($dropRecord) }
    }
    return @()
}
function Invoke-ActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Request,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180
    )
    $script:MockWallAction++
    $node = $Request.program.body[0]
    $actionId = '550e8400-e29b-41d4-a716-{0:d12}' -f $script:MockWallAction
    Add-GateEvent -Event 'action_accepted' -Detail ([ordered]@{
            action_id = $actionId
            program = [string](Get-ObjectProperty $Request.program 'name')
        })
    switch ($node.op) {
        'apply_known_block_plan' {
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
        }
        'navigate_to_known' {
            $script:MockWallNavigation++
            Assert-True ($node.tolerance -eq 0.1) `
                'temporary navigation did not retain the tight tolerance'
            Assert-True ([object]::ReferenceEquals($node.target, $temporaryTarget) -or
                [object]::ReferenceEquals($node.target, $descentTarget)) `
                'temporary navigation target did not originate in a delivered record'
        }
        'pillar_up_known' {
            $script:MockWallPillar++
            $script:MockWallInventory--
            Assert-True ([object]::ReferenceEquals(
                    $node.support, $temporarySupport.position) -and
                [object]::ReferenceEquals(
                    $node.expected_support, $temporarySupport.state)) `
                'mock pillar did not retain delivered support evidence'
        }
        'clear_known_block_plan' {
            $script:MockWallClear++
            $script:MockTemporaryCleared = $true
            Assert-True ([object]::ReferenceEquals(
                    $node.anchor, $freshTemporarySurface.position)) `
                'mock clear did not retain the fresh temporary position'
        }
        'collect_visible_item' {
            $script:MockWallCollect++
            $script:MockWallInventory++
            Assert-True ([object]::ReferenceEquals($node.target, $dropPosition)) `
                'mock collect did not retain the fresh visible_entity position'
        }
        'wait_ticks' {
            $script:MockWallWait++
            Assert-True ($node.ticks -eq 40 -and
                @($Request.program.capabilities).Count -eq 0) `
                'mock settle Action was not the bounded input-free wait'
        }
        'face_known_position' {
            Assert-True ($null -ne $node.target) `
                'wall face Action did not retain a delivered target'
            if ((Get-BlockPositionKey $node.target) -ceq
                (Get-BlockPositionKey $temporaryTarget)) {
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
    'mock wall orchestration did not execute five placement Actions plus the bounded scaffold lifecycle'
Assert-True ($wallResult.total_action_count -eq 17) `
    'wall result did not aggregate all accepted Actions'
Assert-True ($script:MockWallPillar -eq 1 -and $script:MockWallClear -eq 1 -and
    $script:MockWallCollect -eq 1 -and $script:MockWallWait -eq 1 -and
    $script:MockWallNavigation -eq 2) `
    'mock wall orchestration did not execute one complete temporary scaffold lifecycle'
Assert-True ($script:MockWallRowFace -eq 5) `
    'mock wall orchestration did not execute two row and three top-cell face Actions'
Assert-True ($script:MockWallCleanupFace -eq 1) `
    'mock wall orchestration did not face the fresh temporary block before clear'
Assert-True ($script:MockWallDropProbes -eq 2) `
    'mock wall orchestration did not prove zero pre-existing drops then one cleanup drop'
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
    [object]::ReferenceEquals(
        $wallResult.temporary_scaffold.collected_drop_target, $dropPosition)) `
    'wall result lost Action-coordinate provenance for the temporary lifecycle'
Assert-True ($wallResult.temporary_scaffold.settle_ticks -eq 40 -and
    $wallResult.temporary_scaffold.settle_action_id -cmatch `
        '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') `
    'wall result did not retain the bounded drop-settle Action'

$script:ToolTransport = $null
$script:DelayTransport = $null
$script:ActiveActionId = $null
'MCMCP construction capability gate mock tests passed.'
