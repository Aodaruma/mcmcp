#requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'McmcpBuildingLedger.ps1')
function Assert-BuildingTest([bool]$Value,[string]$Message){if(-not $Value){throw $Message}}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-building-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
try {
    $example=Join-Path $PSScriptRoot 'example-small-floor.json'
    $plan=Read-BuildingBlueprint $example
    Assert-BuildingTest ($plan.cells.Count -eq 4 -and $plan.cells[2].position.x -eq -3 -and $plan.cells[3].position.z -eq 7) 'serpentine path mismatch'
    $blueprint=Get-Content -LiteralPath $example -Raw | ConvertFrom-Json -AsHashtable
    $blueprint.torches=@(for($u=0;$u -le 120;$u+=12){for($v=0;$v -le 120;$v+=12){@{u=$u;v=$v}}})
    foreach($angle in @(0,90,180,270)) {
        $blueprint.rotation=$angle
        $blueprint.rows=@('A'*128)*128
        $path=Join-Path $temp 'large.json'
        $blueprint | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path
        $large=Read-BuildingBlueprint $path
        Assert-BuildingTest ($large.cells.Count -eq 16384) 'large plan truncated'
        $previous=$large.start
        foreach($cell in $large.cells) {
            Assert-BuildingTest (([Math]::Abs($cell.position.x-$previous.x)+[Math]::Abs($cell.position.z-$previous.z)) -eq 1) 'path lost cardinal adjacency'
            $previous=$cell.position
        }
    }
    $checkpoint=New-BuildingCheckpoint $large
    $checkpointPath=Join-Path $temp 'checkpoint.json'
    Save-BuildingCheckpoint $checkpointPath $checkpoint
    $loaded=Read-BuildingCheckpoint $checkpointPath $large
    Assert-BuildingTest ($loaded.cells.Count -eq 16384 -and $loaded.next -eq 0) 'large checkpoint reload mismatch'
    $checkpoint.cells=@($checkpoint.cells | ForEach-Object {'confirmed'});$checkpoint.next=16384
    $checkpoint.torches=@($checkpoint.torches | ForEach-Object {'confirmed'});$checkpoint.torch_next=121
    for($i=0;$i -lt 16505;$i++){$checkpoint.receipts[[string]$i]=@{action_id=[guid]::NewGuid().ToString();verification='confirmed';effect_seq=1;client_tick=100;world_revision=1}}
    Save-BuildingCheckpoint $checkpointPath $checkpoint
    $loaded=Read-BuildingCheckpoint $checkpointPath $large
    Assert-BuildingTest ($loaded.receipts.Count -eq 16505 -and (Get-Item $checkpointPath).Length -lt 8388608) 'completed large ledger lost receipts or exceeded size bound'
    $checkpoint=New-BuildingCheckpoint $plan
    $cell=$plan.cells[0]
    $checkpoint.pending=@{kind='floor';index=0;target=$cell.position;material=$cell.material;nonce='test';action_id='test-action';terminal=$null}
    $effect=@{kind='block_place';subject='block:minecraft:overworld:-4,60,6';verification='confirmed';observed_after=$cell.material.state;seq=1;client_tick=10;world_revision=1}
    $terminal=@{action_id='test-action';state='cancelled';effects=@($effect)}
    Complete-BuildingPending $checkpoint $terminal
    Complete-BuildingPending $checkpoint $terminal
    Assert-BuildingTest ($checkpoint.next -eq 1 -and $checkpoint.consumed['minecraft:snow_block'] -eq 1) 'cancelled confirmed effect lost or counted twice'
    Save-BuildingCheckpoint $checkpointPath $checkpoint
    $loaded=Read-BuildingCheckpoint $checkpointPath $plan
    Assert-BuildingTest ($loaded.next -eq 1 -and $null -eq $loaded.pending) 'atomic commit did not retain progress'
    $checkpoint.pending=@{kind='floor';index=1;target=$plan.cells[1].position;material=$plan.cells[1].material;nonce='test';action_id='unknown';terminal=$null}
    Complete-BuildingPending $checkpoint @{action_id='unknown';state='failed';effects=@(@{kind='block_place';verification='unknown'})}
    Assert-BuildingTest ($checkpoint.next -eq 1 -and $checkpoint.status -ceq 'unknown' -and $null -ne $checkpoint.pending) 'unknown placement was replayable'
    $caught=$false
    try {Read-BuildingCheckpoint $checkpointPath $large | Out-Null}catch{$caught=$true}
    Assert-BuildingTest $caught 'changed blueprint accepted an old checkpoint'
    $raw=Get-Content -LiteralPath $checkpointPath -Raw
    [IO.File]::WriteAllText($checkpointPath,$raw.Replace('confirmed','observed'))
    $caught=$false
    try {Read-BuildingCheckpoint $checkpointPath $plan | Out-Null}catch{$caught=$true}
    Assert-BuildingTest $caught 'corrupt checkpoint accepted'
    $lock=[IO.File]::Open($checkpointPath+'.lock','OpenOrCreate','ReadWrite','None')
    try {
        $caught=$false
        try {$second=[IO.File]::Open($checkpointPath+'.lock','OpenOrCreate','ReadWrite','None');$second.Dispose()}catch{$caught=$true}
        Assert-BuildingTest $caught 'two writers acquired checkpoint lock'
    } finally {$lock.Dispose()}
    'MCMCP building ledger tests passed (4 rotations x 16384 cells, recovery, unknown, checksum, lock).'
} finally {
    # Only the GUID-named test directory created above is removed.
    $resolved=[IO.Path]::GetFullPath($temp)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -cmatch '^mcmcp-building-[a-f0-9]{32}$') {Remove-Item -LiteralPath $resolved -Recurse -Force}
}
