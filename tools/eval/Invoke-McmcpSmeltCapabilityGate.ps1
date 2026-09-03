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
# The station gate has its own admission, oracle, artifacts, and executable entry point.
$smeltArtifactDirectory = $ArtifactDirectory
$smeltTokenPath = $TokenPath
$smeltEndpoint = $Endpoint
$smeltLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $smeltArtifactDirectory `
    -TokenPath $smeltTokenPath -Endpoint $smeltEndpoint -LibraryOnly
$ArtifactDirectory = $smeltArtifactDirectory
$TokenPath = $smeltTokenPath
$Endpoint = $smeltEndpoint
$LibraryOnly = $smeltLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

$script:SmeltStationBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 196; min_y = 200; min_z = 194
    max_x = 196; max_y = 200; max_z = 194
}

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-smelt-capability-gate'
            version = '1'
        }
    }
}

function Test-SmeltObjectProperty {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)

    if ($null -eq $Object) { return $false }
    if ($Object -is [Collections.IDictionary]) { return $Object.Contains($Name) }
    return @($Object.PSObject.Properties | Where-Object Name -CEQ $Name).Count -eq 1
}

function Get-SmeltRecipeState {
    $state = Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{
            query = [ordered]@{
                kind = 'result_item'
                item = 'minecraft:iron_ingot'
            }
            max_results = 8
        })
    Assert-ReadyState -State $state -Phase 'smelt recipe acquisition'
    return $state
}

function Get-OnlySmeltRecipe {
    param([Parameter(Mandatory)][object]$State)

    $query = Get-ObjectProperty $State 'recipe_query'
    if ($null -eq $query) { throw 'smelt gate did not receive a recipe query result' }
    $coverage = Get-ObjectProperty $query 'coverage'
    foreach ($field in @('source', 'complete', 'matched', 'returned', 'truncated')) {
        if (-not (Test-SmeltObjectProperty -Object $coverage -Name $field)) {
            throw "smelt recipe coverage omitted required field: $field"
        }
    }
    $matched = [int](Get-ObjectProperty $coverage 'matched')
    $returned = [int](Get-ObjectProperty $coverage 'returned')
    if ((Get-ObjectProperty $coverage 'source') -cne 'client_known_recipe_displays' -or
        [bool](Get-ObjectProperty $coverage 'complete') -or
        $matched -lt 1 -or $returned -ne $matched -or $returned -gt 8 -or
        [bool](Get-ObjectProperty $coverage 'truncated')) {
        throw 'smelt gate requires a bounded untruncated client-known iron-ingot result set'
    }
    $recipes = @((Get-ObjectProperty $query 'recipes'))
    if ($recipes.Count -ne $returned) {
        throw 'smelt recipe coverage count does not match the returned recipe array'
    }
    # Vanilla exposes both smelting and blasting for the same input/output. A
    # modpack may add more result variants. Select by the requested station
    # contract, then require that slice to be unique instead of assuming the
    # broad result_item query itself has exactly one match.
    $candidates = @(foreach ($candidate in $recipes) {
            $candidateResult = Get-ObjectProperty $candidate 'result'
            $candidateOutputs = @((Get-ObjectProperty $candidateResult 'alternatives'))
            $candidateIngredients = @((Get-ObjectProperty $candidate 'ingredients'))
            $candidateInputs = @(if ($candidateIngredients.Count -eq 1) {
                    @((Get-ObjectProperty $candidateIngredients[0] 'alternatives'))
                })
            if ((Get-ObjectProperty $candidate 'display_kind') -ceq 'smelting' -and
                (Get-ObjectProperty $candidate 'required_screen') -ceq 'furnace' -and
                [bool](Get-ObjectProperty $candidate 'supported') -and
                $null -eq (Get-ObjectProperty $candidate 'unsupported_reason') -and
                [bool](Get-ObjectProperty $candidateResult 'deterministic') -and
                $candidateOutputs.Count -eq 1 -and
                (Get-ObjectProperty $candidateOutputs[0] 'item') -ceq 'minecraft:iron_ingot' -and
                [int](Get-ObjectProperty $candidateOutputs[0] 'count') -eq 1 -and
                $candidateIngredients.Count -eq 1 -and
                [int](Get-ObjectProperty $candidateIngredients[0] 'count_per_craft') -eq 1 -and
                $candidateInputs.Count -eq 1 -and
                (Get-ObjectProperty $candidateInputs[0] 'item') -ceq 'minecraft:raw_iron') {
                $candidate
            }
        })
    if ($candidates.Count -ne 1) {
        throw "smelt gate requires exactly one supported furnace-smelting candidate; found=$($candidates.Count)"
    }
    $recipe = $candidates[0]
    $recipeRef = [string](Get-ObjectProperty $recipe 'recipe_ref')
    $fingerprint = [string](Get-ObjectProperty $recipe 'fingerprint')
    if ($recipeRef -cnotmatch '^[A-Za-z0-9_-]{24}$' -or
        $fingerprint -cnotmatch '^sha256:[0-9a-f]{64}$') {
        throw 'smelt recipe did not contain valid opaque identity fields'
    }

    $result = Get-ObjectProperty $recipe 'result'
    $alternatives = @((Get-ObjectProperty $result 'alternatives'))
    if (-not [bool](Get-ObjectProperty $result 'deterministic') -or
        $alternatives.Count -ne 1 -or
        (Get-ObjectProperty $alternatives[0] 'item') -cne 'minecraft:iron_ingot' -or
        [int](Get-ObjectProperty $alternatives[0] 'count') -ne 1) {
        throw 'smelt recipe does not deterministically produce one iron ingot'
    }
    $ingredients = @((Get-ObjectProperty $recipe 'ingredients'))
    if ($ingredients.Count -ne 1 -or
        [int](Get-ObjectProperty $ingredients[0] 'count_per_craft') -ne 1) {
        throw 'smelt recipe does not expose one exact input ingredient'
    }
    $inputAlternatives = @((Get-ObjectProperty $ingredients[0] 'alternatives'))
    if ($inputAlternatives.Count -ne 1 -or
        (Get-ObjectProperty $inputAlternatives[0] 'item') -cne 'minecraft:raw_iron') {
        throw 'smelt recipe input is not exactly raw iron'
    }
    return $recipe
}

function Get-OnlyVisibleFurnaceSurface {
    param([Parameter(Mandatory)][object]$State)

    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:furnace' `
        -Bounds $script:SmeltStationBounds -Faces $null)
    if ($records.Count -ne 1) {
        throw 'smelt gate requires exactly one visible furnace record at the fixture station'
    }
    $surface = $records[0]
    $position = Get-ObjectProperty $surface 'position'
    $state = Get-ObjectProperty $surface 'state'
    if ((Get-ObjectProperty $position 'dimension') -cne 'minecraft:overworld' -or
        [int](Get-ObjectProperty $position 'x') -ne 196 -or
        [int](Get-ObjectProperty $position 'y') -ne 200 -or
        [int](Get-ObjectProperty $position 'z') -ne 194 -or
        $null -eq $state -or
        (Get-ObjectProperty $state 'block') -cne 'minecraft:furnace') {
        throw 'visible furnace evidence does not match the phase5 smelt fixture'
    }
    return $surface
}

