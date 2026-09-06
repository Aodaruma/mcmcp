# Shared, side-effect-free definitions for the evaluator and fallback CLI.
# PowerShell 7.4+; dot-sourcing this file never reads credentials or sends requests.

function ConvertTo-CompactJson {
    param([AllowNull()][object]$Value)
    return (ConvertTo-Json -InputObject $Value -Depth 100 -Compress)
}

function Get-Property {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) {
        $matchingKeys = @($Object.Keys | Where-Object { [string]$_ -ceq $Name })
        if ($matchingKeys.Count -ne 1) { return $null }
        return [pscustomobject]@{ Name = [string]$matchingKeys[0]; Value = $Object[$matchingKeys[0]] }
    }
    $matchingProperties = @($Object.PSObject.Properties |
        Where-Object { $_.Name -ceq $Name })
    if ($matchingProperties.Count -ne 1) { return $null }
    return $matchingProperties[0]
}

function Get-PropertyValue {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)
    $property = Get-Property -Object $Object -Name $Name
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-McpMeta {
    param([string]$ClientName = 'mcmcp-fresh-eval-dynamic-bridge')
    return [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = '2026-07-28'
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = $ClientName
            version = '1'
        }
    }
}

function Test-IsObjectValue {
    param([AllowNull()][object]$Value)
    return $null -ne $Value -and (
        $Value -is [pscustomobject] -or $Value -is [Collections.IDictionary])
}

function Test-IsArrayValue {
    param([AllowNull()][object]$Value)
    return $null -ne $Value -and $Value -isnot [string] -and
        $Value -is [Collections.IEnumerable] -and -not (Test-IsObjectValue $Value)
}

