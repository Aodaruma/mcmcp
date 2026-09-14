[CmdletBinding()]
param([Parameter(Mandatory)][string]$ArtifactDirectory, [string]$TokenPath,
    [string]$Endpoint='http://127.0.0.1:8765/mcp', [switch]$LibraryOnly)
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionMaterialObservation.ps1')

function Invoke-McmcpFloorExtensionCapabilityGate {
    param([switch]$InventorySources)
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $resultPath=Join-Path $ArtifactDirectory 'floor-result.json'
    if(Test-Path -LiteralPath $resultPath){throw 'Refusing to overwrite a prior floor result'}
    $failure=$null; $release=$null; $cancelResult=$null; $confirmed=[Collections.Generic.List[object]]::new()
    try {
        Assert-FixedFiveToolSurface
        foreach($item in @('minecraft:snow_block','minecraft:black_wool')){
            $chest=Get-MaterialSurface 'minecraft:chest' -7 61 7 $null
            $node=[ordered]@{id='take';op='take_known_container_stack';target=(Get-ObjectProperty $chest 'position')
                expected_block='minecraft:chest';item=$item;stack_policy='default_components_only';minimum_inventory_count=1}
            [void](Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'floor_take' -Capabilities @('camera','inventory_transfer') -Node $node -Interactions 3) -WallTimeoutSeconds 90)
        }
        if($InventorySources) {
            $materials=@(Get-ObjectProperty (Get-FreshState) 'placement_materials')
            $snow=$materials | Where-Object item -CEQ 'minecraft:snow_block' | Select-Object -First 1
            $black=$materials | Where-Object item -CEQ 'minecraft:black_wool' | Select-Object -First 1
            if($null -eq $snow -or $null -eq $black){throw 'Owned placement material sources missing'}
        } else {
            $snow=Get-MaterialSurface 'minecraft:snow_block' -7 61 5 $null
            $black=Get-MaterialSurface 'minecraft:black_wool' -7 61 6 $null
        }
        $steps=@(
            @{support=@(-5,60,6);block='minecraft:smooth_stone';direction='east';target=@(-4,60,6);source=$snow;item='minecraft:snow_block'},
            @{support=@(-4,60,6);block='minecraft:snow_block';direction='east';target=@(-3,60,6);source=$black;item='minecraft:black_wool'},
            @{support=@(-3,60,6);block='minecraft:black_wool';direction='north';target=@(-3,60,5);source=$snow;item='minecraft:snow_block'},
            @{support=@(-3,60,5);block='minecraft:snow_block';direction='west';target=@(-4,60,5);source=$black;item='minecraft:black_wool'}
        )
        foreach($step in $steps){
            $support=Get-MaterialSurface $step.block $step.support[0] $step.support[1] $step.support[2] @('up')
            $before=Get-InventoryCount -State (Get-FreshState) -Item $step.item
            $node=[ordered]@{id='extend';op='extend_known_floor';support=(Get-ObjectProperty $support 'position')
                expected_support=(Get-ObjectProperty $support 'state');direction=$step.direction
                placement_state_ref=(Get-ObjectProperty $step.source 'placement_state_ref')}
            $terminal=Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'floor_extend' -Capabilities @('movement','camera','block_place') `
                -Node $node -Duration 20000 -Ticks 400 -Distance 2 -Camera 720 -Placements 1) -WallTimeoutSeconds 60
            $afterState=Get-FreshState
            $after=Get-InventoryCount -State $afterState -Item $step.item
            $position=Get-ObjectProperty (Get-ObjectProperty $afterState 'world') 'position'
            if($after -ne $before-1 -or [Math]::Abs($position.x-($step.target[0]+0.5)) -gt 0.15 `
                -or [Math]::Abs($position.z-($step.target[2]+0.5)) -gt 0.15 -or [Math]::Abs($position.y-61) -gt 0.02){
                throw 'Floor extension did not confirm inventory and grounded endpoint'
            }
            $observed=Get-MaterialSurface $step.item $step.target[0] $step.target[1] $step.target[2] @('up')
            $confirmed.Add([ordered]@{direction=$step.direction;item=$step.item;position=$position;before=$before;after=$after
                target=(Get-ObjectProperty $observed 'position');action_id=(Get-ObjectProperty $terminal 'action_id')})
        }
        $support=Get-MaterialSurface 'minecraft:black_wool' -4 60 5 @('up')
        $before=Get-InventoryCount -State (Get-FreshState) -Item 'minecraft:snow_block'
        $node=[ordered]@{id='cancel_edge';op='extend_known_floor';support=(Get-ObjectProperty $support 'position')
            expected_support=(Get-ObjectProperty $support 'state');direction='north'
            placement_state_ref=(Get-ObjectProperty $snow 'placement_state_ref')}
        $request=New-PrimitiveRequest -Name 'floor_cancel' -Capabilities @('movement','camera','block_place') `
            -Node $node -Duration 20000 -Ticks 400 -Distance 2 -Camera 720 -Placements 1
        $receipt=Invoke-GateTool -Tool 'agent_start_action' -Arguments $request
        $script:ActiveActionId=[string](Get-ObjectProperty $receipt 'action_id')
        if((Get-ObjectProperty $receipt 'state') -cne 'queued' -or
            $script:ActiveActionId -cnotmatch '^[0-9a-f-]{36}$'){throw 'Invalid cancel-test start receipt'}
        $moving=$false
        for($poll=0;$poll -lt 80;$poll++) {
            $snapshot=Invoke-GateTool -Tool 'agent_get_action' -Arguments @{action_id=$script:ActiveActionId;wait_timeout_ms=0}
            if((Get-ObjectProperty $snapshot 'state') -cin $script:TerminalStates){break}
            if([double](Get-ObjectProperty (Get-ObjectProperty $snapshot 'progress') 'distance_travelled') -gt 0.05){
                $moving=$true;break
            }
        }
        if(-not $moving){throw 'Cancel test did not observe active crouch movement'}
        [void](Invoke-GateTool -Tool 'agent_cancel_action' -Arguments @{action_id=$script:ActiveActionId})
        $cancelResult=Wait-McmcpActionTerminal -ActionId $script:ActiveActionId -WallTimeoutSeconds 60
        Add-ActionTerminalEvent -ActionId $script:ActiveActionId -Terminal $cancelResult
        $script:ActiveActionId=$null
        if((Get-ObjectProperty $cancelResult 'state') -cne 'cancelled' -or
            (Get-InventoryCount -State (Get-FreshState) -Item 'minecraft:snow_block') -ne $before){
            throw 'Early cancellation did not preserve material inventory'
        }
    } catch { $failure=$_ } finally { try{$release=Invoke-GateCleanup}catch{if($null-eq$failure){$failure=$_}} }
    $result=[ordered]@{status=$(if($null-eq$failure -and $confirmed.Count-eq4){'passed'}else{'failed'})
        confirmed=@($confirmed.ToArray());cancellation=$cancelResult;input_release=$release;failure=$(if($null-eq$failure){$null}else{$failure.Exception.Message})}
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'floor-events.jsonl'),@($script:GateEvents|ForEach-Object{ConvertTo-CompactJson $_}),$script:Utf8NoBom)
    [IO.File]::WriteAllText($resultPath,($result|ConvertTo-Json -Depth 30),$script:Utf8NoBom)
    if($null-ne$failure){throw $failure}; return $result
}
if(-not $LibraryOnly){
    if([string]::IsNullOrWhiteSpace($TokenPath)){throw 'TokenPath is required'}
    $script:Bearer=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    Invoke-McmcpFloorExtensionCapabilityGate | ConvertTo-Json -Depth 30
}
