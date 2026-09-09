# 仮設足場のexact/隣接移動、有限replan、下降先の選択。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Select-AdjacentScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$FromPosition,
        [Parameter(Mandatory)][object]$TargetColumn,
        [Parameter(Mandatory)][int]$TargetY,
        [switch]$AllowMissing
    )
    $fromX = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'x'))
    $fromY = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'y'))
    $fromZ = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'z'))
    $eligible = @($Records | Where-Object {
            if (-not (Test-SafeTraversabilityRecord $_)) { return $false }
            $target = Get-ObjectProperty $_ 'navigation_target'
            (Get-ObjectProperty $target 'dimension') -ceq
                (Get-ObjectProperty $TargetColumn 'dimension') -and
            [int](Get-ObjectProperty $target 'x') -eq [int](Get-ObjectProperty $TargetColumn 'x') -and
            [int](Get-ObjectProperty $target 'y') -eq $TargetY -and
            [int](Get-ObjectProperty $target 'z') -eq [int](Get-ObjectProperty $TargetColumn 'z') -and
            [Math]::Abs([int]$target.x - $fromX) +
                [Math]::Abs([int]$target.z - $fromZ) -eq 1 -and
            [Math]::Abs([int]$target.y - $fromY) -le 1
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    if ($eligible.Count -lt 1) {
        if ($AllowMissing) { return $null }
        throw 'no delivered adjacent scaffold navigation step satisfies abs(dy)<=1'
    }
    # A single observed target can be delivered more than once (for example,
    # as both CONFIRMED and PROBE_ALLOWED).  They name the same exact step;
    # use the deterministically preferred original record instead of treating
    # redundant policy evidence as spatial ambiguity.
    return $eligible[0]
}

function Select-ExactScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$ExpectedTarget,
        [switch]$AllowMissing
    )
    $key = Get-BlockPositionKey $ExpectedTarget
    $eligible = @($Records | Where-Object {
            (Test-SafeTraversabilityRecord $_) -and
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')) -ceq $key
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    if ($eligible.Count -lt 1) {
        if ($AllowMissing) { return $null }
        throw 'no delivered scaffold navigation target matches the requested step'
    }
    # Preserve the selected delivery object.  Duplicate evidence for this
    # exact coordinate is ordered CONFIRMED-first and then by compact JSON.
    return $eligible[0]
}

function Wait-ForExactScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$ExpectedTarget,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        # This is an exact-coordinate wait, so ask the public filter for that
        # one cell instead of opening a multi-page lease for the whole worksite.
        $records = @(Get-ExactScaffoldTraversabilityRecords -State $state `
                -ExpectedTarget $ExpectedTarget | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        $record = Select-ExactScaffoldNavigationRecord `
            -Records $records `
            -ExpectedTarget $ExpectedTarget -AllowMissing
        if ($null -ne $record) {
            Add-GateEvent -Event 'scaffold_traversability_current' -Detail ([ordered]@{
                    mode = 'exact'; polls = $poll
                    target = Get-ObjectProperty $record 'navigation_target'
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = $worldRevision
                })
            return [pscustomobject]@{ state = $state; record = $record; polls = $poll }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw 'no delivered scaffold navigation target became safe within the bounded wait'
}

function Wait-ForAdjacentScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$TargetColumn,
        [Parameter(Mandatory)][int]$TargetY,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-WallScaffoldTraversabilityRecords -State $state | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        $record = Select-AdjacentScaffoldNavigationRecord `
            -Records $records `
            -FromPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -TargetColumn $TargetColumn -TargetY $TargetY -AllowMissing
        if ($null -ne $record) {
            Add-GateEvent -Event 'scaffold_traversability_current' -Detail ([ordered]@{
                    mode = 'adjacent'; polls = $poll
                    target = Get-ObjectProperty $record 'navigation_target'
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = $worldRevision
                })
            return [pscustomobject]@{ state = $state; record = $record; polls = $poll }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw 'no delivered adjacent scaffold navigation step became safe within the bounded wait'
}

function Invoke-TemporaryScaffoldNavigation {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$NavigationRecord,
        [Parameter(Mandatory)][double]$Tolerance,
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][string]$Event,
        [switch]$ReturnAfterResliceableFailure,
        [ValidateRange(1, 8)][int]$MaximumSlices =
            $script:MaximumScaffoldNavigationSlices
    )
    $expectedTarget = Get-ObjectProperty $NavigationRecord 'navigation_target'
    $expectedTargetKey = Get-BlockPositionKey $expectedTarget
    $sliceState = $State
    $sliceRecord = $NavigationRecord
    $actionIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)

    for ($slice = 1; $slice -le $MaximumSlices; $slice++) {
        $from = Get-ObjectProperty (Get-ObjectProperty $sliceState 'world') 'position'
        $target = Get-ObjectProperty $sliceRecord 'navigation_target'
        if ((Get-BlockPositionKey $target) -cne $expectedTargetKey) {
            throw 'fresh scaffold navigation record changed the requested exact coordinate'
        }
        $request = New-NavigationActionRequest -NavigationRecord $sliceRecord `
            -State $sliceState -Tolerance $Tolerance
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90 `
            -ReturnFailure
        $actionId = [string](Get-ObjectProperty $terminal 'action_id')
        $hasNewActionId = -not [string]::IsNullOrWhiteSpace($actionId) -and
            $actionIds.Add($actionId)
        if ((Get-ObjectProperty $terminal 'state') -ceq 'succeeded') {
            if (-not $hasNewActionId) {
                throw 'temporary scaffold navigation did not receive a new action_id for each slice'
            }
            $nextState = Get-FreshState
            $fromX = [Math]::Floor([double](Get-ObjectProperty $from 'x'))
            $fromY = [Math]::Floor([double](Get-ObjectProperty $from 'y'))
            $fromZ = [Math]::Floor([double](Get-ObjectProperty $from 'z'))
            $dx = [Math]::Abs([int](Get-ObjectProperty $target 'x') - $fromX)
            $dy = [Math]::Abs([int](Get-ObjectProperty $target 'y') - $fromY)
            $dz = [Math]::Abs([int](Get-ObjectProperty $target 'z') - $fromZ)
            Add-GateEvent -Event $Event -Detail ([ordered]@{
                    step = $Step
                    action_id = $actionId
                    from = $from
                    target = $target
                    target_verbatim = [object]::ReferenceEquals(
                        $target, $request.program.body[0].target)
                    status = [string](Get-ObjectProperty $sliceRecord 'status')
                    horizontal_manhattan = $dx + $dz
                    absolute_y_delta = $dy
                    tolerance = $request.program.body[0].tolerance
                    slice = $slice
                    maximum_slices = $MaximumSlices
                    resliced = $slice -gt 1
                    action_ids = @($actionIds)
                })
            return [pscustomobject]@{
                state = $nextState
                terminal = $terminal
                target = $target
                horizontal_manhattan = $dx + $dz
                absolute_y_delta = $dy
                slices = $slice
            }
        }

        $failure = Get-ObjectProperty $terminal 'failure'
        $resliceAllowed = Test-NavigationTerminalRequiresFreshSlice -Terminal $terminal
        Add-GateEvent -Event 'temporary_scaffold_navigation_slice_failed' `
            -Detail ([ordered]@{
                step = $Step
                slice = $slice
                maximum_slices = $MaximumSlices
                failed_action_id = $actionId
                new_action_id = $hasNewActionId
                target = $target
                failure_code = [string](Get-ObjectProperty $failure 'code')
                failure_evidence = @((Get-ObjectProperty $failure 'evidence'))
                reslice_allowed = $resliceAllowed
                slices_remaining = $MaximumSlices - $slice
                fresh_state_required = $true
                old_record_reuse_allowed = $false
                synthetic_target_allowed = $false
            })
        if (-not $hasNewActionId) {
            throw 'temporary scaffold navigation did not receive a new action_id for each slice'
        }
        if (-not $resliceAllowed) {
            throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
        }
        if ($ReturnAfterResliceableFailure) {
            # A movement Action can cross the item before a route-shape replan
            # closes it as failed.  Recovery is governed by the material/drop
            # ledger, so return one fresh state to that caller instead of
            # waiting for the now-occupied exact target to be delivered again.
            $nextState = Get-FreshState
            $fromX = [Math]::Floor([double](Get-ObjectProperty $from 'x'))
            $fromY = [Math]::Floor([double](Get-ObjectProperty $from 'y'))
            $fromZ = [Math]::Floor([double](Get-ObjectProperty $from 'z'))
            $dx = [Math]::Abs([int](Get-ObjectProperty $target 'x') - $fromX)
            $dy = [Math]::Abs([int](Get-ObjectProperty $target 'y') - $fromY)
            $dz = [Math]::Abs([int](Get-ObjectProperty $target 'z') - $fromZ)
            Add-GateEvent -Event $Event -Detail ([ordered]@{
                    step = $Step
                    action_id = $actionId
                    terminal_state = [string](Get-ObjectProperty $terminal 'state')
                    failure_code = [string](Get-ObjectProperty $failure 'code')
                    reslice_required = $true
                    material_recheck_required = $true
                    from = $from
                    target = $target
                    target_verbatim = [object]::ReferenceEquals(
                        $target, $request.program.body[0].target)
                    horizontal_manhattan = $dx + $dz
                    absolute_y_delta = $dy
                    tolerance = $request.program.body[0].tolerance
                    slice = $slice
                    maximum_slices = $MaximumSlices
                    action_ids = @($actionIds)
                })
            return [pscustomobject]@{
                state = $nextState
                terminal = $terminal
                target = $target
                horizontal_manhattan = $dx + $dz
                absolute_y_delta = $dy
                slices = $slice
                reslice_required = $true
            }
        }
        if ($slice -eq $MaximumSlices) {
            throw "temporary scaffold navigation exhausted its bounded $MaximumSlices Action slices"
        }

        $freshState = Get-FreshState
        $fresh = Wait-ForExactScaffoldNavigationRecord -InitialState $freshState `
            -ExpectedTarget $expectedTarget
        $sliceState = $fresh.state
        $sliceRecord = $fresh.record
        Add-GateEvent -Event 'temporary_scaffold_navigation_reslice_selected' `
            -Detail ([ordered]@{
                step = $Step
                next_slice = $slice + 1
                maximum_slices = $MaximumSlices
                previous_action_id = $actionId
                target = Get-ObjectProperty $sliceRecord 'navigation_target'
                frame_id = Get-ObservationFrameId -State $sliceState
                world_revision = Get-CurrentWorldRevision -State $sliceState
                target_from_fresh_delivery = $true
                old_record_reuse_allowed = $false
                synthetic_target_allowed = $false
            })
    }
}

