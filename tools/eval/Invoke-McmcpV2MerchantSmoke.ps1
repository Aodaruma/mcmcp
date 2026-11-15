#requires -Version 7.4
[CmdletBinding()]
param([string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [string]$ExpectedWorldSession,[string]$ArtifactDirectory,
    [ValidateSet('Trade','Cancel','Stock')][string]$MerchantCase='Trade',[switch]$LibraryOnly)
$runMerchant=-not $LibraryOnly
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2ProductionSmoke.ps1') -TokenPath $TokenPath -Endpoint $Endpoint `
    -ExpectedWorldSession $ExpectedWorldSession -ArtifactDirectory $ArtifactDirectory -LibraryOnly

function Invoke-V2SmokeCore {
    [void](Get-V2SmokeState)
    $control=Invoke-V2SmokeTool 'agent_get_mcp_status'
    $observation=Invoke-V2SmokeTool 'agent_get_observation' @{schema_version=1;frame_id=$control.latest_frame_id;
        kinds=@('visible_entity');cursor=$null;limit=16}
    $merchant=@($observation.records|Where-Object {$_.kind -ceq 'visible_entity' -and $_.entity_type -ceq 'minecraft:villager'})
    Assert-V2Smoke ($merchant.Count -eq 1) 'Expected one visible fixture merchant'
    $inspect=@{target='entity';operation='inspect_offers';entity_ref=$merchant[0].entity_ref;advance=$false;max_ticks=600}
    $found=Invoke-V2SmokeJob 'agent_interact' $inspect
    Assert-V2Smoke ($found.result.offers.Count -eq 1) 'Expected one synchronized offer'
    $offer=$found.result.offers[0]
    Assert-V2Smoke ($offer.cost_a.item -ceq 'minecraft:emerald' -and $offer.cost_a.count -eq 3 -and
        $offer.cost_b.item -ceq 'minecraft:book' -and $offer.cost_b.count -eq 1 -and
        $offer.result.item -ceq 'minecraft:bread' -and $offer.result.count -eq 2 -and $offer.uses -eq 0) 'Unexpected fixture offer'
    $trade=$inspect.Clone(); $trade.operation='trade'; $trade.offer_ref=$offer.offer_ref; $trade.count=3
    $trade.max_total_cost=@(@{item='minecraft:emerald';count=9},@{item='minecraft:book';count=3})
    if($MerchantCase -eq 'Stock') {
        $trade.count=17; $trade.max_total_cost=@(@{item='minecraft:emerald';count=51},@{item='minecraft:book';count=17})
        $done=Wait-V2SmokeJob (Start-V2SmokeJob 'agent_interact' $trade)
        Assert-V2Smoke ($done.state -ceq 'failed' -and $done.result.failure -ceq 'insufficient_trade_stock' -and $done.result.confirmed_trades -eq 0) 'Stock rejection failed'
    } elseif($MerchantCase -eq 'Cancel') {
        $trade.count=10; $trade.max_total_cost=@(@{item='minecraft:emerald';count=30},@{item='minecraft:book';count=10})
        $id=Start-V2SmokeJob 'agent_interact' $trade
        $clock=[Diagnostics.Stopwatch]::StartNew()
        do {
            $running=Invoke-V2SmokeTool 'agent_get_action' @{action_id=$id;wait_timeout_ms=50;include_result=$true}
            Assert-V2Smoke ($running.state -cin @('queued','running') -and $clock.Elapsed.TotalSeconds -lt 15) 'Trade did not reach payment preparation'
        } while(-not (Get-PropertyValue (Get-PropertyValue $running 'result') 'unconfirmed'))
        [void](Invoke-V2SmokeTool 'agent_cancel_action' @{action_id=$id})
        $done=Wait-V2SmokeJob $id
        Assert-V2Smoke ($done.state -ceq 'cancelled' -and -not $done.result.release_pending) 'Trade cancellation failed'
    } else {
        $low=$trade.Clone(); $low.max_total_cost=@(@{item='minecraft:emerald';count=8},@{item='minecraft:book';count=3})
        $rejected=Wait-V2SmokeJob (Start-V2SmokeJob 'agent_interact' $low)
        Assert-V2Smoke ($rejected.state -ceq 'failed' -and $rejected.result.failure -ceq 'trade_cost_limit' -and $rejected.result.confirmed_trades -eq 0) 'Cost rejection failed'
        $done=Invoke-V2SmokeJob 'agent_interact' $trade
        Assert-V2Smoke ($done.result.confirmed_trades -eq 3 -and $done.result.confirmed_count -eq 6 -and -not $done.result.unconfirmed) 'Trade quantity mismatch'
        $stale=Wait-V2SmokeJob (Start-V2SmokeJob 'agent_interact' $trade)
        Assert-V2Smoke ($stale.state -ceq 'failed' -and $stale.result.failure -ceq 'merchant_offer_changed' -and $stale.result.confirmed_trades -eq 0) 'Stale offer was reused'
    }
    $inventory=Get-V2SmokeInventory
    $trades=$done.result.confirmed_trades
    Assert-V2Smoke ((Get-V2SmokeItemCount $inventory 'minecraft:emerald') -eq 30-3*$trades -and
        (Get-V2SmokeItemCount $inventory 'minecraft:book') -eq 10-$trades -and
        (Get-V2SmokeItemCount $inventory 'minecraft:bread') -eq 2*$trades) 'Trade conservation mismatch'
}
if($runMerchant) {
    $result=Invoke-McmcpV2Smoke
    [pscustomobject]$result|Select-Object status,failure,active_action_id|ConvertTo-Json -Compress
    if($result.status -ne 'passed'){exit 1}
}
