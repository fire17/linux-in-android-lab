# LinuxInApp — a real Alpine Linux userland inside an Android app (PRoot)

`com.fire17.linuxinapp`. No root needed. You open the app and get a real Alpine Linux shell
(`apk add python3 git …` works under fake root), with a **Self-test** button that runs
`uname -a; cat /etc/os-release; id` and shows what it printed.

> **בעברית בקצרה:** האפליקציה מריצה לינוקס Alpine אמיתי בתוך האפליקציה, בלי רוט, דרך PRoot.
> proot נארז כקובץ `libproot.so` בתוך `jniLibs` ורץ מתוך `nativeLibraryDir`, ככה עוקפים את חסימת
> ה-exec של Android 10+. ה-rootfs של Alpine (בערך 4MB) ארוז בתוך ה-APK ונפרס ל-`filesDir` בהפעלה
> הראשונה. מסך הטרמינל הוא ה-terminal-view של Termux (Apache-2.0). כפתור **בדיקה** מריץ
> `uname -a; cat /etc/os-release; id` ומציג את התוצאה. **שום דבר עוד לא נבדק על טלפון אמיתי.**
> הסטטוס המדויק נמצא למטה, בטבלה "מה אמור לעבוד / מה לא הוכח".

Status: **source complete, never built or run.** It was written on a Mac with a full disk, so
Gradle was not run. CI builds it (see `.github/workflows/build.yml`).

---

## Architecture

```
┌──────────────────────── APK (targetSdk 36, arm64-v8a + x86_64) ────────────────────────┐
│ lib/<abi>/libproot.so          ← proot executable, disguised as a .so                    │
│ lib/<abi>/libproot_loader.so   ← proot's static ELF loader stub (PROOT_LOADER)           │
│ lib/<abi>/libtalloc.so, libandroid-shmem.so  ← libproot.so's DT_NEEDED deps              │
│ assets/rootfs/alpine-<arch>.tar.gz  ← Alpine 3.24.4 minirootfs (data)                    │
│ classes.dex: MainActivity · LinuxService · ProotLauncher · RootfsInstaller · TarGz…      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
          │ install (useLegacyPackaging=true → extractNativeLibs=true)
          ▼
/data/app/…/lib/<abi>/libproot.so   (SELinux apk_data_file → executable by the app)
/data/data/com.fire17.linuxinapp/files/rootfs/   (Alpine tree, extracted on first launch)

MainActivity ──bind──► LinuxService (foreground, specialUse, partial wakelock)
     │                     └─ owns TerminalSession(argv = [libproot.so, --kill-on-exit,
     │                          --link2symlink, -0, -r rootfs, -b /dev -b /proc -b /sys …,
     │                          -w /root, /usr/bin/env -i HOME=/root … /bin/sh -l])
     └─ TerminalView (termux terminal-view 0.118.0) renders the pty; extra-keys row
        Self-test button → ProotLauncher.runOnce("uname -a; cat /etc/os-release; id")
```

