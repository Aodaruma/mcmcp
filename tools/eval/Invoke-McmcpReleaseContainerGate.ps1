[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [string]$TokenPath,
    [string]$Endpoint='http://127.0.0.1:8765/mcp',
    [ValidateSet('normal','partial','exact')][string]$Scenario='normal',
    [switch]$LibraryOnly
)
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionMaterialObservation.ps1')

function Invoke-ReleaseContainerStep {
    param([string]$Name, [ValidateSet('inspect','take','store')][string]$Operation,
        [string]$Block, [int]$X, [int]$Z, [string]$Item, [int]$Goal,
        [int]$Limit=896, [int]$ExpectedTransfer=0, [int]$TransferCount=0, [switch]$ExpectedPartial)
    $before=Get-InventoryCount -State (Get-FreshState) -Item $Item
    $surface=Get-MaterialSurface $Block $X 61 $Z @('up')
    $node=[ordered]@{id=$Name;op='inspect_known_container';target=$surface.position;expected_block=$Block}
    $interactions=1
    if($Operation -ne 'inspect') {
        $node.op=if($Operation -eq 'take'){'take_known_container_stack'}else{'store_known_container_stack'}
        $node.item=$Item;$node.stack_policy='default_components_only'
        $node.max_stacks=14;$node.max_transfer_count=$Limit
        if($TransferCount -gt 0){$node.transfer_count=$TransferCount}
        if($Operation -eq 'take'){$node.minimum_inventory_count=$Goal}else{$node.minimum_container_count=$Goal}
        $interactions=16
    }
    $terminal=Invoke-ActionRequest -Request (New-PrimitiveRequest -Name $Name `
        -Capabilities @('camera','inventory_transfer') -Node $node -Duration 90000 -Ticks 1800 `
        -Interactions $interactions) -WallTimeoutSeconds 180 -ReturnFailure
    $script:ReleaseTerminals.Add($terminal)
    if($ExpectedPartial) {
        if($terminal.state -cne 'failed' -or $terminal.failure.code -cne 'SERVER_DENIED_OR_DESYNC' -or
            $terminal.failure.evidence -cnotcontains 'transfer_batch_goal_not_reached' -or
            -not $terminal.partial.has_confirmed_effects -or -not $terminal.partial.resume_requires_reobservation){
            throw 'Partial transfer omitted its expected failure or reobservation requirement'
        }
    }elseif($terminal.state -cne 'succeeded'){throw 'Container step did not succeed'}
    if($terminal.effect_aggregate.unknown_effects -ne 0){throw 'Unknown effect in container regression'}
    if($Operation -eq 'inspect') {
        $readback=Invoke-GateTool -Tool 'agent_get_action' -Arguments @{
            action_id=$terminal.action_id;wait_timeout_ms=0;include_container_results=$true;container_results_limit=1}
        if($readback.action_id -cne $terminal.action_id){throw 'Inspection result ID mismatch'}
        $script:ReleaseInspections.Add($readback)
        $results=@($readback.container_results.results)
        if($results.Count -ne 1 -or $results[0].truncated -or
            $null -ne $readback.container_results.next_cursor -or
            $results[0].target.dimension -cne 'minecraft:overworld' -or
            $results[0].target.x -ne $X -or $results[0].target.y -ne 61 -or $results[0].target.z -ne $Z){
            throw 'Missing, truncated, or mismatched inspection'
        }
        $count=0
        foreach($stack in @($results[0].items)){if($stack.item_id -ceq $Item){$count+=[int]$stack.count}}
        if($count -ne $Goal){throw "Inspection count mismatch: expected $Goal, observed $count"}
    }else{
        $effects=@($terminal.effects)
        $kind=if($Operation -eq 'take'){'container_take'}else{'container_store'}
        if($effects.Count -eq 0 -or @($effects|Where-Object {$_.verification -cne 'confirmed' -or $_.kind -cne $kind}).Count){
            throw 'Transfer effect was not confirmed with the expected kind'
        }
        $transferred=0
        foreach($effect in $effects){$transferred+=[int]$effect.observed_after.transferred}
        if($transferred -ne $ExpectedTransfer){throw 'Confirmed transfer total mismatch'}
        $inventory=Get-InventoryCount -State (Get-FreshState) -Item $Item
        $expectedInventory=if($Operation -eq 'take'){$before+$ExpectedTransfer}else{$before-$ExpectedTransfer}
        if($inventory -ne $expectedInventory){throw 'Post-transfer inventory mismatch'}
    }
}

