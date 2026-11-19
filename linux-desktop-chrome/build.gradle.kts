// Root build file. Versions checked against Google Maven / Maven Central on 2026-11-19:
//   AGP 8.13.2 (latest 8.x), Kotlin Gradle plugin 2.4.21.
// KGP 2.4.21 matches the kotlin-stdlib 2.4.21 that upstream lorie depends on, so the Kotlin compiler
// can read that stdlib's metadata.
plugins {
    id("com.android.application") version "8.13.2" apply false
    // Upstream lorie and its stub apply this plugin by id; declaring it here puts it on the classpath.
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

// lorie compiles a whole X server (xserver, pixman, libX11, ...) through CMake. By default it builds
// every ABI. Limit it to the two ABIs this APK ships, which halves CI time. This changes nothing in the
// upstream sources.
subprojects {
    if (path == ":lorie") {
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "x86_64")
            }
        }
    }
}
