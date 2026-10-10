# Generates update.json for a published GitHub release. Consumes the
# *.build-info.txt written by Build.ps1 so the manifest cannot drift from the
# signed APK that was actually built.
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Repository = "Indigo2233/MobileObservatory",
    [string]$InstallerDir = "bin/Installer",
    [string]$OutputPath = "",
    [string]$NotesFile = "",
    [switch]$Mandatory,
    [int]$MinSupportedVersionCode = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Resolve-RootRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $root $Path
}

$installerPath = Resolve-RootRelativePath $InstallerDir
if (-not (Test-Path -LiteralPath $installerPath)) {
    throw "Installer directory is missing: $installerPath"
}

$buildInfoFile = Get-ChildItem -LiteralPath $installerPath `
    -Filter "IndigoObservatory_android_v*-build*.build-info.txt" -File |
    Sort-Object LastWriteTime |
    Select-Object -Last 1
if ($null -eq $buildInfoFile) {
    throw "No build information file found in $installerPath. Run Build.ps1 -Release first."
}

$buildInfo = @{}
foreach ($line in Get-Content -LiteralPath $buildInfoFile.FullName) {
    $separator = $line.IndexOf('=')
    if ($separator -gt 0) {
        $buildInfo[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }
}

$versionName = $buildInfo["versionName"]
$expectedSigningDigest = "46a82d0f1ba2989f42448b7ef9845a8283a9a2cb8e8938bb49a7bae3a27aaa3a"
$expectedLegacyDigest = "bc4b926780ae1826eb2b66c3c2bea99a546bac91f5bc9f6274547680cc86c374"
if ($buildInfo["variant"] -ne "release") {
    throw "Update manifests require a release build: $($buildInfoFile.FullName)"
}
if ($buildInfo["stellariumIncluded"] -ne "True") {
    throw "Published builds must include the licensed Stellarium assets."
}
if ($buildInfo["sequenceEnabled"] -ne "False") {
    throw "Published builds must keep the unfinished sequence entry points disabled."
}
if ($buildInfo["signingCertificateSha256"] -ne $expectedSigningDigest) {
    throw "Build information does not contain the maintainer signing certificate."
}
if ($buildInfo["legacySigningCertificateSha256"] -ne $expectedLegacyDigest) {
    throw "Build information does not contain the v1.0.6 upgrade certificate."
}
$versionCode = 0
if (-not [int]::TryParse($buildInfo["versionCode"], [ref]$versionCode) -or $versionCode -le 0) {
    throw "Build information does not contain a valid versionCode: $($buildInfoFile.FullName)"
}
$sha256 = $buildInfo["sha256"]
if ([string]::IsNullOrWhiteSpace($sha256)) {
    throw "Build information does not contain a SHA-256 checksum: $($buildInfoFile.FullName)"
}
if ([string]::IsNullOrWhiteSpace($versionName) -or $Tag -notlike "*$versionName*") {
    throw "Release tag '$Tag' does not contain the built version name '$versionName'."
}

$apkName = "IndigoObservatory_android_v$versionName-build$versionCode.apk"
$apkPath = Join-Path $installerPath $apkName
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Versioned APK is missing: $apkPath"
}
$actualSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $sha256.ToLowerInvariant()) {
    throw "APK checksum does not match the build information: $apkPath"
}

$notes = ""
if (-not [string]::IsNullOrWhiteSpace($NotesFile)) {
    $notesPath = Resolve-RootRelativePath $NotesFile
    if (Test-Path -LiteralPath $notesPath) {
        $notes = (Get-Content -LiteralPath $notesPath -Raw).Trim()
    }
}

$manifest = [ordered]@{
    schema                  = 1
    versionCode             = $versionCode
    versionName             = $versionName
    apkUrl                  = "https://github.com/$Repository/releases/download/$Tag/$apkName"
    sha256                  = $sha256.ToLowerInvariant()
    notes                   = $notes
    mandatory               = $Mandatory.IsPresent
    minSupportedVersionCode = $MinSupportedVersionCode
    publishedAtUtc          = [DateTime]::UtcNow.ToString("o")
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $installerPath "update.json"
} else {
    $OutputPath = Resolve-RootRelativePath $OutputPath
}

$json = $manifest | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Update manifest: $OutputPath" -ForegroundColor Green
Write-Host "Update target: $($manifest.apkUrl)" -ForegroundColor Green
Write-Output $OutputPath
