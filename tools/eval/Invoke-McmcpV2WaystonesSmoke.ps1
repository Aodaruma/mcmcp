#requires -Version 7.4
[CmdletBinding()]
param([string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,[string]$ArtifactDirectory,
    [ValidateSet('Inspect','Cancel','TickLimit','Budget','UnknownRef')][string]$WaystoneCase='Inspect',
    [switch]$LibraryOnly)
$runWaystones=-not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -Phase Core -LibraryOnly

function Get-V2SmokeState {
    $control=Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state=Invoke-V2SmokeTool 'agent_get_state'
    $p=$state.player.position
    Assert-V2Smoke ([Math]::Abs($p.x-305.5) -lt 0.01 -and [Math]::Abs($p.y-101) -lt 0.01 -and
        [Math]::Abs($p.z-305.5) -lt 0.01 -and $state.player.health -eq 20) 'Outside the healthy fixed Waystones fixture'
    return $state
}

function Invoke-V2SmokeCore {
    [void](Get-V2SmokeState)
    $inspect=@{target='item';item='waystones:warp_stone';operation='inspect_destinations';max_ticks=600}
    if($WaystoneCase -cin @('Cancel','TickLimit')) {
        $request=$inspect.Clone()
        if($WaystoneCase -ceq 'TickLimit'){$request.max_ticks=1}
        $id=Start-V2SmokeJob 'agent_interact' $request
        if($WaystoneCase -ceq 'Cancel') {
            $watch=[Diagnostics.Stopwatch]::StartNew()
            do {
                $running=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;wait_timeout_ms=10;include_result=$true}
                Assert-V2Smoke ($running.state -cin @('queued','running') -and $watch.Elapsed.TotalSeconds -lt 15) 'Use ended before cancellation'
            } while(-not (Get-PropertyValue (Get-PropertyValue $running 'result') 'dispatched'))
            [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
        }
        $done=Wait-V2SmokeJob $id
        $expected=if($WaystoneCase -ceq 'Cancel'){'cancelled'}else{'failed'}
        Assert-V2Smoke ($done.state -ceq $expected -and -not $done.result.release_pending -and
            -not $done.result.selection_dispatched) 'Held-use stop did not release safely'
        # A new inspect must be able to own a new menu after the cancelled late-open barrier.
    }
    $listed=Invoke-V2SmokeJob 'agent_interact' $inspect
    Assert-V2Smoke ($listed.result.complete -and -not $listed.result.selection_dispatched -and
        -not $listed.result.effect_confirmed) 'Inspection claimed a selection or an effect'
    $rows=@($listed.result.destinations)
    foreach($name in @('MCMCP Overworld','MCMCP End')) {
        Assert-V2Smoke (@($rows|Where-Object name -CEQ $name).Count -eq 1) 'Fixture destination missing or duplicated'
    }
    foreach($row in $rows) {
        Assert-V2Smoke ($row.destination_ref -cmatch '^[A-Za-z0-9_-]{24}$' -and
            $row.xp_levels -ge 0 -and $row.xp_points -ge 0) 'Invalid destination metadata'
    }
    if($WaystoneCase -cin @('Budget','UnknownRef')) {
        $select=@{target='item';item='waystones:warp_stone';operation='select_destination';max_ticks=600}
        if($WaystoneCase -ceq 'Budget') {
            $paid=@($rows|Where-Object {$_.selectable -and ($_.xp_levels -gt 0 -or $_.xp_points -gt 0)})
            Assert-V2Smoke ($paid.Count -gt 0) 'Cost test requires an affordable paid fixture destination'
            $select.destination_ref=$paid[0].destination_ref
        } else {$select.destination_ref='a'*24}
        $done=Wait-V2SmokeJob (Start-V2SmokeJob 'agent_interact' $select)
        Assert-V2Smoke ($done.state -ceq 'failed' -and -not $done.result.selection_dispatched -and
            $done.result.failure -ceq 'waystone_destination_or_cost_changed' -and
            -not $done.result.release_pending) 'Invalid reference or unpaid cost was not rejected safely'
    }
    [void](Get-V2SmokeState)
}
if($runWaystones) {
    $result=Invoke-McmcpV2Smoke
    [pscustomobject]$result|Select-Object status,failure,active_action_id|ConvertTo-Json -Compress
    if($result.status -ne 'passed'){exit 1}
}
