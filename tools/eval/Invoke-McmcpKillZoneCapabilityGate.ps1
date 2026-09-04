[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [ValidateRange(10, 55)][int]$ConsentWaitSeconds = 55,
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$killZoneArtifactDirectory = $ArtifactDirectory
$killZoneTokenPath = $TokenPath
$killZoneEndpoint = $Endpoint
$killZoneConsentWaitSeconds = $ConsentWaitSeconds
$killZoneLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $killZoneArtifactDirectory `
    -TokenPath $killZoneTokenPath -Endpoint $killZoneEndpoint -LibraryOnly
$ArtifactDirectory = $killZoneArtifactDirectory
$TokenPath = $killZoneTokenPath
$Endpoint = $killZoneEndpoint
$ConsentWaitSeconds = $killZoneConsentWaitSeconds
$LibraryOnly = $killZoneLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null
$script:KillZoneArtifactInitialized = $false
$script:KillZoneInvocationStarted = $false
$script:KillZoneExpectedStand = [ordered]@{ x = 199.5; y = 200.0; z = 197.5 }
$script:KillZoneTargetBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min = [ordered]@{ x = 199.0; y = 200.0; z = 199.0 }
    max = [ordered]@{ x = 200.0; y = 202.0; z = 200.0 }
}
$script:KillZoneObservationBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = 199.0; min_y = 200.0; min_z = 199.0
    max_x = 200.0; max_y = 202.0; max_z = 200.0
}

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-kill-zone-capability-gate'; version = '1'
        }
    }
}

function Initialize-KillZoneArtifactDirectory {
    if ($script:KillZoneArtifactInitialized) { return }
    if (Test-Path -LiteralPath $ArtifactDirectory) {
        throw "kill-zone artifact directory must be new: $ArtifactDirectory"
    }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $script:KillZoneArtifactInitialized = $true
}

function Assert-KillZoneFixedFive {
    $events = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'fixed_five_surface_verified'
        })
    if ($events.Count -ne 1) {
        throw 'kill-zone gate requires exactly one fixed-five verification event'
    }
    $tools = @((Get-ObjectProperty $events[0] 'tools'))
    if ((Get-ObjectProperty $events[0] 'protocol_version') -cne $script:ProtocolVersion -or
        $tools.Count -ne $script:AllowedTools.Count) {
        throw 'kill-zone gate fixed-five protocol or tool count mismatch'
    }
    for ($index = 0; $index -lt $tools.Count; $index++) {
        if ($tools[$index] -cne $script:AllowedTools[$index]) {
            throw "kill-zone gate fixed-five tool order mismatch at index $index"
        }
    }
    return [ordered]@{ protocol_version = $script:ProtocolVersion; tools = $tools }
}

function Assert-KillZonePlayerState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][double]$ExpectedHealth,
        [Parameter(Mandatory)][string]$Phase
    )
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    foreach ($axis in @('x', 'y', 'z')) {
        if ([Math]::Abs([double](Get-ObjectProperty $position $axis) -
                [double]$script:KillZoneExpectedStand[$axis]) -gt 0.0001) {
            throw "$Phase changed the stationary player $axis coordinate"
        }
    }
    if ([Math]::Abs([double](Get-ObjectProperty $world 'health') - $ExpectedHealth) -gt 0.0001) {
        throw "$Phase changed player health"
    }
}

function Assert-KillZoneInventory {
    param([Parameter(Mandatory)][object]$State, [switch]$Initial)
    $swords = 0L
    $other = 0L
    foreach ($stack in @(Get-ObjectProperty $State 'inventory')) {
        $count = [long](Get-ObjectProperty $stack 'count')
        if ((Get-ObjectProperty $stack 'item') -ceq 'minecraft:stone_sword') {
            $swords += $count
        } else {
            $other += $count
        }
    }
    if ($swords -ne 1 -or ($Initial -and $other -ne 0)) {
        throw 'kill-zone fixture requires one stone sword and no other initial inventory'
    }
}

function Get-OnlyKillZoneTarget {
    param([Parameter(Mandatory)][object]$State)
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:armor_stand')
            position_bounds = $script:KillZoneObservationBounds
        }))
    if ($records.Count -ne 1) {
        throw "kill-zone gate requires exactly one visible armor stand; found=$($records.Count)"
    }
    $target = $records[0]
    if ((Get-ObjectProperty $target 'entity_type') -cne 'minecraft:armor_stand' -or
        [string](Get-ObjectProperty $target 'entity_ref') -cnotmatch '^[A-Za-z0-9_-]{24}$') {
        throw 'kill-zone target is not one fresh opaque armor-stand observation'
    }
    $box = Get-ObjectProperty $target 'aabb'
    if ([double](Get-ObjectProperty $box 'min_x') -lt 199.0 -or
        [double](Get-ObjectProperty $box 'min_y') -lt 200.0 -or
        [double](Get-ObjectProperty $box 'min_z') -lt 199.0 -or
        [double](Get-ObjectProperty $box 'max_x') -gt 200.0 -or
        [double](Get-ObjectProperty $box 'max_y') -gt 202.0 -or
        [double](Get-ObjectProperty $box 'max_z') -gt 200.0) {
        throw 'visible armor-stand AABB is not wholly inside the declared kill zone'
    }
    return $target
}

function New-KillZoneRequest {
    param([AllowNull()][object]$ConsentRef)
    if ($null -ne $ConsentRef -and
        ([string]$ConsentRef -cnotmatch '^[A-Za-z0-9_-]{24}$')) {
        throw 'kill-zone consent ref must be null or one exact opaque reference'
    }
    New-ActionRequest -Name 'capability_gate_kill_zone' -Capabilities @('entity_attack') `
        -Body @([ordered]@{
            id = 'attack_once'; op = 'operate_kill_zone'
            target_kill_zone_bounds = $script:KillZoneTargetBounds
            entity_type_allowlist = @('minecraft:armor_stand')
            main_hand_item = 'minecraft:stone_sword'
            consent_ref = $ConsentRef
            max_attacks = 1
            minimum_interval_ticks = 10
            max_operation_duration_ticks = 200
        }) -Budget ([ordered]@{
            max_duration_ms = 10500; max_ticks = 210
            max_distance_blocks = 0; max_camera_degrees = 0
            max_interactions = 1; max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function Assert-AwaitingKillZoneConsent {
    param([Parameter(Mandatory)][object]$Receipt)
    $hash = [string](Get-ObjectProperty $Receipt 'policy_binding_hash')
    if ((Get-ObjectProperty $Receipt 'state') -cne 'AWAITING_CONSENT' -or
        $hash -cnotmatch '^sha256:[0-9a-f]{64}$' -or
        (Get-ObjectProperty $Receipt 'action_reserved') -isnot [bool] -or
        [bool](Get-ObjectProperty $Receipt 'action_reserved') -or
        (Get-ObjectProperty $Receipt 'input_acquired') -isnot [bool] -or
        [bool](Get-ObjectProperty $Receipt 'input_acquired') -or
        $null -ne (Get-ObjectProperty $Receipt 'action_id')) {
        throw 'first kill-zone start did not return the non-owning AWAITING_CONSENT receipt'
    }
    return $hash
}

function Assert-ConsentBoundsEqual {
    param(
        [Parameter(Mandatory)][object]$Actual,
        [Parameter(Mandatory)][double[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    $names = @('min_x', 'min_y', 'min_z', 'max_x', 'max_y', 'max_z')
    for ($index = 0; $index -lt $names.Count; $index++) {
        if ([Math]::Abs([double](Get-ObjectProperty $Actual $names[$index]) -
                $Expected[$index]) -gt 0.0001) {
            throw "$Label differs at $($names[$index])"
        }
    }
}

function Wait-ForPhysicalKillZoneGrant {
    param([Parameter(Mandatory)][string]$PolicyHash)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $polls = 0
    do {
        $polls++
        $state = Get-FreshState
        $consent = Get-ObjectProperty $state 'entity_attack_consent'
        $consentState = [string](Get-ObjectProperty $consent 'state')
        if ($consentState -ceq 'granted') {
            $reference = [string](Get-ObjectProperty $consent 'consent_ref')
            $scope = Get-ObjectProperty $consent 'scope'
            $mainHand = Get-ObjectProperty $scope 'main_hand'
            if ((Get-ObjectProperty $consent 'policy_binding_hash') -cne $PolicyHash -or
                $reference -cnotmatch '^[A-Za-z0-9_-]{24}$' -or
                [long](Get-ObjectProperty $consent 'valid_before_tick') -le 0 -or
                (Get-ObjectProperty $scope 'dimension') -cne 'minecraft:overworld' -or
                @((Get-ObjectProperty $scope 'entity_type_allowlist')).Count -ne 1 -or
                @((Get-ObjectProperty $scope 'entity_type_allowlist'))[0] -cne
                    'minecraft:armor_stand' -or
                (Get-ObjectProperty $mainHand 'item') -cne 'minecraft:stone_sword' -or
                -not [bool](Get-ObjectProperty $mainHand 'attack_effects_bound') -or
                (Get-ObjectProperty $scope 'side_effect_profile') -cne 'vanilla_sweep' -or
                -not [bool](Get-ObjectProperty $scope 'structure_bound') -or
                [int](Get-ObjectProperty $scope 'max_attacks') -ne 1 -or
                [long](Get-ObjectProperty $scope 'minimum_interval_ticks') -ne 10 -or
                [long](Get-ObjectProperty $scope 'max_operation_duration_ticks') -ne 200) {
                throw 'physical Grant is not bound to the exact requested kill-zone policy'
            }
            Assert-ConsentBoundsEqual `
                -Actual (Get-ObjectProperty $scope 'target_kill_zone_bounds') `
                -Expected @(199.0, 200.0, 199.0, 200.0, 202.0, 200.0) `
                -Label 'physical Grant target bounds'
            $station = Get-ObjectProperty $scope 'player_station_bounds'
            Assert-ConsentBoundsEqual -Actual $station `
                -Expected @(199.075, 199.9375, 197.075, 199.925, 201.925, 197.925) `
                -Label 'physical Grant player station bounds'
            Add-GateEvent -Event 'physical_consent_observed' -Detail ([ordered]@{
                    policy_binding_hash = $PolicyHash; polls = $polls
                })
            return $reference
        }
        if ($consentState -cnotin @('pending', 'none') -or
            ($consentState -ceq 'pending' -and
                (Get-ObjectProperty $consent 'policy_binding_hash') -cne $PolicyHash)) {
            throw "unexpected kill-zone consent state while waiting: $consentState"
        }
        if ($watch.Elapsed.TotalSeconds -lt $ConsentWaitSeconds) {
            Invoke-GateDelaySeconds -Seconds 0.25
        }
    } while ($watch.Elapsed.TotalSeconds -lt $ConsentWaitSeconds)
    throw "physical kill-zone Grant was not observed within $ConsentWaitSeconds seconds"
}

function Assert-KillZoneTerminal {
    param([Parameter(Mandatory)][object]$Terminal)
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded' -or
        $null -ne (Get-ObjectProperty $Terminal 'failure') -or
        [int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'total_node_upper_bound') -ne 1 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [double](Get-ObjectProperty $progress 'camera_degrees') -ne 0 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 1 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0 -or
        [int](Get-ObjectProperty $progress 'ticks') -gt 210) {
        throw 'kill-zone terminal violates its stationary one-node finite budget'
    }
    $effects = @((Get-ObjectProperty $Terminal 'effects'))
    $attacks = @($effects | Where-Object {
            (Get-ObjectProperty $_ 'kind') -ceq 'entity_attack'
        })
    $summaries = @($effects | Where-Object {
            (Get-ObjectProperty $_ 'kind') -ceq 'kill_zone_summary'
        })
    $attackBefore = if ($attacks.Count -eq 1) {
        Get-ObjectProperty $attacks[0] 'observed_before'
    } else { $null }
    $summaryBefore = if ($summaries.Count -eq 1) {
        Get-ObjectProperty $summaries[0] 'observed_before'
    } else { $null }
    $attackAfter = if ($attacks.Count -eq 1) {
        Get-ObjectProperty $attacks[0] 'observed_after'
    } else { $null }
    if ($effects.Count -ne 2 -or $attacks.Count -ne 1 -or $summaries.Count -ne 1 -or
        (Get-ObjectProperty $attacks[0] 'verification') -cne 'confirmed' -or
        (Get-ObjectProperty $attackBefore 'entity_type') -cne 'minecraft:armor_stand' -or
        (Get-ObjectProperty $attackAfter 'outcome') -cne 'armor_stand_hit_event' -or
        (Get-ObjectProperty $summaries[0] 'verification') -cne 'confirmed' -or
        (Get-ObjectProperty $summaries[0] 'subject') -cne 'operation' -or
        [int](Get-ObjectProperty $summaryBefore 'max_attacks') -ne 1) {
        throw 'kill-zone terminal lacks one confirmed armor-stand attack and one confirmed summary'
    }
    $summary = Get-ObjectProperty $summaries[0] 'observed_after'
    if ([int](Get-ObjectProperty $summary 'dispatched_attacks') -ne 1 -or
        [int](Get-ObjectProperty $summary 'confirmed_attacks') -ne 1 -or
        [int](Get-ObjectProperty $summary 'unknown_attacks') -ne 0 -or
        (Get-ObjectProperty $summary 'completion_reason') -cne 'attack_limit_reached') {
        throw 'kill-zone confirmed summary is not the exact one-attack finite result'
    }
    $aggregate = Get-ObjectProperty $Terminal 'effect_aggregate'
    if ([long](Get-ObjectProperty $aggregate 'total_effects') -ne 2 -or
        [long](Get-ObjectProperty $aggregate 'retained_effects') -ne 2 -or
        [long](Get-ObjectProperty $aggregate 'confirmed_effects') -ne 2 -or
        [long](Get-ObjectProperty $aggregate 'qualified_effects') -ne 0 -or
        [long](Get-ObjectProperty $aggregate 'unknown_effects') -ne 0 -or
        [long](Get-ObjectProperty $aggregate 'dispatched_attacks') -ne 1 -or
        [long](Get-ObjectProperty $aggregate 'confirmed_attacks') -ne 1 -or
        [long](Get-ObjectProperty $aggregate 'unknown_attacks') -ne 0) {
        throw 'kill-zone effect aggregate does not retain both confirmed proofs'
    }
    return $summary
}

function Invoke-KillZoneGateCore {
    $fixedFive = Assert-KillZoneFixedFive
    $initial = Get-FreshState
    $initialHealth = [double](Get-ObjectProperty (Get-ObjectProperty $initial 'world') 'health')
    if ([Math]::Abs($initialHealth - 16.0) -gt 0.0001) {
        throw 'kill-zone fixture initial health is not 16'
    }
    Assert-KillZonePlayerState -State $initial -ExpectedHealth $initialHealth -Phase 'initial state'
    Assert-KillZoneInventory -State $initial -Initial
    [void](Get-OnlyKillZoneTarget -State $initial)
    $initialConsent = Get-ObjectProperty $initial 'entity_attack_consent'
    if ((Get-ObjectProperty $initialConsent 'state') -cne 'none') {
        throw 'kill-zone fixture starts with stale consent state'
    }

    $awaiting = Invoke-GateTool -Tool 'agent_start_action' `
        -Arguments (New-KillZoneRequest -ConsentRef $null)
    $policyHash = Assert-AwaitingKillZoneConsent -Receipt $awaiting
    Add-GateEvent -Event 'awaiting_physical_consent' -Detail ([ordered]@{
            policy_binding_hash = $policyHash; action_reserved = $false
            input_acquired = $false
        })
    Write-Host "AWAITING_CONSENT: MinecraftのGrantを物理クリックしてください（自動化しません）。"
    $consentRef = Wait-ForPhysicalKillZoneGrant -PolicyHash $policyHash

    $terminal = Invoke-ActionRequest `
        -Request (New-KillZoneRequest -ConsentRef $consentRef) -WallTimeoutSeconds 25
    $summary = Assert-KillZoneTerminal -Terminal $terminal

    $final = Get-FreshState
    Assert-KillZonePlayerState -State $final -ExpectedHealth $initialHealth -Phase 'final state'
    Assert-KillZoneInventory -State $final
    if ((Get-ObjectProperty (Get-ObjectProperty $final 'entity_attack_consent') 'state') -cne
        'none') {
        throw 'single-use physical Grant was not consumed by the finite Action'
    }

    return [ordered]@{
        gate = 'phase9-kill-zone'
        fixture_precondition = '/mcmcp_fixture phase5 kill_zone'
        fixed_five_surface = $fixedFive
        normal_player_actions_only = $true
        physical_grant_required = $true
        physical_grant_observed = $true
        grant_automation = $false
        action_boundary = 'one_stationary_finite_attack'
        online_oracle = [ordered]@{
            dispatched_attacks = [int](Get-ObjectProperty $summary 'dispatched_attacks')
            confirmed_attacks = [int](Get-ObjectProperty $summary 'confirmed_attacks')
            unknown_attacks = [int](Get-ObjectProperty $summary 'unknown_attacks')
            completion_reason = Get-ObjectProperty $summary 'completion_reason'
            player_position_unchanged = $true
            player_health_unchanged = $true
            consent_consumed = $true
        }
        fixture_tick_mutation_after_t0 = $false
    }
}

function Write-KillZoneArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    if (-not $script:KillZoneArtifactInitialized -or
        -not (Test-Path -LiteralPath $ArtifactDirectory -PathType Container)) {
        throw 'kill-zone artifact directory was not initialized exactly once'
    }
    [IO.File]::WriteAllLines(
        (Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1; gate = 'phase9-kill-zone'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools); fixed_five_only = $true
        normal_player_actions_only = $true
        physical_grant_required = $true; grant_automation = $false
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
}

function Invoke-McmcpKillZoneCapabilityGate {
    if ($script:KillZoneInvocationStarted) {
        throw 'kill-zone gate invocation is single-use and will not overwrite its artifacts'
    }
    $script:KillZoneInvocationStarted = $true
    Initialize-KillZoneArtifactDirectory
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-KillZoneGateCore } catch { $primaryFailure = $_ }
    finally { try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ } }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-KillZoneArtifacts -GateResult $gateResult -InputRelease $release -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return [ordered]@{ gate_result = $gateResult; input_release = $release }
}

if (-not $LibraryOnly) {
    Initialize-KillZoneArtifactDirectory
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    if ([string]::IsNullOrWhiteSpace($script:Bearer) -or
        $script:Bearer.Contains("`r") -or $script:Bearer.Contains("`n")) {
        throw 'MCP token file is empty or malformed'
    }
    Assert-FixedFiveToolSurface
    $result = Invoke-McmcpKillZoneCapabilityGate
    ConvertTo-Json $result -Depth 100
}
