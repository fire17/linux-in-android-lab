# PhoneInPhone — Android inside Android (Version B)

`com.fire17.phoneinphone` · Kotlin · AGP 8.13.2 · compileSdk 36 · targetSdk 36 · minSdk 26

> **Tami:** "אנדרואיד הוא בעצמו סוג של לינוקס. אז אם אנחנו יכולים להריץ לינוקס בתוך אפליקציה, אז אנחנו יכולים להריץ גם אנדרואיד בתוך אפליקציה."
>
> This app treats that sentence as a hypothesis to *test*, not to believe or dismiss. The app is a shell with a **swappable backend**:
>
> 1. **Stream**: a whole real Android drawn inside the app, with touch and keyboard. This is built to work end to end today.
> 2. **On-device**: an instrumented experiment that climbs from "Linux in the app" toward "Android in the app" one rung at a time, *on this phone*. It shows exactly which rung held and which broke.

Nothing in this folder has been compiled or run yet. The Mac's disk was full, so building was not allowed. Everything below says what I *expect*, and the "Verification status" section separates expectations from facts.

---

## Architecture

```
┌──────────────────────── PhoneInPhone (one APK) ───────────────────────┐
│ MainActivity (settings)  →  PhoneActivity (shell)                     │
│   GuestSurfaceView ─ touch (normalized, multi-pointer) ─┐             │
│   InputConnection ─ committed text (Hebrew ok) ─────────┤             │
│   nav bar ◀ ● ■ ⌨ ─ keycodes ───────────────────────────┤             │
│   stage ladder + log + HUD ◀── BackendListener ─────────┤             │
│                                                         ▼             │
│                 interface PhoneBackend  (backend/PhoneBackend.kt)      │
│        ┌──────────────────────────┴───────────────────────────┐        │
│  StreamBackend (stream/)                        OnDeviceBackend (ondevice/)
│   dadb (in-app ADB client)                       Track A: proot ladder A1…A6
│   push scrcpy-server 5.0.1                       Track B: AvfProbe (reflection,
│   app_process … tunnel_forward=true               runtime-gated)
│   localabstract:scrcpy_<scid>                                                 │
│     video → MediaCodec → Surface                                              │
│     control ← touch/keys/text                                                 │
└───────────────────────────────────────────────────────────────────────┘
           │ ADB over TCP (Tailscale/WireGuard only)
           ▼
  redroid container on an ARM Linux box  ·  Android Emulator on the Mac  ·  spare phone
```

| File | Role |
|---|---|
| `backend/PhoneBackend.kt` | The swap point. A backend gets a `Surface`, normalized touches, keys and text, and reports `Stage`s, logs and stats. |
| `backend/BackendFactory.kt` | Turns the saved settings into a backend. To add a third backend, add one `when` branch. |
| `stream/ScrcpyProtocol.kt` | A clean-room Kotlin encoder/decoder for the scrcpy v5.0.1 wire format, written against the Apache-2.0 sources. Unit-tested against upstream's golden byte vectors. |
| `stream/AdbLink.kt` | Uses dadb 2.0.0 in one of two modes: **DIRECT** (to adbd at `:5555`) or **ADB_SERVER** (to an adb server at `:5037`, e.g. the Mac). |
| `stream/StreamBackend.kt` | Connects, pushes the server, launches it, opens the video and control sockets, runs the decode loop, and handles input. |
| `stream/VideoDecoder.kt` | Hardware `MediaCodec` decodes straight into the `SurfaceView` (H.264, H.265 or AV1) with low-latency hints. |
| `ondevice/OnDeviceBackend.kt` | The ladder A1–A6 plus the AVF track. Every rung ends as ✓, ✗, – or ∅, with its evidence. |
| `ondevice/ProotRunner.kt` | Runs `libproot.so` from `nativeLibraryDir`, sets the loader and tmp environment, adds the libtalloc shim, and checks 16 KB ELF alignment. |
| `ondevice/TarExtractor.kt` | Extracts rootfs tarballs with symlinks, hardlinks, GNU long names and pax headers, and guards against path traversal. |
| `ondevice/AvfProbe.kt` | Reflects into AVF and stops at the first failing gate, using the scion-avf gate names. |

### Why targetSdk 36 and not 28

