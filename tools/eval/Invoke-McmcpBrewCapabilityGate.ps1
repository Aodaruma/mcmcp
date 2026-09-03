[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TokenPath,

    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',

    # Dot-source the functions without touching the network. Used only by the mock test.
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Reuse only the fixed-five MCP transport, lifecycle, observation, and cleanup primitives.
$brewArtifactDirectory = $ArtifactDirectory
$brewTokenPath = $TokenPath
$brewEndpoint = $Endpoint
$brewLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $brewArtifactDirectory `
    -TokenPath $brewTokenPath -Endpoint $brewEndpoint -LibraryOnly
$ArtifactDirectory = $brewArtifactDirectory
$TokenPath = $brewTokenPath
$Endpoint = $brewEndpoint
$LibraryOnly = $brewLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

$script:BrewStationBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 197; min_y = 200; min_z = 194
    max_x = 197; max_y = 200; max_z = 194
}

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-brew-capability-gate'
            version = '1'
        }
    }
}

function Get-StandardPotionCount {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Item,
        [Parameter(Mandatory)][string]$Potion
    )

    $total = 0
    foreach ($entry in @((Get-ObjectProperty $State 'standard_potions'))) {
        if ((Get-ObjectProperty $entry 'item') -ceq $Item -and
            (Get-ObjectProperty $entry 'potion') -ceq $Potion) {
            $total += [int](Get-ObjectProperty $entry 'count')
        }
    }
    return $total
}

function Assert-BrewExactLedger {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][bool]$Completed
    )

    $inventory = @((Get-ObjectProperty $State 'inventory'))
    $potions = @((Get-ObjectProperty $State 'standard_potions'))
    $potionItems = Get-InventoryCount -State $State -Item 'minecraft:potion'
    $netherWart = Get-InventoryCount -State $State -Item 'minecraft:nether_wart'
    $blazePowder = Get-InventoryCount -State $State -Item 'minecraft:blaze_powder'
    $water = Get-StandardPotionCount -State $State `
        -Item 'minecraft:potion' -Potion 'minecraft:water'
    $awkward = Get-StandardPotionCount -State $State `
        -Item 'minecraft:potion' -Potion 'minecraft:awkward'

    if (-not $Completed) {
        if ($inventory.Count -ne 3 -or $potions.Count -ne 1 -or
            $potionItems -ne 3 -or $netherWart -ne 1 -or $blazePowder -ne 1 -or
            $water -ne 3 -or $awkward -ne 0) {
            throw 'brew fixture initial ledger is not exactly water potion 3, nether wart 1, blaze powder 1'
        }
    } elseif ($inventory.Count -ne 1 -or $potions.Count -ne 1 -or
        $potionItems -ne 3 -or $netherWart -ne 0 -or $blazePowder -ne 0 -or
        $water -ne 0 -or $awkward -ne 3) {
        throw 'brew fixture final ledger is not exactly awkward potion 3 with both ingredients consumed'
    }

    return [ordered]@{
        inventory = [ordered]@{
            'minecraft:potion' = $potionItems
            'minecraft:nether_wart' = $netherWart
            'minecraft:blaze_powder' = $blazePowder
        }
        standard_potions = [ordered]@{
            'minecraft:potion|minecraft:water' = $water
            'minecraft:potion|minecraft:awkward' = $awkward
        }
        inventory_row_count = $inventory.Count
        standard_potion_row_count = $potions.Count
    }
}

function Get-OnlyVisibleBrewingStandSurface {
    param([Parameter(Mandatory)][object]$State)

    $records = @(Get-VisibleSurfaceRecords -State $State `
        -Block 'minecraft:brewing_stand' -Bounds $script:BrewStationBounds -Faces $null)
    if ($records.Count -ne 1) {
        throw 'brew gate requires exactly one visible brewing-stand record at the fixture station'
    }
    $surface = $records[0]
    $position = Get-ObjectProperty $surface 'position'
    $deliveredBlock = Get-ObjectProperty $surface 'block'
    if ($deliveredBlock -cne 'minecraft:brewing_stand' -or
        (Get-ObjectProperty $position 'dimension') -cne 'minecraft:overworld' -or
        [int](Get-ObjectProperty $position 'x') -ne 197 -or
        [int](Get-ObjectProperty $position 'y') -ne 200 -or
        [int](Get-ObjectProperty $position 'z') -ne 194) {
        throw 'visible brewing-stand evidence does not match the phase5 brew fixture'
    }
    return $surface
}

