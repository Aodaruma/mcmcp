#requires -Version 7.4

[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)] [string] $ManifestPath,
    [switch] $ValidateOnly,
    [string] $GameDirectory,
    [ValidateRange(1, 65535)] [int] $Port = 8765,
    [ValidateRange(50, 2000)] [int] $PollIntervalMilliseconds = 250
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$manifestSchema = @'
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "additionalProperties": false,
  "required": ["schema", "id", "max_total_seconds", "steps"],
  "properties": {
    "schema": {"const": "craftagent.dev-build-gate/v1"},
    "id": {"$ref": "#/definitions/id"},
    "max_total_seconds": {"type": "integer", "minimum": 1, "maximum": 900},
    "steps": {
      "type": "array", "minItems": 1, "maxItems": 17,
      "contains": {"properties": {"kind": {"const": "apply_block_plan"}}, "required": ["kind"]},
      "items": {"oneOf": [
        {"$ref": "#/definitions/navigateStep"},
        {"$ref": "#/definitions/applyStep"}
      ]}
    }
  },
  "definitions": {
    "id": {"type": "string", "pattern": "^[a-z][a-z0-9_.-]{0,63}$"},
    "registryId": {"type": "string", "pattern": "^[a-z0-9_.-]+:[a-z0-9_./-]+$", "maxLength": 256},
    "xyz": {
      "type": "object", "additionalProperties": false, "required": ["x", "y", "z"],
      "properties": {
        "x": {"type": "integer", "minimum": -30000000, "maximum": 29999999},
        "y": {"type": "integer", "minimum": -2048, "maximum": 2047},
        "z": {"type": "integer", "minimum": -30000000, "maximum": 29999999}
      }
    },
    "offset": {
      "type": "object", "additionalProperties": false, "required": ["x", "y", "z"],
      "properties": {
        "x": {"type": "integer", "minimum": -4096, "maximum": 4096},
        "y": {"type": "integer", "minimum": -4096, "maximum": 4096},
        "z": {"type": "integer", "minimum": -4096, "maximum": 4096}
      }
    },
    "position": {
      "type": "object", "additionalProperties": false,
      "required": ["dimension", "x", "y", "z"],
      "properties": {
        "dimension": {"$ref": "#/definitions/registryId"},
        "x": {"type": "integer", "minimum": -30000000, "maximum": 29999999},
        "y": {"type": "integer", "minimum": -2048, "maximum": 2047},
        "z": {"type": "integer", "minimum": -30000000, "maximum": 29999999}
      }
    },
    "region": {
      "type": "object", "additionalProperties": false, "required": ["min", "max"],
      "properties": {"min": {"$ref": "#/definitions/xyz"}, "max": {"$ref": "#/definitions/xyz"}}
    },
    "state": {
      "type": "object", "additionalProperties": false, "required": ["block", "properties"],
      "properties": {
        "block": {"$ref": "#/definitions/registryId"},
        "properties": {
          "type": "object", "maxProperties": 128,
          "propertyNames": {"pattern": "^[a-z0-9_]+$"},
          "additionalProperties": {"type": "string", "minLength": 1, "maxLength": 64}
        }
      }
    },
    "navigateBounds": {
      "type": "object", "additionalProperties": false,
      "required": ["dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break"],
      "properties": {
        "dimension": {"$ref": "#/definitions/registryId"},
        "region": {"$ref": "#/definitions/region"},
        "max_travel_blocks": {"type": "integer", "minimum": 1, "maximum": 128},
        "max_duration_seconds": {"type": "integer", "minimum": 1, "maximum": 120},
        "allow_break": {"const": false}
      }
    },
    "applyBounds": {
      "type": "object", "additionalProperties": false,
      "required": ["dimension", "region", "max_travel_blocks", "max_duration_seconds", "allow_break"],
      "properties": {
        "dimension": {"$ref": "#/definitions/registryId"},
        "region": {"$ref": "#/definitions/region"},
        "max_travel_blocks": {"const": 0},
        "max_duration_seconds": {"type": "integer", "minimum": 1, "maximum": 120},
        "allow_break": {"type": "boolean"}
      }
    },
    "entry": {
      "type": "object", "additionalProperties": false,
      "required": ["id", "offset", "operation", "expected_before", "expected_after"],
      "properties": {
        "id": {"$ref": "#/definitions/id"},
        "offset": {"$ref": "#/definitions/offset"},
        "operation": {"enum": ["verify_only", "break_to_air", "place", "replace"]},
        "expected_before": {"$ref": "#/definitions/state"},
        "expected_after": {"$ref": "#/definitions/state"},
        "item": {"$ref": "#/definitions/registryId"}
      },
      "allOf": [
        {"if": {"properties": {"operation": {"enum": ["place", "replace"]}}}, "then": {"required": ["item"]}},
        {"if": {"properties": {"operation": {"enum": ["verify_only", "break_to_air"]}}}, "then": {"not": {"required": ["item"]}}}
      ]
    },
    "navigateStep": {
      "type": "object", "additionalProperties": false, "required": ["id", "kind", "parameters", "bounds"],
      "properties": {
        "id": {"$ref": "#/definitions/id"}, "kind": {"const": "navigate_to"},
        "parameters": {
          "type": "object", "additionalProperties": false,
          "required": ["target", "horizontal_tolerance_blocks"],
          "properties": {
            "target": {"$ref": "#/definitions/position"},
            "horizontal_tolerance_blocks": {"type": "number", "minimum": 0.25, "maximum": 2.0}
          }
        },
        "bounds": {"$ref": "#/definitions/navigateBounds"}
      }
    },
    "applyStep": {
      "type": "object", "additionalProperties": false, "required": ["id", "kind", "parameters", "bounds"],
      "properties": {
        "id": {"$ref": "#/definitions/id"}, "kind": {"const": "apply_block_plan"},
        "parameters": {
          "type": "object", "additionalProperties": false,
          "required": ["anchor", "transform", "phase", "entries"],
          "properties": {
            "anchor": {"$ref": "#/definitions/position"},
            "transform": {
              "type": "object", "additionalProperties": false, "required": ["rotation", "mirror"],
              "properties": {"rotation": {"enum": [0, 90, 180, 270]}, "mirror": {"enum": ["none", "x", "z"]}}
            },
            "phase": {
              "type": "object", "additionalProperties": false, "required": ["id", "index", "total"],
              "properties": {
                "id": {"$ref": "#/definitions/id"},
                "index": {"type": "integer", "minimum": 1, "maximum": 64},
                "total": {"type": "integer", "minimum": 1, "maximum": 64}
              }
            },
            "entries": {"type": "array", "minItems": 1, "maxItems": 64, "items": {"$ref": "#/definitions/entry"}}
          }
        },
        "bounds": {"$ref": "#/definitions/applyBounds"}
      }
    }
  }
}
'@

