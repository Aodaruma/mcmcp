# capability gateの共通入口。建築シナリオや引数を読み込まない。
# dot-sourceごとに従来のscript状態を初期化する。関数は呼出元と同じscopeに置き、
# 各gateのGet-McpMetaとテストのtransport差替えを維持する。
# 読込時には通信、credential読取、artifact作成、ゲーム操作を行わない。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $script:Utf8NoBom
Add-Type -AssemblyName System.Net.Http

$script:ProtocolVersion = '2026-07-28'
$script:AllowedTools = @(
    'agent_get_state',
    'agent_get_observation',
    'agent_start_action',
    'agent_get_action',
    'agent_cancel_action'
)
$script:TerminalStates = @('succeeded', 'failed', 'cancelled')
$script:RequestId = 0L
$script:LastRequestTimestamp = 0L
$script:Bearer = $null
$script:ActiveActionId = $null
$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ToolTransport = $null
$script:DelayTransport = $null

function ConvertTo-CompactJson {
    param([AllowNull()][object]$Value)
    ConvertTo-Json -InputObject $Value -Depth 100 -Compress
}

function Get-ObjectProperty {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return $Object[$Name] }
        return $null
    }
    $property = @($Object.PSObject.Properties | Where-Object Name -CEQ $Name)
    if ($property.Count -eq 1) { return $property[0].Value }
    return $null
}

function Add-GateEvent {
    param(
        [Parameter(Mandatory)][string]$Event,
        [AllowNull()][Collections.IDictionary]$Detail
    )
    $entry = [ordered]@{
        utc = [DateTimeOffset]::UtcNow.ToString('O')
        event = $Event
    }
    if ($null -ne $Detail) {
        foreach ($item in $Detail.GetEnumerator()) { $entry[$item.Key] = $item.Value }
    }
    $script:GateEvents.Add($entry)
}

. (Join-Path $PSScriptRoot 'McmcpCapabilityGateTransport.ps1')
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateObservation.ps1')
. (Join-Path $PSScriptRoot 'McmcpCapabilityGateAction.ps1')
