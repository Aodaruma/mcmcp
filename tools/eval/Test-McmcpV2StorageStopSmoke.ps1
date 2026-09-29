#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -LibraryOnly

# Only the controller is mocked; these cases cannot certify Minecraft behavior.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-v2-storage-stop-test-' + [guid]::NewGuid())
$ExpectedWorldSession = 'fixture-session'
$TokenPath = 'unused-in-mock'
$catalog = Get-Content (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw | ConvertFrom-Json -Depth 100
$calls = [Collections.Generic.List[string]]::new()

function Invoke-McmcpClient {
    param($TokenPath, $Endpoint, $Tool, $Arguments, [switch]$Check)
    if ($Check) { return @{ ok = $true; tool_count = 14 } }
    $calls.Add($Tool)
    $definition = @($catalog.tools | Where-Object name -CEQ $Tool)[0]
    Assert-McmcpSchema $Arguments $definition.inputSchema 'invalid_extended_smoke_request'
    switch ($Tool) {
        'agent_get_mcp_status' {
            $mode = if ($script:active) { 'agent' } elseif ($script:stopped -and $Phase -cin @('UiOff', 'WorldChange') -and
                $script:scenario -cne 'still_ready') { 'off' } else { 'ready' }
            $session = if ($script:stopped -and $Phase -ceq 'WorldChange' -and $script:scenario -cne 'same_session') {
                'new-session'
            } else { $ExpectedWorldSession }
            if ($script:stopped -and $script:scenario -ceq 'no_session') { $session = $null }
            return @{ ok = $true; result = @{ control_mode = $mode; world_session_id = $session
                running_action_id = $(if ($script:active) { $script:id } else { $null }) } }
        }
        'agent_get_state' { return @{ ok = $true; result = [pscustomobject]@{
            schema_version = 2; hotbar = @{ slots = @(0..8) }
            player = @{ position = @{ x = 200.5; y = 201; z = 200.5 } }
        } } }
        { $_ -cin @('agent_inventory', 'agent_interact') } {
            $script:id = [guid]::NewGuid().ToString()
            $script:active = $true; $script:polls = 0; $script:cancelled = $false
            $script:longUse = $Tool -ceq 'agent_interact' -and $Arguments.hold_ticks -eq 1000
            $script:payload = @{}
            if ($Tool -ceq 'agent_inventory') {
                if ($Arguments.operation -ceq 'transfer') {
                    $script:transfers++
                    $delta = if ($Arguments.direction -ceq 'store') { $Arguments.count } else { -$Arguments.count }
                    $script:bag += $delta; $script:own -= $delta
                    $script:payload = @{ confirmed_count = $Arguments.count; unconfirmed = $script:scenario -ceq 'unconfirmed' }
                } else {
                    $isBag = $Arguments.ContainsKey('target') -and $Arguments.target -ceq 'storage'
                    $count = if ($isBag) { $script:bag } else { $script:own }
                    if ($isBag -and $script:transfers -gt 0 -and $script:scenario -ceq 'wrong_readback') { $count++ }
                    $slots = if ($count -gt 0) { @(@{ item = 'minecraft:snow_block'; count = $count; slot = 0 }) } else { @() }
                    $script:payload = @{ complete = $true; slots = @($slots) }
                }
            } else { $script:payload = @{ dispatched = $true; client_consumed = $true } }
            return @{ ok = $true; result = @{ action_id = $script:id; state = 'queued' } }
        }
        'agent_cancel_action' {
            $script:cancelled = $true
            return @{ ok = $true; result = @{ action_id = $script:id; cancel_requested = $true } }
        }
        'agent_get_action' {
            $script:polls++
            $state = 'succeeded'; $failure = $null
            if ($script:longUse) {
                if ($script:polls -eq 1 -and $script:scenario -cne 'premature') { $state = 'running' }
                elseif ($script:cancelled) { $state = 'cancelled'; $failure = 'client_request' }
                elseif ($script:scenario -cne 'premature') {
                    $state = 'failed'
                    $failure = switch ($Phase) {
                        'Escape' { 'local_emergency_key' }
                        'UiOff' { 'local_ui_disabled' }
                        'WorldChange' { 'world_boundary' }
                    }
                    if ($script:scenario -ceq 'wrong_reason') { $failure = 'duration_limit' }
                }
            }
            if ($state -cne 'running') {
                $script:active = $false
                if ($script:longUse) { $script:stopped = $true }
            }
            return @{ ok = $true; result = @{ action_id = $script:id; state = $state; failure = $failure
                progress = @{ completed_operations = 3 }; result = $script:payload } }
        }
        default { throw "Unexpected mock tool: $Tool" }
    }
}

try {
    $cases = @(
        @('Storage', 'success'), @('Storage', 'unconfirmed'), @('Storage', 'wrong_readback'),
        @('ItemCancel', 'success'), @('ItemCancel', 'premature'),
        @('Escape', 'success'), @('Escape', 'wrong_reason'),
        @('UiOff', 'success'), @('UiOff', 'still_ready'),
        @('WorldChange', 'success'), @('WorldChange', 'same_session'), @('WorldChange', 'still_ready'), @('WorldChange', 'no_session')
    )
    foreach ($case in $cases) {
        $Phase = $case[0]; $script:scenario = $case[1]
        $script:active = $false; $script:stopped = $false; $script:cancelled = $false
        $script:longUse = $false; $script:bag = 0; $script:own = 16; $script:transfers = 0
        $calls.Clear()
        $ArtifactDirectory = Join-Path $testRoot "$Phase-$scenario"
        $result = Invoke-McmcpV2Smoke
        $expected = if ($scenario -ceq 'success') { 'passed' } else { 'failed' }
        Assert-V2Smoke ($result.status -ceq $expected) "False outcome for $Phase/$scenario : $($result.failure)"
        if ($Phase -ceq 'Storage') {
            Assert-V2Smoke ($script:transfers -eq $(if ($scenario -ceq 'success') { 2 } else { 1 })) 'Storage failure triggered extra mutations'
        } else {
            $uses = @($calls | Where-Object { $_ -ceq 'agent_interact' }).Count
            Assert-V2Smoke ($uses -eq $(if ($Phase -ceq 'ItemCancel' -and $scenario -ceq 'success') { 2 } else { 1 })) 'Stop scenario replayed the use'
            $signal = Join-Path $ArtifactDirectory 'stop-ready.json'
            if ($Phase -cne 'ItemCancel') {
                $ready = Get-Content $signal -Raw | ConvertFrom-Json
                Assert-V2Smoke ($ready.running.state -ceq 'running' -and $ready.running.result.dispatched) 'Stop signal preceded item use'
            }
        }
        Assert-V2Smoke (-not $script:active) 'Mock action was left running'
    }
    'V2 storage/stop smoke orchestration tests passed (13 cases; no Minecraft connection).'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = (Resolve-Path -LiteralPath $testRoot).Path
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith('mcmcp-v2-storage-stop-test-')) { throw 'Unsafe temporary cleanup path' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
