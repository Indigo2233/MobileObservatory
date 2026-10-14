[CmdletBinding()]
param(
    [string]$CacheRoot = 'D:\Unity\MobileObservatory-BenchmarkCache',
    [string]$AndroidSdk = 'D:\Unity\AndroidSDK34',
    [string]$MnnTag = '3.6.1',
    [string]$MnnCommit = 'd407447ed56c4121a11ccbd266dc184ca1ead0c2',
    [string]$SourceOnnx = 'C:\Users\yao.su\AppData\Local\Programs\老佛爷的三件套\engine\models\InDeConv.onnx',
    [string]$SourceFits = 'D:\素材\心脏\DATE_4\2025-11-01_20-16-40_H_-10.00_600.00s_0013.fits',
    [string]$ConvertedModel = 'C:\Users\yao.su\AppData\Local\Temp\indeconv-real-case\InDeConv-fp16-plain.mnn',
    [string]$InputFixture = 'C:\Users\yao.su\AppData\Local\Temp\indeconv-real-case\real-input.bin',
    [string]$ReferenceOutput = 'C:\Users\yao.su\AppData\Local\Temp\indeconv-real-case\desktop-output.bin'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked {
    param([Parameter(Mandatory)][string]$FilePath, [Parameter(Mandatory)][string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath exited with code $LASTEXITCODE"
    }
}

function Assert-Hash {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Expected)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, actual $actual"
    }
}

$toolRoot = $PSScriptRoot
$mnnSource = Join-Path $CacheRoot "MNN-$MnnTag"
$mnnBuild = Join-Path $CacheRoot "MNN-$MnnTag-build-android-arm64-gpu"
$fixtures = Join-Path $CacheRoot 'fixtures'
$runtime = Join-Path $CacheRoot 'runtime'
$harnessBuild = Join-Path $toolRoot 'build-android'
$ndk = Join-Path $AndroidSdk 'ndk\25.1.8937393'
$cmake = Join-Path $AndroidSdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $AndroidSdk 'cmake\3.22.1\bin\ninja.exe'
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'
$cxxShared = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'

foreach ($required in @($cmake, $ninja, $toolchain, $cxxShared)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Android toolchain component is missing: $required"
    }
}

New-Item -ItemType Directory -Path $CacheRoot, $fixtures, $runtime -Force | Out-Null
Assert-Hash -Path $SourceOnnx -Expected '442e09deb80612e820647d5c9101b0d78884d3fb3bd5a583cec4605739ef0db6'
Assert-Hash -Path $SourceFits -Expected '1efcf70291f5015d29f21223aa16a004d41207194998b6d789a0223fe6cb593f'
if (-not (Test-Path -LiteralPath (Join-Path $mnnSource '.git'))) {
    Invoke-Checked -FilePath 'git' -Arguments @(
        'clone', '--branch', $MnnTag, '--depth', '1', '--recurse-submodules', '--shallow-submodules',
        'https://github.com/alibaba/MNN.git', $mnnSource
    )
}
$actualCommit = (& git -C $mnnSource rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $MnnCommit) {
    throw "MNN source revision mismatch. Expected $MnnCommit, actual $actualCommit"
}

$cachedModel = Join-Path $fixtures 'InDeConv-fp16.mnn'
$cachedInput = Join-Path $fixtures 'real-input.bin'
$cachedReference = Join-Path $fixtures 'desktop-output.bin'
if (-not (Test-Path -LiteralPath $cachedModel)) { Copy-Item -LiteralPath $ConvertedModel -Destination $cachedModel }
if (-not (Test-Path -LiteralPath $cachedInput)) { Copy-Item -LiteralPath $InputFixture -Destination $cachedInput }
if (-not (Test-Path -LiteralPath $cachedReference)) { Copy-Item -LiteralPath $ReferenceOutput -Destination $cachedReference }

Assert-Hash -Path $cachedModel -Expected '8b5bdac18b2aab6f28d964eed4ae9175e94374bdac7166441f5c6de7aa633421'
Assert-Hash -Path $cachedInput -Expected '93cb8ec08addef37fe2a34853fae8ef4adf856371d988e624d3a01963f7203ea'
Assert-Hash -Path $cachedReference -Expected '0c2a6750cc3579a4cfcb41eff39ea4d81b318082f789689d39608645d7fe74b7'

