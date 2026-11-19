# LinuxDesk: the app's screen IS a Linux X11 display running desktop Chromium

`com.fire17.linuxdesk` · **GPL-3.0-or-later** · Version A of the ideas-1911 "Linux inside an Android app" lab.

You open the app and tap **Start**. On the first run it downloads Chromium. After that the whole phone
screen is a real Linux X11 display with one desktop Chromium window on it. Touch goes to Chromium as real
multitouch (scroll, fling and pinch are handled by Chromium itself). Typing goes through Gboard, including
Hebrew. A small floating button toggles the keyboard, and long-pressing it opens a session menu. A
foreground service keeps everything alive while the app is in the background.

> **בעברית בקצרה:** המסך של האפליקציה הוא תצוגת X11 אמיתית של לינוקס. בפנים רץ שרת ה-X של
> Termux:X11 (lorie) כספרייה, באותו APK. עליו רץ Chromium שולחני של לינוקס, בתוך Alpine Linux דרך PRoot.
> הדגלים מחושבים מה-DisplayMetrics של הטלפון: `--force-device-scale-factor` לפי ה-density,
> `--touch-events=enabled`, ומצב Direct touch, כך שה-pinch והגלילה הם של Chromium עצמו. כפתור צף
> מקפיץ ומסתיר את המקלדת, ותוסף קטן ל-Chromium מקפיץ אותה לבד כשנוגעים בשדה טקסט. שירות foreground
> שומר על הסשן בחיים. **שום דבר עוד לא נבנה ולא רץ על טלפון.** הבנייה נעשית ב-CI. הטבלה
> "מה אמור לעבוד / מה לא הוכח" נמצאת למטה, והיא כנה.

Status: **source complete, never compiled, never run.** It was written on a Mac with a full disk, so no
Gradle, no NDK and no device were used. CI (`.github/workflows/build.yml`) does the first real build.

---

## Architecture

```
┌───────────── APK  com.fire17.linuxdesk  (targetSdk 36, arm64-v8a + x86_64) ─────────────┐
│ lib/<abi>/libXlorie.so        ← Termux:X11 "lorie" X server, built from source by CI      │
│ lib/<abi>/libproot.so, libproot_loader*.so, libtalloc.so, libandroid-shmem.so  (pinned)   │
│ assets/rootfs/alpine-<arch>.rootfs.bin ← Alpine 3.24.4 minirootfs .tar.gz (~4 MB, sha256)  │
│ assets/linuxdesk/  setup-packages.sh · start-browser.sh · fit-window.sh · focus-bridge/  │
└────────────────────────────────────────────────────────────────────────────────────────────┘

 main process                                              ":x11" process
 ─────────────────────────────────────────────            ─────────────────────────────────
 LauncherActivity ──Start──► SessionService (FGS) ──bind──► X11ServerService
                                │                            └ LinuxDeskX11Entry → new CmdEntryPoint([":0","-nolock","-dpi",N])
                                │                                 = lorie X server (dix_main thread)
                                │                                   socket: <rootfs>/tmp/.X11-unix/X0
                                │                                   ACTION_START broadcast (binder) ─┐
 DesktopActivity  ◄─────────────┼── LorieApp$Receiver ◄─────────────────────────────────────────────┘
 (extends lorie MainActivity)   │     hands binder → LorieView gets X connection fd, gives Surface
 fullscreen LorieView/Surface   │
 = THE LINUX DISPLAY            ├─ proot -r rootfs  /opt/linuxdesk/setup-packages.sh   (first run: apk add chromium …)
 + floating ⌨ button            └─ proot -r rootfs  /opt/linuxdesk/start-browser.sh  <flags>
 + gesture-exclusion edges            └ DISPLAY=:0 chromium-browser --no-sandbox --touch-events=enabled
        ▲                                  --force-device-scale-factor=<density> --ozone-platform=x11 …
        │ showSoftInput                     + fit-window.sh  (xdotool: keep the main window = X screen size)
   FocusBridge (127.0.0.1:<rnd>, token) ◄── focus-bridge extension (focusin/focusout on text fields)
```

