param([switch]$CompileUiTests, [switch]$Lint)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $gradleArgs = @('testDebugUnitTest')
    foreach ($pattern in @('*astrometry.*', '*polar.*', '*PointingReferencePreflightTest',
        '*SingleExposureCaptureTest', '*PrecisionGotoMathTest', '*ObservingUiWiringTest')) {
        $gradleArgs += @('--tests', $pattern)
    }
    if ($CompileUiTests) { $gradleArgs += 'compileDebugAndroidTestKotlin' }
    if ($Lint) { $gradleArgs += 'lintDebug' }
    $gradleArgs += @('-PstellariumNonCommercial=true', '-PsequenceEnabled=false', '-PemulatorTest=false', '--console=plain')
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Field workflow checks failed: exit $LASTEXITCODE" }
    $reports = Get-ChildItem -LiteralPath 'app/build/test-results/testDebugUnitTest' -Filter 'TEST-*.xml' -File
    $cases = 0; $failures = 0; $skipped = 0
    foreach ($report in $reports) {
        [xml]$result = Get-Content -LiteralPath $report.FullName -Raw
        $cases += [int]$result.testsuite.tests
        $failures += [int]$result.testsuite.failures + [int]$result.testsuite.errors
        $skipped += [int]$result.testsuite.skipped
    }
    Write-Host "Software field workflows: $cases tests, $failures failures/errors, $skipped skipped."
    Write-Host (Join-Path $root 'app/build/reports/tests/testDebugUnitTest/index.html')
} finally { Pop-Location }