function New-BrewActionRequest {
    param([Parameter(Mandatory)][object]$Surface)

    $deliveredTarget = Get-ObjectProperty $Surface 'position'
    $deliveredBlock = Get-ObjectProperty $Surface 'block'
    $node = [ordered]@{
        id = 'brew_fixture_awkward'
        op = 'brew_known_potion_batch'
        target = $deliveredTarget
        expected_block = $deliveredBlock
        input = [ordered]@{
            item = 'minecraft:potion'
            potion = 'minecraft:water'
            count = 3
        }
        ingredient_item = 'minecraft:nether_wart'
        fuel_item = 'minecraft:blaze_powder'
        expected_output = [ordered]@{
            item = 'minecraft:potion'
            potion = 'minecraft:awkward'
            count = 3
        }
    }
    if (-not [object]::ReferenceEquals($deliveredTarget, $node.target) -or
        $node.expected_block -cne $deliveredBlock) {
        throw 'brew request changed delivery-backed station evidence'
    }
    return New-ActionRequest -Name 'capability_gate_brew_awkward_potions' `
        -Capabilities @('camera', 'inventory_transfer') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 70000
            max_ticks = 1400
            max_distance_blocks = 0
            max_camera_degrees = 540
            max_interactions = 16
            max_blocks_broken = 0
            max_blocks_placed = 0
        })
}

function Assert-BrewActionLifecycle {
    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminal = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    if ($accepted.Count -ne 1 -or $terminal.Count -ne 1) {
        throw "brew gate requires one accepted and one terminal Action; accepted=$($accepted.Count) terminal=$($terminal.Count)"
    }
    $acceptedId = [string](Get-ObjectProperty $accepted[0] 'action_id')
    $terminalId = [string](Get-ObjectProperty $terminal[0] 'action_id')
    if ($acceptedId -cne $terminalId -or
        (Get-ObjectProperty $terminal[0] 'state') -cne 'succeeded') {
        throw 'brew gate Action lifecycle did not close as accepted==succeeded-terminal'
    }
    return [ordered]@{
        accepted = $accepted.Count
        terminal = $terminal.Count
        action_id = $acceptedId
        accepted_equals_terminal = $true
    }
}

function Assert-BrewFixedFiveSurfaceEvidence {
    $verified = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($verified.Count -ne 1) {
        throw "brew gate requires exactly one fixed-five surface verification; found=$($verified.Count)"
    }
    $tools = @((Get-ObjectProperty $verified[0] 'tools'))
    if ((Get-ObjectProperty $verified[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'brew fixed-five surface evidence has the wrong protocol or tool count'
    }
    for ($index = 0; $index -lt $script:AllowedTools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "brew fixed-five surface evidence order mismatch at index $index"
        }
    }
    return [ordered]@{
        protocol_version = Get-ObjectProperty $verified[0] 'protocol_version'
        tools = $tools
        event_count = 1
    }
}

function Assert-BrewTerminalProof {
    param([Parameter(Mandatory)][object]$Terminal)

    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'brew terminal proof requires a succeeded Action without failure'
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    $interactions = [int](Get-ObjectProperty $progress 'interactions')
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        $interactions -lt 1 -or $interactions -gt 16 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        throw 'brew terminal progress exceeds the one-node stationary interaction contract'
    }
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $completionEvidence = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'brewing_complete=3'
        })
    $nodeCompleted = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'brew_fixture_awkward'
        })
    if ($completionEvidence.Count -ne 1 -or $nodeCompleted.Count -ne 1) {
        throw 'brew terminal trace did not prove three brewed potions and one completed node'
    }
    $evidenceIndex = [Array]::IndexOf($trace, $completionEvidence[0])
    $completedIndex = [Array]::IndexOf($trace, $nodeCompleted[0])
    if ($evidenceIndex -lt 0 -or $completedIndex -le $evidenceIndex) {
        throw 'brew terminal trace did not publish completion evidence before node completion'
    }
    return [ordered]@{
        completion_evidence = Get-ObjectProperty $completionEvidence[0] 'detail'
        completed_node = Get-ObjectProperty $nodeCompleted[0] 'detail'
        executed_nodes = 1
        interactions = $interactions
        distance_travelled = 0
        blocks_broken = 0
        blocks_placed = 0
        station_empty_after_close_reopen = $true
        cursor_empty = $true
    }
}

function Invoke-BrewGateCore {
    $fixedFive = Assert-BrewFixedFiveSurfaceEvidence
    $initial = Get-FreshState
    $ledgerBefore = Assert-BrewExactLedger -State $initial -Completed:$false
    $surface = Get-OnlyVisibleBrewingStandSurface -State $initial
    $request = New-BrewActionRequest -Surface $surface
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 180

    $final = Get-FreshState
    $ledgerAfter = Assert-BrewExactLedger -State $final -Completed:$true
    $lifecycle = Assert-BrewActionLifecycle
    $terminalProof = Assert-BrewTerminalProof -Terminal $terminal
    return [ordered]@{
        gate = 'phase5-brew'
        fixture_precondition = '/mcmcp_fixture phase5 brew'
        fixed_five_surface = $fixedFive
        lifecycle = $lifecycle
        station_evidence_copied_verbatim = $true
        request_station_evidence_copied_from_delivery = $true
        evidence_basis = [ordered]@{
            observation_frame_id = Get-ObservationFrameId -State $initial
            surface_face = Get-ObjectProperty $surface 'face'
            surface_observed_tick = Get-ObjectProperty $surface 'observed_tick'
            surface_world_revision = Get-ObjectProperty $surface 'world_revision'
        }
        station = [ordered]@{
            kind = 'brewing_stand'
            target = Get-ObjectProperty $surface 'position'
            expected_block = Get-ObjectProperty $surface 'block'
        }
        batch = [ordered]@{
            input = [ordered]@{
                item = 'minecraft:potion'; potion = 'minecraft:water'; count = 3
            }
            ingredient_item = 'minecraft:nether_wart'
            fuel_item = 'minecraft:blaze_powder'
            expected_output = [ordered]@{
                item = 'minecraft:potion'; potion = 'minecraft:awkward'; count = 3
            }
        }
        material_output_oracle = [ordered]@{
            source = 'fixture_exact_item_and_standard_potion_delta_plus_validated_action_trace'
            inventory_before = $ledgerBefore.inventory
            inventory_after = $ledgerAfter.inventory
            standard_potions_before = $ledgerBefore.standard_potions
            standard_potions_after = $ledgerAfter.standard_potions
            exact_row_counts = [ordered]@{
                inventory_before = $ledgerBefore.inventory_row_count
                inventory_after = $ledgerAfter.inventory_row_count
                standard_potions_before = $ledgerBefore.standard_potion_row_count
                standard_potions_after = $ledgerAfter.standard_potion_row_count
            }
            expected_deltas = [ordered]@{
                inventory = [ordered]@{
                    'minecraft:potion' = 0
                    'minecraft:nether_wart' = -1
                    'minecraft:blaze_powder' = -1
                }
                standard_potions = [ordered]@{
                    'minecraft:potion|minecraft:water' = -3
                    'minecraft:potion|minecraft:awkward' = 3
                }
            }
            succeeded_action_contract = [ordered]@{
                action_id = Get-ObjectProperty $terminal 'action_id'
                state = Get-ObjectProperty $terminal 'state'
                terminal_proof = $terminalProof
                station_empty_after_close_reopen = $true
                cursor_empty = $true
                note = 'validated brewing_complete trace is emitted only after exact inventory, empty stand/cursor, final close, and input-release verification succeed'
            }
        }
    }
}

function Write-BrewGateArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )

    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $eventLines = @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ })
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'), $eventLines, $script:Utf8NoBom)
    $fixedFiveEvents = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'phase5-brew'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools)
        fixed_five_only = ($fixedFiveEvents.Count -eq 1)
        normal_player_actions_only = $true
        public_input_release = $InputRelease
        result = $GateResult
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{
                type = $Failure.Exception.GetType().FullName
                message = $Failure.Exception.Message
            }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($null -ne $GateResult) {
        [IO.File]::WriteAllText(
            (Join-Path $ArtifactDirectory 'material-output-oracle.json'),
            (ConvertTo-Json (Get-ObjectProperty $GateResult 'material_output_oracle') -Depth 100),
            $script:Utf8NoBom)
    }
}

function Invoke-McmcpBrewCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try {
        $gateResult = Invoke-BrewGateCore
    } catch {
        $primaryFailure = $_
    } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-BrewGateArtifacts -GateResult $gateResult -InputRelease $release `
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
    $result = Invoke-McmcpBrewCapabilityGate
    ConvertTo-Json $result -Depth 100
}
