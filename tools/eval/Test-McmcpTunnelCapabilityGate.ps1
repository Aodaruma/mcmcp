[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpTunnelCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-tunnel-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -FixtureMode straight16 -ArtifactDirectory $artifactDirectory `
    -FixtureSetupId '550e8400-e29b-41d4-a716-446655440090' `
    -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "tunnel gate mock failed: $Message" }
}

function New-MockTunnelState {
    param([Parameter(Mandatory)][int]$Stage)
    [pscustomobject]@{
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
        standard_potions = @(); recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 512 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0000000000000001' }
        action = $null
    }
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
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-446655440091'
        state = $(if ($isHazard) { 'failed' } else { 'succeeded' })
        failure = $(if ($isHazard) {
                [pscustomobject]@{
                    code = 'SAFETY_INTERRUPTED'; message = 'unsafe floor'
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
}

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
    $terminalResult = Assert-TunnelTerminal (New-MockTunnelTerminal -Mode $mode)
    Assert-True ($terminalResult.completed_cells -eq $case.cells -and
        $terminalResult.completed_moves -eq $case.moves -and
        [bool]$terminalResult.bounded_summary) `
        "$mode terminal oracle did not preserve its bounded summary"
}

$script:TunnelFixtureMode = 'straight16'
$badAggregate = New-MockTunnelTerminal -Mode straight16
$badAggregate.effect_aggregate.confirmed_effects = 31
$rejected = $false
try { [void](Assert-TunnelTerminal $badAggregate) } catch { $rejected = $true }
Assert-True $rejected 'gate accepted block counters without the matching ACK aggregate'

$badBudget = New-MockTunnelTerminal -Mode straight16
$badBudget.progress.interactions = 1
$rejected = $false
try { [void](Assert-TunnelTerminal $badBudget) } catch { $rejected = $true }
Assert-True $rejected 'gate accepted a terminal outside its zero-interaction budget'

$script:TunnelFixtureMode = 'straight16'
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:Submitted = $null
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' { New-MockTunnelState -Stage 0 }
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
                schema_version = 1
                action_id = '550e8400-e29b-41d4-a716-446655440091'; state = 'queued'
            }
        }
        'agent_get_action' { New-MockTunnelTerminal -Mode 'straight16' }
        'agent_cancel_action' { throw 'mock should not cancel a successful tunnel' }
        default { throw "unexpected tunnel mock tool: $Tool" }
    }
}

try {
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
        [bool]$manifest.fixture_oracle_required) `
        'gate artifact did not preserve the fixture acceptance requirement'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP tunnel capability gate mock tests passed.'
