[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$fishingArtifactDirectory = $ArtifactDirectory
$fishingTokenPath = $TokenPath
$fishingEndpoint = $Endpoint
$fishingLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $fishingArtifactDirectory `
    -TokenPath $fishingTokenPath -Endpoint $fishingEndpoint -LibraryOnly
$ArtifactDirectory = $fishingArtifactDirectory
$TokenPath = $fishingTokenPath
$Endpoint = $fishingEndpoint
$LibraryOnly = $fishingLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null
$script:FishingWaterTarget = [ordered]@{
    dimension = 'minecraft:overworld'; x = 199; y = 202; z = 200
}
$script:FishingTargetBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 199; min_y = 202; min_z = 200
    max_x = 199; max_y = 202; max_z = 200
}
$script:FishingWorkspaceBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 193; min_y = 199; min_z = 194
    max_x = 205; max_y = 207; max_z = 206
}
$script:FishingSoundBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min = [ordered]@{ x = 194.0; y = 200.0; z = 195.0 }
    max = [ordered]@{ x = 205.0; y = 204.0; z = 206.0 }
}
$script:FishingExpectedStand = [ordered]@{ x = 199.5; y = 203.0; z = 194.5 }
$script:FishingLootItems = @(
    'minecraft:bamboo', 'minecraft:bone', 'minecraft:book', 'minecraft:bow',
    'minecraft:bowl', 'minecraft:cod', 'minecraft:fishing_rod',
    'minecraft:ink_sac', 'minecraft:leather', 'minecraft:leather_boots',
    'minecraft:lily_pad', 'minecraft:name_tag', 'minecraft:nautilus_shell',
    'minecraft:potion', 'minecraft:pufferfish', 'minecraft:rotten_flesh',
    'minecraft:saddle', 'minecraft:salmon', 'minecraft:stick', 'minecraft:string',
    'minecraft:tripwire_hook', 'minecraft:tropical_fish'
)

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-fishing-capability-gate'; version = '1'
        }
    }
}

function Assert-FishingFixedFive {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) {
        throw 'fishing gate requires exactly one fixed-five verification event'
    }
    $tools = @((Get-ObjectProperty $events[0] 'tools'))
    if ((Get-ObjectProperty $events[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'fishing gate fixed-five protocol or tool count mismatch'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "fishing gate fixed-five tool order mismatch at index $index"
        }
    }
    return [ordered]@{ protocol_version = $script:ProtocolVersion; tools = $tools }
}

function Assert-FishingPlayerState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase
    )
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    foreach ($axis in @('x', 'y', 'z')) {
        if ([Math]::Abs([double](Get-ObjectProperty $position $axis) -
                [double]$script:FishingExpectedStand[$axis]) -gt 0.0001) {
            throw "$Phase changed the stationary player $axis coordinate"
        }
    }
    if ([Math]::Abs([double](Get-ObjectProperty $world 'health') - $ExpectedHealth) -gt 0.0001) {
        throw "$Phase changed player health"
    }
}

function Get-FishingLootSummary {
    param([Parameter(Mandatory)][object]$State)
    $lootCount = 0L
    $rodCount = 0L
    $unexpected = [Collections.Generic.List[object]]::new()
    foreach ($stack in @(Get-ObjectProperty $State 'inventory')) {
        $item = [string](Get-ObjectProperty $stack 'item')
        $count = [long](Get-ObjectProperty $stack 'count')
        if ($item -ceq 'minecraft:fishing_rod') {
            $rodCount += $count
            continue
        }
        if ($item -cin $script:FishingLootItems) {
            $lootCount += $count
        } else {
            $unexpected.Add([ordered]@{ item = $item; count = $count })
        }
    }
    if ($rodCount -gt 1) { $lootCount += $rodCount - 1 }
    return [ordered]@{
        primary_rod_present = $rodCount -ge 1
        rod_count = $rodCount
        loot_item_count = $lootCount
        unexpected_inventory = @($unexpected)
    }
}

function Get-OnlyFishingWaterSurface {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') `
        -Filter ([ordered]@{
            block_ids = @('minecraft:water'); faces = @('up')
            position_bounds = $script:FishingTargetBounds
        }))
    if ($records.Count -ne 1) {
        throw "fishing gate requires one fresh central source-water face; found=$($records.Count)"
    }
    $surface = $records[0]
    $position = Get-ObjectProperty $surface 'position'
    $state = Get-ObjectProperty $surface 'state'
    $properties = Get-ObjectProperty $state 'properties'
    if ((ConvertTo-CompactJson $position) -cne
            (ConvertTo-CompactJson $script:FishingWaterTarget) -or
        (Get-ObjectProperty $surface 'face') -cne 'up' -or
        (Get-ObjectProperty $state 'block') -cne 'minecraft:water' -or
        (Get-ObjectProperty $properties 'level') -cne '0') {
        throw 'fishing cast evidence is not the exact delivered central source-water top face'
    }
    return $surface
}

