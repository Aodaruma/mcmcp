#requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-recovery-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
try {
    . (Join-Path $PSScriptRoot 'Invoke-McmcpBuilding.ps1') -BlueprintPath (Join-Path $PSScriptRoot 'example-small-floor.json') -CheckpointPath (Join-Path $temp 'checkpoint.json') -LibraryOnly
    $script:Plan=Read-BuildingBlueprint $BlueprintPath
    $script:Checkpoint=New-BuildingCheckpoint $script:Plan
    $script:Checkpoint.session_id='same-session'
    $script:Clock=[Diagnostics.Stopwatch]::StartNew();$script:Actions=0;$script:Starts=0;$script:RejectStart=$false
    function Get-BuildingState {return @{world=@{session_id='same-session';dimension='minecraft:overworld'}}}
    function Invoke-GateTool {
        param($Tool,$Arguments)
        if($Tool -ceq 'agent_start_action') {
            $disk=Read-BuildingCheckpoint $CheckpointPath $script:Plan
            if($null -eq $disk.pending -or $disk.pending.nonce -cne $Arguments.program.name){throw 'intent_not_durable'}
            $script:Starts++
            if($script:RejectStart){$rejection=[InvalidOperationException]::new('rejected');$rejection.Data['start_rejected']=$true;$rejection.Data['domain_code']='TARGET_UNKNOWN';throw $rejection}
            throw 'lost_start_response'
        }
        if($Tool -ceq 'agent_get_state'){return @{world=@{session_id='same-session';dimension='minecraft:overworld'};action=@{action_id='finished-action'}}}
        if($Tool -ceq 'agent_get_action') {
            $cell=$script:Plan.cells[0]
            return @{action_id='finished-action';state='succeeded';source=@{canonical_json=(@{program=@{name=$script:Nonce}} | ConvertTo-Json -Compress)}
                effects=@(@{kind='block_place';subject='block:minecraft:overworld:-4,60,6';verification='confirmed';observed_after=$cell.material.state;seq=1;client_tick=10;world_revision=1})}
        }
        throw 'unexpected_tool'
    }
    $cell=$script:Plan.cells[0]
    $request=@{program=@{name='placeholder'}}
    $caught=$false
    try{Start-BuildingAction $request 'floor' 0 $cell.position $cell.material}catch{$caught=$_.Exception.Message -ceq 'lost_start_response'}
    if(-not $caught -or $script:Starts -ne 1){throw 'lost_start_not_simulated'}
    # Reload exactly as a new process does; the terminal latest summary still identifies the nonce.
    $script:Checkpoint=Read-BuildingCheckpoint $CheckpointPath $script:Plan
    $invalid=Read-BuildingCheckpoint $CheckpointPath $script:Plan
    $invalid.pending.index=999
    Save-BuildingCheckpoint (Join-Path $temp 'invalid.json') $invalid
    $caught=$false
    try{Read-BuildingCheckpoint (Join-Path $temp 'invalid.json') $script:Plan | Out-Null}catch{$caught=$_.Exception.Message -ceq 'invalid_pending_index'}
    if(-not $caught){throw 'invalid_pending_was_loaded'}
    $script:Nonce=$script:Checkpoint.pending.nonce
    Resolve-BuildingPending
    Resolve-BuildingPending
    if($script:Checkpoint.next -ne 1 -or $script:Checkpoint.consumed['minecraft:snow_block'] -ne 1 -or $script:Starts -ne 1){throw 'receipt_recovery_replayed_or_double_counted'}
    $script:Checkpoint.pending=@{kind='floor';index=1;nonce='different-action';action_id=$null;terminal=$null}
    $caught=$false
    try{Resolve-BuildingPending}catch{$caught=$_.Exception.Message -ceq 'unknown_start_receipt'}
    if(-not $caught -or $script:Starts -ne 1 -or $null -ne $script:Checkpoint.pending.action_id){throw 'unrelated_latest_action_adopted'}
    $caught=$false
    try{Assert-BuildingSession @{world=@{session_id='changed';dimension='minecraft:overworld'}}}catch{$caught=$true}
    if(-not $caught){throw 'world_change_accepted'}
    $script:Checkpoint=New-BuildingCheckpoint $script:Plan;$script:RejectStart=$true
    try{Start-BuildingAction $request 'floor' 0 $cell.position $cell.material}catch{ }
    $disk=Read-BuildingCheckpoint $CheckpointPath $script:Plan
    if($null -ne $disk.pending){throw 'definite_rejection_left_unknown_intent'}
    $script:Checkpoint.initial_inventory=@{'minecraft:snow_block'=0;'minecraft:black_wool'=0;'minecraft:torch'=0}
    $script:Checkpoint.supplied=@{'minecraft:snow_block'=65};$script:Checkpoint.consumed=@{'minecraft:snow_block'=2}
    Update-BuildingInventory @{inventory=@(@{item='minecraft:snow_block';count=63})}
    if($script:Checkpoint.unknown_material_balance){throw 'balanced_materials_rejected'}
    Update-BuildingInventory @{inventory=@(@{item='minecraft:snow_block';count=63},@{item='minecraft:torch';count=1})}
    if(-not $script:Checkpoint.unknown_material_balance -or $script:Checkpoint.external_inventory_delta['minecraft:torch'] -ne 1){throw 'untracked_pickup_hidden'}
    if(-not (Test-BuildingCentered @{x=-3.39;y=61;z=6.5} $cell.position) -or
        (Test-BuildingCentered @{x=-3.3;y=61;z=6.5} $cell.position) -or
        (Test-BuildingCentered @{x=-3.5;y=60.9;z=6.5} $cell.position)){throw 'arrival_support_bound_changed'}
    function Get-BuildingState {return @{placement_materials=@(@{item='minecraft:snow_block';count=64;state=@{block='minecraft:snow_block';properties=@{}};placement_state_ref='psr_0123456789abcdef0123456789abcdef'})}}
    function Get-RecordsFromState {throw 'owned_material_must_not_require_world_sample'}
    $owned=Get-BuildingSource @{item='minecraft:snow_block';state=@{block='minecraft:snow_block';properties=@{}};source=$null}
    if($owned.placement_state_ref -cne 'psr_0123456789abcdef0123456789abcdef'){throw 'owned_source_not_adopted'}
    'MCMCP building recovery tests passed (durable intent, lost receipt, exact nonce, no replay, world change, owned material).'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -cmatch '^mcmcp-recovery-[a-f0-9]{32}$'){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
