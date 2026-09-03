[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpBrewCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-brew-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "brew capability gate mock test failed: $Message" }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$Message)

    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

function New-MockBrewState {
    param([Parameter(Mandatory)][bool]$Completed)

    $inventory = if ($Completed) {
        @([pscustomobject]@{ item = 'minecraft:potion'; count = 3 })
    } else {
        @(
            [pscustomobject]@{ item = 'minecraft:potion'; count = 3 }
            [pscustomobject]@{ item = 'minecraft:nether_wart'; count = 1 }
            [pscustomobject]@{ item = 'minecraft:blaze_powder'; count = 1 }
        )
    }
    $standardPotions = if ($Completed) {
        @([pscustomobject]@{
                item = 'minecraft:potion'; potion = 'minecraft:awkward'; count = 3
            })
    } else {
        @([pscustomobject]@{
                item = 'minecraft:potion'; potion = 'minecraft:water'; count = 3
            })
    }
    return [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 10L; world_revision = 1L
            position = [pscustomobject]@{ x = 197.5; y = 200.0; z = 196.5 }
            yaw = 180.0; pitch = 25.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = $inventory
        standard_potions = $standardPotions
        recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-fedcba9876543210' }
        action = $null
    }
}

$surface = [pscustomobject]@{
    kind = 'visible_surface'
    block = 'minecraft:brewing_stand'
    position = [pscustomobject]@{
        dimension = 'minecraft:overworld'; x = 197; y = 200; z = 194
    }
    face = 'south'
    # Partial-shape surfaces deliberately omit copyable BlockState evidence.
    # brew_known_potion_batch only consumes the delivered block identity.
    state = $null
    placement_item = $null
    placement_state_ref = $null
    observed_tick = 10L
    world_revision = 1L
}

$initial = New-MockBrewState -Completed:$false
$initialLedger = Assert-BrewExactLedger -State $initial -Completed:$false
Assert-True ($initialLedger.standard_potions.'minecraft:potion|minecraft:water' -eq 3) `
    'initial standard-potion identity was not retained'
$request = New-BrewActionRequest -Surface $surface
$node = $request.program.body[0]
Assert-True ([object]::ReferenceEquals($surface.position, $node.target)) `
    'station position was not copied as the delivered object'
Assert-True ($node.expected_block -ceq $surface.block) `
    'station block was not copied verbatim'
Assert-True ($node.op -ceq 'brew_known_potion_batch') `
    'request did not use the normal brewing Action'
Assert-True ($node.input.item -ceq 'minecraft:potion' -and
    $node.input.potion -ceq 'minecraft:water' -and $node.input.count -eq 3) `
    'request input is not exactly three water potions'
Assert-True ($node.ingredient_item -ceq 'minecraft:nether_wart' -and
    $node.fuel_item -ceq 'minecraft:blaze_powder') `
    'request ingredient or fuel changed'
Assert-True ($node.expected_output.item -ceq 'minecraft:potion' -and
    $node.expected_output.potion -ceq 'minecraft:awkward' -and
    $node.expected_output.count -eq 3) `
    'request output is not exactly three awkward potions'
Assert-True ($request.budget.max_ticks -eq 1400) 'brew tick budget is not exact'
Assert-True ($request.budget.max_duration_ms -eq 70000) `
    'brew duration budget is not exact'
Assert-True ($request.budget.max_distance_blocks -eq 0) `
    'brew distance budget is not stationary'
Assert-True ($request.budget.max_camera_degrees -eq 540) `
    'brew camera budget is not exact'
Assert-True ($request.budget.max_interactions -eq 16) `
    'brew interaction budget is not exact'
Assert-True ($request.budget.max_blocks_broken -eq 0 -and
    $request.budget.max_blocks_placed -eq 0) `
    'brew mutation budget is not zero'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ActionCompleted = $false
$actionId = '550e8400-e29b-41d4-a716-446655440060'
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion
        tools = @($script:AllowedTools)
    })
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            New-MockBrewState -Completed:$script:ActionCompleted
        }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-fedcba9876543210'
                frame_completed_tick = 10L
                visible_entities_truncated = $false
                records = @($surface)
                next_cursor = $null
                sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $submitted = $Arguments.program.body[0]
            if (-not [object]::ReferenceEquals($surface.position, $submitted.target) -or
                $submitted.expected_block -cne $surface.block -or
                $submitted.op -cne 'brew_known_potion_batch' -or
                $submitted.input.item -cne 'minecraft:potion' -or
                $submitted.input.potion -cne 'minecraft:water' -or
                $submitted.input.count -ne 3 -or
                $submitted.ingredient_item -cne 'minecraft:nether_wart' -or
                $submitted.fuel_item -cne 'minecraft:blaze_powder' -or
                $submitted.expected_output.item -cne 'minecraft:potion' -or
                $submitted.expected_output.potion -cne 'minecraft:awkward' -or
                $submitted.expected_output.count -ne 3) {
                throw 'mock observed transformed or incorrect brew request evidence'
            }
            [pscustomobject]@{ schema_version = 1; action_id = $actionId; state = 'queued' }
        }
        'agent_get_action' {
            $script:ActionCompleted = $true
            [pscustomobject]@{
                schema_version = 1; action_id = $actionId; state = 'succeeded'
                progress = [pscustomobject]@{
                    executed_nodes = 1; total_node_upper_bound = 1
                    distance_travelled = 0; camera_degrees = 120
                    interactions = 16; blocks_broken = 0; blocks_placed = 0
                }
                failure = $null
                trace = @(
                    [pscustomobject]@{
                        tick = 0; event = 'NODE_STARTED'; detail = 'brew_fixture_awkward'
                    }
                    [pscustomobject]@{
                        tick = 400; event = 'NODE_EVIDENCE'; detail = 'brewing_complete=3'
                    }
                    [pscustomobject]@{
                        tick = 400; event = 'NODE_COMPLETED'; detail = 'brew_fixture_awkward'
                    }
                    [pscustomobject]@{ tick = 400; event = 'SUCCEEDED'; detail = 'succeeded' }
                )
            }
        }
        default { throw "unexpected brew mock tool: $Tool" }
    }
}

