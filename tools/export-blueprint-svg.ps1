#requires -Version 7.4

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InputPath,

    [string] $OutputDirectory,

    [switch] $ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$maximumCells = 4194304
$maximumAxis = 256
$maximumChunks = 64
$maximumJsonBytes = 67108864

function Read-BoundedJsonText([string] $Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer) {
        throw [IO.InvalidDataException]::new('InputPath must be a JSON or JSON.gz file.')
    }
    $input = [IO.File]::OpenRead($file.FullName)
    $payload = $null
    try {
        if ($file.Name.EndsWith('.gz', [StringComparison]::OrdinalIgnoreCase)) {
            $payload = [IO.Compression.GZipStream]::new(
                $input, [IO.Compression.CompressionMode]::Decompress, $false)
        } else {
            $payload = $input
        }
        $memory = [IO.MemoryStream]::new()
        try {
            $buffer = [byte[]]::new(8192)
            while (($read = $payload.Read($buffer, 0, $buffer.Length)) -gt 0) {
                if ($memory.Length + $read -gt $maximumJsonBytes) {
                    throw [IO.InvalidDataException]::new('Decompressed artifact exceeds 64 MiB.')
                }
                $memory.Write($buffer, 0, $read)
            }
            $bytes = $memory.ToArray()
            $offset = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and
                $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 3 } else { 0 }
            return [Text.UTF8Encoding]::new($false, $true).GetString(
                $bytes, $offset, $bytes.Length - $offset)
        }
        finally {
            $memory.Dispose()
        }
    }
    finally {
        if ($null -ne $payload -and $payload -ne $input) { $payload.Dispose() }
        $input.Dispose()
    }
}

function Get-Map([object] $Value, [string] $Path) {
    if ($Value -isnot [Collections.IDictionary]) {
        throw [IO.InvalidDataException]::new("$Path must be an object.")
    }
    return $Value
}

function Get-Array([object] $Value, [string] $Path) {
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [Collections.IDictionary]) {
        throw [IO.InvalidDataException]::new("$Path must be an array.")
    }
    return @($Value)
}

function Get-Required([Collections.IDictionary] $Map, [string] $Name, [string] $Path) {
    if (-not $Map.Contains($Name)) {
        throw [IO.InvalidDataException]::new("$Path.$Name is required.")
    }
    return $Map[$Name]
}

function Convert-ToInteger(
    [object] $Value,
    [string] $Path,
    [long] $Minimum,
    [long] $Maximum
) {
    if ($Value -is [bool] -or $Value -isnot [ValueType]) {
        throw [IO.InvalidDataException]::new("$Path must be an integer.")
    }
    $number = [double] $Value
    $integer = [long] $Value
    if (-not [double]::IsFinite($number) -or $number -ne $integer -or
        $integer -lt $Minimum -or $integer -gt $Maximum) {
        throw [IO.InvalidDataException]::new("$Path must be an integer in $Minimum..$Maximum.")
    }
    return $integer
}

function Get-RegistryId([object] $Value, [string] $Path) {
    if ($Value -isnot [string]) {
        throw [IO.InvalidDataException]::new("$Path must be a registry id.")
    }
    $text = [string] $Value
    if ($text -cnotmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$' -or $text.Length -gt 256) {
        throw [IO.InvalidDataException]::new("$Path must be a registry id.")
    }
    return $text
}

function Get-OrdinalKeys([Collections.IDictionary] $Map) {
    [string[]] $keys = @($Map.Keys | ForEach-Object { [string] $_ })
    [Array]::Sort($keys, [StringComparer]::Ordinal)
    return $keys
}

