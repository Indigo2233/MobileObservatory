import org.gradle.api.GradleException

plugins {
    id("com.android.application")
}

val benchmarkCacheRoot = providers.gradleProperty("benchmarkCacheRoot")
    .orElse("D:/Unity/MobileObservatory-BenchmarkCache")
    .get()
val mnnSource = "$benchmarkCacheRoot/MNN-3.6.1"
val mnnLibrary = "$benchmarkCacheRoot/runtime/libMNN.so"
val mnnRuntime = file("$benchmarkCacheRoot/runtime")
val stagedJniLibraries = layout.buildDirectory.dir("generated/mnnJniLibs")

val stageMnnRuntime by tasks.registering(Sync::class) {
    from(mnnRuntime) {
        include("*.so")
    }
    into(stagedJniLibraries.map { it.dir("arm64-v8a") })
}

if (!file(mnnSource).isDirectory || !file(mnnLibrary).isFile) {
    throw GradleException("Run Prepare-MnnBenchmark.ps1 before building the Android probe.")
}

android {
    namespace = "com.indigo.mobileobservatory.indeconvprobe"
    compileSdk = 34
    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.indigo.mobileobservatory.indeconvprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DMNN_SOURCE_DIR=$mnnSource",
                    "-DMNN_LIBRARY=$mnnLibrary"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(stagedJniLibraries)
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageMnnRuntime)
}
