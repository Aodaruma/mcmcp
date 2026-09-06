[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3', 'wall-5x5', 'gate-c')]
    [string]$Gate,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TokenPath,

    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',

    [ValidateRange(61, 600)]
    [int]$StateRefWaitSeconds = 65,

    # Dot-source the functions without touching the network. Used only by the mock test.
    [switch]$LibraryOnly
)

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
$script:SourceObservationForbidden = $false
$script:SourceObservationCount = 0
$script:ConstructionNavigationTolerance = 0.75
$script:PillarNavigationTolerance = 0.1
$script:TemporaryDropRecoveryNavigationTolerance = 0.1
$script:MaximumScaffoldNavigationSlices = 3

$script:ChestBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -12; min_y = 55; min_z = 2
    max_x = -10; max_y = 57; max_z = 5
}
$script:SourceBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 56; min_z = -1
    max_x = -18; max_y = 62; max_z = 5
}
$script:DestinationSupportBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 55; min_z = 9
    max_x = -18; max_y = 55; max_z = 15
}
$script:DestinationWallBounds = [ordered]@{
    dimension = 'minecraft:overworld'
    min_x = -23; min_y = 55; min_z = 9
    max_x = -18; max_y = 60; max_z = 15
}

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
    $resultProperty = if ($response -is [Collections.IDictionary]) {
        if ($response.Contains('result')) { [pscustomobject]@{ Value = $response['result'] } } else { $null }
    } elseif ($response -is [pscustomobject]) {
        $response.PSObject.Properties['result']
    } else { $null }
    if ($null -eq $resultProperty -or $resultProperty.Value -is [array] -or
        ($resultProperty.Value -isnot [pscustomobject] -and
            $resultProperty.Value -isnot [Collections.IDictionary])) {
        throw "$Method returned no object result"
    }
    $result = $resultProperty.Value
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
        $structuredProperty = if ($result -is [Collections.IDictionary]) {
            if ($result.Contains('structuredContent')) {
                [pscustomobject]@{ Value = $result['structuredContent'] }
            } else { $null }
        } elseif ($result -is [pscustomobject]) {
            $result.PSObject.Properties['structuredContent']
        } else { $null }
        $structured = if ($null -eq $structuredProperty) { $null } else { $structuredProperty.Value }
    }
    if ($null -eq $structured -or $structured -is [array] -or
        ($structured -isnot [pscustomobject] -and $structured -isnot [Collections.IDictionary])) {
        throw "$Tool returned no object structured content"
    }
    Add-GateEvent -Event 'tool_call_completed' -Detail ([ordered]@{ tool = $Tool })
    return $structured
}

function Assert-ReadyState {
    param([Parameter(Mandatory)][object]$State, [string]$Phase = 'readiness')
    $control = Get-ObjectProperty $State 'control'
    $action = Get-ObjectProperty $State 'action'
    if ((Get-ObjectProperty $control 'mode') -cne 'ready' -or
        (Get-ObjectProperty $control 'game_paused') -isnot [bool] -or
        [bool](Get-ObjectProperty $control 'game_paused')) {
        throw "$Phase requires control.mode=ready and an unpaused game"
    }
    if ($null -eq (Get-ObjectProperty $State 'world') -or
        $null -eq (Get-ObjectProperty $State 'observation')) {
        throw "$Phase requires a loaded world and observation frame"
    }
    if ($null -ne $action -and
        (Get-ObjectProperty $action 'state') -cnotin $script:TerminalStates) {
        throw "$Phase found a non-terminal Action"
    }
}

function Get-FreshState {
    $state = Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{})
    Assert-ReadyState -State $state
    return $state
}

function Get-ObservationFrameId {
    param([Parameter(Mandatory)][object]$State)
    $frameId = [string](Get-ObjectProperty `
        (Get-ObjectProperty $State 'observation') 'latest_frame_id')
    if ($frameId -cnotmatch '^obs-[0-9a-f]{16}$') {
        throw 'agent_get_state did not announce a valid observation frame'
    }
    return $frameId
}

function Invoke-GateDelaySeconds {
    param([ValidateRange(0.001, 900.0)][double]$Seconds)
    if ($null -ne $script:DelayTransport) {
        & $script:DelayTransport $Seconds
        return
    }
    Start-Sleep -Milliseconds ([Math]::Max(1, [Math]::Ceiling($Seconds * 1000.0)))
}

function Wait-ForObservationFrameAdvance {
    param(
        [Parameter(Mandatory)][string]$PreviousFrameId,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    if ($PreviousFrameId -cnotmatch '^obs-[0-9a-f]{16}$') {
        throw 'observation frame barrier requires a valid previous frame id'
    }
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $state = Get-FreshState
        $currentFrameId = Get-ObservationFrameId -State $state
        if ($currentFrameId -cne $PreviousFrameId) {
            Add-GateEvent -Event 'observation_frame_advanced' -Detail ([ordered]@{
                    previous_frame_id = $PreviousFrameId
                    current_frame_id = $currentFrameId
                    polls = $poll
                })
            return $state
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
        }
    }
    throw "observation frame did not advance from $PreviousFrameId after $MaximumPolls polls"
}

function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    $frameId = Get-ObservationFrameId -State $State
    $records = [Collections.Generic.List[object]]::new()
    $cursor = $null
    do {
        $arguments = [ordered]@{
            schema_version = 1
            frame_id = $frameId
            kinds = @($Kinds)
            cursor = $cursor
            limit = 256
        }
        if ($null -ne $Filter) { $arguments.filter = $Filter }
        $page = Invoke-GateTool -Tool 'agent_get_observation' -Arguments $arguments
        if ((Get-ObjectProperty $page 'frame_id') -cne $frameId) {
            throw 'agent_get_observation returned a mismatched frame'
        }
        foreach ($record in @(Get-ObjectProperty $page 'records')) {
            # Windows PowerShell may materialize an empty JSON array as a single null pipeline
            # value. Treat it as the empty page advertised by the protocol.
            if ($null -ne $record) { $records.Add($record) }
        }
        $cursor = Get-ObjectProperty $page 'next_cursor'
    } while ($null -ne $cursor)
    return @($records)
}

function Get-InventoryCount {
    param([Parameter(Mandatory)][object]$State, [Parameter(Mandatory)][string]$Item)
    $count = 0L
    foreach ($stack in @(Get-ObjectProperty $State 'inventory')) {
        if ((Get-ObjectProperty $stack 'item') -ceq $Item) {
            $count += [long](Get-ObjectProperty $stack 'count')
        }
    }
    return $count
}

function New-ActionRequest {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Capabilities,
        [Parameter(Mandatory)][object[]]$Body,
        [Parameter(Mandatory)][Collections.IDictionary]$Budget
    )
    [ordered]@{
        schema_version = 1
        program = [ordered]@{
            dsl_version = 1
            name = $Name
            capabilities = @($Capabilities)
            body = @($Body)
        }
        budget = $Budget
    }
}

function Get-PolicyDistanceBudget {
    param([Parameter(Mandatory)][object]$State)
    $policy = Get-ObjectProperty $State 'policy'
    $distance = Get-ObjectProperty $policy 'max_distance_blocks'
    if ($null -eq $distance -or [double]$distance -le 0) {
        throw 'state does not expose a positive navigation distance budget'
    }
    return [double]$distance
}

function Wait-McmcpActionTerminal {
    param(
        [Parameter(Mandatory)][string]$ActionId,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180
    )
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = Invoke-GateTool -Tool 'agent_get_action' -Arguments ([ordered]@{
                action_id = $ActionId
                wait_timeout_ms = 25000
            })
        if ((Get-ObjectProperty $snapshot 'action_id') -cne $ActionId) {
            throw 'agent_get_action returned a mismatched action_id'
        }
        $state = [string](Get-ObjectProperty $snapshot 'state')
        if ($state -cin $script:TerminalStates) { return $snapshot }
    } while ($watch.Elapsed.TotalSeconds -lt $WallTimeoutSeconds)
    throw "Action did not become terminal within $WallTimeoutSeconds seconds"
}

function Add-ActionTerminalEvent {
    param(
        [Parameter(Mandatory)][string]$ActionId,
        [Parameter(Mandatory)][object]$Terminal,
        [ValidateSet('request_wait', 'cleanup_recovery')][string]$Source = 'request_wait'
    )
    $existing = @($script:GateEvents | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'action_terminal' -and
            (Get-ObjectProperty $_ 'action_id') -ceq $ActionId
        })
    if ($existing.Count -gt 0) { return }
    Add-GateEvent -Event 'action_terminal' -Detail ([ordered]@{
            action_id = $ActionId
            state = [string](Get-ObjectProperty $Terminal 'state')
            progress = Get-ObjectProperty $Terminal 'progress'
            failure = Get-ObjectProperty $Terminal 'failure'
            trace = Get-ObjectProperty $Terminal 'trace'
            terminal_source = $Source
        })
}

function Invoke-ActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Request,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180,
        [switch]$ReturnFailure,
        [switch]$ReturnStartDomainError
    )
    $receipt = Invoke-GateTool -Tool 'agent_start_action' -Arguments $Request `
        -ReturnDomainError:$ReturnStartDomainError
    $startDomainError = Get-ObjectProperty $receipt 'domain_error'
    if ($null -ne $startDomainError) {
        return [pscustomobject]@{
            state = 'rejected'
            start_domain_error = $startDomainError
        }
    }
    $actionId = [string](Get-ObjectProperty $receipt 'action_id')
    if ($actionId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' -or
        (Get-ObjectProperty $receipt 'state') -cne 'queued') {
        throw 'agent_start_action returned an invalid receipt'
    }
    $script:ActiveActionId = $actionId
    Add-GateEvent -Event 'action_accepted' -Detail ([ordered]@{
            action_id = $actionId
            program = [string](Get-ObjectProperty (Get-ObjectProperty $Request 'program') 'name')
            body = Get-ObjectProperty (Get-ObjectProperty $Request 'program') 'body'
            budget = Get-ObjectProperty $Request 'budget'
        })
    $terminal = Wait-McmcpActionTerminal -ActionId $actionId `
        -WallTimeoutSeconds $WallTimeoutSeconds
    $script:ActiveActionId = $null
    Add-ActionTerminalEvent -ActionId $actionId -Terminal $terminal
    if ((Get-ObjectProperty $terminal 'state') -cne 'succeeded') {
        if ($ReturnFailure) { return $terminal }
        $failure = Get-ObjectProperty $terminal 'failure'
        throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
    }
    return $terminal
}

function Test-NavigationTerminalRequiresFreshSlice {
    param([Parameter(Mandatory)][object]$Terminal)
    if ((Get-ObjectProperty $Terminal 'state') -cne 'failed') { return $false }
    $failure = Get-ObjectProperty $Terminal 'failure'
    if ((Get-ObjectProperty $failure 'code') -cne 'BUDGET_EXCEEDED') { return $false }
    $evidence = @((Get-ObjectProperty $failure 'evidence'))
    if ($evidence.Count -ne 1 -or
        $evidence[0] -cnotin @(
            'primitive_replanned_route',
            'replanned_route',
            'replanned_route_shape_exceeds_occurrence',
            'replanned_route_global_budget',
            'replanned_route_remaining_occurrence')) {
        return $false
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'interactions') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        return $false
    }
    return @((Get-ObjectProperty $Terminal 'trace') | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'REPLANNING'
        }).Count -ge 1
}

function Invoke-GateCleanup {
    $cancelRequested = $false
    if (-not [string]::IsNullOrWhiteSpace([string]$script:ActiveActionId)) {
        $actionId = [string]$script:ActiveActionId
        $snapshot = Invoke-GateTool -Tool 'agent_get_action' -Arguments ([ordered]@{
                action_id = $actionId; wait_timeout_ms = 0
            })
        if ((Get-ObjectProperty $snapshot 'state') -cnotin $script:TerminalStates) {
            $cancel = Invoke-GateTool -Tool 'agent_cancel_action' -Arguments ([ordered]@{
                    action_id = $actionId
                })
            if ((Get-ObjectProperty $cancel 'action_id') -cne $actionId) {
                throw 'agent_cancel_action returned a mismatched action_id'
            }
            $cancelRequested = [bool](Get-ObjectProperty $cancel 'cancel_requested')
            Add-GateEvent -Event 'cleanup_cancel_requested' -Detail ([ordered]@{
                    action_id = $actionId; cancel_requested = $cancelRequested
                })
            $snapshot = Wait-McmcpActionTerminal -ActionId $actionId -WallTimeoutSeconds 60
        }
        if ((Get-ObjectProperty $snapshot 'state') -cnotin $script:TerminalStates) {
            throw 'cleanup did not reach a terminal Action state'
        }
        Add-ActionTerminalEvent -ActionId $actionId -Terminal $snapshot `
            -Source 'cleanup_recovery'
        $script:ActiveActionId = $null
    }
    $state = Invoke-GateTool -Tool 'agent_get_state' -Arguments ([ordered]@{})
    Assert-ReadyState -State $state -Phase 'cleanup input release'
    Add-GateEvent -Event 'public_input_release_verified' -Detail ([ordered]@{
            control_ready = $true
            all_actions_terminal = $true
            cancel_requested = $cancelRequested
            proof_scope = 'fixed_five_public_state'
        })
    return [ordered]@{
        control_ready = $true
        all_actions_terminal = $true
        cancel_requested = $cancelRequested
        input_owner_directly_exposed = $false
    }
}

function Select-NavigationRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$WorldPosition,
        [double]$MaximumDistance = 8
    )
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        $dx = [double](Get-ObjectProperty $target 'x') - [double](Get-ObjectProperty $WorldPosition 'x')
        $dy = [double](Get-ObjectProperty $target 'y') - [double](Get-ObjectProperty $WorldPosition 'y')
        $dz = [double](Get-ObjectProperty $target 'z') - [double](Get-ObjectProperty $WorldPosition 'z')
        $distance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
        if ($distance -ge 3 -and $distance -le $MaximumDistance) {
            [pscustomobject]@{ record = $record; distance = $distance }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'distance'; Descending = $true },
            @{ Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target') }; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no traversable fresh navigation_target between 3 and 8 blocks was delivered'
    }
    return $selected[0].record
}

function New-NavigationActionRequest {
    param(
        [Parameter(Mandatory)][object]$NavigationRecord,
        [Parameter(Mandatory)][object]$State,
        [ValidateRange(0.1, 1.5)][double]$Tolerance = 0.75
    )
    $observedTarget = Get-ObjectProperty $NavigationRecord 'navigation_target'
    $node = [ordered]@{
        id = 'navigate_gate'
        op = 'navigate_to_known'
        # Deliberately retain the delivered object. Do not floor/round from/to.
        target = $observedTarget
        tolerance = $Tolerance
    }
    if ((ConvertTo-CompactJson $node.target) -cne (ConvertTo-CompactJson $observedTarget)) {
        throw 'navigation target changed while constructing the Action'
    }
    New-ActionRequest -Name 'capability_gate_navigation' -Capabilities @('movement') `
        -Body @($node) -Budget ([ordered]@{
            max_duration_ms = 30000; max_ticks = 600
            max_distance_blocks = Get-PolicyDistanceBudget $State
            max_camera_degrees = 0; max_interactions = 0
            max_blocks_broken = 0; max_blocks_placed = 0
        })
}

function Invoke-NavigationGate {
    $initial = Get-FreshState
    $world = Get-ObjectProperty $initial 'world'
    $records = Get-RecordsFromState -State $initial -Kinds @('traversability') -Filter $null
    $record = Select-NavigationRecord -Records $records `
        -WorldPosition (Get-ObjectProperty $world 'position')
    $target = Get-ObjectProperty $record 'navigation_target'
    $request = New-NavigationActionRequest -NavigationRecord $record -State $initial
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
    $final = Get-FreshState
    $position = Get-ObjectProperty (Get-ObjectProperty $final 'world') 'position'
    if ([Math]::Floor([double](Get-ObjectProperty $position 'x')) -ne
            [int](Get-ObjectProperty $target 'x') -or
        [Math]::Floor([double](Get-ObjectProperty $position 'y')) -ne
            [int](Get-ObjectProperty $target 'y') -or
        [Math]::Floor([double](Get-ObjectProperty $position 'z')) -ne
            [int](Get-ObjectProperty $target 'z')) {
        throw 'navigation Action succeeded outside the delivered target feet cell'
    }
    return [ordered]@{
        gate = 'navigation'
        navigation_target = $target
        target_verbatim = $true
        final_feet_cell_matches = $true
        external_oracle = [ordered]@{
            expected_world_mutations = 0
            compare_regions = @('source', 'destination', 'work-area')
        }
    }
}

