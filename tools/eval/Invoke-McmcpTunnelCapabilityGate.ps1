[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('straight16', 'straight160', 'branches', 'hazard')]
    [string]$FixtureMode,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$FixtureStatusPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tunnelArtifactDirectory = $ArtifactDirectory
$tunnelTokenPath = $TokenPath
$tunnelEndpoint = $Endpoint
$tunnelLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $tunnelArtifactDirectory `
    -TokenPath $tunnelTokenPath -Endpoint $tunnelEndpoint -LibraryOnly
$ArtifactDirectory = $tunnelArtifactDirectory
$TokenPath = $tunnelTokenPath
$Endpoint = $tunnelEndpoint
$LibraryOnly = $tunnelLibraryOnly

$script:TunnelFixtureMode = $FixtureMode
$script:TunnelFixtureBinding = $null
$script:TunnelTerminalWorldSessionId = $null
$script:TunnelEntrance = [ordered]@{
    dimension = 'minecraft:overworld'; x = 258; y = 200; z = 256
}
$script:TunnelEntranceBounds = [ordered]@{
    dimension = 'minecraft:overworld'; min_x = 258; min_y = 200; min_z = 256
    max_x = 258; max_y = 200; max_z = 256
}
$script:TunnelExpectedStart = [ordered]@{ x = 257.5; y = 200.0; z = 256.5 }
$script:TunnelCases = [ordered]@{
    straight16 = [ordered]@{
        length = 16; pattern = 'straight'; branch_length = 0; branch_spacing = 0
        duration = 565000; ticks = 11300; distance = 25.5; camera = 8680
        breaks = 32; cells = 16; moves = 16; wall_timeout = 1800; terminal = 'succeeded'
    }
    straight160 = [ordered]@{
        length = 160; pattern = 'straight'; branch_length = 0; branch_spacing = 0
        duration = 5605000; ticks = 112100; distance = 241.5; camera = 83560
        breaks = 320; cells = 160; moves = 160; wall_timeout = 7200; terminal = 'succeeded'
    }
    branches = [ordered]@{
        length = 16; pattern = 'branches'; branch_length = 3; branch_spacing = 4
        duration = 1525000; ticks = 30500; distance = 97.5; camera = 29800
        breaks = 80; cells = 40; moves = 64; wall_timeout = 2400; terminal = 'succeeded'
    }
    hazard = [ordered]@{
        length = 16; pattern = 'straight'; branch_length = 0; branch_spacing = 0
        duration = 565000; ticks = 11300; distance = 25.5; camera = 8680
        breaks = 32; cells = 3; moves = 3; confirmed_breaks = 8
        wall_timeout = 1800; terminal = 'failed'
    }
}

function Assert-TunnelFixedFive {
    $proof = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($proof.Count -ne 1 -or @((Get-ObjectProperty $proof[0] 'tools')).Count -ne 5) {
        throw 'tunnel gate requires one fixed-five Tool surface proof'
    }
}

function Read-TunnelPreRunStatus {
    if (-not (Test-Path -LiteralPath $FixtureStatusPath -PathType Leaf) -or
        (Get-Item -LiteralPath $FixtureStatusPath).Length -gt 65536) {
        throw 'tunnel pre-run status artifact is missing or exceeds its fixed size limit'
    }
    $bytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($FixtureStatusPath))
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 65536) {
        throw 'tunnel pre-run status artifact has an invalid size'
    }
    try {
        $status = [Text.UTF8Encoding]::new($false, $true).GetString($bytes) |
            ConvertFrom-Json -Depth 40 -NoEnumerate
    } catch { throw 'tunnel pre-run status artifact is not valid UTF-8 JSON' }
    if ($status -isnot [System.Management.Automation.PSCustomObject]) {
        throw 'tunnel pre-run status artifact must contain one JSON object'
    }
    $schema = $status.PSObject.Properties['schema']
    $kind = $status.PSObject.Properties['kind']
    $mode = $status.PSObject.Properties['mode']
    $setup = $status.PSObject.Properties['setupId']
    $session = $status.PSObject.Properties['worldSessionId']
    $baselineBlocks = $status.PSObject.Properties['baselineBlocks']
    $fixtureTickMutation = $status.PSObject.Properties['fixtureTickMutation']
    $uuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    if ($null -eq $schema -or $schema.Value -isnot [string] -or $schema.Value -cne 'mcmcp_fixture_tunnel_v1' -or
        $null -eq $kind -or $kind.Value -isnot [string] -or $kind.Value -cne 'status' -or
        $null -eq $mode -or $mode.Value -isnot [string] -or
        $mode.Value -cne ('tunnel_' + $script:TunnelFixtureMode) -or
        $null -eq $setup -or $setup.Value -isnot [string] -or $setup.Value -cnotmatch $uuid -or
        $null -eq $session -or $session.Value -isnot [string] -or $session.Value -cnotmatch $uuid -or
        $null -eq $baselineBlocks -or $baselineBlocks.Value -isnot [long] -or
        $baselineBlocks.Value -ne 22168 -or
        $null -eq $fixtureTickMutation -or $fixtureTickMutation.Value -isnot [string] -or
        $fixtureTickMutation.Value -cne 'none') {
        throw 'tunnel pre-run status identity does not match the selected fixture'
    }
    $setupId = $setup.Value
    $sessionId = $session.Value
    foreach ($flag in @('ready', 'baselineMatches', 'inventoryMatches',
            'startPoseMatches', 'playerBaselineMatches', 'resourcesActive')) {
        $property = $status.PSObject.Properties[$flag]
        if ($null -eq $property -or $property.Value -isnot [bool] -or -not $property.Value) {
            throw 'tunnel pre-run status does not prove the untouched ready baseline'
        }
    }
    $entities = $status.PSObject.Properties['entities']
    if ($null -eq $entities -or $entities.Value -isnot [long] -or $entities.Value -ne 0) {
        throw 'tunnel pre-run status contains entities'
    }
    $raysPerTick = $status.PSObject.Properties['raysPerTick']
    if ($null -eq $raysPerTick -or $raysPerTick.Value -isnot [long] -or $raysPerTick.Value -ne 512) {
        throw 'tunnel pre-run status does not prove the fixed observation budget'
    }
    $forcedChunks = $status.PSObject.Properties['forcedChunks']
    if ($null -eq $forcedChunks -or $forcedChunks.Value -isnot [long] -or $forcedChunks.Value -ne 22) {
        throw 'tunnel pre-run status does not prove the fixed loaded chunk coverage'
    }
    return [ordered]@{
        setup_id = $setupId
        world_session_id = $sessionId
        fixture_mode = $script:TunnelFixtureMode
        status_sha256 = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    }
}

function Get-TunnelSessionState {
    # The existing public recipe-query basis exposes the actual client world session.
    # Recipe results are neither used as fixture facts nor recorded in the binding.
    $state = Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{
            query = [ordered]@{ kind = 'result_item'; item = 'minecraft:stick' }
            max_results = 1
        })
    Assert-ReadyState -State $state -Phase 'tunnel world-session boundary'
    $basis = Get-ObjectProperty (Get-ObjectProperty $state 'recipe_query') 'basis'
    $sessionId = Get-ObjectProperty $basis 'world_session_id'
    if ($null -eq $script:TunnelFixtureBinding -or $sessionId -isnot [string] -or
        $sessionId -cne $script:TunnelFixtureBinding.world_session_id) {
        throw 'tunnel public world session does not match the pre-run fixture status'
    }
    return $state
}

function Assert-TunnelInitialState([object]$State) {
    Assert-ReadyState -State $State -Phase 'tunnel initial state'
    $inventory = @(Get-ObjectProperty $State 'inventory')
    if ($inventory.Count -ne 1 -or
        (Get-ObjectProperty $inventory[0] 'item') -cne 'minecraft:netherite_pickaxe' -or
        (Get-ObjectProperty $inventory[0] 'count') -ne 1) {
        throw 'tunnel fixture requires only one netherite pickaxe in public inventory'
    }
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    foreach ($axis in @('x', 'y', 'z')) {
        if ([Math]::Abs([double](Get-ObjectProperty $position $axis) -
                [double]$script:TunnelExpectedStart[$axis]) -gt 0.0001) {
            throw "tunnel fixture start position mismatch: $axis"
        }
    }
    if ([double](Get-ObjectProperty $world 'health') -ne 20.0 -or
        [int](Get-ObjectProperty $world 'hunger') -ne 20) {
        throw 'tunnel fixture requires full health and hunger'
    }
}

function Get-TunnelEntranceSurface([object]$State) {
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') `
        -Filter ([ordered]@{
            block_ids = @('minecraft:stone'); faces = @('west')
            position_bounds = $script:TunnelEntranceBounds
        }))
    if ($records.Count -ne 1) { throw 'tunnel fixture entrance surface was not uniquely observed' }
    $surface = $records[0]
    if ((ConvertTo-CompactJson (Get-ObjectProperty $surface 'position')) -cne
        (ConvertTo-CompactJson $script:TunnelEntrance) -or
        (Get-ObjectProperty $surface 'face') -cne 'west' -or
        (Get-ObjectProperty $surface 'block') -cne 'minecraft:stone') {
        throw 'tunnel entrance observation changed'
    }
    return $surface
}

