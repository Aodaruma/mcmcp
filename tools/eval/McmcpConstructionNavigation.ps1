# navigation gateと、配送された移動先だけを使う建築対象への接近。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Test-NavigationTerminalRequiresFreshSlice {
    param([Parameter(Mandatory)][object]$Terminal)
    if ((Get-ObjectProperty $Terminal 'state') -cne 'failed') { return $false }
    $failure = Get-ObjectProperty $Terminal 'failure'
    if ((Get-ObjectProperty $failure 'code') -cne 'BUDGET_EXCEEDED') { return $false }
    $evidence = @((Get-ObjectProperty $failure 'evidence'))
    if ($evidence.Count -ne 1 -or
        $evidence[0] -cnotin @(
            'primitive_replanned_route',
            'replanned_route',
            'replanned_route_shape_exceeds_occurrence',
            'replanned_route_global_budget',
            'replanned_route_remaining_occurrence')) {
        return $false
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        return $false
    }
    return @((Get-ObjectProperty $Terminal 'trace') | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'REPLANNING'
        }).Count -ge 1
}

function Select-NavigationRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$WorldPosition,
        [double]$MaximumDistance = 8
    )
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        $dx = [double](Get-ObjectProperty $target 'x') - [double](Get-ObjectProperty $WorldPosition 'x')
        $dy = [double](Get-ObjectProperty $target 'y') - [double](Get-ObjectProperty $WorldPosition 'y')
        $dz = [double](Get-ObjectProperty $target 'z') - [double](Get-ObjectProperty $WorldPosition 'z')
        $distance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
        if ($distance -ge 3 -and $distance -le $MaximumDistance) {
            [pscustomobject]@{ record = $record; distance = $distance }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'distance'; Descending = $true },
            @{ Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target') }; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no traversable fresh navigation_target between 3 and 8 blocks was delivered'
    }
    return $selected[0].record
}

function New-NavigationActionRequest {
    param(
        [Parameter(Mandatory)][object]$NavigationRecord,
        [Parameter(Mandatory)][object]$State,
        [ValidateRange(0.1, 1.5)][double]$Tolerance = 0.75
    )
    $observedTarget = Get-ObjectProperty $NavigationRecord 'navigation_target'
    $node = [ordered]@{
        id = 'navigate_gate'
        op = 'navigate_to_known'
        # Deliberately retain the delivered object. Do not floor/round from/to.
        target = $observedTarget
        tolerance = $Tolerance
    }
    if ((ConvertTo-CompactJson $node.target) -cne (ConvertTo-CompactJson $observedTarget)) {
        throw 'navigation target changed while constructing the Action'
    }
    New-ActionRequest -Name 'capability_gate_navigation' -Capabilities @('movement') `
        -Body @($node) -Budget ([ordered]@{
            max_duration_ms = 30000; max_ticks = 600
            max_distance_blocks = Get-PolicyDistanceBudget $State
            max_camera_degrees = 0; max_interactions = 0
            max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function Invoke-NavigationGate {
    $initial = Get-FreshState
    $world = Get-ObjectProperty $initial 'world'
    $records = Get-RecordsFromState -State $initial -Kinds @('traversability') -Filter $null
    $record = Select-NavigationRecord -Records $records `
        -WorldPosition (Get-ObjectProperty $world 'position')
    $target = Get-ObjectProperty $record 'navigation_target'
    $request = New-NavigationActionRequest -NavigationRecord $record -State $initial
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
    $final = Get-FreshState
    $position = Get-ObjectProperty (Get-ObjectProperty $final 'world') 'position'
    if ([Math]::Floor([double](Get-ObjectProperty $position 'x')) -ne
            [int](Get-ObjectProperty $target 'x') -or
        [Math]::Floor([double](Get-ObjectProperty $position 'y')) -ne
            [int](Get-ObjectProperty $target 'y') -or
        [Math]::Floor([double](Get-ObjectProperty $position 'z')) -ne
            [int](Get-ObjectProperty $target 'z')) {
        throw 'navigation Action succeeded outside the delivered target feet cell'
    }
    return [ordered]@{
        gate = 'navigation'
        navigation_target = $target
        target_verbatim = $true
        final_feet_cell_matches = $true
        external_oracle = [ordered]@{
            expected_world_mutations = 0
            compare_regions = @('source', 'destination', 'work-area')
        }
    }
}

