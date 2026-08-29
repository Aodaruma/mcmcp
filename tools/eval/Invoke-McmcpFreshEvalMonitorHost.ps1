[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$EncodedParameters
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $Utf8NoBom

$monitorPrefix = 'MCMCP_MONITOR:'
$runnerProcess = $null
$stderrTask = $null

try {
    try { $Host.UI.RawUI.WindowTitle = 'MCMCP 評価monitor' } catch { }

    $json = [Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($EncodedParameters))
    $parameters = $json | ConvertFrom-Json -Depth 10
    $expectedNames = @(
        'Model', 'ReasoningEffort', 'BaselineId', 'ArtifactDirectory',
        'TokenPath', 'PromptProfile', 'Endpoint'
    )
    $actualNames = @($parameters.PSObject.Properties.Name)
    if ($actualNames.Count -ne $expectedNames.Count) {
        throw 'parameter contract mismatch'
    }
    foreach ($name in $expectedNames) {
        if (@($actualNames | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw 'parameter contract mismatch'
        }
    }

    $runnerPath = Join-Path $PSScriptRoot 'Invoke-McmcpFreshEval.ps1'
    if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
        throw 'runner missing'
    }
    $powerShellExecutable = (Get-Process -Id $PID).Path
    $processStart = [Diagnostics.ProcessStartInfo]::new()
    $processStart.FileName = $powerShellExecutable
    $processStart.WorkingDirectory = $PSScriptRoot
    $processStart.UseShellExecute = $false
    $processStart.CreateNoWindow = $true
    $processStart.RedirectStandardOutput = $true
    $processStart.RedirectStandardError = $true
    $processStart.StandardOutputEncoding = $Utf8NoBom
    $processStart.StandardErrorEncoding = $Utf8NoBom
    foreach ($argument in @(
            '-NoLogo', '-NoProfile', '-NonInteractive', '-File', $runnerPath,
            '-Model', [string]$parameters.Model,
            '-ReasoningEffort', [string]$parameters.ReasoningEffort,
            '-BaselineId', [string]$parameters.BaselineId,
            '-ArtifactDirectory', [string]$parameters.ArtifactDirectory,
            '-TokenPath', [string]$parameters.TokenPath,
            '-PromptProfile', [string]$parameters.PromptProfile,
            '-Endpoint', [string]$parameters.Endpoint,
            '-LiveMonitor')) {
        $processStart.ArgumentList.Add($argument)
    }

    $runnerProcess = [Diagnostics.Process]::new()
    $runnerProcess.StartInfo = $processStart
    if (-not $runnerProcess.Start()) { throw 'runner start failed' }
    $stderrTask = $runnerProcess.StandardError.ReadToEndAsync()

    # ReadLineは新しい公開eventまたはprocess終了までblockする。時刻pollingはしない。
    while ($null -ne ($line = $runnerProcess.StandardOutput.ReadLine())) {
        if (-not $line.StartsWith($monitorPrefix, [StringComparison]::Ordinal)) { continue }
        $publicLine = $line.Substring($monitorPrefix.Length)
        # formatter外からprefixを偽装されてもTerminal制御文字は通さない。
        # Codexが公開したcommentary / reasoning summaryの通常文字列はそのまま表示する。
        if ($publicLine -match '[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\p{Cf}\p{Cs}\p{Zl}\p{Zp}]') {
            continue
        }
        [Console]::Out.WriteLine($publicLine)
        [Console]::Out.Flush()
    }

    $runnerProcess.WaitForExit()
    if (-not $stderrTask.Wait(5000)) { throw 'stderr drain timeout' }
    $stderrTask.GetAwaiter().GetResult() | Out-Null
    $exitCode = $runnerProcess.ExitCode
    exit $exitCode
} catch {
    exit 3
} finally {
    if ($null -ne $runnerProcess) {
        try {
            if (-not $runnerProcess.HasExited) { $runnerProcess.Kill($true) }
        } catch { }
        try { $runnerProcess.Dispose() } catch { }
    }
    $json = $null
    $parameters = $null
}
