// LinuxDesk — com.fire17.linuxdesk (GPL-3.0-or-later, because it links Termux:X11's lorie).
//
// Module graph:
//   :app                → this project's Kotlin app
//   :lorie              → termux/termux-x11 @ a00942e (the X server, as an Android LIBRARY module),
//                         cloned at CI time by scripts/fetch-termux-x11.sh into third_party/termux-x11
//   :shell-loader:stub  → upstream's compile-only stubs for hidden framework classes (lorie needs them)
//   :shell-loader       → empty placeholder (x11-shim/shell-loader) so upstream's real shell-loader
//                         build file, which needs upstream's :lorie-app, is never evaluated.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS (not FAIL_ON_PROJECT_REPOS): the upstream lorie build files are used unmodified.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LinuxDesk"

val termuxX11 = file("third_party/termux-x11")
if (!File(termuxX11, "lorie/build.gradle").isFile) {
    throw GradleException(
        "third_party/termux-x11 is missing.\n" +
            "Run ./scripts/fetch-termux-x11.sh (or ./scripts/fetch-all.sh) first; it clones termux/termux-x11\n" +
            "at the pinned commit together with its submodules (xserver, pixman, libx11, ...)."
    )
}

include(":app")

include(":lorie")
project(":lorie").projectDir = File(termuxX11, "lorie")

include(":shell-loader:stub")
project(":shell-loader").projectDir = file("x11-shim/shell-loader")
project(":shell-loader:stub").projectDir = File(termuxX11, "shell-loader/stub")
