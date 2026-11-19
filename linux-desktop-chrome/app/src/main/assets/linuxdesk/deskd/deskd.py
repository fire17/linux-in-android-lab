#!/usr/bin/env python3
"""deskd: the LinuxDesk guest daemon (spec notes/spec/01-protocol-and-lanes.md section 2 phase A, 02-closing.md).

Runs INSIDE the Alpine guest under PRoot. Python 3 stdlib only (Pillow is used for JPEG when py3-pillow is installed).
Serves every Linux-side verb of the linuxdesk/1 wire: shell.* input.* screen.* browser.* stream.*.
Layout: deskd.py (server, auth, shell, screen, stream, self-test) · cdp.py (Chromium over the CDP pipe) ·
xinput.py (xdotool) · capture.py (xwd -> jpeg/png) · common.py. Replaces genie-daemon.py (now a refusing shim).

Wire (spec 01 1.1): request = u32 BE len || JSON (<= 1 MiB). Reply type 0 = u32 len || 0x00 || JSON; type 1 =
u32 len || 0x01 || u16 hlen || JSON header || bytes (<= 8 MiB). "id" is echoed; requests with an id run
concurrently (out-of-order replies), requests without one are answered in order. Stream frames carry sid+seq.

Auth (spec 01 2 + 4 rule 3): binds 127.0.0.1 only. The first frame must be {"auth": K, "genie": ..}, where K is
  (a) the session key: $LINUXDESK_KEY, read once at start and removed from the environment, or
  (b) a paired-genie token whose sha256 hex equals /run/linuxdesk/genie.hash (the app writes it at pairing and
      deletes it on Forget; a token session then gets {"error":"unpaired"} on its next command).
Anything else gets one {"error":"auth"} and a close. Auth values, typed text, stdin and file contents are never logged.

Browser (spec 02 #3 + section 03 override): with --browser CMD, deskd is Chromium's parent and runs
`CMD <flags> --remote-debugging-pipe` with the CDP pipe on fds 3/4. Only if the pipe fails does deskd use the decided
(weaker, UNAUTHED) loopback DevTools port, and it flags that in sys.ping and selftest.log (--cdp-fallback none = off).

Usage (the app / integrator):
  python3 deskd.py [--port 8765] [--browser /opt/linuxdesk/start-browser.sh] [--cdp-fallback port] -- <chromium flags>
  (flags may instead come as a JSON list in $LINUXDESK_BROWSER_FLAGS)
"""
import base64
import hashlib
import hmac
import json
import os
import pwd
import queue
import secrets
import selectors
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import capture  # noqa: E402
import cdp  # noqa: E402
import xinput  # noqa: E402
from common import VerbError, log, num, which  # noqa: E402

VERSION = "1.1.0"
PROTO = "linuxdesk/1"
MAX_IN = 1 << 20
MAX_OUT = 8 << 20
MAX_INFLIGHT = 16           # concurrent id-tagged requests per connection
MAX_SPAWN = 4               # spec 01 4 rule 9
MAX_STREAMS = 2
ACK_WINDOW = 4
EXEC_MAX_S = 600
EXEC_MAX_BYTES = 3 << 20    # per stream; keeps the JSON reply under 8 MiB

CFG = {"hash_file": "/run/linuxdesk/genie.hash", "run_dir": "/run/linuxdesk", "user": "desk"}
KEY = None                  # session key bytes (set once in main)


# ----------------------------------------------------------------------------------------------
# Frames

class TooBig(Exception):
    pass


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(min(1 << 16, n - len(buf)))
        if not chunk:
            raise EOFError("peer closed")
        buf += chunk
    return bytes(buf)


def read_frame(sock):
    n = struct.unpack(">I", recv_exact(sock, 4))[0]
    if n > MAX_IN:
        raise TooBig()
    return recv_exact(sock, n) if n else b""


def json_frame(obj):
    body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(body) + 1 > MAX_OUT:
        err = {"error": "too_big", "detail": "reply of %d bytes > 8 MiB" % len(body)}
        for k in ("id", "sid"):
            if k in obj:
                err[k] = obj[k]
        body = json.dumps(err).encode("utf-8")
    return struct.pack(">I", len(body) + 1) + b"\x00" + body


def bin_frame(header, data):
    h = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    total = 1 + 2 + len(h) + len(data)
    if total > MAX_OUT or len(h) > 0xFFFF:
        err = {"error": "too_big", "detail": "binary frame of %d bytes > 8 MiB; lower scale/quality" % total}
        for k in ("id", "sid"):
            if k in header:
                err[k] = header[k]
        return json_frame(err)
    return struct.pack(">I", total) + b"\x01" + struct.pack(">H", len(h)) + h + data


