# 建築材料の取得、faces-placeとstate-ref-ttl、壁にも使う単一配置。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Acquire-OakLogFromChest {
    $chest = Get-OrNavigateToVisibleSurface -Block 'minecraft:chest' `
        -Bounds $script:ChestBounds -Faces $null
    $state = Get-FreshState
    Invoke-ApproachSurface -Record $chest -State $state
    $state = Get-FreshState
    $chest = Get-VisibleSurface -State $state -Block 'minecraft:chest' `
        -Bounds $script:ChestBounds -Faces $null
    $node = [ordered]@{
        id = 'take_oak_log'; op = 'take_known_container_stack'
        target = Get-ObjectProperty $chest 'position'
        expected_block = 'minecraft:chest'
        item = 'minecraft:oak_log'
        stack_policy = 'default_components_only'
        minimum_inventory_count = 1
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_take_oak_log' `
        -Capabilities @('camera', 'inventory_transfer') -Node $node -Interactions 3
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
    $state = Get-FreshState
    $count = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
    if ($count -lt 1) { throw 'normal chest transfer did not yield an oak log' }
    return $count
}

function Move-NearDestinationSupport {
    param([ValidateSet(3, 5)][int]$Width = 3)
    $support = Get-OrNavigateToVisibleSurface -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') -ExcludePlayerFeetAbove
    $state = Get-FreshState
    Invoke-ApproachSurface -Record $support -State $state
    if ($Width -eq 5) { Invoke-WallStagingNavigation -Width $Width }
}

function Assert-SourceObservationAllowed {
    if ($script:SourceObservationForbidden) {
        throw 'source observation is forbidden after the state-ref retention window starts'
    }
}

function Get-OakLogPlacementSource {
    param([Parameter(Mandatory)][object]$State)
    Assert-SourceObservationAllowed
    $script:SourceObservationCount++
    $filter = [ordered]@{
        block_ids = @('minecraft:oak_log')
        position_bounds = $script:SourceBounds
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') -Filter $filter)
    $eligible = @($records | Where-Object {
            $state = Get-ObjectProperty $_ 'state'
            $properties = Get-ObjectProperty $state 'properties'
            (Get-ObjectProperty $_ 'block') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
            [string](Get-ObjectProperty $_ 'placement_state_ref') -cmatch '^psr_[0-9a-f]{32}$' -and
            (Get-ObjectProperty $state 'block') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $properties 'axis') -ceq 'y'
        } | Sort-Object { ConvertTo-CompactJson (Get-ObjectProperty $_ 'position') })
    if ($eligible.Count -eq 0) {
        throw 'no eligible visible vertical oak-log source was delivered'
    }
    return $eligible[0]
}

function Get-ExactSupportAfterFace {
    param([Parameter(Mandatory)][object]$Position)
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $state = Get-FreshState
    return Get-VisibleSurface -State $state -Block 'minecraft:white_wool' `
        -Bounds $bounds -Faces @('up')
}

function Invoke-FaceSupport {
    param([Parameter(Mandatory)][object]$Support)
    $node = [ordered]@{
        id = 'face_support'; op = 'face_known_position'
        target = Get-ObjectProperty $Support 'position'
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_face_support' `
        -Capabilities @('camera') -Node $node -Distance 0 -Camera 360
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 60)
}

function New-OneOakLogPlacementPhase {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support,
        [switch]$UseStateRef
    )
    $supportPosition = Get-ObjectProperty $Support 'position'
    $target = Get-TargetAboveSupport $supportPosition
    $entry = [ordered]@{
        id = 'single_log'
        offset = [ordered]@{ x = 0; y = 0; z = 0 }
    }
    if ($UseStateRef) {
        $entry.placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
    } else {
        $entry.source_state = Get-ObjectProperty $Source 'state'
        $entry.item = [string](Get-ObjectProperty $Source 'placement_item')
    }
    $entry.support = [ordered]@{
        position = $supportPosition
        face = 'up'
        expected_state = Get-ObjectProperty $Support 'state'
        dependency_entry_id = $null
    }
    if ($null -eq $entry.support.expected_state) {
        throw 'white-wool support did not include a complete state'
    }
    $node = [ordered]@{
        id = 'place_single_log'; op = 'apply_known_block_plan'
        anchor = $target
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @($entry)
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_place_single_log' `
        -Capabilities @('camera', 'block_place') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 0 -Camera 80 -Placements 1
    return [pscustomobject]@{
        request = $request
        target = $target
        entry = $entry
    }
}

function Invoke-OneOakLogPlacement {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support,
        [switch]$UseStateRef
    )
    $phase = New-OneOakLogPlacementPhase -Source $Source -Support $Support `
        -UseStateRef:$UseStateRef
    [void](Invoke-ActionRequest -Request $phase.request -WallTimeoutSeconds 60)
    return $phase.target
}

function Wait-StateRefRetentionWindow {
    param([Parameter(Mandatory)][ValidateRange(61, 600)][int]$Seconds)
    $script:SourceObservationForbidden = $true
    Add-GateEvent -Event 'state_ref_retention_wait_started' -Detail ([ordered]@{
            seconds = $Seconds; source_observation_count = $script:SourceObservationCount
        })
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-GateDelaySeconds -Seconds $Seconds
    $watch.Stop()
    Add-GateEvent -Event 'state_ref_retention_wait_completed' -Detail ([ordered]@{
            requested_seconds = $Seconds
            elapsed_seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 3)
            source_observation_count = $script:SourceObservationCount
        })
}

function Invoke-PlacementGate {
    param([switch]$UseStateRef)
    $inventoryBefore = Acquire-OakLogFromChest
    Move-NearDestinationSupport
    $state = Get-FreshState
    $source = Get-OakLogPlacementSource -State $state
    if ($UseStateRef) {
        Wait-StateRefRetentionWindow -Seconds $StateRefWaitSeconds
        if ($script:SourceObservationCount -ne 1) {
            throw 'state-ref TTL gate observed the source more than once'
        }
        $state = Get-FreshState
    }
    $support = Get-VisibleSurface -State $state -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') `
        -ExcludePlayerFeetAbove
    Invoke-FaceSupport -Support $support
    $support = Get-ExactSupportAfterFace `
        -Position (Get-ObjectProperty $support 'position')
    $target = Invoke-OneOakLogPlacement -Source $source -Support $support `
        -UseStateRef:$UseStateRef
    $final = Get-FreshState
    $inventoryAfter = Get-InventoryCount -State $final -Item 'minecraft:oak_log'
    if ($inventoryAfter -ne $inventoryBefore - 1) {
        throw 'oak-log inventory count did not decrease by exactly one'
    }
    return [ordered]@{
        gate = if ($UseStateRef) { 'state-ref-ttl' } else { 'faces-place' }
        faces_filter_verified = $true
        source_observations = $script:SourceObservationCount
        state_ref_wait_seconds = if ($UseStateRef) { $StateRefWaitSeconds } else { 0 }
        placement_identity = if ($UseStateRef) { 'placement_state_ref' } else { 'inline_state_item' }
        placement_target = $target
        expected_state = Get-ObjectProperty $source 'state'
        inventory_before_placement = $inventoryBefore
        inventory_after_placement = $inventoryAfter
        external_oracle = [ordered]@{
            exact_changed_position = $target
            expected_source_region_changes = 0
            expected_other_destination_changes = 0
        }
    }
}

