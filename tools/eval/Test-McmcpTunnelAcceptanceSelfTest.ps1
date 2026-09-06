[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$checker = Join-Path $PSScriptRoot 'Test-McmcpTunnelAcceptance.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-tunnel-acceptance-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
$setupId = '550e8400-e29b-41d4-a716-446655440099'
$sessionId = '550e8400-e29b-41d4-a716-446655440080'
$common = [ordered]@{
    schema = 'mcmcp_fixture_tunnel_v1'; setupId = $setupId; mode = 'tunnel_hazard'
    worldSessionId = $sessionId
    baselineBlocks = 22168
    measurement = 'completedCells/prefixCells count excavated two-block columns, not visited route cells'
    auditBounds = [ordered]@{ max = @(418,203,264); min = @(256,196,248) }
    scenario = [ordered]@{
        lengthBlocks = 16; pattern = 'straight'; branchLengthBlocks = 0
        branchSpacingBlocks = 0; startFeet = @(257,200,256)
        entrance = @(258,200,256); face = 'west'; excavationCells = 16; routeMoves = 16
    }
    expectedResult = [ordered]@{
        excavatedCells = 4; completedMoves = 3; confirmedBreaks = 8; finalFeet = @(260,200,256)
    }
}
$status = [ordered]@{} + $common
$status.kind = 'status'; $status.baselineMatches = $true; $status.entities = 0
$status.inventoryMatches = $true; $status.startPoseMatches = $true
$status.playerBaselineMatches = $true; $status.ready = $true; $status.fixtureTickMutation = 'none'
$status.resourcesActive = $true; $status.raysPerTick = 512; $status.forcedChunks = 22
$oracle = [ordered]@{} + $common
$oracle.kind = 'oracle'; $oracle.baselineMatches = $false; $oracle.outsideChanged = 0
$oracle.completedCells = 4; $oracle.prefixCells = 4; $oracle.partialCells = 0
$oracle.invalidInsideStates = 0; $oracle.poseMatch = $true; $oracle.hazardPrefix = $true
$oracle.player = @(260.45,200.03,256.5); $oracle.health = 20.0; $oracle.pass = $true
$oracle.resourcesActive = $true; $oracle.forcedChunks = 22; $oracle.raysPerTick = 512
$oracle.scope = 'world-only; join with public Action and evaluation lease terminal receipts'
$gate = [ordered]@{
    schema_version = 1; gate = 'tunnel'; fixture_mode = 'hazard'; fixture_setup_id = $setupId
    world_session_id = $sessionId; fixture_status_sha256 = $null
    status = 'passed'; normal_player_actions_only = $true
    result = [ordered]@{
        action_id = '550e8400-e29b-41d4-a716-446655440091'; state = 'failed'
        world_session_id = $sessionId
        confirmed_breaks = 8; completed_cells = 3; completed_moves = 3; bounded_summary = $true
    }
    public_input_release = [ordered]@{
        action_id = '550e8400-e29b-41d4-a716-446655440091'; action_state = 'failed'
        control_ready = $true; all_actions_terminal = $true; cancel_requested = $false
        world_session_id = $sessionId
        input_owner_directly_exposed = $false
    }
    fixture_oracle_required = $true; failure = $null
}
$paths = @{
    gate = Join-Path $root 'gate.json'; status = Join-Path $root 'status.json'
    oracle = Join-Path $root 'oracle.json'; report = Join-Path $root 'report.json'
}
$script:AdditionalChecks = 0
function Write-TestArtifacts([bool]$BindHash = $true) {
    [IO.File]::WriteAllText($paths.status, (ConvertTo-Json $status -Depth 30), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    if ($BindHash) {
        $gate.fixture_status_sha256 = (Get-FileHash -LiteralPath $paths.status -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
}
function Assert-CheckResult([string]$Label, [bool]$ExpectedPass = $false, [bool]$ParseFailure = $false) {
    if (Test-Path -LiteralPath $paths.report) { Remove-Item -LiteralPath $paths.report }
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report 2>$null | Out-Null
    $checkerExit = $LASTEXITCODE
    if ($ParseFailure) {
        if ($checkerExit -ne 1 -or (Test-Path -LiteralPath $paths.report)) {
            throw "malformed JSON reached acceptance validation: $Label"
        }
    } else {
        if (-not (Test-Path -LiteralPath $paths.report)) { throw "checker omitted its report: $Label" }
        $checked = Get-Content -LiteralPath $paths.report -Raw | ConvertFrom-Json
        if ($checkerExit -ne $(if ($ExpectedPass) { 0 } else { 1 }) -or
            $checked.passed -ne $ExpectedPass -or
            (-not $ExpectedPass -and $checked.violations.Count -eq 0)) {
            throw "unexpected acceptance result: $Label"
        }
    }
    $script:AdditionalChecks++
    if ($script:AdditionalChecks % 20 -eq 0) { Write-Output "Additional acceptance checks: $script:AdditionalChecks passed" }
}
function Assert-FieldRejected([Collections.IDictionary]$Target, [string]$Field,
        [AllowNull()][object]$Value = $null, [switch]$Remove) {
    $original = $Target[$Field]
    $isHash = [object]::ReferenceEquals($Target, $gate) -and $Field -ceq 'fixture_status_sha256'
    try {
        if ($Remove) { $Target.Remove($Field) } else { $Target[$Field] = $Value }
        Write-TestArtifacts (-not $isHash)
        Assert-CheckResult $Field
    } finally { $Target[$Field] = $original }
}
try {
    $gate, $status, $oracle | ForEach-Object -Begin { $index = 0 } -Process {
        $path = @($paths.gate, $paths.status, $paths.oracle)[$index++]
        [IO.File]::WriteAllText($path, (ConvertTo-Json $_ -Depth 30), [Text.UTF8Encoding]::new($false))
    }
    $gate.fixture_status_sha256 = (Get-FileHash -LiteralPath $paths.status -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 0 -or -not (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'valid hazard acceptance was rejected'
    }
    $invalidCoverage = @(
        @{ field = 'resourcesActive'; remove = $true },
        @{ field = 'forcedChunks'; remove = $true },
        @{ field = 'raysPerTick'; remove = $true },
        @{ field = 'resourcesActive'; value = $false },
        @{ field = 'resourcesActive'; value = 'true' },
        @{ field = 'resourcesActive'; value = @($true) },
        @{ field = 'forcedChunks'; value = 21 },
        @{ field = 'forcedChunks'; value = '22' },
        @{ field = 'forcedChunks'; value = @(22) },
        @{ field = 'raysPerTick'; value = 256 },
        @{ field = 'raysPerTick'; value = '512' },
        @{ field = 'raysPerTick'; value = @(512) }
    )
    foreach ($invalid in $invalidCoverage) {
        $original = $oracle[$invalid.field]
        if ($invalid.ContainsKey('remove')) { $oracle.Remove($invalid.field) }
        else { $oracle[$invalid.field] = $invalid.value }
        [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
        & (Get-Process -Id $PID).Path -NoProfile -File $checker `
            -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
            -FixtureOraclePath $paths.oracle -OutputPath $paths.report
        $coverageReport = Get-Content -LiteralPath $paths.report -Raw | ConvertFrom-Json
        if ($LASTEXITCODE -ne 1 -or $coverageReport.passed -or
            $coverageReport.violations -cnotcontains 'fixture oracle did not preserve resource coverage') {
            throw "invalid post-run oracle coverage was accepted: $($invalid.field)"
        }
        $oracle[$invalid.field] = $original
    }
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    $invalidStatus = @(
        @{ field = 'forcedChunks'; value = 21 },
        @{ field = 'forcedChunks'; value = '22' },
        @{ field = 'forcedChunks'; value = @(22) },
        @{ field = 'resourcesActive'; value = @($true) },
        @{ field = 'ready'; value = @($true) },
        @{ field = 'raysPerTick'; value = '512' },
        @{ field = 'raysPerTick'; value = @(512) },
        @{ field = 'entities'; value = '0' },
        @{ field = 'entities'; value = @(0) }
    )
    foreach ($invalid in $invalidStatus) {
        $original = $status[$invalid.field]
        $status[$invalid.field] = $invalid.value
        [IO.File]::WriteAllText($paths.status, (ConvertTo-Json $status -Depth 30), [Text.UTF8Encoding]::new($false))
        $gate.fixture_status_sha256 = (Get-FileHash -LiteralPath $paths.status -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
        & (Get-Process -Id $PID).Path -NoProfile -File $checker `
            -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
            -FixtureOraclePath $paths.oracle -OutputPath $paths.report
        $statusReport = Get-Content -LiteralPath $paths.report -Raw | ConvertFrom-Json
        if ($LASTEXITCODE -ne 1 -or $statusReport.passed -or $statusReport.violations.Count -eq 0) {
            throw "invalid pre-run status evidence was accepted: $($invalid.field)"
        }
        $status[$invalid.field] = $original
    }
    $validStatusJson = ConvertTo-Json $status -Depth 30
    foreach ($invalidJson in @(('[' + $validStatusJson + ']'), 'true', 'null')) {
        [IO.File]::WriteAllText($paths.status, $invalidJson, [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $paths.report) { Remove-Item -LiteralPath $paths.report }
        & (Get-Process -Id $PID).Path -NoProfile -File $checker `
            -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
            -FixtureOraclePath $paths.oracle -OutputPath $paths.report 2>$null
        if ($LASTEXITCODE -ne 1 -or (Test-Path -LiteralPath $paths.report)) {
            throw 'non-object status JSON reached acceptance field validation'
        }
    }
    [IO.File]::WriteAllText($paths.status, $validStatusJson, [Text.UTF8Encoding]::new($false))
    $gate.fixture_status_sha256 = (Get-FileHash -LiteralPath $paths.status -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
    $oracle.player = @(260.8,200.0,256.5)
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'out-of-tolerance final pose was accepted'
    }
    $oracle.player = @(260.45,200.03,256.5)
    $oracle.outsideChanged = 1
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'out-of-bounds mutation was accepted'
    }
    $oracle.outsideChanged = 0
    foreach ($badField in @('worldSessionId', 'setupId', 'mode')) {
        $original = $oracle[$badField]
        $oracle[$badField] = if ($badField -ceq 'mode') { 'tunnel_straight16' }
            else { '550e8400-e29b-41d4-a716-446655440088' }
        [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
        & (Get-Process -Id $PID).Path -NoProfile -File $checker `
            -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
            -FixtureOraclePath $paths.oracle -OutputPath $paths.report
        if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
            throw "oracle from a different $badField was accepted"
        }
        $oracle[$badField] = $original
    }
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    foreach ($section in @('result', 'public_input_release')) {
        $gate[$section].world_session_id = '550e8400-e29b-41d4-a716-446655440088'
        [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
        & (Get-Process -Id $PID).Path -NoProfile -File $checker `
            -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
            -FixtureOraclePath $paths.oracle -OutputPath $paths.report
        if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
            throw "public $section from a different world session was accepted"
        }
        $gate[$section].world_session_id = $sessionId
    }
    [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
    # Missing required fields must not turn into zero, null, or an unbound success receipt.
    foreach ($field in @('schema_version', 'fixture_status_sha256', 'failure', 'result', 'public_input_release')) {
        Assert-FieldRejected $gate $field -Remove
    }
    foreach ($field in @('action_id', 'state', 'confirmed_breaks', 'bounded_summary')) {
        Assert-FieldRejected $gate.result $field -Remove
    }
    foreach ($field in @('action_id', 'action_state', 'cancel_requested', 'control_ready')) {
        Assert-FieldRejected $gate.public_input_release $field -Remove
    }
    foreach ($field in @('outsideChanged', 'scope', 'health', 'measurement', 'player')) {
        Assert-FieldRejected $oracle $field -Remove
    }
    foreach ($field in @('inventoryMatches', 'startPoseMatches', 'playerBaselineMatches', 'entities')) {
        Assert-FieldRejected $status $field -Remove
    }
    $malformed = @(
        @{ target = $gate; field = 'schema_version'; value = '1' },
        @{ target = $gate; field = 'fixture_mode'; value = @('hazard') },
        @{ target = $gate; field = 'fixture_setup_id'; value = @($setupId) },
        @{ target = $gate; field = 'world_session_id'; value = @($sessionId) },
        @{ target = $gate; field = 'fixture_status_sha256'; value = @($gate.fixture_status_sha256) },
        @{ target = $gate; field = 'fixture_status_sha256'; value = $gate.fixture_status_sha256.ToUpperInvariant() },
        @{ target = $gate; field = 'fixture_status_sha256'; value = 'not-a-hash' },
        @{ target = $gate; field = 'normal_player_actions_only'; value = @($true) },
        @{ target = $gate; field = 'fixture_oracle_required'; value = @($true) },
        @{ target = $gate; field = 'failure'; value = @{ message = 'still failed' } },
        @{ target = $gate; field = 'result'; value = @($gate.result) },
        @{ target = $gate; field = 'public_input_release'; value = @($gate.public_input_release) },
        @{ target = $gate.result; field = 'action_id'; value = 'not-an-action' },
        @{ target = $gate.result; field = 'action_id'; value = @($gate.result.action_id) },
        @{ target = $gate.result; field = 'confirmed_breaks'; value = '8' },
        @{ target = $gate.result; field = 'completed_cells'; value = @(3) },
        @{ target = $gate.result; field = 'completed_moves'; value = $true },
        @{ target = $gate.result; field = 'bounded_summary'; value = @($true) },
        @{ target = $gate.public_input_release; field = 'action_id'; value = '550e8400-e29b-41d4-a716-446655440088' },
        @{ target = $gate.public_input_release; field = 'action_state'; value = 'succeeded' },
        @{ target = $gate.public_input_release; field = 'cancel_requested'; value = $true },
        @{ target = $gate.public_input_release; field = 'cancel_requested'; value = @($false) },
        @{ target = $gate.public_input_release; field = 'all_actions_terminal'; value = @($true) },
        @{ target = $oracle; field = 'outsideChanged'; value = $null },
        @{ target = $oracle; field = 'outsideChanged'; value = $false },
        @{ target = $oracle; field = 'outsideChanged'; value = '0' },
        @{ target = $oracle; field = 'outsideChanged'; value = @(0) },
        @{ target = $oracle; field = 'pass'; value = @($true) },
        @{ target = $oracle; field = 'baselineMatches'; value = @($false) },
        @{ target = $oracle; field = 'poseMatch'; value = @($true) },
        @{ target = $oracle; field = 'hazardPrefix'; value = @($true) },
        @{ target = $oracle; field = 'health'; value = '20' },
        @{ target = $oracle; field = 'player'; value = @('260.5', 200.0, 256.5) },
        @{ target = $oracle; field = 'scope'; value = 'inventory pickup was confirmed' },
        @{ target = $status.scenario; field = 'lengthBlocks'; value = '16' },
        @{ target = $status.scenario; field = 'face'; value = 'east' },
        @{ target = $status.scenario; field = 'startFeet'; value = @(258,200,256) },
        @{ target = $status.scenario; field = 'entrance'; value = @('258',200,256) },
        @{ target = $status.auditBounds; field = 'min'; value = @('256',196,248) },
        @{ target = $status.expectedResult; field = 'finalFeet'; value = @(260,200) }
    )
    foreach ($invalid in $malformed) { Assert-FieldRejected $invalid.target $invalid.field $invalid.value }
    # Both fixtures agreeing on a changed/absent description must still fail against the fixed meaning.
    $savedMeasurement = $status.measurement
    foreach ($value in @($null, 'completedCells counts visited route cells')) {
        if ($null -eq $value) { $status.Remove('measurement'); $oracle.Remove('measurement') }
        else { $status.measurement = $value; $oracle.measurement = $value }
        Write-TestArtifacts
        Assert-CheckResult 'both measurement fields'
    }
    $status.measurement = $savedMeasurement; $oracle.measurement = $savedMeasurement
    Write-TestArtifacts
    # Duplicate property detection runs on the original bytes before PowerShell can collapse them.
    foreach ($artifact in @('gate', 'status', 'oracle')) {
        foreach ($extra in @('"probe":1,"probe":2,', '"probe":1,"PROBE":2,',
                '"probe":1,"pr\u006fbe":2,', '"probe":[{"child":1,"CHILD":2}],')) {
            Write-TestArtifacts
            $raw = [IO.File]::ReadAllText($paths[$artifact])
            $duplicate = '{' + $extra + $raw.Substring($raw.IndexOf('{') + 1)
            [IO.File]::WriteAllText($paths[$artifact], $duplicate, [Text.UTF8Encoding]::new($false))
            if ($artifact -ceq 'status') {
                $gate.fixture_status_sha256 = (Get-FileHash -LiteralPath $paths.status -Algorithm SHA256).Hash.ToLowerInvariant()
                [IO.File]::WriteAllText($paths.gate, (ConvertTo-Json $gate -Depth 30), [Text.UTF8Encoding]::new($false))
            }
            Assert-CheckResult "duplicate $artifact JSON" $false $true
        }
    }
    Write-TestArtifacts
    $rawOracle = [IO.File]::ReadAllText($paths.oracle)
    [IO.File]::WriteAllText($paths.oracle, ($rawOracle -replace '"health":\s*20\.0', '"health":1e400'), [Text.UTF8Encoding]::new($false))
    Assert-CheckResult 'non-finite numeric health'
    Write-TestArtifacts
    $savedDocuments = ConvertTo-Json @{ gate = $gate; status = $status; oracle = $oracle } -Depth 40
    foreach ($validCase in @(
            @{ mode = 'straight16'; length = 16; cells = 16; moves = 16; breaks = 32; finalX = 273; branch = $false },
            @{ mode = 'straight160'; length = 160; cells = 160; moves = 160; breaks = 320; finalX = 417; branch = $false },
            @{ mode = 'branches'; length = 16; cells = 40; moves = 64; breaks = 80; finalX = 273; branch = $true })) {
        $gate.fixture_mode = $validCase.mode
        $gate.result.state = 'succeeded'; $gate.public_input_release.action_state = 'succeeded'
        $gate.result.confirmed_breaks = $validCase.breaks
        $gate.result.completed_cells = $validCase.cells; $gate.result.completed_moves = $validCase.moves
        foreach ($fixture in @($status, $oracle)) {
            $fixture.mode = 'tunnel_' + $validCase.mode
            $fixture.scenario.lengthBlocks = $validCase.length
            $fixture.scenario.pattern = if ($validCase.branch) { 'branches' } else { 'straight' }
            $fixture.scenario.branchLengthBlocks = if ($validCase.branch) { 3 } else { 0 }
            $fixture.scenario.branchSpacingBlocks = if ($validCase.branch) { 4 } else { 0 }
            $fixture.scenario.excavationCells = $validCase.cells
            $fixture.scenario.routeMoves = $validCase.moves
            $fixture.expectedResult.excavatedCells = $validCase.cells
            $fixture.expectedResult.completedMoves = $validCase.moves
            $fixture.expectedResult.confirmedBreaks = $validCase.breaks
            $fixture.expectedResult.finalFeet = @($validCase.finalX,200,256)
        }
        $oracle.completedCells = $validCase.cells; $oracle.prefixCells = $validCase.cells
        $oracle.hazardPrefix = $false; $oracle.player = @(($validCase.finalX + 0.5),200.03,256.5)
        Write-TestArtifacts
        Assert-CheckResult $validCase.mode $true
    }
    $restored = ConvertFrom-Json -InputObject $savedDocuments -Depth 40 -AsHashtable -NoEnumerate
    $gate = $restored.gate; $status = $restored.status; $oracle = $restored.oracle
    Write-TestArtifacts
    # Even a semantically identical replacement must not masquerade as the bytes read before dispatch.
    [IO.File]::AppendAllText($paths.status, "`n", [Text.UTF8Encoding]::new($false))
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'a status artifact replaced after dispatch was accepted'
    }
} finally {
    if (Test-Path -LiteralPath $root) {
        $cleanupPath = [IO.Path]::GetFullPath($root)
        if (-not $cleanupPath.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($cleanupPath).StartsWith('mcmcp-tunnel-acceptance-', [StringComparison]::Ordinal)) {
            throw 'acceptance cleanup path escaped its temporary test directory'
        }
        Remove-Item -LiteralPath $cleanupPath -Recurse -Force
    }
}
Write-Output 'MCMCP tunnel acceptance self-test passed.'
