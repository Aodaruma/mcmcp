# 観測済み足場候補から柱・階段を選択し、仮設柱を構築する。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Get-BlockColumnKey {
    param([Parameter(Mandatory)][object]$Position)
    return ('{0}|{1}|{2}' -f
        [string](Get-ObjectProperty $Position 'dimension'),
        [int](Get-ObjectProperty $Position 'x'),
        [int](Get-ObjectProperty $Position 'z'))
}

function Get-WallScaffoldTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [ValidateRange(0, 4)][int]$AdditionalHeight = 4
    )
    $bounds = [ordered]@{
        dimension = [string]$script:DestinationSupportBounds.dimension
        min_x = [int]$script:DestinationSupportBounds.min_x
        min_y = [int]$script:DestinationSupportBounds.min_y + 1
        min_z = [int]$script:DestinationSupportBounds.min_z
        max_x = [int]$script:DestinationSupportBounds.max_x
        max_y = [int]$script:DestinationSupportBounds.max_y + 1 + $AdditionalHeight
        max_z = [int]$script:DestinationSupportBounds.max_z
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }))
}

function Get-WallGroundTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    return @(Get-WallScaffoldTraversabilityRecords -State $State -AdditionalHeight 0)
}

function Get-ExactScaffoldTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$ExpectedTarget
    )
    $dimension = [string](Get-ObjectProperty $ExpectedTarget 'dimension')
    $x = [int](Get-ObjectProperty $ExpectedTarget 'x')
    $y = [int](Get-ObjectProperty $ExpectedTarget 'y')
    $z = [int](Get-ObjectProperty $ExpectedTarget 'z')
    $bounds = [ordered]@{
        dimension = $dimension
        min_x = $x; min_y = $y; min_z = $z
        max_x = $x; max_y = $y; max_z = $z
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }))
}

function Test-SafeTraversabilityRecord {
    param([Parameter(Mandatory)][object]$Record)
    return (Get-ObjectProperty $Record 'kind') -ceq 'traversability' -and
        (Get-ObjectProperty $Record 'status') -cin @('CONFIRMED', 'PROBE_ALLOWED') -and
        (Get-ObjectProperty $Record 'target_support') -ceq 'confirmed' -and
        (Get-ObjectProperty $Record 'transition_clearance') -ceq 'confirmed' -and
        (Get-ObjectProperty $Record 'fluid') -ceq 'none'
}

