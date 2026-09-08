# 仮設足場の再観測・撤去と、落下itemの観測・在庫照合・回収支援。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Get-ExactTemporarySurface {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:oak_log' `
        -Bounds $bounds -Faces $Faces)
    $eligible = @($records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq
                (Get-BlockPositionKey $Position) -and
            (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
            (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                (ConvertTo-CompactJson $ExpectedState)
        })
    if ($eligible.Count -ne 1) {
        throw 'temporary pillar was not freshly delivered with its exact safe state'
    }
    return $eligible[0]
}

function Wait-ForCurrentExactTemporarySurface {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $positionKey = Get-BlockPositionKey $Position
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-VisibleSurfaceRecords -State $state -Block 'minecraft:oak_log' `
            -Bounds $bounds -Faces $Faces -AllowMissing)
        $eligible = @($records | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq $positionKey -and
                (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
                (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                    (ConvertTo-CompactJson $ExpectedState) -and
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        if ($eligible.Count -eq 1) {
            Add-GateEvent -Event 'temporary_surface_revision_current' -Detail ([ordered]@{
                    world_revision = $worldRevision
                    polls = $poll
                    position = $Position
                })
            return [pscustomobject]@{
                state = $state
                surface = $eligible[0]
                world_revision = $worldRevision
                polls = $poll
            }
        }
        if ($eligible.Count -gt 1) {
            throw 'duplicate current-revision temporary pillar surfaces were delivered'
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "temporary pillar surface did not reach the current world revision after $MaximumPolls polls"
}

function New-TemporaryClearActionRequest {
    param([Parameter(Mandatory)][object]$Surface)
    $position = Get-ObjectProperty $Surface 'position'
    $state = Get-ObjectProperty $Surface 'state'
    $node = [ordered]@{
        id = 'clear_temporary_wall_pillar'
        op = 'clear_known_block_plan'
        anchor = $position
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @([ordered]@{
                id = 'temporary_pillar'
                offset = [ordered]@{ x = 0; y = 0; z = 0 }
                expected_before = $state
            })
    }
    return New-PrimitiveRequest -Name 'capability_gate_clear_temporary_wall_pillar' `
        -Capabilities @('camera', 'block_break') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 0 -Camera 80 -Breaks 1
}

function New-TemporaryDropSettleActionRequest {
    $node = [ordered]@{
        id = 'settle_temporary_wall_drop'
        op = 'wait_ticks'
        ticks = 40
    }
    return New-PrimitiveRequest -Name 'capability_gate_settle_temporary_wall_drop' `
        -Capabilities @() -Node $node -Duration 3000 -Ticks 40 `
        -Distance 0 -Camera 0
}

function Get-TemporaryDropRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [AllowNull()][Nullable[int]]$MinimumY = $null
    )
    $temporaryY = [int](Get-ObjectProperty $TemporaryPosition 'y')
    $minimumDropY = if ($null -eq $MinimumY) {
        $temporaryY - 1
    } else {
        [int]$MinimumY
    }
    if ($minimumDropY -gt $temporaryY + 1) {
        throw 'temporary drop swept-volume minimum y exceeds its maximum'
    }
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $TemporaryPosition 'dimension')
        min_x = [int](Get-ObjectProperty $TemporaryPosition 'x') - 2
        min_y = $minimumDropY
        min_z = [int](Get-ObjectProperty $TemporaryPosition 'z') - 2
        max_x = [int](Get-ObjectProperty $TemporaryPosition 'x') + 2
        max_y = $temporaryY + 1
        max_z = [int](Get-ObjectProperty $TemporaryPosition 'z') + 2
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
                entity_types = @('minecraft:item')
                displayed_items = @('minecraft:oak_log')
                position_bounds = $bounds
            }))
    $eligible = @($records | Where-Object {
            $position = Get-ObjectProperty $_ 'position'
            $x = [double](Get-ObjectProperty $position 'x')
            $y = [double](Get-ObjectProperty $position 'y')
            $z = [double](Get-ObjectProperty $position 'z')
            (Get-ObjectProperty $_ 'kind') -ceq 'visible_entity' -and
            (Get-ObjectProperty $_ 'entity_type') -ceq 'minecraft:item' -and
            (Get-ObjectProperty $_ 'displayed_item') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $position 'dimension') -ceq
                (Get-ObjectProperty $TemporaryPosition 'dimension') -and
            -not [double]::IsNaN($x) -and -not [double]::IsInfinity($x) -and
            -not [double]::IsNaN($y) -and -not [double]::IsInfinity($y) -and
            -not [double]::IsNaN($z) -and -not [double]::IsInfinity($z) -and
            [Math]::Floor($x) -ge [double]$bounds.min_x -and
            [Math]::Floor($x) -le [double]$bounds.max_x -and
            [Math]::Floor($y) -ge [double]$bounds.min_y -and
            [Math]::Floor($y) -le [double]$bounds.max_y -and
            [Math]::Floor($z) -ge [double]$bounds.min_z -and
            [Math]::Floor($z) -le [double]$bounds.max_z
        })
    return @($eligible)
}

