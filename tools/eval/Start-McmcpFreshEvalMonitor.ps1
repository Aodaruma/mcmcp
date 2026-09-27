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
    [ValidateSet('short-regression', 'full-cycle', 'warehouse-smelt', 'hard-building-copy', 'container-inspect-recovery',
        'tunnel-straight16', 'tunnel-straight160', 'tunnel-branches', 'tunnel-hazard')]
    [string]$PromptProfile,

    [string]$ProductCommit,
    [string]$ExpectedBuildJarSha256,
    [string]$BuildJarPath,
    [string]$InstalledJarPath,
    [string]$OptionsPath,
    [int]$ExpectedMaxFps,

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
    $parameterValues = [ordered]@{
        Model = $Model
        ReasoningEffort = $ReasoningEffort
        BaselineId = $BaselineId
        ArtifactDirectory = $ArtifactDirectory
        TokenPath = $TokenPath
        PromptProfile = $PromptProfile
        Endpoint = $Endpoint
    }
    if ($PromptProfile -ceq 'container-inspect-recovery') {
        $parameterValues.ProductCommit = $ProductCommit
        $parameterValues.ExpectedBuildJarSha256 = $ExpectedBuildJarSha256
        $parameterValues.BuildJarPath = $BuildJarPath
        $parameterValues.InstalledJarPath = $InstalledJarPath
        $parameterValues.OptionsPath = $OptionsPath
        $parameterValues.ExpectedMaxFps = $ExpectedMaxFps
    }
    $parameterJson = $parameterValues | ConvertTo-Json -Depth 5 -Compress
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