class Binary(object):
    """A verb result that goes out as a type-1 frame."""

    def __init__(self, header, data):
        self.header, self.data = header, data


# ----------------------------------------------------------------------------------------------
# Auth

def _token_hash():
    try:
        with open(CFG["hash_file"]) as f:
            return f.read().strip().lower()
    except OSError:
        return None


def authenticate(given):
    """-> "key" | ("token", sha256hex) | None. Constant-time compares."""
    g = str(given or "").encode("utf-8")
    if not g:
        return None
    if KEY is not None and hmac.compare_digest(g, KEY):
        return "key"
    want = _token_hash()
    have = hashlib.sha256(g).hexdigest()
    if want and len(want) == 64 and hmac.compare_digest(have, want):
        return ("token", have)
    return None


# ----------------------------------------------------------------------------------------------
# shell.*

def _identity(a):
    """-> (user kwargs for Popen, env additions). root = guest fake root (proot -0), user = desk (uid 1000)."""
    who = str(a.get("as", "user"))
    if who not in ("user", "root"):
        raise VerbError("bad_args", "as must be user or root")
    am_root = os.geteuid() == 0
    if who == "root":
        if not am_root:
            raise VerbError("unsupported", "deskd is not running as (fake) root")
        return {}, {"HOME": "/root", "USER": "root", "LOGNAME": "root"}
    if not am_root:
        pw = pwd.getpwuid(os.geteuid())   # dev hosts: run as whoever deskd is
        return {}, {"HOME": pw.pw_dir, "USER": pw.pw_name, "LOGNAME": pw.pw_name}
    try:
        pw = pwd.getpwnam(CFG["user"])
    except KeyError:
        raise VerbError("not_ready", "guest user %s does not exist (run setup-genie.sh)" % CFG["user"])
    return ({"user": pw.pw_uid, "group": pw.pw_gid, "extra_groups": []},
            {"HOME": pw.pw_dir, "USER": pw.pw_name, "LOGNAME": pw.pw_name})


