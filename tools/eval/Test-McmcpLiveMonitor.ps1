[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$monitorModulePath = Join-Path $PSScriptRoot 'McmcpLiveMonitor.psm1'
Import-Module $monitorModulePath -Force

$safe = ConvertTo-McmcpPublicMonitorText `
    -Text '畑の状態を確認し、次に安全な操作へ進みます。'
if ($safe -cne '畑の状態を確認し、次に安全な操作へ進みます。') {
    throw '安全な日本語要約が保持されませんでした。'
}

$multiline = "一行目を確認します。`n二行目へ進みます。"
if ((ConvertTo-McmcpPublicMonitorText -Text $multiline) -cne $multiline) {
    throw '公開された複数行要約が保持されませんでした。'
}

$publicCases = @(
    "座標 (12, 64, -3) を確認します。",
    '対象は 12, 64, -3 です。',
    '対象は（12 64 -3）です。',
    '詳細は https://example.invalid/path にあります。',
    '{"arguments":{"x":12}}',
    'C:\private\artifact\trace.jsonl を読みます。',
    "/private/artifact/trace.jsonl を読みます。"
)
foreach ($case in $publicCases) {
    $result = ConvertTo-McmcpPublicMonitorText -Text $case
    if ($result -cne $case) {
        throw "Codexの公開表示が意味的に加工されました: $case"
    }
}

$unsafeCases = @(
    "`e[31m赤い文字`e[0m",
    "前半$([char]0x2028)後半",
    "前半$([char]0)後半"
)
foreach ($case in $unsafeCases) {
    $result = ConvertTo-McmcpPublicMonitorText -Text $case
    if ($result -cne '安全でないTerminal制御文字が含まれるため省略しました。') {
        throw 'Terminal制御文字を固定文へ置換できませんでした。'
    }
    if ($result -match '[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\p{Cf}\p{Cs}\p{Zl}\p{Zp}]') {
        throw '制御文字の無害化結果が不正です。'
    }
}

$expectedAliases = [ordered]@{
    agent_get_state = '状態確認'
    agent_get_observation = '周辺観測'
    agent_start_action = 'Action開始'
    agent_get_action = 'Action進行確認'
    agent_cancel_action = 'Action取消'
}
foreach ($entry in $expectedAliases.GetEnumerator()) {
    if ((Get-McmcpPublicToolAlias -ToolName $entry.Key) -cne $entry.Value) {
        throw "Tool aliasが不正です: $($entry.Key)"
    }
}

$unknownRejected = $false
try {
    Get-McmcpPublicToolAlias -ToolName 'unknown_tool' | Out-Null
} catch {
    $unknownRejected = $true
}
if (-not $unknownRejected) { throw '未知Toolがmonitorへ公開されました。' }

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-monitor-selftest-' + [Guid]::NewGuid().ToString('N'))
$monitorLogPath = Join-Path $temporaryRoot 'live-monitor.log'
$failureMonitorLogPath = Join-Path $temporaryRoot 'failed-live-monitor.log'
$originalConsoleOut = [Console]::Out
$capturedConsole = [IO.StringWriter]::new(
    [Globalization.CultureInfo]::InvariantCulture)
$logState = $null
try {
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $logState = New-McmcpLiveMonitorState -Enabled
    [Console]::SetOut($capturedConsole)
    Write-McmcpLiveMonitorText -State $logState -Kind 'Commentary' `
        -Text '座標 (12, 64, -3) と {"status":"ready"} を確認します。'
    Start-McmcpLiveMonitorLog -State $logState -Path $monitorLogPath
    Write-McmcpLiveMonitorTool -State $logState `
        -ToolName 'agent_get_state' -Status 'Completed'
    Stop-McmcpLiveMonitorLog -State $logState
    Start-McmcpLiveMonitorLog -State $logState -Path $monitorLogPath -Append
    Write-McmcpLiveMonitorFixed -State $logState -Event 'runner_completed'
    Stop-McmcpLiveMonitorLog -State $logState
} finally {
    if ($null -ne $logState) {
        try { Stop-McmcpLiveMonitorLog -State $logState } catch { }
    }
    [Console]::SetOut($originalConsoleOut)
}
try {
    $displayLines = @($capturedConsole.ToString().Replace("`r`n", "`n") `
            -split "`n" | Where-Object { $_.Length -gt 0 })
    $loggedLines = @([IO.File]::ReadAllLines($monitorLogPath, [Text.Encoding]::UTF8))
    if ($displayLines.Count -ne 3 -or $loggedLines.Count -ne $displayLines.Count) {
        throw '公開monitor表示とlogの行数が一致しません。'
    }
    for ($index = 0; $index -lt $displayLines.Count; $index++) {
        if (-not $displayLines[$index].StartsWith(
                'MCMCP_MONITOR:', [StringComparison]::Ordinal)) {
            throw 'runner側monitor prefixが固定されていません。'
        }
        $displayed = $displayLines[$index].Substring('MCMCP_MONITOR:'.Length)
        if ($loggedLines[$index] -cne $displayed) {
            throw 'live-monitor.logがTerminal表示と一致しません。'
        }
    }
    if ($loggedLines[0] -cnotlike '*座標 (12, 64, -3) と {"status":"ready"}*' -or
        $loggedLines[1] -cnotlike '*Tool・状態確認・完了' -or
        $loggedLines[2] -cnotlike '*評価runnerが正常終了しました。') {
        throw 'buffer→stream→appendのmonitor log順序が不正です。'
    }
    $logBytes = [IO.File]::ReadAllBytes($monitorLogPath)
    if ($logBytes.Length -ge 3 -and $logBytes[0] -eq 0xEF -and
        $logBytes[1] -eq 0xBB -and $logBytes[2] -eq 0xBF) {
        throw 'live-monitor.logへUTF-8 BOMが混入しました。'
    }

    $failureConsole = [IO.StringWriter]::new(
        [Globalization.CultureInfo]::InvariantCulture)
    $failureState = New-McmcpLiveMonitorState -Enabled
    try {
        [Console]::SetOut($failureConsole)
        Write-McmcpLiveMonitorFixed -State $failureState -Event 'runner_started'
        Start-McmcpLiveMonitorLog -State $failureState -Path $failureMonitorLogPath
        Write-McmcpLiveMonitorFixed -State $failureState -Event 'runner_failed'
        Stop-McmcpLiveMonitorLog -State $failureState
    } finally {
        try { Stop-McmcpLiveMonitorLog -State $failureState } catch { }
        [Console]::SetOut($originalConsoleOut)
    }
    try {
        $failureDisplayLines = @($failureConsole.ToString().Replace("`r`n", "`n") `
                -split "`n" | Where-Object { $_.Length -gt 0 })
        $failureLoggedLines = @([IO.File]::ReadAllLines(
                $failureMonitorLogPath, [Text.Encoding]::UTF8))
        if ($failureDisplayLines.Count -ne 2 -or
            $failureLoggedLines.Count -ne $failureDisplayLines.Count) {
            throw '異常runnerの公開monitor表示とlogの行数が一致しません。'
        }
        for ($index = 0; $index -lt $failureDisplayLines.Count; $index++) {
            if (-not $failureDisplayLines[$index].StartsWith(
                    'MCMCP_MONITOR:', [StringComparison]::Ordinal) -or
                $failureLoggedLines[$index] -cne $failureDisplayLines[$index].Substring(
                    'MCMCP_MONITOR:'.Length)) {
                throw '異常runnerのTerminal表示とlive-monitor.logが一致しません。'
            }
        }
        if ($failureLoggedLines[1] -cnotlike '*評価runnerが終了条件を満たせませんでした。') {
            throw '異常runnerの固定terminal eventが欠落しています。'
        }
    } finally {
        $failureConsole.Dispose()
    }
} finally {
    $capturedConsole.Dispose()
    if ([IO.Directory]::Exists($temporaryRoot)) {
        [IO.Directory]::Delete($temporaryRoot, $true)
    }
}

$runnerPath = Join-Path $PSScriptRoot 'Invoke-McmcpFreshEval.ps1'
$hostPath = Join-Path $PSScriptRoot 'Invoke-McmcpFreshEvalMonitorHost.ps1'
$launcherPath = Join-Path $PSScriptRoot 'Start-McmcpFreshEvalMonitor.ps1'
$runnerText = [IO.File]::ReadAllText($runnerPath).Replace("`r`n", "`n")
$hostText = [IO.File]::ReadAllText($hostPath).Replace("`r`n", "`n")
$launcherText = [IO.File]::ReadAllText($launcherPath).Replace("`r`n", "`n")

# RedirectされたWindows child pwshは既定でCP932になり得る。runnerと同じ初期化を
# byte境界で通し、hostがstrict UTF-8として日本語を完全復元できることを固定する。
$escapedMonitorModulePath = $monitorModulePath.Replace("'", "''")
$encodingChildScript = @'
[Console]::OutputEncoding = [Text.Encoding]::GetEncoding(932)
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $Utf8NoBom
Import-Module '__MONITOR_MODULE__' -Force
$state = New-McmcpLiveMonitorState -Enabled
Write-McmcpLiveMonitorText -State $state -Kind 'Commentary' `
    -Text '日本語のmonitor表示を確認します。'
'@.Replace('__MONITOR_MODULE__', $escapedMonitorModulePath)
$encodingStart = [Diagnostics.ProcessStartInfo]::new()
$encodingStart.FileName = (Get-Process -Id $PID).Path
$encodingStart.UseShellExecute = $false
$encodingStart.CreateNoWindow = $true
$encodingStart.RedirectStandardOutput = $true
$encodingStart.RedirectStandardError = $true
$encodingStart.ArgumentList.Add('-NoLogo')
$encodingStart.ArgumentList.Add('-NoProfile')
$encodingStart.ArgumentList.Add('-NonInteractive')
$encodingStart.ArgumentList.Add('-EncodedCommand')
$encodingStart.ArgumentList.Add([Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($encodingChildScript)))
$encodingProcess = [Diagnostics.Process]::new()
$encodingProcess.StartInfo = $encodingStart
$encodingBytes = [IO.MemoryStream]::new()
try {
    if (-not $encodingProcess.Start()) { throw 'UTF-8 child start failed' }
    $encodingStderr = $encodingProcess.StandardError.ReadToEndAsync()
    $encodingProcess.StandardOutput.BaseStream.CopyTo($encodingBytes)
    $encodingProcess.WaitForExit()
    if (-not $encodingStderr.Wait(5000)) { throw 'UTF-8 child stderr drain timeout' }
    $encodingErrorText = $encodingStderr.GetAwaiter().GetResult()
    if ($encodingProcess.ExitCode -ne 0 -or -not [string]::IsNullOrEmpty($encodingErrorText)) {
        throw 'UTF-8 child failed'
    }
    $strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
    $decodedMonitor = $strictUtf8.GetString($encodingBytes.ToArray())
    if (-not $decodedMonitor.StartsWith(
            'MCMCP_MONITOR:', [StringComparison]::Ordinal) -or
        -not $decodedMonitor.Contains(
            'コメント・日本語のmonitor表示を確認します。',
            [StringComparison]::Ordinal) -or
        $decodedMonitor.Contains([char]0xFFFD)) {
        throw 'redirect childの日本語monitor出力がstrict UTF-8で一致しません。'
    }
} finally {
    $encodingBytes.Dispose()
    $encodingProcess.Dispose()
}

$requiredRunnerContracts = @(
        "summary = 'detailed'",
        "'item/reasoning/summaryPartAdded'",
        "'item/reasoning/textDelta'",
        "'item/reasoning/summaryTextDelta'",
        "`$headers['Mcmcp-Evaluation-Lease'] = `$script:EvaluationLeaseId",
        'EVALUATION_LEASE = $script:EvaluationLeaseId',
        '[Threading.Tasks.Task]::WhenAny',
        '$leaseReady = $leaseTask.IsCompleted',
        'evaluation input lease ended before turn completion',
        'evaluation lease normal terminal reason mismatch',
        "'player_unavailable'", "'endpoint_fault'", "'acquire_abandoned'",
        'Assert-EvaluationLeaseActiveBeforeT0',
        'reasoning item failed safe artifact schema validation',
        'artifact boundary rejected exact evaluation secret',
        'input_owner_none = $script:EvaluationLeaseInputOwnerNone',
        'all_actions_terminal = $script:EvaluationLeaseAllActionsTerminal',
        'process_identity_bound = $script:EvaluationLeaseProcessIdentityBound',
        "`$liveMonitorLogPath = Join-Path `$artifactPath 'live-monitor.log'",
        'Start-McmcpLiveMonitorLog -State $script:LiveMonitorState',
        'Stop-McmcpLiveMonitorLog -State $script:LiveMonitorState',
        'Write-McmcpRunnerFailureEvent',
        "'artifact_live-monitor.log_exact_display'",
        "'hard-building-copy' = [ordered]@{",
        "prompt = 'チェストの材料を自由に加工して、近くにある屋根付きの木造建築を見本に、羊毛の上へ同じ建築をコピーしてください。'",
        'timeout_minutes = 90',
        '$EvaluationProfile = $EvaluationProfiles[$PromptProfile]',
        '$EvaluatorTimeout = [TimeSpan]::FromMinutes([int]$EvaluationProfile[''timeout_minutes''])',
        '$deadline = $startedAt.Add($EvaluatorTimeout)',
        'evaluator_timeout_seconds = [int]$EvaluatorTimeout.TotalSeconds',
        'monitor_module_sha256',
        'monitor_test_sha256',
        'monitor_launcher_sha256',
        'monitor_host_sha256')
foreach ($required in $requiredRunnerContracts) {
    if (-not $runnerText.Contains($required, [StringComparison]::Ordinal)) {
        throw "評価runnerの公開monitor/input lease契約が欠落しています: $required"
    }
}
$runnerEncoding = $runnerText.IndexOf(
    '[Console]::OutputEncoding = $Utf8NoBom', [StringComparison]::Ordinal)
$runnerMonitorImport = $runnerText.IndexOf(
    'Import-Module $monitorModulePath -Force', [StringComparison]::Ordinal)
$runnerFirstMonitor = $runnerText.IndexOf(
    "Write-McmcpLiveMonitorFixed -State `$script:LiveMonitorState -Event 'runner_started'",
    [StringComparison]::Ordinal)
if ($runnerEncoding -lt 0 -or $runnerMonitorImport -lt 0 -or
    $runnerFirstMonitor -lt 0 -or $runnerEncoding -ge $runnerMonitorImport -or
    $runnerEncoding -ge $runnerFirstMonitor) {
    throw 'runner stdoutのUTF-8初期化が最初のmonitor出力より前に固定されていません。'
}
$reasoningPrewriteGuard = $runnerText.IndexOf(
    'if ($method -cin $script:PrivateReasoningNotificationMethods)',
    [StringComparison]::Ordinal)
$reasoningRawWrite = $runnerText.IndexOf(
    '$script:RawWriter.WriteLine($line)',
    [StringComparison]::Ordinal)
if ($reasoningPrewriteGuard -lt 0 -or $reasoningRawWrite -lt 0 -or
    $reasoningPrewriteGuard -ge $reasoningRawWrite) {
    throw 'private reasoning notificationの書込み前拒否が固定されていません。'
}
$rawSecretGuard = $runnerText.IndexOf(
    'Assert-NoEvaluationSecretForArtifactText -Text $line',
    [StringComparison]::Ordinal)
if ($rawSecretGuard -lt 0 -or $rawSecretGuard -ge $reasoningRawWrite) {
    throw 'app-server raw lineの実credential書込み前拒否が固定されていません。'
}
$bridgeSecretGuard = $runnerText.IndexOf(
    'Assert-NoEvaluationSecretForArtifactText -Text $recordJson',
    [StringComparison]::Ordinal)
$bridgeRawWrite = $runnerText.IndexOf(
    '$script:BridgeWriter.WriteLine($recordJson)',
    [StringComparison]::Ordinal)
if ($bridgeSecretGuard -lt 0 -or $bridgeRawWrite -lt 0 -or
    $bridgeSecretGuard -ge $bridgeRawWrite) {
    throw 'bridge recordの実credential書込み前拒否が固定されていません。'
}
$preliminaryReadinessCall = $runnerText.IndexOf(
    "`n    `$preliminaryT0Readiness = Invoke-McmcpReadinessCheck -Phase 'preliminary'`n",
    [StringComparison]::Ordinal)
$acquireCall = $runnerText.IndexOf(
    "`n    Start-EvaluationTurnLease`n", [StringComparison]::Ordinal)
$authoritativeReadinessCall = $runnerText.IndexOf(
    "`n    `$t0Readiness = Invoke-McmcpReadinessCheck -Phase 'T0'`n",
    [StringComparison]::Ordinal)
$t0Record = $runnerText.IndexOf(
    "`n            event = 't0'", [StringComparison]::Ordinal)
$releaseCall = $runnerText.IndexOf(
    "`n    Stop-EvaluationTurnLease -Reason `$evaluationLeaseReleaseReason`n",
    [StringComparison]::Ordinal)
$stdinClose = $runnerText.IndexOf(
    "`n    `$script:CodexProcess.StandardInput.Close()", [StringComparison]::Ordinal)
if ($preliminaryReadinessCall -lt 0 -or $acquireCall -lt 0 -or
    $authoritativeReadinessCall -lt 0 -or $t0Record -lt 0 -or
    $preliminaryReadinessCall -ge $acquireCall -or
    $acquireCall -ge $authoritativeReadinessCall -or
    $authoritativeReadinessCall -ge $t0Record) {
    throw 'preliminary readiness→lease acquire→authoritative readiness→T0順序が固定されていません。'
}
if ($releaseCall -lt 0 -or $stdinClose -lt 0 -or $releaseCall -ge $stdinClose) {
    throw 'lease terminal確認がCodex process終了待機より前に固定されていません。'
}
if (-not $hostText.Contains('.StandardOutput.ReadLine()', [StringComparison]::Ordinal) -or
    -not $hostText.Contains('.WaitForExit()', [StringComparison]::Ordinal) -or
    $hostText.Contains('Start-Sleep', [StringComparison]::Ordinal)) {
    throw 'monitor hostがevent/process wait契約を満たしていません。'
}
$hostEncoding = $hostText.IndexOf(
    '[Console]::OutputEncoding = $Utf8NoBom', [StringComparison]::Ordinal)
$hostOutput = $hostText.IndexOf(
    '[Console]::Out.WriteLine($publicLine)', [StringComparison]::Ordinal)
if ($hostEncoding -lt 0 -or $hostOutput -lt 0 -or $hostEncoding -ge $hostOutput -or
    -not $hostText.Contains(
        '$processStart.StandardOutputEncoding = $Utf8NoBom',
        [StringComparison]::Ordinal) -or
    -not $hostText.Contains(
        '$processStart.StandardErrorEncoding = $Utf8NoBom',
        [StringComparison]::Ordinal)) {
    throw 'monitor hostのUTF-8入出力境界が固定されていません。'
}
$hostOwnedOutputTokens = @(
    '[Console]::Error.WriteLine', 'Write-Host', 'Write-Output', 'Write-Error', 'Out-Host')
if (@($hostOwnedOutputTokens | Where-Object {
            $hostText.Contains($_, [StringComparison]::Ordinal)
        }).Count -gt 0 -or
    ([regex]::Matches($hostText, '\[Console\]::Out\.WriteLine\(').Count -ne 1) -or
    -not $hostText.Contains('[Console]::Out.WriteLine($publicLine)',
        [StringComparison]::Ordinal)) {
    throw 'monitor hostがrunner公開行以外のTerminal出力を追加しています。'
}
$removedSemanticFilters = @(
    '(?:https?|ftp|file|app)://',
    '(?:[a-z]:[\\/]|\\\\)',
    '\bagent_(?:get|start|cancel)')
foreach ($filter in $removedSemanticFilters) {
    if ($hostText.Contains($filter, [StringComparison]::Ordinal)) {
        throw 'monitor hostがCodexの公開本文を意味的に加工しています。'
    }
}
if (-not $launcherText.Contains('.WaitForExit()', [StringComparison]::Ordinal) -or
    $launcherText.Contains("'-NoExit'", [StringComparison]::Ordinal) -or
    $launcherText.Contains('[Console]::Out.WriteLine', [StringComparison]::Ordinal) -or
    @($hostOwnedOutputTokens | Where-Object {
            $launcherText.Contains($_, [StringComparison]::Ordinal)
        }).Count -gt 0) {
    throw 'visible launcherがchild終了連動契約を満たしていません。'
}
if (-not $launcherText.Contains(
        "[ValidateSet('short-regression', 'full-cycle', 'hard-building-copy')]",
        [StringComparison]::Ordinal)) {
    throw 'visible launcherがhard-building-copy固定profileを受理しません。'
}

$passed = 15 + $publicCases.Count + $unsafeCases.Count + $expectedAliases.Count + `
    $requiredRunnerContracts.Count + $removedSemanticFilters.Count + 8
Write-Host "公開monitor/評価lease self-test: $passed/$passed passed"
