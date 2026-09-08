# 壁の基礎・行・照準の選択と再観測、行Action、wall/Gate Cのoracle manifest。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Select-ContiguousWallFoundation {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$PlayerPosition,
        [ValidateRange(1, 8)][int]$Width = 3,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5
    )
    $byPosition = @{}
    foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $record 'face') -cne 'up' -or
            (Get-ObjectProperty (Get-ObjectProperty $record 'state') 'block') -cne
                'minecraft:white_wool') {
            throw 'wall foundation selection received an ineligible surface record'
        }
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if ($byPosition.ContainsKey($key)) {
            throw "wall foundation contains duplicate position $key"
        }
        $byPosition[$key] = $record
    }
    $candidates = [Collections.Generic.List[object]]::new()
    foreach ($record in $Records) {
        $start = Get-ObjectProperty $record 'position'
        foreach ($axis in @('x', 'z')) {
            $row = [Collections.Generic.List[object]]::new()
            $maximumDistanceSquared = 0.0
            for ($offset = 0; $offset -lt $Width; $offset++) {
                $position = [ordered]@{
                    dimension = [string](Get-ObjectProperty $start 'dimension')
                    x = [int](Get-ObjectProperty $start 'x')
                    y = [int](Get-ObjectProperty $start 'y')
                    z = [int](Get-ObjectProperty $start 'z')
                }
                $position[$axis] = [int]$position[$axis] + $offset
                $key = Get-BlockPositionKey $position
                if (-not $byPosition.ContainsKey($key)) {
                    $row.Clear()
                    break
                }
                $surface = $byPosition[$key]
                $row.Add($surface)
                $supportPosition = Get-ObjectProperty $surface 'position'
                $dx = ([double](Get-ObjectProperty $supportPosition 'x') + 0.5) -
                    [double](Get-ObjectProperty $PlayerPosition 'x')
                $dy = ([double](Get-ObjectProperty $supportPosition 'y') + 1.0) -
                    ([double](Get-ObjectProperty $PlayerPosition 'y') + 1.62)
                $dz = ([double](Get-ObjectProperty $supportPosition 'z') + 0.5) -
                    [double](Get-ObjectProperty $PlayerPosition 'z')
                $distanceSquared = $dx * $dx + $dy * $dy + $dz * $dz
                $maximumDistanceSquared = [Math]::Max($maximumDistanceSquared, $distanceSquared)
            }
            if ($row.Count -ne $Width -or
                [Math]::Sqrt($maximumDistanceSquared) -gt $MaximumReach) { continue }
            $centerRecord = $row[[int][Math]::Floor($Width / 2)]
            $center = Get-ObjectProperty $centerRecord 'position'
            $centerDx = ([double](Get-ObjectProperty $center 'x') + 0.5) -
                [double](Get-ObjectProperty $PlayerPosition 'x')
            $centerDz = ([double](Get-ObjectProperty $center 'z') + 0.5) -
                [double](Get-ObjectProperty $PlayerPosition 'z')
            $candidates.Add([pscustomobject]@{
                    axis = $axis
                    maximum_distance_squared = $maximumDistanceSquared
                    center_distance_squared = $centerDx * $centerDx + $centerDz * $centerDz
                    start_key = Get-BlockPositionKey $start
                    supports = @($row)
                })
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_distance_squared'; Descending = $false },
            @{ Expression = 'center_distance_squared'; Descending = $false },
            @{ Expression = 'axis'; Descending = $false },
            @{ Expression = 'start_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw "no policy-visible contiguous $Width-block white-wool UP foundation is within stationary reach"
    }
    return @($selected[0].supports)
}

function Select-WallStagingNavigationSite {
    param(
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -ne 5) {
        throw '5-wide staging requires one exact observed foundation row'
    }
    $wallColumns = @{}
    foreach ($support in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $support 'position'))] = $true
    }
    $center = Get-ObjectProperty `
        $WallFoundation[[int][Math]::Floor($WallFoundation.Count / 2)] 'position'
    $supportY = [int](Get-ObjectProperty `
        (Get-ObjectProperty $WallFoundation[0] 'position') 'y')
    $maximumReachSquared = $MaximumReach * $MaximumReach
    $candidates = foreach ($record in $TraversabilityRecords) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne
                (Get-ObjectProperty $center 'dimension') -or
            [int](Get-ObjectProperty $target 'y') -ne $supportY + 1 -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $target))) { continue }

        $maximumDistanceSquared = 0.0
        $maximumToleranceBoundSquared = 0.0
        foreach ($support in $WallFoundation) {
            $position = Get-ObjectProperty $support 'position'
            $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                ([double](Get-ObjectProperty $target 'x') + 0.5)
            $dy = ([double](Get-ObjectProperty $position 'y') + 1.0) -
                ([double](Get-ObjectProperty $target 'y') + 1.62)
            $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                ([double](Get-ObjectProperty $target 'z') + 0.5)
            $horizontalDistance = [Math]::Sqrt($dx * $dx + $dz * $dz)
            $distanceSquared = $horizontalDistance * $horizontalDistance + $dy * $dy
            $toleranceBoundSquared =
                ($horizontalDistance + $NavigationTolerance) *
                ($horizontalDistance + $NavigationTolerance) + $dy * $dy
            $maximumDistanceSquared = [Math]::Max(
                $maximumDistanceSquared, $distanceSquared)
            $maximumToleranceBoundSquared = [Math]::Max(
                $maximumToleranceBoundSquared, $toleranceBoundSquared)
        }
        if ($maximumToleranceBoundSquared -gt $maximumReachSquared) { continue }
        $centerDx = ([double](Get-ObjectProperty $center 'x') + 0.5) -
            ([double](Get-ObjectProperty $target 'x') + 0.5)
        $centerDz = ([double](Get-ObjectProperty $center 'z') + 0.5) -
            ([double](Get-ObjectProperty $target 'z') + 0.5)
        [pscustomobject]@{
            navigation_record = $record
            maximum_support_distance_squared = $maximumDistanceSquared
            maximum_tolerance_bound_squared = $maximumToleranceBoundSquared
            center_horizontal_distance_squared = $centerDx * $centerDx + $centerDz * $centerDz
            status_rank = if ((Get-ObjectProperty $record 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
            target_key = Get-BlockPositionKey $target
        }
    }
    # Prefer the site with the largest reach margin. The final accepted pose may
    # lie anywhere inside navigation tolerance, so center-distance alone is not
    # a valid construction-reach proof.
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_support_distance_squared'; Descending = $false },
            @{ Expression = 'center_horizontal_distance_squared'; Descending = $false },
            @{ Expression = 'status_rank'; Descending = $false },
            @{ Expression = 'target_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh outside-row traversability target keeps all five supports within reach'
    }
    return $selected[0]
}