function Select-TemporaryPillarSite {
    param(
        [Parameter(Mandatory)][object[]]$WhiteWoolRecords,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$RowOneTargets,
        [ValidateRange(1, 8)][double]$MaximumWallReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -lt 1 -or
        $WallFoundation.Count -ne $RowOneTargets.Count) {
        throw 'temporary pillar selection requires one complete wall row'
    }
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $eligibleTraversability = [Collections.Generic.List[object]]::new()
    foreach ($record in $TraversabilityRecords) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $eligibleTraversability.Add($record)
    }

    $candidates = [Collections.Generic.List[object]]::new()
    foreach ($support in $WhiteWoolRecords) {
        $position = Get-ObjectProperty $support 'position'
        $state = Get-ObjectProperty $support 'state'
        if ((Get-ObjectProperty $support 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $support 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $support 'face') -cne 'up' -or
            (Get-ObjectProperty $state 'block') -cne 'minecraft:white_wool' -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $position))) { continue }

        $joined = @($eligibleTraversability | Where-Object {
                $target = Get-ObjectProperty $_ 'navigation_target'
                (Get-ObjectProperty $target 'dimension') -ceq
                    (Get-ObjectProperty $position 'dimension') -and
                [int](Get-ObjectProperty $target 'x') -eq
                    [int](Get-ObjectProperty $position 'x') -and
                [int](Get-ObjectProperty $target 'y') -eq
                    [int](Get-ObjectProperty $position 'y') + 1 -and
                [int](Get-ObjectProperty $target 'z') -eq
                    [int](Get-ObjectProperty $position 'z')
            } | Sort-Object `
                @{ Expression = {
                        if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                    }; Descending = $false },
                @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
        if ($joined.Count -eq 0) { continue }
        # Multiple edges may legitimately converge on one navigation_target.
        # Prefer a confirmed witness and retain that record's target verbatim.
        $joinedRecord = $joined[0]
        $target = Get-ObjectProperty $joinedRecord 'navigation_target'

        # The pillar lands one block above navigation_target. From that delivered
        # cell, every already-built row-1 UP face must remain within product reach.
        $maximumDistanceSquared = 0.0
        $maximumToleranceBoundSquared = 0.0
        foreach ($wallSupport in $RowOneTargets) {
            $dx = ([double](Get-ObjectProperty $wallSupport 'x') + 0.5) -
                ([double](Get-ObjectProperty $target 'x') + 0.5)
            $dy = ([double](Get-ObjectProperty $wallSupport 'y') + 1.0) -
                ([double](Get-ObjectProperty $target 'y') + 1.0 + 1.62)
            $dz = ([double](Get-ObjectProperty $wallSupport 'z') + 0.5) -
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
        if ($maximumToleranceBoundSquared -gt $MaximumWallReach * $MaximumWallReach) {
            continue
        }
        $candidates.Add([pscustomobject]@{
                support = $support
                navigation_record = $joinedRecord
                maximum_wall_distance_squared = $maximumDistanceSquared
                maximum_wall_tolerance_bound_squared = $maximumToleranceBoundSquared
                support_key = Get-BlockPositionKey $position
            })
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_wall_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_wall_distance_squared'; Descending = $false },
            @{ Expression = 'support_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh outside-footprint white-wool UP support joins a safe traversability target within raised wall reach'
    }
    return $selected[0]
}

function Select-TemporaryStaircasePlan {
    param(
        [Parameter(Mandatory)][object[]]$WhiteWoolRecords,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$RowOneTargets,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -ne 5 -or $RowOneTargets.Count -ne 5) {
        throw 'temporary staircase selection requires the complete five-wide wall'
    }
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $safeRecords = @($TraversabilityRecords | Where-Object {
            Test-SafeTraversabilityRecord $_
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    $safeByTarget = @{}
    foreach ($record in $safeRecords) {
        $targetKey = Get-BlockPositionKey (Get-ObjectProperty $record 'navigation_target')
        if (-not $safeByTarget.ContainsKey($targetKey)) {
            # Preserve the first, deterministically preferred delivered record object.
            $safeByTarget[$targetKey] = $record
        }
    }
    $groundY = [int]$script:DestinationSupportBounds.min_y + 1
    $sites = [Collections.Generic.List[object]]::new()
    $sitesByTarget = @{}
    foreach ($support in @($WhiteWoolRecords | Sort-Object `
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'position')
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })) {
        $position = Get-ObjectProperty $support 'position'
        $state = Get-ObjectProperty $support 'state'
        if ((Get-ObjectProperty $support 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $support 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $support 'face') -cne 'up' -or
            (Get-ObjectProperty $state 'block') -cne 'minecraft:white_wool' -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $position))) { continue }
        $joinedKey = '{0}|{1}|{2}|{3}' -f
            (Get-ObjectProperty $position 'dimension'),
            [int](Get-ObjectProperty $position 'x'),
            ([int](Get-ObjectProperty $position 'y') + 1),
            [int](Get-ObjectProperty $position 'z')
        if (-not $safeByTarget.ContainsKey($joinedKey)) { continue }
        $joinedRecord = $safeByTarget[$joinedKey]
        $joinedTarget = Get-ObjectProperty $joinedRecord 'navigation_target'
        if ([int](Get-ObjectProperty $joinedTarget 'y') -ne $groundY -or
            $sitesByTarget.ContainsKey($joinedKey)) { continue }
        $site = [pscustomobject]@{
                support = $support
                navigation_record = $joinedRecord
                target = $joinedTarget
                key = Get-BlockPositionKey $position
            }
        $sites.Add($site)
        $sitesByTarget[$joinedKey] = $site
    }

    $plans = [Collections.Generic.List[object]]::new()
    $directions = @(
        [pscustomobject]@{ x = -1; z = 0 },
        [pscustomobject]@{ x = 0; z = -1 },
        [pscustomobject]@{ x = 0; z = 1 },
        [pscustomobject]@{ x = 1; z = 0 }
    )
    foreach ($high in $sites) {
        $highTarget = $high.target
        $maximumWallDistanceSquared = 0.0
        foreach ($wallSupport in $RowOneTargets) {
            $dx = ([double]$wallSupport.x + 0.5) - ([double]$highTarget.x + 0.5)
            $dy = ([double]$wallSupport.y + 1.0) -
                ([double]$highTarget.y + 3.0 + 1.62)
            $dz = ([double]$wallSupport.z + 0.5) - ([double]$highTarget.z + 0.5)
            $horizontal = [Math]::Sqrt($dx * $dx + $dz * $dz)
            $distance = ($horizontal + $NavigationTolerance) *
                ($horizontal + $NavigationTolerance) + $dy * $dy
            $maximumWallDistanceSquared = [Math]::Max(
                $maximumWallDistanceSquared, $distance)
        }
        if ($maximumWallDistanceSquared -gt $MaximumReach * $MaximumReach) { continue }
        foreach ($direction in $directions) {
            $stepX = [int]$direction.x
            $stepZ = [int]$direction.z
            $mediumKey = '{0}|{1}|{2}|{3}' -f
                $highTarget.dimension, ([int]$highTarget.x + $stepX), $groundY,
                ([int]$highTarget.z + $stepZ)
            if (-not $sitesByTarget.ContainsKey($mediumKey)) { continue }
            $medium = $sitesByTarget[$mediumKey]
            $mediumTarget = $medium.target
            $lowKey = '{0}|{1}|{2}|{3}' -f
                $highTarget.dimension, ([int]$mediumTarget.x + $stepX), $groundY,
                ([int]$mediumTarget.z + $stepZ)
            if (-not $sitesByTarget.ContainsKey($lowKey)) { continue }
            $low = $sitesByTarget[$lowKey]
            $lowTarget = $low.target
            $temporaryPositions = @(
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = $highTarget.y; z = $highTarget.z },
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = ([int]$highTarget.y + 1); z = $highTarget.z },
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = ([int]$highTarget.y + 2); z = $highTarget.z },
                [pscustomobject]@{ dimension = $mediumTarget.dimension; x = $mediumTarget.x; y = $mediumTarget.y; z = $mediumTarget.z },
                [pscustomobject]@{ dimension = $mediumTarget.dimension; x = $mediumTarget.x; y = ([int]$mediumTarget.y + 1); z = $mediumTarget.z },
                [pscustomobject]@{ dimension = $lowTarget.dimension; x = $lowTarget.x; y = $lowTarget.y; z = $lowTarget.z }
            )
            foreach ($groundDirection in $directions) {
                $groundKey = '{0}|{1}|{2}|{3}' -f
                    $lowTarget.dimension,
                    ([int]$lowTarget.x + [int]$groundDirection.x), $groundY,
                    ([int]$lowTarget.z + [int]$groundDirection.z)
                if ($groundKey -cin @(
                        Get-BlockPositionKey $highTarget
                        Get-BlockPositionKey $mediumTarget
                        Get-BlockPositionKey $lowTarget) -or
                    -not $safeByTarget.ContainsKey($groundKey)) { continue }
                $groundRecord = $safeByTarget[$groundKey]
                $groundTarget = Get-ObjectProperty $groundRecord 'navigation_target'
                if ($wallColumns.ContainsKey((Get-BlockColumnKey $groundTarget))) { continue }
                $maximumCleanupDistanceSquared = 0.0
                foreach ($temporary in $temporaryPositions) {
                    $dx = ([double]$temporary.x + 0.5) - ([double]$groundTarget.x + 0.5)
                    $dy = ([double]$temporary.y + 0.5) - ([double]$groundTarget.y + 1.62)
                    $dz = ([double]$temporary.z + 0.5) - ([double]$groundTarget.z + 0.5)
                    $horizontal = [Math]::Sqrt($dx * $dx + $dz * $dz)
                    $distance = ($horizontal + $NavigationTolerance) *
                        ($horizontal + $NavigationTolerance) + $dy * $dy
                    $maximumCleanupDistanceSquared = [Math]::Max(
                        $maximumCleanupDistanceSquared, $distance)
                }
                if ($maximumWallDistanceSquared -gt $MaximumReach * $MaximumReach -or
                    $maximumCleanupDistanceSquared -gt $MaximumReach * $MaximumReach) { continue }
                $plans.Add([pscustomobject]@{
                        high = $high
                        medium = $medium
                        low = $low
                        ground_record = $groundRecord
                        maximum_wall_tolerance_bound_squared = $maximumWallDistanceSquared
                        maximum_cleanup_tolerance_bound_squared = $maximumCleanupDistanceSquared
                        key = ((Get-BlockPositionKey $highTarget) + '>' +
                            (Get-BlockPositionKey $mediumTarget) + '>' +
                            (Get-BlockPositionKey $lowTarget) + '>' +
                            (Get-BlockPositionKey $groundTarget))
                    })
            }
        }
    }
    $selected = @($plans | Sort-Object `
            @{ Expression = 'maximum_wall_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_cleanup_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'key'; Descending = $false } | Select-Object -First 1)
    if ($selected.Count -ne 1) {
        $siteKeys = @($sites | ForEach-Object { Get-BlockPositionKey $_.target }) -join ','
        $recordKeys = @($safeRecords | ForEach-Object {
                Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
            }) -join ','
        throw "no delivery-backed 3-2-1 temporary staircase keeps wall and cleanup reach; sites=$siteKeys records=$recordKeys"
    }
    return $selected[0]
}

function Select-TemporaryStaircaseSurveyRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$PlayerPosition,
        [Parameter(Mandatory)][object[]]$WallFoundation
    )
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $fromX = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'x'))
    $fromY = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'y'))
    $fromZ = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'z'))
    $candidates = @($Records | Where-Object {
            if (-not (Test-SafeTraversabilityRecord $_)) { return $false }
            $target = Get-ObjectProperty $_ 'navigation_target'
            $dx = [int]$target.x - $fromX
            $dz = [int]$target.z - $fromZ
            [int]$target.y -eq $fromY -and
            -not $wallColumns.ContainsKey((Get-BlockColumnKey $target)) -and
            $dx * $dx + $dz * $dz -ge 4 -and
            $dx * $dx + $dz * $dz -le 16
        } | Sort-Object `
            @{ Expression = {
                    $target = Get-ObjectProperty $_ 'navigation_target'
                    $dx = [int]$target.x - $fromX
                    $dz = [int]$target.z - $fromZ
                    $dx * $dx + $dz * $dz
                }; Descending = $true },
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
                }; Descending = $false })
    if ($candidates.Count -eq 0) {
        throw 'no delivered safe survey stance can reveal the under-foot staircase base'
    }
    return $candidates[0]
}

