[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('gpt-5.6-sol', 'gpt-5.6-luna')]
    [string]$Model,

    [Parameter(Mandatory)]
    [ValidateSet('high', 'xhigh')]
    [string]$ReasoningEffort,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$BaselineId,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$TokenPath,

    [Parameter(Mandatory)]
    [ValidateSet('short-regression', 'full-cycle', 'warehouse-smelt', 'hard-building-copy', 'container-inspect-recovery')]
    [string]$PromptProfile,

    # Required only for the recovery regression; paths never enter model input/artifacts.
    [string]$ProductCommit,
    [string]$ExpectedBuildJarSha256,
    [string]$BuildJarPath,
    [string]$InstalledJarPath,
    [string]$OptionsPath,
    [int]$ExpectedMaxFps,

    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',

    [switch]$LiveMonitor
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $Utf8NoBom

. (Join-Path $PSScriptRoot '../mcp/McmcpTransport.ps1')
. (Join-Path $PSScriptRoot 'McmcpRecoveryPreflight.ps1')

$monitorModulePath = Join-Path $PSScriptRoot 'McmcpLiveMonitor.psm1'
$monitorTestPath = Join-Path $PSScriptRoot 'Test-McmcpLiveMonitor.ps1'
$monitorLauncherPath = Join-Path $PSScriptRoot 'Start-McmcpFreshEvalMonitor.ps1'
$monitorHostPath = Join-Path $PSScriptRoot 'Invoke-McmcpFreshEvalMonitorHost.ps1'
Import-Module $monitorModulePath -Force
$script:LiveMonitorState = New-McmcpLiveMonitorState -Enabled:$LiveMonitor
Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'runner_started'

$EvaluationProfiles = [ordered]@{
    'container-inspect-recovery' = [ordered]@{
        prompt = '近くにあるチェスト1つの中身を一度だけ確認し、結果を教えてください。アイテムの出し入れはしないでください。'
        timeout_minutes = 5
    }
    'short-regression' = [ordered]@{
        prompt = 'チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか'
        timeout_minutes = 30
    }
    'full-cycle' = [ordered]@{
        prompt = 'チェストに小麦の種と鍬が入っています。これを取り出し、この畑の区画にある耕作可能な土をすべて耕して、すべてに小麦の種を植えてください。成熟後はすべて収穫して植え直す工程を、小麦を1スタック（64個）以上所持するまで繰り返してください。'
        timeout_minutes = 30
    }
    'warehouse-smelt' = [ordered]@{
        prompt = '近くの材料チェストから生の鉄1個と石炭1個を取り出し、かまどで鉄インゴット1個に精錬し、完成品用の空の樽へ収納してください。終了時はプレイヤーのインベントリ、材料チェスト、かまどを空にしてください。'
        timeout_minutes = 30
    }
    'hard-building-copy' = [ordered]@{
        prompt = 'チェストの材料を自由に加工して、近くにある屋根付きの木造建築を見本に、羊毛の上へ同じ建築をコピーしてください。'
        timeout_minutes = 90
    }
}
$EvaluationProfile = $EvaluationProfiles[$PromptProfile]
$ProductionPrompt = [string]$EvaluationProfile['prompt']
$RequiredCodexVersion = 'codex-cli 0.146.1'
$ModernProtocolVersion = '2026-07-28'
$EvaluatorTimeout = [TimeSpan]::FromMinutes([int]$EvaluationProfile['timeout_minutes'])
$TurnCompletionReserveSeconds = 15
$EvaluationLeaseMaximumDuration = $EvaluatorTimeout.Add([TimeSpan]::FromSeconds(45))
$EvaluationControlTimeoutSeconds = 10
$MaximumMcpForwardSeconds = 35
$AgentGetActionTransportMarginSeconds = 2
$DeadlineCleanupCancelTimeoutSeconds = 5
$DeadlineRejectedOutputText = '{"code":"EVALUATION_DEADLINE_IMMINENT","message":"The evaluation deadline is too close to safely forward another MCP request.","recoverable":false}'
$AuthExpirySafetyMargin = [TimeSpan]::FromMinutes(5)
$MinimumMcpRequestIntervalMilliseconds = 60
$ExpectedMcmcpServerName = 'mcmcp'
$ExpectedMcmcpServerVersion = '0.1.0'
$ExpectedCatalogFileSha256 = '21ec583df868f4e7da99f7d9645c86d5533550c0bba4780dc174a2736bdced39'
$ExpectedToolSurfaceSha256 = '7908d69ff5c498042557cd7df7694b29c0fe829a576d379174a003bde807a218'
$AllowedTools = @(
    'agent_get_state',
    'agent_get_observation',
    'agent_start_action',
    'agent_get_action',
    'agent_cancel_action'
)
$script:PrivateReasoningNotificationMethods = @(
    'item/reasoning/summaryPartAdded',
    'item/reasoning/summaryTextDelta',
    'item/reasoning/textDelta'
)
$DisabledFeatures = @(
    'shell_tool',
    'shell_snapshot',
    'unified_exec',
    'computer_use',
    'browser_use',
    'browser_use_external',
    'in_app_browser',
    'apps',
    'plugins',
    'remote_plugin',
    'skill_search',
    'skill_mcp_dependency_install',
    'tool_suggest',
    'multi_agent',
    'image_generation',
    'workspace_dependencies',
    'goals',
    'code_mode',
    'code_mode_host',
    'request_permissions_tool',
    'memories',
    'hooks',
    'auth_elicitation',
    'tool_call_mcp_elicitation'
)
$OptOutNotifications = @(
    'account/login/completed',
    'account/rateLimits/updated',
    'account/updated',
    'app/list/updated',
    'command/exec/outputDelta',
    'externalAgentConfig/import/completed',
    'externalAgentConfig/import/progress',
    'fs/changed',
    'fuzzyFileSearch/sessionCompleted',
    'fuzzyFileSearch/sessionUpdated',
    'hook/completed',
    'hook/started',
    'item/agentMessage/delta',
    'item/autoApprovalReview/completed',
    'item/autoApprovalReview/started',
    'item/commandExecution/outputDelta',
    'item/commandExecution/terminalInteraction',
    'item/fileChange/outputDelta',
    'item/fileChange/patchUpdated',
    'item/mcpToolCall/progress',
    'item/plan/delta',
    'item/reasoning/summaryPartAdded',
    'item/reasoning/summaryTextDelta',
    'item/reasoning/textDelta',
    'mcpServer/oauthLogin/completed',
    'mcpServer/startupStatus/updated',
    'model/rerouted',
    'model/safetyBuffering/updated',
    'model/verification',
    'process/exited',
    'process/outputDelta',
    'remoteControl/status/changed',
    'serverRequest/resolved',
    'skills/changed',
    'thread/archived',
    'thread/closed',
    'thread/compacted',
    'thread/deleted',
    'thread/environment/connected',
    'thread/environment/disconnected',
    'thread/goal/cleared',
    'thread/goal/updated',
    'thread/name/updated',
    'thread/realtime/closed',
    'thread/realtime/error',
    'thread/realtime/itemAdded',
    'thread/realtime/outputAudio/delta',
    'thread/realtime/sdp',
    'thread/realtime/started',
    'thread/realtime/transcript/delta',
    'thread/realtime/transcript/done',
    'thread/settings/updated',
    'thread/tokenUsage/updated',
    'thread/unarchived',
    'turn/diff/updated',
    'turn/moderationMetadata',
    'turn/plan/updated',
    'windows/worldWritableWarning',
    'windowsSandbox/setupCompleted'
)

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

function Get-Sha256 {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { $Text = '' }
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
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
    $canonical = ConvertTo-CanonicalNode $Value
    return (ConvertTo-Json -InputObject $canonical -Depth 100 -Compress)
}

function Get-PinnedCatalogSurface {
    param([Parameter(Mandatory)][string]$CatalogPath)
    if (-not (Test-Path -LiteralPath $CatalogPath -PathType Leaf)) {
        throw "canonical MCP Tool catalog is missing: $CatalogPath"
    }
    $fileHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $CatalogPath).Hash.ToLowerInvariant()
    if ($fileHash -cne $ExpectedCatalogFileSha256) {
        throw "canonical MCP Tool catalog file hash mismatch: $fileHash"
    }
    $catalog = [IO.File]::ReadAllText($CatalogPath) | ConvertFrom-Json -Depth 100
    $catalogTools = @(Get-PropertyValue -Object $catalog -Name 'tools')
    if ($catalogTools.Count -ne $AllowedTools.Count) {
        throw 'canonical MCP Tool catalog must contain exactly five tools'
    }
    $surface = [Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $AllowedTools.Count; $index++) {
        $tool = $catalogTools[$index]
        $name = [string](Get-PropertyValue $tool 'name')
        $description = [string](Get-PropertyValue $tool 'description')
        $schema = Get-Property $tool 'inputSchema'
        if ($name -cne $AllowedTools[$index] -or [string]::IsNullOrWhiteSpace($description) -or
            $null -eq $schema -or $null -eq $schema.Value) {
            throw "canonical MCP Tool surface is invalid at index $index"
        }
        $surface.Add([ordered]@{
                name = $name
                description = $description
                inputSchema = $schema.Value
            })
    }
    $canonicalJson = ConvertTo-SemanticCanonicalJson @($surface)
    $surfaceHash = Get-Sha256 $canonicalJson
    if ($surfaceHash -cne $ExpectedToolSurfaceSha256) {
        throw "canonical MCP Tool surface hash mismatch: $surfaceHash"
    }
    return [ordered]@{
        file_sha256 = $fileHash
        surface_sha256 = $surfaceHash
        canonical_json = $canonicalJson
        catalog_canonical_json = ConvertTo-SemanticCanonicalJson $catalog
    }
}

function Resolve-CodexExecutableTarget {
    param([Parameter(Mandatory)][string]$SourcePath)

    $currentPath = [IO.Path]::GetFullPath($SourcePath)
    $visitedPaths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase)
    $resolvedFile = $null
    for ($depth = 0; $depth -lt 16; $depth++) {
        if (-not $visitedPaths.Add($currentPath)) {
            throw "Codex executable reparse-point loop detected: $currentPath"
        }
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction Stop
        if ($item -isnot [IO.FileInfo] -or -not $item.Exists) {
            throw "Codex executable candidate is not a file: $currentPath"
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
            $resolvedFile = $item
            break
        }
        $target = $item.ResolveLinkTarget($false)
        if ($null -eq $target) {
            throw "Codex executable reparse-point target is unavailable: $currentPath"
        }
        $currentPath = [IO.Path]::GetFullPath($target.FullName)
    }
    if ($null -eq $resolvedFile) {
        throw 'Codex executable reparse-point chain exceeds the depth limit.'
    }

    $ancestor = $resolvedFile.Directory
    while ($null -ne $ancestor) {
        $ancestorItem = Get-Item -LiteralPath $ancestor.FullName -Force -ErrorAction Stop
        if (($ancestorItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Codex executable target ancestor is a reparse point: $($ancestorItem.FullName)"
        }
        $ancestor = $ancestorItem.Parent
    }
    return [IO.Path]::GetFullPath($resolvedFile.FullName)
}

