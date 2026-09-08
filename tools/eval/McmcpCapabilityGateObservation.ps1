# ready状態、観測ページ・frame/revision待機、可視表面、inventoryと座標の共通支援。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Assert-ReadyState {
    param([Parameter(Mandatory)][object]$State, [string]$Phase = 'readiness')
    $control = Get-ObjectProperty $State 'control'
    $action = Get-ObjectProperty $State 'action'
    if ((Get-ObjectProperty $control 'mode') -cne 'ready' -or
        (Get-ObjectProperty $control 'game_paused') -isnot [bool] -or
        [bool](Get-ObjectProperty $control 'game_paused')) {
        throw "$Phase requires control.mode=ready and an unpaused game"
    }
    if ($null -eq (Get-ObjectProperty $State 'world') -or
        $null -eq (Get-ObjectProperty $State 'observation')) {
        throw "$Phase requires a loaded world and observation frame"
    }
    if ($null -ne $action -and
        (Get-ObjectProperty $action 'state') -cnotin $script:TerminalStates) {
        throw "$Phase found a non-terminal Action"
    }
}

function Get-FreshState {
    $state = Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{})
    Assert-ReadyState -State $state
    return $state
}

function Get-ObservationFrameId {
    param([Parameter(Mandatory)][object]$State)
    $frameId = [string](Get-ObjectProperty `
        (Get-ObjectProperty $State 'observation') 'latest_frame_id')
    if ($frameId -cnotmatch '^obs-[0-9a-f]{16}$') {
        throw 'agent_get_state did not announce a valid observation frame'
    }
    return $frameId
}

function Invoke-GateDelaySeconds {
    param([ValidateRange(0.001, 900.0)][double]$Seconds)
    if ($null -ne $script:DelayTransport) {
        & $script:DelayTransport $Seconds
        return
    }
    Start-Sleep -Milliseconds ([Math]::Max(1, [Math]::Ceiling($Seconds * 1000.0)))
}

function Wait-ForObservationFrameAdvance {
    param(
        [Parameter(Mandatory)][string]$PreviousFrameId,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    if ($PreviousFrameId -cnotmatch '^obs-[0-9a-f]{16}$') {
        throw 'observation frame barrier requires a valid previous frame id'
    }
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $state = Get-FreshState
        $currentFrameId = Get-ObservationFrameId -State $state
        if ($currentFrameId -cne $PreviousFrameId) {
            Add-GateEvent -Event 'observation_frame_advanced' -Detail ([ordered]@{
                    previous_frame_id = $PreviousFrameId
                    current_frame_id = $currentFrameId
                    polls = $poll
                })
            return $state
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
        }
    }
    throw "observation frame did not advance from $PreviousFrameId after $MaximumPolls polls"
}

function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    $frameId = Get-ObservationFrameId -State $State
    $records = [Collections.Generic.List[object]]::new()
    $cursor = $null
    do {
        $arguments = [ordered]@{
            schema_version = 1
            frame_id = $frameId
            kinds = @($Kinds)
            cursor = $cursor
            limit = 256
        }
        if ($null -ne $Filter) { $arguments.filter = $Filter }
        $page = Invoke-GateTool -Tool 'agent_get_observation' -Arguments $arguments
        if ((Get-ObjectProperty $page 'frame_id') -cne $frameId) {
            throw 'agent_get_observation returned a mismatched frame'
        }
        foreach ($record in @(Get-ObjectProperty $page 'records')) {
            # Windows PowerShell may materialize an empty JSON array as a single null pipeline
            # value. Treat it as the empty page advertised by the protocol.
            if ($null -ne $record) { $records.Add($record) }
        }
        $cursor = Get-ObjectProperty $page 'next_cursor'
    } while ($null -ne $cursor)
    return @($records)
}

function Get-InventoryCount {
    param([Parameter(Mandatory)][object]$State, [Parameter(Mandatory)][string]$Item)
    $count = 0L
    foreach ($stack in @(Get-ObjectProperty $State 'inventory')) {
        if ((Get-ObjectProperty $stack 'item') -ceq $Item) {
            $count += [long](Get-ObjectProperty $stack 'count')
        }
    }
    return $count
}

function Get-PolicyDistanceBudget {
    param([Parameter(Mandatory)][object]$State)
    $policy = Get-ObjectProperty $State 'policy'
    $distance = Get-ObjectProperty $policy 'max_distance_blocks'
    if ($null -eq $distance -or [double]$distance -le 0) {
        throw 'state does not expose a positive navigation distance budget'
    }
    return [double]$distance
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
    $filter = [ordered]@{
        block_ids = @($Block)
        position_bounds = $Bounds
    }
    if ($null -ne $Faces) { $filter.faces = @($Faces) }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') -Filter $filter)
    if ($records.Count -eq 0) {
        # A bare return emits no pipeline object. `return $null` becomes a one-element null array
        # under Windows PowerShell when a caller wraps this helper in @(...).
        if ($AllowMissing) { return }
        throw "no visible $Block surface was delivered in the gate bounds"
    }
    foreach ($record in $records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'block') -cne $Block) {
            throw 'visible_surface filter returned an out-of-filter record'
        }
        if ($null -ne $Faces -and (Get-ObjectProperty $record 'face') -cnotin $Faces) {
            throw 'visible_surface faces filter returned an out-of-filter face'
        }
    }
    if ($ExcludePlayerFeetAbove) {
        $player = Get-ObjectProperty (Get-ObjectProperty $State 'world') 'position'
        $feetX = [Math]::Floor([double](Get-ObjectProperty $player 'x'))
        $feetY = [Math]::Floor([double](Get-ObjectProperty $player 'y'))
        $feetZ = [Math]::Floor([double](Get-ObjectProperty $player 'z'))
        $records = @($records | Where-Object {
                $position = Get-ObjectProperty $_ 'position'
                -not (
                    [int](Get-ObjectProperty $position 'x') -eq $feetX -and
                    [int](Get-ObjectProperty $position 'y') + 1 -eq $feetY -and
                    [int](Get-ObjectProperty $position 'z') -eq $feetZ)
            })
        if ($records.Count -eq 0) {
            throw 'all visible destination targets are occupied by the player'
        }
        $records = @($records | Sort-Object @{
                Expression = {
                    $position = Get-ObjectProperty $_ 'position'
                    $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                        [double](Get-ObjectProperty $player 'x')
                    $dy = ([double](Get-ObjectProperty $position 'y') + 1.5) -
                        [double](Get-ObjectProperty $player 'y')
                    $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                        [double](Get-ObjectProperty $player 'z')
                    $dx * $dx + $dy * $dy + $dz * $dz
                }
                Descending = $false
            }, @{
                Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_ 'position') }
                Descending = $false
            })
    } else {
        $records = @($records | Sort-Object {
                ConvertTo-CompactJson (Get-ObjectProperty $_ 'position')
            })
    }
    return @($records)
}

function Get-VisibleSurface {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [switch]$AllowMissing
    )
    $records = @(Get-VisibleSurfaceRecords -State $State -Block $Block -Bounds $Bounds `
        -Faces $Faces -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove `
        -AllowMissing:$AllowMissing)
    if ($records.Count -eq 0) { return $null }
    return $records[0]
}

function Get-TargetAboveSupport {
    param([Parameter(Mandatory)][object]$SupportPosition)
    [ordered]@{
        dimension = [string](Get-ObjectProperty $SupportPosition 'dimension')
        x = [int](Get-ObjectProperty $SupportPosition 'x')
        y = [int](Get-ObjectProperty $SupportPosition 'y') + 1
        z = [int](Get-ObjectProperty $SupportPosition 'z')
    }
}

function Get-BlockPositionKey {
    param([Parameter(Mandatory)][object]$Position)
    return ('{0}|{1}|{2}|{3}' -f
        [string](Get-ObjectProperty $Position 'dimension'),
        [int](Get-ObjectProperty $Position 'x'),
        [int](Get-ObjectProperty $Position 'y'),
        [int](Get-ObjectProperty $Position 'z'))
}

function Get-CurrentWorldRevision {
    param([Parameter(Mandatory)][object]$State)
    $revision = Get-ObjectProperty (Get-ObjectProperty $State 'world') 'world_revision'
    if ($revision -isnot [sbyte] -and $revision -isnot [byte] -and
        $revision -isnot [int16] -and $revision -isnot [uint16] -and
        $revision -isnot [int32] -and $revision -isnot [uint32] -and
        $revision -isnot [int64] -and $revision -isnot [uint64]) {
        throw 'agent_get_state did not announce an integer world revision'
    }
    return [long]$revision
}

function Wait-ForCurrentVisibleSurfaceRecords {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
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
            Add-GateEvent -Event 'visible_surfaces_revision_current' -Detail ([ordered]@{
                    block = $Block
                    world_revision = $worldRevision
                    polls = $poll
                    surface_count = $currentRecords.Count
                })
            return [pscustomobject]@{
                state = $state
                records = @($currentRecords)
                world_revision = $worldRevision
                polls = $poll
            }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "$Block surfaces did not reach the current world revision after $MaximumPolls polls"
}

