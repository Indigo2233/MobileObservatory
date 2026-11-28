import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class VerifyReleaseSigning : DefaultTask() {
    @get:Input
    abstract val missingProperties: ListProperty<String>

    @get:Input
    abstract val storeFilePath: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @TaskAction
    fun verify() {
        val missing = missingProperties.get()
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing requires: ${missing.joinToString()}")
        }

        val store = storeFilePath.get().takeIf { it.isNotBlank() }?.let(::File)
        if (store == null || !store.isFile) {
            throw GradleException("Release keystore does not exist: $store")
        }
        if (store.name.equals("debug.keystore", ignoreCase = true) ||
            keyAlias.get().equals("androiddebugkey", ignoreCase = true)
        ) {
            throw GradleException("Release builds cannot use the Android debug signing identity.")
        }
    }
}

abstract class ApplyGitPatch : DefaultTask() {
    @get:InputDirectory
    abstract val repository: DirectoryProperty

    @get:InputFile
    abstract val patchFile: RegularFileProperty

    @TaskAction
    fun apply() {
        val repo = repository.get().asFile.absolutePath
        val patch = patchFile.get().asFile.absolutePath

        fun git(vararg arguments: String): Int {
            val process = ProcessBuilder(listOf("git", "-C", repo) + arguments)
                .redirectErrorStream(true)
            process.environment().keys.removeIf { it.startsWith("GIT_CONFIG_") }
            val started = process.start()
            val output = started.inputStream.bufferedReader().use { it.readText() }
            val code = started.waitFor()
            if (output.isNotBlank()) logger.info(output.trimEnd())
            return code
        }

        val reverseCode = git("apply", "--reverse", "--check", patch)
        logger.info("MJSolEx patch reverse-check exit code: $reverseCode")
        if (reverseCode == 0) return
        val forwardCheckCode = git("apply", "--check", patch)
        logger.info("MJSolEx patch forward-check exit code: $forwardCheckCode")
        if (forwardCheckCode != 0) {
            throw GradleException("Could not apply patch: $patch")
        }
        val applyCode = git("apply", patch)
        logger.info("MJSolEx patch apply exit code: $applyCode")
        if (applyCode != 0) throw GradleException("Could not apply patch: $patch")
    }
}

abstract class VerifyRustLibraryPackaging : DefaultTask() {
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val maxBytesPerLibrary: Property<Long>

    @TaskAction
    fun verify() {
        val files = libraries.files
        if (files.isEmpty() || files.any { !it.isFile || it.length() <= 0L }) {
            throw GradleException("Rust JNI library output is missing or empty.")
        }
        val oversized = files.firstOrNull { it.length() > maxBytesPerLibrary.get() }
        if (oversized != null) {
            throw GradleException(
                "Rust JNI library ${oversized.name} is ${oversized.length()} bytes; " +
                    "Android GUI dependencies may have been linked."
            )
        }
    }
}

// Exercise the same upstream PPEC core in host JVM tests as in the Android JNI library.
val phd2HostDirectory = layout.buildDirectory.dir("phd2-host")
val phd2WindowsHost = System.getProperty("os.name").startsWith("Windows")
val phd2HostLibrary = phd2HostDirectory.map { it.file(if (phd2WindowsHost) "phd2_guiding.dll" else "libphd2_guiding.so") }
val phd2Jni = File(System.getProperty("java.home"), "include")
val phd2NativeRoot = file("src/main/cpp")
val phd2HostFlags = mutableListOf("g++", "-std=c++11", "-O1", "-DNDEBUG", "-DEIGEN_MPL2_ONLY", "-DEIGEN_DONT_PARALLELIZE", "-shared")
if (phd2WindowsHost) phd2HostFlags += listOf("-static-libgcc", "-static-libstdc++", "-static") else phd2HostFlags += "-fPIC"
phd2HostFlags += listOf("-I${phd2Jni.absolutePath}", "-I${File(phd2Jni, if (phd2WindowsHost) "win32" else "linux").absolutePath}",
    "-I${File(phd2NativeRoot, "phd2/eigen").absolutePath}", "-I${File(phd2NativeRoot, "phd2/src").absolutePath}",
    "-I${File(phd2NativeRoot, "phd2/tools").absolutePath}")
phd2HostFlags += listOf("phd2_guiding_jni.cpp", "phd2/src/gaussian_process_guider.cpp", "phd2/src/gaussian_process.cpp",
    "phd2/src/covariance_functions.cpp", "phd2/src/zfilterfactory.cpp", "phd2/tools/math_tools.cpp").map { File(phd2NativeRoot, it).absolutePath }