The brief allowed either one, and the research (req2 winner, score 8.5) picked **binaries in jniLibs**:
- `proot`, its loader, `libtalloc` and `libandroid-shmem` ship as `lib*.so` files under `jniLibs/<abi>/`.
- `useLegacyPackaging = true` makes the installer extract them into `nativeLibraryDir`. That location is `apk_data_file`, which an untrusted app may exec at any targetSdk.
- proot never execve's guest binaries. Its loader mmaps them `PROT_EXEC`, which sepolicy still allows for `app_data_file`.
- LinuxOnAndroid uses the same layout and is live on Play at targetSdk 36.

So targetSdk 28 (the old Termux trick) gains nothing and loses modern APIs. The **Stream** backend never execs anything at all.

---

## What I expect to work on first run vs. what is unproven

### Expected to work (high confidence, still not run on a phone)

| # | Thing | Why I believe it |
|---|---|---|
| 1 | CI builds the APK | Versions were pinned against live Maven/Google repos on 2026-11-19: AGP 8.13.2, Kotlin 2.2.21, Gradle 8.14.3, dadb 2.0.0, hiddenapibypass 6.1. I chose 6.1 because 6.2's AAR requires compileSdk 37. No AndroidX, so there are fewer moving parts. **Not compiled here (disk full)**, so a typo-level compile error is possible. CI will show it. |
| 2 | Fetch scripts | **Actually run on the Mac** in /tmp (then deleted). All 6 Termux .debs downloaded, sha256-verified and laid out into `jniLibs/{arm64-v8a,x86_64}`. `scrcpy-server.jar` was sha256-verified too. The Alpine hashes come from the official yaml; that file was not downloaded. |
| 3 | proot binaries are 16 KB aligned | Checked: `PT_LOAD align 0x4000` on the aarch64 `proot` and `loader`. |
| 4 | **Stream → redroid / emulator**: video | The protocol follows scrcpy v5.0.1 field by field. The control encoding is unit-tested against upstream's byte vectors. Remaining risks are dadb on Android (it uses `java.util.Base64`, so API 26+) and decoder quirks on specific SoCs. |
| 5 | **Stream**: touch, multi-touch, Back/Home/Recents, ASCII typing | These are standard scrcpy `INJECT_TOUCH_EVENT`/`INJECT_KEYCODE`/`INJECT_TEXT` messages. |
| 6 | **Stream**: Hebrew typing | It goes through `SET_CLIPBOARD(paste=true)` because `INJECT_TEXT` can't type non-ASCII characters. I expect this to work on Android 10+ guests. |
| 7 | **OnDevice A1–A2**: "Linux inside the app" | proot plus Alpine 3.24.4 should run `uname -a; id` and show **✓**. That is the proven half of Tami's sentence. The research is clear on it, but it has not been run here. |

### Unproven: the app measures these, it does not claim them

