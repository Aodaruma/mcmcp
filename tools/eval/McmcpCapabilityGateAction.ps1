# 有限予算のAction構築、開始・terminal待機・監査、cancelと入力解放確認。
# 定義のみ。入口と同じscopeへdot-sourceし、単独では実行しない。

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

