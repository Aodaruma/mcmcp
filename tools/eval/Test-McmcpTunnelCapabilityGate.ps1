[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpTunnelCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-tunnel-gate-' + [Guid]::NewGuid().ToString('N'))
$fixtureStatusPath = Join-Path $artifactDirectory 'pre-run-status.json'
$script:FixtureWorldSessionId = '550e8400-e29b-41d4-a716-446655440080'
. $runner -FixtureMode straight16 -ArtifactDirectory $artifactDirectory `
    -FixtureStatusPath $fixtureStatusPath `
    -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "tunnel gate mock failed: $Message" }
}

function Assert-Rejected([scriptblock]$Operation, [string]$Message) {
    $rejected = $false
    try { [void](& $Operation) } catch { $rejected = $true }
    Assert-True $rejected $Message
}

function Set-MockField([object]$Object, [string]$Path, [object]$Value, [switch]$Remove) {
    $parts = $Path.Split('.')
    $owner = $Object
    for ($index = 0; $index -lt $parts.Count - 1; $index++) {
        $part = $parts[$index]
        $owner = if ($owner -is [array]) { $owner[[int]$part] } else { $owner.PSObject.Properties[$part].Value }
    }
    $key = $parts[-1]
    if ($Remove) { $owner.PSObject.Properties.Remove($key) }
    else { $owner.PSObject.Properties[$key].Value = $Value }
}

$script:ToolTransport = {
    param($Tool, $Arguments)
    Write-Output -NoEnumerate ([object[]]@([pscustomobject]@{ schema_version = 1L }))
}
Assert-Rejected {
    Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{})
} 'common Tool boundary accepted a root singleton array as structured content'
$script:ToolTransport = $null

function New-MockTunnelState {
    param([Parameter(Mandatory)][int]$Stage)
    $value = [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 100L + $Stage
            world_revision = 20L
            position = [pscustomobject]@{ x = 257.5; y = 200.0; z = 256.5 }
            yaw = -90.0; pitch = 0.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @([pscustomobject]@{
                item = 'minecraft:netherite_pickaxe'; count = 1
            })
        standard_potions = @()
        recipe_query = [pscustomobject]@{
            basis = [pscustomobject]@{ world_session_id = $script:FixtureWorldSessionId }
        }
        policy = [pscustomobject]@{ max_distance_blocks = 512 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0000000000000001' }
        action = $(if ($null -ne $script:Submitted) {
                [pscustomobject]@{
                    action_id = '550e8400-e29b-41d4-a716-446655440091'; state = 'succeeded'
                }
            } else { $null })
    }
    ConvertFrom-Json -InputObject (ConvertTo-Json $value -Depth 40) -Depth 40 -NoEnumerate
}

function New-MockTunnelSurface {
    [pscustomobject]@{
        kind = 'visible_surface'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 258; y = 200; z = 256
        }
        face = 'west'; block = 'minecraft:stone'
        state = [pscustomobject]@{
            block = 'minecraft:stone'; properties = [pscustomobject]@{}
        }
        placement_item = 'minecraft:stone'; placement_state_ref = $null
        shape_class = 'full_cube'
        eye_origin = [pscustomobject]@{ x = 257.5; y = 201.62; z = 256.5 }
        observed_tick = 100L; world_revision = 20L; provenance = 'visual'
    }
}

