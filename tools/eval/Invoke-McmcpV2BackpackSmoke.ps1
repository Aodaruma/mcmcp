#requires -Version 7.4
[CmdletBinding()]
param([string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,[string]$ArtifactDirectory,
    [ValidateSet('Plain','Upgraded','Tab','Cancel','Budget')][string]$BackpackCase='Tab',
    [switch]$LibraryOnly)
$runBackpack=-not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2Smoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -Phase Core -LibraryOnly

function Get-V2SmokeState {
    $control=Invoke-V2SmokeTool 'agent_get_mcp_status'
    Assert-V2Smoke ($control.world_session_id -ceq $ExpectedWorldSession) 'World session changed'
    $state=Invoke-V2SmokeTool 'agent_get_state'
    $p=$state.player.position
    Assert-V2Smoke ([Math]::Abs($p.x-305.5) -lt 0.01 -and [Math]::Abs($p.y-101) -lt 0.01 -and
        [Math]::Abs($p.z-305.5) -lt 0.01 -and $state.player.health -eq 20) 'Outside the healthy fixed storage fixture'
    return $state
}

function Get-BackpackCount($Slots,[string]$Item) {
    return [int](($Slots|Where-Object item -CEQ $Item|Measure-Object count -Sum).Sum)
}

function Invoke-V2SmokeCore {
    [void](Get-V2SmokeState)
    $initial=if($BackpackCase -ceq 'Plain'){64}else{2048}
    $inspect=@{operation='inspect';target='storage';storage_slot=6;
        storage_item='sophisticatedbackpacks:netherite_backpack';max_ticks=400}
    $before=Invoke-V2SmokeJob 'agent_inventory' $inspect
    Assert-V2Smoke ($before.result.complete -and (Get-BackpackCount $before.result.slots 'minecraft:white_concrete') -eq $initial -and
        (Get-BackpackCount $before.result.slots 'minecraft:black_concrete') -eq 64) 'Initial backpack mismatch'
    $take=$inspect.Clone(); $take.operation='transfer'; $take.direction='take'; $take.item='minecraft:white_concrete'
    $take.count=if($BackpackCase -ceq 'Cancel'){448}elseif($BackpackCase -ceq 'Budget'){896}else{24}
    $id=Start-V2SmokeJob 'agent_inventory' $take
    if($BackpackCase -ceq 'Cancel') {
        $watch=[Diagnostics.Stopwatch]::StartNew()
        do {
            $running=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;wait_timeout_ms=10;include_result=$true}
            Assert-V2Smoke ($running.state -cin @('unconfirmed','queued','running') -and $watch.Elapsed.TotalSeconds -lt 15) 'Transfer ended before cancellation'
            $progress=Get-PropertyValue (Get-PropertyValue $running 'result') 'storage_progress'
        } while((Get-PropertyValue $progress 'phase') -cne 'exact_update')
        $script:SmokeEvents.Add(@{cancel_trigger=$running})
        [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
    }
    $done=Wait-V2SmokeJob $id
    if($BackpackCase -ceq 'Cancel') {
        Assert-V2Smoke ($done.state -ceq 'cancelled' -and -not $done.result.release_pending) 'Cancellation did not release the menu'
    } elseif($BackpackCase -ceq 'Budget') {
        Assert-V2Smoke ($done.state -ceq 'failed' -and $done.result.confirmed_count -eq 0 -and -not $done.result.unconfirmed) 'Click budget did not reject before transfer'
    } else {
        Assert-V2Smoke ($done.state -ceq 'succeeded' -and $done.result.confirmed_count -eq 24 -and -not $done.result.unconfirmed) 'Exact take failed'
        $store=$take.Clone(); $store.direction='store'; $store.item='minecraft:black_concrete'; $store.count=16
        $stored=Invoke-V2SmokeJob 'agent_inventory' $store
        Assert-V2Smoke ($stored.result.confirmed_count -eq 16 -and -not $stored.result.unconfirmed) 'Exact store failed'
    }
    $after=Invoke-V2SmokeJob 'agent_inventory' $inspect
    $inventory=Get-V2SmokeInventory
    $ownWhite=Get-V2SmokeItemCount $inventory 'minecraft:white_concrete'
    $bagWhite=Get-BackpackCount $after.result.slots 'minecraft:white_concrete'
    $ownBlack=Get-V2SmokeItemCount $inventory 'minecraft:black_concrete'
    $bagBlack=Get-BackpackCount $after.result.slots 'minecraft:black_concrete'
    Assert-V2Smoke ($ownWhite+$bagWhite -eq $initial -and $ownBlack+$bagBlack -eq 96) 'Item conservation failed'
    if($BackpackCase -cin @('Plain','Upgraded','Tab')) {
        Assert-V2Smoke ($ownWhite -eq 24 -and $ownBlack -eq 16 -and $bagBlack -eq 80) 'Readback quantity mismatch'
    }
    [void](Get-V2SmokeState)
}
if($runBackpack) {
    $result=Invoke-McmcpV2Smoke
    [pscustomobject]$result|Select-Object status,failure,active_action_id|ConvertTo-Json -Compress
    if($result.status -ne 'passed'){exit 1}
}
