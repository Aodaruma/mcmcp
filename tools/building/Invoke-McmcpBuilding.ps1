#requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$BlueprintPath,[Parameter(Mandatory)][string]$CheckpointPath,
    [string]$TokenPath,[string]$Endpoint='http://127.0.0.1:8765/mcp',
    [ValidateRange(1,16384)][int]$MaxCells=64,[ValidateRange(1,2048)][int]$MaxActions=512,
    [ValidateRange(60,86400)][int]$MaxSeconds=900,[switch]$Refill,[switch]$AcceptWorldSessionChange,
    [switch]$StatusOnly,[switch]$LibraryOnly)
. (Join-Path $PSScriptRoot 'McmcpBuildingLedger.ps1')
. (Join-Path $PSScriptRoot '../eval/McmcpCapabilityGateSupport.ps1')
. (Join-Path $PSScriptRoot '../eval/McmcpConstructionNavigation.ps1')
. (Join-Path $PSScriptRoot '../mcp/McmcpClient.ps1')

# Reuse the existing public transport and catalog validators. No evaluation fixture or admin API.
function Invoke-GateTool {
    param([string]$Tool,[object]$Arguments)
    Wait-McpRequestSlot
    $reply=Invoke-McmcpClient -TokenPath $TokenPath -Endpoint $Endpoint -Tool $Tool -Arguments $Arguments
    if(-not $reply.ok){
        $code=Get-ObjectProperty (Get-ObjectProperty $reply 'error') 'code'
        $failure=[InvalidOperationException]::new('mcp_'+$(if($null -ne $code){$code}else{$reply.diagnostic_code}))
        $failure.Data['start_rejected']=$Tool -ceq 'agent_start_action' -and (
            $reply.diagnostic_code -ceq 'invalid_tool_arguments' -or
            ($reply.failure_kind -ceq 'tool' -and $code -cin @('INVALID_ARGUMENT','MCP_OPERATION_DISABLED','NO_WORLD',
                'TASK_BUSY','MULTIPLAYER_NOT_ALLOWED','TARGET_UNKNOWN','NO_KNOWN_PATH','SAFETY_PRECONDITION',
                'PROGRAM_TOO_COMPLEX','PROGRAM_BUDGET_UNPROVABLE','PREDICATE_UNAVAILABLE','CAPABILITY_DENIED')))
        $failure.Data['domain_code']=$code
        throw $failure
    }
    # The building ledger uses dictionaries consistently; the transport validates the original reply.
    return ($reply.result | ConvertTo-Json -Depth 100 -Compress | ConvertFrom-Json -AsHashtable -Depth 100)
}
function Assert-BuildingSession($State) {
    if($State.world.dimension -cne $script:Plan.blueprint.anchor.dimension -or
        $State.world.session_id -cne $script:Checkpoint.session_id){throw 'world_session_changed'}
}
function Get-BuildingState {
    $state=Get-FreshState
    Assert-BuildingSession $state
    return $state
}
function Update-BuildingInventory($State) {
    $items=@($script:Plan.blueprint.palette.Values | ForEach-Object {$_.item})+@('minecraft:torch')
    foreach($item in $items | Select-Object -Unique) {
        $actual=Get-InventoryCount $State $item
        $script:Checkpoint.last_inventory[$item]=$actual
        if($null -eq $script:Checkpoint.initial_inventory){$script:Checkpoint.unknown_material_balance=$true;continue}
        $expected=[long]$script:Checkpoint.initial_inventory[$item]
        if($script:Checkpoint.supplied.Contains($item)){$expected+=$script:Checkpoint.supplied[$item]}
        if($script:Checkpoint.consumed.Contains($item)){$expected-=$script:Checkpoint.consumed[$item]}
        $script:Checkpoint.external_inventory_delta[$item]=$actual-$expected
        if($actual -ne $expected){$script:Checkpoint.unknown_material_balance=$true}
    }
}
function Get-BuildingSurface($Position,$Expected,[string[]]$Faces=@()) {
    $bounds=@{dimension=$Position.dimension;min_x=$Position.x;max_x=$Position.x;min_y=$Position.y;max_y=$Position.y;min_z=$Position.z;max_z=$Position.z}
    $faceFilter=if($Faces.Count -eq 0){$null}else{$Faces}
    $visible=Wait-ForCurrentVisibleSurfaceRecords -InitialState (Get-BuildingState) -Block $Expected.block -Bounds $bounds -Faces $faceFilter
    foreach($surface in $visible.records){if(Test-BuildingStateEqual $surface.state $Expected){return $surface}}
    throw 'observed_state_mismatch'
}
function Get-BuildingSource($Material) {
    $state=Get-BuildingState
    foreach($owned in @(Get-ObjectProperty $state 'placement_materials')) {
        if($null -ne $owned -and $owned.item -ceq $Material.item -and
            (Test-BuildingStateEqual $owned.state $Material.state) -and $null -ne $owned.placement_state_ref) {
            $script:Checkpoint.material_refs[(Get-BuildingMaterialKey $Material)]=$owned.placement_state_ref
            Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
            return $owned
        }
    }
    $materials=@($script:Plan.blueprint.palette.Values)+@(@{item='minecraft:torch';state=@{block='minecraft:torch';properties=@{}}})
    $records=@(Get-RecordsFromState -State $state -Kinds @('visible_surface') -Filter @{block_ids=@($materials | ForEach-Object {$_.state.block} | Select-Object -Unique)})
    $changed=$false
    foreach($record in $records) {
        if($record.world_revision -ne $state.world.world_revision -or $null -eq $record.placement_state_ref){continue}
        foreach($sample in $materials) {
            if($record.placement_item -ceq $sample.item -and (Test-BuildingStateEqual $record.state $sample.state)) {
                $key=Get-BuildingMaterialKey $sample
                if(-not $script:Checkpoint.material_refs.Contains($key) -or $script:Checkpoint.material_refs[$key] -cne $record.placement_state_ref){
                    $script:Checkpoint.material_refs[$key]=$record.placement_state_ref;$changed=$true
                }
            }
        }
    }
    if($changed){Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint}
    # Placement-state identities are immutable and session-scoped, unlike expiring coordinate
    # witnesses. Retain only these identities; every target/support still needs current evidence.
    $key=Get-BuildingMaterialKey $Material
    if($script:Checkpoint.material_refs.Contains($key)){return @{placement_state_ref=$script:Checkpoint.material_refs[$key]}}
    if($null -ne $Material.source) {
        $source=Get-BuildingSurface $Material.source $Material.state
        if($source.placement_item -cne $Material.item -or $null -eq $source.placement_state_ref){throw 'fresh_material_source_required'}
        $script:Checkpoint.material_refs[$key]=$source.placement_state_ref
        Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
        return $source
    }
    throw 'fresh_material_source_required'
}
function Get-BuildingMaterialKey($Material) {
    $properties=@($Material.state.properties.Keys | Sort-Object | ForEach-Object {$_+'='+$Material.state.properties[$_]}) -join ','
    Get-BuildingHash ($Material.item+'|'+$Material.state.block+'|'+$properties)
}
function Start-BuildingAction($Request,[string]$Kind,[int]$Index=-1,$Target=$null,$Material=$null) {
    $script:LastTerminal=$null
    if($null -ne $script:Checkpoint.pending){throw 'unresolved_pending_action'}
    if($script:Actions -ge $MaxActions -or $script:Clock.Elapsed.TotalSeconds -gt $MaxSeconds-60){throw 'run_budget_reached'}
    [void](Get-BuildingState)
    $nonce='build_'+[guid]::NewGuid().ToString('N')
    $Request.program.name=$nonce
    $script:Checkpoint.pending=@{kind=$Kind;index=$Index;target=$Target;material=$Material;nonce=$nonce;action_id=$null;terminal=$null}
    try {Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint} catch {
        # HTTP has not begun. Do not let the outer failure save persist an unsent intent.
        $script:Checkpoint.pending=$null
        $_.Exception.Data['start_not_sent']=$true
        throw
    }
    try {$receipt=Invoke-GateTool 'agent_start_action' $Request} catch {
        # A validated pre-admission rejection is distinct from a lost/invalid response.
        # SERVER_BUSY, INTERNAL_ERROR and transport failures remain uncertain intents.
        if($_.Exception.Data['start_rejected'] -eq $true) {
            $script:Checkpoint.pending=$null
            if($_.Exception.Data['domain_code'] -ceq 'TARGET_UNKNOWN'){$script:Checkpoint.material_refs=@{}}
            Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
        }
        throw
    }
    $script:Checkpoint.pending.action_id=$receipt.action_id
    Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    $script:Actions++
    $terminal=Wait-McmcpActionTerminal -ActionId $receipt.action_id -WallTimeoutSeconds 90
    $script:LastTerminal=$terminal
    Complete-BuildingPending $script:Checkpoint $terminal
    Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    if($null -ne $script:Checkpoint.pending -or $terminal.state -cne 'succeeded'){throw 'action_requires_reconciliation'}
}
function Resolve-BuildingPending {
    $pending=$script:Checkpoint.pending
    if($null -eq $pending){return}
    if($null -eq $pending.action_id) {
        $state=Invoke-GateTool 'agent_get_state' @{}
        Assert-BuildingSession $state
        if($null -eq $state.action){throw 'unknown_start_receipt'}
        $candidate=Invoke-GateTool 'agent_get_action' @{action_id=$state.action.action_id;wait_timeout_ms=0}
        $source=$candidate.source.canonical_json | ConvertFrom-Json -AsHashtable -Depth 100
        if($source.program.name -cne $pending.nonce){throw 'unknown_start_receipt'}
        $pending.action_id=$candidate.action_id
        Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    }
    $snapshot=Invoke-GateTool 'agent_get_action' @{action_id=$pending.action_id;wait_timeout_ms=0}
    if($snapshot.state -cnotin $script:TerminalStates) {
        [void](Invoke-GateTool 'agent_cancel_action' @{action_id=$pending.action_id})
        $snapshot=Wait-McmcpActionTerminal -ActionId $pending.action_id -WallTimeoutSeconds 90
    }
    Complete-BuildingPending $script:Checkpoint $snapshot
    Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    if($null -ne $script:Checkpoint.pending) {
        if($pending.kind -cnotin @('floor','torch')){throw 'unknown_nonplacement_action'}
        # A visible desired block can reconcile the cell, but cannot invent an inventory ACK.
        [void](Get-BuildingSurface $pending.target $pending.material.state)
        if($pending.kind -ceq 'floor'){$script:Checkpoint.cells[$pending.index]='observed';$script:Checkpoint.next=$pending.index+1}
        elseif($pending.kind -ceq 'torch'){$script:Checkpoint.torches[$pending.index]='observed';$script:Checkpoint.torch_next=$pending.index+1}
        else{throw 'unknown_nonplacement_action'}
        $script:Checkpoint.receipts["$($pending.kind):$($pending.index)"]=@{action_id=$pending.action_id;verification='observed'}
        $script:Checkpoint.pending=$null
        Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    }
}
function Get-BuildingStand([int]$Index) {
    if($Index -eq -1){return @{position=$script:Plan.start;state=$script:Plan.blueprint.start_support_state}}
    $cell=$script:Plan.cells[$Index]
    return @{position=$cell.position;state=$cell.material.state}
}
function Test-BuildingCentered($Position,$Stand) {
    [Math]::Abs($Position.x-$Stand.x-0.5) -le 0.15 -and [Math]::Abs($Position.z-$Stand.z-0.5) -le 0.15 -and
        [Math]::Abs($Position.y-$Stand.y-1) -le 0.02
}
function Move-BuildingTo {
    param([int]$Index,[switch]$ForFloorPlacement)
    $goal=Get-BuildingStand $Index
    while($true) {
        $state=Get-BuildingState;$pos=$state.world.position
        if(Test-BuildingCentered $pos $goal.position){
            # The floor caller acquires this support immediately before placement.
            if(-not $ForFloorPlacement){[void](Get-BuildingSurface $goal.position $goal.state @('up'))}
            return
        }
        $current=-2
        for($i=-1;$i -lt $script:Checkpoint.next;$i++) {
            $stand=Get-BuildingStand $i
            if([Math]::Floor($pos.x) -eq $stand.position.x -and [Math]::Floor($pos.z) -eq $stand.position.z -and
                [Math]::Abs($pos.y-$stand.position.y-1) -le 0.02){$current=$i;break}
        }
        if($current -eq -2){throw 'return_to_confirmed_floor_required'}
        $next=if($current -eq $Index){$Index}elseif($current -lt $Index){$current+1}else{$current-1}
        $stand=Get-BuildingStand $next
        [void](Get-BuildingSurface $stand.position $stand.state @('up'))
        $records=@(Get-NearbyTraversabilityRecords (Get-BuildingState))
        $record=@($records | Where-Object {$_.kind -ceq 'traversability' -and $_.target_support -ceq 'confirmed' -and
            $_.transition_clearance -ceq 'confirmed' -and $_.fluid -ceq 'none' -and $_.status -cin @('CONFIRMED','PROBE_ALLOWED') -and
            $_.navigation_target.x -eq $stand.position.x -and $_.navigation_target.y -eq $stand.position.y+1 -and
            $_.navigation_target.z -eq $stand.position.z} | Select-Object -First 1)
        if($record.Count -ne 1){throw 'fresh_return_route_required'}
        $request=New-NavigationActionRequest -NavigationRecord $record[0] -State (Get-BuildingState) -Tolerance 0.1
        try {Start-BuildingAction $request 'navigate'} catch {
            $terminal=$script:LastTerminal
            if($null -eq $terminal -or $terminal.state -cne 'failed' -or
                $terminal.failure.code -cne 'BUDGET_EXCEEDED' -or $terminal.effects.Count -ne 0 -or
                @($terminal.failure.evidence).Count -ne 1 -or
                $terminal.failure.evidence[0] -cne 'replanned_route_remaining_occurrence' -or
                @($terminal.trace | Where-Object {$_.event -ceq 'REPLANNING' -and $_.detail -ceq 'unverified_actual_movement'}).Count -eq 0 -or
                $null -ne $script:Checkpoint.pending){throw}
            $after=Get-BuildingState
            # Navigation may exhaust its tighter 0.1 arrival tolerance after braking. The floor
            # primitive accepts 0.15 per axis; adopt only that fresh, grounded, unchanged-health pose.
            if($after.world.health -lt $state.world.health -or
                -not (Test-BuildingCentered $after.world.position $stand.position)){throw}
            [void](Get-BuildingSurface $stand.position $stand.state @('up'))
        }
    }
}
function Ensure-BuildingMaterial($Material,[int]$ReturnIndex) {
    if((Get-InventoryCount (Get-BuildingState) $Material.item) -gt 0){return}
    $script:Checkpoint.status='needs_material';$script:Checkpoint.diagnostic=$Material.item
    Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
    if(-not $Refill -or $null -eq $script:Plan.blueprint.supply){throw 'needs_material'}
    Move-BuildingTo -1
    $supply=$script:Plan.blueprint.supply
    $state=Get-BuildingState
    $p=$supply.position
    $bounds=@{dimension=$p.dimension;min_x=$p.x;max_x=$p.x;min_y=$p.y;max_y=$p.y;min_z=$p.z;max_z=$p.z}
    $visible=Wait-ForCurrentVisibleSurfaceRecords -InitialState $state -Block $supply.block -Bounds $bounds -Faces $null
    $node=@{id='refill';op='take_known_container_stack';target=$visible.records[0].position;expected_block=$supply.block
        item=$Material.item;stack_policy='default_components_only';minimum_inventory_count=1}
    Start-BuildingAction (New-PrimitiveRequest -Name 'refill' -Capabilities @('camera','inventory_transfer') -Node $node -Interactions 3) 'supply' -Material $Material
    if((Get-InventoryCount (Get-BuildingState) $Material.item) -lt 1){throw 'needs_material'}
    Move-BuildingTo $ReturnIndex
}
function Invoke-BuildingRun {
    $script:Plan=Read-BuildingBlueprint $BlueprintPath
    $CheckpointPath=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($CheckpointPath)
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($CheckpointPath))
    $lock=[IO.File]::Open($CheckpointPath+'.lock',[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $script:Checkpoint=$null
    try {
        $script:Checkpoint=Read-BuildingCheckpoint $CheckpointPath $script:Plan
        if($StatusOnly){return $script:Checkpoint}
        $script:Clock=[Diagnostics.Stopwatch]::StartNew();$script:Actions=0
        $initial=Invoke-GateTool 'agent_get_state' @{}
        if($null -eq $initial.world){throw 'world_required'}
        if($null -eq $script:Checkpoint.session_id){
            $script:Checkpoint.session_id=$initial.world.session_id
            $script:Checkpoint.initial_inventory=@{}
            foreach($item in (@($script:Plan.blueprint.palette.Values | ForEach-Object {$_.item})+@('minecraft:torch') | Select-Object -Unique)) {
                $script:Checkpoint.initial_inventory[$item]=Get-InventoryCount $initial $item
            }
        }
        elseif($script:Checkpoint.session_id -cne $initial.world.session_id) {
            if(-not $AcceptWorldSessionChange -or $null -ne $script:Checkpoint.pending){throw 'world_session_change_requires_explicit_binding'}
            # The public session ID detects changes; it is not a persistent save identifier.
            $script:Checkpoint.session_id=$initial.world.session_id
            $script:Checkpoint.material_refs=@{}
        }
        Assert-BuildingSession $initial
        Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
        Resolve-BuildingPending
        $started=$script:Checkpoint.next+$script:Checkpoint.torch_next
        while(($script:Checkpoint.next+$script:Checkpoint.torch_next-$started) -lt $MaxCells) {
            if($script:Checkpoint.next -lt $script:Plan.cells.Count) {
                $index=$script:Checkpoint.next;$cell=$script:Plan.cells[$index]
                Ensure-BuildingMaterial $cell.material ($index-1)
                Move-BuildingTo ($index-1) -ForFloorPlacement
                $source=Get-BuildingSource $cell.material
                $stand=Get-BuildingStand ($index-1)
                $support=Get-BuildingSurface $stand.position $stand.state @('up')
                $dx=$cell.position.x-$stand.position.x;$dz=$cell.position.z-$stand.position.z
                $direction=if($dx -eq 1){'east'}elseif($dx -eq -1){'west'}elseif($dz -eq 1){'south'}else{'north'}
                $node=@{id='floor';op='extend_known_floor';support=$support.position;expected_support=$support.state
                    direction=$direction;placement_state_ref=$source.placement_state_ref}
                $request=New-PrimitiveRequest -Name 'floor' -Capabilities @('movement','camera','block_place') -Node $node -Duration 20000 -Ticks 400 -Distance 2 -Camera 720 -Placements 1
                Start-BuildingAction $request 'floor' $index $cell.position $cell.material
                # Complete-BuildingPending requires an exact target/state CONFIRMED effect;
                # Start-BuildingAction persists it before returning. Next use gets fresh support.
                $receipt=$script:Checkpoint.receipts["floor:$index"]
                if($null -ne $script:Checkpoint.pending -or $script:Checkpoint.cells[$index] -cne 'confirmed' -or
                    $script:Checkpoint.next -ne $index+1 -or $null -eq $script:LastTerminal -or
                    $script:LastTerminal.state -cne 'succeeded' -or $null -eq $receipt -or
                    $receipt.verification -cne 'confirmed' -or $receipt.action_id -cne $script:LastTerminal.action_id){
                    throw 'floor_confirmation_required'
                }
            } elseif($script:Checkpoint.torch_next -lt $script:Plan.torches.Count) {
                $index=$script:Checkpoint.torch_next;$floorIndex=$script:Plan.torches[$index]
                $standIndex=if($floorIndex -gt 0){$floorIndex-1}else{1}
                $material=@{item='minecraft:torch';state=@{block='minecraft:torch';properties=@{}};source=$script:Plan.blueprint.torch_source}
                Ensure-BuildingMaterial $material $standIndex
                Move-BuildingTo $standIndex
                $source=Get-BuildingSource $material
                $floor=Get-BuildingStand $floorIndex
                $support=Get-BuildingSurface $floor.position $floor.state @('up')
                $face=@{id='face';op='face_known_block_face';target=$support.position;expected_block=$floor.state.block;face='up'}
                Start-BuildingAction (New-PrimitiveRequest -Name 'face' -Capabilities @('camera') -Node $face) 'camera'
                $support=Get-BuildingSurface $floor.position $floor.state @('up')
                $target=@{dimension=$floor.position.dimension;x=$floor.position.x;y=$floor.position.y+1;z=$floor.position.z}
                $entry=@{id='torch';offset=@{x=0;y=0;z=0};placement_state_ref=$source.placement_state_ref
                    support=@{position=$support.position;face='up';expected_state=$support.state;dependency_entry_id=$null}}
                $node=@{id='light';op='apply_known_block_plan';anchor=$target;transform=@{rotation=0;mirror='none'};entries=@($entry)}
                $request=New-PrimitiveRequest -Name 'torch' -Capabilities @('camera','block_place') -Node $node -Duration 15000 -Ticks 300 -Camera 80 -Placements 1
                Start-BuildingAction $request 'torch' $index $target $material
                [void](Get-BuildingSurface $target $material.state)
            } else{break}
        }
        Update-BuildingInventory (Get-BuildingState)
        $script:Checkpoint.status=if($script:Checkpoint.next -eq $script:Plan.cells.Count -and $script:Checkpoint.torch_next -eq $script:Plan.torches.Count){
            if($script:Checkpoint.unknown_material_balance){'built_with_unknown_balance'}else{'complete'}
        }else{'paused'}
        $script:Checkpoint.diagnostic=if($script:Checkpoint.unknown_material_balance){'material_balance_requires_reconciliation'}else{$null}
        [void](Get-BuildingState)
        Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
        return $script:Checkpoint
    } catch {
        if($null -ne $script:Checkpoint) {
            # Best-effort terminal recovery for the known Action only. Uncertain starts stay recorded.
            if($null -ne $script:Checkpoint.pending -and $null -ne $script:Checkpoint.pending.action_id) {
                try { Resolve-BuildingPending } catch { }
            }
            if($script:Checkpoint.status -cnotin @('needs_material','unknown')){$script:Checkpoint.status='paused'}
            if($script:Checkpoint.status -cnotin @('needs_material','unknown') -or $null -eq $script:Checkpoint.diagnostic){
                $script:Checkpoint.diagnostic=if($_.Exception.Data['start_not_sent'] -eq $true){
                    'intent_save_failed_before_dispatch'
                }else{$_.Exception.Message}
            }
            try {Update-BuildingInventory (Get-BuildingState)} catch { }
            Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
            return $script:Checkpoint
        }
        throw
    } finally {$lock.Dispose()}
}
if(-not $LibraryOnly) {
    $result=Invoke-BuildingRun
    [ordered]@{status=$result.status;floor_completed=$result.next;floor_total=$result.cells.Count
        floor_server_confirmed=@($result.cells | Where-Object {$_ -ceq 'confirmed'}).Count
        floor_observed=@($result.cells | Where-Object {$_ -ceq 'observed'}).Count
        torches_completed=$result.torch_next;torches_total=$result.torches.Count
        torches_server_confirmed=@($result.torches | Where-Object {$_ -ceq 'confirmed'}).Count
        torches_observed=@($result.torches | Where-Object {$_ -ceq 'observed'}).Count;pending=$result.pending
        consumed=$result.consumed;supplied=$result.supplied;unknown_material_balance=$result.unknown_material_balance
        inventory=$result.last_inventory;external_inventory_delta=$result.external_inventory_delta
        diagnostic=$result.diagnostic} | ConvertTo-Json -Depth 30
}