function Assert-RunParameters {
    if ($Model -eq 'gpt-5.6-sol' -and $ReasoningEffort -ne 'high') {
        throw 'gpt-5.6-sol は high のみをこの評価 protocol で許可します。'
    }
    if ($Model -eq 'gpt-5.6-luna' -and $ReasoningEffort -notin @('xhigh', 'high')) {
        throw 'gpt-5.6-luna は xhigh または high のみを許可します。'
    }

    $endpointUri = [Uri]$Endpoint
    if (-not $endpointUri.IsAbsoluteUri -or $endpointUri.Scheme -ne 'http' -or
        $endpointUri.Host -cne '127.0.0.1' -or
        $endpointUri.Port -lt 1 -or $endpointUri.Port -gt 65535 -or
        $endpointUri.UserInfo -or $endpointUri.Query -or $endpointUri.Fragment) {
        throw 'Endpoint は明示 port を持つ安全な loopback HTTP URL である必要があります。'
    }
    if ($Endpoint -notmatch '^http://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9._~/%-]*$') {
        throw 'Endpoint の形式が評価用 allowlist に一致しません。'
    }
    if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
        throw "Bearer token file が見つかりません: $TokenPath"
    }

    $matchingCodexExecutables = [Collections.Generic.List[string]]::new()
    $seenCodexPaths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase)
    foreach ($codexCommand in @(Get-Command codex -CommandType Application -All `
                -ErrorAction Stop)) {
        $sourcePath = [string]$codexCommand.Source
        if ([string]::IsNullOrWhiteSpace($sourcePath)) { continue }
        try {
            $candidatePath = Resolve-CodexExecutableTarget -SourcePath $sourcePath
            if (-not $seenCodexPaths.Add($candidatePath)) { continue }
            $candidate = Get-Item -LiteralPath $candidatePath -Force -ErrorAction Stop
            if ($candidate -isnot [IO.FileInfo] -or -not $candidate.Exists -or
                ($candidate.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                continue
            }
            $candidateVersionOutput = @(& $candidate.FullName --version 2>$null)
            $candidateExitCode = $LASTEXITCODE
            if ($candidateExitCode -eq 0 -and $candidateVersionOutput.Count -eq 1 -and
                [string]$candidateVersionOutput[0] -ceq $RequiredCodexVersion) {
                $matchingCodexExecutables.Add($candidate.FullName)
            }
        } catch {
            continue
        }
    }
    if ($matchingCodexExecutables.Count -ne 1) {
        throw "Codex CLI executable match count must be exactly one: $($matchingCodexExecutables.Count)"
    }
    $script:CodexExecutable = $matchingCodexExecutables[0]
    $script:ValidatedCodexVersion = $RequiredCodexVersion
}

function Test-IsDescendantPath {
    param([Parameter(Mandatory)][string]$Candidate, [Parameter(Mandatory)][string]$Parent)
    $candidatePath = [IO.Path]::GetFullPath($Candidate).TrimEnd('\', '/')
    $parentPath = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    return $candidatePath.StartsWith(
        $parentPath + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)
}

function Assert-NoProjectCodexConfig {
    param([Parameter(Mandatory)][string]$Workspace)
    $cursor = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Workspace))
    while ($null -ne $cursor) {
        $candidate = Join-Path (Join-Path $cursor.FullName '.codex') 'config.toml'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            throw "clean cwd ancestor contains project Codex config: $candidate"
        }
        $cursor = $cursor.Parent
    }
}

function Assert-NoReparsePointInPath {
    param([Parameter(Mandatory)][string]$Path)
    $cursor = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    while ($null -ne $cursor) {
        if (($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "isolated evaluation path contains a reparse point: $($cursor.FullName)"
        }
        $cursor = $cursor.Parent
    }
}

function ConvertFrom-Base64Url {
    param([Parameter(Mandatory)][string]$Text)
    $padded = $Text.Replace('-', '+').Replace('_', '/')
    switch ($padded.Length % 4) {
        0 { }
        2 { $padded += '==' }
        3 { $padded += '=' }
        default { throw 'invalid external auth token' }
    }
    try {
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($padded))
    } catch {
        throw 'invalid external auth token'
    }
}

function Get-ExternalAuthSecrets {
    param([Parameter(Mandatory)][string]$AuthPath)
    if (-not (Test-Path -LiteralPath $AuthPath -PathType Leaf)) {
        throw 'canonical Codex auth file is unavailable'
    }
    try {
        $auth = [IO.File]::ReadAllText($AuthPath) | ConvertFrom-Json -Depth 20
        $tokens = Get-PropertyValue -Object $auth -Name 'tokens'
        $accessToken = [string](Get-PropertyValue -Object $tokens -Name 'access_token')
        $accountId = [string](Get-PropertyValue -Object $tokens -Name 'account_id')
        if ([string]::IsNullOrWhiteSpace($accessToken) -or
            [string]::IsNullOrWhiteSpace($accountId)) {
            throw 'missing external auth fields'
        }
        $parts = $accessToken.Split('.')
        if ($parts.Count -ne 3) { throw 'invalid external auth token' }
        $payloadJson = ConvertFrom-Base64Url -Text $parts[1]
        $payload = $payloadJson | ConvertFrom-Json -Depth 20
        $exp = Get-PropertyValue -Object $payload -Name 'exp'
        if ($null -eq $exp) { throw 'invalid external auth token' }
        $expiresAt = [DateTimeOffset]::FromUnixTimeSeconds([long]$exp)
        return [ordered]@{
            access_token = $accessToken
            account_id = $accountId
            expires_at = $expiresAt
        }
    } catch {
        throw 'canonical Codex auth file could not provide valid external credentials'
    } finally {
        $auth = $null
        $tokens = $null
        $payload = $null
        $payloadJson = $null
        $accessToken = $null
        $accountId = $null
    }
}

function Assert-ExternalAuthLifetime {
    param(
        [Parameter(Mandatory)][DateTimeOffset]$ExpiresAt,
        [Parameter(Mandatory)][string]$Phase
    )
    $minimumExpiry = [DateTimeOffset]::UtcNow.Add($EvaluatorTimeout).Add($AuthExpirySafetyMargin)
    if ($ExpiresAt -le $minimumExpiry) {
        throw "external auth token lifetime is insufficient at $Phase"
    }
}

function Get-AppRequestIdKey {
    param([AllowNull()][object]$Value)
    if ($Value -is [string]) {
        if ([string]::IsNullOrWhiteSpace($Value)) { throw 'app-server request id is empty' }
        return 'string:' + $Value
    }
    if ($null -ne $Value -and $Value.GetType().FullName -in @(
            'System.Byte', 'System.SByte', 'System.Int16', 'System.UInt16',
            'System.Int32', 'System.UInt32', 'System.Int64', 'System.UInt64')) {
        return 'number:' + [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    throw 'app-server request id must be a non-empty string or integer'
}

function Test-IsForbiddenChildEnvironmentName {
    param([Parameter(Mandatory)][string]$Name)
    if ($Name -match '(?i)^(CODEX_|OPENAI_|MCMCP)' -or
        $Name -match '(?i)(TOKEN|BEARER|SECRET|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|PASSWORD|CREDENTIAL)' -or
        $Name -match '(?i)^OTEL_' -or
        $Name -in @(
            'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
            'SSL_CERT_FILE', 'SSL_CERT_DIR', 'NODE_EXTRA_CA_CERTS',
            'REQUESTS_CA_BUNDLE', 'CURL_CA_BUNDLE', 'SSLKEYLOGFILE', 'RUST_LOG')) {
        return $true
    }
    return $false
}

function Protect-ArtifactTreeFromSecrets {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Secrets,
        [Parameter(Mandatory)][string]$Root
    )
    $leaks = [Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return @() }
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse -Force) {
        try {
            $content = [IO.File]::ReadAllText($file.FullName)
            $changed = $false
            foreach ($entry in $Secrets.GetEnumerator()) {
                $secret = [string]$entry.Value
                if ([string]::IsNullOrEmpty($secret) -or
                    -not $content.Contains($secret, [StringComparison]::Ordinal)) {
                    continue
                }
                $content = $content.Replace(
                    $secret,
                    "[REDACTED_$($entry.Key)]",
                    [StringComparison]::Ordinal)
                $changed = $true
            }
            if ($changed) {
                [IO.File]::WriteAllText(
                    $file.FullName,
                    $content,
                    $Utf8NoBom)
                $leaks.Add($file.FullName)
            }
        } catch {
            throw "artifact secret scan failed: $($file.FullName)"
        }
    }
    return @($leaks)
}

function Test-ContainsEvaluationSecret {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) { return $false }
    foreach ($secret in @(
            $script:Bearer,
            $script:AccessToken,
            $script:ChatgptAccountId,
            $script:EvaluationLeaseId)) {
        if (-not [string]::IsNullOrEmpty([string]$secret) -and
            $Text.Contains([string]$secret, [StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Assert-NoEvaluationSecretForArtifactText {
    param([AllowNull()][string]$Text)
    if (Test-ContainsEvaluationSecret $Text) {
        $script:BridgeSecretDetected = $true
        throw 'artifact boundary rejected exact evaluation secret'
    }
}

function Remove-IsolatedRoot {
    param([AllowNull()][string]$Root, [Parameter(Mandatory)][string]$Base)
    if ([string]::IsNullOrWhiteSpace($Root) -or -not [IO.Directory]::Exists($Root)) { return }
    $fullRoot = [IO.Path]::GetFullPath($Root)
    $leaf = [IO.Path]::GetFileName($fullRoot)
    if (-not (Test-IsDescendantPath -Candidate $fullRoot -Parent $Base) -or
        $leaf -notmatch '^mcmcp-eval-[0-9a-f]{32}$') {
        throw "isolated root cleanup guard rejected path: $fullRoot"
    }
    Assert-NoReparsePointInPath -Path $Base
    Assert-NoReparsePointInPath -Path $fullRoot
    foreach ($entry in Get-ChildItem -LiteralPath $fullRoot -Force -Recurse) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "isolated root cleanup rejected nested reparse point: $($entry.FullName)"
        }
    }
    [IO.Directory]::Delete($fullRoot, $true)
}

function Get-DynamicForwardTimeoutSeconds {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][object]$Arguments
    )
    if ($Tool -cne 'agent_get_action') {
        return $MaximumMcpForwardSeconds
    }

    $waitTimeoutMilliseconds = 0L
    $waitProperty = Get-Property -Object $Arguments -Name 'wait_timeout_ms'
    if ($null -ne $waitProperty) {
        if (-not (Test-JsonIntegerValue $waitProperty.Value) -or
            $waitProperty.Value -lt 0 -or $waitProperty.Value -gt 25000) {
            throw 'agent_get_action wait_timeout_ms failed strict validation'
        }
        $waitTimeoutMilliseconds = [long]$waitProperty.Value
    }
    $waitSeconds = [int][Math]::Ceiling($waitTimeoutMilliseconds / 1000.0D)
    return [int][Math]::Min(
        $MaximumMcpForwardSeconds,
        [Math]::Max(1, $waitSeconds + $AgentGetActionTransportMarginSeconds))
}

function Test-DeadlineCleanupCancelArguments {
    param([Parameter(Mandatory)][object]$Arguments)
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

function Wait-McmcpRequestSlot {
    $now = [Diagnostics.Stopwatch]::GetTimestamp()
    if ($script:LastMcpRequestTimestamp -gt 0) {
        $elapsedMilliseconds = (($now - $script:LastMcpRequestTimestamp) * 1000.0D) /
            [Diagnostics.Stopwatch]::Frequency
        $remainingMilliseconds =
            $MinimumMcpRequestIntervalMilliseconds - $elapsedMilliseconds
        if ($remainingMilliseconds -gt 0) {
            Start-Sleep -Milliseconds ([int][Math]::Ceiling($remainingMilliseconds))
        }
    }
    $script:LastMcpRequestTimestamp = [Diagnostics.Stopwatch]::GetTimestamp()
}

function Invoke-McmcpJsonRpc {
    param(
        [Parameter(Mandatory)][ValidateSet('server/discover', 'tools/list', 'tools/call')]
        [string]$Method,
        [Parameter(Mandatory)][object]$Parameters,
        [string]$ToolName,
        [ValidateRange(1, 35)][int]$TimeoutSeconds = 15,
        [switch]$PacingAlreadyApplied
    )

    if (-not $PacingAlreadyApplied) {
        Wait-McmcpRequestSlot
    }
    $script:McpRequestId++
    [long]$requestId = $script:McpRequestId
    $leaseId = if ($script:EvaluationLeaseAcquired) { $script:EvaluationLeaseId } else { $null }
    return Invoke-McmcpTransportRequest -Endpoint $Endpoint -Bearer $script:Bearer `
        -RequestId $requestId -Method $Method -Parameters $Parameters -ToolName $ToolName `
        -EvaluationLeaseId $leaseId -TimeoutSeconds $TimeoutSeconds
}

function Assert-NoJsonRpcError {
    param([Parameter(Mandatory)][object]$Response, [Parameter(Mandatory)][string]$Operation)
    $errorProperty = Get-Property -Object $Response -Name 'error'
    if ($null -ne $errorProperty -and $null -ne $errorProperty.Value) {
        $code = Get-PropertyValue -Object $errorProperty.Value -Name 'code'
        throw "$Operation returned JSON-RPC error code=$code"
    }
    $resultProperty = Get-Property -Object $Response -Name 'result'
    if ($null -eq $resultProperty -or $null -eq $resultProperty.Value) {
        throw "$Operation returned no result"
    }
}

function Invoke-McmcpReadinessCheck {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('preflight', 'preliminary', 'T0')]
        [string]$Phase
    )
    $operation = "tools/call(agent_get_state:$Phase)"
    $state = Invoke-McmcpJsonRpc -Method 'tools/call' -ToolName 'agent_get_state' `
        -Parameters ([ordered]@{
            _meta = Get-McpMeta
            name = 'agent_get_state'
            arguments = [ordered]@{}
        })
    Assert-NoJsonRpcError -Response $state -Operation $operation
    $stateResult = Get-PropertyValue -Object $state -Name 'result'
    Assert-McmcpToolResult -Result $stateResult -Operation $operation -RequireSuccess
    $snapshotProperty = Get-Property -Object $stateResult -Name 'structuredContent'
    if ($null -eq $snapshotProperty -or $null -eq $snapshotProperty.Value) {
        throw "$operation returned no structuredContent snapshot"
    }
    $snapshot = $snapshotProperty.Value
    $readyModeOk = (Get-NestedValue $snapshot 'control.mode') -ceq 'ready'
    $pausedValue = Get-NestedValue $snapshot 'control.game_paused'
    $gameUnpaused = $pausedValue -is [bool] -and -not $pausedValue
    $worldPresent = $null -ne (Get-PropertyValue $snapshot 'world')
    $observationPresent = $null -ne (Get-PropertyValue $snapshot 'observation')
    $inventoryProperty = Get-Property $snapshot 'inventory'
    $inventoryEmpty = $null -ne $inventoryProperty -and
        $inventoryProperty.Value -is [Collections.IEnumerable] -and
        $inventoryProperty.Value -isnot [string] -and @($inventoryProperty.Value).Count -eq 0
    $raysPerTick = Get-NestedValue $snapshot 'policy.omnidirectional_rays_per_tick'
    $raysPerTick512 = $raysPerTick -is [long] -and $raysPerTick -eq 512
    $visibleEntityCount = Get-NestedValue `
        $snapshot 'observation.record_counts.visible_entity'
    $visibleEntitiesZero = $visibleEntityCount -is [long] -and $visibleEntityCount -eq 0
    $actionProperty = Get-Property $snapshot 'action'
    $actionIdleOrTerminal = $null -ne $actionProperty -and (
        $null -eq $actionProperty.Value -or
        (Get-PropertyValue $actionProperty.Value 'state') -cin @(
            'succeeded', 'failed', 'cancelled'))
    $readiness = [ordered]@{
        get_state_ok = $true
        ready_mode_ok = $readyModeOk
        game_unpaused = $gameUnpaused
        world_present = $worldPresent
        observation_present = $observationPresent
        inventory_empty = $inventoryEmpty
        rays_per_tick_512 = $raysPerTick512
        visible_entities_zero = $visibleEntitiesZero
        action_idle_or_terminal = $actionIdleOrTerminal
    }
    $failedFlags = @($readiness.Keys | Where-Object {
            $_ -cne 'get_state_ok' -and -not [bool]$readiness[$_]
        })
    if ($failedFlags.Count -gt 0) {
        # Persist only fixed-name Boolean proofs. Never retain the state snapshot itself.
        $diagnostic = [ordered]@{
            phase = $Phase
            get_state_ok = $readiness.get_state_ok
            ready_mode_ok = $readiness.ready_mode_ok
            game_unpaused = $readiness.game_unpaused
            world_present = $readiness.world_present
            observation_present = $readiness.observation_present
            inventory_empty = $readiness.inventory_empty
            rays_per_tick_512 = $readiness.rays_per_tick_512
            visible_entities_zero = $readiness.visible_entities_zero
            action_idle_or_terminal = $readiness.action_idle_or_terminal
            failed_flags = @($failedFlags)
            raw_state_recorded = $false
        }
        $script:ReadinessFailure = $diagnostic
        Write-BridgeEvent ([ordered]@{
                event = 'readiness_check_failed'
                phase = $diagnostic.phase
                get_state_ok = $diagnostic.get_state_ok
                ready_mode_ok = $diagnostic.ready_mode_ok
                game_unpaused = $diagnostic.game_unpaused
                world_present = $diagnostic.world_present
                observation_present = $diagnostic.observation_present
                inventory_empty = $diagnostic.inventory_empty
                rays_per_tick_512 = $diagnostic.rays_per_tick_512
                visible_entities_zero = $diagnostic.visible_entities_zero
                action_idle_or_terminal = $diagnostic.action_idle_or_terminal
                failed_flags = @($diagnostic.failed_flags)
                raw_state_recorded = $diagnostic.raw_state_recorded
            })
        throw "agent_get_state readiness contract failed at ${Phase}: failed_flags=$($failedFlags -join ',')"
    }
    return $readiness
}

function Invoke-ReadOnlyPreflight {
    $meta = Get-McpMeta
    $discover = Invoke-McmcpJsonRpc -Method 'server/discover' -Parameters ([ordered]@{
            _meta = $meta
        })
    Assert-NoJsonRpcError -Response $discover -Operation 'server/discover'
    $discoverResult = Get-PropertyValue $discover 'result'
    $supportedVersionsProperty = Get-Property $discoverResult 'supportedVersions'
    $supportedVersions = if ($null -ne $supportedVersionsProperty -and
        (Test-IsArrayValue $supportedVersionsProperty.Value)) {
        @($supportedVersionsProperty.Value)
    } else { @() }
    $expectedDiscoverResult = [ordered]@{
        resultType = 'complete'
        supportedVersions = @($ModernProtocolVersion)
        capabilities = [ordered]@{ tools = [ordered]@{ listChanged = $false } }
        _meta = [ordered]@{
            'io.modelcontextprotocol/serverInfo' = [ordered]@{
                name = $ExpectedMcmcpServerName
                version = $ExpectedMcmcpServerVersion
            }
        }
        ttlMs = 0
        cacheScope = 'private'
    }
    if ((ConvertTo-SemanticCanonicalJson $discoverResult) -cne
        (ConvertTo-SemanticCanonicalJson $expectedDiscoverResult)) {
        throw 'server/discover returned an invalid modern discovery contract'
    }
    Assert-McmcpServerMeta -Result $discoverResult -Operation 'server/discover'

    $list = Invoke-McmcpJsonRpc -Method 'tools/list' -Parameters ([ordered]@{
            _meta = $meta
        })
    Assert-NoJsonRpcError -Response $list -Operation 'tools/list'
    $listResult = Get-PropertyValue -Object $list -Name 'result'
    if ((Get-PropertyValue $listResult 'resultType') -cne 'complete' -or
        (Get-PropertyValue $listResult 'ttlMs') -ne 0 -or
        (Get-PropertyValue $listResult 'cacheScope') -cne 'private') {
        throw 'tools/list returned an invalid complete/private contract'
    }
    Assert-McmcpServerMeta -Result $listResult -Operation 'tools/list'
    if ((ConvertTo-SemanticCanonicalJson $listResult) -cne
        $script:PinnedCatalogSurface.catalog_canonical_json) {
        throw 'tools/list full result does not match the pinned canonical catalog'
    }
    $toolsProperty = Get-Property $listResult 'tools'
    if ($null -eq $toolsProperty -or -not (Test-IsArrayValue $toolsProperty.Value)) {
        throw 'tools/list returned no tools array'
    }
    $listed = @($toolsProperty.Value)
    if ($listed.Count -ne $AllowedTools.Count) {
        throw "tools/list count mismatch: $($listed.Count)"
    }

    $dynamicTools = [Collections.Generic.List[object]]::new()
    $liveSurface = [Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $AllowedTools.Count; $index++) {
        $tool = $listed[$index]
        $name = [string](Get-PropertyValue -Object $tool -Name 'name')
        $description = [string](Get-PropertyValue -Object $tool -Name 'description')
        $schemaProperty = Get-Property -Object $tool -Name 'inputSchema'
        if ($name -ne $AllowedTools[$index]) {
            throw "tools/list order/name mismatch at $index`: $name"
        }
        if ([string]::IsNullOrWhiteSpace($description) -or $null -eq $schemaProperty -or
            -not (Test-IsObjectValue $schemaProperty.Value)) {
            throw "tool description/inputSchema missing: $name"
        }
        $liveSurface.Add([ordered]@{
                name = $name
                description = $description
                inputSchema = $schemaProperty.Value
            })
        $dynamicTools.Add([ordered]@{
                type = 'function'
                name = $name
                description = $description
                inputSchema = $schemaProperty.Value
            })
    }

    $liveSurfaceJson = ConvertTo-SemanticCanonicalJson @($liveSurface)
    $liveSurfaceHash = Get-Sha256 $liveSurfaceJson
    if ($liveSurfaceJson -cne $script:PinnedCatalogSurface.canonical_json -or
        $liveSurfaceHash -cne $ExpectedToolSurfaceSha256) {
        throw "live MCP Tool surface does not match the pinned catalog: $liveSurfaceHash"
    }

    $readiness = Invoke-McmcpReadinessCheck -Phase 'preflight'

    $dynamicJson = ConvertTo-CompactJson @($dynamicTools)
    if (Test-ContainsEvaluationSecret $dynamicJson) {
        throw 'tools/list schema was blocked by the secret filter'
    }
    return [ordered]@{
        artifact = [ordered]@{
            protocol_version = $ModernProtocolVersion
            discover_ok = $true
            discover_contract_ok = $true
            list_contract_ok = $true
            discover_semantic_exact = $true
            list_semantic_exact = $true
            jsonrpc_envelopes_ok = $true
            http_content_type_ok = $true
            server_info_ok = $true
            listed_tools = @($AllowedTools)
            dynamic_tools_sha256 = Get-Sha256 $dynamicJson
            catalog_file_sha256 = $script:PinnedCatalogSurface.file_sha256
            expected_tool_surface_sha256 = $ExpectedToolSurfaceSha256
            live_tool_surface_sha256 = $liveSurfaceHash
            tool_surface_match = $true
            get_state_ok = $readiness.get_state_ok
            ready_mode_ok = $readiness.ready_mode_ok
            game_unpaused = $readiness.game_unpaused
            world_present = $readiness.world_present
            observation_present = $readiness.observation_present
            inventory_empty = $readiness.inventory_empty
            rays_per_tick_512 = $readiness.rays_per_tick_512
            visible_entities_zero = $readiness.visible_entities_zero
            action_idle_or_terminal = $readiness.action_idle_or_terminal
            gameplay_calls_made = $false
        }
        dynamic_tools = @($dynamicTools)
    }
}

