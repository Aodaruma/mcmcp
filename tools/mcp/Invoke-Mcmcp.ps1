#requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [ValidateSet('agent_get_state', 'agent_get_observation', 'agent_start_action',
        'agent_get_action', 'agent_cancel_action')][string]$Tool = 'agent_get_state',
    [string]$ArgumentsPath,
    [switch]$Check,
    [ValidateRange(0, 900)][int]$WaitSeconds = 0
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'McmcpClient.ps1')
try {
    $arguments = [ordered]@{}
    if ($ArgumentsPath) {
        if ((Get-Item -LiteralPath $ArgumentsPath).Length -gt 1048576) { throw 'input too large' }
        $arguments = Get-Content -LiteralPath $ArgumentsPath -Raw -Encoding utf8 |
            ConvertFrom-Json -Depth 100 -NoEnumerate
    }
    $reply = Invoke-McmcpClient -TokenPath $TokenPath -Endpoint $Endpoint -Tool $Tool `
        -Arguments $arguments -Check:$Check -WaitSeconds $WaitSeconds
} catch {
    $reply = [ordered]@{ ok = $false; failure_kind = 'input'; diagnostic_code = 'invalid_arguments_file' }
}
[Console]::Out.WriteLine((ConvertTo-CompactJson $reply))
if (-not $reply.ok) { exit 1 }
