#!/usr/bin/env bash
# LinuxDesk GENIE end-to-end check on an emulator (CI only, debug APK only). Run it from the app folder:
#   bash tools/ci/genie-e2e.sh app-debug.apk
# It switches "Genie access" + "Developer: local link" ON in the app's prefs (both are OFF by default),
# autostarts a session, pairs genie-client over `adb forward tcp:8766`, and drives
# pair/ping/sh/sh-root/open/tree/type/click/shot. Output: out/genie-e2e/*. Ends with one SUMMARY line.
# The pairing TOKEN is never printed or uploaded: genie-client keeps it in $LINUXDESK_HOME (0600), and
# every artifact is scrubbed for it before upload. The 6-char CODE is printed; it dies at pairing.
set -u
APK="${1:-app-debug.apk}"
PKG=com.fire17.linuxdesk
HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="$(cd "$HERE/../genie-client" && pwd)"
OUT="${OUT:-out/genie-e2e}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/genie-e2e.log"
LOGCAT="$OUT/logcat.txt"
: > "$LOG"
export LC_ALL=C.UTF-8 LANG=C.UTF-8 PYTHONIOENCODING=utf-8 PYTHONUTF8=1
export LINUXDESK_HOME="${RUNNER_TEMP:-/tmp}/linuxdesk-home"   # token store, outside the checkout
rm -rf "$LINUXDESK_HOME"
WAIT_UP_S="${WAIT_UP_S:-720}"         # rootfs + packages + setup-genie.sh + deskd (max ~12 min)
WAIT_BROWSER_S="${WAIT_BROWSER_S:-300}"

say() { echo "$(date +%H:%M:%S) $*" | tee -a "$LOG"; }
L() { python3 "$CLIENT_DIR/linuxdesk.py" --tcp 127.0.0.1:8766 --genie ci:genie-e2e --timeout 120 "$@"; }

declare -A RES
for s in pair ping sh sh-root open tree type click shot; do RES[$s]=FAIL; done
pass() { RES[$1]=PASS; say "STEP $1: PASS"; }
fail() { say "STEP $1: FAIL ${2:-}"; }
# run_cli NAME ARGS... : runs the CLI, appends stdout+stderr to the log, leaves output in $OUT/NAME.out
run_cli() {
  local name="$1"; shift
  say "\$ linuxdesk $*"
  L "$@" > "$OUT/$name.out" 2>&1
  local rc=$?
  cat "$OUT/$name.out" >> "$LOG"
  echo "[exit $rc]" >> "$LOG"
  return $rc
}

# ------------------------------------------------------------------------------------------------ install
say "adb install -r -g $APK"
adb install -r -g "$APK" 2>&1 | tee -a "$LOG"
adb shell settings put global settings_enable_monitor_phantom_procs false || true
adb shell am force-stop $PKG || true

# ------------------------------------------------------------------------------------------------ prefs
# ld.genie keys from Binding.kt (GenieSettings): "access" (Genie access) and "localLink" (Developer: local
# link). Written BEFORE the first start, while the app is stopped, so its prefs cache cannot overwrite them.
PREFS_XML="$OUT/ld.genie.xml"
cat > "$PREFS_XML" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="access" value="true" />
    <boolean name="localLink" value="true" />
</map>
XML
prefs_ok() { adb shell run-as $PKG cat shared_prefs/ld.genie.xml 2>/dev/null | grep -q 'name="localLink" value="true"'; }
say "writing shared_prefs/ld.genie.xml via run-as (debuggable APK)"
adb shell run-as $PKG mkdir -p shared_prefs 2>&1 | tee -a "$LOG"
# 1) stream the file through run-as's stdin
adb shell "run-as $PKG sh -c 'cat > shared_prefs/ld.genie.xml'" < "$PREFS_XML" 2>&1 | tee -a "$LOG"
if ! prefs_ok; then
  # 2) copy from /data/local/tmp (run-as can read a world-readable file there on most images)
  say "stdin write did not stick; trying run-as cp from /data/local/tmp"
  adb push "$PREFS_XML" /data/local/tmp/ld.genie.xml >/dev/null 2>&1
  adb shell chmod 644 /data/local/tmp/ld.genie.xml
  adb shell run-as $PKG cp /data/local/tmp/ld.genie.xml shared_prefs/ld.genie.xml 2>&1 | tee -a "$LOG"
fi
if ! prefs_ok; then
  # 3) google_apis emulator images allow `adb root`: write as root, then give it back to the app uid.
  say "run-as write failed; falling back to adb root (google_apis image)"
  adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
  D=/data/data/$PKG
  U=$(adb shell stat -c %u $D | tr -d '\r')
  adb push "$PREFS_XML" /data/local/tmp/ld.genie.xml >/dev/null 2>&1
  adb shell "mkdir -p $D/shared_prefs && cp /data/local/tmp/ld.genie.xml $D/shared_prefs/ && chown -R $U:$U $D/shared_prefs && chmod 771 $D/shared_prefs && chmod 660 $D/shared_prefs/ld.genie.xml && restorecon -R $D/shared_prefs" 2>&1 | tee -a "$LOG"
