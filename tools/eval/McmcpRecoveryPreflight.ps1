# Read-only, pre-T0 file evidence. Nothing in this record is sent to the model.
function Get-McmcpRecoveryFile {
    param([string]$Path, [long]$MaximumBytes)
    try {
        $file = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        if ($file -isnot [IO.FileInfo] -or $file.Length -le 0 -or
            $file.Length -gt $MaximumBytes) { throw 'invalid' }
        $cursor = $file
        while ($null -ne $cursor) {
            if (($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'invalid'
            }
            $cursor = if ($cursor -is [IO.FileInfo]) { $cursor.Directory } else { $cursor.Parent }
        }
        return $file
    } catch { throw 'RECOVERY_PREFLIGHT_FILE_INVALID' }
}

function New-McmcpRecoveryPreflight {
    param(
        [string]$ProductCommit, [string]$ExpectedBuildJarSha256,
        [string]$BuildJarPath, [string]$InstalledJarPath, [string]$OptionsPath,
        [string]$BaselineId, [int]$ExpectedMaxFps
    )
    if ($ProductCommit -cnotmatch '^[0-9a-f]{40}$' -or
        $ExpectedBuildJarSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $BaselineId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
        $ExpectedMaxFps -lt 1 -or $ExpectedMaxFps -gt 1000) {
        throw 'RECOVERY_PREFLIGHT_METADATA_INVALID'
    }
    $build = Get-McmcpRecoveryFile -Path $BuildJarPath -MaximumBytes 268435456
    $installed = Get-McmcpRecoveryFile -Path $InstalledJarPath -MaximumBytes 268435456
    $options = Get-McmcpRecoveryFile -Path $OptionsPath -MaximumBytes 2097152
    if ($build.Extension -cne '.jar' -or $installed.Extension -cne '.jar') {
        throw 'RECOVERY_PREFLIGHT_FILE_INVALID'
    }
    if ($build.FullName.Equals($installed.FullName, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'RECOVERY_PREFLIGHT_DISTINCT_JARS_REQUIRED'
    }
    # Bind the disk snapshot to a single game directory, without persisting its path.
    if ($installed.Directory.Name -cne 'mods' -or
        -not $installed.Directory.Parent.FullName.Equals(
            $options.Directory.FullName, [StringComparison]::OrdinalIgnoreCase) -or
        $options.Name -cne 'options.txt') {
        throw 'RECOVERY_PREFLIGHT_GAME_DIRECTORY_MISMATCH'
    }
    try {
        $buildHash = (Get-FileHash -LiteralPath $build.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $installedHash = (Get-FileHash -LiteralPath $installed.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $fpsLines = @([IO.File]::ReadAllLines($options.FullName) | Where-Object { $_ -cmatch '^maxFps:' })
    } catch { throw 'RECOVERY_PREFLIGHT_READ_FAILED' }
    if ($buildHash -cne $ExpectedBuildJarSha256 -or $installedHash -cne $buildHash) {
        throw 'RECOVERY_PREFLIGHT_JAR_MISMATCH'
    }
    if ($fpsLines.Count -ne 1 -or $fpsLines[0] -cnotmatch '^maxFps:([1-9][0-9]{0,3})$' -or
        [int]$Matches[1] -ne $ExpectedMaxFps) {
        throw 'RECOVERY_PREFLIGHT_FPS_MISMATCH'
    }
    return [ordered]@{
        schema_version = 1
        captured_utc = [DateTimeOffset]::UtcNow.ToString('o')
        product_commit = $ProductCommit
        product_commit_source = 'operator_build_record'
        expected_build_jar_sha256 = $ExpectedBuildJarSha256
        build_jar_sha256 = $buildHash
        installed_jar_sha256 = $installedHash
        baseline_id = $BaselineId
        baseline_source = 'operator_restoration_record'
        max_fps = $ExpectedMaxFps
        max_fps_source = 'options_txt_pre_t0'
        jar_files_match = $true
        same_game_directory = $true
        runtime_jar_and_fps_verified = $false
    }
}

function Test-McmcpRecoveryPreflight {
    param([AllowNull()][object]$Record, [string]$T0Utc)
    # JSON normalization supports both a live ordered dictionary and parsed artifacts.
    try {
        $value = $Record | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json -AsHashtable -DateKind String
        $keys = @('schema_version', 'captured_utc', 'product_commit', 'product_commit_source',
            'expected_build_jar_sha256', 'build_jar_sha256', 'installed_jar_sha256',
            'baseline_id', 'baseline_source', 'max_fps', 'max_fps_source', 'jar_files_match',
            'same_game_directory', 'runtime_jar_and_fps_verified')
        if ($null -eq $value -or $value.Count -ne $keys.Count) { return $false }
        foreach ($key in $keys) { if (-not $value.ContainsKey($key)) { return $false } }
        $captured = [DateTimeOffset]::ParseExact($value.captured_utc, 'o', [Globalization.CultureInfo]::InvariantCulture)
        $t0 = [DateTimeOffset]::Parse($T0Utc, [Globalization.CultureInfo]::InvariantCulture)
        return ($value.schema_version -is [long] -and $value.schema_version -eq 1 -and
            $value.product_commit -is [string] -and $value.product_commit -cmatch '^[0-9a-f]{40}$' -and
            $value.product_commit_source -ceq 'operator_build_record' -and
            $value.expected_build_jar_sha256 -is [string] -and
            $value.expected_build_jar_sha256 -cmatch '^[0-9a-f]{64}$' -and
            $value.build_jar_sha256 -ceq $value.expected_build_jar_sha256 -and
            $value.installed_jar_sha256 -ceq $value.build_jar_sha256 -and
            $value.baseline_id -is [string] -and
            $value.baseline_id -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -and
            $value.baseline_source -ceq 'operator_restoration_record' -and
            $value.max_fps -is [long] -and $value.max_fps -ge 1 -and $value.max_fps -le 1000 -and
            $value.max_fps_source -ceq 'options_txt_pre_t0' -and
            $value.jar_files_match -is [bool] -and $value.jar_files_match -and
            $value.same_game_directory -is [bool] -and $value.same_game_directory -and
            $value.runtime_jar_and_fps_verified -is [bool] -and -not $value.runtime_jar_and_fps_verified -and
            $captured -le $t0 -and ($t0 - $captured).TotalSeconds -le 30)
    } catch { return $false }
}