| File | Role |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Plain `Activity` (no Compose/AppCompat). Starts and binds the service, extracts the rootfs on a worker thread, attaches the session to the `TerminalView`. Also: Self-test dialog (with Copy), Restart, keyboard, ESC/TAB/CTRL/arrow keys, pinch-to-zoom font. |
| `.../LinuxService.kt` | Foreground service that owns the `TerminalSession`, so the shell survives rotation and app switching. Holds a partial wakelock while the shell is alive. |
| `.../proot/ProotLauncher.kt` | Builds the proot argv and host env (`PROOT_LOADER`, `PROOT_LOADER_32`, `PROOT_TMP_DIR`, `PROOT_L2S_DIR`, `LD_LIBRARY_PATH`, `PROOT_NO_SECCOMP=1`). Has `runOnce()` (ProcessBuilder) for the self-test and `preflight()` diagnostics. |
| `.../rootfs/RootfsInstaller.kt` | Extracts the right-arch tarball into `files/rootfs.partial`, writes `resolv.conf`/`hosts`/profile and a version marker, then renames it into place atomically. Idempotent. |
| `.../rootfs/TarGzExtractor.kt` | Streaming tar.gz extractor with no dependencies. Handles ustar, GNU long names, PAX, symlinks (`Os.symlink`) and modes. Hard links become **copies** because `link(2)` is denied to apps. Blocks path traversal. |
| `app/src/androidTest/.../ProotSelfTest.kt` | Runs the same self-test headless (`connectedDebugAndroidTest`). |
| `scripts/fetch-proot.sh` | Downloads 10 proot `.so` files (2 ABIs), **sha256-pinned**. |
| `scripts/fetch-rootfs.sh` | Downloads Alpine 3.24.4 minirootfs aarch64 + x86_64, **sha256-pinned** from Alpine's release YAML. |
| `scripts/fetch-all.sh` | Runs both. `preBuild` fails with a clear message if the payload is missing. |
| `.github/workflows/build.yml` | JDK 21 + SDK 36 + Gradle 8.14.3 → fetch → `assembleDebug` → upload `LinuxInApp-debug-apk`. A second job, best-effort, runs the self-test on an x86_64 emulator. |

### Why targetSdk 36 (not 28)

