#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -LibraryOnly

# Only the orchestration is mocked. These results are never game acceptance evidence.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-v2-smoke-test-' + [guid]::NewGuid())
$ExpectedWorldSession = 'fixture-session'
$TokenPath = 'unused-in-mock'
$action = '550e8400-e29b-41d4-a716-446655440000'
$calls = [Collections.Generic.List[string]]::new()
$Phase = 'Core'

function Invoke-McmcpClient {
    param($TokenPath, $Endpoint, $Tool, $Arguments, [switch]$Check)
    if ($Check) { return @{ ok = $true; tool_count = 13 } }
    $calls.Add($Tool)
    switch ($Tool) {
        'agent_get_mcp_status' {
            $id = if ($script:serverActive) { $action } else { $null }
            $world = if ($script:scenario -eq 'wrong_world') { 'other-world' } else { $ExpectedWorldSession }
            return @{ ok = $true; result = [pscustomobject]@{
                control_mode = $(if ($script:serverActive) { 'agent' } else { 'ready' })
                running_action_id = $id; world_session_id = $world
            } }
        }
        'agent_get_state' { return @{ ok = $true; result = [pscustomobject]@{
            schema_version = 2; hotbar = @{ slots = @(0..8) }
            player = @{ position = @{ x = 200.5; y = 201; z = 200.5 } }
        } } }
        'agent_move' {
            $script:serverActive = $true
            if ($script:scenario -eq 'lost_receipt') { return @{ ok = $false; diagnostic_code = 'request_timeout' } }
            return @{ ok = $true; result = @{ state = 'queued'; action_id = $action } }
        }
        'agent_get_action' {
            if ($script:scenario -eq 'poll_failed' -and -not $script:cancelled) {
                return @{ ok = $false; diagnostic_code = 'http_request_failed' }
            }
            $script:serverActive = $false
            $state = if ($script:cancelled) { 'cancelled' } elseif ($script:scenario -eq 'job_failed') { 'failed' } else { 'succeeded' }
            return @{ ok = $true; result = @{ action_id = $action; state = $state; failure = 'test_outcome' } }
        }
        'agent_cancel_action' {
            $script:cancelled = $true
            return @{ ok = $true; result = @{ action_id = $action; cancel_requested = $true } }
        }
        default { throw 'Unexpected mock call' }
    }
}

function Invoke-V2SmokeCore { [void](Invoke-V2SmokeJob 'agent_move' @{ x = 201; y = 201; z = 200 }) }

try {
    foreach ($case in @('success', 'wrong_world', 'lost_receipt', 'job_failed', 'poll_failed')) {
        $script:scenario = $case; $script:serverActive = $false; $script:cancelled = $false
        $calls.Clear()
        $ArtifactDirectory = Join-Path $testRoot $case
        $result = Invoke-McmcpV2Smoke
        $expected = if ($case -eq 'success') { 'passed' } else { 'failed' }
        Assert-V2Smoke ($result.status -ceq $expected) "False outcome: $case"
        $starts = @($calls | Where-Object { $_ -ceq 'agent_move' }).Count
        Assert-V2Smoke ($starts -eq $(if ($case -eq 'wrong_world') { 0 } else { 1 })) "Unsafe retry/admission: $case"
        $cancels = @($calls | Where-Object { $_ -ceq 'agent_cancel_action' }).Count
        Assert-V2Smoke ($cancels -eq $(if ($case -eq 'poll_failed') { 1 } else { 0 })) "Cleanup authority mismatch: $case"
        if ($case -eq 'lost_receipt') {
            Assert-V2Smoke ($result.final_control.running_action_id -ceq $action) 'Lost receipt omitted unresolved server job'
        } else { Assert-V2Smoke ($null -eq $result.active_action_id) 'Known job was not finalized' }
        $saved = Get-Content -LiteralPath (Join-Path $ArtifactDirectory 'result.json') -Raw | ConvertFrom-Json
        Assert-V2Smoke ($saved.status -ceq $expected) 'Failure evidence was not persisted'
    }
    # Exercise the real stop scenarios, including single-element input arrays and
    # the separate, atomic fixture-controller handshake, against the public schema.
    $catalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw |
        ConvertFrom-Json -Depth 100
    function Start-V2SmokeJob([string]$Name, [object]$Arguments) {
        $definition = @($catalog.tools | Where-Object name -CEQ $Name)[0]
        Assert-McmcpSchema $Arguments $definition.inputSchema 'invalid_smoke_request'
        $script:stopArguments = $Arguments
        return $action
    }
    function Invoke-V2SmokeTool([string]$Name, [object]$Arguments) {
        if ($Name -eq 'agent_get_action') {
            return @{ action_id = $action; state = 'running'; progress = @{ completed_operations = 3 } }
        }
        Assert-V2Smoke ($Name -eq 'agent_cancel_action') 'Unexpected stop-test tool'
        return @{ action_id = $action; cancel_requested = $true }
    }
    function Wait-V2SmokeJob { return @{ state = $(if ($script:testHazard) { 'failed' } else { 'cancelled' }); failure = 'safety_interrupted' } }
    function Get-V2SmokeState { return @{ player = @{ position = @{ x = 207.5; y = 201; z = 200.5 } } } }
    foreach ($hazard in @($false, $true)) {
        $script:testHazard = $hazard
        $ArtifactDirectory = Join-Path $testRoot "stop-$hazard"
        [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
        Invoke-V2SmokeStop $hazard
        Assert-V2Smoke ($script:stopArguments.steps[0].inputs -is [array]) 'Input collection became a scalar'
        if ($hazard) {
            $ready = Get-Content -LiteralPath (Join-Path $ArtifactDirectory 'hazard-ready.json') -Raw | ConvertFrom-Json
            Assert-V2Smoke ($ready.action_id -eq $action -and $ready.running.state -eq 'running') 'Invalid hazard handshake'
            Assert-V2Smoke (-not (Test-Path (Join-Path $ArtifactDirectory 'hazard-ready.tmp'))) 'Handshake remained partial'
        }
    }
    'V2 smoke orchestration tests passed (7 cases; no Minecraft connection).'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = (Resolve-Path -LiteralPath $testRoot).ProviderPath
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith('mcmcp-v2-smoke-test-')) { throw 'Unsafe temporary cleanup path' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
