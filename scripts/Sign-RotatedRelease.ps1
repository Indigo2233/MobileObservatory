param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ApkSignerPath,
    [int]$RotationMinSdkVersion = 28
)

$ErrorActionPreference = "Stop"

function Get-RequiredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Release signing rotation requires $Name."
    }
    return $value
}

$newKeyStore = Get-RequiredEnvironmentValue "ANDROID_RELEASE_KEYSTORE"
$newKeyAlias = Get-RequiredEnvironmentValue "ANDROID_RELEASE_KEY_ALIAS"
$null = Get-RequiredEnvironmentValue "ANDROID_RELEASE_STORE_PASSWORD"
$null = Get-RequiredEnvironmentValue "ANDROID_RELEASE_KEY_PASSWORD"
$oldKeyStore = Get-RequiredEnvironmentValue "ANDROID_RELEASE_OLD_KEYSTORE"
$oldKeyAlias = Get-RequiredEnvironmentValue "ANDROID_RELEASE_OLD_KEY_ALIAS"
$null = Get-RequiredEnvironmentValue "ANDROID_RELEASE_OLD_STORE_PASSWORD"
$null = Get-RequiredEnvironmentValue "ANDROID_RELEASE_OLD_KEY_PASSWORD"

foreach ($path in @($ApkPath, $ApkSignerPath, $newKeyStore, $oldKeyStore)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required release signing file does not exist: $path"
    }
}
if ($RotationMinSdkVersion -lt 28) {
    throw "Signing-certificate rotation requires Android API 28 or newer."
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryDirectory = Join-Path $temporaryRoot (
    "indigo-observatory-signing-{0}" -f [Guid]::NewGuid().ToString("N")
)
$lineagePath = Join-Path $temporaryDirectory "signing-lineage.bin"
$signedApkPath = Join-Path $temporaryDirectory "rotated-release.apk"
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null

try {
    & $ApkSignerPath rotate `
        --out $lineagePath `
        --old-signer `
        --ks $oldKeyStore `
        --ks-key-alias $oldKeyAlias `
        --ks-pass env:ANDROID_RELEASE_OLD_STORE_PASSWORD `
        --key-pass env:ANDROID_RELEASE_OLD_KEY_PASSWORD `
        --set-installed-data true `
        --set-shared-uid true `
        --set-permission true `
        --set-rollback false `
        --set-auth true `
        --new-signer `
        --ks $newKeyStore `
        --ks-key-alias $newKeyAlias `
        --ks-pass env:ANDROID_RELEASE_STORE_PASSWORD `
        --key-pass env:ANDROID_RELEASE_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $lineagePath -PathType Leaf)) {
        throw "Failed to create the APK signing-certificate lineage."
    }

    & $ApkSignerPath sign `
        --in $ApkPath `
        --out $signedApkPath `
        --ks $oldKeyStore `
        --ks-key-alias $oldKeyAlias `
        --ks-pass env:ANDROID_RELEASE_OLD_STORE_PASSWORD `
        --key-pass env:ANDROID_RELEASE_OLD_KEY_PASSWORD `
        --next-signer `
        --ks $newKeyStore `
        --ks-key-alias $newKeyAlias `
        --ks-pass env:ANDROID_RELEASE_STORE_PASSWORD `
        --key-pass env:ANDROID_RELEASE_KEY_PASSWORD `
        --lineage $lineagePath `
        --rotation-min-sdk-version $RotationMinSdkVersion
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $signedApkPath -PathType Leaf)) {
        throw "Failed to sign the APK with the signing-certificate lineage."
    }

    Move-Item -LiteralPath $signedApkPath -Destination $ApkPath -Force
}
finally {
    $resolvedTemporaryDirectory = [IO.Path]::GetFullPath($temporaryDirectory)
    if ($resolvedTemporaryDirectory.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
