[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpSmeltCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-smelt-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "smelt capability gate mock test failed: $Message" }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$Message)

    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

function New-MockSmeltState {
    param([Parameter(Mandatory)][bool]$Completed, [switch]$WithRecipe)

    $recipeQuery = $null
    if ($WithRecipe) {
        $recipeQuery = [pscustomobject]@{
            basis = [pscustomobject]@{
                world_session_id = 'session-1'; client_tick = 10L; recipe_book_revision = 1L
            }
            coverage = [pscustomobject]@{
                source = 'client_known_recipe_displays'; complete = $false
                known = 1; matched = 1; returned = 1; truncated = $false
            }
            recipes = @([pscustomobject]@{
                    recipe_ref = 'abcdefghijklmnopqrstuvwx'
                    fingerprint = 'sha256:' + ('a' * 64)
                    display_kind = 'smelting'; required_screen = 'furnace'
                    supported = $true; unsupported_reason = $null
                    result = [pscustomobject]@{
                        deterministic = $true
                        alternatives = @([pscustomobject]@{
                                item = 'minecraft:iron_ingot'; count = 1
                                stack_fingerprint = 'sha256:' + ('b' * 64)
                            })
                    }
                    ingredients = @([pscustomobject]@{
                            index = 0; count_per_craft = 1
                            alternatives = @([pscustomobject]@{ item = 'minecraft:raw_iron' })
                        })
                    shape = $null
                })
        }
    }
    $inventory = if ($Completed) {
        @([pscustomobject]@{ item = 'minecraft:iron_ingot'; count = 1 })
    } else {
        @(
            [pscustomobject]@{ item = 'minecraft:raw_iron'; count = 1 }
            [pscustomobject]@{ item = 'minecraft:coal'; count = 1 }
        )
    }
    return [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 10L; world_revision = 1L
            position = [pscustomobject]@{ x = 196.5; y = 200.0; z = 196.5 }
            yaw = 180.0; pitch = 25.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = $inventory
        standard_potions = @()
        recipe_query = $recipeQuery
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0123456789abcdef' }
        action = $null
    }
}

$surface = [pscustomobject]@{
    kind = 'visible_surface'
    block = 'minecraft:furnace'
    position = [pscustomobject]@{
        dimension = 'minecraft:overworld'; x = 196; y = 200; z = 194
    }
    face = 'south'
    state = [pscustomobject]@{
        block = 'minecraft:furnace'
        properties = [pscustomobject]@{ facing = 'north'; lit = 'false' }
    }
    placement_item = $null
    placement_state_ref = $null
    observed_tick = 10L
    world_revision = 1L
}
$initial = New-MockSmeltState -Completed:$false -WithRecipe
$recipe = Get-OnlySmeltRecipe -State $initial
$request = New-SmeltActionRequest -Recipe $recipe -Surface $surface
$node = $request.program.body[0]
Assert-True ([object]::ReferenceEquals($surface.position, $node.station.target)) `
    'station position was not copied as the delivered object'
Assert-True ([object]::ReferenceEquals($surface.state, $node.station.expected_state)) `
    'station state was not copied as the delivered object'
Assert-True ($node.recipe_ref -ceq $recipe.recipe_ref) `
    'opaque recipe_ref was changed'
Assert-True ($node.recipe_fingerprint -ceq $recipe.fingerprint) `
    'recipe fingerprint was changed'
Assert-True ($request.budget.max_ticks -eq 2400) 'smelt tick budget is not exact'
Assert-True ($request.budget.max_duration_ms -eq 120000) 'smelt duration budget is not exact'
Assert-True ($request.budget.max_interactions -eq 7) 'smelt interaction budget is not exact'

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ActionCompleted = $false
$actionId = '550e8400-e29b-41d4-a716-446655440050'
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion
        tools = @($script:AllowedTools)
    })
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            $withRecipe = $Arguments.Contains('query')
            New-MockSmeltState -Completed:$script:ActionCompleted -WithRecipe:$withRecipe
        }
        'agent_get_observation' {
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-0123456789abcdef'
                frame_completed_tick = 10L
                visible_entities_truncated = $false
                records = @($surface)
                next_cursor = $null
                sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $submitted = $Arguments.program.body[0]
            if (-not [object]::ReferenceEquals($surface.position, $submitted.station.target) -or
                -not [object]::ReferenceEquals($surface.state, $submitted.station.expected_state)) {
                throw 'mock observed transformed station evidence'
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
                    interactions = 7; blocks_broken = 0; blocks_placed = 0
                }
                failure = $null
                trace = @(
                    [pscustomobject]@{ tick = 0; event = 'NODE_STARTED'; detail = 'smelt_fixture_iron' }
                    [pscustomobject]@{
                        tick = 200; event = 'NODE_EVIDENCE'
                        detail = 'smelt_complete=minecraft:iron_ingot'
                    }
                    [pscustomobject]@{
                        tick = 200; event = 'NODE_COMPLETED'; detail = 'smelt_fixture_iron'
                    }
                    [pscustomobject]@{ tick = 200; event = 'SUCCEEDED'; detail = 'succeeded' }
                )
            }
        }
        default { throw "unexpected smelt mock tool: $Tool" }
    }
}

$validTerminal = & $script:ToolTransport 'agent_get_action' ([ordered]@{})
[void](Assert-SmeltTerminalProof -Terminal $validTerminal)
$emptyTraceTerminal = $validTerminal.PSObject.Copy()
$emptyTraceTerminal.trace = @()
Assert-Throws { Assert-SmeltTerminalProof -Terminal $emptyTraceTerminal } `
    'an empty terminal trace was accepted as smelt completion proof'
$movingTerminal = $validTerminal.PSObject.Copy()
$movingTerminal.progress = $validTerminal.progress.PSObject.Copy()
$movingTerminal.progress.distance_travelled = 0.25
Assert-Throws { Assert-SmeltTerminalProof -Terminal $movingTerminal } `
    'a moving smelt Action was accepted as stationary proof'
$savedSurfaceEvents = $script:GateEvents
$script:GateEvents = [Collections.Generic.List[object]]::new()
Assert-Throws { Assert-SmeltFixedFiveSurfaceEvidence } `
    'missing fixed-five verification was accepted'
$script:GateEvents = $savedSurfaceEvents
$script:ActionCompleted = $false

try {
    $result = Invoke-McmcpSmeltCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase5-smelt') 'gate did not pass'
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
    Assert-True ($result.gate_result.material_output_oracle.inventory_before.'minecraft:raw_iron' `
            -eq 1) 'material oracle lost the raw-iron baseline'
    Assert-True ($result.gate_result.material_output_oracle.inventory_after.'minecraft:iron_ingot' `
            -eq 1) 'material oracle lost the iron-ingot result'
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

Write-Output 'MCMCP smelt capability gate mock tests passed.'