def _popen(a, stdin_pipe):
    argv = a.get("argv")
    shcmd = a.get("sh")
    if argv is not None:
        if not (isinstance(argv, list) and argv and all(isinstance(x, str) for x in argv)):
            raise VerbError("bad_args", "argv must be a non-empty list of strings")
    elif isinstance(shcmd, str) and shcmd:
        argv = ["/bin/sh", "-c", shcmd]
    else:
        raise VerbError("bad_args", "argv:[...] or sh:\"...\" is required")
    ident, envadd = _identity(a)
    env = {k: v for k, v in os.environ.items() if not k.startswith("LINUXDESK_")}
    env.update(envadd)
    env.setdefault("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    env.setdefault("DISPLAY", ":0")
    extra = a.get("env") or {}
    if not isinstance(extra, dict):
        raise VerbError("bad_args", "env must be an object")
    for k, v in extra.items():
        env[str(k)] = str(v)
    cwd = a.get("cwd") or env.get("HOME")
    if cwd and not os.path.isdir(cwd):
        if a.get("cwd"):
            raise VerbError("bad_args", "cwd does not exist")
        cwd = "/"
    try:
        return subprocess.Popen(argv, stdin=subprocess.PIPE if stdin_pipe else subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd, env=env,
                                start_new_session=True, close_fds=True, **ident)
    except FileNotFoundError:
        raise VerbError("bad_args", "no such program: %s" % argv[0][:200])
    except PermissionError as e:
        raise VerbError("denied", str(e)[:200])


def _killpg(p, sig=signal.SIGKILL):
    try:
        os.killpg(p.pid, sig)
    except OSError:
        pass


def v_exec(a, ctx):
    timeout_s = num(a, "timeout_s", 30, 0.1, EXEC_MAX_S)
    max_bytes = num(a, "max_bytes", 256 << 10, 1, EXEC_MAX_BYTES, int)
    stdin = b""
    if a.get("stdin_b64"):
        try:
            stdin = base64.b64decode(a["stdin_b64"], validate=True)
        except (ValueError, TypeError):
            raise VerbError("bad_args", "stdin_b64 is not base64")
    t0 = time.time()
    p = _popen(a, stdin_pipe=bool(stdin))
    bufs = {1: bytearray(), 2: bytearray()}
    truncated = False
    sel = selectors.DefaultSelector()
    sel.register(p.stdout, selectors.EVENT_READ, 1)
    sel.register(p.stderr, selectors.EVENT_READ, 2)
    if stdin:
        os.set_blocking(p.stdin.fileno(), False)
        sel.register(p.stdin, selectors.EVENT_WRITE, 0)
    view = memoryview(stdin)
    deadline = t0 + timeout_s
    timed_out = False
    try:
        while sel.get_map():
            left = deadline - time.time()
            if left <= 0:
                timed_out = True
                _killpg(p)
                break
            for key, _ in sel.select(min(left, 0.5)):
                if key.data == 0:
                    try:
                        n = os.write(key.fileobj.fileno(), view[:65536])
                        view = view[n:]
                    except (BrokenPipeError, OSError):
                        view = view[:0]
                    if not view:
                        sel.unregister(key.fileobj)
                        key.fileobj.close()
                    continue
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    sel.unregister(key.fileobj)
                    continue
                b = bufs[key.data]
                room = max_bytes - len(b)
                if room > 0:
                    b += chunk[:room]
                if len(chunk) > room:
                    truncated = True
    finally:
        sel.close()
    try:
        code = p.wait(5 if not timed_out else 10)
    except subprocess.TimeoutExpired:
        _killpg(p)
        code = p.wait()
    for f in (p.stdout, p.stderr, p.stdin):
        try:
            if f:
                f.close()
        except OSError:
            pass
    out = {"ok": not timed_out, "code": code if code is None or code >= 0 else None, "truncated": truncated,
           "took_ms": int((time.time() - t0) * 1000)}
    if code is not None and code < 0:
        out["signal"] = -code
    if a.get("binary"):
        out["stdout_b64"] = base64.b64encode(bytes(bufs[1])).decode()
        out["stderr_b64"] = base64.b64encode(bytes(bufs[2])).decode()
    else:
        out["stdout"] = bufs[1].decode("utf-8", "replace")
        out["stderr"] = bufs[2].decode("utf-8", "replace")
    if timed_out:
        out.update(error="timeout", detail="killed after %ss" % timeout_s)
        del out["ok"]
    return out


class Proc(object):
    def __init__(self, conn, p, sid):
        self.conn, self.p, self.sid = conn, p, sid
        self.seq = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._pump, name="proc-%s" % sid, daemon=True).start()

    def _pump(self):
        sel = selectors.DefaultSelector()
        sel.register(self.p.stdout, selectors.EVENT_READ, 1)
        sel.register(self.p.stderr, selectors.EVENT_READ, 2)
        try:
            while sel.get_map():
                for key, _ in sel.select(1.0):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        sel.unregister(key.fileobj)
                        continue
                    with self.lock:
                        self.seq += 1
                        seq = self.seq
                    self.conn.send_binary({"sid": self.sid, "seq": seq, "fd": key.data, "t": time.time(),
                                           "mime": "application/octet-stream"}, chunk)
        except OSError:
            pass
        finally:
            sel.close()
        code = self.p.wait()
        self.conn.procs.pop(self.sid, None)
        msg = {"sid": self.sid, "exit": code if code >= 0 else None}
        if code < 0:
            msg["signal"] = -code
        self.conn.send_json(msg)

    def write(self, data=None, eof=False):
        if self.p.stdin is None:
            raise VerbError("bad_args", "stdin is closed")
        try:
            if data:
                self.p.stdin.write(data)
                self.p.stdin.flush()
            if eof:
                self.p.stdin.close()
                self.p.stdin = None
        except (BrokenPipeError, OSError):
            raise VerbError("bad_args", "the process has closed its stdin")

    def kill(self, sig=signal.SIGTERM):
        _killpg(self.p, sig)


def v_spawn(a, ctx):
    with ctx.lock:
        if len(ctx.procs) >= MAX_SPAWN:
            raise VerbError("busy", "at most %d spawned processes per session" % MAX_SPAWN)
        sid = ctx.new_sid("p")
        ctx.procs[sid] = None          # reserve the slot
    try:
        p = _popen(a, stdin_pipe=True)
    except Exception:
        ctx.procs.pop(sid, None)
        raise
    if a.get("stdin_b64"):
        try:
            p.stdin.write(base64.b64decode(a["stdin_b64"]))
            p.stdin.flush()
        except (ValueError, OSError):
            pass
    ctx.procs[sid] = Proc(ctx, p, sid)
    return {"ok": True, "sid": sid, "pid": p.pid}