function New-PrimitiveRequest {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Capabilities,
        [Parameter(Mandatory)][object]$Node,
        [long]$Duration = 30000,
        [long]$Ticks = 600,
        [double]$Distance = 0,
        [double]$Camera = 360,
        [long]$Interactions = 0,
        [long]$Breaks = 0,
        [long]$Placements = 0
    )
    New-ActionRequest -Name $Name -Capabilities $Capabilities -Body @($Node) `
        -Budget ([ordered]@{
            max_duration_ms = $Duration; max_ticks = $Ticks
            max_distance_blocks = $Distance; max_camera_degrees = $Camera
            max_interactions = $Interactions; max_blocks_broken = $Breaks
            max_blocks_placed = $Placements
        })
}

function Get-VisibleSurfaceRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [switch]$AllowMissing
    )
    $filter = [ordered]@{
        block_ids = @($Block)
        position_bounds = $Bounds
    }
    if ($null -ne $Faces) { $filter.faces = @($Faces) }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') -Filter $filter)
    if ($records.Count -eq 0) {
        # A bare return emits no pipeline object. `return $null` becomes a one-element null array
        # under Windows PowerShell when a caller wraps this helper in @(...).
        if ($AllowMissing) { return }
        throw "no visible $Block surface was delivered in the gate bounds"
    }
    foreach ($record in $records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'block') -cne $Block) {
            throw 'visible_surface filter returned an out-of-filter record'
        }
        if ($null -ne $Faces -and (Get-ObjectProperty $record 'face') -cnotin $Faces) {
            throw 'visible_surface faces filter returned an out-of-filter face'
        }
    }
    if ($ExcludePlayerFeetAbove) {
        $player = Get-ObjectProperty (Get-ObjectProperty $State 'world') 'position'
        $feetX = [Math]::Floor([double](Get-ObjectProperty $player 'x'))
        $feetY = [Math]::Floor([double](Get-ObjectProperty $player 'y'))
        $feetZ = [Math]::Floor([double](Get-ObjectProperty $player 'z'))
        $records = @($records | Where-Object {
                $position = Get-ObjectProperty $_ 'position'
                -not (
                    [int](Get-ObjectProperty $position 'x') -eq $feetX -and
                    [int](Get-ObjectProperty $position 'y') + 1 -eq $feetY -and
                    [int](Get-ObjectProperty $position 'z') -eq $feetZ)
            })
        if ($records.Count -eq 0) {
            throw 'all visible destination targets are occupied by the player'
        }
        $records = @($records | Sort-Object @{
                Expression = {
                    $position = Get-ObjectProperty $_ 'position'
                    $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                        [double](Get-ObjectProperty $player 'x')
                    $dy = ([double](Get-ObjectProperty $position 'y') + 1.5) -
                        [double](Get-ObjectProperty $player 'y')
                    $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                        [double](Get-ObjectProperty $player 'z')
                    $dx * $dx + $dy * $dy + $dz * $dz
                }
                Descending = $false
            }, @{
                Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_ 'position') }
                Descending = $false
            })
    } else {
        $records = @($records | Sort-Object {
                ConvertTo-CompactJson (Get-ObjectProperty $_ 'position')
            })
    }
    return @($records)
}

function Get-VisibleSurface {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [switch]$AllowMissing
    )
    $records = @(Get-VisibleSurfaceRecords -State $State -Block $Block -Bounds $Bounds `
        -Faces $Faces -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove `
        -AllowMissing:$AllowMissing)
    if ($records.Count -eq 0) { return $null }
    return $records[0]
}

function Select-NavigationRecordTowardBounds {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$WorldPosition,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [double]$MaximumDistance = 8
    )
    $worldX = [double](Get-ObjectProperty $WorldPosition 'x')
    $worldY = [double](Get-ObjectProperty $WorldPosition 'y')
    $worldZ = [double](Get-ObjectProperty $WorldPosition 'z')
    $goalX = ([double]$Bounds.min_x + [double]$Bounds.max_x) / 2.0
    $goalZ = ([double]$Bounds.min_z + [double]$Bounds.max_z) / 2.0
    $currentGoalDistance = [Math]::Sqrt(
        [Math]::Pow($worldX - $goalX, 2) + [Math]::Pow($worldZ - $goalZ, 2))
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        $dx = [double](Get-ObjectProperty $target 'x') - $worldX
        $dy = [double](Get-ObjectProperty $target 'y') - $worldY
        $dz = [double](Get-ObjectProperty $target 'z') - $worldZ
        $travelDistance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
        $goalDistance = [Math]::Sqrt(
            [Math]::Pow([double](Get-ObjectProperty $target 'x') - $goalX, 2) +
            [Math]::Pow([double](Get-ObjectProperty $target 'z') - $goalZ, 2))
        if ($travelDistance -ge 1 -and $travelDistance -le $MaximumDistance -and
            $goalDistance -lt $currentGoalDistance) {
            [pscustomobject]@{
                record = $record
                goal_distance = $goalDistance
                progress = $currentGoalDistance - $goalDistance
                travel_distance = $travelDistance
            }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'goal_distance'; Descending = $false },
            @{ Expression = 'progress'; Descending = $true },
            @{ Expression = 'travel_distance'; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target') }; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh traversability record makes progress toward the gate bounds'
    }
    return $selected[0].record
}

function Get-NearbyTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    $world = Get-ObjectProperty $State 'world'
    $position = Get-ObjectProperty $world 'position'
    $feetX = [Math]::Floor([double](Get-ObjectProperty $position 'x'))
    $feetY = [Math]::Floor([double](Get-ObjectProperty $position 'y'))
    $feetZ = [Math]::Floor([double](Get-ObjectProperty $position 'z'))
    $filter = [ordered]@{
        position_bounds = [ordered]@{
            dimension = [string](Get-ObjectProperty $world 'dimension')
            min_x = $feetX - 2; min_y = $feetY - 1; min_z = $feetZ - 2
            max_x = $feetX + 2; max_y = $feetY + 1; max_z = $feetZ + 2
        }
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter $filter)
}

function Get-OrNavigateToVisibleSurface {
    param(
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [ValidateRange(0, 8)][int]$MaximumApproaches = 8
    )
    for ($attempt = 0; $attempt -le $MaximumApproaches; $attempt++) {
        $state = Get-FreshState
        $surface = Get-VisibleSurface -State $state -Block $Block -Bounds $Bounds `
            -Faces $Faces -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove -AllowMissing
        if ($null -ne $surface) {
            $player = Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position'
            $target = Get-ObjectProperty $surface 'position'
            $dx = ([double](Get-ObjectProperty $target 'x') + 0.5) -
                [double](Get-ObjectProperty $player 'x')
            $dy = ([double](Get-ObjectProperty $target 'y') + 0.5) -
                ([double](Get-ObjectProperty $player 'y') + 1.62)
            $dz = ([double](Get-ObjectProperty $target 'z') + 0.5) -
                [double](Get-ObjectProperty $player 'z')
            $surfaceDistance = [Math]::Sqrt($dx * $dx + $dy * $dy + $dz * $dz)
            if ($surfaceDistance -le 4.0) { return $surface }
            Add-GateEvent -Event 'visible_surface_outside_interaction_range' -Detail ([ordered]@{
                    block = $Block; attempt = $attempt
                    distance = [Math]::Round($surfaceDistance, 3)
                })
        }
        if ($attempt -eq $MaximumApproaches) { break }
        $world = Get-ObjectProperty $state 'world'
        $records = Get-NearbyTraversabilityRecords -State $state
        $record = Select-NavigationRecordTowardBounds -Records $records `
            -WorldPosition (Get-ObjectProperty $world 'position') -Bounds $Bounds
        $target = Get-ObjectProperty $record 'navigation_target'
        $request = New-NavigationActionRequest -NavigationRecord $record -State $state
        Add-GateEvent -Event 'surface_approach_navigation_selected' -Detail ([ordered]@{
                block = $Block; attempt = $attempt + 1
                navigation_target = $target; target_verbatim = $true
            })
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90 `
            -ReturnFailure
        if ((Get-ObjectProperty $terminal 'state') -cne 'succeeded') {
            if (-not (Test-NavigationTerminalRequiresFreshSlice -Terminal $terminal)) {
                $failure = Get-ObjectProperty $terminal 'failure'
                throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
            }
            Add-GateEvent -Event 'surface_approach_navigation_reslice_required' `
                -Detail ([ordered]@{
                    block = $Block; attempt = $attempt + 1
                    failed_action_id = [string](Get-ObjectProperty $terminal 'action_id')
                    failure_code = [string](Get-ObjectProperty `
                        (Get-ObjectProperty $terminal 'failure') 'code')
                    fresh_state_required = $true
                    old_target_reuse_allowed = $false
                })
            continue
        }
    }
    throw "no visible $Block surface was delivered after bounded observed-target approaches"
}

function Invoke-ApproachSurface {
    param([Parameter(Mandatory)][object]$Record, [Parameter(Mandatory)][object]$State)
    $observedTarget = Get-ObjectProperty $Record 'position'
    $node = [ordered]@{
        id = 'approach_surface'; op = 'approach_known_surface'
        target = $observedTarget
        expected_block = Get-ObjectProperty $Record 'block'
    }
    if ((ConvertTo-CompactJson $node.target) -cne (ConvertTo-CompactJson $observedTarget)) {
        throw 'approach target changed while constructing the Action'
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_approach' `
        -Capabilities @('movement', 'camera') -Node $node `
        -Distance (Get-PolicyDistanceBudget $State)
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
}

function Acquire-OakLogFromChest {
    $chest = Get-OrNavigateToVisibleSurface -Block 'minecraft:chest' `
        -Bounds $script:ChestBounds -Faces $null
    $state = Get-FreshState
    Invoke-ApproachSurface -Record $chest -State $state
    $state = Get-FreshState
    $chest = Get-VisibleSurface -State $state -Block 'minecraft:chest' `
        -Bounds $script:ChestBounds -Faces $null
    $node = [ordered]@{
        id = 'take_oak_log'; op = 'take_known_container_stack'
        target = Get-ObjectProperty $chest 'position'
        expected_block = 'minecraft:chest'
        item = 'minecraft:oak_log'
        stack_policy = 'default_components_only'
        minimum_inventory_count = 1
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_take_oak_log' `
        -Capabilities @('camera', 'inventory_transfer') -Node $node -Interactions 3
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
    $state = Get-FreshState
    $count = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
    if ($count -lt 1) { throw 'normal chest transfer did not yield an oak log' }
    return $count
}

function Move-NearDestinationSupport {
    param([ValidateSet(3, 5)][int]$Width = 3)
    $support = Get-OrNavigateToVisibleSurface -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') -ExcludePlayerFeetAbove
    $state = Get-FreshState
    Invoke-ApproachSurface -Record $support -State $state
    if ($Width -eq 5) { Invoke-WallStagingNavigation -Width $Width }
}

function Assert-SourceObservationAllowed {
    if ($script:SourceObservationForbidden) {
        throw 'source observation is forbidden after the state-ref retention window starts'
    }
}

function Get-OakLogPlacementSource {
    param([Parameter(Mandatory)][object]$State)
    Assert-SourceObservationAllowed
    $script:SourceObservationCount++
    $filter = [ordered]@{
        block_ids = @('minecraft:oak_log')
        position_bounds = $script:SourceBounds
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_surface') -Filter $filter)
    $eligible = @($records | Where-Object {
            $state = Get-ObjectProperty $_ 'state'
            $properties = Get-ObjectProperty $state 'properties'
            (Get-ObjectProperty $_ 'block') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
            [string](Get-ObjectProperty $_ 'placement_state_ref') -cmatch '^psr_[0-9a-f]{32}$' -and
            (Get-ObjectProperty $state 'block') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $properties 'axis') -ceq 'y'
        } | Sort-Object { ConvertTo-CompactJson (Get-ObjectProperty $_ 'position') })
    if ($eligible.Count -eq 0) {
        throw 'no eligible visible vertical oak-log source was delivered'
    }
    return $eligible[0]
}

function Get-ExactSupportAfterFace {
    param([Parameter(Mandatory)][object]$Position)
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $state = Get-FreshState
    return Get-VisibleSurface -State $state -Block 'minecraft:white_wool' `
        -Bounds $bounds -Faces @('up')
}

function Invoke-FaceSupport {
    param([Parameter(Mandatory)][object]$Support)
    $node = [ordered]@{
        id = 'face_support'; op = 'face_known_position'
        target = Get-ObjectProperty $Support 'position'
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_face_support' `
        -Capabilities @('camera') -Node $node -Distance 0 -Camera 360
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 60)
}

function Get-TargetAboveSupport {
    param([Parameter(Mandatory)][object]$SupportPosition)
    [ordered]@{
        dimension = [string](Get-ObjectProperty $SupportPosition 'dimension')
        x = [int](Get-ObjectProperty $SupportPosition 'x')
        y = [int](Get-ObjectProperty $SupportPosition 'y') + 1
        z = [int](Get-ObjectProperty $SupportPosition 'z')
    }
}

function New-OneOakLogPlacementPhase {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support,
        [switch]$UseStateRef
    )
    $supportPosition = Get-ObjectProperty $Support 'position'
    $target = Get-TargetAboveSupport $supportPosition
    $entry = [ordered]@{
        id = 'single_log'
        offset = [ordered]@{ x = 0; y = 0; z = 0 }
    }
    if ($UseStateRef) {
        $entry.placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
    } else {
        $entry.source_state = Get-ObjectProperty $Source 'state'
        $entry.item = [string](Get-ObjectProperty $Source 'placement_item')
    }
    $entry.support = [ordered]@{
        position = $supportPosition
        face = 'up'
        expected_state = Get-ObjectProperty $Support 'state'
        dependency_entry_id = $null
    }
    if ($null -eq $entry.support.expected_state) {
        throw 'white-wool support did not include a complete state'
    }
    $node = [ordered]@{
        id = 'place_single_log'; op = 'apply_known_block_plan'
        anchor = $target
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @($entry)
    }
    $request = New-PrimitiveRequest -Name 'capability_gate_place_single_log' `
        -Capabilities @('camera', 'block_place') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 0 -Camera 80 -Placements 1
    return [pscustomobject]@{
        request = $request
        target = $target
        entry = $entry
    }
}

function Invoke-OneOakLogPlacement {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support,
        [switch]$UseStateRef
    )
    $phase = New-OneOakLogPlacementPhase -Source $Source -Support $Support `
        -UseStateRef:$UseStateRef
    [void](Invoke-ActionRequest -Request $phase.request -WallTimeoutSeconds 60)
    return $phase.target
}

function Wait-StateRefRetentionWindow {
    param([Parameter(Mandatory)][ValidateRange(61, 600)][int]$Seconds)
    $script:SourceObservationForbidden = $true
    Add-GateEvent -Event 'state_ref_retention_wait_started' -Detail ([ordered]@{
            seconds = $Seconds; source_observation_count = $script:SourceObservationCount
        })
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-GateDelaySeconds -Seconds $Seconds
    $watch.Stop()
    Add-GateEvent -Event 'state_ref_retention_wait_completed' -Detail ([ordered]@{
            requested_seconds = $Seconds
            elapsed_seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 3)
            source_observation_count = $script:SourceObservationCount
        })
}

function Invoke-PlacementGate {
    param([switch]$UseStateRef)
    $inventoryBefore = Acquire-OakLogFromChest
    Move-NearDestinationSupport
    $state = Get-FreshState
    $source = Get-OakLogPlacementSource -State $state
    if ($UseStateRef) {
        Wait-StateRefRetentionWindow -Seconds $StateRefWaitSeconds
        if ($script:SourceObservationCount -ne 1) {
            throw 'state-ref TTL gate observed the source more than once'
        }
        $state = Get-FreshState
    }
    $support = Get-VisibleSurface -State $state -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') `
        -ExcludePlayerFeetAbove
    Invoke-FaceSupport -Support $support
    $support = Get-ExactSupportAfterFace `
        -Position (Get-ObjectProperty $support 'position')
    $target = Invoke-OneOakLogPlacement -Source $source -Support $support `
        -UseStateRef:$UseStateRef
    $final = Get-FreshState
    $inventoryAfter = Get-InventoryCount -State $final -Item 'minecraft:oak_log'
    if ($inventoryAfter -ne $inventoryBefore - 1) {
        throw 'oak-log inventory count did not decrease by exactly one'
    }
    return [ordered]@{
        gate = if ($UseStateRef) { 'state-ref-ttl' } else { 'faces-place' }
        faces_filter_verified = $true
        source_observations = $script:SourceObservationCount
        state_ref_wait_seconds = if ($UseStateRef) { $StateRefWaitSeconds } else { 0 }
        placement_identity = if ($UseStateRef) { 'placement_state_ref' } else { 'inline_state_item' }
        placement_target = $target
        expected_state = Get-ObjectProperty $source 'state'
        inventory_before_placement = $inventoryBefore
        inventory_after_placement = $inventoryAfter
        external_oracle = [ordered]@{
            exact_changed_position = $target
            expected_source_region_changes = 0
            expected_other_destination_changes = 0
        }
    }
}

function Get-BlockPositionKey {
    param([Parameter(Mandatory)][object]$Position)
    return ('{0}|{1}|{2}|{3}' -f
        [string](Get-ObjectProperty $Position 'dimension'),
        [int](Get-ObjectProperty $Position 'x'),
        [int](Get-ObjectProperty $Position 'y'),
        [int](Get-ObjectProperty $Position 'z'))
}

function Select-ContiguousWallFoundation {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$PlayerPosition,
        [ValidateRange(1, 8)][int]$Width = 3,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5
    )
    $byPosition = @{}
    foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $record 'face') -cne 'up' -or
            (Get-ObjectProperty (Get-ObjectProperty $record 'state') 'block') -cne
                'minecraft:white_wool') {
            throw 'wall foundation selection received an ineligible surface record'
        }
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if ($byPosition.ContainsKey($key)) {
            throw "wall foundation contains duplicate position $key"
        }
        $byPosition[$key] = $record
    }
    $candidates = [Collections.Generic.List[object]]::new()
    foreach ($record in $Records) {
        $start = Get-ObjectProperty $record 'position'
        foreach ($axis in @('x', 'z')) {
            $row = [Collections.Generic.List[object]]::new()
            $maximumDistanceSquared = 0.0
            for ($offset = 0; $offset -lt $Width; $offset++) {
                $position = [ordered]@{
                    dimension = [string](Get-ObjectProperty $start 'dimension')
                    x = [int](Get-ObjectProperty $start 'x')
                    y = [int](Get-ObjectProperty $start 'y')
                    z = [int](Get-ObjectProperty $start 'z')
                }
                $position[$axis] = [int]$position[$axis] + $offset
                $key = Get-BlockPositionKey $position
                if (-not $byPosition.ContainsKey($key)) {
                    $row.Clear()
                    break
                }
                $surface = $byPosition[$key]
                $row.Add($surface)
                $supportPosition = Get-ObjectProperty $surface 'position'
                $dx = ([double](Get-ObjectProperty $supportPosition 'x') + 0.5) -
                    [double](Get-ObjectProperty $PlayerPosition 'x')
                $dy = ([double](Get-ObjectProperty $supportPosition 'y') + 1.0) -
                    ([double](Get-ObjectProperty $PlayerPosition 'y') + 1.62)
                $dz = ([double](Get-ObjectProperty $supportPosition 'z') + 0.5) -
                    [double](Get-ObjectProperty $PlayerPosition 'z')
                $distanceSquared = $dx * $dx + $dy * $dy + $dz * $dz
                $maximumDistanceSquared = [Math]::Max($maximumDistanceSquared, $distanceSquared)
            }
            if ($row.Count -ne $Width -or
                [Math]::Sqrt($maximumDistanceSquared) -gt $MaximumReach) { continue }
            $centerRecord = $row[[int][Math]::Floor($Width / 2)]
            $center = Get-ObjectProperty $centerRecord 'position'
            $centerDx = ([double](Get-ObjectProperty $center 'x') + 0.5) -
                [double](Get-ObjectProperty $PlayerPosition 'x')
            $centerDz = ([double](Get-ObjectProperty $center 'z') + 0.5) -
                [double](Get-ObjectProperty $PlayerPosition 'z')
            $candidates.Add([pscustomobject]@{
                    axis = $axis
                    maximum_distance_squared = $maximumDistanceSquared
                    center_distance_squared = $centerDx * $centerDx + $centerDz * $centerDz
                    start_key = Get-BlockPositionKey $start
                    supports = @($row)
                })
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_distance_squared'; Descending = $false },
            @{ Expression = 'center_distance_squared'; Descending = $false },
            @{ Expression = 'axis'; Descending = $false },
            @{ Expression = 'start_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw "no policy-visible contiguous $Width-block white-wool UP foundation is within stationary reach"
    }
    return @($selected[0].supports)
}

function Select-WallStagingNavigationSite {
    param(
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -ne 5) {
        throw '5-wide staging requires one exact observed foundation row'
    }
    $wallColumns = @{}
    foreach ($support in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $support 'position'))] = $true
    }
    $center = Get-ObjectProperty `
        $WallFoundation[[int][Math]::Floor($WallFoundation.Count / 2)] 'position'
    $supportY = [int](Get-ObjectProperty `
        (Get-ObjectProperty $WallFoundation[0] 'position') 'y')
    $maximumReachSquared = $MaximumReach * $MaximumReach
    $candidates = foreach ($record in $TraversabilityRecords) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne
                (Get-ObjectProperty $center 'dimension') -or
            [int](Get-ObjectProperty $target 'y') -ne $supportY + 1 -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $target))) { continue }

        $maximumDistanceSquared = 0.0
        $maximumToleranceBoundSquared = 0.0
        foreach ($support in $WallFoundation) {
            $position = Get-ObjectProperty $support 'position'
            $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                ([double](Get-ObjectProperty $target 'x') + 0.5)
            $dy = ([double](Get-ObjectProperty $position 'y') + 1.0) -
                ([double](Get-ObjectProperty $target 'y') + 1.62)
            $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                ([double](Get-ObjectProperty $target 'z') + 0.5)
            $horizontalDistance = [Math]::Sqrt($dx * $dx + $dz * $dz)
            $distanceSquared = $horizontalDistance * $horizontalDistance + $dy * $dy
            $toleranceBoundSquared =
                ($horizontalDistance + $NavigationTolerance) *
                ($horizontalDistance + $NavigationTolerance) + $dy * $dy
            $maximumDistanceSquared = [Math]::Max(
                $maximumDistanceSquared, $distanceSquared)
            $maximumToleranceBoundSquared = [Math]::Max(
                $maximumToleranceBoundSquared, $toleranceBoundSquared)
        }
        if ($maximumToleranceBoundSquared -gt $maximumReachSquared) { continue }
        $centerDx = ([double](Get-ObjectProperty $center 'x') + 0.5) -
            ([double](Get-ObjectProperty $target 'x') + 0.5)
        $centerDz = ([double](Get-ObjectProperty $center 'z') + 0.5) -
            ([double](Get-ObjectProperty $target 'z') + 0.5)
        [pscustomobject]@{
            navigation_record = $record
            maximum_support_distance_squared = $maximumDistanceSquared
            maximum_tolerance_bound_squared = $maximumToleranceBoundSquared
            center_horizontal_distance_squared = $centerDx * $centerDx + $centerDz * $centerDz
            status_rank = if ((Get-ObjectProperty $record 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
            target_key = Get-BlockPositionKey $target
        }
    }
    # Prefer the site with the largest reach margin. The final accepted pose may
    # lie anywhere inside navigation tolerance, so center-distance alone is not
    # a valid construction-reach proof.
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_support_distance_squared'; Descending = $false },
            @{ Expression = 'center_horizontal_distance_squared'; Descending = $false },
            @{ Expression = 'status_rank'; Descending = $false },
            @{ Expression = 'target_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh outside-row traversability target keeps all five supports within reach'
    }
    return $selected[0]
}