fi
adb shell rm -f /data/local/tmp/ld.genie.xml || true
if prefs_ok; then say "prefs: Genie access ON, local link ON"; else say "prefs: COULD NOT WRITE ld.genie.xml"; fi
adb shell run-as $PKG cat shared_prefs/ld.genie.xml 2>&1 | tee -a "$LOG"   # ld.genie holds no secrets

# ------------------------------------------------------------------------------------------------ start
adb logcat -c || true
adb logcat -v time -s LinuxDeskSession:* LinuxDeskX11:* LinuxDesk:* RootfsInstaller:* FocusBridge:* AndroidRuntime:E > "$LOGCAT" 2>&1 &
LOGCAT_PID=$!
say "autostart session"
adb shell am start -n $PKG/.LauncherActivity --ez autostart true --es url about:blank 2>&1 | tee -a "$LOG"

has() { grep -qE "$1" "$LOGCAT" 2>/dev/null; }
say "waiting up to ${WAIT_UP_S}s for the local link (8766) and deskd (8765)"
t0=$(date +%s); wire=0; deskd=0
while :; do
  has 'local link listening on 127\.0\.0\.1:8766' && wire=1
  has '\[deskd\].*listening on 127\.0\.0\.1:8765' && deskd=1
  [ $wire = 1 ] && [ $deskd = 1 ] && break
  if has 'setup-genie\.sh failed|Package setup failed|deskd failed to start|5 times in a minute|FATAL EXCEPTION'; then
    say "failure marker in logcat:"; grep -E 'setup-genie\.sh failed|Package setup failed|deskd failed|5 times|FATAL' "$LOGCAT" | tail -5 | tee -a "$LOG"
    break
  fi
  [ $(( $(date +%s) - t0 )) -ge "$WAIT_UP_S" ] && break
  sleep 10
done
say "after $(( $(date +%s) - t0 ))s: wire=$wire deskd=$deskd"
if [ $deskd = 1 ]; then
  say "waiting up to ${WAIT_BROWSER_S}s for the browser window"
  t1=$(date +%s)
  until has 'LINUXDESK_WINDOW_UP|\[RUNNING\]|browser not started'; do
    [ $(( $(date +%s) - t1 )) -ge "$WAIT_BROWSER_S" ] && break
    sleep 10
  done
  grep -E 'LINUXDESK_WINDOW_UP|browser not started' "$LOGCAT" | tail -2 | tee -a "$LOG"
fi

# ------------------------------------------------------------------------------------------------ pair
adb forward tcp:8766 tcp:8766 2>&1 | tee -a "$LOG"
# The app mints the code lazily (PairActivity or the first hello). A non-code hello ("probe", 5 chars)
# makes it mint one and costs no strike (BindingCore.authenticate); then read it from prefs ld.binding.
python3 - "$CLIENT_DIR" <<'PY' 2>&1 | tee -a "$LOG"
import sys; sys.path.insert(0, sys.argv[1])
import linuxdesk_client as lc
try:
    with lc.Conn.tcp("127.0.0.1", 8766, timeout=20) as c:
        c._hello("probe", "ci:genie-e2e")
    print("probe: unexpectedly accepted")
except lc.RemoteError as e:
    print("probe refused as expected (%s); the app now holds a fresh code" % e)
except Exception as e:
    print("probe failed: %s: %s" % (type(e).__name__, e))
PY
# ld.binding also holds the iroh secret key: only the 6-char code is extracted, the file is never shown.
CODE=$(adb shell run-as $PKG cat shared_prefs/ld.binding.xml 2>/dev/null \
  | sed -n 's/.*<string name="code">\([A-Z0-9]\{6\}\)<\/string>.*/\1/p' | head -1 | tr -d '\r')
say "pairing code: ${CODE:-<none found>}"
if [ -n "$CODE" ] && run_cli pair pair --code "$CODE" && grep -q "paired with" "$OUT/pair.out"; then pass pair; else fail pair; fi

# ------------------------------------------------------------------------------------------------ verbs
if run_cli ping ping && ! grep -q '"error"' "$OUT/ping.out"; then pass ping; else fail ping; fi
if run_cli sh sh -- uname -a && grep -q Linux "$OUT/sh.out"; then pass sh; else fail sh; fi
if run_cli sh-root sh --root -- id && grep -q 'uid=0' "$OUT/sh-root.out"; then pass sh-root; else fail sh-root; fi