phd2HostFlags += listOf("-o", phd2HostLibrary.get().asFile.absolutePath)
phd2HostDirectory.get().asFile.mkdirs()
val buildPhd2Host by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp/phd2"), file("src/main/cpp/phd2_guiding_jni.cpp"))
    outputs.file(phd2HostLibrary)
    commandLine(phd2HostFlags)
}
tasks.withType<Test>().configureEach {
    dependsOn(buildPhd2Host)
    systemProperty("java.library.path", phd2HostDirectory.get().asFile.absolutePath)
}

val stellariumNonCommercial =
    providers.gradleProperty("stellariumNonCommercial").orNull == "true"

val sequenceEnabled =
    providers.gradleProperty("sequenceEnabled").orNull?.toBooleanStrictOrNull() ?: false

val emulatorTest =
    providers.gradleProperty("emulatorTest").orNull?.toBooleanStrictOrNull() ?: false

val observatoryBuildId =
    providers.gradleProperty("observatoryBuildId").orNull?.takeIf { it.isNotBlank() } ?: "development"

val rustStackAccumulatorDirectory = file("src/main/rust/stack_accumulator")
val rustPlanetaryStackDirectory = file("src/main/rust/planetary_stack")
val rustDemosaicDirectory = file("src/main/rust/demosaic")
val rustCalibrationDirectory = file("src/main/rust/calibration")
val rustMjsolexDirectory = file("src/main/rust/mjsolex")
val rustSolarScanDirectory = file("src/main/rust/solar_scan")
val rustStackJniLibsDirectory = layout.buildDirectory.dir("rustJniLibs/stack")
val rustPlanetaryJniLibsDirectory = layout.buildDirectory.dir("rustJniLibs/planetary")
val rustDemosaicJniLibsDirectory = layout.buildDirectory.dir("rustJniLibs/demosaic")
val rustCalibrationJniLibsDirectory = layout.buildDirectory.dir("rustJniLibs/calibration")
val rustSolarScanCargoOutputDirectory = layout.buildDirectory.dir("rustJniLibs/solar_scan-cargo")
val rustSolarScanJniLibsDirectory = layout.buildDirectory.dir("rustJniLibs/solar_scan")
val rustTargets = if (emulatorTest) listOf("x86", "x86_64") else listOf("arm64-v8a")
val rustAndroidTargetDirectory = layout.buildDirectory.dir("rust-target/android")
val rustHostTargetDirectory = layout.buildDirectory.dir("rust-target/host")
val rustHostLibraryDirectory = rustHostTargetDirectory.map { it.dir("debug") }
val rustStackHostLibrary = rustHostLibraryDirectory.map {
    it.file(if (phd2WindowsHost) "stack_accumulator_jni.dll" else "libstack_accumulator_jni.so")
}
val rustPlanetaryHostLibrary = rustHostLibraryDirectory.map {
    it.file(if (phd2WindowsHost) "planetary_stack_jni.dll" else "libplanetary_stack_jni.so")
}
val rustDemosaicHostLibrary = rustHostLibraryDirectory.map {
    it.file(if (phd2WindowsHost) "demosaic_jni.dll" else "libdemosaic_jni.so")
}
val rustCalibrationHostLibrary = rustHostLibraryDirectory.map {
    it.file(if (phd2WindowsHost) "calibration_jni.dll" else "libcalibration_jni.so")
}
val rustSolarScanHostLibrary = rustHostLibraryDirectory.map {
    it.file(if (phd2WindowsHost) "solar_scan_jni.dll" else "libsolar_scan_jni.so")
}

val applyMjsolexCorePatch by tasks.registering(ApplyGitPatch::class) {
    description = "Builds only the MJSol'Ex reconstruction core, without its Android GUI."
    repository.set(rustMjsolexDirectory)
    patchFile.set(file("../patches/mjsolex-core-only.patch"))
}

val applyMjsolexSpectrumRangePatch by tasks.registering(ApplyGitPatch::class) {
    description = "Adds bounded spectrum averaging for live solar sweep coverage checks."
    dependsOn(applyMjsolexCorePatch)
    repository.set(rustMjsolexDirectory)
    patchFile.set(file("../patches/mjsolex-spectrum-range.patch"))
}

