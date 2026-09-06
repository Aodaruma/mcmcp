[CmdletBinding(DefaultParameterSetName = 'Audit')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateNotNullOrEmpty()]
    [string]$TracePath,

    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateNotNullOrEmpty()]
    [string]$BridgeLogPath,

    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateNotNullOrEmpty()]
    [string]$OutputPath,

    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateSet('gpt-5.6-sol', 'gpt-5.6-luna')]
    [string]$ExpectedModel,

    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateSet('high', 'xhigh')]
    [string]$ExpectedEffort,

    [Parameter(Mandatory, ParameterSetName = 'Audit')]
    [ValidateSet('short-regression', 'full-cycle', 'warehouse-smelt', 'hard-building-copy', 'container-inspect-recovery',
        'tunnel-straight16', 'tunnel-straight160', 'tunnel-branches', 'tunnel-hazard')]
    [string]$ExpectedPromptProfile,

    [Parameter(Mandatory, ParameterSetName = 'SelfTest')]
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'McmcpRecoveryPreflight.ps1')
Import-Module (Join-Path $PSScriptRoot 'McmcpRecoveryWitness.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'McmcpTunnelRecoveryWitness.psm1') -Force

$EvaluationProfiles = [ordered]@{
    'tunnel-straight16' = [ordered]@{
        prompt = '正面にある石壁の下段を入口として、幅1・高さ2の直線坑道を16マス掘ってください。坑道掘削Actionを1回だけ開始し、完了まで確認してください。'
        timeout_minutes = 30
        inventory_profile = 'single_netherite_pickaxe'
    }
    'tunnel-straight160' = [ordered]@{
        prompt = '正面にある石壁の下段を入口として、幅1・高さ2の直線坑道を160マス掘ってください。坑道掘削Actionを1回だけ開始し、完了まで確認してください。'
        timeout_minutes = 120
        inventory_profile = 'single_netherite_pickaxe'
    }
    'tunnel-branches' = [ordered]@{
        prompt = '正面にある石壁の下段を入口として、主坑道16マス、枝の長さ3マス、枝の間隔4マスの枝坑道を掘ってください。坑道掘削Actionを1回だけ開始し、完了まで確認してください。'
        timeout_minutes = 40
        inventory_profile = 'single_netherite_pickaxe'
    }
    'tunnel-hazard' = [ordered]@{
        prompt = '正面にある石壁の下段を入口として、幅1・高さ2の直線坑道を16マス掘ってください。危険を検出した場合はそのActionを再送せず、確認済みの地点で停止してください。'
        timeout_minutes = 30
        inventory_profile = 'single_netherite_pickaxe'
    }
    'container-inspect-recovery' = [ordered]@{
        prompt = '近くにあるチェスト1つの中身を一度だけ確認し、結果を教えてください。アイテムの出し入れはしないでください。'
        timeout_minutes = 5
        inventory_profile = 'empty'
    }
    'short-regression' = [ordered]@{
        prompt = 'チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか'
        timeout_minutes = 30
        inventory_profile = 'empty'
    }
    'full-cycle' = [ordered]@{
        prompt = 'チェストに小麦の種と鍬が入っています。これを取り出し、この畑の区画にある耕作可能な土をすべて耕して、すべてに小麦の種を植えてください。成熟後はすべて収穫して植え直す工程を、小麦を1スタック（64個）以上所持するまで繰り返してください。'
        timeout_minutes = 30
        inventory_profile = 'empty'
    }
    'warehouse-smelt' = [ordered]@{
        prompt = '近くの材料チェストから生の鉄1個と石炭1個を取り出し、かまどで鉄インゴット1個に精錬し、完成品用の空の樽へ収納してください。終了時はプレイヤーのインベントリ、材料チェスト、かまどを空にしてください。'
        timeout_minutes = 30
        inventory_profile = 'empty'
    }
    'hard-building-copy' = [ordered]@{
        prompt = 'チェストの材料を自由に加工して、近くにある屋根付きの木造建築を見本に、羊毛の上へ同じ建築をコピーしてください。'
        timeout_minutes = 90
        inventory_profile = 'empty'
    }
}
$AuditPromptProfile = if ($PSCmdlet.ParameterSetName -eq 'Audit') {
    $ExpectedPromptProfile
} else {
    'short-regression'
}
$AuditProfile = $EvaluationProfiles[$AuditPromptProfile]
$ProductionPrompt = [string]$AuditProfile['prompt']
$ExpectedInventoryProfile = [string]$AuditProfile['inventory_profile']
$ExpectedCatalogFileSha256 = '68c679e5e14feeca2c3721d62933448e1e84019a3278569881662109debb1f23'
$ExpectedToolSurfaceSha256 = '1f0b0576c7499b5bbdcab86207d070dbad6b8914d2888bdfd260109b988f2a24'
$ExpectedEvaluatorTimeoutSeconds = [int]$AuditProfile['timeout_minutes'] * 60
$TurnCompletionReserveSeconds = 15
$MaximumMcpForwardSeconds = 35
$AgentGetActionTransportMarginSeconds = 2
$DeadlineCleanupCancelTimeoutSeconds = 5
$DeadlineRejectedOutputText = '{"code":"EVALUATION_DEADLINE_IMMINENT","message":"The evaluation deadline is too close to safely forward another MCP request.","recoverable":false}'
$AllowedTools = @(
    'agent_get_state',
    'agent_get_observation',
    'agent_start_action',
    'agent_get_action',
    'agent_cancel_action'
)
$AllowedNotifications = @(
    'thread/started',
    'thread/status/changed',
    'turn/started',
    'item/started',
    'item/completed',
    'turn/completed'
)
$AllowedItemTypes = @(
    'userMessage', 'reasoning', 'agentMessage', 'dynamicToolCall', 'contextCompaction'
)
$RequiredReasoningDeltaOptOuts = @(
    'item/reasoning/summaryPartAdded',
    'item/reasoning/summaryTextDelta',
    'item/reasoning/textDelta'
)
$RequiredClientSendKinds = @('initialize', 'initialized', 'thread_start', 'turn_start')
$RequiredFalseFeatures = @(
    'multi_agent',
    'tool_suggest',
    'apps',
    'plugins',
    'image_generation',
    'standalone_web_search',
    'code_mode',
    'code_mode_only',
    'request_permissions_tool',
    'deferred_executor',
    'token_budget',
    'current_time_reminder'
)
$RequiredDisabledFeatures = @(
    'shell_tool', 'shell_snapshot', 'unified_exec', 'computer_use',
    'browser_use', 'browser_use_external', 'in_app_browser', 'apps', 'plugins',
    'remote_plugin', 'skill_search', 'skill_mcp_dependency_install',
    'tool_suggest', 'multi_agent', 'image_generation', 'workspace_dependencies',
    'goals', 'code_mode', 'code_mode_host', 'request_permissions_tool',
    'memories', 'hooks', 'auth_elicitation', 'tool_call_mcp_elicitation'
)
$RequiredCliConfigs = @(
    'cli_auth_credentials_store="ephemeral"',
    'tools.update_plan.enabled=false',
    'tools.experimental_request_user_input.enabled=false',
    'orchestrator.skills.enabled=false',
    'orchestrator.mcp.enabled=false',
    'web_search="disabled"',
    'tools.web_search=false',
    'memories.use_memories=false',
    'agents.enabled=false',
    'history.persistence="none"',
    'project_doc_max_bytes=0'
)
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$MaximumUnixTimeMilliseconds = ([DateTimeOffset]::MaxValue).ToUnixTimeMilliseconds()

function ConvertTo-CompactJson {
    param([AllowNull()][object]$Value)
    return (ConvertTo-Json -InputObject $Value -Depth 100 -Compress)
}

function Get-Sha256 {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { $Text = '' }
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
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

function ConvertTo-CanonicalNode {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [Collections.IDictionary]) {
        $sorted = [ordered]@{}
        foreach ($key in @($Value.Keys | ForEach-Object { [string]$_ } | Sort-Object)) {
            $sorted[$key] = ConvertTo-CanonicalNode $Value[$key]
        }
        return $sorted
    }
    if ($Value -is [pscustomobject]) {
        $sorted = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | Sort-Object Name)) {
            $sorted[$property.Name] = ConvertTo-CanonicalNode $property.Value
        }
        return $sorted
    }
    if ($Value -is [Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @($Value | ForEach-Object { ConvertTo-CanonicalNode $_ })
        Write-Output -NoEnumerate $items
        return
    }
    return $Value
}