def _proc(a, ctx):
    pr = ctx.procs.get(str(a.get("sid")))
    if pr is None:
        raise VerbError("bad_args", "no running process with that sid")
    return pr


def v_write(a, ctx):
    pr = _proc(a, ctx)
    data = None
    if a.get("data_b64"):
        try:
            data = base64.b64decode(a["data_b64"], validate=True)
        except (ValueError, TypeError):
            raise VerbError("bad_args", "data_b64 is not base64")
    pr.write(data, bool(a.get("eof")))
    return {"ok": True}


_SIGNALS = {"TERM": signal.SIGTERM, "KILL": signal.SIGKILL, "INT": signal.SIGINT, "HUP": signal.SIGHUP,
            "STOP": signal.SIGSTOP, "CONT": signal.SIGCONT, "USR1": signal.SIGUSR1, "USR2": signal.SIGUSR2}


def v_kill(a, ctx):
    pr = _proc(a, ctx)
    s = str(a.get("signal", "TERM")).upper().replace("SIG", "")
    if s.isdigit():
        sig = int(s)
    elif s in _SIGNALS:
        sig = _SIGNALS[s]
    else:
        raise VerbError("bad_args", "unknown signal")
    pr.kill(sig)
    return {"ok": True}


# ----------------------------------------------------------------------------------------------
# screen.* and the xbox calibration (spec 02 #6)

XB = {"key": None, "win": None, "calibrated": False, "t": 0}


def _browser_win():
    if not xinput.available():
        return None
    return xinput.browser_window()


def xbox_hook(tid, m):
    """(window origin in X px or None, calibrated). Self-calibrates once per window geometry (rotation = new)."""
    now = time.time()
    if now - XB["t"] < 2 and XB["key"] is not None:
        return XB["win"], XB["calibrated"]
    XB["t"] = now
    g = _browser_win()
    if not g:
        XB.update(key=None, win=None, calibrated=False)
        return None, False
    key = (g["x"], g["y"], g["w"], g["h"], m.get("iw"), m.get("ih"), m.get("dpr"))
    if key != XB["key"]:
        XB.update(key=key, win=(g["x"], g["y"]), calibrated=_calibrate(tid, m, (g["x"], g["y"])))
    return XB["win"], XB["calibrated"]


def _calibrate(tid, m, win):
    """Moves the X pointer to the xbox of the viewport centre and checks where the page saw it."""
    try:
        cx, cy = (m.get("iw") or 0) / 2.0, (m.get("ih") or 0) / 2.0
        if not cx or not cy:
            return False
        xb = cdp.xbox_of([cx, cy, 0, 0], m, win)
        cdp.B.eval(tid, "window.__ldHover=null;addEventListener('mousemove',e=>{window.__ldHover=[e.clientX,e.clientY]},"
                        "{capture:true,once:true});true", timeout=5)
        xinput.v_move({"x": xb[0] + 3, "y": xb[1] + 3})
        time.sleep(0.05)
        xinput.v_move({"x": xb[0], "y": xb[1]})
        time.sleep(0.3)
        seen = cdp.B.eval(tid, "window.__ldHover", timeout=5)
        ok = bool(seen) and abs(seen[0] - cx) <= 2 and abs(seen[1] - cy) <= 2
        log("xbox-calib %s" % ("ok" if ok else "off (%s)" % (seen,)))
        return ok
    except (VerbError, cdp.CDPError, TypeError, IndexError):
        return False


def v_info(a, ctx):
    out = {"ok": True, "w": None, "h": None, "dpi": _envnum("LINUXDESK_DPI"), "density": _envnum("LINUXDESK_DENSITY"),
           "browser_window": None, "dpr": None, "capture_ms_last": capture.LAST["ms"],
           "capture_source_last": capture.LAST["source"], "xbox_calibrated": XB["calibrated"]}
    try:
        out["w"], out["h"] = xinput.screen_size()
    except VerbError:
        pass
    out["browser_window"] = _browser_win()
    if cdp.B.ready():
        try:
            tid = cdp.B.resolve_tab(a.get("tab"))["targetId"]
            m = cdp.metrics(tid)
            out["dpr"] = m.get("dpr")
            if out["w"] is None:
                out["w"], out["h"] = int((m.get("ow") or 0) * (m.get("dpr") or 1)), int((m.get("oh") or 0) * (m.get("dpr") or 1))
        except (VerbError, cdp.CDPError):
            pass
    return out


