import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

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
        versionCode = 62
        versionName = "1.0.10"
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