function Invoke-WallStagingNavigation {
    param([Parameter(Mandatory)][ValidateSet(5)][int]$Width)
    $state = Get-FreshState
    $foundationRecords = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    $foundation = @(Select-ContiguousWallFoundation -Records $foundationRecords `
        -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
        -Width $Width -MaximumReach 8)
    $traversability = @(Get-WallGroundTraversabilityRecords -State $state)
    $site = Select-WallStagingNavigationSite -WallFoundation $foundation `
        -TraversabilityRecords $traversability `
        -NavigationTolerance $script:ConstructionNavigationTolerance
    $record = $site.navigation_record
    $target = Get-ObjectProperty $record 'navigation_target'
    $request = New-NavigationActionRequest -NavigationRecord $record `
        -State $state -Tolerance $script:ConstructionNavigationTolerance
    Add-GateEvent -Event 'wall_staging_navigation_selected' -Detail ([ordered]@{
            target = $target
            target_verbatim = [object]::ReferenceEquals($target, $request.program.body[0].target)
            foundation_positions = @($foundation | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
                })
            maximum_support_distance = [Math]::Sqrt(
                [double]$site.maximum_support_distance_squared)
            maximum_support_distance_with_tolerance = [Math]::Sqrt(
                [double]$site.maximum_tolerance_bound_squared)
            navigation_tolerance = $script:ConstructionNavigationTolerance
        })
    $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90

    # A terminal navigation does not extend the old evidence lease. Prove the
    # complete row again from the first post-navigation frame before construction.
    $verifiedState = Get-FreshState
    $verifiedRecords = @(Get-VisibleSurfaceRecords -State $verifiedState `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    $verified = @(Select-ContiguousWallFoundation -Records $verifiedRecords `
        -PlayerPosition (Get-ObjectProperty `
            (Get-ObjectProperty $verifiedState 'world') 'position') -Width $Width)
    Add-GateEvent -Event 'wall_staging_foundation_verified' -Detail ([ordered]@{
            action_id = [string](Get-ObjectProperty $terminal 'action_id')
            target = $target
            support_count = $verified.Count
            positions = @($verified | ForEach-Object { Get-ObjectProperty $_ 'position' })
        })
}

function Select-ExactWallSupportRow {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object[]]$ExpectedPositions,
        [Parameter(Mandatory)][object]$ExpectedState
    )
    $byPosition = @{}
    foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $record 'face') -cne 'up') { continue }
        $key = Get-BlockPositionKey (Get-ObjectProperty $record 'position')
        if ($byPosition.ContainsKey($key)) { throw "duplicate delivered wall support $key" }
        $byPosition[$key] = $record
    }
    $selected = [Collections.Generic.List[object]]::new()
    foreach ($position in $ExpectedPositions) {
        $key = Get-BlockPositionKey $position
        if (-not $byPosition.ContainsKey($key)) {
            throw "placed wall cell was not freshly delivered as an UP support: $key"
        }
        $record = $byPosition[$key]
        if ((ConvertTo-CompactJson (Get-ObjectProperty $record 'state')) -cne
            (ConvertTo-CompactJson $ExpectedState)) {
            throw "fresh wall support has the wrong complete state: $key"
        }
        $selected.Add($record)
    }
    return @($selected)
}

function Get-CurrentWorldRevision {
    param([Parameter(Mandatory)][object]$State)
    $revision = Get-ObjectProperty (Get-ObjectProperty $State 'world') 'world_revision'
    if ($revision -isnot [sbyte] -and $revision -isnot [byte] -and
        $revision -isnot [int16] -and $revision -isnot [uint16] -and
        $revision -isnot [int32] -and $revision -isnot [uint32] -and
        $revision -isnot [int64] -and $revision -isnot [uint64]) {
        throw 'agent_get_state did not announce an integer world revision'
    }
    return [long]$revision
}

function Wait-ForCurrentVisibleSurfaceRecords {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [AllowNull()][string[]]$Faces,
        [switch]$ExcludePlayerFeetAbove,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-VisibleSurfaceRecords -State $state -Block $Block `
            -Bounds $Bounds -Faces $Faces `
            -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove -AllowMissing)
        $currentRecords = @($records | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        if ($currentRecords.Count -gt 0) {
            Add-GateEvent -Event 'visible_surfaces_revision_current' -Detail ([ordered]@{
                    block = $Block
                    world_revision = $worldRevision
                    polls = $poll
                    surface_count = $currentRecords.Count
                })
            return [pscustomobject]@{
                state = $state
                records = @($currentRecords)
                world_revision = $worldRevision
                polls = $poll
            }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "$Block surfaces did not reach the current world revision after $MaximumPolls polls"
}

function Wait-ForCurrentWallReorientationSurface {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $current = Wait-ForCurrentVisibleSurfaceRecords -InitialState $InitialState `
        -Block 'minecraft:oak_log' -Bounds $bounds `
        -Faces @('up', 'north', 'south', 'east', 'west')
    $positionKey = Get-BlockPositionKey $Position
    $eligible = @($current.records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq $positionKey -and
            (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                (ConvertTo-CompactJson $ExpectedState)
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'face') -ceq 'up') { 0 } else { 1 }
                }; Descending = $false }, `
            @{ Expression = { [string](Get-ObjectProperty $_ 'face') }; Descending = $false })
    if ($eligible.Count -eq 0) {
        throw 'current wall reorientation surface has the wrong complete state'
    }
    return [pscustomobject]@{
        state = $current.state
        surface = $eligible[0]
        world_revision = $current.world_revision
        polls = $current.polls
    }
}

function Wait-ForCurrentExactWallSupportRow {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][string]$Block,
        [Parameter(Mandatory)][Collections.IDictionary]$Bounds,
        [Parameter(Mandatory)][object[]]$ExpectedPositions,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces = @('up'),
        [switch]$ExcludePlayerFeetAbove,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-VisibleSurfaceRecords -State $state -Block $Block `
            -Bounds $Bounds -Faces $Faces `
            -ExcludePlayerFeetAbove:$ExcludePlayerFeetAbove -AllowMissing)
        $currentRecords = @($records | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        if ($currentRecords.Count -gt 0) {
            try {
                $supports = @(Select-ExactWallSupportRow -Records $currentRecords `
                    -ExpectedPositions $ExpectedPositions -ExpectedState $ExpectedState)
                Add-GateEvent -Event 'wall_support_revision_current' -Detail ([ordered]@{
                        world_revision = $worldRevision
                        polls = $poll
                        positions = @($ExpectedPositions)
                    })
                return [pscustomobject]@{
                    state = $state
                    supports = @($supports)
                    world_revision = $worldRevision
                    polls = $poll
                }
            } catch {
                if ($_.Exception.Message -notmatch 'was not freshly delivered') { throw }
            }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "exact $Block support did not reach the current world revision after $MaximumPolls polls"
}

function Get-BlockColumnKey {
    param([Parameter(Mandatory)][object]$Position)
    return ('{0}|{1}|{2}' -f
        [string](Get-ObjectProperty $Position 'dimension'),
        [int](Get-ObjectProperty $Position 'x'),
        [int](Get-ObjectProperty $Position 'z'))
}

function Get-WallScaffoldTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [ValidateRange(0, 4)][int]$AdditionalHeight = 4
    )
    $bounds = [ordered]@{
        dimension = [string]$script:DestinationSupportBounds.dimension
        min_x = [int]$script:DestinationSupportBounds.min_x
        min_y = [int]$script:DestinationSupportBounds.min_y + 1
        min_z = [int]$script:DestinationSupportBounds.min_z
        max_x = [int]$script:DestinationSupportBounds.max_x
        max_y = [int]$script:DestinationSupportBounds.max_y + 1 + $AdditionalHeight
        max_z = [int]$script:DestinationSupportBounds.max_z
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }))
}

function Get-WallGroundTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    return @(Get-WallScaffoldTraversabilityRecords -State $State -AdditionalHeight 0)
}

function Get-ExactScaffoldTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$ExpectedTarget
    )
    $dimension = [string](Get-ObjectProperty $ExpectedTarget 'dimension')
    $x = [int](Get-ObjectProperty $ExpectedTarget 'x')
    $y = [int](Get-ObjectProperty $ExpectedTarget 'y')
    $z = [int](Get-ObjectProperty $ExpectedTarget 'z')
    $bounds = [ordered]@{
        dimension = $dimension
        min_x = $x; min_y = $y; min_z = $z
        max_x = $x; max_y = $y; max_z = $z
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }))
}

function Test-SafeTraversabilityRecord {
    param([Parameter(Mandatory)][object]$Record)
    return (Get-ObjectProperty $Record 'kind') -ceq 'traversability' -and
        (Get-ObjectProperty $Record 'status') -cin @('CONFIRMED', 'PROBE_ALLOWED') -and
        (Get-ObjectProperty $Record 'target_support') -ceq 'confirmed' -and
        (Get-ObjectProperty $Record 'transition_clearance') -ceq 'confirmed' -and
        (Get-ObjectProperty $Record 'fluid') -ceq 'none'
}

function Select-TemporaryPillarSite {
    param(
        [Parameter(Mandatory)][object[]]$WhiteWoolRecords,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$RowOneTargets,
        [ValidateRange(1, 8)][double]$MaximumWallReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -lt 1 -or
        $WallFoundation.Count -ne $RowOneTargets.Count) {
        throw 'temporary pillar selection requires one complete wall row'
    }
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $eligibleTraversability = [Collections.Generic.List[object]]::new()
    foreach ($record in $TraversabilityRecords) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $eligibleTraversability.Add($record)
    }

    $candidates = [Collections.Generic.List[object]]::new()
    foreach ($support in $WhiteWoolRecords) {
        $position = Get-ObjectProperty $support 'position'
        $state = Get-ObjectProperty $support 'state'
        if ((Get-ObjectProperty $support 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $support 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $support 'face') -cne 'up' -or
            (Get-ObjectProperty $state 'block') -cne 'minecraft:white_wool' -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $position))) { continue }

        $joined = @($eligibleTraversability | Where-Object {
                $target = Get-ObjectProperty $_ 'navigation_target'
                (Get-ObjectProperty $target 'dimension') -ceq
                    (Get-ObjectProperty $position 'dimension') -and
                [int](Get-ObjectProperty $target 'x') -eq
                    [int](Get-ObjectProperty $position 'x') -and
                [int](Get-ObjectProperty $target 'y') -eq
                    [int](Get-ObjectProperty $position 'y') + 1 -and
                [int](Get-ObjectProperty $target 'z') -eq
                    [int](Get-ObjectProperty $position 'z')
            } | Sort-Object `
                @{ Expression = {
                        if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                    }; Descending = $false },
                @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
        if ($joined.Count -eq 0) { continue }
        # Multiple edges may legitimately converge on one navigation_target.
        # Prefer a confirmed witness and retain that record's target verbatim.
        $joinedRecord = $joined[0]
        $target = Get-ObjectProperty $joinedRecord 'navigation_target'

        # The pillar lands one block above navigation_target. From that delivered
        # cell, every already-built row-1 UP face must remain within product reach.
        $maximumDistanceSquared = 0.0
        $maximumToleranceBoundSquared = 0.0
        foreach ($wallSupport in $RowOneTargets) {
            $dx = ([double](Get-ObjectProperty $wallSupport 'x') + 0.5) -
                ([double](Get-ObjectProperty $target 'x') + 0.5)
            $dy = ([double](Get-ObjectProperty $wallSupport 'y') + 1.0) -
                ([double](Get-ObjectProperty $target 'y') + 1.0 + 1.62)
            $dz = ([double](Get-ObjectProperty $wallSupport 'z') + 0.5) -
                ([double](Get-ObjectProperty $target 'z') + 0.5)
            $horizontalDistance = [Math]::Sqrt($dx * $dx + $dz * $dz)
            $distanceSquared = $horizontalDistance * $horizontalDistance + $dy * $dy
            $toleranceBoundSquared =
                ($horizontalDistance + $NavigationTolerance) *
                ($horizontalDistance + $NavigationTolerance) + $dy * $dy
            $maximumDistanceSquared = [Math]::Max(
                $maximumDistanceSquared, $distanceSquared)
            $maximumToleranceBoundSquared = [Math]::Max(
                $maximumToleranceBoundSquared, $toleranceBoundSquared)
        }
        if ($maximumToleranceBoundSquared -gt $MaximumWallReach * $MaximumWallReach) {
            continue
        }
        $candidates.Add([pscustomobject]@{
                support = $support
                navigation_record = $joinedRecord
                maximum_wall_distance_squared = $maximumDistanceSquared
                maximum_wall_tolerance_bound_squared = $maximumToleranceBoundSquared
                support_key = Get-BlockPositionKey $position
            })
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_wall_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_wall_distance_squared'; Descending = $false },
            @{ Expression = 'support_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh outside-footprint white-wool UP support joins a safe traversability target within raised wall reach'
    }
    return $selected[0]
}

function Select-TemporaryStaircasePlan {
    param(
        [Parameter(Mandatory)][object[]]$WhiteWoolRecords,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$RowOneTargets,
        [ValidateRange(1, 8)][double]$MaximumReach = 4.5,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    if ($WallFoundation.Count -ne 5 -or $RowOneTargets.Count -ne 5) {
        throw 'temporary staircase selection requires the complete five-wide wall'
    }
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $safeRecords = @($TraversabilityRecords | Where-Object {
            Test-SafeTraversabilityRecord $_
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    $safeByTarget = @{}
    foreach ($record in $safeRecords) {
        $targetKey = Get-BlockPositionKey (Get-ObjectProperty $record 'navigation_target')
        if (-not $safeByTarget.ContainsKey($targetKey)) {
            # Preserve the first, deterministically preferred delivered record object.
            $safeByTarget[$targetKey] = $record
        }
    }
    $groundY = [int]$script:DestinationSupportBounds.min_y + 1
    $sites = [Collections.Generic.List[object]]::new()
    $sitesByTarget = @{}
    foreach ($support in @($WhiteWoolRecords | Sort-Object `
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'position')
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })) {
        $position = Get-ObjectProperty $support 'position'
        $state = Get-ObjectProperty $support 'state'
        if ((Get-ObjectProperty $support 'kind') -cne 'visible_surface' -or
            (Get-ObjectProperty $support 'block') -cne 'minecraft:white_wool' -or
            (Get-ObjectProperty $support 'face') -cne 'up' -or
            (Get-ObjectProperty $state 'block') -cne 'minecraft:white_wool' -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $position))) { continue }
        $joinedKey = '{0}|{1}|{2}|{3}' -f
            (Get-ObjectProperty $position 'dimension'),
            [int](Get-ObjectProperty $position 'x'),
            ([int](Get-ObjectProperty $position 'y') + 1),
            [int](Get-ObjectProperty $position 'z')
        if (-not $safeByTarget.ContainsKey($joinedKey)) { continue }
        $joinedRecord = $safeByTarget[$joinedKey]
        $joinedTarget = Get-ObjectProperty $joinedRecord 'navigation_target'
        if ([int](Get-ObjectProperty $joinedTarget 'y') -ne $groundY -or
            $sitesByTarget.ContainsKey($joinedKey)) { continue }
        $site = [pscustomobject]@{
                support = $support
                navigation_record = $joinedRecord
                target = $joinedTarget
                key = Get-BlockPositionKey $position
            }
        $sites.Add($site)
        $sitesByTarget[$joinedKey] = $site
    }

    $plans = [Collections.Generic.List[object]]::new()
    $directions = @(
        [pscustomobject]@{ x = -1; z = 0 },
        [pscustomobject]@{ x = 0; z = -1 },
        [pscustomobject]@{ x = 0; z = 1 },
        [pscustomobject]@{ x = 1; z = 0 }
    )
    foreach ($high in $sites) {
        $highTarget = $high.target
        $maximumWallDistanceSquared = 0.0
        foreach ($wallSupport in $RowOneTargets) {
            $dx = ([double]$wallSupport.x + 0.5) - ([double]$highTarget.x + 0.5)
            $dy = ([double]$wallSupport.y + 1.0) -
                ([double]$highTarget.y + 3.0 + 1.62)
            $dz = ([double]$wallSupport.z + 0.5) - ([double]$highTarget.z + 0.5)
            $horizontal = [Math]::Sqrt($dx * $dx + $dz * $dz)
            $distance = ($horizontal + $NavigationTolerance) *
                ($horizontal + $NavigationTolerance) + $dy * $dy
            $maximumWallDistanceSquared = [Math]::Max(
                $maximumWallDistanceSquared, $distance)
        }
        if ($maximumWallDistanceSquared -gt $MaximumReach * $MaximumReach) { continue }
        foreach ($direction in $directions) {
            $stepX = [int]$direction.x
            $stepZ = [int]$direction.z
            $mediumKey = '{0}|{1}|{2}|{3}' -f
                $highTarget.dimension, ([int]$highTarget.x + $stepX), $groundY,
                ([int]$highTarget.z + $stepZ)
            if (-not $sitesByTarget.ContainsKey($mediumKey)) { continue }
            $medium = $sitesByTarget[$mediumKey]
            $mediumTarget = $medium.target
            $lowKey = '{0}|{1}|{2}|{3}' -f
                $highTarget.dimension, ([int]$mediumTarget.x + $stepX), $groundY,
                ([int]$mediumTarget.z + $stepZ)
            if (-not $sitesByTarget.ContainsKey($lowKey)) { continue }
            $low = $sitesByTarget[$lowKey]
            $lowTarget = $low.target
            $temporaryPositions = @(
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = $highTarget.y; z = $highTarget.z },
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = ([int]$highTarget.y + 1); z = $highTarget.z },
                [pscustomobject]@{ dimension = $highTarget.dimension; x = $highTarget.x; y = ([int]$highTarget.y + 2); z = $highTarget.z },
                [pscustomobject]@{ dimension = $mediumTarget.dimension; x = $mediumTarget.x; y = $mediumTarget.y; z = $mediumTarget.z },
                [pscustomobject]@{ dimension = $mediumTarget.dimension; x = $mediumTarget.x; y = ([int]$mediumTarget.y + 1); z = $mediumTarget.z },
                [pscustomobject]@{ dimension = $lowTarget.dimension; x = $lowTarget.x; y = $lowTarget.y; z = $lowTarget.z }
            )
            foreach ($groundDirection in $directions) {
                $groundKey = '{0}|{1}|{2}|{3}' -f
                    $lowTarget.dimension,
                    ([int]$lowTarget.x + [int]$groundDirection.x), $groundY,
                    ([int]$lowTarget.z + [int]$groundDirection.z)
                if ($groundKey -cin @(
                        Get-BlockPositionKey $highTarget
                        Get-BlockPositionKey $mediumTarget
                        Get-BlockPositionKey $lowTarget) -or
                    -not $safeByTarget.ContainsKey($groundKey)) { continue }
                $groundRecord = $safeByTarget[$groundKey]
                $groundTarget = Get-ObjectProperty $groundRecord 'navigation_target'
                if ($wallColumns.ContainsKey((Get-BlockColumnKey $groundTarget))) { continue }
                $maximumCleanupDistanceSquared = 0.0
                foreach ($temporary in $temporaryPositions) {
                    $dx = ([double]$temporary.x + 0.5) - ([double]$groundTarget.x + 0.5)
                    $dy = ([double]$temporary.y + 0.5) - ([double]$groundTarget.y + 1.62)
                    $dz = ([double]$temporary.z + 0.5) - ([double]$groundTarget.z + 0.5)
                    $horizontal = [Math]::Sqrt($dx * $dx + $dz * $dz)
                    $distance = ($horizontal + $NavigationTolerance) *
                        ($horizontal + $NavigationTolerance) + $dy * $dy
                    $maximumCleanupDistanceSquared = [Math]::Max(
                        $maximumCleanupDistanceSquared, $distance)
                }
                if ($maximumWallDistanceSquared -gt $MaximumReach * $MaximumReach -or
                    $maximumCleanupDistanceSquared -gt $MaximumReach * $MaximumReach) { continue }
                $plans.Add([pscustomobject]@{
                        high = $high
                        medium = $medium
                        low = $low
                        ground_record = $groundRecord
                        maximum_wall_tolerance_bound_squared = $maximumWallDistanceSquared
                        maximum_cleanup_tolerance_bound_squared = $maximumCleanupDistanceSquared
                        key = ((Get-BlockPositionKey $highTarget) + '>' +
                            (Get-BlockPositionKey $mediumTarget) + '>' +
                            (Get-BlockPositionKey $lowTarget) + '>' +
                            (Get-BlockPositionKey $groundTarget))
                    })
            }
        }
    }
    $selected = @($plans | Sort-Object `
            @{ Expression = 'maximum_wall_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'maximum_cleanup_tolerance_bound_squared'; Descending = $false },
            @{ Expression = 'key'; Descending = $false } | Select-Object -First 1)
    if ($selected.Count -ne 1) {
        $siteKeys = @($sites | ForEach-Object { Get-BlockPositionKey $_.target }) -join ','
        $recordKeys = @($safeRecords | ForEach-Object {
                Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
            }) -join ','
        throw "no delivery-backed 3-2-1 temporary staircase keeps wall and cleanup reach; sites=$siteKeys records=$recordKeys"
    }
    return $selected[0]
}

function Select-TemporaryStaircaseSurveyRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$PlayerPosition,
        [Parameter(Mandatory)][object[]]$WallFoundation
    )
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $fromX = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'x'))
    $fromY = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'y'))
    $fromZ = [Math]::Floor([double](Get-ObjectProperty $PlayerPosition 'z'))
    $candidates = @($Records | Where-Object {
            if (-not (Test-SafeTraversabilityRecord $_)) { return $false }
            $target = Get-ObjectProperty $_ 'navigation_target'
            $dx = [int]$target.x - $fromX
            $dz = [int]$target.z - $fromZ
            [int]$target.y -eq $fromY -and
            -not $wallColumns.ContainsKey((Get-BlockColumnKey $target)) -and
            $dx * $dx + $dz * $dz -ge 4 -and
            $dx * $dx + $dz * $dz -le 16
        } | Sort-Object `
            @{ Expression = {
                    $target = Get-ObjectProperty $_ 'navigation_target'
                    $dx = [int]$target.x - $fromX
                    $dz = [int]$target.z - $fromZ
                    $dx * $dx + $dz * $dz
                }; Descending = $true },
            @{ Expression = {
                    Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')
                }; Descending = $false })
    if ($candidates.Count -eq 0) {
        throw 'no delivered safe survey stance can reveal the under-foot staircase base'
    }
    return $candidates[0]
}

function Select-AdjacentScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$FromPosition,
        [Parameter(Mandatory)][object]$TargetColumn,
        [Parameter(Mandatory)][int]$TargetY,
        [switch]$AllowMissing
    )
    $fromX = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'x'))
    $fromY = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'y'))
    $fromZ = [Math]::Floor([double](Get-ObjectProperty $FromPosition 'z'))
    $eligible = @($Records | Where-Object {
            if (-not (Test-SafeTraversabilityRecord $_)) { return $false }
            $target = Get-ObjectProperty $_ 'navigation_target'
            (Get-ObjectProperty $target 'dimension') -ceq
                (Get-ObjectProperty $TargetColumn 'dimension') -and
            [int](Get-ObjectProperty $target 'x') -eq [int](Get-ObjectProperty $TargetColumn 'x') -and
            [int](Get-ObjectProperty $target 'y') -eq $TargetY -and
            [int](Get-ObjectProperty $target 'z') -eq [int](Get-ObjectProperty $TargetColumn 'z') -and
            [Math]::Abs([int]$target.x - $fromX) +
                [Math]::Abs([int]$target.z - $fromZ) -eq 1 -and
            [Math]::Abs([int]$target.y - $fromY) -le 1
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    if ($eligible.Count -lt 1) {
        if ($AllowMissing) { return $null }
        throw 'no delivered adjacent scaffold navigation step satisfies abs(dy)<=1'
    }
    # A single observed target can be delivered more than once (for example,
    # as both CONFIRMED and PROBE_ALLOWED).  They name the same exact step;
    # use the deterministically preferred original record instead of treating
    # redundant policy evidence as spatial ambiguity.
    return $eligible[0]
}

function Select-ExactScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$ExpectedTarget,
        [switch]$AllowMissing
    )
    $key = Get-BlockPositionKey $ExpectedTarget
    $eligible = @($Records | Where-Object {
            (Test-SafeTraversabilityRecord $_) -and
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'navigation_target')) -ceq $key
        } | Sort-Object `
            @{ Expression = {
                    if ((Get-ObjectProperty $_ 'status') -ceq 'CONFIRMED') { 0 } else { 1 }
                }; Descending = $false },
            @{ Expression = { ConvertTo-CompactJson $_ }; Descending = $false })
    if ($eligible.Count -lt 1) {
        if ($AllowMissing) { return $null }
        throw 'no delivered scaffold navigation target matches the requested step'
    }
    # Preserve the selected delivery object.  Duplicate evidence for this
    # exact coordinate is ordered CONFIRMED-first and then by compact JSON.
    return $eligible[0]
}

function Wait-ForExactScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$ExpectedTarget,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        # This is an exact-coordinate wait, so ask the public filter for that
        # one cell instead of opening a multi-page lease for the whole worksite.
        $records = @(Get-ExactScaffoldTraversabilityRecords -State $state `
                -ExpectedTarget $ExpectedTarget | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        $record = Select-ExactScaffoldNavigationRecord `
            -Records $records `
            -ExpectedTarget $ExpectedTarget -AllowMissing
        if ($null -ne $record) {
            Add-GateEvent -Event 'scaffold_traversability_current' -Detail ([ordered]@{
                    mode = 'exact'; polls = $poll
                    target = Get-ObjectProperty $record 'navigation_target'
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = $worldRevision
                })
            return [pscustomobject]@{ state = $state; record = $record; polls = $poll }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw 'no delivered scaffold navigation target became safe within the bounded wait'
}

function Wait-ForAdjacentScaffoldNavigationRecord {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$TargetColumn,
        [Parameter(Mandatory)][int]$TargetY,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-WallScaffoldTraversabilityRecords -State $state | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        $record = Select-AdjacentScaffoldNavigationRecord `
            -Records $records `
            -FromPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -TargetColumn $TargetColumn -TargetY $TargetY -AllowMissing
        if ($null -ne $record) {
            Add-GateEvent -Event 'scaffold_traversability_current' -Detail ([ordered]@{
                    mode = 'adjacent'; polls = $poll
                    target = Get-ObjectProperty $record 'navigation_target'
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = $worldRevision
                })
            return [pscustomobject]@{ state = $state; record = $record; polls = $poll }
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw 'no delivered adjacent scaffold navigation step became safe within the bounded wait'
}

function New-TemporaryPillarActionRequest {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support
    )
    $supportPosition = Get-ObjectProperty $Support 'position'
    $supportState = Get-ObjectProperty $Support 'state'
    $placementStateRef = [string](Get-ObjectProperty $Source 'placement_state_ref')
    $supportBlock = [string](Get-ObjectProperty $supportState 'block')
    if ((Get-ObjectProperty $Support 'face') -cne 'up' -or
        $supportBlock -cnotin @('minecraft:white_wool', 'minecraft:oak_log') -or
        $placementStateRef -cnotmatch '^psr_[0-9a-f]{32}$') {
        throw 'temporary pillar inputs are not delivery-backed safe identities'
    }
    $node = [ordered]@{
        id = 'temporary_wall_pillar'
        op = 'pillar_up_known'
        support = $supportPosition
        expected_support = $supportState
        placement_state_ref = $placementStateRef
    }
    return New-PrimitiveRequest -Name 'capability_gate_wall_temporary_pillar' `
        -Capabilities @('movement', 'camera', 'block_place') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 2 -Camera 360 -Placements 1
}

function Invoke-TemporaryScaffoldColumn {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Site,
        [Parameter(Mandatory)][ValidateSet('single', 'low', 'medium', 'high')][string]$Role,
        [Parameter(Mandatory)][ValidateRange(1, 3)][int]$Height,
        [AllowNull()][string]$RaiseNavigationActionId
    )
    $state = $InitialState
    $positions = [Collections.Generic.List[object]]::new()
    $scaffolds = [Collections.Generic.List[object]]::new()
    for ($level = 1; $level -le $Height; $level++) {
        if ($level -eq 1) {
            $support = Get-ObjectProperty $Site 'support'
            $position = Get-ObjectProperty `
                (Get-ObjectProperty $Site 'navigation_record') 'navigation_target'
        } else {
            $previous = $positions[$positions.Count - 1]
            $support = Get-ExactTemporarySurface -State $state -Position $previous `
                -ExpectedState (Get-ObjectProperty $Source 'state') -Faces @('up')
            $position = Get-TargetAboveSupport $previous
        }
        $request = New-TemporaryPillarActionRequest -Source $Source -Support $support
        $frameId = Get-ObservationFrameId -State $state
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 60
        $state = Wait-ForObservationFrameAdvance -PreviousFrameId $frameId
        $positions.Add($position)
        $record = [ordered]@{
            column_role = $Role
            column_height = $Height
            level = $level
            level_in_column = $level
            support = Get-ObjectProperty $support 'position'
            position = $position
            raise_navigation_action_id = if ($level -eq 1) {
                $RaiseNavigationActionId
            } else { $null }
            pillar_action_id = [string](Get-ObjectProperty $terminal 'action_id')
            placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
        }
        $scaffolds.Add($record)
        Add-GateEvent -Event 'wall_temporary_pillar_terminal' -Detail ([ordered]@{
                column_role = $Role
                column_height = $Height
                level = $level
                action_id = [string](Get-ObjectProperty $terminal 'action_id')
                support = Get-ObjectProperty $support 'position'
                placed_position = $position
                placement_state_ref = [string](Get-ObjectProperty $Source 'placement_state_ref')
            })
    }
    return [pscustomobject]@{
        state = $state
        positions = @($positions)
        scaffolds = @($scaffolds)
        top_position = $positions[$positions.Count - 1]
    }
}

function Get-CurrentTemporaryScaffoldSite {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$ExpectedSite,
        [Parameter(Mandatory)][object]$NavigationRecord
    )
    $expectedSupport = Get-ObjectProperty $ExpectedSite 'support'
    $expectedPosition = Get-ObjectProperty $expectedSupport 'position'
    $current = Wait-ForCurrentVisibleSurfaceRecords -InitialState $State `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove
    $matches = @($current.records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq
                (Get-BlockPositionKey $expectedPosition)
        })
    if ($matches.Count -ne 1) {
        throw 'temporary staircase base did not have one current exact UP surface'
    }
    return [pscustomobject]@{
        state = $current.state
        site = [pscustomobject]@{
            support = $matches[0]
            navigation_record = $NavigationRecord
            target = Get-ObjectProperty $NavigationRecord 'navigation_target'
            key = Get-BlockPositionKey $expectedPosition
        }
    }
}

function Invoke-TemporaryScaffoldNavigation {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$NavigationRecord,
        [Parameter(Mandatory)][double]$Tolerance,
        [Parameter(Mandatory)][string]$Step,
        [Parameter(Mandatory)][string]$Event,
        [switch]$ReturnAfterResliceableFailure,
        [ValidateRange(1, 8)][int]$MaximumSlices =
            $script:MaximumScaffoldNavigationSlices
    )
    $expectedTarget = Get-ObjectProperty $NavigationRecord 'navigation_target'
    $expectedTargetKey = Get-BlockPositionKey $expectedTarget
    $sliceState = $State
    $sliceRecord = $NavigationRecord
    $actionIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)

    for ($slice = 1; $slice -le $MaximumSlices; $slice++) {
        $from = Get-ObjectProperty (Get-ObjectProperty $sliceState 'world') 'position'
        $target = Get-ObjectProperty $sliceRecord 'navigation_target'
        if ((Get-BlockPositionKey $target) -cne $expectedTargetKey) {
            throw 'fresh scaffold navigation record changed the requested exact coordinate'
        }
        $request = New-NavigationActionRequest -NavigationRecord $sliceRecord `
            -State $sliceState -Tolerance $Tolerance
        $terminal = Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90 `
            -ReturnFailure
        $actionId = [string](Get-ObjectProperty $terminal 'action_id')
        $hasNewActionId = -not [string]::IsNullOrWhiteSpace($actionId) -and
            $actionIds.Add($actionId)
        if ((Get-ObjectProperty $terminal 'state') -ceq 'succeeded') {
            if (-not $hasNewActionId) {
                throw 'temporary scaffold navigation did not receive a new action_id for each slice'
            }
            $nextState = Get-FreshState
            $fromX = [Math]::Floor([double](Get-ObjectProperty $from 'x'))
            $fromY = [Math]::Floor([double](Get-ObjectProperty $from 'y'))
            $fromZ = [Math]::Floor([double](Get-ObjectProperty $from 'z'))
            $dx = [Math]::Abs([int](Get-ObjectProperty $target 'x') - $fromX)
            $dy = [Math]::Abs([int](Get-ObjectProperty $target 'y') - $fromY)
            $dz = [Math]::Abs([int](Get-ObjectProperty $target 'z') - $fromZ)
            Add-GateEvent -Event $Event -Detail ([ordered]@{
                    step = $Step
                    action_id = $actionId
                    from = $from
                    target = $target
                    target_verbatim = [object]::ReferenceEquals(
                        $target, $request.program.body[0].target)
                    status = [string](Get-ObjectProperty $sliceRecord 'status')
                    horizontal_manhattan = $dx + $dz
                    absolute_y_delta = $dy
                    tolerance = $request.program.body[0].tolerance
                    slice = $slice
                    maximum_slices = $MaximumSlices
                    resliced = $slice -gt 1
                    action_ids = @($actionIds)
                })
            return [pscustomobject]@{
                state = $nextState
                terminal = $terminal
                target = $target
                horizontal_manhattan = $dx + $dz
                absolute_y_delta = $dy
                slices = $slice
            }
        }

        $failure = Get-ObjectProperty $terminal 'failure'
        $resliceAllowed = Test-NavigationTerminalRequiresFreshSlice -Terminal $terminal
        Add-GateEvent -Event 'temporary_scaffold_navigation_slice_failed' `
            -Detail ([ordered]@{
                step = $Step
                slice = $slice
                maximum_slices = $MaximumSlices
                failed_action_id = $actionId
                new_action_id = $hasNewActionId
                target = $target
                failure_code = [string](Get-ObjectProperty $failure 'code')
                failure_evidence = @((Get-ObjectProperty $failure 'evidence'))
                reslice_allowed = $resliceAllowed
                slices_remaining = $MaximumSlices - $slice
                fresh_state_required = $true
                old_record_reuse_allowed = $false
                synthetic_target_allowed = $false
            })
        if (-not $hasNewActionId) {
            throw 'temporary scaffold navigation did not receive a new action_id for each slice'
        }
        if (-not $resliceAllowed) {
            throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
        }
        if ($ReturnAfterResliceableFailure) {
            # A movement Action can cross the item before a route-shape replan
            # closes it as failed.  Recovery is governed by the material/drop
            # ledger, so return one fresh state to that caller instead of
            # waiting for the now-occupied exact target to be delivered again.
            $nextState = Get-FreshState
            $fromX = [Math]::Floor([double](Get-ObjectProperty $from 'x'))
            $fromY = [Math]::Floor([double](Get-ObjectProperty $from 'y'))
            $fromZ = [Math]::Floor([double](Get-ObjectProperty $from 'z'))
            $dx = [Math]::Abs([int](Get-ObjectProperty $target 'x') - $fromX)
            $dy = [Math]::Abs([int](Get-ObjectProperty $target 'y') - $fromY)
            $dz = [Math]::Abs([int](Get-ObjectProperty $target 'z') - $fromZ)
            Add-GateEvent -Event $Event -Detail ([ordered]@{
                    step = $Step
                    action_id = $actionId
                    terminal_state = [string](Get-ObjectProperty $terminal 'state')
                    failure_code = [string](Get-ObjectProperty $failure 'code')
                    reslice_required = $true
                    material_recheck_required = $true
                    from = $from
                    target = $target
                    target_verbatim = [object]::ReferenceEquals(
                        $target, $request.program.body[0].target)
                    horizontal_manhattan = $dx + $dz
                    absolute_y_delta = $dy
                    tolerance = $request.program.body[0].tolerance
                    slice = $slice
                    maximum_slices = $MaximumSlices
                    action_ids = @($actionIds)
                })
            return [pscustomobject]@{
                state = $nextState
                terminal = $terminal
                target = $target
                horizontal_manhattan = $dx + $dz
                absolute_y_delta = $dy
                slices = $slice
                reslice_required = $true
            }
        }
        if ($slice -eq $MaximumSlices) {
            throw "temporary scaffold navigation exhausted its bounded $MaximumSlices Action slices"
        }

        $freshState = Get-FreshState
        $fresh = Wait-ForExactScaffoldNavigationRecord -InitialState $freshState `
            -ExpectedTarget $expectedTarget
        $sliceState = $fresh.state
        $sliceRecord = $fresh.record
        Add-GateEvent -Event 'temporary_scaffold_navigation_reslice_selected' `
            -Detail ([ordered]@{
                step = $Step
                next_slice = $slice + 1
                maximum_slices = $MaximumSlices
                previous_action_id = $actionId
                target = Get-ObjectProperty $sliceRecord 'navigation_target'
                frame_id = Get-ObservationFrameId -State $sliceState
                world_revision = Get-CurrentWorldRevision -State $sliceState
                target_from_fresh_delivery = $true
                old_record_reuse_allowed = $false
                synthetic_target_allowed = $false
            })
    }
}

