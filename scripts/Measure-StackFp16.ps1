param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleLib,
    [string]$OutputDirectory = 'build/diagnostics/fp16-precision',
    [ValidateRange(16, 16384)][int]$Frames = 4096,
    [string]$Scenarios = 'dark,bright,low_noise,calibrated'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { throw 'Pass -JavaHome pointing to JDK 21 or newer.' }
if (-not $GradleLib) {
    $compiler = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/wrapper/dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse |
        Select-Object -First 1
    if (-not $compiler) { throw 'Run Gradle once or pass -GradleLib.' }
    $GradleLib = Split-Path $compiler.FullName -Parent
}
$java = Join-Path $JavaHome 'bin/java.exe'
$standardLibrary = (Get-ChildItem -LiteralPath $GradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1).FullName
$destination = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }
$classes = Join-Path $destination 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$main = Join-Path $root 'app/src/main/java/com/indigo/mobileobservatory/stacking'
$sourceFiles = @('LinearImage.kt', 'StarRegistration.kt', 'DeepSkyStacker.kt', 'StackPreviewGrid.kt', 'OfflineDeepSkyMemory.kt') |
    ForEach-Object { Join-Path $main $_ }

# Generate an experimental copy of the current production engine. Only its class name,
# constructor and stores into mean/M2 change. The production file is never written.
$engine = Get-Content -LiteralPath (Join-Path $main 'DeepSkyStacker.kt') -Raw
$bodyStart = $engine.IndexOf('class DeepSkyStacker(')
if ($bodyStart -lt 0) { throw 'Production engine class signature changed.' }
$copy = "package com.indigo.mobileobservatory.stacking`nimport kotlin.math.*`nimport java.util.concurrent.CancellationException`n" + $engine.Substring($bodyStart)
$copy = $copy.Replace('class DeepSkyStacker(', 'class PrecisionProbeStacker(')
$constructor = 'private val reusableOfflineInput: Boolean = false, private val livePreviewMaxSide: Int? = null)'
$update = 'mean[i] += delta / next; m2[i] += delta * (sampled[ch] - mean[i])'
if (($copy.Split($constructor).Length - 1) -ne 1 -or ($copy.Split($update).Length - 1) -ne 1) {
    throw 'Production accumulation changed; inspect the experiment before running it.'
}
$copy = $copy.Replace($constructor, 'private val reusableOfflineInput: Boolean = false, private val livePreviewMaxSide: Int? = null, private val precision: ProbeEncoding = ProbeEncoding(ProbePrecision.FP32))')
$copy = $copy.Replace($update, 'val updatedMean = mean[i] + delta / next; mean[i] = precision.storeMean(updatedMean, ch); m2[i] = precision.storeM2(m2[i] + delta * (sampled[ch] - if (precision.mode.centered) updatedMean else mean[i]))')
$generated = Join-Path $destination 'PrecisionProbeStacker.kt'
$copy | Set-Content -LiteralPath $generated -Encoding utf8
$harness = Join-Path $PSScriptRoot 'stacking/Fp16PrecisionProbe.kt'
Copy-Item -LiteralPath $harness -Destination (Join-Path $destination 'Fp16PrecisionProbe.kt')
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $destination 'Measure-StackFp16.ps1')
$snapshot = @($sourceFiles + $harness + $generated | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
})
[ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    frames = $Frames
    scenarios = $Scenarios
    scope = 'Desktop numerical experiment: binary16 storage round-trip, FP32 arithmetic, exact counts; arrays remain FloatArray, so this does not measure packed-FP16 heap or device speed. Harness and launcher are archived alongside the generated engine.'
    files = $snapshot
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'source-snapshot.json') -Encoding utf8

& $java -cp "$GradleLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp $standardLibrary `
    -d $classes @sourceFiles $generated $harness
if ($LASTEXITCODE -ne 0) { throw 'FP16 diagnostic compilation failed.' }
& $java -Xmx512m -XX:ActiveProcessorCount=2 -cp "$classes;$standardLibrary" `
    com.indigo.mobileobservatory.stacking.Fp16PrecisionProbe $destination $Frames $Scenarios
if ($LASTEXITCODE -ne 0) { throw 'FP16 precision experiment failed.' }
foreach ($entry in $snapshot) {
    if ((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
        throw ('Source changed during the experiment: ' + $entry.path)
    }
}