function Get-StateKey([Collections.IDictionary] $State) {
    $block = Get-RegistryId (Get-Required $State 'block' 'palette.state') 'palette.state.block'
    $properties = Get-Map (Get-Required $State 'properties' 'palette.state') 'palette.state.properties'
    $pairs = @(Get-OrdinalKeys $properties | ForEach-Object {
            $name = [string] $_
            if ($name -cnotmatch '^[a-z0-9_]+$') {
                throw [IO.InvalidDataException]::new('BlockState property name is invalid.')
            }
            if ($properties[$name] -isnot [string]) {
                throw [IO.InvalidDataException]::new('BlockState property value must be a string.')
            }
            $value = [string] $properties[$name]
            if ([string]::IsNullOrEmpty($value) -or $value.Length -gt 64) {
                throw [IO.InvalidDataException]::new('BlockState property value is invalid.')
            }
            "$name=$value"
        })
    if ($pairs.Count -eq 0) {
        return $block
    }
    return "$block[$($pairs -join ',')]"
}

function Get-StateColor([string] $StateKey, [string] $Block) {
    if ($Block -ceq 'minecraft:air') { return '#ffffff' }
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($StateKey))
    }
    finally {
        $sha256.Dispose()
    }
    return '#{0:X2}{1:X2}{2:X2}' -f `
        (72 + ($digest[0] % 144)), (72 + ($digest[1] % 144)), (72 + ($digest[2] % 144))
}

function Convert-ToXmlText([object] $Value, [int] $MaximumLength = 160) {
    $text = [string] $Value
    if ($text.Length -gt $MaximumLength) {
        $text = $text.Substring(0, $MaximumLength - 1) + [char] 0x2026
    }
    return [Security.SecurityElement]::Escape($text)
}

function Get-ChunkCoordinate([long] $BlockCoordinate) {
    return [long] [Math]::Floor($BlockCoordinate / 16.0)
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$document = Read-BoundedJsonText $resolvedInput | ConvertFrom-Json -AsHashtable -Depth 100
$root = Get-Map $document 'artifact'
if ([string] (Get-Required $root 'schema' 'artifact') -cne 'mcmcp.creative-blueprint-artifact/v1') {
    throw [IO.InvalidDataException]::new(
        'artifact.schema must be mcmcp.creative-blueprint-artifact/v1.')
}

$blueprint = Get-Map (Get-Required $root 'blueprint' 'artifact') 'artifact.blueprint'
if ([string] (Get-Required $blueprint 'schema' 'blueprint') -cne 'mcmcp.blueprint-palette-rle/v1') {
    throw [IO.InvalidDataException]::new(
        'blueprint.schema must be mcmcp.blueprint-palette-rle/v1.')
}
$hash = [string] (Get-Required $blueprint 'hash' 'blueprint')
if ($hash -cnotmatch '^sha256:[0-9a-f]{64}$') {
    throw [IO.InvalidDataException]::new('blueprint.hash is not a SHA-256 fingerprint.')
}

$anchor = Get-Map (Get-Required $blueprint 'anchor' 'blueprint') 'blueprint.anchor'
$dimension = Get-RegistryId (Get-Required $anchor 'dimension' 'blueprint.anchor') 'blueprint.anchor.dimension'
$anchorX = Convert-ToInteger (Get-Required $anchor 'x' 'blueprint.anchor') 'blueprint.anchor.x' -30000000 29999999
$anchorY = Convert-ToInteger (Get-Required $anchor 'y' 'blueprint.anchor') 'blueprint.anchor.y' -2048 2047
$anchorZ = Convert-ToInteger (Get-Required $anchor 'z' 'blueprint.anchor') 'blueprint.anchor.z' -30000000 29999999

$transform = Get-Map (Get-Required $blueprint 'transform' 'blueprint') 'blueprint.transform'
if ((Convert-ToInteger (Get-Required $transform 'rotation' 'blueprint.transform') `
        'blueprint.transform.rotation' 0 0) -ne 0 -or
    (Get-Required $transform 'mirror' 'blueprint.transform') -isnot [string] -or
    [string] (Get-Required $transform 'mirror' 'blueprint.transform') -cne 'none') {
    throw [IO.InvalidDataException]::new('blueprint.transform must be rotation=0, mirror=none.')
}

