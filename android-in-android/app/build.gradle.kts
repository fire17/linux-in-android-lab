import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val scrcpyVersion: String = (project.findProperty("pip.scrcpyVersion") as String?) ?: "5.0.1"

android {
    namespace = "com.fire17.phoneinphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fire17.phoneinphone"
        // 26: java.util.Base64 (dadb key generation) + Process.waitFor(timeout) + MediaCodec low-latency paths.
        minSdk = 26
        // 36 (not 28): we never exec anything from app data. proot + its loader ship as lib*.so in
        // jniLibs and are exec'd from nativeLibraryDir (apk_data_file, executable for untrusted_app).
        // Guest ELFs are mmap'd PROT_EXEC by proot's loader, which sepolicy still allows. See README.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // proot binaries are fetched for these two ABIs only (phone + Apple-silicon/x86 emulator).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // extractNativeLibs=true: the .so files must exist as real files in nativeLibraryDir
            // so ProcessBuilder can exec libproot.so / libproot_loader.so.
            useLegacyPackaging = true
            // libproot.so / libproot_loader.so are executables in disguise (already stripped by
            // Termux). Don't let AGP's strip step touch them.
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot_loader.so")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // keep .tar.gz rootfs assets stored, not re-compressed
        noCompress += listOf("gz", "jar")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // In-app ADB client (Apache-2.0). Pure JVM, okio 2.10 transitively.
    implementation("dev.mobile:dadb:2.0.0")
    // Hidden-API exemption helper for the AVF experiment (Apache-2.0).
    // 6.1, not 6.2: 6.2's AAR metadata requires compileSdk 37.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("junit:junit:4.13.2")
}
