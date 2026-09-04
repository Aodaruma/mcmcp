[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpKillZoneCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-kill-zone-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' `
    -ConsentWaitSeconds 10 -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "kill-zone gate mock failed: $Message" }
}

function New-MockKillZoneConsent {
    param([Parameter(Mandatory)][ValidateSet('none', 'pending', 'granted')][string]$State)
    if ($State -ceq 'none') {
        return [pscustomobject]@{
            state = 'none'; policy_binding_hash = $null; scope = $null
            consent_ref = $null; valid_before_tick = $null
        }
    }
    $scope = [pscustomobject]@{
        dimension = 'minecraft:overworld'
        player_station_bounds = [pscustomobject]@{
            min_x = 199.075; min_y = 199.9375; min_z = 197.075
            max_x = 199.925; max_y = 201.925; max_z = 197.925
        }
        target_kill_zone_bounds = [pscustomobject]@{
            min_x = 199.0; min_y = 200.0; min_z = 199.0
            max_x = 200.0; max_y = 202.0; max_z = 200.0
        }
        entity_type_allowlist = @('minecraft:armor_stand')
        main_hand = [pscustomobject]@{
            item = 'minecraft:stone_sword'; attack_effects_bound = $true
        }
        side_effect_profile = 'vanilla_sweep'; structure_bound = $true
        max_attacks = 1; minimum_interval_ticks = 10
        max_operation_duration_ticks = 200
    }
    [pscustomobject]@{
        state = $State; policy_binding_hash = 'sha256:' + ('a' * 64); scope = $scope
        consent_ref = if ($State -ceq 'granted') { 'k_' + ('b' * 22) } else { $null }
        valid_before_tick = if ($State -ceq 'granted') { 2500L } else { $null }
    }
}

function New-MockKillZoneState {
    param([Parameter(Mandatory)][string]$ConsentState)
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 100L
            world_revision = 20L; position = [pscustomobject]@{
                x = 199.5; y = 200.0; z = 197.5
            }
            yaw = 0.0; pitch = 18.0; health = 16.0; absorption = 0.0
            hunger = 17; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @([pscustomobject]@{ item = 'minecraft:stone_sword'; count = 1 })
        standard_potions = @()
        entity_attack_consent = New-MockKillZoneConsent -State $ConsentState
        recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0000000000000064' }
        action = $null
    }
}

function New-MockKillZoneTarget {
    [pscustomobject]@{
        kind = 'visible_entity'; entity_type = 'minecraft:armor_stand'
        entity_ref = 'e_' + ('c' * 22); displayed_item = $null
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 199.5; y = 200.0; z = 199.5
        }
        velocity = [pscustomobject]@{ x = 0.0; y = 0.0; z = 0.0 }
        aabb = [pscustomobject]@{
            min_x = 199.25; min_y = 200.0; min_z = 199.25
            max_x = 199.75; max_y = 201.975; max_z = 199.75
        }
        hazard_class = 'none'
        eye_origin = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 199.5; y = 201.62; z = 197.5
        }
        observed_tick = 100L; world_revision = 20L; provenance = 'VISUAL'
    }
}