function New-TunnelActionRequest([object]$Surface) {
    $case = $script:TunnelCases[$script:TunnelFixtureMode]
    $node = [ordered]@{
        id = 'mine_fixture'; op = 'excavate_tunnel'
        target = Get-ObjectProperty $Surface 'position'
        face = 'west'; expected_state = Get-ObjectProperty $Surface 'state'
        tool_item = 'minecraft:netherite_pickaxe'; length_blocks = $case.length
    }
    if ($case.pattern -ceq 'branches') {
        $node.pattern = 'branches'
        $node.branch_length_blocks = $case.branch_length
        $node.branch_spacing_blocks = $case.branch_spacing
    }
    New-ActionRequest -Name ('tunnel_fixture_' + $script:TunnelFixtureMode) `
        -Capabilities @('movement', 'camera', 'block_break') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = $case.duration; max_ticks = $case.ticks
            max_distance_blocks = $case.distance; max_camera_degrees = $case.camera
            max_interactions = 0; max_blocks_broken = $case.breaks; max_blocks_placed = 0
        })
}

function Wait-TunnelActionTerminal([string]$ActionId, [int]$WallTimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = Invoke-GateTool -Tool 'agent_get_action' -Arguments ([ordered]@{
                action_id = $ActionId; wait_timeout_ms = 25000
            })
        if ((Get-ObjectProperty $snapshot 'action_id') -cne $ActionId) {
            throw 'tunnel Action id changed while polling'
        }
        if ((Get-ObjectProperty $snapshot 'state') -cin $script:TerminalStates) {
            return $snapshot
        }
    } while ($watch.Elapsed.TotalSeconds -lt $WallTimeoutSeconds)
    throw 'tunnel Action exceeded its fixed wall timeout'
}

function Invoke-TunnelAction([Collections.IDictionary]$Request) {
    [void](Get-TunnelSessionState)
    $receipt = Invoke-GateTool -Tool 'agent_start_action' -Arguments $Request
    $actionId = [string](Get-ObjectProperty $receipt 'action_id')
    if ($actionId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' -or
        (Get-ObjectProperty $receipt 'state') -cne 'queued') {
        throw 'tunnel Action start receipt is invalid'
    }
    $script:ActiveActionId = $actionId
    Add-GateEvent -Event 'action_accepted' -Detail ([ordered]@{
            action_id = $actionId; fixture_mode = $script:TunnelFixtureMode
            request = $Request
        })
    $terminal = Wait-TunnelActionTerminal -ActionId $actionId `
        -WallTimeoutSeconds $script:TunnelCases[$script:TunnelFixtureMode].wall_timeout
    $terminalState = Get-TunnelSessionState
    $terminalAction = Get-ObjectProperty $terminalState 'action'
    if ((Get-ObjectProperty $terminalAction 'action_id') -cne $actionId -or
        (Get-ObjectProperty $terminalAction 'state') -cne (Get-ObjectProperty $terminal 'state')) {
        throw 'tunnel terminal does not belong to the current public world session'
    }
    $script:TunnelTerminalWorldSessionId = $script:TunnelFixtureBinding.world_session_id
    $script:ActiveActionId = $null
    Add-ActionTerminalEvent -ActionId $actionId -Terminal $terminal
    return $terminal
}

