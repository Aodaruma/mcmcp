# gate専用の既存HTTP/JSON-RPC通信、固定5 Tool検証、mock transport。通信契約は変更しない。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-construction-capability-gate'
            version = '1'
        }
    }
}

function Wait-McpRequestSlot {
    $now = [Diagnostics.Stopwatch]::GetTimestamp()
    if ($script:LastRequestTimestamp -gt 0) {
        $elapsed = (($now - $script:LastRequestTimestamp) * 1000.0) /
            [Diagnostics.Stopwatch]::Frequency
        if ($elapsed -lt 60) {
            Start-Sleep -Milliseconds ([int][Math]::Ceiling(60 - $elapsed))
        }
    }
    $script:LastRequestTimestamp = [Diagnostics.Stopwatch]::GetTimestamp()
}

function Invoke-NoProxyJsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][Collections.IDictionary]$Headers,
        [Parameter(Mandatory)][string]$Body,
        [ValidateRange(1, 35)][int]$TimeoutSeconds
    )
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, $Uri)
    $response = $null
    try {
        foreach ($header in $Headers.GetEnumerator()) {
            if (-not $request.Headers.TryAddWithoutValidation(
                    [string]$header.Key, [string]$header.Value)) {
                throw "HTTP request rejected header $($header.Key)"
            }
        }
        $request.Content = [Net.Http.StringContent]::new(
            $Body, [Text.Encoding]::UTF8, 'application/json')
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $contentType = if ($null -eq $response.Content.Headers.ContentType) {
            ''
        } else {
            [string]$response.Content.Headers.ContentType
        }
        $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "MCP HTTP request failed with status $([int]$response.StatusCode)"
        }
        try {
            $json = ConvertFrom-Json -InputObject $responseBody
        } catch {
            throw 'MCP HTTP response was not valid JSON'
        }
        return [pscustomobject]@{
            body = $json
            content_type = $contentType
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Invoke-LiveMcpRequest {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('server/discover', 'tools/list', 'tools/call')]
        [string]$Method,
        [Parameter(Mandatory)][object]$Parameters,
        [AllowNull()][string]$ToolName,
        [ValidateRange(1, 35)][int]$TimeoutSeconds = 15
    )
    Wait-McpRequestSlot
    $script:RequestId++
    $requestId = $script:RequestId
    $headers = @{
        Authorization = "Bearer $($script:Bearer)"
        Accept = 'application/json, text/event-stream'
        'MCP-Protocol-Version' = $script:ProtocolVersion
        'Mcp-Method' = $Method
    }
    if (-not [string]::IsNullOrWhiteSpace($ToolName)) {
        $headers['Mcp-Name'] = $ToolName
    }
    $transport = Invoke-NoProxyJsonPost -Uri $Endpoint -Headers $headers `
        -TimeoutSeconds $TimeoutSeconds `
        -Body (ConvertTo-CompactJson ([ordered]@{
                jsonrpc = '2.0'; id = $requestId; method = $Method; params = $Parameters
            }))
    $response = $transport.body
    $contentType = [string]$transport.content_type
    if ($contentType -notmatch '(?i)^application/json(?:\s*;\s*charset=(?:utf-8|"utf-8"))?\s*$') {
        throw "$Method returned an invalid Content-Type"
    }
    if ((Get-ObjectProperty $response 'jsonrpc') -cne '2.0' -or
        (Get-ObjectProperty $response 'id') -ne $requestId) {
        throw "$Method returned an invalid JSON-RPC envelope"
    }
    $error = Get-ObjectProperty $response 'error'
    if ($null -ne $error) {
        throw "$Method returned JSON-RPC error code=$(Get-ObjectProperty $error 'code')"
    }
    $result = Get-ObjectProperty $response 'result'
    if ($null -eq $result) { throw "$Method returned no result" }
    return $result
}