function New-MockTunnelTerminal {
    param([Parameter(Mandatory)][string]$Mode)
    $case = $script:TunnelCases[$Mode]
    $isHazard = $Mode -ceq 'hazard'
    $breaks = if ($isHazard) { $case.confirmed_breaks } else { $case.breaks }
    $retained = [Math]::Min($breaks, 64)
    $summary = "tunnel_cells=$($case.cells),moves=$($case.moves),server_confirmed_breaks=$breaks,drop_collection=not_asserted"
    $value = [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-446655440091'
        state = $(if ($isHazard) { 'failed' } else { 'succeeded' })
        failure = $(if ($isHazard) {
                [pscustomobject]@{
                    code = 'SAFETY_INTERRUPTED'; message = 'unsafe floor'; recoverable = $true
                    evidence = @('tunnel_unsafe_floor')
                }
            } else { $null })
        progress = [pscustomobject]@{
            phase = 'finished'; current_node_id = $null
            executed_nodes = $(if ($isHazard) { 0 } else { 1 })
            total_node_upper_bound = 1; distance_travelled = 0
            camera_degrees = 0; interactions = 0; blocks_broken = $breaks
            blocks_placed = 0; ticks = 1
        }
        trace = @(
            [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = 'mine_fixture' },
            [pscustomobject]@{ tick = 1; event = 'NODE_EVIDENCE'; detail = $summary },
            [pscustomobject]@{
                tick = 1; event = $(if ($isHazard) { 'FAILED' } else { 'SUCCEEDED' })
                detail = $(if ($isHazard) { 'SAFETY_INTERRUPTED' } else { 'succeeded' })
            }
        )
        effects = @((($breaks - $retained + 1)..$breaks) | ForEach-Object {
                [pscustomobject]@{
                    seq = $_; node_id = 'mine_fixture'; kind = 'block_break'
                    subject = "block:minecraft:overworld:$($_),200,256"
                    observed_before = [pscustomobject]@{
                        block = 'minecraft:stone'; properties = [pscustomobject]@{}
                        affected_blocks = 1
                    }
                    observed_after = [pscustomobject]@{
                        block = 'minecraft:air'; properties = [pscustomobject]@{}
                        affected_blocks = 1
                    }
                    verification = 'confirmed'; client_tick = 100L + $_; world_revision = 20L + $_
                }
            })
        effect_aggregate = [pscustomobject]@{
            total_effects = $breaks; retained_effects = $retained
            confirmed_effects = $breaks; qualified_effects = 0; unknown_effects = 0
            dispatched_attacks = 0; confirmed_attacks = 0; unknown_attacks = 0
        }
        partial = [pscustomobject]@{
            has_confirmed_effects = ($breaks -gt 0)
            interrupted_node_id = $(if ($isHazard) { 'mine_fixture' } else { $null })
            remaining_node_upper_bound = $(if ($isHazard) { 1 } else { 0 })
            resume_requires_reobservation = $isHazard
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
    ConvertFrom-Json -InputObject (ConvertTo-Json $value -Depth 40) -Depth 40 -NoEnumerate
}

$script:TunnelTerminalWorldSessionId = $script:FixtureWorldSessionId
$mockActionId = '550e8400-e29b-41d4-a716-446655440091'
$surface = New-MockTunnelSurface
foreach ($mode in @('straight16', 'straight160', 'branches', 'hazard')) {
    $script:TunnelFixtureMode = $mode
    $case = $script:TunnelCases[$mode]
    $request = New-TunnelActionRequest -Surface $surface
    $node = $request.program.body[0]
    Assert-True ($node.op -ceq 'excavate_tunnel' -and
        $node.length_blocks -eq $case.length -and
        $node.target.x -eq 258 -and $node.face -ceq 'west' -and
        $node.tool_item -ceq 'minecraft:netherite_pickaxe') `
        "$mode builder changed the fixed tunnel target"
    Assert-True ($request.budget.max_duration_ms -eq $case.duration -and
        $request.budget.max_ticks -eq $case.ticks -and
        $request.budget.max_distance_blocks -eq $case.distance -and
        $request.budget.max_camera_degrees -eq $case.camera -and
        $request.budget.max_blocks_broken -eq $case.breaks) `
        "$mode builder changed an exact static budget"
    if ($mode -ceq 'branches') {
        Assert-True ($node.pattern -ceq 'branches' -and
            $node.branch_length_blocks -eq 3 -and
            $node.branch_spacing_blocks -eq 4) `
            'branch builder omitted the fixed branch geometry'
    } else {
        Assert-True (-not $node.Contains('pattern') -and
            -not $node.Contains('branch_length_blocks') -and
            -not $node.Contains('branch_spacing_blocks')) `
            "$mode unexpectedly enabled branch excavation"
    }
    $terminalResult = Assert-TunnelTerminal (New-MockTunnelTerminal -Mode $mode) -ActionId $mockActionId
    Assert-True ($terminalResult.completed_cells -eq $case.cells -and
        $terminalResult.completed_moves -eq $case.moves -and
        [bool]$terminalResult.bounded_summary) `
        "$mode terminal oracle did not preserve its bounded summary"
}

$script:TunnelFixtureMode = 'straight16'
$badAggregate = New-MockTunnelTerminal -Mode straight16
$badAggregate.effect_aggregate.confirmed_effects = 31
$rejected = $false
try { [void](Assert-TunnelTerminal $badAggregate -ActionId $mockActionId) } catch { $rejected = $true }
Assert-True $rejected 'gate accepted block counters without the matching ACK aggregate'

$badBudget = New-MockTunnelTerminal -Mode straight16
$badBudget.progress.interactions = 1
$rejected = $false
try { [void](Assert-TunnelTerminal $badBudget -ActionId $mockActionId) } catch { $rejected = $true }
Assert-True $rejected 'gate accepted a terminal outside its zero-interaction budget'

# Every field used to decide the terminal result must exist with its original JSON type.
$terminalFields = @{
    schema_version = 'long'; action_id = 'string'; state = 'string'; progress = 'object'
    failure = 'object'; effect_aggregate = 'object'; effects = 'array'; partial = 'object'; trace = 'array'
    'progress.phase' = 'string'; 'progress.current_node_id' = 'string'
    'progress.distance_travelled' = 'number'; 'progress.camera_degrees' = 'number'
    'partial.has_confirmed_effects' = 'bool'; 'partial.interrupted_node_id' = 'string'
    'partial.remaining_node_upper_bound' = 'long'; 'partial.resume_requires_reobservation' = 'bool'
    'effects.0.seq' = 'long'; 'effects.0.client_tick' = 'long'; 'effects.0.world_revision' = 'long'
    'effects.0.node_id' = 'string'; 'effects.0.kind' = 'string'; 'effects.0.subject' = 'string'
    'effects.0.verification' = 'string'; 'effects.0.observed_before' = 'object'
    'effects.0.observed_after' = 'object'; 'effects.0.observed_before.block' = 'string'
    'effects.0.observed_before.affected_blocks' = 'long'; 'effects.0.observed_after.block' = 'string'
    'effects.0.observed_after.affected_blocks' = 'long'; 'trace.0.tick' = 'long'
    'trace.0.event' = 'string'; 'trace.0.detail' = 'string'
    'failure.code' = 'string'; 'failure.message' = 'string'
    'failure.recoverable' = 'bool'; 'failure.evidence' = 'array'
}
foreach ($field in @('executed_nodes', 'total_node_upper_bound', 'interactions', 'blocks_broken', 'blocks_placed', 'ticks')) {
    $terminalFields['progress.' + $field] = 'long'
}
foreach ($field in @('total_effects', 'retained_effects', 'confirmed_effects', 'qualified_effects',
        'unknown_effects', 'dispatched_attacks', 'confirmed_attacks', 'unknown_attacks')) {
    $terminalFields['effect_aggregate.' + $field] = 'long'
}
$script:TunnelFixtureMode = 'hazard'
foreach ($path in $terminalFields.Keys) {
    $invalid = New-MockTunnelTerminal hazard
    Set-MockField $invalid $path $null -Remove
    Assert-Rejected { Assert-TunnelTerminal $invalid -ActionId $mockActionId } "terminal accepted missing $path"
    $wrongType = switch ($terminalFields[$path]) {
        long { '0' }; number { $true }; string { ,@('value') }
        bool { 'true' }; object { ,@([pscustomobject]@{}) }; array { [pscustomobject]@{} }
    }
    $invalid = New-MockTunnelTerminal hazard
    Set-MockField $invalid $path $wrongType
    Assert-Rejected { Assert-TunnelTerminal $invalid -ActionId $mockActionId } "terminal coerced $path"
}
foreach ($badNumber in @([double]::NaN, [double]::PositiveInfinity, [double]::NegativeInfinity, '0', @(0))) {
    $invalid = New-MockTunnelTerminal hazard
    $invalid.progress.distance_travelled = $badNumber
    Assert-Rejected { Assert-TunnelTerminal $invalid -ActionId $mockActionId } 'terminal accepted a non-finite or non-scalar distance'
}
$invalid = New-MockTunnelTerminal hazard
$invalid.action_id = '550e8400-e29b-41d4-a716-446655440092'
Assert-Rejected { Assert-TunnelTerminal $invalid -ActionId $mockActionId } 'terminal accepted another Action UUID'
$invalid = New-MockTunnelTerminal hazard
$invalid.failure.evidence = ,@('tunnel_unsafe_floor')
Assert-Rejected { Assert-TunnelTerminal $invalid -ActionId $mockActionId } 'terminal accepted nested failure evidence'

$script:TunnelFixtureMode = 'straight16'
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:Submitted = $null
$script:ChangeSessionOnTerminal = $false
$script:ResponseMutation = $null
$script:QueryCount = 0
$script:PollCount = 0
$script:CancelCount = 0
$script:CleanupRecovery = $false
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    $response = switch ($Tool) {
        'agent_get_state' {
            if ($Arguments.Contains('query')) {
                $script:QueryCount++
                Assert-True ($Arguments.query.kind -ceq 'result_item' -and
                    $Arguments.query.item -ceq 'minecraft:stick' -and $Arguments.max_results -eq 1) `
                    'session probe changed its bounded read-only public query'
            }
            $state = New-MockTunnelState -Stage 0
            if ($script:CleanupRecovery -and $script:PollCount -ge 3) { $state.action.state = 'cancelled' }
            $state
        }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1; frame_id = 'obs-0000000000000001'
                frame_completed_tick = 100L; visible_entities_truncated = $false
                records = @((New-MockTunnelSurface)); next_cursor = $null
                sampling_coverage = 1
            }
        }
        'agent_start_action' {
            if ($null -ne $script:Submitted) { throw 'mock received an extra Action' }
            $script:Submitted = $Arguments
            [pscustomobject]@{
                schema_version = 1L
                action_id = '550e8400-e29b-41d4-a716-446655440091'; state = 'queued'
                accepted_at = '2026-09-07T00:00:00.123456789Z'
            }
        }
        'agent_get_action' {
            $script:PollCount++
            if ($script:CleanupRecovery -and $script:PollCount -eq 1) { throw 'simulated polling failure' }
            if ($script:ChangeSessionOnTerminal) {
                $script:FixtureWorldSessionId = '550e8400-e29b-41d4-a716-446655440082'
            }
            $terminal = New-MockTunnelTerminal -Mode 'straight16'
            if ($script:CleanupRecovery) {
                $terminal.state = if ($script:PollCount -eq 2) { 'running' } else { 'cancelled' }
            }
            $terminal
        }
        'agent_cancel_action' {
            $script:CancelCount++
            if (-not $script:CleanupRecovery) { throw 'mock should not cancel a successful tunnel' }
            [pscustomobject]@{
                schema_version = 1L; action_id = '550e8400-e29b-41d4-a716-446655440091'
                cancel_requested = $true; state_at_request = 'running'
            }
        }
        default { throw "unexpected tunnel mock tool: $Tool" }
    }
    if ($null -ne $script:ResponseMutation) { [void](& $script:ResponseMutation $Tool $Arguments $response) }
    return $response
}