function Assert-NoTemporaryDropRecord {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [AllowNull()][Nullable[int]]$MinimumY = $null
    )
    $eligible = @(Get-TemporaryDropRecords -State $State `
        -TemporaryPosition $TemporaryPosition -MinimumY $MinimumY)
    if ($eligible.Count -ne 0) {
        throw "temporary pillar cleanup area already contains $($eligible.Count) oak-log drop(s)"
    }
}

function Resolve-TemporaryDropRecovery {
    param(
        [Parameter(Mandatory)][long]$InventoryBeforeClear,
        [Parameter(Mandatory)][long]$InventoryAfterSettle,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$VisibleDrops
    )
    $drops = @($VisibleDrops)
    $inventoryDelta = $InventoryAfterSettle - $InventoryBeforeClear
    if ($inventoryDelta -eq 1L -and $drops.Count -eq 0) {
        return [pscustomobject]@{
            recovery_mode = 'passive_pickup'
            inventory_delta = $inventoryDelta
            visible_drop_count = 0
            drop = $null
        }
    }
    if ($inventoryDelta -eq 0L -and $drops.Count -eq 1) {
        return [pscustomobject]@{
            recovery_mode = 'active_collect'
            inventory_delta = $inventoryDelta
            visible_drop_count = 1
            drop = $drops[0]
        }
    }
    throw ('temporary pillar recovery evidence is inconsistent: ' +
        "inventory_delta=$inventoryDelta, visible_oak_drops=$($drops.Count)")
}

function Wait-ForTemporaryDropRecoveryEvidence {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [Parameter(Mandatory)][long]$InventoryBeforeClear,
        [AllowNull()][Nullable[int]]$MinimumY = $null,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50,
        [switch]$AllowUnavailable
    )
    $state = $InitialState
    $lastObservedFrameId = $null
    $observedFrames = 0
    $pendingEmptyObservations = 0
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $frameId = Get-ObservationFrameId -State $state
        if ($frameId -cne $lastObservedFrameId) {
            $lastObservedFrameId = $frameId
            $observedFrames++
            $inventoryAfterSettle = Get-InventoryCount -State $state `
                -Item 'minecraft:oak_log'
            $visibleDrops = @(Get-TemporaryDropRecords -State $state `
                -TemporaryPosition $TemporaryPosition -MinimumY $MinimumY)
            $inventoryDelta = $inventoryAfterSettle - $InventoryBeforeClear

            # An unchanged inventory with no visible item is not contradictory:
            # the entity observation may trail the block break by one or more
            # frames. Keep the wait read-only and bounded, while every other
            # unsupported combination still fails closed immediately.
            if ($inventoryDelta -eq 0L -and $visibleDrops.Count -eq 0) {
                $pendingEmptyObservations++
            } else {
                $recovery = Resolve-TemporaryDropRecovery `
                    -InventoryBeforeClear $InventoryBeforeClear `
                    -InventoryAfterSettle $inventoryAfterSettle `
                    -VisibleDrops $visibleDrops
                Add-GateEvent -Event 'temporary_drop_recovery_evidence_ready' `
                    -Detail ([ordered]@{
                        frame_id = $frameId
                        polls = $poll
                        observed_frames = $observedFrames
                        pending_empty_observations = $pendingEmptyObservations
                        recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                        inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                        visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
                    })
                return [pscustomobject]@{
                    state = $state
                    recovery = $recovery
                    inventory_after_settle = $inventoryAfterSettle
                    visible_drops = @($visibleDrops)
                    polls = $poll
                    observed_frames = $observedFrames
                    pending_empty_observations = $pendingEmptyObservations
                }
            }
        }

        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    $message = 'temporary pillar recovery evidence remained unavailable after ' +
        "$MaximumPolls bounded poll(s): observed_frames=$observedFrames, " +
        "pending_empty_observations=$pendingEmptyObservations"
    if (-not $AllowUnavailable) { throw $message }
    Add-GateEvent -Event 'temporary_drop_recovery_evidence_unavailable' `
        -Detail ([ordered]@{
            frame_id = $lastObservedFrameId
            polls = $MaximumPolls
            observed_frames = $observedFrames
            pending_empty_observations = $pendingEmptyObservations
            inventory_delta = $inventoryAfterSettle - $InventoryBeforeClear
            visible_drop_count = @($visibleDrops).Count
            bounded_passive_approach_required = $true
        })
    return [pscustomobject]@{
        state = $state
        recovery = $null
        inventory_after_settle = $inventoryAfterSettle
        visible_drops = @($visibleDrops)
        polls = $MaximumPolls
        observed_frames = $observedFrames
        pending_empty_observations = $pendingEmptyObservations
    }
}

function New-TemporaryDropCollectionRequest {
    param(
        [Parameter(Mandatory)][object]$Record,
        [Parameter(Mandatory)][object]$State
    )
    $node = [ordered]@{
        id = 'collect_temporary_wall_pillar'
        op = 'collect_visible_item'
        displayed_item = Get-ObjectProperty $Record 'displayed_item'
        target = Get-ObjectProperty $Record 'position'
    }
    return New-PrimitiveRequest -Name 'capability_gate_collect_temporary_wall_pillar' `
        -Capabilities @('movement') -Node $node -Distance (Get-PolicyDistanceBudget $State) `
        -Camera 0
}

function Get-CurrentSafeWallTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    $worldRevision = Get-CurrentWorldRevision -State $State
    return @(Get-WallScaffoldTraversabilityRecords -State $State | Where-Object {
            $recordRevision = Get-ObjectProperty $_ 'world_revision'
            (Test-SafeTraversabilityRecord $_) -and
            ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
            [long]$recordRevision -eq $worldRevision
        })
}

function Select-TemporaryDropRecoveryApproachRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    $world = Get-ObjectProperty $State 'world'
    $player = Get-ObjectProperty $world 'position'
    $dimension = [string](Get-ObjectProperty $TemporaryPosition 'dimension')
    $goalX = [double](Get-ObjectProperty $TemporaryPosition 'x')
    $goalY = [double](Get-ObjectProperty $TemporaryPosition 'y')
    $goalZ = [double](Get-ObjectProperty $TemporaryPosition 'z')
    $playerX = [double](Get-ObjectProperty $player 'x')
    $playerY = [double](Get-ObjectProperty $player 'y')
    $playerZ = [double](Get-ObjectProperty $player 'z')
    $currentGoalDistance = [Math]::Sqrt(
        [Math]::Pow($playerX - $goalX, 2) +
        [Math]::Pow($playerY - $goalY, 2) +
        [Math]::Pow($playerZ - $goalZ, 2))
    $candidates = foreach ($record in $Records) {
        if (-not (Test-SafeTraversabilityRecord $record)) { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne $dimension) { continue }
        $targetX = [double](Get-ObjectProperty $target 'x')
        $targetY = [double](Get-ObjectProperty $target 'y')
        $targetZ = [double](Get-ObjectProperty $target 'z')
        $travelDistance = [Math]::Sqrt(
            [Math]::Pow($targetX - $playerX, 2) +
            [Math]::Pow($targetY - $playerY, 2) +
            [Math]::Pow($targetZ - $playerZ, 2))
        $goalDistance = [Math]::Sqrt(
            [Math]::Pow($targetX - $goalX, 2) +
            [Math]::Pow($targetY - $goalY, 2) +
            [Math]::Pow($targetZ - $goalZ, 2))
        if ($travelDistance -ge 1 -and $travelDistance -le 8 -and
            $goalDistance -lt $currentGoalDistance) {
            [pscustomobject]@{
                record = $record
                goal_distance = $goalDistance
                progress = $currentGoalDistance - $goalDistance
                travel_distance = $travelDistance
            }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'goal_distance'; Descending = $false },
            @{ Expression = 'progress'; Descending = $true },
            @{ Expression = 'travel_distance'; Descending = $false },
            @{ Expression = {
                    ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target')
                }; Descending = $false } | Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no current safe traversability makes three-dimensional progress toward temporary recovery'
    }
    return $selected[0].record
}

function Get-CurrentTemporaryDropPickupTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Drop
    )
    # visible_entity only proves that the item exists. collect_visible_item also
    # requires an independently delivered, current safe pickup cell and route.
    # Query a bounded area centred on the observed item, because break physics
    # can move it just beyond the construction footprint. The observed position
    # is only a filter centre: every Action target remains an unchanged target
    # from one of the returned policy records.
    $worldRevision = Get-CurrentWorldRevision -State $State
    $position = Get-ObjectProperty $Drop 'position'
    $dimension = [string](Get-ObjectProperty $position 'dimension')
    $dropX = [Math]::Floor([double](Get-ObjectProperty $position 'x'))
    $dropY = [Math]::Floor([double](Get-ObjectProperty $position 'y'))
    $dropZ = [Math]::Floor([double](Get-ObjectProperty $position 'z'))
    $bounds = [ordered]@{
        dimension = $dimension
        min_x = $dropX - 4; min_y = $dropY - 3; min_z = $dropZ - 4
        max_x = $dropX + 4; max_y = $dropY + 3; max_z = $dropZ + 4
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }) | Where-Object {
            $recordRevision = Get-ObjectProperty $_ 'world_revision'
            (Test-SafeTraversabilityRecord $_) -and
            ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
            [long]$recordRevision -eq $worldRevision
        })
    if ($records.Count -lt 1) {
        throw 'no current safe traversability was delivered for temporary drop pickup'
    }
    Add-GateEvent -Event 'temporary_drop_pickup_traversability_current' `
        -Detail ([ordered]@{
            frame_id = Get-ObservationFrameId -State $State
            world_revision = $worldRevision
            record_count = $records.Count
            query_bounds = $bounds
            drop_position = $position
            records_delivered_for_product_planner_selection = $true
            pickup_cell_selected = $false
        })
    return @($records)
}