function Assert-SmeltInitialInventory {
    param([Parameter(Mandatory)][object]$State)

    $rawIron = Get-InventoryCount -State $State -Item 'minecraft:raw_iron'
    $coal = Get-InventoryCount -State $State -Item 'minecraft:coal'
    $ironIngot = Get-InventoryCount -State $State -Item 'minecraft:iron_ingot'
    if ($rawIron -ne 1 -or $coal -ne 1 -or $ironIngot -ne 0) {
        throw "smelt fixture inventory mismatch: raw_iron=$rawIron coal=$coal iron_ingot=$ironIngot"
    }
    return [ordered]@{
        'minecraft:raw_iron' = $rawIron
        'minecraft:coal' = $coal
        'minecraft:iron_ingot' = $ironIngot
    }
}

function New-SmeltActionRequest {
    param(
        [Parameter(Mandatory)][object]$Recipe,
        [Parameter(Mandatory)][object]$Surface,
        [ValidateRange(1, 64)][int]$InputCount = 1,
        [ValidateRange(1, 2304)][int]$MinimumOutputCount = 1
    )

    $deliveredRecipeRef = Get-ObjectProperty $Recipe 'recipe_ref'
    $deliveredFingerprint = Get-ObjectProperty $Recipe 'fingerprint'
    $deliveredTarget = Get-ObjectProperty $Surface 'position'
    $deliveredState = Get-ObjectProperty $Surface 'state'
    $node = [ordered]@{
        id = 'smelt_fixture_iron'
        op = 'smelt_known_recipe'
        recipe_ref = $deliveredRecipeRef
        recipe_fingerprint = $deliveredFingerprint
        goal = [ordered]@{
            item = 'minecraft:iron_ingot'
            stack_policy = 'default_components_only'
            minimum_inventory_count = $MinimumOutputCount
        }
        station = [ordered]@{
            kind = 'furnace'
            target = $deliveredTarget
            expected_state = $deliveredState
        }
        fuel = [ordered]@{
            item = 'minecraft:coal'
            stack_policy = 'default_components_only'
        }
        max_smelts = $InputCount
    }
    if (-not [object]::ReferenceEquals($deliveredTarget, $node.station.target) -or
        -not [object]::ReferenceEquals($deliveredState, $node.station.expected_state) -or
        $node.recipe_ref -cne $deliveredRecipeRef -or
        $node.recipe_fingerprint -cne $deliveredFingerprint) {
        throw 'smelt request changed delivery-backed recipe or station evidence'
    }
    $ticks = 2200L + 200L * $InputCount
    return New-ActionRequest -Name 'capability_gate_smelt_iron' `
        -Capabilities @('camera', 'inventory_transfer') -Body @($node) `
        -Budget ([ordered]@{
            max_duration_ms = 50L * $ticks
            max_ticks = $ticks
            max_distance_blocks = 0
            max_camera_degrees = 540
            max_interactions = 7
            max_blocks_broken = 0
            max_blocks_placed = 0
        })
}

