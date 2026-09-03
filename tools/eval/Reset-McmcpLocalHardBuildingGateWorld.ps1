[CmdletBinding()]
param(
    [string]$DataRoot = 'C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902',

    [string]$InstanceRoot = 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\MCMCP-Validation',

    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl', 'wall-3x3', 'wall-5x5')]
    [string]$Gate,

    # Only for an audited empty local profile. Existing saves are always backed up.
    [switch]$AllowMissingCurrentSave,

    # Dot-source pure guards without inspecting processes, ports, or the filesystem.
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = New-Object Text.UTF8Encoding($false)

$script:ExpectedDataRoot = 'C:\Users\aod\AppData\Local\Temp\mcmcp-hard-building-20260902'
$script:ExpectedInstanceRoot = 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\MCMCP-Validation'
$script:ExpectedSaveRoot = 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\MCMCP-Validation\minecraft\saves'
$script:ExpectedSavePath = 'C:\Users\aod\AppData\Roaming\PrismLauncher\instances\MCMCP-Validation\minecraft\saves\tester (1)'
$script:WorldName = 'tester (1)'
$script:PlayerUuid = 'd48f4ce9-4f5a-48d1-ae5d-fe2a3ddd9ae4'
$script:ArchiveEntryCount = 88
$script:ArchiveSha256 = 'abfb8f879d70bbe49fa46ffad30c701368222e09b47aeed2a88be226e41817a4'
$script:PlayerSha256 = '8e961b49f0d7bfa184dee10d5cc0b7fef5d0d836de58b87a95ed90f3175a9332'
$script:ForbiddenPorts = @(8765, 18766, 18775)
$script:MandatoryWorldFiles = @(
    'level.dat',
    'dimensions\minecraft\overworld\region\r.-1.-1.mca',
    'dimensions\minecraft\overworld\region\r.-1.0.mca',
    "players\data\$($script:PlayerUuid).dat",
    "players\data\$($script:PlayerUuid).dat_old"
)

function Get-NormalizedFullPath {
    param([Parameter(Mandatory)][string]$Path)
    [IO.Path]::GetFullPath($Path).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
}

function Test-SamePath {
    param([Parameter(Mandatory)][string]$Left, [Parameter(Mandatory)][string]$Right)
    (Get-NormalizedFullPath $Left).Equals(
        (Get-NormalizedFullPath $Right), [StringComparison]::OrdinalIgnoreCase)
}

function Test-DescendantPath {
    param([Parameter(Mandatory)][string]$Candidate, [Parameter(Mandatory)][string]$Parent)
    $candidateFull = Get-NormalizedFullPath $Candidate
    $parentFull = Get-NormalizedFullPath $Parent
    $prefix = $parentFull + [IO.Path]::DirectorySeparatorChar
    $candidateFull.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-ExactPathContract {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    if (-not (Test-SamePath $Actual $Expected)) {
        throw "$Label is not the audited local path: $Actual"
    }
}

function Assert-StrictDescendant {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Label
    )
    if (-not (Test-DescendantPath -Candidate $Path -Parent $Root)) {
        throw "$Label escaped its audited root"
    }
}

function Assert-SameVolume {
    param([Parameter(Mandatory)][string[]]$Paths)
    if ($Paths.Count -lt 2) { throw 'same-volume guard requires at least two paths' }
    $expectedRoot = [IO.Path]::GetPathRoot((Get-NormalizedFullPath $Paths[0]))
    foreach ($path in $Paths) {
        $actualRoot = [IO.Path]::GetPathRoot((Get-NormalizedFullPath $path))
        if (-not $actualRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "atomic promotion paths are not on one volume: $path"
        }
    }
}

function Move-ExactDirectory {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "$Label source directory is missing: $Source"
    }
    if (Test-Path -LiteralPath $Destination) {
        throw "$Label destination already exists: $Destination"
    }
    [IO.Directory]::Move(
        (Get-NormalizedFullPath $Source),
        (Get-NormalizedFullPath $Destination))
    if ((Test-Path -LiteralPath $Source) -or
        -not (Test-Path -LiteralPath $Destination -PathType Container)) {
        throw "$Label did not complete as one exact directory rename"
    }
}

function Assert-NoReparsePoint {
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Recurse
    )
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $pending = New-Object 'Collections.Generic.Stack[string]'
    $pending.Push((Get-NormalizedFullPath $Path))
    while ($pending.Count -gt 0) {
        $current = $pending.Pop()
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "reparse point rejected: $($item.FullName)"
        }
        if ($Recurse -and $item.PSIsContainer) {
            foreach ($child in @(Get-ChildItem -LiteralPath $item.FullName -Force -ErrorAction Stop)) {
                if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "nested reparse point rejected: $($child.FullName)"
                }
                if ($child.PSIsContainer) { $pending.Push($child.FullName) }
            }
        }
    }
}

