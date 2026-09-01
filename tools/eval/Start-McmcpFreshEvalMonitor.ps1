[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('gpt-5.6-sol', 'gpt-5.6-luna')]
    [string]$Model,

    [Parameter(Mandatory)]
    [ValidateSet('high', 'xhigh')]
    [string]$ReasoningEffort,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$BaselineId,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TokenPath,

    [Parameter(Mandatory)]
    [ValidateSet('short-regression', 'full-cycle', 'hard-building-copy')]
    [string]$PromptProfile,

    [string]$Endpoint = 'http://127.0.0.1:8765/mcp'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$visibleProcess = $null
try {
    $hostScript = Join-Path $PSScriptRoot 'Invoke-McmcpFreshEvalMonitorHost.ps1'
    if (-not (Test-Path -LiteralPath $hostScript -PathType Leaf)) {
        throw 'monitor host missing'
    }
    $parameterJson = [ordered]@{
        Model = $Model
        ReasoningEffort = $ReasoningEffort
        BaselineId = $BaselineId
        ArtifactDirectory = $ArtifactDirectory
        TokenPath = $TokenPath
        PromptProfile = $PromptProfile
        Endpoint = $Endpoint
    } | ConvertTo-Json -Depth 5 -Compress
    $encodedParameters = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($parameterJson))

    $escapedHostScript = $hostScript.Replace("'", "''", [StringComparison]::Ordinal)
    $encodedCommandText = "& '$escapedHostScript' -EncodedParameters '$encodedParameters'; exit `$LASTEXITCODE"
    $encodedCommand = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($encodedCommandText))
    $powerShellExecutable = (Get-Process -Id $PID).Path

    # ユーザーが明示的に希望したvisible console。-NoExitは付けず、host完了時に閉じる。
    $visibleProcess = Start-Process -FilePath $powerShellExecutable `
        -ArgumentList @('-NoLogo', '-NoProfile', '-NonInteractive',
            '-EncodedCommand', $encodedCommand) -PassThru
    $visibleProcess.WaitForExit()
    exit $visibleProcess.ExitCode
} catch {
    exit 3
} finally {
    if ($null -ne $visibleProcess) {
        try { $visibleProcess.Dispose() } catch { }
    }
    $parameterJson = $null
    $encodedParameters = $null
    $encodedCommandText = $null
    $encodedCommand = $null
}
