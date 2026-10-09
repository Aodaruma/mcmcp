#requires -Version 7.4
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2PrioritiesSmoke.ps1') -LibraryOnly
$catalog=Get-Content (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw | ConvertFrom-Json -Depth 100
$script:seen=0
$script:SmokeEvents=[Collections.Generic.List[object]]::new()
function Invoke-V2SmokeJob([string]$Name,[object]$Arguments) {
    $schema=@($catalog.tools | Where-Object name -CEQ $Name)[0].inputSchema
    Assert-McmcpSchema $Arguments $schema 'invalid_priority_smoke_arguments'
    $script:seen++
    return @{result=@{confirmed_transfers=1;completed_commands=1;resources=@{blueprint=@{format='csv'}};
        slots=@(@{slot=9;item='minecraft:oak_planks';count=24},@{slot=2;item='minecraft:stone';count=32})};
        progress=@{broken_blocks=1}}
}
function Get-V2SmokeState { return @{player=@{health=20;air=240;position=@{y=103.5}}} }
function Start-V2SmokeJob([string]$Name,[object]$Arguments) {
    [void](Invoke-V2SmokeJob $Name $Arguments)
    return '11111111-1111-1111-1111-111111111111'
}
function Wait-V2SmokeJob([string]$Id) {
    if($PriorityCase -eq 'Boat'){return @{state='failed';result=@{failure='dismount_landing_unverified';unconfirmed=$false}}}
    return @{state='failed';failure='air_supply_retreat';result=@{diverted=$true;diversion_reason='air_supply_retreat'}}
}
function Invoke-V2SmokeTool([string]$Name,[object]$Arguments=@{}) {
    if($Name -ceq 'agent_get_mcp_status'){return @{latest_frame_id='obs-0000000000000001'}}
    Assert-V2Smoke ($Name -ceq 'agent_get_observation') 'Unexpected observation'
    $schema=@($catalog.tools | Where-Object name -CEQ $Name)[0].inputSchema
    Assert-McmcpSchema $Arguments $schema 'invalid_priority_observation_arguments'
    return @{records=@(@{block='sophisticatedbackpacks:backpack'},@{entity_type='minecraft:oak_boat';entity_ref=('a'*24)},
        @{entity_type='minecraft:minecart';entity_ref=('b'*24)},@{entity_type='minecraft:horse';entity_ref=('c'*24)})}
}
foreach($PriorityCase in @('Inventory','Water','WaterAir','Boat','Minecart','Horse','ModBreak','Resources')) { Invoke-V2SmokeCore }
Assert-V2Smoke ($script:seen -eq 23) "Unexpected dispatch count: $script:seen"
'V2 priorities runner schemas passed for inventory, water, vehicles, MOD break and resources.'
