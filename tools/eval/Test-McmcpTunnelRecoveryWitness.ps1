[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'McmcpTunnelRecoveryWitness.psm1') -Force
$actionId = '550e8400-e29b-41d4-a716-446655440071'

function New-Start([string]$Op = 'excavate_tunnel', [string]$ReceiptId = $actionId) {
    [ordered]@{
        tool = 'agent_start_action'
        arguments = [ordered]@{
            program = [ordered]@{
                body = @([ordered]@{ op = $Op; length_blocks = 16 })
            }
        }
        success = $true
        output_text = ([ordered]@{ action_id = $ReceiptId; state = 'queued' } |
            ConvertTo-Json -Depth 10 -Compress)
    }
}

function New-Get([string]$State, [string[]]$Details, [string]$RequestId = $actionId,
        [string]$ResultId = $actionId) {
    $trace = @($Details | ForEach-Object {
            [ordered]@{ tick = 1; event = 'NODE_EVIDENCE'; detail = $_ }
        })
    [ordered]@{
        tool = 'agent_get_action'
        arguments = [ordered]@{ action_id = $RequestId; wait_timeout_ms = 25000 }
        success = $true
        output_text = ([ordered]@{
                action_id = $ResultId; state = $State; progress = [ordered]@{ phase = 'finished' }
                failure = $null; trace = $trace
            } | ConvertTo-Json -Depth 10 -Compress)
    }
}

$summary = 'tunnel_renderer_missing=2,revalidated=1,scope=block_probe'
$otherId = '550e8400-e29b-41d4-a716-446655440072'
$cases = @(
    @{ Name = 'no_action_not_exercised'; Calls = @(); Status = 'not_exercised' },
    @{ Name = 'not_exercised'; Calls = @((New-Start), (New-Get succeeded @())); Status = 'not_exercised' },
    @{ Name = 'witnessed'; Calls = @((New-Start), (New-Get succeeded @($summary))); Status = 'witnessed' },
    @{ Name = 'missing_only'; Calls = @((New-Start), (New-Get failed @(
                    'tunnel_renderer_missing=1,revalidated=0,scope=block_probe'))); Status = 'observed_not_recovered' },
    @{ Name = 'cancelled_after_recovery'; Calls = @((New-Start), (New-Get cancelled @(
                    'tunnel_renderer_missing=1,revalidated=1,scope=block_probe'))); Status = 'observed_not_recovered' },
    @{ Name = 'duplicate'; Calls = @((New-Start), (New-Get succeeded @(
                    'tunnel_renderer_missing=1,revalidated=1,scope=block_probe',
                    'tunnel_renderer_missing=1,revalidated=1,scope=block_probe'))); Status = 'invalid' },
    @{ Name = 'impossible'; Calls = @((New-Start), (New-Get succeeded @(
                    'tunnel_renderer_missing=1,revalidated=2,scope=block_probe'))); Status = 'invalid' },
    @{ Name = 'borrowed_terminal'; Calls = @((New-Get succeeded @($summary))); Status = 'invalid' },
    @{ Name = 'request_id_mismatch'; Calls = @((New-Start),
            (New-Get succeeded @($summary) -RequestId $otherId)); Status = 'invalid' },
    @{ Name = 'result_id_mismatch'; Calls = @((New-Start),
            (New-Get succeeded @($summary) -ResultId $otherId)); Status = 'invalid' },
    @{ Name = 'wrong_operation'; Calls = @((New-Start 'move_to'),
            (New-Get succeeded @($summary))); Status = 'invalid' },
    @{ Name = 'multiple_starts'; Calls = @((New-Start), (New-Start),
            (New-Get succeeded @($summary))); Status = 'invalid' }
)

foreach ($case in $cases) {
    $actual = Get-McmcpTunnelRecoveryWitness -Calls @($case.Calls) `
        -ExpectedProfile tunnel-straight16
    if ($actual.status -cne $case.Status) {
        throw "$($case.Name): expected=$($case.Status) actual=$($actual.status) violations=$($actual.violations -join ',')"
    }
}

$branchStart = New-Start
$branchStart.arguments.program.body[0].pattern = 'branches'
$branchStart.arguments.program.body[0].branch_length_blocks = 3
$branchStart.arguments.program.body[0].branch_spacing_blocks = 4
$branch = Get-McmcpTunnelRecoveryWitness `
    -Calls @($branchStart, (New-Get succeeded @($summary))) `
    -ExpectedProfile tunnel-branches
if ($branch.status -cne 'witnessed') { throw 'exact branch profile witness was rejected' }

Write-Host "坑道renderer回復witness self-test: $($cases.Count + 1)/$($cases.Count + 1) passed"
