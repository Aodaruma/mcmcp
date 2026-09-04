[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpCobblestoneGeneratorCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-cobblestone-generator-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "cobblestone generator gate mock failed: $Message" }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$Message)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-True $threw $Message
}

function New-MockCobblestoneSurface {
    param([Parameter(Mandatory)][long]$Revision)
    [pscustomobject]@{
        kind = 'visible_surface'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = 199; y = 201; z = 200
        }
        face = 'north'
        block = 'minecraft:cobblestone'
        state = [pscustomobject]@{
            block = 'minecraft:cobblestone'; properties = [pscustomobject]@{}
        }
        placement_item = 'minecraft:cobblestone'
        placement_state_ref = 'psr_' + ('a' * 32)
        shape_class = 'opaque'
        eye_origin = [pscustomobject]@{ x = 199.5; y = 202.62; z = 199.5 }
        observed_tick = 100L + $Revision
        world_revision = $Revision
        provenance = 'visual'
    }
}

function New-MockCobblestoneState {
    param([Parameter(Mandatory)][int]$CobblestoneCount)
    $inventory = [Collections.Generic.List[object]]::new()
    $inventory.Add([pscustomobject]@{ item = 'minecraft:iron_pickaxe'; count = 1 })
    if ($CobblestoneCount -gt 0) {
        $inventory.Add([pscustomobject]@{
                item = 'minecraft:cobblestone'; count = $CobblestoneCount
            })
    }
    $frameId = 'obs-' + ([long]$CobblestoneCount).ToString('x16')
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{
            mode = 'ready'; ready_expires_at = $null; game_paused = $false
        }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'
            client_tick = 100L + $CobblestoneCount
            world_revision = 20L + $CobblestoneCount
            position = [pscustomobject]@{ x = 199.5; y = 201.0; z = 199.5 }
            yaw = 0.0; pitch = 8.0; health = 20.0; absorption = 0.0
            hunger = 17; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @($inventory)
        standard_potions = @(); recipe_query = $null
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = $frameId }
        action = $null
    }
}

$surface = New-MockCobblestoneSurface -Revision 20
$request = New-CobblestoneBreakRequest -Surface $surface -MinimumInventoryCount 1
$node = $request.program.body[0]
Assert-True ([object]::ReferenceEquals($surface.position, $node.target)) `
    'request did not retain delivered position'
Assert-True ([object]::ReferenceEquals($surface.state, $node.expected_state)) `
    'request did not retain delivered exact state'
Assert-True ($node.op -ceq 'break_known_block') 'request used the wrong opcode'
Assert-True ($node.tool_item -ceq 'minecraft:iron_pickaxe') 'request used the wrong tool'
Assert-True ($node.expected_drop -ceq 'minecraft:cobblestone') `
    'request used the wrong pickup goal'
Assert-True ($request.program.capabilities.Count -eq 2 -and
    $request.program.capabilities[0] -ceq 'camera' -and
    $request.program.capabilities[1] -ceq 'block_break') `
    'request did not declare exactly camera+block_break'
Assert-True ($request.budget.max_blocks_broken -eq 1 -and
    $request.budget.max_distance_blocks -eq 0 -and
    $request.budget.max_interactions -eq 0 -and
    $request.budget.max_blocks_placed -eq 0) `
    'request budget is not a stationary single break'

function New-MockBreakTerminal {
    param([ValidateRange(1, 8)][int]$Iteration, [string]$Verification = 'confirmed')
    $actionId = '550e8400-e29b-41d4-a716-' + $Iteration.ToString('000000000000')
    [pscustomobject]@{
        schema_version = 1; action_id = $actionId; state = 'succeeded'
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1
            distance_travelled = 0; camera_degrees = 3
            interactions = 0; blocks_broken = 1; blocks_placed = 0
        }
        failure = $null
        trace = @(
            [pscustomobject]@{
                tick = 0; event = 'NODE_STARTED'; detail = "break_cobblestone_$Iteration"
            },
            [pscustomobject]@{
                tick = 4; event = 'NODE_COMPLETED'; detail = "break_cobblestone_$Iteration"
            },
            [pscustomobject]@{ tick = 4; event = 'SUCCEEDED'; detail = 'succeeded' }
        )
        effects = @([pscustomobject]@{
                seq = 1; node_id = "break_cobblestone_$Iteration"; kind = 'block_break'
                subject = 'block:minecraft:overworld:199,201,200'
                observed_before = [pscustomobject]@{
                    block = 'minecraft:cobblestone'; properties = [pscustomobject]@{}
                    expected_drop = 'minecraft:cobblestone'
                    minimum_inventory_count = $Iteration
                }
                observed_after = [pscustomobject]@{
                    block = 'minecraft:air'; properties = [pscustomobject]@{}
                    inventory_count = $Iteration
                }
                verification = $Verification
                client_tick = 100L + $Iteration; world_revision = 20L + $Iteration
            })
        partial = [pscustomobject]@{
            has_confirmed_effects = $true; interrupted_node_id = $null
            remaining_node_upper_bound = 0; resume_requires_reobservation = $false
        }
        source = [pscustomobject]@{}
        template = [pscustomobject]@{}
        reference_requirements = @()
    }
}

[void](Assert-CobblestoneBreakTerminal -Terminal (New-MockBreakTerminal -Iteration 1) `
        -Iteration 1)