| File | Role |
|---|---|
| `settings.gradle.kts` | Includes `:app`, plus `:lorie` and `:shell-loader:stub` from `third_party/termux-x11` (cloned by CI). |
| `app/src/main/java/com/termux/x11/LinuxDeskX11Entry.java` | The **embedding glue**. It must live in package `com.termux.x11`, because `CmdEntryPoint`'s constructor is package-private. It does what upstream `CmdEntryPoint.main()` does (load libXlorie, set `ctx`, construct, which calls the native `start()`), but in our own service process instead of `app_process`. |
| `.../x11/X11ServerService.kt` | Hosts the X server in process `:x11`. Sets `TMPDIR=<rootfs>/tmp` (so the guest's `/tmp/.X11-unix/X0` is the socket) and `XKB_CONFIG_ROOT=<rootfs>/usr/share/X11/xkb`. Kills its own process on unbind, because lorie's native threads cannot be stopped. |
| `.../LinuxDeskApp.kt` | `extends LorieApp`. Before lorie reads its prefs, it writes **touchMode=3 (Direct touch)**, fullscreen, hideCutout, native resolution and preferScancodes=false (Unicode commit, so Hebrew works). It seeds once: never sleep, clipboard on, Back = toggle keyboard. |
| `.../DesktopActivity.kt` | `extends com.termux.x11.MainActivity`, so upstream's input handling is unchanged. Adds the floating keyboard button (long-press opens the menu: restart browser, X settings, launcher, stop), the focus-bridge keyboard requests, gesture-exclusion edge strips, a status pill, and reports the view size and connection state. |
| `.../SessionService.kt` | Foreground service (`specialUse`) plus a partial wake lock. Runs the pipeline: rootfs → packages → X → display → browser. Its notification has *Restart browser* and *Stop*. |
| `.../linux/ProotRuntime.kt`, `RootfsInstaller.kt`, `TarGzExtractor.kt` | The proot route from the sibling project **linux-in-app**, with the extractor copied unchanged. proot binaries now come from the official Termux `proot 5.1.107.102` .debs (see the `scripts/fetch-proot.sh` header: it has the fork→clone fix that the older LinuxOnAndroid build lacks). Changes: `/dev/shm` bind, optional `--sysvipc`, seccomp acceleration on by default (toggle in settings), and deletes that never follow guest symlinks. |
| `.../bridge/ChromiumFlags.kt` (+ unit test) | Computes the Chromium command line from `DisplayMetrics` (scale = density × user %, window size in DIPs, `--accept-lang` from Android locales). |
| `.../bridge/FocusBridge.kt` + `assets/linuxdesk/focus-bridge/` | **Keyboard auto-show** on X11 (the research's proposed workaround). A MV3 extension reports focusin and focusout on editable elements to a loopback HTTP endpoint (random port, per-session token), and the Activity calls `setKeyboardVisible`. |
| `assets/linuxdesk/setup-packages.sh` | Runs in the guest: `apk add xkeyboard-config font-dejavu font-noto-hebrew font-noto-emoji xdotool xwininfo xprop chromium`. |
| `assets/linuxdesk/start-browser.sh`, `fit-window.sh` | Waits for the X socket, then execs Chromium. Keeps the main window at 0,0 and screen size, skipping menus and dialogs; this also handles rotation. |
| `scripts/fetch-termux-x11.sh` | Clones termux/termux-x11 at a **pinned commit** plus its 16 submodules. |
| `scripts/fetch-proot.sh`, `fetch-rootfs.sh`, `fetch-all.sh` | sha256-pinned proot `.so` files (2 ABIs) and the Alpine minirootfs (2 arches). |
| `.github/workflows/build.yml` | JDK 21, SDK 36 + 34, NDK 29.0.14206865, CMake 3.22.1, bison → fetch → unit tests → `assembleDebug` → artifact `LinuxDesk-debug-apk`. A best-effort x86_64 emulator smoke job uploads a screenshot. |

### Decisions and why

**1. Display: embed lorie from source, pinned (`termux/termux-x11@a00942e8`, 2026-11-16).**
Since upstream commit `37ad5a7` (2026-07-24), `lorie/` has been a pure `com.android.library`. It finds its
host app through the host's `implementation(project(":lorie"))`, and bakes that applicationId into
`BuildConfig.APPLICATION_ID`, which is where `CmdEntryPoint` sends its ACTION_START broadcast. The
upstream README section "Using with 3rd party apps" points at `shell-loader/Loader.java`. That loader is
for *a separate Termux process* starting the server through `app_process`. An app that embeds lorie has
no `app_process`, so `LinuxDeskX11Entry` replays `CmdEntryPoint.main()` inside our own `:x11` service
process. DroidDesk (GPL-3, ships one APK) runs the X server in a dedicated `:x11` service process the
same way, but from forked Java sources. **We keep upstream unmodified.** No VNC, no RDP: zero-copy
AHardwareBuffer rendering straight into our SurfaceView.

**2. Linux userspace: proot + Alpine + Alpine's `chromium` package, not a rebuilt Termux prefix.**
The research recommends a native Termux (bionic) prefix (faster, and zink GPU on Adreno). But Termux
packages are compiled for `/data/data/com.termux/files/usr`. Using them under another package name means
rebuilding *every* package, Chromium included, which is a multi-hour build per architecture, or the
fragile binary relocation DroidDesk does. That is too big for this pass, so **this project uses the
proot route from project 1 (linux-in-app)**, exactly as the brief allowed. Consequences:
- Chromium is Alpine's `chromium 156.0.8078.79-r0` (musl), not Termux's 157. It installs with one `apk add` on first run.
- It is slower than native: ptrace syscall interception, partly reduced by proot's seccomp acceleration.
- No GPU (`--disable-gpu`, software compositing), which is fine for normal browsing and weak for WebGL.
- Upgrade path (phase 4 of the research): swap `ProotRuntime` + `RootfsInstaller` for a bundled Termux
  bootstrap rebuilt with `TERMUX_APP__PACKAGE_NAME=com.fire17.linuxdesk`. The X side does not change.

**3. Why targetSdk 36 (not 28).** Nothing is `execve()`'d from app data: proot ships as `lib*.so` in
`jniLibs` and runs from `nativeLibraryDir` (an `apk_data_file`, so exec is allowed); proot's loader
*mmaps* guest ELFs, which apps may still do at API 29+; and the X server is a JNI library (`libXlorie.so`)
with xkbcomp compiled in, so no helper binary is exec'd. targetSdk 28 would only be needed for the native
Termux-prefix route, which we did not take. `useLegacyPackaging = true` is what puts the `.so` files on
disk.

**4. Touch: lorie Direct touch (touchMode 3).** lorie registers an XI2 `XIDirectTouch` device ("Lorie
touch", 20 points). Chromium's X11 touch factory only accepts *direct* touch devices, so Chromium itself
does scrolling, fling, pinch-zoom and long-press. lorie turns off its own pinch-zoomer in this mode.

**5. Keyboard.** lorie's IME commits Unicode keysyms (preferScancodes OFF), so Hebrew from Gboard needs no
XKB layout. Its limitations: no word suggestions (`TYPE_TEXT_FLAG_NO_SUGGESTIONS`), and composition is
emulated with backspaces. X11 has no "a text field took focus" signal, so we have three ways to show the
keyboard: the floating button, Android **Back** (lorie's `backButtonAction=toggle soft keyboard`), and the
**focus-bridge extension**, which shows it automatically for web-page fields only, not Chromium's own
address bar.

**6. License.** lorie is GPL-3.0, so LinuxDesk is GPL-3.0 (see `LICENSE`). proot (GPL-2.0+) and Alpine
run as separate programs. Keep the source public if the APK is ever shared.

---

## What is downloaded when

| When | What | Size | Pinned how |
|---|---|---|---|
| CI build | termux-x11 sources + 16 submodules | ~200 MB of source | git commit `a00942e81d949070e63fac268f37226838a13839` (submodules pinned by gitlinks) |
| CI build | Termux proot / libtalloc / libandroid-shmem .debs → 10 `.so` files | ~2 MB | sha256 of each .deb **and** of each final `.so` (`scripts/fetch-proot.sh`). Termux's pool keeps only the current version, so the script fails loudly when proot is bumped. |
| CI build | Alpine 3.24.4 minirootfs aarch64 + x86_64 | ~8 MB | sha256 from Alpine's `latest-releases.yaml` |
| **Phone, first Start** | `apk add chromium` + fonts + X tools from dl-cdn.alpinelinux.org v3.24 | **~150–250 MB** | Alpine's signed APKINDEX (apk-tools verifies the signatures). Not hash-pinned by us: the TODO below. |

Chromium is not in the APK, for two reasons: to keep the APK small (an estimated 20–40 MB), and because
Alpine's chromium together with its dependency tree is hundreds of MB. **TODO (unverified size):** an
optional "offline" variant could `apk fetch` the exact package set in CI and bundle it.

---

## What I expect on the first run vs. what is unproven (honest)

**Nothing has been compiled or run.** The Mac disk is full, so there was no Gradle, emulator or phone.

| Item | Confidence | Why |
|---|---|---|
| fetch scripts download and verify | High | URLs and commit checked live 2026-11-19. Re-hashed `arm64-v8a/libproot.so` and the aarch64 rootfs; both match the pins. |
| Gradle configures (`:lorie` from upstream under **our** AGP 8.13.2; upstream uses AGP 9.3.1) | **Medium** | I read `lorie/build.gradle` at the pin; everything it uses exists in AGP 8.x. The `hostProjects` lookup should find `:app`. **Most likely first CI failure.** If AGP 8 rejects something, bump to AGP 9.x and Gradle 9.x (the brief said 8.x, so I kept 8.x). |
| lorie's native build (CMake: xserver, pixman, xkbcomp …) on ubuntu-latest | Medium-high | This is upstream's own CI recipe (same NDK pin, `submodule --depth 1`). Needs bison and python3, which the workflow installs. About 15–30 min. |
| Kotlin and Java compile | Medium | Written against the real upstream signatures at the pin: `CmdEntryPoint(String[])` (package-private, hence the glue class's package), `CmdEntryPoint.ctx`, `MainActivity.getLorieView()`, `toggleKeyboardVisibility()`, `LorieView.setKeyboardVisible()`, `LorieApp`. Expect small nullability or SAM fixes. |
| Manifest merge (`tools:replace` application name, stripping lorie's launcher intent-filter) | Medium-high | Standard merger directives. |
| APK installs; first Start extracts Alpine; `apk add chromium` succeeds under proot | Medium | The proot engine and pins are the same as linux-in-app (also unproven on a device). `apk` under proot `-0 --link2symlink` is a well-trodden path (proot-distro and Andronix). Post-install trigger failures are tolerated; we verify the binary exists afterwards. |
| **The X server starts in our `:x11` process** (`LinuxDeskX11Entry`) | **Medium-low: the key unknown** | Upstream only starts it through `app_process`. Our path skips `createContext()` and passes the app Context instead, and relies on `JNI_OnLoad` registering the `CmdEntryPoint` natives. Both are true at the pin, and DroidDesk does the same in-app. If it fails, the log shows "X server process died". First thing to try: Settings → Legacy drawing. |
| LorieView connects and shows the X root window | Medium | It is upstream's own handshake (ACTION_START → `LorieApp$Receiver` → `connectToService`), with our package as `APPLICATION_ID`. |
| Chromium window appears full-screen | Medium | Socket path, `DISPLAY=:0`, `--no-sandbox`, `--disable-dev-shm-usage`, `--disable-gpu`. **`proot --sysvipc` is unverified for the pinned proot build.** If the browser log says "unknown option", set `USE_SYSVIPC=false` in `SessionService.kt`. |
| Touch: scroll, fling, pinch-zoom in Chromium | Medium (pinch **unverified anywhere**) | Mechanism verified in source (XIDirectTouch + Chromium's touch factory). No device report confirms pinch yet. Fallback: Chromium flag `--touch-devices=<xinput id>`. |
| Hebrew typing from Gboard | Medium (**untested end-to-end anywhere**) | Unicode keysym commit is in lorie's code. No suggestions, and possible flicker while composing. |
| Keyboard auto-show (focus-bridge) | Low-medium | My design, untested. Risks: Chromium 156 ignoring `--load-extension` despite `--disable-features=DisableLoadExtensionCommandLineSwitch`, and the service worker sleeping. The floating button and Back always work as fallbacks. |
| Rotation follows | Medium-low | lorie resizes the X screen through RandR, and `fit-window.sh` resizes Chromium every second. Not tested without a WM. |
| Survives app switch | Medium | FGS + wake lock + `BIND_IMPORTANT`. **Android 12+ phantom-process killer** (32 processes across all apps) will kill Chromium children unless you flip the developer toggle (the launcher has a button and the adb command). |
| Speed | Expect sluggish | proot + software rendering on a phone. Fine for reading and forms; not for WebGL or video. |
| Security | Known limitation | `--no-sandbox` is mandatory. Do not use it for banking or other sensitive logins. |
| x86_64 emulator smoke job | Unknown | `continue-on-error`. It shows how far the pipeline gets and uploads a screenshot. It is evidence, not a gate. |

---

## How to build

CI is the intended path (the Mac has no disk space):

```bash
./scripts/fetch-all.sh                   # termux-x11 @ pinned commit + submodules, proot, Alpine (~210 MB total)
gradle wrapper --gradle-version 8.14.3   # optional, if you want ./gradlew (the jar is not committed)
gradle :app:testDebugUnitTest assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 21, Android SDK platforms 36 + 34, NDK `29.0.14206865`, CMake 3.22.1, `bison`, `python3`.
When pushed as a repo, CI uploads the **`LinuxDesk-debug-apk`** artifact (if it lives in a monorepo subfolder, copy the
workflow to the repo root and set `APP_DIR`).

## How to test on a phone (arm64, Android 10+, ideally 14–16)

1. **One-time:** Settings → Developer options → **Disable child process restrictions** (Android 14+), or
   from a PC: `adb shell settings put global settings_enable_monitor_phantom_procs false`.
2. Install: `adb install -r app-debug.apk`, or open the APK on the phone. Allow notifications.
3. Open **LinuxDesk** → **Start**. First run: "Preparing Linux", then "downloading Chromium" (a few
   minutes on Wi-Fi). The app then switches to the black X display with a status pill, and after a few
   seconds Chromium fills the screen.
4. Go/no-go checklist (write down the results; the research needs exactly these):
   - one-finger scroll and fling · **pinch-zoom** · long-press context menu and text selection
   - tap Google's search box → does the keyboard pop up by itself? (focus-bridge) · type **Hebrew** with
     Gboard, including RTL and word replacement
   - the ⌨ button and Back toggle the keyboard · rotate the phone · home → come back after 1 min
5. If something fails: in the launcher, tap **Copy log** and send it. Also useful:
   `adb logcat -s LinuxDeskSession:* LinuxDeskX11:* LorieNative:* FocusBridge:* AndroidRuntime:E`.
   - black screen with a cursor → launcher → enable **Legacy drawing** → Stop → Start
   - Chromium dies after a while → the phantom-process toggle (step 1)
   - very slow → keep "Slow-safe PRoot mode" **off**; try **App window** mode (fewer UI processes)
6. Headless: `adb shell am start -n com.fire17.linuxdesk/.LauncherActivity --ez autostart true --es url https://example.com`

## Genie link (LinuxDesk × Storage Genie, linuxdesk/1)

A paired "genie" (your agent on a Mac or server) can drive this phone's LinuxDesk session. It can open pages,
read the page tree, click and type in Chromium, run shell commands as the guest user or guest root, use the
mouse and keyboard over X, take screenshots and frame streams, and use the Storage Genie file verbs (`fs.*`). The
protocol and lane spec live outside this repo (`notes/spec/01-protocol-and-lanes.md`, `02-closing.md`).

**How it is wired**
- Launcher → **Pair a genie** opens the Pair screen. It shows a 6-char code, **Share with my genie** (the line
  `LINUXDESK <node-id>:<CODE>`), **Forget my genie**, and the switches. Every switch is **OFF by default**.
- **Genie access OFF** (the default): the session runs exactly as before (`start-browser.sh` straight from
  `SessionService`).
- **Genie access ON**: on the next Start, `GenieRuntime` runs `setup-genie.sh` once (marker
  `/etc/linuxdesk/genie-v1`: python3, pillow, xwd, imagemagick, xdotool, and the guest user `desk`). It then
  starts `python3 /opt/linuxdesk/deskd/deskd.py` under proot on `127.0.0.1:8765`, with a fresh random key each
  session. deskd launches the **same** `start-browser.sh` and flags as Chromium's parent, with CDP over
  `--remote-debugging-pipe` (no debugging port). The app holds the genie connection over iroh (ALPN
  `linuxdesk/1`), checks the pairing, and relays the Linux verbs to deskd.
- **Developer: local link ON**: the same router also listens on `127.0.0.1:8766`, for
  `adb forward tcp:8766 tcp:8766` plus `genie-client pair --tcp 127.0.0.1:8766 --code ABC234`.
- While a genie can connect, the session notification has **Stop genie**. Deletes always need an **Approve** tap
  on the phone's review sheet; nothing else can trigger them.
- Storage Genie behaviour is the default: genie commands work while the app is on screen or within 2 minutes of
  it. All-files access and Genie shell may both be on. The agent's recommendations are switches that stay **OFF**
  until you turn them on: "Allow while the session runs", "Refuse while locked", and "All-files XOR shell".
- Honest caveat: `as:"root"` is proot's fake root inside Alpine, not Android root. The guest runs as the app's
  uid, so shell access means control of the app's own sandbox, and with All-files access it may also reach
  shared storage through `/proc`.

**Verification status (honest, 2026-11-19)**
| Item | Status |
|---|---|
| Kotlin wiring (manifest, launcher button, `SessionService` → `GenieRuntime`, `GenieWindow.install`) | Written and self-reviewed. **Not compiled** (no JDK or Android SDK on the Mac). The first CI build is the compile check. |
| iroh-android 1.1.0 in the APK | Not built yet. ABIs checked on Maven only. |
| deskd + genie-client | 32/32 tests pass **on the Mac**, including live Chrome over a real `--remote-debugging-pipe`. **Not run under proot or on Alpine Chromium.** |
| `setup-genie.sh` under apk / proot | Syntax-checked only. |
| Pairing, the window gate, fs verbs, the delete review sheet | Written. Unit tests are written but not run (no JDK). |
| On-device tests: cdp-pipe, xwd cost, unix socket, xbox calibration, proc-root | **Pending.** Nothing has run on a phone. |

## Next steps (not done)

- Bundle a Termux-prefix rebuild (`TERMUX_APP__PACKAGE_NAME=com.fire17.linuxdesk`) to replace proot: native speed, Chromium 157, zink GPU on Snapdragon.
- Phase 2 display: a Wayland compositor (tawc / Smithay) for real `text-input-v3`, meaning Gboard suggestions, native auto-show and fractional scale.
- A minimal WM fallback if Chromium does not follow `fit-window.sh` resizes.
- An in-app licenses and source screen (GPL offer for lorie and proot); an offline package bundle.