def _envnum(k):
    try:
        return float(os.environ[k])
    except (KeyError, ValueError):
        return None


def v_capture(a, ctx):
    c = capture.args_of(a)
    t0 = time.time()
    if c["source"] == "browser":
        data, w, h, mime = cdp.capture(a, c["format"], c["quality"], c["scale"], c["region"])
        capture._note((time.time() - t0) * 1000, "browser", "cdp")
    else:
        data, w, h, mime = capture.capture_x11(c["format"], c["quality"], c["scale"], c["region"])
    return Binary({"mime": mime, "w": w, "h": h, "source": c["source"], "t": time.time(),
                   "ms": int((time.time() - t0) * 1000)}, data)


# ----------------------------------------------------------------------------------------------
# stream.*

class ScreenStream(object):
    """Screen frames with an ack window: at most 4 unacked; when the genie lags, older frames are dropped and
    the newest is sent once the window opens."""

    def __init__(self, ctx, sid, c):
        self.ctx, self.sid, self.c = ctx, sid, c
        self.seq = 0
        self.acked = 0
        self.frames = 0
        self.dropped = 0
        self.pending = None
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.cast = None
        if c["source"] == "browser":
            self.cast = cdp.Screencast({"tab": c.get("tab")}, self.offer, "jpeg", c["quality"], c["scale"])
        else:
            threading.Thread(target=self._x11_loop, name="stream-%s" % sid, daemon=True).start()

    def _window_open(self):
        return self.seq - self.acked < ACK_WINDOW

    def offer(self, data, w, h, mime):
        if self.stopped.is_set():
            return
        with self.lock:
            if not self._window_open():
                if self.pending is not None:
                    self.dropped += 1
                self.pending = (data, w, h, mime)
                return
            self._send(data, w, h, mime)

    def _send(self, data, w, h, mime):
        self.seq += 1
        self.frames += 1
        self.ctx.send_binary({"sid": self.sid, "seq": self.seq, "t": time.time(), "mime": mime, "w": w, "h": h}, data)

    def ack(self, seq):
        with self.lock:
            self.acked = max(self.acked, min(int(seq), self.seq))
            if self.pending is not None and self._window_open():
                p, self.pending = self.pending, None
                self._send(*p)

    def _x11_loop(self):
        period = 1.0 / self.c["fps"]
        while not self.stopped.is_set():
            t0 = time.time()
            with self.lock:
                full = not self._window_open()
            if full:
                self.dropped += 1          # skip the capture: the genie gets the newest state when it catches up
            else:
                try:
                    data, w, h, mime = capture.capture_x11(self.c["format"], self.c["quality"], self.c["scale"],
                                                           self.c["region"])
                    self.offer(data, w, h, mime)
                except VerbError as e:
                    self.ctx.send_json({"sid": self.sid, "error": e.code, "detail": e.detail})
                    self.stopped.set()
                    self.ctx.streams.pop(self.sid, None)
                    return
            self.stopped.wait(max(0.0, period - (time.time() - t0)))

    def stop(self):
        self.stopped.set()
        if self.cast:
            self.cast.stop()
        return {"ok": True, "frames": self.frames, "dropped": self.dropped}


def v_stream_start(a, ctx):
    kind = str(a.get("kind", "screen"))
    if kind == "audio":
        raise VerbError("unsupported", "no sound server in guest")
    if kind == "proc":
        return v_spawn(a, ctx)
    if kind != "screen":
        raise VerbError("bad_args", "kind must be screen, proc or audio")
    source = str(a.get("source", "x11"))
    c = capture.args_of(a, fps_max=5 if source == "x11" else 15)
    c["tab"] = a.get("tab")
    with ctx.lock:
        if len(ctx.streams) >= MAX_STREAMS:
            raise VerbError("busy", "at most %d streams per session" % MAX_STREAMS)
        sid = ctx.new_sid("s")
        ctx.streams[sid] = None
    try:
        ctx.streams[sid] = ScreenStream(ctx, sid, c)
    except Exception:
        ctx.streams.pop(sid, None)
        raise
    return {"ok": True, "sid": sid, "fps": c["fps"], "source": source}


def v_stream_ack(a, ctx):
    st = ctx.streams.get(str(a.get("sid")))
    if st is not None:
        st.ack(num(a, "seq", 0, kind=int))
    return None                        # no reply (spec 01)


