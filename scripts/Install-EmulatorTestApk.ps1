param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

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

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $root "bin\Installer\IndigoObservatory_emulator_test.apk"
}
$ApkPath = [IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "Emulator test APK does not exist: $ApkPath"
}

$sdkRoot = Get-AndroidSdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB was not found: $adb"
}

$state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if (($LASTEXITCODE -ne 0 -or $state -ne "device") -and
    $Serial -match '^[^:]+:\d+$') {
    & $adb connect $Serial | Out-Host
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
}
if ($LASTEXITCODE -ne 0 -or $state -ne "device") {
    throw "ADB target is unavailable: $Serial ($state)"
}

$apiText = (& $adb -s $Serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
$api = 0
if (-not [int]::TryParse($apiText, [ref]$api) -or $api -lt 26) {
    throw "ADB target $Serial uses API $apiText; API 26 or newer is required."
}

$abis = (& $adb -s $Serial shell getprop ro.product.cpu.abilist 2>&1 | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($abis)) {
    $abis = (& $adb -s $Serial shell getprop ro.product.cpu.abi 2>&1 | Out-String).Trim()
}
if ($abis -notmatch '(^|,)(x86|x86_64)(,|$)') {
    throw "ADB target $Serial reports unsupported ABIs: $abis"
}

Write-Host "Installing on $Serial (API $api, ABIs $abis)" -ForegroundColor Cyan
& $adb -s $Serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "ADB installation failed for $Serial."
}

& $adb -s $Serial shell am start -n "com.indigo.mobileobservatory.emulatortest/com.indigo.mobileobservatory.MainActivity"
if ($LASTEXITCODE -ne 0) {
    throw "The emulator test application was installed but could not be launched."
}

Write-Host "Emulator test application launched on $Serial." -ForegroundColor Green
