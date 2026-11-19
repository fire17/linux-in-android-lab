pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux terminal-view / terminal-emulator (Apache-2.0) are published only on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.termux.termux-app") }
        }
    }
}

rootProject.name = "LinuxInApp"
include(":app")