$encoding = Get-Map (Get-Required $blueprint 'encoding' 'blueprint') 'blueprint.encoding'
if ([string] (Get-Required $encoding 'ordering' 'blueprint.encoding') -cne
        'chunk_z_x_then_y_z_x_within_clipped_chunk') {
    throw [IO.InvalidDataException]::new('Unsupported blueprint RLE ordering.')
}
if ((Get-Required $encoding 'hash_ordering' 'blueprint.encoding') -isnot [string] -or
    [string] (Get-Required $encoding 'hash_ordering' 'blueprint.encoding') -cne 'y_z_x') {
    throw [IO.InvalidDataException]::new('blueprint.encoding.hash_ordering must be y_z_x.')
}
$size = Get-Map (Get-Required $encoding 'size' 'blueprint.encoding') 'blueprint.encoding.size'
$sizeX = [int] (Convert-ToInteger (Get-Required $size 'x' 'blueprint.encoding.size') 'size.x' 1 $maximumAxis)
$sizeY = [int] (Convert-ToInteger (Get-Required $size 'y' 'blueprint.encoding.size') 'size.y' 1 $maximumAxis)
$sizeZ = [int] (Convert-ToInteger (Get-Required $size 'z' 'blueprint.encoding.size') 'size.z' 1 $maximumAxis)
$volume = [long] $sizeX * $sizeY * $sizeZ
if ($volume -gt $maximumCells) {
    throw [IO.InvalidDataException]::new("Blueprint exceeds $maximumCells cells.")
}
$maxX = $anchorX + $sizeX - 1
$maxY = $anchorY + $sizeY - 1
$maxZ = $anchorZ + $sizeZ - 1
$chunkMinX = Get-ChunkCoordinate $anchorX
$chunkMaxX = Get-ChunkCoordinate $maxX
$chunkMinZ = Get-ChunkCoordinate $anchorZ
$chunkMaxZ = Get-ChunkCoordinate $maxZ
$chunkCount = ($chunkMaxX - $chunkMinX + 1) * ($chunkMaxZ - $chunkMinZ + 1)
if ($chunkCount -gt $maximumChunks) {
    throw [IO.InvalidDataException]::new("Blueprint intersects more than $maximumChunks chunk columns.")
}

$palette = @(Get-Array (Get-Required $blueprint 'palette' 'blueprint') 'blueprint.palette')
if ($palette.Count -lt 1 -or $palette.Count -gt 65536) {
    throw [IO.InvalidDataException]::new('blueprint.palette must contain 1..65536 entries.')
}
$paletteIndexById = @{}
$stateByPalette = [string[]]::new($palette.Count)
$canonicalByPalette = [string[]]::new($palette.Count)
$blockByPalette = [string[]]::new($palette.Count)
$colorByPalette = [string[]]::new($palette.Count)
$declaredPaletteCounts = [long[]]::new($palette.Count)
$stateDetails = @{}
for ($index = 0; $index -lt $palette.Count; $index++) {
    $entry = Get-Map $palette[$index] "blueprint.palette[$index]"
    $id = [string] (Get-Required $entry 'palette_id' "blueprint.palette[$index]")
    if ($id -cnotmatch '^p[0-9]{6}$' -or $paletteIndexById.ContainsKey($id)) {
        throw [IO.InvalidDataException]::new("Invalid or duplicate palette id: $id")
    }
    $state = Get-Map (Get-Required $entry 'state' "blueprint.palette[$index]") 'palette.state'
    $stateKey = Get-StateKey $state
    $block = Get-RegistryId (Get-Required $state 'block' 'palette.state') 'palette.state.block'
    $properties = Get-Map (Get-Required $state 'properties' 'palette.state') 'palette.state.properties'
    $canonicalPairs = @(Get-OrdinalKeys $properties | ForEach-Object {
            "$_=$($properties[$_])"
        })
    $paletteIndexById[$id] = $index
    $stateByPalette[$index] = $stateKey
    $canonicalByPalette[$index] = if ($canonicalPairs.Count -eq 0) {
        $block
    } else {
        "$block|$($canonicalPairs -join '|')"
    }
    $blockByPalette[$index] = $block
    $colorByPalette[$index] = Get-StateColor $stateKey $block
    $declaredPaletteCounts[$index] = Convert-ToInteger `
        (Get-Required $entry 'count' "blueprint.palette[$index]") `
        "blueprint.palette[$index].count" 1 $maximumCells
    $stateDetails[$stateKey] = [ordered]@{ Block = $block; Color = $colorByPalette[$index] }
}

