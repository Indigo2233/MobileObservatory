param(
    [switch]$Clean,
    [switch]$NonCommercial,
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildType = if ($Release) { "release" } else { "debug" }
$gradleTask = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$apk = Join-Path $root "app\build\outputs\apk\$buildType\app-$buildType.apk"
$apkMetadata = Join-Path $root "app\build\outputs\apk\$buildType\output-metadata.json"
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

function Get-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }

    $localProperties = Join-Path $root "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $candidate = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':'
            $candidate = $candidate -replace '\\\\', '\'
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }

    throw "Android SDK path is unavailable. Set ANDROID_HOME or ANDROID_SDK_ROOT."
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
    $gradleArgs += $gradleTask
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

    $sha256 = (Get-FileHash -LiteralPath $versionedApkOut -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumOut = "$versionedApkOut.sha256"
    Set-Content -LiteralPath $checksumOut -Encoding ascii -Value "$sha256  $([IO.Path]::GetFileName($versionedApkOut))"

    $signingCertificateSha256 = ""
    if ($Release) {
        $androidSdkRoot = Get-AndroidSdkRoot
        $apkSigner = Get-ChildItem (Join-Path $androidSdkRoot "build-tools") \
            -Filter "apksigner*" -File -Recurse |
            Sort-Object FullName |
            Select-Object -Last 1
        if ($null -eq $apkSigner) {
            throw "apksigner was not found under $androidSdkRoot."
        }
        $signatureReport = & $apkSigner.FullName verify --print-certs $versionedApkOut 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release APK signature verification failed: $signatureReport"
        }
        if ($signatureReport -match 'androiddebugkey') {
            throw "Release APK uses the Android debug signing identity."
        }
        $digestLine = $signatureReport | Where-Object {
            $_ -match '^Signer #1 certificate SHA-256 digest:'
        } | Select-Object -First 1
        if (-not $digestLine) {
            throw "Release APK signing certificate digest was not reported."
        }
        $signingCertificateSha256 = ($digestLine -split ': ', 2)[1].Trim()
    }

    $commit = (& git -C $root rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the source commit for build metadata."
    }
    $buildInfoOut = [IO.Path]::ChangeExtension($versionedApkOut, ".build-info.txt")
    @(
        "application=Indigo Observatory"
        "variant=$buildType"
        "versionName=$($apkElement.versionName)"
        "versionCode=$($apkElement.versionCode)"
        "commit=$commit"
        "sha256=$sha256"
        "signingCertificateSha256=$signingCertificateSha256"
        "stellariumIncluded=$($NonCommercial.IsPresent)"
        "builtAtUtc=$([DateTime]::UtcNow.ToString('o'))"
    ) | Set-Content -LiteralPath $buildInfoOut -Encoding utf8

    if ($Release -and $NonCommercial) {
        & "$root\tools\Package-ReleaseSource.ps1" -OutputDirectory "$root\bin\Source"
        if ($LASTEXITCODE -ne 0) {
            throw "AGPL source packaging failed with exit code $LASTEXITCODE."
        }
    }

    Write-Host "Indigo Observatory APK: $versionedApkOut" -ForegroundColor Green
    Write-Host "Latest APK: $apkOut" -ForegroundColor Green
    Write-Host "SHA-256: $checksumOut" -ForegroundColor Green
    Write-Host "Build info: $buildInfoOut" -ForegroundColor Green
}
finally {
    Pop-Location
}
