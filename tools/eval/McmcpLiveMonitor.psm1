Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MonitorPrefix = 'MCMCP_MONITOR:'
$script:OmittedText = '安全でないTerminal制御文字が含まれるため省略しました。'
$script:FixedMessages = [ordered]@{
    runner_started = '評価runnerを開始しました。'
    app_server_started = '評価用Codexを起動しました。'
    initialized = 'Codexとの接続を初期化しました。'
    login_ok = '評価用認証を確認しました。'
    config_ok = '隔離設定を確認しました。'
    preflight_ok = 'MCPの事前確認が完了しました。'
    thread_ready = '評価セッションの準備が完了しました。'
    evaluation_lease_acquired = '推論中の入力ロックを開始しました。'
    t0 = 'T0に到達しました。ここから自律評価を開始します。'
    turn_started = '評価モデルが推論を開始しました。'
    turn_completed = '評価モデルの応答が完了しました。'
    evaluation_lease_released = '入力ロックの解除を確認しました。'
    process_exited = '評価用Codexの終了を確認しました。'
    audit_started = '監査を開始しました。'
    audit_passed = '監査に合格しました。'
    audit_failed = '監査に合格しませんでした。'
    runner_failed = '評価runnerが終了条件を満たせませんでした。'
    runner_completed = '評価runnerが正常終了しました。'
}
$script:ToolAliases = [ordered]@{
    agent_get_state = '状態確認'
    agent_get_observation = '周辺観測'
    agent_start_action = 'Action開始'
    agent_get_action = 'Action進行確認'
    agent_cancel_action = 'Action取消'
}

function New-McmcpLiveMonitorState {
    [CmdletBinding()]
    param([switch]$Enabled)

    return [pscustomobject]@{
        Enabled = [bool]$Enabled
        Stopwatch = [Diagnostics.Stopwatch]::StartNew()
        BufferedLines = [Collections.Generic.List[string]]::new()
        LogWriter = $null
    }
}

function Start-McmcpLiveMonitorLog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Path,
        [switch]$Append
    )
    if (-not [bool]$State.Enabled) { return }
    if ($null -ne $State.LogWriter) {
        throw '公開monitor logは既に開始されています。'
    }
    $writer = [IO.StreamWriter]::new(
        $Path, [bool]$Append, [Text.UTF8Encoding]::new($false))
    try {
        foreach ($line in $State.BufferedLines) {
            $writer.WriteLine([string]$line)
        }
        $writer.Flush()
        $State.BufferedLines.Clear()
        $State.LogWriter = $writer
    } catch {
        $writer.Dispose()
        throw
    }
}

function Stop-McmcpLiveMonitorLog {
    [CmdletBinding()]
    param([Parameter(Mandatory)][object]$State)

    if ($null -eq $State.LogWriter) { return }
    try {
        $State.LogWriter.Flush()
    } finally {
        $State.LogWriter.Dispose()
        $State.LogWriter = $null
    }
}

function ConvertTo-McmcpPublicMonitorText {
    [CmdletBinding()]
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }

    # Codexがpublic itemとして確定した本文は意味的に加工しない。
    # Terminal command injectionに使える制御文字だけは内容ごと公開しない。
    $candidate = $Text.Replace("`r`n", "`n", [StringComparison]::Ordinal)
    $candidate = $candidate.Replace("`r", "`n", [StringComparison]::Ordinal)
    if ($candidate -match '[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\p{Cf}\p{Cs}\p{Zl}\p{Zp}]') {
        return $script:OmittedText
    }
    return $candidate
}

function Get-McmcpPublicToolAlias {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ToolName)

    if (-not $script:ToolAliases.Contains($ToolName)) {
        throw '公開monitorで許可されていないToolです。'
    }
    return [string]$script:ToolAliases[$ToolName]
}

function Format-McmcpMonitorLine {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Message
    )
    $elapsed = $State.Stopwatch.Elapsed
    $hours = [int][Math]::Floor($elapsed.TotalHours)
    return ('[+{0:D2}:{1:D2}:{2:D2}] {3}' -f `
            $hours, $elapsed.Minutes, $elapsed.Seconds, $Message)
}

function Write-McmcpMonitorLine {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not [bool]$State.Enabled) { return }
    $line = Format-McmcpMonitorLine -State $State -Message $Message
    [Console]::Out.WriteLine($script:MonitorPrefix + $line)
    [Console]::Out.Flush()
    if ($null -ne $State.LogWriter) {
        $State.LogWriter.WriteLine($line)
        $State.LogWriter.Flush()
    } else {
        $State.BufferedLines.Add($line)
    }
}

function Write-McmcpLiveMonitorFixed {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Event
    )
    if (-not $script:FixedMessages.Contains($Event)) {
        throw '公開monitorで許可されていない固定eventです。'
    }
    Write-McmcpMonitorLine -State $State -Message ([string]$script:FixedMessages[$Event])
}

function Write-McmcpLiveMonitorTool {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$ToolName,
        [Parameter(Mandatory)]
        [ValidateSet('Started', 'Completed', 'Rejected', 'Failed')]
        [string]$Status
    )
    $alias = Get-McmcpPublicToolAlias -ToolName $ToolName
    $statusText = switch ($Status) {
        'Started' { '開始' }
        'Completed' { '完了' }
        'Rejected' { '期限により見送り' }
        'Failed' { '転送失敗' }
    }
    Write-McmcpMonitorLine -State $State -Message ("Tool・${alias}・${statusText}")
}

function Write-McmcpLiveMonitorText {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][ValidateSet('ReasoningSummary', 'Commentary')]
        [string]$Kind,
        [AllowNull()][string]$Text
    )
    if (-not [bool]$State.Enabled) { return }
    $publicText = ConvertTo-McmcpPublicMonitorText -Text $Text
    if ([string]::IsNullOrWhiteSpace($publicText)) { return }
    $label = if ($Kind -eq 'ReasoningSummary') { '推論要約' } else { 'コメント' }
    $lines = @($publicText -split "`n", -1)
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $lineLabel = if ($index -eq 0) { "${label}・" } else { "${label}・（続き）" }
        Write-McmcpMonitorLine -State $State -Message ("${lineLabel}$($lines[$index])")
    }
}

Export-ModuleMember -Function @(
    'New-McmcpLiveMonitorState',
    'Start-McmcpLiveMonitorLog',
    'Stop-McmcpLiveMonitorLog',
    'ConvertTo-McmcpPublicMonitorText',
    'Get-McmcpPublicToolAlias',
    'Write-McmcpLiveMonitorFixed',
    'Write-McmcpLiveMonitorTool',
    'Write-McmcpLiveMonitorText'
)
