#requires -Version 7.4
[CmdletBinding()]
param([string]$PlanPath,[string]$CheckpointPath,[string]$TokenPath,
    [string]$Endpoint='http://127.0.0.1:8765/mcp',
    [ValidateRange(1,16384)][int]$MaxSteps=64,
    [ValidateRange(1,86400)][int]$MaxSeconds=900,
    [switch]$AcceptWorldSessionChange,
    [ValidateSet('Complete','Retry')][string]$ResolvePending,
    [switch]$StatusOnly,[switch]$LibraryOnly)
. (Join-Path $PSScriptRoot 'McmcpBuildingLedger.ps1')
. (Join-Path $PSScriptRoot '../mcp/McmcpClient.ps1')

# Host-side ledger only. Minecraft receives bounded public v2 placements, never files or commands.
function Read-V2ConstructionPlan([string]$Path) {
    $file=Get-Item -LiteralPath $Path
    if($file.Length -gt 2097152){throw 'plan_too_large'}
    $text=[IO.File]::ReadAllText($file.FullName)
    $plan=ConvertFrom-Json -InputObject $text -AsHashtable -Depth 50
    if($plan.version -ne 2 -or $plan.dimension -cnotmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$' -or
        $plan.steps -isnot [array] -or $plan.steps.Count -lt 1 -or $plan.steps.Count -gt 16384){throw 'invalid_construction_plan'}
    foreach($key in $plan.Keys){if($key -cnotin @('version','dimension','steps')){throw 'unknown_plan_key'}}
    $catalog=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw | ConvertFrom-Json -Depth 100
    $schema=($catalog.tools | Where-Object name -CEQ 'agent_place_block').inputSchema
    foreach($step in $plan.steps) {
        Assert-McmcpSchema $step $schema 'invalid_placement_step'
        foreach($key in @('dx','dy','dz','max_blocks')){if($step.Contains($key)){throw 'construction_requires_single_anchors'}}
    }
    return @{hash=(Get-BuildingHash $text);dimension=$plan.dimension;steps=$plan.steps}
}
function Read-V2ConstructionCheckpoint([string]$Path,$Plan) {
    if(-not [IO.File]::Exists($Path)) {
        return @{version=2;plan_sha256=$Plan.hash;session_id=$null;next=0;status='new';pending=$null;receipts=@();resolutions=@()}
    }
    if((Get-Item -LiteralPath $Path).Length -gt 33554432){throw 'checkpoint_too_large'}
    $envelope=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable -Depth 60
    if($envelope.sha256 -cne (Get-BuildingHash $envelope.data)){throw 'checkpoint_checksum_mismatch'}
    $checkpoint=ConvertFrom-Json -InputObject $envelope.data -AsHashtable -Depth 50
    if($checkpoint.version -ne 2 -or $checkpoint.plan_sha256 -cne $Plan.hash -or
        ($checkpoint.next -isnot [int] -and $checkpoint.next -isnot [long]) -or
        $checkpoint.next -lt 0 -or $checkpoint.next -gt $Plan.steps.Count -or
        $checkpoint.receipts.Count -ne $checkpoint.next){throw 'checkpoint_plan_mismatch'}
    if($null -ne $checkpoint.pending -and ($checkpoint.pending.index -ne $checkpoint.next -or
        $checkpoint.next -ge $Plan.steps.Count -or ($null -ne $checkpoint.pending.action_id -and
        $checkpoint.pending.action_id -cnotmatch '^[0-9a-f-]{36}$'))){throw 'invalid_pending_step'}
    return $checkpoint
}
function Invoke-V2ConstructionTool([string]$Name,$Arguments=@{}) {
    $reply=Invoke-McmcpClient -TokenPath $TokenPath -Endpoint $Endpoint -Tool $Name -Arguments $Arguments
    if(-not $reply.ok){throw "construction_call_failed:$Name/$($reply.diagnostic_code)"}
    return ($reply.result | ConvertTo-Json -Depth 100 -Compress | ConvertFrom-Json -AsHashtable -Depth 100)
}
function Assert-V2ConstructionSession($Plan,$Checkpoint) {
    $control=Invoke-V2ConstructionTool 'agent_get_mcp_status'
    $state=Invoke-V2ConstructionTool 'agent_get_state'
    if($control.world_session_id -cne $Checkpoint.session_id -or $state.player.dimension -cne $Plan.dimension){throw 'world_session_changed'}
}
function Complete-V2ConstructionStep($Checkpoint,$Terminal) {
    if($null -eq $Checkpoint.pending -or $Terminal.action_id -cne $Checkpoint.pending.action_id){throw 'pending_action_mismatch'}
    if($Terminal.state -cnotin @('succeeded','failed','cancelled')){return $false}
    # A skipped non-matching cell is not completion. Only placement or visible desired-state evidence counts.
    $verified=$false
    if($Terminal.Contains('result') -and $null -ne $Terminal.result) {
        $r=$Terminal.result
        $verified=($r.Contains('confirmed_count') -and $r.confirmed_count -eq 1) -or
            ($r.Contains('observed_count') -and $r.observed_count -eq 1)
    }
    if($Terminal.state -ceq 'succeeded' -and $verified) {
        $Checkpoint.receipts+=,@{index=$Checkpoint.next;action_id=$Terminal.action_id;result=$Terminal.result}
        $Checkpoint.next++
        $Checkpoint.pending=$null
        $Checkpoint.status='paused'
        return $true
    }
    $Checkpoint.pending.terminal=$Terminal
    $Checkpoint.status='needs_review'
    return $false
}
function Resolve-V2ConstructionPending($Checkpoint,[string]$Resolution) {
    if($null -eq $Checkpoint.pending){throw 'no_pending_step'}
    $Checkpoint.resolutions+=,@{index=$Checkpoint.next;action_id=$Checkpoint.pending.action_id;resolution=$Resolution;utc=[DateTime]::UtcNow.ToString('o')}
    if($Resolution -ceq 'Complete') {
        $Checkpoint.receipts+=,@{index=$Checkpoint.next;action_id=$Checkpoint.pending.action_id;verification='operator_confirmed'}
        $Checkpoint.next++
    } elseif($Resolution -cne 'Retry'){throw 'invalid_resolution'}
    $Checkpoint.pending=$null
    $Checkpoint.status='paused'
}
function Invoke-V2ConstructionRun {
    if([string]::IsNullOrWhiteSpace($PlanPath) -or [string]::IsNullOrWhiteSpace($CheckpointPath)){throw 'plan_and_checkpoint_required'}
    $plan=Read-V2ConstructionPlan $PlanPath
    $fullCheckpoint=[IO.Path]::GetFullPath($CheckpointPath)
    if($fullCheckpoint -eq [IO.Path]::GetFullPath($PlanPath)){throw 'checkpoint_must_not_replace_plan'}
    # One process owns the ledger. The lock contains no credentials and persists harmlessly after exit.
    $lock=[IO.File]::Open($fullCheckpoint+'.lock',[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $checkpoint=Read-V2ConstructionCheckpoint $fullCheckpoint $plan
        if($StatusOnly){return $checkpoint}
        $control=Invoke-V2ConstructionTool 'agent_get_mcp_status'
        $state=Invoke-V2ConstructionTool 'agent_get_state'
        if($state.player.dimension -cne $plan.dimension){throw 'plan_dimension_mismatch'}
        if($null -ne $checkpoint.session_id -and $checkpoint.session_id -cne $control.world_session_id -and -not $AcceptWorldSessionChange){throw 'verify_world_then_accept_session_change'}
        $sessionChanged=$null -ne $checkpoint.session_id -and $checkpoint.session_id -cne $control.world_session_id
        if($ResolvePending) {
            # Explicit resolution is never performed while an old live action could still mutate the world.
            if($null -ne $control.running_action_id){throw 'finish_or_cancel_active_action_before_resolution'}
            Resolve-V2ConstructionPending $checkpoint $ResolvePending
        } elseif($sessionChanged -and $null -ne $checkpoint.pending){throw 'previous_session_pending_requires_review'}
        $checkpoint.session_id=$control.world_session_id
        Save-BuildingCheckpoint $fullCheckpoint $checkpoint
        $clock=[Diagnostics.Stopwatch]::StartNew()
        $initial=$checkpoint.next
        while($checkpoint.next -lt $plan.steps.Count -and $checkpoint.next-$initial -lt $MaxSteps) {
            Assert-V2ConstructionSession $plan $checkpoint
            if($clock.Elapsed.TotalSeconds -ge $MaxSeconds){break}
            if($null -eq $checkpoint.pending) {
                $checkpoint.pending=@{index=$checkpoint.next;action_id=$null;terminal=$null}
                $checkpoint.status='dispatching'
                Save-BuildingCheckpoint $fullCheckpoint $checkpoint
                $receipt=Invoke-V2ConstructionTool 'agent_place_block' $plan.steps[$checkpoint.next]
                if($receipt.action_id -cnotmatch '^[0-9a-f-]{36}$' -or $receipt.state -cne 'queued'){throw 'invalid_action_receipt'}
                $checkpoint.pending.action_id=$receipt.action_id
                $checkpoint.status='running'
                Save-BuildingCheckpoint $fullCheckpoint $checkpoint
            }
            if($null -eq $checkpoint.pending.action_id){throw 'unconfirmed_dispatch_requires_review'}
            if($null -ne $checkpoint.pending.terminal){throw 'pending_terminal_requires_review'}
            do {
                Assert-V2ConstructionSession $plan $checkpoint
                $job=Invoke-V2ConstructionTool 'agent_get_action' @{action_id=$checkpoint.pending.action_id;include_result=$true;wait_timeout_ms=1000}
                if($job.action_id -cne $checkpoint.pending.action_id){throw 'action_identity_mismatch'}
                if($job.state -cin @('succeeded','failed','cancelled')) {
                    $done=Complete-V2ConstructionStep $checkpoint $job
                    Save-BuildingCheckpoint $fullCheckpoint $checkpoint
                    if(-not $done){throw 'placement_requires_review'}
                    break
                }
                if($clock.Elapsed.TotalSeconds -ge $MaxSeconds){throw 'construction_wait_limit'}
            } while($true)
        }
        $checkpoint.status=if($checkpoint.next -eq $plan.steps.Count){'complete'}else{'paused'}
        Save-BuildingCheckpoint $fullCheckpoint $checkpoint
        return $checkpoint
    } finally { $lock.Dispose() }
}
if(-not $LibraryOnly){Invoke-V2ConstructionRun}
