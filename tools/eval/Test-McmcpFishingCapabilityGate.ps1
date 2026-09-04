[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpFishingCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-fishing-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "fishing gate mock failed: $Message" }
}

function New-MockFishingSurface {
    [pscustomobject]@{
        kind = 'visible_surface'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 199; y = 202; z = 200
        }
        face = 'up'; block = 'minecraft:water'
        state = [pscustomobject]@{
            block = 'minecraft:water'; properties = [pscustomobject]@{ level = '0' }
        }
        placement_item = $null; placement_state_ref = $null; shape_class = 'empty'
        eye_origin = [pscustomobject]@{ x = 199.5; y = 204.62; z = 194.5 }
        observed_tick = 100L; world_revision = 20L; provenance = 'visual'
    }
}

function New-MockFishingState {
    param([Parameter(Mandatory)][int]$Stage)
    $inventory = [Collections.Generic.List[object]]::new()
    $inventory.Add([pscustomobject]@{ item = 'minecraft:fishing_rod'; count = 1 })
    if ($Stage -ge 3) {
        $inventory.Add([pscustomobject]@{ item = 'minecraft:cod'; count = 1 })
    }
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 100L + $Stage
            world_revision = 20L; position = [pscustomobject]@{
                x = 199.5; y = 203.0; z = 194.5
            }
            yaw = 0.0; pitch = 10.0; health = 20.0; absorption = 0.0
            hunger = 17; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @($inventory); standard_potions = @(); recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{
            latest_frame_id = 'obs-' + ([long]$Stage).ToString('x16')
        }
        action = $null
    }
}

