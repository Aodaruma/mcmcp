[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resetScript = Join-Path $PSScriptRoot 'Reset-McmcpLocalHardBuildingGateWorld.ps1'
$expectedRoot = 'C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902'
$expectedInstance = 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\MCMCP-Validation'
. $resetScript -Gate wall-5x5 -DataRoot $expectedRoot -InstanceRoot $expectedInstance -LibraryOnly

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "local hard-building reset contract test failed: $Message" }
}

function Test-ThrowsLike {
    param(
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)][string]$Pattern
    )
    try { & $Action } catch { return $_.Exception.Message -match $Pattern }
    return $false
}

$tokens = $null
$parseErrors = $null
$resetAst = [Management.Automation.Language.Parser]::ParseFile(
    $resetScript, [ref]$tokens, [ref]$parseErrors)
Assert-True ($parseErrors.Count -eq 0) 'reset script does not parse on Windows PowerShell 5.1'
$unsupportedDepth = @($resetAst.FindAll({
            param($node)
            if ($node -isnot [Management.Automation.Language.CommandAst]) { return $false }
            $elements = @($node.CommandElements | ForEach-Object { $_.Extent.Text })
            return $elements.Count -gt 0 -and
                $elements[0] -ceq 'ConvertFrom-Json' -and
                @($elements | Where-Object { $_ -ceq '-Depth' }).Count -gt 0
        }, $true))
Assert-True ($unsupportedDepth.Count -eq 0) 'PowerShell 6+ ConvertFrom-Json -Depth is present'

$resetSource = [IO.File]::ReadAllText($resetScript)
Assert-True (-not [Text.RegularExpressions.Regex]::IsMatch(
        $resetSource, '(?<!@)\(Get-ChildItem[^\r\n]*\)\.Count')) `
    'Get-ChildItem Count is not array-wrapped for PowerShell 5.1'
Assert-True ([Text.RegularExpressions.Regex]::IsMatch(
        $resetSource, '@\(Get-ChildItem[^\r\n]*\)\.Count')) `
    'the array-wrapped empty-staging Count guard is missing'
Assert-True ($resetSource -notmatch '(?i)docker') 'local reset is coupled to Docker'
Assert-True ($resetSource -notmatch '(?i)mcp-token|token\.json') 'local reset touches or names token state'
Assert-True ($resetSource -notmatch '(?i)Remove-Item') 'local reset contains a recursive-capable removal command'
Assert-True ($resetSource -match "ValidateSet\('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3', 'wall-5x5'\)") `
    'gate allow-list changed'
Assert-True ($resetSource -match [regex]::Escape($script:ArchiveSha256)) 'known archive hash is absent'
Assert-True ($resetSource -match [regex]::Escape($script:PlayerSha256)) 'known player hash is absent'
Assert-True ($resetSource -match [regex]::Escape($script:ExpectedSavePath)) 'exact save path is absent'
Assert-True (@([regex]::Matches($resetSource, 'Assert-LocalRuntimeStopped')).Count -ge 7) `
    'runtime is not checked at entry and promotion boundaries'
Assert-True ([Text.RegularExpressions.Regex]::IsMatch(
        $resetSource,
        'function Restore-PriorWorldAfterFailure[\s\S]*?Assert-LocalRuntimeStopped[\s\S]*?Move-ExactDirectory -Source \$SavePath[\s\S]*?Assert-LocalRuntimeStopped[\s\S]*?Move-ExactDirectory -Source \$BackupWorld')) `
    'rollback moves are not each preceded by a fresh runtime-stop guard'
Assert-True ($resetSource.Contains('current audited save is missing; refusing a reset without a backup')) `
    'missing current save is not rejected before promotion'
Assert-True ($resetSource.Contains('[switch]$AllowMissingCurrentSave')) `
    'audited empty-profile opt-in is missing'
Assert-True ($resetSource.Contains('if (-not $priorSaveExisted -and -not $AllowMissingCurrentSave)')) `
    'missing current save is not rejected unless explicitly opted in'
Assert-True ($resetSource.Contains("[Guid]::NewGuid().ToString('N')")) `
    'operation-specific backup identity is missing'
Assert-True ($resetSource.Contains('Move-ExactDirectory -Source $savePath -Destination $backupWorld')) `
    'current save is not moved to its unique backup'
Assert-True ($resetSource.Contains('Move-ExactDirectory -Source $stagedWorld -Destination $savePath')) `
    'same-volume staged-world promotion is missing'
Assert-True ($resetSource.Contains('Restore-PriorWorldAfterFailure')) `
    'rollback path is missing'
Assert-True ($resetSource.Contains('archive_entry_count = $script:ArchiveEntryCount')) `
    'receipt does not record the validated archive cardinality'
Assert-True ($resetSource.Contains('previous_save_backup = if ($priorSaveExisted) { $backupWorld } else { $null }')) `
    'receipt does not record the unique prior-save backup'
