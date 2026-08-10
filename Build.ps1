param(
    [switch]$Clean,
    [switch]$NonCommercial
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$apkMetadata = Join-Path $root "app\build\outputs\apk\debug\output-metadata.json"
$apkOut = Join-Path $root "bin\Installer\IndigoObservatory_android.apk"

function Test-ValidApkArchive {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    $item = Get-Item -LiteralPath $Path
    if ($item.Length -lt 4) {
        return $false
    }
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $signature = New-Object byte[] 4
        if ($stream.Read($signature, 0, 4) -ne 4) {
            return $false
        }
        return $signature[0] -eq 0x50 -and
            $signature[1] -eq 0x4b -and
            $signature[2] -eq 0x03 -and
            $signature[3] -eq 0x04
    }
    finally {
        $stream.Dispose()
    }
}

Push-Location $root
try {
    & "$root\scripts\Apply-LibusbAndroidFdPatch.ps1" -RepoRoot $root
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to apply libusb Android FD patch."
    }

    $gradleArgs = @()
    if ($Clean) {
        $gradleArgs += "clean"
    }
    $gradleArgs += "assembleDebug"
    if ($NonCommercial) {
        $gradleArgs += "-PstellariumNonCommercial=true"
    }

    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Android build failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-ValidApkArchive -Path $apk)) {
        throw "Gradle output is not a valid APK archive: $apk"
    }
    if (-not (Test-Path -LiteralPath $apkMetadata)) {
        throw "Gradle APK metadata is missing: $apkMetadata"
    }

    $metadata = Get-Content -LiteralPath $apkMetadata -Raw | ConvertFrom-Json
    $apkElement = $metadata.elements | Select-Object -First 1
    if ($null -eq $apkElement -or
        $null -eq $apkElement.versionCode -or
        [string]::IsNullOrWhiteSpace([string]$apkElement.versionName)) {
        throw "Gradle APK metadata does not contain a version code and name: $apkMetadata"
    }
    $versionedApkOut = Join-Path $root (
        "bin\Installer\IndigoObservatory_android_v{0}-build{1}.apk" -f
        $apkElement.versionName,
        $apkElement.versionCode
    )

    New-Item -ItemType Directory -Path (Split-Path $apkOut) -Force | Out-Null
    Copy-Item -LiteralPath $apk -Destination $apkOut -Force
    Copy-Item -LiteralPath $apk -Destination $versionedApkOut -Force
    if (-not (Test-ValidApkArchive -Path $apkOut)) {
        throw "Copied APK failed archive validation: $apkOut"
    }
    if (-not (Test-ValidApkArchive -Path $versionedApkOut)) {
        throw "Versioned APK failed archive validation: $versionedApkOut"
    }

    Write-Host "Indigo Observatory APK: $versionedApkOut" -ForegroundColor Green
    Write-Host "Latest APK: $apkOut" -ForegroundColor Green
}
finally {
    Pop-Location
}
