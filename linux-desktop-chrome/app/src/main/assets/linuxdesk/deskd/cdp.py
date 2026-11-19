"""deskd CDP: Chromium control over the Chrome DevTools Protocol (spec 01 section 1.3 `browser.*`).

Transport (spec 02 #3):
  * PIPE (default, the only one allowed without an explicit opt-in): deskd is Chromium's parent and starts
    `start-browser.sh <flags> --remote-debugging-pipe` with fd 3 = Chromium's input and fd 4 = Chromium's output.
    Messages are JSON + NUL. No TCP port exists, so no other app on the phone can drive the browser.
  * PORT fallback (default ON per spec 02 section 03, used ONLY if the pipe fails; --cdp-fallback none = off): `--remote-debugging-port=0 --remote-debugging-address=127.0.0.1
    --remote-allow-origins=`; deskd reads DevToolsActivePort from the profile dir. This port is an UNAUTHED loopback
    surface while it is open; deskd relaunches Chromium on a new random port on every genie disconnect.
  * ATTACH (legacy, --cdp-port N): connect to an already running browser's DevTools port (the old genie-daemon path).

One browser-level connection carries everything; tabs are driven through flattened target sessions
(Target.attachToTarget flatten=true), so the same code works over the pipe and over a WebSocket.
Python 3 stdlib only.
"""
import base64
import fcntl
import hashlib
import http.client
import json
import os
import signal
import socket
import struct
import subprocess
import threading
import time
import urllib.parse

from common import VerbError, image_size, log, num


class CDPError(Exception):
    pass


# ----------------------------------------------------------------------------------------------
# Transports: send(str) / recv() -> str / close()

class PipeTransport(object):
    """--remote-debugging-pipe: deskd writes to Chromium's fd 3 and reads Chromium's fd 4; NUL-separated JSON."""

    def __init__(self, wfd, rfd):
        self.wfd, self.rfd = wfd, rfd
        self.buf = b""
        self.wlock = threading.Lock()
        self.kind = "pipe"

    def send(self, text):
        data = text.encode("utf-8") + b"\0"
        with self.wlock:
            view = memoryview(data)
            while view:
                n = os.write(self.wfd, view)
                view = view[n:]

    def recv(self):
        while True:
            i = self.buf.find(b"\0")
            if i >= 0:
                msg, self.buf = self.buf[:i], self.buf[i + 1:]
                return msg.decode("utf-8", "replace")
            chunk = os.read(self.rfd, 1 << 16)
            if not chunk:
                raise ConnectionError("CDP pipe closed")
            self.buf += chunk
            if len(self.buf) > (256 << 20):
                raise ConnectionError("CDP pipe message too large")

    def close(self):
        for fd in (self.wfd, self.rfd):
            try:
                os.close(fd)
            except OSError:
                pass


_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _mask(data, key):
    n = len(data)
    if n == 0:
        return b""
    k = (key * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "big") ^ int.from_bytes(k, "big")).to_bytes(n, "big")


class WSTransport(object):
    """Minimal RFC 6455 client (text frames, masking, ping/pong, close) for the port fallback and attach mode."""

    def __init__(self, url, timeout=15.0):
        u = urllib.parse.urlsplit(url)
        if u.scheme != "ws":
            raise CDPError("only ws:// is supported: %s" % url)
        host, port = u.hostname, u.port or 80
        if host not in ("127.0.0.1", "localhost", "::1"):
            raise CDPError("refusing a non-loopback DevTools endpoint")
        path = (u.path or "/") + ("?" + u.query if u.query else "")
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        # No Origin header: --remote-allow-origins only applies to connects that send one.
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n") % (path, host, port, key)
        self.sock.sendall(req.encode("ascii"))
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise CDPError("websocket handshake: connection closed")
            resp += chunk
            if len(resp) > 65536:
                raise CDPError("websocket handshake: header too long")
        head, self.buf = resp.split(b"\r\n\r\n", 1)
        lines = head.decode("latin-1").split("\r\n")
        if " 101 " not in lines[0] + " ":
            raise CDPError("websocket handshake refused: %s" % lines[0])
        want = base64.b64encode(hashlib.sha1((key + _WS_GUID).encode("ascii")).digest()).decode("ascii")
        got = ""
        for ln in lines[1:]:
            if ln.lower().startswith("sec-websocket-accept:"):
                got = ln.split(":", 1)[1].strip()
        if got != want:
            raise CDPError("websocket handshake: bad Sec-WebSocket-Accept")
        self.sock.settimeout(None)
        self.wlock = threading.Lock()
        self.kind = "ws"

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buf)))
            if not chunk:
                raise ConnectionError("websocket closed by peer")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode, payload):
        n = len(payload)
        hdr = bytearray([0x80 | opcode])
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack(">Q", n)
        mkey = os.urandom(4)
        with self.wlock:
            self.sock.sendall(bytes(hdr) + mkey + _mask(payload, mkey))

    def send(self, text):
        self._send_frame(0x1, text.encode("utf-8"))

    def recv(self):
        parts = []
        while True:
            b0, b1 = self._recv(2)
            fin, opcode = b0 & 0x80, b0 & 0x0F
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv(8))[0]
            mkey = self._recv(4) if b1 & 0x80 else None
            data = self._recv(n) if n else b""
            if mkey:
                data = _mask(data, mkey)
            if opcode == 0x9:
                self._send_frame(0xA, data)
                continue
            if opcode == 0xA:
                continue
            if opcode == 0x8:
                raise ConnectionError("websocket closed")
            parts.append(data)
            if fin:
                return b"".join(parts).decode("utf-8", "replace")

    def close(self):
        try:
            self._send_frame(0x8, b"")
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


# ----------------------------------------------------------------------------------------------
# Browser-level CDP connection: one reader thread routes replies by id and events to listeners.

