[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resetScript = Join-Path $PSScriptRoot 'Reset-McmcpHardBuildingGateWorld.ps1'
. $resetScript -Gate wall-3x3 -DataRoot 'F:\mcmcp-testlab\20260902-hard-building-v1' `
    -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "hard-building reset static test failed: $Message" }
}

# The reset runs through Windows PowerShell 5.1 on aod-mimoid. Detect accidental
# reintroduction of the PowerShell 6+ ConvertFrom-Json -Depth parameter statically.
$tokens = $null
$parseErrors = $null
$resetAst = [Management.Automation.Language.Parser]::ParseFile(
    $resetScript, [ref]$tokens, [ref]$parseErrors)
Assert-True ($parseErrors.Count -eq 0) 'reset script did not parse on this PowerShell host'
$unsupportedDepth = @($resetAst.FindAll({
            param($node)
            if ($node -isnot [Management.Automation.Language.CommandAst]) { return $false }
            $elements = @($node.CommandElements | ForEach-Object { $_.Extent.Text })
            return $elements.Count -gt 0 -and
                $elements[0] -ceq 'ConvertFrom-Json' -and
                @($elements | Where-Object { $_ -ceq '-Depth' }).Count -gt 0
        }, $true))
Assert-True ($unsupportedDepth.Count -eq 0) `
    'reset script uses PowerShell 6+ ConvertFrom-Json -Depth'

# Windows PowerShell 5.1 returns $null rather than an empty collection when
# Get-ChildItem has no results. Every Count check must force array semantics.
$resetSource = [IO.File]::ReadAllText($resetScript)
Assert-True ($resetSource -match
    "ValidateSet\('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3', 'wall-5x5'\)") `
    'remote reset gate list does not include the 5x5 wall profile'
Assert-True (-not [Text.RegularExpressions.Regex]::IsMatch(
        $resetSource,
        '(?<!@)\(Get-ChildItem[^\r\n]*\)\.Count')) `
    'Get-ChildItem Count check is not array-wrapped for PowerShell 5.1'
Assert-True ([Text.RegularExpressions.Regex]::IsMatch(
        $resetSource,
        '@\(Get-ChildItem[^\r\n]*\)\.Count')) `
    'expected array-wrapped empty-staging Count check is missing'

$dockerJson = '[{"Name":"/mcmcp-hard-building-20260902","State":{"Running":false,"Paused":false},"Mounts":[]}]'
$parsedDockerJson = @(ConvertFrom-DockerJson -Json $dockerJson)
Assert-True ($parsedDockerJson.Count -eq 1) '5.1-compatible Docker JSON parser changed array cardinality'
Assert-True ($parsedDockerJson[0].Name -ceq '/mcmcp-hard-building-20260902') `
    '5.1-compatible Docker JSON parser changed object fields'

Assert-True `
    (Test-DescendantPath `
        -Candidate 'F:\mcmcp-testlab\20260902-hard-building-v1\prism\save' `
        -Parent 'F:\mcmcp-testlab\20260902-hard-building-v1') `
    'known descendant was rejected'
Assert-True `
    (-not (Test-DescendantPath `
            -Candidate 'F:\mcmcp-testlab\20260902-hard-building-v10\prism\save' `
            -Parent 'F:\mcmcp-testlab\20260902-hard-building-v1')) `
    'prefix sibling escaped the descendant guard'
Assert-True `
    (-not (Test-DescendantPath `
            -Candidate 'F:\mcmcp-testlab\outside' `
            -Parent 'F:\mcmcp-testlab\20260902-hard-building-v1')) `
    'outside path escaped the descendant guard'

$validEntries = @(
    'tester (1)/',
    'tester (1)/level.dat',
    'tester (1)/dimensions/minecraft/overworld/region/r.-1.-1.mca',
    'tester (1)/dimensions/minecraft/overworld/region/r.-1.0.mca',
    'tester (1)/players/data/d48f4ce9-4f5a-48d1-ae5d-fe2a3ddd9ae4.dat'
)
Assert-ArchiveEntryList -Entries $validEntries
$unsafeArchiveRejected = $false
try {
    Assert-ArchiveEntryList -Entries ($validEntries + '../../outside')
} catch {
    $unsafeArchiveRejected = $_.Exception.Message -match 'unsafe entry'
}
Assert-True $unsafeArchiveRejected 'archive traversal entry was accepted'

$root = 'F:\mcmcp-testlab\20260902-hard-building-v1'
$dockerDesktopRoot = '/run/desktop/mnt/host/f/mcmcp-testlab/20260902-hard-building-v1'
$stopped = [pscustomobject]@{
    Name = '/mcmcp-hard-building-20260902'
    State = [pscustomobject]@{ Running = $false; Paused = $false }
    Mounts = @([pscustomobject]@{
            Type = 'bind'; Source = $root; Destination = '/data'
        })
}
Assert-ContainerContract -Container $stopped -Root $root

$dockerDesktopStopped = $stopped.PSObject.Copy()
$dockerDesktopStopped.Mounts = @([pscustomobject]@{
        Type = 'bind'; Source = $dockerDesktopRoot; Destination = '/data'
    })
Assert-ContainerContract -Container $dockerDesktopStopped -Root $root
Assert-True `
    ((Get-NormalizedDockerBindSourcePath $dockerDesktopRoot) -ceq $root) `
    'Docker Desktop host bind did not normalize to the audited Windows root'

$runningRejected = $false
$running = $stopped.PSObject.Copy()
$running.State = [pscustomobject]@{ Running = $true; Paused = $false }
try { Assert-ContainerContract -Container $running -Root $root } catch {
    $runningRejected = $_.Exception.Message -match 'fully stopped'
}
Assert-True $runningRejected 'running container passed the stop guard'

$wrongBindRejected = $false
$wrongBind = $stopped.PSObject.Copy()
$wrongBind.Mounts = @([pscustomobject]@{
        Type = 'bind'; Source = 'F:\mcmcp-testlab\wrong'; Destination = '/data'
    })
try { Assert-ContainerContract -Container $wrongBind -Root $root } catch {
    $wrongBindRejected = $_.Exception.Message -match 'bind does not match'
}
Assert-True $wrongBindRejected 'wrong /data bind passed the container guard'

foreach ($invalidDockerSource in @(
        '/run/desktop/mnt/host/f/mcmcp-testlab/20260902-hard-building',
        '/run/desktop/mnt/host/f/mcmcp-testlab/20260902-hard-building-v1/child',
        '/run/desktop/mnt/host/f/mcmcp-testlab/wrong',
        '/run/desktop/mnt/host/g/mcmcp-testlab/20260902-hard-building-v1',
        '/run/desktop/mnt/host/f/mcmcp-testlab/../20260902-hard-building-v1',
        '/host_mnt/f/mcmcp-testlab/20260902-hard-building-v1')) {
    $invalidBind = $stopped.PSObject.Copy()
    $invalidBind.Mounts = @([pscustomobject]@{
            Type = 'bind'; Source = $invalidDockerSource; Destination = '/data'
        })
    $invalidBindRejected = $false
    try { Assert-ContainerContract -Container $invalidBind -Root $root } catch {
        $invalidBindRejected = $_.Exception.Message -match 'bind does not match'
    }
    Assert-True $invalidBindRejected "unsafe Docker bind source passed: $invalidDockerSource"
}

Assert-True `
    (Test-DockerBindSourceOverlapsPath `
        -Source $dockerDesktopRoot -Path $root) `
    'exact Docker Desktop bind was not detected as overlapping'
Assert-True `
    (Test-DockerBindSourceOverlapsPath `
        -Source "$dockerDesktopRoot/child" -Path $root) `
    'Docker Desktop descendant bind was not detected as overlapping'
Assert-True `
    (Test-DockerBindSourceOverlapsPath `
        -Source '/run/desktop/mnt/host/f/mcmcp-testlab' -Path $root) `
    'Docker Desktop ancestor bind was not detected as overlapping'

'MCMCP hard-building world reset static tests passed.'