function Assert-SmeltActionLifecycle {
    $accepted = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
        })
    $terminal = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal'
        })
    if ($accepted.Count -ne 1 -or $terminal.Count -ne 1) {
        throw "smelt gate requires one accepted and one terminal Action; accepted=$($accepted.Count) terminal=$($terminal.Count)"
    }
    $acceptedId = [string](Get-ObjectProperty $accepted[0] 'action_id')
    $terminalId = [string](Get-ObjectProperty $terminal[0] 'action_id')
    if ($acceptedId -cne $terminalId -or
        (Get-ObjectProperty $terminal[0] 'state') -cne 'succeeded') {
        throw 'smelt gate Action lifecycle did not close as accepted==succeeded-terminal'
    }
    return [ordered]@{
        accepted = $accepted.Count
        terminal = $terminal.Count
        action_id = $acceptedId
        accepted_equals_terminal = $true
    }
}

function Assert-SmeltFixedFiveSurfaceEvidence {
    $verified = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($verified.Count -ne 1) {
        throw "smelt gate requires exactly one fixed-five surface verification; found=$($verified.Count)"
    }
    $tools = @((Get-ObjectProperty $verified[0] 'tools'))
    if ((Get-ObjectProperty $verified[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'smelt fixed-five surface evidence has the wrong protocol or tool count'
    }
    for ($index = 0; $index -lt $script:AllowedTools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "smelt fixed-five surface evidence order mismatch at index $index"
        }
    }
    return [ordered]@{
        protocol_version = Get-ObjectProperty $verified[0] 'protocol_version'
        tools = $tools
        event_count = 1
    }
}

function Assert-SmeltTerminalProof {
    param([Parameter(Mandatory)][object]$Terminal)

    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure')) {
        throw 'smelt terminal proof requires a succeeded Action without failure'
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    $interactions = [int](Get-ObjectProperty $progress 'interactions')
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        $interactions -lt 1 -or $interactions -gt 7 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        throw 'smelt terminal progress exceeds the one-node stationary interaction contract'
    }
    $trace = @((Get-ObjectProperty $Terminal 'trace'))
    $completionEvidence = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'smelt_complete=minecraft:iron_ingot'
        })
    $nodeCompleted = @($trace | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_COMPLETED' -and
            (Get-ObjectProperty $_ 'detail') -ceq 'smelt_fixture_iron'
        })
    if ($completionEvidence.Count -ne 1 -or $nodeCompleted.Count -ne 1) {
        throw 'smelt terminal trace did not prove one iron-ingot completion and one completed node'
    }
    $evidenceIndex = [Array]::IndexOf($trace, $completionEvidence[0])
    $completedIndex = [Array]::IndexOf($trace, $nodeCompleted[0])
    if ($evidenceIndex -lt 0 -or $completedIndex -le $evidenceIndex) {
        throw 'smelt terminal trace did not publish completion evidence before node completion'
    }
    return [ordered]@{
        completion_evidence = Get-ObjectProperty $completionEvidence[0] 'detail'
        completed_node = Get-ObjectProperty $nodeCompleted[0] 'detail'
        executed_nodes = 1
        interactions = $interactions
        distance_travelled = 0
        blocks_broken = 0
        blocks_placed = 0
    }
}

