param(
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$stellariumRevision = "1fa2d3bbb19f66ebb0de6deed3090391497c8047"
$stellariumRepository = "https://github.com/Stellarium/stellarium-web-engine.git"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "bin\Source"
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE."
    }
}

function Compress-SourceTree {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDirectory,
        [Parameter(Mandatory = $true)][string]$ArchivePath
    )

    Invoke-NativeCommand -FilePath "tar.exe" -Arguments @(
        "-a", "-cf", $ArchivePath, "-C", $SourceDirectory, "."
    )
    if (-not (Test-Path -LiteralPath $ArchivePath) -or
        (Get-Item -LiteralPath $ArchivePath).Length -eq 0) {
        throw "Source archive was not created: $ArchivePath"
    }
}

$trackedChanges = & git -C $repoRoot status --porcelain --untracked-files=no --ignore-submodules=dirty
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect repository status."
}
if ($trackedChanges) {
    throw "Release source packaging requires a clean tracked worktree."
}
if (Test-Path -LiteralPath (Join-Path $repoRoot ".industrial-overlay-applied")) {
    throw "Release source packaging is disabled while an industrial overlay is applied."
}

$sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to resolve the application source commit."
}
$submoduleTreeEntry = & git -C $repoRoot ls-tree HEAD app/src/main/cpp/libusb
if ($LASTEXITCODE -ne 0 -or -not $submoduleTreeEntry) {
    throw "Unable to resolve the libusb submodule commit."
}
$libusbCommit = ($submoduleTreeEntry -split "\s+")[2]
$libusbRepository = Join-Path $repoRoot "app\src\main\cpp\libusb"
if (-not (Test-Path -LiteralPath (Join-Path $libusbRepository ".git"))) {
    throw "Initialize the libusb submodule before packaging release source."
}

$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $systemTemp ("IndigoObservatory-source-" + [Guid]::NewGuid().ToString("N"))
$resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot)
if (-not $resolvedTempRoot.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary source directory resolved outside the system temporary directory."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    $appSource = Join-Path $tempRoot "MobileObservatory"
    $appTar = Join-Path $tempRoot "MobileObservatory.tar"
    New-Item -ItemType Directory -Path $appSource -Force | Out-Null
    Invoke-NativeCommand -FilePath "git" -Arguments @(
        "-C", $repoRoot, "archive", "--format=tar", "--output=$appTar", $sourceCommit
    )
    Invoke-NativeCommand -FilePath "tar.exe" -Arguments @("-xf", $appTar, "-C", $appSource)

    $archivedLibusb = Join-Path $appSource "app\src\main\cpp\libusb"
    $libusbTar = Join-Path $tempRoot "libusb.tar"
    New-Item -ItemType Directory -Path $archivedLibusb -Force | Out-Null
    Invoke-NativeCommand -FilePath "git" -Arguments @(
        "-C", $libusbRepository, "archive", "--format=tar", "--output=$libusbTar", $libusbCommit
    )
    Invoke-NativeCommand -FilePath "tar.exe" -Arguments @("-xf", $libusbTar, "-C", $archivedLibusb)

    @(
        "application=Indigo Observatory"
        "sourceCommit=$sourceCommit"
        "libusbCommit=$libusbCommit"
        "stellariumWebEngineCommit=$stellariumRevision"
    ) | Set-Content -LiteralPath (Join-Path $appSource "SOURCE_MANIFEST.txt") -Encoding utf8

    $applicationArchive = Join-Path $OutputDirectory (
        "MobileObservatory_Source_{0}.zip" -f $sourceCommit.Substring(0, 12)
    )
    Compress-SourceTree -SourceDirectory $appSource -ArchivePath $applicationArchive

    $stellariumRepositoryPath = Join-Path $tempRoot "stellarium-web-engine-repository"
    $stellariumSource = Join-Path $tempRoot "StellariumWebEngine"
    $stellariumTar = Join-Path $tempRoot "StellariumWebEngine.tar"
    Invoke-NativeCommand -FilePath "git" -Arguments @(
        "clone", "--filter=blob:none", "--no-checkout", $stellariumRepository, $stellariumRepositoryPath
    )
    Invoke-NativeCommand -FilePath "git" -Arguments @(
        "-C", $stellariumRepositoryPath, "checkout", "--detach", $stellariumRevision
    )
    New-Item -ItemType Directory -Path $stellariumSource -Force | Out-Null
    Invoke-NativeCommand -FilePath "git" -Arguments @(
        "-C", $stellariumRepositoryPath, "archive", "--format=tar", "--output=$stellariumTar", $stellariumRevision
    )
    Invoke-NativeCommand -FilePath "tar.exe" -Arguments @("-xf", $stellariumTar, "-C", $stellariumSource)

    $stellariumArchive = Join-Path $OutputDirectory (
        "StellariumWebEngine_{0}.zip" -f $stellariumRevision.Substring(0, 12)
    )
    Compress-SourceTree -SourceDirectory $stellariumSource -ArchivePath $stellariumArchive

    Write-Host "Application source: $applicationArchive" -ForegroundColor Green
    Write-Host "Stellarium source: $stellariumArchive" -ForegroundColor Green
}
finally {
    if (Test-Path -LiteralPath $resolvedTempRoot) {
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
}