$noEffect = New-MockBreakTerminal -Iteration 1
$noEffect.effects = @()
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $noEffect -Iteration 1 } `
    'terminal without an effect was accepted'
$unknownEffect = New-MockBreakTerminal -Iteration 1 -Verification 'unknown'
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $unknownEffect -Iteration 1 } `
    'unknown break effect was accepted'
$moved = New-MockBreakTerminal -Iteration 1
$moved.progress.distance_travelled = 0.1
Assert-Throws { Assert-CobblestoneBreakTerminal -Terminal $moved -Iteration 1 } `
    'moving break terminal was accepted'

$script:ToolTransport = {
    param($Tool, $Arguments)
    if ($Tool -cne 'agent_get_observation') { throw "unexpected empty-page tool: $Tool" }
    [pscustomobject]@{
        frame_id = $Arguments.frame_id; records = $null; next_cursor = $null
    }
}
$emptyRecords = @(Get-RecordsFromState -State (New-MockCobblestoneState `
            -CobblestoneCount 0) -Kinds @('visible_surface') -Filter $null)
Assert-True ($emptyRecords.Count -eq 0) `
    'a Windows PowerShell null materialization was not treated as an empty page'
$emptySurfaces = @(Get-VisibleSurfaceRecords -State (New-MockCobblestoneState `
            -CobblestoneCount 0) -Block 'minecraft:cobblestone' `
        -Bounds $script:CobbleTargetBounds -Faces @('north') -AllowMissing)
Assert-True ($emptySurfaces.Count -eq 0) `
    'an allowed missing surface emitted a null pipeline element'
$script:ToolTransport = $null

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:MockCompleted = 0
$script:MockPendingIteration = 0
Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
        protocol_version = $script:ProtocolVersion; tools = @($script:AllowedTools)
    })
$script:DelayTransport = { param($Seconds) }
$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' {
            New-MockCobblestoneState -CobblestoneCount $script:MockCompleted
        }
        'agent_get_observation' {
            $kinds = @($Arguments.kinds)
            $records = if ($kinds.Count -eq 1 -and $kinds[0] -ceq 'visible_surface') {
                @(New-MockCobblestoneSurface -Revision (20L + $script:MockCompleted))
            } else { @() }
            [pscustomobject]@{
                schema_version = 1
                frame_id = 'obs-' + ([long]$script:MockCompleted).ToString('x16')
                frame_completed_tick = 100L + $script:MockCompleted
                visible_entities_truncated = $false
                records = $records; next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $submitted = $Arguments.program.body[0]
            $expected = $script:MockCompleted + 1
            if ($submitted.op -cne 'break_known_block' -or
                [int]$submitted.minimum_inventory_count -ne $expected -or
                $submitted.target.x -ne 199 -or $submitted.target.y -ne 201 -or
                $submitted.target.z -ne 200) {
                throw 'mock received a stale or malformed cobblestone break'
            }
            $script:MockPendingIteration = $expected
            [pscustomobject]@{
                schema_version = 1
                action_id = '550e8400-e29b-41d4-a716-' + $expected.ToString('000000000000')
                state = 'queued'
            }
        }
        'agent_get_action' {
            if ($script:MockPendingIteration -lt 1) { throw 'mock has no pending break' }
            $iteration = $script:MockPendingIteration
            $script:MockCompleted = $iteration
            $script:MockPendingIteration = 0
            New-MockBreakTerminal -Iteration $iteration
        }
        'agent_cancel_action' { throw 'mock should not cancel a successful break' }
        default { throw "unexpected cobblestone mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpCobblestoneGeneratorCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase9-cobblestone-generator') `
        'gate result name is wrong'
    Assert-True ($result.gate_result.lifecycle.accepted -eq 8 -and
        $result.gate_result.lifecycle.terminal -eq 8 -and
        $result.gate_result.lifecycle.unique_frame_ids -eq 8) `
        'eight fresh single-Action cycles were not proven'
    Assert-True ($result.gate_result.terminal_effects.Count -eq 8) `
        'eight confirmed break effects were not retained'
    Assert-True ($result.gate_result.online_oracle.cobblestone_delta -eq 8) `
        'online inventory oracle is not +8'
    Assert-True ($result.gate_result.external_oracle.player.health -eq 20.0) `
        'offline oracle did not bind the observed health baseline'
    Assert-True ([bool]$result.input_release.control_ready -and
        [bool]$result.input_release.all_actions_terminal) `
        'input release was not proven'
    foreach ($name in @('gate-events.jsonl', 'gate-result.json',
            'external-oracle-manifest.json')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $artifactDirectory $name)) `
            "missing artifact $name"
    }
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed' -and [bool]$manifest.fixed_five_only -and
        [bool]$manifest.normal_player_actions_only) `
        'artifact manifest weakened the acceptance boundary'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP cobblestone generator capability gate mock tests passed.'