$runs = @(Get-Array (Get-Required $blueprint 'runs' 'blueprint') 'blueprint.runs')
if ($runs.Count -lt 1) {
    throw [IO.InvalidDataException]::new('blueprint.runs must not be empty.')
}
$runPalette = [int[]]::new($runs.Count)
$runLengths = [long[]]::new($runs.Count)
$actualPaletteCounts = [long[]]::new($palette.Count)
$runTotal = 0L
$previousPalette = -1
for ($index = 0; $index -lt $runs.Count; $index++) {
    $run = Get-Map $runs[$index] "blueprint.runs[$index]"
    $id = [string] (Get-Required $run 'palette_id' "blueprint.runs[$index]")
    if (-not $paletteIndexById.ContainsKey($id)) {
        throw [IO.InvalidDataException]::new("Run references unknown palette id: $id")
    }
    $paletteIndex = [int] $paletteIndexById[$id]
    if ($paletteIndex -eq $previousPalette) {
        throw [IO.InvalidDataException]::new('Adjacent RLE runs must use different palette ids.')
    }
    $count = Convert-ToInteger (Get-Required $run 'count' "blueprint.runs[$index]") `
        "blueprint.runs[$index].count" 1 $maximumCells
    $runPalette[$index] = $paletteIndex
    $runLengths[$index] = $count
    $actualPaletteCounts[$paletteIndex] += $count
    $runTotal += $count
    if ($runTotal -gt $volume) {
        throw [IO.InvalidDataException]::new('RLE runs exceed the blueprint volume.')
    }
    $previousPalette = $paletteIndex
}
if ($runTotal -ne $volume) {
    throw [IO.InvalidDataException]::new("RLE cell count mismatch: expected=$volume actual=$runTotal")
}
for ($index = 0; $index -lt $palette.Count; $index++) {
    if ($actualPaletteCounts[$index] -ne $declaredPaletteCounts[$index]) {
        throw [IO.InvalidDataException]::new("Palette count mismatch at index $index.")
    }
}

$basis = Get-Map (Get-Required $root 'basis' 'artifact') 'artifact.basis'
if ([string] (Get-Required $basis 'dimension' 'artifact.basis') -cne $dimension -or
    (Convert-ToInteger (Get-Required $basis 'volume' 'artifact.basis') `
        'artifact.basis.volume' 1 $maximumCells) -ne $volume -or
    [string] (Get-Required $basis 'consistency' 'artifact.basis') -cne 'server_thread_chunk_sequence') {
    throw [IO.InvalidDataException]::new('Artifact basis does not match the blueprint.')
}
$basisRegion = Get-Map (Get-Required $basis 'region' 'artifact.basis') 'artifact.basis.region'
$basisMin = Get-Map (Get-Required $basisRegion 'min' 'artifact.basis.region') 'artifact.basis.region.min'
$basisMax = Get-Map (Get-Required $basisRegion 'max' 'artifact.basis.region') 'artifact.basis.region.max'
$expectedCoordinates = @(
    @($basisMin, 'x', $anchorX, -30000000, 29999999, 'artifact.basis.region.min.x'),
    @($basisMin, 'y', $anchorY, -2048, 2047, 'artifact.basis.region.min.y'),
    @($basisMin, 'z', $anchorZ, -30000000, 29999999, 'artifact.basis.region.min.z'),
    @($basisMax, 'x', $maxX, -30000000, 29999999, 'artifact.basis.region.max.x'),
    @($basisMax, 'y', $maxY, -2048, 2047, 'artifact.basis.region.max.y'),
    @($basisMax, 'z', $maxZ, -30000000, 29999999, 'artifact.basis.region.max.z')
)
foreach ($coordinate in $expectedCoordinates) {
    $actual = Convert-ToInteger `
        (Get-Required $coordinate[0] $coordinate[1] $coordinate[5]) `
        $coordinate[5] $coordinate[3] $coordinate[4]
    if ($actual -ne $coordinate[2]) {
        throw [IO.InvalidDataException]::new('artifact.basis.region does not match the blueprint.')
    }
}
$startedServerTick = Convert-ToInteger `
    (Get-Required $basis 'started_server_tick' 'artifact.basis') `
    'basis.started_server_tick' 0 ([int]::MaxValue)
