[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3')]
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
    max_x = -18; max_y = 58; max_z = 15
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
    $responseHeaders = $null
    $response = Invoke-RestMethod -Method Post -Uri $Endpoint -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -NoProxy -MaximumRedirection 0 `
        -TimeoutSec $TimeoutSeconds -ResponseHeadersVariable responseHeaders `
        -Body (ConvertTo-CompactJson ([ordered]@{
                jsonrpc = '2.0'; id = $requestId; method = $Method; params = $Parameters
            }))
    $contentType = [string]$responseHeaders['Content-Type']
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
        [Parameter(Mandatory)][Collections.IDictionary]$Arguments
    )
    if ($Tool -cnotin $script:AllowedTools) {
        throw "capability gate rejected a non-public tool: $Tool"
    }
    Add-GateEvent -Event 'tool_call_started' -Detail ([ordered]@{ tool = $Tool })
    if ($null -ne $script:ToolTransport) {
        $structured = & $script:ToolTransport $Tool $Arguments
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
            throw "$Tool returned a domain error: $diagnostic"
        }
        $structured = Get-ObjectProperty $result 'structuredContent'
    }
    if ($null -eq $structured) { throw "$Tool returned no structured content" }
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

function Get-RecordsFromState {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][string[]]$Kinds,
        [AllowNull()][Collections.IDictionary]$Filter
    )
    $observation = Get-ObjectProperty $State 'observation'
    $frameId = [string](Get-ObjectProperty $observation 'latest_frame_id')
    if ($frameId -cnotmatch '^obs-[0-9a-f]{16}$') {
        throw 'agent_get_state did not announce a valid observation frame'
    }
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
        foreach ($record in @(Get-ObjectProperty $page 'records')) { $records.Add($record) }
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

function Invoke-ActionRequest {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Request,
        [ValidateRange(1, 900)][int]$WallTimeoutSeconds = 180
    )
    $receipt = Invoke-GateTool -Tool 'agent_start_action' -Arguments $Request
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
    Add-GateEvent -Event 'action_terminal' -Detail ([ordered]@{
            action_id = $actionId
            state = [string](Get-ObjectProperty $terminal 'state')
            progress = Get-ObjectProperty $terminal 'progress'
            failure = Get-ObjectProperty $terminal 'failure'
            trace = Get-ObjectProperty $terminal 'trace'
        })
    if ((Get-ObjectProperty $terminal 'state') -cne 'succeeded') {
        $failure = Get-ObjectProperty $terminal 'failure'
        throw "Action ended as $(Get-ObjectProperty $terminal 'state'): $(Get-ObjectProperty $failure 'code')"
    }
    return $terminal
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
        if ($AllowMissing) { return $null }
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
            @{ Expression = 'travel_distance'; Descending = $false },
            @{ Expression = 'goal_distance'; Descending = $false },
            @{ Expression = 'progress'; Descending = $true },
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
        [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 90)
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
    $support = Get-OrNavigateToVisibleSurface -Block 'minecraft:white_wool' `
        -Bounds $script:DestinationSupportBounds -Faces @('up') -ExcludePlayerFeetAbove
    $state = Get-FreshState
    Invoke-ApproachSurface -Record $support -State $state
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
    if ($null -ne $script:DelayTransport) {
        & $script:DelayTransport $Seconds
    } else {
        Start-Sleep -Seconds $Seconds
    }
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
        throw 'no policy-visible contiguous three-block white-wool UP foundation is within stationary reach'
    }
    return @($selected[0].supports)
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

function Get-BlockColumnKey {
    param([Parameter(Mandatory)][object]$Position)
    return ('{0}|{1}|{2}' -f
        [string](Get-ObjectProperty $Position 'dimension'),
        [int](Get-ObjectProperty $Position 'x'),
        [int](Get-ObjectProperty $Position 'z'))
}

function Get-WallGroundTraversabilityRecords {
    param([Parameter(Mandatory)][object]$State)
    $bounds = [ordered]@{
        dimension = [string]$script:DestinationSupportBounds.dimension
        min_x = [int]$script:DestinationSupportBounds.min_x
        min_y = [int]$script:DestinationSupportBounds.min_y + 1
        min_z = [int]$script:DestinationSupportBounds.min_z
        max_x = [int]$script:DestinationSupportBounds.max_x
        max_y = [int]$script:DestinationSupportBounds.max_y + 1
        max_z = [int]$script:DestinationSupportBounds.max_z
    }
    return @(Get-RecordsFromState -State $State -Kinds @('traversability') `
        -Filter ([ordered]@{ position_bounds = $bounds }))
}