function Select-TemporaryPillarDescentRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [ValidateRange(0.1, 1.5)][double]$NavigationTolerance =
            $script:ConstructionNavigationTolerance
    )
    $wallColumns = @{}
    foreach ($record in $WallFoundation) {
        $wallColumns[(Get-BlockColumnKey (Get-ObjectProperty $record 'position'))] = $true
    }
    $candidates = foreach ($record in $Records) {
        if ((Get-ObjectProperty $record 'kind') -cne 'traversability' -or
            (Get-ObjectProperty $record 'status') -cnotin @('CONFIRMED', 'PROBE_ALLOWED') -or
            (Get-ObjectProperty $record 'target_support') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'transition_clearance') -cne 'confirmed' -or
            (Get-ObjectProperty $record 'fluid') -cne 'none') { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne
                (Get-ObjectProperty $TemporaryPosition 'dimension') -or
            [int](Get-ObjectProperty $target 'y') -ne
                [int](Get-ObjectProperty $TemporaryPosition 'y') -or
            $wallColumns.ContainsKey((Get-BlockColumnKey $target))) { continue }
        $dx = [double](Get-ObjectProperty $target 'x') -
            [double](Get-ObjectProperty $TemporaryPosition 'x')
        $dz = [double](Get-ObjectProperty $target 'z') -
            [double](Get-ObjectProperty $TemporaryPosition 'z')
        $horizontalDistanceSquared = $dx * $dx + $dz * $dz
        $horizontalDistance = [Math]::Sqrt($horizontalDistanceSquared)
        # Account for every pose accepted by navigate_to_known. A two-block
        # minimum remains after tolerance, while the far edge stays in break reach.
        if ($horizontalDistance - $NavigationTolerance -ge 2.0 -and
            $horizontalDistance + $NavigationTolerance -le 4.0) {
            [pscustomobject]@{
                record = $record
                horizontal_distance_squared = $horizontalDistanceSquared
                target_key = Get-BlockPositionKey $target
            }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'horizontal_distance_squared'; Descending = $true },
            @{ Expression = 'target_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh safe descent target is outside the wall and within cleanup reach'
    }
    return $selected[0].record
}

