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

function Assert-UniqueJsonKeys([Text.Json.JsonElement]$Element) {
    if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $names.Add($property.Name)) { throw 'duplicate JSON property' }
            Assert-UniqueJsonKeys $property.Value
        }
    } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { Assert-UniqueJsonKeys $item }
    }
}

function Read-JsonObject([string]$Path, [string]$Label, $Sha256 = $null) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-Item -LiteralPath $Path).Length -gt 65536) {
        throw "$Label file is missing or exceeds its fixed size limit"
    }
    $bytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($Path))
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 65536) { throw "$Label has an invalid size" }
    $document = $null
    try {
        $json = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        $options = [Text.Json.JsonDocumentOptions]::new()
        $options.MaxDepth = 100
        $document = [Text.Json.JsonDocument]::Parse([ReadOnlyMemory[byte]]::new($bytes), $options)
        if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) {
            throw 'JSON root must be an object'
        }
        Assert-UniqueJsonKeys $document.RootElement
        $value = ConvertFrom-Json -InputObject $json -Depth 100 -NoEnumerate
        if ($value -isnot [System.Management.Automation.PSCustomObject]) { throw 'invalid object' }
    } catch { throw "$Label must be unique-key UTF-8 JSON with one object root" }
    finally { if ($null -ne $document) { $document.Dispose() } }
    if ($null -ne $Sha256) {
        $Sha256.Value = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    }
    return $value
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $violations.Add($Message) }
}
function Test-FiniteNumber([AllowNull()][object]$Value) {
    return ($Value -is [long] -or $Value -is [double]) -and [double]::IsFinite([double]$Value)
}

# Validate the original PSProperty.Value before returning it; preserve arrays as single values.
function Get-Field([AllowNull()][object]$Object, [string]$Name,
        [ValidateSet('string', 'long', 'bool', 'object', 'array', 'number', 'null')][string]$Type,
        [string]$Label) {
    $property = $null
    if ($Object -is [System.Management.Automation.PSCustomObject]) {
        $matching = @($Object.PSObject.Properties | Where-Object { $_.Name -ceq $Name })
        if ($matching.Count -eq 1) { $property = $matching[0] }
    }
    $valid = $null -ne $property
    if ($valid) {
        $valid = switch ($Type) {
            'string' { $property.Value -is [string] }
            'long' { $property.Value -is [long] }
            'bool' { $property.Value -is [bool] }
            'object' { $property.Value -isnot [array] -and
                    $property.Value -is [System.Management.Automation.PSCustomObject] }
            'array' { $property.Value -is [array] }
            'number' { Test-FiniteNumber $property.Value }
            'null' { $null -eq $property.Value }
        }
    }
    if (-not $valid) {
        Require $false "$Label.$Name must be a present $Type value"
        return $null
    }
    if ($Type -ceq 'array') { Write-Output -NoEnumerate $property.Value }
    else { return $property.Value }
}

