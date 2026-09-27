[CmdletBinding()]
param([Parameter(Mandatory)][string]$ArtifactDirectory,[string]$TokenPath,
    [string]$Endpoint='http://127.0.0.1:8765/mcp',[switch]$LibraryOnly)
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')

# Deterministic capability smoke test in the water-navigation fixture, not fresh LLM acceptance.
function Invoke-McmcpWaterNavigationGate {
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $path=Join-Path $ArtifactDirectory 'water-result.json'
    if(Test-Path -LiteralPath $path){throw 'Existing water result'}
    $results=[Collections.Generic.List[object]]::new()
    $failure=$null;$release=$null;$finalWorld=$null
    try {
        Assert-FixedFiveToolSurface
        foreach($goal in @(@(-2,61,5),@(-3,60,5),@(-4,59,5),@(-3,60,5),@(-2,61,5),@(-1,62,5),@(-1,62,5))) {
            $state=Get-FreshState
            $records=@(Get-RecordsFromState -State $state -Kinds @('traversability') -Filter $null)
            $record=@($records | Where-Object {
                $target=Get-ObjectProperty $_ 'navigation_target'
                $null -ne $target -and $target.x -eq $goal[0] -and $target.y -eq $goal[1] -and $target.z -eq $goal[2]
            } | Select-Object -First 1)
            if($record.Count -ne 1){
                Add-GateEvent -Event 'missing_water_target' -Detail @{goal=$goal;records=$records;world=$state.world}
                throw 'Fresh water navigation target unavailable'
            }
            $node=@{id='swim';op='navigate_to_known';target=$record[0].navigation_target;tolerance=0.20}
            $terminal=Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'water_navigation' `
                -Capabilities @('movement') -Node $node -Distance 32 -Camera 0) -WallTimeoutSeconds 60
            $after=Get-FreshState
            $position=$after.world.position
            Add-GateEvent -Event 'arrival_readback' -Detail @{target=$node.target;world=$after.world}
            $readbackTolerance=0.25
            if($after.world.health -lt $state.world.health -or
                [Math]::Abs($position.y-$goal[1]) -gt $readbackTolerance -or
                [Math]::Sqrt([Math]::Pow($position.x-$goal[0]-0.5,2)+[Math]::Pow($position.z-$goal[2]-0.5,2)) -gt $readbackTolerance){
                throw 'Water arrival or health mismatch'
            }
            $results.Add(@{target=$node.target;before=$state.world;after=$after.world;terminal=$terminal})
        }
    } catch {$failure=$_} finally {
        try {$release=Invoke-GateCleanup} catch {if($null -eq $failure){$failure=$_}}
        try {$finalWorld=(Get-FreshState).world} catch {if($null -eq $failure){$failure=$_}}
    }
    $result=@{status=$(if($null -eq $failure -and $results.Count -eq 7){'passed'}else{'failed'})
        movements=@($results.ToArray());input_release=$release;final_world=$finalWorld
        failure=$(if($null -eq $failure){$null}else{$failure.Exception.Message})}
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'water-events.jsonl'),
        @($script:GateEvents | ForEach-Object {ConvertTo-CompactJson $_}),$script:Utf8NoBom)
    [IO.File]::WriteAllText($path,($result | ConvertTo-Json -Depth 40),$script:Utf8NoBom)
    return $result
}
if(-not $LibraryOnly){
    $script:Bearer=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    Invoke-McmcpWaterNavigationGate | ConvertTo-Json -Depth 40
}
