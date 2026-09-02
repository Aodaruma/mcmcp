[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl')]
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
        [Parameter(Mandatory)][string[]]$Capabilities,
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
        })
    $terminal = Wait-McmcpActionTerminal -ActionId $actionId `
        -WallTimeoutSeconds $WallTimeoutSeconds
    $script:ActiveActionId = $null
    Add-GateEvent -Event 'action_terminal' -Detail ([ordered]@{
            action_id = $actionId
            state = [string](Get-ObjectProperty $terminal 'state')
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
    param([Parameter(Mandatory)][object]$NavigationRecord, [Parameter(Mandatory)][object]$State)
    $observedTarget = Get-ObjectProperty $NavigationRecord 'navigation_target'
    $node = [ordered]@{
        id = 'navigate_gate'
        op = 'navigate_to_known'
        # Deliberately retain the delivered object. Do not floor/round from/to.
        target = $observedTarget
        tolerance = 0.75
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
        [Parameter(Mandatory)][string[]]$Capabilities,
        [Parameter(Mandatory)][object]$Node,
        [long]$Duration = 30000,
        [long]$Ticks = 600,
        [double]$Distance = 0,
        [double]$Camera = 360,
        [long]$Interactions = 0,
        [long]$Placements = 0
    )
    New-ActionRequest -Name $Name -Capabilities $Capabilities -Body @($Node) `
        -Budget ([ordered]@{
            max_duration_ms = $Duration; max_ticks = $Ticks
            max_distance_blocks = $Distance; max_camera_degrees = $Camera
            max_interactions = $Interactions; max_blocks_broken = 0
            max_blocks_placed = $Placements
        })
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

function Invoke-OneOakLogPlacement {
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
    [void](Invoke-ActionRequest -Request $request -WallTimeoutSeconds 60)
    return $target
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