function ConvertTo-SemanticCanonicalJson {
    param([AllowNull()][object]$Value)
    return (ConvertTo-Json -InputObject (ConvertTo-CanonicalNode $Value) `
        -Depth 100 -Compress)
}

function Get-NestedValue {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Path)
    $cursor = $Object
    foreach ($segment in $Path.Split('.')) {
        if ($null -eq $cursor) { return $null }
        $property = Get-Property -Object $cursor -Name $segment
        if ($null -eq $property) { return $null }
        $cursor = $property.Value
    }
    return $cursor
}

function Read-JsonLines {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][AllowEmptyCollection()]
        [Collections.Generic.List[string]]$Violations
    )
    $records = [Collections.Generic.List[object]]::new()
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $records.Add([ordered]@{
                    line = $lineNumber
                    value = ($line | ConvertFrom-Json -Depth 100 -DateKind String)
                })
        } catch {
            $Violations.Add("$Label line ${lineNumber}: JSONL として解析できません")
        }
    }
    return @($records)
}

function Add-ViolationUnless {
    param(
        [bool]$Condition,
        [Parameter(Mandatory)][string]$Message,
        [Parameter(Mandatory)][AllowEmptyCollection()]
        [Collections.Generic.List[string]]$Violations
    )
    if (-not $Condition) { $Violations.Add($Message) }
}

function Test-StringSetEquals {
    param([object[]]$Actual, [string[]]$Expected)
    if ($Actual.Count -ne $Expected.Count) { return $false }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ([string]$Actual[$index] -cne $Expected[$index]) { return $false }
    }
    return $true
}

function Test-StringSetContainsAll {
    param([object[]]$Actual, [string[]]$Required)
    foreach ($requiredValue in $Required) {
        if (@($Actual | Where-Object {
                    $_ -is [string] -and [string]$_ -ceq $requiredValue
                }).Count -ne 1) {
            return $false
        }
    }
    return $true
}

function Test-IsObjectValue {
    param([AllowNull()][object]$Value)
    return $null -ne $Value -and (
        $Value -is [pscustomobject] -or $Value -is [Collections.IDictionary])
}

function Test-IsExplicitEmptyArray {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)
    $property = Get-Property $Object $Name
    if ($null -eq $property -or $null -eq $property.Value -or
        $property.Value -is [string] -or
        $property.Value -isnot [Collections.IEnumerable]) {
        return $false
    }
    return @($property.Value).Count -eq 0
}

function Test-ExactPropertySet {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Expected
    )
    if (-not (Test-IsObjectValue $Object)) { return $false }
    $actual = if ($Object -is [Collections.IDictionary]) {
        @($Object.Keys | ForEach-Object { [string]$_ })
    } else {
        @($Object.PSObject.Properties | ForEach-Object { $_.Name })
    }
    $actualNames = @($actual)
    $expectedNames = @($Expected)
    if ($actualNames.Count -ne $expectedNames.Count) { return $false }
    foreach ($name in $expectedNames) {
        if (@($actualNames | Where-Object { $_ -ceq $name }).Count -ne 1) { return $false }
    }
    return $true
}

function Test-IsSafeReasoningItem {
    param(
        [AllowNull()][object]$Item,
        [Parameter(Mandatory)]
        [ValidateSet('item/started', 'item/completed')]
        [string]$Method
    )
    if (-not (Test-ExactPropertySet $Item @('id', 'type', 'summary', 'content'))) {
        return $false
    }
    $id = Get-PropertyValue $Item 'id'
    $summaryProperty = Get-Property $Item 'summary'
    if ($id -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$id) -or
        ([string]$id).Length -gt 256 -or ([string]$id) -cmatch '[\p{Cc}\p{Cf}]' -or
        (Get-PropertyValue $Item 'type') -cne 'reasoning' -or
        $null -eq $summaryProperty -or $null -eq $summaryProperty.Value -or
        $summaryProperty.Value -is [string] -or
        $summaryProperty.Value -isnot [Collections.IEnumerable] -or
        @($summaryProperty.Value | Where-Object { $_ -isnot [string] }).Count -ne 0) {
        return $false
    }
    if ($Method -ceq 'item/started' -and @($summaryProperty.Value).Count -ne 0) {
        return $false
    }
    # Public completed summaries are the only readable reasoning payload. The
    # private `content` channel must stay explicit and empty in every lifecycle item.
    return Test-IsExplicitEmptyArray $Item 'content'
}

function Test-IsJsonNumber {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $false }
    return $Value.GetType().FullName -in @(
        'System.Byte', 'System.SByte', 'System.Int16', 'System.UInt16',
        'System.Int32', 'System.UInt32', 'System.Int64', 'System.UInt64',
        'System.Single', 'System.Double', 'System.Decimal')
}

function Test-IsJsonInteger {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $false }
    return $Value.GetType().FullName -in @(
        'System.Byte', 'System.SByte', 'System.Int16', 'System.UInt16',
        'System.Int32', 'System.UInt32', 'System.Int64', 'System.UInt64')
}

function Get-ExpectedDynamicForwardTimeoutSeconds {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][AllowNull()][object]$Arguments
    )
    if (-not (Test-IsObjectValue $Arguments)) { return $null }
    if ($Tool -cne 'agent_get_action') {
        return $MaximumMcpForwardSeconds
    }

    $waitTimeoutMilliseconds = 0L
    $waitProperty = Get-Property -Object $Arguments -Name 'wait_timeout_ms'
    if ($null -ne $waitProperty) {
        if (-not (Test-IsJsonInteger $waitProperty.Value) -or
            $waitProperty.Value -lt 0 -or $waitProperty.Value -gt 25000) {
            return $null
        }
        $waitTimeoutMilliseconds = [long]$waitProperty.Value
    }
    $waitSeconds = [int][Math]::Ceiling($waitTimeoutMilliseconds / 1000.0D)
    return [int][Math]::Min(
        $MaximumMcpForwardSeconds,
        [Math]::Max(1, $waitSeconds + $AgentGetActionTransportMarginSeconds))
}

function Test-DeadlineCleanupCancelArguments {
    param([Parameter(Mandatory)][AllowNull()][object]$Arguments)
    if (-not (Test-IsObjectValue $Arguments)) { return $false }
    $propertyNames = if ($Arguments -is [Collections.IDictionary]) {
        @($Arguments.Keys | ForEach-Object { [string]$_ })
    } else {
        @($Arguments.PSObject.Properties | ForEach-Object { $_.Name })
    }
    $actionId = Get-PropertyValue -Object $Arguments -Name 'action_id'
    return @($propertyNames).Count -eq 1 -and @($propertyNames)[0] -ceq 'action_id' -and
        $actionId -is [string] -and $actionId -cmatch
            '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
}

function Test-DomainErrorText {
    param([AllowNull()][object]$Value)
    if ($Value -isnot [string]) { return $false }
    $document = $null
    try {
        $document = [Text.Json.JsonDocument]::Parse($Value)
    } catch {
        return $false
    }
    try {
        $root = $document.RootElement
        if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Object) { return $false }
        $properties = @($root.EnumerateObject())
        if ($properties.Count -ne 3) { return $false }
        $code = @($properties | Where-Object { $_.Name -ceq 'code' })
        $message = @($properties | Where-Object { $_.Name -ceq 'message' })
        $recoverable = @($properties | Where-Object { $_.Name -ceq 'recoverable' })
        return $code.Count -eq 1 -and $message.Count -eq 1 -and
            $recoverable.Count -eq 1 -and
            $code[0].Value.ValueKind -eq [Text.Json.JsonValueKind]::String -and
            $message[0].Value.ValueKind -eq [Text.Json.JsonValueKind]::String -and
            $recoverable[0].Value.ValueKind -in @(
                [Text.Json.JsonValueKind]::True,
                [Text.Json.JsonValueKind]::False)
    } finally {
        $document.Dispose()
    }
}

function Get-AppRequestIdKey {
    param([AllowNull()][object]$Value)
    if ($Value -is [string] -and -not [string]::IsNullOrWhiteSpace($Value)) {
        return 'string:' + $Value
    }
    if ($null -ne $Value -and $Value.GetType().FullName -in @(
            'System.Byte', 'System.SByte', 'System.Int16', 'System.UInt16',
            'System.Int32', 'System.UInt32', 'System.Int64', 'System.UInt64')) {
        return 'number:' + [Convert]::ToString(
            $Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    return $null
}

function Get-OnlyBridgeMessage {
    param(
        [object[]]$BridgeRecords,
        [Parameter(Mandatory)][string]$Kind,
        [Parameter(Mandatory)][AllowEmptyCollection()]
        [Collections.Generic.List[string]]$Violations
    )
    $matches = @($BridgeRecords | Where-Object {
            (Get-PropertyValue -Object $_.value -Name 'event') -eq 'client_send' -and
            (Get-PropertyValue -Object $_.value -Name 'kind') -eq $Kind
        })
    if ($matches.Count -ne 1) {
        $Violations.Add("bridge client_send '$Kind' は1件必須です: $($matches.Count)")
        return $null
    }
    $messageProperty = Get-Property -Object $matches[0].value -Name 'message'
    if ($null -eq $messageProperty -or -not (Test-IsObjectValue $messageProperty.Value)) {
        $Violations.Add("bridge client_send '$Kind' message は非null objectが必須です")
        return $null
    }
    return $messageProperty.Value
}

function Assert-HardeningConfig {
    param(
        [AllowNull()][object]$Config,
        [Parameter(Mandatory)][string]$ReasoningEffort,
        [Parameter(Mandatory)][AllowEmptyCollection()]
        [Collections.Generic.List[string]]$Violations
    )
    $expectedValues = [ordered]@{
        'cli_auth_credentials_store' = 'ephemeral'
        'model_reasoning_effort' = $ReasoningEffort
        'tools.update_plan.enabled' = $false
        'tools.experimental_request_user_input.enabled' = $false
        'orchestrator.skills.enabled' = $false
        'orchestrator.mcp.enabled' = $false
        'web_search' = 'disabled'
        'tools.web_search' = $false
        'memories.use_memories' = $false
        'agents.enabled' = $false
        'history.persistence' = 'none'
        'project_doc_max_bytes' = 0
    }
    foreach ($entry in $expectedValues.GetEnumerator()) {
        $actual = Get-NestedValue -Object $Config -Path $entry.Key
        if ((ConvertTo-CompactJson $actual) -cne (ConvertTo-CompactJson $entry.Value)) {
            $Violations.Add("thread/start hardening config mismatch: $($entry.Key)")
        }
    }
    foreach ($feature in $RequiredFalseFeatures) {
        $value = Get-NestedValue -Object $Config -Path "features.$feature"
        if ($value -isnot [bool] -or $value) {
            $Violations.Add("thread/start feature must be false: $feature")
        }
    }
}

function Invoke-TraceAudit {
    param(
        [Parameter(Mandatory)][string]$RawTrace,
        [Parameter(Mandatory)][string]$BridgeTrace,
        [Parameter(Mandatory)][string]$ReportPath,
        [Parameter(Mandatory)][string]$Model,
        [Parameter(Mandatory)][string]$Effort
    )

    $violations = [Collections.Generic.List[string]]::new()
    if (($Model -ceq 'gpt-5.6-sol' -and $Effort -cne 'high') -or
        ($Model -ceq 'gpt-5.6-luna' -and $Effort -cnotin @('high', 'xhigh'))) {
        $violations.Add("評価protocolで許可されないmodel/effort pairです: $Model/$Effort")
    }
    if (-not (Test-Path -LiteralPath $RawTrace -PathType Leaf)) {
        throw "trace が見つかりません: $RawTrace"
    }
    if (-not (Test-Path -LiteralPath $BridgeTrace -PathType Leaf)) {
        throw "bridge log が見つかりません: $BridgeTrace"
    }
    $traceRecords = @(Read-JsonLines -Path $RawTrace -Label 'trace' -Violations $violations)
    $bridgeRecords = @(Read-JsonLines -Path $BridgeTrace -Label 'bridge' -Violations $violations)

    $catalogPath = [IO.Path]::GetFullPath(
        [IO.Path]::Combine($PSScriptRoot, '..', '..', 'docs',
            'MCMCP_MCP_Tool_Catalog.json'))
    $catalogSurfaceHash = $null
    $catalogSurface = $null
    if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
        $violations.Add('canonical MCP Tool catalog is missing')
    } else {
        $catalogFileHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $catalogPath).Hash.ToLowerInvariant()
        if ($catalogFileHash -cne $ExpectedCatalogFileSha256) {
            $violations.Add("canonical MCP Tool catalog file hash mismatch: $catalogFileHash")
        }
        try {
            $catalog = [IO.File]::ReadAllText($catalogPath) | ConvertFrom-Json -Depth 100
            $catalogSurface = @(@(Get-PropertyValue $catalog 'tools') | ForEach-Object {
                    [ordered]@{
                        name = Get-PropertyValue $_ 'name'
                        description = Get-PropertyValue $_ 'description'
                        inputSchema = Get-PropertyValue $_ 'inputSchema'
                    }
                })
            $catalogSurfaceHash = Get-Sha256 (ConvertTo-SemanticCanonicalJson $catalogSurface)
            if ($catalogSurfaceHash -cne $ExpectedToolSurfaceSha256) {
                $violations.Add("canonical MCP Tool surface hash mismatch: $catalogSurfaceHash")
            }
        } catch {
            $violations.Add('canonical MCP Tool catalog could not be parsed')
        }
    }

    $expectedSequence = 1
    $previousBridgeUtc = $null
    foreach ($record in $bridgeRecords) {
        $sequence = Get-PropertyValue -Object $record.value -Name 'sequence'
        if ($sequence -ne $expectedSequence) {
            $violations.Add("bridge line $($record.line): sequence mismatch expected=$expectedSequence")
        }
        $expectedSequence++
        $event = [string](Get-PropertyValue -Object $record.value -Name 'event')
        if ($event -notin @(
                'preflight', 'launcher_config', 'app_response_received',
                'external_auth_login_ok', 'effective_config_checked', 'client_send',
                't0', 'readiness_check_failed',
                'mcp_forward_started', 'mcp_forward_completed',
                'mcp_forward_failed', 'dynamic_deadline_rejected',
                'dynamic_response_sent')) {
            $violations.Add("bridge line $($record.line): unknown event '$event'")
        }
        $utc = Get-PropertyValue -Object $record.value -Name 'utc'
        if ($utc -isnot [string]) {
            $violations.Add("bridge line $($record.line): utc timestamp is required")
        } else {
            try {
                $parsedBridgeUtc = [DateTimeOffset]::Parse(
                    $utc, [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::RoundtripKind)
                if ($null -ne $previousBridgeUtc -and $parsedBridgeUtc -lt $previousBridgeUtc) {
                    $violations.Add("bridge line $($record.line): utc timestamp order mismatch")
                }
                $previousBridgeUtc = $parsedBridgeUtc
            } catch {
                $violations.Add("bridge line $($record.line): utc timestamp is invalid")
            }
        }
        if ($event -ceq 'client_send' -and
            -not (Test-ExactPropertySet $record.value @(
                    'sequence', 'utc', 'event', 'kind', 'message'))) {
            $violations.Add("bridge line $($record.line): client_send property set mismatch")
        }
        if ($event -in @('mcp_forward_started', 'mcp_forward_completed',
                'mcp_forward_failed', 'dynamic_deadline_rejected',
                'dynamic_response_sent')) {
            foreach ($forbiddenField in @('authorization', 'bearer', 'result', 'response', 'content', 'text')) {
                if ($null -ne (Get-Property -Object $record.value -Name $forbiddenField)) {
                    $violations.Add("bridge line $($record.line): dynamic audit record contains '$forbiddenField'")
                }
            }
        }
        if ($event -ceq 'mcp_forward_failed') {
            Add-ViolationUnless (Test-ExactPropertySet $record.value @(
                    'sequence', 'utc', 'event', 'app_request_id', 'call_id',
                    'tool', 'mcp_request_id', 'failure_kind',
                    'diagnostic_code', 'http_status')) `
                "bridge line $($record.line): MCP failure property set mismatch" $violations
            $failureKind = [string](Get-PropertyValue $record.value 'failure_kind')
            $diagnosticCode = [string](Get-PropertyValue $record.value 'diagnostic_code')
            $httpStatus = Get-PropertyValue $record.value 'http_status'
            $diagnosticContractValid = switch ($failureKind) {
                'http_status' {
                    (Test-IsJsonInteger $httpStatus) -and
                    $httpStatus -ge 100 -and $httpStatus -le 599 -and
                    (($httpStatus -eq 429 -and $diagnosticCode -ceq 'rate_limited') -or
                    ($httpStatus -ne 429 -and $diagnosticCode -ceq 'http_non_success'))
                    break
                }
                'transport' {
                    $null -eq $httpStatus -and $diagnosticCode -cin @(
                        'request_timeout', 'http_request_failed', 'transport_unclassified')
                    break
                }
                'protocol_validation' {
                    $httpStatus -eq 200 -and $diagnosticCode -cin @(
                        'invalid_content_type', 'invalid_jsonrpc_envelope')
                    break
                }
                'deadline' {
                    $null -eq $httpStatus -and
                    $diagnosticCode -ceq 'turn_deadline_expired'
                    break
                }
                'internal' {
                    $null -eq $httpStatus -and
                    $diagnosticCode -ceq 'unclassified_bridge_exception'
                    break
                }
                default { $false }
            }
            Add-ViolationUnless $diagnosticContractValid `
                "bridge line $($record.line): MCP failure diagnostic contract mismatch" $violations
        }
        if ($event -ceq 'dynamic_deadline_rejected') {
            Add-ViolationUnless (Test-ExactPropertySet $record.value @(
                    'sequence', 'utc', 'event', 'app_request_id', 'call_id',
                    'thread_id', 'turn_id', 'tool', 'arguments_sha256', 'reason',
                    'remaining_seconds', 'forward_timeout_seconds',
                    'terminalization_reserve_seconds', 'required_headroom_seconds',
                    'success', 'output_sha256')) `
                "bridge line $($record.line): deadline rejection property set mismatch" $violations
            $reason = Get-PropertyValue $record.value 'reason'
            Add-ViolationUnless ($reason -is [string] -and $reason -cin @(
                    'insufficient_deadline_headroom', 'terminalization_latched')) `
                "bridge line $($record.line): deadline rejection reason mismatch" $violations
            $remainingSeconds = Get-PropertyValue $record.value 'remaining_seconds'
            $forwardTimeoutSeconds = Get-PropertyValue $record.value 'forward_timeout_seconds'
            $reserveSeconds = Get-PropertyValue $record.value 'terminalization_reserve_seconds'
            $requiredHeadroomSeconds = Get-PropertyValue $record.value 'required_headroom_seconds'
            Add-ViolationUnless ((Test-IsJsonInteger $remainingSeconds) -and
                $remainingSeconds -ge 0 -and $remainingSeconds -le $ExpectedEvaluatorTimeoutSeconds) `
                "bridge line $($record.line): deadline rejection remaining seconds mismatch" $violations
            Add-ViolationUnless ((Test-IsJsonInteger $forwardTimeoutSeconds) -and
                $forwardTimeoutSeconds -ge 1 -and
                $forwardTimeoutSeconds -le $MaximumMcpForwardSeconds) `
                "bridge line $($record.line): deadline rejection forward timeout mismatch" $violations
            Add-ViolationUnless ((Test-IsJsonInteger $reserveSeconds) -and
                $reserveSeconds -eq $TurnCompletionReserveSeconds) `
                "bridge line $($record.line): deadline rejection reserve mismatch" $violations
            Add-ViolationUnless ((Test-IsJsonInteger $requiredHeadroomSeconds) -and
                (Test-IsJsonInteger $forwardTimeoutSeconds) -and
                $requiredHeadroomSeconds -eq
                    ($forwardTimeoutSeconds + $TurnCompletionReserveSeconds)) `
                "bridge line $($record.line): deadline rejection headroom mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $record.value 'success') -is [bool] -and
                -not (Get-PropertyValue $record.value 'success')) `
                "bridge line $($record.line): deadline rejection success must be false" $violations
            Add-ViolationUnless ((Get-PropertyValue $record.value 'output_sha256') -ceq
                (Get-Sha256 $DeadlineRejectedOutputText)) `
                "bridge line $($record.line): deadline rejection output hash mismatch" $violations
        }
        if ($event -ceq 'readiness_check_failed') {
            $readinessNames = @(
                'ready_mode_ok', 'game_unpaused', 'world_present',
                'observation_present', 'inventory_profile_matches', 'rays_per_tick_512',
                'visible_entities_zero', 'action_idle_or_terminal')
            Add-ViolationUnless (Test-ExactPropertySet $record.value @(
                    'sequence', 'utc', 'event', 'phase', 'get_state_ok',
                    'ready_mode_ok', 'game_unpaused', 'world_present',
                    'observation_present', 'inventory_empty', 'inventory_profile_matches',
                    'rays_per_tick_512',
                    'visible_entities_zero', 'action_idle_or_terminal', 'failed_flags',
                    'raw_state_recorded')) `
                "bridge line $($record.line): readiness failure property set mismatch" $violations
            $phase = Get-PropertyValue $record.value 'phase'
            Add-ViolationUnless ($phase -is [string] -and
                $phase -cin @('preflight', 'preliminary', 'T0')) `
                "bridge line $($record.line): readiness failure phase mismatch" $violations
            $getStateOk = Get-PropertyValue $record.value 'get_state_ok'
            Add-ViolationUnless ($getStateOk -is [bool] -and $getStateOk) `
                "bridge line $($record.line): readiness failure get_state_ok must be true" $violations
            $expectedFailedFlags = [Collections.Generic.List[string]]::new()
            foreach ($readinessName in $readinessNames) {
                $readinessValue = Get-PropertyValue $record.value $readinessName
                Add-ViolationUnless ($readinessValue -is [bool]) `
                    "bridge line $($record.line): readiness diagnostic must be Boolean: $readinessName" $violations
                if ($readinessValue -is [bool] -and -not $readinessValue) {
                    $expectedFailedFlags.Add($readinessName)
                }
            }
            $failedFlagsProperty = Get-Property $record.value 'failed_flags'
            $failedFlags = if ($null -ne $failedFlagsProperty -and
                $failedFlagsProperty.Value -is [Collections.IEnumerable] -and
                $failedFlagsProperty.Value -isnot [string]) {
                @($failedFlagsProperty.Value)
            } else { @() }
            Add-ViolationUnless ($expectedFailedFlags.Count -gt 0) `
                "bridge line $($record.line): readiness failure has no false flag" $violations
            Add-ViolationUnless (Test-StringSetEquals $failedFlags @($expectedFailedFlags)) `
                "bridge line $($record.line): readiness failed_flags mismatch" $violations
            $rawStateRecorded = Get-PropertyValue $record.value 'raw_state_recorded'
            Add-ViolationUnless ($rawStateRecorded -is [bool] -and -not $rawStateRecorded) `
                "bridge line $($record.line): readiness raw state must not be recorded" $violations
            $violations.Add(
                "readiness check failed at ${phase}: $($expectedFailedFlags -join ', ')")
        }
    }

    $appResponseRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -ceq 'app_response_received'
        })
    foreach ($responseId in @('init', 'login', 'thread', 'turn')) {
        $matches = @($appResponseRecords | Where-Object {
                (Get-PropertyValue $_.value 'request_id') -ceq $responseId
            })
        Add-ViolationUnless ($matches.Count -eq 1) `
            "bridge app_response_received '$responseId' は1件必須です" $violations
        if ($matches.Count -eq 1) {
            $proof = $matches[0].value
            Add-ViolationUnless (Test-ExactPropertySet $proof @(
                    'sequence', 'utc', 'event', 'request_id', 'response_ok',
                    'contract_valid', 'raw_artifact_recorded')) `
                "app response proof '$responseId' property set mismatch" $violations
            foreach ($flag in @('response_ok', 'contract_valid', 'raw_artifact_recorded')) {
                $value = Get-PropertyValue $proof $flag
                Add-ViolationUnless ($value -is [bool] -and $value) `
                    "app response proof '$responseId' $flag must be true" $violations
            }
        }
    }
    Add-ViolationUnless ($appResponseRecords.Count -eq 4) `
        'bridge app_response_received は固定4 responseだけである必要があります' $violations

    $effectiveConfigRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -ceq 'effective_config_checked'
        })
    Add-ViolationUnless ($effectiveConfigRecords.Count -eq 1) `
        'effective_config_checked は1件必須です' $violations
    if ($effectiveConfigRecords.Count -eq 1) {
        $configProof = $effectiveConfigRecords[0].value
        Add-ViolationUnless (Test-ExactPropertySet $configProof @(
                'sequence', 'utc', 'event', 'request_id', 'include_layers',
                'cwd_is_clean', 'mcp_servers_object', 'mcp_server_count',
                'raw_artifact_recorded')) `
            'effective_config_checked property set mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $configProof 'request_id') -ceq 'config' -and
            (Get-PropertyValue $configProof 'mcp_server_count') -eq 0) `
            'effective config identity/MCP count mismatch' $violations
        foreach ($flag in @('include_layers', 'cwd_is_clean', 'mcp_servers_object')) {
            $value = Get-PropertyValue $configProof $flag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "effective config $flag must be true" $violations
        }
        $rawConfigRecorded = Get-PropertyValue $configProof 'raw_artifact_recorded'
        Add-ViolationUnless ($rawConfigRecorded -is [bool] -and -not $rawConfigRecorded) `
            'config/read response must not be written to raw artifact' $violations
    }

    $preflightRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue -Object $_.value -Name 'event') -eq 'preflight'
        })
    Add-ViolationUnless ($preflightRecords.Count -eq 1) `
        'bridge preflight は1件必須です' $violations
    $preflight = if ($preflightRecords.Count -eq 1) {
        $preflightRecords[0].value
    } else { $null }
    if ($null -ne $preflight) {
        Add-ViolationUnless ((Get-PropertyValue $preflight 'protocol_version') -eq '2026-07-28') `
            'preflight protocol version mismatch' $violations
        foreach ($flag in @(
                'discover_ok', 'discover_contract_ok', 'list_contract_ok',
                'discover_semantic_exact', 'list_semantic_exact',
                'jsonrpc_envelopes_ok', 'http_content_type_ok',
                'server_info_ok', 'direct_fallback_config_absent',
                'direct_fallback_path_reparse_absent', 'effective_config_read_ok',
                'effective_mcp_servers_object', 'get_state_ok')) {
            $value = Get-PropertyValue $preflight $flag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "preflight $flag must be true" $violations
        }
        $gameplayCalls = Get-PropertyValue $preflight 'gameplay_calls_made'
        Add-ViolationUnless ($gameplayCalls -is [bool] -and -not $gameplayCalls) `
            'preflight gameplay_calls_made must be false' $violations
        Add-ViolationUnless ((Get-PropertyValue $preflight 'effective_mcp_server_count') -eq 0) `
            'preflight effective MCP server count must be zero' $violations
        Add-ViolationUnless (Test-StringSetEquals `
                @(Get-PropertyValue $preflight 'listed_tools') $AllowedTools) `
            'preflight listed_tools mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $preflight 'catalog_file_sha256') -ceq
            $ExpectedCatalogFileSha256) 'preflight catalog file hash mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $preflight 'expected_tool_surface_sha256') -ceq
            $ExpectedToolSurfaceSha256 -and
            (Get-PropertyValue $preflight 'live_tool_surface_sha256') -ceq
            $ExpectedToolSurfaceSha256 -and $catalogSurfaceHash -ceq
            $ExpectedToolSurfaceSha256) 'preflight expected/live/catalog surface mismatch' $violations
        $surfaceMatch = Get-PropertyValue $preflight 'tool_surface_match'
        $noProxy = Get-PropertyValue $preflight 'parent_mcp_no_proxy'
        Add-ViolationUnless ($surfaceMatch -is [bool] -and $surfaceMatch) `
            'preflight tool_surface_match must be true' $violations
        Add-ViolationUnless ($noProxy -is [bool] -and $noProxy) `
            'preflight parent_mcp_no_proxy must be true' $violations
        $redirectsDisabled = Get-PropertyValue $preflight 'parent_mcp_redirects_disabled'
        Add-ViolationUnless ($redirectsDisabled -is [bool] -and $redirectsDisabled) `
            'preflight parent_mcp_redirects_disabled must be true' $violations
        foreach ($readinessFlag in @(
                'ready_mode_ok', 'game_unpaused', 'world_present',
                'observation_present', 'inventory_profile_matches', 'rays_per_tick_512',
                'visible_entities_zero', 'action_idle_or_terminal')) {
            $value = Get-PropertyValue $preflight $readinessFlag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "preflight readiness flag must be true: $readinessFlag" $violations
        }
        $preflightInventoryEmpty = Get-PropertyValue $preflight 'inventory_empty'
        Add-ViolationUnless ($preflightInventoryEmpty -is [bool] -and
            $preflightInventoryEmpty -eq ($ExpectedInventoryProfile -ceq 'empty')) `
            'preflight inventory-empty proof does not match the fixed profile' $violations
    }

    $launcherRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue -Object $_.value -Name 'event') -eq 'launcher_config'
        })
    Add-ViolationUnless ($launcherRecords.Count -eq 1) `
        'bridge launcher_config は1件必須です' $violations
    if ($launcherRecords.Count -eq 1) {
        $launcher = $launcherRecords[0].value
        Add-ViolationUnless ((Get-PropertyValue $launcher 'codex_version') -eq 'codex-cli 0.146.1') `
            'launcher Codex version mismatch' $violations
        foreach ($flag in @('stdio', 'strict_config', 'isolated_codex_home',
                'isolated_empty_cwd', 'external_auth_ephemeral',
                'tool_surface_pinned', 'clean_cwd_ancestor_config_absent',
                'isolated_home_config_absent', 'isolated_path_reparse_points_absent')) {
            $value = Get-PropertyValue $launcher $flag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "launcher $flag must be true" $violations
        }
        Add-ViolationUnless (Test-StringSetEquals `
                @(Get-PropertyValue $launcher 'disabled_features') $RequiredDisabledFeatures) `
            'launcher disabled feature list mismatch' $violations
        Add-ViolationUnless (Test-StringSetEquals `
                @(Get-PropertyValue $launcher 'cli_configs') $RequiredCliConfigs) `
            'launcher strict CLI config list mismatch' $violations
        $credentialFileCreated = Get-PropertyValue $launcher 'credential_file_created'
        Add-ViolationUnless ($credentialFileCreated -is [bool] -and -not $credentialFileCreated) `
            'launcher credential_file_created must be false' $violations
        Add-ViolationUnless ((Get-PropertyValue $launcher 'child_mcmcp_env_count') -eq 0 -and
            (Get-PropertyValue $launcher 'child_sensitive_env_count') -eq 0 -and
            (Get-PropertyValue $launcher 'child_forbidden_env_count') -eq 0 -and
            (Get-PropertyValue $launcher 'child_secret_value_count') -eq 0) `
            'launcher child environment still contains MCMCP/sensitive variables' $violations
    }

    $externalAuthRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'external_auth_login_ok'
        })
    Add-ViolationUnless ($externalAuthRecords.Count -eq 1) `
        'external_auth_login_ok は1件必須です' $violations
    if ($externalAuthRecords.Count -eq 1) {
        $authRecord = $externalAuthRecords[0].value
        Add-ViolationUnless ((Get-PropertyValue $authRecord 'request_id') -ceq 'login' -and
            (Get-PropertyValue $authRecord 'auth_type') -ceq 'chatgptAuthTokens') `
            'external auth identity mismatch' $violations
        foreach ($flag in @('jwt_lifetime_guard_ok')) {
            $value = Get-PropertyValue $authRecord $flag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "external auth $flag must be true" $violations
        }
        $authCredentialFileCreated = Get-PropertyValue $authRecord 'credential_file_created'
        Add-ViolationUnless ($authCredentialFileCreated -is [bool] -and
            -not $authCredentialFileCreated) `
            'external auth credential_file_created must be false' $violations
    }
    Add-ViolationUnless (@($bridgeRecords | Where-Object {
                (Get-PropertyValue $_.value 'event') -eq 'client_send' -and
                ((Get-PropertyValue $_.value 'kind') -match '(?i)login|auth' -or
                (Get-NestedValue $_.value 'message.method') -eq 'account/login/start')
            }).Count -eq 0) 'secret-bearing login request must not be logged' $violations

    $clientSendRecords = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -ceq 'client_send'
        })
    Add-ViolationUnless ($clientSendRecords.Count -eq $RequiredClientSendKinds.Count) `
        'bridge client_send は必須4 kindだけである必要があります' $violations
    foreach ($clientSendRecord in $clientSendRecords) {
        $kind = [string](Get-PropertyValue $clientSendRecord.value 'kind')
        if ($kind -cnotin $RequiredClientSendKinds) {
            $violations.Add("bridge line $($clientSendRecord.line): unknown client_send kind '$kind'")
        }
    }

    $initialize = Get-OnlyBridgeMessage -BridgeRecords $bridgeRecords `
        -Kind 'initialize' -Violations $violations
    $initialized = Get-OnlyBridgeMessage -BridgeRecords $bridgeRecords `
        -Kind 'initialized' -Violations $violations
    $threadStart = Get-OnlyBridgeMessage -BridgeRecords $bridgeRecords `
        -Kind 'thread_start' -Violations $violations
    $turnStart = Get-OnlyBridgeMessage -BridgeRecords $bridgeRecords `
        -Kind 'turn_start' -Violations $violations
    $t0Records = @($bridgeRecords | Where-Object {
            (Get-PropertyValue -Object $_.value -Name 'event') -eq 't0'
        })
    $t0DeadlineUtc = $null
    $t0 = $null
    Add-ViolationUnless ($t0Records.Count -eq 1) 'bridge t0 は1件必須です' $violations
    if ($t0Records.Count -eq 1) {
        $t0 = $t0Records[0].value
        try {
            $t0DeadlineUtc = [DateTimeOffset]::Parse(
                [string](Get-PropertyValue $t0 'utc'),
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind)
        } catch {
            $violations.Add('T0 UTC deadline proof is missing or invalid')
        }
        Add-ViolationUnless ((Get-PropertyValue $t0 'prompt_sha256') -eq
            (Get-Sha256 $ProductionPrompt)) 'T0 prompt hash mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $t0 'prompt_profile') -ceq
            $AuditPromptProfile) 'T0 prompt profile mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $t0 'timeout_seconds') -eq $ExpectedEvaluatorTimeoutSeconds) `
            "T0 timeout must be $ExpectedEvaluatorTimeoutSeconds seconds" $violations
        foreach ($readinessFlag in @(
                'preliminary_readiness_passed', 'evaluation_lease_header_bound',
                'readiness_rechecked', 'ready_mode_ok', 'game_unpaused',
                'world_present', 'observation_present', 'inventory_profile_matches',
                'rays_per_tick_512', 'visible_entities_zero',
                'action_idle_or_terminal')) {
            $value = Get-PropertyValue $t0 $readinessFlag
            Add-ViolationUnless ($value -is [bool] -and $value) `
                "T0 readiness flag must be true: $readinessFlag" $violations
        }
        $t0InventoryEmpty = Get-PropertyValue $t0 'inventory_empty'
        Add-ViolationUnless ($t0InventoryEmpty -is [bool] -and
            $t0InventoryEmpty -eq ($ExpectedInventoryProfile -ceq 'empty')) `
            'T0 inventory-empty proof does not match the fixed profile' $violations
    }

    $orderedKinds = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -notin @(
                'mcp_forward_started', 'mcp_forward_completed',
                'mcp_forward_failed', 'dynamic_deadline_rejected',
                'dynamic_response_sent',
                'readiness_check_failed')
        } | ForEach-Object {
            $event = [string](Get-PropertyValue $_.value 'event')
            if ($event -ceq 'client_send') {
                'client_send:' + [string](Get-PropertyValue $_.value 'kind')
            } elseif ($event -ceq 'app_response_received') {
                'app_response:' + [string](Get-PropertyValue $_.value 'request_id')
            } else {
                $event
            }
        })
    Add-ViolationUnless (
        (Test-StringSetEquals $orderedKinds @(
                'launcher_config',
                'client_send:initialize', 'app_response:init',
                'client_send:initialized', 'app_response:login',
                'external_auth_login_ok', 'effective_config_checked', 'preflight',
                'client_send:thread_start', 'app_response:thread', 't0',
                'client_send:turn_start', 'app_response:turn'))) `
        'bridge setup/config/preflight/T0 response 順序が不正です' $violations

    if ($null -ne $initialize) {
        Add-ViolationUnless (Test-ExactPropertySet $initialize @('method', 'id', 'params')) `
            'initialize message property set mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $initialize 'method') -eq 'initialize') `
            'initialize method mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $initialize 'id') -ceq 'init') `
            'initialize id mismatch' $violations
        $initializeParams = Get-PropertyValue $initialize 'params'
        Add-ViolationUnless (Test-IsObjectValue $initializeParams) `
            'initialize params object が必須です' $violations
        Add-ViolationUnless (Test-ExactPropertySet $initializeParams @(
                'clientInfo', 'capabilities')) `
            'initialize params property set mismatch' $violations
        $clientInfo = Get-PropertyValue $initializeParams 'clientInfo'
        Add-ViolationUnless ((Test-ExactPropertySet $clientInfo @('name', 'version')) -and
            (Get-PropertyValue $clientInfo 'name') -ceq 'mcmcp-fresh-eval' -and
            (Get-PropertyValue $clientInfo 'version') -ceq '1') `
            'initialize clientInfo mismatch' $violations
        $capabilities = Get-PropertyValue $initializeParams 'capabilities'
        Add-ViolationUnless (Test-IsObjectValue $capabilities) `
            'initialize capabilities object が必須です' $violations
        Add-ViolationUnless (Test-ExactPropertySet $capabilities @(
                'experimentalApi', 'optOutNotificationMethods')) `
            'initialize capabilities property set mismatch' $violations
        $experimentalApi = Get-PropertyValue $capabilities 'experimentalApi'
        Add-ViolationUnless ($experimentalApi -is [bool] -and $experimentalApi) `
            'initialize experimentalApi=true が必須です' $violations
        $optOutProperty = Get-Property $capabilities 'optOutNotificationMethods'
        Add-ViolationUnless ($null -ne $optOutProperty -and
            $optOutProperty.Value -is [Collections.IEnumerable] -and
            $optOutProperty.Value -isnot [string] -and
            @($optOutProperty.Value | Where-Object { $_ -isnot [string] }).Count -eq 0) `
            'initialize optOutNotificationMethods string array が必須です' $violations
        if ($null -ne $optOutProperty -and
            $optOutProperty.Value -is [Collections.IEnumerable] -and
            $optOutProperty.Value -isnot [string]) {
            $optOutMethods = @($optOutProperty.Value)
            Add-ViolationUnless (Test-StringSetContainsAll `
                    -Actual $optOutMethods -Required $RequiredReasoningDeltaOptOuts) `
                'reasoning raw/summary delta notification opt-out が必須です' $violations
        }
    }
    if ($null -ne $initialized) {
        Add-ViolationUnless (Test-ExactPropertySet $initialized @('method', 'params')) `
            'initialized message property set mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $initialized 'method') -eq 'initialized') `
            'initialized method mismatch' $violations
        Add-ViolationUnless ($null -eq (Get-Property $initialized 'id')) `
            'initialized notification に id を付けてはいけません' $violations
        Add-ViolationUnless (Test-IsObjectValue (Get-PropertyValue $initialized 'params')) `
            'initialized params object が必須です' $violations
        Add-ViolationUnless (Test-ExactPropertySet (Get-PropertyValue $initialized 'params') @()) `
            'initialized params は空objectである必要があります' $violations
    }

    $threadParams = if ($null -ne $threadStart) {
        Get-PropertyValue $threadStart 'params'
    } else { $null }
    if ($null -ne $threadStart) {
        Add-ViolationUnless (Test-IsObjectValue $threadParams) `
            'thread/start params object が必須です' $violations
        Add-ViolationUnless (Test-ExactPropertySet $threadStart @('method', 'id', 'params')) `
            'thread/start message property set mismatch' $violations
    }
    $cleanCwd = [string](Get-PropertyValue $threadParams 'cwd')
    if ($null -ne $threadParams) {
        Add-ViolationUnless (Test-ExactPropertySet $threadParams @(
                'model', 'cwd', 'approvalPolicy', 'sandbox', 'personality',
                'ephemeral', 'environments', 'runtimeWorkspaceRoots',
                'dynamicTools', 'config')) `
            'thread/start params に追加instruction/context fieldがあります' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadStart 'method') -eq 'thread/start') `
            'thread/start method mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadStart 'id') -ceq 'thread') `
            'thread/start id mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadParams 'model') -eq $Model) `
            'thread/start model mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadParams 'approvalPolicy') -eq 'never') `
            'thread/start approvalPolicy must be never' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadParams 'sandbox') -eq 'read-only') `
            'thread/start sandbox must be read-only' $violations
        Add-ViolationUnless ((Get-PropertyValue $threadParams 'personality') -eq 'none') `
            'thread/start personality must be none' $violations
        $ephemeral = Get-PropertyValue $threadParams 'ephemeral'
        Add-ViolationUnless ($ephemeral -is [bool] -and $ephemeral) `
            'thread/start ephemeral=true が必須です' $violations
        Add-ViolationUnless (Test-IsExplicitEmptyArray $threadParams 'environments') `
            'thread/start environments=[] が必須です' $violations
        Add-ViolationUnless (Test-IsExplicitEmptyArray $threadParams 'runtimeWorkspaceRoots') `
            'thread/start runtimeWorkspaceRoots=[] が必須です' $violations
        Add-ViolationUnless ($cleanCwd -match '[\\/]mcmcp-eval-[0-9a-f]{32}[\\/]empty-cwd$') `
            'thread/start cwd がclean isolated cwd形式ではありません' $violations

        $dynamicToolsProperty = Get-Property $threadParams 'dynamicTools'
        Add-ViolationUnless ($null -ne $dynamicToolsProperty -and
            $null -ne $dynamicToolsProperty.Value -and
            $dynamicToolsProperty.Value -is [Collections.IEnumerable] -and
            $dynamicToolsProperty.Value -isnot [string]) `
            'thread/start dynamicTools array が必須です' $violations
        $dynamicTools = @(Get-PropertyValue $threadParams 'dynamicTools')
        $dynamicNames = @($dynamicTools | ForEach-Object { Get-PropertyValue $_ 'name' })
        Add-ViolationUnless (Test-StringSetEquals $dynamicNames $AllowedTools) `
            'dynamicTools は固定5 toolsの順序・集合と一致する必要があります' $violations
        foreach ($tool in $dynamicTools) {
            $name = [string](Get-PropertyValue $tool 'name')
            Add-ViolationUnless (Test-ExactPropertySet $tool @(
                    'type', 'name', 'description', 'inputSchema')) `
                "dynamic tool property set mismatch: $name" $violations
            Add-ViolationUnless ((Get-PropertyValue $tool 'type') -eq 'function') `
                "dynamic tool type mismatch: $name" $violations
            Add-ViolationUnless (-not [string]::IsNullOrWhiteSpace(
                    [string](Get-PropertyValue $tool 'description'))) `
                "dynamic tool description missing: $name" $violations
            Add-ViolationUnless ($null -ne (Get-Property $tool 'inputSchema')) `
                "dynamic tool inputSchema missing: $name" $violations
        }
        if ($null -ne $catalogSurface) {
            $dynamicSurface = @($dynamicTools | ForEach-Object {
                    [ordered]@{
                        name = Get-PropertyValue $_ 'name'
                        description = Get-PropertyValue $_ 'description'
                        inputSchema = Get-PropertyValue $_ 'inputSchema'
                    }
                })
            Add-ViolationUnless ((ConvertTo-SemanticCanonicalJson $dynamicSurface) -ceq
                (ConvertTo-SemanticCanonicalJson $catalogSurface)) `
                'thread dynamicTools surface does not match canonical catalog' $violations
        }
        if ($null -ne $preflight) {
            Add-ViolationUnless ((Get-PropertyValue $preflight 'dynamic_tools_sha256') -eq
                (Get-Sha256 (ConvertTo-CompactJson $dynamicTools))) `
                'preflight/thread dynamic tool schema hash mismatch' $violations
        }
        $threadConfig = Get-PropertyValue $threadParams 'config'
        Add-ViolationUnless (Test-IsObjectValue $threadConfig) `
            'thread/start config object が必須です' $violations
        Assert-HardeningConfig -Config $threadConfig `
            -ReasoningEffort $Effort `
            -Violations $violations
    }

    $turnParams = if ($null -ne $turnStart) {
        Get-PropertyValue $turnStart 'params'
    } else { $null }
    if ($null -ne $turnStart) {
        Add-ViolationUnless (Test-IsObjectValue $turnParams) `
            'turn/start params object が必須です' $violations
        Add-ViolationUnless (Test-ExactPropertySet $turnStart @('method', 'id', 'params')) `
            'turn/start message property set mismatch' $violations
    }
    if ($null -ne $turnParams) {
        Add-ViolationUnless (Test-ExactPropertySet $turnParams @(
                'threadId', 'input', 'model', 'effort', 'summary', 'cwd', 'environments')) `
            'turn/start params に追加instruction/context fieldがあります' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnStart 'method') -eq 'turn/start') `
            'turn/start method mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnStart 'id') -ceq 'turn') `
            'turn/start id mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnParams 'model') -eq $Model) `
            'turn/start model mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnParams 'effort') -eq $Effort) `
            'turn/start effort mismatch' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnParams 'summary') -ceq 'detailed') `
            'turn/start summary=detailed が必須です' $violations
        Add-ViolationUnless ((Get-PropertyValue $turnParams 'cwd') -ceq $cleanCwd) `
            'thread/turn cwd mismatch' $violations
        Add-ViolationUnless (Test-IsExplicitEmptyArray $turnParams 'environments') `
            'turn/start environments=[] が必須です' $violations
        $inputProperty = Get-Property $turnParams 'input'
        $inputArrayOk = $null -ne $inputProperty -and $null -ne $inputProperty.Value -and
            $inputProperty.Value -is [Collections.IEnumerable] -and
            $inputProperty.Value -isnot [string]
        Add-ViolationUnless $inputArrayOk 'turn/start input array が必須です' $violations
        $inputs = @(Get-PropertyValue $turnParams 'input')
        $exactPrompt = $inputArrayOk -and $inputs.Count -eq 1 -and
            (Get-PropertyValue $inputs[0] 'type') -eq 'text' -and
            (Get-PropertyValue $inputs[0] 'text') -ceq $ProductionPrompt -and
            @($inputs[0].PSObject.Properties.Name).Count -eq 2
        Add-ViolationUnless $exactPrompt `
            'turn/start input はproduction prompt exact 1件（prefix/suffixなし）が必須です' $violations
    }

    $responseCounts = [Collections.Generic.Dictionary[string, int]]::new(
        [StringComparer]::Ordinal)
    foreach ($responseId in @('init', 'login', 'thread', 'turn')) {
        $responseCounts.Add($responseId, 0)
    }
    $expectedResponseOrder = @('init', 'login', 'thread', 'turn')
    $responseOrderIndex = 0
    $threadId = $null
    $turnId = $null
    $threadEffectiveResult = $null
    $threadStarted = 0
    $turnStarted = 0
    $turnCompleted = 0
    $turnTerminalSeen = $false
    $completedAgentMessage = $false
    $completedUserMessage = $false
    $completedUserMessageCount = 0
    $itemLifecycle = [Collections.Generic.Dictionary[string, object]]::new(
        [StringComparer]::Ordinal)
    $dynamicRequests = [Collections.Generic.List[object]]::new()
    $dynamicCompleted = [Collections.Generic.Dictionary[string, object]]::new(
        [StringComparer]::Ordinal)
    $seenAppRequestIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $seenDynamicCallIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $lifecyclePhase = 'expect_thread_started'
    $notifiedThreadId = $null
    $notifiedTurnId = $null
    $completedTurnId = $null
    $previousEmittedAtMs = $null

    foreach ($record in $traceRecords) {
        $message = $record.value
        $line = $record.line
        $idProperty = Get-Property $message 'id'
        $methodProperty = Get-Property $message 'method'

        if ($turnTerminalSeen) {
            $violations.Add("trace line ${line}: turn/completed 後のmessageは禁止です")
            continue
        }

        if ($null -ne $idProperty -and $null -eq $methodProperty) {
            $idKey = [string]$idProperty.Value
            if (-not $responseCounts.ContainsKey($idKey)) {
                $violations.Add("trace line ${line}: unexpected response id=$idKey")
                continue
            }
            if ($responseOrderIndex -ge $expectedResponseOrder.Count -or
                $idKey -cne $expectedResponseOrder[$responseOrderIndex]) {
                $expectedId = if ($responseOrderIndex -lt $expectedResponseOrder.Count) {
                    $expectedResponseOrder[$responseOrderIndex]
                } else { '<none>' }
                $violations.Add(
                    "trace line ${line}: app response order mismatch expected=$expectedId actual=$idKey")
            } else {
                $responseOrderIndex++
            }
            $responseCounts[$idKey]++
            $responseError = Get-Property $message 'error'
            $responseResult = Get-Property $message 'result'
            Add-ViolationUnless (Test-ExactPropertySet $message @('id', 'result')) `
                "trace line ${line}: app response property set mismatch id=$idKey" $violations
            if ($null -ne $responseError -or
                $null -eq $responseResult -or $null -eq $responseResult.Value) {
                $violations.Add("trace line ${line}: request id=$idKey returned error")
            }
            if ($idKey -eq 'login') {
                $loginResultValue = if ($null -ne $responseResult) {
                    $responseResult.Value
                } else { $null }
                Add-ViolationUnless ((ConvertTo-SemanticCanonicalJson $loginResultValue) -ceq
                    (ConvertTo-SemanticCanonicalJson ([ordered]@{
                                type = 'chatgptAuthTokens'
                            }))) 'external auth login response mismatch' $violations
            } elseif ($idKey -eq 'thread') {
                $threadId = [string](Get-NestedValue $message 'result.thread.id')
                if ($null -ne $responseResult) {
                    $threadEffectiveResult = $responseResult.Value
                }
            } elseif ($idKey -eq 'turn') {
                $turnId = [string](Get-NestedValue $message 'result.turn.id')
            }
            continue
        }

        if ($null -ne $idProperty -and $null -ne $methodProperty) {
            $method = [string]$methodProperty.Value
            if ($method -cne 'item/tool/call') {
                if ($method -eq 'account/chatgptAuthTokens/refresh') {
                    $violations.Add("trace line ${line}: external auth refresh request is forbidden")
                } else {
                    $violations.Add("trace line ${line}: forbidden server request '$method'")
                }
                continue
            }
            if ($lifecyclePhase -ne 'active') {
                $violations.Add("trace line ${line}: item/tool/call is outside active turn")
            }
            $params = Get-PropertyValue $message 'params'
            if (-not (Test-IsObjectValue $params)) {
                $violations.Add("trace line ${line}: item/tool/call params object is required")
            }
            $requestIdKey = Get-AppRequestIdKey $idProperty.Value
            if ($null -eq $requestIdKey) {
                $violations.Add("trace line ${line}: item/tool/call id type is invalid")
            } elseif (-not $seenAppRequestIds.Add($requestIdKey)) {
                $violations.Add("trace line ${line}: duplicate app-server request id")
            }
            $namespaceProperty = Get-Property $params 'namespace'
            if ($null -ne $namespaceProperty -and $null -ne $namespaceProperty.Value) {
                $violations.Add("trace line ${line}: item/tool/call namespace must be omitted or null")
            }
            $request = [ordered]@{
                line = $line
                app_request_id = $idProperty.Value
                call_id = [string](Get-PropertyValue $params 'callId')
                thread_id = [string](Get-PropertyValue $params 'threadId')
                turn_id = [string](Get-PropertyValue $params 'turnId')
                tool = [string](Get-PropertyValue $params 'tool')
                arguments = Get-PropertyValue $params 'arguments'
            }
            if ($request.tool -cnotin $AllowedTools -or
                [string]::IsNullOrWhiteSpace($request.call_id) -or
                -not (Test-IsObjectValue $request.arguments)) {
                $violations.Add("trace line ${line}: item/tool/call validation failed")
            }
            if (-not [string]::IsNullOrWhiteSpace($request.call_id) -and
                -not $seenDynamicCallIds.Add($request.call_id)) {
                $violations.Add("trace line ${line}: duplicate dynamic callId")
            }
            if ($request.thread_id -cne $threadId -or $request.turn_id -cne $turnId) {
                $violations.Add("trace line ${line}: item/tool/call active thread/turn mismatch")
            }
            if (-not $itemLifecycle.ContainsKey($request.call_id)) {
                $violations.Add("trace line ${line}: item/tool/call has no preceding dynamicToolCall start")
            } else {
                $requestLifecycle = $itemLifecycle[$request.call_id]
                if ($requestLifecycle.type -ne 'dynamicToolCall' -or
                    $requestLifecycle.completed -or $requestLifecycle.request_seen) {
                    $violations.Add("trace line ${line}: item/tool/call lifecycle order mismatch")
                }
                $startedItem = $requestLifecycle.item
                if ((Get-PropertyValue $startedItem 'tool') -cne $request.tool -or
                    (Get-Sha256 (ConvertTo-CompactJson (Get-PropertyValue $startedItem 'arguments'))) -cne
                    (Get-Sha256 (ConvertTo-CompactJson $request.arguments))) {
                    $violations.Add("trace line ${line}: dynamicToolCall start/request mismatch")
                }
                $requestLifecycle.request_seen = $true
                $requestLifecycle.request_line = $line
            }
            $dynamicRequests.Add($request)
            continue
        }

        if ($null -eq $methodProperty) {
            $violations.Add("trace line ${line}: unclassified message")
            continue
        }
        $method = [string]$methodProperty.Value
        if ($method -cnotin $AllowedNotifications) {
            $violations.Add("trace line ${line}: forbidden notification '$method'")
            continue
        }
        Add-ViolationUnless (Test-ExactPropertySet $message @(
                'method', 'params', 'emittedAtMs')) `
            "trace line ${line}: notification property set mismatch '$method'" $violations
        $emittedAtMs = Get-PropertyValue $message 'emittedAtMs'
        $emittedAtMsValid = (Test-IsJsonInteger $emittedAtMs) -and
            $emittedAtMs -ge 0 -and $emittedAtMs -le $MaximumUnixTimeMilliseconds
        Add-ViolationUnless $emittedAtMsValid `
            "trace line ${line}: notification emittedAtMs must be an integer Unix-ms value in range" `
            $violations
        if ($emittedAtMsValid) {
            if ($null -ne $previousEmittedAtMs -and
                $emittedAtMs -lt $previousEmittedAtMs) {
                $violations.Add(
                    "trace line ${line}: notification emittedAtMs order mismatch")
            }
            $previousEmittedAtMs = [long]$emittedAtMs
        }
        $params = Get-PropertyValue $message 'params'
        switch ($method) {
            'thread/started' {
                $threadStarted++
                Add-ViolationUnless (Test-ExactPropertySet $params @('thread')) `
                    "trace line ${line}: thread/started params mismatch" $violations
                $notifiedThreadId = [string](Get-NestedValue $params 'thread.id')
                if ($responseOrderIndex -lt 2) {
                    $violations.Add("trace line ${line}: thread/started precedes login response")
                }
                if ($lifecyclePhase -ne 'expect_thread_started') {
                    $violations.Add("trace line ${line}: thread/started lifecycle order mismatch")
                }
                $lifecyclePhase = 'expect_turn_started'
            }
            'thread/status/changed' {
                Add-ViolationUnless (Test-ExactPropertySet $params @('threadId', 'status')) `
                    "trace line ${line}: thread/status/changed params mismatch" $violations
                if ((Get-PropertyValue $params 'threadId') -cne $threadId) {
                    $violations.Add("trace line ${line}: thread/status/changed thread mismatch")
                }
            }
            'turn/started' {
                $turnStarted++
                Add-ViolationUnless (Test-ExactPropertySet $params @('threadId', 'turn')) `
                    "trace line ${line}: turn/started params mismatch" $violations
                $notifiedTurnId = [string](Get-NestedValue $params 'turn.id')
                if ((Get-PropertyValue $params 'threadId') -cne $threadId -or
                    $notifiedTurnId -cne $turnId) {
                    $violations.Add("trace line ${line}: turn/started active thread/turn mismatch")
                }
                if ($responseOrderIndex -ne $expectedResponseOrder.Count) {
                    $violations.Add("trace line ${line}: turn/started precedes setup response completion")
                }
                if ($lifecyclePhase -ne 'expect_turn_started') {
                    $violations.Add("trace line ${line}: turn/started lifecycle order mismatch")
                }
                $lifecyclePhase = 'active'
            }
            'turn/completed' {
                $turnCompleted++
                Add-ViolationUnless (Test-ExactPropertySet $params @('threadId', 'turn')) `
                    "trace line ${line}: turn/completed params mismatch" $violations
                if ($lifecyclePhase -ne 'active') {
                    $violations.Add("trace line ${line}: turn/completed lifecycle order mismatch")
                }
                $completedTurn = Get-PropertyValue $params 'turn'
                $completedTurnId = [string](Get-PropertyValue $completedTurn 'id')
                if ((Get-PropertyValue $params 'threadId') -cne $threadId -or
                    $completedTurnId -cne $turnId) {
                    $violations.Add("trace line ${line}: turn/completed active thread/turn mismatch")
                }
                if ((Get-PropertyValue $completedTurn 'status') -ne 'completed') {
                    $violations.Add("trace line ${line}: turn status is not completed")
                }
                $turnError = Get-Property $completedTurn 'error'
                if ($null -eq $turnError -or $null -ne $turnError.Value) {
                    $violations.Add("trace line ${line}: completed turn error must be explicit null")
                }
                $turnTerminalSeen = $true
                $lifecyclePhase = 'complete'
            }
            { $_ -in @('item/started', 'item/completed') } {
                if ($lifecyclePhase -ne 'active') {
                    $violations.Add("trace line ${line}: item lifecycle is outside active turn")
                }
                $timestampName = if ($method -eq 'item/started') {
                    'startedAtMs'
                } else { 'completedAtMs' }
                Add-ViolationUnless (Test-ExactPropertySet $params @(
                        'threadId', 'turnId', $timestampName, 'item')) `
                    "trace line ${line}: $method params property set mismatch" $violations
                if ((Get-PropertyValue $params 'threadId') -cne $threadId -or
                    (Get-PropertyValue $params 'turnId') -cne $turnId) {
                    $violations.Add("trace line ${line}: $method active thread/turn mismatch")
                }
                $timestamp = Get-PropertyValue $params $timestampName
                if (-not (Test-IsJsonNumber $timestamp) -or $timestamp -lt 0) {
                    $violations.Add("trace line ${line}: $method $timestampName must be non-negative numeric")
                }
                if ($emittedAtMsValid -and (Test-IsJsonNumber $timestamp) -and
                    $timestamp -ge 0 -and $emittedAtMs -lt $timestamp) {
                    $violations.Add(
                        "trace line ${line}: notification emittedAtMs precedes $timestampName")
                }
                $item = Get-PropertyValue $params 'item'
                $itemId = [string](Get-PropertyValue $item 'id')
                $itemType = [string](Get-PropertyValue $item 'type')
                if ($itemType -cnotin $AllowedItemTypes) {
                    $violations.Add("trace line ${line}: MCP-only評価で許可されない item type '$itemType'")
                }
                if ($itemType -ceq 'reasoning') {
                    Add-ViolationUnless (Test-IsSafeReasoningItem -Item $item -Method $method) `
                        "trace line ${line}: reasoning item exact safe schema mismatch" `
                        $violations
                } elseif ($itemType -ceq 'contextCompaction') {
                    Add-ViolationUnless (Test-ExactPropertySet $item @('type', 'id')) `
                        "trace line ${line}: contextCompaction item exact schema mismatch" `
                        $violations
                }
                if ([string]::IsNullOrWhiteSpace($itemId)) {
                    $violations.Add("trace line ${line}: item id missing")
                    $itemId = "<missing>:$line"
                }
                if ($method -eq 'item/started') {
                    if ($itemLifecycle.ContainsKey($itemId)) {
                        $violations.Add("trace line ${line}: item '$itemId' duplicate start")
                    } else {
                        $itemLifecycle[$itemId] = [ordered]@{
                            type = $itemType
                            started = $true
                            completed = $false
                            request_seen = $false
                            request_line = $null
                            start_line = $line
                            item = $item
                        }
                    }
                    if ($itemType -eq 'dynamicToolCall' -and
                        (Get-PropertyValue $item 'status') -ne 'inProgress') {
                        $violations.Add("trace line ${line}: dynamicToolCall start status mismatch")
                    }
                    if ($itemType -eq 'dynamicToolCall' -and
                        ((Get-PropertyValue $item 'tool') -cnotin $AllowedTools -or
                        -not (Test-IsObjectValue (Get-PropertyValue $item 'arguments')))) {
                        $violations.Add("trace line ${line}: dynamicToolCall start tool/arguments invalid")
                    }
                } else {
                    if (-not $itemLifecycle.ContainsKey($itemId)) {
                        $violations.Add("trace line ${line}: item '$itemId' completed without start")
                        $itemLifecycle[$itemId] = [ordered]@{
                            type = $itemType; started = $false; completed = $false
                            request_seen = $false; request_line = $null; start_line = $null
                            item = $null
                        }
                    }
                    $lifecycle = $itemLifecycle[$itemId]
                    if ($lifecycle.completed) {
                        $violations.Add("trace line ${line}: item '$itemId' duplicate completion")
                    }
                    if ($lifecycle.type -ne $itemType) {
                        $violations.Add("trace line ${line}: item '$itemId' type changed")
                    }
                    $lifecycle.completed = $true
                    if ($itemType -eq 'agentMessage') {
                        if (-not [string]::IsNullOrWhiteSpace(
                                [string](Get-PropertyValue $item 'text'))) {
                            $completedAgentMessage = $true
                        }
                    } elseif ($itemType -eq 'userMessage') {
                        $completedUserMessageCount++
                        $content = @(Get-PropertyValue $item 'content')
                        if ($content.Count -eq 1 -and
                            (Get-PropertyValue $content[0] 'type') -eq 'text' -and
                            (Get-PropertyValue $content[0] 'text') -ceq $ProductionPrompt) {
                            $completedUserMessage = $true
                        } else {
                            $violations.Add("trace line ${line}: userMessage is not exact production prompt")
                        }
                    } elseif ($itemType -eq 'dynamicToolCall') {
                        $contentItems = @(Get-PropertyValue $item 'contentItems')
                        $success = Get-PropertyValue $item 'success'
                        $status = [string](Get-PropertyValue $item 'status')
                        $terminalStatusOk = $status -in @('completed', 'failed')
                        if ((Get-PropertyValue $item 'tool') -cnotin $AllowedTools -or
                            -not (Test-IsObjectValue (Get-PropertyValue $item 'arguments'))) {
                            $violations.Add("trace line ${line}: dynamicToolCall '$itemId' terminal tool/arguments invalid")
                        }
                        if (-not $terminalStatusOk -or $success -isnot [bool] -or
                            ($success -and $status -ne 'completed') -or
                            (-not $success -and $status -ne 'failed') -or
                            $contentItems.Count -ne 1 -or
                            -not (Test-ExactPropertySet $contentItems[0] @('type', 'text')) -or
                            (Get-PropertyValue $contentItems[0] 'type') -ne 'inputText' -or
                            (Get-PropertyValue $contentItems[0] 'text') -isnot [string]) {
                            $violations.Add("trace line ${line}: dynamicToolCall '$itemId' has invalid terminal result")
                        }
                        if (-not $lifecycle.request_seen) {
                            $violations.Add("trace line ${line}: dynamicToolCall '$itemId' completed without request")
                        }
                        $startedItem = $lifecycle.item
                        if ((Get-PropertyValue $startedItem 'tool') -cne
                            (Get-PropertyValue $item 'tool') -or
                            (Get-Sha256 (ConvertTo-CompactJson (
                                        Get-PropertyValue $startedItem 'arguments'))) -cne
                            (Get-Sha256 (ConvertTo-CompactJson (
                                        Get-PropertyValue $item 'arguments')))) {
                            $violations.Add("trace line ${line}: dynamicToolCall start/completion mismatch")
                        }
                        $outputText = if ($contentItems.Count -eq 1) {
                            [string](Get-PropertyValue $contentItems[0] 'text')
                        } else { '' }
                        $domainErrorTextValid = $success -is [bool] -and
                            (-not $success) -and (Test-DomainErrorText $outputText)
                        if ($success -is [bool] -and -not $success -and
                            -not $domainErrorTextValid) {
                            $violations.Add(
                                "trace line ${line}: dynamicToolCall '$itemId' domain error body is invalid")
                        }
                        $dynamicCompleted[$itemId] = [ordered]@{
                            tool = [string](Get-PropertyValue $item 'tool')
                            arguments = Get-PropertyValue $item 'arguments'
                            status = $status
                            success = $success
                            output_sha256 = Get-Sha256 $outputText
                            output_text = $(if ($AuditPromptProfile -ceq 'container-inspect-recovery' -or
                                    $AuditPromptProfile -like 'tunnel-*') { $outputText } else { $null })
                            domain_error_contract_valid = $domainErrorTextValid
                        }
                    }
                }
            }
        }
    }

    foreach ($key in @('init', 'login', 'thread', 'turn')) {
        if ($responseCounts[$key] -ne 1) {
            $violations.Add("app-server response id=$key は1件必須です: $($responseCounts[$key])")
        }
    }
    Add-ViolationUnless ($responseOrderIndex -eq $expectedResponseOrder.Count) `
        'app-server setup responses are incomplete or out of order' $violations
    if ($null -ne $threadEffectiveResult) {
        $effectiveSandbox = Get-PropertyValue $threadEffectiveResult 'sandbox'
        $effectiveThread = Get-PropertyValue $threadEffectiveResult 'thread'
        Add-ViolationUnless ((Get-PropertyValue $threadEffectiveResult 'model') -ceq $Model -and
            (Get-PropertyValue $threadEffectiveResult 'cwd') -ceq $cleanCwd -and
            (Get-PropertyValue $threadEffectiveResult 'approvalPolicy') -ceq 'never' -and
            (Get-PropertyValue $threadEffectiveResult 'approvalsReviewer') -ceq 'user' -and
            (Get-PropertyValue $threadEffectiveResult 'modelProvider') -ceq 'openai' -and
            (Get-PropertyValue $threadEffectiveResult 'reasoningEffort') -ceq $Effort) `
            'thread/start effective scalar values mismatch' $violations
        Add-ViolationUnless (Test-IsObjectValue $effectiveSandbox) `
            'thread/start effective sandbox object is required' $violations
        Add-ViolationUnless ((Get-PropertyValue $effectiveSandbox 'type') -ceq 'readOnly' -and
            (Get-PropertyValue $effectiveSandbox 'networkAccess') -is [bool] -and
            -not (Get-PropertyValue $effectiveSandbox 'networkAccess')) `
            'thread/start effective sandbox is not readOnly/network-disabled' $violations
        Add-ViolationUnless (Test-IsExplicitEmptyArray $threadEffectiveResult 'instructionSources') `
            'thread/start effective instructionSources=[] is required' $violations
        Add-ViolationUnless (Test-IsExplicitEmptyArray $threadEffectiveResult 'runtimeWorkspaceRoots') `
            'thread/start effective runtimeWorkspaceRoots=[] is required' $violations
        Add-ViolationUnless (Test-IsObjectValue $effectiveThread) `
            'thread/start effective thread object is required' $violations
        $effectiveThreadEphemeral = Get-PropertyValue $effectiveThread 'ephemeral'
        Add-ViolationUnless ($effectiveThreadEphemeral -is [bool] -and
            $effectiveThreadEphemeral -and
            (Get-PropertyValue $effectiveThread 'cwd') -ceq $cleanCwd -and
            (Get-PropertyValue $effectiveThread 'modelProvider') -ceq 'openai') `
            'thread/start effective thread isolation values mismatch' $violations
    }
    Add-ViolationUnless ($threadStarted -eq 1) 'thread/started は1件必須です' $violations
    Add-ViolationUnless ($turnStarted -eq 1) 'turn/started は1件必須です' $violations
    Add-ViolationUnless ($turnCompleted -eq 1) 'turn/completed は1件必須です' $violations
    Add-ViolationUnless ($notifiedThreadId -eq $threadId) `
        'thread/started id and thread/start response mismatch' $violations
    Add-ViolationUnless ($notifiedTurnId -eq $turnId -and $completedTurnId -eq $turnId) `
        'turn lifecycle ids and turn/start response mismatch' $violations
    Add-ViolationUnless ($completedUserMessage -and $completedUserMessageCount -eq 1) `
        'exact userMessage completion は1件必須です' $violations
    Add-ViolationUnless $completedAgentMessage 'completed agentMessage がありません' $violations
    if ($null -ne $turnParams) {
        Add-ViolationUnless ((Get-PropertyValue $turnParams 'threadId') -eq $threadId) `
            'turn/start threadId and response thread id mismatch' $violations
    }
    foreach ($entry in $itemLifecycle.GetEnumerator()) {
        if (-not $entry.Value.started -or -not $entry.Value.completed) {
            $violations.Add("item '$($entry.Key)' lifecycle incomplete")
        }
    }

    $bridgeStarts = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'mcp_forward_started'
        })
    $bridgeCompletions = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'mcp_forward_completed'
        })
    $bridgeResponses = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'dynamic_response_sent'
        })
    $bridgeFailures = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'mcp_forward_failed'
        })
    $bridgeRejections = @($bridgeRecords | Where-Object {
            (Get-PropertyValue $_.value 'event') -eq 'dynamic_deadline_rejected'
        })
    Add-ViolationUnless ($bridgeFailures.Count -eq 0) `
        'MCP transport/protocol bridge failure recordがあります' $violations
    $turnResponseProof = @($appResponseRecords | Where-Object {
            (Get-PropertyValue $_.value 'request_id') -ceq 'turn'
        })
    $turnResponseProofSequence = if ($turnResponseProof.Count -eq 1) {
        Get-PropertyValue $turnResponseProof[0].value 'sequence'
    } else { [long]::MaxValue }
    foreach ($dynamicBridgeRecord in @(
            $bridgeStarts + $bridgeCompletions + $bridgeResponses +
            $bridgeFailures + $bridgeRejections)) {
        Add-ViolationUnless ((Get-PropertyValue $dynamicBridgeRecord.value 'sequence') -gt
            $turnResponseProofSequence) `
            "dynamic bridge event must follow validated turn/start response" $violations
    }
    $orderedBridgeRejections = @($bridgeRejections | Sort-Object {
            Get-PropertyValue $_.value 'sequence'
        })
    $firstRejectionSequence = $null
    $firstRejectionResponseSequence = $null
    $deadlineCleanupForwardCount = 0
    if ($orderedBridgeRejections.Count -gt 0) {
        for ($rejectionIndex = 0; $rejectionIndex -lt $orderedBridgeRejections.Count;
            $rejectionIndex++) {
            $expectedReason = if ($rejectionIndex -eq 0) {
                'insufficient_deadline_headroom'
            } else {
                'terminalization_latched'
            }
            Add-ViolationUnless ((Get-PropertyValue `
                        $orderedBridgeRejections[$rejectionIndex].value 'reason') -ceq
                $expectedReason) `
                "deadline rejection latch order mismatch at index $rejectionIndex" $violations
            $rejection = $orderedBridgeRejections[$rejectionIndex].value
            $rejectionUtc = $null
            try {
                $rejectionUtc = [DateTimeOffset]::Parse(
                    [string](Get-PropertyValue $rejection 'utc'),
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::RoundtripKind)
            } catch {
                $violations.Add(
                    "deadline rejection UTC proof is missing or invalid at index $rejectionIndex")
            }
            if ($null -ne $t0DeadlineUtc -and $null -ne $rejectionUtc) {
                $elapsedSeconds = ($rejectionUtc - $t0DeadlineUtc).TotalSeconds
                $expectedRemainingSeconds = [int][Math]::Max(
                    0, [Math]::Floor([double]$ExpectedEvaluatorTimeoutSeconds - $elapsedSeconds))
                $recordedRemainingSeconds = Get-PropertyValue `
                    $rejection 'remaining_seconds'
                $remainingDelta = if (Test-IsJsonInteger $recordedRemainingSeconds) {
                    [long]$recordedRemainingSeconds - $expectedRemainingSeconds
                } else { [long]::MinValue }
                Add-ViolationUnless ($elapsedSeconds -ge 0.0D -and
                    $elapsedSeconds -le ($ExpectedEvaluatorTimeoutSeconds + 1.0D)) `
                    "deadline rejection UTC is outside the T0 deadline window at index $rejectionIndex" `
                    $violations
                Add-ViolationUnless ($remainingDelta -ge 0 -and $remainingDelta -le 1) `
                    "deadline rejection remaining UTC proof mismatch at index $rejectionIndex" `
                    $violations
            }
        }
        $firstRejectionSequence = Get-PropertyValue `
            $orderedBridgeRejections[0].value 'sequence'
        $firstRejectionCallId = [string](Get-PropertyValue `
                $orderedBridgeRejections[0].value 'call_id')
        $firstRejectionResponses = @($bridgeResponses | Where-Object {
                (Get-PropertyValue $_.value 'call_id') -ceq $firstRejectionCallId -and
                (Get-PropertyValue $_.value 'sequence') -gt $firstRejectionSequence
            })
        if ($firstRejectionResponses.Count -eq 1) {
            $firstRejectionResponseSequence = Get-PropertyValue `
                $firstRejectionResponses[0].value 'sequence'
        }
        $startsAfterRejection = @($bridgeStarts | Where-Object {
                (Get-PropertyValue $_.value 'sequence') -gt $firstRejectionSequence
            })
        $deadlineCleanupForwardCount = @($startsAfterRejection | Where-Object {
                (Get-PropertyValue $_.value 'tool') -ceq 'agent_cancel_action'
            }).Count
        Add-ViolationUnless ($startsAfterRejection.Count -le 1) `
            'more than one deadline cleanup cancel forward is forbidden' $violations
        foreach ($startAfterRejection in $startsAfterRejection) {
            $cleanupTool = [string](Get-PropertyValue $startAfterRejection.value 'tool')
            if ($cleanupTool -cne 'agent_cancel_action') {
                $violations.Add("non-cancel MCP forward after deadline rejection is forbidden: $([string](
                            Get-PropertyValue $startAfterRejection.value 'call_id'))")
            }
        }
    }
    $validDynamicCalls = 0
    $recoveryCalls = [Collections.Generic.List[object]]::new()
    $tunnelCalls = [Collections.Generic.List[object]]::new()
    $successfulDynamicCalls = 0
    $domainToolErrorCalls = 0
    $validDeadlineRejections = 0
    foreach ($request in $dynamicRequests) {
        $violationCountBeforeCall = $violations.Count
        $callId = $request.call_id
        Add-ViolationUnless ($request.thread_id -ceq $threadId -and $request.turn_id -ceq $turnId) `
            "dynamic call '$callId' request thread/turn mismatch" $violations
        $starts = @($bridgeStarts | Where-Object {
                (Get-PropertyValue $_.value 'call_id') -ceq $callId
            })
        $completions = @($bridgeCompletions | Where-Object {
                (Get-PropertyValue $_.value 'call_id') -ceq $callId
            })
        $responses = @($bridgeResponses | Where-Object {
                (Get-PropertyValue $_.value 'call_id') -ceq $callId
            })
        $rejections = @($bridgeRejections | Where-Object {
                (Get-PropertyValue $_.value 'call_id') -ceq $callId
            })
        $isForwardLifecycle = $starts.Count -eq 1 -and $completions.Count -eq 1 -and
            $rejections.Count -eq 0 -and $responses.Count -eq 1
        $isDeadlineRejectionLifecycle = $starts.Count -eq 0 -and
            $completions.Count -eq 0 -and $rejections.Count -eq 1 -and
            $responses.Count -eq 1
        if (-not $isForwardLifecycle -and -not $isDeadlineRejectionLifecycle) {
            $violations.Add(
                "dynamic call '$callId' bridge mapping is neither forward 1:1:1 nor deadline-reject 1:1")
            continue
        }
        $response = $responses[0].value
        $requestIdKey = Get-AppRequestIdKey $request.app_request_id
        $responseSuccess = Get-PropertyValue $response 'success'
        Add-ViolationUnless (Test-ExactPropertySet $response @(
                'sequence', 'utc', 'event', 'app_request_id', 'call_id', 'tool',
                'success', 'output_sha256')) `
            "dynamic call '$callId' response property set mismatch" $violations
        Add-ViolationUnless ((Get-AppRequestIdKey (
                    Get-PropertyValue $response 'app_request_id')) -ceq $requestIdKey) `
            "dynamic call '$callId' response request id mismatch" $violations
        Add-ViolationUnless ((Get-PropertyValue $response 'tool') -ceq $request.tool) `
            "dynamic call '$callId' response tool mismatch" $violations

        $terminalSuccess = $null
        $expectedOutputHash = $null
        $isDomainToolError = $false
        if ($isForwardLifecycle) {
            $start = $starts[0].value
            $completion = $completions[0].value
            $isDeadlineCleanupForward = $null -ne $firstRejectionSequence -and
                (Get-PropertyValue $start 'sequence') -gt $firstRejectionSequence
            $expectedStartProperties = @(
                'sequence', 'utc', 'event', 'app_request_id', 'call_id',
                'thread_id', 'turn_id', 'tool', 'arguments_sha256',
                'mcp_request_id', 'http_timeout_seconds')
            if ($isDeadlineCleanupForward) {
                $expectedStartProperties += @(
                    'forward_mode', 'remaining_seconds',
                    'terminalization_reserve_seconds', 'required_headroom_seconds')
            }
            Add-ViolationUnless (Test-ExactPropertySet $start $expectedStartProperties) `
                "dynamic call '$callId' forward start property set mismatch" $violations
            Add-ViolationUnless ((Get-AppRequestIdKey (
                        Get-PropertyValue $start 'app_request_id')) -ceq $requestIdKey) `
                "dynamic call '$callId' app request id mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $start 'tool') -ceq $request.tool) `
                "dynamic call '$callId' tool mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $start 'thread_id') -ceq
                $request.thread_id -and (Get-PropertyValue $start 'turn_id') -ceq
                $request.turn_id) `
                "dynamic call '$callId' thread/turn mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $start 'arguments_sha256') -eq
                (Get-Sha256 (ConvertTo-CompactJson $request.arguments))) `
                "dynamic call '$callId' arguments hash mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $completion 'mcp_request_id') -eq
                (Get-PropertyValue $start 'mcp_request_id')) `
                "dynamic call '$callId' MCP request id mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $start 'sequence') -lt
                (Get-PropertyValue $completion 'sequence') -and
                (Get-PropertyValue $completion 'sequence') -lt
                (Get-PropertyValue $response 'sequence')) `
                "dynamic call '$callId' bridge lifecycle order mismatch" $violations
            $httpTimeout = Get-PropertyValue $start 'http_timeout_seconds'
            Add-ViolationUnless (Test-IsJsonInteger $httpTimeout) `
                "dynamic call '$callId' HTTP timeout is missing" $violations
            if (Test-IsJsonInteger $httpTimeout) {
                Add-ViolationUnless ($httpTimeout -ge 1 -and
                    $httpTimeout -le $MaximumMcpForwardSeconds) `
                    "dynamic call '$callId' HTTP timeout is outside 1..35" $violations
            }
            if ($isDeadlineCleanupForward) {
                Add-ViolationUnless ($request.tool -ceq 'agent_cancel_action' -and
                    (Test-DeadlineCleanupCancelArguments $request.arguments)) `
                    "dynamic call '$callId' deadline cleanup cancel contract mismatch" $violations
                Add-ViolationUnless ($httpTimeout -eq $DeadlineCleanupCancelTimeoutSeconds) `
                    "dynamic call '$callId' deadline cleanup cancel timeout mismatch" $violations
                Add-ViolationUnless ((Get-PropertyValue $start 'forward_mode') -ceq
                    'deadline_cleanup_cancel') `
                    "dynamic call '$callId' deadline cleanup forward mode mismatch" $violations
                $cleanupRemainingSeconds = Get-PropertyValue $start 'remaining_seconds'
                $cleanupReserveSeconds = Get-PropertyValue `
                    $start 'terminalization_reserve_seconds'
                $cleanupRequiredHeadroom = Get-PropertyValue `
                    $start 'required_headroom_seconds'
                Add-ViolationUnless ((Test-IsJsonInteger $cleanupRemainingSeconds) -and
                    $cleanupRemainingSeconds -ge 0 -and $cleanupRemainingSeconds -le $ExpectedEvaluatorTimeoutSeconds) `
                    "dynamic call '$callId' deadline cleanup remaining seconds mismatch" $violations
                Add-ViolationUnless ((Test-IsJsonInteger $cleanupReserveSeconds) -and
                    $cleanupReserveSeconds -eq $TurnCompletionReserveSeconds) `
                    "dynamic call '$callId' deadline cleanup reserve mismatch" $violations
                Add-ViolationUnless ((Test-IsJsonInteger $cleanupRequiredHeadroom) -and
                    $cleanupRequiredHeadroom -eq
                        ($DeadlineCleanupCancelTimeoutSeconds +
                            $TurnCompletionReserveSeconds)) `
                    "dynamic call '$callId' deadline cleanup headroom mismatch" $violations
                Add-ViolationUnless ((Test-IsJsonInteger $cleanupRemainingSeconds) -and
                    (Test-IsJsonInteger $cleanupRequiredHeadroom) -and
                    $cleanupRemainingSeconds -gt $cleanupRequiredHeadroom) `
                    "dynamic call '$callId' deadline cleanup has insufficient headroom" $violations
                Add-ViolationUnless ($null -ne $firstRejectionResponseSequence -and
                    (Get-PropertyValue $start 'sequence') -gt
                        $firstRejectionResponseSequence) `
                    "dynamic call '$callId' deadline cleanup started before rejection response" `
                    $violations
                $cleanupStartUtc = $null
                try {
                    $cleanupStartUtc = [DateTimeOffset]::Parse(
                        [string](Get-PropertyValue $start 'utc'),
                        [Globalization.CultureInfo]::InvariantCulture,
                        [Globalization.DateTimeStyles]::RoundtripKind)
                } catch {
                    $violations.Add(
                        "dynamic call '$callId' deadline cleanup UTC proof is invalid")
                }
                if ($null -ne $t0DeadlineUtc -and $null -ne $cleanupStartUtc) {
                    $cleanupElapsedSeconds = ($cleanupStartUtc - $t0DeadlineUtc).TotalSeconds
                    $expectedCleanupRemaining = [int][Math]::Max(
                        0, [Math]::Floor([double]$ExpectedEvaluatorTimeoutSeconds - $cleanupElapsedSeconds))
                    $cleanupRemainingDelta = if (
                        Test-IsJsonInteger $cleanupRemainingSeconds) {
                        [long]$cleanupRemainingSeconds - $expectedCleanupRemaining
                    } else { [long]::MinValue }
                    Add-ViolationUnless ($cleanupElapsedSeconds -ge 0.0D -and
                        $cleanupElapsedSeconds -le ($ExpectedEvaluatorTimeoutSeconds + 1.0D) -and
                        $cleanupRemainingDelta -ge 0 -and
                        $cleanupRemainingDelta -le 1) `
                        "dynamic call '$callId' deadline cleanup remaining UTC proof mismatch" `
                        $violations
                }
            } else {
                $expectedForwardTimeout = Get-ExpectedDynamicForwardTimeoutSeconds `
                    -Tool $request.tool -Arguments $request.arguments
                Add-ViolationUnless ($null -ne $expectedForwardTimeout) `
                    "dynamic call '$callId' forward arguments are invalid" $violations
                if ($null -ne $expectedForwardTimeout) {
                    Add-ViolationUnless ($httpTimeout -eq $expectedForwardTimeout) `
                        "dynamic call '$callId' forward tool timeout mismatch" $violations
                }
            }
            $terminalSuccess = Get-PropertyValue $completion 'success'
            Add-ViolationUnless ($terminalSuccess -is [bool] -and
                $responseSuccess -is [bool] -and $terminalSuccess -eq $responseSuccess) `
                "dynamic call '$callId' bridge success mismatch" $violations
            Add-ViolationUnless ((Get-AppRequestIdKey (
                        Get-PropertyValue $completion 'app_request_id')) -ceq $requestIdKey) `
                "dynamic call '$callId' completion request id mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $completion 'tool') -ceq $request.tool) `
                "dynamic call '$callId' completion tool mismatch" $violations
            $payloadMode = [string](Get-PropertyValue $completion 'payload_mode')
            $jsonRpcResponseValid = Get-PropertyValue $completion 'jsonrpc_response_valid'
            Add-ViolationUnless (Test-ExactPropertySet $completion @(
                    'sequence', 'utc', 'event', 'app_request_id', 'call_id', 'tool',
                    'mcp_request_id', 'success', 'payload_mode', 'output_sha256',
                    'jsonrpc_response_valid', 'mcp_is_error',
                    'domain_error_contract_valid', 'structured_content_present')) `
                "dynamic call '$callId' completion property set mismatch" $violations
            Add-ViolationUnless ($jsonRpcResponseValid -is [bool] -and
                $jsonRpcResponseValid) `
                "dynamic call '$callId' JSON-RPC response validation proof missing" $violations
            $mcpIsError = Get-PropertyValue $completion 'mcp_is_error'
            $domainErrorContractValid = Get-PropertyValue `
                $completion 'domain_error_contract_valid'
            $structuredContentPresent = Get-PropertyValue `
                $completion 'structured_content_present'
            Add-ViolationUnless ($mcpIsError -is [bool] -and
                $domainErrorContractValid -is [bool] -and
                $structuredContentPresent -is [bool]) `
                "dynamic call '$callId' MCP result proof types are invalid" $violations
            if ($terminalSuccess -is [bool]) {
                if ($terminalSuccess) {
                    Add-ViolationUnless ($payloadMode -in @(
                            'structuredContent', 'textContent', 'wholeResult')) `
                        "dynamic call '$callId' success payload mode is invalid: $payloadMode" $violations
                    Add-ViolationUnless (-not $mcpIsError -and
                        -not $domainErrorContractValid -and $structuredContentPresent) `
                        "dynamic call '$callId' success MCP result proof mismatch" $violations
                } else {
                    $isDomainToolError = $true
                    Add-ViolationUnless ($payloadMode -eq 'tool_error') `
                        "dynamic call '$callId' non-domain failure payload mode is invalid: $payloadMode" $violations
                    Add-ViolationUnless ($mcpIsError -and $domainErrorContractValid -and
                        -not $structuredContentPresent) `
                        "dynamic call '$callId' domain error MCP result proof mismatch" $violations
                }
            }
            $expectedOutputHash = Get-PropertyValue $completion 'output_sha256'
        } else {
            $rejection = $rejections[0].value
            Add-ViolationUnless ((Get-AppRequestIdKey (
                        Get-PropertyValue $rejection 'app_request_id')) -ceq $requestIdKey) `
                "dynamic call '$callId' deadline rejection request id mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $rejection 'tool') -ceq $request.tool) `
                "dynamic call '$callId' deadline rejection tool mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $rejection 'thread_id') -ceq
                $request.thread_id -and (Get-PropertyValue $rejection 'turn_id') -ceq
                $request.turn_id) `
                "dynamic call '$callId' deadline rejection thread/turn mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $rejection 'arguments_sha256') -ceq
                (Get-Sha256 (ConvertTo-CompactJson $request.arguments))) `
                "dynamic call '$callId' deadline rejection arguments hash mismatch" $violations
            Add-ViolationUnless ((Get-PropertyValue $rejection 'sequence') -lt
                (Get-PropertyValue $response 'sequence')) `
                "dynamic call '$callId' deadline rejection lifecycle order mismatch" $violations
            $expectedForwardTimeout = Get-ExpectedDynamicForwardTimeoutSeconds `
                -Tool $request.tool -Arguments $request.arguments
            Add-ViolationUnless ($null -ne $expectedForwardTimeout) `
                "dynamic call '$callId' deadline rejection arguments are invalid" $violations
            if ($null -ne $expectedForwardTimeout) {
                Add-ViolationUnless ((Get-PropertyValue $rejection 'forward_timeout_seconds') -eq
                    $expectedForwardTimeout) `
                    "dynamic call '$callId' deadline rejection tool timeout mismatch" $violations
                Add-ViolationUnless ((Get-PropertyValue $rejection 'required_headroom_seconds') -eq
                    ($expectedForwardTimeout + $TurnCompletionReserveSeconds)) `
                    "dynamic call '$callId' deadline rejection required headroom mismatch" $violations
            }
            if ((Get-PropertyValue $rejection 'reason') -ceq
                'insufficient_deadline_headroom') {
                Add-ViolationUnless ((Get-PropertyValue $rejection 'remaining_seconds') -le
                    (Get-PropertyValue $rejection 'required_headroom_seconds')) `
                    "dynamic call '$callId' deadline rejection had sufficient headroom" $violations
            }
            $terminalSuccess = Get-PropertyValue $rejection 'success'
            Add-ViolationUnless ($terminalSuccess -is [bool] -and
                -not $terminalSuccess -and $responseSuccess -is [bool] -and
                -not $responseSuccess) `
                "dynamic call '$callId' deadline rejection success mismatch" $violations
            $expectedOutputHash = Get-PropertyValue $rejection 'output_sha256'
        }

        Add-ViolationUnless ((Get-PropertyValue $response 'output_sha256') -ceq
            $expectedOutputHash) `
            "dynamic call '$callId' bridge output hash mismatch" $violations

        if (-not $dynamicCompleted.ContainsKey($callId)) {
            $violations.Add("dynamic call '$callId' has no completed dynamicToolCall item")
        } else {
            $item = $dynamicCompleted[$callId]
            Add-ViolationUnless ($item.tool -ceq $request.tool) `
                "dynamic call '$callId' completed item tool mismatch" $violations
            Add-ViolationUnless ((Get-Sha256 (ConvertTo-CompactJson $item.arguments)) -eq
                (Get-Sha256 (ConvertTo-CompactJson $request.arguments))) `
                "dynamic call '$callId' completed item arguments mismatch" $violations
            Add-ViolationUnless ($item.output_sha256 -eq
                $expectedOutputHash) `
                "dynamic call '$callId' completed item output mismatch" $violations
            Add-ViolationUnless ($item.success -is [bool] -and
                $item.success -eq $terminalSuccess) `
                "dynamic call '$callId' completed item success mismatch" $violations
            if ($isForwardLifecycle -and $terminalSuccess -is [bool] -and
                -not $terminalSuccess) {
                Add-ViolationUnless ($item.domain_error_contract_valid -and
                    $domainErrorContractValid) `
                    "dynamic call '$callId' domain error body/proof mismatch" $violations
            }
        }
        if ($violations.Count -eq $violationCountBeforeCall) {
            $validDynamicCalls++
            if ($AuditPromptProfile -ceq 'container-inspect-recovery') {
                $recoveryCalls.Add([ordered]@{
                    tool = $request.tool
                    arguments = $request.arguments
                    success = $item.success
                    output_text = $item.output_text
                })
            }
            if ($AuditPromptProfile -like 'tunnel-*') {
                $tunnelCalls.Add([ordered]@{
                    tool = $request.tool
                    arguments = $request.arguments
                    success = $item.success
                    output_text = $item.output_text
                })
            }
            if ($isDeadlineRejectionLifecycle) {
                $validDeadlineRejections++
            } elseif ($terminalSuccess) {
                $successfulDynamicCalls++
            } elseif ($isDomainToolError) {
                $domainToolErrorCalls++
            }
        }
    }
    if (($bridgeStarts.Count + $bridgeRejections.Count) -ne $dynamicRequests.Count -or
        $bridgeCompletions.Count -ne $bridgeStarts.Count -or
        $bridgeResponses.Count -ne $dynamicRequests.Count) {
        $violations.Add('orphan/duplicate dynamic bridge recordがあります')
    }
    foreach ($completedCallId in @($dynamicCompleted.Keys)) {
        $matchingRequests = @($dynamicRequests | Where-Object {
                $_.call_id -ceq [string]$completedCallId
            })
        if ($matchingRequests.Count -ne 1) {
            $violations.Add("completed dynamicToolCall '$completedCallId' request mapping is not 1:1")
        }
    }
    foreach ($bridgeGroup in @(
            $bridgeStarts, $bridgeCompletions, $bridgeRejections, $bridgeResponses)) {
        foreach ($bridgeRecord in @($bridgeGroup)) {
            $bridgeCallId = [string](Get-PropertyValue $bridgeRecord.value 'call_id')
            $matchingRequests = @($dynamicRequests | Where-Object {
                    $_.call_id -ceq $bridgeCallId
                })
            if ($matchingRequests.Count -ne 1 -or
                (Get-PropertyValue $bridgeRecord.value 'tool') -cnotin $AllowedTools) {
                $violations.Add("orphan/forbidden bridge dynamic call '$bridgeCallId'")
            }
        }
    }
    $mcpRequestIds = @($bridgeStarts | ForEach-Object {
            [string](Get-PropertyValue $_.value 'mcp_request_id')
        })
    Add-ViolationUnless (@($mcpRequestIds | Select-Object -Unique).Count -eq
        $mcpRequestIds.Count) 'duplicate MCP request id in dynamic bridge' $violations

    $manualReviewRequired = [Collections.Generic.List[string]]::new()
    $recoveryWitness = $null
    $tunnelRendererRecoveryWitness = $null
    if ($AuditPromptProfile -ceq 'container-inspect-recovery') {
        Add-ViolationUnless (Test-McmcpRecoveryPreflight `
            -Record (Get-PropertyValue $t0 'recovery_preflight') `
            -T0Utc ([string](Get-PropertyValue $t0 'utc'))) `
            'recovery pre-T0 attestation is missing or invalid' $violations
        if ($violations.Count -eq 0) {
            $recoveryWitness = Get-McmcpRecoveryWitness -Calls @($recoveryCalls)
            foreach ($violation in @($recoveryWitness.violations)) {
                $violations.Add([string]$violation)
            }
        }
        $manualReviewRequired.Add('製品commitとbuild記録、baseline復元、起動済みJARとFPS設定を別の起動前記録で照合すること。disk attestationだけではruntime一致を証明しない')
        $manualReviewRequired.Add('通常FPS1回とmaxFps=10の1〜3回を同一baseline・製品commit・JAR hashで比較すること。not_exercisedは欠測回復PASSに数えない')
    }
    if ($AuditPromptProfile -like 'tunnel-*') {
        $tunnelRendererRecoveryWitness = Get-McmcpTunnelRecoveryWitness `
            -Calls @($tunnelCalls) -ExpectedProfile $AuditPromptProfile
        foreach ($violation in @($tunnelRendererRecoveryWitness.violations)) {
            $violations.Add([string]$violation)
        }
    }
    $manualReviewRequired.Add('agent_start_action の target が先行する正規MCP観測に由来すること')
    $manualReviewRequired.Add('agent_get_action(wait_timeout_ms=25000) をterminalまで反復し、非terminal timeout snapshotをエラー扱いしていないこと')
    if ($AuditPromptProfile -ceq 'hard-building-copy') {
        $manualReviewRequired.Add(
            'source/destinationのairを含む完全state差分、craft/smelt evidence、inventory収支とaction auditが建築copy完了を裏付けること')
    } elseif ($AuditPromptProfile -ceq 'warehouse-smelt') {
        $manualReviewRequired.Add(
            'world終了後のoffline oracleでsource chest、furnace、player inventoryが空、output barrelがdefault-componentsのiron ingot 1個だけ、周辺block不変を裏付けること')
    } elseif ($AuditPromptProfile -like 'tunnel-*') {
        $manualReviewRequired.Add(
            '対応するone-shot tunnel fixtureの起動前baselineと終了後bounded oracleで、掘削範囲、範囲外不変、終点またはhazard停止prefixを照合すること')
        $manualReviewRequired.Add(
            'evaluation leaseのterminal receiptでinputs_released、input_owner_none、all_actions_terminal、process_identity_boundがすべてBoolean trueであること')
        $manualReviewRequired.Add(
            'tunnel renderer recoveryは固定NODE_EVIDENCEのmissing>0かつrevalidated>0とAction succeededが揃う場合だけwitnessedとし、低FPSまたは成功だけを欠測回復PASSに数えないこと')
    } elseif ($AuditPromptProfile -cne 'container-inspect-recovery') {
        $manualReviewRequired.Add(
            '最終 inventory/observation と action audit が小麦64個を裏付けること')
    }
    if ($dynamicRequests.Count -eq 0) {
        $manualReviewRequired.Add(
            'zero-call run: capability不足の具体的理由が最終agentMessageに記載されていること')
    }
    if ($bridgeRejections.Count -gt 0) {
        $manualReviewRequired.Add(
            'deadline-rejected run: Minecraft内の全Actionがterminalであることを別artifactで確認すること')
    }
    $report = [ordered]@{
        schema_version = 8
        passed = ($violations.Count -eq 0)
        recovery_witness = $recoveryWitness
        tunnel_renderer_recovery_witness = $tunnelRendererRecoveryWitness
        prompt_profile = $AuditPromptProfile
        evaluator_timeout_seconds = $ExpectedEvaluatorTimeoutSeconds
        trace_message_count = $traceRecords.Count
        bridge_record_count = $bridgeRecords.Count
        dynamic_request_count = $dynamicRequests.Count
        valid_dynamic_call_count = $validDynamicCalls
        successful_dynamic_call_count = $successfulDynamicCalls
        failed_dynamic_call_count = $domainToolErrorCalls
        domain_tool_error_count = $domainToolErrorCalls
        deadline_rejection_count = $bridgeRejections.Count
        valid_deadline_rejection_count = $validDeadlineRejections
        deadline_cleanup_cancel_forward_count = $deadlineCleanupForwardCount
        no_dynamic_call_capability_run = ($dynamicRequests.Count -eq 0)
        allowed_tools = $AllowedTools
        violations = @($violations)
        manual_review_required = @($manualReviewRequired)
    }
    $outputDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($ReportPath))
    if (-not (Test-Path -LiteralPath $outputDirectory)) {
        [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    }
    [IO.File]::WriteAllText(
        $ReportPath,
        ($report | ConvertTo-Json -Depth 30),
        $Utf8NoBom)
    return $report.passed
}

function New-SyntheticThreadConfig {
    return [ordered]@{
        cli_auth_credentials_store = 'ephemeral'
        model_reasoning_effort = 'high'
        tools = [ordered]@{
            update_plan = [ordered]@{ enabled = $false }
            experimental_request_user_input = [ordered]@{ enabled = $false }
            web_search = $false
        }
        orchestrator = [ordered]@{
            skills = [ordered]@{ enabled = $false }
            mcp = [ordered]@{ enabled = $false }
        }
        web_search = 'disabled'
        memories = [ordered]@{ use_memories = $false }
        agents = [ordered]@{ enabled = $false }
        history = [ordered]@{ persistence = 'none' }
        project_doc_max_bytes = 0
        features = [ordered]@{}
    }
}

function Invoke-AuditSelfTest {
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) `
        ('mcmcp-eval-audit-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $cwd = 'C:\Temp\mcmcp-eval-0123456789abcdef0123456789abcdef\empty-cwd'
    $catalog = [IO.File]::ReadAllText([IO.Path]::Combine(
            $PSScriptRoot, '..', '..', 'docs',
            'MCMCP_MCP_Tool_Catalog.json')) | ConvertFrom-Json -Depth 100
    $toolSpecs = @(@(Get-PropertyValue $catalog 'tools') | ForEach-Object {
            [ordered]@{
                type = 'function'
                name = Get-PropertyValue $_ 'name'
                description = Get-PropertyValue $_ 'description'
                inputSchema = Get-PropertyValue $_ 'inputSchema'
            }
        })
    $config = New-SyntheticThreadConfig
    foreach ($feature in $RequiredFalseFeatures) { $config.features[$feature] = $false }
    $arguments = [ordered]@{}
    $outputText = '{"ok":true}'

    $initialize = [ordered]@{
        method = 'initialize'; id = 'init'
        params = [ordered]@{
            clientInfo = [ordered]@{ name = 'mcmcp-fresh-eval'; version = '1' }
            capabilities = [ordered]@{
                experimentalApi = $true
                optOutNotificationMethods = $RequiredReasoningDeltaOptOuts
            }
        }
    }
    $initialized = [ordered]@{ method = 'initialized'; params = [ordered]@{} }
    $threadStart = [ordered]@{
        method = 'thread/start'; id = 'thread'
        params = [ordered]@{
            model = 'gpt-5.6-sol'; cwd = $cwd; approvalPolicy = 'never'
            sandbox = 'read-only'; personality = 'none'; ephemeral = $true
            environments = @(); runtimeWorkspaceRoots = @()
            dynamicTools = $toolSpecs; config = $config
        }
    }
    $turnStart = [ordered]@{
        method = 'turn/start'; id = 'turn'
        params = [ordered]@{
            threadId = 'thread_1'; input = @([ordered]@{ type = 'text'; text = $ProductionPrompt })
            model = 'gpt-5.6-sol'; effort = 'high'; summary = 'detailed'
            cwd = $cwd; environments = @()
        }
    }
    $bridge = @(
        [ordered]@{
            sequence = 1; event = 'launcher_config'; codex_version = 'codex-cli 0.146.1'
            stdio = $true; strict_config = $true; disabled_features = $RequiredDisabledFeatures
            cli_configs = $RequiredCliConfigs; isolated_codex_home = $true
            isolated_empty_cwd = $true; external_auth_ephemeral = $true
            credential_file_created = $false; tool_surface_pinned = $true
            clean_cwd_ancestor_config_absent = $true
            isolated_home_config_absent = $true
            isolated_path_reparse_points_absent = $true
            child_mcmcp_env_count = 0; child_sensitive_env_count = 0
            child_forbidden_env_count = 0; child_secret_value_count = 0
        },
        [ordered]@{ sequence = 2; event = 'client_send'; kind = 'initialize'; message = $initialize },
        [ordered]@{
            sequence = 3; event = 'app_response_received'; request_id = 'init'
            response_ok = $true; contract_valid = $true; raw_artifact_recorded = $true
        },
        [ordered]@{ sequence = 4; event = 'client_send'; kind = 'initialized'; message = $initialized },
        [ordered]@{
            sequence = 5; event = 'app_response_received'; request_id = 'login'
            response_ok = $true; contract_valid = $true; raw_artifact_recorded = $true
        },
        [ordered]@{
            sequence = 6; event = 'external_auth_login_ok'; request_id = 'login'
            auth_type = 'chatgptAuthTokens'; credential_file_created = $false
            jwt_lifetime_guard_ok = $true
        },
        [ordered]@{
            sequence = 7; event = 'effective_config_checked'; request_id = 'config'
            include_layers = $true; cwd_is_clean = $true; mcp_servers_object = $true
            mcp_server_count = 0; raw_artifact_recorded = $false
        },
        [ordered]@{
            sequence = 8; event = 'preflight'; protocol_version = '2026-07-28'
            discover_ok = $true; listed_tools = $AllowedTools
            dynamic_tools_sha256 = Get-Sha256 (ConvertTo-CompactJson $toolSpecs)
            get_state_ok = $true; gameplay_calls_made = $false
            discover_contract_ok = $true; list_contract_ok = $true
            discover_semantic_exact = $true; list_semantic_exact = $true
            jsonrpc_envelopes_ok = $true; http_content_type_ok = $true
            server_info_ok = $true
            direct_fallback_config_absent = $true
            direct_fallback_path_reparse_absent = $true
            effective_config_read_ok = $true
            effective_mcp_servers_object = $true
            effective_mcp_server_count = 0
            catalog_file_sha256 = $ExpectedCatalogFileSha256
            expected_tool_surface_sha256 = $ExpectedToolSurfaceSha256
            live_tool_surface_sha256 = $ExpectedToolSurfaceSha256
            tool_surface_match = $true; parent_mcp_no_proxy = $true
            parent_mcp_redirects_disabled = $true
            ready_mode_ok = $true; game_unpaused = $true; world_present = $true
            observation_present = $true; inventory_empty = $true
            inventory_profile_matches = $true
            rays_per_tick_512 = $true; visible_entities_zero = $true
            action_idle_or_terminal = $true
        },
        [ordered]@{ sequence = 9; event = 'client_send'; kind = 'thread_start'; message = $threadStart },
        [ordered]@{
            sequence = 10; event = 'app_response_received'; request_id = 'thread'
            response_ok = $true; contract_valid = $true; raw_artifact_recorded = $true
        },
        [ordered]@{
            sequence = 11; event = 't0'; prompt_profile = 'short-regression'
            prompt_sha256 = Get-Sha256 $ProductionPrompt
            timeout_seconds = $ExpectedEvaluatorTimeoutSeconds; preliminary_readiness_passed = $true
            evaluation_lease_header_bound = $true; readiness_rechecked = $true
            ready_mode_ok = $true; game_unpaused = $true; world_present = $true
            observation_present = $true; inventory_empty = $true
            inventory_profile_matches = $true
            rays_per_tick_512 = $true; visible_entities_zero = $true
            action_idle_or_terminal = $true
        },
        [ordered]@{ sequence = 12; event = 'client_send'; kind = 'turn_start'; message = $turnStart },
        [ordered]@{
            sequence = 13; event = 'app_response_received'; request_id = 'turn'
            response_ok = $true; contract_valid = $true; raw_artifact_recorded = $true
        },
        [ordered]@{
            sequence = 14; event = 'mcp_forward_started'; app_request_id = 0
            call_id = 'call_1'; thread_id = 'thread_1'; turn_id = 'turn_1'
            tool = 'agent_get_state'; arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $arguments)
            mcp_request_id = 4; http_timeout_seconds = 35
        },
        [ordered]@{
            sequence = 15; event = 'mcp_forward_completed'; app_request_id = 0
            call_id = 'call_1'; tool = 'agent_get_state'; mcp_request_id = 4
            success = $true; payload_mode = 'structuredContent'; output_sha256 = Get-Sha256 $outputText
            jsonrpc_response_valid = $true
            mcp_is_error = $false; domain_error_contract_valid = $false
            structured_content_present = $true
        },
        [ordered]@{
            sequence = 16; event = 'dynamic_response_sent'; app_request_id = 0
            call_id = 'call_1'; tool = 'agent_get_state'; success = $true
            output_sha256 = Get-Sha256 $outputText
        }
    )
    $syntheticUtcBase = [DateTimeOffset]::Parse('2026-08-28T00:00:00.0000000+00:00')
    $syntheticT0Utc = $syntheticUtcBase.AddMilliseconds(10)
    for ($index = 0; $index -lt $bridge.Count; $index++) {
        $bridge[$index].Add(
            'utc', $syntheticUtcBase.AddMilliseconds($index).ToString('o'))
    }
    $trace = @(
        [ordered]@{ id = 'init'; result = [ordered]@{ userAgent = 'test' } },
        [ordered]@{ id = 'login'; result = [ordered]@{ type = 'chatgptAuthTokens' } },
        [ordered]@{ method = 'thread/started'; params = [ordered]@{ thread = [ordered]@{ id = 'thread_1' } } },
        [ordered]@{
            id = 'thread'
            result = [ordered]@{
                thread = [ordered]@{
                    id = 'thread_1'; ephemeral = $true; cwd = $cwd
                    modelProvider = 'openai'
                }
                model = 'gpt-5.6-sol'; cwd = $cwd; approvalPolicy = 'never'
                approvalsReviewer = 'user'; modelProvider = 'openai'
                sandbox = [ordered]@{ type = 'readOnly'; networkAccess = $false }
                instructionSources = @(); reasoningEffort = 'high'
                runtimeWorkspaceRoots = @()
            }
        },
        [ordered]@{ id = 'turn'; result = [ordered]@{ turn = [ordered]@{ id = 'turn_1'; status = 'inProgress'; error = $null } } },
        [ordered]@{ method = 'turn/started'; params = [ordered]@{ threadId = 'thread_1'; turn = [ordered]@{ id = 'turn_1' } } },
        [ordered]@{
            method = 'item/started'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 1
                item = [ordered]@{ id = 'user_1'; type = 'userMessage'; content = @([ordered]@{ type = 'text'; text = $ProductionPrompt }) }
            }
        },
        [ordered]@{
            method = 'item/completed'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 2
                item = [ordered]@{ id = 'user_1'; type = 'userMessage'; content = @([ordered]@{ type = 'text'; text = $ProductionPrompt }) }
            }
        },
        [ordered]@{
            method = 'item/started'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 3
                item = [ordered]@{ id = 'call_1'; type = 'dynamicToolCall'; tool = 'agent_get_state'; arguments = $arguments; status = 'inProgress' }
            }
        },
        [ordered]@{ method = 'item/tool/call'; id = 0; params = [ordered]@{ callId = 'call_1'; threadId = 'thread_1'; turnId = 'turn_1'; tool = 'agent_get_state'; arguments = $arguments } },
        [ordered]@{
            method = 'item/completed'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 4
                item = [ordered]@{ id = 'call_1'; type = 'dynamicToolCall'; tool = 'agent_get_state'; arguments = $arguments; status = 'completed'; success = $true; contentItems = @([ordered]@{ type = 'inputText'; text = $outputText }) }
            }
        },
        [ordered]@{
            method = 'item/started'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 5
                item = [ordered]@{ id = 'compaction_1'; type = 'contextCompaction' }
            }
        },
        [ordered]@{
            method = 'item/completed'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 6
                item = [ordered]@{ id = 'compaction_1'; type = 'contextCompaction' }
            }
        },
        [ordered]@{
            method = 'item/started'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 7
                item = [ordered]@{ id = 'agent_1'; type = 'agentMessage'; text = '' }
            }
        },
        [ordered]@{
            method = 'item/completed'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 8
                item = [ordered]@{ id = 'agent_1'; type = 'agentMessage'; text = 'done' }
            }
        },
        [ordered]@{ method = 'turn/completed'; params = [ordered]@{ threadId = 'thread_1'; turn = [ordered]@{ id = 'turn_1'; status = 'completed'; error = $null } } }
    )
    $syntheticEmittedAtMs = $syntheticUtcBase.ToUnixTimeMilliseconds()
    foreach ($traceMessage in $trace) {
        $traceMethod = [string](Get-PropertyValue $traceMessage 'method')
        if ($traceMethod -cin $AllowedNotifications) {
            $traceMessage.Add('emittedAtMs', $syntheticEmittedAtMs)
            $syntheticEmittedAtMs++
        }
    }

    $validReasoningTraceList = [Collections.Generic.List[object]]::new()
    foreach ($sourceMessage in $trace) {
        $copy = ConvertTo-CompactJson $sourceMessage | ConvertFrom-Json -Depth 100
        if ((Get-PropertyValue $copy 'method') -ceq 'item/started' -and
            (Get-NestedValue $copy 'params.item.id') -ceq 'agent_1') {
            $validReasoningTraceList.Add([pscustomobject][ordered]@{
                    method = 'item/started'
                    params = [ordered]@{
                        threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 5
                        item = [ordered]@{
                            id = 'reasoning_1'; type = 'reasoning'
                            summary = @(); content = @()
                        }
                    }
                })
            $validReasoningTraceList.Add([pscustomobject][ordered]@{
                    method = 'item/completed'
                    params = [ordered]@{
                        threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 6
                        item = [ordered]@{
                            id = 'reasoning_1'; type = 'reasoning'
                            summary = @('公開された推論要約'); content = @()
                        }
                    }
                })
        }
        $validReasoningTraceList.Add($copy)
    }
    $validReasoningTrace = @($validReasoningTraceList)
    $reasoningEmittedAtMs = $syntheticUtcBase.ToUnixTimeMilliseconds()
    foreach ($reasoningTraceMessage in $validReasoningTrace) {
        $reasoningTraceMethod = [string](Get-PropertyValue $reasoningTraceMessage 'method')
        if ($reasoningTraceMethod -cin $AllowedNotifications) {
            $reasoningTraceMessage | Add-Member -NotePropertyName emittedAtMs `
                -NotePropertyValue $reasoningEmittedAtMs -Force
            $reasoningEmittedAtMs++
        }
    }
    $rawReasoningContentTrace = @($validReasoningTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -ceq 'item/completed' -and
                (Get-NestedValue $copy 'params.item.id') -ceq 'reasoning_1') {
                $copy.params.item.content = @('private readable reasoning')
                $copy.params.item | Add-Member -NotePropertyName privateReasoning `
                    -NotePropertyValue 'must not be persisted' -Force
            }
            $copy
        })

    $domainErrorText = '{"code":"TARGET_UNAVAILABLE","message":"recoverable domain failure","recoverable":true}'
    $domainTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1' -and
                (Get-PropertyValue $copy 'method') -eq 'item/completed') {
                $copy.params.item.status = 'failed'
                $copy.params.item.success = $false
                $copy.params.item.contentItems[0].text = $domainErrorText
            }
            $copy
        })
    $domainBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $event = [string](Get-PropertyValue $copy 'event')
            if ($event -eq 'mcp_forward_completed') {
                $copy.success = $false
                $copy.payload_mode = 'tool_error'
                $copy.output_sha256 = Get-Sha256 $domainErrorText
                $copy.mcp_is_error = $true
                $copy.domain_error_contract_valid = $true
                $copy.structured_content_present = $false
            } elseif ($event -eq 'dynamic_response_sent') {
                $copy.success = $false
                $copy.output_sha256 = Get-Sha256 $domainErrorText
            }
            $copy
        })
    $protocolFailureBridge = @($domainBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'mcp_forward_completed') {
                $copy.payload_mode = 'rpc_error'
                $copy.jsonrpc_response_valid = $false
            }
            $copy
        })

    $deadlineTrace = @($domainTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1' -and
                (Get-PropertyValue $copy 'method') -eq 'item/completed') {
                $copy.params.item.contentItems[0].text = $DeadlineRejectedOutputText
            }
            $copy
        })
    $deadlineBridge = @($bridge[0..12] | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $deadlineBridge += [pscustomobject][ordered]@{
        sequence = 14
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.5D).ToString('o')
        event = 'dynamic_deadline_rejected'
        app_request_id = 0
        call_id = 'call_1'
        thread_id = 'thread_1'
        turn_id = 'turn_1'
        tool = 'agent_get_state'
        arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $arguments)
        reason = 'insufficient_deadline_headroom'
        remaining_seconds = 40
        forward_timeout_seconds = 35
        terminalization_reserve_seconds = 15
        required_headroom_seconds = 50
        success = $false
        output_sha256 = Get-Sha256 $DeadlineRejectedOutputText
    }
    $deadlineBridge += [pscustomobject][ordered]@{
        sequence = 15
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.49D).ToString('o')
        event = 'dynamic_response_sent'
        app_request_id = 0
        call_id = 'call_1'
        tool = 'agent_get_state'
        success = $false
        output_sha256 = Get-Sha256 $DeadlineRejectedOutputText
    }

    $getActionArguments = [ordered]@{
        action_id = '00000000-0000-0000-0000-000000000001'
        wait_timeout_ms = 25000
    }
    $deadlineGetActionTrace = @($deadlineTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1') {
                $copy.params.item.tool = 'agent_get_action'
                $copy.params.item.arguments = $getActionArguments
            }
            if ((Get-PropertyValue $copy 'method') -ceq 'item/tool/call') {
                $copy.params.tool = 'agent_get_action'
                $copy.params.arguments = $getActionArguments
            }
            $copy
        })
    $deadlineGetActionBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.tool = 'agent_get_action'
                $copy.arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $getActionArguments)
                $copy.utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 42.5D).ToString('o')
                $copy.remaining_seconds = 42
                $copy.forward_timeout_seconds = 27
                $copy.required_headroom_seconds = 42
            } elseif ((Get-PropertyValue $copy 'event') -ceq 'dynamic_response_sent') {
                $copy.tool = 'agent_get_action'
                $copy.utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 42.49D).ToString('o')
            }
            $copy
        })
    $deadlineGetActionSufficientBridge = @($deadlineGetActionBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.remaining_seconds = 43
            }
            $copy
        })
    $invalidWaitArguments = [ordered]@{
        action_id = '00000000-0000-0000-0000-000000000001'
        wait_timeout_ms = '25000'
    }
    $deadlineInvalidWaitTrace = @($deadlineGetActionTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1') {
                $copy.params.item.arguments = $invalidWaitArguments
            }
            if ((Get-PropertyValue $copy 'method') -ceq 'item/tool/call') {
                $copy.params.arguments = $invalidWaitArguments
            }
            $copy
        })
    $deadlineInvalidWaitBridge = @($deadlineGetActionBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $invalidWaitArguments)
            }
            $copy
        })

    $deadlineLatchTraceBase = @($deadlineTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'agent_1') {
                if ((Get-PropertyValue $copy 'method') -eq 'item/started') {
                    $copy.params.startedAtMs = 7
                } elseif ((Get-PropertyValue $copy 'method') -eq 'item/completed') {
                    $copy.params.completedAtMs = 8
                }
            }
            $copy
        })
    $deadlineLatchTrace = @(
        $deadlineLatchTraceBase[0..10]
        [ordered]@{
            method = 'item/started'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; startedAtMs = 5
                item = [ordered]@{
                    id = 'call_2'; type = 'dynamicToolCall'; tool = 'agent_get_state'
                    arguments = $arguments; status = 'inProgress'
                }
            }
        }
        [ordered]@{
            method = 'item/tool/call'; id = 1
            params = [ordered]@{
                callId = 'call_2'; threadId = 'thread_1'; turnId = 'turn_1'
                tool = 'agent_get_state'; arguments = $arguments
            }
        }
        [ordered]@{
            method = 'item/completed'
            params = [ordered]@{
                threadId = 'thread_1'; turnId = 'turn_1'; completedAtMs = 6
                item = [ordered]@{
                    id = 'call_2'; type = 'dynamicToolCall'; tool = 'agent_get_state'
                    arguments = $arguments; status = 'failed'; success = $false
                    contentItems = @([ordered]@{
                            type = 'inputText'; text = $DeadlineRejectedOutputText
                        })
                }
            }
        }
        $deadlineLatchTraceBase[11..($deadlineLatchTraceBase.Count - 1)]
    )
    $latchEmittedAtMs = $syntheticUtcBase.ToUnixTimeMilliseconds()
    foreach ($traceMessage in $deadlineLatchTrace) {
        $traceMethod = [string](Get-PropertyValue $traceMessage 'method')
        if ($traceMethod -cin $AllowedNotifications) {
            if ($null -eq (Get-Property $traceMessage 'emittedAtMs')) {
                $traceMessage.Add('emittedAtMs', $latchEmittedAtMs)
            } else {
                $traceMessage.emittedAtMs = $latchEmittedAtMs
            }
            $latchEmittedAtMs++
        }
    }
    $deadlineLatchBridge = @($deadlineBridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $deadlineLatchBridge += [pscustomobject][ordered]@{
        sequence = 16
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 39.5D).ToString('o')
        event = 'dynamic_deadline_rejected'
        app_request_id = 1
        call_id = 'call_2'
        thread_id = 'thread_1'
        turn_id = 'turn_1'
        tool = 'agent_get_state'
        arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $arguments)
        reason = 'terminalization_latched'
        remaining_seconds = 39
        forward_timeout_seconds = 35
        terminalization_reserve_seconds = 15
        required_headroom_seconds = 50
        success = $false
        output_sha256 = Get-Sha256 $DeadlineRejectedOutputText
    }
    $deadlineLatchBridge += [pscustomobject][ordered]@{
        sequence = 17
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 39.49D).ToString('o')
        event = 'dynamic_response_sent'
        app_request_id = 1
        call_id = 'call_2'
        tool = 'agent_get_state'
        success = $false
        output_sha256 = Get-Sha256 $DeadlineRejectedOutputText
    }

    $cleanupActionId = '00000000-0000-4000-8000-000000000001'
    $cleanupArguments = [ordered]@{ action_id = $cleanupActionId }
    $cleanupOutputText = ConvertTo-CompactJson ([ordered]@{
            schema_version = 1
            action_id = $cleanupActionId
            cancel_requested = $true
            state_at_request = 'running'
        })
    $deadlineCleanupTrace = @($deadlineLatchTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_2') {
                $copy.params.item.tool = 'agent_cancel_action'
                $copy.params.item.arguments = $cleanupArguments
                if ((Get-PropertyValue $copy 'method') -ceq 'item/completed') {
                    $copy.params.item.status = 'completed'
                    $copy.params.item.success = $true
                    $copy.params.item.contentItems[0].text = $cleanupOutputText
                }
            }
            if ((Get-PropertyValue $copy 'method') -ceq 'item/tool/call' -and
                (Get-NestedValue $copy 'params.callId') -ceq 'call_2') {
                $copy.params.tool = 'agent_cancel_action'
                $copy.params.arguments = $cleanupArguments
            }
            $copy
        })
    $deadlineCleanupBridge = @($deadlineBridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $deadlineCleanupBridge += [pscustomobject][ordered]@{
        sequence = 16
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 38.5D).ToString('o')
        event = 'mcp_forward_started'
        app_request_id = 1
        call_id = 'call_2'
        thread_id = 'thread_1'
        turn_id = 'turn_1'
        tool = 'agent_cancel_action'
        arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $cleanupArguments)
        mcp_request_id = 4
        http_timeout_seconds = 5
        forward_mode = 'deadline_cleanup_cancel'
        remaining_seconds = 38
        terminalization_reserve_seconds = 15
        required_headroom_seconds = 20
    }
    $deadlineCleanupBridge += [pscustomobject][ordered]@{
        sequence = 17
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 38.4D).ToString('o')
        event = 'mcp_forward_completed'
        app_request_id = 1
        call_id = 'call_2'
        tool = 'agent_cancel_action'
        mcp_request_id = 4
        success = $true
        payload_mode = 'structuredContent'
        output_sha256 = Get-Sha256 $cleanupOutputText
        jsonrpc_response_valid = $true
        mcp_is_error = $false
        domain_error_contract_valid = $false
        structured_content_present = $true
    }
    $deadlineCleanupBridge += [pscustomobject][ordered]@{
        sequence = 18
        utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 38.3D).ToString('o')
        event = 'dynamic_response_sent'
        app_request_id = 1
        call_id = 'call_2'
        tool = 'agent_cancel_action'
        success = $true
        output_sha256 = Get-Sha256 $cleanupOutputText
    }
    $deadlineCleanupTimeoutBridge = @($deadlineCleanupBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'mcp_forward_started' -and
                (Get-PropertyValue $copy 'call_id') -ceq 'call_2') {
                $copy.http_timeout_seconds = 6
            }
            $copy
        })
    $deadlineCleanupInsufficientBridge = @($deadlineCleanupBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $event = [string](Get-PropertyValue $copy 'event')
            $callId = [string](Get-PropertyValue $copy 'call_id')
            if ($callId -ceq 'call_2') {
                if ($event -ceq 'mcp_forward_started') {
                    $copy.utc = $syntheticT0Utc.AddSeconds(999.5D).ToString('o')
                    $copy.remaining_seconds = 20
                } elseif ($event -ceq 'mcp_forward_completed') {
                    $copy.utc = $syntheticT0Utc.AddSeconds(999.6D).ToString('o')
                } elseif ($event -ceq 'dynamic_response_sent') {
                    $copy.utc = $syntheticT0Utc.AddSeconds(999.7D).ToString('o')
                }
            }
            $copy
        })
    $invalidCleanupArguments = [ordered]@{
        action_id = $cleanupActionId
        unexpected = $true
    }
    $deadlineCleanupInvalidTrace = @($deadlineCleanupTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_2') {
                $copy.params.item.arguments = $invalidCleanupArguments
            }
            if ((Get-PropertyValue $copy 'method') -ceq 'item/tool/call' -and
                (Get-NestedValue $copy 'params.callId') -ceq 'call_2') {
                $copy.params.arguments = $invalidCleanupArguments
            }
            $copy
        })
    $deadlineCleanupInvalidBridge = @($deadlineCleanupBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'mcp_forward_started' -and
                (Get-PropertyValue $copy 'call_id') -ceq 'call_2') {
                $copy.arguments_sha256 = Get-Sha256 (
                    ConvertTo-CompactJson $invalidCleanupArguments)
            }
            $copy
        })
    $deadlineCleanupBeforeResponseBridge = @(
        $deadlineCleanupBridge[0..13] | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        }
        $deadlineCleanupBridge[15..17] | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        }
        (ConvertTo-CompactJson $deadlineCleanupBridge[14] |
            ConvertFrom-Json -Depth 100)
    )
    for ($index = 0; $index -lt $deadlineCleanupBeforeResponseBridge.Count; $index++) {
        $deadlineCleanupBeforeResponseBridge[$index].sequence = $index + 1
    }
    $deadlineCleanupBeforeResponseBridge[14].utc =
        $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.4D).ToString('o')
    $deadlineCleanupBeforeResponseBridge[14].remaining_seconds = 40
    $deadlineCleanupBeforeResponseBridge[15].utc =
        $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.3D).ToString('o')
    $deadlineCleanupBeforeResponseBridge[16].utc =
        $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.2D).ToString('o')
    $deadlineCleanupBeforeResponseBridge[17].utc =
        $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 40.1D).ToString('o')
    $deadlineSecondCleanupBridge = @($deadlineCleanupBridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $secondCleanupStart = ConvertTo-CompactJson $deadlineCleanupBridge[15] |
        ConvertFrom-Json -Depth 100
    $secondCleanupStart.sequence = 19
    $secondCleanupStart.utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 37.5D).ToString('o')
    $secondCleanupStart.app_request_id = 2
    $secondCleanupStart.call_id = 'call_3'
    $secondCleanupStart.mcp_request_id = 5
    $secondCleanupStart.remaining_seconds = 37
    $deadlineSecondCleanupBridge += $secondCleanupStart

    $deadlineMissingT0UtcBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.PSObject.Properties.Remove('utc')
            }
            $copy
        })
    $deadlineMissingRejectUtcBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.PSObject.Properties.Remove('utc')
            }
            $copy
        })
    $deadlineTamperedRemainingBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.remaining_seconds = 38
            }
            $copy
        })
    $deadlineEarlyRejectionBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.utc = $syntheticT0Utc.AddSeconds(1.5D).ToString('o')
                $copy.remaining_seconds = $ExpectedEvaluatorTimeoutSeconds - 2
            } elseif ((Get-PropertyValue $copy 'event') -ceq 'dynamic_response_sent') {
                $copy.utc = $syntheticT0Utc.AddSeconds(1.51D).ToString('o')
            }
            $copy
        })
    $deadlineTamperedT0UtcBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.utc = $syntheticUtcBase.AddSeconds(-1.59D).ToString('o')
            }
            $copy
        })

    $deadlineMissingResponseBridge = @($deadlineBridge | Where-Object {
            (Get-PropertyValue $_ 'event') -cne 'dynamic_response_sent'
        } | ForEach-Object { ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 })
    $deadlineDuplicateRejectionBridge = @($deadlineBridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $duplicateRejection = ConvertTo-CompactJson $deadlineDuplicateRejectionBridge[13] |
        ConvertFrom-Json -Depth 100
    $duplicateRejection.reason = 'terminalization_latched'
    $duplicateRejection.utc = $syntheticT0Utc.AddSeconds($ExpectedEvaluatorTimeoutSeconds - 39.5D).ToString('o')
    $duplicateRejection.remaining_seconds = 39
    $deadlineDuplicateRejectionBridge += $duplicateRejection
    for ($index = 0; $index -lt $deadlineDuplicateRejectionBridge.Count; $index++) {
        $deadlineDuplicateRejectionBridge[$index].sequence = $index + 1
    }
    $deadlineMixedBridge = @(
        $deadlineBridge[0..13] | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        }
        $bridge[13..15] | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        }
    )
    for ($index = 0; $index -lt $deadlineMixedBridge.Count; $index++) {
        $deadlineMixedBridge[$index].sequence = $index + 1
        if ($index -ge 14) {
            $deadlineMixedBridge[$index].utc = $syntheticT0Utc.AddSeconds(
                979.51D + (($index - 13) / 100.0D)).ToString('o')
        }
    }
    $deadlineArgumentsHashBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.arguments_sha256 = ('0' * 64)
            }
            $copy
        })
    $deadlineOutputHashBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.output_sha256 = ('0' * 64)
            }
            $copy
        })
    $deadlineSuccessBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.success = $true
            }
            $copy
        })
    $deadlineHeadroomBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.remaining_seconds = 51
            }
            $copy
        })
    $deadlineReasonBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.reason = 'terminalization_latched'
            }
            $copy
        })
    $deadlineIdentityBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'dynamic_deadline_rejected') {
                $copy.tool = 'agent_get_observation'
            }
            $copy
        })

    $zeroTrace = @($trace | Where-Object {
            (Get-PropertyValue $_ 'method') -ne 'item/tool/call' -and
            (Get-NestedValue $_ 'params.item.id') -ne 'call_1'
        })
    $zeroBridge = @($bridge | Where-Object {
            (Get-PropertyValue $_ 'event') -notin @(
                'mcp_forward_started', 'mcp_forward_completed', 'dynamic_response_sent')
        } | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    for ($index = 0; $index -lt $zeroBridge.Count; $index++) {
        $zeroBridge[$index].sequence = $index + 1
    }
    $wrongRouteTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -eq 'item/tool/call') {
                $copy.params.turnId = 'turn_other'
            }
            $copy
        })
    $namespaceTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -eq 'item/tool/call') {
                $copy.params | Add-Member -NotePropertyName namespace `
                    -NotePropertyValue 'forbidden' -Force
            }
            $copy
        })
    $missingMessageBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -eq 'thread_start') {
                $copy.PSObject.Properties.Remove('message')
            }
            $copy
        })
    $missingEnvironmentBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -eq 'turn_start') {
                $copy.message.params.PSObject.Properties.Remove('environments')
            }
            $copy
        })
    $wrongReasoningSummaryBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -eq 'turn_start') {
                $copy.message.params.summary = 'concise'
            }
            $copy
        })
    $missingReasoningDeltaOptOutBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -eq 'initialize') {
                $copy.message.params.capabilities.optOutNotificationMethods = @(
                    $RequiredReasoningDeltaOptOuts | Where-Object {
                        $_ -cne 'item/reasoning/textDelta'
                    })
            }
            $copy
        })
    $rawReasoningDeltaTrace = @($trace | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $rawReasoningDeltaNotification = [pscustomobject][ordered]@{
        method = 'item/reasoning/textDelta'
        params = [ordered]@{
            threadId = 'thread_1'; turnId = 'turn_1'; itemId = 'reasoning_1'
            contentIndex = 0; delta = 'raw reasoning must never be emitted'
        }
        emittedAtMs = $syntheticUtcBase.ToUnixTimeMilliseconds() + 6
    }
    $rawReasoningDeltaTrace = @(
        $rawReasoningDeltaTrace[0..($rawReasoningDeltaTrace.Count - 2)] +
        $rawReasoningDeltaNotification +
        $rawReasoningDeltaTrace[($rawReasoningDeltaTrace.Count - 1)]
    )
    $missingPropertiesBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send') {
                switch ([string](Get-PropertyValue $copy 'kind')) {
                    'initialize' {
                        $copy.message.PSObject.Properties.Remove('params')
                    }
                    'thread_start' {
                        $copy.message.params.PSObject.Properties.Remove('dynamicTools')
                        $copy.message.params.PSObject.Properties.Remove('config')
                    }
                    'turn_start' {
                        $copy.message.params.PSObject.Properties.Remove('input')
                    }
                }
            }
            $copy
        })
    $unknownClientSendBridge = @($bridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $extraClientSend = [pscustomobject][ordered]@{
        sequence = 0
        utc = ''
        event = 'client_send'
        kind = 'resume'
        message = [ordered]@{ method = 'turn/start'; id = 'resume'; params = [ordered]@{} }
    }
    $unknownClientSendBridge = @($unknownClientSendBridge[0..12] +
        $extraClientSend + $unknownClientSendBridge[13..($unknownClientSendBridge.Count - 1)])
    for ($index = 0; $index -lt $unknownClientSendBridge.Count; $index++) {
        $unknownClientSendBridge[$index].sequence = $index + 1
        $unknownClientSendBridge[$index].utc =
            $syntheticUtcBase.AddMilliseconds($index).ToString('o')
    }

    $latePreflightBridge = @($bridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $latePreflightBridge = @($latePreflightBridge[0..6] +
        $latePreflightBridge[8..10] + $latePreflightBridge[7] +
        $latePreflightBridge[11..($latePreflightBridge.Count - 1)])
    for ($index = 0; $index -lt $latePreflightBridge.Count; $index++) {
        $latePreflightBridge[$index].sequence = $index + 1
        $latePreflightBridge[$index].utc =
            $syntheticUtcBase.AddMilliseconds($index).ToString('o')
    }

    $lateResponseTrace = @($trace | Where-Object {
            [string](Get-PropertyValue $_ 'id') -cne 'turn'
        } | ForEach-Object { ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 })
    $lateResponseTrace += @($trace | Where-Object {
            [string](Get-PropertyValue $_ 'id') -ceq 'turn'
        } | ForEach-Object { ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 })

    $malformedDomainText = '{"code":"TARGET_UNAVAILABLE","message":"missing recoverable"}'
    $malformedDomainTrace = @($domainTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1' -and
                (Get-PropertyValue $copy 'method') -eq 'item/completed') {
                $copy.params.item.contentItems[0].text = $malformedDomainText
            }
            $copy
        })
    $malformedDomainBridge = @($domainBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -in @(
                    'mcp_forward_completed', 'dynamic_response_sent')) {
                $copy.output_sha256 = Get-Sha256 $malformedDomainText
            }
            $copy
        })
    $duplicateDomainText = '{"code":"A","code":"B","message":"duplicate","recoverable":true}'
    $duplicateDomainTrace = @($domainTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1' -and
                (Get-PropertyValue $copy 'method') -eq 'item/completed') {
                $copy.params.item.contentItems[0].text = $duplicateDomainText
            }
            $copy
        })
    $duplicateDomainBridge = @($domainBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -in @(
                    'mcp_forward_completed', 'dynamic_response_sent')) {
                $copy.output_sha256 = Get-Sha256 $duplicateDomainText
            }
            $copy
        })
    $domainProofMismatchBridge = @($domainBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'mcp_forward_completed') {
                $copy.structured_content_present = $true
            }
            $copy
        })

    $extraInstructionBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send') {
                if ((Get-PropertyValue $copy 'kind') -eq 'thread_start') {
                    $copy.message.params | Add-Member -NotePropertyName developerInstructions `
                        -NotePropertyValue 'forbidden' -Force
                } elseif ((Get-PropertyValue $copy 'kind') -eq 'turn_start') {
                    $copy.message.params | Add-Member -NotePropertyName additionalContext `
                        -NotePropertyValue @() -Force
                }
            }
            $copy
        })
    $wrongItemRouteTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -eq 'item/started' -and
                (Get-NestedValue $copy 'params.item.id') -eq 'user_1') {
                $copy.params.threadId = 'thread_other'
            }
            $copy
        })
    $missingTimestampTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -eq 'item/started' -and
                (Get-NestedValue $copy 'params.item.id') -eq 'user_1') {
                $copy.params.PSObject.Properties.Remove('startedAtMs')
            }
            $copy
        })
    $missingEmittedAtMsTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -ceq 'thread/started') {
                $copy.PSObject.Properties.Remove('emittedAtMs')
            }
            $copy
        })
    $nonIntegerEmittedAtMsTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -ceq 'thread/started') {
                $copy.emittedAtMs = 1.5D
            }
            $copy
        })
    $outOfRangeEmittedAtMsTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -ceq 'thread/started') {
                $copy.emittedAtMs = -1L
            }
            $copy
        })
    $reorderedEmittedAtMsTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'method') -ceq 'turn/started') {
                $copy.emittedAtMs = $syntheticUtcBase.ToUnixTimeMilliseconds() - 1
            }
            $copy
        })
    $caseMismatchBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -eq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -eq 'initialized') {
                $copy.message.PSObject.Properties.Remove('method')
                $copy.message | Add-Member -NotePropertyName Method `
                    -NotePropertyValue 'initialized' -Force
            }
            $copy
        })
    $readinessFailureBridge = @($bridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $readinessFailure = [pscustomobject][ordered]@{
        sequence = 0
        utc = ''
        event = 'readiness_check_failed'
        phase = 'preflight'
        get_state_ok = $true
        ready_mode_ok = $true
        game_unpaused = $true
        world_present = $false
        observation_present = $true
        inventory_empty = $false
        inventory_profile_matches = $false
        rays_per_tick_512 = $true
        visible_entities_zero = $true
        action_idle_or_terminal = $true
        failed_flags = @('world_present', 'inventory_profile_matches')
        raw_state_recorded = $false
    }
    $readinessFailureBridge = @($readinessFailureBridge[0..6] +
        $readinessFailure + $readinessFailureBridge[7..($readinessFailureBridge.Count - 1)])
    for ($index = 0; $index -lt $readinessFailureBridge.Count; $index++) {
        $readinessFailureBridge[$index].sequence = $index + 1
        $readinessFailureBridge[$index].utc =
            $syntheticUtcBase.AddMilliseconds($index).ToString('o')
    }
    $unsafeReadinessFailureBridge = @($readinessFailureBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 'readiness_check_failed') {
                $copy.raw_state_recorded = $true
                $copy | Add-Member -NotePropertyName snapshot `
                    -NotePropertyValue ([ordered]@{ forbidden = $true }) -Force
            }
            $copy
        })
    $invalidFailureDiagnosticBridge = @($bridge | ForEach-Object {
            ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
        })
    $invalidFailureDiagnosticBridge += [pscustomobject][ordered]@{
        sequence = $invalidFailureDiagnosticBridge.Count + 1
        utc = $syntheticUtcBase.AddMilliseconds(
            $invalidFailureDiagnosticBridge.Count).ToString('o')
        event = 'mcp_forward_failed'
        app_request_id = 999
        call_id = 'orphan_failure'
        tool = 'agent_get_state'
        mcp_request_id = 999
        failure_kind = 'http_status'
        diagnostic_code = 'http_non_success'
        http_status = 429
    }

    # Exercise the entire strict trace -> correlated witness path, not just the module.
    $recoveryProfilePrompt = [string]$EvaluationProfiles['container-inspect-recovery']['prompt']
    $recoveryId = '00000000-0000-4000-8000-000000000001'
    $recoveryTarget = @{ dimension = 'minecraft:overworld'; x = 1; y = 64; z = 2 }
    $recoveryStartArguments = @{ program = @{ body = @(@{
        id = 'inspect'; op = 'inspect_known_container'; target = $recoveryTarget
    }) } }
    $recoveryStartText = ConvertTo-CompactJson @{
        schema_version = 1; action_id = $recoveryId; state = 'queued'; accepted_at = '2026-08-28T00:00:00Z'
    }
    $recoveryGetArguments = @{ action_id = $recoveryId; include_container_results = $true; wait_timeout_ms = 25000 }
    $recoveryGetText = ConvertTo-CompactJson @{
        action_id = $recoveryId; state = 'succeeded'; failure = $null
        progress = @{ phase = 'finished'; executed_nodes = 1; total_node_upper_bound = 1; interactions = 1 }
        trace = @(@{ tick = 20; event = 'RENDERER_RECOVERY'; detail = 'missing=capture;revalidated=capture' })
        container_results = @{
            results = @(@{
                result_seq = 1; node_id = 'inspect'; node_execution = 1; target = $recoveryTarget
                world_session_id = $recoveryId; observed_client_tick = 100; packet_revision = 5
                items = @(); total_item_types = 0; returned_item_types = 0; truncated = $false
            })
            total_results = 1; retained_results = 1; snapshot_result_count = 1; returned_results = 1
            action_terminal = $true; truncated = $false; has_more = $false; next_cursor = $null
        }
    }
    $recoveryAttestation = @{
        schema_version = 1; captured_utc = $syntheticUtcBase.ToString('o')
        product_commit = ('a' * 40); product_commit_source = 'operator_build_record'
        expected_build_jar_sha256 = ('b' * 64); build_jar_sha256 = ('b' * 64); installed_jar_sha256 = ('b' * 64)
        baseline_id = 'synthetic-v1'; baseline_source = 'operator_restoration_record'
        max_fps = 10; max_fps_source = 'options_txt_pre_t0'; jar_files_match = $true
        same_game_directory = $true; runtime_jar_and_fps_verified = $false
    }
    $recoveryTrace = @($trace | ForEach-Object {
        $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 -DateKind String
        $item = Get-NestedValue $copy 'params.item'
        if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
            $item.content[0].text = $recoveryProfilePrompt
        }
        if ((Get-NestedValue $copy 'params.callId') -ceq 'call_1') {
            $copy.params.tool = 'agent_start_action'; $copy.params.arguments = $recoveryStartArguments
        }
        if ($null -ne $item -and (Get-PropertyValue $item 'id') -ceq 'call_1') {
            $item.tool = 'agent_start_action'; $item.arguments = $recoveryStartArguments
            if ((Get-PropertyValue $copy 'method') -ceq 'item/completed') {
                $item.contentItems[0].text = $recoveryStartText
            }
        }
        $copy
        if ((Get-PropertyValue $copy 'method') -ceq 'item/completed' -and
            (Get-NestedValue $copy 'params.item.id') -ceq 'call_1') {
            foreach ($original in @($trace | Where-Object {
                (Get-NestedValue $_ 'params.item.id') -ceq 'call_1' -or
                (Get-NestedValue $_ 'params.callId') -ceq 'call_1'
            })) {
                $extra = ConvertTo-CompactJson $original | ConvertFrom-Json -Depth 100 -DateKind String
                if ((Get-PropertyValue $extra 'method') -ceq 'item/tool/call') {
                    $extra.id = 1; $extra.params.callId = 'call_2'
                    $extra.params.tool = 'agent_get_action'; $extra.params.arguments = $recoveryGetArguments
                } else {
                    $extra.params.item.id = 'call_2'; $extra.params.item.tool = 'agent_get_action'
                    $extra.params.item.arguments = $recoveryGetArguments
                    if ((Get-PropertyValue $extra 'method') -ceq 'item/completed') {
                        $extra.params.item.contentItems[0].text = $recoveryGetText
                    }
                }
                $extra
            }
        }
    })
    $emitted = $syntheticUtcBase.ToUnixTimeMilliseconds()
    foreach ($entry in $recoveryTrace) {
        if ((Get-PropertyValue $entry 'method') -cin $AllowedNotifications) {
            $entry.emittedAtMs = $emitted; $emitted++
        }
    }
    $recoveryBridge = @($bridge | ForEach-Object {
        $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 -DateKind String
        if ($copy.event -ceq 't0') {
            $copy.prompt_profile = 'container-inspect-recovery'
            $copy.prompt_sha256 = Get-Sha256 $recoveryProfilePrompt
            $copy.timeout_seconds = 300
            $copy | Add-Member -NotePropertyName recovery_preflight -NotePropertyValue $recoveryAttestation
        } elseif ($copy.event -ceq 'client_send' -and $copy.kind -ceq 'turn_start') {
            $copy.message.params.input[0].text = $recoveryProfilePrompt
        }
        if ((Get-PropertyValue $copy 'call_id') -ceq 'call_1') {
            $copy.tool = 'agent_start_action'
            if ($copy.event -ceq 'mcp_forward_started') {
                $copy.arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $recoveryStartArguments)
            } else { $copy.output_sha256 = Get-Sha256 $recoveryStartText }
        }
        $copy
    })
    foreach ($entry in @($recoveryBridge | Where-Object { (Get-PropertyValue $_ 'call_id') -ceq 'call_1' })) {
        $extra = ConvertTo-CompactJson $entry | ConvertFrom-Json -Depth 100 -DateKind String
        $extra.sequence += 3; $extra.utc = $syntheticUtcBase.AddMilliseconds($extra.sequence - 1).ToString('o')
        $extra.call_id = 'call_2'; $extra.app_request_id = 1; $extra.tool = 'agent_get_action'
        if ($extra.event -cne 'dynamic_response_sent') { $extra.mcp_request_id = 5 }
        if ($extra.event -ceq 'mcp_forward_started') {
            $extra.arguments_sha256 = Get-Sha256 (ConvertTo-CompactJson $recoveryGetArguments)
            $extra.http_timeout_seconds = 27
        } else { $extra.output_sha256 = Get-Sha256 $recoveryGetText }
        $recoveryBridge += $extra
    }
    $recoveryMissingAttestation = @($recoveryBridge | ForEach-Object {
        $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100 -DateKind String
        if ($copy.event -ceq 't0') { $copy.recovery_preflight = $null }
        $copy
    })

    $fullCyclePrompt = [string]$EvaluationProfiles['full-cycle']['prompt']
    $fullProfileTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $item = Get-NestedValue $copy 'params.item'
            if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
                $item.content[0].text = $fullCyclePrompt
            }
            $copy
        })
    $fullProfileBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.prompt_profile = 'full-cycle'
                $copy.prompt_sha256 = Get-Sha256 $fullCyclePrompt
            } elseif ((Get-PropertyValue $copy 'event') -ceq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -ceq 'turn_start') {
                $copy.message.params.input[0].text = $fullCyclePrompt
            }
            $copy
        })

    $warehouseSmeltPrompt = [string]$EvaluationProfiles['warehouse-smelt']['prompt']
    $warehouseSmeltTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $item = Get-NestedValue $copy 'params.item'
            if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
                $item.content[0].text = $warehouseSmeltPrompt
            }
            $copy
        })
    $warehouseSmeltBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.prompt_profile = 'warehouse-smelt'
                $copy.prompt_sha256 = Get-Sha256 $warehouseSmeltPrompt
            } elseif ((Get-PropertyValue $copy 'event') -ceq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -ceq 'turn_start') {
                $copy.message.params.input[0].text = $warehouseSmeltPrompt
            }
            $copy
        })

    $hardBuildingPrompt = [string]$EvaluationProfiles['hard-building-copy']['prompt']
    $hardBuildingTimeoutSeconds =
        [int]$EvaluationProfiles['hard-building-copy']['timeout_minutes'] * 60
    $hardProfileTrace = @($trace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $item = Get-NestedValue $copy 'params.item'
            if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
                $item.content[0].text = $hardBuildingPrompt
            }
            $copy
        })
    $hardProfileBridge = @($bridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.prompt_profile = 'hard-building-copy'
                $copy.prompt_sha256 = Get-Sha256 $hardBuildingPrompt
                $copy.timeout_seconds = $hardBuildingTimeoutSeconds
            } elseif ((Get-PropertyValue $copy 'event') -ceq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -ceq 'turn_start') {
                $copy.message.params.input[0].text = $hardBuildingPrompt
            }
            $copy
        })
    $hardWrongTimeoutBridge = @($hardProfileBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                $copy.timeout_seconds = 1800
            }
            $copy
        })
    $hardDeadlineTrace = @($deadlineTrace | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $item = Get-NestedValue $copy 'params.item'
            if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
                $item.content[0].text = $hardBuildingPrompt
            }
            $copy
        })
    $hardDeadlineBridge = @($deadlineBridge | ForEach-Object {
            $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
            $event = [string](Get-PropertyValue $copy 'event')
            if ($event -ceq 't0') {
                $copy.prompt_profile = 'hard-building-copy'
                $copy.prompt_sha256 = Get-Sha256 $hardBuildingPrompt
                $copy.timeout_seconds = $hardBuildingTimeoutSeconds
            } elseif ($event -ceq 'client_send' -and
                (Get-PropertyValue $copy 'kind') -ceq 'turn_start') {
                $copy.message.params.input[0].text = $hardBuildingPrompt
            } elseif ($event -ceq 'dynamic_deadline_rejected') {
                $copy.utc = $syntheticT0Utc.AddSeconds(
                    $hardBuildingTimeoutSeconds - 40.5D).ToString('o')
            } elseif ($event -ceq 'dynamic_response_sent') {
                $copy.utc = $syntheticT0Utc.AddSeconds(
                    $hardBuildingTimeoutSeconds - 40.49D).ToString('o')
            }
            $copy
        })

    $tunnelProfileCases = [Collections.Generic.List[object]]::new()
    foreach ($tunnelProfileName in @(
            'tunnel-straight16', 'tunnel-straight160', 'tunnel-branches', 'tunnel-hazard')) {
        $tunnelPrompt = [string]$EvaluationProfiles[$tunnelProfileName]['prompt']
        $tunnelTimeoutSeconds =
            [int]$EvaluationProfiles[$tunnelProfileName]['timeout_minutes'] * 60
        $tunnelTrace = @($trace | ForEach-Object {
                $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
                $item = Get-NestedValue $copy 'params.item'
                if ($null -ne $item -and (Get-PropertyValue $item 'type') -ceq 'userMessage') {
                    $item.content[0].text = $tunnelPrompt
                }
                $copy
            })
        $tunnelBridge = @($bridge | ForEach-Object {
                $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
                if ((Get-PropertyValue $copy 'event') -ceq 'preflight') {
                    $copy.inventory_empty = $false
                } elseif ((Get-PropertyValue $copy 'event') -ceq 't0') {
                    $copy.prompt_profile = $tunnelProfileName
                    $copy.prompt_sha256 = Get-Sha256 $tunnelPrompt
                    $copy.timeout_seconds = $tunnelTimeoutSeconds
                    $copy.inventory_empty = $false
                } elseif ((Get-PropertyValue $copy 'event') -ceq 'client_send' -and
                    (Get-PropertyValue $copy 'kind') -ceq 'turn_start') {
                    $copy.message.params.input[0].text = $tunnelPrompt
                }
                $copy
            })
        $tunnelProfileCases.Add([ordered]@{
                name = $tunnelProfileName.Replace('-', '_') + '_valid'
                trace = $tunnelTrace
                bridge = $tunnelBridge
                expected_profile = $tunnelProfileName
                expected_exit = 0
                expected_success = 1
                expected_failure = 0
                required = @()
                required_manual = @('one-shot tunnel fixture', 'input_owner_none',
                    'missing>0かつrevalidated>0')
            })
        if ($tunnelProfileName -ceq 'tunnel-straight160') {
            $wrongInventoryBridge = @($tunnelBridge | ForEach-Object {
                    $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
                    if ((Get-PropertyValue $copy 'event') -ceq 't0') {
                        $copy.inventory_empty = $true
                    }
                    $copy
                })
            $tunnelProfileCases.Add([ordered]@{
                    name = 'tunnel_straight160_wrong_inventory_proof'
                    trace = $tunnelTrace
                    bridge = $wrongInventoryBridge
                    expected_profile = $tunnelProfileName
                    expected_exit = 1
                    required = @('T0 inventory-empty proof does not match the fixed profile')
                })
        }
    }

    $cases = @(
        [ordered]@{
            name = 'recovery_profile_witnessed'; trace = $recoveryTrace; bridge = $recoveryBridge
            expected_profile = 'container-inspect-recovery'; expected_exit = 0
            required = @(); expected_recovery = 'witnessed'
        },
        [ordered]@{
            name = 'recovery_profile_no_attestation'; trace = $recoveryTrace; bridge = $recoveryMissingAttestation
            expected_profile = 'container-inspect-recovery'; expected_exit = 1
            required = @('recovery pre-T0 attestation is missing or invalid')
        },
        [ordered]@{
            name = 'valid'; trace = $trace; bridge = $bridge
            expected_exit = 0; expected_success = 1; expected_failure = 0
            required = @()
        },
        [ordered]@{
            name = 'valid_public_reasoning_summary'; trace = $validReasoningTrace
            bridge = $bridge; expected_exit = 0
            expected_success = 1; expected_failure = 0; required = @()
        },
        [ordered]@{
            name = 'raw_reasoning_content'; trace = $rawReasoningContentTrace
            bridge = $bridge; expected_exit = 1
            required = @('reasoning item exact safe schema mismatch')
        },
        [ordered]@{
            name = 'full_profile_valid'; trace = $fullProfileTrace
            bridge = $fullProfileBridge; expected_profile = 'full-cycle'
            expected_exit = 0; expected_success = 1; expected_failure = 0
            required = @()
        },
        [ordered]@{
            name = 'warehouse_smelt_profile_valid'; trace = $warehouseSmeltTrace
            bridge = $warehouseSmeltBridge; expected_profile = 'warehouse-smelt'
            expected_exit = 0; expected_success = 1; expected_failure = 0
            required = @(); required_manual = @('output barrel')
        },
        [ordered]@{
            name = 'hard_profile_valid'; trace = $hardProfileTrace
            bridge = $hardProfileBridge; expected_profile = 'hard-building-copy'
            expected_exit = 0; expected_success = 1; expected_failure = 0
            required = @(); required_manual = @('airを含む完全state差分')
        },
        [ordered]@{
            name = 'hard_profile_wrong_timeout'; trace = $hardProfileTrace
            bridge = $hardWrongTimeoutBridge; expected_profile = 'hard-building-copy'
            expected_exit = 1; required = @('T0 timeout must be 5400 seconds')
        },
        [ordered]@{
            name = 'hard_profile_deadline_rejection'; trace = $hardDeadlineTrace
            bridge = $hardDeadlineBridge; expected_profile = 'hard-building-copy'
            expected_exit = 0; expected_success = 0; expected_failure = 0
            expected_rejection = 1; required = @()
            required_manual = @('airを含む完全state差分', 'deadline-rejected run')
        },
        [ordered]@{
            name = 'domain_recovery'; trace = $domainTrace; bridge = $domainBridge
            expected_exit = 0; expected_success = 0; expected_failure = 1
            required = @()
        },
        [ordered]@{
            name = 'zero_call_capability'; trace = $zeroTrace; bridge = $zeroBridge
            expected_exit = 0; expected_success = 0; expected_failure = 0
            required = @(); required_manual = @('zero-call run')
        },
        [ordered]@{
            name = 'deadline_rejection'; trace = $deadlineTrace; bridge = $deadlineBridge
            expected_exit = 0; expected_success = 0; expected_failure = 0
            expected_rejection = 1; required = @()
            required_manual = @('deadline-rejected run')
        },
        [ordered]@{
            name = 'deadline_get_action_boundary'; trace = $deadlineGetActionTrace
            bridge = $deadlineGetActionBridge; expected_exit = 0
            expected_success = 0; expected_failure = 0; expected_rejection = 1
            required = @(); required_manual = @('deadline-rejected run')
        },
        [ordered]@{
            name = 'deadline_get_action_above_boundary'; trace = $deadlineGetActionTrace
            bridge = $deadlineGetActionSufficientBridge; expected_exit = 1
            required = @('deadline rejection had sufficient headroom')
        },
        [ordered]@{
            name = 'deadline_get_action_invalid_wait'; trace = $deadlineInvalidWaitTrace
            bridge = $deadlineInvalidWaitBridge; expected_exit = 1
            required = @('deadline rejection arguments are invalid')
        },
        [ordered]@{
            name = 'deadline_rejection_latched'; trace = $deadlineLatchTrace
            bridge = $deadlineLatchBridge; expected_exit = 0
            expected_success = 0; expected_failure = 0; expected_rejection = 2
            required = @(); required_manual = @('deadline-rejected run')
        },
        [ordered]@{
            name = 'deadline_cleanup_cancel'; trace = $deadlineCleanupTrace
            bridge = $deadlineCleanupBridge; expected_exit = 0
            expected_success = 1; expected_failure = 0; expected_rejection = 1
            required = @(); required_manual = @('deadline-rejected run')
        },
        [ordered]@{
            name = 'deadline_cleanup_timeout'; trace = $deadlineCleanupTrace
            bridge = $deadlineCleanupTimeoutBridge; expected_exit = 1
            required = @('deadline cleanup cancel timeout mismatch')
        },
        [ordered]@{
            name = 'deadline_cleanup_insufficient'; trace = $deadlineCleanupTrace
            bridge = $deadlineCleanupInsufficientBridge; expected_exit = 1
            required = @('deadline cleanup has insufficient headroom')
        },
        [ordered]@{
            name = 'deadline_cleanup_invalid_arguments'; trace = $deadlineCleanupInvalidTrace
            bridge = $deadlineCleanupInvalidBridge; expected_exit = 1
            required = @('deadline cleanup cancel contract mismatch')
        },
        [ordered]@{
            name = 'deadline_cleanup_before_response'; trace = $deadlineCleanupTrace
            bridge = $deadlineCleanupBeforeResponseBridge; expected_exit = 1
            required = @('deadline cleanup started before rejection response')
        },
        [ordered]@{
            name = 'deadline_second_cleanup'; trace = $deadlineCleanupTrace
            bridge = $deadlineSecondCleanupBridge; expected_exit = 1
            required = @('more than one deadline cleanup cancel forward is forbidden')
        },
        [ordered]@{
            name = 'deadline_missing_t0_utc'; trace = $deadlineTrace
            bridge = $deadlineMissingT0UtcBridge; expected_exit = 1
            required = @('T0 UTC deadline proof is missing or invalid')
        },
        [ordered]@{
            name = 'deadline_missing_reject_utc'; trace = $deadlineTrace
            bridge = $deadlineMissingRejectUtcBridge; expected_exit = 1
            required = @('deadline rejection UTC proof is missing or invalid')
        },
        [ordered]@{
            name = 'deadline_tampered_remaining'; trace = $deadlineTrace
            bridge = $deadlineTamperedRemainingBridge; expected_exit = 1
            required = @('deadline rejection remaining UTC proof mismatch')
        },
        [ordered]@{
            name = 'deadline_early_rejection'; trace = $deadlineTrace
            bridge = $deadlineEarlyRejectionBridge; expected_exit = 1
            required = @('deadline rejection had sufficient headroom')
        },
        [ordered]@{
            name = 'deadline_tampered_t0_utc'; trace = $deadlineTrace
            bridge = $deadlineTamperedT0UtcBridge; expected_exit = 1
            required = @('deadline rejection remaining UTC proof mismatch')
        },
        [ordered]@{
            name = 'deadline_missing_response'; trace = $deadlineTrace
            bridge = $deadlineMissingResponseBridge; expected_exit = 1
            required = @('neither forward 1:1:1 nor deadline-reject 1:1')
        },
        [ordered]@{
            name = 'deadline_duplicate_rejection'; trace = $deadlineTrace
            bridge = $deadlineDuplicateRejectionBridge; expected_exit = 1
            required = @('neither forward 1:1:1 nor deadline-reject 1:1')
        },
        [ordered]@{
            name = 'deadline_forward_mixed'; trace = $deadlineTrace
            bridge = $deadlineMixedBridge; expected_exit = 1
            required = @('non-cancel MCP forward after deadline rejection is forbidden',
                'neither forward 1:1:1 nor deadline-reject 1:1')
        },
        [ordered]@{
            name = 'deadline_arguments_hash'; trace = $deadlineTrace
            bridge = $deadlineArgumentsHashBridge; expected_exit = 1
            required = @('deadline rejection arguments hash mismatch')
        },
        [ordered]@{
            name = 'deadline_output_hash'; trace = $deadlineTrace
            bridge = $deadlineOutputHashBridge; expected_exit = 1
            required = @('deadline rejection output hash mismatch')
        },
        [ordered]@{
            name = 'deadline_success_true'; trace = $deadlineTrace
            bridge = $deadlineSuccessBridge; expected_exit = 1
            required = @('deadline rejection success must be false')
        },
        [ordered]@{
            name = 'deadline_sufficient_headroom'; trace = $deadlineTrace
            bridge = $deadlineHeadroomBridge; expected_exit = 1
            required = @('deadline rejection had sufficient headroom')
        },
        [ordered]@{
            name = 'deadline_latch_reason'; trace = $deadlineTrace
            bridge = $deadlineReasonBridge; expected_exit = 1
            required = @('deadline rejection latch order mismatch')
        },
        [ordered]@{
            name = 'deadline_identity'; trace = $deadlineTrace
            bridge = $deadlineIdentityBridge; expected_exit = 1
            required = @('deadline rejection tool mismatch')
        },
        [ordered]@{
            name = 'protocol_failure'; trace = $domainTrace; bridge = $protocolFailureBridge
            expected_exit = 1
            required = @('JSON-RPC response validation proof missing',
                'non-domain failure payload mode')
        },
        [ordered]@{
            name = 'wrong_route'; trace = $wrongRouteTrace; bridge = $bridge
            expected_exit = 1; required = @('active thread/turn mismatch')
        },
        [ordered]@{
            name = 'namespace'; trace = $namespaceTrace; bridge = $bridge
            expected_exit = 1; required = @('namespace must be omitted or null')
        },
        [ordered]@{
            name = 'missing_message'; trace = $trace; bridge = $missingMessageBridge
            expected_exit = 1; required = @('message は非null object')
        },
        [ordered]@{
            name = 'missing_environments'; trace = $trace; bridge = $missingEnvironmentBridge
            expected_exit = 1; required = @('turn/start environments')
        },
        [ordered]@{
            name = 'reasoning_summary_not_detailed'; trace = $trace
            bridge = $wrongReasoningSummaryBridge; expected_exit = 1
            required = @('turn/start summary=detailed')
        },
        [ordered]@{
            name = 'missing_reasoning_delta_opt_out'; trace = $trace
            bridge = $missingReasoningDeltaOptOutBridge; expected_exit = 1
            required = @('reasoning raw/summary delta notification opt-out')
        },
        [ordered]@{
            name = 'raw_reasoning_text_delta'; trace = $rawReasoningDeltaTrace
            bridge = $bridge; expected_exit = 1
            required = @("forbidden notification 'item/reasoning/textDelta'")
        },
        [ordered]@{
            name = 'missing_properties'; trace = $trace; bridge = $missingPropertiesBridge
            expected_exit = 1
            required = @('initialize params object', 'dynamicTools array',
                'config object', 'turn/start input array')
        },
        [ordered]@{
            name = 'unknown_client_send'; trace = $trace; bridge = $unknownClientSendBridge
            expected_exit = 1
            required = @('unknown client_send kind', '必須4 kindだけ')
        },
        [ordered]@{
            name = 'late_preflight'; trace = $trace; bridge = $latePreflightBridge
            expected_exit = 1; required = @('setup/config/preflight/T0 response 順序')
        },
        [ordered]@{
            name = 'response_after_terminal'; trace = $lateResponseTrace; bridge = $bridge
            expected_exit = 1; required = @('turn/completed 後のmessageは禁止')
        },
        [ordered]@{
            name = 'malformed_domain'; trace = $malformedDomainTrace
            bridge = $malformedDomainBridge; expected_exit = 1
            required = @('domain error body is invalid', 'domain error body/proof mismatch')
        },
        [ordered]@{
            name = 'duplicate_domain_member'; trace = $duplicateDomainTrace
            bridge = $duplicateDomainBridge; expected_exit = 1
            required = @('domain error body is invalid')
        },
        [ordered]@{
            name = 'domain_proof_mismatch'; trace = $domainTrace
            bridge = $domainProofMismatchBridge; expected_exit = 1
            required = @('domain error MCP result proof mismatch')
        },
        [ordered]@{
            name = 'extra_instruction_context'; trace = $trace; bridge = $extraInstructionBridge
            expected_exit = 1; required = @('追加instruction/context field')
        },
        [ordered]@{
            name = 'invalid_model_effort'; trace = $trace; bridge = $bridge
            expected_exit = 1; expected_effort = 'xhigh'
            required = @('許可されないmodel/effort pair')
        },
        [ordered]@{
            name = 'wrong_item_route'; trace = $wrongItemRouteTrace; bridge = $bridge
            expected_exit = 1; required = @('item/started active thread/turn mismatch')
        },
        [ordered]@{
            name = 'missing_item_timestamp'; trace = $missingTimestampTrace; bridge = $bridge
            expected_exit = 1; required = @('item/started startedAtMs must be non-negative numeric')
        },
        [ordered]@{
            name = 'missing_emitted_at'; trace = $missingEmittedAtMsTrace; bridge = $bridge
            expected_exit = 1; required = @(
                "notification property set mismatch 'thread/started'",
                'notification emittedAtMs must be an integer Unix-ms value in range')
        },
        [ordered]@{
            name = 'non_integer_emitted_at'; trace = $nonIntegerEmittedAtMsTrace; bridge = $bridge
            expected_exit = 1
            required = @('notification emittedAtMs must be an integer Unix-ms value in range')
        },
        [ordered]@{
            name = 'out_of_range_emitted_at'; trace = $outOfRangeEmittedAtMsTrace; bridge = $bridge
            expected_exit = 1
            required = @('notification emittedAtMs must be an integer Unix-ms value in range')
        },
        [ordered]@{
            name = 'reordered_emitted_at'; trace = $reorderedEmittedAtMsTrace; bridge = $bridge
            expected_exit = 1; required = @('notification emittedAtMs order mismatch')
        },
        [ordered]@{
            name = 'case_mismatch'; trace = $trace; bridge = $caseMismatchBridge
            expected_exit = 1; required = @('initialized message property set mismatch')
        },
        [ordered]@{
            name = 'readiness_failure'; trace = $trace; bridge = $readinessFailureBridge
            expected_exit = 1
            required = @('readiness check failed at preflight: world_present, inventory_profile_matches')
        },
        [ordered]@{
            name = 'unsafe_readiness_failure'; trace = $trace
            bridge = $unsafeReadinessFailureBridge; expected_exit = 1
            required = @('readiness failure property set mismatch',
                'readiness raw state must not be recorded')
        },
        [ordered]@{
            name = 'invalid_failure_diagnostic'; trace = $trace
            bridge = $invalidFailureDiagnosticBridge; expected_exit = 1
            required = @('MCP failure diagnostic contract mismatch')
        },
        [ordered]@{
            name = 'forbidden'
            trace = @($trace | ForEach-Object {
                    $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
                    if ((Get-NestedValue $copy 'params.item.id') -eq 'call_1' -and
                        (Get-PropertyValue $copy 'method') -eq 'item/started') {
                        $copy.params.item.type = 'commandExecution'
                    }
                    $copy
                })
            bridge = @($bridge | ForEach-Object {
                    $copy = ConvertTo-CompactJson $_ | ConvertFrom-Json -Depth 100
                    if ((Get-PropertyValue $copy 'event') -eq 'mcp_forward_completed') {
                        $copy.success = $false
                    }
                    $copy
                })
            expected_exit = 1
            required = @('許可されない item type', 'bridge success mismatch')
        },
        [ordered]@{
            name = 'malformed'; trace = $null; bridge = $bridge
            expected_exit = 1; required = @('JSONL として解析できません')
        }
    )
    $cases += @($tunnelProfileCases)

    try {
        $powerShellExecutable = (Get-Process -Id $PID).Path
        foreach ($case in $cases) {
            $tracePath = Join-Path $temporaryRoot ($case.name + '-trace.jsonl')
            $bridgePath = Join-Path $temporaryRoot ($case.name + '-bridge.jsonl')
            $reportPath = Join-Path $temporaryRoot ($case.name + '-report.json')
            if ($case.name -eq 'malformed') {
                [IO.File]::WriteAllText($tracePath, "not-json`n", $Utf8NoBom)
            } else {
                [IO.File]::WriteAllText(
                    $tracePath,
                    (($case.trace | ForEach-Object { ConvertTo-CompactJson $_ }) -join "`n") + "`n",
                    $Utf8NoBom)
            }
            [IO.File]::WriteAllText(
                $bridgePath,
                (($case.bridge | ForEach-Object { ConvertTo-CompactJson $_ }) -join "`n") + "`n",
                $Utf8NoBom)

            $caseEffort = if ($case.Contains('expected_effort')) {
                [string]$case.expected_effort
            } else { 'high' }
            $casePromptProfile = if ($case.Contains('expected_profile')) {
                [string]$case.expected_profile
            } else { 'short-regression' }
            & $powerShellExecutable -NoProfile -File $PSCommandPath `
                -TracePath $tracePath -BridgeLogPath $bridgePath -OutputPath $reportPath `
                -ExpectedModel gpt-5.6-sol -ExpectedEffort $caseEffort `
                -ExpectedPromptProfile $casePromptProfile
            if ($LASTEXITCODE -ne $case.expected_exit) {
                $failureDetail = if (Test-Path -LiteralPath $reportPath) {
                    (Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json).violations -join '; '
                } else { 'report missing' }
                throw "self-test '$($case.name)' exit mismatch: $LASTEXITCODE; $failureDetail"
            }
            $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
            $caseTimeoutSeconds =
                [int]$EvaluationProfiles[$casePromptProfile]['timeout_minutes'] * 60
            if ($report.evaluator_timeout_seconds -ne $caseTimeoutSeconds) {
                throw "self-test '$($case.name)' profile timeout mismatch"
            }
            if ($case.Contains('expected_success') -and
                ($report.successful_dynamic_call_count -ne $case.expected_success -or
                    $report.failed_dynamic_call_count -ne $case.expected_failure)) {
                throw "self-test '$($case.name)' success/failure count mismatch"
            }
            if ($case.Contains('expected_rejection') -and
                $report.deadline_rejection_count -ne $case.expected_rejection) {
                throw "self-test '$($case.name)' deadline rejection count mismatch"
            }
            if ($case.Contains('expected_recovery') -and
                $report.recovery_witness.status -cne $case.expected_recovery) {
                throw "self-test '$($case.name)' recovery witness mismatch"
            }
            foreach ($needle in $case.required) {
                if (@($report.violations | Where-Object { [string]$_ -like "*$needle*" }).Count -eq 0) {
                    throw "self-test '$($case.name)' missing violation '$needle'"
                }
            }
            if ($case.Contains('required_manual')) {
                foreach ($needle in $case.required_manual) {
                    if (@($report.manual_review_required | Where-Object {
                                [string]$_ -like "*$needle*"
                            }).Count -eq 0) {
                        throw "self-test '$($case.name)' missing manual review '$needle'"
                    }
                }
            }
        }
    } finally {
        if ([IO.Directory]::Exists($temporaryRoot)) {
            [IO.Directory]::Delete($temporaryRoot, $true)
        }
    }
    & $powerShellExecutable -NoProfile -File (Join-Path $PSScriptRoot 'Test-McmcpTunnelRecoveryWitness.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'tunnel renderer recovery witness self-test failed'
    }
    Write-Host "評価 app-server trace audit self-test: $($cases.Count)/$($cases.Count) passed"
}

if ($PSCmdlet.ParameterSetName -eq 'SelfTest') {
    Invoke-AuditSelfTest
    exit 0
}

$passed = Invoke-TraceAudit -RawTrace $TracePath -BridgeTrace $BridgeLogPath `
    -ReportPath $OutputPath -Model $ExpectedModel -Effort $ExpectedEffort
if (-not $passed) { exit 1 }
exit 0