def v_stream_stop(a, ctx):
    sid = str(a.get("sid"))
    st = ctx.streams.pop(sid, None)
    if st is not None:
        return st.stop()
    pr = ctx.procs.get(sid)
    if pr is not None:
        pr.kill(signal.SIGTERM)
        return {"ok": True}
    raise VerbError("bad_args", "no stream with that sid")


# ----------------------------------------------------------------------------------------------
# sys.ping (deskd's own view; the app's Router answers sys.* for genies in phase B)

def caps():
    c = ["shell", "stream"]
    if xinput.available():
        c.append("input")
    if which("xwd") or cdp.B.ready():
        c.append("screen")
    if cdp.B.ready():
        c.append("browser")
    return c


def v_ping(a, ctx):
    b = cdp.B
    return {"ok": True, "app": "deskd", "version": VERSION, "proto": PROTO, "caps": caps(), "audio": False,
            "session": {"deskd": True, "x11": xinput.available(), "browser": b.ready(), "cdp": b.mode,
                        "cdp_unauthed_port": b.mode == "port",
                        "cdp_pipe": b.pipe_result, "browser_version": (b.version or {}).get("product")},
            "as_root": os.geteuid() == 0}


def _wrap(fn):
    return lambda a, ctx: fn(a)


VERBS = {
    "sys.ping": v_ping, "sys.caps": lambda a, ctx: {"ok": True, "caps": caps(), "audio": False}, "ping": v_ping,
    "shell.exec": v_exec, "shell.spawn": v_spawn, "shell.write": v_write, "shell.kill": v_kill,
    "screen.info": v_info, "screen.capture": v_capture,
    "stream.start": v_stream_start, "stream.ack": v_stream_ack, "stream.stop": v_stream_stop,
}
for _n, _f in xinput.VERBS.items():
    VERBS[_n] = _wrap(_f)
for _n, _f in cdp.VERBS.items():
    VERBS[_n] = _wrap(_f)
for _old, _new in cdp.LEGACY.items():
    VERBS[_old] = VERBS[_new]
NEEDS_X = {n for n in xinput.VERBS}
TEXT_ARGS = {"input.type", "browser.type", "chrome.type", "shell.write", "shell.exec", "shell.spawn"}


def dispatch(msg, ctx):
    verb = msg.get("cmd") or msg.get("verb") or ""
    fn = VERBS.get(verb)
    if fn is None:
        return {"error": "unknown_cmd", "detail": "unknown cmd %r" % str(verb)[:60]}
    args = msg.get("args") if isinstance(msg.get("args"), dict) else msg
    try:
        return fn(args, ctx)
    except VerbError as e:
        return {"error": e.code, "detail": e.detail}
    except cdp.CDPError as e:
        return {"error": "internal", "detail": "cdp: %s" % str(e)[:300]}
    except (KeyError, TypeError, ValueError) as e:
        return {"error": "bad_args", "detail": "%s: %s" % (type(e).__name__, str(e)[:200])}
    except Exception as e:  # a verb must never crash the connection
        return {"error": "internal", "detail": "%s: %s" % (type(e).__name__, str(e)[:300])}


# ----------------------------------------------------------------------------------------------
# Server

class Conn(object):
    def __init__(self, sock, peer):
        self.sock, self.peer = sock, peer
        self.wlock = threading.Lock()
        self.lock = threading.Lock()
        self.procs = {}
        self.streams = {}
        self.inflight = 0
        self.closed = False
        self.auth = None
        self._sid = 0

    def new_sid(self, prefix):
        self._sid += 1
        return "%s%d" % (prefix, self._sid)

    def _write(self, frame):
        if self.closed:
            return
        with self.wlock:
            try:
                self.sock.sendall(frame)
            except OSError:
                self.closed = True

    def send_json(self, obj):
        self._write(json_frame(obj))

    def send_binary(self, header, data):
        self._write(bin_frame(header, data))

    def reply(self, msg, result):
        if result is None:
            return
        if isinstance(result, Binary):
            h = dict(result.header)
            if "id" in msg:
                h["id"] = msg["id"]
            self.send_binary(h, result.data)
        else:
            if "id" in msg:
                result["id"] = msg["id"]
            self.send_json(result)

    def still_paired(self):
        if self.auth == "key":
            return True
        h = _token_hash()
        return bool(h) and hmac.compare_digest(h, self.auth[1])

    def run(self, msg):
        t0 = time.time()
        verb = str(msg.get("cmd") or msg.get("verb") or "")[:40]
        r = dispatch(msg, self)
        self.reply(msg, r)
        ok = "ok" if (r is None or isinstance(r, Binary) or r.get("ok")) else r.get("error")
        if verb != "stream.ack":
            log("%s %s %dms" % (verb, ok, (time.time() - t0) * 1000))

    def run_async(self, msg):
        try:
            self.run(msg)
        finally:
            with self.lock:
                self.inflight -= 1

    def cleanup(self):
        self.closed = True
        for st in list(self.streams.values()):
            if st:
                st.stop()
        for pr in list(self.procs.values()):
            if pr:
                pr.kill(signal.SIGKILL)
        self.streams.clear()
        if cdp.B.mode == "port" and self.auth is not None:
            # spec 02 #3 fallback: a new random DevTools port after every disconnect
            threading.Thread(target=_safe_restart, daemon=True).start()


