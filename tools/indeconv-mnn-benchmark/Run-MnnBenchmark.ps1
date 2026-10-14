[CmdletBinding()]
param(
    [string]$CacheRoot = 'D:\Unity\MobileObservatory-BenchmarkCache',
    [string]$AdbPath = '',
    [string]$Serial = '',
    [ValidateSet('opencl-image', 'opencl-buffer', 'vulkan-image', 'vulkan-fp32', 'cpu-fp16', 'cpu-fp32')]
    [string[]]$Modes = @('opencl-image', 'opencl-buffer', 'vulkan-image', 'cpu-fp16', 'cpu-fp32'),
    [ValidateRange(1, 16)]
    [int]$Threads = 4,
    [ValidateRange(0, 120)]
    [int]$CooldownSeconds = 10
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $AdbPath = $adbCommand.Source
    } else {
        $AdbPath = 'D:\Unity\AndroidSDK34\platform-tools\adb.exe'
    }
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb is missing: $AdbPath"
}

$runtime = Join-Path $CacheRoot 'runtime'
$fixtures = Join-Path $CacheRoot 'fixtures'
$model = Join-Path $fixtures 'InDeConv-fp16.mnn'
$inputFixture = Join-Path $fixtures 'real-input.bin'
$reference = Join-Path $fixtures 'desktop-output.bin'
$manifest = Join-Path $CacheRoot 'manifest.json'
$required = @(
    (Join-Path $runtime 'indeconv_bench'),
    (Join-Path $runtime 'libMNN.so'),
    (Join-Path $runtime 'libc++_shared.so'),
    $model,
    $inputFixture,
    $reference,
    $manifest
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Benchmark environment is incomplete: $path. Run Prepare-MnnBenchmark.ps1 first."
    }
}
$manifestData = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json -AsHashtable
foreach ($entry in $manifestData.files.GetEnumerator()) {
    $filePath = [string]$entry.Value.path
    $expectedHash = [string]$entry.Value.sha256
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "Manifest file is missing: $filePath"
    }
    $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Manifest SHA-256 mismatch for $filePath"
    }
}

$deviceLines = @(@(& $AdbPath devices) | Where-Object { $_ -match '^([^\s]+)\s+device$' })
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceLines.Count -ne 1) {
        throw "Expected one authorized Android device, found $($deviceLines.Count). Pass -Serial when several devices are connected."
    }
    $Serial = ([regex]::Match($deviceLines[0], '^([^\s]+)')).Groups[1].Value
} elseif (-not ($deviceLines | Where-Object { $_ -match ('^' + [regex]::Escape($Serial) + '\s+device$') })) {
    throw "Android device $Serial is unavailable or unauthorized."
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $output = & $script:AdbPath '-s' $script:Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $script:LastAdbExitCode = $exitCode
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed with code $exitCode`n$($output -join "`n")"
    }
    return @($output)
}

$abi = ((Invoke-Adb -Arguments @('shell', 'getprop ro.product.cpu.abi')) -join '').Trim()
if ($abi -ne 'arm64-v8a') {
    throw "The benchmark runtime requires arm64-v8a; device reports $abi"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$hostResult = Join-Path $CacheRoot "results\$timestamp-$Serial"
New-Item -ItemType Directory -Path $hostResult -Force | Out-Null
$remoteRoot = '/data/local/tmp/indeconv-mnn-benchmark'
Invoke-Adb -Arguments @('shell', "mkdir -p $remoteRoot/results") | Out-Null

function Push-Verified {
    param([Parameter(Mandatory)][string]$Local, [Parameter(Mandatory)][string]$Remote)
    $expected = (Get-FileHash -LiteralPath $Local -Algorithm SHA256).Hash.ToLowerInvariant()
    $currentLines = Invoke-Adb -Arguments @('shell', "if [ -f '$Remote' ]; then sha256sum '$Remote' | cut -d ' ' -f 1; fi") -AllowFailure
    $current = ($currentLines -join '').Trim().ToLowerInvariant()
    if ($current -ne $expected) {
        Invoke-Adb -Arguments @('push', $Local, $Remote) | Write-Host
        $verifiedLines = Invoke-Adb -Arguments @('shell', "sha256sum '$Remote' | cut -d ' ' -f 1")
        $verified = ($verifiedLines -join '').Trim().ToLowerInvariant()
        if ($verified -ne $expected) {
            throw "Device SHA-256 mismatch for $Remote"
        }
    } else {
        Write-Host "Reuse verified device file: $Remote"
    }
}

Push-Verified -Local (Join-Path $runtime 'indeconv_bench') -Remote "$remoteRoot/indeconv_bench"
Push-Verified -Local (Join-Path $runtime 'libMNN.so') -Remote "$remoteRoot/libMNN.so"
Push-Verified -Local (Join-Path $runtime 'libc++_shared.so') -Remote "$remoteRoot/libc++_shared.so"
Push-Verified -Local $model -Remote "$remoteRoot/InDeConv-fp16.mnn"
Push-Verified -Local $inputFixture -Remote "$remoteRoot/real-input.bin"
Invoke-Adb -Arguments @('shell', "chmod 0755 $remoteRoot/indeconv_bench") | Out-Null

$properties = @(
    'ro.product.manufacturer', 'ro.product.model', 'ro.product.device', 'ro.build.version.release',
    'ro.build.version.sdk', 'ro.soc.manufacturer', 'ro.soc.model', 'ro.hardware', 'ro.hardware.vulkan'
)
$deviceInfo = [ordered]@{ serial = $Serial }
foreach ($property in $properties) {
    $deviceInfo[$property] = ((Invoke-Adb -Arguments @('shell', "getprop $property")) -join '').Trim()
}
$deviceInfo | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $hostResult 'device.json') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'getprop')) | Set-Content -LiteralPath (Join-Path $hostResult 'getprop.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'cat /proc/cpuinfo')) | Set-Content -LiteralPath (Join-Path $hostResult 'cpuinfo.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'for f in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq; do echo "$f=$(cat $f 2>/dev/null)"; done')) | Set-Content -LiteralPath (Join-Path $hostResult 'cpu-frequencies.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'cmd gpu vkjson')) -join "`n" | Set-Content -LiteralPath (Join-Path $hostResult 'vulkan.json') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys SurfaceFlinger')) -join "`n" | Select-String -Pattern 'GLES|Vulkan|GPU' | Set-Content -LiteralPath (Join-Path $hostResult 'surfaceflinger-gpu.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys battery')) | Set-Content -LiteralPath (Join-Path $hostResult 'battery-before.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys thermalservice')) | Set-Content -LiteralPath (Join-Path $hostResult 'thermal-before.txt') -Encoding utf8

