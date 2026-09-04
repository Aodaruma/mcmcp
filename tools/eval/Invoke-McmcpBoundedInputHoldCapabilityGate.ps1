[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [ValidateSet('attack', 'use')][string]$HoldInput = 'attack',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$holdArtifactDirectory = $ArtifactDirectory
$holdTokenPath = $TokenPath
$holdEndpoint = $Endpoint
$requestedHoldInput = $HoldInput
$holdLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $holdArtifactDirectory `
    -TokenPath $holdTokenPath -Endpoint $holdEndpoint -LibraryOnly
$ArtifactDirectory = $holdArtifactDirectory
$TokenPath = $holdTokenPath
$Endpoint = $holdEndpoint
$HoldInput = $requestedHoldInput
$LibraryOnly = $holdLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

$script:HoldTicks = 60L
$script:HoldItem = 'minecraft:wooden_pickaxe'
$script:HoldInput = $HoldInput
$script:HoldTarget = [ordered]@{
    dimension = 'minecraft:overworld'; x = 204; y = 200; z = 194
}
$script:HoldBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 204; min_y = 200; min_z = 194
    max_x = 204; max_y = 200; max_z = 194
}

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-bounded-input-hold-capability-gate'; version = '1'
        }
    }
}

function Assert-BoundedInputFixedFive {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) {
        throw 'bounded-input gate requires exactly one fixed-five verification event'
    }
    $tools = @((Get-ObjectProperty $events[0] 'tools'))
    if ($tools.Count -ne $script:AllowedTools.Count) {
        throw 'bounded-input gate fixed-five tool count mismatch'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "bounded-input gate fixed-five tool order mismatch at index $index"
        }
    }
}

function Assert-HoldPlayerState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$ExpectedPosition,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase
    )
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    foreach ($axis in @('x', 'y', 'z')) {
        if ([Math]::Abs([double](Get-ObjectProperty $position $axis) -
                [double](Get-ObjectProperty $ExpectedPosition $axis)) -gt 0.0001) {
            throw "$Phase changed stationary player $axis"
        }
    }
    if ([Math]::Abs([double](Get-ObjectProperty $world 'health') - $ExpectedHealth) -gt 0.0001) {
        throw "$Phase changed player health"
    }
    if ((Get-InventoryCount -State $State -Item $script:HoldItem) -ne 1) {
        throw "$Phase did not preserve the fixture wooden pickaxe"
    }
}

function Get-OnlyHoldTargetSurface {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') `
        -Filter ([ordered]@{
            block_ids = @('minecraft:obsidian')
            faces = @('south')
            position_bounds = $script:HoldBounds
        }))
    if ($records.Count -ne 1) {
        throw "bounded-input gate requires one exact visible obsidian south face; found=$($records.Count)"
    }
    $surface = $records[0]
    $state = Get-ObjectProperty $surface 'state'
    $position = Get-ObjectProperty $surface 'position'
    if ((ConvertTo-CompactJson $position) -cne (ConvertTo-CompactJson $script:HoldTarget) -or
        (Get-ObjectProperty $surface 'face') -cne 'south' -or
        (Get-ObjectProperty $surface 'block') -cne 'minecraft:obsidian' -or
        (Get-ObjectProperty $state 'block') -cne 'minecraft:obsidian') {
        throw 'bounded-input target surface did not match the fixture contract'
    }
    return $surface
}

