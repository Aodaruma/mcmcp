[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$GateResultPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$FixtureStatusPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$FixtureOraclePath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$violations = [Collections.Generic.List[string]]::new()

function Read-JsonObject([string]$Path, [string]$Label, $Sha256 = $null) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-Item -LiteralPath $Path).Length -gt 65536) {
        throw "$Label file is missing or exceeds its fixed size limit"
    }
    $bytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($Path))
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 65536) { throw "$Label has an invalid size" }
    try { $value = [Text.UTF8Encoding]::new($false, $true).GetString($bytes) | ConvertFrom-Json -Depth 100 -NoEnumerate }
    catch { throw "$Label is not valid UTF-8 JSON" }
    if ($value -isnot [System.Management.Automation.PSCustomObject]) { throw "$Label must be one JSON object" }
    if ($null -ne $Sha256) {
        $Sha256.Value = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    }
    return $value
}

function Get-Value([object]$Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $violations.Add($Message) }
}

function ConvertTo-Canonical([object]$Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [Collections.IDictionary]) {
        $sorted = [ordered]@{}
        foreach ($key in @($Value.Keys | ForEach-Object { [string]$_ } | Sort-Object)) {
            $sorted[$key] = ConvertTo-Canonical $Value[$key]
        }
        return $sorted
    }
    if ($Value -is [pscustomobject]) {
        $sorted = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | Sort-Object Name)) {
            $sorted[$property.Name] = ConvertTo-Canonical $property.Value
        }
        return $sorted
    }
    if ($Value -is [Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @($Value | ForEach-Object { ConvertTo-Canonical $_ })
        Write-Output -NoEnumerate $items
        return
    }
    return $Value
}

function Compact([object]$Value) {
    return ConvertTo-Json (ConvertTo-Canonical $Value) -Compress -Depth 100
}

$gate = Read-JsonObject $GateResultPath 'gate result'
$statusHash = $null
$status = Read-JsonObject $FixtureStatusPath 'fixture status' ([ref]$statusHash)
$oracle = Read-JsonObject $FixtureOraclePath 'fixture oracle'
$mode = [string](Get-Value $gate 'fixture_mode')
$cases = [ordered]@{
    straight16 = [ordered]@{
        wire = 'tunnel_straight16'; action_cells = 16; excavated_cells = 16
        moves = 16; breaks = 32; final_feet = @(273, 200, 256)
    }
    straight160 = [ordered]@{
        wire = 'tunnel_straight160'; action_cells = 160; excavated_cells = 160
        moves = 160; breaks = 320; final_feet = @(417, 200, 256)
    }
    branches = [ordered]@{
        wire = 'tunnel_branches'; action_cells = 40; excavated_cells = 40
        moves = 64; breaks = 80; final_feet = @(273, 200, 256)
    }
    hazard = [ordered]@{
        wire = 'tunnel_hazard'; action_cells = 3; excavated_cells = 4
        moves = 3; breaks = 8; final_feet = @(260, 200, 256)
    }
}
$case = $cases[$mode]
Require ($null -ne $case) 'gate fixture_mode is unsupported'