try {
    [void][IO.Directory]::CreateDirectory($artifactDirectory)
    $preRunStatus = [ordered]@{
        schema = 'mcmcp_fixture_tunnel_v1'; kind = 'status'; mode = 'tunnel_straight16'
        setupId = '550e8400-e29b-41d4-a716-446655440090'
        worldSessionId = $script:FixtureWorldSessionId; baselineBlocks = 22168
        ready = $true; baselineMatches = $true; inventoryMatches = $true
        startPoseMatches = $true; playerBaselineMatches = $true; entities = 0
        resourcesActive = $true; raysPerTick = 512; forcedChunks = 22
        fixtureTickMutation = 'none'
    }
    function Write-MockStatus {
        [IO.File]::WriteAllText($fixtureStatusPath,
            (ConvertTo-Json $preRunStatus -Depth 20), [Text.UTF8Encoding]::new($false))
    }
    Write-MockStatus
    foreach ($badField in @('mode', 'ready', 'setupId', 'worldSessionId', 'resourcesActive', 'raysPerTick', 'forcedChunks')) {
        $original = $preRunStatus[$badField]
        $preRunStatus[$badField] = switch ($badField) {
            'mode' { 'tunnel_straight160' }
            'ready' { 'true' }
            'setupId' { '' }
            'worldSessionId' { 'not-a-session' }
            'resourcesActive' { $false }
            'raysPerTick' { 1 }
            'forcedChunks' { 21 }
        }
        Write-MockStatus
        $rejected = $false
        try { [void](Read-TunnelPreRunStatus) } catch { $rejected = $true }
        Assert-True $rejected "pre-run artifact accepted invalid $badField"
        $preRunStatus[$badField] = $original
    }
    $invalidStatusTypes = @(
        @{ field = 'ready'; value = @($true) },
        @{ field = 'resourcesActive'; value = @($true) },
        @{ field = 'raysPerTick'; value = '512' },
        @{ field = 'raysPerTick'; value = @(512) },
        @{ field = 'entities'; value = '0' },
        @{ field = 'entities'; value = @(0) },
        @{ field = 'baselineBlocks'; value = '22168' },
        @{ field = 'schema'; value = @('mcmcp_fixture_tunnel_v1') }
    )
    foreach ($invalid in $invalidStatusTypes) {
        $original = $preRunStatus[$invalid.field]
        $preRunStatus[$invalid.field] = $invalid.value
        Write-MockStatus
        $rejected = $false
        try { [void](Read-TunnelPreRunStatus) } catch { $rejected = $true }
        Assert-True $rejected "pre-run artifact accepted non-scalar or incorrectly typed $($invalid.field)"
        $preRunStatus[$invalid.field] = $original
    }
    foreach ($invalidChunks in @($null, '22', @(22))) {
        $preRunStatus.forcedChunks = $invalidChunks
        Write-MockStatus
        $rejected = $false
        try { [void](Read-TunnelPreRunStatus) } catch { $rejected = $true }
        Assert-True $rejected 'forcedChunks accepted non-integer JSON evidence'
    }
    $preRunStatus.forcedChunks = 22
    $nonObjectJson = @('[]', '[1]', '[{}]', '1', 'true', 'null', '"not-a-status"',
        ('[' + (ConvertTo-Json $preRunStatus -Depth 20 -Compress) + ']'))
    foreach ($invalidJson in $nonObjectJson) {
        [IO.File]::WriteAllText($fixtureStatusPath, $invalidJson, [Text.UTF8Encoding]::new($false))
        $diagnostic = $null
        try { [void](Read-TunnelPreRunStatus) } catch { $diagnostic = $_.Exception.Message }
        Assert-True ($diagnostic -ceq 'tunnel pre-run status artifact must contain one JSON object') `
            'non-object JSON did not fail before property lookup with the fixed diagnostic'
    }
    $validStatusJson = ConvertTo-Json $preRunStatus -Depth 20 -Compress
    foreach ($prefix in @('"ready":false,', '"READY":false,', '"rea\u0064y":false,',
            '"unused":{"value":1,"value":2},', '"unused":[{"value":1,"VALUE":2}],')) {
        [IO.File]::WriteAllText($fixtureStatusPath, $validStatusJson.Insert(1, $prefix), [Text.UTF8Encoding]::new($false))
        $diagnostic = $null
        try { [void](Read-TunnelPreRunStatus) } catch { $diagnostic = $_.Exception.Message }
        Assert-True ($diagnostic -ceq 'tunnel JSON contains duplicate object keys') `
            'raw JSON duplicate keys were collapsed before validation'
    }
    # A real UUID from another run must fail against the public session before any Action.
    $preRunStatus.worldSessionId = '550e8400-e29b-41d4-a716-446655440081'
    Write-MockStatus
    $rejected = $false
    try { [void](Invoke-McmcpTunnelCapabilityGate) } catch { $rejected = $true }
    Assert-True ($rejected -and $null -eq $script:Submitted) `
        'a foreign-run status reached Action dispatch'
    $preRunStatus.worldSessionId = $script:FixtureWorldSessionId
    Write-MockStatus
    $tunnelGateResult = Invoke-McmcpTunnelCapabilityGate
    Assert-True ($tunnelGateResult.gate_result.state -ceq 'succeeded' -and
        $tunnelGateResult.gate_result.completed_cells -eq 16 -and
        $script:Submitted.program.body[0].op -ceq 'excavate_tunnel') `
        'end-to-end gate did not submit and validate one straight tunnel Action'
    Assert-True ([bool]$tunnelGateResult.input_release.control_ready -and
        [bool]$tunnelGateResult.input_release.all_actions_terminal) `
        'end-to-end gate did not prove public input release'
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed' -and
        $manifest.fixture_mode -ceq 'straight16' -and
        $manifest.fixture_setup_id -ceq '550e8400-e29b-41d4-a716-446655440090' -and
        $manifest.world_session_id -ceq $script:FixtureWorldSessionId -and
        $manifest.result.world_session_id -ceq $script:FixtureWorldSessionId -and
        $manifest.public_input_release.world_session_id -ceq $script:FixtureWorldSessionId -and
        $manifest.result.action_id -ceq $mockActionId -and
        $manifest.public_input_release.action_id -ceq $mockActionId -and
        $manifest.public_input_release.action_state -ceq $manifest.result.state -and
        $manifest.public_input_release.cancel_requested -is [bool] -and
        -not $manifest.public_input_release.cancel_requested -and
        $manifest.fixture_status_sha256 -ceq
            (Get-FileHash -LiteralPath $fixtureStatusPath -Algorithm SHA256).Hash.ToLowerInvariant() -and
        [bool]$manifest.fixture_oracle_required) `
        'gate artifact did not preserve the fixture acceptance requirement'
    $script:Submitted = $null
    $script:ChangeSessionOnTerminal = $true
    $rejected = $false
    try { [void](Invoke-McmcpTunnelCapabilityGate) } catch { $rejected = $true }
    Assert-True ($rejected -and $null -ne $script:Submitted) `
        'world session changed after dispatch but the gate still passed'
    $driftManifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($driftManifest.status -ceq 'failed' -and $null -eq $driftManifest.result) `
        'session drift retained a successful terminal proof'

    function Reset-MockRun {
        $script:Submitted = $null
        $script:ChangeSessionOnTerminal = $false
        $script:ResponseMutation = $null
        $script:QueryCount = 0; $script:PollCount = 0; $script:CancelCount = 0
        $script:CleanupRecovery = $false
        $script:FixtureWorldSessionId = '550e8400-e29b-41d4-a716-446655440080'
        $preRunStatus.worldSessionId = $script:FixtureWorldSessionId
        Write-MockStatus
    }

    $stateFields = @{
        schema_version = '1'; control = @([pscustomobject]@{}); 'control.mode' = @('ready')
        'control.game_paused' = 'false'; world = @([pscustomobject]@{})
        'world.dimension' = @('minecraft:overworld'); 'world.client_tick' = '100'
        'world.world_revision' = @(20); observation = @([pscustomobject]@{})
        'observation.latest_frame_id' = @('obs-0000000000000001'); action = @([pscustomobject]@{})
        recipe_query = @([pscustomobject]@{}); 'recipe_query.basis' = @([pscustomobject]@{})
        'recipe_query.basis.world_session_id' = @($script:FixtureWorldSessionId)
        inventory = [pscustomobject]@{}; 'inventory.0.item' = @('minecraft:netherite_pickaxe')
        'inventory.0.count' = '1'; 'world.position' = @([pscustomobject]@{})
        'world.position.x' = '257.5'; 'world.position.y' = $true; 'world.position.z' = [double]::NaN
        'world.health' = '20'; 'world.hunger' = 20.0
    }
    foreach ($path in $stateFields.Keys) {
        foreach ($remove in @($false, $true)) {
            Reset-MockRun
            $script:ResponseMutation = {
                param($Tool, $Arguments, $Response)
                if ($Tool -ceq 'agent_get_state' -and $Arguments.Contains('query')) {
                    Set-MockField $Response $path $stateFields[$path] -Remove:$remove
                }
            }
            Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } "initial state accepted invalid $path"
            Assert-True ($null -eq $script:Submitted) "invalid initial $path reached dispatch"
        }
    }
    $receiptFields = @{
        schema_version = '1'; action_id = @($mockActionId); state = @('queued')
        accepted_at = @('2026-09-07T00:00:00Z')
    }
    foreach ($path in $receiptFields.Keys) {
        foreach ($remove in @($false, $true)) {
            Reset-MockRun
            $script:ResponseMutation = {
                param($Tool, $Arguments, $Response)
                if ($Tool -ceq 'agent_start_action') {
                    Set-MockField $Response $path $receiptFields[$path] -Remove:$remove
                }
            }
            Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } "start receipt accepted invalid $path"
            Assert-True ($script:PollCount -eq 0 -and $script:CancelCount -eq 0) `
                'invalid start receipt authorized polling or cancellation'
        }
    }
    foreach ($badDate in @('', 'yesterday', '2026-09-07', '2026-13-07T00:00:00Z')) {
        Reset-MockRun
        $script:ResponseMutation = {
            param($Tool, $Arguments, $Response)
            if ($Tool -ceq 'agent_start_action') { $Response.accepted_at = $badDate }
        }
        Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } 'start receipt accepted an invalid Instant'
        Assert-True ($script:PollCount -eq 0) 'invalid Instant authorized polling'
    }
    foreach ($path in @('action_id', 'schema_version', 'state')) {
        foreach ($remove in @($false, $true)) {
            Reset-MockRun
            $script:ResponseMutation = {
                param($Tool, $Arguments, $Response)
                if ($Tool -ceq 'agent_get_action') {
                    $value = if ($path -ceq 'action_id') { '550e8400-e29b-41d4-a716-446655440092' }
                        elseif ($path -ceq 'schema_version') { '1' } else { ,@('succeeded') }
                    Set-MockField $Response $path $value -Remove:$remove
                }
            }
            Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } "poll accepted invalid $path"
        }
    }
    foreach ($boundary in @(3, 4)) {
        foreach ($path in @('action_id', 'state')) {
            Reset-MockRun
            $script:ResponseMutation = {
                param($Tool, $Arguments, $Response)
                if ($Tool -ceq 'agent_get_state' -and $Arguments.Contains('query') -and
                    $script:QueryCount -eq $boundary) {
                    $value = if ($path -ceq 'action_id') { '550e8400-e29b-41d4-a716-446655440092' } else { 'cancelled' }
                    Set-MockField $Response.action $path $value
                }
            }
            Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } "boundary $boundary accepted another Action/state"
            $rejectedManifest = Get-Content (Join-Path $artifactDirectory 'gate-result.json') -Raw | ConvertFrom-Json
            Assert-True ($rejectedManifest.status -ceq 'failed') 'cross-Action evidence produced a passed artifact'
        }
    }
    Reset-MockRun
    $script:CleanupRecovery = $true
    Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } 'poll failure was hidden by successful cleanup'
    $recovered = Get-Content (Join-Path $artifactDirectory 'gate-result.json') -Raw | ConvertFrom-Json
    Assert-True ($script:CancelCount -eq 1 -and $recovered.status -ceq 'failed' -and
        $recovered.public_input_release.cancel_requested -is [bool] -and
        $recovered.public_input_release.cancel_requested -and
        $recovered.public_input_release.action_id -ceq $mockActionId -and
        $recovered.public_input_release.action_state -ceq 'cancelled') `
        'failure cleanup lost its exact terminal/cancel evidence'
    foreach ($cancelValue in @('true', @($true), $null)) {
        Reset-MockRun
        $script:CleanupRecovery = $true
        $script:ResponseMutation = {
            param($Tool, $Arguments, $Response)
            if ($Tool -ceq 'agent_cancel_action') { $Response.cancel_requested = $cancelValue }
        }
        Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } 'malformed cancellation was accepted'
        $badCleanup = Get-Content (Join-Path $artifactDirectory 'gate-result.json') -Raw | ConvertFrom-Json
        Assert-True ($script:PollCount -eq 2 -and $null -eq $badCleanup.public_input_release) `
            'malformed cancellation became successful release evidence'
    }
    foreach ($path in @('schema_version', 'action_id', 'cancel_requested', 'state_at_request')) {
        foreach ($remove in @($false, $true)) {
            Reset-MockRun
            $script:CleanupRecovery = $true
            $script:ResponseMutation = {
                param($Tool, $Arguments, $Response)
                if ($Tool -ceq 'agent_cancel_action') {
                    $value = switch ($path) {
                        schema_version { '1' }
                        action_id { '550e8400-e29b-41d4-a716-446655440092' }
                        cancel_requested { 'true' }
                        state_at_request { ,@('running') }
                    }
                    Set-MockField $Response $path $value -Remove:$remove
                }
            }
            Assert-Rejected { Invoke-McmcpTunnelCapabilityGate } "cleanup accepted invalid $path"
            $badCleanup = Get-Content (Join-Path $artifactDirectory 'gate-result.json') -Raw | ConvertFrom-Json
            Assert-True ($script:PollCount -eq 2 -and $null -eq $badCleanup.public_input_release) `
                'invalid cancellation response authorized successful cleanup evidence'
        }
    }
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        $cleanupPath = [IO.Path]::GetFullPath($artifactDirectory)
        if (-not $cleanupPath.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($cleanupPath).StartsWith('mcmcp-tunnel-gate-', [StringComparison]::Ordinal)) {
            throw 'mock cleanup path escaped its temporary test directory'
        }
        Remove-Item -LiteralPath $cleanupPath -Recurse -Force
    }
}

Write-Output 'MCMCP tunnel capability gate mock tests passed.'