function Get-ExactTemporarySurface {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:oak_log' `
        -Bounds $bounds -Faces $Faces)
    $eligible = @($records | Where-Object {
            (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq
                (Get-BlockPositionKey $Position) -and
            (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
            (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                (ConvertTo-CompactJson $ExpectedState)
        })
    if ($eligible.Count -ne 1) {
        throw 'temporary pillar was not freshly delivered with its exact safe state'
    }
    return $eligible[0]
}

function Wait-ForCurrentExactTemporarySurface {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$Position,
        [Parameter(Mandatory)][object]$ExpectedState,
        [AllowNull()][string[]]$Faces,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $Position 'dimension')
        min_x = [int](Get-ObjectProperty $Position 'x')
        min_y = [int](Get-ObjectProperty $Position 'y')
        min_z = [int](Get-ObjectProperty $Position 'z')
        max_x = [int](Get-ObjectProperty $Position 'x')
        max_y = [int](Get-ObjectProperty $Position 'y')
        max_z = [int](Get-ObjectProperty $Position 'z')
    }
    $positionKey = Get-BlockPositionKey $Position
    $state = $InitialState
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $worldRevision = Get-CurrentWorldRevision -State $state
        $records = @(Get-VisibleSurfaceRecords -State $state -Block 'minecraft:oak_log' `
            -Bounds $bounds -Faces $Faces -AllowMissing)
        $eligible = @($records | Where-Object {
                $recordRevision = Get-ObjectProperty $_ 'world_revision'
                (Get-BlockPositionKey (Get-ObjectProperty $_ 'position')) -ceq $positionKey -and
                (Get-ObjectProperty $_ 'placement_item') -ceq 'minecraft:oak_log' -and
                (ConvertTo-CompactJson (Get-ObjectProperty $_ 'state')) -ceq
                    (ConvertTo-CompactJson $ExpectedState) -and
                ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                    $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                    $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                    $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
                [long]$recordRevision -eq $worldRevision
            })
        if ($eligible.Count -eq 1) {
            Add-GateEvent -Event 'temporary_surface_revision_current' -Detail ([ordered]@{
                    world_revision = $worldRevision
                    polls = $poll
                    position = $Position
                })
            return [pscustomobject]@{
                state = $state
                surface = $eligible[0]
                world_revision = $worldRevision
                polls = $poll
            }
        }
        if ($eligible.Count -gt 1) {
            throw 'duplicate current-revision temporary pillar surfaces were delivered'
        }
        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds -Seconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    throw "temporary pillar surface did not reach the current world revision after $MaximumPolls polls"
}

function New-TemporaryClearActionRequest {
    param([Parameter(Mandatory)][object]$Surface)
    $position = Get-ObjectProperty $Surface 'position'
    $state = Get-ObjectProperty $Surface 'state'
    $node = [ordered]@{
        id = 'clear_temporary_wall_pillar'
        op = 'clear_known_block_plan'
        anchor = $position
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @([ordered]@{
                id = 'temporary_pillar'
                offset = [ordered]@{ x = 0; y = 0; z = 0 }
                expected_before = $state
            })
    }
    return New-PrimitiveRequest -Name 'capability_gate_clear_temporary_wall_pillar' `
        -Capabilities @('camera', 'block_break') -Node $node `
        -Duration 15000 -Ticks 300 -Distance 0 -Camera 80 -Breaks 1
}

function New-TemporaryDropSettleActionRequest {
    $node = [ordered]@{
        id = 'settle_temporary_wall_drop'
        op = 'wait_ticks'
        ticks = 40
    }
    return New-PrimitiveRequest -Name 'capability_gate_settle_temporary_wall_drop' `
        -Capabilities @() -Node $node -Duration 3000 -Ticks 40 `
        -Distance 0 -Camera 0
}

function Get-TemporaryDropRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [AllowNull()][Nullable[int]]$MinimumY = $null
    )
    $temporaryY = [int](Get-ObjectProperty $TemporaryPosition 'y')
    $minimumDropY = if ($null -eq $MinimumY) {
        $temporaryY - 1
    } else {
        [int]$MinimumY
    }
    if ($minimumDropY -gt $temporaryY + 1) {
        throw 'temporary drop swept-volume minimum y exceeds its maximum'
    }
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $TemporaryPosition 'dimension')
        min_x = [int](Get-ObjectProperty $TemporaryPosition 'x') - 2
        min_y = $minimumDropY
        min_z = [int](Get-ObjectProperty $TemporaryPosition 'z') - 2
        max_x = [int](Get-ObjectProperty $TemporaryPosition 'x') + 2
        max_y = $temporaryY + 1
        max_z = [int](Get-ObjectProperty $TemporaryPosition 'z') + 2
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
                entity_types = @('minecraft:item')
                displayed_items = @('minecraft:oak_log')
                position_bounds = $bounds
            }))
    $eligible = @($records | Where-Object {
            $position = Get-ObjectProperty $_ 'position'
            $x = [double](Get-ObjectProperty $position 'x')
            $y = [double](Get-ObjectProperty $position 'y')
            $z = [double](Get-ObjectProperty $position 'z')
            (Get-ObjectProperty $_ 'kind') -ceq 'visible_entity' -and
            (Get-ObjectProperty $_ 'entity_type') -ceq 'minecraft:item' -and
            (Get-ObjectProperty $_ 'displayed_item') -ceq 'minecraft:oak_log' -and
            (Get-ObjectProperty $position 'dimension') -ceq
                (Get-ObjectProperty $TemporaryPosition 'dimension') -and
            -not [double]::IsNaN($x) -and -not [double]::IsInfinity($x) -and
            -not [double]::IsNaN($y) -and -not [double]::IsInfinity($y) -and
            -not [double]::IsNaN($z) -and -not [double]::IsInfinity($z) -and
            [Math]::Floor($x) -ge [double]$bounds.min_x -and
            [Math]::Floor($x) -le [double]$bounds.max_x -and
            [Math]::Floor($y) -ge [double]$bounds.min_y -and
            [Math]::Floor($y) -le [double]$bounds.max_y -and
            [Math]::Floor($z) -ge [double]$bounds.min_z -and
            [Math]::Floor($z) -le [double]$bounds.max_z
        })
    return @($eligible)
}

function Assert-NoTemporaryDropRecord {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [AllowNull()][Nullable[int]]$MinimumY = $null
    )
    $eligible = @(Get-TemporaryDropRecords -State $State `
        -TemporaryPosition $TemporaryPosition -MinimumY $MinimumY)
    if ($eligible.Count -ne 0) {
        throw "temporary pillar cleanup area already contains $($eligible.Count) oak-log drop(s)"
    }
}

function Resolve-TemporaryDropRecovery {
    param(
        [Parameter(Mandatory)][long]$InventoryBeforeClear,
        [Parameter(Mandatory)][long]$InventoryAfterSettle,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$VisibleDrops
    )
    $drops = @($VisibleDrops)
    $inventoryDelta = $InventoryAfterSettle - $InventoryBeforeClear
    if ($inventoryDelta -eq 1L -and $drops.Count -eq 0) {
        return [pscustomobject]@{
            recovery_mode = 'passive_pickup'
            inventory_delta = $inventoryDelta
            visible_drop_count = 0
            drop = $null
        }
    }
    if ($inventoryDelta -eq 0L -and $drops.Count -eq 1) {
        return [pscustomobject]@{
            recovery_mode = 'active_collect'
            inventory_delta = $inventoryDelta
            visible_drop_count = 1
            drop = $drops[0]
        }
    }
    throw ('temporary pillar recovery evidence is inconsistent: ' +
        "inventory_delta=$inventoryDelta, visible_oak_drops=$($drops.Count)")
}

function Wait-ForTemporaryDropRecoveryEvidence {
    param(
        [Parameter(Mandatory)][object]$InitialState,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [Parameter(Mandatory)][long]$InventoryBeforeClear,
        [AllowNull()][Nullable[int]]$MinimumY = $null,
        [ValidateRange(1, 40)][int]$MaximumPolls = 40,
        [ValidateRange(1, 1000)][int]$DelayMilliseconds = 50,
        [switch]$AllowUnavailable
    )
    $state = $InitialState
    $lastObservedFrameId = $null
    $observedFrames = 0
    $pendingEmptyObservations = 0
    for ($poll = 1; $poll -le $MaximumPolls; $poll++) {
        $frameId = Get-ObservationFrameId -State $state
        if ($frameId -cne $lastObservedFrameId) {
            $lastObservedFrameId = $frameId
            $observedFrames++
            $inventoryAfterSettle = Get-InventoryCount -State $state `
                -Item 'minecraft:oak_log'
            $visibleDrops = @(Get-TemporaryDropRecords -State $state `
                -TemporaryPosition $TemporaryPosition -MinimumY $MinimumY)
            $inventoryDelta = $inventoryAfterSettle - $InventoryBeforeClear

            # An unchanged inventory with no visible item is not contradictory:
            # the entity observation may trail the block break by one or more
            # frames. Keep the wait read-only and bounded, while every other
            # unsupported combination still fails closed immediately.
            if ($inventoryDelta -eq 0L -and $visibleDrops.Count -eq 0) {
                $pendingEmptyObservations++
            } else {
                $recovery = Resolve-TemporaryDropRecovery `
                    -InventoryBeforeClear $InventoryBeforeClear `
                    -InventoryAfterSettle $inventoryAfterSettle `
                    -VisibleDrops $visibleDrops
                Add-GateEvent -Event 'temporary_drop_recovery_evidence_ready' `
                    -Detail ([ordered]@{
                        frame_id = $frameId
                        polls = $poll
                        observed_frames = $observedFrames
                        pending_empty_observations = $pendingEmptyObservations
                        recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                        inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                        visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
                    })
                return [pscustomobject]@{
                    state = $state
                    recovery = $recovery
                    inventory_after_settle = $inventoryAfterSettle
                    visible_drops = @($visibleDrops)
                    polls = $poll
                    observed_frames = $observedFrames
                    pending_empty_observations = $pendingEmptyObservations
                }
            }
        }

        if ($poll -lt $MaximumPolls) {
            Invoke-GateDelaySeconds ($DelayMilliseconds / 1000.0)
            $state = Get-FreshState
        }
    }
    $message = 'temporary pillar recovery evidence remained unavailable after ' +
        "$MaximumPolls bounded poll(s): observed_frames=$observedFrames, " +
        "pending_empty_observations=$pendingEmptyObservations"
    if (-not $AllowUnavailable) { throw $message }
    Add-GateEvent -Event 'temporary_drop_recovery_evidence_unavailable' `
        -Detail ([ordered]@{
            frame_id = $lastObservedFrameId
            polls = $MaximumPolls
            observed_frames = $observedFrames
            pending_empty_observations = $pendingEmptyObservations
            inventory_delta = $inventoryAfterSettle - $InventoryBeforeClear
            visible_drop_count = @($visibleDrops).Count
            bounded_passive_approach_required = $true
        })
    return [pscustomobject]@{
        state = $state
        recovery = $null
        inventory_after_settle = $inventoryAfterSettle
        visible_drops = @($visibleDrops)
        polls = $MaximumPolls
        observed_frames = $observedFrames
        pending_empty_observations = $pendingEmptyObservations
    }
}

function Sort-WallSupportsFarToNear {
    param(
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][object]$ObserverPosition,
        [switch]$ObserverIsPlayerPosition
    )
    $observerOffset = if ($ObserverIsPlayerPosition) { 0.0 } else { 0.5 }
    return @($Supports | Sort-Object `
            @{ Expression = {
                    $position = Get-ObjectProperty $_ 'position'
                    $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'x') + $observerOffset)
                    $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'z') + $observerOffset)
                    $dx * $dx + $dz * $dz
                }; Descending = $true },
            @{ Expression = { Get-BlockPositionKey (Get-ObjectProperty $_ 'position') }; Descending = $false })
}

function New-TemporaryDropCollectionRequest {
    param(
        [Parameter(Mandatory)][object]$Record,
        [Parameter(Mandatory)][object]$State
    )
    $node = [ordered]@{
        id = 'collect_temporary_wall_pillar'
        op = 'collect_visible_item'
        displayed_item = Get-ObjectProperty $Record 'displayed_item'
        target = Get-ObjectProperty $Record 'position'
    }
    return New-PrimitiveRequest -Name 'capability_gate_collect_temporary_wall_pillar' `
        -Capabilities @('movement') -Node $node -Distance (Get-PolicyDistanceBudget $State) `
        -Camera 0
}

function Get-CurrentSafeWallTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    $worldRevision = Get-CurrentWorldRevision -State $State
    return @(Get-WallScaffoldTraversabilityRecords -State $State | Where-Object {
            $recordRevision = Get-ObjectProperty $_ 'world_revision'
            (Test-SafeTraversabilityRecord $_) -and
            ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
            [long]$recordRevision -eq $worldRevision
        })
}

function Select-TemporaryDropRecoveryApproachRecord {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Records,
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    $world = Get-ObjectProperty $State 'world'
    $player = Get-ObjectProperty $world 'position'
    $dimension = [string](Get-ObjectProperty $TemporaryPosition 'dimension')
    $goalX = [double](Get-ObjectProperty $TemporaryPosition 'x')
    $goalY = [double](Get-ObjectProperty $TemporaryPosition 'y')
    $goalZ = [double](Get-ObjectProperty $TemporaryPosition 'z')
    $playerX = [double](Get-ObjectProperty $player 'x')
    $playerY = [double](Get-ObjectProperty $player 'y')
    $playerZ = [double](Get-ObjectProperty $player 'z')
    $currentGoalDistance = [Math]::Sqrt(
        [Math]::Pow($playerX - $goalX, 2) +
        [Math]::Pow($playerY - $goalY, 2) +
        [Math]::Pow($playerZ - $goalZ, 2))
    $candidates = foreach ($record in $Records) {
        if (-not (Test-SafeTraversabilityRecord $record)) { continue }
        $target = Get-ObjectProperty $record 'navigation_target'
        if ((Get-ObjectProperty $target 'dimension') -cne $dimension) { continue }
        $targetX = [double](Get-ObjectProperty $target 'x')
        $targetY = [double](Get-ObjectProperty $target 'y')
        $targetZ = [double](Get-ObjectProperty $target 'z')
        $travelDistance = [Math]::Sqrt(
            [Math]::Pow($targetX - $playerX, 2) +
            [Math]::Pow($targetY - $playerY, 2) +
            [Math]::Pow($targetZ - $playerZ, 2))
        $goalDistance = [Math]::Sqrt(
            [Math]::Pow($targetX - $goalX, 2) +
            [Math]::Pow($targetY - $goalY, 2) +
            [Math]::Pow($targetZ - $goalZ, 2))
        if ($travelDistance -ge 1 -and $travelDistance -le 8 -and
            $goalDistance -lt $currentGoalDistance) {
            [pscustomobject]@{
                record = $record
                goal_distance = $goalDistance
                progress = $currentGoalDistance - $goalDistance
                travel_distance = $travelDistance
            }
        }
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'goal_distance'; Descending = $false },
            @{ Expression = 'progress'; Descending = $true },
            @{ Expression = 'travel_distance'; Descending = $false },
            @{ Expression = {
                    ConvertTo-CompactJson (Get-ObjectProperty $_.record 'navigation_target')
                }; Descending = $false } | Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no current safe traversability makes three-dimensional progress toward temporary recovery'
    }
    return $selected[0].record
}