function Assert-IntVector([AllowNull()][object]$Object, [string]$Name,
        [long[]]$Expected, [string]$Label) {
    $vector = Get-Field $Object $Name 'array' $Label
    $shape = $null -ne $vector -and $vector.Count -eq $Expected.Count
    Require $shape "$Label.$Name has an invalid vector shape"
    if (-not $shape) { return }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        Require ($vector[$index] -is [long] -and $vector[$index] -eq $Expected[$index]) `
            "$Label.$Name differs from the fixed integer vector"
    }
}

$gate = Read-JsonObject $GateResultPath 'gate result'
$statusHash = $null
$status = Read-JsonObject $FixtureStatusPath 'fixture status' ([ref]$statusHash)
$oracle = Read-JsonObject $FixtureOraclePath 'fixture oracle'
$mode = Get-Field $gate 'fixture_mode' 'string' 'gate'
$setupId = Get-Field $gate 'fixture_setup_id' 'string' 'gate'
$sessionId = Get-Field $gate 'world_session_id' 'string' 'gate'
$boundHash = Get-Field $gate 'fixture_status_sha256' 'string' 'gate'
$uuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
$cases = [ordered]@{
    straight16 = @{ wire = 'tunnel_straight16'; action_cells = 16; excavated_cells = 16; moves = 16; breaks = 32; final_feet = @(273,200,256) }
    straight160 = @{ wire = 'tunnel_straight160'; action_cells = 160; excavated_cells = 160; moves = 160; breaks = 320; final_feet = @(417,200,256) }
    branches = @{ wire = 'tunnel_branches'; action_cells = 40; excavated_cells = 40; moves = 64; breaks = 80; final_feet = @(273,200,256) }
    hazard = @{ wire = 'tunnel_hazard'; action_cells = 3; excavated_cells = 4; moves = 3; breaks = 8; final_feet = @(260,200,256) }
}
$case = if ($null -ne $mode -and $mode -cin @($cases.Keys)) { $cases[$mode] } else { $null }
Require ($null -ne $case) 'gate fixture_mode is unsupported'
Require ($setupId -cmatch $uuid) 'gate fixture_setup_id is invalid'
Require ($sessionId -cmatch $uuid) 'gate public world session is invalid'
Require ($boundHash -cmatch '^[0-9a-f]{64}$' -and $boundHash -ceq $statusHash) `
    'fixture status bytes differ from the artifact read before the Action'
Require ((Get-Field $gate 'schema_version' 'long' 'gate') -ceq 1 -and
    (Get-Field $gate 'gate' 'string' 'gate') -ceq 'tunnel' -and
    (Get-Field $gate 'status' 'string' 'gate') -ceq 'passed' -and
    (Get-Field $gate 'normal_player_actions_only' 'bool' 'gate') -ceq $true -and
    (Get-Field $gate 'fixture_oracle_required' 'bool' 'gate') -ceq $true) `
    'gate did not finish with the fixed tunnel acceptance contract'
[void](Get-Field $gate 'failure' 'null' 'gate')
$result = Get-Field $gate 'result' 'object' 'gate'
$release = Get-Field $gate 'public_input_release' 'object' 'gate'

if ($null -ne $case) {
    $expectedActionState = if ($mode -ceq 'hazard') { 'failed' } else { 'succeeded' }
    $actionId = Get-Field $result 'action_id' 'string' 'gate.result'
    Require ($actionId -cmatch $uuid) 'public Action id is invalid'
    Require ((Get-Field $result 'state' 'string' 'gate.result') -ceq $expectedActionState -and
        (Get-Field $result 'world_session_id' 'string' 'gate.result') -ceq $sessionId -and
        (Get-Field $result 'confirmed_breaks' 'long' 'gate.result') -ceq $case.breaks -and
        (Get-Field $result 'completed_cells' 'long' 'gate.result') -ceq $case.action_cells -and
        (Get-Field $result 'completed_moves' 'long' 'gate.result') -ceq $case.moves -and
        (Get-Field $result 'bounded_summary' 'bool' 'gate.result') -ceq $true) `
        'public Action result does not match the fixed plan'
    $releaseId = Get-Field $release 'action_id' 'string' 'gate.public_input_release'
    Require ($releaseId -cmatch $uuid -and $releaseId -ceq $actionId -and
        (Get-Field $release 'action_state' 'string' 'gate.public_input_release') -ceq $expectedActionState -and
        (Get-Field $release 'world_session_id' 'string' 'gate.public_input_release') -ceq $sessionId -and
        (Get-Field $release 'control_ready' 'bool' 'gate.public_input_release') -ceq $true -and
        (Get-Field $release 'all_actions_terminal' 'bool' 'gate.public_input_release') -ceq $true -and
        (Get-Field $release 'cancel_requested' 'bool' 'gate.public_input_release') -ceq $false -and
        (Get-Field $release 'input_owner_directly_exposed' 'bool' 'gate.public_input_release') -ceq $false) `
        'gate did not prove input release for the same terminal Action'

    $length = if ($mode -ceq 'straight160') { 160 } else { 16 }
    $branches = $mode -ceq 'branches'
    $pattern = if ($branches) { 'branches' } else { 'straight' }
    $measurement = 'completedCells/prefixCells count excavated two-block columns, not visited route cells'
    foreach ($entry in @(@{ value = $status; label = 'status' }, @{ value = $oracle; label = 'oracle' })) {
        $fixture = $entry.value
        $label = $entry.label
        Require ((Get-Field $fixture 'schema' 'string' $label) -ceq 'mcmcp_fixture_tunnel_v1' -and
            (Get-Field $fixture 'kind' 'string' $label) -ceq $label -and
            (Get-Field $fixture 'setupId' 'string' $label) -ceq $setupId -and
            (Get-Field $fixture 'worldSessionId' 'string' $label) -ceq $sessionId -and
            (Get-Field $fixture 'mode' 'string' $label) -ceq $case.wire -and
            (Get-Field $fixture 'baselineBlocks' 'long' $label) -ceq 22168 -and
            (Get-Field $fixture 'measurement' 'string' $label) -ceq $measurement) `
            "$label does not match the fixed fixture identity and measurement"
        $bounds = Get-Field $fixture 'auditBounds' 'object' $label
        Assert-IntVector $bounds 'min' @(256,196,248) "$label.auditBounds"
        Assert-IntVector $bounds 'max' @(418,203,264) "$label.auditBounds"
        $scenario = Get-Field $fixture 'scenario' 'object' $label
        Require ((Get-Field $scenario 'lengthBlocks' 'long' "$label.scenario") -ceq $length -and
            (Get-Field $scenario 'pattern' 'string' "$label.scenario") -ceq $pattern -and
            (Get-Field $scenario 'branchLengthBlocks' 'long' "$label.scenario") -ceq $(if ($branches) { 3 } else { 0 }) -and
            (Get-Field $scenario 'branchSpacingBlocks' 'long' "$label.scenario") -ceq $(if ($branches) { 4 } else { 0 }) -and
            (Get-Field $scenario 'face' 'string' "$label.scenario") -ceq 'west' -and
            (Get-Field $scenario 'excavationCells' 'long' "$label.scenario") -ceq $(if ($branches) { 40 } else { $length }) -and
            (Get-Field $scenario 'routeMoves' 'long' "$label.scenario") -ceq $(if ($branches) { 64 } else { $length })) `
            "$label scenario does not match the selected profile"
        Assert-IntVector $scenario 'startFeet' @(257,200,256) "$label.scenario"
        Assert-IntVector $scenario 'entrance' @(258,200,256) "$label.scenario"
        $expected = Get-Field $fixture 'expectedResult' 'object' $label
        Require ((Get-Field $expected 'excavatedCells' 'long' "$label.expectedResult") -ceq $case.excavated_cells -and
            (Get-Field $expected 'completedMoves' 'long' "$label.expectedResult") -ceq $case.moves -and
            (Get-Field $expected 'confirmedBreaks' 'long' "$label.expectedResult") -ceq $case.breaks) `
            "$label expectedResult does not match the selected profile"
        Assert-IntVector $expected 'finalFeet' $case.final_feet "$label.expectedResult"
        Require ((Get-Field $fixture 'resourcesActive' 'bool' $label) -ceq $true -and
            (Get-Field $fixture 'forcedChunks' 'long' $label) -ceq 22 -and
            (Get-Field $fixture 'raysPerTick' 'long' $label) -ceq 512) `
            "fixture $label did not preserve resource coverage"
    }
    foreach ($flag in @('ready', 'baselineMatches', 'inventoryMatches', 'startPoseMatches', 'playerBaselineMatches')) {
        Require ((Get-Field $status $flag 'bool' 'status') -ceq $true) 'fixture T0 status was not ready and immutable'
    }
    Require ((Get-Field $status 'entities' 'long' 'status') -ceq 0 -and
        (Get-Field $status 'fixtureTickMutation' 'string' 'status') -ceq 'none') `
        'fixture T0 status was not ready and immutable'

    $player = Get-Field $oracle 'player' 'array' 'oracle'
    $playerMatches = $null -ne $player -and $player.Count -eq 3
    if ($playerMatches) {
        $playerMatches = (Test-FiniteNumber $player[0]) -and (Test-FiniteNumber $player[1]) -and
            (Test-FiniteNumber $player[2])
        if ($playerMatches) {
            $playerMatches = [double]::Hypot([double]$player[0] - ($case.final_feet[0] + 0.5),
                [double]$player[2] - ($case.final_feet[2] + 0.5)) -le 0.25 -and
                [Math]::Abs([double]$player[1] - 200.0) -le 0.05
        }
    }
    Require $playerMatches 'fixture final player position is not a finite in-tolerance vector'
    Require ((Get-Field $oracle 'baselineMatches' 'bool' 'oracle') -ceq $false -and
        (Get-Field $oracle 'pass' 'bool' 'oracle') -ceq $true -and
        (Get-Field $oracle 'outsideChanged' 'long' 'oracle') -ceq 0 -and
        (Get-Field $oracle 'completedCells' 'long' 'oracle') -ceq $case.excavated_cells -and
        (Get-Field $oracle 'prefixCells' 'long' 'oracle') -ceq $case.excavated_cells -and
        (Get-Field $oracle 'partialCells' 'long' 'oracle') -ceq 0 -and
        (Get-Field $oracle 'invalidInsideStates' 'long' 'oracle') -ceq 0 -and
        (Get-Field $oracle 'poseMatch' 'bool' 'oracle') -ceq $true -and
        (Get-Field $oracle 'health' 'number' 'oracle') -ceq 20 -and
        (Get-Field $oracle 'hazardPrefix' 'bool' 'oracle') -ceq ($mode -ceq 'hazard') -and
        (Get-Field $oracle 'scope' 'string' 'oracle') -ceq
            'world-only; join with public Action and evaluation lease terminal receipts') `
        'fixture world oracle did not prove the exact bounded result'
}

$report = [ordered]@{
    schema_version = 1; passed = $violations.Count -eq 0; fixture_mode = $mode
    fixture_setup_id = $setupId; world_session_id = $sessionId
    fixture_status_sha256 = $statusHash; violations = @($violations)
}
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($OutputPath)))
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),
    (ConvertTo-Json $report -Depth 20), $Utf8NoBom)
if ($violations.Count -ne 0) { exit 1 }
Write-Output 'MCMCP tunnel acceptance: PASS'