function Assert-TunnelTerminal([object]$Terminal) {
    $case = $script:TunnelCases[$script:TunnelFixtureMode]
    $state = [string](Get-ObjectProperty $Terminal 'state')
    if ($state -cne $case.terminal) { throw "unexpected tunnel terminal: $state" }
    $progress = Get-ObjectProperty $Terminal 'progress'
    $expectedBreaks = if ($script:TunnelFixtureMode -ceq 'hazard') {
        $case.confirmed_breaks
    } else { $case.breaks }
    $distance = Get-ObjectProperty $progress 'distance_travelled'
    $camera = Get-ObjectProperty $progress 'camera_degrees'
    $ticks = Get-ObjectProperty $progress 'ticks'
    if ((Get-ObjectProperty $progress 'phase') -cne 'finished' -or
        $null -ne (Get-ObjectProperty $progress 'current_node_id') -or
        (Get-ObjectProperty $progress 'executed_nodes') -ne
            $(if ($state -ceq 'succeeded') { 1 } else { 0 }) -or
        (Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        $distance -isnot [ValueType] -or -not [double]::IsFinite([double]$distance) -or
        [double]$distance -lt 0 -or [double]$distance -gt $case.distance -or
        $camera -isnot [ValueType] -or -not [double]::IsFinite([double]$camera) -or
        [double]$camera -lt 0 -or [double]$camera -gt $case.camera -or
        $ticks -isnot [ValueType] -or [long]$ticks -lt 0 -or [long]$ticks -gt $case.ticks -or
        (Get-ObjectProperty $progress 'interactions') -ne 0 -or
        (Get-ObjectProperty $progress 'blocks_broken') -ne $expectedBreaks -or
        (Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        throw 'tunnel terminal counters do not match the fixed plan'
    }
    if ($script:TunnelFixtureMode -ceq 'hazard') {
        $failure = Get-ObjectProperty $Terminal 'failure'
        if ((Get-ObjectProperty $failure 'code') -cne 'SAFETY_INTERRUPTED' -or
            @((Get-ObjectProperty $failure 'evidence')) -cnotcontains 'tunnel_unsafe_floor') {
            throw 'hazard fixture did not stop at the unsafe floor'
        }
    } elseif ($null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'successful tunnel unexpectedly reported a failure'
    }
    $aggregate = Get-ObjectProperty $Terminal 'effect_aggregate'
    $expectedRetained = [Math]::Min($expectedBreaks, 64)
    if ((Get-ObjectProperty $aggregate 'total_effects') -ne $expectedBreaks -or
        (Get-ObjectProperty $aggregate 'retained_effects') -ne $expectedRetained -or
        (Get-ObjectProperty $aggregate 'confirmed_effects') -ne $expectedBreaks -or
        (Get-ObjectProperty $aggregate 'qualified_effects') -ne 0 -or
        (Get-ObjectProperty $aggregate 'unknown_effects') -ne 0 -or
        (Get-ObjectProperty $aggregate 'dispatched_attacks') -ne 0 -or
        (Get-ObjectProperty $aggregate 'confirmed_attacks') -ne 0 -or
        (Get-ObjectProperty $aggregate 'unknown_attacks') -ne 0) {
        throw 'tunnel terminal effect aggregate does not match confirmed break ACKs'
    }
    $effects = @(Get-ObjectProperty $Terminal 'effects')
    $subjects = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $effects.Count; $index++) {
        $effect = $effects[$index]
        $before = Get-ObjectProperty $effect 'observed_before'
        $after = Get-ObjectProperty $effect 'observed_after'
        $subject = [string](Get-ObjectProperty $effect 'subject')
        if ((Get-ObjectProperty $effect 'seq') -ne ($expectedBreaks - $expectedRetained + $index + 1) -or
            (Get-ObjectProperty $effect 'node_id') -cne 'mine_fixture' -or
            (Get-ObjectProperty $effect 'kind') -cne 'block_break' -or
            (Get-ObjectProperty $effect 'verification') -cne 'confirmed' -or
            -not $subject.StartsWith('block:minecraft:overworld:', [StringComparison]::Ordinal) -or
            -not $subjects.Add($subject) -or
            (Get-ObjectProperty $before 'block') -cne 'minecraft:stone' -or
            (Get-ObjectProperty $before 'affected_blocks') -ne 1 -or
            (Get-ObjectProperty $after 'block') -cne 'minecraft:air' -or
            (Get-ObjectProperty $after 'affected_blocks') -ne 1 -or
            [long](Get-ObjectProperty $effect 'client_tick') -lt 0 -or
            [long](Get-ObjectProperty $effect 'world_revision') -lt 0) {
            throw 'tunnel terminal retained effect is not a unique confirmed block break'
        }
    }
    if ($effects.Count -ne $expectedRetained) {
        throw 'tunnel terminal retained effect count changed'
    }
    $partial = Get-ObjectProperty $Terminal 'partial'
    $isHazard = $script:TunnelFixtureMode -ceq 'hazard'
    if ((Get-ObjectProperty $partial 'has_confirmed_effects') -isnot [bool] -or
        -not [bool](Get-ObjectProperty $partial 'has_confirmed_effects') -or
        (Get-ObjectProperty $partial 'interrupted_node_id') -cne
            $(if ($isHazard) { 'mine_fixture' } else { $null }) -or
        (Get-ObjectProperty $partial 'remaining_node_upper_bound') -ne
            $(if ($isHazard) { 1 } else { 0 }) -or
        (Get-ObjectProperty $partial 'resume_requires_reobservation') -isnot [bool] -or
        [bool](Get-ObjectProperty $partial 'resume_requires_reobservation') -ne $isHazard) {
        throw 'tunnel terminal partial-result contract changed'
    }
    $summary = "tunnel_cells=$($case.cells),moves=$($case.moves),server_confirmed_breaks=$expectedBreaks,drop_collection=not_asserted"
    $evidence = @((Get-ObjectProperty $Terminal 'trace') | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq $summary
        })
    if ($evidence.Count -ne 1) { throw 'tunnel terminal omitted its exact bounded summary' }
    return [ordered]@{
        action_id = Get-ObjectProperty $Terminal 'action_id'; state = $state
        world_session_id = $script:TunnelTerminalWorldSessionId
        confirmed_breaks = $expectedBreaks; completed_cells = $case.cells
        completed_moves = $case.moves; bounded_summary = $true
    }
}

