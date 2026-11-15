[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SourceJar,
    [Parameter(Mandatory)][string]$ModsDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSourceSha256,
    [Parameter(Mandatory)][string]$ResultPath,
    [switch]$Install
)

# Windows-only. Run from an unpackaged PowerShell process, never an MSIX AppData view.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ installed = $false }
$staged = $null
$resultStream = $null
$resultPathVerified = $false
try {
    foreach ($path in @($SourceJar, $ModsDirectory, $ResultPath)) {
        if ($path -notmatch '^[a-zA-Z]:[\\/]') { throw 'All paths must be fully qualified local drive paths.' }
    }
    $ResultPath = [IO.Path]::GetFullPath($ResultPath)
    $modsPrefix = [IO.Path]::GetFullPath($ModsDirectory).TrimEnd('\') + '\'
    if ([IO.Path]::GetExtension($ResultPath) -ine '.json' -or
        $ResultPath.StartsWith($modsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'ResultPath must be a new JSON file outside the mods directory.'
    }
    if (-not ('McmcpProductInstallNative' -as [type])) { Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class McmcpProductInstallNative {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
    public static extern int GetCurrentPackageFullName(ref uint size, StringBuilder name);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern uint GetFinalPathNameByHandle(IntPtr file, StringBuilder buffer, int length, uint flags);
}
'@
    }
    $size = [uint32]0
    $packageStatus = [McmcpProductInstallNative]::GetCurrentPackageFullName([ref]$size, $null)
    if ($packageStatus -ne 15700) { # APPMODEL_ERROR_NO_PACKAGE
        throw 'Packaged process refused: AppData may be redirected. Use an unpackaged PowerShell process.'
    }
    $source = (Get-Item -LiteralPath $SourceJar -Force).FullName
    $mods = Get-Item -LiteralPath $ModsDirectory -Force
    if (-not $mods.PSIsContainer) { throw 'ModsDirectory must be a directory.' }
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    function Test-ProductJar([string]$Path) {
        $zip = [IO.Compression.ZipFile]::OpenRead($Path)
        try { return $null -ne $zip.GetEntry('dev/aod/mcmcp/McmcpMod.class') }
        finally { $zip.Dispose() }
    }
    function Get-NativeFilePath([string]$Path, [uint32]$Flags = 2, [IO.FileStream]$Stream = $null) {
        $ownsStream = $null -eq $Stream
        if ($ownsStream) { $Stream = [IO.File]::OpenRead($Path) }
        try {
            $buffer = [Text.StringBuilder]::new(32768)
            $length = [McmcpProductInstallNative]::GetFinalPathNameByHandle(
                $Stream.SafeFileHandle.DangerousGetHandle(), $buffer, $buffer.Capacity, $Flags)
            if ($length -eq 0 -or $length -ge $buffer.Capacity) { throw 'Cannot verify native file path.' }
            return $buffer.ToString()
        } finally { if ($ownsStream) { $Stream.Dispose() } }
    }
    function Assert-ExactPhysicalPath([string]$Path, [IO.FileStream]$Stream = $null) {
        $physical = Get-NativeFilePath $Path 0 $Stream
        if ($physical.StartsWith('\\?\UNC\')) { $physical = '\\' + $physical.Substring(8) }
        elseif ($physical.StartsWith('\\?\')) { $physical = $physical.Substring(4) }
        if (-not [string]::Equals([IO.Path]::GetFullPath($Path), $physical,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Physical path differs from the requested path; redirected or linked installation refused.'
        }
    }
    function Assert-MinecraftStopped {
        # Do not emit JVM arguments: Prism can put game credentials there.
        $running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
            Where-Object { $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon' })
        if ($running.Count -ne 0) { throw 'A Java process other than Gradle is running; close Minecraft before installation.' }
    }
    function Write-Result {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($result | ConvertTo-Json -Depth 5))
        $resultStream.Position = 0
        $resultStream.SetLength(0)
        $resultStream.Write($bytes, 0, $bytes.Length)
        $resultStream.Flush()
    }
    # CreateNew never truncates an existing file, including a link to a JAR.
    $resultStream = [IO.File]::Open($ResultPath, [IO.FileMode]::CreateNew,
        [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Assert-ExactPhysicalPath $ResultPath $resultStream
    $resultPathVerified = $true
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ine $ExpectedSourceSha256) {
        throw 'Source JAR hash mismatch.'
    }
    if (-not (Test-ProductJar $source)) { throw 'Source is not an MCMCP product JAR.' }
    $products = @(Get-ChildItem -LiteralPath $mods.FullName -Filter '*.jar' -File -Force |
        Where-Object { Test-ProductJar $_.FullName })
    if ($products.Count -ne 1) { throw 'Expected exactly one installed MCMCP product JAR.' }
    $destination = $products[0].FullName
    if ($source -ieq $destination) { throw 'Source and destination must differ.' }
    # Child processes can inherit AppData redirection without reporting a package identity.
    Assert-ExactPhysicalPath $source
    Assert-ExactPhysicalPath $destination
    $result.source = $source
    $result.destination = $destination
    $result.native_source = Get-NativeFilePath $source
    $result.native_destination = Get-NativeFilePath $destination
    $result.previous_sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $result.source_sha256 = $ExpectedSourceSha256.ToLowerInvariant()
    $result.product_count = $products.Count
    if ($Install) {
        Assert-MinecraftStopped
        Assert-ExactPhysicalPath $destination
        $staged = Join-Path $mods.FullName ('.mcmcp-install-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        [IO.File]::Copy($source, $staged, $false)
        Assert-ExactPhysicalPath $staged
        if ((Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash -ine $ExpectedSourceSha256) {
            throw 'Staged JAR hash mismatch.'
        }
        Assert-MinecraftStopped
        Assert-ExactPhysicalPath $destination
        [IO.File]::Replace($staged, $destination, [NullString]::Value) # Atomic replacement; no extra backup.
        $staged = $null
        Assert-ExactPhysicalPath $destination
        $result.installed_sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($result.installed_sha256 -cne $result.source_sha256) { throw 'Installed JAR hash mismatch.' }
        $result.native_destination = Get-NativeFilePath $destination
        $result.installed = $true
    }
    $result.checked_at = [DateTimeOffset]::Now.ToString('o')
    Write-Result
    $result | ConvertTo-Json -Depth 5
} catch {
    # Fixed installer messages and exception type only; never serialize arbitrary JVM/Mod data.
    $result.error_type = $_.Exception.GetType().FullName
    $result.error = if ($_.Exception -is [System.Management.Automation.RuntimeException]) {
        $_.Exception.Message
    } else { 'Installation failed; inspect the local paths and process state.' }
    if ($resultPathVerified) { Write-Result }
    throw
} finally {
    if ($null -ne $resultStream) {
        $resultStream.Dispose()
        if (-not $resultPathVerified) { [IO.File]::Delete($ResultPath) }
    }
    if ($null -ne $staged -and [IO.File]::Exists($staged)) { [IO.File]::Delete($staged) }
}