function Assert-McmcpServerMeta {
    param([Parameter(Mandatory)][object]$Result, [Parameter(Mandatory)][string]$Operation)
    $meta = Get-PropertyValue -Object $Result -Name '_meta'
    $serverInfo = Get-PropertyValue -Object $meta `
        -Name 'io.modelcontextprotocol/serverInfo'
    if (-not (Test-IsObjectValue $serverInfo) -or
        (Get-PropertyValue $serverInfo 'name') -cne 'mcmcp' -or
        (Get-PropertyValue $serverInfo 'version') -cne '0.1.0') {
        throw "$Operation returned unexpected MCP serverInfo"
    }
}

function Assert-McmcpJsonRpcEnvelope {
    param(
        [Parameter(Mandatory)][object]$Response,
        [Parameter(Mandatory)][long]$ExpectedId,
        [Parameter(Mandatory)][string]$Operation
    )
    if ((Get-PropertyValue $Response 'jsonrpc') -cne '2.0') {
        throw "$Operation returned an invalid JSON-RPC version"
    }
    $idProperty = Get-Property $Response 'id'
    if ($null -eq $idProperty -or $null -eq $idProperty.Value -or
        $idProperty.Value.GetType() -ne $ExpectedId.GetType() -or
        $idProperty.Value -ne $ExpectedId) {
        throw "$Operation returned a mismatched JSON-RPC id"
    }
    $resultProperty = Get-Property $Response 'result'
    $errorProperty = Get-Property $Response 'error'
    $hasResultProperty = $null -ne $resultProperty
    $hasErrorProperty = $null -ne $errorProperty
    if ($hasResultProperty -eq $hasErrorProperty -or
        ($hasResultProperty -and $null -eq $resultProperty.Value) -or
        ($hasErrorProperty -and $null -eq $errorProperty.Value)) {
        throw "$Operation must return exactly one non-null result or error member"
    }
    if ($hasErrorProperty -and -not (Test-IsObjectValue $errorProperty.Value)) {
        throw "$Operation returned a malformed JSON-RPC error"
    }
    if ($hasErrorProperty -and (
        -not (Test-JsonIntegerValue (Get-PropertyValue $errorProperty.Value 'code')) -or
        (Get-PropertyValue $errorProperty.Value 'message') -isnot [string])) {
        throw "$Operation returned a malformed JSON-RPC error"
    }
}

function New-McmcpBridgeFailureException {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('http_status', 'transport', 'protocol_validation', 'deadline', 'internal')]
        [string]$FailureKind,
        [Parameter(Mandatory)]
        [ValidateSet(
            'rate_limited', 'http_non_success', 'request_timeout',
            'http_request_failed', 'transport_unclassified',
            'invalid_content_type', 'invalid_jsonrpc_envelope',
            'turn_deadline_expired', 'unclassified_bridge_exception')]
        [string]$DiagnosticCode,
        [AllowNull()][Nullable[int]]$HttpStatus
    )

    $failure = [InvalidOperationException]::new('MCMCP bridge request failed')
    $failure.Data['failure_kind'] = $FailureKind
    $failure.Data['diagnostic_code'] = $DiagnosticCode
    if ($null -ne $HttpStatus) {
        $failure.Data['http_status'] = [int]$HttpStatus
    }
    return $failure
}

function Get-McmcpHttpFailureStatus {
    param([Parameter(Mandatory)][Management.Automation.ErrorRecord]$Failure)

    $responseProperty = @($Failure.Exception.PSObject.Properties |
        Where-Object { $_.Name -ceq 'Response' })
    if ($responseProperty.Count -ne 1 -or $null -eq $responseProperty[0].Value) {
        return $null
    }
    $statusProperty = @($responseProperty[0].Value.PSObject.Properties |
        Where-Object { $_.Name -ceq 'StatusCode' })
    if ($statusProperty.Count -ne 1 -or $null -eq $statusProperty[0].Value) {
        return $null
    }
    try {
        $status = [int]$statusProperty[0].Value
        if ($status -lt 100 -or $status -gt 599) { return $null }
        return $status
    } catch {
        return $null
    }
}

function Test-JsonIntegerValue {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $false }
    return $Value.GetType().FullName -in @(
        'System.Byte', 'System.SByte', 'System.Int16', 'System.UInt16',
        'System.Int32', 'System.UInt32', 'System.Int64', 'System.UInt64')
}

function Assert-ExactMcmcpDomainErrorText {
    param([Parameter(Mandatory)][string]$Text, [Parameter(Mandatory)][string]$Operation)
    $document = $null
    try {
        $document = [Text.Json.JsonDocument]::Parse($Text)
    } catch {
        throw "$Operation domain error text must be valid JSON"
    }
    try {
        $root = $document.RootElement
        if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Object) {
            throw "$Operation domain error text must be a JSON object"
        }
        $properties = @($root.EnumerateObject())
        if ($properties.Count -ne 3) {
            throw "$Operation domain error object must contain exactly three members"
        }
        $code = @($properties | Where-Object { $_.Name -ceq 'code' })
        $message = @($properties | Where-Object { $_.Name -ceq 'message' })
        $recoverable = @($properties | Where-Object { $_.Name -ceq 'recoverable' })
        if ($code.Count -ne 1 -or $message.Count -ne 1 -or $recoverable.Count -ne 1 -or
            $code[0].Value.ValueKind -ne [Text.Json.JsonValueKind]::String -or
            $message[0].Value.ValueKind -ne [Text.Json.JsonValueKind]::String -or
            $recoverable[0].Value.ValueKind -notin @(
                [Text.Json.JsonValueKind]::True,
                [Text.Json.JsonValueKind]::False)) {
            throw "$Operation domain error object has an invalid exact shape"
        }
    } finally {
        $document.Dispose()
    }
}

function Assert-McmcpToolResult {
    param(
        [Parameter(Mandatory)][object]$Result,
        [Parameter(Mandatory)][string]$Operation,
        [switch]$RequireSuccess
    )
    if ((Get-PropertyValue $Result 'resultType') -cne 'complete') {
        throw "$Operation returned an invalid resultType"
    }
    Assert-McmcpServerMeta -Result $Result -Operation $Operation
    $isErrorProperty = Get-Property $Result 'isError'
    if ($null -eq $isErrorProperty -or $isErrorProperty.Value -isnot [bool]) {
        throw "$Operation returned a non-Boolean or missing isError"
    }
    if ($RequireSuccess -and $isErrorProperty.Value) {
        throw "$Operation returned isError=true"
    }
    $contentProperty = Get-Property $Result 'content'
    if ($null -eq $contentProperty -or -not (Test-IsArrayValue $contentProperty.Value)) {
        throw "$Operation returned malformed content"
    }
    if (@($contentProperty.Value).Count -lt 1) {
        throw "$Operation returned empty content"
    }
    foreach ($item in @($contentProperty.Value)) {
        if (-not (Test-IsObjectValue $item) -or
            (Get-PropertyValue $item 'type') -cne 'text' -or
            (Get-PropertyValue $item 'text') -isnot [string]) {
            throw "$Operation returned malformed TextContent"
        }
    }
    $structuredProperty = Get-Property $Result 'structuredContent'
    if ($isErrorProperty.Value) {
        if ($null -ne $structuredProperty) {
            throw "$Operation domain error must omit structuredContent"
        }
        $content = @($contentProperty.Value)
        if ($content.Count -ne 1) {
            throw "$Operation domain error must contain exactly one TextContent"
        }
        Assert-ExactMcmcpDomainErrorText `
            -Text ([string](Get-PropertyValue $content[0] 'text')) -Operation $Operation
    } elseif ($null -eq $structuredProperty -or
        -not (Test-IsObjectValue $structuredProperty.Value)) {
        throw "$Operation success must contain object structuredContent"
    }
}

