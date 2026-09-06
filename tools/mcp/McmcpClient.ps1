# Fixed fallback for public MCMCP tools; never constructs a gameplay Action.
. (Join-Path $PSScriptRoot 'McmcpTransport.ps1')

function New-McmcpClientFailure {
    param([string]$Kind, [string]$Code)
    $failure = [InvalidOperationException]::new('MCMCP client request failed')
    $failure.Data['failure_kind'] = $Kind
    $failure.Data['diagnostic_code'] = $Code
    return $failure
}

function Assert-McmcpSchema {
    param([object]$Value, [object]$Schema, [string]$Code)
    try {
        $valid = Test-Json -Json (ConvertTo-CompactJson $Value) `
            -Schema (ConvertTo-CompactJson $Schema) -ErrorAction Stop
        if (-not $valid) { throw 'schema mismatch' }
    } catch {
        throw (New-McmcpClientFailure 'protocol_validation' $Code)
    }
}

function Invoke-McmcpClient {
    param(
        [Parameter(Mandatory)][string]$TokenPath,
        [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
        [ValidateSet('agent_get_state', 'agent_get_observation', 'agent_start_action',
            'agent_get_action', 'agent_cancel_action')][string]$Tool = 'agent_get_state',
        [object]$Arguments = ([ordered]@{}),
        [switch]$Check,
        [ValidateRange(0, 900)][int]$WaitSeconds = 0
    )
    $bearer = $null
    $actionId = $null
    try {
        if ($WaitSeconds -gt 0 -and ($Check -or $Tool -cne 'agent_start_action')) {
            throw (New-McmcpClientFailure 'input' 'wait_requires_start_action')
        }
        try {
            $catalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../docs/MCMCP_MCP_Tool_Catalog.json') `
                -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
            $definition = @($catalog.tools | Where-Object name -CEQ $Tool)[0]
        } catch {
            throw (New-McmcpClientFailure 'input' 'catalog_unavailable')
        }
        if (-not $Check) {
            Assert-McmcpSchema $Arguments $definition.inputSchema 'invalid_tool_arguments'
        }
        try {
            $tokenFile = Get-Item -LiteralPath $TokenPath -Force -ErrorAction Stop
            if ($tokenFile.PSIsContainer -or $tokenFile.Length -gt 256 -or
                ($tokenFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'invalid token file'
            }
            $bearer = [IO.File]::ReadAllText($tokenFile.FullName, [Text.Encoding]::UTF8).Trim()
            if ($bearer -cnotmatch '^[A-Za-z0-9_-]{43,256}$') { throw 'invalid token' }
        } catch {
            throw (New-McmcpClientFailure 'connection' 'token_unavailable')
        }
        $requestId = 0L
        $lastRequest = 0L
        # Shared evaluator transport. This local closure carries no saved session or credentials.
        function Send-Request([string]$Method, [string]$Name, [object]$Params, [int]$Timeout) {
            $now = [Diagnostics.Stopwatch]::GetTimestamp()
            $elapsed = ($now - $lastRequest) * 1000.0 / [Diagnostics.Stopwatch]::Frequency
            if ($lastRequest -gt 0 -and $elapsed -lt 60) {
                Start-Sleep -Milliseconds ([int][Math]::Ceiling(60 - $elapsed))
            }
            Set-Variable -Name lastRequest -Value ([Diagnostics.Stopwatch]::GetTimestamp()) -Scope 1
            Set-Variable -Name requestId -Value ($requestId + 1L) -Scope 1
            $response = Invoke-McmcpTransportRequest -Endpoint $Endpoint -Bearer $bearer `
                -RequestId $requestId -Method $Method -ToolName $Name -Parameters $Params `
                -TimeoutSeconds $Timeout
            if ((ConvertTo-CompactJson $response).Contains($bearer, [StringComparison]::Ordinal)) {
                throw (New-McmcpClientFailure 'protocol_validation' 'secret_blocked')
            }
            if ($null -ne (Get-Property $response 'error')) {
                throw (New-McmcpClientFailure 'jsonrpc' 'jsonrpc_error')
            }
            return $response.result
        }
        $meta = Get-McpMeta -ClientName 'mcmcp-fallback'
        $discover = Send-Request 'server/discover' $null @{ _meta = $meta } 15
        try {
            Assert-McmcpServerMeta $discover 'server/discover'
            if ($discover.resultType -cne 'complete' -or
                -not (Test-IsArrayValue (Get-Property $discover 'supportedVersions').Value) -or
                '2026-07-28' -cnotin $discover.supportedVersions) { throw 'version mismatch' }
        } catch {
            throw (New-McmcpClientFailure 'protocol_validation' 'discovery_mismatch')
        }
        if ($Check) {
            $listed = Send-Request 'tools/list' $null @{ _meta = $meta } 15
            try {
                Assert-McmcpServerMeta $listed 'tools/list'
                if ($listed.resultType -cne 'complete' -or @($listed.tools).Count -ne 5 -or
                    (@($listed.tools.name | Sort-Object) -join ',') -cne
                    (@($catalog.tools.name | Sort-Object) -join ',')) { throw 'tool mismatch' }
            } catch {
                throw (New-McmcpClientFailure 'protocol_validation' 'tool_catalog_mismatch')
            }
            return [ordered]@{ ok = $true; connection = 'reachable'; tool_count = 5 }
        }
        $clock = [Diagnostics.Stopwatch]::StartNew()
        do {
            $timeout = 35
            if ($Tool -ceq 'agent_get_action') {
                $wait = Get-PropertyValue $Arguments 'wait_timeout_ms'
                $timeout = [Math]::Max(2, [Math]::Ceiling([double]$wait / 1000) + 2)
            }
            if ($null -ne $actionId) {
                $remaining = $WaitSeconds - $clock.Elapsed.TotalSeconds
                if ($remaining -lt 3) {
                    throw (New-McmcpClientFailure 'deadline' 'action_wait_timeout')
                }
                $Arguments = [ordered]@{ action_id = $actionId
                    wait_timeout_ms = [int][Math]::Min(25000, [Math]::Floor(($remaining - 2) * 1000)) }
                $timeout = [int][Math]::Min(27, [Math]::Floor($remaining))
            }
            $result = Send-Request 'tools/call' $Tool `
                @{ _meta = $meta; name = $Tool; arguments = $Arguments } $timeout
            try { Assert-McmcpToolResult $result 'tools/call' } catch {
                throw (New-McmcpClientFailure 'protocol_validation' 'invalid_tool_result')
            }
            if ($result.isError) {
                $rejection = [ordered]@{ ok = $false; failure_kind = 'tool'; diagnostic_code = 'tool_rejected'
                    error = ($result.content[0].text | ConvertFrom-Json -Depth 10) }
                # TextContent contains a second JSON document; decoding escapes can reveal a secret.
                if ((ConvertTo-CompactJson $rejection).Contains($bearer, [StringComparison]::Ordinal)) {
                    throw (New-McmcpClientFailure 'protocol_validation' 'secret_blocked')
                }
                if ($null -ne $actionId) { $rejection.action_id = $actionId }
                return $rejection
            }
            $data = $result.structuredContent
            $definition = @($catalog.tools | Where-Object name -CEQ $Tool)[0]
            Assert-McmcpSchema $data $definition.outputSchema 'invalid_success_schema'
            if ($Tool -cin @('agent_get_action', 'agent_cancel_action') -and
                $data.action_id -cne (Get-PropertyValue $Arguments 'action_id')) {
                throw (New-McmcpClientFailure 'protocol_validation' 'action_id_mismatch')
            }
            if ($Tool -ceq 'agent_start_action' -and $WaitSeconds -gt 0 -and $data.state -ceq 'queued') {
                # Only the schema-validated successful start may supply this ID.
                $actionId = $data.action_id
                $Tool = 'agent_get_action'
            } elseif ($null -eq $actionId -or $data.state -cin @('succeeded', 'failed', 'cancelled')) {
                return [ordered]@{ ok = $true; result = $data }
            }
        } while ($true)
    } catch {
        $failure = [ordered]@{ ok = $false; failure_kind = 'internal'; diagnostic_code = 'client_failed' }
        if ($_.Exception.Data.Contains('failure_kind')) {
            $failure.failure_kind = $_.Exception.Data['failure_kind']
            $failure.diagnostic_code = $_.Exception.Data['diagnostic_code']
        }
        if ($_.Exception.Data.Contains('http_status')) { $failure.http_status = $_.Exception.Data['http_status'] }
        if ($null -ne $actionId) { $failure.action_id = $actionId }
        return $failure
    } finally {
        $bearer = $null
    }
}