function New-TemporaryPillarActionRequest {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support
    )
    $supportPosition = Get-ObjectProperty $Support 'position'
    $supportState = Get-ObjectProperty $Support 'state'
    $placementStateRef = [string](Get-ObjectProperty $Source 'placement_state_ref')
    $supportBlock = [string](Get-ObjectProperty $supportState 'block')
    if ((Get-ObjectProperty $Support 'face') -cne 'up' -or
        $supportBlock -cnotin @('minecraft:white_wool', 'minecraft:oak_log') -or
        $placementStateRef -cnotmatch '^psr_[0-9a-f]{32}$') {
        throw 'temporary pillar inputs are not delivery-backed safe identities'
    }
    $node = [ordered]@{
        id = 'temporary_wall_pillar'
        op = 'pillar_up_known'
        support = $supportPosition
        expected_support = $supportState
        placement_state_ref = $placementStateRef
    }
    return New-PrimitiveRequest -Name 'capability_gate_wall_temporary_pillar' `
        -Capabilities @('movement', 'camera', 'block_place') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 2 -Camera 360 -Placements 1
}

function Invoke-TemporaryScaffoldColumn {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Site,
        [Parameter(Mandatory)][ValidateSet('single', 'low', 'medium', 'high')][string]$Role,
        [Parameter(Mandatory)][ValidateRange(1, 3)][int]$Height,
        [AllowNull()][string]$RaiseNavigationActionId
    )
    $state = $InitialState
    $positions = [Collections.Generic.List[object]]::new()
    $scaffolds = [Collections.Generic.List[object]]::new()
    for ($level = 1; $level -le $Height; $level++) {
        if ($level -eq 1) {
            $support = Get-ObjectProperty $Site 'support'
            $position = Get-ObjectProperty `
                (Get-ObjectProperty $Site 'navigation_record') 'navigation_target'
        } else {
            $previous = $positions[$positions.Count - 1]
            $support = Get-ExactTemporarySurface -State $state -Position $previous `
                -ExpectedState (Get-ObjectProperty $Source 'state') -Faces @('up')
            $position = Get-TargetAboveSupport $previous
        }
        $request = New-TemporaryPillarActionRequest -Source $Source -Support $support
        $frameId = Get-ObservationFrameId -State $state
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 60
        $state = Wait-ForObservationFrameAdvance -PreviousFrameId $frameId
        $positions.Add($position)
        $record = [ordered]@{
            column_role = $Role
            column_height = $Height
            level = $level
            level_in_column = $level
            support = Get-ObjectProperty $support 'position'
            position = $position
            raise_navigation_action_id = if ($level -eq 1) {
                $RaiseNavigationActionId
            } else { $null }
            pillar_action_id = [string](Get-ObjectProperty $terminal 'action_id')
            placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
        }
        $scaffolds.Add($record)
        Add-GateEvent -Event 'wall_temporary_pillar_terminal' -Detail ([ordered]@{
                column_role = $Role
                column_height = $Height
                level = $level
                action_id = [string](Get-ObjectProperty $terminal 'action_id')
                support = Get-ObjectProperty $support 'position'
                placed_position = $position
                placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
            })
    }
    return [pscustomobject]@{
        state = $state
        positions = @($positions)
        scaffolds = @($scaffolds)
        top_position = $positions[$positions.Count - 1]
    }
}

function Get-CurrentTemporaryScaffoldSite {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$ExpectedSite,
        [Parameter(Mandatory)][object]$NavigationRecord
    )
    $expectedSupport = Get-ObjectProperty $ExpectedSite 'support'
    $expectedPosition = Get-ObjectProperty $expectedSupport 'position'
    $current = Wait-ForCurrentVisibleSurfaceRecords -InitialState $State `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove
    $matches = @($current.records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq
                (Get-BlockPositionKey $expectedPosition)
        })
    if ($matches.Count -ne 1) {
        throw 'temporary staircase base did not have one current exact UP surface'
    }
    return [pscustomobject]@{
        state = $current.state
        site = [pscustomobject]@{
            support = $matches[0]
            navigation_record = $NavigationRecord
            target = Get-ObjectProperty $NavigationRecord 'navigation_target'
            key = Get-BlockPositionKey $expectedPosition
        }
    }
}