function Get-ThreadConfig {
    return [ordered]@{
        cli_auth_credentials_store = 'ephemeral'
        model_reasoning_effort = $ReasoningEffort
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
        features = [ordered]@{
            multi_agent = $false
            tool_suggest = $false
            apps = $false
            plugins = $false
            image_generation = $false
            standalone_web_search = $false
            code_mode = $false
            code_mode_only = $false
            request_permissions_tool = $false
            deferred_executor = $false
            token_budget = $false
            current_time_reminder = $false
        }
    }
}

function Write-BridgeEvent {
    param([Parameter(Mandatory)][Collections.IDictionary]$Fields)
    $script:BridgeSequence++
    $record = [ordered]@{
        sequence = $script:BridgeSequence
        utc = [DateTimeOffset]::UtcNow.ToString('o')
    }
    foreach ($key in $Fields.Keys) { $record[$key] = $Fields[$key] }
    $recordJson = ConvertTo-CompactJson $record
    Assert-NoEvaluationSecretForArtifactText -Text $recordJson
    $script:BridgeWriter.WriteLine($recordJson)
    $script:BridgeWriter.Flush()
}

function Test-ExactEvaluationPropertySet {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory)][string[]]$Expected
    )
    if (-not (Test-IsObjectValue $Object)) { return $false }
    $actual = @($Object.PSObject.Properties.Name)
    if ($actual.Count -ne $Expected.Count) { return $false }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) { return $false }
    }
    return $true
}

function Assert-SafeReasoningArtifactMessage {
    param([Parameter(Mandatory)][object]$Message)

    $method = Get-PropertyValue -Object $Message -Name 'method'
    # Opt-out is only the first boundary. If a pinned or damaged app-server emits
    # a private reasoning notification anyway, reject it before RawWriter sees it.
    if ($method -cin $script:PrivateReasoningNotificationMethods) {
        throw 'private reasoning notification reached artifact boundary'
    }
    if ($method -cnotin @('item/started', 'item/completed')) { return }
    $params = Get-PropertyValue -Object $Message -Name 'params'
    $item = Get-PropertyValue -Object $params -Name 'item'
    if ((Get-PropertyValue -Object $item -Name 'type') -cne 'reasoning') { return }

    $timestampName = if ($method -ceq 'item/started') {
        'startedAtMs'
    } else { 'completedAtMs' }
    $summaryProperty = Get-Property -Object $item -Name 'summary'
    $contentProperty = Get-Property -Object $item -Name 'content'
    $id = Get-PropertyValue -Object $item -Name 'id'
    $safe = Test-ExactEvaluationPropertySet -Object $Message `
        -Expected @('method', 'params', 'emittedAtMs')
    $safe = $safe -and (Test-ExactEvaluationPropertySet -Object $params `
            -Expected @('threadId', 'turnId', $timestampName, 'item'))
    $safe = $safe -and (Test-ExactEvaluationPropertySet -Object $item `
            -Expected @('id', 'type', 'summary', 'content'))
    $safe = $safe -and $id -is [string] -and
        -not [string]::IsNullOrWhiteSpace([string]$id) -and
        ([string]$id).Length -le 256 -and ([string]$id) -cnotmatch '[\p{Cc}\p{Cf}]'
    $safe = $safe -and $null -ne $summaryProperty -and
        (Test-IsArrayValue $summaryProperty.Value) -and
        @($summaryProperty.Value | Where-Object { $_ -isnot [string] }).Count -eq 0
    $safe = $safe -and ($method -cne 'item/started' -or
        @($summaryProperty.Value).Count -eq 0)
    # `content` is the private-readable reasoning channel in the pinned app-server
    # schema. Only completed public summary strings may be retained.
    $safe = $safe -and $null -ne $contentProperty -and
        (Test-IsArrayValue $contentProperty.Value) -and
        @($contentProperty.Value).Count -eq 0
    if (-not $safe) {
        throw 'reasoning item failed safe artifact schema validation'
    }
}

function Write-ValidatedAppServerTail {
    param([AllowNull()][string]$Tail)
    if ([string]::IsNullOrEmpty($Tail)) { return }

    $reader = [IO.StringReader]::new($Tail)
    try {
        while ($null -ne ($tailLine = $reader.ReadLine())) {
            if ([string]::IsNullOrWhiteSpace($tailLine)) {
                throw 'app-server tail contained a non-JSONL line'
            }
            try {
                $tailMessage = $tailLine | ConvertFrom-Json -Depth 100
            } catch {
                throw 'app-server tail emitted malformed JSONL'
            }
            Assert-SafeReasoningArtifactMessage -Message $tailMessage
            Assert-NoEvaluationSecretForArtifactText -Text $tailLine
            $script:RawWriter.WriteLine($tailLine)
        }
        $script:RawWriter.Flush()
    } finally {
        $reader.Dispose()
    }
}

function Wait-EvaluationTask {
    param(
        [Parameter(Mandatory)][object]$Task,
        [Parameter(Mandatory)][ValidateRange(1, 600000)][int]$TimeoutMilliseconds,
        [Parameter(Mandatory)][string]$FailureMessage
    )
    try {
        if (-not $Task.Wait($TimeoutMilliseconds)) { throw $FailureMessage }
        return $Task.GetAwaiter().GetResult()
    } catch {
        throw $FailureMessage
    }
}

function Read-CompletedEvaluationLeaseEvent {
    if ($null -eq $script:EvaluationLeaseReadTask -or
        -not $script:EvaluationLeaseReadTask.IsCompleted) {
        throw 'evaluation lease event was consumed before completion'
    }
    try {
        $line = $script:EvaluationLeaseReadTask.GetAwaiter().GetResult()
    } catch {
        throw 'evaluation lease stream read failed'
    }
    if ($null -eq $line) { throw 'evaluation lease stream ended without terminal receipt' }
    try {
        $event = $line | ConvertFrom-Json -Depth 10
    } catch {
        throw 'evaluation lease stream emitted malformed JSON'
    }

    if (Test-ExactEvaluationPropertySet -Object $event -Expected @('state')) {
        if ((Get-PropertyValue $event 'state') -cne 'active') {
            throw 'evaluation lease heartbeat state mismatch'
        }
        $script:EvaluationLeaseReadTask = $script:EvaluationLeaseReader.ReadLineAsync()
        return 'active'
    }

    if (-not (Test-ExactEvaluationPropertySet -Object $event `
                -Expected @(
                    'state', 'reason', 'inputs_released', 'input_owner_none',
                    'all_actions_terminal', 'process_identity_bound')) -or
        (Get-PropertyValue $event 'state') -cne 'released' -or
        (Get-PropertyValue $event 'reason') -isnot [string] -or
        (Get-PropertyValue $event 'reason') -cnotin @(
            'turn_completed', 'runner_failure', 'evaluation_deadline',
            'launcher_teardown', 'runner_connection_closed', 'runner_process_exited',
            'local_escape', 'local_ui_disabled', 'world_changed',
            'player_unavailable', 'endpoint_fault', 'client_shutdown',
            'lease_expired', 'acquire_abandoned', 'input_release_failed') -or
        (Get-PropertyValue $event 'inputs_released') -isnot [bool] -or
        -not [bool](Get-PropertyValue $event 'inputs_released') -or
        (Get-PropertyValue $event 'input_owner_none') -isnot [bool] -or
        -not [bool](Get-PropertyValue $event 'input_owner_none') -or
        (Get-PropertyValue $event 'all_actions_terminal') -isnot [bool] -or
        -not [bool](Get-PropertyValue $event 'all_actions_terminal') -or
        (Get-PropertyValue $event 'process_identity_bound') -isnot [bool] -or
        -not [bool](Get-PropertyValue $event 'process_identity_bound')) {
        throw 'evaluation lease terminal receipt mismatch'
    }
    $script:EvaluationLeaseTerminalObserved = $true
    $script:EvaluationLeaseTerminalReason = [string](Get-PropertyValue $event 'reason')
    $script:EvaluationLeaseInputsReleased = [bool](Get-PropertyValue $event 'inputs_released')
    $script:EvaluationLeaseInputOwnerNone = [bool](
        Get-PropertyValue $event 'input_owner_none')
    $script:EvaluationLeaseAllActionsTerminal = [bool](
        Get-PropertyValue $event 'all_actions_terminal')
    $script:EvaluationLeaseProcessIdentityBound = [bool](
        Get-PropertyValue $event 'process_identity_bound')
    $script:EvaluationLeaseReadTask = $null
    $script:EvaluationLeaseReleasedAt = [DateTimeOffset]::UtcNow
    return 'released'
}

function Assert-EvaluationLeaseActiveBeforeT0 {
    if (-not $script:EvaluationLeaseAcquired -or
        $script:EvaluationLeaseTerminalObserved -or
        $null -eq $script:EvaluationLeaseReadTask) {
        throw 'evaluation lease is not active before T0'
    }
    for ($index = 0; $index -lt 64; $index++) {
        if (-not $script:EvaluationLeaseReadTask.IsCompleted) { return }
        if ((Read-CompletedEvaluationLeaseEvent) -ceq 'released') {
            throw 'evaluation input lease ended before T0'
        }
    }
    throw 'evaluation lease heartbeat backlog exceeded bound before T0'
}

