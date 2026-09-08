[CmdletBinding()]
param(
    [string]$PythonExecutable = 'python',
    [switch]$SkipJava
)

# ソースの回帰試験のみ。Minecraft・fixture・実MCP endpointは起動しない。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$shellPath = (Get-Process -Id $PID).Path
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    if (-not $SkipJava) {
        if ($IsWindows) {
            & .\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build --console=plain
        } else {
            & bash ./gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build --console=plain
        }
        if ($LASTEXITCODE -ne 0) { throw 'Gradle source checks failed' }
    }

    & $PythonExecutable -m unittest discover -s tools/mcp -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'MCP transport unit tests failed' }

    # 実機評価runnerとは分け、mock capability gateだけを別scope/processで実行する。
    $mockTests = @(Get-ChildItem -LiteralPath tools/eval -Filter 'Test-Mcmcp*CapabilityGate.ps1') +
        @(Get-Item -LiteralPath tools/eval/Test-McmcpCapabilityGateLoading.ps1)
    foreach ($test in $mockTests | Sort-Object Name) {
        & $shellPath -NoProfile -File $test.FullName
        if ($LASTEXITCODE -ne 0) { throw "Capability mock test failed: $($test.Name)" }
    }
    Write-Output 'MCMCP source checks passed.'
} finally {
    Pop-Location
}
