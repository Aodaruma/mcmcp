[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'McmcpRecoveryPreflight.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-preflight-test-' + [guid]::NewGuid().ToString('N'))
$passed = 0
function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "Test failed: $Name" }
    $script:passed++
}
function Reject([scriptblock]$Body, [string]$Code) {
    $observed = ''
    try { & $Body | Out-Null } catch { $observed = $_.Exception.Message }
    Check ($observed -ceq $Code) $Code
}
try {
    [IO.Directory]::CreateDirectory((Join-Path $root 'game/mods')) | Out-Null
    $build = Join-Path $root 'build.jar'
    $installed = Join-Path $root 'game/mods/product.jar'
    $options = Join-Path $root 'game/options.txt'
    [IO.File]::WriteAllText($build, 'fake product for file identity tests')
    [IO.File]::WriteAllText($installed, 'fake product for file identity tests')
    [IO.File]::WriteAllText($options, "maxFps:10`notherOption:private-value")
    $arguments = @{
        ProductCommit = ('a' * 40); ExpectedBuildJarSha256 = (Get-FileHash $build).Hash.ToLowerInvariant()
        BuildJarPath = $build; InstalledJarPath = $installed; OptionsPath = $options
        BaselineId = 'fixture-v1'; ExpectedMaxFps = 10
    }
    $record = New-McmcpRecoveryPreflight @arguments
    $t0 = [DateTimeOffset]::UtcNow.ToString('o')
    Check (Test-McmcpRecoveryPreflight $record $t0) 'valid record'
    Check (($record | ConvertTo-Json -Compress) -notmatch 'private-value|game[/\\]|build\.jar') 'no paths or unrelated options'
    Check (-not $record.runtime_jar_and_fps_verified) 'disk proof does not claim runtime'
    foreach ($key in @($record.Keys)) {
        $copy = $record | ConvertTo-Json | ConvertFrom-Json -AsHashtable -DateKind String
        $copy.Remove($key)
        Check (-not (Test-McmcpRecoveryPreflight $copy $t0)) "missing $key"
    }
    foreach ($change in @(
        @{key='installed_jar_sha256'; value=('b' * 64)},
        @{key='max_fps'; value='10'}, @{key='max_fps'; value=0},
        @{key='jar_files_match'; value='true'},
        @{key='runtime_jar_and_fps_verified'; value=$true},
        @{key='product_commit'; value='../private'},
        @{key='captured_utc'; value=[DateTimeOffset]::UtcNow.AddMinutes(1).ToString('o')},
        @{key='captured_utc'; value=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o')}
    )) {
        $copy = $record | ConvertTo-Json | ConvertFrom-Json -AsHashtable -DateKind String
        $copy[$change.key] = $change.value
        Check (-not (Test-McmcpRecoveryPreflight $copy $t0)) "invalid $($change.key)"
    }
    [IO.File]::WriteAllText($installed, 'different JAR')
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_JAR_MISMATCH'
    Copy-Item -LiteralPath $build -Destination $installed
    $arguments.ExpectedBuildJarSha256 = 'b' * 64
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_JAR_MISMATCH'
    $arguments.ExpectedBuildJarSha256 = $record.build_jar_sha256
    foreach ($badOptions in @('maxFps:120', "maxFps:10`nmaxFps:10", 'unrelated:10', 'maxFps:010', 'maxFps:10 extra')) {
        [IO.File]::WriteAllText($options, $badOptions)
        Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_FPS_MISMATCH'
    }
    [IO.File]::WriteAllText($options, 'maxFps:10')
    $arguments.InstalledJarPath = $build
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_DISTINCT_JARS_REQUIRED'
    $arguments.InstalledJarPath = $installed
    $arguments.OptionsPath = Join-Path $root 'options.txt'
    [IO.File]::WriteAllText($arguments.OptionsPath, 'maxFps:10')
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_GAME_DIRECTORY_MISMATCH'
    $arguments.OptionsPath = $options
    $arguments.BaselineId = "bad`nidentifier"
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_METADATA_INVALID'
    $arguments.BaselineId = 'fixture-v1'
    $arguments.BuildJarPath = Join-Path $root 'missing.jar'
    Reject { New-McmcpRecoveryPreflight @arguments } 'RECOVERY_PREFLIGHT_FILE_INVALID'
    Write-Output "Recovery preflight: $passed checks passed."
} finally {
    # Only the unique directory created above; verify the resolved target before deletion.
    $resolvedRoot = [IO.Path]::GetFullPath($root)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($resolvedRoot) -eq $tempRoot -and
        [IO.Path]::GetFileName($resolvedRoot) -cmatch '^mcmcp-preflight-test-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