function Read-Manifest([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $file = Get-Item -LiteralPath $resolved
    if ($file.PSIsContainer -or $file.Length -gt 1MB) { throw 'Manifest must be a JSON file no larger than 1 MiB.' }
    $json = Get-Content -LiteralPath $resolved -Raw -Encoding UTF8
    if (-not (Test-Json -Json $json -Schema $manifestSchema -ErrorAction Stop)) {
        throw 'Manifest does not match the closed development build-gate schema.'
    }
    return ($json | ConvertFrom-Json -AsHashtable -Depth 100)
}

function Test-InRegion(
    [System.Collections.IDictionary] $Point,
    [System.Collections.IDictionary] $Region
) {
    foreach ($axis in @('x', 'y', 'z')) {
        if ([long] $Point[$axis] -lt [long] $Region.min[$axis] -or
            [long] $Point[$axis] -gt [long] $Region.max[$axis]) {
            return $false
        }
    }
    return $true
}

function Get-TransformedTarget(
    [System.Collections.IDictionary] $Anchor,
    [System.Collections.IDictionary] $Offset,
    [System.Collections.IDictionary] $Transform
) {
    $x = [long] $Offset.x
    $z = [long] $Offset.z
    if ($Transform.mirror -ceq 'x') { $x = -$x }
    if ($Transform.mirror -ceq 'z') { $z = -$z }
    $rotated = switch ([int] $Transform.rotation) {
        0   { @($x, $z); break }
        90  { @(-$z, $x); break }
        180 { @(-$x, -$z); break }
        270 { @($z, -$x); break }
    }
    return [ordered]@{
        x = [long] $Anchor.x + $rotated[0]
        y = [long] $Anchor.y + [long] $Offset.y
        z = [long] $Anchor.z + $rotated[1]
    }
}

function Test-StateEqual(
    [System.Collections.IDictionary] $First,
    [System.Collections.IDictionary] $Second
) {
    if ([string] $First.block -cne [string] $Second.block) { return $false }
    $firstNames = @($First.properties.Keys | Sort-Object)
    $secondNames = @($Second.properties.Keys | Sort-Object)
    if ($firstNames.Count -ne $secondNames.Count) { return $false }
    for ($index = 0; $index -lt $firstNames.Count; $index++) {
        $name = [string] $firstNames[$index]
        if ($name -cne [string] $secondNames[$index] -or
            [string] $First.properties[$name] -cne [string] $Second.properties[$name]) {
            return $false
        }
    }
    return $true
}

function Test-CrossStepRules([System.Collections.IDictionary] $Manifest) {
    $steps = @($Manifest.steps)
    $stepIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $phaseIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $phases = [Collections.Generic.List[object]]::new()
    $deadlineSum = 0
    foreach ($step in $steps) {
        if (-not $stepIds.Add([string] $step.id)) { throw "Duplicate step id: $($step.id)." }
        $deadlineSum += [int] $step.bounds.max_duration_seconds + 5
        foreach ($axis in @('x', 'y', 'z')) {
            if ([long] $step.bounds.region.min[$axis] -gt [long] $step.bounds.region.max[$axis]) {
                throw "Step $($step.id) has an inverted bounds.region $axis range."
            }
        }
        if ($step.kind -ceq 'navigate_to') {
            if ($step.parameters.target.dimension -cne $step.bounds.dimension) {
                throw "Step $($step.id) target dimension differs from bounds.dimension."
            }
            if (-not (Test-InRegion $step.parameters.target $step.bounds.region)) {
                throw "Step $($step.id) target is outside bounds.region."
            }
            continue
        }
        if ($step.parameters.anchor.dimension -cne $step.bounds.dimension) {
            throw "Step $($step.id) anchor dimension differs from bounds.dimension."
        }
        $phases.Add($step.parameters.phase)
        if (-not $phaseIds.Add([string] $step.parameters.phase.id)) {
            throw "Duplicate apply phase id: $($step.parameters.phase.id)."
        }
        $entryIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $targets = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $requiresBreak = $false
        foreach ($entry in @($step.parameters.entries)) {
            if (-not $entryIds.Add([string] $entry.id)) { throw "Duplicate entry id in $($step.id): $($entry.id)." }
            $beforeAir = [string] $entry.expected_before.block -ceq 'minecraft:air'
            $afterAir = [string] $entry.expected_after.block -ceq 'minecraft:air'
            $sameState = Test-StateEqual $entry.expected_before $entry.expected_after
            if (($beforeAir -and $entry.expected_before.properties.Count -ne 0) -or
                ($afterAir -and $entry.expected_after.properties.Count -ne 0)) {
                throw "Entry $($entry.id) must use an empty property map for minecraft:air."
            }
            switch ([string] $entry.operation) {
                'verify_only' {
                    if (-not $sameState) { throw "Entry $($entry.id) verify_only must keep the exact state." }
                }
                'break_to_air' {
                    if ($beforeAir -or -not $afterAir) {
                        throw "Entry $($entry.id) break_to_air must change a non-air state to air."
                    }
                }
                'place' {
                    if ($afterAir -or $sameState) {
                        throw "Entry $($entry.id) place must change to a non-air state."
                    }
                }
                'replace' {
                    if ($beforeAir -or $afterAir -or $sameState) {
                        throw "Entry $($entry.id) replace must change one non-air state to another."
                    }
                }
            }
            $target = Get-TransformedTarget $step.parameters.anchor $entry.offset $step.parameters.transform
            if (-not (Test-InRegion $target $step.bounds.region)) {
                throw "Entry $($entry.id) in $($step.id) transforms outside bounds.region."
            }
            $targetKey = "$($target.x),$($target.y),$($target.z)"
            if (-not $targets.Add($targetKey)) {
                throw "Step $($step.id) has duplicate transformed target $targetKey."
            }
            $requiresBreak = $requiresBreak -or @('break_to_air', 'replace') -ccontains $entry.operation
        }
        if ($requiresBreak -ne [bool] $step.bounds.allow_break) {
            throw "Step $($step.id) has an inconsistent allow_break value."
        }
    }
    for ($index = 0; $index -lt $phases.Count; $index++) {
        if ($phases[$index].index -ne ($index + 1) -or $phases[$index].total -ne $phases.Count) {
            throw 'Apply phases must use ordered indexes 1..N and total=N.'
        }
    }
    if ($deadlineSum -gt $Manifest.max_total_seconds) {
        throw "max_total_seconds must be at least $deadlineSum (routine bounds plus finalization reserves)."
    }
    return [ordered]@{
        id = [string] $Manifest.id
        steps = $steps
        applyCount = $phases.Count
        maxTotalSeconds = [int] $Manifest.max_total_seconds
    }
}

function ConvertFrom-McpBody([string] $Body) {
    $text = $Body.Trim()
    if (-not $text) { return $null }
    if ($text.StartsWith('{')) { return ($text | ConvertFrom-Json -AsHashtable -Depth 100) }
    $messages = @([regex]::Matches($text, '(?m)^data:\s*(.+)$') | ForEach-Object {
            if ($_.Groups[1].Value -cne '[DONE]') {
                $_.Groups[1].Value | ConvertFrom-Json -AsHashtable -Depth 100
            }
        })
    if ($messages.Count -eq 0) { throw 'MCP returned no JSON response.' }
    return $messages[-1]
}

$script:ProtocolVersion = '2025-11-25'
$script:RpcId = 0L
$script:SessionId = $null
$script:HttpClient = $null
$script:Origin = "http://127.0.0.1:$Port"

function Invoke-Rpc {
    param(
        [string] $Method,
        [System.Collections.IDictionary] $Params,
        [string] $Token,
        [string] $Endpoint,
        [switch] $Notification,
        [switch] $WithProtocol
    )
    $message = [ordered]@{ jsonrpc = '2.0' }
    $requestId = $null
    if (-not $Notification) {
        $requestId = ++$script:RpcId
        $message.id = $requestId
    }
    $message.method = $Method
    if ($null -ne $Params) { $message.params = $Params }
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, $Endpoint)
    $response = $null
    try {
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        $request.Headers.Accept.ParseAdd('application/json, text/event-stream')
        [void] $request.Headers.TryAddWithoutValidation('Origin', $script:Origin)
        if ($WithProtocol) { [void] $request.Headers.TryAddWithoutValidation('MCP-Protocol-Version', $script:ProtocolVersion) }
        if ($script:SessionId) { [void] $request.Headers.TryAddWithoutValidation('Mcp-Session-Id', $script:SessionId) }
        $json = $message | ConvertTo-Json -Depth 100 -Compress
        $request.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        $response = $script:HttpClient.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "MCP HTTP status $([int] $response.StatusCode)." }
        $sessionValues = $null
        if ($response.Headers.TryGetValues('Mcp-Session-Id', [ref] $sessionValues)) {
            $script:SessionId = @($sessionValues)[0]
        }
        if ($Notification) { return $null }
        $rpc = ConvertFrom-McpBody $body
        if ($rpc.jsonrpc -cne '2.0' -or [long] $rpc.id -ne $requestId) {
            throw 'MCP returned a mismatched JSON-RPC response.'
        }
        if ($rpc.Contains('error')) { throw "MCP JSON-RPC error $($rpc.error.code)." }
        return $rpc
    }
    finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Invoke-Tool {
    param([string] $Name, [System.Collections.IDictionary] $Arguments, [string] $Token, [string] $Endpoint)
    $rpc = Invoke-Rpc 'tools/call' ([ordered]@{ name = $Name; arguments = $Arguments }) $Token $Endpoint -WithProtocol
    $result = $rpc.result
    $envelope = if ($result.Contains('structuredContent')) { $result.structuredContent } else { $null }
    if ($envelope -isnot [System.Collections.IDictionary]) {
        $block = @($result.content | Where-Object { $_.type -ceq 'text' } | Select-Object -First 1)
        if ($block.Count -ne 1) { throw "Tool $Name returned no envelope." }
        $envelope = $block[0].text | ConvertFrom-Json -AsHashtable -Depth 100
    }
    if ($envelope.tool -cne $Name) { throw "Tool response was attributed to '$($envelope.tool)', expected '$Name'." }
    if ($envelope.ok -ne $true) { throw "Tool $Name failed ($($envelope.error.code)): $($envelope.error.message)" }
    return $envelope.data
}

