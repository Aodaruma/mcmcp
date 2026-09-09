[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3', 'wall-5x5', 'gate-c')]
    [string]$Gate,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TokenPath,

    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',

    [ValidateRange(61, 600)]
    [int]$StateRefWaitSeconds = 65,

    # Dot-source the functions without touching the network. Used only by the mock test.
    [switch]$LibraryOnly
)

# 共通支援と建築シナリオを同じscopeへ読み込む。LibraryOnlyでも全建築関数を公開する。
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1')

$script:SourceObservationForbidden = $false
$script:SourceObservationCount = 0
$script:ConstructionNavigationTolerance = 0.75
$script:PillarNavigationTolerance = 0.1
$script:TemporaryDropRecoveryNavigationTolerance = 0.1
$script:MaximumScaffoldNavigationSlices = 3

$script:ChestBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -12; min_y = 55; min_z = 2
    max_x = -10; max_y = 57; max_z = 5
}
$script:SourceBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 56; min_z = -1
    max_x = -18; max_y = 62; max_z = 5
}
$script:DestinationSupportBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 55; min_z = 9
    max_x = -18; max_y = 55; max_z = 15
}
$script:DestinationWallBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 55; min_z = 9
    max_x = -18; max_y = 60; max_z = 15
}

. (Join-Path $PSScriptRoot 'McmcpConstructionNavigation.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionPlacement.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionWallPlans.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionScaffoldBuild.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionScaffoldNavigation.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionScaffoldRecovery.ps1')
. (Join-Path $PSScriptRoot 'McmcpConstructionWallScenarios.ps1')

function Write-GateArtifacts {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Result,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $eventsPath = Join-Path $ArtifactDirectory 'gate-events.jsonl'
    $eventLines = @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ })
    [IO.File]::WriteAllLines($eventsPath, $eventLines, $script:Utf8NoBom)
    $gateResult = Get-ObjectProperty $Result 'gate_result'
    $capabilityComplete = Get-ObjectProperty $gateResult 'capability_complete'
    $manifest = [ordered]@{
        schema_version = 1
        gate = $Gate
        status = if ($null -ne $Failure) {
            'failed'
        } elseif ($capabilityComplete -is [bool] -and -not [bool]$capabilityComplete) {
            'incomplete'
        } else {
            'passed'
        }
        fixed_tools = @($script:AllowedTools)
        fixed_five_only = $true
        normal_player_actions_only = $true
        public_input_release = Get-ObjectProperty $Result 'input_release'
        result = Get-ObjectProperty $Result 'gate_result'
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{ type = $Failure.Exception.GetType().FullName; message = $Failure.Exception.Message }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($Gate -cin @('wall-3x3', 'wall-5x5', 'gate-c') -and
        $null -ne (Get-ObjectProperty $Result 'gate_result')) {
        $oracle = Get-ObjectProperty (Get-ObjectProperty $Result 'gate_result') 'external_oracle'
        if ($null -eq $oracle) { throw 'wall gate did not produce an external oracle manifest' }
        [IO.File]::WriteAllText(
            (Join-Path $ArtifactDirectory 'external-oracle-manifest.json'),
            (ConvertTo-Json $oracle -Depth 100), $script:Utf8NoBom)
    }
}

function Invoke-McmcpConstructionCapabilityGate {
    $script:ActiveActionId = $null
    $script:SourceObservationForbidden = $false
    $script:SourceObservationCount = 0
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try {
        $initial = Get-FreshState
        Assert-ReadyState -State $initial -Phase 'gate start'
        $gateResult = switch ($Gate) {
            'navigation' { Invoke-NavigationGate }
            'faces-place' { Invoke-PlacementGate }
            'state-ref-ttl' { Invoke-PlacementGate -UseStateRef }
            'wall-3x3' { Invoke-Wall3x3Gate }
            'wall-5x5' { Invoke-Wall5x5Gate }
            'gate-c' { Invoke-BuildingGateC }
        }
    } catch {
        $primaryFailure = $_
    } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $combined = [ordered]@{ gate_result = $gateResult; input_release = $release }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-GateArtifacts -Result $combined -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return $combined
}

if (-not $LibraryOnly) {
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    if ([string]::IsNullOrWhiteSpace($script:Bearer) -or
        $script:Bearer.Contains("`r") -or $script:Bearer.Contains("`n")) {
        throw 'MCP token file is empty or malformed'
    }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    Assert-FixedFiveToolSurface
    $result = Invoke-McmcpConstructionCapabilityGate
    ConvertTo-Json $result -Depth 100
}
