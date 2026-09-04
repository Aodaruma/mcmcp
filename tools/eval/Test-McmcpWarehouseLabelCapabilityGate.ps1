[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'Invoke-McmcpWarehouseLabelCapabilityGate.ps1'
$artifactDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('mcmcp-warehouse-label-gate-' + [Guid]::NewGuid().ToString('N'))
. $runner -ArtifactDirectory $artifactDirectory -TokenPath 'mock-token' -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "warehouse-label gate mock test failed: $Message" }
}

function New-MockState {
    param([ValidateRange(0, 2)][int]$Stage)
    $inventory = if ($Stage -eq 1) {
        @([pscustomobject]@{ item = 'minecraft:raw_iron'; count = 16 })
    } else { @() }
    [pscustomobject]@{
        schema_version = 1
        control = [pscustomobject]@{ mode = 'ready'; game_paused = $false }
        world = [pscustomobject]@{
            dimension = 'minecraft:overworld'; client_tick = 20L
            position = [pscustomobject]@{ x = 196.5; y = 200.0; z = 196.5 }
        }
        inventory = $inventory
        policy = [pscustomobject]@{ max_distance_blocks = 32 }
        observation = [pscustomobject]@{ latest_frame_id = 'obs-0123456789abcdef' }
        action = $null
    }
}

function New-MockLabel {
    param([Parameter(Mandatory)][Collections.IDictionary]$Target, [string]$Ref)
    [pscustomobject]@{
        kind = 'visible_entity'; entity_type = 'minecraft:item_frame'; entity_ref = $Ref
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = [double]$Target.x + 0.5
            y = [double]$Target.y + 0.5; z = [double]$Target.z + 1.0
        }
        container_label = [pscustomobject]@{
            item = 'minecraft:raw_iron'; container_block = $Target.block
            attachment_face = 'south'
            container_position = [pscustomobject]@{
                dimension = 'minecraft:overworld'; x = $Target.x; y = $Target.y; z = $Target.z
            }
        }
    }
}

function New-MockSurface {
    param([Parameter(Mandatory)][Collections.IDictionary]$Target)
    [pscustomobject]@{
        kind = 'visible_surface'; block = $Target.block; face = 'up'
        position = [pscustomobject]@{
            dimension = 'minecraft:overworld'; x = $Target.x; y = $Target.y; z = $Target.z
        }
    }
}

function New-MockTerminal {
    param([Parameter(Mandatory)][ValidateSet('take', 'store')][string]$Direction,
        [Parameter(Mandatory)][string]$ActionId)
    $kind = if ($Direction -ceq 'take') { 'container_take' } else { 'container_store' }
    $detail = if ($Direction -ceq 'take') {
        'container_transfer=minecraft:raw_iron'
    } else { 'container_store=minecraft:raw_iron' }
    [pscustomobject]@{
        schema_version = 1; action_id = $ActionId; state = 'succeeded'; failure = $null
        progress = [pscustomobject]@{
            executed_nodes = 1; total_node_upper_bound = 1; interactions = 3
            distance_travelled = 0; camera_degrees = 20
            blocks_broken = 0; blocks_placed = 0
        }
        trace = @(
            [pscustomobject]@{ event = 'NODE_EVIDENCE'; detail = $detail },
            [pscustomobject]@{ event = 'NODE_COMPLETED'; detail = "${Direction}_labeled_raw_iron" }
        )
        effects = @([pscustomobject]@{
                kind = $kind; verification = 'confirmed'
                observed_before = [pscustomobject]@{ source_count = 16; destination_count = 0 }
                observed_after = [pscustomobject]@{
                    source_count = 0; destination_count = 16; transferred = 16
                }
            })
    }
}

$script:Stage = 0
$script:Submitted = [Collections.Generic.List[object]]::new()
$takeId = '550e8400-e29b-41d4-a716-446655440061'
$storeId = '550e8400-e29b-41d4-a716-446655440062'
$sourceLabel = New-MockLabel -Target $script:Source -Ref 'abcdefghijklmnopqrstuvwx'
$destinationLabel = New-MockLabel -Target $script:Destination -Ref 'zyxwvutsrqponmlkjihgfedc'
$sourceSurface = New-MockSurface -Target $script:Source
$destinationSurface = New-MockSurface -Target $script:Destination

$script:ToolTransport = {
    param($Tool, $Arguments)
    switch ($Tool) {
        'agent_get_state' { New-MockState -Stage $script:Stage }
        'agent_get_observation' {
            $kind = [string]$Arguments.kinds[0]
            $x = [int]$Arguments.filter.position_bounds.min_x
            $record = if ($kind -ceq 'visible_entity') {
                if ($x -eq 195) { $sourceLabel } else { $destinationLabel }
            } else {
                if ($x -eq 195) { $sourceSurface } else { $destinationSurface }
            }
            [pscustomobject]@{
                schema_version = 1; frame_id = 'obs-0123456789abcdef'
                frame_completed_tick = 20L; visible_entities_truncated = $false
                records = @($record); next_cursor = $null; sampling_coverage = 1
            }
        }
        'agent_start_action' {
            $node = $Arguments.program.body[0]
            $script:Submitted.Add($node)
            $id = if ($node.op -ceq 'take_known_container_stack') { $takeId } else { $storeId }
            [pscustomobject]@{ schema_version = 1; action_id = $id; state = 'queued' }
        }
        'agent_get_action' {
            if ($Arguments.action_id -ceq $takeId) {
                $script:Stage = 1
                New-MockTerminal -Direction take -ActionId $takeId
            } else {
                $script:Stage = 2
                New-MockTerminal -Direction store -ActionId $storeId
            }
        }
        default { throw "unexpected warehouse-label mock tool: $Tool" }
    }
}

try {
    $result = Invoke-McmcpWarehouseLabelCapabilityGate
    Assert-True ($result.gate_result.gate -ceq 'phase5-warehouse-label-transfer') `
        'gate result did not pass'
    Assert-True ($script:Submitted.Count -eq 2) 'gate did not submit exactly two Actions'
    Assert-True ($script:Submitted[0].routing_label.entity_ref -ceq
            $sourceLabel.entity_ref) 'take did not copy the source label ref'
    Assert-True ($script:Submitted[1].routing_label.entity_ref -ceq
            $destinationLabel.entity_ref) 'store did not refresh and copy the destination label ref'
    Assert-True ($script:Submitted[0].routing_label.item -ceq 'minecraft:raw_iron' -and
        $script:Submitted[1].routing_label.item -ceq 'minecraft:raw_iron') `
        'routing label item was transformed'
    Assert-True ($script:Submitted[0].op -ceq 'take_known_container_stack' -and
        $script:Submitted[1].op -ceq 'store_known_container_stack') `
        'take/store order changed'
    Assert-True ([bool]$result.input_release.control_ready) `
        'cleanup did not prove public input release'
    $manifest = Get-Content -LiteralPath (Join-Path $artifactDirectory 'gate-result.json') `
        -Raw | ConvertFrom-Json
    Assert-True ($manifest.status -ceq 'passed') 'artifact did not record PASS'
} finally {
    if (Test-Path -LiteralPath $artifactDirectory) {
        Remove-Item -LiteralPath $artifactDirectory -Recurse -Force
    }
}

Write-Output 'MCMCP warehouse-label capability gate mock tests passed.'
