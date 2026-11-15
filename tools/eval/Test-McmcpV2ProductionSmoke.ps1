#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpV2ProductionSmoke.ps1') -LibraryOnly
$ExpectedWorldSession = 'isolated-production'
$script:world = $ExpectedWorldSession
$script:x = 305
$script:duplicate = $false
Assert-V2Smoke ((Get-V2SmokeItemCount @{slots=@()} 'minecraft:milk_bucket') -eq 0) 'Empty inventory is zero'
Assert-V2Smoke ((Get-V2SmokeItemCount @{slots=@(@{item='minecraft:bucket';count=6})} 'minecraft:milk_bucket') -eq 0) 'Absent materials are zero'
function Invoke-V2SmokeTool([string]$Name, [object]$Arguments = @{}) {
    switch ($Name) {
        'agent_get_mcp_status' { return @{world_session_id=$script:world} }
        'agent_get_state' {
            if (!$Arguments.ContainsKey('query')) { return @{player=@{position=@{x=$script:x;y=101;z=305}}} }
            $recipe = @{recipe_ref=('a'*24);fingerprint=('sha256:'+('0'*64));supported=$false;v2_craft_supported=$true}
            return @{recipe_query=@{recipes=$(if($script:duplicate){@($recipe,$recipe)}else{@($recipe)})}}
        }
        default { throw 'Unexpected tool in mock' }
    }
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected=$false
    try { & $Action | Out-Null } catch { $rejected=$true }
    if (!$rejected) { throw 'Unsafe fixture admission' }
}
[void](Get-V2SmokeState)
$script:world='other'
Assert-Rejected { Get-V2SmokeState }
$script:world=$ExpectedWorldSession;$script:x=0
Assert-Rejected { Get-V2SmokeState }
$script:x=305
$recipe=Get-V2ProductionRecipe 'minecraft:cake'
Assert-V2Smoke ($recipe.v2_craft_supported -and !$recipe.supported) 'Legacy support cannot exclude v2 remainders'
$script:duplicate=$true
Assert-Rejected { Get-V2ProductionRecipe 'minecraft:cake' }
$catalog=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') -Raw | ConvertFrom-Json -Depth 100
$script:seen=0
function Invoke-V2SmokeJob([string]$Name, [object]$Arguments) {
    $schema=@($catalog.tools | Where-Object name -CEQ $Name)[0].inputSchema
    Assert-McmcpSchema $Arguments $schema 'invalid_mock_production_arguments'
    if ($Name -eq 'agent_interact') {
        $script:seen++
        Assert-V2Smoke ($Arguments.input.potion -eq 'minecraft:awkward' -and $Arguments.ingredient -eq 'minecraft:blaze_powder') 'Wrong precharged brew transition'
        return @{result=@{confirmed_count=3;confirmed=$true}}
    }
    return @{result=@{slots=@()}}
}
$ProductionCase='BrewFilled'
Invoke-V2SmokeCore
Assert-V2Smoke ($script:seen -eq 1) 'Production dispatch must not repeat'
Write-Output 'V2 production runner tests passed (fixture/session, v2 remainder recipe, ambiguity, typed brew dispatch).'