$commonToolchainArguments = @(
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DCMAKE_BUILD_TYPE=Release',
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-26',
    '-DANDROID_STL=c++_shared'
)

$mnnConfigure = @('-S', $mnnSource, '-B', $mnnBuild) + $commonToolchainArguments + @(
    '-DMNN_BUILD_SHARED_LIBS=ON',
    '-DMNN_SEP_BUILD=OFF',
    '-DMNN_OPENCL=ON',
    '-DMNN_OPENCL_SIZE_CUT=OFF',
    '-DMNN_VULKAN=ON',
    '-DMNN_VULKAN_IMAGE=ON',
    '-DMNN_ARM82=ON',
    '-DMNN_KLEIDIAI=OFF',
    '-DMNN_GPU_TIME_PROFILE=OFF',
    '-DMNN_BUILD_TOOLS=OFF',
    '-DMNN_BUILD_TEST=OFF',
    '-DMNN_BUILD_BENCHMARK=OFF',
    '-DMNN_BUILD_DEMO=OFF',
    '-DMNN_BUILD_FOR_ANDROID_COMMAND=ON',
    '-DMNN_USE_LOGCAT=OFF',
    '-DCMAKE_CXX_FLAGS='
)
Invoke-Checked -FilePath $cmake -Arguments $mnnConfigure
Invoke-Checked -FilePath $cmake -Arguments @('--build', $mnnBuild, '--target', 'MNN', '-j', '12')

$mnnLibrary = Join-Path $mnnBuild 'libMNN.so'
if (-not (Test-Path -LiteralPath $mnnLibrary -PathType Leaf)) {
    throw "MNN build did not produce $mnnLibrary"
}

$harnessConfigure = @('-S', $toolRoot, '-B', $harnessBuild) + $commonToolchainArguments + @(
    "-DMNN_SOURCE_DIR=$mnnSource",
    "-DMNN_LIBRARY=$mnnLibrary"
)
Invoke-Checked -FilePath $cmake -Arguments $harnessConfigure
Invoke-Checked -FilePath $cmake -Arguments @('--build', $harnessBuild, '--target', 'indeconv_bench', '-j', '8')

$harness = Join-Path $harnessBuild 'indeconv_bench'
foreach ($pair in @(
    @($harness, (Join-Path $runtime 'indeconv_bench')),
    @($mnnLibrary, (Join-Path $runtime 'libMNN.so')),
    @($cxxShared, (Join-Path $runtime 'libc++_shared.so'))
)) {
    Copy-Item -LiteralPath $pair[0] -Destination $pair[1] -Force
}

$manifest = [ordered]@{
    preparedAt = (Get-Date).ToString('o')
    mnnTag = $MnnTag
    mnnCommit = $MnnCommit
    sourceModel = [ordered]@{
        path = $SourceOnnx
        sha256 = (Get-FileHash -LiteralPath $SourceOnnx -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    sourceImage = [ordered]@{
        path = $SourceFits
        sha256 = (Get-FileHash -LiteralPath $SourceFits -Algorithm SHA256).Hash.ToLowerInvariant()
        crop = '1024x1024 real H-alpha FITS crop encoded in real-input.bin'
    }
    androidAbi = 'arm64-v8a'
    androidApi = 26
    buildOptions = @('OpenCL Image/Buffer', 'Vulkan Image', 'ARMv8.2 FP16', 'KleidiAI disabled')
    files = [ordered]@{}
}
foreach ($path in @($cachedModel, $cachedInput, $cachedReference, (Join-Path $runtime 'indeconv_bench'), (Join-Path $runtime 'libMNN.so'), (Join-Path $runtime 'libc++_shared.so'))) {
    $item = Get-Item -LiteralPath $path
    $manifest.files[$item.Name] = [ordered]@{
        path = $item.FullName
        bytes = $item.Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifestPath = Join-Path $CacheRoot 'manifest.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Host "Prepared MNN Android benchmark."
Write-Host "Manifest: $manifestPath"
Write-Host "Run tomorrow: .\tools\indeconv-mnn-benchmark\Run-MnnBenchmark.ps1"