class CDPConn(object):
    def __init__(self, transport):
        self.t = transport
        self.next_id = 0
        self.lock = threading.Lock()
        self.pending = {}            # id -> [Event, reply]
        self.listeners = {}          # (sessionId, method) -> [fn]
        self.dead = False
        self.on_close = None
        th = threading.Thread(target=self._reader, name="cdp-reader", daemon=True)
        th.start()

    def _reader(self):
        try:
            while True:
                msg = json.loads(self.t.recv())
                mid = msg.get("id")
                if mid is not None:
                    with self.lock:
                        slot = self.pending.pop(mid, None)
                    if slot:
                        slot[1] = msg
                        slot[0].set()
                    continue
                key = (msg.get("sessionId"), msg.get("method"))
                for fn in list(self.listeners.get(key, ())) + list(self.listeners.get((None, msg.get("method")), ())):
                    try:
                        fn(msg.get("params") or {}, msg.get("sessionId"))
                    except Exception as e:  # a listener must never kill the reader
                        log("cdp listener error %s" % type(e).__name__)
        except (ConnectionError, OSError, ValueError) as e:
            log("cdp connection closed (%s)" % type(e).__name__)
        finally:
            self.dead = True
            with self.lock:
                slots, self.pending = list(self.pending.values()), {}
            for s in slots:
                s[1] = {"error": {"message": "CDP connection closed"}, "_closed": True}
                s[0].set()
            if self.on_close:
                try:
                    self.on_close()
                except Exception:
                    pass

    def call(self, method, params=None, session=None, timeout=30.0):
        if self.dead:
            raise VerbError("not_ready", "the browser is not connected")
        ev = threading.Event()
        slot = [ev, None]
        with self.lock:
            self.next_id += 1
            mid = self.next_id
            self.pending[mid] = slot
        msg = {"id": mid, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        try:
            self.t.send(json.dumps(msg))
        except OSError:
            with self.lock:
                self.pending.pop(mid, None)
            raise VerbError("not_ready", "the browser connection is broken")
        if not ev.wait(timeout):
            with self.lock:
                self.pending.pop(mid, None)
            raise VerbError("timeout", "%s took longer than %ss" % (method, timeout))
        r = slot[1]
        if r.get("_closed"):
            raise VerbError("not_ready", "the browser connection closed")
        if "error" in r:
            raise CDPError(r["error"].get("message", "cdp error"))
        return r.get("result", {})

    def on(self, method, session, fn):
        self.listeners.setdefault((session, method), []).append(fn)

    def off(self, method, session, fn):
        lst = self.listeners.get((session, method)) or []
        if fn in lst:
            lst.remove(fn)

    def close(self):
        self.dead = True
        self.t.close()


# ----------------------------------------------------------------------------------------------
# The browser: launch (pipe / port fallback) or attach, plus tab sessions

class Browser(object):
    def __init__(self):
        self.cmd = None              # argv prefix, e.g. ["/opt/linuxdesk/start-browser.sh"]
        self.args = []               # Chromium flags (from the app's ChromiumFlags)
        self.fallback = None         # None | "port"
        self.attach_port = None
        self.proc = None
        self.conn = None
        self.mode = None             # "pipe" | "port" | "attach"
        self.lock = threading.RLock()
        self.sessions = {}           # targetId -> sessionId
        self.current = None
        self.snaps = {}              # targetId -> {"seq": n, "snap": id, "loader": id, "names": {ref: (role, name)}}
        self.version = None
        self.restarts = []
        self.stopping = False
        self.last_error = None
        self.pipe_result = None      # "ok" | "fail:<why>" (self-test cdp-pipe)
        self.xbox = None             # calibration state, filled by deskd

    # --- lifecycle ---
    @property
    def owned(self):
        return self.cmd is not None

    def ready(self):
        return self.conn is not None and not self.conn.dead

    def start(self):
        with self.lock:
            if self.attach_port:
                return self._attach(self.attach_port)
            if not self.cmd:
                raise VerbError("not_ready", "deskd was started without a browser command")
            if os.path.exists("/etc/linuxdesk/genie-enabled"):
                # The legacy opt-in block in start-browser.sh would add an unauthed --remote-debugging-port=9222.
                raise VerbError("denied", "legacy marker /etc/linuxdesk/genie-enabled exists (it opens an unauthed "
                                          "DevTools port); setup-genie.sh removes it")
            try:
                self._launch_pipe()
                self.pipe_result = "ok"
                return
            except VerbError as e:
                self.pipe_result = "fail:%s" % e.detail[:120]
                log("cdp-pipe fail (%s)" % e.detail[:120])
                self._kill()
                if self.fallback != "port":
                    self.last_error = e.detail
                    raise
            log("cdp falling back to an unauthed loopback DevTools port (--cdp-fallback port)")
            self._launch_port()

    def _env(self):
        env = dict(os.environ)
        for k in list(env):
            if k.startswith("LINUXDESK_") and k not in ("LINUXDESK_DPI", "LINUXDESK_DENSITY"):
                env.pop(k)
        env["LINUXDESK_GENIE"] = "0"     # never let start-browser.sh's legacy block open port 9222
        return env

    def _launch_pipe(self):
        to_r, to_w = os.pipe()           # deskd -> chromium (chromium reads fd 3)
        from_r, from_w = os.pipe()       # chromium -> deskd (chromium writes fd 4)
        # The child's ends go to fds >= 5, so moving them onto 3/4 can never clobber a source fd.
        c3 = fcntl.fcntl(to_r, fcntl.F_DUPFD_CLOEXEC, 5)
        c4 = fcntl.fcntl(from_w, fcntl.F_DUPFD_CLOEXEC, 5)
        os.close(to_r)
        os.close(from_w)
        # A tiny /bin/sh trampoline (no preexec_fn, which is unsafe with threads): pass_fds keeps only c3/c4 open,
        # the shell moves them onto 3/4 and execs start-browser.sh, whose chromium inherits 3/4 (proot keeps fds
        # across execve; spec 02 #3).
        tramp = 'eval "exec 3<&$1 4>&$2 $1<&- $2>&-"; shift 2; exec "$@"'
        argv = ["/bin/sh", "-c", tramp, "deskd-cdp", str(c3), str(c4)] + list(self.cmd) + list(self.args) + [
            "--remote-debugging-pipe"]
        try:
            self.proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, env=self._env(), start_new_session=True,
                                         pass_fds=(c3, c4), close_fds=True)
        except OSError as e:
            for fd in (c3, c4, to_w, from_r):
                os.close(fd)
            raise VerbError("not_ready", "cannot start the browser: %s" % e)
        os.close(c3)
        os.close(c4)
        os.set_blocking(from_r, True)
        conn = CDPConn(PipeTransport(to_w, from_r))
        try:
            v = conn.call("Browser.getVersion", timeout=float(os.environ.get("LINUXDESK_CDP_WAIT", "20")))
        except (VerbError, CDPError) as e:
            conn.close()
            raise VerbError("not_ready", "no Browser.getVersion reply on the CDP pipe: %s" % e)
        self._adopt(conn, "pipe", v)
        log("cdp-pipe ok (%s)" % v.get("product", "?"))

    def _launch_port(self):
        udd = None
        for a in self.args:
            if a.startswith("--user-data-dir="):
                udd = a.split("=", 1)[1]
        if not udd:
            raise VerbError("not_ready", "port fallback needs --user-data-dir in the browser flags")
        portfile = os.path.join(udd, "DevToolsActivePort")
        try:
            os.unlink(portfile)
        except OSError:
            pass
        argv = list(self.cmd) + list(self.args) + [
            "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1", "--remote-allow-origins="]
        self.proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, env=self._env(), start_new_session=True)
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                with open(portfile) as f:
                    lines = f.read().split()
                if len(lines) >= 2:
                    url = "ws://127.0.0.1:%s%s" % (int(lines[0]), lines[1])
                    conn = CDPConn(WSTransport(url))
                    self._adopt(conn, "port", conn.call("Browser.getVersion"))
                    log("cdp over loopback port (UNAUTHED while open)")
                    return
            except (OSError, ValueError, CDPError):
                pass
            time.sleep(0.3)
        raise VerbError("not_ready", "no DevToolsActivePort after 30 s")

    def _attach(self, port):
        try:
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            c.request("GET", "/json/version")
            v = json.loads(c.getresponse().read().decode("utf-8"))
            c.close()
            conn = CDPConn(WSTransport(v["webSocketDebuggerUrl"]))
        except (OSError, ValueError, KeyError, CDPError, http.client.HTTPException) as e:
            raise VerbError("not_ready", "no DevTools on 127.0.0.1:%d (%s)" % (port, e))
        self._adopt(conn, "attach", conn.call("Browser.getVersion"))

    def _adopt(self, conn, mode, version):
        self.conn, self.mode, self.version = conn, mode, version
        self.sessions, self.snaps = {}, {}
        conn.on("Target.detachedFromTarget", None, self._detached)
        conn.on_close = self._closed
        self.last_error = None

    def _detached(self, p, _sid):
        with self.lock:
            for tid, sid in list(self.sessions.items()):
                if sid == p.get("sessionId"):
                    self.sessions.pop(tid, None)

    def _closed(self):
        if self.stopping or not self.owned:
            return
        threading.Thread(target=self._watchdog, name="browser-restart", daemon=True).start()

    def _watchdog(self):
        """Restart with backoff (3 s, at most 5 per minute) after the browser or its CDP link dies."""
        time.sleep(3)
        now = time.time()
        self.restarts = [t for t in self.restarts if now - t < 60]
        if self.stopping or self.ready():
            return
        if len(self.restarts) >= 5:
            log("browser restart limit reached (5/min); use browser.restart")
            return
        self.restarts.append(now)
        try:
            self.restart()
        except VerbError as e:
            log("browser restart failed: %s" % e.detail[:160])

    def _kill(self):
        p, self.proc = self.proc, None
        if p and p.poll() is None:
            try:
                os.killpg(p.pid, signal.SIGTERM)
            except OSError:
                pass
            try:
                p.wait(5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(p.pid, signal.SIGKILL)
                except OSError:
                    pass
                p.wait(5)

    def restart(self):
        with self.lock:
            self.stopping = True
            try:
                if self.conn:
                    self.conn.close()
                self._kill()
            finally:
                self.stopping = False
            self.conn = None
            self.start()

    def stop(self):
        self.stopping = True
        if self.conn:
            self.conn.close()
        self._kill()

    # --- tabs ---
    def _c(self):
        if not self.ready():
            raise VerbError("not_ready", "the browser is not running" + (": " + self.last_error if self.last_error else ""))
        return self.conn

    def pages(self):
        r = self._c().call("Target.getTargets")
        return [t for t in r.get("targetInfos", []) if t.get("type") == "page"]

    def resolve_tab(self, tab=None):
        pages = self.pages()
        if not pages:
            raise VerbError("not_ready", "no open page in the browser")
        if tab is not None and tab != "":
            if isinstance(tab, int) or (isinstance(tab, str) and tab.isdigit() and len(tab) < 4):
                i = int(tab)
                if 0 <= i < len(pages):
                    return pages[i]
                raise VerbError("bad_args", "tab index %d out of range (0..%d)" % (i, len(pages) - 1))
            for p in pages:
                if p["targetId"] == tab or p["targetId"].startswith(str(tab)):
                    return p
            raise VerbError("bad_args", "no tab %s" % tab)
        for p in pages:
            if p["targetId"] == self.current:
                return p
        return pages[0]

    def session(self, tid):
        with self.lock:
            sid = self.sessions.get(tid)
            if sid:
                return sid
            try:
                sid = self._c().call("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
            except CDPError as e:
                raise VerbError("not_ready", "cannot attach to tab %s: %s" % (tid[:8], e))
            self.sessions[tid] = sid
            return sid

    def call(self, tid, method, params=None, timeout=30.0):
        for attempt in (0, 1):
            sid = self.session(tid)
            try:
                return self._c().call(method, params, session=sid, timeout=timeout)
            except CDPError as e:
                if attempt == 0 and "session" in str(e).lower() and "not found" in str(e).lower():
                    with self.lock:
                        self.sessions.pop(tid, None)
                    continue
                raise

    def eval(self, tid, js, await_promise=True, timeout=30.0):
        r = self.call(tid, "Runtime.evaluate", {
            "expression": js, "returnByValue": True, "awaitPromise": await_promise,
            "userGesture": True, "timeout": int(timeout * 1000)}, timeout=timeout + 2)
        if "exceptionDetails" in r:
            d = r["exceptionDetails"]
            ex = d.get("exception", {})
            raise VerbError("js_error", (ex.get("description") or d.get("text") or "exception")[:500])
        return r.get("result", {}).get("value")

    def loader_id(self, tid):
        try:
            return self.call(tid, "Page.getFrameTree", timeout=10)["frameTree"]["frame"].get("loaderId")
        except (CDPError, KeyError, VerbError):
            return None


B = Browser()


# ----------------------------------------------------------------------------------------------
# Page-side JavaScript helpers (moved unchanged from genie-daemon.py v1)

_INTERACTIVE_SEL = ("a[href],button,input:not([type=hidden]),select,textarea,summary,label[for],"
                    "[role=button],[role=link],[role=checkbox],[role=radio],[role=switch],[role=tab],"
                    "[role=menuitem],[role=option],[role=textbox],[role=searchbox],[role=combobox],"
                    "[role=slider],[contenteditable=''],[contenteditable=true],[onclick],[tabindex]")

_JS_PRELUDE = r"""
(function(){
 if (window.__ld) return;
 const map = new Map(), ids = new WeakMap(); let seq = 0;
 const SEL = %s;
 function refOf(el){ let r = ids.get(el); if (!r){ r = 'd' + (++seq); ids.set(el, r); map.set(r, new WeakRef(el)); } return r; }
 function byRef(r){ const w = map.get(r); return w ? w.deref() || null : null; }
 function label(el){
   const a = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || el.getAttribute('alt'));
   let t = a || ((el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') ? (el.value || '') : '') || el.innerText || el.textContent || '';
   return String(t).replace(/\s+/g, ' ').trim();
 }
 function visible(el){
   const r = el.getBoundingClientRect(); if (r.width < 1 || r.height < 1) return null;
   const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0') return null;
   return r;
 }
 function implicitRole(el){
   const t = el.tagName.toLowerCase(), ty = (el.getAttribute('type') || '').toLowerCase();
   if (el.getAttribute('role')) return el.getAttribute('role');
   if (t === 'a') return 'link'; if (t === 'button' || t === 'summary') return 'button';
   if (t === 'select') return 'combobox'; if (t === 'textarea') return 'textbox';
   if (t === 'input') return ({checkbox:'checkbox', radio:'radio', submit:'button', button:'button', reset:'button', range:'slider', search:'searchbox'})[ty] || 'textbox';
   if (el.isContentEditable) return 'textbox';
   return t;
 }
 function find(spec){
   if (spec.ref) return byRef(spec.ref);
   if (spec.selector) return document.querySelector(spec.selector);
   if (spec.text != null){
     const want = String(spec.text).replace(/\s+/g, ' ').trim().toLowerCase(); if (!want) return null;
     let best = null, bestScore = -1e9;
     for (const el of document.querySelectorAll('body *')){
       const tc = (el.textContent || '').toLowerCase();
       const attrs = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('alt') || '') + ' ' + (el.value || '')).toLowerCase();
       if (!tc.includes(want) && !attrs.includes(want)) continue;
       const r = visible(el); if (!r) continue;
       const l = label(el).toLowerCase(); if (!l.includes(want) && !attrs.includes(want)) continue;
       let s = 0; if (l === want) s += 4; if (el.matches(SEL)) s += 3; if (spec.exact && l !== want) continue;
       s -= Math.log10(r.width * r.height + 10) / 10;
       if (s > bestScore){ bestScore = s; best = el; }
     }
     return best;
   }
   return null;
 }
 function center(el){
   el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
   const r = el.getBoundingClientRect();
   return {x: r.x + r.width / 2, y: r.y + r.height / 2, box: [r.x, r.y, r.width, r.height], ref: refOf(el), role: implicitRole(el), name: label(el).slice(0, 120)};
 }
 function domTree(interactiveOnly, max){
   const out = []; let truncated = false;
   const els = document.querySelectorAll(interactiveOnly ? SEL : 'body *');
   for (const el of els){
     if (!interactiveOnly && !el.matches(SEL)){
       let direct = false; for (const c of el.childNodes) if (c.nodeType === 3 && c.textContent.trim()){ direct = true; break; }
       if (!direct) continue;
     }
     const r = visible(el); if (!r) continue;
     if (out.length >= max){ truncated = true; break; }
     const n = {ref: refOf(el), role: implicitRole(el), name: label(el).slice(0, 120), tag: el.tagName.toLowerCase(), box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]};
     if (el.href) n.href = String(el.href).slice(0, 300);
     if ('value' in el && el.tagName !== 'BUTTON' && el.type !== 'password' && el.value) n.value = String(el.value).slice(0, 200);
     if (el.disabled) n.disabled = true;
     if (el.checked) n.checked = true;
     if (document.activeElement === el) n.focused = true;
     out.push(n);
   }
   return {nodes: out, truncated: truncated};
 }
 function metrics(){
   return {sx: window.screenX, sy: window.screenY, ow: window.outerWidth, oh: window.outerHeight,
           iw: window.innerWidth, ih: window.innerHeight, dpr: window.devicePixelRatio,
           scrollX: window.scrollX, scrollY: window.scrollY};
 }
 window.__ld = {find: find, center: center, domTree: domTree, label: label, metrics: metrics, refOf: refOf, byRef: byRef};
})();
""" % json.dumps(_INTERACTIVE_SEL)


def js_call(tid, body, timeout=30.0):
    return B.eval(tid, _JS_PRELUDE + "\n;(" + body + ")", timeout=timeout)


def metrics(tid):
    return js_call(tid, "window.__ld.metrics()", timeout=10) or {}


def xbox_of(box, m, win=None):
    """Spec 02 #6: xbox = (cssX*dpr + winX, cssY*dpr + toolbar_px + winY), toolbar_px = (outerH - innerH)*dpr.
    `win` = (x, y) of the browser window in X px (xdotool); without it, window.screenX/Y * dpr."""
    dpr = m.get("dpr") or 1
    toolbar = max(0, (m.get("oh") or 0) - (m.get("ih") or 0)) * dpr
    side = max(0, (m.get("ow") or 0) - (m.get("iw") or 0)) * dpr / 2.0
    wx, wy = win if win else ((m.get("sx") or 0) * dpr, (m.get("sy") or 0) * dpr)
    return [int(round(box[0] * dpr + wx + side)), int(round(box[1] * dpr + toolbar + wy)),
            int(round(box[2] * dpr)), int(round(box[3] * dpr))]


# ----------------------------------------------------------------------------------------------
# Verbs

def _wait_loaded(tid, timeout_s, prev_url=None):
    t0 = time.time()
    deadline = t0 + timeout_s
    state = None
    time.sleep(0.15)
    while time.time() < deadline:
        try:
            v = B.eval(tid, "[document.readyState, location.href, document.title]", timeout=5)
            state = v
            fresh = prev_url is None or v[1] != prev_url or time.time() - t0 > 1.5
            if v and v[0] == "complete" and fresh:
                return {"loaded": True, "url": v[1], "title": v[2]}
        except (VerbError, CDPError) as e:
            if isinstance(e, VerbError) and e.code not in ("timeout", "js_error", "not_ready"):
                raise
        time.sleep(0.25)
    out = {"loaded": False}
    if state:
        out.update({"url": state[1], "title": state[2], "ready_state": state[0]})
    return out


def _norm_url(url):
    u = str(url or "").strip()
    if not u:
        raise VerbError("bad_args", "url is required")
    if "://" in u or u.startswith(("about:", "chrome:", "data:", "file:", "javascript:")):
        return u
    return "https://" + u


def v_tabs(a):
    pages = B.pages()
    tabs = []
    for i, p in enumerate(pages):
        tabs.append({"tab": p["targetId"], "title": p.get("title", ""), "url": p.get("url", ""), "index": i,
                     "active": p["targetId"] == B.current or (B.current is None and i == 0)})
    return {"ok": True, "tabs": tabs}


def v_open(a):
    url = _norm_url(a.get("url"))
    timeout_s = num(a, "timeout_s", 30, 1, 120)
    prev = None
    if a.get("new_tab"):
        tid = B._c().call("Target.createTarget", {"url": url})["targetId"]
        try:
            B._c().call("Target.activateTarget", {"targetId": tid})
        except CDPError:
            pass
    else:
        t = B.resolve_tab(a.get("tab"))
        tid, prev = t["targetId"], t.get("url")
        r = B.call(tid, "Page.navigate", {"url": url}, timeout=timeout_s)
        if r.get("errorText"):
            raise VerbError("nav_failed", r["errorText"])
    B.current = tid
    B.snaps.pop(tid, None)
    out = {"ok": True, "tab": tid, "url": url, "title": ""}
    if a.get("wait", True):
        out.update(_wait_loaded(tid, timeout_s, prev))
    return out


def v_navigate(a):
    t = B.resolve_tab(a.get("tab"))
    tid = t["targetId"]
    B.current = tid
    timeout_s = num(a, "timeout_s", 30, 1, 120)
    if a.get("url"):
        r = B.call(tid, "Page.navigate", {"url": _norm_url(a["url"])}, timeout=timeout_s)
        if r.get("errorText"):
            raise VerbError("nav_failed", r["errorText"])
    elif a.get("reload"):
        B.call(tid, "Page.reload", {"ignoreCache": bool(a.get("hard", False))})
        time.sleep(0.3)
    elif a.get("back") or a.get("forward"):
        h = B.call(tid, "Page.getNavigationHistory")
        i = h["currentIndex"] + (-1 if a.get("back") else 1)
        if not 0 <= i < len(h["entries"]):
            raise VerbError("bad_args", "nothing to go %s to" % ("back" if a.get("back") else "forward"))
        B.call(tid, "Page.navigateToHistoryEntry", {"entryId": h["entries"][i]["id"]})
        time.sleep(0.3)
    else:
        raise VerbError("bad_args", "one of url / back / forward / reload is required")
    B.snaps.pop(tid, None)
    out = {"ok": True, "tab": tid}
    if a.get("wait", True):
        out.update(_wait_loaded(tid, timeout_s, t.get("url") if a.get("url") else None))
    return out


def v_activate(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    B._c().call("Target.activateTarget", {"targetId": tid})
    B.current = tid
    return {"ok": True, "tab": tid}


def v_close(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    B._c().call("Target.closeTarget", {"targetId": tid})
    with B.lock:
        B.sessions.pop(tid, None)
    B.snaps.pop(tid, None)
    if B.current == tid:
        B.current = None
    return {"ok": True, "tab": tid}


def v_restart(a):
    if not B.owned:
        raise VerbError("unsupported", "deskd does not own the browser (attach mode)")
    B.restart()
    return {"ok": True, "mode": B.mode, "browser": (B.version or {}).get("product")}


_AX_INTERACTIVE = {
    "button", "link", "textbox", "searchbox", "combobox", "checkbox", "radio", "switch", "menuitem",
    "menuitemcheckbox", "menuitemradio", "tab", "option", "slider", "spinbutton", "listbox", "treeitem",
    "textField", "TextField", "PopUpButton", "ComboBoxGrouping", "ComboBoxMenuButton", "disclosureTriangle",
}
_AX_SKIP = {"none", "generic", "InlineTextBox", "LineBreak", "presentation", "ignored", "LayoutTable",
            "LayoutTableRow", "LayoutTableCell"}
_AX_PROPS = ("focused", "disabled", "checked", "expanded", "selected", "pressed", "required", "readonly")


def _boxes_by_backend(tid):
    snap = B.call(tid, "DOMSnapshot.captureSnapshot", {"computedStyles": []}, timeout=30)
    docs = snap.get("documents") or []
    if not docs:
        return {}
    d = docs[0]
    be = d["nodes"].get("backendNodeId") or []
    lay = d.get("layout") or {}
    sx, sy = d.get("scrollOffsetX", 0) or 0, d.get("scrollOffsetY", 0) or 0
    out = {}
    for idx, b in zip(lay.get("nodeIndex") or [], lay.get("bounds") or []):
        if idx < len(be) and len(b) == 4:
            out[be[idx]] = [round(b[0] - sx), round(b[1] - sy), round(b[2]), round(b[3])]
    return out


def _ax_nodes(tid, interactive_only, max_nodes, with_boxes):
    r = B.call(tid, "Accessibility.getFullAXTree", {}, timeout=45)
    raw = r.get("nodes") or []
    parent = {n["nodeId"]: n.get("parentId") for n in raw}

    def depth(nid):
        k, d = nid, 0
        while parent.get(k) and d < 200:
            k, d = parent[k], d + 1
        return d

    boxes = _boxes_by_backend(tid) if with_boxes else {}
    nodes, truncated = [], False
    for n in raw:
        if n.get("ignored"):
            continue
        role = (n.get("role") or {}).get("value") or ""
        name = str((n.get("name") or {}).get("value") or "").strip()
        be = n.get("backendDOMNodeId")
        props = {}
        for p in n.get("properties") or []:
            props[p.get("name")] = (p.get("value") or {}).get("value")
        interactive = role in _AX_INTERACTIVE or props.get("focusable") is True or props.get("editable") not in (None, "", False)
        if interactive_only and not interactive:
            continue
        if not interactive and (role in _AX_SKIP or (not name and role not in ("heading", "img", "image"))):
            continue
        if be is None or role == "RootWebArea":
            continue
        box = boxes.get(be)
        if with_boxes and interactive_only and (box is None or box[2] < 1 or box[3] < 1):
            continue
        node = {"ref": "n%d" % be, "role": role, "name": name[:120], "depth": depth(n["nodeId"])}
        val = (n.get("value") or {}).get("value")
        if val not in (None, "") and props.get("protected") is not True:
            node["value"] = str(val)[:200]
        for k in _AX_PROPS:
            if props.get(k) not in (None, False, "false"):
                node[k] = props[k]
        if box is not None:
            node["box"] = box
        if len(nodes) >= max_nodes:
            truncated = True
            break
        nodes.append(node)
    return nodes, truncated


XBOX_HOOK = None      # set by deskd: fn(tid, m) -> ((winX, winY) or None, calibrated: bool)


def v_tree(a):
    t = B.resolve_tab(a.get("tab"))
    tid = t["targetId"]
    B.current = tid
    mode = a.get("mode", "ax")
    interactive_only = bool(a.get("interactive_only", True))
    max_nodes = num(a, "max_nodes", 400, 1, 5000, int)
    with_boxes = bool(a.get("with_boxes", True))
    t0 = time.time()
    if mode == "ax":
        nodes, truncated = _ax_nodes(tid, interactive_only, max_nodes, with_boxes)
    elif mode == "dom":
        r = js_call(tid, "window.__ld.domTree(%s, %d)" % ("true" if interactive_only else "false", max_nodes))
        nodes, truncated = r["nodes"], r["truncated"]
    else:
        raise VerbError("bad_args", "mode must be ax or dom")
    m = metrics(tid)
    calibrated = False
    if with_boxes:
        win, calibrated = XBOX_HOOK(tid, m) if XBOX_HOOK else (None, False)
        for n in nodes:
            if "box" in n:
                n["xbox"] = xbox_of(n["box"], m, win)
    page = B.eval(tid, "[location.href, document.title]", timeout=10) or ["", ""]
    st = B.snaps.setdefault(tid, {"seq": 0})
    st["seq"] += 1
    st["snap"] = "s%d" % st["seq"]
    st["loader"] = B.loader_id(tid)
    st["names"] = {n["ref"]: (n.get("role"), n.get("name")) for n in nodes}
    return {"ok": True, "snap": st["snap"], "tab": tid, "mode": mode, "url": page[0], "title": page[1],
            "viewport": {"w": m.get("iw"), "h": m.get("ih"), "dpr": m.get("dpr"),
                         "scroll": [m.get("scrollX"), m.get("scrollY")]},
            "xbox_calibrated": bool(calibrated), "count": len(nodes), "truncated": truncated,
            "took_ms": int((time.time() - t0) * 1000), "nodes": nodes}


def v_find(a):
    if a.get("selector"):
        tid = B.resolve_tab(a.get("tab"))["targetId"]
        r = js_call(tid, "(() => { const out = []; for (const el of document.querySelectorAll(%s)) {"
                         " const c = el.getBoundingClientRect(); out.push({ref: window.__ld.refOf(el), role: el.tagName.toLowerCase(),"
                         " name: window.__ld.label(el).slice(0,120), box: [Math.round(c.x), Math.round(c.y), Math.round(c.width), Math.round(c.height)]});"
                         " if (out.length >= %d) break; } return out; })()" % (
                             json.dumps(a["selector"]), num(a, "max_nodes", 50, 1, 500, int)))
        return {"ok": True, "snap": None, "tab": tid, "nodes": r}
    snap = v_tree(dict(a, interactive_only=a.get("interactive_only", False), max_nodes=a.get("max_nodes", 3000)))
    text = str(a.get("text") or a.get("name") or "").strip().lower()
    role = a.get("role")
    hits = []
    for n in snap["nodes"]:
        if role and n.get("role") != role:
            continue
        if text and text not in (n.get("name", "") + " " + str(n.get("value", ""))).lower():
            continue
        hits.append(n)
    return {"ok": True, "snap": snap["snap"], "tab": snap["tab"], "url": snap["url"], "count": len(hits),
            "nodes": hits[:num(a, "limit", 50, 1, 1000, int)]}


def _ref_of(a):
    ref = a.get("ref", a.get("node_id"))
    if ref is None:
        return None
    ref = str(ref)
    return "n" + ref if ref.isdigit() else ref


def _check_snap(tid, a):
    snap = a.get("snap")
    if not snap:
        return
    st = B.snaps.get(tid)
    if not st or st.get("snap") != snap:
        raise VerbError("stale_ref", "snapshot %s is not the latest for this tab; take a new tree" % snap)
    if st.get("loader") and B.loader_id(tid) not in (None, st["loader"]):
        raise VerbError("stale_ref", "the page navigated since snapshot %s" % snap)


def _locate(tid, a):
    ref = _ref_of(a)
    if ref and ref.startswith("n"):
        _check_snap(tid, a)
        try:
            be = int(ref[1:])
        except ValueError:
            raise VerbError("bad_args", "bad ref %s" % ref)
        try:
            try:
                B.call(tid, "DOM.scrollIntoViewIfNeeded", {"backendNodeId": be})
            except CDPError:
                pass
            q = B.call(tid, "DOM.getContentQuads", {"backendNodeId": be}).get("quads") or []
        except CDPError as e:
            raise VerbError("stale_ref", "%s: %s (take a new tree)" % (ref, e))
        if not q:
            raise VerbError("not_found", "%s has no layout box" % ref)
        xs, ys = q[0][0::2], q[0][1::2]
        role, name = (B.snaps.get(tid) or {}).get("names", {}).get(ref, (None, None))
        return {"x": sum(xs) / 4.0, "y": sum(ys) / 4.0, "ref": ref, "role": role, "name": name}
    spec = {}
    if ref and ref.startswith("d"):
        spec["ref"] = ref
    elif a.get("selector"):
        spec["selector"] = a["selector"]
    elif a.get("text") is not None:
        spec["text"] = a["text"]
        spec["exact"] = bool(a.get("exact", False))
    else:
        return None
    r = js_call(tid, "(() => { const el = window.__ld.find(%s); return el ? window.__ld.center(el) : null; })()" % json.dumps(spec))
    if not r:
        raise VerbError("stale_ref" if "ref" in spec else "not_found", "no element matches")
    return r


_BUTTONS = {"left": 1, "right": 2, "middle": 4}


def _mouse_click(tid, x, y, button="left", count=1):
    mask = _BUTTONS.get(button, 1)
    B.call(tid, "Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x, "y": y})
    for i in range(1, count + 1):
        for typ in ("mousePressed", "mouseReleased"):
            B.call(tid, "Input.dispatchMouseEvent", {
                "type": typ, "x": x, "y": y, "button": button, "buttons": mask if typ == "mousePressed" else 0,
                "clickCount": i})


def v_click(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    B.current = tid
    button = {"1": "left", "2": "middle", "3": "right"}.get(str(a.get("button", "left")), str(a.get("button", "left")))
    if button not in _BUTTONS:
        raise VerbError("bad_args", "button must be 1/2/3 or left/middle/right")
    count = num(a, "count", 1, 1, 3, int)
    loc = _locate(tid, a)
    if loc is None:
        if a.get("x") is None or a.get("y") is None:
            raise VerbError("bad_args", "give one of ref, selector, text, or x+y (CSS px)")
        loc = {"x": num(a, "x"), "y": num(a, "y")}
    _mouse_click(tid, loc["x"], loc["y"], button, count)
    clicked = {k: loc.get(k) for k in ("ref", "role", "name")}
    clicked["x"], clicked["y"] = round(loc["x"], 1), round(loc["y"], 1)
    return {"ok": True, "tab": tid, "clicked": clicked}


def v_type(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    B.current = tid
    text = a.get("text")
    if text is None:
        raise VerbError("bad_args", "text is required")
    text = str(text)
    if len(text) > 100000:
        raise VerbError("bad_args", "text longer than 100000 chars")
    ref = _ref_of(a)
    if ref and ref.startswith("n"):
        _check_snap(tid, a)
        try:
            B.call(tid, "DOM.focus", {"backendNodeId": int(ref[1:])})
        except CDPError:
            loc = _locate(tid, a)
            _mouse_click(tid, loc["x"], loc["y"])
    elif ref or a.get("selector") or a.get("target_text") is not None:
        spec = {"ref": ref} if ref else ({"selector": a["selector"]} if a.get("selector") else {"text": a["target_text"]})
        ok = js_call(tid, "(() => { const el = window.__ld.find(%s); if (!el) return false;"
                          " el.scrollIntoView({block:'center'}); el.focus(); return true; })()" % json.dumps(spec))
        if not ok:
            raise VerbError("not_found", "no element to type into")
    if a.get("clear"):
        js_call(tid, r"""(() => { const el = document.activeElement; if (!el) return false;
          if ('value' in el && el.tagName !== 'BUTTON') {
            const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
            if (d && d.set) d.set.call(el, ''); else el.value = '';
            el.dispatchEvent(new Event('input', {bubbles: true})); return true; }
          if (el.isContentEditable) { document.execCommand('selectAll'); document.execCommand('delete'); return true; }
          return false; })()""")
    delay = (num(a, "delay_ms", 0, 0, 1000) or 0) / 1000.0
    if delay > 0:
        for ch in text:
            B.call(tid, "Input.insertText", {"text": ch})
            time.sleep(delay)
    elif text:
        B.call(tid, "Input.insertText", {"text": text})
    if a.get("submit"):
        _press(tid, "Enter")
    return {"ok": True, "tab": tid, "chars": len(text)}


_KEYS = {
    "enter": ("Enter", "Enter", 13, "\r"), "return": ("Enter", "Enter", 13, "\r"),
    "tab": ("Tab", "Tab", 9, None), "backspace": ("Backspace", "Backspace", 8, None),
    "delete": ("Delete", "Delete", 46, None), "del": ("Delete", "Delete", 46, None),
    "escape": ("Escape", "Escape", 27, None), "esc": ("Escape", "Escape", 27, None),
    "arrowup": ("ArrowUp", "ArrowUp", 38, None), "up": ("ArrowUp", "ArrowUp", 38, None),
    "arrowdown": ("ArrowDown", "ArrowDown", 40, None), "down": ("ArrowDown", "ArrowDown", 40, None),
    "arrowleft": ("ArrowLeft", "ArrowLeft", 37, None), "left": ("ArrowLeft", "ArrowLeft", 37, None),
    "arrowright": ("ArrowRight", "ArrowRight", 39, None), "right": ("ArrowRight", "ArrowRight", 39, None),
    "home": ("Home", "Home", 36, None), "end": ("End", "End", 35, None),
    "pageup": ("PageUp", "PageUp", 33, None), "prior": ("PageUp", "PageUp", 33, None),
    "pagedown": ("PageDown", "PageDown", 34, None), "next": ("PageDown", "PageDown", 34, None),
    "space": (" ", "Space", 32, " "), "insert": ("Insert", "Insert", 45, None),
}
for _i in range(1, 13):
    _KEYS["f%d" % _i] = ("F%d" % _i, "F%d" % _i, 111 + _i, None)
_MODS = {
    "alt": ("Alt", "AltLeft", 18, 1), "ctrl": ("Control", "ControlLeft", 17, 2),
    "control": ("Control", "ControlLeft", 17, 2), "meta": ("Meta", "MetaLeft", 91, 4),
    "cmd": ("Meta", "MetaLeft", 91, 4), "super": ("Meta", "MetaLeft", 91, 4),
    "shift": ("Shift", "ShiftLeft", 16, 8),
}
_EDIT_COMMANDS = {("a", 2): "selectAll", ("c", 2): "copy", ("v", 2): "paste", ("x", 2): "cut",
                  ("z", 2): "undo", ("z", 10): "redo", ("y", 2): "redo"}


def _key_def(name, shift=False):
    k = _KEYS.get(name.lower())
    if k:
        return k
    if len(name) == 1:
        c = name
        if c.isalpha() and c.isascii():
            up = c.upper()
            ch = up if shift else c.lower()
            return (ch, "Key" + up, ord(up), ch)
        if c.isdigit():
            return (c, "Digit" + c, ord(c), c)
        return (c, "", 0, c)
    raise VerbError("bad_args", "unknown key %r" % name)


def _press(tid, combo):
    parts = [p for p in combo.replace("-", "+").split("+") if p] if len(combo) > 1 else [combo]
    mods, bits = [], 0
    for p in parts[:-1]:
        m = _MODS.get(p.lower())
        if not m:
            raise VerbError("bad_args", "unknown modifier %r in %r" % (p, combo))
        mods.append(m)
        bits |= m[3]
    key, code, vk, text = _key_def(parts[-1], shift=bool(bits & 8))
    for m in mods:
        B.call(tid, "Input.dispatchKeyEvent", {"type": "rawKeyDown", "key": m[0], "code": m[1],
                                               "windowsVirtualKeyCode": m[2], "modifiers": bits})
    down = {"type": "keyDown" if (text and not bits & 7) else "rawKeyDown", "key": key, "code": code,
            "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk, "modifiers": bits}
    if text and not bits & 7:
        down["text"] = text
        down["unmodifiedText"] = text
    cmd = _EDIT_COMMANDS.get((key.lower(), bits))
    if cmd:
        down["commands"] = [cmd]
    B.call(tid, "Input.dispatchKeyEvent", down)
    B.call(tid, "Input.dispatchKeyEvent", {"type": "keyUp", "key": key, "code": code, "windowsVirtualKeyCode": vk,
                                           "nativeVirtualKeyCode": vk, "modifiers": bits})
    for m in reversed(mods):
        B.call(tid, "Input.dispatchKeyEvent", {"type": "keyUp", "key": m[0], "code": m[1],
                                               "windowsVirtualKeyCode": m[2], "modifiers": 0})


def v_key(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    keys = str(a.get("keys") or a.get("key") or "").strip()
    if not keys:
        raise VerbError("bad_args", "keys is required, e.g. \"Enter\", \"Control+A\", \"Tab Tab Enter\"")
    repeat = num(a, "repeat", 1, 1, 100, int)
    seq = keys.split() if keys != " " else [" "]
    for _ in range(repeat):
        for combo in seq:
            _press(tid, combo)
    return {"ok": True, "tab": tid, "pressed": len(seq) * repeat}


def v_scroll(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    if a.get("ref") or a.get("node_id") or a.get("selector") or a.get("text") is not None:
        loc = _locate(tid, a)
        if a.get("dy") is None and a.get("dx") is None:
            return {"ok": True, "tab": tid, "scrolled_to": loc.get("ref")}
    if a.get("to") in ("top", "bottom"):
        B.eval(tid, "window.scrollTo(0, %s)" % ("0" if a["to"] == "top" else "document.documentElement.scrollHeight"))
        return {"ok": True, "tab": tid}
    m = metrics(tid)
    x = num(a, "x", (m.get("iw") or 400) / 2.0)
    y = num(a, "y", (m.get("ih") or 400) / 2.0)
    B.call(tid, "Input.dispatchMouseEvent", {"type": "mouseWheel", "x": x, "y": y,
                                             "deltaX": num(a, "dx", 0), "deltaY": num(a, "dy", 400)})
    time.sleep(0.15)
    return {"ok": True, "tab": tid, "scroll": B.eval(tid, "[window.scrollX, window.scrollY]", timeout=5)}


def v_eval(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    js = a.get("js", a.get("expression"))
    if not js:
        raise VerbError("bad_args", "js is required")
    t = num(a, "timeout_s", 30, 1, 120)
    return {"ok": True, "tab": tid, "value": B.eval(tid, str(js), bool(a.get("await", True)), t)}


def v_wait(a):
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    timeout_s = num(a, "timeout_s", 10, 0, 120)
    if a.get("text") is not None:
        js = "(document.body && document.body.innerText || '').toLowerCase().includes(%s)" % json.dumps(str(a["text"]).lower())
    elif a.get("selector"):
        js = "!!document.querySelector(%s)" % json.dumps(a["selector"])
    elif a.get("url_contains"):
        js = "location.href.includes(%s)" % json.dumps(a["url_contains"])
    elif a.get("load"):
        js = "document.readyState === 'complete'"
    else:
        raise VerbError("bad_args", "one of text / selector / url_contains / load is required")
    t0 = time.time()
    while True:
        try:
            if B.eval(tid, js, timeout=5):
                return {"ok": True, "found": True, "waited_ms": int((time.time() - t0) * 1000)}
        except VerbError as e:
            if e.code not in ("js_error", "timeout"):
                raise
        if time.time() - t0 >= timeout_s:
            return {"ok": True, "found": False, "waited_ms": int((time.time() - t0) * 1000)}
        time.sleep(0.3)


def capture(a, fmt="jpeg", quality=60, scale=0.5, region=None):
    """Viewport screenshot via Page.captureScreenshot -> (bytes, w, h, mime). region = [x,y,w,h] in CSS px."""
    tid = B.resolve_tab(a.get("tab"))["targetId"]
    p = {"format": fmt}
    if fmt == "jpeg":
        p["quality"] = int(quality)
    if region or scale != 1:
        if region:
            clip = {"x": region[0], "y": region[1], "width": region[2], "height": region[3]}
        else:
            lm = B.call(tid, "Page.getLayoutMetrics")
            vv = lm.get("cssVisualViewport") or lm.get("visualViewport") or {}
            clip = {"x": vv.get("pageX", 0), "y": vv.get("pageY", 0),
                    "width": vv.get("clientWidth", 800), "height": vv.get("clientHeight", 800)}
        clip["scale"] = scale
        p["clip"] = clip
    raw = base64.b64decode(B.call(tid, "Page.captureScreenshot", p, timeout=30).get("data", ""))
    w, h = image_size(raw)
    return raw, w, h, "image/" + fmt


class Screencast(object):
    """CDP Page.startScreencast; Chromium pushes JPEG frames, deskd acks each one at once and lets the stream's
    own window (4 unacked, newest wins) decide what reaches the genie."""

    def __init__(self, a, on_frame, fmt="jpeg", quality=60, scale=0.5):
        self.tid = B.resolve_tab(a.get("tab"))["targetId"]
        self.sid = B.session(self.tid)
        self.on_frame = on_frame
        m = metrics(self.tid)
        dpr = m.get("dpr") or 1
        p = {"format": fmt, "quality": int(quality), "everyNthFrame": 1,
             "maxWidth": max(64, int((m.get("iw") or 800) * dpr * scale)),
             "maxHeight": max(64, int((m.get("ih") or 800) * dpr * scale))}
        self.mime = "image/" + fmt
        B._c().on("Page.screencastFrame", self.sid, self._frame)
        B.call(self.tid, "Page.startScreencast", p)

    def _frame(self, p, _sid):
        try:
            B._c().call("Page.screencastFrameAck", {"sessionId": p.get("sessionId")}, session=self.sid, timeout=5)
        except (VerbError, CDPError):
            pass
        raw = base64.b64decode(p.get("data", ""))
        w, h = image_size(raw)
        self.on_frame(raw, w, h, self.mime)

    def stop(self):
        try:
            B._c().off("Page.screencastFrame", self.sid, self._frame)
            B.call(self.tid, "Page.stopScreencast", timeout=5)
        except (VerbError, CDPError):
            pass


def v_screenshot_legacy(a):
    """chrome.screenshot (v1 genie-daemon shape): JSON with data_b64. New clients use screen.capture source=browser."""
    fmt = str(a.get("format", "png")).lower()
    if fmt not in ("png", "jpeg"):
        raise VerbError("bad_args", "format must be png or jpeg")
    raw, w, h, mime = capture(a, fmt, num(a, "quality", 70, 1, 100, int), num(a, "scale", 1, 0.1, 2))
    return {"ok": True, "mime": mime, "bytes": len(raw), "w": w, "h": h, "data_b64": base64.b64encode(raw).decode()}


VERBS = {
    "browser.tabs": v_tabs, "browser.open": v_open, "browser.navigate": v_navigate,
    "browser.activate": v_activate, "browser.close": v_close, "browser.tree": v_tree, "browser.find": v_find,
    "browser.click": v_click, "browser.type": v_type, "browser.key": v_key, "browser.scroll": v_scroll,
    "browser.eval": v_eval, "browser.wait": v_wait, "browser.restart": v_restart,
}
# The v1 genie-daemon verb names keep working for old clients.
LEGACY = {"chrome.tabs": "browser.tabs", "chrome.open": "browser.open", "chrome.navigate": "browser.navigate",
          "chrome.activate": "browser.activate", "chrome.close": "browser.close", "chrome.snapshot": "browser.tree",
          "chrome.find": "browser.find", "chrome.click": "browser.click", "chrome.type": "browser.type",
          "chrome.key": "browser.key", "chrome.scroll": "browser.scroll", "chrome.eval": "browser.eval",
          "chrome.wait": "browser.wait"}
VERBS["chrome.screenshot"] = v_screenshot_legacy
