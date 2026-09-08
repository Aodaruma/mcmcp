[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Loading {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "capability gate loading test failed: $Message" }
}

# 共通支援のトップレベルでは定義・初期化・固定ファイル読込だけを許す。
# 関数本体内の通信は既存mockテストで扱い、ここでは実行しない。
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'McmcpCapabilityGate*.ps1') {
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $file.FullName, [ref]$null, [ref]$errors)
    Assert-Loading ($errors.Count -eq 0) "$($file.Name) did not parse"
    Assert-Loading ($null -eq $ast.ParamBlock) 'support must not bind caller arguments'
    foreach ($statement in $ast.EndBlock.Statements) {
        if ($statement -is [Management.Automation.Language.FunctionDefinitionAst]) { continue }
        foreach ($call in $statement.FindAll({
                param($node)
                $node -is [Management.Automation.Language.CommandAst]
            }, $true)) {
            if ($call.InvocationOperator -eq [Management.Automation.Language.TokenKind]::Dot) {
                Assert-Loading ($call.Extent.Text -cmatch
                    "^\. \(Join-Path \`$PSScriptRoot 'McmcpCapabilityGate(Transport|Observation|Action)\.ps1'\)$") `
                    'support loaded an unexpected file'
            } else {
                Assert-Loading ($call.GetCommandName() -cin @('Set-StrictMode', 'Add-Type', 'Join-Path')) `
                    'support invoked external work while loading'
            }
        }
        foreach ($call in $statement.FindAll({
                param($node)
                $node -is [Management.Automation.Language.InvokeMemberExpressionAst]
            }, $true)) {
            Assert-Loading ($call.Extent.Text -cin @(
                    '[Text.UTF8Encoding]::new($false)', '[Collections.Generic.List[object]]::new()')) `
                'support performed unexpected method calls while loading'
        }
    }
}

$supportPath = Join-Path $PSScriptRoot 'McmcpCapabilityGateSupport.ps1'
$Gate = 'caller-gate'
$StateRefWaitSeconds = 123
$LibraryOnly = $false
. $supportPath
Assert-Loading ($Gate -ceq 'caller-gate' -and $StateRefWaitSeconds -eq 123 -and -not $LibraryOnly) `
    'support overwrote caller parameters'
$script:RequestId = 99L
$script:LastRequestTimestamp = 99L
$script:Bearer = 'mock-only'
$script:ActiveActionId = 'mock-only'
$script:ToolTransport = { throw 'old transport must be reset' }
$script:DelayTransport = { throw 'old delay must be reset' }
Add-GateEvent -Event 'old_event' -Detail $null
. $supportPath
Assert-Loading ($script:RequestId -eq 0 -and $script:LastRequestTimestamp -eq 0 -and
    $null -eq $script:Bearer -and $null -eq $script:ActiveActionId -and
    $null -eq $script:ToolTransport -and $null -eq $script:DelayTransport -and
    $script:GateEvents.Count -eq 0) 'repeat loading changed shared initialization'

$clients = [ordered]@{
    BoundedInputHold = 'bounded-input-hold'
    Brew = 'brew'
    CobblestoneGenerator = 'cobblestone-generator'
    DirectionalStairs = 'directional-stairs'
    Fishing = 'fishing'
    KillZone = 'kill-zone'
    Redstone = 'redstone'
    Smelt = 'smelt'
    WarehouseLabel = 'warehouse-label'
}
foreach ($client in $clients.GetEnumerator()) {
    # 子scopeで入口をdot-sourceする。別プロセスやCLIは起動しない。
    & {
        $path = Join-Path $PSScriptRoot "Invoke-Mcmcp$($client.Key)CapabilityGate.ps1"
        $artifact = Join-Path $PSScriptRoot ('.mock-never-created-' + [guid]::NewGuid().ToString('N'))
        $extraParameters = @{}
        if ($client.Key -ceq 'BoundedInputHold') { $extraParameters.HoldInput = 'use' }
        if ($client.Key -ceq 'KillZone') {
            $extraParameters.ConsentWaitSeconds = 17
            $extraParameters.ApprovalMode = 'physical_fallback'
        }
        . $path -ArtifactDirectory $artifact -TokenPath 'mock-missing-token' `
            -Endpoint 'http://127.0.0.1:1/mock-never-called' -LibraryOnly @extraParameters
        Assert-Loading ($LibraryOnly -and $ArtifactDirectory -ceq $artifact -and
            $TokenPath -ceq 'mock-missing-token' -and
            $Endpoint -ceq 'http://127.0.0.1:1/mock-never-called') 'gate arguments changed during loading'
        if ($client.Key -ceq 'BoundedInputHold') {
            Assert-Loading ($HoldInput -ceq 'use') 'hold input was overwritten'
        }
        if ($client.Key -ceq 'KillZone') {
            Assert-Loading ($ConsentWaitSeconds -eq 17 -and $ApprovalMode -ceq 'physical_fallback') `
                'kill-zone options were overwritten'
        }
        Assert-Loading (-not (Test-Path -LiteralPath $artifact)) 'LibraryOnly created artifacts'
        Assert-Loading (@(Get-Command -CommandType Function | Where-Object {
                    $_.ScriptBlock.File -like '*McmcpConstruction*.ps1' -or
                    $_.ScriptBlock.File -like '*Invoke-McmcpConstructionCapabilityGate.ps1'
                }).Count -eq 0) 'another capability loaded construction scenarios'
        $meta = Get-McpMeta
        Assert-Loading ($meta['io.modelcontextprotocol/clientInfo'].name -ceq
            "mcmcp-$($client.Value)-capability-gate") 'gate metadata override was lost'
        $script:ToolTransport = { param($tool, $arguments) [pscustomobject]@{ marker = 'mock-result' } }
        $result = Invoke-GateTool -Tool agent_get_state -Arguments ([ordered]@{})
        Assert-Loading ($result.marker -ceq 'mock-result') 'script transport override was lost'
        $script:DelayTransport = { param($seconds) $script:LoadingDelaySeconds = $seconds }
        Invoke-GateDelaySeconds -Seconds 0.001
        Assert-Loading ($script:LoadingDelaySeconds -eq 0.001) 'script delay override was lost'
    }
}

# 従来は建築入口のparameter bindingが代行していたRedstoneの空引数拒否。
$redstonePath = Join-Path $PSScriptRoot 'Invoke-McmcpRedstoneCapabilityGate.ps1'
foreach ($parameter in @('ArtifactDirectory', 'TokenPath')) {
    foreach ($empty in @('', $null)) {
        $arguments = @{ ArtifactDirectory = 'mock-unused'; TokenPath = 'mock-missing'; LibraryOnly = $true }
        $arguments[$parameter] = $empty
        $rejected = $false
        try { & $redstonePath @arguments } catch [Management.Automation.ParameterBindingException] {
            $rejected = $true
        }
        Assert-Loading $rejected "redstone accepted an empty $parameter"
    }
}

Write-Output 'MCMCP capability gate loading tests passed.'
