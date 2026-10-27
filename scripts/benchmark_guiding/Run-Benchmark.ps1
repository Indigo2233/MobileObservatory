param(
    [string]$BaselineRevision = 'ca0278142caa8cd417ca0dcadf68b6caecaa5878',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipCompile
)
$ErrorActionPreference = 'Stop'
$guideRoot = (Resolve-Path "$PSScriptRoot/../..").Path
$guideOutput = Join-Path $guideRoot 'build/reports/guide-color/local-performance'
$guideClasses = Join-Path $guideRoot 'app/build/tmp/kotlin-classes/debug'
$guideJava = Join-Path $JavaHome 'bin/java.exe'
$guideJavac = Join-Path $JavaHome 'bin/javac.exe'
$guideGradleUser = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$guideCompiler = (Resolve-Path "$guideGradleUser/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/lib" | Select-Object -First 1).Path
$guideKotlinVersion = ([regex]::Match((Get-Content "$guideRoot/build.gradle.kts" -Raw), 'id\("org.jetbrains.kotlin.android"\)\s+version\s+"([^"]+)"')).Groups[1].Value
$guideCompilerJar = (Get-ChildItem "$guideGradleUser/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/$guideKotlinVersion" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$guideStdlib = (Get-ChildItem "$guideGradleUser/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.9.24" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force -Path $guideOutput | Out-Null
Push-Location $guideRoot
try {
    if (-not $SkipCompile) {
        & ./gradlew.bat :app:compileDebugKotlin -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=false
        if ($LASTEXITCODE -ne 0) { throw 'Production Kotlin compilation failed' }
    }
    $guideSource = Join-Path $guideOutput 'baseline-source'
    $guideBaseline = Join-Path $guideOutput 'baseline-classes'
    $guideCurrent = Join-Path $guideOutput 'current-classes'
    $guideHarness = Join-Path $guideOutput 'harness'
    New-Item -ItemType Directory -Force -Path $guideSource,$guideBaseline,$guideCurrent,$guideHarness | Out-Null
    $guideManifest = @()
    $guideFiles = foreach ($guideName in @('GuideImage','GuideModule','Phd2StarMeasurement')) {
        $guideRelative = "app/src/main/java/com/indigo/mobileobservatory/guide/$guideName.kt"
        $guideTarget = Join-Path $guideSource "$guideName.kt"
        $guideText = (& git show "${BaselineRevision}:$guideRelative") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read baseline: $guideRelative" }
        [IO.File]::WriteAllText($guideTarget, $guideText, [Text.UTF8Encoding]::new($false))
        $guideManifest += @{ path = $guideRelative; currentSha256 = (Get-FileHash $guideRelative -Algorithm SHA256).Hash }
        $guideTarget
    }
    & $guideJava -cp "$guideCompilerJar;$guideCompiler/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$guideClasses" -classpath "$guideClasses;$guideStdlib" -d $guideBaseline @guideFiles
    if ($LASTEXITCODE -ne 0) { throw 'Baseline Kotlin compilation failed' }
    $guideCurrentFiles = @('GuideImage','GuideModule','Phd2StarMeasurement') | ForEach-Object { "$guideRoot/app/src/main/java/com/indigo/mobileobservatory/guide/$_.kt" }
    & $guideJava -cp "$guideCompilerJar;$guideCompiler/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$guideClasses" -classpath "$guideClasses;$guideStdlib" -d $guideCurrent @guideCurrentFiles
    if ($LASTEXITCODE -ne 0) { throw 'Current Kotlin compilation failed' }
    & $guideJavac -cp "$guideClasses;$guideStdlib" -d $guideHarness "$PSScriptRoot/GuideReconstructionBenchmark.java"
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
    @{
        baselineRevision = $BaselineRevision
        currentRevision = (& git rev-parse HEAD)
        currentSources = $guideManifest
        compiler = "Both variants compile the production source with Kotlin $guideKotlinVersion CLI, JVM target 17, without Compose instrumentation"
        protocol = 'Desktop; 30 warmups, 21 measured runs; separate JVM per case; serial execution; synthetic RAW14, 20 stars; detector maxStars=16'
        exclusions = 'Frame construction, capture, preview rendering, PSF profiles; these measurements do not establish Android device latency'
    } | ConvertTo-Json -Depth 5 | Set-Content "$guideOutput/provenance.json" -Encoding utf8
    foreach ($guideSize in @(@(640,480),@(1920,1080),@(3840,2160))) {
        foreach ($guideOperation in @('detect','local')) {
            foreach ($guideVariant in @('baseline','current')) {
                $guideCp = if ($guideVariant -eq 'baseline') { "$guideBaseline;$guideClasses;$guideStdlib;$guideHarness" } else { "$guideCurrent;$guideClasses;$guideStdlib;$guideHarness" }
                & $guideJava -Xms512m -Xmx512m -XX:+UseG1GC -cp $guideCp GuideReconstructionBenchmark $guideOperation $guideSize[0] $guideSize[1] |
                    Tee-Object -FilePath "$guideOutput/$guideVariant-$guideOperation-$($guideSize[0]).csv"
                if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $guideVariant/$guideOperation" }
            }
        }
    }
} finally { Pop-Location }
