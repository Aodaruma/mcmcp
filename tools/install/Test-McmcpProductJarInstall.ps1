[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$buildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../build'))
$testRoot = Join-Path $buildRoot ('install-test-' + [Guid]::NewGuid().ToString('N'))
$source = Join-Path $testRoot 'source.jar'
$mods = Join-Path $testRoot 'mods'
$destination = Join-Path $mods 'mcmcp.jar'
$javaRunning = $false

# Only process discovery is stubbed. Real temporary ZIPs, hash checks and atomic replacement run.
function Get-CimInstance {
    param([string]$ClassName, [string]$Filter)
    if ($javaRunning) { [pscustomobject]@{ CommandLine='fixture-java' } }
}
function Write-Product([string]$Path, [string]$Payload) {
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = [IO.StreamWriter]::new($zip.CreateEntry('dev/aod/mcmcp/McmcpMod.class').Open())
        try { $writer.Write($Payload) } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
}
function Invoke-Installer([string]$Hash, [bool]$Install, [string]$ExpectedError = '') {
    $result = Join-Path $testRoot ([Guid]::NewGuid().ToString('N') + '.json')
    $parameters = @{ SourceJar=$source; ModsDirectory=$mods; ExpectedSourceSha256=$Hash;
        ResultPath=$result; Install=$Install }
    try {
        $null = & (Join-Path $PSScriptRoot 'Install-McmcpProductJar.ps1') @parameters
        if ($ExpectedError) { throw 'Expected installer refusal did not occur.' }
    } catch {
        if (-not $ExpectedError -or $_.Exception.Message -notlike ('*' + $ExpectedError + '*')) { throw }
    }
    return Get-Content -LiteralPath $result -Raw -Encoding UTF8 | ConvertFrom-Json
}
try {
    $null = New-Item -ItemType Directory -Path $mods -Force
    Write-Product $source 'new-fixture'
    Write-Product $destination 'old-fixture'
    $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $oldHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    $check = Invoke-Installer $hash $false
    if ($check.installed -or $check.product_count -ne 1) { throw 'Preflight changed destination or counted wrong.' }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $oldHash) {
        throw 'Preflight changed the existing product.'
    }
    $null = Invoke-Installer ('0' * 64) $true 'Source JAR hash mismatch'
    Copy-Item -LiteralPath $destination -Destination (Join-Path $mods 'duplicate.jar')
    $null = Invoke-Installer $hash $true 'exactly one'
    [IO.File]::SetAttributes((Join-Path $mods 'duplicate.jar'), [IO.FileAttributes]::Hidden)
    $null = Invoke-Installer $hash $true 'exactly one'
    Remove-Item -LiteralPath (Join-Path $mods 'duplicate.jar') -Force
    $existingResult = Join-Path $testRoot 'existing.json'
    [IO.File]::WriteAllText($existingResult, 'preserve-this-file')
    foreach ($unsafeOutput in @($source, $destination, $existingResult)) {
        $parameters = @{ SourceJar=$source; ModsDirectory=$mods; ExpectedSourceSha256=$hash;
            ResultPath=$unsafeOutput; Install=$true }
        $refused = $false
        try { $null = & (Join-Path $PSScriptRoot 'Install-McmcpProductJar.ps1') @parameters }
        catch { $refused = $true }
        if (-not $refused) { throw 'Unsafe result path was accepted.' }
    }
    if ([IO.File]::ReadAllText($existingResult) -cne 'preserve-this-file' -or
        (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -cne $hash -or
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $oldHash) {
        throw 'Unsafe output path changed an existing file.'
    }
    foreach ($relativePath in @('C:source.jar', '\source.jar')) {
        $refused = $false
        try {
            $null = & (Join-Path $PSScriptRoot 'Install-McmcpProductJar.ps1') -SourceJar $relativePath `
                -ModsDirectory $mods -ExpectedSourceSha256 $hash -ResultPath (Join-Path $testRoot 'relative.json')
        } catch { $refused = $_.Exception.Message -like '*fully qualified*' }
        if (-not $refused) { throw 'Drive-relative or root-relative path was accepted.' }
    }
    $javaRunning = $true
    $null = Invoke-Installer $hash $true 'Java process'
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $oldHash) {
        throw 'Refused installation changed the existing product.'
    }
    $javaRunning = $false
    $installed = Invoke-Installer $hash $true
    if (-not $installed.installed -or $installed.installed_sha256 -ine $hash -or
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $hash) {
        throw 'Atomic installation failed.'
    }
    if (@(Get-ChildItem -LiteralPath $mods -Force).Count -ne 1) { throw 'Unexpected staging file or backup remains.' }
    'MCMCP product installer tests passed (11 cases).'
} finally {
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolvedTestRoot -Leaf) -like 'install-test-*') {
        if (Test-Path -LiteralPath $resolvedTestRoot) { Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force }
    }
}