function Assert-FixedFiveToolSurface {
    $meta = Get-McpMeta
    $discover = Invoke-LiveMcpRequest -Method 'server/discover' `
        -Parameters ([ordered]@{ _meta = $meta }) -ToolName $null
    $versions = @(Get-ObjectProperty $discover 'supportedVersions')
    if ((Get-ObjectProperty $discover 'resultType') -cne 'complete' -or
        $versions.Count -ne 1 -or $versions[0] -cne $script:ProtocolVersion) {
        throw 'server/discover did not advertise the required protocol exclusively'
    }
    $list = Invoke-LiveMcpRequest -Method 'tools/list' `
        -Parameters ([ordered]@{ _meta = $meta }) -ToolName $null
    if ((Get-ObjectProperty $list 'resultType') -cne 'complete' -or
        (Get-ObjectProperty $list 'ttlMs') -ne 0 -or
        (Get-ObjectProperty $list 'cacheScope') -cne 'private') {
        throw 'tools/list metadata is not complete/private'
    }
    $tools = @(Get-ObjectProperty $list 'tools')
    if ($tools.Count -ne $script:AllowedTools.Count) {
        throw "tools/list returned $($tools.Count) tools instead of five"
    }
    for ($index = 0; $index -lt $script:AllowedTools.Count; $index++) {
        if ((Get-ObjectProperty $tools[$index] 'name') -cne $script:AllowedTools[$index]) {
            throw "tools/list fixed order mismatch at index $index"
        }
    }
    Add-GateEvent -Event 'fixed_five_surface_verified' -Detail ([ordered]@{
            protocol_version = $script:ProtocolVersion
            tools = @($script:AllowedTools)
        })
}

function Invoke-GateTool {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][Collections.IDictionary]$Arguments,
        [switch]$ReturnDomainError
    )
    if ($Tool -cnotin $script:AllowedTools) {
        throw "capability gate rejected a non-public tool: $Tool"
    }
    Add-GateEvent -Event 'tool_call_started' -Detail ([ordered]@{ tool = $Tool })
    if ($null -ne $script:ToolTransport) {
        $structured = & $script:ToolTransport $Tool $Arguments
        $domainError = Get-ObjectProperty $structured '__domain_error'
        if ($null -ne $domainError) {
            if (-not $ReturnDomainError) {
                throw "$Tool returned a domain error: $(ConvertTo-CompactJson $domainError)"
            }
            Add-GateEvent -Event 'tool_call_domain_error' -Detail ([ordered]@{
                    tool = $Tool
                    code = [string](Get-ObjectProperty $domainError 'code')
                    recoverable = Get-ObjectProperty $domainError 'recoverable'
                })
            return [pscustomobject]@{ domain_error = $domainError }
        }
    } else {
        $timeout = if ($Tool -ceq 'agent_get_action' -and
            $Arguments.Contains('wait_timeout_ms')) {
            [Math]::Min(35, [Math]::Max(2,
                    [int][Math]::Ceiling([long]$Arguments.wait_timeout_ms / 1000.0) + 2))
        } else { 35 }
        $result = Invoke-LiveMcpRequest -Method 'tools/call' -ToolName $Tool `
            -TimeoutSeconds $timeout -Parameters ([ordered]@{
                _meta = Get-McpMeta
                name = $Tool
                arguments = $Arguments
            })
        if ((Get-ObjectProperty $result 'resultType') -cne 'complete') {
            throw "$Tool returned a non-complete result"
        }
        $isError = Get-ObjectProperty $result 'isError'
        if ($isError -isnot [bool]) { throw "$Tool returned invalid isError" }
        if ($isError) {
            $content = @(Get-ObjectProperty $result 'content')
            $diagnostic = if ($content.Count -gt 0) {
                [string](Get-ObjectProperty $content[0] 'text')
            } else { '{"code":"UNKNOWN_TOOL_ERROR"}' }
            if ($ReturnDomainError) {
                try {
                    $domainError = $diagnostic | ConvertFrom-Json -ErrorAction Stop
                } catch {
                    $domainError = [pscustomobject]@{
                        code = 'UNKNOWN_TOOL_ERROR'
                        message = $diagnostic
                        recoverable = $false
                    }
                }
                Add-GateEvent -Event 'tool_call_domain_error' -Detail ([ordered]@{
                        tool = $Tool
                        code = [string](Get-ObjectProperty $domainError 'code')
                        recoverable = Get-ObjectProperty $domainError 'recoverable'
                    })
                return [pscustomobject]@{ domain_error = $domainError }
            }
            throw "$Tool returned a domain error: $diagnostic"
        }
        $structured = Get-ObjectProperty $result 'structuredContent'
    }
    if ($null -eq $structured) { throw "$Tool returned no structured content" }
    Add-GateEvent -Event 'tool_call_completed' -Detail ([ordered]@{ tool = $Tool })
    return $structured
}