function New-BoundedInputHoldRequest {
    param([Parameter(Mandatory)][object]$Surface)
    $target = Get-ObjectProperty $Surface 'position'
    $state = Get-ObjectProperty $Surface 'state'
    $node = [ordered]@{
        id = "hold_$($script:HoldInput)"
        op = 'hold_bounded_inputs'
        inputs = @($script:HoldInput)
        duration_ticks = $script:HoldTicks
        target_guard = [ordered]@{
            target = $target
            face = Get-ObjectProperty $Surface 'face'
            expected_state = $state
        }
        selected_item = $script:HoldItem
    }
    if (-not [object]::ReferenceEquals($target, $node.target_guard.target) -or
        -not [object]::ReferenceEquals($state, $node.target_guard.expected_state)) {
        throw 'bounded-input builder changed delivery-backed target evidence'
    }
    $capability = if ($script:HoldInput -ceq 'attack') { 'block_break' } else { 'item_use' }
    $maxInteractions = if ($script:HoldInput -ceq 'use') { 1 } else { 0 }
    $maxBlocksBroken = if ($script:HoldInput -ceq 'attack') { 1 } else { 0 }
    New-ActionRequest -Name "capability_gate_bounded_input_$($script:HoldInput)_hold" `
        -Capabilities @($capability) -Body @($node) -Budget ([ordered]@{
            max_duration_ms = 3000; max_ticks = $script:HoldTicks
            max_distance_blocks = 0; max_camera_degrees = 0
            max_interactions = $maxInteractions
            max_blocks_broken = $maxBlocksBroken
            max_blocks_placed = 0
        })
}

function Assert-BoundedInputTerminal {
    param([Parameter(Mandatory)][object]$Terminal)
    $progress = Get-ObjectProperty $Terminal 'progress'
    $ticks = [long](Get-ObjectProperty $progress 'ticks')
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        $ticks -lt $script:HoldTicks -or $ticks -gt ($script:HoldTicks + 20)) {
        throw 'bounded-input terminal violated its stationary finite-hold contract'
    }
    $completed = @((Get-ObjectProperty $Terminal 'trace') | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq "hold_$($script:HoldInput)"
        })
    if ($completed.Count -ne 1) {
        throw 'bounded-input terminal omitted the hold node completion trace'
    }
    return [ordered]@{
        action_id = Get-ObjectProperty $Terminal 'action_id'
        duration_ticks = $script:HoldTicks
        recorded_ticks = $ticks
        target_unchanged = $true
    }
}

function Invoke-BoundedInputHoldGateCore {
    Assert-BoundedInputFixedFive
    $initial = Get-FreshState
    $initialWorld = Get-ObjectProperty $initial 'world'
    $initialPosition = Get-ObjectProperty $initialWorld 'position'
    $initialHealth = [double](Get-ObjectProperty $initialWorld 'health')
    $initialTick = [long](Get-ObjectProperty $initialWorld 'client_tick')
    Assert-HoldPlayerState -State $initial -ExpectedPosition $initialPosition `
        -ExpectedHealth $initialHealth -Phase 'initial'
    $surface = Get-OnlyHoldTargetSurface -State $initial
    $terminal = Invoke-ActionRequest -Request (New-BoundedInputHoldRequest -Surface $surface) `
        -WallTimeoutSeconds 30
    $terminalProof = Assert-BoundedInputTerminal -Terminal $terminal

    $final = Get-FreshState
    Assert-HoldPlayerState -State $final -ExpectedPosition $initialPosition `
        -ExpectedHealth $initialHealth -Phase 'final'
    $finalTick = [long](Get-ObjectProperty (Get-ObjectProperty $final 'world') 'client_tick')
    if ($finalTick - $initialTick -lt $script:HoldTicks) {
        throw 'bounded-input hold completed before its declared client-tick duration'
    }
    [void](Get-OnlyHoldTargetSurface -State $final)
    return [ordered]@{
        gate = 'phase9-bounded-input-hold'
        fixture_command = '/mcmcp_fixture phase5 bounded_input_hold'
        input = $script:HoldInput
        selected_item = $script:HoldItem
        target = $script:HoldTarget
        finite_hold = $terminalProof
        final_player_stationary = $true
        final_health_unchanged = $true
        final_target_state = 'minecraft:obsidian'
    }
}

function Write-BoundedInputHoldArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'phase9-bounded-input-hold'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools)
        normal_player_actions_only = $true
        duration_ticks = $script:HoldTicks
        input = $script:HoldInput
        public_input_release = $InputRelease
        result = $GateResult
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{
                type = $Failure.Exception.GetType().FullName
                message = $Failure.Exception.Message
            }
        }
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
}

function Invoke-McmcpBoundedInputHoldCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-BoundedInputHoldGateCore } catch { $primaryFailure = $_ } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-BoundedInputHoldArtifacts -GateResult $gateResult -InputRelease $release `
        -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return [ordered]@{ gate_result = $gateResult; input_release = $release }
}

if (-not $LibraryOnly) {
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    if ([string]::IsNullOrWhiteSpace($script:Bearer) -or
        $script:Bearer.Contains("`r") -or $script:Bearer.Contains("`n")) {
        throw 'MCP token file is empty or malformed'
    }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    Assert-FixedFiveToolSurface
    ConvertTo-Json (Invoke-McmcpBoundedInputHoldCapabilityGate) -Depth 100
}