$completedServerTick = Convert-ToInteger `
    (Get-Required $basis 'completed_server_tick' 'artifact.basis') `
    'basis.completed_server_tick' 0 ([int]::MaxValue)
if ($completedServerTick -lt $startedServerTick) {
    throw [IO.InvalidDataException]::new('completed_server_tick precedes started_server_tick.')
}

$cells = [int[]]::new([int] $volume)
$runIndex = 0
$runRemaining = $runLengths[0]
$currentPalette = $runPalette[0]
for ($chunkZ = $chunkMinZ; $chunkZ -le $chunkMaxZ; $chunkZ++) {
    $clippedMinZ = [Math]::Max($anchorZ, $chunkZ * 16)
    $clippedMaxZ = [Math]::Min($maxZ, $chunkZ * 16 + 15)
    for ($chunkX = $chunkMinX; $chunkX -le $chunkMaxX; $chunkX++) {
        $clippedMinX = [Math]::Max($anchorX, $chunkX * 16)
        $clippedMaxX = [Math]::Min($maxX, $chunkX * 16 + 15)
        $rowWidth = [int] ($clippedMaxX - $clippedMinX + 1)
        for ($relativeY = 0; $relativeY -lt $sizeY; $relativeY++) {
            for ($absoluteZ = $clippedMinZ; $absoluteZ -le $clippedMaxZ; $absoluteZ++) {
                $globalIndex = [int] (([long] $relativeY * $sizeZ +
                        ($absoluteZ - $anchorZ)) * $sizeX + ($clippedMinX - $anchorX))
                $rowRemaining = $rowWidth
                while ($rowRemaining -gt 0) {
                    if ($runRemaining -eq 0) {
                        $runIndex++
                        if ($runIndex -ge $runs.Count) {
                            throw [IO.InvalidDataException]::new('RLE stream ended before the region was decoded.')
                        }
                        $runRemaining = $runLengths[$runIndex]
                        $currentPalette = $runPalette[$runIndex]
                    }
                    $take = [int] [Math]::Min([long] $rowRemaining, $runRemaining)
                    [Array]::Fill[int]($cells, $currentPalette, $globalIndex, $take)
                    $globalIndex += $take
                    $rowRemaining -= $take
                    $runRemaining -= $take
                }
            }
        }
    }
}
if ($runIndex -ne $runs.Count - 1 -or $runRemaining -ne 0) {
    throw [IO.InvalidDataException]::new('RLE stream contains unused cells after decoding.')
}

$blueprintDigest = [Security.Cryptography.IncrementalHash]::CreateHash(
    [Security.Cryptography.HashAlgorithmName]::SHA256)
try {
    $blueprintDigest.AppendData([Text.Encoding]::UTF8.GetBytes('mcmcp.blueprint/v1'))
    for ($index = 0; $index -lt $cells.Length; $index++) {
        $relativeX = $index % $sizeX
        $remaining = [Math]::Floor($index / $sizeX)
        $relativeZ = $remaining % $sizeZ
        $relativeY = [Math]::Floor($remaining / $sizeZ)
        $canonical = "`n$relativeX|$relativeY|$relativeZ|$($canonicalByPalette[$cells[$index]])"
        $blueprintDigest.AppendData([Text.Encoding]::UTF8.GetBytes($canonical))
    }
    $computedHash = 'sha256:' + [Convert]::ToHexString(
        $blueprintDigest.GetHashAndReset()).ToLowerInvariant()
}
finally {
    $blueprintDigest.Dispose()
}
if ($computedHash -cne $hash) {
    throw [IO.InvalidDataException]::new('blueprint.hash does not match the decoded cells.')
}

