plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stellariumNonCommercial =
    providers.gradleProperty("stellariumNonCommercial").orNull == "true"

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

android {
    namespace = "com.indigo.mobileobservatory"
    compileSdk = 34

    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.indigo.mobileobservatory"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "STELLARIUM_ENABLED", stellariumNonCommercial.toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
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
                "**/libastap_cli.so"
            )
        }
    }

    sourceSets {
        getByName("main") {
            if (stellariumNonCommercial) {
                assets.srcDir("src/stellarium/assets")
            }
        }
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Rejects unsigned or debug-signed release builds."
    doLast {
        val missing = releaseSigningValues.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing requires: ${missing.joinToString()}")
        }
        val store = file(releaseStoreFile!!)
        if (!store.isFile) {
            throw GradleException("Release keystore does not exist: $store")
        }
        if (store.name.equals("debug.keystore", ignoreCase = true) ||
            releaseKeyAlias.equals("androiddebugkey", ignoreCase = true)
        ) {
            throw GradleException("Release builds cannot use the Android debug signing identity.")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
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