function Get-CurrentTemporaryDropPickupTraversabilityRecords {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$Drop
    )
    # visible_entity only proves that the item exists. collect_visible_item also
    # requires an independently delivered, current safe pickup cell and route.
    # Query a bounded area centred on the observed item, because break physics
    # can move it just beyond the construction footprint. The observed position
    # is only a filter centre: every Action target remains an unchanged target
    # from one of the returned policy records.
    $worldRevision = Get-CurrentWorldRevision -State $State
    $position = Get-ObjectProperty $Drop 'position'
    $dimension = [string](Get-ObjectProperty $position 'dimension')
    $dropX = [Math]::Floor([double](Get-ObjectProperty $position 'x'))
    $dropY = [Math]::Floor([double](Get-ObjectProperty $position 'y'))
    $dropZ = [Math]::Floor([double](Get-ObjectProperty $position 'z'))
    $bounds = [ordered]@{
        dimension = $dimension
        min_x = $dropX - 4; min_y = $dropY - 3; min_z = $dropZ - 4
        max_x = $dropX + 4; max_y = $dropY + 3; max_z = $dropZ + 4
    }
    $records = @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }) | Where-Object {
            $recordRevision = Get-ObjectProperty $_ 'world_revision'
            (Test-SafeTraversabilityRecord $_) -and
            ($recordRevision -is [sbyte] -or $recordRevision -is [byte] -or
                $recordRevision -is [int16] -or $recordRevision -is [uint16] -or
                $recordRevision -is [int32] -or $recordRevision -is [uint32] -or
                $recordRevision -is [int64] -or $recordRevision -is [uint64]) -and
            [long]$recordRevision -eq $worldRevision
        })
    if ($records.Count -lt 1) {
        throw 'no current safe traversability was delivered for temporary drop pickup'
    }
    Add-GateEvent -Event 'temporary_drop_pickup_traversability_current' `
        -Detail ([ordered]@{
            frame_id = Get-ObservationFrameId -State $State
            world_revision = $worldRevision
            record_count = $records.Count
            query_bounds = $bounds
            drop_position = $position
            records_delivered_for_product_planner_selection = $true
            pickup_cell_selected = $false
        })
    return @($records)
}

function New-WallRowActionPhase {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][ValidateRange(0, 7)][int]$RowIndex,
        [ValidateRange(1, 8)][int]$WallWidth = $Supports.Count
    )
    if ($Supports.Count -ne $WallWidth) {
        throw "a wall row must contain exactly $WallWidth supports"
    }
    $placementStateRef = [string](Get-ObjectProperty $Source 'placement_state_ref')
    if ($placementStateRef -cnotmatch '^psr_[0-9a-f]{32}$') {
        throw 'wall source did not retain a delivered placement_state_ref'
    }
    $targets = @($Supports | ForEach-Object {
            Get-TargetAboveSupport (Get-ObjectProperty $_ 'position')
        })
    $anchor = $targets[0]
    $entries = for ($column = 0; $column -lt $Supports.Count; $column++) {
        $support = $Supports[$column]
        $supportPosition = Get-ObjectProperty $support 'position'
        $supportState = Get-ObjectProperty $support 'state'
        if ($null -eq $supportState) { throw 'wall support did not include a complete state' }
        [ordered]@{
            id = "row_$($RowIndex)_column_$column"
            offset = [ordered]@{
                x = [int](Get-ObjectProperty $targets[$column] 'x') -
                    [int](Get-ObjectProperty $anchor 'x')
                y = [int](Get-ObjectProperty $targets[$column] 'y') -
                    [int](Get-ObjectProperty $anchor 'y')
                z = [int](Get-ObjectProperty $targets[$column] 'z') -
                    [int](Get-ObjectProperty $anchor 'z')
            }
            placement_state_ref = $placementStateRef
            support = [ordered]@{
                # Both values are retained from the fresh visible_surface record.
                position = $supportPosition
                face = 'up'
                expected_state = $supportState
                dependency_entry_id = $null
            }
        }
    }
    if (@($entries).Count -gt 8) { throw 'wall row exceeded the eight-entry phase limit' }
    $node = [ordered]@{
        id = "place_wall_row_$RowIndex"
        op = 'apply_known_block_plan'
        anchor = $anchor
        transform = [ordered]@{ rotation = 0; mirror = 'none' }
        entries = @($entries)
    }
    $entryCount = @($entries).Count
    $request = New-PrimitiveRequest -Name "capability_gate_wall_${WallWidth}wide_row_$RowIndex" `
        -Capabilities @('camera', 'block_place') -Node $node `
        -Duration (15000 * $entryCount) -Ticks (300 * $entryCount) `
        -Distance 0 -Camera (80 * $entryCount) -Placements $entryCount
    return [pscustomobject]@{
        request = $request
        targets = @($targets)
        entries = @($entries)
    }
}

function New-WallExternalOracleManifest {
    param(
        [Parameter(Mandatory)][object[]]$Targets,
        [Parameter(Mandatory)][object]$ExpectedState,
        [Parameter(Mandatory)][object]$SourcePosition,
        [Parameter(Mandatory)][object[]]$TemporaryPositions
    )
    $keys = @($Targets | ForEach-Object { Get-BlockPositionKey $_ } | Select-Object -Unique)
    if ($Targets.Count -lt 1 -or $keys.Count -ne $Targets.Count) {
        throw 'wall oracle targets are empty or not unique'
    }
    $temporaryKeys = @($TemporaryPositions | ForEach-Object {
            Get-BlockPositionKey $_
        } | Select-Object -Unique)
    if ($TemporaryPositions.Count -lt 1 -or
        $temporaryKeys.Count -ne $TemporaryPositions.Count) {
        throw 'temporary scaffold positions are empty or not unique'
    }
    if (@($temporaryKeys | Where-Object { $_ -cin $keys }).Count -gt 0) {
        throw 'temporary scaffold overlaps the permanent wall oracle'
    }
    $temporaryScaffolds = @($TemporaryPositions | ForEach-Object {
            [ordered]@{
                position = $_
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                transient_state = $ExpectedState
                after_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                included_in_expected_changed_cells = $false
                cleanup_required = $true
                drop_collection_required = 'minecraft:oak_log'
            }
        })
    [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        dimension = [string](Get-ObjectProperty $Targets[0] 'dimension')
        expected_changed_cell_count = $Targets.Count
        expected_changed_cells = @($Targets | ForEach-Object {
                [ordered]@{
                    position = $_
                    before_state = [ordered]@{
                        block = 'minecraft:air'; properties = [ordered]@{}
                    }
                    after_state = $ExpectedState
                }
            })
        expected_source = [ordered]@{
            position = $SourcePosition
            state = $ExpectedState
            changed = $false
        }
        # Keep the singular field for the established 3x3 artifact reader.
        temporary_scaffold = $temporaryScaffolds[0]
        temporary_scaffolds = $temporaryScaffolds
        temporary_scaffold_count = $temporaryScaffolds.Count
        reject_unlisted_changes = $true
        expected_air_violations = 0
        expected_extra_mutations = 0
    }
}

function New-GateCExternalOracleManifest {
    param(
        [Parameter(Mandatory)][object]$ExpectedState,
        [Parameter(Mandatory)][object]$SourcePosition,
        [Parameter(Mandatory)][object[]]$TemporaryPositions
    )
    $temporaryKeys = @($TemporaryPositions | ForEach-Object {
            Get-BlockPositionKey $_
        } | Select-Object -Unique)
    if ($TemporaryPositions.Count -ne 3 -or
        $temporaryKeys.Count -ne $TemporaryPositions.Count) {
        throw 'Gate C oracle requires exactly three unique temporary scaffold cells'
    }
    $temporaryScaffolds = @($TemporaryPositions | ForEach-Object {
            [ordered]@{
                position = $_
                before_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                transient_state = $ExpectedState
                after_state = [ordered]@{
                    block = 'minecraft:air'; properties = [ordered]@{}
                }
                included_in_expected_changed_cells = $false
                cleanup_required = $true
                drop_collection_required = 'minecraft:oak_log'
            }
        })
    return [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        dimension = [string](Get-ObjectProperty $TemporaryPositions[0] 'dimension')
        expected_changed_cell_count = 0
        expected_changed_cells = @()
        expected_source = [ordered]@{
            position = $SourcePosition
            state = $ExpectedState
            changed = $false
        }
        temporary_scaffolds = $temporaryScaffolds
        temporary_scaffold_count = $temporaryScaffolds.Count
        reject_unlisted_changes = $true
        expected_air_violations = 0
        expected_extra_mutations = 0
        expected_inventory_delta = 0
    }
}