# These three builders are the only runner locations coupled to the production fishing opcodes.
function New-FishingCastRequest {
    param([Parameter(Mandatory)][object]$Surface)
    $target = Get-ObjectProperty $Surface 'position'
    $state = Get-ObjectProperty $Surface 'state'
    $face = [string](Get-ObjectProperty $Surface 'face')
    $node = [ordered]@{
        id = 'cast_line'; op = 'cast_known_fishing_rod'
        hand = 'main_hand'; rod_item = 'minecraft:fishing_rod'
        target = $target; face = $face; expected_state = $state
    }
    if (-not [object]::ReferenceEquals($target, $node.target) -or
        -not [object]::ReferenceEquals($state, $node.expected_state)) {
        throw 'fishing cast request changed delivery-backed water evidence'
    }
    New-ActionRequest -Name 'capability_gate_fishing_cast' `
        -Capabilities @('camera', 'item_use') -Body @($node) -Budget ([ordered]@{
            max_duration_ms = 15000; max_ticks = 300
            max_distance_blocks = 0; max_camera_degrees = 360
            max_interactions = 2; max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function New-FishingSoundWaitRequest {
    param([Parameter(Mandatory)][long]$SinceTick)
    New-ActionRequest -Name 'capability_gate_fishing_bite_wait' -Capabilities @() `
        -Body @([ordered]@{
            id = 'wait_for_bite'; op = 'wait_until'; max_ticks = 720
            condition = [ordered]@{
                type = 'sound_clue'
                sound_event = 'minecraft:entity.fishing_bobber.splash'
                since_tick = $SinceTick
                bounds = $script:FishingSoundBounds
            }
        }) -Budget ([ordered]@{
            max_duration_ms = 36000; max_ticks = 720
            max_distance_blocks = 0; max_camera_degrees = 0
            max_interactions = 0; max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function New-FishingReelRequest {
    param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{24}$')][string]$SessionRef)
    New-ActionRequest -Name 'capability_gate_fishing_reel' -Capabilities @('item_use') `
        -Body @([ordered]@{
            id = 'reel_line'; op = 'reel_known_fishing_session'
            fishing_session_ref = $SessionRef
            hand = 'main_hand'; rod_item = 'minecraft:fishing_rod'
        }) -Budget ([ordered]@{
            max_duration_ms = 4000; max_ticks = 80
            max_distance_blocks = 0; max_camera_degrees = 0
            max_interactions = 1; max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function New-FishingCollectRequest {
    param([Parameter(Mandatory)][object]$ItemRecord)
    $displayedItem = [string](Get-ObjectProperty $ItemRecord 'displayed_item')
    $position = Get-ObjectProperty $ItemRecord 'position'
    if ($displayedItem -cnotin $script:FishingLootItems) {
        throw 'visible post-reel item is not one Vanilla fishing-loot candidate'
    }
    New-ActionRequest -Name 'capability_gate_fishing_collect' -Capabilities @('movement') `
        -Body @([ordered]@{
            id = 'collect_fishing_loot'; op = 'collect_visible_item'
            displayed_item = $displayedItem; target = $position
        }) -Budget ([ordered]@{
            max_duration_ms = 30000; max_ticks = 600
            max_distance_blocks = 8; max_camera_degrees = 0
            max_interactions = 0; max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function Assert-FishingTerminalBudget {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [Parameter(Mandatory)][string]$Phase,
        [Parameter(Mandatory)][int]$ExpectedInteractions,
        [Parameter(Mandatory)][double]$MaximumCamera
    )
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne $ExpectedInteractions -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -gt $MaximumCamera) {
        throw "$Phase terminal violates its stationary one-node budget"
    }
}

function Get-FishingSessionProof {
    param([Parameter(Mandatory)][object]$Terminal)
    Assert-FishingTerminalBudget -Terminal $Terminal -Phase 'cast' `
        -ExpectedInteractions 1 -MaximumCamera 360
    $proofs = [Collections.Generic.List[object]]::new()
    foreach ($effect in @((Get-ObjectProperty $Terminal 'effects'))) {
        $after = Get-ObjectProperty $effect 'observed_after'
        $reference = [string](Get-ObjectProperty $after 'fishing_session_ref')
        if ((Get-ObjectProperty $effect 'node_id') -ceq 'cast_line' -and
            (Get-ObjectProperty $effect 'verification') -ceq 'confirmed' -and
            $reference -cmatch '^[A-Za-z0-9_-]{24}$') {
            $proof = [ordered]@{
                fishing_session_ref = $reference
                client_tick = [long](Get-ObjectProperty $effect 'client_tick')
                world_revision = Get-ObjectProperty $effect 'world_revision'
            }
            $proofs.Add([object]$proof)
        }
    }
    if ($proofs.Count -ne 1) {
        throw 'cast terminal did not publish exactly one confirmed fishing_session_ref'
    }
    return $proofs[0]
}

function Get-VisibleFishingLoot {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:item')
            displayed_items = @($script:FishingLootItems)
            position_bounds = $script:FishingWorkspaceBounds
        }))
    if ($records.Count -gt 1) {
        throw 'one fishing cycle exposed more than one loose fishing-loot entity'
    }
    if ($records.Count -eq 1) { return $records[0] }
    return $null
}

function Assert-NoVisibleFishingEntities {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:item', 'minecraft:fishing_bobber')
            position_bounds = $script:FishingWorkspaceBounds
        }))
    if ($records.Count -ne 0) {
        throw "fishing gate left $($records.Count) visible bobber/item entities"
    }
}

function New-FishingOfflineOracleManifest {
    [ordered]@{
        schema_version = 1
        oracle = 'offline-vanilla-fishing-world'
        inspector = 'tools/eval/Inspect-McmcpFishingOracle.py'
        world_closed_required = $true
        fixture_precondition = '/mcmcp_fixture phase5 fishing'
        workspace = $script:FishingWorkspaceBounds
        player = [ordered]@{
            position = $script:FishingExpectedStand; health = 16.0
            primary_fishing_rod_count = 1; primary_fishing_rod_damage = 1
            primary_fishing_rod_enchanted = $false
        }
        vanilla_fishing_loot_minimum = 1
        allowed_loot_items = @($script:FishingLootItems)
        owned_bobber_count = 0
        loose_item_count = 0
        pool_and_workspace_unchanged = $true
        fixture_tick_mutation_after_t0 = $false
    }
}

function Invoke-FishingGateCore {
    $fixedFive = Assert-FishingFixedFive
    $initial = Get-FreshState
    $initialHealth = [double](Get-ObjectProperty (Get-ObjectProperty $initial 'world') 'health')
    if ([Math]::Abs($initialHealth - 16.0) -gt 0.0001) {
        throw 'fishing fixture initial health is not 16'
    }
    Assert-FishingPlayerState -State $initial -ExpectedHealth $initialHealth -Phase 'initial state'
    $initialInventory = Get-FishingLootSummary -State $initial
    if (-not $initialInventory.primary_rod_present -or $initialInventory.rod_count -ne 1 -or
        $initialInventory.loot_item_count -ne 0 -or
        $initialInventory.unexpected_inventory.Count -ne 0) {
        throw 'fishing fixture requires only one pristine baseline rod and no loot candidate'
    }
    Assert-NoVisibleFishingEntities -State $initial
    $surface = Get-OnlyFishingWaterSurface -State $initial

    $castTerminal = Invoke-ActionRequest -Request (New-FishingCastRequest -Surface $surface) `
        -WallTimeoutSeconds 30
    $session = Get-FishingSessionProof -Terminal $castTerminal
    Add-GateEvent -Event 'fishing_session_issued' -Detail ([ordered]@{
            client_tick = $session.client_tick; world_revision = $session.world_revision
        })

    $waitTerminal = Invoke-ActionRequest `
        -Request (New-FishingSoundWaitRequest -SinceTick $session.client_tick) `
        -WallTimeoutSeconds 50
    Assert-FishingTerminalBudget -Terminal $waitTerminal -Phase 'bite wait' `
        -ExpectedInteractions 0 -MaximumCamera 0
    Add-GateEvent -Event 'fishing_bite_sound_confirmed' -Detail ([ordered]@{
            sound_event = 'minecraft:entity.fishing_bobber.splash'
            since_tick = $session.client_tick; max_ticks = 720
        })

    $reelTerminal = Invoke-ActionRequest `
        -Request (New-FishingReelRequest -SessionRef $session.fishing_session_ref) `
        -WallTimeoutSeconds 15
    Assert-FishingTerminalBudget -Terminal $reelTerminal -Phase 'reel' `
        -ExpectedInteractions 1 -MaximumCamera 0

    $final = Get-FreshState
    $loot = Get-FishingLootSummary -State $final
    $collectionUsed = $false
    if ($loot.loot_item_count -lt 1) {
        $visible = Get-VisibleFishingLoot -State $final
        if ($null -eq $visible) {
            throw 'successful reel produced neither inventory loot nor one visible collectible'
        }
        [void](Invoke-ActionRequest -Request (New-FishingCollectRequest -ItemRecord $visible) `
            -WallTimeoutSeconds 45)
        $collectionUsed = $true
        $final = Get-FreshState
        $loot = Get-FishingLootSummary -State $final
    }
    if ($loot.loot_item_count -lt 1 -or $loot.unexpected_inventory.Count -ne 0) {
        throw 'fishing inventory did not gain one allowed Vanilla fishing loot'
    }
    Assert-FishingPlayerState -State $final -ExpectedHealth $initialHealth -Phase 'final state'
    Assert-NoVisibleFishingEntities -State $final

    return [ordered]@{
        gate = 'phase9-fishing'
        fixture_precondition = '/mcmcp_fixture phase5 fishing'
        fixed_five_surface = $fixedFive
        normal_player_actions_only = $true
        action_boundary = 'fresh_water_then_cast_sound_bound_wait_reel_optional_visible_collect'
        finite_timeout = [ordered]@{ bite_wait_ticks = 720; bite_wait_wall_seconds = 50 }
        online_oracle = [ordered]@{
            fishing_loot_count = $loot.loot_item_count
            collection_used = $collectionUsed
            player_position_unchanged = $true
            player_health_unchanged = $true
            visible_bobber_or_item_entities = 0
        }
        external_oracle_status = 'pending_world_close'
        external_oracle = New-FishingOfflineOracleManifest
    }
}

function Write-FishingArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1; gate = 'phase9-fishing'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools); fixed_five_only = $true
        normal_player_actions_only = $true; public_input_release = $InputRelease
        result = $GateResult
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{ type = $Failure.Exception.GetType().FullName; message = $Failure.Exception.Message }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($null -ne $GateResult) {
        [IO.File]::WriteAllText(
            (Join-Path $ArtifactDirectory 'external-oracle-manifest.json'),
            (ConvertTo-Json $GateResult.external_oracle -Depth 100), $script:Utf8NoBom)
    }
}

function Invoke-McmcpFishingCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-FishingGateCore } catch { $primaryFailure = $_ }
    finally { try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ } }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-FishingArtifacts -GateResult $gateResult -InputRelease $release -Failure $reportedFailure
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
    $result = Invoke-McmcpFishingCapabilityGate
    ConvertTo-Json $result -Depth 100
}