function Start-EvaluationTurnLease {
    if ($script:EvaluationLeaseAcquired) {
        throw 'evaluation lease is already acquired'
    }
    $script:EvaluationLeaseId = [Guid]::NewGuid().ToString('D').ToLowerInvariant()
    $script:EvaluationLeaseIdSha256 = Get-Sha256 $script:EvaluationLeaseId
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    $script:EvaluationLeaseClient = [Net.Http.HttpClient]::new($handler, $true)
    $script:EvaluationLeaseClient.Timeout = [Threading.Timeout]::InfiniteTimeSpan
    $request = $null
    try {
        $request = [Net.Http.HttpRequestMessage]::new(
            [Net.Http.HttpMethod]::Post, $script:EvaluationControlEndpoint)
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer', $script:Bearer)
        $request.Headers.Accept.ParseAdd('application/x-ndjson')
        $maximumDurationMilliseconds = [long][Math]::Ceiling(
            $EvaluationLeaseMaximumDuration.TotalMilliseconds)
        $body = [ordered]@{
            lease_id = $script:EvaluationLeaseId
            runner_pid = [long]$PID
            max_duration_ms = $maximumDurationMilliseconds
        }
        $request.Content = [Net.Http.StringContent]::new(
            (ConvertTo-CompactJson $body), [Text.Encoding]::UTF8, 'application/json')
        $sendTask = $script:EvaluationLeaseClient.SendAsync(
            $request,
            [Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            [Threading.CancellationToken]::None)
        $script:EvaluationLeaseResponse = Wait-EvaluationTask -Task $sendTask `
            -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
            -FailureMessage 'evaluation lease acquire response timeout'
        if ([int]$script:EvaluationLeaseResponse.StatusCode -ne 200) {
            throw 'evaluation lease acquire was rejected'
        }
        $contentType = $script:EvaluationLeaseResponse.Content.Headers.ContentType
        if ($null -eq $contentType -or
            $contentType.MediaType -cne 'application/x-ndjson' -or
            ([string]$contentType.CharSet).Trim('"') -cne 'utf-8') {
            throw 'evaluation lease stream content type mismatch'
        }
        try {
            $leaseHeaders = @($script:EvaluationLeaseResponse.Headers.GetValues(
                    'Mcmcp-Evaluation-Lease'))
        } catch {
            throw 'evaluation lease response header missing'
        }
        if ($leaseHeaders.Count -ne 1 -or $leaseHeaders[0] -cne 'active') {
            throw 'evaluation lease response header mismatch'
        }
        $streamTask = $script:EvaluationLeaseResponse.Content.ReadAsStreamAsync()
        $stream = Wait-EvaluationTask -Task $streamTask `
            -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
            -FailureMessage 'evaluation lease stream open timeout'
        $script:EvaluationLeaseReader = [IO.StreamReader]::new(
            $stream, [Text.UTF8Encoding]::new($false, $true), $true, 1024, $false)
        $initialTask = $script:EvaluationLeaseReader.ReadLineAsync()
        $initialLine = Wait-EvaluationTask -Task $initialTask `
            -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
            -FailureMessage 'evaluation lease active receipt timeout'
        if ($null -eq $initialLine) { throw 'evaluation lease stream ended before activation' }
        try { $initial = $initialLine | ConvertFrom-Json -Depth 10 } catch {
            throw 'evaluation lease active receipt was malformed'
        }
        if (-not (Test-ExactEvaluationPropertySet -Object $initial `
                    -Expected @(
                        'state', 'reason', 'inputs_released', 'input_owner_none',
                        'all_actions_terminal', 'process_identity_bound')) -or
            (Get-PropertyValue $initial 'state') -cne 'active' -or
            $null -ne (Get-PropertyValue $initial 'reason') -or
            (Get-PropertyValue $initial 'inputs_released') -isnot [bool] -or
            [bool](Get-PropertyValue $initial 'inputs_released') -or
            (Get-PropertyValue $initial 'input_owner_none') -isnot [bool] -or
            -not [bool](Get-PropertyValue $initial 'input_owner_none') -or
            (Get-PropertyValue $initial 'all_actions_terminal') -isnot [bool] -or
            -not [bool](Get-PropertyValue $initial 'all_actions_terminal') -or
            (Get-PropertyValue $initial 'process_identity_bound') -isnot [bool] -or
            -not [bool](Get-PropertyValue $initial 'process_identity_bound')) {
            throw 'evaluation lease active receipt mismatch'
        }
        $script:EvaluationLeaseAcquired = $true
        $script:EvaluationLeaseInputOwnerNone = [bool](
            Get-PropertyValue $initial 'input_owner_none')
        $script:EvaluationLeaseAllActionsTerminal = [bool](
            Get-PropertyValue $initial 'all_actions_terminal')
        $script:EvaluationLeaseProcessIdentityBound = [bool](
            Get-PropertyValue $initial 'process_identity_bound')
        $script:EvaluationLeaseReadTask = $script:EvaluationLeaseReader.ReadLineAsync()
        $script:EvaluationLeaseAcquiredAt = [DateTimeOffset]::UtcNow
        Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState `
            -Event 'evaluation_lease_acquired'
    } catch {
        try { if ($null -ne $script:EvaluationLeaseReader) { $script:EvaluationLeaseReader.Dispose() } } catch { }
        try { if ($null -ne $script:EvaluationLeaseResponse) { $script:EvaluationLeaseResponse.Dispose() } } catch { }
        try { if ($null -ne $script:EvaluationLeaseClient) { $script:EvaluationLeaseClient.Dispose() } } catch { }
        $script:EvaluationLeaseReader = $null
        $script:EvaluationLeaseResponse = $null
        $script:EvaluationLeaseClient = $null
        $script:EvaluationLeaseReadTask = $null
        $script:EvaluationLeaseId = $null
        $script:EvaluationLeaseIdSha256 = $null
        throw
    } finally {
        if ($null -ne $request) { $request.Dispose() }
    }
}

function Stop-EvaluationTurnLease {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('turn_completed', 'runner_failure', 'evaluation_deadline', 'launcher_teardown')]
        [string]$Reason
    )
    if (-not $script:EvaluationLeaseAcquired) { return }
    if (-not $script:EvaluationLeaseReleaseHttpConfirmed) {
        $request = $null
        $response = $null
        $script:EvaluationLeaseReleaseInProgress = $true
        try {
            $request = [Net.Http.HttpRequestMessage]::new(
                [Net.Http.HttpMethod]::Delete, $script:EvaluationControlEndpoint)
            $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new(
                'Bearer', $script:Bearer)
            $request.Headers.Accept.ParseAdd('application/json')
            $request.Content = [Net.Http.StringContent]::new(
                (ConvertTo-CompactJson ([ordered]@{
                        lease_id = $script:EvaluationLeaseId
                        reason = $Reason
                    })),
                [Text.Encoding]::UTF8,
                'application/json')
            $sendTask = $script:EvaluationLeaseClient.SendAsync($request)
            $response = Wait-EvaluationTask -Task $sendTask `
                -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
                -FailureMessage 'evaluation lease release response timeout'
            if ([int]$response.StatusCode -ne 200) {
                throw 'evaluation lease release was rejected'
            }
            $contentType = $response.Content.Headers.ContentType
            if ($null -eq $contentType -or $contentType.MediaType -cne 'application/json' -or
                ([string]$contentType.CharSet).Trim('"') -cne 'utf-8') {
                throw 'evaluation lease release content type mismatch'
            }
            $bodyTask = $response.Content.ReadAsStringAsync()
            $bodyText = Wait-EvaluationTask -Task $bodyTask `
                -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
                -FailureMessage 'evaluation lease release body timeout'
            try { $receipt = $bodyText | ConvertFrom-Json -Depth 10 } catch {
                throw 'evaluation lease release response was malformed'
            }
            if (-not (Test-ExactEvaluationPropertySet -Object $receipt `
                        -Expected @(
                            'state', 'reason', 'inputs_released', 'input_owner_none',
                            'all_actions_terminal', 'process_identity_bound')) -or
                (Get-PropertyValue $receipt 'state') -cne 'released' -or
                (Get-PropertyValue $receipt 'reason') -isnot [string] -or
                (Get-PropertyValue $receipt 'inputs_released') -isnot [bool] -or
                -not [bool](Get-PropertyValue $receipt 'inputs_released') -or
                (Get-PropertyValue $receipt 'input_owner_none') -isnot [bool] -or
                -not [bool](Get-PropertyValue $receipt 'input_owner_none') -or
                (Get-PropertyValue $receipt 'all_actions_terminal') -isnot [bool] -or
                -not [bool](Get-PropertyValue $receipt 'all_actions_terminal') -or
                (Get-PropertyValue $receipt 'process_identity_bound') -isnot [bool] -or
                -not [bool](Get-PropertyValue $receipt 'process_identity_bound')) {
                throw 'evaluation lease release receipt mismatch'
            }
            $responseReason = [string](Get-PropertyValue $receipt 'reason')
            $responseInputOwnerNone = [bool](Get-PropertyValue $receipt 'input_owner_none')
            $responseAllActionsTerminal = [bool](
                Get-PropertyValue $receipt 'all_actions_terminal')
            $responseProcessIdentityBound = [bool](
                Get-PropertyValue $receipt 'process_identity_bound')
            while (-not $script:EvaluationLeaseTerminalObserved) {
                if ($null -eq $script:EvaluationLeaseReadTask) {
                    throw 'evaluation lease terminal stream task missing'
                }
                Wait-EvaluationTask -Task $script:EvaluationLeaseReadTask `
                    -TimeoutMilliseconds ($EvaluationControlTimeoutSeconds * 1000) `
                    -FailureMessage 'evaluation lease terminal stream timeout' | Out-Null
                Read-CompletedEvaluationLeaseEvent | Out-Null
            }
            if (-not $script:EvaluationLeaseInputsReleased -or
                $script:EvaluationLeaseTerminalReason -cne $responseReason -or
                $script:EvaluationLeaseInputOwnerNone -ne $responseInputOwnerNone -or
                $script:EvaluationLeaseAllActionsTerminal -ne $responseAllActionsTerminal -or
                $script:EvaluationLeaseProcessIdentityBound -ne
                    $responseProcessIdentityBound) {
                throw 'evaluation lease HTTP and stream terminals disagree'
            }
            $script:EvaluationLeaseReleaseHttpConfirmed = $true
        } finally {
            $script:EvaluationLeaseReleaseInProgress = $false
            if ($null -ne $response) { $response.Dispose() }
            if ($null -ne $request) { $request.Dispose() }
        }
    }
    if (-not $script:EvaluationLeaseReleaseEventWritten) {
        $script:EvaluationLeaseReleaseEventWritten = $true
        Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState `
            -Event 'evaluation_lease_released'
    }
    if ($Reason -ceq 'turn_completed' -and (
            -not $script:EvaluationLeaseTerminalObserved -or
            -not $script:EvaluationLeaseInputsReleased -or
            -not $script:EvaluationLeaseInputOwnerNone -or
            -not $script:EvaluationLeaseAllActionsTerminal -or
            -not $script:EvaluationLeaseProcessIdentityBound -or
            $script:EvaluationLeaseTerminalReason -cne 'turn_completed')) {
        throw 'evaluation lease normal terminal reason mismatch'
    }
}

function Close-EvaluationLeaseTransport {
    try { if ($null -ne $script:EvaluationLeaseReader) { $script:EvaluationLeaseReader.Dispose() } } catch { }
    try { if ($null -ne $script:EvaluationLeaseResponse) { $script:EvaluationLeaseResponse.Dispose() } } catch { }
    try { if ($null -ne $script:EvaluationLeaseClient) { $script:EvaluationLeaseClient.Dispose() } } catch { }
    $script:EvaluationLeaseReader = $null
    $script:EvaluationLeaseResponse = $null
    $script:EvaluationLeaseClient = $null
    $script:EvaluationLeaseReadTask = $null
}

function Send-AppMessage {
    param(
        [Parameter(Mandatory)][object]$Message,
        [string]$AuditKind
    )
    if (-not [string]::IsNullOrWhiteSpace($AuditKind)) {
        Write-BridgeEvent ([ordered]@{
                event = 'client_send'
                kind = $AuditKind
                message = $Message
            })
    }
    $script:CodexProcess.StandardInput.WriteLine((ConvertTo-CompactJson $Message))
    $script:CodexProcess.StandardInput.Flush()
}

function Read-AppLine {
    param(
        [Parameter(Mandatory)][DateTimeOffset]$Deadline,
        [AllowNull()][object]$SuppressResponseId
    )
    $lineTask = $script:CodexProcess.StandardOutput.ReadLineAsync()
    while ($true) {
        $remaining = $Deadline - [DateTimeOffset]::UtcNow
        if ($remaining.TotalMilliseconds -le 0) { throw 'app-server response timeout' }
        $waitMilliseconds = [int][Math]::Min(
            [int]::MaxValue,
            [Math]::Ceiling($remaining.TotalMilliseconds))
        if ($script:EvaluationLeaseAcquired -and
            -not $script:EvaluationLeaseTerminalObserved -and
            $null -ne $script:EvaluationLeaseReadTask) {
            $leaseTask = $script:EvaluationLeaseReadTask
            $leaseReady = $leaseTask.IsCompleted
            if (-not $leaseReady) {
                $whenAny = [Threading.Tasks.Task]::WhenAny(
                    [Threading.Tasks.Task[]]@($lineTask, $leaseTask))
                if (-not $whenAny.Wait($waitMilliseconds)) {
                    throw 'app-server response timeout'
                }
                $whenAny.GetAwaiter().GetResult() | Out-Null
                # If stdout and the control stream complete together, the lease
                # terminal always wins. This prevents an abnormal terminal from
                # being hidden by a simultaneous turn/completed notification.
                $leaseReady = $leaseTask.IsCompleted
            }
            if ($leaseReady) {
                $leaseState = Read-CompletedEvaluationLeaseEvent
                if ($leaseState -ceq 'released' -and
                    -not $script:EvaluationLeaseReleaseInProgress) {
                    throw 'evaluation input lease ended before turn completion'
                }
                continue
            }
        } elseif (-not $lineTask.Wait($waitMilliseconds)) {
            throw 'app-server response timeout'
        }
        break
    }
    $line = $lineTask.GetAwaiter().GetResult()
    if ($null -eq $line) { throw 'app-server stdout ended before completion' }
    $suppressNonMethodMessages = $PSBoundParameters.ContainsKey('SuppressResponseId')
    try {
        $message = $line | ConvertFrom-Json -Depth 100
    } catch {
        throw 'app-server emitted malformed JSONL'
    }
    Assert-SafeReasoningArtifactMessage -Message $message
    $methodProperty = Get-Property -Object $message -Name 'method'
    $isMethodMessage = $null -ne $methodProperty -and
        $methodProperty.Value -is [string] -and
        -not [string]::IsNullOrWhiteSpace([string]$methodProperty.Value)
    # During a secret-bearing config/read wait, only valid method-bearing
    # notifications/requests may be recorded. Every response/unclassified line
    # remains memory-only until Wait-AppResponse validates its exact id/shape.
    $suppressRaw = $suppressNonMethodMessages -and -not $isMethodMessage
    if (-not $suppressRaw) {
        Assert-NoEvaluationSecretForArtifactText -Text $line
        $script:RawWriter.WriteLine($line)
        $script:RawWriter.Flush()
    }
    return $message
}

function Wait-AppResponse {
    param(
        [Parameter(Mandatory)][object]$Id,
        [Parameter(Mandatory)][DateTimeOffset]$Deadline,
        [switch]$SuppressRawResponse
    )
    while ($true) {
        $message = if ($SuppressRawResponse) {
            Read-AppLine -Deadline $Deadline -SuppressResponseId $Id
        } else {
            Read-AppLine -Deadline $Deadline
        }
        $idProperty = Get-Property -Object $message -Name 'id'
        $methodProperty = Get-Property -Object $message -Name 'method'
        $isMethodMessage = $null -ne $methodProperty -and
            $methodProperty.Value -is [string] -and
            -not [string]::IsNullOrWhiteSpace([string]$methodProperty.Value)
        if ($null -ne $idProperty -and $null -eq $methodProperty -and
            (Get-AppRequestIdKey $idProperty.Value) -ceq (Get-AppRequestIdKey $Id)) {
            $errorProperty = Get-Property -Object $message -Name 'error'
            if ($null -ne $errorProperty) {
                $code = Get-PropertyValue -Object $errorProperty.Value -Name 'code'
                throw "app-server request id=$Id failed: code=$code"
            }
            $resultProperty = Get-Property -Object $message -Name 'result'
            if ($null -eq $resultProperty -or $null -eq $resultProperty.Value) {
                throw "app-server request id=$Id returned no result"
            }
            return $message
        }
        if ($SuppressRawResponse -and -not $isMethodMessage) {
            if ($null -eq $methodProperty -and $null -ne $idProperty) {
                throw "unexpected app-server response while waiting for id=$Id"
            }
            throw "unclassified app-server message while waiting for id=$Id"
        }
        if ($null -ne $idProperty -and $null -ne $methodProperty) {
            throw "unexpected app-server request before turn: $($methodProperty.Value)"
        }
    }
}

function Get-EffectiveConfigProof {
    param(
        [Parameter(Mandatory)][object]$Response,
        [Parameter(Mandatory)][string]$ExpectedCwd
    )
    $result = Get-PropertyValue -Object $Response -Name 'result'
    if (-not (Test-IsObjectValue $result)) {
        throw 'config/read result must be an object'
    }
    $config = Get-PropertyValue -Object $result -Name 'config'
    if (-not (Test-IsObjectValue $config)) {
        throw 'config/read result.config must be an object'
    }
    $mcpServersProperty = Get-Property -Object $config -Name 'mcp_servers'
    if ($null -eq $mcpServersProperty -or
        -not (Test-IsObjectValue $mcpServersProperty.Value)) {
        throw 'effective config.mcp_servers must be a case-sensitive object'
    }
    $mcpServerCount = if ($mcpServersProperty.Value -is [Collections.IDictionary]) {
        @($mcpServersProperty.Value.Keys).Count
    } else {
        @($mcpServersProperty.Value.PSObject.Properties).Count
    }
    if ($mcpServerCount -ne 0) {
        throw "effective config contains MCP servers: count=$mcpServerCount"
    }
    return [ordered]@{
        request_id = 'config'
        include_layers = $true
        cwd_is_clean = -not [string]::IsNullOrWhiteSpace($ExpectedCwd)
        mcp_servers_object = $true
        mcp_server_count = 0
        raw_artifact_recorded = $false
    }
}

function Convert-McpResultToDynamicResponse {
    param([AllowNull()][object]$McpResponse)
    $success = $false
    $mode = 'rpc_error'
    $text = '{"error":"MCMCP JSON-RPC error"}'
    $mcpIsError = $false
    $domainErrorContractValid = $false
    $structuredContentPresent = $false

    $errorProperty = Get-Property -Object $McpResponse -Name 'error'
    if ($null -ne $errorProperty -and $null -ne $errorProperty.Value) {
        $code = Get-PropertyValue -Object $errorProperty.Value -Name 'code'
        $text = ConvertTo-CompactJson ([ordered]@{
                error = [ordered]@{ code = $code; message = 'MCMCP JSON-RPC error' }
            })
    } else {
        $resultProperty = Get-Property -Object $McpResponse -Name 'result'
        if ($null -eq $resultProperty -or $null -eq $resultProperty.Value) {
            $mode = 'missing_result'
            $text = '{"error":"MCMCP result missing"}'
        } else {
            $result = $resultProperty.Value
            Assert-McmcpToolResult -Result $result -Operation 'dynamic tools/call'
            $isToolError = [bool](Get-PropertyValue -Object $result -Name 'isError')
            $structuredProperty = Get-Property -Object $result -Name 'structuredContent'
            $mcpIsError = $isToolError
            $structuredContentPresent = $null -ne $structuredProperty -and
                $null -ne $structuredProperty.Value
            if ($null -ne $structuredProperty -and $null -ne $structuredProperty.Value) {
                $selectedMode = 'structuredContent'
                $text = ConvertTo-CompactJson $structuredProperty.Value
            } else {
                $content = @(Get-PropertyValue -Object $result -Name 'content')
                $texts = @($content | Where-Object {
                        (Get-PropertyValue -Object $_ -Name 'type') -eq 'text' -and
                        $null -ne (Get-Property -Object $_ -Name 'text')
                    } | ForEach-Object {
                        [string](Get-PropertyValue -Object $_ -Name 'text')
                    })
                if ($texts.Count -gt 0) {
                    $selectedMode = 'textContent'
                    $text = $texts -join "`n"
                } else {
                    $selectedMode = 'wholeResult'
                    $text = ConvertTo-CompactJson $result
                }
            }
            if ($isToolError) {
                # Domain rejection/recoverable failure is valid model input. Preserve its safe body.
                $mode = 'tool_error'
                $success = $false
                $domainErrorContractValid = $true
            } else {
                $mode = $selectedMode
                $success = $true
            }
        }
    }

    if (Test-ContainsEvaluationSecret $text) {
        $script:BridgeSecretDetected = $true
        $success = $false
        $mode = 'secret_blocked'
        $text = '{"error":"MCMCP response blocked by secret filter"}'
    }
    return [ordered]@{
        success = $success
        mode = $mode
        text = $text
        mcp_is_error = $mcpIsError
        domain_error_contract_valid = $domainErrorContractValid
        structured_content_present = $structuredContentPresent
    }
}

