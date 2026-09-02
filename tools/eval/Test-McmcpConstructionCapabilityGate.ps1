[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $runner -Gate navigation -ArtifactDirectory (Join-Path $PSScriptRoot '.mock-unused') `
    -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "capability gate mock test failed: $Message" }
}

function New-MockState {
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{ mode = 'ready'; ready_expires_at = $null; game_paused = $false }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 10L; world_revision = 1L
            position = [pscustomobject]@{ x = -9.3; y = 56.0; z = -8.1 }
            yaw = 0.0; pitch = 0.0; health = 20.0; absorption = 0.0
            hunger = 20; air = 300; max_air = 300; on_fire = $false
            submerged = $false; status_effects = @()
        }
        inventory = @()
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0123456789abcdef' }
        action = $null
    }
}

function New-MockActionSnapshot {
    param([Parameter(Mandatory)][string]$ActionId, [Parameter(Mandatory)][string]$State)
    [pscustomobject]@{
        schema_version = 1
        action_id = $ActionId
        state = $State
        progress = [pscustomobject]@{}
        failure = $null
        trace = @()
    }
}

# A navigate_to_known target must be the exact delivered object, not a value derived
# from traversability.from/to.
$target = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -15; y = 56; z = -6
}
$navigationRecord = [pscustomobject]@{
    kind = 'traversability'
    navigation_target = $target
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$navigationRequest = New-NavigationActionRequest -NavigationRecord $navigationRecord `
    -State (New-MockState)
$requestTarget = $navigationRequest.program.body[0].target
Assert-True ([object]::ReferenceEquals($target, $requestTarget)) `
    'navigate_to_known did not retain the delivered navigation_target object'
Assert-True ((ConvertTo-CompactJson $target) -ceq (ConvertTo-CompactJson $requestTarget)) `
    'navigate_to_known changed delivered target values'
Assert-True ($navigationRequest.budget.max_distance_blocks -eq 32) `
    'navigation request did not use the advertised shared distance budget'

# A hidden fixture surface may be approached only through an unchanged target
# from a fresh traversability record. The fixture bounds are merely the ranking
# goal and never become an Action target.
$towardTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -11; y = 56; z = -4
}
$awayTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -6; y = 56; z = -10
}
$towardRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $towardTarget
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$nearTarget = [pscustomobject]@{
    dimension = 'minecraft:overworld'; x = -10; y = 56; z = -7
}
$nearRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $nearTarget
    status = 'PROBE_ALLOWED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$awayRecord = [pscustomobject]@{
    kind = 'traversability'; navigation_target = $awayTarget
    status = 'CONFIRMED'; target_support = 'confirmed'
    transition_clearance = 'confirmed'; fluid = 'none'
}
$selectedToward = Select-NavigationRecordTowardBounds `
    -Records @($awayRecord, $towardRecord, $nearRecord) `
    -WorldPosition (New-MockState).world.position -Bounds $script:ChestBounds
Assert-True ([object]::ReferenceEquals($nearRecord, $selectedToward)) `
    'bounded surface approach did not choose the nearest progress-making record'
$towardRequest = New-NavigationActionRequest -NavigationRecord $selectedToward `
    -State (New-MockState)
Assert-True ([object]::ReferenceEquals(
        $nearTarget, $towardRequest.program.body[0].target)) `
    'bounded surface approach transformed the delivered navigation_target'

# Missing visible surfaces are an expected pre-approach condition, not a
# filtering success and not an exception when explicitly requested.
$script:ToolTransport = {
    param($Tool, $Arguments)
    [pscustomobject]@{
        schema_version = 1; frame_id = 'obs-0123456789abcdef'
        records = @(); next_cursor = $null
    }
}
$missingSurface = Get-VisibleSurface -State (New-MockState) `
    -Block 'minecraft:chest' -Bounds $script:ChestBounds -Faces $null -AllowMissing
Assert-True ($null -eq $missingSurface) `
    'explicit missing-surface probe did not return null'

$script:NearbyArguments = $null
$script:ToolTransport = {
    param($Tool, $Arguments)
    $script:NearbyArguments = $Arguments
    [pscustomobject]@{
        schema_version = 1; frame_id = 'obs-0123456789abcdef'
        records = @(); next_cursor = $null
    }
}
[void](Get-NearbyTraversabilityRecords -State (New-MockState))
$nearbyBounds = $script:NearbyArguments.filter.position_bounds
Assert-True ($nearbyBounds.min_x -eq -12 -and $nearbyBounds.max_x -eq -8 -and
    $nearbyBounds.min_z -eq -11 -and $nearbyBounds.max_z -eq -7) `
    'surface approach did not bound fresh traversability pagination near the player'

# The transport boundary must reject every name outside the fixed five before the
# injected transport is reached.
$script:MockTransportReached = $false
$script:ToolTransport = { param($Tool, $Arguments) $script:MockTransportReached = $true; @{} }
$forbiddenRejected = $false
try { [void](Invoke-GateTool -Tool 'raw_key' -Arguments ([ordered]@{})) } catch {
    $forbiddenRejected = $_.Exception.Message -match 'non-public tool'
}
Assert-True $forbiddenRejected 'non-public tool was not rejected'
Assert-True (-not $script:MockTransportReached) 'forbidden tool reached the transport'

# Terminal waiting is event-driven long-polling with the maximum public wait.
$actionId = '550e8400-e29b-41d4-a716-446655440000'
$pollStates = [Collections.Generic.Queue[string]]::new()
$pollStates.Enqueue('running')
$pollStates.Enqueue('succeeded')
$pollCalls = [Collections.Generic.List[object]]::new()
$script:ToolTransport = {
    param($Tool, $Arguments)
    $pollCalls.Add([pscustomobject]@{ tool = $Tool; arguments = $Arguments })
    New-MockActionSnapshot -ActionId $actionId -State $pollStates.Dequeue()
}
$terminal = Wait-McmcpActionTerminal -ActionId $actionId -WallTimeoutSeconds 2
Assert-True ($terminal.state -ceq 'succeeded') 'long-poll did not return terminal success'
Assert-True ($pollCalls.Count -eq 2) 'long-poll did not repeat until terminal'
Assert-True (@($pollCalls | Where-Object {
            $_.tool -cne 'agent_get_action' -or $_.arguments.wait_timeout_ms -ne 25000
        }).Count -eq 0) 'terminal polling did not use agent_get_action(25000) exclusively'

# Cleanup observes, cancels only a live action, waits terminal, then proves the
# strongest input-release fact available through the fixed public surface.
$cleanupCalls = [Collections.Generic.List[string]]::new()
$script:MockCleanupPoll = 0
$script:ActiveActionId = $actionId
$script:ToolTransport = {
    param($Tool, $Arguments)
    $cleanupCalls.Add($Tool)
    switch ($Tool) {
        'agent_get_action' {
            $script:MockCleanupPoll++
            if ($script:MockCleanupPoll -eq 1) {
                return New-MockActionSnapshot -ActionId $actionId -State 'running'
            }
            return New-MockActionSnapshot -ActionId $actionId -State 'cancelled'
        }
        'agent_cancel_action' {
            return [pscustomobject]@{
                schema_version = 1; action_id = $actionId
                cancel_requested = $true; state_at_request = 'running'
            }
        }
        'agent_get_state' { return New-MockState }
        default { throw "unexpected mock tool: $Tool" }
    }
}
$release = Invoke-GateCleanup
Assert-True $release.control_ready 'cleanup did not prove ready control'
Assert-True $release.all_actions_terminal 'cleanup did not prove terminal Action state'
Assert-True ($cleanupCalls -join ',' -ceq
    'agent_get_action,agent_cancel_action,agent_get_action,agent_get_state') `
    'cleanup call order changed'

# Waiting beyond the observation-handle TTL performs no Tool calls and permanently
# forbids another source lookup in the state-ref gate.
$script:MockDelaySeconds = 0
$script:MockWaitToolCalls = 0
$script:SourceObservationCount = 1
$script:SourceObservationForbidden = $false
$script:ToolTransport = { param($Tool, $Arguments) $script:MockWaitToolCalls++; throw 'unexpected Tool call' }
$script:DelayTransport = { param($Seconds) $script:MockDelaySeconds = $Seconds }
Wait-StateRefRetentionWindow -Seconds 61
Assert-True ($script:MockDelaySeconds -eq 61) 'state-ref retention wait was shortened'
Assert-True ($script:MockWaitToolCalls -eq 0) 'a Tool call occurred inside the retention wait'
$sourceRejected = $false
try { Assert-SourceObservationAllowed } catch {
    $sourceRejected = $_.Exception.Message -match 'forbidden'
}
Assert-True $sourceRejected 'source re-observation remained possible after the retention wait'
Assert-True ($script:SourceObservationCount -eq 1) 'source observation count changed during wait'

$script:ToolTransport = $null
$script:DelayTransport = $null
$script:ActiveActionId = $null
'MCMCP construction capability gate mock tests passed.'
