Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
# Regress the accidental entry-point dot-source that overwrote LibraryOnly and TokenPath.
foreach($name in @('Invoke-McmcpConstructionMaterialsGate.ps1','Invoke-McmcpFloorExtensionCapabilityGate.ps1')) {
    & {
        $path=Join-Path $PSScriptRoot $name
        $artifact=Join-Path ([IO.Path]::GetTempPath()) ('mcmcp-no-create-'+[guid]::NewGuid().ToString('N'))
        . $path -ArtifactDirectory $artifact -TokenPath 'missing-test-token' -LibraryOnly
        if(-not $LibraryOnly -or $TokenPath -cne 'missing-test-token' -or
            $ArtifactDirectory -cne $artifact -or (Test-Path -LiteralPath $artifact)) {
            throw 'Construction gate changed caller arguments or performed work while loading'
        }
        $caught=$false
        try { . $path -ArtifactDirectory $artifact }
        catch { $caught=$_.Exception.Message -ceq 'TokenPath is required' }
        if(-not $caught) { throw 'Standalone gate was silently converted into LibraryOnly' }
    }
}
'MCMCP floor extension gate loading regression tests passed.'
