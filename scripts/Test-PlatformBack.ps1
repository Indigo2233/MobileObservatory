param(
    [string]$AdbPath = 'adb',
    [string]$Serial = 'emulator-5560',
    [string]$TestPackage = 'com.indigo.mobileobservatory.emulatortest.test',
    [string]$LogPath = (Join-Path $PSScriptRoot '..\bin\platform-back-tests.log')
)
$ErrorActionPreference = 'Stop'
$platformClasses = 'com.indigo.mobileobservatory.platform.ExternalMediaBoundaryInstrumentedTest,com.indigo.mobileobservatory.platform.SystemBackGestureInstrumentedTest'
$platformOutput = & $AdbPath -s $Serial shell am instrument -w -e class $platformClasses "$TestPackage/androidx.test.runner.AndroidJUnitRunner" 2>&1
$platformExit = $LASTEXITCODE
$platformLog = [System.IO.Path]::GetFullPath($LogPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($platformLog)) | Out-Null
[System.IO.File]::WriteAllLines($platformLog, [string[]]$platformOutput, [System.Text.UTF8Encoding]::new($false))
$platformOutput | Write-Output
$platformText = $platformOutput -join "`n"
if ($platformExit -ne 0 -or $platformText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[34]' -or $platformText -notmatch 'OK \([0-9]+ tests?\)') {
    throw "Platform Back tests did not pass. Read $platformLog"
}
Write-Output "Emulator/platform evidence saved to $platformLog"