function Select-TemporaryPillarDescentRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne
                (Get-ObjectProperty $TemporaryPosition 'dimension') -or
            [int](Get-ObjectProperty $target 'y') -ne
                [int](Get-ObjectProperty $TemporaryPosition 'y') -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $target))) { continue }
        $dx = [double](Get-ObjectProperty $target 'x') -
            [double](Get-ObjectProperty $TemporaryPosition 'x')
        $dz = [double](Get-ObjectProperty $target 'z') -
            [double](Get-ObjectProperty $TemporaryPosition 'z')
        $horizontalDistanceSquared = $dx * $dx + $dz * $dz
        $horizontalDistance = [Math]::Sqrt($horizontalDistanceSquared)
        # Account for every pose accepted by navigate_to_known. A two-block
        # minimum remains after tolerance, while the far edge stays in break reach.
        if ($horizontalDistance - $NavigationTolerance -ge 2.0 -and
            $horizontalDistance + $NavigationTolerance -le 4.0) {
            [pscustomobject]@{
                record = $record
                horizontal_distance_squared = $horizontalDistanceSquared
                target_key = Get-BlockPositionKey $target
            }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'horizontal_distance_squared'; Descending = $true },
            @{ Expression = 'target_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh safe descent target is outside the wall and within cleanup reach'
    }
    return $selected[0].record
}