function Invoke-WallStagingNavigation {
    param([Parameter(Mandatory)][ValidateSet(5)][int]$Width)
    $state = Get-FreshState
    $foundationRecords = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    $foundation = @(Select-ContiguousWallFoundation -Records $foundationRecords `
        -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
        -Width $Width -MaximumReach 8)
    $traversability = @(Get-WallGroundTraversabilityRecords -State $state)
    $site = Select-WallStagingNavigationSite -WallFoundation $foundation `
        -TraversabilityRecords $traversability `
        -NavigationTolerance $script:ConstructionNavigationTolerance
    $record = $site.navigation_record
    $target = Get-ObjectProperty $record 'navigation_target'
    $request = New-NavigationActionRequest -NavigationRecord $record `
        -State $state -Tolerance $script:ConstructionNavigationTolerance
    Add-GateEvent -Event 'wall_staging_navigation_selected' -Detail ([ordered]@{
            target = $target
            target_verbatim = [object]::ReferenceEquals($target, $request.program.body[0].target)
            foundation_positions = @($foundation | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
                })
            maximum_support_distance = [Math]::Sqrt(
                [double]$site.maximum_support_distance_squared)
            maximum_support_distance_with_tolerance = [Math]::Sqrt(
                [double]$site.maximum_tolerance_bound_squared)
            navigation_tolerance = $script:ConstructionNavigationTolerance
        })
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90

    # A terminal navigation does not extend the old evidence lease. Prove the
    # complete row again from the first post-navigation frame before construction.
    $verifiedState = Get-FreshState
    $verifiedRecords = @(Get-VisibleSurfaceRecords -State $verifiedState `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    $verified = @(Select-ContiguousWallFoundation -Records $verifiedRecords `
        -PlayerPosition (Get-ObjectProperty `
            (Get-ObjectProperty $verifiedState 'world') 'position') -Width $Width)
    Add-GateEvent -Event 'wall_staging_foundation_verified' -Detail ([ordered]@{
            action_id = [string](Get-ObjectProperty $terminal 'action_id')
            target = $target
            support_count = $verified.Count
            positions = @($verified | ForEach-Object { Get-ObjectProperty $_ 'position' })
        })
}

function Select-ExactWallSupportRow {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object[]]$ExpectedPositions,
        [Parameter(Mandatory)][object]$ExpectedState
    )
    $byPosition = @{}
    foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'face') -cne 'up') { continue }
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if ($byPosition.ContainsKey($key)) { throw "duplicate delivered wall support $key" }
        $byPosition[$key] = $record
    }
    $selected = [Collections.Generic.List[object]]::new()
    foreach ($position in $ExpectedPositions) {
        $key = Get-BlockPositionKey $position
        if (-not $byPosition.ContainsKey($key)) {
            throw "placed wall cell was not freshly delivered as an UP support: $key"
        }
        $record = $byPosition[$key]
        if ((ConvertTo-CompactJson (Get-ObjectProperty $record 'state')) -cne
            (ConvertTo-CompactJson $ExpectedState)) {
            throw "fresh wall support has the wrong complete state: $key"
        }
        $selected.Add($record)
    }
    return @($selected)
}

function Wait-ForCurrentWallReorientationSurface {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState
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
    $current = Wait-ForCurrentVisibleSurfaceRecords -InitialState $InitialState `
        -Block 'minecraft:oak_log' -Bounds $bounds `
        -Faces @('up', 'north', 'south', 'east', 'west')
    $positionKey = Get-BlockPositionKey $Position
    $eligible = @($current.records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq $positionKey -and
            (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                (ConvertTo-CompactJson $ExpectedState)
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'face') -ceq 'up') { 0 } else { 1 }
                }; Descending = $false }, `
            @{ Expression = { [string](Get-ObjectProperty $_ 'face') }; Descending = $false })
    if ($eligible.Count -eq 0) {
        throw 'current wall reorientation surface has the wrong complete state'
    }
    return [pscustomobject]@{
        state = $current.state
        surface = $eligible[0]
        world_revision = $current.world_revision
        polls = $current.polls
    }
}

function Wait-ForCurrentExactWallSupportRow {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [Parameter(Mandatory)][object[]]$ExpectedPositions,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces = @('up'),
        [switch]$ExcludePlayerFeetAbove,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-VisibleSurfaceRecords -State $state -Block $Block `
            -Bounds $Bounds -Faces $Faces `
            -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove -AllowMissing)
        $currentRecords = @($records | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        if ($currentRecords.Count -gt 0) {
            try {
                $supports = @(Select-ExactWallSupportRow -Records $currentRecords `
                    -ExpectedPositions $ExpectedPositions -ExpectedState $ExpectedState)
                Add-GateEvent -Event 'wall_support_revision_current' -Detail ([ordered]@{
                        world_revision = $worldRevision
                        polls = $poll
                        positions = @($ExpectedPositions)
                    })
                return [pscustomobject]@{
                    state = $state
                    supports = @($supports)
                    world_revision = $worldRevision
                    polls = $poll
                }
            } catch {
                if ($_.Exception.Message -notmatch 'was not freshly delivered') { throw }
            }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "exact $Block support did not reach the current world revision after $MaximumPolls polls"
}

function Sort-WallSupportsFarToNear {
    param(
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][object]$ObserverPosition,
        [switch]$ObserverIsPlayerPosition
    )
    $observerOffset = if ($ObserverIsPlayerPosition) { 0.0 } else { 0.5 }
    return @($Supports | Sort-Object `
            @{ Expression = {
                    $position = Get-ObjectProperty $_ 'position'
                    $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'x') + $observerOffset)
                    $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'z') + $observerOffset)
                    $dx * $dx + $dz * $dz
                }; Descending = $true },
            @{ Expression = { Get-BlockPositionKey (Get-ObjectProperty $_ 'position') }; Descending = $false })
}

