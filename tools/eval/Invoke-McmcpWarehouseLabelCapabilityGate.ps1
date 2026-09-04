[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArtifactDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$TokenPath,
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$labelArtifactDirectory = $ArtifactDirectory
$labelTokenPath = $TokenPath
$labelEndpoint = $Endpoint
$labelLibraryOnly = [bool]$LibraryOnly
$commonRunner = Join-Path $PSScriptRoot 'Invoke-McmcpConstructionCapabilityGate.ps1'
. $commonRunner -Gate navigation -ArtifactDirectory $labelArtifactDirectory `
    -TokenPath $labelTokenPath -Endpoint $labelEndpoint -LibraryOnly
$ArtifactDirectory = $labelArtifactDirectory
$TokenPath = $labelTokenPath
$Endpoint = $labelEndpoint
$LibraryOnly = $labelLibraryOnly

$script:GateEvents = [Collections.Generic.List[object]]::new()
$script:ActiveActionId = $null
$script:ToolTransport = $null
$script:DelayTransport = $null
$script:Bearer = $null

$script:LabelItem = 'minecraft:raw_iron'
$script:LabelCount = 16
$script:Source = [ordered]@{ x = 195; y = 200; z = 194; block = 'minecraft:chest' }
$script:Destination = [ordered]@{ x = 197; y = 200; z = 194; block = 'minecraft:barrel' }

function Get-McpMeta {
    [ordered]@{
        'io.modelcontextprotocol/protocolVersion' = $script:ProtocolVersion
        'io.modelcontextprotocol/clientCapabilities' = [ordered]@{}
        'io.modelcontextprotocol/clientInfo' = [ordered]@{
            name = 'mcmcp-warehouse-label-capability-gate'; version = '1'
        }
    }
}

function Test-LabelProperty {
    param([AllowNull()][object]$Object, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $false }
    if ($Object -is [Collections.IDictionary]) { return $Object.Contains($Name) }
    return @($Object.PSObject.Properties | Where-Object Name -CEQ $Name).Count -eq 1
}

function Get-LabelBounds {
    param([Parameter(Mandatory)][Collections.IDictionary]$Target, [switch]$Frame)
    $z = [int]$Target.z + $(if ($Frame) { 1 } else { 0 })
    return [ordered]@{
        dimension = 'minecraft:overworld'
        min_x = [int]$Target.x; min_y = [int]$Target.y; min_z = $z
        max_x = [int]$Target.x; max_y = [int]$Target.y; max_z = $z
    }
}

function Get-OnlyContainerLabel {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][Collections.IDictionary]$Target
    )
    $records = @(Get-RecordsFromState -State $State -Kinds @('visible_entity') `
        -Filter ([ordered]@{
            entity_types = @('minecraft:item_frame')
            position_bounds = Get-LabelBounds -Target $Target -Frame
        }))
    if ($records.Count -ne 1) {
        throw "expected one visible item-frame label for $($Target.block); found=$($records.Count)"
    }
    $record = $records[0]
    $label = Get-ObjectProperty $record 'container_label'
    $position = Get-ObjectProperty $label 'container_position'
    $entityRef = [string](Get-ObjectProperty $record 'entity_ref')
    if ((Get-ObjectProperty $record 'kind') -cne 'visible_entity' -or
        (Get-ObjectProperty $record 'entity_type') -cne 'minecraft:item_frame' -or
        $entityRef -cnotmatch '^[A-Za-z0-9_-]{24}$' -or
        (Test-LabelProperty -Object $record -Name 'displayed_item') -or
        $null -eq $label -or
        (Get-ObjectProperty $label 'item') -cne $script:LabelItem -or
        (Get-ObjectProperty $label 'container_block') -cne $Target.block -or
        (Get-ObjectProperty $label 'attachment_face') -cne 'south' -or
        (Get-ObjectProperty $position 'dimension') -cne 'minecraft:overworld' -or
        [int](Get-ObjectProperty $position 'x') -ne [int]$Target.x -or
        [int](Get-ObjectProperty $position 'y') -ne [int]$Target.y -or
        [int](Get-ObjectProperty $position 'z') -ne [int]$Target.z) {
        throw 'visible item-frame routing evidence did not match the fixture contract'
    }
    return $record
}

