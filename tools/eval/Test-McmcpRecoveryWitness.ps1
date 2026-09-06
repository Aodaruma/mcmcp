[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'McmcpRecoveryWitness.psm1') -Force

$actionId = '00000000-0000-4000-8000-000000000001'
$otherId = '00000000-0000-4000-8000-000000000002'
$target = @{ dimension = 'minecraft:overworld'; x = 1; y = 64; z = 2 }
$start = @{
    tool = 'agent_start_action'; success = $true
    arguments = @{ program = @{ body = @(@{
        id = 'inspect'; op = 'inspect_known_container'; target = $target
    }) } }
    output_text = (@{ schema_version = 1; action_id = $actionId; state = 'queued'
        accepted_at = '2026-09-06T00:00:00Z' } | ConvertTo-Json -Compress)
}
$terminal = @{
    action_id = $actionId; state = 'succeeded'; failure = $null
    progress = @{ phase = 'finished'; executed_nodes = 1; total_node_upper_bound = 1; interactions = 1 }
    trace = @(@{ tick = 20; event = 'RENDERER_RECOVERY'; detail = 'missing=capture,initial_open;revalidated=capture,initial_open' })
    container_results = @{
        results = @(@{
            result_seq = 1; node_id = 'inspect'; node_execution = 1; target = $target
            world_session_id = $otherId; observed_client_tick = 100; packet_revision = 5
            items = @(@{ item_id = 'minecraft:raw_iron'; count = 16 })
            total_item_types = 1; returned_item_types = 1; truncated = $false
        })
        total_results = 1; retained_results = 1; snapshot_result_count = 1; returned_results = 1
        action_terminal = $true; truncated = $false; has_more = $false; next_cursor = $null
    }
}
$get = @{
    tool = 'agent_get_action'; success = $true
    arguments = @{ action_id = $actionId; include_container_results = $true; wait_timeout_ms = 25000 }
    output_text = ($terminal | ConvertTo-Json -Depth 30 -Compress)
}
$baseJson = @($start, $get) | ConvertTo-Json -Depth 40 -Compress
function Set-TestPayload {
    param($Call, [scriptblock]$Change)
    $payload = ConvertFrom-Json -AsHashtable -InputObject $Call.output_text
    & $Change $payload
    $Call.output_text = $payload | ConvertTo-Json -Depth 40 -Compress
}
$cases = @(
    @{ name = 'witness'; status = 'witnessed'; edit = {} },
    @{ name = 'empty_verified_chest'; status = 'witnessed'; edit = {
        param($c) Set-TestPayload $c[1] { param($p)
            $r = $p.container_results.results[0]; $r.items = @(); $r.total_item_types = 0; $r.returned_item_types = 0
        }
    } },
    @{ name = 'no_gap'; status = 'not_exercised'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace = @() }
    } },
    @{ name = 'duplicate_summary'; code = 'duplicate_recovery_summary'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace += $p.trace[0] }
    } },
    @{ name = 'different_request_action'; code = 'action_id_mismatch'; edit = {
        param($c) $c[1].arguments.action_id = $otherId
    } },
    @{ name = 'different_result_action'; code = 'action_id_mismatch'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.action_id = $otherId }
    } },
    @{ name = 'get_before_start'; code = 'action_id_mismatch'; edit = {
        param($c) $tmp = $c[0]; $c[0] = $c[1]; $c[1] = $tmp
    } },
    @{ name = 'failed_tool'; code = 'action_call_not_successful'; edit = { param($c) $c[1].success = $false } },
    @{ name = 'cancel'; code = 'action_call_not_successful'; edit = { param($c) $c[1].tool = 'agent_cancel_action' } },
    @{ name = 'bad_json'; code = 'recovery_evidence_malformed'; edit = { param($c) $c[1].output_text = '{"SENTINEL_SECRET":invalid' } },
    @{ name = 'unaccepted'; code = 'accepted_action_receipt_required'; edit = {
        param($c) Set-TestPayload $c[0] { param($p) $p.state = 'AWAITING_CONSENT' }
    } },
    @{ name = 'other_opcode'; code = 'standalone_inspect_required'; edit = {
        param($c) $c[0].arguments.program.body[0].op = 'take_known_container_stack'
    } },
    @{ name = 'two_nodes'; code = 'standalone_inspect_required'; edit = {
        param($c) $c[0].arguments.program.body += $c[0].arguments.program.body[0]
    } },
    @{ name = 'nonterminal'; code = 'terminal_result_missing'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.state = 'running' }
    } },
    @{ name = 'failed_action'; code = 'successful_terminal_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.state = 'failed' }
    } },
    @{ name = 'failure_not_null'; code = 'successful_terminal_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.failure = @{ code = 'INTERNAL_ERROR' } }
    } },
    @{ name = 'failure_absent'; code = 'successful_terminal_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.Remove('failure') }
    } },
    @{ name = 'no_interaction'; code = 'inspect_execution_evidence_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.progress.interactions = 0 }
    } },
    @{ name = 'two_executions'; code = 'inspect_execution_evidence_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.progress.executed_nodes = 2 }
    } },
    @{ name = 'string_execution_count'; code = 'inspect_execution_evidence_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.progress.executed_nodes = '1' }
    } },
    @{ name = 'boolean_result_count'; code = 'complete_terminal_container_page_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.returned_results = $true }
    } },
    @{ name = 'trace_overflow'; code = 'bounded_terminal_trace_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace = @($p.trace[0]) * 257 }
    } },
    @{ name = 'bad_summary'; code = 'recovery_summary_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'SENTINEL_SECRET' }
    } },
    @{ name = 'unknown_stage'; code = 'recovery_stage_set_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'missing=imaginary;revalidated=imaginary' }
    } },
    @{ name = 'duplicate_stage'; code = 'recovery_stage_set_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'missing=capture,capture;revalidated=capture' }
    } },
    @{ name = 'unordered_stage'; code = 'recovery_stage_set_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'missing=initial_open,capture;revalidated=capture' }
    } },
    @{ name = 'unrelated_stage'; code = 'revalidated_stage_without_missing'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'missing=capture;revalidated=dispatch' }
    } },
    @{ name = 'no_revalidation'; code = 'recovery_stage_overlap_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.trace[0].detail = 'missing=capture;revalidated=none' }
    } },
    @{ name = 'missing_page'; code = 'confirmed_container_result_missing'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.Remove('container_results') }
    } },
    @{ name = 'unsolicited_page'; code = 'container_result_request_invalid'; edit = {
        param($c) $c[1].arguments.include_container_results = $false
    } },
    @{ name = 'cursor_from_elsewhere'; code = 'container_result_request_invalid'; edit = {
        param($c) $c[1].arguments.container_results_cursor = $otherId + ':1:0'
    } },
    @{ name = 'no_result'; code = 'complete_terminal_container_page_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results = @() }
    } },
    @{ name = 'incomplete_page'; code = 'complete_terminal_container_page_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.has_more = $true }
    } },
    @{ name = 'not_terminal_page'; code = 'complete_terminal_container_page_required'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.action_terminal = $false }
    } },
    @{ name = 'wrong_node'; code = 'confirmed_inspect_result_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results[0].node_id = 'other' }
    } },
    @{ name = 'wrong_target'; code = 'inspect_result_target_mismatch'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results[0].target.x = 3 }
    } },
    @{ name = 'truncated_inventory'; code = 'confirmed_inspect_result_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results[0].truncated = $true }
    } },
    @{ name = 'incorrect_totals'; code = 'confirmed_inspect_result_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results[0].total_item_types = 2 }
    } },
    @{ name = 'invalid_item_count'; code = 'inspect_inventory_totals_invalid'; edit = {
        param($c) Set-TestPayload $c[1] { param($p) $p.container_results.results[0].items[0].count = 0 }
    } }
)
$passed = 0
foreach ($case in $cases) {
    $calls = ConvertFrom-Json -AsHashtable -InputObject $baseJson
    & $case.edit $calls | Out-Null
    $report = Get-McmcpRecoveryWitness -Calls $calls
    $expected = if ($case.Contains('status')) { $case.status } else { 'invalid' }
    if ($report.status -cne $expected -or $report.passed -ne ($expected -ceq 'witnessed') -or
        ($case.Contains('code') -and $case.code -cnotin $report.violations) -or
        ($report | ConvertTo-Json -Depth 10 -Compress) -cmatch 'SENTINEL_SECRET') {
        throw "Recovery witness test failed: $($case.name) ($($report | ConvertTo-Json -Compress -Depth 10))"
    }
    $passed++
}

