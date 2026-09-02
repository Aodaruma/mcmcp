[CmdletBinding()]
param(
    [string]$DataRoot = 'F:\mcmcp-testlab\20260902-hard-building-v1',

    [Parameter(Mandatory)]
    [ValidateSet('navigation', 'faces-place', 'state-ref-ttl')]
    [string]$Gate,

    # Dot-source pure guards without inspecting Docker or changing the filesystem.
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $script:Utf8NoBom

$script:ExpectedDataRoot = 'F:\mcmcp-testlab\20260902-hard-building-v1'
$script:ContainerName = 'mcmcp-hard-building-20260902'
$script:WorldName = 'tester (1)'
$script:PlayerUuid = 'd48f4ce9-4f5a-48d1-ae5d-fe2a3ddd9ae4'
$script:ArchiveSha256 = 'abfb8f879d70bbe49fa46ffad30c701368222e09b47aeed2a88be226e41817a4'
$script:PlayerSha256 = '8e961b49f0d7bfa184dee10d5cc0b7fef5d0d836de58b87a95ed90f3175a9332'
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

function Assert-ExactKnownDataRoot {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-SamePath $resolved $script:ExpectedDataRoot)) {
        throw "DataRoot is not the one audited hard-building root: $resolved"
    }
    return Get-NormalizedFullPath $resolved
}

function Assert-StrictDescendant {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Label
    )
    if (-not (Test-DescendantPath -Candidate $Path -Parent $Root)) {
        throw "$Label escaped DataRoot"
    }
}

function Assert-NoReparsePoint {
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Recurse
    )
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $root = Get-Item -LiteralPath $Path -Force
    if (($root.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "reparse point rejected: $($root.FullName)"
    }
    if ($Recurse -and $root.PSIsContainer) {
        foreach ($entry in Get-ChildItem -LiteralPath $root.FullName -Force -Recurse) {
            if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "nested reparse point rejected: $($entry.FullName)"
            }
        }
    }
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
    if ($actual -cne $Expected) {
        throw "$Label SHA-256 mismatch"
    }
    return $actual
}

