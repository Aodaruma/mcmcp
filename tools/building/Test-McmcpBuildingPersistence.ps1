#requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-persistence-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
try {
    . (Join-Path $PSScriptRoot 'Invoke-McmcpBuilding.ps1') -BlueprintPath (Join-Path $PSScriptRoot 'example-small-floor.json') -CheckpointPath (Join-Path $temp 'checkpoint.json') -LibraryOnly
    $script:Plan=Read-BuildingBlueprint $BlueprintPath
    $script:Checkpoint=New-BuildingCheckpoint $script:Plan
    $script:Checkpoint.session_id='test-session'
    $script:Clock=[Diagnostics.Stopwatch]::StartNew();$script:Actions=0;$script:Starts=0
    function Get-BuildingState {return @{world=@{session_id='test-session';dimension='minecraft:overworld'}}}
    function Invoke-GateTool {param($Tool,$Arguments);$script:Starts++;return @{action_id='accepted-action'}}
    Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint

    if($IsWindows) {
        Add-Type -TypeDefinition @'
using System.IO;
using System.Threading.Tasks;
public static class BuildingPersistenceTestLock {
    public static async Task ReleaseAfter(FileStream stream, int milliseconds) {
        await Task.Delay(milliseconds);
        stream.Dispose();
    }
}
'@
        $held=[IO.File]::Open($CheckpointPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        $release=[BuildingPersistenceTestLock]::ReleaseAfter($held,200)
        try {
            $script:Checkpoint.diagnostic='after_transient_lock'
            Save-BuildingCheckpoint $CheckpointPath $script:Checkpoint
            if((Read-BuildingCheckpoint $CheckpointPath $script:Plan).diagnostic -cne 'after_transient_lock'){throw 'transient_save_not_committed'}
        } finally {[void]($release.GetAwaiter().GetResult());$held.Dispose()}
    }

    # Keep the destination unavailable throughout the first intent save; no HTTP may begin.
    $before=[IO.File]::ReadAllBytes($CheckpointPath)
    $held=$null
    if($IsWindows){$held=[IO.File]::Open($CheckpointPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)}
    else{$CheckpointPath=Join-Path $temp 'directory-destination';[void][IO.Directory]::CreateDirectory($CheckpointPath)}
    $timer=[Diagnostics.Stopwatch]::StartNew();$caught=$false
    try {
        try {Start-BuildingAction @{program=@{name='placeholder'}} 'floor' 0}
        catch {$caught=$_.Exception.Data['start_not_sent'] -eq $true}
    } finally {if($null -ne $held){$held.Dispose()}}
    if(-not $caught -or $script:Starts -ne 0 -or $null -ne $script:Checkpoint.pending){throw 'failed_intent_was_sent_or_retained'}
    if($timer.Elapsed.TotalSeconds -gt 5){throw 'save_retry_was_unbounded'}
    $CheckpointPath=Join-Path $temp 'checkpoint.json'
    if([Convert]::ToBase64String([IO.File]::ReadAllBytes($CheckpointPath)) -cne [Convert]::ToBase64String($before)){throw 'failed_replace_changed_original'}
    if(@(Get-ChildItem -LiteralPath $temp -File -Filter '*.tmp').Count -ne 0){throw 'temporary_file_leaked'}

    # A failure after the accepted HTTP response retains the known action for reconciliation.
    $script:OriginalSave=${function:Save-BuildingCheckpoint};$script:SaveCalls=0
    function Save-BuildingCheckpoint($Path,$Checkpoint) {
        $script:SaveCalls++
        if($script:SaveCalls -eq 2){throw [IO.IOException]::new('test_post_accept_save_failure')}
        & $script:OriginalSave $Path $Checkpoint
    }
    $caught=$false
    try {Start-BuildingAction @{program=@{name='placeholder'}} 'floor' 0}
    catch {$caught=$_.Exception.Message -ceq 'test_post_accept_save_failure' -and $_.Exception.Data['start_not_sent'] -ne $true}
    if(-not $caught -or $script:Starts -ne 1 -or $script:Checkpoint.pending.action_id -cne 'accepted-action'){throw 'accepted_action_was_forgotten'}
    'MCMCP checkpoint persistence tests passed (transient replace, bounded failure, tmp cleanup, unsent/accepted distinction).'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -cmatch '^mcmcp-persistence-[a-f0-9]{32}$'){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
