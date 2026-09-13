[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

# Short capability test for the external construction-materials fixture. No fixture operations
# occur here: all gameplay uses the same five production tools as other capability gates.
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')

. (Join-Path $PSScriptRoot 'McmcpConstructionMaterialObservation.ps1')

function Invoke-McmcpConstructionMaterialsGate {
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $resultPath = Join-Path $ArtifactDirectory 'materials-result.json'
    if (Test-Path -LiteralPath $resultPath) { throw 'Refusing to overwrite a prior gate result' }
    $placements = @(
        [ordered]@{ item = 'minecraft:snow_block'; source = @(-8,56,3); support = @(-6,55,3); support_block = 'minecraft:smooth_stone' },
        [ordered]@{ item = 'minecraft:black_wool'; source = @(-8,56,4); support = @(-5,55,3); support_block = 'minecraft:smooth_stone' },
        [ordered]@{ item = 'minecraft:red_concrete'; source = @(-8,56,5); support = @(-4,55,3); support_block = 'minecraft:smooth_stone' },
        [ordered]@{ item = 'minecraft:torch'; source = @(-8,57,3); support = @(-6,56,3); support_block = 'minecraft:snow_block' }
    )
    $failure = $null
    $release = $null
    $confirmed = [Collections.Generic.List[object]]::new()
    try {
        Assert-FixedFiveToolSurface
        $initial = Get-FreshState
        foreach ($placement in $placements) {
            $chest = Get-MaterialSurface 'minecraft:chest' -7 56 7 $null
            $node = [ordered]@{ id = 'take_material'; op = 'take_known_container_stack'
                target = Get-ObjectProperty $chest 'position'; expected_block = 'minecraft:chest'
                item = $placement.item; stack_policy = 'default_components_only'; minimum_inventory_count = 1 }
            $request = New-PrimitiveRequest -Name 'materials_take' -Capabilities @('camera','inventory_transfer') `
                -Node $node -Interactions 3
            [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
        }
        foreach ($placement in $placements) {
            $source = Get-MaterialSurface $placement.item $placement.source[0] $placement.source[1] $placement.source[2] $null
            $ref = [string](Get-ObjectProperty $source 'placement_state_ref')
            if ($ref -cnotmatch '^psr_[0-9a-f]{32}$' -or
                (Get-ObjectProperty $source 'placement_item') -cne $placement.item) {
                throw 'Material did not provide a usable placement source'
            }
            $support = Get-MaterialSurface $placement.support_block $placement.support[0] $placement.support[1] $placement.support[2] @('up')
            $position = Get-ObjectProperty $support 'position'
            $face = [ordered]@{ id = 'face_support'; op = 'face_known_block_face'; target = $position
                face = 'up'; expected_block = $placement.support_block }
            [void](Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'materials_face' `
                -Capabilities @('camera') -Node $face) -WallTimeoutSeconds 60)
            $support = Get-MaterialSurface $placement.support_block $placement.support[0] $placement.support[1] $placement.support[2] @('up')
            $beforeCount = Get-InventoryCount -State (Get-FreshState) -Item $placement.item
            $target = Get-TargetAboveSupport (Get-ObjectProperty $support 'position')
            $entry = [ordered]@{ id = 'material'; offset = [ordered]@{x=0;y=0;z=0}; placement_state_ref = $ref
                support = [ordered]@{position=(Get-ObjectProperty $support 'position'); face='up'
                    expected_state=(Get-ObjectProperty $support 'state'); dependency_entry_id=$null} }
            $node = [ordered]@{ id='place_material'; op='apply_known_block_plan'; anchor=$target
                transform=[ordered]@{rotation=0;mirror='none'}; entries=@($entry) }
            $terminal = Invoke-ActionRequest -Request (New-PrimitiveRequest -Name 'materials_place' `
                -Capabilities @('camera','block_place') -Node $node -Duration 15000 -Ticks 300 -Camera 80 -Placements 1) `
                -WallTimeoutSeconds 60
            $observed = Get-MaterialSurface $placement.item $target.x $target.y $target.z $null
            $afterCount = Get-InventoryCount -State (Get-FreshState) -Item $placement.item
            if ($afterCount -ne $beforeCount - 1 -or
                (Get-ObjectProperty (Get-ObjectProperty $observed 'state') 'block') -cne $placement.item) {
                throw 'Material placement did not confirm both block and inventory consumption'
            }
            $confirmed.Add([ordered]@{ item=$placement.item; target=$target; before=$beforeCount; after=$afterCount
                action_id=(Get-ObjectProperty $terminal 'action_id'); final_state=(Get-ObjectProperty $observed 'state') })
        }
    } catch { $failure = $_ } finally {
        try { $release = Invoke-GateCleanup } catch { if ($null -eq $failure) { $failure = $_ } }
    }
    $result = [ordered]@{ status= $(if ($null -eq $failure -and $confirmed.Count -eq 4) {'passed'} else {'failed'})
        confirmed=@($confirmed.ToArray()); input_release=$release
        failure=$(if ($null -eq $failure) {$null} else {$failure.Exception.Message}) }
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'materials-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }),$script:Utf8NoBom)
    [IO.File]::WriteAllText($resultPath,($result | ConvertTo-Json -Depth 30),$script:Utf8NoBom)
    if ($null -ne $failure) { throw $failure }
    return $result
}

if (-not $LibraryOnly) {
    if ([string]::IsNullOrWhiteSpace($TokenPath)) { throw 'TokenPath is required' }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    Invoke-McmcpConstructionMaterialsGate | ConvertTo-Json -Depth 30
}