function Assert-LocalRuntimeContract {
    param(
        [object[]]$Processes = @(),
        [object[]]$Listeners = @()
    )
    foreach ($process in @($Processes)) {
        $name = [string]$process.ProcessName
        if ($name -match '^(?i:java|javaw|minecraft|minecraftlauncher|prismlauncher)(?:\.exe)?$') {
            throw "local reset requires Prism and Minecraft/Java to be closed: $name"
        }
    }
    foreach ($listener in @($Listeners)) {
        $port = [int]$listener.LocalPort
        if ($port -in $script:ForbiddenPorts) {
            throw "local reset requires MCMCP listener port $port to be closed"
        }
    }
}

function Assert-LocalRuntimeStopped {
    $processes = @(Get-Process -ErrorAction Stop | Select-Object ProcessName, Id)
    $netCommand = Get-Command Get-NetTCPConnection -ErrorAction Stop
    if ($null -eq $netCommand) { throw 'Get-NetTCPConnection is unavailable' }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {
            [int]$_.LocalPort -in $script:ForbiddenPorts
        } | Select-Object LocalAddress, LocalPort, OwningProcess)
    Assert-LocalRuntimeContract -Processes $processes -Listeners $listeners
}

function Get-RequiredFileHash {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-KnownHash {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    $actual = Get-RequiredFileHash -Path $Path -Label $Label
    if ($actual -cne $Expected) { throw "$Label SHA-256 mismatch" }
    return $actual
}

function Assert-ArchiveEntryList {
    param([Parameter(Mandatory)][string[]]$Entries)
    $normalized = @($Entries | ForEach-Object { ([string]$_).Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($normalized.Count -ne $script:ArchiveEntryCount) {
        throw "baseline archive entry count mismatch: $($normalized.Count)"
    }
    $seen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $expectedPrefix = "$($script:WorldName)/"
    foreach ($entry in $normalized) {
        if (-not $seen.Add($entry)) { throw "baseline archive contains a duplicate entry: $entry" }
        if (-not $entry.StartsWith($expectedPrefix, [StringComparison]::Ordinal) -or
            $entry.Contains('\') -or
            $entry.StartsWith('/') -or
            $entry -match '(^|/)\.\.(/|$)') {
            throw "baseline archive contains an unsafe entry: $entry"
        }
    }
    foreach ($required in @(
            "$($script:WorldName)/level.dat",
            "$($script:WorldName)/dimensions/minecraft/overworld/region/r.-1.-1.mca",
            "$($script:WorldName)/dimensions/minecraft/overworld/region/r.-1.0.mca",
            "$($script:WorldName)/players/data/$($script:PlayerUuid).dat")) {
        if ($required -cnotin $normalized) {
            throw "baseline archive is missing required entry: $required"
        }
    }
}

function Get-WorldHashManifest {
    param([Parameter(Mandatory)][string]$WorldRoot)
    $result = [ordered]@{}
    foreach ($relative in $script:MandatoryWorldFiles) {
        $path = Join-Path $WorldRoot $relative
        $result[$relative.Replace('\', '/')] = Get-RequiredFileHash `
            -Path $path -Label "restored mandatory file $relative"
    }
    return $result
}

function Assert-HashManifestEqual {
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Expected,
        [Parameter(Mandatory)][Collections.IDictionary]$Actual
    )
    if ($Expected.Count -ne $Actual.Count) { throw 'restored hash manifest size mismatch' }
    foreach ($key in $Expected.Keys) {
        if (-not $Actual.Contains($key) -or $Expected[$key] -cne $Actual[$key]) {
            throw "restored mandatory-file hash mismatch: $key"
        }
    }
}

function Restore-PriorWorldAfterFailure {
    param(
        [Parameter(Mandatory)][string]$SavePath,
        [Parameter(Mandatory)][string]$BackupWorld,
        [Parameter(Mandatory)][string]$FailedWorld
    )
    Assert-LocalRuntimeStopped
    if (Test-Path -LiteralPath $SavePath -PathType Container) {
        Move-ExactDirectory -Source $SavePath -Destination $FailedWorld `
            -Label 'failed restored world quarantine'
    }
    Assert-LocalRuntimeStopped
    if (-not (Test-Path -LiteralPath $BackupWorld -PathType Container)) {
        throw "rollback backup is missing: $BackupWorld"
    }
    Move-ExactDirectory -Source $BackupWorld -Destination $SavePath `
        -Label 'prior world rollback'
}

function Invoke-McmcpLocalHardBuildingGateWorldReset {
    $resolvedRoot = (Resolve-Path -LiteralPath $DataRoot -ErrorAction Stop).Path
    $resolvedInstance = (Resolve-Path -LiteralPath $InstanceRoot -ErrorAction Stop).Path
    Assert-ExactPathContract -Actual $resolvedRoot -Expected $script:ExpectedDataRoot -Label 'DataRoot'
    Assert-ExactPathContract -Actual $resolvedInstance -Expected $script:ExpectedInstanceRoot -Label 'instance root'
    $root = Get-NormalizedFullPath $resolvedRoot
    $instance = Get-NormalizedFullPath $resolvedInstance
    $saveRoot = Join-Path $instance 'minecraft\saves'
    $savePath = Join-Path $saveRoot $script:WorldName
    Assert-ExactPathContract -Actual $saveRoot -Expected $script:ExpectedSaveRoot -Label 'save root'
    Assert-ExactPathContract -Actual $savePath -Expected $script:ExpectedSavePath -Label 'world save'

    $archive = Join-Path $root 'tester-1.tar.gz'
    $correctedPlayer = Join-Path $root 'hard-building-player.dat'
    $backupRoot = Join-Path $root 'local-gate-world-backups'
    $stagingRoot = Join-Path $root 'local-gate-world-staging'
    $receiptRoot = Join-Path $root 'local-gate-world-reset-receipts'
    foreach ($path in @($archive, $correctedPlayer, $backupRoot, $stagingRoot, $receiptRoot)) {
        Assert-StrictDescendant -Path $path -Root $root -Label 'local reset path'
    }
    Assert-SameVolume -Paths @($root, $saveRoot, $savePath, $backupRoot, $stagingRoot)
    foreach ($path in @($root, $instance, $saveRoot, $archive, $correctedPlayer)) {
        Assert-NoReparsePoint -Path $path
    }
    if (-not (Test-Path -LiteralPath $saveRoot -PathType Container)) {
        throw "audited save root is missing: $saveRoot"
    }
    $priorSaveExisted = Test-Path -LiteralPath $savePath -PathType Container
    if (-not $priorSaveExisted -and -not $AllowMissingCurrentSave) {
        throw "current audited save is missing; refusing a reset without a backup: $savePath"
    }
    if ($priorSaveExisted) { Assert-NoReparsePoint -Path $savePath -Recurse }
    Assert-LocalRuntimeStopped

    $archiveHash = Assert-KnownHash -Path $archive `
        -Expected $script:ArchiveSha256 -Label 'baseline archive'
    $playerHash = Assert-KnownHash -Path $correctedPlayer `
        -Expected $script:PlayerSha256 -Label 'corrected player fixture'
    $archiveEntries = @(& tar -tzf $archive 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'tar could not list the baseline archive' }
    Assert-ArchiveEntryList -Entries $archiveEntries

    $operationId = '{0}-{1}-{2}' -f `
        [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'),
        $Gate,
        [Guid]::NewGuid().ToString('N')
    $staging = Join-Path $stagingRoot $operationId
    $stagedWorld = Join-Path $staging $script:WorldName
    $backupDirectory = Join-Path $backupRoot $operationId
    $backupWorld = Join-Path $backupDirectory $script:WorldName
    $receiptPath = Join-Path $receiptRoot "$operationId.json"
    $receiptTemporary = Join-Path $receiptRoot "$operationId.json.tmp"
    foreach ($path in @(
            $staging, $stagedWorld, $backupDirectory, $backupWorld,
            $receiptPath, $receiptTemporary)) {
        Assert-StrictDescendant -Path $path -Root $root -Label 'local reset operation path'
    }

    foreach ($directory in @($backupRoot, $stagingRoot, $receiptRoot)) {
        if (-not (Test-Path -LiteralPath $directory)) {
            [void](New-Item -ItemType Directory -Path $directory -ErrorAction Stop)
        }
        Assert-NoReparsePoint -Path $directory
    }
    foreach ($uniquePath in @($staging, $backupDirectory, $receiptPath, $receiptTemporary)) {
        if (Test-Path -LiteralPath $uniquePath) {
            throw "generated reset operation path unexpectedly already exists: $uniquePath"
        }
    }
    [void](New-Item -ItemType Directory -Path $staging -ErrorAction Stop)
    [void](New-Item -ItemType Directory -Path $backupDirectory -ErrorAction Stop)

    & tar -xzf $archive -C $staging
    if ($LASTEXITCODE -ne 0) { throw "tar extraction failed; staging preserved at $staging" }
    if (-not (Test-Path -LiteralPath $stagedWorld -PathType Container)) {
        throw "archive did not create the exact staged world; staging preserved at $staging"
    }
    Assert-NoReparsePoint -Path $stagedWorld -Recurse

    $stagedPlayer = Join-Path $stagedWorld "players\data\$($script:PlayerUuid).dat"
    $stagedPlayerOld = Join-Path $stagedWorld "players\data\$($script:PlayerUuid).dat_old"
    Copy-Item -LiteralPath $correctedPlayer -Destination $stagedPlayer -Force
    Copy-Item -LiteralPath $correctedPlayer -Destination $stagedPlayerOld -Force
    [void](Assert-KnownHash -Path $stagedPlayer -Expected $script:PlayerSha256 `
        -Label 'staged corrected player')
    [void](Assert-KnownHash -Path $stagedPlayerOld -Expected $script:PlayerSha256 `
        -Label 'staged corrected player backup')
    $stagedHashes = Get-WorldHashManifest -WorldRoot $stagedWorld

    # Close the preparation-to-promotion window as far as a script can: Prism,
    # Java, Minecraft, and all known MCP listeners must still be absent here.
    Assert-LocalRuntimeStopped
    if ($priorSaveExisted) { Assert-NoReparsePoint -Path $savePath -Recurse }
    $backupCreated = $false
    $promoted = $false
    try {
        if ($priorSaveExisted) {
            Move-ExactDirectory -Source $savePath -Destination $backupWorld `
                -Label 'current world backup'
            $backupCreated = $true
        }
        Assert-LocalRuntimeStopped
        Move-ExactDirectory -Source $stagedWorld -Destination $savePath `
            -Label 'staged world promotion'
        $promoted = $true
    } catch {
        if (-not $promoted -and $backupCreated -and
            -not (Test-Path -LiteralPath $savePath) -and
            (Test-Path -LiteralPath $backupWorld -PathType Container)) {
            try {
                Move-ExactDirectory -Source $backupWorld -Destination $savePath `
                    -Label 'promotion rollback'
            } catch {
                throw "world promotion failed and automatic rollback also failed; backup=$backupWorld"
            }
        }
        throw
    }

    try {
        Assert-LocalRuntimeStopped
        Assert-NoReparsePoint -Path $savePath -Recurse
        $restoredHashes = Get-WorldHashManifest -WorldRoot $savePath
        Assert-HashManifestEqual -Expected $stagedHashes -Actual $restoredHashes
        [void](Assert-KnownHash `
            -Path (Join-Path $savePath "players\data\$($script:PlayerUuid).dat") `
            -Expected $script:PlayerSha256 -Label 'restored corrected player')
        [void](Assert-KnownHash `
            -Path (Join-Path $savePath "players\data\$($script:PlayerUuid).dat_old") `
            -Expected $script:PlayerSha256 -Label 'restored corrected player backup')
        Assert-LocalRuntimeStopped

        $receipt = [ordered]@{
            schema_version = 1
            operation_id = $operationId
            gate = $Gate
            data_root = $root
            instance_root = $instance
            save_path = $savePath
            runtime_stopped = $true
            prior_save_existed = $priorSaveExisted
            archive_entry_count = $script:ArchiveEntryCount
            archive_sha256 = $archiveHash
            corrected_player_sha256 = $playerHash
            previous_save_backup = if ($priorSaveExisted) { $backupWorld } else { $null }
            restored_hashes = $restoredHashes
            completed_utc = [DateTimeOffset]::UtcNow.ToString('O')
        }
        [IO.File]::WriteAllText(
            $receiptTemporary,
            (ConvertTo-Json $receipt -Depth 20),
            $script:Utf8NoBom)
        Move-Item -LiteralPath $receiptTemporary -Destination $receiptPath -ErrorAction Stop
    } catch {
        $verificationError = $_.Exception.Message
        $failedWorld = Join-Path $staging 'failed-restored-world'
        try {
            if ($priorSaveExisted) {
                Restore-PriorWorldAfterFailure `
                    -SavePath $savePath -BackupWorld $backupWorld -FailedWorld $failedWorld
            } else {
                Assert-LocalRuntimeStopped
                Move-ExactDirectory -Source $savePath -Destination $failedWorld `
                    -Label 'failed new world quarantine'
                Assert-LocalRuntimeStopped
            }
        } catch {
            throw "post-restore verification/receipt failed ($verificationError); rollback also failed: $($_.Exception.Message)"
        }
        $recovery = if ($priorSaveExisted) {
            'prior save restored'
        } else {
            'new world quarantined; audited save remains absent'
        }
        throw "post-restore verification/receipt failed; $recovery; failed world=$failedWorld; reason=$verificationError"
    }

    # The staged world was moved by a same-volume rename. Remove only its now-empty
    # operation directory; never recursively delete any computed path.
    if (@(Get-ChildItem -LiteralPath $staging -Force).Count -eq 0) {
        [IO.Directory]::Delete($staging, $false)
    }
    return $receipt
}

if (-not $LibraryOnly) {
    Invoke-McmcpLocalHardBuildingGateWorldReset | ConvertTo-Json -Depth 20
}