def _safe_restart():
    try:
        cdp.B.restart()
    except VerbError as e:
        log("browser restart failed: %s" % e.detail[:160])


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        c = Conn(self.request, self.client_address[0])
        try:
            self.loop(c)
        finally:
            c.cleanup()
            try:
                self.request.close()
            except OSError:
                pass

    def loop(self, c):
        sock = self.request
        sock.settimeout(30)                   # the hello must come quickly
        while True:
            try:
                raw = read_frame(sock)
            except TooBig:
                c.send_json({"error": "too_big", "detail": "request > 1 MiB"})
                return
            except (EOFError, OSError):
                return
            try:
                msg = json.loads(raw.decode("utf-8"))
                if not isinstance(msg, dict):
                    raise ValueError()
            except (ValueError, UnicodeDecodeError):
                c.send_json({"error": "bad_json"})
                if c.auth is None:
                    return
                continue
            if c.auth is None:
                who = authenticate(msg.get("auth")) if "auth" in msg else None
                if who is None:
                    log("auth refused (%s)" % c.peer)
                    c.send_json({"error": "auth"})
                    time.sleep(0.2)
                    return
                c.auth = who
                sock.settimeout(None)
                genie = str(msg.get("genie") or "?")[:64]
                log("connected: %s via %s" % (genie, "key" if who == "key" else "token"))
                hello = {"ok": True, "app": "deskd", "version": VERSION, "proto": PROTO, "genie": genie,
                         "caps": caps(), "audio": False}
                if "id" in msg:
                    hello["id"] = msg["id"]
                c.send_json(hello)
                continue
            if not c.still_paired():
                c.send_json({"error": "unpaired", **({"id": msg["id"]} if "id" in msg else {})})
                log("token session closed: unpaired")
                return
            if "id" in msg and msg.get("cmd") != "stream.ack":
                with c.lock:
                    if c.inflight >= MAX_INFLIGHT:
                        c.send_json({"id": msg["id"], "error": "busy", "detail": "too many requests in flight"})
                        continue
                    c.inflight += 1
                threading.Thread(target=c.run_async, args=(msg,), daemon=True).start()
            else:
                c.run(msg)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


# ----------------------------------------------------------------------------------------------
# Self-test batch (spec 02): /run/linuxdesk/selftest.log

def selftest(proc_root=False):
    lines = []

    def rec(name, result):
        lines.append("%s %s %s" % (time.strftime("%Y-%m-%dT%H:%M:%S"), name, result))

    pr = cdp.B.pipe_result or ("n/a (mode %s)" % cdp.B.mode)
    if cdp.B.mode == "port":
        pr += " -> FALLBACK loopback DevTools port in use (UNAUTHED while open; relaunched on every disconnect)"
    rec("cdp-pipe", pr)
    try:
        p50, p90 = capture.xwd_cost(10)
        rec("xwd-cost", "p50=%dms p90=%dms encoder=%s gate=%s" % (p50, p90, capture.LAST["encoder"],
                                                                  "FAIL(>400)" if p50 > 400 else "pass"))
    except VerbError as e:
        rec("xwd-cost", "fail %s" % e.detail[:120])
    path = os.path.join(CFG["run_dir"], "t.sock")
    try:
        try:
            os.unlink(path)
        except OSError:
            pass
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.bind(path)
        s.listen(1)
        s.close()
        os.unlink(path)
        rec("unix-sock", "guest-bind ok (app-side connect is the app's half)")
    except OSError as e:
        rec("unix-sock", "guest-bind fail %s" % e.strerror)
    if cdp.B.ready():
        try:
            tid = cdp.B.resolve_tab()["targetId"]
            XB["key"] = None
            XB["t"] = 0
            win, ok = xbox_hook(tid, cdp.metrics(tid))
            rec("xbox-calib", "calibrated=%s win=%s" % (ok, win))
        except (VerbError, cdp.CDPError) as e:
            rec("xbox-calib", "fail %s" % str(e)[:120])
    else:
        rec("xbox-calib", "skipped (no browser)")
    if proc_root:
        res = []
        for label, pid in (("1", 1), ("ppid", os.getppid())):
            try:
                os.listdir("/proc/%d/root/storage/emulated/0" % pid)
                res.append("%s=listed" % label)          # success only; never file names
            except OSError as e:
                res.append("%s=blocked(%s)" % (label, e.errno))
        rec("proc-root", " ".join(res))
    try:
        os.makedirs(CFG["run_dir"], exist_ok=True)
        with open(os.path.join(CFG["run_dir"], "selftest.log"), "w") as f:
            f.write("\n".join(lines) + "\n")
    except OSError as e:
        log("selftest.log not written: %s" % e.strerror)
    for ln in lines:
        log("selftest " + ln.split(" ", 1)[1])
    return lines