function New-MockFishingTerminal {
    param([Parameter(Mandatory)][ValidateSet('cast_known_fishing_rod', 'wait_until',
            'reel_known_fishing_session')][string]$Op)
    $number = switch ($Op) {
        'cast_known_fishing_rod' { 1 }
        'wait_until' { 2 }
        'reel_known_fishing_session' { 3 }
    }
    $interactions = switch ($Op) {
        'cast_known_fishing_rod' { 1 }
        'reel_known_fishing_session' { 1 }
        default { 0 }
    }
    $nodeId = switch ($Op) {
        'cast_known_fishing_rod' { 'cast_line' }
        'wait_until' { 'wait_for_bite' }
        'reel_known_fishing_session' { 'reel_line' }
    }
    $effects = @()
    if ($Op -ceq 'cast_known_fishing_rod') {
        $effects = @([pscustomobject]@{
            seq = 1; node_id = 'cast_line'; kind = 'item_use'
            subject = 'item:minecraft:fishing_rod'
            observed_before = [pscustomobject]@{ owned_bobber = $false }
            observed_after = [pscustomobject]@{
                owned_bobber = $true; fishing_session_ref = 'f_' + ('a' * 22)
            }
            verification = 'confirmed'; client_tick = 101L; world_revision = 20L
        })
    }
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-' + $number.ToString('000000000000')
        state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = if ($number -eq 1) { 4 } else { 0 }
            interactions = $interactions; blocks_broken = 0; blocks_placed = 0
        }
        failure = $null
        trace = @(
            [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = $nodeId },
            [pscustomobject]@{ tick = 4; event = 'NODE_COMPLETED'; detail = $nodeId },
            [pscustomobject]@{ tick = 4; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = $effects
        partial = [pscustomobject]@{
            has_confirmed_effects = @($effects).Count -gt 0; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

$surface = New-MockFishingSurface
$castRequest = New-FishingCastRequest -Surface $surface
Assert-True ([object]::ReferenceEquals($surface.position,
        $castRequest.program.body[0].target)) 'cast builder changed the delivered target'
Assert-True ([object]::ReferenceEquals($surface.state,
        $castRequest.program.body[0].expected_state)) 'cast builder changed exact source water'
Assert-True ($castRequest.program.capabilities.Count -eq 2 -and
    $castRequest.program.capabilities[0] -ceq 'camera' -and
    $castRequest.program.capabilities[1] -ceq 'item_use') `
    'cast builder capabilities are not camera+item_use'
$waitRequest = New-FishingSoundWaitRequest -SinceTick 101
Assert-True ($waitRequest.program.body[0].condition.type -ceq 'sound_clue' -and
    $waitRequest.program.body[0].condition.sound_event -ceq
        'minecraft:entity.fishing_bobber.splash' -and
    $waitRequest.program.body[0].max_ticks -eq 720) `
    'wait builder is not exact-sound-bound and finite'
$reelRequest = New-FishingReelRequest -SessionRef ('f_' + ('a' * 22))
Assert-True ($reelRequest.program.body[0].op -ceq 'reel_known_fishing_session' -and
    $reelRequest.program.body[0].fishing_session_ref -ceq ('f_' + ('a' * 22))) `
    'reel builder changed the opaque session reference'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:MockStage = 0
$script:MockPending = $null
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' { New-MockFishingState -Stage $script:MockStage }
        'agent_get_observation' {
            $records = if (@($Arguments.kinds)[0] -ceq 'visible_surface') {
                @(New-MockFishingSurface)
            } else { @() }
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-' + ([long]$script:MockStage).ToString('x16')
                frame_completed_tick = 100L + $script:MockStage
                visible_entities_truncated = $false; records = $records
                next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $op = [string]$Arguments.program.body[0].op
            $expected = switch ($script:MockStage) {
                0 { 'cast_known_fishing_rod' }
                1 { 'wait_until' }
                2 { 'reel_known_fishing_session' }
                default { throw 'mock received an unexpected extra Action' }
            }
            if ($op -cne $expected) { throw "mock expected $expected, got $op" }
            if ($op -ceq 'wait_until' -and
                [long]$Arguments.program.body[0].condition.since_tick -ne 101L) {
                throw 'mock received a stale fishing sound barrier'
            }
            if ($op -ceq 'reel_known_fishing_session' -and
                $Arguments.program.body[0].fishing_session_ref -cne ('f_' + ('a' * 22))) {
                throw 'mock received a changed fishing session reference'
            }
            $script:MockPending = $op
            $number = $script:MockStage + 1
            [pscustomobject]@{
                schema_version = 1
                action_id = '550e8400-e29b-41d4-a716-' + $number.ToString('000000000000')
                state = 'queued'
            }
        }
        'agent_get_action' {
            if ($null -eq $script:MockPending) { throw 'mock has no pending fishing Action' }
            $terminal = New-MockFishingTerminal -Op $script:MockPending
            $script:MockStage++
            $script:MockPending = $null
            $terminal
        }
        'agent_cancel_action' { throw 'mock should not cancel a successful fishing Action' }
        default { throw "unexpected fishing mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpFishingCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase9-fishing') 'gate result name is wrong'
    Assert-True ($result.gate_result.finite_timeout.bite_wait_ticks -eq 720) `
        'sound wait is not finitely bounded'
    Assert-True ($result.gate_result.online_oracle.fishing_loot_count -eq 1 -and
        -not [bool]$result.gate_result.online_oracle.collection_used) `
        'online oracle did not prove one directly received loot item'
    Assert-True ($result.gate_result.external_oracle.player.health -eq 20.0) `
        'fishing offline oracle did not bind the observed health baseline'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) 'input release was not proven'
    $accepted = @($script:GateEvents | Where-Object event -CEQ 'action_accepted')
    $terminal = @($script:GateEvents | Where-Object event -CEQ 'action_terminal')
    Assert-True ($accepted.Count -eq 3 -and $terminal.Count -eq 3) `
        'cast/wait/reel lifecycle is incomplete'
    foreach ($name in @('gate-events.jsonl', 'gate-result.json',
            'external-oracle-manifest.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "missing artifact $name"
    }
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP fishing capability gate mock tests passed.'