function Invoke-WallGate {
    param(
        [Parameter(Mandatory)][ValidateRange(3, 5)][int]$Width,
        [Parameter(Mandatory)][ValidateRange(3, 5)][int]$Height,
        [Parameter(Mandatory)][ValidateRange(1, 3)][int]$ScaffoldLevels,
        [switch]$MovementCapabilityOnly
    )
    if (($Width -ne 3 -or $Height -ne 3 -or $ScaffoldLevels -ne 1) -and
        ($Width -ne 5 -or $Height -ne 5 -or $ScaffoldLevels -ne 3)) {
        throw 'wall gate supports only the audited 3x3/one-level and 5x5/three-level profiles'
    }
    if ($MovementCapabilityOnly -and
        ($Width -ne 5 -or $Height -ne 5 -or $ScaffoldLevels -ne 3)) {
        throw 'Gate C movement profile must reuse the audited five-wide staircase selector'
    }
    $permanentBlockCount = if ($MovementCapabilityOnly) { 0 } else { $Width * $Height }
    $temporaryBlockCount = if ($MovementCapabilityOnly) {
        3
    } elseif ($Width -eq 5) {
        6
    } else {
        1
    }
    $inventoryBefore = Acquire-OakLogFromChest
    if ($inventoryBefore -lt $permanentBlockCount + $temporaryBlockCount) {
        throw 'normal material acquisition cannot cover the permanent wall and transient scaffold'
    }
    Move-NearDestinationSupport -Width $Width

    $state = Get-FreshState
    $source = Get-OakLogPlacementSource -State $state
    if ($script:SourceObservationCount -ne 1) {
        throw 'wall gate did not obtain exactly one delivery-backed source reference'
    }
    $script:SourceObservationForbidden = $true
    $foundationRecords = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    Add-GateEvent -Event 'wall_foundation_candidates_observed' -Detail ([ordered]@{
            player_position = Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position'
            candidate_count = $foundationRecords.Count
            positions = @($foundationRecords | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
                })
            maximum_stationary_reach = 4.5
        })
    $supports = @(Select-ContiguousWallFoundation -Records $foundationRecords `
        -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
        -Width $Width)
    $wallFoundation = @($supports)
    $wallCenterColumn = Get-BlockColumnKey (Get-ObjectProperty `
        $wallFoundation[[int][Math]::Floor($wallFoundation.Count / 2)] 'position')

    $allTargets = [Collections.Generic.List[object]]::new()
    $rowActions = [Collections.Generic.List[object]]::new()
    $previousRowTargets = if ($MovementCapabilityOnly) {
        @($wallFoundation | ForEach-Object {
                Get-TargetAboveSupport (
                    Get-TargetAboveSupport (Get-ObjectProperty $_ 'position'))
            })
    } else {
        $null
    }
    $lowerRowCount = if ($MovementCapabilityOnly) { 0 } else { 2 }
    for ($row = 0; $row -lt $lowerRowCount; $row++) {
        # Establish execution order before any camera change. Width three fits
        # the shared admission heading; width five does not, so it uses the same
        # face -> fresh exact support -> one-entry boundary as elevated rows.
        $supports = @(Sort-WallSupportsFarToNear -Supports $supports `
            -ObserverPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -ObserverIsPlayerPosition)
        $orderedSupportPositions = @($supports | ForEach-Object {
                Get-ObjectProperty $_ 'position'
            })
        $supportBlock = if ($row -eq 0) { 'minecraft:white_wool' } else { 'minecraft:oak_log' }
        $freshBounds = if ($row -eq 0) {
            $script:DestinationSupportBounds
        } else {
            $script:DestinationWallBounds
        }
        $expectedSupportState = Get-ObjectProperty $supports[0] 'state'
        $rowActionIds = [Collections.Generic.List[string]]::new()
        $rowTerminalStates = [Collections.Generic.List[string]]::new()
        $rowTargets = [Collections.Generic.List[object]]::new()
        if ($Width -eq 3) {
            $pivotSupports = @($supports | Where-Object {
                    (Get-BlockColumnKey (Get-ObjectProperty $_ 'position')) -ceq
                        $wallCenterColumn
                })
            if ($pivotSupports.Count -ne 1) {
                throw 'fresh three-wide row did not retain its unique center pivot'
            }
            $faceSupport = $pivotSupports[0]
            Invoke-FaceSupport -Support $faceSupport
            $currentRow = Wait-ForCurrentExactWallSupportRow `
                -InitialState (Get-FreshState) -Block $supportBlock -Bounds $freshBounds `
                -ExpectedPositions $orderedSupportPositions `
                -ExpectedState $expectedSupportState
            $state = $currentRow.state
            $supports = @($currentRow.supports)
            Add-GateEvent -Event 'wall_row_heading_admitted' -Detail ([ordered]@{
                    row = $row; entry = 0
                    face_target = Get-ObjectProperty $faceSupport 'position'
                    first_execution_support = Get-ObjectProperty $supports[0] 'position'
                    support_count = $supports.Count
                    heading_strategy = 'center_pivot_batch'
                    proof = 'post_face_frame_exact_ordered_row'
                })
            $phase = New-WallRowActionPhase -Source $source -Supports $supports `
                -RowIndex $row -WallWidth $Width
            $placementFrameId = Get-ObservationFrameId -State $state
            $terminal = Invoke-ActionRequest -Request $phase.request -WallTimeoutSeconds 120
            $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
            $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
            $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
            foreach ($target in @($phase.targets)) { $rowTargets.Add($target) }
        } else {
            for ($entry = 0; $entry -lt $orderedSupportPositions.Count; $entry++) {
                # Every successful placement invalidates the preceding surface
                # frame. Reacquire this exact still-unbuilt support before even
                # the next face Action, then reacquire it again after facing.
                if ($entry -eq 0) { $state = Get-FreshState }
                $currentBeforeFace = Wait-ForCurrentExactWallSupportRow `
                    -InitialState $state -Block $supportBlock -Bounds $freshBounds `
                    -ExpectedPositions @($orderedSupportPositions[$entry]) `
                    -ExpectedState $expectedSupportState
                $state = $currentBeforeFace.state
                $supportBeforeFace = @($currentBeforeFace.supports)[0]
                Invoke-FaceSupport -Support $supportBeforeFace
                $currentAfterFace = Wait-ForCurrentExactWallSupportRow `
                    -InitialState (Get-FreshState) -Block $supportBlock -Bounds $freshBounds `
                    -ExpectedPositions @($orderedSupportPositions[$entry]) `
                    -ExpectedState $expectedSupportState
                $state = $currentAfterFace.state
                $freshSupport = @($currentAfterFace.supports)[0]
                Add-GateEvent -Event 'wall_row_heading_admitted' -Detail ([ordered]@{
                        row = $row; entry = $entry
                        face_target = Get-ObjectProperty $supportBeforeFace 'position'
                        first_execution_support = Get-ObjectProperty $freshSupport 'position'
                        support_count = 1
                        heading_strategy = 'first_entry_singleton'
                        proof = 'post_face_frame_exact_ordered_row'
                    })
                $single = New-OneOakLogPlacementPhase -Source $source `
                    -Support $freshSupport -UseStateRef
                $placementFrameId = Get-ObservationFrameId -State $state
                $terminal = Invoke-ActionRequest -Request $single.request -WallTimeoutSeconds 60
                $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
                $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
                $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
                $rowTargets.Add($single.target)
            }
        }
        foreach ($target in @($rowTargets)) { $allTargets.Add($target) }
        $rowActions.Add([ordered]@{
                row = $row
                action_id = $rowActionIds[0]
                action_ids = @($rowActionIds)
                action_count = $rowActionIds.Count
                terminal_state = $rowTerminalStates[$rowTerminalStates.Count - 1]
                terminal_states = @($rowTerminalStates)
                entry_count = $rowTargets.Count
                maximum_entries_per_action = if ($Width -eq 3) { 3 } else { 1 }
                stationary = $true
                order = 'far_to_near'
                targets = @($rowTargets)
            })

        $previousRowTargets = @($rowTargets)
        if ($row -eq 0) {
            $currentPlacedRow = Wait-ForCurrentExactWallSupportRow `
                -InitialState $state -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions @($rowTargets) `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentPlacedRow.state
            $supports = @($currentPlacedRow.supports)
            Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
                    row = $row; positions = @($rowTargets); support_count = $supports.Count
                })
        }
    }

    $temporaryPositions = [Collections.Generic.List[object]]::new()
    $temporaryScaffolds = [Collections.Generic.List[object]]::new()
    $temporaryColumns = [Collections.Generic.List[object]]::new()
    $descentRoute = [Collections.Generic.List[object]]::new()
    $temporaryBasePosition = $null
    $temporaryStaircasePlan = $null
    $cleanupGroundTarget = $null
    $gateCStepUp = $null

    $currentTemporarySupports = Wait-ForCurrentVisibleSurfaceRecords `
        -InitialState $state -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') `
        -ExcludePlayerFeetAbove
    $state = $currentTemporarySupports.state
    $temporarySupports = @($currentTemporarySupports.records)
    $temporaryTraversability = @(Get-WallScaffoldTraversabilityRecords -State $state)
    if ($Width -eq 3) {
        $temporarySite = Select-TemporaryPillarSite `
            -WhiteWoolRecords $temporarySupports `
            -TraversabilityRecords $temporaryTraversability `
            -WallFoundation $wallFoundation -RowOneTargets $previousRowTargets `
            -NavigationTolerance $script:PillarNavigationTolerance
        $temporaryBasePosition = Get-ObjectProperty `
            (Get-ObjectProperty $temporarySite 'navigation_record') 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $temporarySite.navigation_record `
            -Tolerance $script:PillarNavigationTolerance -Step 'ground_to_single' `
            -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $temporarySite -Role 'single' -Height 1 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'single'; height = 1; base_position = $temporaryBasePosition
                top_position = $column.top_position
            })
    } else {
        # The best high base can initially be hidden below the staging pose.
        # Move to a delivered safe survey target, then reacquire all candidate UP
        # faces at the same world revision instead of treating occlusion as air.
        $surveyRecord = Select-TemporaryStaircaseSurveyRecord `
            -Records $temporaryTraversability `
            -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position') `
            -WallFoundation $wallFoundation
        $survey = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $surveyRecord `
            -Tolerance $script:ConstructionNavigationTolerance `
            -Step 'staging_to_survey_stance' `
            -Event 'wall_temporary_survey_navigation_terminal'
        $state = $survey.state
        $currentTemporarySupports = Wait-ForCurrentVisibleSurfaceRecords `
            -InitialState $state -Block 'minecraft:white_wool' `
            -Bounds $script:DestinationSupportBounds -Faces @('up') `
            -ExcludePlayerFeetAbove
        $state = $currentTemporarySupports.state
        $temporarySupports = @($currentTemporarySupports.records)
        $temporaryTraversability = @(Get-WallScaffoldTraversabilityRecords -State $state)
        $temporaryStaircasePlan = Select-TemporaryStaircasePlan `
            -WhiteWoolRecords $temporarySupports `
            -TraversabilityRecords $temporaryTraversability `
            -WallFoundation $wallFoundation -RowOneTargets $previousRowTargets `
            -NavigationTolerance $script:ConstructionNavigationTolerance
        $cleanupGroundTarget = Get-ObjectProperty `
            $temporaryStaircasePlan.ground_record 'navigation_target'
        Add-GateEvent -Event 'wall_temporary_staircase_selected' -Detail ([ordered]@{
                shape = '3-2-1'
                high = $temporaryStaircasePlan.high.target
                medium = $temporaryStaircasePlan.medium.target
                low = $temporaryStaircasePlan.low.target
                ground = $cleanupGroundTarget
                maximum_wall_tolerance_bound_squared =
                    $temporaryStaircasePlan.maximum_wall_tolerance_bound_squared
                maximum_cleanup_tolerance_bound_squared =
                    $temporaryStaircasePlan.maximum_cleanup_tolerance_bound_squared
                target_records_from_policy_delivery = $true
            })

        # Build low first. Each later base is refreshed at the current world
        # revision before navigation so a prior pillar cannot stale its UP face.
        $lowSite = Get-CurrentTemporaryScaffoldSite -State $state `
            -ExpectedSite $temporaryStaircasePlan.low `
            -NavigationRecord $temporaryStaircasePlan.low.navigation_record
        $state = $lowSite.state
        $lowNavigation = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
            -ExpectedTarget $temporaryStaircasePlan.low.target
        $state = $lowNavigation.state
        $lowRecord = $lowNavigation.record
        $lowSite.site.navigation_record = $lowRecord
        $lowSite.site.target = Get-ObjectProperty $lowRecord 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $lowRecord -Tolerance $script:PillarNavigationTolerance `
            -Step 'ground_to_low_base' -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $lowSite.site -Role 'low' -Height 1 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'low'; height = 1; base_position = $lowSite.site.target
                top_position = $column.top_position
            })

        $mediumSite = Get-CurrentTemporaryScaffoldSite -State $state `
            -ExpectedSite $temporaryStaircasePlan.medium `
            -NavigationRecord $temporaryStaircasePlan.medium.navigation_record
        $state = $mediumSite.state
        $mediumNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $temporaryStaircasePlan.medium.target `
            -TargetY ([int]$temporaryStaircasePlan.medium.target.y)
        $state = $mediumNavigation.state
        $mediumRecord = $mediumNavigation.record
        $mediumSite.site.navigation_record = $mediumRecord
        $mediumSite.site.target = Get-ObjectProperty $mediumRecord 'navigation_target'
        $raise = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $mediumRecord -Tolerance $script:PillarNavigationTolerance `
            -Step 'low_top_to_medium_base' -Event 'wall_temporary_pillar_navigation_terminal'
        $state = $raise.state
        $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
            -Site $mediumSite.site -Role 'medium' -Height 2 `
            -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
        $state = $column.state
        foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
        foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
        $temporaryColumns.Add([ordered]@{
                role = 'medium'; height = 2; base_position = $mediumSite.site.target
                top_position = $column.top_position
            })

        $lowTopNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $temporaryStaircasePlan.low.target `
            -TargetY ([int]$temporaryStaircasePlan.low.target.y + 1)
        $state = $lowTopNavigation.state
        $lowTopRecord = $lowTopNavigation.record
        $step = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $lowTopRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step 'medium_top_to_low_top' -Event 'wall_temporary_build_route_terminal'
        $state = $step.state
        if ($MovementCapabilityOnly) {
            $descentRoute.Add([ordered]@{
                    step = 'medium_top_to_low_top'
                    action_id = [string](Get-ObjectProperty $step.terminal 'action_id')
                    target = $step.target
                    horizontal_manhattan = $step.horizontal_manhattan
                    absolute_y_delta = $step.absolute_y_delta
                    target_from_policy_delivery = $true
                })
        }
        $groundNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
            -TargetColumn $cleanupGroundTarget -TargetY ([int]$cleanupGroundTarget.y)
        $state = $groundNavigation.state
        $groundRecord = $groundNavigation.record
        $step = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $groundRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step 'low_top_to_ground' -Event 'wall_temporary_build_route_terminal'
        $state = $step.state
        if ($MovementCapabilityOnly) {
            $descentRoute.Add([ordered]@{
                    step = 'low_top_to_ground'
                    action_id = [string](Get-ObjectProperty $step.terminal 'action_id')
                    target = $step.target
                    horizontal_manhattan = $step.horizontal_manhattan
                    absolute_y_delta = $step.absolute_y_delta
                    target_from_policy_delivery = $true
                })

            # Gate C probes one upward full-block edge only after a complete,
            # already-proved descent to ground.  Missing policy evidence or a
            # fail-closed admission is a capability result, not permission to
            # synthesize a target.  Any accepted Action is still waited to a
            # terminal state before the shared top-down cleanup runs.
            $gateCStepUp = [ordered]@{
                status = 'not_delivered'
                target = $temporaryStaircasePlan.low.target
                target_from_policy_delivery = $false
                action_id = $null
                failure = $null
                returned_to_ground = $false
            }
            try {
                $upNavigation = Wait-ForAdjacentScaffoldNavigationRecord `
                    -InitialState $state -TargetColumn $temporaryStaircasePlan.low.target `
                    -TargetY ([int]$temporaryStaircasePlan.low.target.y + 1)
                $state = $upNavigation.state
                $upRecord = $upNavigation.record
                $upRequest = New-NavigationActionRequest -NavigationRecord $upRecord `
                    -State $state -Tolerance $script:PillarNavigationTolerance
                $upAttempt = Invoke-ActionRequest -Request $upRequest `
                    -WallTimeoutSeconds 90 -ReturnFailure -ReturnStartDomainError
                $gateCStepUp.target = Get-ObjectProperty $upRecord 'navigation_target'
                $gateCStepUp.target_from_policy_delivery = [object]::ReferenceEquals(
                    $gateCStepUp.target, $upRequest.program.body[0].target)
                $startError = Get-ObjectProperty $upAttempt 'start_domain_error'
                if ($null -ne $startError) {
                    $gateCStepUp.status = 'admission_rejected'
                    $gateCStepUp.failure = $startError
                } elseif ((Get-ObjectProperty $upAttempt 'state') -ceq 'succeeded') {
                    $gateCStepUp.status = 'passed'
                    $gateCStepUp.action_id = [string](Get-ObjectProperty $upAttempt 'action_id')
                    $state = Get-FreshState
                    $returnNavigation = Wait-ForAdjacentScaffoldNavigationRecord `
                        -InitialState $state -TargetColumn $cleanupGroundTarget `
                        -TargetY ([int]$cleanupGroundTarget.y)
                    $state = $returnNavigation.state
                    $returnStep = Invoke-TemporaryScaffoldNavigation -State $state `
                        -NavigationRecord $returnNavigation.record `
                        -Tolerance $script:PillarNavigationTolerance `
                        -Step 'gate_c_low_top_to_ground' `
                        -Event 'gate_c_step_up_return_terminal'
                    $state = $returnStep.state
                    $gateCStepUp.returned_to_ground = $true
                } else {
                    $gateCStepUp.status = 'terminal_failed'
                    $gateCStepUp.action_id = [string](Get-ObjectProperty $upAttempt 'action_id')
                    $gateCStepUp.failure = Get-ObjectProperty $upAttempt 'failure'
                }
            } catch {
                $gateCStepUp.status = 'evidence_or_return_failed'
                $gateCStepUp.failure = [ordered]@{
                    type = $_.Exception.GetType().FullName
                    message = $_.Exception.Message
                }
            }
            Add-GateEvent -Event 'gate_c_step_up_probe_completed' -Detail $gateCStepUp
        }

        if (-not $MovementCapabilityOnly) {
            $highSite = Get-CurrentTemporaryScaffoldSite -State $state `
                -ExpectedSite $temporaryStaircasePlan.high `
                -NavigationRecord $temporaryStaircasePlan.high.navigation_record
            $state = $highSite.state
            $highNavigation = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
                -ExpectedTarget $temporaryStaircasePlan.high.target
            $state = $highNavigation.state
            $highRecord = $highNavigation.record
            $highSite.site.navigation_record = $highRecord
            $highSite.site.target = Get-ObjectProperty $highRecord 'navigation_target'
            $raise = Invoke-TemporaryScaffoldNavigation -State $state `
                -NavigationRecord $highRecord -Tolerance $script:PillarNavigationTolerance `
                -Step 'ground_to_high_base' -Event 'wall_temporary_pillar_navigation_terminal'
            $state = $raise.state
            $column = Invoke-TemporaryScaffoldColumn -InitialState $state -Source $source `
                -Site $highSite.site -Role 'high' -Height 3 `
                -RaiseNavigationActionId ([string](Get-ObjectProperty $raise.terminal 'action_id'))
            $state = $column.state
            foreach ($position in $column.positions) { $temporaryPositions.Add($position) }
            foreach ($scaffold in $column.scaffolds) { $temporaryScaffolds.Add($scaffold) }
            $temporaryColumns.Add([ordered]@{
                    role = 'high'; height = 3; base_position = $highSite.site.target
                    top_position = $column.top_position
                })
            $temporaryBasePosition = $highSite.site.target
        }
    }

    $firstElevatedRow = if ($MovementCapabilityOnly) { $Height } else { 2 }
    for ($row = $firstElevatedRow; $row -lt $Height; $row++) {
        # Every elevated row is split far-to-near. Each one-entry Action receives
        # its own post-face frame so a nearer block cannot hide a farther UP face.
        $reorientationTargets = @($previousRowTargets | Where-Object {
                (Get-BlockColumnKey $_) -ceq $wallCenterColumn
            })
        if ($reorientationTargets.Count -ne 1) {
            throw 'elevated row did not retain its unique center reorientation target'
        }
        $reorientation = Wait-ForCurrentWallReorientationSurface `
            -InitialState (Get-FreshState) -Position $reorientationTargets[0] `
            -ExpectedState (Get-ObjectProperty $source 'state')
        $state = $reorientation.state
        Invoke-FaceSupport -Support $reorientation.surface
        Add-GateEvent -Event 'wall_elevated_row_reoriented' -Detail ([ordered]@{
            row = $row
            position = $reorientationTargets[0]
            face = Get-ObjectProperty $reorientation.surface 'face'
            world_revision = $reorientation.world_revision
            polls = $reorientation.polls
            proof = 'current_exact_surface_before_up_surface_scan'
        })
        $currentPlacedRow = Wait-ForCurrentExactWallSupportRow `
            -InitialState (Get-FreshState) -Block 'minecraft:oak_log' `
            -Bounds $script:DestinationWallBounds `
            -ExpectedPositions $previousRowTargets `
            -ExpectedState (Get-ObjectProperty $source 'state')
        $state = $currentPlacedRow.state
        $supports = @($currentPlacedRow.supports)
        Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
                row = $row - 1; positions = $previousRowTargets
                support_count = $supports.Count
                raised_by_temporary_pillar = $true
                scaffold_level = $temporaryPositions.Count
            })

        $observerPosition = $temporaryPositions[$temporaryPositions.Count - 1]
        $remainingSupports = @(Sort-WallSupportsFarToNear -Supports $supports `
            -ObserverPosition $observerPosition)
        $rowActionIds = [Collections.Generic.List[string]]::new()
        $rowTerminalStates = [Collections.Generic.List[string]]::new()
        $rowTargets = [Collections.Generic.List[object]]::new()
        while ($remainingSupports.Count -gt 0) {
            $support = $remainingSupports[0]
            Invoke-FaceSupport -Support $support
            $currentSupport = Wait-ForCurrentExactWallSupportRow `
                -InitialState (Get-FreshState) -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions @((Get-ObjectProperty $support 'position')) `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentSupport.state
            $support = @($currentSupport.supports)[0]
            $single = New-OneOakLogPlacementPhase -Source $source -Support $support `
                -UseStateRef
            $placementFrameId = Get-ObservationFrameId -State $state
            $terminal = Invoke-ActionRequest -Request $single.request -WallTimeoutSeconds 60
            $state = Wait-ForObservationFrameAdvance -PreviousFrameId $placementFrameId
            $rowActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
            $rowTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
            $rowTargets.Add($single.target)
            $allTargets.Add($single.target)
            Add-GateEvent -Event 'wall_elevated_cell_terminal' -Detail ([ordered]@{
                    row = $row
                    action_id = [string](Get-ObjectProperty $terminal 'action_id')
                    support = Get-ObjectProperty $support 'position'
                    target = $single.target
                    remaining_cells = $remainingSupports.Count - 1
                })

            $remainingPositions = @($remainingSupports | Select-Object -Skip 1 | ForEach-Object {
                    Get-ObjectProperty $_ 'position'
            })
            if ($remainingPositions.Count -eq 0) { break }
            $currentRemaining = Wait-ForCurrentExactWallSupportRow `
                -InitialState $state -Block 'minecraft:oak_log' `
                -Bounds $script:DestinationWallBounds `
                -ExpectedPositions $remainingPositions `
                -ExpectedState (Get-ObjectProperty $source 'state')
            $state = $currentRemaining.state
            $remainingSupports = @($currentRemaining.supports)
        }
        $previousRowTargets = @($rowTargets)
        $rowActions.Add([ordered]@{
                row = $row
                action_ids = @($rowActionIds)
                action_count = $rowActionIds.Count
                terminal_states = @($rowTerminalStates)
                entry_count = $rowTargets.Count
                maximum_entries_per_action = 1
                stationary = $true
                order = 'far_to_near'
                targets = @($rowTargets)
        })
    }

    if ($Width -eq 5 -and -not $MovementCapabilityOnly) {
        $descentSteps = @(
            [pscustomobject]@{
                name = 'high_top_to_medium_top'
                column = $temporaryStaircasePlan.medium.target
                y = [int]$temporaryStaircasePlan.medium.target.y + 2
            },
            [pscustomobject]@{
                name = 'medium_top_to_low_top'
                column = $temporaryStaircasePlan.low.target
                y = [int]$temporaryStaircasePlan.low.target.y + 1
            },
            [pscustomobject]@{
                name = 'low_top_to_ground'
                column = $cleanupGroundTarget
                y = [int]$cleanupGroundTarget.y
            }
        )
        foreach ($descentStep in $descentSteps) {
            $descentNavigation = Wait-ForAdjacentScaffoldNavigationRecord -InitialState $state `
                -TargetColumn $descentStep.column -TargetY $descentStep.y
            $state = $descentNavigation.state
            $record = $descentNavigation.record
            $routeStep = Invoke-TemporaryScaffoldNavigation -State $state `
                -NavigationRecord $record -Tolerance $script:PillarNavigationTolerance `
                -Step $descentStep.name -Event 'wall_temporary_descent_step_terminal'
            $state = $routeStep.state
            $descentRoute.Add([ordered]@{
                    step = $descentStep.name
                    action_id = [string](Get-ObjectProperty $routeStep.terminal 'action_id')
                    target = $routeStep.target
                    horizontal_manhattan = $routeStep.horizontal_manhattan
                    absolute_y_delta = $routeStep.absolute_y_delta
                    target_from_policy_delivery = $true
                })
        }
    }

    $cleanupScaffolds = @($temporaryScaffolds | Sort-Object `
            @{ Expression = { [int](Get-ObjectProperty (Get-ObjectProperty $_ 'position') 'y') }; Descending = $true },
            # At one height, remove the outer/lower column first so it cannot
            # occlude the next inner block from the ground cleanup stance.
            @{ Expression = { [int](Get-ObjectProperty $_ 'column_height') }; Descending = $false },
            @{ Expression = { Get-BlockPositionKey (Get-ObjectProperty $_ 'position') }; Descending = $false })
    $cleanupRecoveryMinimumY = if ($Width -eq 5) {
        [int](Get-ObjectProperty $cleanupGroundTarget 'y')
    } else {
        [int](Get-ObjectProperty $temporaryBasePosition 'y')
    }
    for ($cleanupIndex = 0; $cleanupIndex -lt $cleanupScaffolds.Count; $cleanupIndex++) {
        $scaffold = $cleanupScaffolds[$cleanupIndex]
        $temporaryPosition = Get-ObjectProperty $scaffold 'position'
        $state = Get-FreshState
        # Collection may walk back toward the scaffold. Before every clear, move
        # to a newly delivered safe ground target so the block is never broken
        # underfoot. A normal passive pickup may still race the post-break entity
        # observation; the inventory/drop reconciliation below proves either
        # passive pickup or an explicit collect. The product pathfinder must prove
        # a route through staircase blocks that still exist; do not name a top
        # waypoint that an earlier cleanup removed.
        $descentRecord = if ($Width -eq 5) {
            $currentDescent = Wait-ForExactScaffoldNavigationRecord -InitialState $state `
                -ExpectedTarget $cleanupGroundTarget
            $state = $currentDescent.state
            $currentDescent.record
        } else {
            $descentRecords = @(Get-WallScaffoldTraversabilityRecords -State $state)
            Select-TemporaryPillarDescentRecord -Records $descentRecords `
                -TemporaryPosition $temporaryBasePosition -WallFoundation $wallFoundation `
                -NavigationTolerance $script:ConstructionNavigationTolerance
        }
        $descentTarget = Get-ObjectProperty $descentRecord 'navigation_target'
        $descentRequest = New-NavigationActionRequest -NavigationRecord $descentRecord `
            -State $state -Tolerance $script:ConstructionNavigationTolerance
        Add-GateEvent -Event 'wall_temporary_descent_selected' -Detail ([ordered]@{
                cleanup_order = $cleanupIndex + 1
                column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                frame_id = [string](Get-ObjectProperty `
                    (Get-ObjectProperty $state 'observation') 'latest_frame_id')
                target = $descentTarget
                target_verbatim = [object]::ReferenceEquals(
                    $descentTarget, $descentRequest.program.body[0].target)
                status = [string](Get-ObjectProperty $descentRecord 'status')
            })
        # Active drop collection can leave the player several blocks away from
        # the cleanup stance.  A valid route may then change shape while the
        # Action is moving.  Reacquire the same exact policy-delivered target
        # after the narrow replan signal instead of treating the first bounded
        # slice as the whole cleanup attempt.
        $descentStep = Invoke-TemporaryScaffoldNavigation -State $state `
            -NavigationRecord $descentRecord `
            -Tolerance $script:PillarNavigationTolerance `
            -Step "cleanup_ground_$($cleanupIndex + 1)" `
            -Event 'wall_temporary_cleanup_descent_terminal'
        $state = $descentStep.state
        $descentTerminal = $descentStep.terminal

        $currentTemporary = Wait-ForCurrentExactTemporarySurface `
            -InitialState $state -Position $temporaryPosition `
            -ExpectedState (Get-ObjectProperty $source 'state') -Faces $null
        $state = $currentTemporary.state
        $temporarySurface = $currentTemporary.surface
        Invoke-FaceSupport -Support $temporarySurface
        $currentTemporary = Wait-ForCurrentExactTemporarySurface `
            -InitialState (Get-FreshState) -Position $temporaryPosition `
            -ExpectedState (Get-ObjectProperty $source 'state') -Faces $null
        $state = $currentTemporary.state
        $temporarySurface = $currentTemporary.surface
        Assert-NoTemporaryDropRecord -State $state -TemporaryPosition $temporaryPosition `
            -MinimumY $cleanupRecoveryMinimumY
        $inventoryBeforeClear = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
        $clearRequest = New-TemporaryClearActionRequest -Surface $temporarySurface
        $clearFrameId = Get-ObservationFrameId -State $state
        $clearTerminal = Invoke-ActionRequest -Request $clearRequest -WallTimeoutSeconds 60

        # A freshly broken item is still moving. Wait without observing it, then
        # reconcile passive pickup or bind active collect to one fresh drop pose.
        $settleRequest = New-TemporaryDropSettleActionRequest
        $settleTerminal = Invoke-ActionRequest -Request $settleRequest -WallTimeoutSeconds 60
        $state = Wait-ForObservationFrameAdvance -PreviousFrameId $clearFrameId
        $recoveryApproachActionIds = [Collections.Generic.List[string]]::new()
        $collectAdmissionDeferrals = 0
        $collectTerminal = $null
        for ($recoveryApproach = 0; $recoveryApproach -le 2; $recoveryApproach++) {
            $recoveryEvidence = Wait-ForTemporaryDropRecoveryEvidence `
                -InitialState $state -TemporaryPosition $temporaryPosition `
                -InventoryBeforeClear $inventoryBeforeClear `
                -MinimumY $cleanupRecoveryMinimumY `
                -AllowUnavailable
            $state = Get-ObjectProperty $recoveryEvidence 'state'
            $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
            $approachGoal = $temporaryPosition
            $approachReason = 'occluded_drop'
            $approachRecords = $null

            if ($null -ne $recovery) {
                $recoveryMode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                if ($recoveryMode -ceq 'passive_pickup') { break }
                if ($recoveryMode -cne 'active_collect') {
                    throw "unsupported temporary recovery mode: $recoveryMode"
                }

                $drop = Get-ObjectProperty $recovery 'drop'
                Add-GateEvent -Event 'wall_temporary_drop_observed' -Detail ([ordered]@{
                        cleanup_order = $cleanupIndex + 1
                        column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                        scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                        frame_id = Get-ObservationFrameId -State $state
                        recovery_mode = 'active_collect'
                        inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                        position = Get-ObjectProperty $drop 'position'
                        displayed_item = [string](Get-ObjectProperty $drop 'displayed_item')
                        settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
                        settle_ticks = 40
                })
                # The fresh item record and current traversability are
                # independent policy evidence. Let the product planner admit
                # collect first; TARGET_UNKNOWN is a recoverable request for a
                # new viewpoint, not permission to reuse either stale record.
                $approachRecords = @(Get-CurrentTemporaryDropPickupTraversabilityRecords `
                        -State $state -Drop $drop)
                $collectRequest = New-TemporaryDropCollectionRequest -Record $drop -State $state
                $collectAttempt = Invoke-ActionRequest -Request $collectRequest `
                    -WallTimeoutSeconds 90 -ReturnStartDomainError
                $startDomainError = Get-ObjectProperty $collectAttempt 'start_domain_error'
                if ($null -eq $startDomainError) {
                    $collectTerminal = $collectAttempt
                    break
                }
                if ((Get-ObjectProperty $startDomainError 'code') -cne 'TARGET_UNKNOWN' -or
                    (Get-ObjectProperty $startDomainError 'recoverable') -isnot [bool] -or
                    -not [bool](Get-ObjectProperty $startDomainError 'recoverable')) {
                    throw ('temporary drop collect admission failed closed: ' +
                        (ConvertTo-CompactJson $startDomainError))
                }
                $collectAdmissionDeferrals++
                $approachReason = 'collect_target_unknown'
                $rejectedFrameId = Get-ObservationFrameId -State $state
                # The admission attempt may advance both frame and policy
                # state. Discard every pre-rejection item/traversability object,
                # wait for a new frame, and reacquire the recovery evidence
                # before selecting the bounded approach Action.
                $approachRecords = $null
                $state = Wait-ForObservationFrameAdvance `
                    -PreviousFrameId $rejectedFrameId
                $recoveryEvidence = Wait-ForTemporaryDropRecoveryEvidence `
                    -InitialState $state -TemporaryPosition $temporaryPosition `
                    -InventoryBeforeClear $inventoryBeforeClear `
                    -MinimumY $cleanupRecoveryMinimumY `
                    -AllowUnavailable
                $state = Get-ObjectProperty $recoveryEvidence 'state'
                $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
                Add-GateEvent -Event 'wall_temporary_drop_collect_admission_deferred' `
                    -Detail ([ordered]@{
                        cleanup_order = $cleanupIndex + 1
                        approach = $recoveryApproach + 1
                        code = 'TARGET_UNKNOWN'
                        recoverable = $true
                        old_drop_reuse_allowed = $false
                        fresh_observation_required = $true
                        rejected_frame_id = $rejectedFrameId
                        fresh_frame_id = Get-ObservationFrameId -State $state
                        fresh_recovery_mode = if ($null -eq $recovery) {
                            'unavailable'
                        } else {
                            [string](Get-ObjectProperty $recovery 'recovery_mode')
                        }
                    })
                if ($null -ne $recovery -and
                    (Get-ObjectProperty $recovery 'recovery_mode') -ceq 'passive_pickup') {
                    break
                }
                if ($null -ne $recovery) {
                    if ((Get-ObjectProperty $recovery 'recovery_mode') -cne 'active_collect') {
                        throw 'fresh temporary drop recovery returned an unsupported mode'
                    }
                    $drop = Get-ObjectProperty $recovery 'drop'
                    $approachGoal = Get-ObjectProperty $drop 'position'
                    $approachRecords = @(Get-CurrentTemporaryDropPickupTraversabilityRecords `
                            -State $state -Drop $drop)
                } else {
                    $approachGoal = $temporaryPosition
                    $approachReason = 'collect_target_unknown_drop_occluded'
                }
            }

            if ($recoveryApproach -eq 2) {
                throw 'temporary pillar recovery exhausted 2 bounded passive approach Action(s)'
            }
            if ($null -eq $approachRecords) {
                # Clear success plus an unchanged inventory can leave the drop
                # occluded by the wall or the lower scaffold.
                $approachRecords = @(Get-CurrentSafeWallTraversabilityRecords -State $state)
            }
            if ($approachRecords.Count -lt 1) {
                throw 'no current safe traversability was delivered for temporary drop passive recovery'
            }
            $approachRecord = Select-TemporaryDropRecoveryApproachRecord `
                -Records $approachRecords -State $state `
                -TemporaryPosition $approachGoal
            $approachTarget = Get-ObjectProperty $approachRecord 'navigation_target'
            Add-GateEvent -Event 'wall_temporary_drop_passive_approach_selected' `
                -Detail ([ordered]@{
                    cleanup_order = $cleanupIndex + 1
                    approach = $recoveryApproach + 1
                    reason = $approachReason
                    frame_id = Get-ObservationFrameId -State $state
                    world_revision = Get-CurrentWorldRevision -State $state
                    target = $approachTarget
                    target_from_current_policy_delivery = $true
                    synthetic_target_allowed = $false
                })
            # A normal construction tolerance may stop near the edge of the
            # adjacent cell, outside the item pickup overlap.  This route
            # exists only to reconcile one freshly cleared block, so reach the
            # policy-delivered cell centre tightly.
            $approachResult = Invoke-TemporaryScaffoldNavigation `
                -State $state -NavigationRecord $approachRecord `
                -Tolerance $script:TemporaryDropRecoveryNavigationTolerance `
                -Step "cleanup-$($cleanupIndex + 1)-passive-recovery-$($recoveryApproach + 1)" `
                -Event 'wall_temporary_drop_passive_approach_terminal' `
                -ReturnAfterResliceableFailure
            $state = Get-ObjectProperty $approachResult 'state'
            $recoveryApproachActionIds.Add([string](Get-ObjectProperty `
                (Get-ObjectProperty $approachResult 'terminal') 'action_id'))
        }
        $state = Get-ObjectProperty $recoveryEvidence 'state'
        $inventoryAfterSettle = [long](Get-ObjectProperty `
            $recoveryEvidence 'inventory_after_settle')
        $visibleDrops = @(Get-ObjectProperty $recoveryEvidence 'visible_drops')
        $recovery = Get-ObjectProperty $recoveryEvidence 'recovery'
        $drop = Get-ObjectProperty $recovery 'drop'
        Add-GateEvent -Event 'wall_temporary_drop_recovery_selected' -Detail ([ordered]@{
                cleanup_order = $cleanupIndex + 1
                column_role = [string](Get-ObjectProperty $scaffold 'column_role')
                scaffold_level = [int](Get-ObjectProperty $scaffold 'level')
                frame_id = [string](Get-ObjectProperty `
                    (Get-ObjectProperty $state 'observation') 'latest_frame_id')
                recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
                inventory_before_clear = $inventoryBeforeClear
                inventory_after_settle = $inventoryAfterSettle
                inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
                visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
                position = if ($null -eq $drop) {
                    $null
                } else {
                    Get-ObjectProperty $drop 'position'
                }
                settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
                settle_ticks = 40
                evidence_polls = [int](Get-ObjectProperty $recoveryEvidence 'polls')
                evidence_observed_frames = [int](Get-ObjectProperty `
                    $recoveryEvidence 'observed_frames')
                evidence_pending_empty_observations = [int](Get-ObjectProperty `
                    $recoveryEvidence 'pending_empty_observations')
        })
        $scaffold.descent_target = $descentTarget
        $scaffold.descent_action_id = [string](Get-ObjectProperty $descentTerminal 'action_id')
        $scaffold.cleanup_order = $cleanupIndex + 1
        $scaffold.clear_action_id = [string](Get-ObjectProperty $clearTerminal 'action_id')
        $scaffold.settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
        $scaffold.settle_ticks = 40
        $scaffold.recovery_mode = [string](Get-ObjectProperty $recovery 'recovery_mode')
        $scaffold.inventory_before_clear = $inventoryBeforeClear
        $scaffold.inventory_after_settle = $inventoryAfterSettle
        $scaffold.inventory_delta = [long](Get-ObjectProperty $recovery 'inventory_delta')
        $scaffold.visible_drop_count = [int](Get-ObjectProperty $recovery 'visible_drop_count')
        $scaffold.recovery_evidence_polls = [int](Get-ObjectProperty `
            $recoveryEvidence 'polls')
        $scaffold.recovery_evidence_observed_frames = [int](Get-ObjectProperty `
            $recoveryEvidence 'observed_frames')
        $scaffold.recovery_pending_empty_observations = [int](Get-ObjectProperty `
            $recoveryEvidence 'pending_empty_observations')
        $scaffold.recovery_approach_action_ids = @($recoveryApproachActionIds)
        $scaffold.recovery_approach_action_count = $recoveryApproachActionIds.Count
        $scaffold.collect_admission_deferrals = $collectAdmissionDeferrals
        $scaffold.collected_drop_target = if ($null -eq $drop) {
            $null
        } else {
            Get-ObjectProperty $drop 'position'
        }
        $scaffold.collect_action_id = if ($null -eq $collectTerminal) {
            $null
        } else {
            [string](Get-ObjectProperty $collectTerminal 'action_id')
        }
        $scaffold.expected_cleanup = $true
        $scaffold.expected_drop_collection = 'minecraft:oak_log'
        $scaffold.included_in_expected_changed_cells = $false
    }
    $state = Get-FreshState

    if ($script:SourceObservationCount -ne 1) {
        throw 'wall gate re-observed its placement source'
    }
    $inventoryAfter = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
    if ($inventoryAfter -ne $inventoryBefore - $permanentBlockCount) {
        throw "oak-log inventory ledger did not decrease by exactly $permanentBlockCount"
    }
    $targets = @($allTargets)
    $oracle = if ($MovementCapabilityOnly) {
        New-GateCExternalOracleManifest `
            -ExpectedState (Get-ObjectProperty $source 'state') `
            -SourcePosition (Get-ObjectProperty $source 'position') `
            -TemporaryPositions @($temporaryPositions)
    } else {
        New-WallExternalOracleManifest -Targets $targets `
            -ExpectedState (Get-ObjectProperty $source 'state') `
            -SourcePosition (Get-ObjectProperty $source 'position') `
            -TemporaryPositions @($temporaryPositions)
    }
    $gateName = if ($MovementCapabilityOnly) { 'gate-c' } else { "wall-${Width}x${Height}" }
    return [ordered]@{
        gate = $gateName
        wall_dimensions = [ordered]@{ width = $Width; height = $Height; depth = 1 }
        full_cube_block = 'minecraft:oak_log'
        action_coordinates_from_observations_only = $true
        configured_bounds_used_as_observation_filters_only = $true
        material_acquisition = 'fresh_visible_chest_normal_player_transfer'
        placement_identity = 'single_delivery_backed_placement_state_ref'
        source_observations = $script:SourceObservationCount
        source_reobserved = $false
        source_expected_unchanged = $true
        foundation_evidence = 'policy_visible_white_wool_up_faces'
        row_actions = @($rowActions)
        row_phase_count = $rowActions.Count
        wall_placement_action_count = if ($rowActions.Count -eq 0) {
            0
        } else {
            @($rowActions | ForEach-Object {
                    [int](Get-ObjectProperty $_ 'action_count')
                } | Measure-Object -Sum).Sum
        }
        temporary_scaffold = $temporaryScaffolds[0]
        temporary_scaffolds = @($temporaryScaffolds)
        temporary_scaffold_count = $temporaryScaffolds.Count
        temporary_shape = if ($MovementCapabilityOnly) {
            '2-1 staircase'
        } elseif ($Width -eq 5) {
            '3-2-1 staircase'
        } else {
            'single column'
        }
        temporary_columns = @($temporaryColumns)
        temporary_column_count = $temporaryColumns.Count
        descent_route = @($descentRoute)
        descent_action_count = $descentRoute.Count
        total_action_count = @($script:GateEvents | Where-Object {
                (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
            }).Count
        maximum_entries_per_action = if ($rowActions.Count -eq 0) {
            0
        } else {
            @($rowActions | ForEach-Object {
                    [int](Get-ObjectProperty $_ 'maximum_entries_per_action')
                } | Measure-Object -Maximum).Maximum
        }
        phase_entry_limit = 8
        stationary_placement = $true
        exact_target_count = $targets.Count
        exact_targets = $targets
        expected_air_violations = 0
        expected_extra_mutations = 0
        external_oracle_status = 'pending'
        mutation_proof = "$permanentBlockCount unique fresh supports plus $temporaryBlockCount observation-derived temporary blocks cleared top-down and recollected; placement inventory delta minus $permanentBlockCount; external MCA required"
        inventory_before_placement = $inventoryBefore
        inventory_after_placement = $inventoryAfter
        inventory_delta = $inventoryAfter - $inventoryBefore
        capability_complete = -not $MovementCapabilityOnly
        capability_components = if ($MovementCapabilityOnly) {
            [ordered]@{
                pillar_scaffold = 'passed'
                step_down = 'passed'
                step_up = [string](Get-ObjectProperty $gateCStepUp 'status')
                edge_bridge = 'not_expressible_without_safe_crouch_bridge_primitive'
            }
        } else { $null }
        step_up_probe = if ($MovementCapabilityOnly) { $gateCStepUp } else { $null }
        external_oracle = $oracle
    }
}

function Invoke-Wall3x3Gate {
    Invoke-WallGate -Width 3 -Height 3 -ScaffoldLevels 1
}

function Invoke-Wall5x5Gate {
    Invoke-WallGate -Width 5 -Height 5 -ScaffoldLevels 3
}

function Invoke-BuildingGateC {
    Invoke-WallGate -Width 5 -Height 5 -ScaffoldLevels 3 -MovementCapabilityOnly
}

function Write-GateArtifacts {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Result,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    $eventsPath = Join-Path $ArtifactDirectory 'gate-events.jsonl'
    $eventLines = @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ })
    [IO.File]::WriteAllLines($eventsPath, $eventLines, $script:Utf8NoBom)
    $gateResult = Get-ObjectProperty $Result 'gate_result'
    $capabilityComplete = Get-ObjectProperty $gateResult 'capability_complete'
    $manifest = [ordered]@{
        schema_version = 1
        gate = $Gate
        status = if ($null -ne $Failure) {
            'failed'
        } elseif ($capabilityComplete -is [bool] -and -not [bool]$capabilityComplete) {
            'incomplete'
        } else {
            'passed'
        }
        fixed_tools = @($script:AllowedTools)
        fixed_five_only = $true
        normal_player_actions_only = $true
        public_input_release = Get-ObjectProperty $Result 'input_release'
        result = Get-ObjectProperty $Result 'gate_result'
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{ type = $Failure.Exception.GetType().FullName; message = $Failure.Exception.Message }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
    if ($Gate -cin @('wall-3x3', 'wall-5x5', 'gate-c') -and
        $null -ne (Get-ObjectProperty $Result 'gate_result')) {
        $oracle = Get-ObjectProperty (Get-ObjectProperty $Result 'gate_result') 'external_oracle'
        if ($null -eq $oracle) { throw 'wall gate did not produce an external oracle manifest' }
        [IO.File]::WriteAllText(
            (Join-Path $ArtifactDirectory 'external-oracle-manifest.json'),
            (ConvertTo-Json $oracle -Depth 100), $script:Utf8NoBom)
    }
}

function Invoke-McmcpConstructionCapabilityGate {
    $script:ActiveActionId = $null
    $script:SourceObservationForbidden = $false
    $script:SourceObservationCount = 0
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try {
        $initial = Get-FreshState
        Assert-ReadyState -State $initial -Phase 'gate start'
        $gateResult = switch ($Gate) {
            'navigation' { Invoke-NavigationGate }
            'faces-place' { Invoke-PlacementGate }
            'state-ref-ttl' { Invoke-PlacementGate -UseStateRef }
            'wall-3x3' { Invoke-Wall3x3Gate }
            'wall-5x5' { Invoke-Wall5x5Gate }
            'gate-c' { Invoke-BuildingGateC }
        }
    } catch {
        $primaryFailure = $_
    } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $combined = [ordered]@{ gate_result = $gateResult; input_release = $release }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-GateArtifacts -Result $combined -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return $combined
}

if (-not $LibraryOnly) {
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "MCP token file does not exist: $TokenPath"
    }
    $script:Bearer = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenPath)).Trim()
    if ([string]::IsNullOrWhiteSpace($script:Bearer) -or
        $script:Bearer.Contains("`r") -or $script:Bearer.Contains("`n")) {
        throw 'MCP token file is empty or malformed'
    }
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    Assert-FixedFiveToolSurface
    $result = Invoke-McmcpConstructionCapabilityGate
    ConvertTo-Json $result -Depth 100
}