val applyMjsolexColumnProfilePatch by tasks.registering(ApplyGitPatch::class) {
    description = "Exposes per-frame monochrome column sums for live solar sweeps."
    dependsOn(applyMjsolexSpectrumRangePatch)
    repository.set(rustMjsolexDirectory)
    patchFile.set(file("../patches/mjsolex-column-profile.patch"))
}

val buildRustStackAccumulator by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust deep-sky accumulator for the selected Android ABI."
    workingDir(rustStackAccumulatorDirectory)
    inputs.files(
        fileTree(rustStackAccumulatorDirectory) {
            exclude("target/**")
        }
    )
    inputs.property("rustTargets", rustTargets)
    outputs.dir(rustStackJniLibsDirectory)
    environment("CARGO_TARGET_DIR", rustAndroidTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            rustTargets.forEach {
                add("-t")
                add(it)
            }
            add("-o")
            add(rustStackJniLibsDirectory.get().asFile.absolutePath)
            add("build")
            add("--release")
        }
    )
}

val buildRustPlanetaryStack by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust planetary stack core for the selected Android ABI."
    workingDir(rustPlanetaryStackDirectory)
    inputs.files(
        fileTree(rustPlanetaryStackDirectory) {
            exclude("target/**")
        }
    )
    inputs.property("rustTargets", rustTargets)
    outputs.dir(rustPlanetaryJniLibsDirectory)
    environment("CARGO_TARGET_DIR", rustAndroidTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            rustTargets.forEach {
                add("-t")
                add(it)
            }
            add("-o")
            add(rustPlanetaryJniLibsDirectory.get().asFile.absolutePath)
            add("build")
            add("--release")
        }
    )
}

val buildRustDemosaic by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust Bayer demosaic core for the selected Android ABI."
    workingDir(rustDemosaicDirectory)
    inputs.files(
        fileTree(rustDemosaicDirectory) {
            exclude("target/**")
        }
    )
    inputs.property("rustTargets", rustTargets)
    outputs.dir(rustDemosaicJniLibsDirectory)
    environment("CARGO_TARGET_DIR", rustAndroidTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            rustTargets.forEach {
                add("-t")
                add(it)
            }
            add("-o")
            add(rustDemosaicJniLibsDirectory.get().asFile.absolutePath)
            add("build")
            add("--release")
        }
    )
}

val buildRustCalibration by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust sensor calibration core for the selected Android ABI."
    workingDir(rustCalibrationDirectory)
    inputs.files(
        fileTree(rustCalibrationDirectory) {
            exclude("target/**")
        }
    )
    inputs.property("rustTargets", rustTargets)
    outputs.dir(rustCalibrationJniLibsDirectory)
    environment("CARGO_TARGET_DIR", rustAndroidTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            rustTargets.forEach {
                add("-t")
                add(it)
            }
            add("-o")
            add(rustCalibrationJniLibsDirectory.get().asFile.absolutePath)
            add("build")
            add("--release")
        }
    )
}

val buildRustSolarScanCargo by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the MJSol'Ex Rust reconstruction JNI libraries."
    dependsOn(applyMjsolexColumnProfilePatch)
    workingDir(rustSolarScanDirectory)
    inputs.files(
        fileTree(rustSolarScanDirectory) {
            exclude("target/**")
        },
        fileTree(rustMjsolexDirectory) {
            exclude("target/**", ".git")
        }
    )
    inputs.property("rustTargets", rustTargets)
    outputs.dir(rustSolarScanCargoOutputDirectory)
    environment("CARGO_TARGET_DIR", rustAndroidTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            rustTargets.forEach {
                add("-t")
                add(it)
            }
            add("-o")
            add(rustSolarScanCargoOutputDirectory.get().asFile.absolutePath)
            add("build")
            add("--release")
            add("--locked")
        }
    )
}

val buildRustSolarScan by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the Solar JNI library without unused dependency cdylibs."
    dependsOn(buildRustSolarScanCargo)
    from(rustSolarScanCargoOutputDirectory) {
        include("**/libsolar_scan_jni.so")
    }
    into(rustSolarScanJniLibsDirectory)
}

val verifyRustSolarScanPackaging by tasks.registering(VerifyRustLibraryPackaging::class) {
    group = "verification"
    description = "Rejects Solar JNI libraries that unexpectedly link the MJSol'Ex Android GUI."
    dependsOn(buildRustSolarScan)
    libraries.from(fileTree(rustSolarScanJniLibsDirectory) {
        include("**/*.so")
    })
    maxBytesPerLibrary.set(5L * 1024L * 1024L)
}

val buildRustStackAccumulatorHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Rust deep-sky accumulator for host JVM tests."
    workingDir(rustStackAccumulatorDirectory)
    inputs.files(
        fileTree(rustStackAccumulatorDirectory) {
            exclude("target/**")
        }
    )
    outputs.file(rustStackHostLibrary)
    environment("CARGO_TARGET_DIR", rustHostTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine("cargo", "build", "--manifest-path", file("src/main/rust/stack_accumulator/Cargo.toml").absolutePath)
}

val buildRustPlanetaryStackHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Rust planetary stack core for host JVM tests."
    workingDir(rustPlanetaryStackDirectory)
    inputs.files(
        fileTree(rustPlanetaryStackDirectory) {
            exclude("target/**")
        }
    )
    outputs.file(rustPlanetaryHostLibrary)
    environment("CARGO_TARGET_DIR", rustHostTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine("cargo", "build", "--manifest-path", file("src/main/rust/planetary_stack/Cargo.toml").absolutePath)
}

val buildRustDemosaicHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Rust Bayer demosaic core for host JVM tests."
    workingDir(rustDemosaicDirectory)
    inputs.files(
        fileTree(rustDemosaicDirectory) {
            exclude("target/**")
        }
    )
    outputs.file(rustDemosaicHostLibrary)
    environment("CARGO_TARGET_DIR", rustHostTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine("cargo", "build", "--manifest-path", file("src/main/rust/demosaic/Cargo.toml").absolutePath)
}

val buildRustCalibrationHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Rust sensor calibration core for host JVM tests."
    workingDir(rustCalibrationDirectory)
    inputs.files(
        fileTree(rustCalibrationDirectory) {
            exclude("target/**")
        }
    )
    outputs.file(rustCalibrationHostLibrary)
    environment("CARGO_TARGET_DIR", rustHostTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine("cargo", "build", "--manifest-path", file("src/main/rust/calibration/Cargo.toml").absolutePath)
}

val buildRustSolarScanHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the MJSol'Ex Rust reconstruction core for host JVM tests."
    dependsOn(applyMjsolexColumnProfilePatch)
    workingDir(rustSolarScanDirectory)
    inputs.files(
        fileTree(rustSolarScanDirectory) {
            exclude("target/**")
        },
        fileTree(rustMjsolexDirectory) {
            exclude("target/**", ".git")
        }
    )
    outputs.file(rustSolarScanHostLibrary)
    environment("CARGO_TARGET_DIR", rustHostTargetDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", "1")
    commandLine(
        "cargo",
        "build",
        "--manifest-path",
        file("src/main/rust/solar_scan/Cargo.toml").absolutePath,
        "--locked"
    )
}

val buildRustNativeLibraries by tasks.registering {
    group = "build"
    description = "Builds all Rust native libraries for Android."
    dependsOn(
        buildRustStackAccumulator,
        buildRustPlanetaryStack,
        buildRustDemosaic,
        buildRustCalibration,
        verifyRustSolarScanPackaging
    )
}

buildRustPlanetaryStack.configure { mustRunAfter(buildRustStackAccumulator) }
buildRustDemosaic.configure { mustRunAfter(buildRustPlanetaryStack) }
buildRustCalibration.configure { mustRunAfter(buildRustDemosaic) }
buildRustSolarScanCargo.configure { mustRunAfter(buildRustCalibration) }

tasks.matching { it.name == "preBuild" || it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRustNativeLibraries)
}

val buildRustHostLibraries by tasks.registering {
    group = "verification"
    description = "Builds all Rust native libraries for host JVM tests."
    dependsOn(
        buildRustStackAccumulatorHost,
        buildRustPlanetaryStackHost,
        buildRustDemosaicHost,
        buildRustCalibrationHost,
        buildRustSolarScanHost
    )
}

buildRustPlanetaryStackHost.configure { mustRunAfter(buildRustStackAccumulatorHost) }
buildRustDemosaicHost.configure { mustRunAfter(buildRustPlanetaryStackHost) }
buildRustCalibrationHost.configure { mustRunAfter(buildRustDemosaicHost) }
buildRustSolarScanHost.configure { mustRunAfter(buildRustCalibrationHost) }
buildRustStackAccumulatorHost.configure { mustRunAfter(buildRustCalibration) }
buildRustHostLibraries.configure { mustRunAfter(buildRustNativeLibraries) }