$summary = [ordered]@{
    schema = 'mcmcp.blueprint-svg/v1'
    blueprint_hash = $hash
    dimension = $dimension
    anchor = [ordered]@{ x = $anchorX; y = $anchorY; z = $anchorZ }
    size = [ordered]@{ x = $sizeX; y = $sizeY; z = $sizeZ }
    cell_count = $volume
    chunk_columns = $chunkCount
    layer_count = $sizeY
    consistency = 'server_thread_chunk_sequence'
    started_server_tick = $startedServerTick
    completed_server_tick = $completedServerTick
    output_directory = $null
    files = @()
}
if ($ValidateOnly) {
    [pscustomobject] $summary
    return
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $baseName = [IO.Path]::GetFileNameWithoutExtension($resolvedInput)
    if ($baseName.EndsWith('.json', [StringComparison]::OrdinalIgnoreCase)) {
        $baseName = [IO.Path]::GetFileNameWithoutExtension($baseName)
    }
    $shortHash = $hash.Substring(7, 12)
    $OutputDirectory = Join-Path ([IO.Path]::GetDirectoryName($resolvedInput)) "$baseName-$shortHash-svg"
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

$horizontalMaximum = [Math]::Max($sizeX, $sizeZ)
$cellSize = if ($horizontalMaximum -le 32) { 24 } elseif ($horizontalMaximum -le 64) { 12 } `
    elseif ($horizontalMaximum -le 128) { 6 } else { 4 }
$leftMargin = 74
$topMargin = 92
$legendWidth = 470
$bottomMargin = 48
$legendLimit = 64
$writtenFiles = [Collections.Generic.List[string]]::new()

for ($relativeY = 0; $relativeY -lt $sizeY; $relativeY++) {
    $layerCounts = @{}
    $rectangles = [Text.StringBuilder]::new()
    for ($relativeZ = 0; $relativeZ -lt $sizeZ; $relativeZ++) {
        $rowBase = [int] (([long] $relativeY * $sizeZ + $relativeZ) * $sizeX)
        $x = 0
        while ($x -lt $sizeX) {
            $paletteIndex = $cells[$rowBase + $x]
            $stateKey = $stateByPalette[$paletteIndex]
            $end = $x + 1
            while ($end -lt $sizeX -and
                $stateByPalette[$cells[$rowBase + $end]] -ceq $stateKey) { $end++ }
            $length = $end - $x
            $previousCount = if ($layerCounts.ContainsKey($stateKey)) { [long] $layerCounts[$stateKey] } else { 0L }
            $layerCounts[$stateKey] = $previousCount + $length
            $svgX = $leftMargin + $x * $cellSize
            $svgY = $topMargin + $relativeZ * $cellSize
            $title = Convert-ToXmlText "$stateKey @ x=$($anchorX + $x)..$($anchorX + $end - 1), z=$($anchorZ + $relativeZ)"
            [void] $rectangles.Append(
                "<rect x='$svgX' y='$svgY' width='$($length * $cellSize)' height='$cellSize' fill='$($colorByPalette[$paletteIndex])'><title>$title</title></rect>`n")
            $x = $end
        }
    }

    $layerStates = @($layerCounts.Keys | Sort-Object)
    $shownStates = @($layerStates | Select-Object -First $legendLimit)
    $gridWidth = $sizeX * $cellSize
    $gridHeight = $sizeZ * $cellSize
    $extraLegendHeight = if ($layerStates.Count -gt $legendLimit) { 28 } else { 0 }
    $legendHeight = 76 + ($shownStates.Count * 24) + $extraLegendHeight
    $svgWidth = $leftMargin + $gridWidth + $legendWidth
    $svgHeight = [Math]::Max($topMargin + $gridHeight + $bottomMargin, $topMargin + $legendHeight)
    $absoluteY = $anchorY + $relativeY
    $title = Convert-ToXmlText "Blueprint layer y=$relativeY (absolute Y=$absoluteY)"
    $description = Convert-ToXmlText "$dimension anchor=($anchorX,$anchorY,$anchorZ) hash=$($hash.Substring(7,16))"
    $svg = [Text.StringBuilder]::new()
    [void] $svg.Append("<svg xmlns='http://www.w3.org/2000/svg' width='$svgWidth' height='$svgHeight' viewBox='0 0 $svgWidth $svgHeight'>`n")
    [void] $svg.Append("<rect width='100%' height='100%' fill='#f7f7f5'/>`n")
    [void] $svg.Append("<text x='24' y='34' font-family='sans-serif' font-size='20' font-weight='bold'>$title</text>`n")
    [void] $svg.Append("<text x='24' y='58' font-family='monospace' font-size='12' fill='#444'>$description</text>`n")
    [void] $svg.Append($rectangles.ToString())
    [void] $svg.Append("<rect x='$leftMargin' y='$topMargin' width='$gridWidth' height='$gridHeight' fill='none' stroke='#222' stroke-width='1'/>`n")
    if ($cellSize -ge 8) {
        for ($x = 1; $x -lt $sizeX; $x++) {
            $lineX = $leftMargin + $x * $cellSize
            [void] $svg.Append("<line x1='$lineX' y1='$topMargin' x2='$lineX' y2='$($topMargin + $gridHeight)' stroke='#d0d0d0' stroke-width='0.5'/>`n")
        }
        for ($z = 1; $z -lt $sizeZ; $z++) {
            $lineY = $topMargin + $z * $cellSize
            [void] $svg.Append("<line x1='$leftMargin' y1='$lineY' x2='$($leftMargin + $gridWidth)' y2='$lineY' stroke='#d0d0d0' stroke-width='0.5'/>`n")
        }
    }
    [void] $svg.Append("<text x='$($leftMargin + [Math]::Max(0,$gridWidth/2 - 20))' y='$($topMargin - 12)' font-family='sans-serif' font-size='12'>X →</text>`n")
    [void] $svg.Append("<text x='20' y='$($topMargin + [Math]::Max(18,$gridHeight/2))' font-family='sans-serif' font-size='12'>Z ↓</text>`n")
    $legendX = $leftMargin + $gridWidth + 28
    [void] $svg.Append("<text x='$legendX' y='$($topMargin + 18)' font-family='sans-serif' font-size='15' font-weight='bold'>BlockState palette</text>`n")
    for ($index = 0; $index -lt $shownStates.Count; $index++) {
        $stateKey = [string] $shownStates[$index]
        $lineY = $topMargin + 48 + $index * 24
        $color = [string] $stateDetails[$stateKey].Color
        $label = Convert-ToXmlText "$stateKey × $($layerCounts[$stateKey])" 74
        [void] $svg.Append("<rect x='$legendX' y='$($lineY - 13)' width='14' height='14' fill='$color' stroke='#555'/><text x='$($legendX + 22)' y='$lineY' font-family='monospace' font-size='11'>$label</text>`n")
    }
    if ($layerStates.Count -gt $legendLimit) {
        $lineY = $topMargin + 52 + $shownStates.Count * 24
        [void] $svg.Append("<text x='$legendX' y='$lineY' font-family='sans-serif' font-size='11'>legend truncated: $($layerStates.Count - $legendLimit) more states</text>`n")
    }
    [void] $svg.Append('</svg>')

    $relativeLabel = '{0:+000;-000;000}' -f $relativeY
    $absoluteLabel = '{0:+0000;-0000;0000}' -f $absoluteY
    $fileName = "layer-y$relativeLabel-abs$absoluteLabel.svg"
    $path = Join-Path $resolvedOutput $fileName
    [IO.File]::WriteAllText($path, $svg.ToString(), [Text.UTF8Encoding]::new($false))
    $writtenFiles.Add($path)
}

$summary.output_directory = $resolvedOutput
$summary.files = $writtenFiles.ToArray()
[pscustomobject] $summary
