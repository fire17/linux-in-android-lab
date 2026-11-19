"""fake_desk: an in-process fake LinuxDesk phone (the app Router + deskd) for testing genie-client on a Mac.

It speaks the real linuxdesk/1 frames on 127.0.0.1 (random port), with the spec's Binding rules (6-char code,
30-min validity not modelled, single-use, 5 strikes rotate, one genie, constant-time token-hash compare), id
multiplexing with out-of-order replies, type-1 binary frames, the 4-frame stream ack window, and a handful of
verbs per family backed by in-memory state (fs is a dict, the browser is a fake page). Not a deskd replacement:
deskd's own logic is tested against real processes (tests/test_deskd.py).

    fd = FakeDesk(code="ABC234").start()
    ... Conn.tcp("127.0.0.1", fd.port) ...
    fd.stop()
"""
import base64
import hashlib
import hmac
import json
import secrets
import socket
import struct
import threading
import time

ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
JPEG_1PX = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////"
    "////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=")


def _frame_json(obj):
    b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    return struct.pack(">I", len(b) + 1) + b"\x00" + b


def _frame_bin(header, data):
    h = json.dumps(header).encode("utf-8")
    return struct.pack(">I", 3 + len(h) + len(data)) + b"\x01" + struct.pack(">H", len(h)) + h + data