tasks.withType<Test>().configureEach {
    dependsOn(buildRustHostLibraries)
    systemProperty(
        "java.library.path",
        listOf(
            rustHostLibraryDirectory.get().asFile.absolutePath,
            phd2HostDirectory.get().asFile.absolutePath
        ).joinToString(File.pathSeparator)
    )
}

val updateManifestUrl =
    providers.gradleProperty("updateManifestUrl").orNull
        ?.takeIf { it.isNotBlank() }
        ?: "https://github.com/Indigo2233/MobileObservatory/releases/latest/download/update.json"

val updateManifestFallbackUrl =
    providers.gradleProperty("updateManifestFallbackUrl").orNull
        ?.takeIf { it.isNotBlank() }
        ?: "https://indigo2233.github.io/MobileObservatory/update.json"

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun releaseCredential(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseCredential("releaseStoreFile", "ANDROID_RELEASE_KEYSTORE")
val releaseStorePassword = releaseCredential("releaseStorePassword", "ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseCredential("releaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseCredential("releaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    "ANDROID_RELEASE_KEYSTORE" to releaseStoreFile,
    "ANDROID_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "ANDROID_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "ANDROID_RELEASE_KEY_PASSWORD" to releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { it.second != null }
val releaseSigningMissing = releaseSigningValues.filter { it.second == null }.map { it.first }
val releaseStorePath = releaseStoreFile?.let { File(it).absolutePath }
val releaseKeyAliasValue = releaseKeyAlias

android {
    namespace = "com.indigo.mobileobservatory"
    compileSdk = 34

    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.indigo.mobileobservatory"
        minSdk = 26
        targetSdk = 34
        versionCode = 67
        versionName = "1.0.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "STELLARIUM_ENABLED", stellariumNonCommercial.toString())
        buildConfigField("boolean", "SEQUENCE_ENABLED", sequenceEnabled.toString())
        buildConfigField("boolean", "EMULATOR_TEST", emulatorTest.toString())
        buildConfigField("String", "UPDATE_MANIFEST_URL", buildConfigString(updateManifestUrl))
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_FALLBACK_URL",
            buildConfigString(updateManifestFallbackUrl)
        )
        resValue("string", "source_build_id", observatoryBuildId)

        ndk {
            abiFilters += if (emulatorTest) {
                listOf("x86", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (emulatorTest) {
                applicationIdSuffix = ".emulatortest"
                versionNameSuffix = "-emulator-test"
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    testOptions {
        // Lets tests construct android.webkit/util stubs instead of hitting "Stub!".
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libtoupcam.so",
                "**/libASICamera2.so",
                // Keep the irreplaceable community ZWO USB binary byte-for-byte in the APK.
                "**/libusb-1.0.so",
                "**/libastap_cli.so"
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("src/test/resources/astrometry")
        getByName("main") {
            jniLibs.srcDir(rustStackJniLibsDirectory)
            jniLibs.srcDir(rustPlanetaryJniLibsDirectory)
            jniLibs.srcDir(rustDemosaicJniLibsDirectory)
            jniLibs.srcDir(rustCalibrationJniLibsDirectory)
            jniLibs.srcDir(rustSolarScanJniLibsDirectory)
            if (stellariumNonCommercial) {
                assets.srcDir("src/stellarium/assets")
            }
        }
    }
}

val verifyReleaseSigning = tasks.register<VerifyReleaseSigning>("verifyReleaseSigning") {
    group = "verification"
    description = "Rejects unsigned or debug-signed release builds."
    missingProperties.set(releaseSigningMissing)
    storeFilePath.set(releaseStorePath ?: "")
    keyAlias.set(releaseKeyAliasValue ?: "")
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    if (emulatorTest) {
        doFirst {
            throw GradleException("emulatorTest is restricted to debug builds.")
        }
    }
    dependsOn(verifyReleaseSigning)
}

// External FITS replays track the actual source file so Gradle cannot reuse a crop-only run.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    providers.gradleProperty("guideFitsPath").orNull?.let { path ->
        inputs.file(path).withPropertyName("guideFitsSource")
        environment("GUIDE_FITS_PATH", path)
    }
    providers.gradleProperty("guideFitsReportDir").orNull?.let { path ->
        inputs.property("guideFitsReportDir", path)
        environment("GUIDE_FITS_REPORT_DIR", path)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    implementation(files("libs/zwocamera.jar"))
    implementation(files("libs/playerOne_AndroidSdk/playerone-camera-sdk-release.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Android ships org.json as a stub that throws in unit tests.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