function Initialize-Mcp([string] $Token, [string] $Endpoint) {
    $reply = Invoke-Rpc 'initialize' ([ordered]@{
            protocolVersion = $script:ProtocolVersion
            capabilities = [ordered]@{}
            clientInfo = [ordered]@{ name = 'craftagent-dev-build-gate'; version = '1' }
        }) $Token $Endpoint
    if ($reply.result.protocolVersion -cne $script:ProtocolVersion) { throw 'Unsupported MCP protocol version.' }
    [void] (Invoke-Rpc 'notifications/initialized' $null $Token $Endpoint -Notification -WithProtocol)
}

function Read-Token([string] $Directory) {
    $path = Join-Path $Directory 'config/craftagent/bearer.token'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -gt 256) {
        throw 'CraftAgent bearer token file is missing or invalid.'
    }
    $token = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8).Trim()
    if ($token -cnotmatch '^[A-Za-z0-9_-]{43,256}$') { throw 'CraftAgent bearer token file is malformed.' }
    return $token
}

function Get-FailureCode([System.Collections.IDictionary] $Snapshot) {
    if ($Snapshot.Contains('failure') -and $Snapshot.failure) { return [string] $Snapshot.failure.code }
    if ($Snapshot.Contains('finalization') -and $Snapshot.finalization -is [System.Collections.IDictionary] -and
        $Snapshot.finalization.Contains('failure') -and
        $Snapshot.finalization.failure) { return [string] $Snapshot.finalization.failure.code }
    return 'unknown'
}

