# Applies patches/libusb-android-fd.patch to the libusb submodule when needed.
# Idempotent: skips if libusb_set_android_fd is already present.
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

$libusbDir = Join-Path $RepoRoot "app\src\main\cpp\libusb"
$target = Join-Path $libusbDir "libusb\os\linux_usbfs.c"
$patch = Join-Path $RepoRoot "patches\libusb-android-fd.patch"

if (-not (Test-Path -LiteralPath $target)) {
    throw "libusb source missing: $target (run git submodule update --init)"
}
if (-not (Test-Path -LiteralPath $patch)) {
    throw "libusb Android FD patch missing: $patch"
}

if (Select-String -LiteralPath $target -Pattern "libusb_set_android_fd" -Quiet) {
    Write-Host "libusb Android FD patch already applied." -ForegroundColor DarkGray
    exit 0
}

Write-Host "Applying libusb Android FD patch..." -ForegroundColor Cyan
Push-Location $libusbDir
try {
    & git apply --whitespace=nowarn $patch
    if ($LASTEXITCODE -ne 0) {
        throw "git apply failed for $patch (exit $LASTEXITCODE)"
    }
}
finally {
    Pop-Location
}

if (-not (Select-String -LiteralPath $target -Pattern "libusb_set_android_fd" -Quiet)) {
    throw "Patch applied but libusb_set_android_fd was not found in $target"
}

Write-Host "libusb Android FD patch applied." -ForegroundColor Green