class FakeDesk(object):
    def __init__(self, code=None, key=None):
        self.code = code or "".join(secrets.choice(ALPHABET) for _ in range(6))
        self.key = key                      # optional deskd-style session key
        self.token_hash = None              # sha256 hex of the paired genie's token
        self.genie = None
        self.strikes = 0
        self.fs = {"DCIM/a.jpg": b"\xff\xd8fakejpeg", "Download/big.bin": bytes(range(256)) * 4096}
        self.parts = {}
        self.proposals = {}
        self.audit = []
        self.page = {"url": "about:blank", "title": "", "typed": "", "clicked": None, "nav": 0}
        self.sock = None
        self.port = None
        self.threads = []
        self.stopping = False
        self.log = []                       # what a real app would log: verb + outcome, never args

    # --- lifecycle ---
    def start(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        t = threading.Thread(target=self._accept, daemon=True)
        t.start()
        return self

    def stop(self):
        self.stopping = True
        try:
            self.sock.close()
        except OSError:
            pass

    def forget(self):
        """The in-app 'Forget my genie' tap."""
        self.token_hash = None
        self.genie = None
        self.code = "".join(secrets.choice(ALPHABET) for _ in range(6))

    def _accept(self):
        while not self.stopping:
            try:
                s, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(s,), daemon=True).start()

    # --- per connection ---
    def _serve(self, s):
        ctx = {"sock": s, "wlock": threading.Lock(), "authed": None, "streams": {}, "sid": 0}

        def send(frame):
            with ctx["wlock"]:
                try:
                    s.sendall(frame)
                except OSError:
                    pass
        ctx["send"] = send
        try:
            while True:
                hdr = self._recv(s, 4)
                n = struct.unpack(">I", hdr)[0]
                if n > (1 << 20):
                    send(_frame_json({"error": "too_big"}))
                    return
                try:
                    msg = json.loads(self._recv(s, n).decode("utf-8"))
                except ValueError:
                    send(_frame_json({"error": "bad_json"}))
                    if not ctx["authed"]:
                        return
                    continue
                if not ctx["authed"]:
                    r = self._auth(msg)
                    send(_frame_json(r))
                    if "error" in r:
                        return
                    ctx["authed"] = r.get("_kind", "token")
                    r.pop("_kind", None)
                    continue
                if ctx["authed"] == "token" and self.token_hash is None:
                    send(_frame_json({"error": "unpaired", **({"id": msg["id"]} if "id" in msg else {})}))
                    return
                if "id" in msg and msg.get("cmd") != "stream.ack":
                    threading.Thread(target=self._run, args=(ctx, msg), daemon=True).start()
                else:
                    self._run(ctx, msg)
        except (EOFError, OSError):
            pass
        finally:
            for st in ctx["streams"].values():
                st["stop"].set()
            try:
                s.close()
            except OSError:
                pass

    @staticmethod
    def _recv(s, n):
        buf = bytearray()
        while len(buf) < n:
            c = s.recv(n - len(buf))
            if not c:
                raise EOFError()
            buf += c
        return bytes(buf)

    def _auth(self, msg):
        given = str(msg.get("auth") or "")
        caps = ["sys", "fs", "shell", "input", "screen", "browser", "stream"]
        if self.key and hmac.compare_digest(given.encode(), self.key.encode()):
            return {"ok": True, "app": "deskd", "version": "fake", "caps": caps, "_kind": "key"}
        if self.token_hash and hmac.compare_digest(hashlib.sha256(given.encode()).hexdigest(), self.token_hash):
            return {"ok": True, "app": "linuxdesk", "version": "fake", "genie": self.genie, "caps": caps}
        if self.token_hash:
            return {"error": "bound"}
        if hmac.compare_digest(given.upper().encode(), self.code.encode()):
            tok = secrets.token_urlsafe(32)
            self.token_hash = hashlib.sha256(tok.encode()).hexdigest()
            self.genie = str(msg.get("genie") or "?")
            self.code = None
            return {"ok": True, "paired": True, "token": tok, "app": "linuxdesk", "version": "fake", "caps": caps}
        self.strikes += 1
        if self.strikes >= 5:
            self.strikes = 0
            self.code = "".join(secrets.choice(ALPHABET) for _ in range(6))
        return {"error": "auth"}

    def _run(self, ctx, msg):
        verb = msg.get("cmd", "")
        t0 = time.time()
        fn = getattr(self, "v_" + verb.replace(".", "_"), None)
        if fn is None:
            r = {"error": "unknown_cmd", "detail": verb}
        else:
            try:
                r = fn(msg, ctx)
            except KeyError as e:
                r = {"error": "bad_args", "detail": "missing %s" % e}
        self.log.append((verb, "ok" if (r is None or isinstance(r, tuple) or r.get("ok")) else r.get("error")))
        self.audit.append({"t": t0, "cmd": verb, "ok": r is None or isinstance(r, tuple) or bool(r.get("ok")),
                           "ms": int((time.time() - t0) * 1000)})
        if r is None:
            return
        if isinstance(r, tuple):
            h, data = r
            if "id" in msg:
                h["id"] = msg["id"]
            ctx["send"](_frame_bin(h, data))
            return
        if "id" in msg:
            r["id"] = msg["id"]
        ctx["send"](_frame_json(r))

    # --- verbs: sys ---
    def v_sys_ping(self, m, ctx):
        return {"ok": True, "app": "linuxdesk", "version": "fake", "genie": self.genie,
                "caps": ["sys", "fs", "shell", "input", "screen", "browser", "stream"],
                "session": {"rootfs": True, "packages": True, "x11": True, "browser": True, "deskd": True},
                "x": {"w": 1080, "h": 2400, "dpi": 420, "density": 2.625}}

    def v_sys_audit(self, m, ctx):
        return {"ok": True, "rows": list(self.audit)}

    def v_sys_sleep(self, m, ctx):                  # test helper: a slow verb, for out-of-order replies
        time.sleep(float(m.get("s", 0.5)))
        return {"ok": True, "slept": m.get("s")}

    # --- shell ---
    def v_shell_exec(self, m, ctx):
        argv = m.get("argv") or ["/bin/sh", "-c", m.get("sh", "")]
        who = "root" if m.get("as") == "root" else "desk"
        if argv[:1] == ["whoami"]:
            return {"ok": True, "code": 0, "stdout": who + "\n", "stderr": "", "truncated": False, "took_ms": 1}
        if argv[:1] == ["cat"]:
            data = base64.b64decode(m.get("stdin_b64", "")).decode("utf-8", "replace")
            return {"ok": True, "code": 0, "stdout": data, "stderr": "", "truncated": False, "took_ms": 1}
        if argv[:1] == ["false"]:
            return {"ok": True, "code": 1, "stdout": "", "stderr": "", "truncated": False, "took_ms": 1}
        return {"ok": True, "code": 0, "stdout": " ".join(argv) + "\n", "stderr": "", "truncated": False, "took_ms": 1}

    # --- input ---
    def v_input_click(self, m, ctx):
        return {"ok": True}

    def v_input_type(self, m, ctx):
        self.page["typed"] += m["text"]
        return {"ok": True, "chars": len(m["text"])}

    def v_input_key(self, m, ctx):
        return {"ok": True}

    # --- screen ---
    def v_screen_info(self, m, ctx):
        return {"ok": True, "w": 1080, "h": 2400, "dpi": 420, "density": 2.625,
                "browser_window": {"x": 0, "y": 0, "w": 1080, "h": 2400}, "dpr": 2.625, "capture_ms_last": None}

    def v_screen_capture(self, m, ctx):
        return ({"mime": "image/jpeg", "w": 1, "h": 1, "source": m.get("source", "x11"), "t": time.time()}, JPEG_1PX)

    # --- browser ---
    def v_browser_open(self, m, ctx):
        self.page.update(url=m["url"] if "://" in m["url"] else "https://" + m["url"], title="Fake page", nav=self.page["nav"] + 1)
        return {"ok": True, "tab": "T1", "url": self.page["url"], "title": self.page["title"], "loaded": True}

    def v_browser_tabs(self, m, ctx):
        return {"ok": True, "tabs": [{"tab": "T1", "title": self.page["title"], "url": self.page["url"], "active": True}]}

    def v_browser_tree(self, m, ctx):
        snap = "s%d" % self.page["nav"]
        nodes = [{"ref": "n10", "role": "textbox", "name": "Search", "box": [10, 10, 200, 30], "xbox": [26, 26, 525, 79], "depth": 3},
                 {"ref": "n11", "role": "button", "name": "Go now", "box": [220, 10, 60, 30], "xbox": [578, 26, 158, 79], "depth": 3}]
        return {"ok": True, "snap": snap, "url": self.page["url"], "title": self.page["title"], "mode": "ax",
                "nodes": nodes, "count": 2, "truncated": False, "xbox_calibrated": False}

    def v_browser_click(self, m, ctx):
        if m.get("snap") and m["snap"] != "s%d" % self.page["nav"]:
            return {"error": "stale_ref", "detail": "old snapshot"}
        names = {"n10": ("textbox", "Search"), "n11": ("button", "Go now")}
        ref = m.get("ref") or ("n11" if (m.get("text") or "").lower() in "go now" else None)
        if ref not in names:
            return {"error": "not_found"}
        self.page["clicked"] = ref
        return {"ok": True, "clicked": {"ref": ref, "role": names[ref][0], "name": names[ref][1]}}

    def v_browser_type(self, m, ctx):
        self.page["typed"] += m["text"]
        return {"ok": True, "chars": len(m["text"])}

    def v_browser_key(self, m, ctx):
        return {"ok": True}

    # --- stream (4-frame ack window, newest wins) ---
    def v_stream_start(self, m, ctx):
        if m.get("kind") == "audio":
            return {"error": "unsupported", "detail": "no sound server in guest"}
        if len(ctx["streams"]) >= 2:
            return {"error": "busy"}
        ctx["sid"] += 1
        sid = "s%d" % ctx["sid"]
        st = {"seq": 0, "acked": 0, "frames": 0, "dropped": 0, "stop": threading.Event(), "lock": threading.Lock()}
        ctx["streams"][sid] = st
        fps = min(float(m.get("fps", 2)), 15.0)

        def loop():
            while not st["stop"].wait(1.0 / fps):
                with st["lock"]:
                    if st["seq"] - st["acked"] >= 4:
                        st["dropped"] += 1
                        continue
                    st["seq"] += 1
                    st["frames"] += 1
                    seq = st["seq"]
                ctx["send"](_frame_bin({"sid": sid, "seq": seq, "t": time.time(), "mime": "image/jpeg", "w": 1, "h": 1}, JPEG_1PX))
        threading.Thread(target=loop, daemon=True).start()
        return {"ok": True, "sid": sid}

    def v_stream_ack(self, m, ctx):
        st = ctx["streams"].get(m.get("sid"))
        if st:
            with st["lock"]:
                st["acked"] = max(st["acked"], int(m.get("seq", 0)))
        return None

    def v_stream_stop(self, m, ctx):
        st = ctx["streams"].pop(m.get("sid"), None)
        if not st:
            return {"error": "bad_args"}
        st["stop"].set()
        return {"ok": True, "frames": st["frames"], "dropped": st["dropped"]}

    # --- fs (shared storage as a dict; the delete gate is only proposed, never executed here) ---
    @staticmethod
    def _guard(p):
        p = str(p or "")
        if p.startswith("/") or ".." in p.split("/") or "\0" in p or p.startswith(("Android/data", "Android/obb")):
            raise KeyError("path (rejected)")
        return p.strip("/")

    def v_fs_list(self, m, ctx):
        base = self._guard(m.get("path", ""))
        pre = base + "/" if base else ""
        seen = {}
        for k, v in self.fs.items():
            if k.startswith(pre):
                rest = k[len(pre):]
                name = rest.split("/")[0]
                isdir = "/" in rest
                seen[name] = {"name": name, "dir": isdir, "bytes": 0 if isdir else len(v), "mtime": 0, "kind": "dir" if isdir else "file"}
        return {"ok": True, "path": base, "entries": sorted(seen.values(), key=lambda e: e["name"]), "truncated": False}

    def v_fs_get(self, m, ctx):
        p = self._guard(m["path"])
        if p not in self.fs:
            return {"error": "bad_args", "detail": "no such file"}
        data = self.fs[p]
        off = int(m.get("offset", 0))
        ln = min(int(m.get("length", 512 << 10)), 512 << 10)
        chunk = data[off:off + ln]
        return ({"path": p, "offset": off, "size": len(chunk), "total": len(data), "eof": off + len(chunk) >= len(data),
                 "mime": "application/octet-stream"}, chunk)

    def v_fs_put(self, m, ctx):
        p = self._guard(m["path"])
        buf = self.parts.setdefault(p, bytearray())
        if int(m.get("offset", 0)) != len(buf):
            return {"error": "bad_offset", "next_offset": len(buf)}
        buf += base64.b64decode(m.get("data_b64", ""))
        return {"ok": True, "next_offset": len(buf)}

    def v_fs_put_commit(self, m, ctx):
        p = self._guard(m["path"])
        if p in self.fs:
            return {"error": "exists"}
        buf = bytes(self.parts.pop(p, b""))
        if hashlib.sha256(buf).hexdigest() != m.get("sha256"):
            return {"error": "bad_args", "detail": "sha256 mismatch"}
        self.fs[p] = buf
        return {"ok": True, "bytes": len(buf)}

    # Like the real Router + FsVerbs: the frame `id` is the request id, so proposals travel as `proposal`.
    def v_fs_propose_delete(self, m, ctx):
        paths = m["paths"]
        if len(paths) > 200:
            return {"error": "bad_args", "detail": "max 200 paths"}
        ok = [p for p in paths if p in self.fs]
        pid = "P%d" % (len(self.proposals) + 1)
        self.proposals[pid] = {"paths": ok, "reason": m.get("reason"), "state": "selected",
                               "nonce": secrets.token_hex(8),
                               "selected_hash": hashlib.sha256("\n".join(sorted(ok)).encode()).hexdigest()}
        return {"ok": True, "proposal": pid, "count": len(ok), "bytes": sum(len(self.fs[p]) for p in ok),
                "rejected": [p for p in paths if p not in self.fs]}

    def _prop(self, m):
        pid = m.get("proposal")
        if pid not in self.proposals:
            raise KeyError("proposal")
        return pid, self.proposals[pid]

    def v_fs_proposal(self, m, ctx):
        pid, p = self._prop(m)
        out = {"ok": True, "proposal": pid, "state": p["state"], "selected": list(p["paths"]),
               "selected_hash": p["selected_hash"], "nonce": p["nonce"]}
        if p.get("result"):
            out["result"] = p["result"]
        return out

    def v_fs_execute(self, m, ctx):
        pid, p = self._prop(m)
        if m.get("nonce") != p["nonce"] or m.get("selected_hash") != p["selected_hash"]:
            return {"error": "bad_args", "detail": "nonce/selected_hash mismatch"}
        if m.get("mode") not in ("trash", "delete"):
            return {"error": "bad_args", "detail": "mode"}
        p["state"] = "awaiting_confirm"
        return {"ok": True, "proposal": pid, "state": "awaiting_confirm"}

    def v_fs_cancel(self, m, ctx):
        pid, p = self._prop(m)
        p["state"] = "cancelled"
        return {"ok": True, "proposal": pid, "state": "cancelled"}

    def approve(self, pid):
        """Test helper: the person's Approve tap on the review sheet."""
        p = self.proposals[pid]
        for path in p["paths"]:
            self.fs.pop(path, None)
        p["state"] = "done"
        p["result"] = "deleted %d" % len(p["paths"])