$validTerminal = & $script:ToolTransport 'agent_get_action' ([ordered]@{})
[void](Assert-BrewTerminalProof -Terminal $validTerminal)
$emptyTraceTerminal = $validTerminal.PSObject.Copy()
$emptyTraceTerminal.trace = @()
Assert-Throws { Assert-BrewTerminalProof -Terminal $emptyTraceTerminal } `
    'an empty terminal trace was accepted as brew completion proof'
$movingTerminal = $validTerminal.PSObject.Copy()
$movingTerminal.progress = $validTerminal.progress.PSObject.Copy()
$movingTerminal.progress.distance_travelled = 0.25
Assert-Throws { Assert-BrewTerminalProof -Terminal $movingTerminal } `
    'a moving brew Action was accepted as stationary proof'
$overBudgetTerminal = $validTerminal.PSObject.Copy()
$overBudgetTerminal.progress = $validTerminal.progress.PSObject.Copy()
$overBudgetTerminal.progress.interactions = 17
Assert-Throws { Assert-BrewTerminalProof -Terminal $overBudgetTerminal } `
    'an over-budget brew Action was accepted'
$savedSurfaceEvents = $script:GateEvents
$script:GateEvents = [Collections.Generic.List[object]]::new()
Assert-Throws { Assert-BrewFixedFiveSurfaceEvidence } `
    'missing fixed-five verification was accepted'
$script:GateEvents = $savedSurfaceEvents
$script:ActionCompleted = $false

try {
    $result = Invoke-McmcpBrewCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase5-brew') 'gate did not pass'
    Assert-True ($result.gate_result.lifecycle.accepted -eq 1) `
        'accepted Action count is not one'
    Assert-True ($result.gate_result.lifecycle.terminal -eq 1) `
        'terminal Action count is not one'
    Assert-True ([bool]$result.gate_result.lifecycle.accepted_equals_terminal) `
        'accepted==terminal was not proven'
    Assert-True ([bool]$result.input_release.control_ready) `
        'public input release was not proven'
    Assert-True ([bool]$result.input_release.all_actions_terminal) `
        'cleanup did not prove all Actions terminal'
    Assert-True ($result.gate_result.material_output_oracle.inventory_before.'minecraft:nether_wart' `
            -eq 1) 'material oracle lost the nether-wart baseline'
    Assert-True ($result.gate_result.material_output_oracle.inventory_after.'minecraft:blaze_powder' `
            -eq 0) 'material oracle did not prove blaze-powder consumption'
    Assert-True ($result.gate_result.material_output_oracle.standard_potions_before.`
            'minecraft:potion|minecraft:water' -eq 3) `
        'material oracle lost the water-potion baseline'
    Assert-True ($result.gate_result.material_output_oracle.standard_potions_after.`
            'minecraft:potion|minecraft:awkward' -eq 3) `
        'material oracle lost the awkward-potion result'
    Assert-True ([bool]$result.gate_result.material_output_oracle.`
            succeeded_action_contract.station_empty_after_close_reopen) `
        'station-empty proof was not retained'
    Assert-True ([bool]$result.gate_result.material_output_oracle.`
            succeeded_action_contract.cursor_empty) `
        'cursor-empty proof was not retained'
    Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory 'gate-events.jsonl')) `
        'gate event artifact was not written'
    Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory 'gate-result.json')) `
        'gate result artifact was not written'
    Assert-True (Test-Path -LiteralPath `
            (Join-Path $artifactDirectory 'material-output-oracle.json')) `
        'material/output oracle artifact was not written'
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed') 'artifact manifest status was not passed'
    Assert-True ([bool]$manifest.fixed_five_only) 'manifest did not pin the five-tool surface'
    Assert-True ([bool]$manifest.normal_player_actions_only) `
        'manifest did not require normal player Actions'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP brew capability gate mock tests passed.'