function Invoke-DynamicBridge {
    param([Parameter(Mandatory)][object]$Request)
    if ((Get-PropertyValue $Request 'method') -cne 'item/tool/call') {
        throw 'dynamic bridge received a forbidden request method'
    }
    $idProperty = Get-Property -Object $Request -Name 'id'
    if ($null -eq $idProperty -or $null -eq $idProperty.Value) {
        throw 'item/tool/call request has no non-null id property'
    }
    $requestId = $idProperty.Value
    $params = Get-PropertyValue -Object $Request -Name 'params'
    if (-not (Test-IsObjectValue $params)) {
        throw 'item/tool/call params must be an object'
    }
    $tool = [string](Get-PropertyValue -Object $params -Name 'tool')
    $callId = [string](Get-PropertyValue -Object $params -Name 'callId')
    $threadId = [string](Get-PropertyValue -Object $params -Name 'threadId')
    $turnId = [string](Get-PropertyValue -Object $params -Name 'turnId')
    $arguments = Get-PropertyValue -Object $params -Name 'arguments'
    $namespaceProperty = Get-Property -Object $params -Name 'namespace'
    if ($tool -cnotin $AllowedTools -or [string]::IsNullOrWhiteSpace($callId) -or
        [string]::IsNullOrWhiteSpace($threadId) -or [string]::IsNullOrWhiteSpace($turnId) -or
        -not (Test-IsObjectValue $arguments)) {
        throw 'item/tool/call request failed strict validation'
    }
    if ($threadId -cne $script:ActiveThreadId -or $turnId -cne $script:ActiveTurnId) {
        throw 'item/tool/call threadId/turnId does not match the active evaluation turn'
    }
    if ($null -ne $namespaceProperty -and $null -ne $namespaceProperty.Value) {
        throw 'item/tool/call namespace must be omitted or null'
    }
    $requestIdKey = Get-AppRequestIdKey $requestId
    if (-not $script:SeenAppRequestIds.Add($requestIdKey)) {
        throw 'duplicate app-server request id'
    }
    if (-not $script:SeenDynamicCallIds.Add($callId)) {
        throw 'duplicate dynamic callId'
    }
    Write-McmcpLiveMonitorTool -State $script:LiveMonitorState `
        -ToolName $tool -Status 'Started'

    $argumentsJson = ConvertTo-CompactJson $arguments
    $normalHttpTimeoutSeconds = Get-DynamicForwardTimeoutSeconds `
        -Tool $tool -Arguments $arguments
    $isDeadlineCleanupCancel = $script:Terminalizing -and
        $script:DeadlineCleanupCancelForwardCount -eq 0 -and
        $tool -ceq 'agent_cancel_action' -and
        (Test-DeadlineCleanupCancelArguments $arguments)
    $httpTimeoutSeconds = if ($isDeadlineCleanupCancel) {
        $DeadlineCleanupCancelTimeoutSeconds
    } else {
        $normalHttpTimeoutSeconds
    }
    $requiredHeadroomSeconds = $httpTimeoutSeconds + $TurnCompletionReserveSeconds
    $deadlineRejectionReason = if ($script:Terminalizing -and
        -not $isDeadlineCleanupCancel) {
        'terminalization_latched'
    } else {
        Wait-McmcpRequestSlot
        $null
    }
    $remainingSeconds = [int][Math]::Max(0, [Math]::Floor(
            ($script:TurnDeadline - [DateTimeOffset]::UtcNow).TotalSeconds))
    if (($script:Terminalizing -and -not $isDeadlineCleanupCancel) -or
        $remainingSeconds -le $requiredHeadroomSeconds) {
        if (-not $script:Terminalizing) {
            $script:Terminalizing = $true
            $deadlineRejectionReason = 'insufficient_deadline_headroom'
        }
        $script:DeadlineRejectionCount++
        $outputHash = Get-Sha256 $DeadlineRejectedOutputText
        Write-BridgeEvent ([ordered]@{
                event = 'dynamic_deadline_rejected'
                app_request_id = $requestId
                call_id = $callId
                thread_id = $threadId
                turn_id = $turnId
                tool = $tool
                arguments_sha256 = Get-Sha256 $argumentsJson
                reason = $deadlineRejectionReason
                remaining_seconds = $remainingSeconds
                forward_timeout_seconds = $httpTimeoutSeconds
                terminalization_reserve_seconds = $TurnCompletionReserveSeconds
                required_headroom_seconds = $requiredHeadroomSeconds
                success = $false
                output_sha256 = $outputHash
            })
        Send-AppMessage -Message ([ordered]@{
                id = $requestId
                result = [ordered]@{
                    success = $false
                    contentItems = @(
                        [ordered]@{ type = 'inputText'; text = $DeadlineRejectedOutputText }
                    )
                }
            })
        Write-BridgeEvent ([ordered]@{
                event = 'dynamic_response_sent'
                app_request_id = $requestId
                call_id = $callId
                tool = $tool
                success = $false
                output_sha256 = $outputHash
            })
        Write-McmcpLiveMonitorTool -State $script:LiveMonitorState `
            -ToolName $tool -Status 'Rejected'
        return
    }

    $mcpId = $script:McpRequestId + 1
    $forwardStart = [ordered]@{
        event = 'mcp_forward_started'
        app_request_id = $requestId
        call_id = $callId
        thread_id = $threadId
        turn_id = $turnId
        tool = $tool
        arguments_sha256 = Get-Sha256 $argumentsJson
        mcp_request_id = $mcpId
        http_timeout_seconds = $httpTimeoutSeconds
    }
    if ($isDeadlineCleanupCancel) {
        $script:DeadlineCleanupCancelForwardCount++
        $forwardStart.forward_mode = 'deadline_cleanup_cancel'
        $forwardStart.remaining_seconds = $remainingSeconds
        $forwardStart.terminalization_reserve_seconds = $TurnCompletionReserveSeconds
        $forwardStart.required_headroom_seconds = $requiredHeadroomSeconds
    }
    Write-BridgeEvent $forwardStart

    try {
        $mcpResponse = Invoke-McmcpJsonRpc -Method 'tools/call' -ToolName $tool `
            -TimeoutSeconds $httpTimeoutSeconds -PacingAlreadyApplied `
            -Parameters ([ordered]@{
                _meta = Get-McpMeta
                name = $tool
                arguments = $arguments
            })
        if ([DateTimeOffset]::UtcNow -ge $script:TurnDeadline) {
            throw (New-McmcpBridgeFailureException `
                    -FailureKind 'deadline' -DiagnosticCode 'turn_deadline_expired' `
                    -HttpStatus $null)
        }
    } catch {
        $failureKind = [string]$_.Exception.Data['failure_kind']
        $diagnosticCode = [string]$_.Exception.Data['diagnostic_code']
        $httpStatus = $_.Exception.Data['http_status']
        if ($failureKind -notin @(
                'http_status', 'transport', 'protocol_validation', 'deadline', 'internal') -or
            $diagnosticCode -notin @(
                'rate_limited', 'http_non_success', 'request_timeout',
                'http_request_failed', 'transport_unclassified',
                'invalid_content_type', 'invalid_jsonrpc_envelope',
                'turn_deadline_expired', 'unclassified_bridge_exception')) {
            $failureKind = 'internal'
            $diagnosticCode = 'unclassified_bridge_exception'
            $httpStatus = $null
        }
        Write-BridgeEvent ([ordered]@{
                event = 'mcp_forward_failed'
                app_request_id = $requestId
                call_id = $callId
                tool = $tool
                mcp_request_id = $mcpId
                failure_kind = $failureKind
                diagnostic_code = $diagnosticCode
                http_status = $(if ($null -eq $httpStatus) { $null } else { [int]$httpStatus })
            })
        Write-McmcpLiveMonitorTool -State $script:LiveMonitorState `
            -ToolName $tool -Status 'Failed'
        throw 'MCMCP dynamic bridge forwarding failed; see safe diagnostic record'
    }
    $formatted = Convert-McpResultToDynamicResponse -McpResponse $mcpResponse
    $outputHash = Get-Sha256 $formatted.text
    Write-BridgeEvent ([ordered]@{
            event = 'mcp_forward_completed'
            app_request_id = $requestId
            call_id = $callId
            tool = $tool
            mcp_request_id = $mcpId
            success = [bool]$formatted.success
            payload_mode = $formatted.mode
            output_sha256 = $outputHash
            jsonrpc_response_valid = $true
            mcp_is_error = [bool]$formatted.mcp_is_error
            domain_error_contract_valid = [bool]$formatted.domain_error_contract_valid
            structured_content_present = [bool]$formatted.structured_content_present
        })

    $response = [ordered]@{
        id = $requestId
        result = [ordered]@{
            success = [bool]$formatted.success
            contentItems = @(
                [ordered]@{ type = 'inputText'; text = $formatted.text }
            )
        }
    }
    Send-AppMessage -Message $response
    Write-BridgeEvent ([ordered]@{
            event = 'dynamic_response_sent'
            app_request_id = $requestId
            call_id = $callId
            tool = $tool
            success = [bool]$formatted.success
            output_sha256 = $outputHash
        })
    Write-McmcpLiveMonitorTool -State $script:LiveMonitorState `
        -ToolName $tool -Status 'Completed'
}

$sourceAuth = Join-Path (Join-Path `
        ([Environment]::GetFolderPath('UserProfile')) '.codex') 'auth.json'