# ----------------------------------------------------------------------------------------------

def main(argv):
    global KEY
    import argparse
    ap = argparse.ArgumentParser(description="LinuxDesk guest daemon (linuxdesk/1)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=int(os.environ.get("LINUXDESK_PORT", "8765")))
    ap.add_argument("--browser", default=os.environ.get("LINUXDESK_BROWSER_CMD"),
                    help="command deskd runs as Chromium's parent, e.g. /opt/linuxdesk/start-browser.sh")
    ap.add_argument("--cdp-fallback", choices=["none", "port"], default=os.environ.get("LINUXDESK_CDP_FALLBACK", "port"),
                    help="port (default, spec 02 section 03 override) = if the pipe fails, use an UNAUTHED loopback "
                         "DevTools port, flagged in sys.ping and selftest.log; none = no fallback")
    ap.add_argument("--cdp-attach", type=int, default=None, help="dev only: attach to a running DevTools port")
    ap.add_argument("--hash-file", default=CFG["hash_file"])
    ap.add_argument("--run-dir", default=CFG["run_dir"])
    ap.add_argument("--user", default=CFG["user"], help="guest user for as:user (default desk)")
    ap.add_argument("--selftest", action="store_true", default=os.environ.get("LINUXDESK_SELFTEST") == "1")
    ap.add_argument("--selftest-proc-root", action="store_true",
                    default=os.environ.get("LINUXDESK_SELFTEST_PROCROOT") == "1", help="debug builds only")
    ap.add_argument("flags", nargs="*", help="Chromium flags (after --)")
    o = ap.parse_args(argv)

    k = os.environ.pop("LINUXDESK_KEY", None)         # read once, then gone from our environ (and children's)
    KEY = k.encode("utf-8") if k else None
    if o.host not in ("127.0.0.1", "::1"):
        raise SystemExit("[deskd] refusing to bind %s: loopback only" % o.host)
    CFG.update(hash_file=o.hash_file, run_dir=o.run_dir, user=o.user)
    flags = list(o.flags)
    if not flags and os.environ.get("LINUXDESK_BROWSER_FLAGS"):
        try:
            flags = [str(x) for x in json.loads(os.environ["LINUXDESK_BROWSER_FLAGS"])]
        except ValueError:
            raise SystemExit("[deskd] LINUXDESK_BROWSER_FLAGS is not a JSON list")
    try:
        srv = Server((o.host, o.port), Handler)
    except OSError as e:
        log("deskd: port busy %s:%d (%s); not falling back to another address" % (o.host, o.port, e.strerror))
        return 3
    if KEY is None:
        log("no session key in env: only a paired token (genie.hash) can connect")
    log("v%s listening on %s:%d" % (VERSION, o.host, o.port))

    b = cdp.B
    cdp.XBOX_HOOK = xbox_hook
    b.fallback = "port" if o.cdp_fallback == "port" else None
    if o.cdp_attach:
        b.attach_port = o.cdp_attach
    elif o.browser:
        b.cmd = [o.browser]                 # one executable path (may contain spaces); flags go after --
        b.args = flags

    def boot():
        if b.cmd or b.attach_port:
            try:
                b.start()
            except VerbError as e:
                log("browser not started: %s" % e.detail[:200])
        if o.selftest:
            selftest(o.selftest_proc_root)

    threading.Thread(target=boot, name="boot", daemon=True).start()

    def bye(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, bye)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        b.stop()
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
