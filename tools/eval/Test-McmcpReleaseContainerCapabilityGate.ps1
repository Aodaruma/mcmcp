Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Invoke-McmcpReleaseContainerGate.ps1') `
    -ArtifactDirectory 'mock-never-created' -Scenario exact -LibraryOnly

# Exercise the actual step assertions with an already-satisfied absolute goal.
# No network, Minecraft, credentials or artifact writes are used.
function Get-FreshState { return $script:Inventory }
function Get-InventoryCount { param($State,$Item) return $State }
function Get-MaterialSurface {
    param($Block,$X,$Y,$Z,$Faces)
    return @{position=@{dimension='minecraft:overworld';x=$X;y=$Y;z=$Z}}
}
function Invoke-ActionRequest {
    param($Request,$WallTimeoutSeconds,[switch]$ReturnFailure)
    $script:LastRequest=$Request
    $script:Inventory=$script:After
    return @{state='succeeded';effect_aggregate=@{unknown_effects=$script:Unknown};
        effects=@(@{kind=$script:Kind;verification='confirmed';
            observed_after=@{transferred=$script:Transferred}})}
}
foreach($case in @('take','store','wrong-effect','wrong-inventory','unknown')) {
    $script:ReleaseTerminals=[Collections.Generic.List[object]]::new()
    $script:Inventory=4;$script:After=6;$script:Transferred=2;$script:Unknown=0
    $script:Kind='container_take';$operation='take'
    if($case -eq 'store'){$operation='store';$script:After=2;$script:Kind='container_store'}
    if($case -eq 'wrong-effect'){$script:Transferred=1}
    if($case -eq 'wrong-inventory'){$script:After=5}
    if($case -eq 'unknown'){$script:Unknown=1}
    $failed=$false
    try {
        Invoke-ReleaseContainerStep mock $operation minecraft:chest -4 6 minecraft:snow_block 2 `
            -TransferCount 2 -ExpectedTransfer 2
    }catch{$failed=$true}
    if($failed -ne ($case -notin @('take','store'))){throw "Incorrect gate verdict: $case"}
    $node=$script:LastRequest.program.body[0]
    $goal=if($operation -eq 'take'){$node.minimum_inventory_count}else{$node.minimum_container_count}
    if($node.transfer_count -ne 2 -or $goal -ne 2 -or $node.max_transfer_count -ne 896){
        throw 'Exact amount or absolute goal was lost'
    }
}
# Legacy calls must keep whole-stack semantics by omitting transfer_count.
$script:Inventory=4;$script:After=6;$script:Transferred=2;$script:Unknown=0;$script:Kind='container_take'
Invoke-ReleaseContainerStep legacy take minecraft:chest -4 6 minecraft:snow_block 6 -ExpectedTransfer 2
if($script:LastRequest.program.body[0].Contains('transfer_count')){throw 'Legacy transfer semantics changed'}
'MCMCP release container gate tests passed.'
