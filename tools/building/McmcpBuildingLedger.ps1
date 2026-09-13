# Offline plan and atomic checkpoint. No Minecraft, network or credentials.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Get-BuildingHash([string]$Text) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}
function Read-BuildingBlueprint([string]$Path) {
    $file=Get-Item -LiteralPath $Path
    if($file.Length -gt 2097152){throw 'blueprint_too_large'}
    $text=[IO.File]::ReadAllText($file.FullName)
    $schema=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'blueprint.schema.json'))
    if(-not (Test-Json -Json $text -Schema $schema -ErrorAction Stop)){throw 'invalid_blueprint'}
    $blueprint=ConvertFrom-Json -InputObject $text -AsHashtable -Depth 50
    $width=$blueprint.rows[0].Length
    foreach($row in $blueprint.rows) {
        if($row.Length -ne $width){throw 'ragged_blueprint'}
        foreach($key in $row.ToCharArray()){if(-not $blueprint.palette.Contains([string]$key)){throw 'unknown_palette_key'}}
    }
    $cells=[Collections.Generic.List[object]]::new()
    $indices=@{}
    for($v=0;$v -lt $blueprint.rows.Count;$v++) {
        for($n=0;$n -lt $width;$n++) {
            $u=if($v%2 -eq 0){$n}else{$width-1-$n}
            $cell=Get-BuildingPosition $blueprint $u $v
            $cells.Add(@{position=$cell;material=$blueprint.palette[[string]$blueprint.rows[$v][$u]];u=$u;v=$v})
            $indices["$u,$v"]=$cells.Count-1
        }
    }
    $torches=[Collections.Generic.List[int]]::new()
    foreach($torch in $blueprint.torches) {
        $key="$($torch.u),$($torch.v)"
        if(-not $indices.ContainsKey($key) -or $torches.Contains($indices[$key])){throw 'invalid_torch_cell'}
        $torches.Add($indices[$key])
    }
    if($torches.Count -gt 0 -and $cells.Count -lt 2){throw 'torch_requires_adjacent_stand'}
    return @{blueprint=$blueprint;hash=(Get-BuildingHash $text);cells=$cells.ToArray();torches=$torches.ToArray()
        start=(Get-BuildingPosition $blueprint -1 0)}
}
function Get-BuildingPosition($Blueprint,[int]$U,[int]$V) {
    $dx=$U;$dz=$V
    switch($Blueprint.rotation) {90{$dx=-$V;$dz=$U};180{$dx=-$U;$dz=-$V};270{$dx=$V;$dz=-$U}}
    @{dimension=$Blueprint.anchor.dimension;x=$Blueprint.anchor.x+$dx;y=$Blueprint.anchor.y;z=$Blueprint.anchor.z+$dz}
}
function New-BuildingCheckpoint($Plan) {
    @{version=1;blueprint_sha256=$Plan.hash;session_id=$null;status='new';next=0;torch_next=0
        cells=@($Plan.cells | ForEach-Object {'pending'});torches=@($Plan.torches | ForEach-Object {'pending'})
        pending=$null;receipts=@{};material_refs=@{};consumed=@{};supplied=@{};initial_inventory=$null;last_inventory=@{}
        external_inventory_delta=@{};unknown_material_balance=$false;last_action_id=$null;diagnostic=$null}
}
function Save-BuildingCheckpoint([string]$Path,$Checkpoint) {
    $data=ConvertTo-Json -InputObject $Checkpoint -Depth 40 -Compress
    $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -Compress -InputObject @{sha256=(Get-BuildingHash $data);data=$data}))
    if($bytes.Length -gt 33554432){throw 'checkpoint_too_large'}
    $temporary=$Path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    $stream=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
    [IO.File]::Move($temporary,$Path,$true)
}
function Read-BuildingCheckpoint([string]$Path,$Plan) {
    if(-not (Test-Path -LiteralPath $Path)){return New-BuildingCheckpoint $Plan}
    if((Get-Item -LiteralPath $Path).Length -gt 33554432){throw 'checkpoint_too_large'}
    $envelope=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable -Depth 50
    if($envelope.sha256 -cne (Get-BuildingHash $envelope.data)){throw 'checkpoint_checksum_mismatch'}
    $checkpoint=$envelope.data | ConvertFrom-Json -AsHashtable -Depth 50
    if(-not $checkpoint.Contains('receipts')){$checkpoint.receipts=@{}}
    if(-not $checkpoint.Contains('material_refs')){$checkpoint.material_refs=@{}}
    if($checkpoint.material_refs.Count -gt 33){throw 'invalid_material_ref_cache'}
    foreach($key in $checkpoint.material_refs.Keys){
        if($key -cnotmatch '^[0-9a-f]{64}$' -or $checkpoint.material_refs[$key] -cnotmatch '^psr_[0-9a-f]{32}$'){throw 'invalid_material_ref_cache'}
    }
    if(-not $checkpoint.Contains('initial_inventory')) {
        $checkpoint.initial_inventory=$null;$checkpoint.last_inventory=@{};$checkpoint.external_inventory_delta=@{}
        $checkpoint.unknown_material_balance=$true
    }
    if($checkpoint.version -ne 1 -or $checkpoint.blueprint_sha256 -cne $Plan.hash -or
        $checkpoint.next -isnot [long] -and $checkpoint.next -isnot [int] -or
        $checkpoint.torch_next -isnot [long] -and $checkpoint.torch_next -isnot [int] -or
        $checkpoint.cells.Count -ne $Plan.cells.Count -or $checkpoint.torches.Count -ne $Plan.torches.Count -or
        $checkpoint.next -lt 0 -or $checkpoint.next -gt $Plan.cells.Count -or
        $checkpoint.torch_next -lt 0 -or $checkpoint.torch_next -gt $Plan.torches.Count){throw 'checkpoint_plan_mismatch'}
    foreach($pair in @(@{states=$checkpoint.cells;next=$checkpoint.next},@{states=$checkpoint.torches;next=$checkpoint.torch_next})) {
        for($i=0;$i -lt $pair.states.Count;$i++) {
            if(($i -lt $pair.next -and $pair.states[$i] -cnotin @('confirmed','observed')) -or
                ($i -ge $pair.next -and $pair.states[$i] -cne 'pending')){throw 'invalid_checkpoint_prefix'}
        }
    }
    $pending=$checkpoint.pending
    if($null -ne $pending) {
        if($pending.kind -cnotin @('floor','torch','navigate','camera','supply') -or
            $pending.nonce -isnot [string] -or $pending.nonce -cnotmatch '^build_[0-9a-f]{32}$' -or
            ($null -ne $pending.action_id -and ($pending.action_id -isnot [string] -or
                $pending.action_id -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')) -or
            ($pending.index -isnot [int] -and $pending.index -isnot [long])){throw 'invalid_pending_identity'}
        if($pending.kind -cin @('floor','torch')) {
            $limit=if($pending.kind -ceq 'floor'){$Plan.cells.Count}else{$Plan.torches.Count}
            $next=if($pending.kind -ceq 'floor'){$checkpoint.next}else{$checkpoint.torch_next}
            if($pending.index -lt 0 -or $pending.index -ge $limit -or $pending.index -ne $next){throw 'invalid_pending_index'}
            if($pending.kind -ceq 'floor'){$cell=$Plan.cells[$pending.index];$expected=$cell.position;$material=$cell.material}
            else {
                $floor=$Plan.cells[$Plan.torches[$pending.index]].position
                $expected=@{dimension=$floor.dimension;x=$floor.x;y=$floor.y+1;z=$floor.z}
                $material=@{item='minecraft:torch';state=@{block='minecraft:torch';properties=@{}}}
            }
            foreach($key in @('dimension','x','y','z')){if($pending.target[$key] -cne $expected[$key]){throw 'invalid_pending_target'}}
            if($pending.material.item -cne $material.item -or -not (Test-BuildingStateEqual $pending.material.state $material.state)){throw 'invalid_pending_material'}
        } elseif($pending.index -ne -1 -or $null -ne $pending.target){throw 'invalid_pending_nonplacement'}
        if($pending.kind -ceq 'supply' -and ($null -eq $pending.material -or
            $pending.material.item -cnotin (@($Plan.blueprint.palette.Values | ForEach-Object {$_.item})+@('minecraft:torch')))){throw 'invalid_pending_supply'}
    }
    if($checkpoint.unknown_material_balance -isnot [bool] -or
        (-not $checkpoint.unknown_material_balance -and
            (@($checkpoint.cells | Where-Object {$_ -ceq 'observed'}).Count+@($checkpoint.torches | Where-Object {$_ -ceq 'observed'}).Count) -gt 0)){throw 'invalid_observed_balance'}
    return $checkpoint
}
function Add-BuildingBalance($Balance,[string]$Item,[int]$Count) {
    if(-not $Balance.Contains($Item)){$Balance[$Item]=0}
    $Balance[$Item]+=$Count
}
function Test-BuildingStateEqual($Left,$Right) {
    if($null -eq $Left -or $null -eq $Right -or $Left.block -cne $Right.block){return $false}
    $a=@($Left.properties.Keys | Sort-Object);$b=@($Right.properties.Keys | Sort-Object)
    if(($a -join ',') -cne ($b -join ',')){return $false}
    foreach($key in $a){if($Left.properties[$key] -cne $Right.properties[$key]){return $false}}
    return $true
}
function Complete-BuildingPending($Checkpoint,$Terminal) {
    $pending=$Checkpoint.pending
    if($null -eq $pending){return} # Recovered terminal cannot be applied twice.
    if($Terminal.action_id -cne $pending.action_id -or $Terminal.state -cnotin @('succeeded','failed','cancelled')){
        throw 'pending_terminal_mismatch'
    }
    $Checkpoint.last_action_id=$Terminal.action_id
    $effects=@($Terminal.effects | Where-Object verification -CEQ confirmed)
    if($pending.kind -cin @('floor','torch')) {
        $target=$pending.target
        $subject="block:$($target.dimension):$($target.x),$($target.y),$($target.z)"
        $confirmed=@($effects | Where-Object { $_.kind -ceq 'block_place' -and $_.subject -ceq $subject -and
            (Test-BuildingStateEqual $_.observed_after $pending.material.state) })
        if($confirmed.Count -eq 1) {
            if($pending.kind -ceq 'floor'){$Checkpoint.cells[$pending.index]='confirmed';$Checkpoint.next=$pending.index+1}
            else{$Checkpoint.torches[$pending.index]='confirmed';$Checkpoint.torch_next=$pending.index+1}
            Add-BuildingBalance $Checkpoint.consumed $pending.material.item 1
            $Checkpoint.receipts["$($pending.kind):$($pending.index)"]=@{action_id=$Terminal.action_id
                verification='confirmed';effect_seq=$confirmed[0].seq;client_tick=$confirmed[0].client_tick
                world_revision=$confirmed[0].world_revision}
        } elseif($Terminal.state -ceq 'succeeded' -or @($Terminal.effects | Where-Object verification -CEQ unknown).Count -gt 0) {
            $pending.terminal=$Terminal
            $Checkpoint.unknown_material_balance=$true
            $Checkpoint.status='unknown';$Checkpoint.diagnostic='placement_requires_reconciliation'
            return
        }
    } elseif($pending.kind -ceq 'supply') {
        foreach($effect in $effects | Where-Object kind -CEQ container_take) {
            if($effect.observed_after.Contains('transferred')) {
                Add-BuildingBalance $Checkpoint.supplied $pending.material.item ([int]$effect.observed_after.transferred)
            }
        }
        if(@($Terminal.effects | Where-Object verification -CEQ unknown).Count -gt 0){$Checkpoint.unknown_material_balance=$true}
    }
    $Checkpoint.pending=$null
    $Checkpoint.status=if($Terminal.state -ceq 'succeeded'){'running'}else{'paused'}
    $Checkpoint.diagnostic=if($Terminal.state -ceq 'succeeded'){$null}else{'action_'+$Terminal.state}
}
