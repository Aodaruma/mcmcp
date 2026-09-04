[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpBoundedInputHoldCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-bounded-input-hold-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "bounded-input hold gate mock failed: $Message" }
}

function New-MockHoldState {
    param([Parameter(Mandatory)][int]$Stage)
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 100L + 64L * $Stage
            world_revision = 20L
            position = [pscustomobject]@{ x = 204.5; y = 200.0; z = 196.5 }
            yaw = 180.0; pitch = 35.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @([pscustomobject]@{
                item = 'minecraft:wooden_pickaxe'; count = 1
            })
        standard_potions = @(); recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{
            latest_frame_id = 'obs-' + ([long]$Stage).ToString('x16')
        }
        action = $null
    }
}

function New-MockHoldSurface {
    [pscustomobject]@{
        kind = 'visible_surface'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 204; y = 200; z = 194
        }
        face = 'south'; block = 'minecraft:obsidian'
        state = [pscustomobject]@{ block = 'minecraft:obsidian'; properties = [pscustomobject]@{} }
        placement_item = 'minecraft:obsidian'; placement_state_ref = $null
        shape_class = 'full_cube'
        eye_origin = [pscustomobject]@{ x = 204.5; y = 201.62; z = 196.5 }
        observed_tick = 100L; world_revision = 20L; provenance = 'visual'
    }
}

function New-MockHoldTerminal {
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-446655440081'
        state = 'succeeded'; failure = $null
        progress = [pscustomobject]@{
            phase = 'finished'; current_node_id = $null
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 0; interactions = 0
            blocks_broken = 0; blocks_placed = 0; ticks = 61
        }
        trace = @(
            [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = 'hold_attack' },
            [pscustomobject]@{ tick = 60; event = 'NODE_COMPLETED'; detail = 'hold_attack' },
            [pscustomobject]@{ tick = 61; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @()
        partial = [pscustomobject]@{
            has_confirmed_effects = $false; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

$surface = New-MockHoldSurface
$request = New-BoundedInputHoldRequest -Surface $surface
$node = $request.program.body[0]
Assert-True ($node.op -ceq 'hold_bounded_inputs' -and
    $node.inputs.Count -eq 1 -and $node.inputs[0] -ceq 'attack' -and
    $node.duration_ticks -eq 60 -and
    $node.selected_item -ceq 'minecraft:wooden_pickaxe') `
    'builder changed the finite attack hold contract'
Assert-True ([object]::ReferenceEquals($surface.position, $node.target_guard.target) -and
    [object]::ReferenceEquals($surface.state, $node.target_guard.expected_state)) `
    'builder transformed delivered target evidence'
Assert-True ($request.program.capabilities.Count -eq 1 -and
    $request.program.capabilities[0] -ceq 'block_break' -and
    $request.budget.max_duration_ms -eq 3000 -and
    $request.budget.max_ticks -eq 60 -and
    $request.budget.max_blocks_broken -eq 1) `
    'builder did not reserve the exact short-hold budget'

$script:HoldInput = 'use'
$useRequest = New-BoundedInputHoldRequest -Surface $surface
Assert-True ($useRequest.program.body[0].inputs[0] -ceq 'use' -and
    $useRequest.program.body[0].id -ceq 'hold_use' -and
    $useRequest.program.capabilities[0] -ceq 'item_use' -and
    $useRequest.budget.max_interactions -eq 1 -and
    $useRequest.budget.max_blocks_broken -eq 0) `
    'builder changed the finite use hold contract'
$script:HoldInput = 'attack'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:MockStage = 0
$script:Submitted = $null
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' { New-MockHoldState -Stage $script:MockStage }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-' + ([long]$script:MockStage).ToString('x16')
                frame_completed_tick = 100L + 64L * $script:MockStage
                visible_entities_truncated = $false
                records = @((New-MockHoldSurface)); next_cursor = $null
                sampling_coverage = 1
            }
        }
        'agent_start_action' {
            if ($null -ne $script:Submitted) { throw 'mock received an extra Action' }
            $script:Submitted = $Arguments
            [pscustomobject]@{
                schema_version = 1
                action_id = '550e8400-e29b-41d4-a716-446655440081'
                state = 'queued'
            }
        }
        'agent_get_action' {
            $script:MockStage = 1
            New-MockHoldTerminal
        }
        'agent_cancel_action' { throw 'mock should not cancel a successful hold' }
        default { throw "unexpected bounded-input mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpBoundedInputHoldCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase9-bounded-input-hold') `
        'gate result name is wrong'
    Assert-True ($script:Submitted.program.body[0].op -ceq 'hold_bounded_inputs') `
        'gate did not submit the bounded hold'
    Assert-True ([bool]$result.gate_result.final_player_stationary -and
        [bool]$result.gate_result.final_health_unchanged -and
        $result.gate_result.final_target_state -ceq 'minecraft:obsidian') `
        'postcondition oracle is incomplete'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) `
        'terminal input release was not proven'
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed' -and
        $manifest.duration_ticks -eq 60) 'artifact did not record the finite PASS'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP bounded-input hold capability gate mock tests passed.'