URL=$(python3 -c 'import urllib.parse as u; print("data:text/html;charset=utf-8," + u.quote(
"<!doctype html><title>genie-e2e</title><label for=f>Field</label> <input id=f aria-label=Field> "
"<button id=b onclick=\"document.getElementById(\x27o\x27).textContent=\x27CLICKED:\x27+document.getElementById(\x27f\x27).value\">Go</button>"
"<p id=o>idle</p>"))')
if run_cli open open "$URL" && ! grep -q '"error"' "$OUT/open.out"; then pass open; else fail open; fi
sleep 3

run_cli tree --json tree
read -r SNAP FIELD BTN < <(python3 - "$OUT/tree.out" <<'PY'
import json, sys
try:
    t = json.loads(open(sys.argv[1], encoding="utf-8").read().split("\n[exit", 1)[0])
except Exception:
    print("- - -"); sys.exit()
f = b = "-"
for n in t.get("nodes", []):
    role, name = (n.get("role") or "").lower(), n.get("name") or ""
    if f == "-" and role in ("textbox", "searchbox", "textfield"): f = n["ref"]
    if b == "-" and role == "button" and "Go" in name: b = n["ref"]
print(t.get("snap") or "-", f, b)
PY
)
say "tree: snap=$SNAP field=$FIELD button=$BTN"
if [ "$FIELD" != "-" ] && [ "$BTN" != "-" ]; then pass tree; else fail tree "(textbox/button not found)"; fi

HEB="שלום עולם"
# type/click PASS only when the page itself shows the effect (read back with browser.eval).
TYPED=0; CLICKED=0
[ "$FIELD" != "-" ] && run_cli type type "$HEB" --into "$FIELD" && TYPED=1
[ "$BTN" != "-" ] && run_cli click click "$BTN" --snap "$SNAP" && CLICKED=1
sleep 1
run_cli readback raw browser.eval '{"js": "document.getElementById(\"f\").value + \" | \" + document.body.innerText"}'
say "page text: $(grep -o '"value": *"[^"]*"' "$OUT/readback.out" | head -1)"
if [ $TYPED = 1 ] && grep -q "$HEB |" "$OUT/readback.out"; then pass type; else fail type "(field value not read back)"; fi
if [ $CLICKED = 1 ] && grep -q "CLICKED:$HEB" "$OUT/readback.out"; then pass click; else fail click "(page text not changed)"; fi
run_cli page find --role button   # plain-text view of the page after the click

if run_cli shot shot "$OUT/genie-shot.jpg" && [ -s "$OUT/genie-shot.jpg" ]; then pass shot; else fail shot; fi

# ------------------------------------------------------------------------------------------------ evidence
# deskd writes /run/linuxdesk/selftest.log in the guest = <filesDir>/rootfs/run/linuxdesk/selftest.log
# (ProotRuntime.rootfsDir = filesDir/rootfs; /run is not bound, so it is the rootfs's own dir).
SELF=files/rootfs/run/linuxdesk/selftest.log
for _ in $(seq 1 18); do adb shell run-as $PKG test -s $SELF && break; sleep 10; done
adb shell run-as $PKG cat $SELF > "$OUT/selftest.log" 2>&1 || true
say "selftest.log:"; cat "$OUT/selftest.log" | tee -a "$LOG"
adb exec-out screencap -p > "$OUT/final.png" 2>/dev/null || true
kill $LOGCAT_PID 2>/dev/null || true
grep -E 'genie:|\[deskd\]|selftest' "$LOGCAT" | tail -120 >> "$LOG"

# Scrub: the paired token must not appear in anything we upload (the CLI never prints it; this proves it).
python3 - "$LINUXDESK_HOME" "$OUT" <<'PY' 2>&1 | tee -a "$LOG"
import glob, json, os, sys
home, out = sys.argv[1], sys.argv[2]
toks = []
for p in glob.glob(os.path.join(home, "*.json")):
    try: toks.append(json.load(open(p))["token"])
    except Exception: pass
hits = 0
for p in glob.glob(os.path.join(out, "*")):
    if not os.path.isfile(p) or p.endswith((".png", ".jpg")): continue
    b = open(p, "rb").read()
    for t in toks:
        if t and t.encode() in b:
            hits += 1; b = b.replace(t.encode(), b"<TOKEN-REDACTED>")
    open(p, "wb").write(b)
print("token scrub: %d token(s) known, %d occurrence(s) redacted" % (len(toks), hits))
PY
rm -rf "$LINUXDESK_HOME"
rm -f "$OUT"/*.out "$PREFS_XML"

line="SUMMARY genie-e2e:"; ok=0
for s in pair ping sh sh-root open tree type click shot; do line="$line $s=${RES[$s]}"; [ "${RES[$s]}" = PASS ] && ok=$((ok+1)); done
say "$line ($ok/9)"
[ $ok = 9 ]