function New-MockKillZoneTerminal {
    [pscustomobject]@{
        schema_version = 1
        action_id = '550e8400-e29b-41d4-a716-446655440000'
        state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 0; interactions = 1
            blocks_broken = 0; blocks_placed = 0; ticks = 4
        }
        failure = $null
        trace = @(
            [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = 'attack_once' },
            [pscustomobject]@{ tick = 4; event = 'NODE_COMPLETED'; detail = 'attack_once' },
            [pscustomobject]@{ tick = 4; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @(
            [pscustomobject]@{
                seq = 1; node_id = 'attack_once'; kind = 'entity_attack'
                subject = 'refhash:' + ('d' * 64)
                observed_before = [pscustomobject]@{
                    entity_type = 'minecraft:armor_stand'; health = 20.0
                }
                observed_after = [pscustomobject]@{
                    health = 20.0; outcome = 'armor_stand_hit_event'
                }
                verification = 'confirmed'; client_tick = 102L; world_revision = 21L
            },
            [pscustomobject]@{
                seq = 2; node_id = 'attack_once'; kind = 'kill_zone_summary'
                subject = 'operation'
                observed_before = [pscustomobject]@{ max_attacks = 1 }
                observed_after = [pscustomobject]@{
                    dispatched_attacks = 1; confirmed_attacks = 1; unknown_attacks = 0
                    completion_reason = 'attack_limit_reached'
                }
                verification = 'confirmed'; client_tick = 104L; world_revision = 21L
            }
        )
        effect_aggregate = [pscustomobject]@{
            total_effects = 2; retained_effects = 2; confirmed_effects = 2
            qualified_effects = 0; unknown_effects = 0; dispatched_attacks = 1
            confirmed_attacks = 1; unknown_attacks = 0
        }
        partial = [pscustomobject]@{
            has_confirmed_effects = $true; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}; template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

$pendingRequest = New-KillZoneRequest -ConsentRef $null
$node = $pendingRequest.program.body[0]
Assert-True ($node.op -ceq 'operate_kill_zone' -and $null -eq $node.consent_ref) `
    'first request is not an ungranted kill-zone request'
Assert-True ($pendingRequest.program.capabilities.Count -eq 1 -and
    $pendingRequest.program.capabilities[0] -ceq 'entity_attack') `
    'request capability is not entity_attack only'
Assert-True ($node.max_attacks -eq 1 -and $node.minimum_interval_ticks -eq 10 -and
    $node.max_operation_duration_ticks -eq 200) 'request is not one finite attack'
Assert-True ($pendingRequest.budget.max_interactions -eq 1 -and
    $pendingRequest.budget.max_duration_ms -eq 10500 -and
    $pendingRequest.budget.max_ticks -eq 210 -and
    $pendingRequest.budget.max_distance_blocks -eq 0 -and
    $pendingRequest.budget.max_camera_degrees -eq 0) `
    'request budget is not stationary and single-interaction'

$preexistingDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-kill-zone-existing-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($preexistingDirectory)
$savedArtifactDirectory = $ArtifactDirectory
try {
    $ArtifactDirectory = $preexistingDirectory
    $script:KillZoneArtifactInitialized = $false
    $existingRejected = $false
    try { Initialize-KillZoneArtifactDirectory } catch {
        $existingRejected = $_.Exception.Message -like '*must be new*'
    }
    Assert-True $existingRejected 'a pre-existing artifact directory was not rejected'
} finally {
    $ArtifactDirectory = $savedArtifactDirectory
    $script:KillZoneArtifactInitialized = $false
    Remove-Item -LiteralPath $preexistingDirectory -Recurse -Force
}

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:MockAwaiting = $false
$script:MockGrantPoll = 0
$script:MockActionQueued = $false
$script:MockCompleted = $false
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            $consentState = if (-not $script:MockAwaiting -or $script:MockCompleted) {
                'none'
            } elseif ($script:MockGrantPoll -eq 0) {
                $script:MockGrantPoll++
                'pending'
            } else {
                'granted'
            }
            New-MockKillZoneState -ConsentState $consentState
        }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1; frame_id = 'obs-0000000000000064'
                frame_completed_tick = 100L; visible_entities_truncated = $false
                records = @(New-MockKillZoneTarget); next_cursor = $null
                sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $candidate = $Arguments.program.body[0]
            if ($candidate.op -cne 'operate_kill_zone') {
                throw 'mock received a non-kill-zone Action'
            }
            if (-not $script:MockAwaiting) {
                if ($null -ne $candidate.consent_ref) {
                    throw 'mock first Action unexpectedly contained a consent ref'
                }
                $script:MockAwaiting = $true
                [pscustomobject]@{
                    schema_version = 1; state = 'AWAITING_CONSENT'
                    policy_binding_hash = 'sha256:' + ('a' * 64)
                    action_reserved = $false; input_acquired = $false
                }
            } else {
                if ($candidate.consent_ref -cne ('k_' + ('b' * 22)) -or
                    $script:MockActionQueued) {
                    throw 'mock second Action lacked the exact one-use physical Grant'
                }
                $script:MockActionQueued = $true
                [pscustomobject]@{
                    schema_version = 1; action_id = '550e8400-e29b-41d4-a716-446655440000'
                    state = 'queued'
                }
            }
        }
        'agent_get_action' {
            if (-not $script:MockActionQueued -or $script:MockCompleted) {
                throw 'mock has no pending kill-zone Action'
            }
            $script:MockCompleted = $true
            New-MockKillZoneTerminal
        }
        'agent_cancel_action' { throw 'mock should not cancel a successful kill-zone Action' }
        default { throw "unexpected kill-zone mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpKillZoneCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase9-kill-zone') `
        'gate result name is wrong'
    Assert-True ([bool]$result.gate_result.physical_grant_observed -and
        -not [bool]$result.gate_result.grant_automation) `
        'physical-only Grant result was not retained'
    Assert-True ($result.gate_result.online_oracle.confirmed_attacks -eq 1 -and
        $result.gate_result.online_oracle.unknown_attacks -eq 0) `
        'online oracle did not prove exactly one confirmed attack'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) 'input release was not proven'
    $awaiting = @($script:GateEvents | Where-Object event -CEQ 'awaiting_physical_consent')
    $granted = @($script:GateEvents | Where-Object event -CEQ 'physical_consent_observed')
    $accepted = @($script:GateEvents | Where-Object event -CEQ 'action_accepted')
    $terminal = @($script:GateEvents | Where-Object event -CEQ 'action_terminal')
    Assert-True ($awaiting.Count -eq 1 -and $granted.Count -eq 1 -and
        $accepted.Count -eq 1 -and $terminal.Count -eq 1) `
        'consent and single-Action lifecycle is incomplete'
    foreach ($name in @('gate-events.jsonl', 'gate-result.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "missing artifact $name"
    }
    $secondFailedClosed = $false
    try { [void](Invoke-McmcpKillZoneCapabilityGate) } catch {
        $secondFailedClosed = $_.Exception.Message -like '*single-use*'
    }
    Assert-True $secondFailedClosed 'a second invocation could overwrite the new artifact directory'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP kill-zone capability gate mock tests passed.'
