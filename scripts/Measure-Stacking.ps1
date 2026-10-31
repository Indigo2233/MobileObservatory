param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleLib,
    [string]$OutputDirectory = 'build/reports/stacking'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome pointing to JDK 17.' }
if (-not $GradleLib) {
    $compiler = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/wrapper/dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1
    if (-not $compiler) { throw 'Run gradlew once or pass -GradleLib containing Kotlin compiler and JUnit jars.' }
    $GradleLib = Split-Path $compiler.FullName -Parent
}
$java = Join-Path $JavaHome 'bin/java.exe'
$standardLibrary = (Get-ChildItem -LiteralPath $GradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1).FullName
$junit = (Get-ChildItem -LiteralPath $GradleLib -Filter 'junit-4*.jar' | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem -LiteralPath $GradleLib -Filter 'hamcrest-core-*.jar' | Select-Object -First 1).FullName
$classpath = "$standardLibrary;$junit;$hamcrest"
$classes = Join-Path $root 'build/stacking-diagnostics-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$mainDirectory = Join-Path $root 'app/src/main/java/com/indigo/mobileobservatory/stacking'
$testDirectory = Join-Path $root 'app/src/test/java/com/indigo/mobileobservatory/stacking'
$sources = @('LinearImage.kt','StarRegistration.kt','DeepSkyStacker.kt','StackPreviewGrid.kt','OfflineDeepSkyMemory.kt','BackgroundGradient.kt','StackPostProcessor.kt') | ForEach-Object { Join-Path $mainDirectory $_ }
$sources += @('DeepSkyAlgorithmsTest.kt','StackPostProcessorTest.kt','StackingDiagnostics.kt') | ForEach-Object { Join-Path $testDirectory $_ }
& $java -cp "$GradleLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Kotlin diagnostic compilation failed.' }
& $java -cp "$classes;$classpath" org.junit.runner.JUnitCore com.indigo.mobileobservatory.stacking.DeepSkyAlgorithmsTest com.indigo.mobileobservatory.stacking.StackPostProcessorTest
if ($LASTEXITCODE -ne 0) { throw 'Deep-sky/postprocessing tests failed.' }
$destination = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }
& $java -Xmx512m -cp "$classes;$classpath" com.indigo.mobileobservatory.stacking.StackingDiagnostics $destination
if ($LASTEXITCODE -ne 0) { throw 'Stacking benchmark failed.' }