function Invoke-McmcpTransportRequest {
    param(
        [Parameter(Mandatory)][string]$Endpoint,
        [Parameter(Mandatory)][string]$Bearer,
        [Parameter(Mandatory)][long]$RequestId,
        [Parameter(Mandatory)][ValidateSet('server/discover', 'tools/list', 'tools/call')]
        [string]$Method,
        [Parameter(Mandatory)][object]$Parameters,
        [string]$ToolName,
        [string]$EvaluationLeaseId,
        [ValidateRange(1, 35)][int]$TimeoutSeconds = 15
    )
    # No hostname resolution, proxies, redirects, or replay, even for read calls.
    $endpointUri = $null
    if ($Endpoint -cnotmatch '^http://127\.0\.0\.1:[0-9]{1,5}/mcp$' -or
        -not [uri]::TryCreate($Endpoint, [UriKind]::Absolute, [ref]$endpointUri) -or
        $endpointUri.Port -lt 1 -or $endpointUri.Port -gt 65535 -or
        $Bearer -cnotmatch '^[A-Za-z0-9_-]{43,256}$') {
        throw (New-McmcpBridgeFailureException -FailureKind 'protocol_validation' `
            -DiagnosticCode 'invalid_jsonrpc_envelope' -HttpStatus $null)
    }
    $headers = @{
        Authorization = "Bearer $Bearer"
        Accept = 'application/json, text/event-stream'
        'MCP-Protocol-Version' = '2026-07-28'
        'Mcp-Method' = $Method
    }
    if (-not [string]::IsNullOrWhiteSpace($EvaluationLeaseId)) {
        $headers['Mcmcp-Evaluation-Lease'] = $EvaluationLeaseId
    }
    if (-not [string]::IsNullOrWhiteSpace($ToolName)) {
        $headers['Mcp-Name'] = $ToolName
    }
    $request = [ordered]@{
        jsonrpc = '2.0'
        id = $requestId
        method = $Method
        params = $Parameters
    }
    try {
        $responseHeaders = $null
        $response = Invoke-RestMethod -Method Post -Uri $Endpoint -Headers $headers `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes((ConvertTo-CompactJson $request))) -TimeoutSec $TimeoutSeconds `
            -NoProxy -MaximumRedirection 0 -MaximumRetryCount 0 -ResponseHeadersVariable responseHeaders
    } catch {
        $httpStatus = Get-McmcpHttpFailureStatus -Failure $_
        if ($null -ne $httpStatus) {
            $diagnosticCode = if ($httpStatus -eq 429) {
                'rate_limited'
            } else { 'http_non_success' }
            throw (New-McmcpBridgeFailureException `
                    -FailureKind 'http_status' -DiagnosticCode $diagnosticCode `
                    -HttpStatus $httpStatus)
        }
        $exceptionType = $_.Exception.GetType().FullName
        $diagnosticCode = if ($exceptionType -in @(
                'System.TimeoutException', 'System.Threading.Tasks.TaskCanceledException')) {
            'request_timeout'
        } elseif ($exceptionType -eq 'System.Net.Http.HttpRequestException') {
            'http_request_failed'
        } else { 'transport_unclassified' }
        throw (New-McmcpBridgeFailureException `
                -FailureKind 'transport' -DiagnosticCode $diagnosticCode `
                -HttpStatus $null)
    }
    $contentType = [string]$responseHeaders['Content-Type']
    if ($contentType -notmatch '(?i)^application/json(?:\s*;\s*charset=(?:utf-8|"utf-8"))?\s*$' -or
        $contentType -notmatch '(?i)charset=(?:utf-8|"utf-8")') {
        throw (New-McmcpBridgeFailureException `
                -FailureKind 'protocol_validation' -DiagnosticCode 'invalid_content_type' `
                -HttpStatus 200)
    }
    try {
        Assert-McmcpJsonRpcEnvelope -Response $response -ExpectedId $requestId -Operation $Method
    } catch {
        throw (New-McmcpBridgeFailureException `
                -FailureKind 'protocol_validation' -DiagnosticCode 'invalid_jsonrpc_envelope' `
                -HttpStatus 200)
    }
    return $response
}
