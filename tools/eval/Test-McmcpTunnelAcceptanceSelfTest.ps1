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
            $coverageReport.violations -cnotcontains 'fixture post-run oracle did not preserve resource coverage') {
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
        if ($LASTEXITCODE -ne 1 -or $statusReport.passed -or
            $statusReport.violations -cnotcontains 'fixture T0 status was not ready and immutable') {
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
