Set-StrictMode -Version Latest

function Test-TunnelInteger {
    param($Value, [long]$Minimum = 0, [long]$Maximum = [long]::MaxValue)
    return ($Value -is [int] -or $Value -is [long] -or $Value -is [bigint]) -and
        $Value -ge $Minimum -and $Value -le $Maximum
}

function Get-McmcpTunnelRecoveryWitness {
    <# Consumes only calls whose bridge identity, arguments, output hash and lifecycle were
       already audited. It binds recovery evidence to the one tunnel Action accepted in this run. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Calls,
        [Parameter(Mandatory)]
        [ValidateSet('tunnel-straight16', 'tunnel-straight160', 'tunnel-branches', 'tunnel-hazard')]
        [string]$ExpectedProfile
    )

    $violations = [Collections.Generic.List[string]]::new()
    $summaries = [Collections.Generic.List[object]]::new()
    $terminalStates = [Collections.Generic.List[string]]::new()
    $actionId = $null
    $terminalCore = $null
    $acceptedStarts = 0
    $uuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    try {
        foreach ($rawCall in $Calls) {
            $call = $rawCall | ConvertTo-Json -Depth 100 -Compress |
                ConvertFrom-Json -AsHashtable
            if ($call -isnot [Collections.IDictionary] -or
                $call.tool -isnot [string] -or $call.arguments -isnot [Collections.IDictionary] -or
                $call.success -isnot [bool] -or $call.output_text -isnot [string]) {
                throw 'call_shape_invalid'
            }
            if ($call.tool -cin @('agent_get_state', 'agent_get_observation')) { continue }
            if ($call.tool -ceq 'agent_start_action') {
                if (-not $call.success) { continue }
                $acceptedStarts++
                if ($acceptedStarts -ne 1) { throw 'multiple_tunnel_action_starts' }
                $program = $call.arguments.program
                if ($program -isnot [Collections.IDictionary] -or $program.body -isnot [array] -or
                    $program.body.Count -ne 1 -or
                    $program.body[0] -isnot [Collections.IDictionary]) {
                    throw 'standalone_tunnel_action_required'
                }
                $node = $program.body[0]
                $expectedLength = if ($ExpectedProfile -ceq 'tunnel-straight160') { 160 } else { 16 }
                if ($node.op -cne 'excavate_tunnel' -or
                    -not (Test-TunnelInteger $node.length_blocks $expectedLength $expectedLength)) {
                    throw 'tunnel_profile_mismatch'
                }
                if ($ExpectedProfile -ceq 'tunnel-branches') {
                    if ($node.pattern -cne 'branches' -or
                        -not (Test-TunnelInteger $node.branch_length_blocks 3 3) -or
                        -not (Test-TunnelInteger $node.branch_spacing_blocks 4 4)) {
                        throw 'tunnel_profile_mismatch'
                    }
                } elseif (($node.Contains('pattern') -and $node.pattern -cne 'straight') -or
                    $node.Contains('branch_length_blocks') -or
                    $node.Contains('branch_spacing_blocks')) {
                    throw 'tunnel_profile_mismatch'
                }
                $payload = ConvertFrom-Json -AsHashtable -InputObject $call.output_text
                if ($payload -isnot [Collections.IDictionary] -or
                    $payload.action_id -isnot [string] -or $payload.action_id -cnotmatch $uuid -or
                    $payload.state -cne 'queued') {
                    throw 'accepted_tunnel_receipt_required'
                }
                $actionId = $payload.action_id
                continue
            }
            if ($call.tool -ceq 'agent_cancel_action') { continue }
            if ($call.tool -cne 'agent_get_action' -or -not $call.success) { continue }
            if ($null -eq $actionId -or $call.arguments.action_id -cne $actionId) {
                throw 'tunnel_action_id_mismatch'
            }
            $payload = ConvertFrom-Json -AsHashtable -InputObject $call.output_text
            if ($payload -isnot [Collections.IDictionary] -or $payload.action_id -cne $actionId) {
                throw 'tunnel_action_id_mismatch'
            }
            if ($payload.state -cin @('queued', 'running')) { continue }
            if ($payload.state -cnotin @('succeeded', 'failed', 'cancelled') -or
                $payload.trace -isnot [array] -or $payload.trace.Count -gt 256) {
                throw 'bounded_tunnel_terminal_required'
            }
            $terminalStates.Add([string]$payload.state)
            $core = [ordered]@{
                state = $payload.state; progress = $payload.progress
                failure = $payload.failure; trace = $payload.trace
            } | ConvertTo-Json -Depth 100 -Compress
            if ($null -ne $terminalCore -and $terminalCore -cne $core) {
                throw 'tunnel_terminal_evidence_changed'
            }
            $terminalCore = $core
            $matching = @($payload.trace | Where-Object {
                    $_ -is [Collections.IDictionary] -and $_.event -ceq 'NODE_EVIDENCE' -and
                    $_.detail -is [string] -and
                    $_.detail.StartsWith('tunnel_renderer_missing=', [StringComparison]::Ordinal)
                })
            if ($matching.Count -gt 1) { throw 'duplicate_tunnel_renderer_summary' }
            if ($matching.Count -eq 0) { continue }
            $match = [regex]::Match([string]$matching[0].detail,
                '^tunnel_renderer_missing=([0-9]{1,5}),revalidated=([0-9]{1,5}),scope=block_probe$')
            if (-not $match.Success) { throw 'tunnel_renderer_summary_invalid' }
            $missing = [int]$match.Groups[1].Value
            $revalidated = [int]$match.Groups[2].Value
            if ($missing -lt 1 -or $missing -gt 65535 -or $revalidated -lt 0 -or
                $revalidated -gt $missing) {
                throw 'tunnel_renderer_summary_invalid'
            }
            $summaries.Add([ordered]@{
                    detail = [string]$matching[0].detail
                    missing = $missing
                    revalidated = $revalidated
                    terminal_state = [string]$payload.state
                })
        }
        $uniqueDetails = @($summaries | ForEach-Object { $_.detail } | Select-Object -Unique)
        if ($uniqueDetails.Count -gt 1) { throw 'tunnel_renderer_summary_changed' }
    } catch {
        $known = @('call_shape_invalid', 'multiple_tunnel_action_starts',
            'standalone_tunnel_action_required', 'tunnel_profile_mismatch',
            'accepted_tunnel_receipt_required', 'tunnel_action_id_mismatch',
            'bounded_tunnel_terminal_required', 'tunnel_terminal_evidence_changed',
            'duplicate_tunnel_renderer_summary', 'tunnel_renderer_summary_invalid',
            'tunnel_renderer_summary_changed')
        $code = $_.Exception.Message
        $violations.Add($(if ($code -cin $known) { $code } else {
                    'tunnel_renderer_evidence_malformed' }))
    }

    $witness = @($summaries | Where-Object {
            $_.missing -gt 0 -and $_.revalidated -gt 0 -and
            $_.terminal_state -ceq 'succeeded'
        })
    $status = if ($violations.Count -gt 0) { 'invalid' }
        elseif ($witness.Count -gt 0) { 'witnessed' }
        elseif ($summaries.Count -gt 0) { 'observed_not_recovered' }
        else { 'not_exercised' }
    return [ordered]@{
        status = $status
        passed = ($status -ceq 'witnessed')
        action_id = $actionId
        missing = $(if ($summaries.Count -gt 0) { $summaries[0].missing } else { 0 })
        revalidated = $(if ($summaries.Count -gt 0) { $summaries[0].revalidated } else { 0 })
        terminal_states = @($terminalStates | Select-Object -Unique)
        violations = @($violations)
    }
}

Export-ModuleMember -Function Get-McmcpTunnelRecoveryWitness