$externalAuth = Get-ExternalAuthSecrets -AuthPath $sourceAuth
$script:AccessToken = [string]$externalAuth.access_token
$script:ChatgptAccountId = [string]$externalAuth.account_id
$script:AccessTokenExpiresAt = [DateTimeOffset]$externalAuth.expires_at
$externalAuth = $null
Assert-ExternalAuthLifetime -ExpiresAt $script:AccessTokenExpiresAt -Phase 'startup'
$originalAuthSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceAuth).Hash.ToLowerInvariant()

Assert-RunParameters
$validatedEndpointUri = [Uri]$Endpoint
$script:EvaluationControlEndpoint = 'http://127.0.0.1:{0}/mcp/internal/evaluation-turn' -f `
    $validatedEndpointUri.Port

$repoRoot = [IO.Path]::GetFullPath(
    [IO.Path]::Combine($PSScriptRoot, '..', '..')).TrimEnd('\', '/')
$catalogPath = [IO.Path]::Combine($repoRoot, 'docs', 'MCMCP_MCP_Tool_Catalog.json')
$script:PinnedCatalogSurface = Get-PinnedCatalogSurface -CatalogPath $catalogPath
$artifactPath = [IO.Path]::GetFullPath($ArtifactDirectory).TrimEnd('\', '/')
if ($artifactPath.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase) -or
    (Test-IsDescendantPath -Candidate $artifactPath -Parent $repoRoot) -or
    (Test-IsDescendantPath -Candidate $repoRoot -Parent $artifactPath)) {
    throw "ArtifactDirectory は repository と重ならない repo 外 directory が必須です: $artifactPath"
}
if (Test-Path -LiteralPath $artifactPath) {
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Container) -or
        @(Get-ChildItem -LiteralPath $artifactPath -Force).Count -ne 0) {
        throw "ArtifactDirectory は新規または空の directory である必要があります: $artifactPath"
    }
} else {
    [IO.Directory]::CreateDirectory($artifactPath) | Out-Null
}

$script:Bearer = ([IO.File]::ReadAllText([IO.Path]::GetFullPath($TokenPath))).Trim()
if ([string]::IsNullOrWhiteSpace($script:Bearer) -or $script:Bearer.Length -lt 16 -or
    $script:Bearer.Length -gt 4096 -or $script:Bearer -match '[\p{Cc}\p{Cf}]') {
    throw 'Bearer token file は16〜4096文字の単一非制御文字列である必要があります。'
}

$tracePath = Join-Path $artifactPath 'app-server-stdout.jsonl'
$stderrPath = Join-Path $artifactPath 'app-server-stderr.log'
$bridgePath = Join-Path $artifactPath 'bridge.jsonl'
$preflightPath = Join-Path $artifactPath 'preflight.json'
$finalMessagePath = Join-Path $artifactPath 'final-message.txt'
$auditPath = Join-Path $artifactPath 'audit.json'
$auditStderrPath = Join-Path $artifactPath 'audit-stderr.log'
$manifestPath = Join-Path $artifactPath 'manifest.json'
$liveMonitorLogPath = Join-Path $artifactPath 'live-monitor.log'
$auditScript = Join-Path $PSScriptRoot 'Test-McmcpEvalTrace.ps1'

$script:RunnerFailureEventWritten = $false
function Write-McmcpRunnerFailureEvent {
    if ($script:RunnerFailureEventWritten) { return }
    $reopened = $false
    if ([bool]$script:LiveMonitorState.Enabled -and
        $null -eq $script:LiveMonitorState.LogWriter) {
        Start-McmcpLiveMonitorLog -State $script:LiveMonitorState `
            -Path $liveMonitorLogPath -Append
        $reopened = $true
    }
    try {
        Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'runner_failed'
        $script:RunnerFailureEventWritten = $true
    } finally {
        if ($reopened) {
            Stop-McmcpLiveMonitorLog -State $script:LiveMonitorState
        }
    }
}

$commonDocuments = [Environment]::GetFolderPath('CommonDocuments')
if ([string]::IsNullOrWhiteSpace($commonDocuments)) {
    $commonDocuments = [IO.Path]::GetTempPath()
}
$isolatedBase = Join-Path $commonDocuments 'mcmcp-eval-tmp'
$isolatedRoot = $null
$codexHome = $null
$cleanCwd = $null
$originalAuthUnchanged = $false
$credentialFileAbsent = $false
$script:CodexProcess = $null
$script:RawWriter = $null
$script:BridgeWriter = $null
$stderrTask = $null
$tailTask = $null
$streamCaptureMarkers = [Collections.Generic.List[string]]::new()
$processExitConfirmed = $true
$script:McpRequestId = 0
$script:LastMcpRequestTimestamp = 0L
$script:BridgeSequence = 0
$script:BridgeSecretDetected = $false
$script:ActiveThreadId = $null
$script:ActiveTurnId = $null
$script:TurnDeadline = [DateTimeOffset]::MinValue
$script:Terminalizing = $false
$script:DeadlineRejectionCount = 0
$script:DeadlineCleanupCancelForwardCount = 0
$script:ReadinessFailure = $null
$script:RecoveryPreflight = $null
$script:EvaluationLeaseId = $null
$script:EvaluationLeaseIdSha256 = $null
$script:EvaluationLeaseAcquired = $false
$script:EvaluationLeaseTerminalObserved = $false
$script:EvaluationLeaseInputsReleased = $false
$script:EvaluationLeaseInputOwnerNone = $false
$script:EvaluationLeaseAllActionsTerminal = $false
$script:EvaluationLeaseProcessIdentityBound = $false
$script:EvaluationLeaseTerminalReason = $null
$script:EvaluationLeaseReleaseHttpConfirmed = $false
$script:EvaluationLeaseReleaseEventWritten = $false
$script:EvaluationLeaseReleaseInProgress = $false
$script:EvaluationLeaseClient = $null
$script:EvaluationLeaseResponse = $null
$script:EvaluationLeaseReader = $null
$script:EvaluationLeaseReadTask = $null
$script:EvaluationLeaseAcquiredAt = $null
$script:EvaluationLeaseReleasedAt = $null
$script:SeenAppRequestIds = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal)
$script:SeenDynamicCallIds = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal)
$startedAt = $null
$finishedAt = $null
$threadId = $null
$turnId = $null
$exitCode = $null
$runFailure = $null
$evaluationLeaseReleaseReason = 'runner_failure'
$auditPassed = $false
$secretLeakArtifacts = @()
$preflightResult = $null
$dynamicTools = @()
$finalMessages = [Collections.Generic.List[string]]::new()