function Invoke-McmcpTunnelCapabilityGate {
    $script:ActiveActionId = $null
    $script:TunnelFixtureBinding = $null
    $script:TunnelTerminalWorldSessionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $result = $null
    $release = $null
    try {
        Assert-TunnelFixedFive
        $script:TunnelFixtureBinding = Read-TunnelPreRunStatus
        $initial = Get-TunnelSessionState
        Assert-TunnelInitialState $initial
        Add-GateEvent -Event 'fixture_status_bound' -Detail $script:TunnelFixtureBinding
        $surface = Get-TunnelEntranceSurface $initial
        $terminal = Invoke-TunnelAction (New-TunnelActionRequest $surface)
        $result = Assert-TunnelTerminal $terminal
    } catch { $primaryFailure = $_ } finally {
        try {
            $release = Invoke-GateCleanup
            if ($null -ne $script:TunnelFixtureBinding) {
                [void](Get-TunnelSessionState)
                $release.world_session_id = $script:TunnelFixtureBinding.world_session_id
            }
        } catch { $cleanupFailure = $_ }
    }
    $failure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1; gate = 'tunnel'; fixture_mode = $script:TunnelFixtureMode
        fixture_setup_id = $(if ($null -ne $script:TunnelFixtureBinding) { $script:TunnelFixtureBinding.setup_id } else { $null })
        fixture_status_sha256 = $(if ($null -ne $script:TunnelFixtureBinding) { $script:TunnelFixtureBinding.status_sha256 } else { $null })
        world_session_id = $(if ($null -ne $script:TunnelFixtureBinding) { $script:TunnelFixtureBinding.world_session_id } else { $null })
        status = $(if ($null -eq $failure) { 'passed' } else { 'failed' })
        normal_player_actions_only = $true; result = $result; public_input_release = $release
        fixture_oracle_required = $true
        failure = $(if ($null -eq $failure) { $null } else { $failure.Exception.Message })
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return [ordered]@{ gate_result = $result; input_release = $release }
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
    Assert-FixedFiveToolSurface
    ConvertTo-Json (Invoke-McmcpTunnelCapabilityGate) -Depth 100
}
