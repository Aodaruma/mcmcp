[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$checker = Join-Path $PSScriptRoot 'Test-McmcpTunnelAcceptance.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-tunnel-acceptance-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
$setupId = '550e8400-e29b-41d4-a716-446655440099'
$common = [ordered]@{
    schema = 'mcmcp_fixture_tunnel_v1'; setupId = $setupId; mode = 'tunnel_hazard'
    baselineBlocks = 22168
    measurement = 'completedCells/prefixCells count excavated two-block columns, not visited route cells'
    auditBounds = [ordered]@{ min = @(256,196,248); max = @(418,203,264) }
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
$oracle = [ordered]@{} + $common
$oracle.kind = 'oracle'; $oracle.baselineMatches = $false; $oracle.outsideChanged = 0
$oracle.completedCells = 4; $oracle.prefixCells = 4; $oracle.partialCells = 0
$oracle.invalidInsideStates = 0; $oracle.poseMatch = $true; $oracle.hazardPrefix = $true
$oracle.player = @(260.5,200.0,256.5); $oracle.health = 20.0; $oracle.pass = $true
$oracle.scope = 'world-only; join with public Action and evaluation lease terminal receipts'
$gate = [ordered]@{
    schema_version = 1; gate = 'tunnel'; fixture_mode = 'hazard'; fixture_setup_id = $setupId
    status = 'passed'; normal_player_actions_only = $true
    result = [ordered]@{
        action_id = '550e8400-e29b-41d4-a716-446655440091'; state = 'failed'
        confirmed_breaks = 8; completed_cells = 3; completed_moves = 3; bounded_summary = $true
    }
    public_input_release = [ordered]@{
        control_ready = $true; all_actions_terminal = $true; cancel_requested = $false
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
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 0 -or -not (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'valid hazard acceptance was rejected'
    }
    $oracle.outsideChanged = 1
    [IO.File]::WriteAllText($paths.oracle, (ConvertTo-Json $oracle -Depth 30), [Text.UTF8Encoding]::new($false))
    & (Get-Process -Id $PID).Path -NoProfile -File $checker `
        -GateResultPath $paths.gate -FixtureStatusPath $paths.status `
        -FixtureOraclePath $paths.oracle -OutputPath $paths.report
    if ($LASTEXITCODE -ne 1 -or (Get-Content $paths.report -Raw | ConvertFrom-Json).passed) {
        throw 'out-of-bounds mutation was accepted'
    }
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
Write-Output 'MCMCP tunnel acceptance self-test passed.'