| Rung | What it tries | My honest expectation |
|---|---|---|
| A3 | An Android `/system` tree is present. You import it; see below. | ✓ once imported |
| A4 | A *guest* bionic binary (`/system/bin/sh`, `toybox`) runs under proot | **Unknown.** It needs the guest's APEX runtime (linker64 + bionic) in the tarball. redroid usually ships flattened APEXes. If not, the probe retries with the host's `/apex` bound in and reports which variant worked. |
| A5 | The guest `servicemanager` gets binder | **Expected ✗.** The host already owns the binder context manager, and SELinux won't let an app take it. This needs a **userspace binder shim**: an LD_PRELOAD that turns `/dev/binder` into an in-app broker, the way twoyi's libvbinder does. ∅ NOT_IMPLEMENTED. |
| A6 | zygote → system_server → SurfaceFlinger → launcher | ∅ **NOT_IMPLEMENTED**. It is blocked on A5, plus a gralloc/HWC that draws into our Surface (AOSP goldfish-opengl encoders), a property service, and the phantom-process limit of about 32 processes. |
| B | AVF hardware VM | The gates run in order: feature, then permissions, then hidden API, then capabilities, then guest files, then config, then run, then console output. On a **Pixel 7 or later after `scripts/avf-grant.sh`**, getting as far as "Linux version … seen" is plausible. That would mean *a kernel booted in a VM inside this app*. On Android 17 the hidden-API path may already be blocked (Podroid #125). **Booting an ANDROID guest under AVF is ∅.** No third-party app has a display device there, and scion's ashmem framebuffer hook is AGPL-3.0, so I did not copy it. |

So what does the experiment show? "Linux in an app" has been shown many times. "Android in an app" breaks first at **binder (A5)** and then at **graphics (A6)**. Those are exactly the two parts closed-source VMOS/twoyi reimplemented in userspace. The ladder shows you *which* rung your phone reaches. My own read is that the thesis isn't wrong, it is incomplete: Android is Linux *plus* binder *plus* a graphics stack, and each needs a userspace replacement. Treat that as "not yet shown", not "impossible".

---

## How to test on a phone

### 0. Get the APK
CI (`.github/workflows/build.yml`) does the following:
- installs JDK 21 and the Android SDK (platform 36, build-tools 36.0.0)
- runs `scripts/fetch-all.sh`
- runs the unit tests
- runs `assembleDebug`
- uploads **PhoneInPhone-debug-apk**

If this folder is not the repo root, copy the workflow into the root `.github/workflows/` and set `PROJECT_DIR`. Then install it with `adb install -r app-debug.apk`.

### 1. Stream from a redroid server (recommended)
On an **arm64 Linux** box (a cloud ARM VM is fine), run `scripts/redroid-host.sh`. That runs `redroid/redroid:16.0.0_64only-latest`, bound to 127.0.0.1, and publishes it **only** on your tailnet.

Then, in the app:
1. Choose **Stream** and **adbd direct**.
2. Set the host to the box's Tailscale IP and the port to 5555.
3. Tap **Start**.

The stage list should show ✓ for asset, ADB, push, server and video. The stage list then hides and the redroid home screen appears. Use **⌨** for the keyboard, and **log** for raw server output.

### 2. Stream from the Android Emulator on the Mac
1. Start an arm64 emulator on the Mac.
2. Run `scripts/mac-emulator-adb-server.sh` (`adb -a nodaemon server`). Do this **only** on a tailnet or firewalled LAN.
3. In the app, choose **adb server**, set the host to the Mac's IP and the port to 5037, and set the serial to `emulator-5554`.

### 3. Stream a spare phone
1. Plug it into a computer once and run `adb tcpip 5555`.
2. In the app, choose **adbd direct** and enter the spare phone's IP and port 5555.
3. Tap **Allow** on the spare phone's RSA prompt.

Android 11+ *wireless-debugging pairing* (TLS) is **not implemented**, because dadb doesn't document it.

### 4. The on-device experiment
1. Choose **On-device** and tap **Start**. You should get A1 ✓ and A2 ✓ (Alpine), with A3 ✗ ("no system/build.prop").
2. Build an Android userland on a machine with Docker and about 3 GB free: `scripts/make-android-userland.sh`. It docker-exports redroid's `/system /vendor /apex …` without ever running it.
3. Copy the `.tar.gz` to the phone, then tap **Import Android userland** and run again. You get A3, A4 and A5 results.
4. AVF track, on a Pixel 7 or later:
   - Run `scripts/avf-grant.sh`. It does 2× `pm grant` and adds the 2 hidden-API prefixes.
   - Run `scripts/avf-push-guest.sh`. It pushes the sha256-pinned Debian trixie arm64 kernel and initrd via `run-as`.
   - Start the run. Afterwards, `scripts/avf-grant.sh --revert`. The exemption is a **global** setting.

Every run ends with a summary block in the log. Copy it into your notes: that is the experiment's result.

---

## Pinned external binaries (fetched at CI time, never committed)

| What | Version | sha256 | Source |
|---|---|---|---|
| scrcpy-server | 5.0.1 | `764eb6f7…bc536` | GitHub release asset digest |
| proot (aarch64/x86_64 .deb) | 5.1.107.102 | `10a394b5…e030` / `24970642…7758` | Termux stable Packages index; aarch64 re-hashed locally |
| libtalloc | 2.5.0 | `556591f4…5a7` / `b8c6d95f…0530` | same |
| libandroid-shmem | 0.7 | `0da3a24d…7ff6` / `ffa9e4c8…7797` | same |
| Alpine minirootfs | 3.24.4 | `4b14c18f…8de` / `cdaa38cb…3012` | alpinelinux latest-releases.yaml |
| Debian trixie netboot `linux`/`initrd.gz` (AVF guest, run by hand) | current | `9c37ace5…db67` / `f8f48fe3…9451` | deb.debian.org SHA256SUMS. "current" moves on point releases, so update the pins if this fails. |
| redroid 16 64only (Android userland, **not** in the APK) | latest | arm64 manifest `sha256:e194edc9…0b66`, layer ≈ 734 MB | Docker Hub. Not pinned in the script, because `-latest` moves. **TODO:** pin by digest once you choose one. |

**TODO: no sha256 exists for the Android userland tarball**, because you build it yourself. `fetch()` refuses any `TODO` hash on purpose.

## Licenses

- **App code:** yours. No GPL/AGPL code is copied into it.
- **scrcpy:** Apache-2.0. The protocol was re-implemented and the server jar is bundled.
- **dadb:** Apache-2.0.
- **HiddenApiBypass:** Apache-2.0.
- **proot:** GPL-2.0, shipped as a separate executable. Source: github.com/termux/proot.
- **talloc** and **libandroid-shmem:** Termux-packaged. Sources are in github.com/termux/termux-packages.
- **Alpine:** various open-source licenses.
- **Before any public release:** add a GPL source offer for proot and talloc.
- **scion-avf (AGPL-3.0)** was read for gate names and adb steps only. No code was taken.

## Risks (from the research, still true)

- **Play Integrity:** no guest passes it. Banking apps and Wallet won't work in redroid, the emulator or any container.
- **Never sign real accounts into a guest.** Never expose ports 5555 or 5037 to the internet; use Tailscale or WireGuard.
- **GApps:** don't install them inside redroid. That is a licensing gray zone.
- **Play policy:** this is a sideload or internal-testing app. The on-device ladder loads native code from a user-imported tarball.

## Verification status (honest)

- ✅ **Verified by me today:**
  - every pinned URL and hash above
  - the full proot fetch-and-layout script (run, then cleaned up)
  - 16 KB ELF alignment of proot and its loader
  - proot's real DT_NEEDED (`libtalloc.so.2`, `libandroid-shmem.so`, `libc.so`) and RUNPATH (`/data/data/com.termux/files/usr/lib`), which is why the shim and `patchelf` are there
  - the dadb 2.0.0 API (read from its sources jar)
  - the scrcpy 5.0.1 wire format (read from its sources)
- ❌ **Not verified:**
  - Gradle compile and the unit tests (no build allowed on this disk)
  - any run on a phone or emulator
  - dadb's behavior on the Android runtime
  - every OnDevice rung beyond what the research already established

The first CI run and the first phone run are the real tests.

### Build-readiness review (2026-11-19, static only: still not compiled)
- Re-verified live:
  - all 9 pinned CI downloads (sha256 streamed from the real URLs; every one matches)
  - the Maven coordinates (AGP 8.13.2, KGP 2.2.21, dadb 2.0.0 + okio 2.10.0, hiddenapibypass 6.1 with `minCompileSdk=1`, junit 4.13.2)
  - every Action major tag (`checkout@v7`, `setup-java@v6`, `setup-android@v4`, `setup-gradle@v6`, `cache@v6`, `upload-artifact@v7`)
  - the scrcpy 5.0.1 server option names, control/device message ids, and touch golden vector
- Fixed:
  - **Compile safety:**
    - `catch (_: …)` became `catch (ignored: …)` in 17 places. Underscore catch parameters are not guaranteed on Kotlin 2.2.21.
    - `Object()` lock became `Any()`.
    - The `getSystemService(InputMethodManager)` result is now null-safe. It is `@Nullable` in the SDK stubs.
  - **First-run:**
    - `StreamBackend.stop()` ran dadb's close (a socket write) on the main thread from `onDestroy`. That throws `NetworkOnMainThreadException` and leaks the connection and the guest server. Teardown now happens on a background thread.
    - targetSdk 36 forces edge-to-edge on Android 15+, so the nav row and the IME covered the UI. New `ui/Insets.kt` pads both activities by system-bar, cutout and IME insets on API 35+.
  - **CI:** `setup-gradle@v6` defaults to a proprietary "enhanced" cache that is only a free preview for private repos. It now uses `cache-provider: basic`.
- No foreground service or `foregroundServiceType` is needed: the app declares no `<service>`.
