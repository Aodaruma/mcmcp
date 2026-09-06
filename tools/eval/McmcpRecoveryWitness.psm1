Set-StrictMode -Version Latest

function Test-RecoveryInteger {
    param($Value, [long]$Minimum = 0, [long]$Maximum = [long]::MaxValue)
    return ($Value -is [int] -or $Value -is [long] -or $Value -is [bigint]) -and
        $Value -ge $Minimum -and $Value -le $Maximum
}

function Test-RecoveryKeys {
    param($Value, [string[]]$Keys)
    if ($Value -isnot [Collections.IDictionary] -or $Value.Count -ne $Keys.Count) { return $false }
    foreach ($key in $Keys) { if (-not $Value.Contains($key)) { return $false } }
    return $true
}

function Get-McmcpRecoveryWitness {
    <# Only consume completed calls whose app-server/bridge identity, ordering, arguments,
       output hash and success were independently validated by Test-McmcpEvalTrace.
       This is a functional witness, not an input-lease or product-build attestation. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Calls)

    $violations = [Collections.Generic.List[string]]::new()
    $actionId = $null
    $node = $null
    $starts = 0
    $terminal = $null
    $terminalCore = $null
    $completeResult = $null
    $missing = @()
    $revalidated = @()
    $recovered = @()
    $uuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'

    try {
        foreach ($rawCall in $Calls) {
            $call = $rawCall | ConvertTo-Json -Depth 100 -Compress | ConvertFrom-Json -AsHashtable
            if (-not (Test-RecoveryKeys $call @('tool', 'arguments', 'success', 'output_text')) -or
                $call.tool -isnot [string] -or $call.success -isnot [bool] -or
                $call.arguments -isnot [Collections.IDictionary] -or $call.output_text -isnot [string]) {
                throw 'call_shape_invalid'
            }
            if ($call.tool -cin @('agent_get_state', 'agent_get_observation')) { continue }
            if ($call.tool -cnotin @('agent_start_action', 'agent_get_action') -or -not $call.success) {
                throw 'action_call_not_successful'
            }
            $payload = ConvertFrom-Json -AsHashtable -InputObject $call.output_text
            if ($payload -isnot [Collections.IDictionary]) { throw 'action_payload_invalid' }
            if ($call.tool -ceq 'agent_start_action') {
                $starts++
                if ($starts -ne 1) { throw 'multiple_action_starts' }
                $program = $call.arguments.program
                if ($program -isnot [Collections.IDictionary] -or $program.body -isnot [array] -or
                    $program.body.Count -ne 1 -or $program.body[0].op -cne 'inspect_known_container') {
                    throw 'standalone_inspect_required'
                }
                $node = $program.body[0]
                if ($node.id -isnot [string] -or $node.id -cnotmatch '^[a-z][a-z0-9_-]{0,31}$' -or
                    -not (Test-RecoveryKeys $node.target @('dimension', 'x', 'y', 'z')) -or
                    $node.target.dimension -isnot [string] -or $node.target.dimension.Length -gt 128 -or
                    $node.target.dimension -cnotmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$' -or
                    -not (Test-RecoveryInteger $node.target.x -30000000 30000000) -or
                    -not (Test-RecoveryInteger $node.target.y -2048 2048) -or
                    -not (Test-RecoveryInteger $node.target.z -30000000 30000000)) {
                    throw 'inspect_target_invalid'
                }
                if (-not (Test-RecoveryKeys $payload @('schema_version', 'action_id', 'state', 'accepted_at')) -or
                    -not (Test-RecoveryInteger $payload.schema_version 1 1) -or $payload.state -cne 'queued' -or
                    $payload.action_id -isnot [string] -or $payload.action_id -cnotmatch $uuid) {
                    throw 'accepted_action_receipt_required'
                }
                $actionId = $payload.action_id
                continue
            }

            if ($null -eq $actionId -or $call.arguments.action_id -cne $actionId -or
                $payload.action_id -cne $actionId) { throw 'action_id_mismatch' }
            if ($payload.state -cin @('queued', 'running')) {
                if ($null -ne $terminal) { throw 'nonterminal_after_terminal' }
                continue
            }
            if ($payload.state -cne 'succeeded' -or -not $payload.Contains('failure') -or
                $null -ne $payload.failure) { throw 'successful_terminal_required' }
            $progress = $payload.progress
            if ($progress.phase -cne 'finished' -or
                -not (Test-RecoveryInteger $progress.executed_nodes 1 1) -or
                -not (Test-RecoveryInteger $progress.total_node_upper_bound 1 1) -or
                -not (Test-RecoveryInteger $progress.interactions 1 2048)) {
                throw 'inspect_execution_evidence_invalid'
            }
            if ($payload.trace -isnot [array] -or $payload.trace.Count -gt 256) {
                throw 'bounded_terminal_trace_required'
            }
            foreach ($entry in $payload.trace) {
                if (-not (Test-RecoveryKeys $entry @('tick', 'event', 'detail')) -or
                    -not (Test-RecoveryInteger $entry.tick 0 1728200) -or
                    $entry.event -isnot [string] -or $entry.event -cnotmatch '^[A-Z0-9_]{1,64}$' -or
                    $entry.detail -isnot [string] -or $entry.detail.Length -gt 256) {
                    throw 'terminal_trace_entry_invalid'
                }
            }
            # A later page may add container_results, but cannot replace the terminal's
            # state/progress/trace with evidence borrowed from another snapshot.
            $core = [ordered]@{ progress = $progress; trace = $payload.trace } |
                ConvertTo-Json -Depth 100 -Compress
            if ($null -ne $terminalCore -and $core -cne $terminalCore) {
                throw 'terminal_evidence_changed'
            }
            $terminal = $payload
            $terminalCore = $core
            if (-not $payload.Contains('container_results')) { continue }
            if ($call.arguments.include_container_results -isnot [bool] -or
                -not $call.arguments.include_container_results -or
                $call.arguments.Contains('container_results_cursor')) {
                throw 'container_result_request_invalid'
            }
            $page = $payload.container_results
            if (-not (Test-RecoveryKeys $page @('results', 'total_results', 'retained_results',
                    'snapshot_result_count', 'returned_results', 'action_terminal', 'truncated',
                    'has_more', 'next_cursor')) -or $page.results -isnot [array] -or
                $page.results.Count -ne 1 -or -not (Test-RecoveryInteger $page.total_results 1 1) -or
                -not (Test-RecoveryInteger $page.retained_results 1 1) -or
                -not (Test-RecoveryInteger $page.snapshot_result_count 1 1) -or
                -not (Test-RecoveryInteger $page.returned_results 1 1) -or $page.action_terminal -isnot [bool] -or
                -not $page.action_terminal -or $page.truncated -isnot [bool] -or $page.truncated -or
                $page.has_more -isnot [bool] -or $page.has_more -or $null -ne $page.next_cursor) {
                throw 'complete_terminal_container_page_required'
            }
            $result = $page.results[0]
            if (-not (Test-RecoveryKeys $result @('result_seq', 'node_id', 'node_execution', 'target',
                    'world_session_id', 'observed_client_tick', 'packet_revision', 'items',
                    'total_item_types', 'returned_item_types', 'truncated')) -or
                -not (Test-RecoveryInteger $result.result_seq 1 1) -or $result.node_id -cne $node.id -or
                -not (Test-RecoveryInteger $result.node_execution 1 1) -or
                -not (Test-RecoveryKeys $result.target @('dimension', 'x', 'y', 'z')) -or
                $result.world_session_id -isnot [string] -or $result.world_session_id -cnotmatch $uuid -or
                -not (Test-RecoveryInteger $result.observed_client_tick) -or
                -not (Test-RecoveryInteger $result.packet_revision) -or
                $result.items -isnot [array] -or $result.items.Count -gt 54 -or
                -not (Test-RecoveryInteger $result.total_item_types $result.items.Count $result.items.Count) -or
                -not (Test-RecoveryInteger $result.returned_item_types $result.items.Count $result.items.Count) -or
                $result.truncated -isnot [bool] -or $result.truncated) {
                throw 'confirmed_inspect_result_invalid'
            }
            foreach ($key in @('dimension', 'x', 'y', 'z')) {
                if ($result.target[$key] -cne $node.target[$key]) { throw 'inspect_result_target_mismatch' }
            }
            $itemIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($item in $result.items) {
                if (-not (Test-RecoveryKeys $item @('item_id', 'count')) -or
                    $item.item_id -isnot [string] -or
                    $item.item_id -cnotmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$' -or
                    -not (Test-RecoveryInteger $item.count 1 3456) -or
                    -not $itemIds.Add($item.item_id)) { throw 'inspect_inventory_totals_invalid' }
            }
            $resultJson = $result | ConvertTo-Json -Depth 100 -Compress
            if ($null -ne $completeResult -and $resultJson -cne $completeResult) {
                throw 'immutable_inspect_result_changed'
            }
            $completeResult = $resultJson
        }

        if ($starts -ne 1 -or $null -eq $actionId) { throw 'one_accepted_action_required' }
        if ($null -eq $terminal) { throw 'terminal_result_missing' }
        if ($null -eq $completeResult) { throw 'confirmed_container_result_missing' }
        $summaries = @($terminal.trace | Where-Object { $_.event -ceq 'RENDERER_RECOVERY' })
        if ($summaries.Count -gt 1) { throw 'duplicate_recovery_summary' }
        if ($summaries.Count -eq 1) {
            $detail = $summaries[0].detail
            if ($detail -cnotmatch '^missing=([a-z_,]+);revalidated=([a-z_,]+)$') {
                throw 'recovery_summary_invalid'
            }
            $missing = @($Matches[1] -split ',')
            $revalidated = if ($Matches[2] -ceq 'none') { @() } else { @($Matches[2] -split ',') }
            $stages = @('capture', 'commit', 'dispatch', 'jit', 'initial_open')
            foreach ($set in @(@{ values = $missing }, @{ values = $revalidated })) {
                $values = @($set.values)
                if (@($values | Where-Object { $_ -cnotin $stages }).Count -gt 0 -or
                    @($values | Select-Object -Unique).Count -ne $values.Count -or
                    ($values -join ',') -cne (@($stages | Where-Object { $_ -cin $values }) -join ',')) {
                    throw 'recovery_stage_set_invalid'
                }
            }
            if (@($revalidated | Where-Object { $_ -cnotin $missing }).Count -gt 0) {
                throw 'revalidated_stage_without_missing'
            }
            $recovered = @($missing | Where-Object { $_ -cin $revalidated })
            if ($recovered.Count -eq 0) { throw 'recovery_stage_overlap_required' }
        }
    } catch {
        # Never reflect arbitrary JSON, parser exceptions, paths or tool error messages.
        $known = @('call_shape_invalid', 'action_call_not_successful', 'action_payload_invalid',
            'multiple_action_starts', 'standalone_inspect_required', 'inspect_target_invalid',
            'accepted_action_receipt_required', 'action_id_mismatch', 'nonterminal_after_terminal',
            'successful_terminal_required', 'inspect_execution_evidence_invalid',
            'bounded_terminal_trace_required', 'terminal_trace_entry_invalid', 'terminal_evidence_changed',
            'container_result_request_invalid', 'complete_terminal_container_page_required',
            'confirmed_inspect_result_invalid', 'inspect_result_target_mismatch',
            'inspect_inventory_totals_invalid', 'immutable_inspect_result_changed',
            'one_accepted_action_required', 'terminal_result_missing', 'confirmed_container_result_missing',
            'duplicate_recovery_summary', 'recovery_summary_invalid', 'recovery_stage_set_invalid',
            'revalidated_stage_without_missing', 'recovery_stage_overlap_required')
        $code = $_.Exception.Message
        $violations.Add($(if ($code -cin $known) { $code } else { 'recovery_evidence_malformed' }))
    }
    $status = if ($violations.Count -gt 0) { 'invalid' }
        elseif ($recovered.Count -gt 0) { 'witnessed' } else { 'not_exercised' }
    return [ordered]@{
        status = $status
        passed = ($status -ceq 'witnessed')
        action_id = $actionId
        missing_stages = @($missing | Where-Object { $_ -cin @('capture', 'commit', 'dispatch', 'jit', 'initial_open') })
        revalidated_stages = @($revalidated | Where-Object { $_ -cin @('capture', 'commit', 'dispatch', 'jit', 'initial_open') })
        recovered_stages = $recovered
        violations = @($violations)
    }
}

Export-ModuleMember -Function Get-McmcpRecoveryWitness