Assert-True (@([regex]::Matches($resetSource, 'Assert-NoReparsePoint')).Count -ge 7) `
    'reparse-point guards are missing from restore boundaries'

Assert-ExactPathContract -Actual $expectedRoot -Expected $script:ExpectedDataRoot -Label 'DataRoot'
Assert-ExactPathContract -Actual $expectedInstance -Expected $script:ExpectedInstanceRoot -Label 'instance root'
Assert-True (Test-ThrowsLike {
        Assert-ExactPathContract `
            -Actual 'C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-202609020' `
            -Expected $script:ExpectedDataRoot -Label 'DataRoot'
    } 'not the audited local path') 'prefix-sibling DataRoot was accepted'
Assert-True (Test-ThrowsLike {
        Assert-ExactPathContract `
            -Actual 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\Other' `
            -Expected $script:ExpectedInstanceRoot -Label 'instance root'
    } 'not the audited local path') 'wrong Prism instance was accepted'
Assert-True (Test-ThrowsLike {
        Assert-ExactPathContract `
            -Actual "$($script:ExpectedSavePath)-copy" `
            -Expected $script:ExpectedSavePath -Label 'world save'
    } 'not the audited local path') 'prefix-sibling save was accepted'

Assert-True (Test-DescendantPath `
        -Candidate (Join-Path $expectedRoot 'local-gate-world-staging\op') `
        -Parent $expectedRoot) 'known DataRoot descendant was rejected'
Assert-True (-not (Test-DescendantPath `
            -Candidate "$expectedRoot-sibling\op" -Parent $expectedRoot)) `
    'prefix sibling escaped the descendant guard'
Assert-SameVolume -Paths @($expectedRoot, $script:ExpectedSavePath)
Assert-True (Test-ThrowsLike {
        Assert-SameVolume -Paths @($expectedRoot, 'D:\mcmcp-staging')
    } 'not on one volume') 'cross-volume promotion paths were accepted'

Assert-LocalRuntimeContract -Processes @() -Listeners @()
Assert-LocalRuntimeContract `
    -Processes @([pscustomobject]@{ ProcessName = 'notepad'; Id = 1 }) `
    -Listeners @([pscustomobject]@{ LocalPort = 9999 })
foreach ($name in @('java', 'javaw.exe', 'minecraft', 'minecraftlauncher', 'prismlauncher')) {
    Assert-True (Test-ThrowsLike {
            Assert-LocalRuntimeContract `
                -Processes @([pscustomobject]@{ ProcessName = $name; Id = 1 }) -Listeners @()
        } 'requires Prism and Minecraft/Java') "forbidden process was accepted: $name"
}
foreach ($port in @(8765, 18766, 18775)) {
    Assert-True (Test-ThrowsLike {
            Assert-LocalRuntimeContract `
                -Processes @() -Listeners @([pscustomobject]@{ LocalPort = $port })
        } "listener port $port") "forbidden listener was accepted: $port"
}

$requiredEntries = @(
    'tester (1)/',
    'tester (1)/level.dat',
    'tester (1)/dimensions/minecraft/overworld/region/r.-1.-1.mca',
    'tester (1)/dimensions/minecraft/overworld/region/r.-1.0.mca',
    'tester (1)/players/data/d48f4ce9-4f5a-48d1-ae5d-fe2a3ddd9ae4.dat'
)
$fillerEntries = @(0..82 | ForEach-Object { "tester (1)/contract-fixture-$_.bin" })
$validEntries = @($requiredEntries + $fillerEntries)
Assert-True ($validEntries.Count -eq 88) 'mock archive does not exercise the exact entry count'
Assert-ArchiveEntryList -Entries $validEntries
Assert-True (Test-ThrowsLike {
        Assert-ArchiveEntryList -Entries @($validEntries[0..86])
    } 'entry count mismatch') 'wrong archive entry count was accepted'
Assert-True (Test-ThrowsLike {
        $unsafe = @($validEntries)
        $unsafe[87] = 'tester (1)/../../outside'
        Assert-ArchiveEntryList -Entries $unsafe
    } 'unsafe entry') 'archive traversal entry was accepted'
Assert-True (Test-ThrowsLike {
        $missing = @($validEntries)
        $missing[1] = 'tester (1)/different-level.dat'
        Assert-ArchiveEntryList -Entries $missing
    } 'missing required entry') 'archive missing level.dat was accepted'
Assert-True (Test-ThrowsLike {
        $duplicate = @($validEntries)
        $duplicate[87] = $duplicate[86]
        Assert-ArchiveEntryList -Entries $duplicate
    } 'duplicate entry') 'duplicate archive entry was accepted'

$expectedManifest = [ordered]@{ 'level.dat' = 'a'; 'players/data/player.dat' = 'b' }
$sameManifest = [ordered]@{ 'level.dat' = 'a'; 'players/data/player.dat' = 'b' }
Assert-HashManifestEqual -Expected $expectedManifest -Actual $sameManifest
$changedManifest = [ordered]@{ 'level.dat' = 'changed'; 'players/data/player.dat' = 'b' }
Assert-True (Test-ThrowsLike {
        Assert-HashManifestEqual -Expected $expectedManifest -Actual $changedManifest
    } 'mandatory-file hash mismatch') 'changed restored manifest was accepted'

'MCMCP local hard-building world reset contract tests passed.'
