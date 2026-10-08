#requires -Version 7.4
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2PrioritiesSmoke.ps1') -LibraryOnly
$catalog=Get-Content (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw | ConvertFrom-Json -Depth 100
$script:seen=0
function Invoke-V2SmokeJob([string]$Name,[object]$Arguments) {
    $schema=@($catalog.tools | Where-Object name -CEQ $Name)[0].inputSchema
    Assert-McmcpSchema $Arguments $schema 'invalid_priority_smoke_arguments'
    $script:seen++
    return @{result=@{confirmed_transfers=1;completed_commands=1;resources=@{blueprint=@{format='csv'}};
        slots=@(@{slot=9;item='minecraft:oak_planks';count=24},@{slot=2;item='minecraft:stone';count=32})};
        progress=@{broken_blocks=1}}
}
function Get-V2SmokeState { return @{player=@{health=20}} }
function Invoke-V2SmokeTool([string]$Name,[object]$Arguments=@{}) {
    Assert-V2Smoke ($Name -ceq 'agent_get_observation') 'Unexpected observation'
    return @{records=@(@{block='shogi:board'},@{entity_type='minecraft:oak_boat';entity_ref=('a'*24)},
        @{entity_type='minecraft:minecart';entity_ref=('b'*24)},@{entity_type='minecraft:horse';entity_ref=('c'*24)})}
}
foreach($PriorityCase in @('Inventory','Water','Boat','Minecart','Horse','ModBreak','Resources')) { Invoke-V2SmokeCore }
Assert-V2Smoke ($script:seen -eq 22) "Unexpected dispatch count: $script:seen"
'V2 priorities runner schemas passed for inventory, water, vehicles, MOD break and resources.'