The task allowed either targetSdk 28 (exec binaries out of app data, as F-Droid Termux does) or
targetSdk 36 with the binaries in jniLibs. **This project uses 36 + jniLibs**, following the research
(`notes/req2-linux-in-android-app.json`, ranked #1, score 8.5). The reasons:

1. At targetSdk ≥ 29, `execve()` of files in `filesDir` fails. In AOSP sepolicy, `untrusted_app_all`
   keeps `app_data_file:file execute` (mmap) but loses `execute_no_trans` (execve).
   `nativeLibraryDir` files are `apk_data_file`, which has `x_file_perms`, so `libproot.so` can be exec'd.
2. proot never execve's guest binaries. For each guest program it execs **its loader**
   (`PROOT_LOADER` → `libproot_loader.so`, also in nativeLibraryDir), and the loader **mmaps** the
   guest ELF from `files/rootfs` with `PROT_EXEC`. That needs only the `execute` permission,
   which apps still have.
3. A live precedent ships exactly this layout at targetSdk 36 on Play and F-Droid:
   [devwithzachary/LinuxOnAndroid](https://github.com/devwithzachary/LinuxOnAndroid). This project
   pins **its** proot binaries.
4. targetSdk 36 is the current Play floor, so the APK stays shippable later if Tami wants that. Nothing
   is published without his go-ahead.

`useLegacyPackaging = true` is what makes the installer write the `.so` files to disk. Without it
they stay compressed inside the APK and cannot be exec'd.

### Binary provenance (verified 2026-11-19)

| Payload | Source | Verified |
|---|---|---|
| `libproot.so`, `libproot_loader.so`, `libproot_loader32.so`, `libtalloc.so`, `libandroid-shmem.so` × {arm64-v8a, x86_64} | `devwithzachary/LinuxOnAndroid@d1e0facd2f5b…` `app/src/main/jniLibs/` (built from `termux/proot@7266fb3e85…` per its `external/PROVENANCE.md`) | All 10 downloaded in memory and sha256-hashed (hashes pinned in the script). ELF-parsed: every PT_LOAD has `Align 0x4000` (16 KB OK). `libproot.so` is PIE, interp `/system/bin/linker64`, NEEDED `libtalloc.so libandroid-shmem.so libdl.so libc.so`. The loader is static. |
| `alpine-minirootfs-3.24.4-aarch64.tar.gz` (4,029,843 B) | dl-cdn.alpinelinux.org `v3.24/releases/aarch64` | sha256 `4b14c18f…98a8de` from `latest-releases.yaml`. HEAD request returns 200. |
| `alpine-minirootfs-3.24.4-x86_64.tar.gz` (3,704,088 B) | `…/x86_64` | sha256 `cdaa38cb…923012`. HEAD request returns 200. |
| `com.termux.termux-app:terminal-view:0.118.0` | JitPack (pom resolves; `terminal-emulator:0.118.0` is transitive) | The API used here (`TerminalSession` 6-arg ctor, `TerminalSessionClient`, `TerminalViewClient`, `attachSession`, `setTextSize`, `mEmulator.paste`) was read from tag `v0.118.0` source. |
| Gradle 8.14.3 | services.gradle.org | `distributionSha256Sum` pinned. |

`PROOT_FROM_SOURCE=1 ./scripts/fetch-proot.sh` (needs `ANDROID_NDK_HOME`) rebuilds the
binaries from that same pinned commit instead of downloading them. This path is untested.

**Licensing:** proot is GPL-2.0-or-later, talloc is LGPL-3.0+, and libandroid-shmem is BSD. They are
shipped and **exec'd as separate programs**, and their corresponding source is the pinned commit above.
terminal-view and terminal-emulator are Apache-2.0. The app's own Kotlin code is Tami's and is not
GPL-linked. Before any public release, add a "Source / licenses" screen that links the pinned proot source.

---

## Expected on first run vs. unproven (honest)

**Nothing here has been compiled or run.** The Mac disk is full, so there was no Gradle, emulator or phone.

| Item | Confidence | Why |
|---|---|---|
| Gradle config resolves (AGP 8.13.2, Kotlin 2.2.21, Gradle 8.14.3, JitPack terminal-view) | High | Every version exists (checked against Google Maven, Maven Central, JitPack and Gradle on 2026-11-19). |
| Kotlin compiles | Medium-high | Written against the real v0.118.0 Java signatures, but never compiled. Most likely failures are small nullability or SAM mismatches against the Java interfaces; fix them in CI. |
| Fetch scripts download and verify | High | URLs and hashes were checked live. |
| APK installs; `lib/<abi>/libproot.so` is extracted to nativeLibraryDir | High | Standard `useLegacyPackaging` behaviour; same as LinuxOnAndroid. |
| First launch extracts the Alpine rootfs (~4 MB → ~9 MB) | Medium-high | The extractor is custom and untested. Alpine minirootfs is mostly symlinks into busybox, which `Os.symlink` handles. |
| **Self-test prints `Linux … aarch64`, `NAME="Alpine Linux"`, `uid=0(root)`** | **Medium** | This is the core claim. The argv and env mirror LinuxOnAndroid's working engine (same binaries, `PROOT_LOADER`, `-0`, `--link2symlink`, `PROOT_NO_SECCOMP=1`). Not observed on a device. |
| Interactive terminal: typing, prompt, `ls`, `apk update`, `apk add python3` | Medium | The terminal-view wiring follows Termux's own usage. Network depends on the written `resolv.conf` (1.1.1.1, 8.8.8.8). |
| Shell survives app switch and rotation | Medium | The service owns the session and the activity re-attaches. Not tested. |
| x86_64 emulator CI job passes | Unknown | `continue-on-error`. Emulator kernels sometimes behave differently under ptrace. It is evidence, not a gate. |
| Phantom process killer (Android 12+) kills guest processes beyond ~32 (`signal 9`) | Known limitation | The app cannot change this. The user runs `adb shell settings put global settings_enable_monitor_phantom_procs false` (12L+), or uses Developer options → "Disable child process restrictions". |
| No systemd, Docker, real root, mount or iptables | By design | That is proot. A real kernel needs the AVF or QEMU tiers from the research. |
| Play policy | Grey zone | Bundling the rootfs as an asset (done here) is the lower-risk route. `apk add` downloading native code at runtime is the grey part. Not for Play without a decision. |

---

### Build-readiness review (2026-11-19, static — still not compiled)

An adversarial reviewer re-checked everything live without running Gradle. It confirmed:
- all 10 proot pins and both Alpine pins by streaming and re-hashing them
- every Maven and JitPack coordinate (HTTP 200)
- that all six action major tags resolve
- the Gradle 8.14.3 sha256
- the termux v0.118.0 interface signatures (`TerminalViewClient` 23 methods and `TerminalSessionClient` 16; all overridden)
- that the prebuilt `libproot.so` reads `PROOT_LOADER` / `PROOT_LOADER_32` / `PROOT_L2S_DIR` / `PROOT_NO_SECCOMP`

Fixes applied:
- **Kotlin nullability.** `Context.getSystemService(Class)` is `@Nullable` in the SDK stubs, so those calls now use `?.` or the String overload. `Log.*` calls in the client callbacks now always get a non-null tag and message.
- **targetSdk 35+ edge-to-edge.** The root view now applies systemBars and IME insets, so the keyboard no longer covers the prompt or the extra-keys row.
- **Tar extractor.** The ustar `prefix` field is now read only for POSIX `ustar\0` headers. Alpine's minirootfs is **GNU** tar (`ustar  \0`), and in GNU tar that slot holds atime/ctime.
- **Rootfs deletes.** Re-installs now delete without following symlinks. Kotlin's `deleteRecursively` follows a guest's absolute symlinks out onto the host.
- **proot binds.** proot now also binds `nativeLibraryDir`, as LinuxOnAndroid does.
- **CI cache.** `setup-gradle@v6` is set to `cache-provider: basic`. The default "enhanced" cache is proprietary and only a free preview for private repos.

Known caveat: `libtermux.so` (inside JitPack `terminal-emulator:0.118.0`, and still in v0.119.0-beta.3) has **4 KB-aligned** LOAD segments. That does not matter on today's 4 KB-page phones. On a 16 KB-page device running Android 15 the pty library may fail to load (Android 16+ has a compat mode), and Play's 16 KB requirement would flag it. The fix is to build `terminal-emulator` ourselves with NDK r28+.

## How to build

```bash
./scripts/fetch-all.sh          # ~10 MB: proot .so × 2 ABIs + 2 Alpine tarballs, sha256-checked
gradle wrapper --gradle-version 8.14.3   # once, if you want ./gradlew (wrapper jar isn't committed)
./gradlew assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk (~12 MB)
```

Or push the folder as a repo (or copy the workflow to the repo root and set `APP_DIR`). CI uploads
the **`LinuxInApp-debug-apk`** artifact.

## How to test on a phone

1. Install: `adb install -r app-debug.apk`, or open the APK on the phone and allow "install unknown apps".
2. Open **LinuxInApp** and allow notifications (the shell runs in a foreground service).
3. First launch: the status bar shows "Installing Alpine rootfs…" for a few seconds, then "ready", and a `#` prompt appears.
4. Tap **Self-test**. Expected dialog:
   ```
   SELF-TEST PASS  (exit=0, … ms)
   Linux localhost 6.x.x-android… aarch64 Linux
   ---
   NAME="Alpine Linux"  … VERSION_ID=3.24.4 …
   ---
   uid=0(root) gid=0(root) …
   ```
   If it says FAIL, use **Copy** and send the text. The dialog includes preflight (do the libs exist, are they executable)
   and the exact argv. Also useful: `adb logcat -s LinuxInAppSelfTest:* LinuxInApp:* RootfsInstaller:*` and
   `adb logcat | grep avc` for SELinux denials.
5. In the terminal: `apk update && apk add python3 && python3 -c 'print("hi from alpine")'`.
6. Headless alternative: `./gradlew connectedDebugAndroidTest` with the phone attached runs `ProotSelfTest`.

## Next steps (not done)
- Add a licenses/source screen (GPL offer for proot).
- Optional Debian/Ubuntu rootfs, plus a toggle to compare `PROOT_NO_SECCOMP=0` speed.
- Bind shared storage (`/sdcard`) after a permission prompt.
- Turbo tier: AVF via Shizuku. Real-kernel tier: QEMU `libqemu.so` (see research ranks #2 and #3).