function Assert-ArchiveEntryList {
    param([Parameter(Mandatory)][string[]]$Entries)
    if ($Entries.Count -eq 0) { throw 'baseline archive is empty' }
    $expectedPrefix = "$($script:WorldName)/"
    foreach ($raw in $Entries) {
        $entry = ([string]$raw).Trim()
        if ([string]::IsNullOrWhiteSpace($entry) -or
            -not $entry.StartsWith($expectedPrefix, [StringComparison]::Ordinal) -or
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
        if ($required -cnotin $Entries) {
            throw "baseline archive is missing required entry: $required"
        }
    }
}

function Assert-ContainerContract {
    param(
        [Parameter(Mandatory)][object]$Container,
        [Parameter(Mandatory)][string]$Root
    )
    if ([string]$Container.Name -cne "/$($script:ContainerName)") {
        throw 'Docker inspect returned the wrong container'
    }
    if ([bool]$Container.State.Running -or [bool]$Container.State.Paused) {
        throw "$($script:ContainerName) must be fully stopped before world reset"
    }
    $dataMounts = @($Container.Mounts | Where-Object {
            [string]$_.Type -ceq 'bind' -and [string]$_.Destination -ceq '/data'
        })
    if ($dataMounts.Count -ne 1 -or
        -not (Test-SamePath ([string]$dataMounts[0].Source) $Root)) {
        throw 'container /data bind does not match the audited DataRoot'
    }
}

function ConvertFrom-DockerJson {
    param([Parameter(Mandatory)][string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) {
        throw 'Docker returned an empty JSON document'
    }
    # The JSON parser's configurable-depth option arrived after Windows PowerShell
    # 5.1. Docker inspect output is shallow enough for the 5.1-compatible default.
    return @($Json | ConvertFrom-Json)
}

function Get-DockerInspection {
    param([Parameter(Mandatory)][string]$Root)
    $raw = @(& docker container inspect $script:ContainerName 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "docker inspect failed for $($script:ContainerName)"
    }
    $containers = @(ConvertFrom-DockerJson -Json ($raw -join "`n"))
    if ($containers.Count -ne 1) {
        throw 'docker inspect did not return exactly one container'
    }
    Assert-ContainerContract -Container $containers[0] -Root $Root

    $runningIds = @(& docker ps --quiet --no-trunc 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'docker ps failed while checking bind users' }
    if ($runningIds.Count -gt 0) {
        $runningRaw = @(& docker container inspect @($runningIds) 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'docker inspect failed for running containers' }
        $running = @(ConvertFrom-DockerJson -Json ($runningRaw -join "`n"))
        foreach ($container in $running) {
            foreach ($mount in @($container.Mounts)) {
                $source = [string]$mount.Source
                if ([string]$mount.Type -ceq 'bind' -and
                    -not [string]::IsNullOrWhiteSpace($source) -and (
                        (Test-SamePath $source $Root) -or
                        (Test-DescendantPath -Candidate $source -Parent $Root) -or
                        (Test-DescendantPath -Candidate $Root -Parent $source))) {
                    throw "running container still binds DataRoot: $($container.Name)"
                }
            }
        }
    }
    return $containers[0]
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

function Invoke-McmcpHardBuildingGateWorldReset {
    $root = Assert-ExactKnownDataRoot $DataRoot
    Assert-NoReparsePoint -Path $root

    $archive = Join-Path $root 'tester-1.tar.gz'
    $correctedPlayer = Join-Path $root 'incoming-run05\hard-building-player.dat'
    $saveRoot = Join-Path $root 'prism\instances\MCMCP-Validation\minecraft\saves'
    $savePath = Join-Path $saveRoot $script:WorldName
    $backupRoot = Join-Path $root 'gate-world-backups'
    $stagingRoot = Join-Path $root 'gate-world-staging'
    $receiptRoot = Join-Path $root 'gate-world-reset-receipts'
    foreach ($knownPath in @(
            $archive, $correctedPlayer, $saveRoot, $savePath,
            $backupRoot, $stagingRoot, $receiptRoot)) {
        Assert-StrictDescendant -Path $knownPath -Root $root -Label 'known reset path'
    }
    if (-not (Test-Path -LiteralPath $saveRoot -PathType Container)) {
        throw "known save root is missing: $saveRoot"
    }
    Assert-NoReparsePoint -Path $saveRoot

    [void](Get-DockerInspection -Root $root)
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
    foreach ($path in @($staging, $stagedWorld, $backupDirectory, $backupWorld, $receiptPath)) {
        Assert-StrictDescendant -Path $path -Root $root -Label 'operation path'
    }

    foreach ($directory in @($backupRoot, $stagingRoot, $receiptRoot)) {
        if (-not (Test-Path -LiteralPath $directory)) {
            [void](New-Item -ItemType Directory -Path $directory -ErrorAction Stop)
        }
        Assert-NoReparsePoint -Path $directory
    }
    if ((Test-Path -LiteralPath $staging) -or
        (Test-Path -LiteralPath $backupDirectory) -or
        (Test-Path -LiteralPath $receiptPath)) {
        throw 'generated reset operation path unexpectedly already exists'
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

    $backupCreated = $false
    $promoted = $false
    try {
        if (Test-Path -LiteralPath $savePath) {
            Assert-NoReparsePoint -Path $savePath -Recurse
            Move-Item -LiteralPath $savePath -Destination $backupWorld -ErrorAction Stop
            $backupCreated = $true
        }
        Move-Item -LiteralPath $stagedWorld -Destination $savePath -ErrorAction Stop
        $promoted = $true
    } catch {
        if (-not $promoted -and $backupCreated -and
            -not (Test-Path -LiteralPath $savePath) -and
            (Test-Path -LiteralPath $backupWorld -PathType Container)) {
            try { Move-Item -LiteralPath $backupWorld -Destination $savePath -ErrorAction Stop } catch {
                throw "world promotion failed and automatic rollback also failed; backup=$backupWorld"
            }
        }
        throw
    }

    try {
        Assert-NoReparsePoint -Path $savePath -Recurse
        $restoredHashes = Get-WorldHashManifest -WorldRoot $savePath
        Assert-HashManifestEqual -Expected $stagedHashes -Actual $restoredHashes
        [void](Assert-KnownHash `
            -Path (Join-Path $savePath "players\data\$($script:PlayerUuid).dat") `
            -Expected $script:PlayerSha256 -Label 'restored corrected player')
    } catch {
        $failedWorld = Join-Path $staging 'failed-restored-world'
        if (Test-Path -LiteralPath $savePath -PathType Container) {
            Move-Item -LiteralPath $savePath -Destination $failedWorld -ErrorAction Stop
        }
        if ($backupCreated -and (Test-Path -LiteralPath $backupWorld -PathType Container)) {
            Move-Item -LiteralPath $backupWorld -Destination $savePath -ErrorAction Stop
        }
        throw "post-restore verification failed; prior save restored when present; failed world=$failedWorld"
    }

    # The staged world was moved atomically on the same volume, leaving only an
    # empty operation directory. Never recursively delete any computed path.
    if (@(Get-ChildItem -LiteralPath $staging -Force).Count -eq 0) {
        [IO.Directory]::Delete($staging, $false)
    }
    $receipt = [ordered]@{
        schema_version = 1
        operation_id = $operationId
        gate = $Gate
        data_root = $root
        container = $script:ContainerName
        container_stopped = $true
        archive_sha256 = $archiveHash
        corrected_player_sha256 = $playerHash
        previous_save_present = $backupCreated
        previous_save_backup = if ($backupCreated) { $backupWorld } else { $null }
        restored_world = $savePath
        restored_hashes = $restoredHashes
        completed_utc = [DateTimeOffset]::UtcNow.ToString('O')
    }
    [IO.File]::WriteAllText(
        $receiptPath,
        (ConvertTo-Json $receipt -Depth 20),
        $script:Utf8NoBom)
    return $receipt
}

if (-not $LibraryOnly) {
    Invoke-McmcpHardBuildingGateWorldReset | ConvertTo-Json -Depth 20
}