function Invoke-McmcpReleaseContainerGate {
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $resultPath=Join-Path $ArtifactDirectory 'container-result.json'
    if(Test-Path -LiteralPath $resultPath){throw 'Refusing to overwrite an existing result'}
    $script:ReleaseTerminals=[Collections.Generic.List[object]]::new()
    $script:ReleaseInspections=[Collections.Generic.List[object]]::new()
    $failure=$null;$release=$null
    try {
        Assert-FixedFiveToolSurface
        $state=Get-FreshState
        foreach($item in @('minecraft:snow_block','minecraft:black_wool')){
            if((Get-InventoryCount -State $state -Item $item) -ne 0){throw 'Fixture inventory is not empty'}
        }
        if($Scenario -eq 'normal'){
            Invoke-ReleaseContainerStep inspect_chest inspect minecraft:chest -4 6 minecraft:snow_block 896
            Invoke-ReleaseContainerStep take_full_batch take minecraft:chest -4 6 minecraft:snow_block 896 -ExpectedTransfer 896
            Invoke-ReleaseContainerStep inspect_after_take inspect minecraft:chest -4 6 minecraft:snow_block 0
            Invoke-ReleaseContainerStep store_barrel store minecraft:barrel -3 7 minecraft:snow_block 896 -ExpectedTransfer 896
            Invoke-ReleaseContainerStep inspect_barrel inspect minecraft:barrel -3 7 minecraft:snow_block 896
            Invoke-ReleaseContainerStep inspect_copper inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 74
            Invoke-ReleaseContainerStep take_copper take minecraft:waxed_copper_chest -7 6 minecraft:black_wool 74 -Limit 74 -ExpectedTransfer 74
            Invoke-ReleaseContainerStep inspect_empty_copper inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 0
            Invoke-ReleaseContainerStep store_copper store minecraft:waxed_copper_chest -7 6 minecraft:black_wool 74 -Limit 74 -ExpectedTransfer 74
            Invoke-ReleaseContainerStep inspect_restored_copper inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 74
        }elseif($Scenario -eq 'exact'){
            Invoke-ReleaseContainerStep exact_before inspect minecraft:chest -4 6 minecraft:snow_block 896
            Invoke-ReleaseContainerStep exact_take_two take minecraft:chest -4 6 minecraft:snow_block 2 -TransferCount 2 -ExpectedTransfer 2
            Invoke-ReleaseContainerStep exact_take_goal_met take minecraft:chest -4 6 minecraft:snow_block 2 -TransferCount 2 -ExpectedTransfer 2
            Invoke-ReleaseContainerStep exact_store_goal_met store minecraft:chest -4 6 minecraft:snow_block 892 -TransferCount 2 -ExpectedTransfer 2
            Invoke-ReleaseContainerStep exact_restore_two store minecraft:chest -4 6 minecraft:snow_block 896 -TransferCount 2 -ExpectedTransfer 2
            Invoke-ReleaseContainerStep exact_take_seven take minecraft:chest -4 6 minecraft:snow_block 7 -TransferCount 7 -ExpectedTransfer 7
            Invoke-ReleaseContainerStep exact_barrel_two store minecraft:barrel -3 7 minecraft:snow_block 2 -TransferCount 2 -ExpectedTransfer 2
            Invoke-ReleaseContainerStep exact_barrel_five store minecraft:barrel -3 7 minecraft:snow_block 7 -TransferCount 5 -ExpectedTransfer 5
            Invoke-ReleaseContainerStep exact_barrel_take take minecraft:barrel -3 7 minecraft:snow_block 7 -TransferCount 7 -ExpectedTransfer 7
            Invoke-ReleaseContainerStep exact_restore_seven store minecraft:chest -4 6 minecraft:snow_block 896 -TransferCount 7 -ExpectedTransfer 7
            Invoke-ReleaseContainerStep exact_restored inspect minecraft:chest -4 6 minecraft:snow_block 896
            Invoke-ReleaseContainerStep exact_barrel_empty inspect minecraft:barrel -3 7 minecraft:snow_block 0
        }else{
            Invoke-ReleaseContainerStep inspect_partial_source inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 111
            Invoke-ReleaseContainerStep partial_take take minecraft:waxed_copper_chest -7 6 minecraft:black_wool 74 -Limit 74 -ExpectedTransfer 47 -ExpectedPartial
            # This fresh inspection and new request deliberately replace any replay of the failed Action.
            Invoke-ReleaseContainerStep reobserve_partial_source inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 64
            Invoke-ReleaseContainerStep partial_resume take minecraft:waxed_copper_chest -7 6 minecraft:black_wool 111 -Limit 64 -ExpectedTransfer 64
            Invoke-ReleaseContainerStep store_partial_total store minecraft:waxed_copper_chest -7 6 minecraft:black_wool 111 -Limit 111 -ExpectedTransfer 111
            Invoke-ReleaseContainerStep inspect_partial_restored inspect minecraft:waxed_copper_chest -7 6 minecraft:black_wool 111
        }
    } catch {$failure=$_} finally {
        try{$release=Invoke-GateCleanup}catch{if($null -eq $failure){$failure=$_}}
    }
    $result=[ordered]@{status=$(if($null -eq $failure){'passed'}else{'failed'});scenario=$Scenario
        classification='deterministic_public_mcp_regression';actions=@($script:ReleaseTerminals.ToArray())
        inspections=@($script:ReleaseInspections.ToArray());input_release=$release
        failure=$(if($null -eq $failure){$null}else{$failure.Exception.Message})}
    [IO.File]::WriteAllText($resultPath,($result|ConvertTo-Json -Depth 80),$script:Utf8NoBom)
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'container-events.jsonl'),
        @($script:GateEvents|ForEach-Object{ConvertTo-CompactJson $_}),$script:Utf8NoBom)
    if($null -ne $failure){throw $failure}
    return $result
}
if(-not $LibraryOnly){
    if([string]::IsNullOrWhiteSpace($TokenPath)){throw 'TokenPath is required'}
    $script:Bearer=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    Invoke-McmcpReleaseContainerGate | ConvertTo-Json -Depth 80
}
