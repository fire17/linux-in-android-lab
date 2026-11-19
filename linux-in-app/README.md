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

Status (2026-11-19): **CI builds the APK. On the x86_64 emulator proot starts and loads Alpine, but
every fork failed** (`/bin/sh: can't fork: Function not implemented`, run 44508979139). The cause is
known and the pinned proot is fixed (see [x86_64 fork / ENOSYS](#x86_64-fork--enosys-root-caused-2026-11-19)),
but **the fix has not run yet.** It has not been tested on a real phone.

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
| `scripts/fetch-proot.sh` | Downloads Termux's proot 5.1.107.102, talloc and shmem `.deb` files (2 ABIs), sha256-pinned. It unpacks 10 jniLibs files, applies the talloc soname patch, and checks every output hash. |
| `scripts/ci-arm64-proot.sh` | The arm64 smoke test the `arm64-proot` CI job runs inside `termux/termux-docker:aarch64`. |
| `scripts/fetch-rootfs.sh` | Downloads Alpine 3.24.4 minirootfs aarch64 + x86_64, **sha256-pinned** from Alpine's release YAML. |
| `scripts/fetch-all.sh` | Runs both. `preBuild` fails with a clear message if the payload is missing. |
| `.github/workflows/build.yml` | JDK 21 + SDK 36 + Gradle 8.14.3 → fetch → `assembleDebug` → upload `LinuxInApp-debug-apk`. Two best-effort jobs follow. `emulator-selftest` runs the per-step self-test on an x86_64 emulator and reports each step plus the exact failure line. `arm64-proot` runs the arm64 binaries in termux-docker on `ubuntu-24.04-arm`. |

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
| `libproot.so`, `libproot_loader.so`, `libproot_loader32.so`, `libtalloc.so`, `libandroid-shmem.so` × {arm64-v8a, x86_64} | **Since run 4:** the official Termux packages `proot 5.1.107.102` (= `termux/proot` tag `v5.1.107.102`, `52d65a4d…`), `libtalloc 2.5.0`, `libandroid-shmem 0.7` from `packages-cf.termux.dev/apt/termux-main`. **Before (runs 1–3):** `devwithzachary/LinuxOnAndroid@d1e0facd2f5b…`, built from `termux/proot@7266fb3e85…`, which has no x86_64 fork fix. Revert with `PROOT_SOURCE=linuxonandroid`. | All 6 `.deb` files were downloaded to /tmp, sha256-pinned, unpacked and ELF-parsed, then deleted. The 10 output files are pinned too. The script was run end to end in a /tmp copy and all 10 output hashes matched, both fresh and cached. The 64-bit PT_LOADs have `Align 0x4000`. `loader32` has `0x1000`, which only matters for 32-bit guests, and we ship none. `proot` is PIE, interp `/system/bin/linker64`. Its `DT_NEEDED libtalloc.so.2` and talloc's `DT_SONAME` are byte-patched to `libtalloc.so`, because APKs only carry `lib*.so`. Termux's `RUNPATH` and loader/tmp paths are compiled in, but `LD_LIBRARY_PATH`, `PROOT_LOADER`, `PROOT_LOADER_32` and `PROOT_TMP_DIR` override them. **Risk:** the Termux pool keeps only the current version (`5.1.107.101` already returns 404), so a Termux bump will make the fetch fail loudly until the pins are updated. |
| `alpine-minirootfs-3.24.4-aarch64.tar.gz` (4,029,843 B) | dl-cdn.alpinelinux.org `v3.24/releases/aarch64` | sha256 `4b14c18f…98a8de` from `latest-releases.yaml`. HEAD request returns 200. |
| `alpine-minirootfs-3.24.4-x86_64.tar.gz` (3,704,088 B) | `…/x86_64` | sha256 `cdaa38cb…923012`. HEAD request returns 200. |
| `com.termux.termux-app:terminal-view:0.118.0` | JitPack (pom resolves; `terminal-emulator:0.118.0` is transitive) | The API used here (`TerminalSession` 6-arg ctor, `TerminalSessionClient`, `TerminalViewClient`, `attachSession`, `setTextSize`, `mEmulator.paste`) was read from tag `v0.118.0` source. |
| Gradle 8.14.3 | services.gradle.org | `distributionSha256Sum` pinned. |

The old `PROOT_FROM_SOURCE=1` path (rebuilding LinuxOnAndroid's vendored, pre-fix proot) was removed.
It would have rebuilt the same fork bug. A from-source build of `termux/proot` ≥ `v5.1.107.98` with
the NDK is the right long-term replacement, and it is not written yet.

**Licensing:** proot is GPL-2.0-or-later, talloc is LGPL-3.0+, and libandroid-shmem is BSD. They are
shipped and **exec'd as separate programs**. Their corresponding source is `termux/proot` tag `v5.1.107.102` plus termux-packages `packages/{proot,libtalloc,libandroid-shmem}/build.sh` (see `scripts/fetch-proot.sh`).
terminal-view and terminal-emulator are Apache-2.0. The app's own Kotlin code is Tami's and is not
GPL-linked. Before any public release, add a "Source / licenses" screen that links the pinned proot source.

---

## Verification status (honest, 2026-11-19)

**PROVEN** means it was observed in a real CI log. **Expected** means it is argued from source and has not run yet.
The Mac disk is full, so nothing was run locally. There was no Gradle, emulator or phone here.

| Item | Status | Evidence |
|---|---|---|
| Gradle config resolves, Kotlin compiles, debug APK builds | **PROVEN** (CI) | Run 44508979139, job `apk`: `assembleDebug` succeeded and the artifact was uploaded. |
| Fetch scripts download and verify | **PROVEN** (CI) | `[fetch-proot] ok …` and `[fetch-rootfs] ok …` in the same run. The new Termux-based `fetch-proot.sh` was also run end to end on the Mac in /tmp, and all 10 output hashes matched. |
| APK installs; `lib/x86_64/libproot.so` etc. are extracted to nativeLibraryDir and executable | **PROVEN** on x86_64 emulator API 34 | Logcat: `libproot.so: 253488 bytes, canExecute=true`, and the same for the loader, talloc and shmem. |
| Rootfs extraction | **PROVEN** on x86_64 | `rootfs installed in 240 ms: Stats(files=84, dirs=96, symlinks=335, …)`, `bin/busybox exists: true`. |
| proot runs in the `untrusted_app` domain and its loader maps guest ELFs from app data | **PROVEN** on x86_64 | SELinux audit: `granted { execute } … name="busybox"` and `… name="ld-musl-x86_64.so.1"`, `tcontext=app_data_file`, under `libproot.so`. So the jniLibs + loader design works at targetSdk 36. |
| Guest `/bin/sh` starts | **PROVEN** on x86_64 | It printed its own error message. |
| Run-4 changes compile and run: per-step self-test (`ProotLauncher.runSelfTestSteps`), the new CI report step, the `arm64-proot` job, and the Termux-based binaries | **Not yet** | They were written 2026-11-19 after run 3. Gradle was not run locally, so the first proof is the next CI run. |
| Termux binaries' compiled-in Termux paths | Known caveat | `RUNPATH` and the default loader and tmp paths point at `/data/data/com.termux/…`, and the app overrides them with env vars. `libandroid-shmem` hardcodes `/data/data/com.termux/files/usr/tmp/ashv_key_%d`, so SysV shared memory (X11/VNC-style apps) may fail. Plain shell, `apk` and Python do not use it. |
| **Guest fork on x86_64** | **FAILED** (runs 1–3), fix **expected**, not run yet | `/bin/sh: can't fork: Function not implemented`, `exit=2`. Root cause and fix are in the next section. Proof will be run 4's `STEP fork-probe exit=0 :: fork-ok`. |
| Self-test `uname`/`os-release`/`id` on x86_64 | Expected to pass with proot 5.1.107.102 | It depends on fork. Upstream says the identical case passes: Alpine 3.24 on an Android 15 x86_64 emulator, in an app targeting API 35 (commit `9bd53079` message). |
| **arm64 (real phones): fork** | **Expected to work, even with the OLD binaries.** Not observed | aarch64 has no fork syscall. musl uses `clone(SIGCHLD, 0)`, and `clone` is allowed for all ABIs in Android's app seccomp policy. Sources are cited below. |
| arm64 binaries load and run Alpine aarch64 | Not yet observed | The new CI job `arm64-proot` runs the exact arm64-v8a jniLibs in `termux/termux-docker:aarch64` on `ubuntu-24.04-arm`. This proves the binaries, the talloc patch and aarch64 fork under proot, **but not the Android app sandbox** (stock kernel, no zygote seccomp, no SELinux). |
| Interactive terminal: typing, prompt, `ls`, `apk update`, `apk add python3` | Medium | The terminal-view wiring follows Termux's own usage. Network depends on the written `resolv.conf` (1.1.1.1, 8.8.8.8). Not run. |
| Shell survives app switch and rotation | Medium | The service owns the session and the activity re-attaches. Not tested. |
| Phantom process killer (Android 12+) kills guest processes beyond ~32 (`signal 9`) | Known limitation | The app cannot change this. The user runs `adb shell settings put global settings_enable_monitor_phantom_procs false` (12L+), or uses Developer options → "Disable child process restrictions". |
| No systemd, Docker, real root, mount or iptables | By design | That is proot. A real kernel needs the AVF or QEMU tiers from the research. |
| Play policy | Grey zone | Bundling the rootfs as an asset (done here) is the lower-risk route. `apk add` downloading native code at runtime is the grey part. Not for Play without a decision. |

### x86_64 fork / ENOSYS (root-caused 2026-11-19)

**Symptom.** CI run 44508979139, job `emulator-selftest` (x86_64, API 34 google_apis). proot started and the loader
mapped `busybox` and `ld-musl-x86_64.so.1` (SELinux `granted { execute }`). Then:
`/bin/sh: can't fork: Function not implemented`, `exit=2`.

**Hypothesis confirmed, link by link, from source:**

1. **musl calls the legacy `fork` syscall on x86_64, and `clone` on aarch64.**
   - musl `src/process/_Fork.c` L35–39: `#ifdef SYS_fork` / `ret = __syscall(SYS_fork);` / `#else` / `ret = __syscall(SYS_clone, SIGCHLD, 0);`
   - musl `arch/x86_64/bits/syscall.h.in` L57–59 define `__NR_clone 56`, `__NR_fork 57`, `__NR_vfork 58`.
   - musl `arch/aarch64/bits/syscall.h.in` has **no** `__NR_fork` or `__NR_vfork`. L221 is `#define __NR_clone 220`.
   - musl's x86_64 `vfork` (`src/process/x86_64/vfork.s` L5) is `mov $58,%eax`, which is the real vfork syscall.
2. **Android's app seccomp policy does not allow `fork` on 64-bit ABIs.**
   - bionic `libc/SECCOMP_ALLOWLIST_APP.TXT` L35: `fork()	lp32`. It applies to 32-bit only, and per L2 "the resultant policy is applied only to zygote spawned processes".
   - `SECCOMP_ALLOWLIST_COMMON.TXT` allows `clone(...) all` (L14) and `vfork() arm,x86,x86_64` (L25). Bionic itself never calls `fork` (no entry in `SYSCALLS.TXT`).
   - So on x86_64 a musl `fork` raises SIGSYS, and `vfork` is allowed.
3. **Our pinned proot answered that SIGSYS with ENOSYS.**
   - termux/proot `src/tracee/event.c` (master) L835–884: `case SIGSYS:`, then `if (siginfo.si_code == SYS_SECCOMP)`, then `handle_seccomp_event(tracee)`.
   - This path is **not** gated on `PROOT_NO_SECCOMP`. That variable only skips proot's own filter (L145–146). So `PROOT_NO_SECCOMP=1` neither caused this nor could fix it.
   - In `src/tracee/seccomp.c` at the pinned commit `7266fb3e`, `handle_seccomp_event_common` has no `PR_fork` case. Its `default:` (L630–632) does `set_result_after_seccomp(tracee, -ENOSYS);`.
4. **Upstream fixed exactly this:**
   - termux/proot commit `9bd53079` (2026-10-18), "seccomp: run fork(2) as clone(2) when the outer policy rejects it". It is followed by `e2d843ee`, "give the child of fork(2) run as clone(2) its registers back".
   - Its message says: *"Android's app seccomp policy only allows fork(2) on 32-bit ABIs: on x86_64 the syscall raises SIGSYS … Alpine's /bin/sh reports "can't fork: Function not implemented" … aarch64 has no fork(2), so libcs use clone(2) there and are not affected. … Tested on the Android 15 x86_64 emulator."* It closes termux/proot#237.
   - On master, `src/tracee/seccomp.c` L648–663 is `case PR_fork:` … `set_sysnum(tracee, PR_clone); poke_reg(tracee, SYSARG_1, SIGCHLD);` … `restart_syscall_after_seccomp(tracee);`.
   - First released in tag `v5.1.107.98`. There is no env var or flag for this; the only remedy is a newer binary.

**Fix applied.** `scripts/fetch-proot.sh` now pins Termux's `proot 5.1.107.102`, which is 42 commits past `e2d843ee`
and 0 behind. The details are in the provenance table above.

**Remaining x86_64 gap (by reading only).** No `case PR_vfork:` exists, even on master, but the policy allows
`vfork` on x86_64, so it never raises SIGSYS. busybox `sh` forks via `fork()`.

**arm64 (what phones run).** Points 1 and 2 say aarch64 musl never issues `fork`, and `clone` is allowed. So this bug
**cannot** occur on arm64, even with the old binaries. Hosted runners **cannot** run an arm64 Android emulator.
The `ubuntu-24.04-arm` image lists "Android SDK" under *"Tools that are not supported on Arm architecture"*
(actions/partner-runner-images, `arm-ubuntu-24-image.md`), and arm64 system images would need nested KVM there.
The `arm64-proot` CI job is the closest honest substitute: same binaries, same rootfs and same flags, but no Android sandbox.

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
   SELF-TEST PASS  (6/6 steps, … ms, abi=arm64-v8a)

   [ok] builtin-echo (exit=0, … ms) :: builtin-ok
   [ok] exec-uname-m (exit=0, … ms) :: aarch64
   [ok] fork-probe (exit=0, … ms) :: fork-ok
   [ok] uname-a (exit=0, … ms) :: Linux localhost 6.x.x-android… aarch64 Linux
   [ok] os-release (exit=0, … ms) :: NAME="Alpine Linux"
   [ok] id (exit=0, … ms) :: uid=0(root) gid=0(root) …
   ```
   Each step is its own proot run, so a failure names its step and its exact error line.
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