function Select-NavigationRecordTowardBounds {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$WorldPosition,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [double]$MaximumDistance = 8
    )
    $worldX = [double](Get-ObjectProperty $WorldPosition 'x')
    $worldY = [double](Get-ObjectProperty $WorldPosition 'y')
    $worldZ = [double](Get-ObjectProperty $WorldPosition 'z')
    $goalX = ([double]$Bounds.min_x + [double]$Bounds.max_x) / 2.0
    $goalZ = ([double]$Bounds.min_z + [double]$Bounds.max_z) / 2.0
    $currentGoalDistance = [Math]::Sqrt(
        [Math]::Pow($worldX - $goalX, 2) + [Math]::Pow($worldZ - $goalZ, 2))
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        $dx = [double](Get-ObjectProperty $target 'x') - $worldX
        $dy = [double](Get-ObjectProperty $target 'y') - $worldY
        $dz = [double](Get-ObjectProperty $target 'z') - $worldZ
        $travelDistance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
        $goalDistance = [Math]::Sqrt(
            [Math]::Pow([double](Get-ObjectProperty $target 'x') - $goalX, 2) +
            [Math]::Pow([double](Get-ObjectProperty $target 'z') - $goalZ, 2))
        if ($travelDistance -ge 1 -and $travelDistance -le $MaximumDistance -and
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
            @{ Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target') }; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh traversability record makes progress toward the gate bounds'
    }
    return $selected[0].record
}

function Get-NearbyTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    $feetX = [Math]::Floor([double](Get-ObjectProperty $position 'x'))
    $feetY = [Math]::Floor([double](Get-ObjectProperty $position 'y'))
    $feetZ = [Math]::Floor([double](Get-ObjectProperty $position 'z'))
    $filter = [ordered]@{
        position_bounds = [ordered]@{
            dimension = [string](Get-ObjectProperty $world 'dimension')
            min_x = $feetX - 2; min_y = $feetY - 1; min_z = $feetZ - 2
            max_x = $feetX + 2; max_y = $feetY + 1; max_z = $feetZ + 2
        }
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter $filter)
}

function Get-OrNavigateToVisibleSurface {
    param(
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [ValidateRange(0, 8)][int]$MaximumApproaches = 8
    )
    for ($attempt = 0; $attempt -le $MaximumApproaches; $attempt++) {
        $state = Get-FreshState
        $surface = Get-VisibleSurface -State $state -Block $Block -Bounds $Bounds `
            -Faces $Faces -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove -AllowMissing
        if ($null -ne $surface) {
            $player = Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position'
            $target = Get-ObjectProperty $surface 'position'
            $dx = ([double](Get-ObjectProperty $target 'x') + 0.5) -
                [double](Get-ObjectProperty $player 'x')
            $dy = ([double](Get-ObjectProperty $target 'y') + 0.5) -
                ([double](Get-ObjectProperty $player 'y') + 1.62)
            $dz = ([double](Get-ObjectProperty $target 'z') + 0.5) -
                [double](Get-ObjectProperty $player 'z')
            $surfaceDistance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
            if ($surfaceDistance -le 4.0) { return $surface }
            Add-GateEvent -Event 'visible_surface_outside_interaction_range' -Detail ([ordered]@{
                    block = $Block; attempt = $attempt
                    distance = [Math]::Round($surfaceDistance, 3)
                })
        }
        if ($attempt -eq $MaximumApproaches) { break }
        $world = Get-ObjectProperty $state 'world'
        $records = Get-NearbyTraversabilityRecords -State $state
        $record = Select-NavigationRecordTowardBounds -Records $records `
            -WorldPosition (Get-ObjectProperty $world 'position') -Bounds $Bounds
        $target = Get-ObjectProperty $record 'navigation_target'
        $request = New-NavigationActionRequest -NavigationRecord $record -State $state
        Add-GateEvent -Event 'surface_approach_navigation_selected' -Detail ([ordered]@{
                block = $Block; attempt = $attempt + 1
                navigation_target = $target; target_verbatim = $true
            })
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90 `
            -ReturnFailure
        if ((Get-ObjectProperty $terminal 'state') -cne 'succeeded') {
            if (-not (Test-NavigationTerminalRequiresFreshSlice -Terminal $terminal)) {
                $failure = Get-ObjectProperty $terminal 'failure'
                throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
            }
            Add-GateEvent -Event 'surface_approach_navigation_reslice_required' `
                -Detail ([ordered]@{
                    block = $Block; attempt = $attempt + 1
                    failed_action_id = [string](Get-ObjectProperty $terminal 'action_id')
                    failure_code = [string](Get-ObjectProperty `
                        (Get-ObjectProperty $terminal 'failure') 'code')
                    fresh_state_required = $true
                    old_target_reuse_allowed = $false
                })
            continue
        }
    }
    throw "no visible $Block surface was delivered after bounded observed-target approaches"
}

function Invoke-ApproachSurface {
    param([Parameter(Mandatory)][object]$Record, [Parameter(Mandatory)][object]$State)
    $observedTarget = Get-ObjectProperty $Record 'position'
    $node = [ordered]@{
        id = 'approach_surface'; op = 'approach_known_surface'
        target = $observedTarget
        expected_block = Get-ObjectProperty $Record 'block'
    }
    if ((ConvertTo-CompactJson $node.target) -cne (ConvertTo-CompactJson $observedTarget)) {
        throw 'approach target changed while constructing the Action'
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_approach' `
        -Capabilities @('movement', 'camera') -Node $node `
        -Distance (Get-PolicyDistanceBudget $State)
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
}