function Invoke-SmeltGateCore {
    $fixedFive = Assert-SmeltFixedFiveSurfaceEvidence
    $initial = Get-SmeltRecipeState
    $inventoryBefore = Assert-SmeltInitialInventory -State $initial
    $recipe = Get-OnlySmeltRecipe -State $initial
    $surface = Get-OnlyVisibleFurnaceSurface -State $initial
    $request = New-SmeltActionRequest -Recipe $recipe -Surface $surface `
        -InputCount 1 -MinimumOutputCount 1
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 240

    $final = Get-FreshState
    $inventoryAfter = [ordered]@{
        'minecraft:raw_iron' = Get-InventoryCount -State $final -Item 'minecraft:raw_iron'
        'minecraft:coal' = Get-InventoryCount -State $final -Item 'minecraft:coal'
        'minecraft:iron_ingot' = Get-InventoryCount -State $final -Item 'minecraft:iron_ingot'
    }
    if ($inventoryAfter['minecraft:raw_iron'] -ne 0 -or
        $inventoryAfter['minecraft:coal'] -ne 0 -or
        $inventoryAfter['minecraft:iron_ingot'] -ne 1) {
        throw 'smelt output ledger is not raw_iron 1->0, coal 1->0, iron_ingot 0->1'
    }
    $lifecycle = Assert-SmeltActionLifecycle
    $terminalProof = Assert-SmeltTerminalProof -Terminal $terminal
    return [ordered]@{
        gate = 'phase5-smelt'
        fixture_precondition = '/mcmcp_fixture phase5 smelt'
        fixed_five_surface = $fixedFive
        lifecycle = $lifecycle
        opaque_recipe_identity_copied_verbatim = $true
        station_evidence_copied_verbatim = $true
        request_station_evidence_copied_from_delivery = $true
        evidence_basis = [ordered]@{
            observation_frame_id = Get-ObservationFrameId -State $initial
            recipe_query = Get-ObjectProperty `
                (Get-ObjectProperty $initial 'recipe_query') 'basis'
            surface_face = Get-ObjectProperty $surface 'face'
            surface_observed_tick = Get-ObjectProperty $surface 'observed_tick'
            surface_world_revision = Get-ObjectProperty $surface 'world_revision'
        }
        station = [ordered]@{
            kind = 'furnace'
            target = Get-ObjectProperty $surface 'position'
            expected_state = Get-ObjectProperty $surface 'state'
        }
        recipe = [ordered]@{
            recipe_ref = Get-ObjectProperty $recipe 'recipe_ref'
            fingerprint = Get-ObjectProperty $recipe 'fingerprint'
            display_kind = Get-ObjectProperty $recipe 'display_kind'
            required_screen = Get-ObjectProperty $recipe 'required_screen'
        }
        material_output_oracle = [ordered]@{
            source = 'fixture_exact_item_delta_plus_validated_action_trace'
            scope = @('minecraft:raw_iron', 'minecraft:coal', 'minecraft:iron_ingot')
            inventory_before = $inventoryBefore
            inventory_after = $inventoryAfter
            expected_deltas = [ordered]@{
                'minecraft:raw_iron' = -1
                'minecraft:coal' = -1
                'minecraft:iron_ingot' = 1
            }
            succeeded_action_contract = [ordered]@{
                action_id = Get-ObjectProperty $terminal 'action_id'
                state = Get-ObjectProperty $terminal 'state'
                terminal_proof = $terminalProof
                station_empty_after_close_reopen = $true
                cursor_empty = $true
                note = 'validated smelt_complete trace is emitted only after the runtime final full-content readback succeeds'
            }
        }
    }
}

function Write-SmeltGateArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )

    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $eventLines = @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ })
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'), $eventLines, $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'phase5-smelt'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools)
        fixed_five_only = $true
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

function Invoke-McmcpSmeltCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try {
        $gateResult = Invoke-SmeltGateCore
    } catch {
        $primaryFailure = $_
    } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-SmeltGateArtifacts -GateResult $gateResult -InputRelease $release `
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
    $result = Invoke-McmcpSmeltCapabilityGate
    ConvertTo-Json $result -Depth 100
}
