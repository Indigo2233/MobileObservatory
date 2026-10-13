param(
    [ValidateSet('baseline','current')][string]$Variant,
    [string[]]$Cases=@('normalizer','ddfapd','planetary','post_default','post_preview_cached','rejected_snapshot','queue_30fps'),
    [int]$Repeat=1,
    [switch]$SkipCompile,
    [string]$JavaExecutable,
    [Alias('JAVA_HOME')][string]$JavaHome=$env:JAVA_HOME,
    [Alias('GradleLib')][string]$CompilerLib,
    [string]$CoroutinesJar,
    [string]$JsonJar
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$benchmarkRoot=Join-Path $root 'build/performance'
$baseline='b081540'
$gradleUser=if($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
if(-not $JavaExecutable) {
    if($JavaHome) { $JavaExecutable=Join-Path $JavaHome 'bin/java.exe' }
    else {
        $toolchain=Join-Path (Split-Path $root -Parent) '.android-toolchain/jdk17/*/bin/java.exe'
        $JavaExecutable=(Get-ChildItem -Path $toolchain -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
        if(-not $JavaExecutable) { $JavaExecutable=(Get-Command java -ErrorAction SilentlyContinue).Source }
    }
}
if(-not $CompilerLib) { $CompilerLib=(Resolve-Path "$gradleUser/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/lib" -ErrorAction SilentlyContinue | Select-Object -First 1).Path }
if(-not $CoroutinesJar) { $CoroutinesJar=(Resolve-Path "$gradleUser/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3/*/kotlinx-coroutines-core-jvm-1.7.3.jar" -ErrorAction SilentlyContinue | Select-Object -First 1).Path }
if(-not $JsonJar) { $JsonJar=(Resolve-Path "$gradleUser/caches/modules-2/files-2.1/org.json/json/20240303/*/json-20240303.jar" -ErrorAction SilentlyContinue | Select-Object -First 1).Path }
foreach($entry in @(@('JavaExecutable',$JavaExecutable),@('CompilerLib',$CompilerLib),@('CoroutinesJar',$CoroutinesJar),@('JsonJar',$JsonJar))) {
    if(-not $entry[1] -or -not (Test-Path -LiteralPath $entry[1])) { throw "Missing $($entry[0]); pass -$($entry[0]) with the existing toolchain path." }
}
$java=$JavaExecutable; $lib=$CompilerLib; $coroutines=$CoroutinesJar; $json=$JsonJar
$cp="$lib/kotlin-stdlib-1.9.20.jar;$coroutines;$json"
$output="$benchmarkRoot/classes-$Variant"
$source="$benchmarkRoot/source-$Variant"
$paths=@('camera/CameraModels.kt','recording/RawRecordingNormalizer.kt')
$paths+=@('LinearImage','LinearPixelDecoder','DemosaicAlgorithm','DdfapdDemosaicer','VngDemosaicer','PlanetaryStacker','PlanetaryQuality','PlanetaryRegistration','StarRegistration','DeepSkyStacker','LiveStackSession','LiveDeepSkyEngine','BackgroundGradient','StackPostProcessor') | ForEach-Object { "stacking/$_.kt" }
if(-not $SkipCompile) {
    New-Item -ItemType Directory -Force -Path $source,$output | Out-Null
    $manifest=@()
    foreach($relative in $paths) {
        $repoPath="app/src/main/java/com/indigo/mobileobservatory/$relative"
        $target=Join-Path $source ([IO.Path]::GetFileName($relative))
        if($Variant -eq 'baseline') {
            $text=(& git -C $root show "${baseline}:$repoPath") -join "`n"
            if($LASTEXITCODE -ne 0) { throw "git show failed: $repoPath" }
            [IO.File]::WriteAllText($target,$text,[Text.UTF8Encoding]::new($false))
        } else { [IO.File]::Copy((Join-Path $root $repoPath),$target,$true) }
        $manifest+=@{path=$repoPath;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash}
    }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content "$benchmarkRoot/manifest-$Variant.json" -Encoding utf8
    $files=Get-ChildItem -LiteralPath $source -Filter '*.kt' | ForEach-Object FullName
    & $java -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $output @files "$PSScriptRoot/Benchmark.kt" "$PSScriptRoot/Adapter-$Variant.kt"
    if($LASTEXITCODE -ne 0) { throw 'Standalone Kotlin compile failed' }
}
foreach($case in $Cases) {
    & $java -Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=2 -cp "$output;$cp" com.indigo.mobileobservatory.stacking.BenchmarkKt $Variant $case "$benchmarkRoot/results/$Variant-$case-$Repeat.json"
    if($LASTEXITCODE -ne 0) { throw "Benchmark failed: $Variant/$case" }
}