try {
    Start-McmcpLiveMonitorLog -State $script:LiveMonitorState -Path $liveMonitorLogPath
    [IO.Directory]::CreateDirectory($isolatedBase) | Out-Null
    Assert-NoReparsePointInPath -Path $isolatedBase
    $isolatedRoot = Join-Path $isolatedBase ('mcmcp-eval-' + [Guid]::NewGuid().ToString('N'))
    $codexHome = Join-Path $isolatedRoot 'codex-home'
    $cleanCwd = Join-Path $isolatedRoot 'empty-cwd'
    [IO.Directory]::CreateDirectory($codexHome) | Out-Null
    [IO.Directory]::CreateDirectory($cleanCwd) | Out-Null
    Assert-NoReparsePointInPath -Path $isolatedRoot
    Assert-NoReparsePointInPath -Path $codexHome
    Assert-NoReparsePointInPath -Path $cleanCwd
    if ((Test-IsDescendantPath -Candidate $codexHome -Parent $artifactPath) -or
        (Test-IsDescendantPath -Candidate $artifactPath -Parent $isolatedRoot)) {
        throw 'isolated CODEX_HOME and artifact directory must not overlap'
    }
    Assert-NoProjectCodexConfig -Workspace $cleanCwd
    if (Test-Path -LiteralPath (Join-Path $codexHome 'config.toml')) {
        throw 'isolated CODEX_HOME must not contain config.toml'
    }
    if (Test-Path -LiteralPath (Join-Path $codexHome 'auth.json')) {
        throw 'isolated CODEX_HOME must not contain a credential file'
    }

    $processStart = [Diagnostics.ProcessStartInfo]::new()
    $processStart.FileName = $script:CodexExecutable
    $processStart.WorkingDirectory = $cleanCwd
    $processStart.UseShellExecute = $false
    $processStart.RedirectStandardInput = $true
    $processStart.RedirectStandardOutput = $true
    $processStart.RedirectStandardError = $true
    $processStart.StandardInputEncoding = $Utf8NoBom
    $processStart.StandardOutputEncoding = $Utf8NoBom
    $processStart.StandardErrorEncoding = $Utf8NoBom
    $processStart.ArgumentList.Add('app-server')
    $processStart.ArgumentList.Add('--stdio')
    $processStart.ArgumentList.Add('--strict-config')
    foreach ($feature in $DisabledFeatures) {
        $processStart.ArgumentList.Add('--disable')
        $processStart.ArgumentList.Add($feature)
    }
    $cliConfigs = @(
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
    foreach ($config in $cliConfigs) {
        $processStart.ArgumentList.Add('--config')
        $processStart.ArgumentList.Add($config)
    }

    foreach ($key in @($processStart.Environment.Keys)) {
        if (Test-IsForbiddenChildEnvironmentName $key) {
            $processStart.Environment.Remove($key) | Out-Null
        }
    }
    foreach ($key in @($processStart.Environment.Keys)) {
        $value = [string]$processStart.Environment[$key]
        if (Test-ContainsEvaluationSecret $value) {
            $processStart.Environment.Remove($key) | Out-Null
        }
    }
    $processStart.Environment['CODEX_HOME'] = $codexHome
    $childSecretValueCount = @($processStart.Environment.GetEnumerator() | Where-Object {
            Test-ContainsEvaluationSecret ([string]$_.Value)
        }).Count

    $script:RawWriter = [IO.StreamWriter]::new($tracePath, $false, $Utf8NoBom)
    $script:BridgeWriter = [IO.StreamWriter]::new($bridgePath, $false, $Utf8NoBom)
    $script:CodexProcess = [Diagnostics.Process]::new()
    $script:CodexProcess.StartInfo = $processStart
    if (-not $script:CodexProcess.Start()) { throw 'Codex app-server processを開始できませんでした。' }
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'app_server_started'
    $processExitConfirmed = $false
    $stderrTask = $script:CodexProcess.StandardError.ReadToEndAsync()
    Write-BridgeEvent ([ordered]@{
            event = 'launcher_config'
            codex_version = $script:ValidatedCodexVersion
            stdio = $true
            strict_config = $true
            disabled_features = @($DisabledFeatures)
            cli_configs = @($cliConfigs)
            isolated_codex_home = $true
            isolated_empty_cwd = $true
            external_auth_ephemeral = $true
            credential_file_created = $false
            tool_surface_pinned = $true
            clean_cwd_ancestor_config_absent = $true
            isolated_home_config_absent = $true
            isolated_path_reparse_points_absent = $true
            child_mcmcp_env_count = @($processStart.Environment.Keys | Where-Object {
                    $_ -match '(?i)^MCMCP'
                }).Count
            child_sensitive_env_count = @($processStart.Environment.Keys | Where-Object {
                    $_ -match '(?i)(TOKEN|BEARER|SECRET|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|PASSWORD|CREDENTIAL)'
                }).Count
            child_forbidden_env_count = @($processStart.Environment.Keys | Where-Object {
                    $_ -ne 'CODEX_HOME' -and (Test-IsForbiddenChildEnvironmentName $_)
                }).Count
            child_secret_value_count = $childSecretValueCount
        })

    $initialize = [ordered]@{
        method = 'initialize'
        id = 'init'
        params = [ordered]@{
            clientInfo = [ordered]@{ name = 'mcmcp-fresh-eval'; version = '1' }
            capabilities = [ordered]@{
                experimentalApi = $true
                optOutNotificationMethods = $OptOutNotifications
            }
        }
    }
    Send-AppMessage -Message $initialize -AuditKind 'initialize'
    $setupDeadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    $initResponse = Wait-AppResponse -Id 'init' -Deadline $setupDeadline
    Write-BridgeEvent ([ordered]@{
            event = 'app_response_received'
            request_id = 'init'
            response_ok = $true
            contract_valid = $true
            raw_artifact_recorded = $true
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'initialized'
    $initResponse = $null
    Send-AppMessage -Message ([ordered]@{ method = 'initialized'; params = [ordered]@{} }) `
        -AuditKind 'initialized'

    Assert-ExternalAuthLifetime -ExpiresAt $script:AccessTokenExpiresAt -Phase 'login'
    $loginRequest = [ordered]@{
        method = 'account/login/start'
        id = 'login'
        params = [ordered]@{
            type = 'chatgptAuthTokens'
            accessToken = $script:AccessToken
            chatgptAccountId = $script:ChatgptAccountId
            chatgptPlanType = $null
        }
    }
    # This secret-bearing request is intentionally never written to raw/bridge artifacts.
    Send-AppMessage -Message $loginRequest
    $loginRequest = $null
    $loginResponse = Wait-AppResponse -Id 'login' -Deadline $setupDeadline
    $loginResult = Get-PropertyValue $loginResponse 'result'
    if ((ConvertTo-SemanticCanonicalJson $loginResult) -cne
        (ConvertTo-SemanticCanonicalJson ([ordered]@{ type = 'chatgptAuthTokens' }))) {
        throw 'external ChatGPT token login did not return the expected success response'
    }
    Write-BridgeEvent ([ordered]@{
            event = 'app_response_received'
            request_id = 'login'
            response_ok = $true
            contract_valid = $true
            raw_artifact_recorded = $true
        })
    Write-BridgeEvent ([ordered]@{
            event = 'external_auth_login_ok'
            request_id = 'login'
            auth_type = 'chatgptAuthTokens'
            credential_file_created = $false
            jwt_lifetime_guard_ok = $true
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'login_ok'
    $loginResponse = $null
    $loginResult = $null

    $configRequest = [ordered]@{
        method = 'config/read'
        id = 'config'
        params = [ordered]@{
            includeLayers = $true
            cwd = $cleanCwd
        }
    }
    # Effective config may contain private paths or server definitions. Never persist this exchange.
    Send-AppMessage -Message $configRequest
    $configRequest = $null
    $configResponse = Wait-AppResponse -Id 'config' -Deadline $setupDeadline `
        -SuppressRawResponse
    $effectiveConfigProof = Get-EffectiveConfigProof -Response $configResponse `
        -ExpectedCwd $cleanCwd
    Write-BridgeEvent ([ordered]@{
            event = 'effective_config_checked'
            request_id = $effectiveConfigProof.request_id
            include_layers = $effectiveConfigProof.include_layers
            cwd_is_clean = $effectiveConfigProof.cwd_is_clean
            mcp_servers_object = $effectiveConfigProof.mcp_servers_object
            mcp_server_count = $effectiveConfigProof.mcp_server_count
            raw_artifact_recorded = $effectiveConfigProof.raw_artifact_recorded
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'config_ok'
    $configResponse = $null

    $preflightResult = Invoke-ReadOnlyPreflight
    $preflightResult.artifact['effective_config_read_ok'] = $true
    $preflightResult.artifact['effective_mcp_servers_object'] = $true
    $preflightResult.artifact['effective_mcp_server_count'] = 0
    $dynamicTools = @($preflightResult.dynamic_tools)
    $preflightArtifactJson = $preflightResult.artifact | ConvertTo-Json -Depth 20
    Assert-NoEvaluationSecretForArtifactText -Text $preflightArtifactJson
    [IO.File]::WriteAllText($preflightPath, $preflightArtifactJson, $Utf8NoBom)
    Write-BridgeEvent ([ordered]@{
            event = 'preflight'
            protocol_version = $preflightResult.artifact.protocol_version
            discover_ok = $preflightResult.artifact.discover_ok
            discover_contract_ok = $preflightResult.artifact.discover_contract_ok
            list_contract_ok = $preflightResult.artifact.list_contract_ok
            discover_semantic_exact = $preflightResult.artifact.discover_semantic_exact
            list_semantic_exact = $preflightResult.artifact.list_semantic_exact
            jsonrpc_envelopes_ok = $preflightResult.artifact.jsonrpc_envelopes_ok
            http_content_type_ok = $preflightResult.artifact.http_content_type_ok
            server_info_ok = $preflightResult.artifact.server_info_ok
            direct_fallback_config_absent = $true
            direct_fallback_path_reparse_absent = $true
            effective_config_read_ok = $true
            effective_mcp_servers_object = $true
            effective_mcp_server_count = 0
            listed_tools = @($preflightResult.artifact.listed_tools)
            dynamic_tools_sha256 = $preflightResult.artifact.dynamic_tools_sha256
            catalog_file_sha256 = $preflightResult.artifact.catalog_file_sha256
            expected_tool_surface_sha256 = $preflightResult.artifact.expected_tool_surface_sha256
            live_tool_surface_sha256 = $preflightResult.artifact.live_tool_surface_sha256
            tool_surface_match = $preflightResult.artifact.tool_surface_match
            get_state_ok = $preflightResult.artifact.get_state_ok
            ready_mode_ok = $preflightResult.artifact.ready_mode_ok
            game_unpaused = $preflightResult.artifact.game_unpaused
            world_present = $preflightResult.artifact.world_present
            observation_present = $preflightResult.artifact.observation_present
            inventory_empty = $preflightResult.artifact.inventory_empty
            rays_per_tick_512 = $preflightResult.artifact.rays_per_tick_512
            visible_entities_zero = $preflightResult.artifact.visible_entities_zero
            action_idle_or_terminal = $preflightResult.artifact.action_idle_or_terminal
            gameplay_calls_made = $preflightResult.artifact.gameplay_calls_made
            parent_mcp_no_proxy = $true
            parent_mcp_redirects_disabled = $true
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'preflight_ok'
    $effectiveConfigProof = $null
    $setupDeadline = [DateTimeOffset]::UtcNow.AddSeconds(60)

    $threadStart = [ordered]@{
        method = 'thread/start'
        id = 'thread'
        params = [ordered]@{
            model = $Model
            cwd = $cleanCwd
            approvalPolicy = 'never'
            sandbox = 'read-only'
            personality = 'none'
            ephemeral = $true
            environments = @()
            runtimeWorkspaceRoots = @()
            dynamicTools = $dynamicTools
            config = Get-ThreadConfig
        }
    }
    Send-AppMessage -Message $threadStart -AuditKind 'thread_start'
    $threadResponse = Wait-AppResponse -Id 'thread' -Deadline $setupDeadline
    $threadResult = Get-PropertyValue -Object $threadResponse -Name 'result'
    $threadObject = Get-PropertyValue -Object $threadResult -Name 'thread'
    $threadId = [string](Get-PropertyValue -Object $threadObject -Name 'id')
    if ([string]::IsNullOrWhiteSpace($threadId) -or
        -not (Test-IsObjectValue $threadObject) -or
        (Get-PropertyValue $threadObject 'ephemeral') -isnot [bool] -or
        -not (Get-PropertyValue $threadObject 'ephemeral') -or
        (Get-PropertyValue $threadObject 'cwd') -cne $cleanCwd -or
        (Get-PropertyValue $threadObject 'modelProvider') -cne 'openai') {
        throw 'thread/start returned an invalid effective thread'
    }
    $instructionSources = Get-Property -Object $threadResult -Name 'instructionSources'
    $runtimeRoots = Get-Property -Object $threadResult -Name 'runtimeWorkspaceRoots'
    $effectiveSandbox = Get-PropertyValue $threadResult 'sandbox'
    if ((Get-PropertyValue $threadResult 'model') -cne $Model -or
        (Get-PropertyValue $threadResult 'cwd') -cne $cleanCwd -or
        (Get-PropertyValue $threadResult 'approvalPolicy') -cne 'never' -or
        (Get-PropertyValue $threadResult 'approvalsReviewer') -cne 'user' -or
        (Get-PropertyValue $threadResult 'modelProvider') -cne 'openai' -or
        -not (Test-IsObjectValue $effectiveSandbox) -or
        (Get-PropertyValue $effectiveSandbox 'type') -cne 'readOnly' -or
        (Get-PropertyValue $effectiveSandbox 'networkAccess') -isnot [bool] -or
        (Get-PropertyValue $effectiveSandbox 'networkAccess') -or
        (Get-PropertyValue $threadResult 'reasoningEffort') -cne $ReasoningEffort -or
        $null -eq $instructionSources -or
        -not (Test-IsArrayValue $instructionSources.Value) -or
        @($instructionSources.Value).Count -ne 0 -or
        $null -eq $runtimeRoots -or -not (Test-IsArrayValue $runtimeRoots.Value) -or
        @($runtimeRoots.Value).Count -ne 0) {
        throw 'thread/start effective response does not match the isolated evaluation contract'
    }

    Write-BridgeEvent ([ordered]@{
            event = 'app_response_received'
            request_id = 'thread'
            response_ok = $true
            contract_valid = $true
            raw_artifact_recorded = $true
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'thread_ready'
    $preliminaryT0Readiness = Invoke-McmcpReadinessCheck -Phase 'preliminary'
    Assert-ExternalAuthLifetime -ExpiresAt $script:AccessTokenExpiresAt -Phase 'T0'
    Start-EvaluationTurnLease
    # This is the authoritative readiness proof. Invoke-McmcpJsonRpc adds the
    # acquired lease id header; the earlier preflight/preliminary calls remain
    # intentionally headerless.
    $t0Readiness = Invoke-McmcpReadinessCheck -Phase 'T0'
    if ($PromptProfile -ceq 'container-inspect-recovery') {
        $script:RecoveryPreflight = New-McmcpRecoveryPreflight `
            -ProductCommit $ProductCommit -ExpectedBuildJarSha256 $ExpectedBuildJarSha256 `
            -BuildJarPath $BuildJarPath -InstalledJarPath $InstalledJarPath `
            -OptionsPath $OptionsPath -BaselineId $BaselineId -ExpectedMaxFps $ExpectedMaxFps
    }
    Assert-EvaluationLeaseActiveBeforeT0
    $startedAt = [DateTimeOffset]::UtcNow
    $deadline = $startedAt.Add($EvaluatorTimeout)
    $script:TurnDeadline = $deadline
    Write-BridgeEvent ([ordered]@{
            event = 't0'
            utc = $startedAt.ToString('o')
            prompt_profile = $PromptProfile
            recovery_preflight = $script:RecoveryPreflight
            prompt_sha256 = Get-Sha256 $ProductionPrompt
            timeout_seconds = [int]$EvaluatorTimeout.TotalSeconds
            preliminary_readiness_passed = $null -ne $preliminaryT0Readiness
            evaluation_lease_header_bound = $script:EvaluationLeaseAcquired
            readiness_rechecked = $true
            ready_mode_ok = $t0Readiness.ready_mode_ok
            game_unpaused = $t0Readiness.game_unpaused
            world_present = $t0Readiness.world_present
            observation_present = $t0Readiness.observation_present
            inventory_empty = $t0Readiness.inventory_empty
            rays_per_tick_512 = $t0Readiness.rays_per_tick_512
            visible_entities_zero = $t0Readiness.visible_entities_zero
            action_idle_or_terminal = $t0Readiness.action_idle_or_terminal
        })
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 't0'
    $preliminaryT0Readiness = $null
    $t0Readiness = $null
    $turnStart = [ordered]@{
        method = 'turn/start'
        id = 'turn'
        params = [ordered]@{
            threadId = $threadId
            input = @([ordered]@{ type = 'text'; text = $ProductionPrompt })
            model = $Model
            effort = $ReasoningEffort
            summary = 'detailed'
            cwd = $cleanCwd
            environments = @()
        }
    }
    Send-AppMessage -Message $turnStart -AuditKind 'turn_start'
    $turnResponse = Wait-AppResponse -Id 'turn' -Deadline $deadline
    $turnResult = Get-PropertyValue -Object $turnResponse -Name 'result'
    $turnObject = Get-PropertyValue -Object $turnResult -Name 'turn'
    $turnId = [string](Get-PropertyValue -Object $turnObject -Name 'id')
    if ([string]::IsNullOrWhiteSpace($turnId)) { throw 'turn/start returned no turn id' }
    Write-BridgeEvent ([ordered]@{
            event = 'app_response_received'
            request_id = 'turn'
            response_ok = $true
            contract_valid = $true
            raw_artifact_recorded = $true
        })
    $script:ActiveThreadId = $threadId
    $script:ActiveTurnId = $turnId
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'turn_started'

    $turnCompleted = $false
    while (-not $turnCompleted) {
        try {
            $message = Read-AppLine -Deadline $deadline
        } catch {
            if ($_.Exception.Message -ceq 'app-server response timeout' -and
                [DateTimeOffset]::UtcNow -ge $deadline) {
                throw 'evaluation deadline expired before turn/completed'
            }
            throw
        }
        $methodProperty = Get-Property -Object $message -Name 'method'
        $idProperty = Get-Property -Object $message -Name 'id'
        if ($null -ne $methodProperty -and $null -ne $idProperty) {
            if ([string]$methodProperty.Value -cne 'item/tool/call') {
                throw "forbidden app-server request: $($methodProperty.Value)"
            }
            Invoke-DynamicBridge -Request $message
            continue
        }
        if ($null -ne $methodProperty) {
            $method = [string]$methodProperty.Value
            if ($method -eq 'item/completed') {
                $item = Get-PropertyValue -Object (
                    Get-PropertyValue -Object $message -Name 'params') -Name 'item'
                if ((Get-PropertyValue -Object $item -Name 'type') -eq 'agentMessage') {
                    $finalMessages.Add([string](Get-PropertyValue -Object $item -Name 'text'))
                    if ((Get-PropertyValue -Object $item -Name 'phase') -ceq 'commentary') {
                        $commentaryText = [string](Get-PropertyValue -Object $item -Name 'text')
                        if (-not (Test-ContainsEvaluationSecret $commentaryText)) {
                            Write-McmcpLiveMonitorText -State $script:LiveMonitorState `
                                -Kind 'Commentary' -Text $commentaryText
                        }
                    }
                } elseif ((Get-PropertyValue -Object $item -Name 'type') -eq 'reasoning') {
                    $summaryParts = @(
                        @(Get-PropertyValue -Object $item -Name 'summary') |
                        Where-Object { $_ -is [string] }
                    )
                    $summaryText = $summaryParts -join "`n"
                    if (-not (Test-ContainsEvaluationSecret $summaryText)) {
                        Write-McmcpLiveMonitorText -State $script:LiveMonitorState `
                            -Kind 'ReasoningSummary' -Text $summaryText
                    }
                }
            } elseif ($method -eq 'turn/completed') {
                $completedTurn = Get-PropertyValue -Object (
                    Get-PropertyValue -Object $message -Name 'params') -Name 'turn'
                $completedId = [string](Get-PropertyValue -Object $completedTurn -Name 'id')
                $completedStatus = [string](Get-PropertyValue -Object $completedTurn -Name 'status')
                $completedError = Get-Property -Object $completedTurn -Name 'error'
                if ($completedId -ne $turnId -or $completedStatus -ne 'completed' -or
                    $null -eq $completedError -or $null -ne $completedError.Value) {
                    throw "turn terminal mismatch: id=$completedId status=$completedStatus"
                }
                $turnCompleted = $true
                Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState `
                    -Event 'turn_completed'
            }
            continue
        }
        if ($null -ne $idProperty) {
            throw "unexpected app-server response id=$($idProperty.Value)"
        }
        throw 'unclassified app-server message'
    }

    $evaluationLeaseReleaseReason = 'turn_completed'
    Stop-EvaluationTurnLease -Reason $evaluationLeaseReleaseReason
    if (-not $script:EvaluationLeaseReleaseHttpConfirmed -or
        -not $script:EvaluationLeaseTerminalObserved -or
        -not $script:EvaluationLeaseInputsReleased -or
        -not $script:EvaluationLeaseInputOwnerNone -or
        -not $script:EvaluationLeaseAllActionsTerminal -or
        -not $script:EvaluationLeaseProcessIdentityBound -or
        $script:EvaluationLeaseTerminalReason -cne 'turn_completed') {
        throw 'evaluation lease normal terminal proof mismatch'
    }
    $script:CodexProcess.StandardInput.Close()
    $tailTask = $script:CodexProcess.StandardOutput.ReadToEndAsync()
    if (-not $script:CodexProcess.WaitForExit(15000)) {
        $script:CodexProcess.Kill($true)
        if (-not $script:CodexProcess.WaitForExit(5000)) {
            $streamCaptureMarkers.Add('[process exit not confirmed after kill]')
            throw 'Codex app-server did not exit after bounded kill wait'
        }
    }
    $processExitConfirmed = $true
    Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'process_exited'
    if (-not $tailTask.Wait(5000)) {
        $streamCaptureMarkers.Add('[stdout tail capture timed out]')
        throw 'Codex app-server stdout tail did not complete in bounded wait'
    }
    $tail = $tailTask.GetAwaiter().GetResult()
    Write-ValidatedAppServerTail -Tail $tail
    $exitCode = $script:CodexProcess.ExitCode
    $finishedAt = [DateTimeOffset]::UtcNow
    $finalText = if ($finalMessages.Count -gt 0) {
        $finalMessages[$finalMessages.Count - 1]
    } else { '' }
    Assert-NoEvaluationSecretForArtifactText -Text $finalText
    [IO.File]::WriteAllText($finalMessagePath, $finalText, $Utf8NoBom)
} catch {
    $runFailure = $_.Exception.Message
    if ($runFailure -match '(?i)evaluation deadline|deadline expired') {
        $evaluationLeaseReleaseReason = 'evaluation_deadline'
    }
    $finishedAt = [DateTimeOffset]::UtcNow
    Write-McmcpRunnerFailureEvent
} finally {
    if ($script:EvaluationLeaseAcquired -and
        -not $script:EvaluationLeaseReleaseHttpConfirmed) {
        try {
            Stop-EvaluationTurnLease -Reason $evaluationLeaseReleaseReason
        } catch {
            $leaseFailure = 'evaluation input lease release was not confirmed'
            $runFailure = if ($runFailure) {
                "$runFailure; $leaseFailure"
            } else { $leaseFailure }
        }
    }
    Close-EvaluationLeaseTransport
    if ($null -ne $script:CodexProcess) {
        try {
            if (-not $script:CodexProcess.HasExited) {
                $script:CodexProcess.Kill($true)
                if (-not $script:CodexProcess.WaitForExit(5000)) {
                    $streamCaptureMarkers.Add('[process exit not confirmed in finally]')
                    if (-not $runFailure) {
                        $runFailure = 'Codex app-server exit was not confirmed in bounded teardown'
                    }
                }
            }
            if ($script:CodexProcess.HasExited) {
                $processExitConfirmed = $true
                $exitCode = $script:CodexProcess.ExitCode
            }
        } catch {
            $streamCaptureMarkers.Add('[process teardown failed]')
            if (-not $runFailure) { $runFailure = 'Codex app-server bounded teardown failed' }
        }
        try { $script:CodexProcess.Dispose() } catch { }
        $script:CodexProcess = $null
    }
    if ($null -ne $script:RawWriter) {
        try { $script:RawWriter.Dispose() } catch { }
        $script:RawWriter = $null
    }
    if ($null -ne $script:BridgeWriter) {
        try { $script:BridgeWriter.Dispose() } catch { }
        $script:BridgeWriter = $null
    }
    if ($null -ne $stderrTask) {
        $stderrText = ''
        try {
            if ($stderrTask.Wait(5000)) {
                $stderrText = [string]$stderrTask.GetAwaiter().GetResult()
            } else {
                $streamCaptureMarkers.Add('[stderr capture timed out]')
                if (-not $runFailure) { $runFailure = 'stderr capture exceeded bounded wait' }
            }
        } catch {
            $streamCaptureMarkers.Add('[stderr capture failed]')
            if (-not $runFailure) { $runFailure = 'stderr capture failed' }
        }
        $markerText = $streamCaptureMarkers -join [Environment]::NewLine
        if (-not [string]::IsNullOrEmpty($markerText)) {
            if (-not [string]::IsNullOrEmpty($stderrText)) {
                $stderrText += [Environment]::NewLine
            }
            $stderrText += $markerText
        }
        try {
            Assert-NoEvaluationSecretForArtifactText -Text $stderrText
            [IO.File]::WriteAllText($stderrPath, $stderrText, $Utf8NoBom)
        } catch {
            if (-not $runFailure) { $runFailure = 'stderr artifact write failed' }
        }
    }
    try {
        if (-not $processExitConfirmed) {
            throw 'isolated CODEX_HOME retained because child exit was not confirmed'
        }
        if (-not [string]::IsNullOrWhiteSpace($isolatedRoot) -and
            [IO.Directory]::Exists($isolatedRoot)) {
            if (-not (Test-Path -LiteralPath $sourceAuth -PathType Leaf)) {
                throw 'pre-cleanup verification: original auth.json disappeared'
            }
            $currentAuthSha256 = (Get-FileHash -Algorithm SHA256 `
                    -LiteralPath $sourceAuth).Hash.ToLowerInvariant()
            if ($currentAuthSha256 -cne $originalAuthSha256) {
                throw 'pre-cleanup verification: original auth.json changed during evaluation'
            }
            $originalAuthUnchanged = $true
            if ((Test-Path -LiteralPath (Join-Path $codexHome 'auth.json')) -or
                (Test-Path -LiteralPath (Join-Path $codexHome 'config.toml'))) {
                throw 'pre-cleanup verification: isolated CODEX_HOME contains credential/config file'
            }
            $credentialFileAbsent = $true
        }
    } catch {
        if (-not $runFailure) { $runFailure = $_.Exception.Message }
    } finally {
        if ($processExitConfirmed) {
            try {
                Remove-IsolatedRoot -Root $isolatedRoot -Base $isolatedBase
            } catch {
                if (-not $runFailure) { $runFailure = $_.Exception.Message }
            }
        }
    }
}

try {
    if ((Test-Path -LiteralPath $tracePath -PathType Leaf) -and
        (Test-Path -LiteralPath $bridgePath -PathType Leaf)) {
        Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'audit_started'
        $powerShellExecutable = (Get-Process -Id $PID).Path
        & $powerShellExecutable -NoProfile -File $auditScript `
            -TracePath $tracePath -BridgeLogPath $bridgePath -OutputPath $auditPath `
            -ExpectedModel $Model -ExpectedEffort $ReasoningEffort `
            -ExpectedPromptProfile $PromptProfile 2> $auditStderrPath
        $auditPassed = ($LASTEXITCODE -eq 0)
        Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState `
            -Event $(if ($auditPassed) { 'audit_passed' } else { 'audit_failed' })
    }
    Stop-McmcpLiveMonitorLog -State $script:LiveMonitorState

    $gitCommit = $null
    $gitWorktreeDirty = $null
    $gitCommand = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
    if ($gitCommand) {
        $resolvedGitCommit = (& $gitCommand.Source -C $repoRoot rev-parse HEAD 2>$null |
                Select-Object -First 1)
        $gitCommitExitCode = $LASTEXITCODE
        $gitStatus = @(& $gitCommand.Source -C $repoRoot status --porcelain=v1 2>$null)
        if ($gitCommitExitCode -eq 0 -and $LASTEXITCODE -eq 0 -and
                $resolvedGitCommit -cmatch '^[0-9a-f]{40}([0-9a-f]{24})?$') {
            $gitCommit = $resolvedGitCommit
            $gitWorktreeDirty = ($gitStatus.Count -gt 0)
        }
    }
    $manifest = [ordered]@{
        schema_version = 9
        baseline_id = $BaselineId
        recovery_preflight = $script:RecoveryPreflight
        model = $Model
        reasoning_effort = $ReasoningEffort
        prompt_profile = $PromptProfile
        prompt_sha256 = Get-Sha256 $ProductionPrompt
        prompt_delivery = 'app_server_stdio_jsonl_turn_start_exact_text'
        evaluator_timeout_seconds = [int]$EvaluatorTimeout.TotalSeconds
        terminalization_reserve_seconds = $TurnCompletionReserveSeconds
        deadline_rejection_count = $script:DeadlineRejectionCount
        deadline_cleanup_cancel_forward_count = $script:DeadlineCleanupCancelForwardCount
        terminalizing_entered = $script:Terminalizing
        evaluation_lease = [ordered]@{
            acquired = $script:EvaluationLeaseAcquired
            lease_id_sha256 = $script:EvaluationLeaseIdSha256
            maximum_duration_ms = [long][Math]::Ceiling(
                $EvaluationLeaseMaximumDuration.TotalMilliseconds)
            acquired_utc = $(if ($script:EvaluationLeaseAcquiredAt) {
                    $script:EvaluationLeaseAcquiredAt.ToString('o')
                } else { $null })
            released_utc = $(if ($script:EvaluationLeaseReleasedAt) {
                    $script:EvaluationLeaseReleasedAt.ToString('o')
                } else { $null })
            terminal_reason = $script:EvaluationLeaseTerminalReason
            requested_release_reason = $evaluationLeaseReleaseReason
            inputs_released = $script:EvaluationLeaseInputsReleased
            input_owner_none = $script:EvaluationLeaseInputOwnerNone
            all_actions_terminal = $script:EvaluationLeaseAllActionsTerminal
            release_http_confirmed = $script:EvaluationLeaseReleaseHttpConfirmed
            process_identity_bound = $script:EvaluationLeaseProcessIdentityBound
        }
        reasoning_summary = 'detailed_completed_items_only'
        raw_reasoning_delta = 'opted_out_and_forbidden_by_audit'
        live_monitor_persistence = $(if ($LiveMonitor) {
                'artifact_live-monitor.log_exact_display'
            } else { 'disabled' })
        mcp_protocol_version = $ModernProtocolVersion
        bridge = 'codex_app_server_dynamic_tools_direct_mcp'
        model_visible_tools = @($AllowedTools)
        model_visible_tool_count = $AllowedTools.Count
        t0_utc = $(if ($startedAt) { $startedAt.ToString('o') } else { $null })
        finished_utc = $(if ($finishedAt) { $finishedAt.ToString('o') } else { $null })
        thread_id = $threadId
        turn_id = $turnId
        codex_exit_code = $exitCode
        trace_audit_passed = $auditPassed
        runner_failure = $runFailure
        readiness_failure = $script:ReadinessFailure
        bridge_secret_blocked = $script:BridgeSecretDetected
        git_commit = $gitCommit
        git_worktree_dirty = $gitWorktreeDirty
        runner_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
        audit_script_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $auditScript).Hash.ToLowerInvariant()
        recovery_preflight_script_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot 'McmcpRecoveryPreflight.ps1')).Hash.ToLowerInvariant()
        recovery_witness_module_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot 'McmcpRecoveryWitness.psm1')).Hash.ToLowerInvariant()
        monitor_module_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $monitorModulePath).Hash.ToLowerInvariant()
        monitor_test_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $monitorTestPath).Hash.ToLowerInvariant()
        monitor_launcher_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $monitorLauncherPath).Hash.ToLowerInvariant()
        monitor_host_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $monitorHostPath).Hash.ToLowerInvariant()
        codex_version = $script:ValidatedCodexVersion
        isolated_codex_home_cleaned = -not [IO.Directory]::Exists([string]$isolatedRoot)
        original_auth_unchanged = $originalAuthUnchanged
        credential_file_created = -not $credentialFileAbsent
        auth_transport = 'external_chatgpt_tokens_ephemeral_memory_only'
        bearer_child_environment = 'absent'
        secret_persisted = $false
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 20
    Assert-NoEvaluationSecretForArtifactText -Text $manifestJson
    [IO.File]::WriteAllText($manifestPath, $manifestJson, $Utf8NoBom)
} catch {
    Stop-McmcpLiveMonitorLog -State $script:LiveMonitorState
    if (-not $runFailure) { $runFailure = "artifact post-processing failed: $($_.Exception.Message)" }
}

$secretScanFailure = $null
try {
    $secretLeakArtifacts = @(Protect-ArtifactTreeFromSecrets -Secrets ([ordered]@{
                MCMCP_BEARER = $script:Bearer
                CODEX_ACCESS_TOKEN = $script:AccessToken
                CHATGPT_ACCOUNT_ID = $script:ChatgptAccountId
                EVALUATION_LEASE = $script:EvaluationLeaseId
            }) -Root $artifactPath)
} catch {
    $secretScanFailure = 'artifact secret scan did not complete'
} finally {
    $script:Bearer = $null
    $script:AccessToken = $null
    $script:ChatgptAccountId = $null
    $script:AccessTokenExpiresAt = $null
    $script:EvaluationLeaseId = $null
}
if ($secretScanFailure) {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine($secretScanFailure)
    exit 3
}
if ($secretLeakArtifacts.Count -gt 0) {
    if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
        $manifestObject = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $manifestObject.secret_persisted = $true
        $manifestObject | Add-Member -NotePropertyName secret_leak_artifacts `
            -NotePropertyValue @($secretLeakArtifacts | ForEach-Object {
                    [IO.Path]::GetFileName($_)
                }) -Force
        [IO.File]::WriteAllText(
            $manifestPath,
            ($manifestObject | ConvertTo-Json -Depth 20),
            $Utf8NoBom)
    }
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine('Evaluation secret artifact leak was detected and redacted; run is invalid.')
    exit 3
}
if ($script:BridgeSecretDetected) {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine('MCMCP result secret filter fired; run is invalid.')
    exit 3
}
if ($runFailure) {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine("評価 runner が失敗しました: $runFailure")
    exit 3
}
if ($exitCode -ne 0) {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine("Codex app-server exited with code $exitCode")
    exit 3
}
if (-not $auditPassed) {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine('strict trace audit failed; run is invalid.')
    exit 2
}
if (-not $script:EvaluationLeaseAcquired -or
    -not $script:EvaluationLeaseReleaseHttpConfirmed -or
    -not $script:EvaluationLeaseTerminalObserved -or
    -not $script:EvaluationLeaseInputsReleased -or
    -not $script:EvaluationLeaseInputOwnerNone -or
    -not $script:EvaluationLeaseAllActionsTerminal -or
    -not $script:EvaluationLeaseProcessIdentityBound -or
    $script:EvaluationLeaseTerminalReason -cne 'turn_completed' -or
    $evaluationLeaseReleaseReason -cne 'turn_completed') {
    Write-McmcpRunnerFailureEvent
    [Console]::Error.WriteLine('evaluation lease normal terminal proof failed; run is invalid.')
    exit 3
}

Start-McmcpLiveMonitorLog -State $script:LiveMonitorState `
    -Path $liveMonitorLogPath -Append
Write-McmcpLiveMonitorFixed -State $script:LiveMonitorState -Event 'runner_completed'
Stop-McmcpLiveMonitorLog -State $script:LiveMonitorState
if (-not $LiveMonitor) {
    Write-Host "評価終了: $artifactPath"
}
exit 0
