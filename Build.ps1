param(
    [switch]$Clean,
    [switch]$NonCommercial,
    [switch]$Release,
    [switch]$ShowSequence,
    [switch]$HideSequence,
    [switch]$EmulatorTest
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($ShowSequence -and $HideSequence) {
    throw "Use either -ShowSequence or -HideSequence."
}
if ($EmulatorTest -and $Release) {
    throw "EmulatorTest is restricted to debug builds."
}
if ($EmulatorTest -and $HideSequence) {
    throw "EmulatorTest requires the sequence UI."
}
# The advanced sequencer is hidden by default; opt in for development builds.
$sequenceEnabled = $ShowSequence.IsPresent -or $EmulatorTest.IsPresent
$buildType = if ($Release) { "release" } else { "debug" }
$gradleTask = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$apk = Join-Path $root "app\build\outputs\apk\$buildType\app-$buildType.apk"
$apkMetadata = Join-Path $root "app\build\outputs\apk\$buildType\output-metadata.json"
$apkOut = if ($EmulatorTest) {
    Join-Path $root "bin\Installer\IndigoObservatory_emulator_test.apk"
} else {
    Join-Path $root "bin\Installer\IndigoObservatory_android.apk"
}

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
    $gradleArgs += "-PsequenceEnabled=$($sequenceEnabled.ToString().ToLowerInvariant())"
    $gradleArgs += "-PemulatorTest=$($EmulatorTest.IsPresent.ToString().ToLowerInvariant())"

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
    $apkStem = if ($EmulatorTest) { "IndigoObservatory_emulator_test" } else { "IndigoObservatory_android" }
    $versionedApkOut = Join-Path $root (
        "bin\Installer\{0}_v{1}-build{2}.apk" -f
        $apkStem,
        $apkElement.versionName,
        $apkElement.versionCode
    )

    $signingCertificateSha256 = ""
    $legacySigningCertificateSha256 = ""
    $signingRotationMinSdkVersion = ""
    if ($Release) {
        $androidSdkRoot = Get-AndroidSdkRoot
        $apkSigner = Get-ChildItem -Path (Join-Path $androidSdkRoot "build-tools") `
            -File -Recurse |
            Where-Object { $_.Name -in @("apksigner", "apksigner.bat") } |
            Sort-Object FullName |
            Select-Object -Last 1
        if ($null -eq $apkSigner) {
            throw "apksigner was not found under $androidSdkRoot."
        }

        $rotationMinSdkVersion = 28
        & "$root\scripts\Sign-RotatedRelease.ps1" `
            -ApkPath $apk `
            -ApkSignerPath $apkSigner.FullName `
            -RotationMinSdkVersion $rotationMinSdkVersion
        if ($LASTEXITCODE -ne 0) {
            throw "Release APK signing-certificate rotation failed."
        }

        $signatureReport = & $apkSigner.FullName verify --print-certs `
            --min-sdk-version 28 --max-sdk-version 32 $apk 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release APK signature verification failed for Android 9+: $signatureReport"
        }
        $legacySignatureReport = & $apkSigner.FullName verify --print-certs `
            --min-sdk-version 26 --max-sdk-version 27 $apk 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Release APK signature verification failed for Android 8: $legacySignatureReport"
        }

        $signatureText = $signatureReport -join [Environment]::NewLine
        $legacySignatureText = $legacySignatureReport -join [Environment]::NewLine
        $digestMatch = [regex]::Match(
            $signatureText,
            'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
        )
        $legacyDigestMatch = [regex]::Match(
            $legacySignatureText,
            'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
        )
        if (-not $digestMatch.Success -or -not $legacyDigestMatch.Success) {
            throw "Release APK signing certificate digests were not reported."
        }
        $signingCertificateSha256 = $digestMatch.Groups[1].Value.ToLowerInvariant()
        $legacySigningCertificateSha256 = $legacyDigestMatch.Groups[1].Value.ToLowerInvariant()
        $expectedLegacyDigest = "bc4b926780ae1826eb2b66c3c2bea99a546bac91f5bc9f6274547680cc86c374"
        $expectedSigningDigest = "46a82d0f1ba2989f42448b7ef9845a8283a9a2cb8e8938bb49a7bae3a27aaa3a"
        if ($legacySigningCertificateSha256 -ne $expectedLegacyDigest) {
            throw "Android 8 signer does not match the v1.0.6 certificate."
        }
        if ($signingCertificateSha256 -ne $expectedSigningDigest) {
            throw "Android 9+ signer does not match the maintainer certificate."
        }
        $signingRotationMinSdkVersion = [string]$rotationMinSdkVersion
    }

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
        "legacySigningCertificateSha256=$legacySigningCertificateSha256"
        "signingRotationMinSdkVersion=$signingRotationMinSdkVersion"
        "stellariumIncluded=$($NonCommercial.IsPresent)"
        "sequenceEnabled=$sequenceEnabled"
        "emulatorTest=$($EmulatorTest.IsPresent)"
        "abis=$(if ($EmulatorTest) { 'x86,x86_64' } else { 'arm64-v8a' })"
        "builtAtUtc=$([DateTime]::UtcNow.ToString('o'))"
    ) | Set-Content -LiteralPath $buildInfoOut -Encoding utf8

    Write-Host "Indigo Observatory APK: $versionedApkOut" -ForegroundColor Green
    Write-Host "Latest APK: $apkOut" -ForegroundColor Green
    Write-Host "SHA-256: $checksumOut" -ForegroundColor Green
    Write-Host "Build info: $buildInfoOut" -ForegroundColor Green
}
finally {
    Pop-Location
}
