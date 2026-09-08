# wall-3x3 / wall-5x5 / gate-cの実行順序と結果。共有する既存シナリオを維持する。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Invoke-WallGate {
    param(
        [Parameter(Mandatory)][ValidateRange(3, 5)][int]$Width,
        [Parameter(Mandatory)][ValidateRange(3, 5)][int]$Height,
        [Parameter(Mandatory)][ValidateRange(1, 3)][int]$ScaffoldLevels,
        [switch]$MovementCapabilityOnly
    )
    if (($Width -ne 3 -or $Height -ne 3 -or $ScaffoldLevels -ne 1) -and
        ($Width -ne 5 -or $Height -ne 5 -or $ScaffoldLevels -ne 3)) {
        throw 'wall gate supports only the audited 3x3/one-level and 5x5/three-level profiles'
    }
    if ($MovementCapabilityOnly -and
        ($Width -ne 5 -or $Height -ne 5 -or $ScaffoldLevels -ne 3)) {
        throw 'Gate C movement profile must reuse the audited five-wide staircase selector'
    }
    $permanentBlockCount = if ($MovementCapabilityOnly) { 0 } else { $Width * $Height }
    $temporaryBlockCount = if ($MovementCapabilityOnly) {
        3
    } elseif ($Width -eq 5) {
        6
    } else {
        1
    }
    $inventoryBefore = Acquire-OakLogFromChest
    if ($inventoryBefore -lt $permanentBlockCount + $temporaryBlockCount) {
        throw 'normal material acquisition cannot cover the permanent wall and transient scaffold'
    }
    Move-NearDestinationSupport -Width $Width

    $state = Get-FreshState
    $source = Get-OakLogPlacementSource -State $state
    if ($script:SourceObservationCount -ne 1) {
        throw 'wall gate did not obtain exactly one delivery-backed source reference'
    }
    $script:SourceObservationForbidden = $true
    $foundationRecords = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    Add-GateEvent -Event 'wall_foundation_candidates_observed' -Detail ([ordered]@{
            player_position = Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position'
            candidate_count = $foundationRecords.Count
            positions = @($foundationRecords | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
                })
            maximum_stationary_reach = 4.5
        })
    $supports = @(Select-ContiguousWallFoundation -Records $foundationRecords `
        -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
        -Width $Width)
    $wallFoundation = @($supports)
    $wallCenterColumn = Get-BlockColumnKey (Get-ObjectProperty `
        $wallFoundation[[int][Math]::Floor($wallFoundation.Count / 2)] 'position')

    $allTargets = [Collections.Generic.List[object]]::new()
    $rowActions = [Collections.Generic.List[object]]::new()
    $previousRowTargets = if ($MovementCapabilityOnly) {
        @($wallFoundation | ForEach-Object {
                Get-TargetAboveSupport (
                    Get-TargetAboveSupport (Get-ObjectProperty $_ 'position'))
            })
    } else {
        $null
    }
    $lowerRowCount = if ($MovementCapabilityOnly) { 0 } else { 2 }
    for ($row = 0; $row -lt $lowerRowCount; $row++) {
        # Establish execution order before any camera change. Width three fits
        # the shared admission heading; width five does not, so it uses the same
        # face -> fresh exact support -> one-entry boundary as elevated rows.
        $supports = @(Sort-WallSupportsFarToNear -Supports $supports `
            -ObserverPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -ObserverIsPlayerPosition)
        $orderedSupportPositions = @($supports | ForEach-Object {
                Get-ObjectProperty $_ 'position'
            })
        $supportBlock = if ($row -eq 0) { 'minecraft:white_wool' } else { 'minecraft:oak_log' }
        $freshBounds = if ($row -eq 0) {
            $script:DestinationSupportBounds
        } else {
            $script:DestinationWallBounds
        }
        $expectedSupportState = Get-ObjectProperty $supports[0] 'state'
        $rowActionIds = [Collections.Generic.List[string]]::new()
        $rowTerminalStates = [Collections.Generic.List[string]]::new()
        $rowTargets = [Collections.Generic.List[object]]::new()
        if ($Width -eq 3) {
            $pivotSupports = @($supports | Where-Object {
                    (Get-BlockColumnKey (Get-ObjectProperty $_ 'position')) -ceq
                        $wallCenterColumn
                })
            if ($pivotSupports.Count -ne 1) {
                throw 'fresh three-wide row did not retain its unique center pivot'
            }
            $faceSupport = $pivotSupports[0]
            Invoke-FaceSupport -Support $faceSupport
            $currentRow = Wait-ForCurrentExactWallSupportRow `
                -InitialState (Get-FreshState) -Block $supportBlock -Bounds $freshBounds `
                -ExpectedPositions $orderedSupportPositions `
                -ExpectedState $expectedSupportState
            $state = $currentRow.state
            $supports = @($currentRow.supports)
            Add-GateEvent -Event 'wall_row_heading_admitted' -Detail ([ordered]@{
                    row = $row; entry = 0
                    face_target = Get-ObjectProperty $faceSupport 'position'
                    first_execution_support = Get-ObjectProperty $supports[0] 'position'
                    support_count = $supports.Count
                    heading_strategy = 'center_pivot_batch'
                    proof = 'post_face_frame_exact_ordered_row'
                })
            $phase = New-WallRowActionPhase -Source $source -Supports $supports `
                -RowIndex $row -WallWidth $Width
            $placementFrameId = Get-ObservationFrameId -State $state
            $terminal = Invoke-ActionRequest -Request $phase.request -WallTimeoutSeconds 120
            $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
            $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
            $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
            foreach ($target in @($phase.targets)) { $rowTargets.Add($target) }
        } else {
            for ($entry = 0; $entry -lt $orderedSupportPositions.Count; $entry++) {
                # Every successful placement invalidates the preceding surface
                # frame. Reacquire this exact still-unbuilt support before even
                # the next face Action, then reacquire it again after facing.
                if ($entry -eq 0) { $state = Get-FreshState }
                $currentBeforeFace = Wait-ForCurrentExactWallSupportRow `
                    -InitialState $state -Block $supportBlock -Bounds $freshBounds `
                    -ExpectedPositions @($orderedSupportPositions[$entry]) `
                    -ExpectedState $expectedSupportState
                $state = $currentBeforeFace.state
                $supportBeforeFace = @($currentBeforeFace.supports)[0]
                Invoke-FaceSupport -Support $supportBeforeFace
                $currentAfterFace = Wait-ForCurrentExactWallSupportRow `
                    -InitialState (Get-FreshState) -Block $supportBlock -Bounds $freshBounds `
                    -ExpectedPositions @($orderedSupportPositions[$entry]) `
                    -ExpectedState $expectedSupportState
                $state = $currentAfterFace.state
                $freshSupport = @($currentAfterFace.supports)[0]
                Add-GateEvent -Event 'wall_row_heading_admitted' -Detail ([ordered]@{
                        row = $row; entry = $entry
                        face_target = Get-ObjectProperty $supportBeforeFace 'position'
                        first_execution_support = Get-ObjectProperty $freshSupport 'position'
                        support_count = 1
                        heading_strategy = 'first_entry_singleton'
                        proof = 'post_face_frame_exact_ordered_row'
                    })
                $single = New-OneOakLogPlacementPhase -Source $source `
                    -Support $freshSupport -UseStateRef
                $placementFrameId = Get-ObservationFrameId -State $state
                $terminal = Invoke-ActionRequest -Request $single.request -WallTimeoutSeconds 60
                $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
                $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
                $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
                $rowTargets.Add($single.target)
            }
        }
        foreach ($target in @($rowTargets)) { $allTargets.Add($target) }
        $rowActions.Add([ordered]@{
                row = $row
                action_id = $rowActionIds[0]
                action_ids = @($rowActionIds)
                action_count = $rowActionIds.Count
                terminal_state = $rowTerminalStates[$rowTerminalStates.Count - 1]
                terminal_states = @($rowTerminalStates)
                entry_count = $rowTargets.Count
                maximum_entries_per_action = if ($Width -eq 3) { 3 } else { 1 }
                stationary = $true
                order = 'far_to_near'
                targets = @($rowTargets)
            })

        $previousRowTargets = @($rowTargets)
        if ($row -eq 0) {
            $currentPlacedRow = Wait-ForCurrentExactWallSupportRow `
                -InitialState $state -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions @($rowTargets) `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentPlacedRow.state
            $supports = @($currentPlacedRow.supports)
            Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
                    row = $row; positions = @($rowTargets); support_count = $supports.Count
                })
        }
    }

    $temporaryPositions = [Collections.Generic.List[object]]::new()
    $temporaryScaffolds = [Collections.Generic.List[object]]::new()
    $temporaryColumns = [Collections.Generic.List[object]]::new()
    $descentRoute = [Collections.Generic.List[object]]::new()
    $temporaryBasePosition = $null
    $temporaryStaircasePlan = $null
    $cleanupGroundTarget = $null
    $gateCStepUp = $null

    $currentTemporarySupports = Wait-ForCurrentVisibleSurfaceRecords `
        -InitialState $state -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') `
        -ExcludePlayerFeetAbove
    $state = $currentTemporarySupports.state
    $temporarySupports = @($currentTemporarySupports.records)
    $temporaryTraversability = @(Get-WallScaffoldTraversabilityRecords -State $state)
    if ($Width -eq 3) {
        $temporarySite = Select-TemporaryPillarSite `
            -WhiteWoolRecords $temporarySupports `
            -TraversabilityRecords $temporaryTraversability `
            -WallFoundation $wallFoundation -RowOneTargets $previousRowTargets `
            -NavigationTolerance $script:PillarNavigationTolerance
        $temporaryBasePosition = Get-ObjectProperty `
            (Get-ObjectProperty $temporarySite 'navigation_record') 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $temporarySite.navigation_record `
            -Tolerance $script:PillarNavigationTolerance -Step 'ground_to_single' `
            -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $temporarySite -Role 'single' -Height 1 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'single'; height = 1; base_position = $temporaryBasePosition
                top_position = $column.top_position
            })
    } else {
        # The best high base can initially be hidden below the staging pose.
        # Move to a delivered safe survey target, then reacquire all candidate UP
        # faces at the same world revision instead of treating occlusion as air.
        $surveyRecord = Select-TemporaryStaircaseSurveyRecord `
            -Records $temporaryTraversability `
            -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -WallFoundation $wallFoundation
        $survey = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $surveyRecord `
            -Tolerance $script:ConstructionNavigationTolerance `
            -Step 'staging_to_survey_stance' `
            -Event 'wall_temporary_survey_navigation_terminal'
        $state = $survey.state
        $currentTemporarySupports = Wait-ForCurrentVisibleSurfaceRecords `
            -InitialState $state -Block 'minecraft:white_wool' `
            -Bounds $script:DestinationSupportBounds -Faces @('up') `
            -ExcludePlayerFeetAbove
        $state = $currentTemporarySupports.state
        $temporarySupports = @($currentTemporarySupports.records)
        $temporaryTraversability = @(Get-WallScaffoldTraversabilityRecords -State $state)
        $temporaryStaircasePlan = Select-TemporaryStaircasePlan `
            -WhiteWoolRecords $temporarySupports `
            -TraversabilityRecords $temporaryTraversability `
            -WallFoundation $wallFoundation -RowOneTargets $previousRowTargets `
            -NavigationTolerance $script:ConstructionNavigationTolerance
        $cleanupGroundTarget = Get-ObjectProperty `
            $temporaryStaircasePlan.ground_record 'navigation_target'
        Add-GateEvent -Event 'wall_temporary_staircase_selected' -Detail ([ordered]@{
                shape = '3-2-1'
                high = $temporaryStaircasePlan.high.target
                medium = $temporaryStaircasePlan.medium.target
                low = $temporaryStaircasePlan.low.target
                ground = $cleanupGroundTarget
                maximum_wall_tolerance_bound_squared =
                    $temporaryStaircasePlan.maximum_wall_tolerance_bound_squared
                maximum_cleanup_tolerance_bound_squared =
                    $temporaryStaircasePlan.maximum_cleanup_tolerance_bound_squared
                target_records_from_policy_delivery = $true
            })

        # Build low first. Each later base is refreshed at the current world
        # revision before navigation so a prior pillar cannot stale its UP face.
        $lowSite = Get-CurrentTemporaryScaffoldSite -State $state `
            -ExpectedSite $temporaryStaircasePlan.low `
            -NavigationRecord $temporaryStaircasePlan.low.navigation_record
        $state = $lowSite.state
        $lowNavigation = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
            -ExpectedTarget $temporaryStaircasePlan.low.target
        $state = $lowNavigation.state
        $lowRecord = $lowNavigation.record
        $lowSite.site.navigation_record = $lowRecord
        $lowSite.site.target = Get-ObjectProperty $lowRecord 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $lowRecord -Tolerance $script:PillarNavigationTolerance `
            -Step 'ground_to_low_base' -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $lowSite.site -Role 'low' -Height 1 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'low'; height = 1; base_position = $lowSite.site.target
                top_position = $column.top_position
            })

        $mediumSite = Get-CurrentTemporaryScaffoldSite -State $state `
            -ExpectedSite $temporaryStaircasePlan.medium `
            -NavigationRecord $temporaryStaircasePlan.medium.navigation_record
        $state = $mediumSite.state
        $mediumNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $temporaryStaircasePlan.medium.target `
            -TargetY ([int]$temporaryStaircasePlan.medium.target.y)
        $state = $mediumNavigation.state
        $mediumRecord = $mediumNavigation.record
        $mediumSite.site.navigation_record = $mediumRecord
        $mediumSite.site.target = Get-ObjectProperty $mediumRecord 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $mediumRecord -Tolerance $script:PillarNavigationTolerance `
            -Step 'low_top_to_medium_base' -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $mediumSite.site -Role 'medium' -Height 2 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'medium'; height = 2; base_position = $mediumSite.site.target
                top_position = $column.top_position
            })

        $lowTopNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $temporaryStaircasePlan.low.target `
            -TargetY ([int]$temporaryStaircasePlan.low.target.y + 1)
        $state = $lowTopNavigation.state
        $lowTopRecord = $lowTopNavigation.record
        $step = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $lowTopRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step 'medium_top_to_low_top' -Event 'wall_temporary_build_route_terminal'
        $state = $step.state
        if ($MovementCapabilityOnly) {
            $descentRoute.Add([ordered]@{
                    step = 'medium_top_to_low_top'
                    action_id = [string](Get-ObjectProperty $step.terminal 'action_id')
                    target = $step.target
                    horizontal_manhattan = $step.horizontal_manhattan
                    absolute_y_delta = $step.absolute_y_delta
                    target_from_policy_delivery = $true
                })
        }
        $groundNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $cleanupGroundTarget -TargetY ([int]$cleanupGroundTarget.y)
        $state = $groundNavigation.state
        $groundRecord = $groundNavigation.record
        $step = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $groundRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step 'low_top_to_ground' -Event 'wall_temporary_build_route_terminal'
        $state = $step.state
        if ($MovementCapabilityOnly) {
            $descentRoute.Add([ordered]@{
                    step = 'low_top_to_ground'
                    action_id = [string](Get-ObjectProperty $step.terminal 'action_id')
                    target = $step.target
                    horizontal_manhattan = $step.horizontal_manhattan
                    absolute_y_delta = $step.absolute_y_delta
                    target_from_policy_delivery = $true
                })

            # Gate C probes one upward full-block edge only after a complete,
            # already-proved descent to ground.  Missing policy evidence or a
            # fail-closed admission is a capability result, not permission to
            # synthesize a target.  Any accepted Action is still waited to a
            # terminal state before the shared top-down cleanup runs.
            $gateCStepUp = [ordered]@{
                status = 'not_delivered'
                target = $temporaryStaircasePlan.low.target
                target_from_policy_delivery = $false
                action_id = $null
                failure = $null
                returned_to_ground = $false
            }
            try {
                $upNavigation = Wait-ForAdjacentScaffoldNavigationRecord `
                    -InitialState $state -TargetColumn $temporaryStaircasePlan.low.target `
                    -TargetY ([int]$temporaryStaircasePlan.low.target.y + 1)
                $state = $upNavigation.state
                $upRecord = $upNavigation.record
                $upRequest = New-NavigationActionRequest -NavigationRecord $upRecord `
                    -State $state -Tolerance $script:PillarNavigationTolerance
                $upAttempt = Invoke-ActionRequest -Request $upRequest `
                    -WallTimeoutSeconds 90 -ReturnFailure -ReturnStartDomainError
                $gateCStepUp.target = Get-ObjectProperty $upRecord 'navigation_target'
                $gateCStepUp.target_from_policy_delivery = [object]::ReferenceEquals(
                    $gateCStepUp.target, $upRequest.program.body[0].target)
                $startError = Get-ObjectProperty $upAttempt 'start_domain_error'
                if ($null -ne $startError) {
                    $gateCStepUp.status = 'admission_rejected'
                    $gateCStepUp.failure = $startError
                } elseif ((Get-ObjectProperty $upAttempt 'state') -ceq 'succeeded') {
                    $gateCStepUp.status = 'passed'
                    $gateCStepUp.action_id = [string](Get-ObjectProperty $upAttempt 'action_id')
                    $state = Get-FreshState
                    $returnNavigation = Wait-ForAdjacentScaffoldNavigationRecord `
                        -InitialState $state -TargetColumn $cleanupGroundTarget `
                        -TargetY ([int]$cleanupGroundTarget.y)
                    $state = $returnNavigation.state
                    $returnStep = Invoke-TemporaryScaffoldNavigation -State $state `
                        -NavigationRecord $returnNavigation.record `
                        -Tolerance $script:PillarNavigationTolerance `
                        -Step 'gate_c_low_top_to_ground' `
                        -Event 'gate_c_step_up_return_terminal'
                    $state = $returnStep.state
                    $gateCStepUp.returned_to_ground = $true
                } else {
                    $gateCStepUp.status = 'terminal_failed'
                    $gateCStepUp.action_id = [string](Get-ObjectProperty $upAttempt 'action_id')
                    $gateCStepUp.failure = Get-ObjectProperty $upAttempt 'failure'
                }
            } catch {
                $gateCStepUp.status = 'evidence_or_return_failed'
                $gateCStepUp.failure = [ordered]@{
                    type = $_.Exception.GetType().FullName
                    message = $_.Exception.Message
                }
            }
            Add-GateEvent -Event 'gate_c_step_up_probe_completed' -Detail $gateCStepUp
        }

        if (-not $MovementCapabilityOnly) {
            $highSite = Get-CurrentTemporaryScaffoldSite -State $state `
                -ExpectedSite $temporaryStaircasePlan.high `
                -NavigationRecord $temporaryStaircasePlan.high.navigation_record
            $state = $highSite.state
            $highNavigation = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
                -ExpectedTarget $temporaryStaircasePlan.high.target
            $state = $highNavigation.state
            $highRecord = $highNavigation.record
            $highSite.site.navigation_record = $highRecord
            $highSite.site.target = Get-ObjectProperty $highRecord 'navigation_target'
            $raise = Invoke-TemporaryScaffoldNavigation -State $state `
                -NavigationRecord $highRecord -Tolerance $script:PillarNavigationTolerance `
                -Step 'ground_to_high_base' -Event 'wall_temporary_pillar_navigation_terminal'
            $state = $raise.state
            $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
                -Site $highSite.site -Role 'high' -Height 3 `
                -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
            $state = $column.state
            foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
            foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
            $temporaryColumns.Add([ordered]@{
                    role = 'high'; height = 3; base_position = $highSite.site.target
                    top_position = $column.top_position
                })
            $temporaryBasePosition = $highSite.site.target
        }
    }

    $firstElevatedRow = if ($MovementCapabilityOnly) { $Height } else { 2 }
    for ($row = $firstElevatedRow; $row -lt $Height; $row++) {
        # Every elevated row is split far-to-near. Each one-entry Action receives
        # its own post-face frame so a nearer block cannot hide a farther UP face.
        $reorientationTargets = @($previousRowTargets | Where-Object {
                (Get-BlockColumnKey $_) -ceq $wallCenterColumn
            })
        if ($reorientationTargets.Count -ne 1) {
            throw 'elevated row did not retain its unique center reorientation target'
        }
        $reorientation = Wait-ForCurrentWallReorientationSurface `
            -InitialState (Get-FreshState) -Position $reorientationTargets[0] `
            -ExpectedState (Get-ObjectProperty $source 'state')
        $state = $reorientation.state
        Invoke-FaceSupport -Support $reorientation.surface
        Add-GateEvent -Event 'wall_elevated_row_reoriented' -Detail ([ordered]@{
            row = $row
            position = $reorientationTargets[0]
            face = Get-ObjectProperty $reorientation.surface 'face'
            world_revision = $reorientation.world_revision
            polls = $reorientation.polls
            proof = 'current_exact_surface_before_up_surface_scan'
        })
        $currentPlacedRow = Wait-ForCurrentExactWallSupportRow `
            -InitialState (Get-FreshState) -Block 'minecraft:oak_log' `
            -Bounds $script:DestinationWallBounds `
            -ExpectedPositions $previousRowTargets `
            -ExpectedState (Get-ObjectProperty $source 'state')
        $state = $currentPlacedRow.state
        $supports = @($currentPlacedRow.supports)
        Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
                row = $row - 1; positions = $previousRowTargets
                support_count = $supports.Count
                raised_by_temporary_pillar = $true
                scaffold_level = $temporaryPositions.Count
            })

        $observerPosition = $temporaryPositions[$temporaryPositions.Count - 1]
        $remainingSupports = @(Sort-WallSupportsFarToNear -Supports $supports `
            -ObserverPosition $observerPosition)
        $rowActionIds = [Collections.Generic.List[string]]::new()
        $rowTerminalStates = [Collections.Generic.List[string]]::new()
        $rowTargets = [Collections.Generic.List[object]]::new()
        while ($remainingSupports.Count -gt 0) {
            $support = $remainingSupports[0]
            Invoke-FaceSupport -Support $support
            $currentSupport = Wait-ForCurrentExactWallSupportRow `
                -InitialState (Get-FreshState) -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions @((Get-ObjectProperty $support 'position')) `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentSupport.state
            $support = @($currentSupport.supports)[0]
            $single = New-OneOakLogPlacementPhase -Source $source -Support $support `
                -UseStateRef
            $placementFrameId = Get-ObservationFrameId -State $state
            $terminal = Invoke-ActionRequest -Request $single.request -WallTimeoutSeconds 60
            $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
            $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
            $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
            $rowTargets.Add($single.target)
            $allTargets.Add($single.target)
            Add-GateEvent -Event 'wall_elevated_cell_terminal' -Detail ([ordered]@{
                    row = $row
                    action_id = [string](Get-ObjectProperty $terminal 'action_id')
                    support = Get-ObjectProperty $support 'position'
                    target = $single.target
                    remaining_cells = $remainingSupports.Count - 1
                })

            $remainingPositions = @($remainingSupports | Select-Object -Skip 1 | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
            })
            if ($remainingPositions.Count -eq 0) { break }
            $currentRemaining = Wait-ForCurrentExactWallSupportRow `
                -InitialState $state -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions $remainingPositions `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentRemaining.state
            $remainingSupports = @($currentRemaining.supports)
        }
        $previousRowTargets = @($rowTargets)
        $rowActions.Add([ordered]@{
                row = $row
                action_ids = @($rowActionIds)
                action_count = $rowActionIds.Count
                terminal_states = @($rowTerminalStates)
                entry_count = $rowTargets.Count
                maximum_entries_per_action = 1
                stationary = $true
                order = 'far_to_near'
                targets = @($rowTargets)
        })
    }

    if ($Width -eq 5 -and -not $MovementCapabilityOnly) {
        $descentSteps = @(
            [pscustomobject]@{
                name = 'high_top_to_medium_top'
                column = $temporaryStaircasePlan.medium.target
                y = [int]$temporaryStaircasePlan.medium.target.y + 2
            },
            [pscustomobject]@{
                name = 'medium_top_to_low_top'
                column = $temporaryStaircasePlan.low.target
                y = [int]$temporaryStaircasePlan.low.target.y + 1
            },
            [pscustomobject]@{
                name = 'low_top_to_ground'
                column = $cleanupGroundTarget
                y = [int]$cleanupGroundTarget.y
            }
        )
        foreach ($descentStep in $descentSteps) {
            $descentNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
                -TargetColumn $descentStep.column -TargetY $descentStep.y
            $state = $descentNavigation.state
            $record = $descentNavigation.record
            $routeStep = Invoke-TemporaryScaffoldNavigation -State $state `
                -NavigationRecord $record -Tolerance $script:PillarNavigationTolerance `
                -Step $descentStep.name -Event 'wall_temporary_descent_step_terminal'
            $state = $routeStep.state
            $descentRoute.Add([ordered]@{
                    step = $descentStep.name
                    action_id = [string](Get-ObjectProperty $routeStep.terminal 'action_id')
                    target = $routeStep.target
                    horizontal_manhattan = $routeStep.horizontal_manhattan
                    absolute_y_delta = $routeStep.absolute_y_delta
                    target_from_policy_delivery = $true
                })
        }
    }

    $cleanupScaffolds = @($temporaryScaffolds | Sort-Object `
            @{ Expression = { [int](Get-ObjectProperty (Get-ObjectProperty $_ 'position') 'y') }; Descending = $true },
            # At one height, remove the outer/lower column first so it cannot
            # occlude the next inner block from the ground cleanup stance.
            @{ Expression = { [int](Get-ObjectProperty $_ 'column_height') }; Descending = $false },
            @{ Expression = { Get-BlockPositionKey (Get-ObjectProperty $_ 'position') }; Descending = $false })
    $cleanupRecoveryMinimumY = if ($Width -eq 5) {
        [int](Get-ObjectProperty $cleanupGroundTarget 'y')
    } else {
        [int](Get-ObjectProperty $temporaryBasePosition 'y')
    }
    for ($cleanupIndex = 0; $cleanupIndex -lt $cleanupScaffolds.Count; $cleanupIndex++) {
        $scaffold = $cleanupScaffolds[$cleanupIndex]
        $temporaryPosition = Get-ObjectProperty $scaffold 'position'
        $state = Get-FreshState
        # Collection may walk back toward the scaffold. Before every clear, move
        # to a newly delivered safe ground target so the block is never broken
        # underfoot. A normal passive pickup may still race the post-break entity
        # observation; the inventory/drop reconciliation below proves either
        # passive pickup or an explicit collect. The product pathfinder must prove
        # a route through staircase blocks that still exist; do not name a top
        # waypoint that an earlier cleanup removed.
        $descentRecord = if ($Width -eq 5) {
            $currentDescent = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
                -ExpectedTarget $cleanupGroundTarget
            $state = $currentDescent.state
            $currentDescent.record
        } else {
            $descentRecords = @(Get-WallScaffoldTraversabilityRecords -State $state)
            Select-TemporaryPillarDescentRecord -Records $descentRecords `
                -TemporaryPosition $temporaryBasePosition -WallFoundation $wallFoundation `
                -NavigationTolerance $script:ConstructionNavigationTolerance
        }
        $descentTarget = Get-ObjectProperty $descentRecord 'navigation_target'
        $descentRequest = New-NavigationActionRequest -NavigationRecord $descentRecord `
            -State $state -Tolerance $script:ConstructionNavigationTolerance
        Add-GateEvent -Event 'wall_temporary_descent_selected' -Detail ([ordered]@{
                cleanup_order = $cleanupIndex + 1
                column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                frame_id = [string](Get-ObjectProperty `
                    (Get-ObjectProperty $state 'observation') 'latest_frame_id')
                target = $descentTarget
                target_verbatim = [object]::ReferenceEquals(
                    $descentTarget, $descentRequest.program.body[0].target)
                status = [string](Get-ObjectProperty $descentRecord 'status')
            })
        # Active drop collection can leave the player several blocks away from
        # the cleanup stance.  A valid route may then change shape while the
        # Action is moving.  Reacquire the same exact policy-delivered target
        # after the narrow replan signal instead of treating the first bounded
        # slice as the whole cleanup attempt.
        $descentStep = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $descentRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step "cleanup_ground_$($cleanupIndex + 1)" `
            -Event 'wall_temporary_cleanup_descent_terminal'
        $state = $descentStep.state
        $descentTerminal = $descentStep.terminal

        $currentTemporary = Wait-ForCurrentExactTemporarySurface `
            -InitialState $state -Position $temporaryPosition `
            -ExpectedState (Get-ObjectProperty $source 'state') -Faces $null
        $state = $currentTemporary.state
        $temporarySurface = $currentTemporary.surface
        Invoke-FaceSupport -Support $temporarySurface
        $currentTemporary = Wait-ForCurrentExactTemporarySurface `
            -InitialState (Get-FreshState) -Position $temporaryPosition `
            -ExpectedState (Get-ObjectProperty $source 'state') -Faces $null
        $state = $currentTemporary.state
        $temporarySurface = $currentTemporary.surface
        Assert-NoTemporaryDropRecord -State $state -TemporaryPosition $temporaryPosition `
            -MinimumY $cleanupRecoveryMinimumY
        $inventoryBeforeClear = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
        $clearRequest = New-TemporaryClearActionRequest -Surface $temporarySurface
        $clearFrameId = Get-ObservationFrameId -State $state
        $clearTerminal = Invoke-ActionRequest -Request $clearRequest -WallTimeoutSeconds 60

        # A freshly broken item is still moving. Wait without observing it, then
        # reconcile passive pickup or bind active collect to one fresh drop pose.
        $settleRequest = New-TemporaryDropSettleActionRequest
        $settleTerminal = Invoke-ActionRequest -Request $settleRequest -WallTimeoutSeconds 60
        $state = Wait-ForObservationFrameAdvance -PreviousFrameId $clearFrameId
        $recoveryApproachActionIds = [Collections.Generic.List[string]]::new()
        $collectAdmissionDeferrals = 0
        $collectTerminal = $null
        for ($recoveryApproach = 0; $recoveryApproach -le 2; $recoveryApproach++) {
            $recoveryEvidence = Wait-ForTemporaryDropRecoveryEvidence `
                -InitialState $state -TemporaryPosition $temporaryPosition `
                -InventoryBeforeClear $inventoryBeforeClear `
                -MinimumY $cleanupRecoveryMinimumY `
                -AllowUnavailable
            $state = Get-ObjectProperty $recoveryEvidence 'state'
            $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
            $approachGoal = $temporaryPosition
            $approachReason = 'occluded_drop'
            $approachRecords = $null

            if ($null -ne $recovery) {
                $recoveryMode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                if ($recoveryMode -ceq 'passive_pickup') { break }
                if ($recoveryMode -cne 'active_collect') {
                    throw "unsupported temporary recovery mode: $recoveryMode"
                }

                $drop = Get-ObjectProperty $recovery 'drop'
                Add-GateEvent -Event 'wall_temporary_drop_observed' -Detail ([ordered]@{
                        cleanup_order = $cleanupIndex + 1
                        column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                        scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                        frame_id = Get-ObservationFrameId -State $state
                        recovery_mode = 'active_collect'
                        inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                        position = Get-ObjectProperty $drop 'position'
                        displayed_item = [string](Get-ObjectProperty $drop 'displayed_item')
                        settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
                        settle_ticks = 40
                })
                # The fresh item record and current traversability are
                # independent policy evidence. Let the product planner admit
                # collect first; TARGET_UNKNOWN is a recoverable request for a
                # new viewpoint, not permission to reuse either stale record.
                $approachRecords = @(Get-CurrentTemporaryDropPickupTraversabilityRecords `
                        -State $state -Drop $drop)
                $collectRequest = New-TemporaryDropCollectionRequest -Record $drop -State $state
                $collectAttempt = Invoke-ActionRequest -Request $collectRequest `
                    -WallTimeoutSeconds 90 -ReturnStartDomainError
                $startDomainError = Get-ObjectProperty $collectAttempt 'start_domain_error'
                if ($null -eq $startDomainError) {
                    $collectTerminal = $collectAttempt
                    break
                }
                if ((Get-ObjectProperty $startDomainError 'code') -cne 'TARGET_UNKNOWN' -or
                    (Get-ObjectProperty $startDomainError 'recoverable') -isnot [bool] -or
                    -not [bool](Get-ObjectProperty $startDomainError 'recoverable')) {
                    throw ('temporary drop collect admission failed closed: ' +
                        (ConvertTo-CompactJson $startDomainError))
                }
                $collectAdmissionDeferrals++
                $approachReason = 'collect_target_unknown'
                $rejectedFrameId = Get-ObservationFrameId -State $state
                # The admission attempt may advance both frame and policy
                # state. Discard every pre-rejection item/traversability object,
                # wait for a new frame, and reacquire the recovery evidence
                # before selecting the bounded approach Action.
                $approachRecords = $null
                $state = Wait-ForObservationFrameAdvance `
                    -PreviousFrameId $rejectedFrameId
                $recoveryEvidence = Wait-ForTemporaryDropRecoveryEvidence `
                    -InitialState $state -TemporaryPosition $temporaryPosition `
                    -InventoryBeforeClear $inventoryBeforeClear `
                    -MinimumY $cleanupRecoveryMinimumY `
                    -AllowUnavailable
                $state = Get-ObjectProperty $recoveryEvidence 'state'
                $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
                Add-GateEvent -Event 'wall_temporary_drop_collect_admission_deferred' `
                    -Detail ([ordered]@{
                        cleanup_order = $cleanupIndex + 1
                        approach = $recoveryApproach + 1
                        code = 'TARGET_UNKNOWN'
                        recoverable = $true
                        old_drop_reuse_allowed = $false
                        fresh_observation_required = $true
                        rejected_frame_id = $rejectedFrameId
                        fresh_frame_id = Get-ObservationFrameId -State $state
                        fresh_recovery_mode = if ($null -eq $recovery) {
                            'unavailable'
                        } else {
                            [string](Get-ObjectProperty $recovery 'recovery_mode')
                        }
                    })
                if ($null -ne $recovery -and
                    (Get-ObjectProperty $recovery 'recovery_mode') -ceq 'passive_pickup') {
                    break
                }
                if ($null -ne $recovery) {
                    if ((Get-ObjectProperty $recovery 'recovery_mode') -cne 'active_collect') {
                        throw 'fresh temporary drop recovery returned an unsupported mode'
                    }
                    $drop = Get-ObjectProperty $recovery 'drop'
                    $approachGoal = Get-ObjectProperty $drop 'position'
                    $approachRecords = @(Get-CurrentTemporaryDropPickupTraversabilityRecords `
                            -State $state -Drop $drop)
                } else {
                    $approachGoal = $temporaryPosition
                    $approachReason = 'collect_target_unknown_drop_occluded'
                }
            }

            if ($recoveryApproach -eq 2) {
                throw 'temporary pillar recovery exhausted 2 bounded passive approach Action(s)'
            }
            if ($null -eq $approachRecords) {
                # Clear success plus an unchanged inventory can leave the drop
                # occluded by the wall or the lower scaffold.
                $approachRecords = @(Get-CurrentSafeWallTraversabilityRecords -State $state)
            }
            if ($approachRecords.Count -lt 1) {
                throw 'no current safe traversability was delivered for temporary drop passive recovery'
            }
            $approachRecord = Select-TemporaryDropRecoveryApproachRecord `
                -Records $approachRecords -State $state `
                -TemporaryPosition $approachGoal
            $approachTarget = Get-ObjectProperty $approachRecord 'navigation_target'
            Add-GateEvent -Event 'wall_temporary_drop_passive_approach_selected' `
                -Detail ([ordered]@{
                    cleanup_order = $cleanupIndex + 1
                    approach = $recoveryApproach + 1
                    reason = $approachReason
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = Get-CurrentWorldRevision -State $state
                    target = $approachTarget
                    target_from_current_policy_delivery = $true
                    synthetic_target_allowed = $false
                })
            # A normal construction tolerance may stop near the edge of the
            # adjacent cell, outside the item pickup overlap.  This route
            # exists only to reconcile one freshly cleared block, so reach the
            # policy-delivered cell centre tightly.
            $approachResult = Invoke-TemporaryScaffoldNavigation `
                -State $state -NavigationRecord $approachRecord `
                -Tolerance $script:TemporaryDropRecoveryNavigationTolerance `
                -Step "cleanup-$($cleanupIndex + 1)-passive-recovery-$($recoveryApproach + 1)" `
                -Event 'wall_temporary_drop_passive_approach_terminal' `
                -ReturnAfterResliceableFailure
            $state = Get-ObjectProperty $approachResult 'state'
            $recoveryApproachActionIds.Add([string](Get-ObjectProperty `
                (Get-ObjectProperty $approachResult 'terminal') 'action_id'))
        }
        $state = Get-ObjectProperty $recoveryEvidence 'state'
        $inventoryAfterSettle = [long](Get-ObjectProperty `
            $recoveryEvidence 'inventory_after_settle')
        $visibleDrops = @(Get-ObjectProperty $recoveryEvidence 'visible_drops')
        $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
        $drop = Get-ObjectProperty $recovery 'drop'
        Add-GateEvent -Event 'wall_temporary_drop_recovery_selected' -Detail ([ordered]@{
                cleanup_order = $cleanupIndex + 1
                column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                frame_id = [string](Get-ObjectProperty `
                    (Get-ObjectProperty $state 'observation') 'latest_frame_id')
                recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                inventory_before_clear = $inventoryBeforeClear
                inventory_after_settle = $inventoryAfterSettle
                inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
                position = if ($null -eq $drop) {
                    $null
                } else {
                    Get-ObjectProperty $drop 'position'
                }
                settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
                settle_ticks = 40
                evidence_polls = [int](Get-ObjectProperty $recoveryEvidence 'polls')
                evidence_observed_frames = [int](Get-ObjectProperty `
                    $recoveryEvidence 'observed_frames')
                evidence_pending_empty_observations = [int](Get-ObjectProperty `
                    $recoveryEvidence 'pending_empty_observations')
        })
        $scaffold.descent_target = $descentTarget
        $scaffold.descent_action_id = [string](Get-ObjectProperty $descentTerminal 'action_id')
        $scaffold.cleanup_order = $cleanupIndex + 1
        $scaffold.clear_action_id = [string](Get-ObjectProperty $clearTerminal 'action_id')
        $scaffold.settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
        $scaffold.settle_ticks = 40
        $scaffold.recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
        $scaffold.inventory_before_clear = $inventoryBeforeClear
        $scaffold.inventory_after_settle = $inventoryAfterSettle
        $scaffold.inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
        $scaffold.visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
        $scaffold.recovery_evidence_polls = [int](Get-ObjectProperty `
            $recoveryEvidence 'polls')
        $scaffold.recovery_evidence_observed_frames = [int](Get-ObjectProperty `
            $recoveryEvidence 'observed_frames')
        $scaffold.recovery_pending_empty_observations = [int](Get-ObjectProperty `
            $recoveryEvidence 'pending_empty_observations')
        $scaffold.recovery_approach_action_ids = @($recoveryApproachActionIds)
        $scaffold.recovery_approach_action_count = $recoveryApproachActionIds.Count
        $scaffold.collect_admission_deferrals = $collectAdmissionDeferrals
        $scaffold.collected_drop_target = if ($null -eq $drop) {
            $null
        } else {
            Get-ObjectProperty $drop 'position'
        }
        $scaffold.collect_action_id = if ($null -eq $collectTerminal) {
            $null
        } else {
            [string](Get-ObjectProperty $collectTerminal 'action_id')
        }
        $scaffold.expected_cleanup = $true
        $scaffold.expected_drop_collection = 'minecraft:oak_log'
        $scaffold.included_in_expected_changed_cells = $false
    }
    $state = Get-FreshState

    if ($script:SourceObservationCount -ne 1) {
        throw 'wall gate re-observed its placement source'
    }
    $inventoryAfter = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
    if ($inventoryAfter -ne $inventoryBefore - $permanentBlockCount) {
        throw "oak-log inventory ledger did not decrease by exactly $permanentBlockCount"
    }
    $targets = @($allTargets)
    $oracle = if ($MovementCapabilityOnly) {
        New-GateCExternalOracleManifest `
            -ExpectedState (Get-ObjectProperty $source 'state') `
            -SourcePosition (Get-ObjectProperty $source 'position') `
            -TemporaryPositions @($temporaryPositions)
    } else {
        New-WallExternalOracleManifest -Targets $targets `
            -ExpectedState (Get-ObjectProperty $source 'state') `
            -SourcePosition (Get-ObjectProperty $source 'position') `
            -TemporaryPositions @($temporaryPositions)
    }
    $gateName = if ($MovementCapabilityOnly) { 'gate-c' } else { "wall-${Width}x${Height}" }
    return [ordered]@{
        gate = $gateName
        wall_dimensions = [ordered]@{ width = $Width; height = $Height; depth = 1 }
        full_cube_block = 'minecraft:oak_log'
        action_coordinates_from_observations_only = $true
        configured_bounds_used_as_observation_filters_only = $true
        material_acquisition = 'fresh_visible_chest_normal_player_transfer'
        placement_identity = 'single_delivery_backed_placement_state_ref'
        source_observations = $script:SourceObservationCount
        source_reobserved = $false
        source_expected_unchanged = $true
        foundation_evidence = 'policy_visible_white_wool_up_faces'
        row_actions = @($rowActions)
        row_phase_count = $rowActions.Count
        wall_placement_action_count = if ($rowActions.Count -eq 0) {
            0
        } else {
            @($rowActions | ForEach-Object {
                    [int](Get-ObjectProperty $_ 'action_count')
                } | Measure-Object -Sum).Sum
        }
        temporary_scaffold = $temporaryScaffolds[0]
        temporary_scaffolds = @($temporaryScaffolds)
        temporary_scaffold_count = $temporaryScaffolds.Count
        temporary_shape = if ($MovementCapabilityOnly) {
            '2-1 staircase'
        } elseif ($Width -eq 5) {
            '3-2-1 staircase'
        } else {
            'single column'
        }
        temporary_columns = @($temporaryColumns)
        temporary_column_count = $temporaryColumns.Count
        descent_route = @($descentRoute)
        descent_action_count = $descentRoute.Count
        total_action_count = @($script:GateEvents | Where-Object {
                (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
            }).Count
        maximum_entries_per_action = if ($rowActions.Count -eq 0) {
            0
        } else {
            @($rowActions | ForEach-Object {
                    [int](Get-ObjectProperty $_ 'maximum_entries_per_action')
                } | Measure-Object -Maximum).Maximum
        }
        phase_entry_limit = 8
        stationary_placement = $true
        exact_target_count = $targets.Count
        exact_targets = $targets
        expected_air_violations = 0
        expected_extra_mutations = 0
        external_oracle_status = 'pending'
        mutation_proof = "$permanentBlockCount unique fresh supports plus $temporaryBlockCount observation-derived temporary blocks cleared top-down and recollected; placement inventory delta minus $permanentBlockCount; external MCA required"
        inventory_before_placement = $inventoryBefore
        inventory_after_placement = $inventoryAfter
        inventory_delta = $inventoryAfter - $inventoryBefore
        capability_complete = -not $MovementCapabilityOnly
        capability_components = if ($MovementCapabilityOnly) {
            [ordered]@{
                pillar_scaffold = 'passed'
                step_down = 'passed'
                step_up = [string](Get-ObjectProperty $gateCStepUp 'status')
                edge_bridge = 'not_expressible_without_safe_crouch_bridge_primitive'
            }
        } else { $null }
        step_up_probe = if ($MovementCapabilityOnly) { $gateCStepUp } else { $null }
        external_oracle = $oracle
    }
}

function Invoke-Wall3x3Gate {
    Invoke-WallGate -Width 3 -Height 3 -ScaffoldLevels 1
}

function Invoke-Wall5x5Gate {
    Invoke-WallGate -Width 5 -Height 5 -ScaffoldLevels 3
}

function Invoke-BuildingGateC {
    Invoke-WallGate -Width 5 -Height 5 -ScaffoldLevels 3 -MovementCapabilityOnly
}

