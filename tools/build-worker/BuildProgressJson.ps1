#requires -Version 7.4
# Receipt arrays are immutable to the worker; only record_blueprint.py appends them.
function Read-BuildProgressJson {
    param([Parameter(Mandatory)][string]$Path)
    $inputMemory = [IO.MemoryStream]::new()
    $documentOptions = [Text.Json.JsonDocumentOptions]::new()
    $documentOptions.MaxDepth = 100
    try {
        $stream = [IO.File]::Open($Path, 'Open', 'Read',
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        try { $stream.CopyTo($inputMemory) } finally { $stream.Dispose() }
        $bytes = $inputMemory.GetBuffer()
        $inputMemory.Position = if ($inputMemory.Length -ge 3 -and
            $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) { 3 } else { 0 }
        # Parse after closing the shared file; the stream overload avoids large-string conversion.
        $document = [Text.Json.JsonDocument]::Parse($inputMemory, $documentOptions)
    } finally { $inputMemory.Dispose() }
    $memory = [IO.MemoryStream]::new()
    $writer = [Text.Json.Utf8JsonWriter]::new($memory)
    try {
        $root = $document.RootElement
        if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw 'invalid_progress_object' }
        $receipts = @{}
        $writer.WriteStartObject()
        foreach ($property in $root.EnumerateObject()) {
            if ($property.Name -cin @('floor_receipts', 'torch_receipts')) {
                if ($receipts.ContainsKey($property.Name) -or
                    $property.Value.ValueKind -ne [Text.Json.JsonValueKind]::Array) {
                    throw 'invalid_progress_receipts'
                }
                $receipts[$property.Name] = $property.Value.Clone()
            } else { $property.WriteTo($writer) }
        }
        if ($receipts.Count -ne 2) { throw 'missing_progress_receipts' }
        $writer.WriteEndObject()
        $writer.Flush()
        $value = [Text.Encoding]::UTF8.GetString($memory.ToArray()) |
            ConvertFrom-Json -AsHashtable -Depth 100
        foreach ($name in $receipts.Keys) { $value[$name] = $receipts[$name] }
        return $value
    } finally {
        $writer.Dispose()
        $memory.Dispose()
        $document.Dispose()
    }
}

function Get-BuildProgressLastFloor {
    param([Parameter(Mandatory)]$Progress)
    $floors = $Progress.floor_receipts
    $count = $floors.GetArrayLength()
    if ($count -lt 1) { throw 'missing_confirmed_floor' }
    return $floors[$count - 1].GetRawText() | ConvertFrom-Json -Depth 100
}

# Writes only a temporary file. The caller retains its lock, ownership checks and atomic rename.
function Write-BuildProgressJsonTemp {
    param([Parameter(Mandatory)]$Progress, [Parameter(Mandatory)][string]$Path)
    $metadata = [ordered]@{}
    foreach ($entry in $Progress.GetEnumerator()) {
        if ($entry.Key -cnotin @('floor_receipts', 'torch_receipts')) {
            $metadata[$entry.Key] = $entry.Value
        }
    }
    foreach ($name in @('floor_receipts', 'torch_receipts')) {
        if ($Progress[$name] -isnot [Text.Json.JsonElement] -or
            $Progress[$name].ValueKind -ne [Text.Json.JsonValueKind]::Array) {
            throw 'invalid_progress_receipts'
        }
    }
    # Keep PowerShell's handling of PSCustomObject and timestamps for mutable fields.
    $documentOptions = [Text.Json.JsonDocumentOptions]::new()
    $documentOptions.MaxDepth = 100
    $document = [Text.Json.JsonDocument]::Parse(($metadata | ConvertTo-Json -Depth 100), $documentOptions)
    $stream = $null
    $writer = $null
    try {
        $stream = [IO.File]::Open($Path, 'Create', 'Write', 'None')
        $options = [Text.Json.JsonWriterOptions]::new()
        $options.Indented = $true
        $writer = [Text.Json.Utf8JsonWriter]::new($stream, $options)
        $writer.WriteStartObject()
        foreach ($property in $document.RootElement.EnumerateObject()) { $property.WriteTo($writer) }
        foreach ($name in @('floor_receipts', 'torch_receipts')) {
            $writer.WritePropertyName($name)
            $Progress[$name].WriteTo($writer)
        }
        $writer.WriteEndObject()
        $writer.Flush()
    } finally {
        if ($writer) { $writer.Dispose() }
        if ($stream) { $stream.Dispose() }
        $document.Dispose()
    }
}
