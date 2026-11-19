import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fire17.linuxdesk"
    compileSdk = 36
    // Must be the same NDK as upstream's `termuxX11NdkVersion` (third_party/termux-x11/lorie/version.gradle).
    // Otherwise :lorie's CMake configuration differs between the app and the library, and the X server
    // gets compiled twice.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.fire17.linuxdesk"
        minSdk = 26
        // targetSdk 36 works because nothing is execve()'d from app data (see README "Why targetSdk 36"):
        //  - proot ships as lib*.so in jniLibs and runs from nativeLibraryDir (apk_data_file, exec allowed);
        //  - proot's loader (also in nativeLibraryDir) mmaps the guest ELFs instead of exec'ing them;
        //  - the X server is libXlorie.so, loaded into our own :x11 process (xkbcomp is compiled in).
        targetSdk = 36
        // CI passes its run number, so every build is a higher version than the last and installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 1
        versionName = "0.2.23"

        ndk {
            // arm64-v8a = real phones. x86_64 = the CI emulator smoke test.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // One fixed debug key, so each new APK installs OVER the previous one (Tami, voice AC7D55D3: a fresh CI
    // runner made a new key every build, and Android refuses an update signed with a different key).
    // CI writes the key from the repo secret LINUXDESK_DEBUG_KEYSTORE_B64 to LINUXDESK_DEBUG_KEYSTORE; the key is
    // never committed. Without that file the build falls back to the default debug key, as before.
    val fixedDebugKeystore = System.getenv("LINUXDESK_DEBUG_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (fixedDebugKeystore != null) {
            create("fixedDebug") {
                storeFile = fixedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (fixedDebugKeystore != null) signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // iroh-ffi ships JNA desktop binaries inside the AAR that Android never loads (as Storage Genie does).
            excludes += setOf(
                "/win32-x86-64/**", "/win32-x86/**",
                "/darwin-aarch64/**", "/darwin-x86-64/**", "/darwin/**",
                "/linux-x86-64/**", "/linux-aarch64/**", "/linux-arm/**",
            )
        }
        jniLibs {
            // REQUIRED: the installer must extract libproot.so & co. to nativeLibraryDir as real files
            // (extractNativeLibs=true). Otherwise those "executables" stay inside the APK and cannot be exec'd.
            useLegacyPackaging = true
            // Keep the sha256-pinned proot binaries byte-identical (no strip pass).
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot_loader.so", "**/libproot_loader32.so",
                "**/libtalloc.so", "**/libandroid-shmem.so")
        }
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
    // Termux:X11's X server as an Android library (GPL-3.0). It comes from third_party/termux-x11, which
    // scripts/fetch-termux-x11.sh clones at a pinned commit. Upstream lorie's build.gradle finds THIS module
    // as its host through this exact `implementation` dependency, and bakes our applicationId into
    // its BuildConfig.APPLICATION_ID (the package its ACTION_START broadcast is sent to).
    implementation(project(":lorie"))

    // lorie keeps its deps as `implementation`, so we must declare what our code compiles against:
    // DesktopActivity extends com.termux.x11.MainActivity (an AppCompatActivity), and Prefs extends
    // androidx.preference.PreferenceDataStore.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.preference:preference:1.2.1")
    // FileProvider for "Share logs" (LogShare.kt). appcompat already pulls it in; declared because our code uses it.
    implementation("androidx.core:core:1.16.0")

    // The genie link, phase B (spec 01 §2): iroh endpoint with ALPN "linuxdesk/1". The same artifact Storage
    // Genie ships; the genie side uses pip iroh==1.1.0. Brings JNA + kotlinx-coroutines at runtime scope.
    implementation("computer.iroh:iroh-android:1.1.0")
    // IrohLink runs iroh's suspend calls with runBlocking(Dispatchers.IO), as Storage Genie does. The stdlib-only
    // bridge we used before never got Endpoint.bind's result on Android (bind timed out after 30 s).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM (returns defaults); FrameTest/RouterTest need the real one.
    testImplementation("org.json:json:20231013")
}

// ---------------------------------------------------------------------------------------------
// Fail fast with a helpful message if the binary payload was not fetched (scripts/fetch-all.sh).
// ---------------------------------------------------------------------------------------------
val requiredPayload: List<String> =
    listOf("arm64-v8a", "x86_64").flatMap { abi ->
        listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so")
            .map { "src/main/jniLibs/$abi/$it" }
    } + listOf(
        "src/main/assets/rootfs/alpine-aarch64.rootfs.bin",
        "src/main/assets/rootfs/alpine-x86_64.rootfs.bin",
        "src/main/assets/rootfs/VERSION",
    )

val verifyNativePayload by tasks.registering {
    group = "verification"
    description = "Checks that scripts/fetch-all.sh has populated jniLibs/ and assets/rootfs/."
    val files = requiredPayload.map { layout.projectDirectory.file(it).asFile }
    doLast {
        val missing = files.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "LinuxDesk binary payload missing. Run ./scripts/fetch-all.sh from the project root.\n" +
                    missing.joinToString("\n") { "  - $it" }
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyNativePayload)
}
