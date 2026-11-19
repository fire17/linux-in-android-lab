import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fire17.linuxinapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fire17.linuxinapp"
        minSdk = 26
        // targetSdk 36 is allowed because NOTHING is execve()'d from app data:
        // proot ships as lib*.so in jniLibs and runs from nativeLibraryDir (apk_data_file,
        // executable), and the guest ELFs are mmap'd by proot's loader, not exec'd.
        // See README "Why targetSdk 36 (not 28)".
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a = real phones + Apple-Silicon emulators; x86_64 = Intel emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // REQUIRED: makes the installer extract libproot.so & co. to nativeLibraryDir as
            // real files (sets android:extractNativeLibs=true). Without it the "executables"
            // stay inside the APK and cannot be exec'd.
            useLegacyPackaging = true
            // Keep the sha256-pinned binaries byte-identical (no strip pass).
            keepDebugSymbols += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Termux terminal widget + emulator (Apache-2.0 modules of termux-app, via JitPack).
    // terminal-emulator 0.118.0 comes in transitively (it carries libtermux.so for the pty).
    implementation("com.termux.termux-app:terminal-view:0.118.0")

    // On-device/emulator self-test (CI job "emulator-selftest" runs it on an x86_64 emulator).
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// ---------------------------------------------------------------------------------------
// Fail fast with a helpful message if the binary payload was not fetched.
// CI runs scripts/fetch-all.sh before assembling; locally you must too.
// ---------------------------------------------------------------------------------------
val requiredPayload: List<String> =
    listOf("arm64-v8a", "x86_64").flatMap { abi ->
        listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so")
            .map { "src/main/jniLibs/$abi/$it" }
    } + listOf(
        "src/main/assets/rootfs/alpine-aarch64.tar.gz",
        "src/main/assets/rootfs/alpine-x86_64.tar.gz",
    )

val verifyNativePayload by tasks.registering {
    group = "verification"
    description = "Checks that scripts/fetch-all.sh has populated jniLibs/ and assets/rootfs/."
    val files = requiredPayload.map { layout.projectDirectory.file(it).asFile }
    doLast {
        val missing = files.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "LinuxInApp binary payload missing. Run ./scripts/fetch-all.sh from the project root.\n" +
                    missing.joinToString("\n") { "  - $it" }
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyNativePayload)
}