function New-WallRowActionPhase {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][ValidateRange(0, 7)][int]$RowIndex,
        [ValidateRange(1, 8)][int]$WallWidth = $Supports.Count
    )
    if ($Supports.Count -ne $WallWidth) {
        throw "a wall row must contain exactly $WallWidth supports"
    }
    $placementStateRef = [string](Get-ObjectProperty $Source 'placement_state_ref')
    if ($placementStateRef -cnotmatch '^psr_[0-9a-f]{32}$') {
        throw 'wall source did not retain a delivered placement_state_ref'
    }
    $targets = @($Supports | ForEach-Object {
            Get-TargetAboveSupport (Get-ObjectProperty $_ 'position')
        })
    $anchor = $targets[0]
    $entries = for ($column = 0; $column -lt $Supports.Count; $column++) {
        $support = $Supports[$column]
        $supportPosition = Get-ObjectProperty $support 'position'
        $supportState = Get-ObjectProperty $support 'state'
        if ($null -eq $supportState) { throw 'wall support did not include a complete state' }
        [ordered]@{
            id = "row_$($RowIndex)_column_$column"
            offset = [ordered]@{
                x = [int](Get-ObjectProperty $targets[$column] 'x') -
                    [int](Get-ObjectProperty $anchor 'x')
                y = [int](Get-ObjectProperty $targets[$column] 'y') -
                    [int](Get-ObjectProperty $anchor 'y')
                z = [int](Get-ObjectProperty $targets[$column] 'z') -
                    [int](Get-ObjectProperty $anchor 'z')
            }
            placement_state_ref = $placementStateRef
            support = [ordered]@{
                # Both values are retained from the fresh visible_surface record.
                position = $supportPosition
                face = 'up'
                expected_state = $supportState
                dependency_entry_id = $null
            }
        }
    }
    if (@($entries).Count -gt 8) { throw 'wall row exceeded the eight-entry phase limit' }
    $node = [ordered]@{
        id = "place_wall_row_$RowIndex"
        op = 'apply_known_block_plan'
        anchor = $anchor
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @($entries)
    }
    $entryCount = @($entries).Count
    $request = New-PrimitiveRequest -Name "capability_gate_wall_${WallWidth}wide_row_$RowIndex" `
        -Capabilities @('camera', 'block_place') -Node $node `
        -Duration (15000 * $entryCount) -Ticks (300 * $entryCount) `
        -Distance 0 -Camera (80 * $entryCount) -Placements $entryCount
    return [pscustomobject]@{
        request = $request
        targets = @($targets)
        entries = @($entries)
    }
}

function New-WallExternalOracleManifest {
    param(
        [Parameter(Mandatory)][object[]]$Targets,
        [Parameter(Mandatory)][object]$ExpectedState,
        [Parameter(Mandatory)][object]$SourcePosition,
        [Parameter(Mandatory)][object[]]$TemporaryPositions
    )
    $keys = @($Targets | ForEach-Object { Get-BlockPositionKey $_ } | Select-Object -Unique)
    if ($Targets.Count -lt 1 -or $keys.Count -ne $Targets.Count) {
        throw 'wall oracle targets are empty or not unique'
    }
    $temporaryKeys = @($TemporaryPositions | ForEach-Object {
            Get-BlockPositionKey $_
        } | Select-Object -Unique)
    if ($TemporaryPositions.Count -lt 1 -or
        $temporaryKeys.Count -ne $TemporaryPositions.Count) {
        throw 'temporary scaffold positions are empty or not unique'
    }
    if (@($temporaryKeys | Where-Object { $_ -cin $keys }).Count -gt 0) {
        throw 'temporary scaffold overlaps the permanent wall oracle'
    }
    $temporaryScaffolds = @($TemporaryPositions | ForEach-Object {
            [ordered]@{
                position = $_
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                transient_state = $ExpectedState
                after_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                included_in_expected_changed_cells = $false
                cleanup_required = $true
                drop_collection_required = 'minecraft:oak_log'
            }
        })
    [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        dimension = [string](Get-ObjectProperty $Targets[0] 'dimension')
        expected_changed_cell_count = $Targets.Count
        expected_changed_cells = @($Targets | ForEach-Object {
                [ordered]@{
                    position = $_
                    before_state = [ordered]@{
                        block = 'minecraft:air'; properties = [ordered]@{}
                    }
                    after_state = $ExpectedState
                }
            })
        expected_source = [ordered]@{
            position = $SourcePosition
            state = $ExpectedState
            changed = $false
        }
        # Keep the singular field for the established 3x3 artifact reader.
        temporary_scaffold = $temporaryScaffolds[0]
        temporary_scaffolds = $temporaryScaffolds
        temporary_scaffold_count = $temporaryScaffolds.Count
        reject_unlisted_changes = $true
        expected_air_violations = 0
        expected_extra_mutations = 0
    }
}

function New-GateCExternalOracleManifest {
    param(
        [Parameter(Mandatory)][object]$ExpectedState,
        [Parameter(Mandatory)][object]$SourcePosition,
        [Parameter(Mandatory)][object[]]$TemporaryPositions
    )
    $temporaryKeys = @($TemporaryPositions | ForEach-Object {
            Get-BlockPositionKey $_
        } | Select-Object -Unique)
    if ($TemporaryPositions.Count -ne 3 -or
        $temporaryKeys.Count -ne $TemporaryPositions.Count) {
        throw 'Gate C oracle requires exactly three unique temporary scaffold cells'
    }
    $temporaryScaffolds = @($TemporaryPositions | ForEach-Object {
            [ordered]@{
                position = $_
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                transient_state = $ExpectedState
                after_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                included_in_expected_changed_cells = $false
                cleanup_required = $true
                drop_collection_required = 'minecraft:oak_log'
            }
        })
    return [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        dimension = [string](Get-ObjectProperty $TemporaryPositions[0] 'dimension')
        expected_changed_cell_count = 0
        expected_changed_cells = @()
        expected_source = [ordered]@{
            position = $SourcePosition
            state = $ExpectedState
            changed = $false
        }
        temporary_scaffolds = $temporaryScaffolds
        temporary_scaffold_count = $temporaryScaffolds.Count
        reject_unlisted_changes = $true
        expected_air_violations = 0
        expected_extra_mutations = 0
        expected_inventory_delta = 0
    }
}