function Select-TemporaryPillarSite {
    param(
        [Parameter(Mandatory)][object[]]$WhiteWoolRecords,
        [Parameter(Mandatory)][object[]]$TraversabilityRecords,
        [Parameter(Mandatory)][object[]]$WallFoundation,
        [Parameter(Mandatory)][object[]]$RowOneTargets,
        [ValidateRange(1, 8)][double]$MaximumWallReach = 4.5
    )
    if ($WallFoundation.Count -ne 3 -or $RowOneTargets.Count -ne 3) {
        throw 'temporary pillar selection requires the exact three-column wall footprint'
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
        foreach ($wallSupport in $RowOneTargets) {
            $dx = ([double](Get-ObjectProperty $wallSupport 'x') + 0.5) -
                ([double](Get-ObjectProperty $target 'x') + 0.5)
            $dy = ([double](Get-ObjectProperty $wallSupport 'y') + 1.0) -
                ([double](Get-ObjectProperty $target 'y') + 1.0 + 1.62)
            $dz = ([double](Get-ObjectProperty $wallSupport 'z') + 0.5) -
                ([double](Get-ObjectProperty $target 'z') + 0.5)
            $maximumDistanceSquared = [Math]::Max(
                $maximumDistanceSquared, $dx * $dx + $dy * $dy + $dz * $dz)
        }
        if ([Math]::Sqrt($maximumDistanceSquared) -gt $MaximumWallReach) { continue }
        $candidates.Add([pscustomobject]@{
                support = $support
                navigation_record = $joinedRecord
                maximum_wall_distance_squared = $maximumDistanceSquared
                support_key = Get-BlockPositionKey $position
            })
    }
    $selected = @($candidates | Sort-Object `
            @{ Expression = 'maximum_wall_distance_squared'; Descending = $false },
            @{ Expression = 'support_key'; Descending = $false } |
            Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'no fresh outside-footprint white-wool UP support joins a safe traversability target within raised wall reach'
    }
    return $selected[0]
}

function New-TemporaryPillarActionRequest {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object]$Support
    )
    $supportPosition = Get-ObjectProperty $Support 'position'
    $supportState = Get-ObjectProperty $Support 'state'
    $placementStateRef = [string](Get-ObjectProperty $Source 'placement_state_ref')
    if ((Get-ObjectProperty $Support 'face') -cne 'up' -or
        (Get-ObjectProperty $supportState 'block') -cne 'minecraft:white_wool' -or
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

function Select-TemporaryPillarDescentRecord {
    param(
        [Parameter(Mandatory)][object[]]$Records,
        [Parameter(Mandatory)][object]$TemporaryPosition,
        [Parameter(Mandatory)][object[]]$WallFoundation
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
        # Two to three blocks prevents incidental pickup while retaining normal
        # break reach after the centered descent.
        if ($horizontalDistanceSquared -ge 4.0 -and
            $horizontalDistanceSquared -le 9.0) {
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
    $records = @(Get-VisibleSurfaceRecords -State $State -Block 'minecraft:oak_log' `
        -Bounds $bounds -Faces $null)
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
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    $bounds = [ordered]@{
        dimension = [string](Get-ObjectProperty $TemporaryPosition 'dimension')
        min_x = [int](Get-ObjectProperty $TemporaryPosition 'x') - 1
        min_y = [int](Get-ObjectProperty $TemporaryPosition 'y') - 1
        min_z = [int](Get-ObjectProperty $TemporaryPosition 'z') - 1
        max_x = [int](Get-ObjectProperty $TemporaryPosition 'x') + 1
        max_y = [int](Get-ObjectProperty $TemporaryPosition 'y') + 1
        max_z = [int](Get-ObjectProperty $TemporaryPosition 'z') + 1
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

function Get-TemporaryDropRecord {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    $eligible = @(Get-TemporaryDropRecords -State $State `
        -TemporaryPosition $TemporaryPosition)
    if ($eligible.Count -ne 1) {
        throw "temporary pillar cleanup requires exactly one fresh nearby oak-log drop; observed $($eligible.Count)"
    }
    return $eligible[0]
}

function Assert-NoTemporaryDropRecord {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    $eligible = @(Get-TemporaryDropRecords -State $State `
        -TemporaryPosition $TemporaryPosition)
    if ($eligible.Count -ne 0) {
        throw "temporary pillar cleanup area already contains $($eligible.Count) oak-log drop(s)"
    }
}

function Sort-WallSupportsFarToNear {
    param(
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][object]$ObserverPosition
    )
    return @($Supports | Sort-Object `
            @{ Expression = {
                    $position = Get-ObjectProperty $_ 'position'
                    $dx = ([double](Get-ObjectProperty $position 'x') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'x') + 0.5)
                    $dz = ([double](Get-ObjectProperty $position 'z') + 0.5) -
                        ([double](Get-ObjectProperty $ObserverPosition 'z') + 0.5)
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

function New-WallRowActionPhase {
    param(
        [Parameter(Mandatory)][object]$Source,
        [Parameter(Mandatory)][object[]]$Supports,
        [Parameter(Mandatory)][ValidateRange(0, 2)][int]$RowIndex
    )
    if ($Supports.Count -ne 3) { throw 'a wall row must contain exactly three supports' }
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
    $request = New-PrimitiveRequest -Name "capability_gate_wall_3x3_row_$RowIndex" `
        -Capabilities @('camera', 'block_place') -Node $node `
        -Duration 45000 -Ticks 900 -Distance 0 -Camera 240 -Placements 3
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
        [Parameter(Mandatory)][object]$TemporaryPosition
    )
    if ($Targets.Count -ne 9) { throw 'wall oracle requires exactly nine targets' }
    $keys = @($Targets | ForEach-Object { Get-BlockPositionKey $_ } | Select-Object -Unique)
    if ($keys.Count -ne 9) { throw 'wall oracle targets are not unique' }
    if ((Get-BlockPositionKey $TemporaryPosition) -cin $keys) {
        throw 'temporary pillar overlaps the permanent wall oracle'
    }
    [ordered]@{
        schema_version = 1
        oracle = 'offline_anvil_before_after'
        dimension = [string](Get-ObjectProperty $Targets[0] 'dimension')
        expected_changed_cell_count = 9
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
        temporary_scaffold = [ordered]@{
            position = $TemporaryPosition
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
        reject_unlisted_changes = $true
        expected_air_violations = 0
        expected_extra_mutations = 0
    }
}

function Invoke-Wall3x3Gate {
    $inventoryBefore = Acquire-OakLogFromChest
    if ($inventoryBefore -lt 9) { throw 'normal material acquisition yielded fewer than nine oak logs' }
    Move-NearDestinationSupport

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
        -PlayerPosition (Get-ObjectProperty (Get-ObjectProperty $state 'world') 'position'))
    $wallFoundation = @($supports)

    $allTargets = [Collections.Generic.List[object]]::new()
    $rowActions = [Collections.Generic.List[object]]::new()
    $rowOneTargets = $null
    for ($row = 0; $row -lt 2; $row++) {
        # Construction deliberately allows only a narrow camera correction. Face
        # the delivered middle support first, then keep the three-entry placement
        # Action itself at its exact fixed cost.
        $faceSupport = $supports[[int][Math]::Floor($supports.Count / 2)]
        Invoke-FaceSupport -Support $faceSupport
        Add-GateEvent -Event 'wall_row_heading_admitted' -Detail ([ordered]@{
                row = $row
                face_target = Get-ObjectProperty $faceSupport 'position'
                support_count = $supports.Count
            })
        $phase = New-WallRowActionPhase -Source $source -Supports $supports -RowIndex $row
        $terminal = Invoke-ActionRequest -Request $phase.request -WallTimeoutSeconds 90
        foreach ($target in @($phase.targets)) { $allTargets.Add($target) }
        $rowActions.Add([ordered]@{
                row = $row
                action_id = [string](Get-ObjectProperty $terminal 'action_id')
                action_ids = @([string](Get-ObjectProperty $terminal 'action_id'))
                action_count = 1
                terminal_state = [string](Get-ObjectProperty $terminal 'state')
                terminal_states = @([string](Get-ObjectProperty $terminal 'state'))
                entry_count = @($phase.entries).Count
                maximum_entries_per_action = @($phase.entries).Count
                stationary = $phase.request.budget.max_distance_blocks -eq 0
                targets = @($phase.targets)
            })

        $state = Get-FreshState
        if ($row -eq 0) {
            $placedRecords = @(Get-VisibleSurfaceRecords -State $state `
                -Block 'minecraft:oak_log' -Bounds $script:DestinationWallBounds `
                -Faces @('up'))
            $supports = @(Select-ExactWallSupportRow -Records $placedRecords `
                -ExpectedPositions @($phase.targets) `
                -ExpectedState (Get-ObjectProperty $source 'state'))
            Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
                    row = $row; positions = @($phase.targets); support_count = $supports.Count
                })
        } else {
            $rowOneTargets = @($phase.targets)
        }
    }

    # The r3 failure occurred because row 1 hid its own UP faces from the low eye.
    # Deliver an outside-footprint inert support first, then join its direct-above
    # cell to a safe traversability target from this same immutable frame.
    $temporarySupports = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:white_wool' -Bounds $script:DestinationSupportBounds `
        -Faces @('up') -ExcludePlayerFeetAbove)
    $temporaryTraversability = @(Get-WallGroundTraversabilityRecords -State $state)
    $temporarySite = Select-TemporaryPillarSite `
        -WhiteWoolRecords $temporarySupports `
        -TraversabilityRecords $temporaryTraversability `
        -WallFoundation $wallFoundation -RowOneTargets $rowOneTargets
    $temporarySupport = $temporarySite.support
    $temporaryNavigation = $temporarySite.navigation_record
    $temporaryPosition = Get-ObjectProperty $temporaryNavigation 'navigation_target'
    $raiseNavigationRequest = New-NavigationActionRequest `
        -NavigationRecord $temporaryNavigation -State $state -Tolerance 0.1
    $raiseNavigationTerminal = Invoke-ActionRequest `
        -Request $raiseNavigationRequest -WallTimeoutSeconds 90
    Add-GateEvent -Event 'wall_temporary_pillar_navigation_terminal' `
        -Detail ([ordered]@{
            action_id = [string](Get-ObjectProperty $raiseNavigationTerminal 'action_id')
            target = $temporaryPosition
            target_verbatim = [object]::ReferenceEquals(
                $temporaryPosition,
                $raiseNavigationRequest.program.body[0].target)
            tolerance = $raiseNavigationRequest.program.body[0].tolerance
        })

    # Do not refresh or re-observe the source/support between centering and this
    # exclusive pillar Action. pillar_up_known retains only the delivered support
    # witness through that bounded centering step.
    $pillarRequest = New-TemporaryPillarActionRequest `
        -Source $source -Support $temporarySupport
    $pillarTerminal = Invoke-ActionRequest -Request $pillarRequest -WallTimeoutSeconds 60
    Add-GateEvent -Event 'wall_temporary_pillar_terminal' -Detail ([ordered]@{
            action_id = [string](Get-ObjectProperty $pillarTerminal 'action_id')
            support = Get-ObjectProperty $temporarySupport 'position'
            placed_position = $temporaryPosition
            placement_state_ref = [string](Get-ObjectProperty $source 'placement_state_ref')
        })

    # The raised eye must now receive all three row-1 UP faces freshly before row 2.
    $state = Get-FreshState
    $placedRecords = @(Get-VisibleSurfaceRecords -State $state `
        -Block 'minecraft:oak_log' -Bounds $script:DestinationWallBounds `
        -Faces @('up'))
    $supports = @(Select-ExactWallSupportRow -Records $placedRecords `
        -ExpectedPositions $rowOneTargets `
        -ExpectedState (Get-ObjectProperty $source 'state'))
    Add-GateEvent -Event 'wall_row_fresh_support_verified' -Detail ([ordered]@{
            row = 1; positions = $rowOneTargets; support_count = $supports.Count
            raised_by_temporary_pillar = $true
        })

    # A near block can hide a farther UP face from this raised pose. Place the top
    # row far-to-near as three independent, freshly admitted one-entry Actions.
    # This reuses the product heading/reach checks instead of copying their math.
    $remainingSupports = @(Sort-WallSupportsFarToNear -Supports $supports `
        -ObserverPosition $temporaryPosition)
    $topActionIds = [Collections.Generic.List[string]]::new()
    $topTerminalStates = [Collections.Generic.List[string]]::new()
    $topTargets = [Collections.Generic.List[object]]::new()
    while ($remainingSupports.Count -gt 0) {
        $support = $remainingSupports[0]
        Invoke-FaceSupport -Support $support
        $state = Get-FreshState
        $freshRecords = @(Get-VisibleSurfaceRecords -State $state `
            -Block 'minecraft:oak_log' -Bounds $script:DestinationWallBounds `
            -Faces @('up'))
        $support = @(Select-ExactWallSupportRow -Records $freshRecords `
            -ExpectedPositions @((Get-ObjectProperty $support 'position')) `
            -ExpectedState (Get-ObjectProperty $source 'state'))[0]
        $single = New-OneOakLogPlacementPhase -Source $source -Support $support `
            -UseStateRef
        $terminal = Invoke-ActionRequest -Request $single.request -WallTimeoutSeconds 60
        $topActionIds.Add([string](Get-ObjectProperty $terminal 'action_id'))
        $topTerminalStates.Add([string](Get-ObjectProperty $terminal 'state'))
        $topTargets.Add($single.target)
        $allTargets.Add($single.target)
        Add-GateEvent -Event 'wall_top_cell_terminal' -Detail ([ordered]@{
                row = 2
                action_id = [string](Get-ObjectProperty $terminal 'action_id')
                support = Get-ObjectProperty $support 'position'
                target = $single.target
                remaining_cells = $remainingSupports.Count - 1
            })

        $remainingPositions = @($remainingSupports | Select-Object -Skip 1 | ForEach-Object {
                Get-ObjectProperty $_ 'position'
            })
        if ($remainingPositions.Count -eq 0) { break }
        $state = Get-FreshState
        $freshRecords = @(Get-VisibleSurfaceRecords -State $state `
            -Block 'minecraft:oak_log' -Bounds $script:DestinationWallBounds `
            -Faces @('up'))
        $remainingSupports = @(Select-ExactWallSupportRow -Records $freshRecords `
            -ExpectedPositions $remainingPositions `
            -ExpectedState (Get-ObjectProperty $source 'state'))
    }
    $rowActions.Add([ordered]@{
            row = 2
            action_ids = @($topActionIds)
            action_count = $topActionIds.Count
            terminal_states = @($topTerminalStates)
            entry_count = $topTargets.Count
            maximum_entries_per_action = 1
            stationary = $true
            order = 'far_to_near'
            targets = @($topTargets)
        })

    $state = Get-FreshState
    # Descend to a newly delivered safe target far enough away that breaking the
    # temporary pillar cannot be credited by incidental pickup.
    $descentRecords = @(Get-WallGroundTraversabilityRecords -State $state)
    $descentRecord = Select-TemporaryPillarDescentRecord -Records $descentRecords `
        -TemporaryPosition $temporaryPosition -WallFoundation $wallFoundation
    $descentTarget = Get-ObjectProperty $descentRecord 'navigation_target'
    $descentRequest = New-NavigationActionRequest -NavigationRecord $descentRecord `
        -State $state -Tolerance 0.1
    Add-GateEvent -Event 'wall_temporary_descent_selected' -Detail ([ordered]@{
            frame_id = [string](Get-ObjectProperty `
                (Get-ObjectProperty $state 'observation') 'latest_frame_id')
            target = $descentTarget
            status = [string](Get-ObjectProperty $descentRecord 'status')
        })
    $descentTerminal = Invoke-ActionRequest -Request $descentRequest -WallTimeoutSeconds 90

    $state = Get-FreshState
    $temporarySurface = Get-ExactTemporarySurface -State $state `
        -Position $temporaryPosition -ExpectedState (Get-ObjectProperty $source 'state')
    Assert-NoTemporaryDropRecord -State $state -TemporaryPosition $temporaryPosition
    Invoke-FaceSupport -Support $temporarySurface
    $clearRequest = New-TemporaryClearActionRequest -Surface $temporarySurface
    $clearTerminal = Invoke-ActionRequest -Request $clearRequest -WallTimeoutSeconds 60

    # A freshly broken item is still moving. Wait without observing it, then bind
    # the collect Action to the first post-settle policy-visible continuous pose.
    $settleRequest = New-TemporaryDropSettleActionRequest
    $settleTerminal = Invoke-ActionRequest -Request $settleRequest -WallTimeoutSeconds 60
    $state = Get-FreshState
    $drop = Get-TemporaryDropRecord -State $state -TemporaryPosition $temporaryPosition
    Add-GateEvent -Event 'wall_temporary_drop_observed' -Detail ([ordered]@{
            frame_id = [string](Get-ObjectProperty `
                (Get-ObjectProperty $state 'observation') 'latest_frame_id')
            position = Get-ObjectProperty $drop 'position'
            displayed_item = [string](Get-ObjectProperty $drop 'displayed_item')
            settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
            settle_ticks = 40
        })
    $collectRequest = New-TemporaryDropCollectionRequest -Record $drop -State $state
    $collectTerminal = Invoke-ActionRequest -Request $collectRequest -WallTimeoutSeconds 90
    $state = Get-FreshState

    if ($script:SourceObservationCount -ne 1) {
        throw 'wall gate re-observed its placement source'
    }
    $inventoryAfter = Get-InventoryCount -State $state -Item 'minecraft:oak_log'
    if ($inventoryAfter -ne $inventoryBefore - 9) {
        throw 'oak-log inventory ledger did not decrease by exactly nine'
    }
    $targets = @($allTargets)
    $oracle = New-WallExternalOracleManifest -Targets $targets `
        -ExpectedState (Get-ObjectProperty $source 'state') `
        -SourcePosition (Get-ObjectProperty $source 'position') `
        -TemporaryPosition $temporaryPosition
    return [ordered]@{
        gate = 'wall-3x3'
        wall_dimensions = [ordered]@{ width = 3; height = 3; depth = 1 }
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
        wall_placement_action_count = @($rowActions | ForEach-Object {
                [int](Get-ObjectProperty $_ 'action_count')
            } | Measure-Object -Sum).Sum
        temporary_scaffold = [ordered]@{
            support = Get-ObjectProperty $temporarySupport 'position'
            position = $temporaryPosition
            raise_navigation_action_id = [string](
                Get-ObjectProperty $raiseNavigationTerminal 'action_id')
            pillar_action_id = [string](Get-ObjectProperty $pillarTerminal 'action_id')
            descent_target = $descentTarget
            descent_action_id = [string](Get-ObjectProperty $descentTerminal 'action_id')
            clear_action_id = [string](Get-ObjectProperty $clearTerminal 'action_id')
            settle_action_id = [string](Get-ObjectProperty $settleTerminal 'action_id')
            settle_ticks = 40
            collected_drop_target = Get-ObjectProperty $drop 'position'
            collect_action_id = [string](Get-ObjectProperty $collectTerminal 'action_id')
            expected_cleanup = $true
            expected_drop_collection = 'minecraft:oak_log'
            included_in_expected_changed_cells = $false
        }
        total_action_count = @($script:GateEvents | Where-Object {
                (Get-ObjectProperty $_ 'event') -ceq 'action_accepted'
            }).Count
        maximum_entries_per_action = 3
        phase_entry_limit = 8
        stationary_placement = $true
        exact_target_count = $targets.Count
        exact_targets = $targets
        expected_air_violations = 0
        expected_extra_mutations = 0
        external_oracle_status = 'pending'
        mutation_proof = 'nine unique fresh supports plus one observation-derived pillar fully cleared and recollected; placement inventory delta minus 9; external MCA required'
        inventory_before_placement = $inventoryBefore
        inventory_after_placement = $inventoryAfter
        inventory_delta = $inventoryAfter - $inventoryBefore
        external_oracle = $oracle
    }
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
    $manifest = [ordered]@{
        schema_version = 1
        gate = $Gate
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
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
    if ($Gate -ceq 'wall-3x3' -and $null -ne (Get-ObjectProperty $Result 'gate_result')) {
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
