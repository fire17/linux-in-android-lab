An empty placeholder for the Gradle project `:shell-loader`.

Upstream lorie declares `compileOnly project(':shell-loader:stub')`, so Gradle needs a parent
project named `:shell-loader`. Upstream's own `shell-loader/build.gradle` would read signing config
from upstream's `:lorie-app`, which is not part of this build. So `settings.gradle.kts` points
`:shell-loader` here, at a directory with no build file. `:shell-loader:stub` still comes from
upstream (`third_party/termux-x11/shell-loader/stub`).
