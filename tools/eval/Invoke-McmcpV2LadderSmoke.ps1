#requires -Version 7.4
[CmdletBinding()]
param([string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,[string]$ArtifactDirectory,
    [ValidateSet('Down','LongDown','Up','LongUp','Gap','Cancel','TickLimit','Enclosed')][string]$LadderCase='Down',
    [switch]$LibraryOnly)
$runLadder=-not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -Phase Core -LibraryOnly

function Get-V2SmokeState {
    $control=Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state=Invoke-V2SmokeTool 'agent_get_state'
    $p=$state.player.position
    Assert-V2Smoke ($p.x -ge 300 -and $p.x -le 320 -and $p.y -ge 101 -and $p.y -le 115 -and
        $p.z -ge 300 -and $p.z -le 320) 'Player is outside the ladder fixture'
    return $state
}

function Invoke-V2SmokeCore {
    $before=Get-V2SmokeState
    $top=if($LadderCase -cin @('LongDown','LongUp','Cancel','Enclosed')){112}else{104}
    $ascending=$LadderCase -cin @('Up','LongUp')
    $startY=if($ascending){101}else{$top}
    Assert-V2Smoke ([Math]::Abs($before.player.position.y-$startY) -lt 0.01) 'Unexpected ladder start height'
    $targetY=if($ascending){$top}else{101}
    $arguments=@{x=306;y=$targetY;z=305;clear_path=$false;auto_replan=$false;max_distance=24;
        max_ticks=$(if($LadderCase -ceq 'TickLimit'){3}else{600})}
    $id=Start-V2SmokeJob 'agent_move' $arguments
    if($LadderCase -ceq 'Cancel') {
        $watch=[Diagnostics.Stopwatch]::StartNew()
        do {
            $running=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;wait_timeout_ms=50;include_result=$true}
            Assert-V2Smoke ($running.state -cin @('queued','running') -and $watch.Elapsed.TotalSeconds -lt 15) 'Move ended before descending cancellation'
            $state=Get-V2SmokeState
        } while($state.player.position.y -gt $top-1)
        [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
    }
    $done=Wait-V2SmokeJob $id
    switch($LadderCase) {
        'Cancel' { Assert-V2Smoke ($done.state -ceq 'cancelled') 'Ladder cancellation failed' }
        'TickLimit' { Assert-V2Smoke ($done.state -ceq 'failed' -and $done.failure -ceq 'tick_limit') 'Tick limit failed' }
        'Gap' { Assert-V2Smoke ($done.state -ceq 'failed') 'Missing-rung route unexpectedly succeeded' }
        default { Assert-V2Smoke ($done.state -ceq 'succeeded') "Ladder move failed: $($done.failure)" }
    }
    $after=Get-V2SmokeState
    Assert-V2Smoke ($after.player.health -eq $before.player.health) 'Ladder movement lost health'
    if($LadderCase -cin @('Down','LongDown','Up','LongUp','Enclosed')) {
        $p=$after.player.position
        Assert-V2Smoke ([Math]::Abs($p.y-$targetY) -lt 0.05 -and
            [Math]::Sqrt([Math]::Pow($p.x-306.5,2)+[Math]::Pow($p.z-305.5,2)) -le 0.25) 'Ladder destination mismatch'
    }
    $script:SmokeEvents.Add(@{before=$before.player;after=$after.player;case=$LadderCase})
}
if($runLadder) {
    $result=Invoke-McmcpV2Smoke
    [pscustomobject]$result|Select-Object status,failure,active_action_id|ConvertTo-Json -Compress
    if($result.status -ne 'passed'){exit 1}
}