# Multi-call cases verify that evidence cannot be borrowed across actions or snapshots.
$calls = ConvertFrom-Json -AsHashtable -InputObject $baseJson
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $calls[1], $calls[0], $calls[1])
if ('multiple_action_starts' -cnotin $report.violations) { throw 'Second Action was accepted' }; $passed++
$running = ConvertFrom-Json -AsHashtable -InputObject ($calls[1] | ConvertTo-Json -Depth 40)
Set-TestPayload $running { param($p) $p.state = 'running' }
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $running, $calls[1])
if ($report.status -cne 'witnessed') { throw 'Valid terminal after running was rejected' }; $passed++
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $calls[1], $running)
if ('nonterminal_after_terminal' -cnotin $report.violations) { throw 'State regression was accepted' }; $passed++
$noGap = ConvertFrom-Json -AsHashtable -InputObject ($calls[1] | ConvertTo-Json -Depth 40)
Set-TestPayload $noGap { param($p) $p.trace = @() }
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $running, $noGap)
if ($report.status -cne 'not_exercised') { throw 'Borrowed nonterminal witness' }; $passed++
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $calls[1], $noGap)
if ('terminal_evidence_changed' -cnotin $report.violations) { throw 'Changed terminal was accepted' }; $passed++
$withoutPage = ConvertFrom-Json -AsHashtable -InputObject ($calls[1] | ConvertTo-Json -Depth 40)
$withoutPage.arguments.Remove('include_container_results')
Set-TestPayload $withoutPage { param($p) $p.Remove('container_results') }
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $withoutPage, $calls[1])
if ($report.status -cne 'witnessed') { throw 'Terminal followed by requested result was rejected' }; $passed++
$changedPage = ConvertFrom-Json -AsHashtable -InputObject ($calls[1] | ConvertTo-Json -Depth 40)
Set-TestPayload $changedPage { param($p) $p.container_results.results[0].items[0].count = 17 }
$report = Get-McmcpRecoveryWitness -Calls @($calls[0], $calls[1], $changedPage)
if ('immutable_inspect_result_changed' -cnotin $report.violations) { throw 'Changed inventory was accepted' }; $passed++
$report = Get-McmcpRecoveryWitness -Calls @()
if ('one_accepted_action_required' -cnotin $report.violations) { throw 'Zero Action was accepted' }; $passed++
Write-Output "MCMCP recovery witness: $passed tests passed."