foreach ($mode in $Modes) {
    Write-Host "Running $mode ..."
    if ($CooldownSeconds -gt 0) { Start-Sleep -Seconds $CooldownSeconds }
    (Invoke-Adb -Arguments @('shell', 'dumpsys battery')) | Set-Content -LiteralPath (Join-Path $hostResult "battery-before-$mode.txt") -Encoding utf8
    (Invoke-Adb -Arguments @('shell', 'dumpsys thermalservice')) | Set-Content -LiteralPath (Join-Path $hostResult "thermal-before-$mode.txt") -Encoding utf8
    $remoteMode = "$remoteRoot/results/$mode"
    Invoke-Adb -Arguments @('shell', "rm -rf $remoteMode && mkdir -p $remoteMode") | Out-Null
    $command = "cd $remoteRoot && export LD_LIBRARY_PATH=$remoteRoot && ./indeconv_bench ./InDeConv-fp16.mnn ./real-input.bin ./results/$mode $mode 1 4 $Threads > ./results/$mode/run.log 2>&1"
    $runOutput = Invoke-Adb -Arguments @('shell', $command) -AllowFailure
    $remoteExitCode = $script:LastAdbExitCode
    $localMode = Join-Path $hostResult $mode
    New-Item -ItemType Directory -Path $localMode -Force | Out-Null
    Invoke-Adb -Arguments @('pull', "$remoteMode/.", $localMode) | Out-Null
    (Invoke-Adb -Arguments @('shell', 'dumpsys battery')) | Set-Content -LiteralPath (Join-Path $hostResult "battery-after-$mode.txt") -Encoding utf8
    (Invoke-Adb -Arguments @('shell', 'dumpsys thermalservice')) | Set-Content -LiteralPath (Join-Path $hostResult "thermal-after-$mode.txt") -Encoding utf8
    if ($remoteExitCode -ne 0) {
        $logPath = Join-Path $localMode 'run.log'
        $tail = if (Test-Path -LiteralPath $logPath) { (Get-Content -LiteralPath $logPath -Tail 80) -join "`n" } else { $runOutput -join "`n" }
        throw "Benchmark mode $mode failed with code $remoteExitCode`n$tail"
    }
}

(Invoke-Adb -Arguments @('shell', 'dumpsys battery')) | Set-Content -LiteralPath (Join-Path $hostResult 'battery-after.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys thermalservice')) | Set-Content -LiteralPath (Join-Path $hostResult 'thermal-after.txt') -Encoding utf8
Copy-Item -LiteralPath $manifest -Destination (Join-Path $hostResult 'build-manifest.json')

& python (Join-Path $PSScriptRoot 'Compare-MnnResults.py') --results $hostResult --reference $reference
if ($LASTEXITCODE -ne 0) {
    throw "Result comparison failed with code $LASTEXITCODE"
}
Write-Host "Benchmark complete: $hostResult"
