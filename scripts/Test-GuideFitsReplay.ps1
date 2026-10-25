param(
    [Parameter(Mandatory = $true)][string]$FitsPath,
    [string]$ReportDirectory
)

$ErrorActionPreference = 'Stop'
$guideRepoRoot = Split-Path -Parent $PSScriptRoot
$guideSourcePath = (Resolve-Path -LiteralPath $FitsPath).Path
if (-not (Test-Path -LiteralPath $guideSourcePath -PathType Leaf)) { throw 'FitsPath must name a FITS file.' }
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $guideRepoRoot 'app\build\reports\guide-fits-full'
}
$guideReportPath = [IO.Path]::GetFullPath($ReportDirectory)
New-Item -ItemType Directory -Path $guideReportPath -Force | Out-Null
Push-Location $guideRepoRoot
try {
    $guideArgs = @(':app:testDebugUnitTest', '--tests', 'com.indigo.mobileobservatory.guide.GuideFitsReplayTest',
        "-PguideFitsPath=$guideSourcePath", "-PguideFitsReportDir=$guideReportPath",
        '-PstellariumNonCommercial=true', '-PsequenceEnabled=false', '-PemulatorTest=false', '--console=plain')
    & .\gradlew.bat @guideArgs
    if ($LASTEXITCODE -ne 0) { throw "FITS replay failed; reports: $guideReportPath" }
    & python (Join-Path $PSScriptRoot 'verify_guiding_fits.py') --fits $guideSourcePath --reports $guideReportPath
    if ($LASTEXITCODE -ne 0) { throw 'FITS replay report generation failed.' }
    Write-Host "FITS replay verified: $guideReportPath"
}
finally { Pop-Location }