if ($null -ne $case) {
    $setupId = [string](Get-Value $gate 'fixture_setup_id')
    $sessionId = Get-Value $gate 'world_session_id'
    Require ($sessionId -is [string] -and
        $sessionId -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') `
        'gate public world session is invalid'
    Require ((Get-Value $gate 'fixture_status_sha256') -ceq $statusHash) `
        'fixture status bytes differ from the artifact read before the Action'
    Require ($setupId -match '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$') `
        'gate fixture_setup_id is invalid'
    Require ((Get-Value $gate 'schema_version') -eq 1 -and
        (Get-Value $gate 'gate') -ceq 'tunnel' -and
        (Get-Value $gate 'status') -ceq 'passed' -and
        (Get-Value $gate 'normal_player_actions_only') -is [bool] -and
        [bool](Get-Value $gate 'normal_player_actions_only') -and
        (Get-Value $gate 'fixture_oracle_required') -is [bool] -and
        [bool](Get-Value $gate 'fixture_oracle_required')) `
        'gate did not finish with the fixed tunnel acceptance contract'
    $release = Get-Value $gate 'public_input_release'
    Require ((Get-Value $release 'control_ready') -is [bool] -and
        [bool](Get-Value $release 'control_ready') -and
        (Get-Value $release 'all_actions_terminal') -is [bool] -and
        [bool](Get-Value $release 'all_actions_terminal') -and
        (Get-Value $release 'world_session_id') -ceq $sessionId -and
        (Get-Value $release 'input_owner_directly_exposed') -is [bool] -and
        -not [bool](Get-Value $release 'input_owner_directly_exposed')) `
        'gate did not prove terminal public input release'

    foreach ($fixture in @($status, $oracle)) {
        Require ((Get-Value $fixture 'schema') -ceq 'mcmcp_fixture_tunnel_v1') `
            'fixture schema changed'
        Require ((Get-Value $fixture 'setupId') -ceq $setupId) `
            'fixture setupId does not match the gate run'
        Require ((Get-Value $fixture 'worldSessionId') -ceq $sessionId) `
            'fixture world session does not match the public Action run'
        Require ((Get-Value $fixture 'mode') -ceq $case.wire) `
            'fixture mode does not match the gate run'
        Require ((Get-Value $fixture 'baselineBlocks') -eq 22168) `
            'fixture bounded volume changed'
    }
    foreach ($field in @('auditBounds', 'scenario', 'expectedResult', 'measurement')) {
        Require ((Compact (Get-Value $status $field)) -ceq (Compact (Get-Value $oracle $field))) `
            "fixture status/oracle mismatch: $field"
    }
    $bounds = Get-Value $oracle 'auditBounds'
    Require ((Compact (Get-Value $bounds 'min')) -ceq (Compact @(256,196,248)) -and
        (Compact (Get-Value $bounds 'max')) -ceq (Compact @(418,203,264))) `
        'fixture audit bounds changed'
    $scenario = Get-Value $oracle 'scenario'
    $expectedPattern = if ($mode -ceq 'branches') { 'branches' } else { 'straight' }
    $expectedLength = if ($mode -ceq 'straight160') { 160 } else { 16 }
    Require ((Get-Value $scenario 'lengthBlocks') -eq $expectedLength -and
        (Get-Value $scenario 'pattern') -ceq $expectedPattern -and
        (Get-Value $scenario 'branchLengthBlocks') -eq $(if ($mode -ceq 'branches') { 3 } else { 0 }) -and
        (Get-Value $scenario 'branchSpacingBlocks') -eq $(if ($mode -ceq 'branches') { 4 } else { 0 }) -and
        (Get-Value $scenario 'excavationCells') -eq $(if ($mode -ceq 'straight160') { 160 } elseif ($mode -ceq 'branches') { 40 } else { 16 }) -and
        (Get-Value $scenario 'routeMoves') -eq $(if ($mode -ceq 'branches') { 64 } else { $expectedLength })) `
        'fixture scenario does not match the selected profile'
    $forcedChunks = $status.PSObject.Properties['forcedChunks']
    Require ((Get-Value $status 'kind') -ceq 'status' -and
        (Get-Value $status 'ready') -is [bool] -and [bool](Get-Value $status 'ready') -and
        (Get-Value $status 'baselineMatches') -is [bool] -and
        [bool](Get-Value $status 'baselineMatches') -and
        (Get-Value $status 'resourcesActive') -is [bool] -and
        [bool](Get-Value $status 'resourcesActive') -and
        (Get-Value $status 'raysPerTick') -eq 512 -and
        $null -ne $forcedChunks -and $forcedChunks.Value -is [long] -and
        $forcedChunks.Value -eq 22 -and
        (Get-Value $status 'fixtureTickMutation') -ceq 'none') `
        'fixture T0 status was not ready and immutable'

    $result = Get-Value $gate 'result'
    $expectedActionState = if ($mode -ceq 'hazard') { 'failed' } else { 'succeeded' }
    Require ((Get-Value $result 'state') -ceq $expectedActionState -and
        (Get-Value $result 'world_session_id') -ceq $sessionId -and
        (Get-Value $result 'confirmed_breaks') -eq $case.breaks -and
        (Get-Value $result 'completed_cells') -eq $case.action_cells -and
        (Get-Value $result 'completed_moves') -eq $case.moves -and
        (Get-Value $result 'bounded_summary') -is [bool] -and
        [bool](Get-Value $result 'bounded_summary')) `
        'public Action result does not match the fixed plan'

    $expectedResult = Get-Value $oracle 'expectedResult'
    Require ((Get-Value $expectedResult 'excavatedCells') -eq $case.excavated_cells -and
        (Get-Value $expectedResult 'completedMoves') -eq $case.moves -and
        (Get-Value $expectedResult 'confirmedBreaks') -eq $case.breaks -and
        (Compact (Get-Value $expectedResult 'finalFeet')) -ceq (Compact $case.final_feet)) `
        'fixture expectedResult does not match the selected profile'
    $player = @(Get-Value $oracle 'player')
    $playerMatches = $player.Count -eq 3 -and
        $player[0] -is [ValueType] -and $player[1] -is [ValueType] -and
        $player[2] -is [ValueType] -and
        [double]::IsFinite([double]$player[0]) -and
        [double]::IsFinite([double]$player[1]) -and
        [double]::IsFinite([double]$player[2]) -and
        [double]::Hypot(
            [double]$player[0] - ([double]($case.final_feet[0]) + 0.5),
            [double]$player[2] - ([double]($case.final_feet[2]) + 0.5)) -le 0.25 -and
        [Math]::Abs([double]$player[1] - 200.0) -le 0.05
    Require ((Get-Value $oracle 'kind') -ceq 'oracle' -and
        (Get-Value $oracle 'baselineMatches') -is [bool] -and
        -not [bool](Get-Value $oracle 'baselineMatches') -and
        (Get-Value $oracle 'pass') -is [bool] -and [bool](Get-Value $oracle 'pass') -and
        (Get-Value $oracle 'outsideChanged') -eq 0 -and
        (Get-Value $oracle 'completedCells') -eq $case.excavated_cells -and
        (Get-Value $oracle 'prefixCells') -eq $case.excavated_cells -and
        (Get-Value $oracle 'partialCells') -eq 0 -and
        (Get-Value $oracle 'invalidInsideStates') -eq 0 -and
        (Get-Value $oracle 'poseMatch') -is [bool] -and
        [bool](Get-Value $oracle 'poseMatch') -and
        $playerMatches -and
        (Get-Value $oracle 'health') -eq 20) `
        'fixture world oracle did not prove the exact bounded result'
    $expectedHazard = $mode -ceq 'hazard'
    Require ((Get-Value $oracle 'hazardPrefix') -is [bool] -and
        [bool](Get-Value $oracle 'hazardPrefix') -eq $expectedHazard) `
        'fixture hazard prefix proof does not match the selected profile'
}

$report = [ordered]@{
    schema_version = 1
    passed = $violations.Count -eq 0
    fixture_mode = $mode
    fixture_setup_id = Get-Value $gate 'fixture_setup_id'
    world_session_id = Get-Value $gate 'world_session_id'
    fixture_status_sha256 = $statusHash
    violations = @($violations)
}
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($OutputPath)))
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),
    (ConvertTo-Json $report -Depth 20), $Utf8NoBom)
if ($violations.Count -ne 0) { exit 1 }
Write-Output 'MCMCP tunnel acceptance: PASS'
