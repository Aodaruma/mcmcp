[CmdletBinding()]
param([Parameter(Mandatory)][string]$ArtifactDirectory,[string]$TokenPath,
    [string]$Endpoint='http://127.0.0.1:8765/mcp',[switch]$LibraryOnly,[switch]$ExpectOldFailure)
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionMaterialObservation.ps1')

function Invoke-McmcpConstructionStagingGate {
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $path=Join-Path $ArtifactDirectory 'staging-result.json'
    if(Test-Path -LiteralPath $path){throw 'Existing staging result'}
    $results=[Collections.Generic.List[object]]::new()
    $failure=$null;$release=$null
    try {
        Assert-FixedFiveToolSurface
        # Vanilla chest QUICK_MOVE fills hotbar 8..0 before the main inventory. Nine full
        # snow stacks force the subsequently acquired torch into the main inventory.
        foreach($goal in 1..9) {
            $chest=Get-MaterialSurface 'minecraft:chest' -700 80 62 $null
            $node=@{id='take';op='take_known_container_stack';target=$chest.position
                expected_block='minecraft:chest';item='minecraft:snow_block'
                stack_policy='default_components_only';minimum_inventory_count=($goal*64)}
            [void](Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'fill_hotbar' `
                -Capabilities @('camera','inventory_transfer') -Node $node -Interactions 3) -WallTimeoutSeconds 90)
        }
        $chest=Get-MaterialSurface 'minecraft:chest' -700 80 62 $null
        $node=@{id='take';op='take_known_container_stack';target=$chest.position;expected_block='minecraft:chest'
            item='minecraft:torch';stack_policy='default_components_only';minimum_inventory_count=16}
        [void](Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'take_torch' `
            -Capabilities @('camera','inventory_transfer') -Node $node -Interactions 3) -WallTimeoutSeconds 90)
        $placements=@(@{item='minecraft:torch';x=-700;z=64;source=@(-703,80,64)},
            @{item='minecraft:torch';x=-701;z=64;source=@(-703,80,64)},
            @{item='minecraft:snow_block';x=-697;z=62;source=@(-699,79,64)})
        foreach($p in $placements) {
            $source=Get-MaterialSurface $p.item $p.source[0] $p.source[1] $p.source[2] $null
            $support=Get-MaterialSurface 'minecraft:snow_block' $p.x 79 $p.z @('up')
            $face=@{id='face';op='face_known_block_face';target=$support.position;expected_block='minecraft:snow_block';face='up'}
            [void](Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'face' `
                -Capabilities @('camera') -Node $face) -WallTimeoutSeconds 60)
            $support=Get-MaterialSurface 'minecraft:snow_block' $p.x 79 $p.z @('up')
            $target=@{dimension='minecraft:overworld';x=$p.x;y=80;z=$p.z}
            $entry=@{id='material';offset=@{x=0;y=0;z=0};placement_state_ref=$source.placement_state_ref
                support=@{position=$support.position;face='up';expected_state=$support.state;dependency_entry_id=$null}}
            $node=@{id='light';op='apply_known_block_plan';anchor=$target;transform=@{rotation=0;mirror='none'};entries=@($entry)}
            $before=Get-InventoryCount -State (Get-FreshState) -Item $p.item
            $terminal=Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'staging_place' `
                -Capabilities @('camera','block_place') -Node $node -Duration 15000 -Ticks 300 -Camera 80 -Placements 1) -WallTimeoutSeconds 60
            $after=Get-InventoryCount -State (Get-FreshState) -Item $p.item
            $surface=Get-MaterialSurface $p.item $p.x 80 $p.z $null
            if($after -ne $before-1 -or $surface.state.block -cne $p.item){throw 'Placement or count mismatch'}
            $results.Add(@{item=$p.item;target=$target;before=$before;after=$after;action_id=$terminal.action_id})
        }
    } catch {$failure=$_} finally {
        try {$release=Invoke-GateCleanup} catch {if($null -eq $failure){$failure=$_}}
    }
    $result=@{status=$(if($null -eq $failure -and $results.Count -eq 3){'passed'}else{'failed'})
        placements=@($results.ToArray());input_release=$release;expected_old_failure=[bool]$ExpectOldFailure
        failure=$(if($null -eq $failure){$null}else{$failure.Exception.Message})}
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'staging-events.jsonl'),
        @($script:GateEvents | ForEach-Object {ConvertTo-CompactJson $_}),$script:Utf8NoBom)
    [IO.File]::WriteAllText($path,($result | ConvertTo-Json -Depth 30),$script:Utf8NoBom)
    return $result
}
if(-not $LibraryOnly){
    $script:Bearer=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    Invoke-McmcpConstructionStagingGate | ConvertTo-Json -Depth 30
}