$manifest = Read-Manifest $ManifestPath
$checked = Test-CrossStepRules $manifest
Write-Host "Validated '$($checked.id)': $($checked.steps.Count) routines, $($checked.applyCount) apply phases."
if ($ValidateOnly) { return }

if (-not $GameDirectory) { $GameDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'run/harness-client' }
$gamePath = (Resolve-Path -LiteralPath $GameDirectory).Path
$token = Read-Token $gamePath
$endpoint = "http://127.0.0.1:$Port/mcp"
$handler = [Net.Http.HttpClientHandler]::new()
$script:HttpClient = [Net.Http.HttpClient]::new($handler, $true)
$script:HttpClient.Timeout = [TimeSpan]::FromSeconds(10)
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($checked.maxTotalSeconds)
$activeRoutineId = $null
$startOutcomeUncertain = $false

try {
    Initialize-Mcp $token $endpoint
    for ($index = 0; $index -lt $checked.steps.Count; $index++) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Build-gate deadline expired.' }
        $step = $checked.steps[$index]
        $intent = if ($index -eq $checked.steps.Count - 1) { 'finish_goal' } else { 'continue_goal' }
        $arguments = [ordered]@{
            kind = $step.kind
            parameters = $step.parameters
            bounds = $step.bounds
            completion_intent = $intent
            idempotency_key = [guid]::NewGuid().ToString()
        }
        Write-Host "[$($index + 1)/$($checked.steps.Count)] Starting $($step.id) ($($step.kind))."
        $startOutcomeUncertain = $true
        $started = Invoke-Tool 'start_routine' $arguments $token $endpoint
        $returnedRoutineId = [string] $started.routine_id
        $parsedRoutineId = [guid]::Empty
        if (-not [guid]::TryParseExact($returnedRoutineId, 'D', [ref] $parsedRoutineId)) {
            throw 'start_routine returned an invalid routine id.'
        }
        $activeRoutineId = $parsedRoutineId.ToString()
        $startOutcomeUncertain = $false

        while ($true) {
            if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Build-gate deadline expired while polling.' }
            $snapshot = Invoke-Tool 'get_routine' ([ordered]@{
                    routine_id = $activeRoutineId; after_event_seq = 0; max_events = 1
                }) $token $endpoint
            if ([string] $snapshot.routine_id -cne $activeRoutineId -or
                [string] $snapshot.kind -cne [string] $step.kind) {
                throw 'get_routine returned a mismatched routine snapshot.'
            }
            if (@('SUCCEEDED', 'FAILED', 'CANCELLED') -ccontains $snapshot.state) {
                $terminalId = $activeRoutineId
                $activeRoutineId = $null
                $safeSuccess = $snapshot.state -ceq 'SUCCEEDED' -and
                    $snapshot.goal.verified -eq $true -and
                    $snapshot.finalization.status -ceq 'succeeded'
                if (-not $safeSuccess) {
                    throw "Routine $terminalId stopped as $($snapshot.state) ($(Get-FailureCode $snapshot))."
                }
                Write-Host "[$($index + 1)/$($checked.steps.Count)] Succeeded $($step.id)."
                break
            }
            $delay = if ($snapshot.Contains('next_poll_after_ms') -and $snapshot.next_poll_after_ms -is [ValueType]) {
                [Math]::Clamp([int] $snapshot.next_poll_after_ms, 50, 2000)
            } else { 250 }
            Start-Sleep -Milliseconds ([Math]::Max($PollIntervalMilliseconds, $delay))
        }
    }
    $finalStatus = Invoke-Tool 'get_status' ([ordered]@{}) $token $endpoint
    if ($null -ne $finalStatus.active_routine -or
        $finalStatus.lock.locked -ne $true -or
        [string] $finalStatus.lock.reason -cne 'goal_finished') {
        throw 'Final goal completion did not clear the routine and restore the local lock.'
    }
    Write-Host "Build gate '$($checked.id)' completed."
}
catch {
    $failure = $_
    try {
        if ($activeRoutineId) {
            [void] (Invoke-Tool 'cancel_routine' ([ordered]@{
                        routine_id = $activeRoutineId; reason = 'development build runner stopped'
                    }) $token $endpoint)
        }
    }
    catch {
        # Continue to the priority stop even when graceful cancellation failed.
    }
    try {
        $stopReason = if ($startOutcomeUncertain) {
            'development build runner start outcome unknown'
        } else {
            'development build runner failed closed'
        }
        [void] (Invoke-Tool 'emergency_stop' ([ordered]@{ reason = $stopReason }) $token $endpoint)
    }
    catch {
        # The local emergency-stop key remains authoritative when MCP is unavailable.
    }
    throw $failure
}
finally {
    $token = $null
    if ($script:HttpClient) { $script:HttpClient.Dispose(); $script:HttpClient = $null }
}