function Get-OnlyLabeledContainerSurface {
    param(
        [Parameter(Mandatory)][object]$State,
        [Parameter(Mandatory)][Collections.IDictionary]$Target
    )
    $records = @(Get-VisibleSurfaceRecords -State $State -Block $Target.block `
        -Bounds (Get-LabelBounds -Target $Target) -Faces @('up'))
    if ($records.Count -ne 1) {
        throw "expected one visible up surface for $($Target.block); found=$($records.Count)"
    }
    return $records[0]
}

function New-LabeledTransferRequest {
    param(
        [Parameter(Mandatory)][ValidateSet('take', 'store')][string]$Direction,
        [Parameter(Mandatory)][object]$LabelRecord,
        [Parameter(Mandatory)][object]$Surface
    )
    $label = Get-ObjectProperty $LabelRecord 'container_label'
    $target = Get-ObjectProperty $Surface 'position'
    if ((ConvertTo-CompactJson $target) -cne
            (ConvertTo-CompactJson (Get-ObjectProperty $label 'container_position'))) {
        throw 'label target and delivered interaction surface do not identify the same container'
    }
    $routing = [ordered]@{
        entity_ref = Get-ObjectProperty $LabelRecord 'entity_ref'
        item = Get-ObjectProperty $label 'item'
    }
    $node = [ordered]@{
        id = "${Direction}_labeled_raw_iron"
        op = if ($Direction -ceq 'take') {
            'take_known_container_stack'
        } else { 'store_known_container_stack' }
        target = $target
        expected_block = Get-ObjectProperty $Surface 'block'
        item = $script:LabelItem
        stack_policy = 'default_components_only'
    }
    if ($Direction -ceq 'take') {
        $node.minimum_inventory_count = $script:LabelCount
    } else {
        $node.minimum_container_count = $script:LabelCount
    }
    $node.routing_label = $routing
    return New-PrimitiveRequest -Name "warehouse_label_$Direction" `
        -Capabilities @('camera', 'inventory_transfer') -Node $node -Interactions 3
}

function Assert-LabeledTransferTerminal {
    param(
        [Parameter(Mandatory)][object]$Terminal,
        [Parameter(Mandatory)][ValidateSet('take', 'store')][string]$Direction
    )
    if ((Get-ObjectProperty $Terminal 'state') -cne 'succeeded') {
        throw "$Direction labeled transfer did not succeed"
    }
    $progress = Get-ObjectProperty $Terminal 'progress'
    if ([int](Get-ObjectProperty $progress 'executed_nodes') -ne 1 -or
        [int](Get-ObjectProperty $progress 'interactions') -ne 3 -or
        [double](Get-ObjectProperty $progress 'distance_travelled') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_broken') -ne 0 -or
        [int](Get-ObjectProperty $progress 'blocks_placed') -ne 0) {
        throw "$Direction labeled transfer exceeded its stationary three-interaction contract"
    }
    $expectedDetail = if ($Direction -ceq 'take') {
        "container_transfer=$script:LabelItem"
    } else { "container_store=$script:LabelItem" }
    $evidence = @((Get-ObjectProperty $Terminal 'trace') | Where-Object {
            (Get-ObjectProperty $_ 'event') -ceq 'NODE_EVIDENCE' -and
            (Get-ObjectProperty $_ 'detail') -ceq $expectedDetail
        })
    $effects = @((Get-ObjectProperty $Terminal 'effects'))
    $expectedKind = if ($Direction -ceq 'take') { 'container_take' } else { 'container_store' }
    if ($evidence.Count -ne 1 -or $effects.Count -ne 1 -or
        (Get-ObjectProperty $effects[0] 'kind') -cne $expectedKind -or
        (Get-ObjectProperty $effects[0] 'verification') -cne 'confirmed' -or
        [int](Get-ObjectProperty (Get-ObjectProperty $effects[0] 'observed_after') 'transferred') `
            -ne $script:LabelCount) {
        throw "$Direction terminal omitted its confirmed whole-stack evidence"
    }
    return [ordered]@{
        action_id = Get-ObjectProperty $Terminal 'action_id'
        effect_kind = $expectedKind
        transferred = $script:LabelCount
        interactions = 3
    }
}

function Invoke-WarehouseLabelGateCore {
    $initial = Get-FreshState
    if ((Get-InventoryCount -State $initial -Item $script:LabelItem) -ne 0) {
        throw 'label-transfer fixture player inventory was not empty at T0'
    }
    $sourceLabel = Get-OnlyContainerLabel -State $initial -Target $script:Source
    $sourceSurface = Get-OnlyLabeledContainerSurface -State $initial -Target $script:Source
    $take = Invoke-ActionRequest -Request (New-LabeledTransferRequest `
        -Direction take -LabelRecord $sourceLabel -Surface $sourceSurface) -WallTimeoutSeconds 90
    $takeProof = Assert-LabeledTransferTerminal -Terminal $take -Direction take

    $middle = Get-FreshState
    if ((Get-InventoryCount -State $middle -Item $script:LabelItem) -ne $script:LabelCount) {
        throw 'labeled take did not produce the exact player inventory count'
    }
    $destinationLabel = Get-OnlyContainerLabel -State $middle -Target $script:Destination
    $destinationSurface = Get-OnlyLabeledContainerSurface `
        -State $middle -Target $script:Destination
    $store = Invoke-ActionRequest -Request (New-LabeledTransferRequest `
        -Direction store -LabelRecord $destinationLabel -Surface $destinationSurface) `
        -WallTimeoutSeconds 90
    $storeProof = Assert-LabeledTransferTerminal -Terminal $store -Direction store

    $final = Get-FreshState
    if ((Get-InventoryCount -State $final -Item $script:LabelItem) -ne 0) {
        throw 'labeled store did not empty the transferred player stack'
    }
    return [ordered]@{
        gate = 'phase5-warehouse-label-transfer'
        fixture_precondition = '/mcmcp_fixture phase5 label_transfer'
        label_item = $script:LabelItem
        exact_item_only = $true
        source = $script:Source
        destination = $script:Destination
        take = $takeProof
        store = $storeProof
        final_player_item_count = 0
        destination_count_proven_by_store_readback = $script:LabelCount
    }
}

function Write-WarehouseLabelArtifacts {
    param(
        [AllowNull()][Collections.IDictionary]$GateResult,
        [AllowNull()][Collections.IDictionary]$InputRelease,
        [AllowNull()][Management.Automation.ErrorRecord]$Failure
    )
    [void][IO.Directory]::CreateDirectory($ArtifactDirectory)
    [IO.File]::WriteAllLines((Join-Path $ArtifactDirectory 'gate-events.jsonl'),
        @($script:GateEvents | ForEach-Object { ConvertTo-CompactJson $_ }), $script:Utf8NoBom)
    $manifest = [ordered]@{
        schema_version = 1
        gate = 'phase5-warehouse-label-transfer'
        status = if ($null -eq $Failure) { 'passed' } else { 'failed' }
        fixed_tools = @($script:AllowedTools)
        normal_player_actions_only = $true
        public_input_release = $InputRelease
        result = $GateResult
        failure = if ($null -eq $Failure) { $null } else {
            [ordered]@{ type = $Failure.Exception.GetType().FullName
                message = $Failure.Exception.Message }
        }
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'gate-result.json'),
        (ConvertTo-Json $manifest -Depth 100), $script:Utf8NoBom)
}

function Invoke-McmcpWarehouseLabelCapabilityGate {
    $script:ActiveActionId = $null
    $primaryFailure = $null
    $cleanupFailure = $null
    $gateResult = $null
    $release = $null
    try { $gateResult = Invoke-WarehouseLabelGateCore } catch { $primaryFailure = $_ } finally {
        try { $release = Invoke-GateCleanup } catch { $cleanupFailure = $_ }
    }
    $reportedFailure = if ($null -ne $primaryFailure) { $primaryFailure } else { $cleanupFailure }
    Write-WarehouseLabelArtifacts -GateResult $gateResult -InputRelease $release `
        -Failure $reportedFailure
    if ($null -ne $primaryFailure) { throw $primaryFailure }
    if ($null -ne $cleanupFailure) { throw $cleanupFailure }
    return [ordered]@{ gate_result = $gateResult; input_release = $release }
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
    ConvertTo-Json (Invoke-McmcpWarehouseLabelCapabilityGate) -Depth 100
}
