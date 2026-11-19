"""Real deskd (assets/linuxdesk/deskd/deskd.py) as a subprocess on this host, driven by linuxdesk_client.

Covers: loopback bind, key auth (env read once, removed), token-hash auth + unpaired on Forget, bad first frame,
too_big, shell.exec (user, timeout, truncation, stdin, binary), shell.spawn stream + write + kill + limit,
id multiplexing, the CDP pipe plumbing (fds 3/4 through the /bin/sh trampoline -> a fake CDP browser), the
legacy-marker refusal, the xwd parser/encoders, and (if Chrome exists locally) a LIVE Chrome run over the
real --remote-debugging-pipe: open, AX tree refs, click by ref + text, type Hebrew, key, browser capture,
screencast stream. The temp profile is removed afterwards.
"""
import base64
import hashlib
import json
import os
import shutil
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DESKD_DIR = os.path.join(ROOT, "apps/linux-desktop-chrome/app/src/main/assets/linuxdesk/deskd")
DESKD = os.path.join(DESKD_DIR, "deskd.py")
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, DESKD_DIR)

import linuxdesk_client as lc  # noqa: E402

CHROME = next((p for p in ("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                           shutil.which("chromium") or "", shutil.which("chromium-browser") or "",
                           shutil.which("google-chrome") or "") if p and os.path.exists(p)), None)

FAKE_BROWSER = r'''#!/usr/bin/env python3
# Stands in for start-browser.sh -> chromium: a CDP endpoint on fds 3 (in) / 4 (out), NUL-separated JSON.
import json, os, sys
assert "--remote-debugging-pipe" in sys.argv, "no pipe flag"
open(os.environ["FAKE_ARGV_OUT"], "w").write(json.dumps({"argv": sys.argv[1:], "env_key": "LINUXDESK_KEY" in os.environ,
                                                      "genie": os.environ.get("LINUXDESK_GENIE")}))
buf = b""
while True:
    c = os.read(3, 65536)
    if not c: break
    buf += c
    while b"\0" in buf:
        m, buf = buf.split(b"\0", 1)
        msg = json.loads(m)
        if msg["method"] == "Browser.getVersion":
            res = {"product": "FakeChrome/1.0", "protocolVersion": "1.3"}
        elif msg["method"] == "Target.getTargets":
            res = {"targetInfos": [{"targetId": "T1", "type": "page", "title": "fake", "url": "about:blank"}]}
        else:
            res = {}
        os.write(4, json.dumps({"id": msg["id"], "result": res}).encode() + b"\0")
'''


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Deskd(object):
    def __init__(self, tmp, key="K" * 43, extra=(), flags=(), env=None):
        self.port = free_port()
        self.tmp = tmp
        self.hash_file = os.path.join(tmp, "genie.hash")
        self.logf = open(os.path.join(tmp, "deskd-%d.log" % self.port), "w+")
        e = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", **(env or {}))
        if key:
            e["LINUXDESK_KEY"] = key
        self.key = key
        argv = [sys.executable, DESKD, "--port", str(self.port), "--hash-file", self.hash_file,
                "--run-dir", os.path.join(tmp, "run")] + list(extra)
        if flags:
            argv += ["--"] + list(flags)
        self.p = subprocess.Popen(argv, env=e, stdout=self.logf, stderr=subprocess.STDOUT)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=0.5).close()
                return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("deskd did not start: " + self.log())

    def log(self):
        self.logf.flush()
        self.logf.seek(0)
        return self.logf.read()

    def conn(self, auth=None):
        c = lc.Conn.tcp("127.0.0.1", self.port, timeout=60)
        c.hello(genie="test", token=auth or self.key)
        return c

    def stop(self):
        self.p.terminate()
        try:
            self.p.wait(10)
        except subprocess.TimeoutExpired:
            self.p.kill()
        self.logf.close()


def raw_frames(port, frames):
    """Sends raw request payloads; returns the decoded replies until the server closes."""
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    for f in frames:
        s.sendall(struct.pack(">I", len(f)) + f)
    out, buf = [], b""
    try:
        while True:
            c = s.recv(65536)
            if not c:
                break
            buf += c
    except socket.timeout:
        pass
    s.close()
    while len(buf) >= 4:
        n = struct.unpack(">I", buf[:4])[0]
        out.append(lc.decode_reply(buf[4:4 + n]))
        buf = buf[4 + n:]
    return out


class DeskdCore(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="deskd-test-")
        cls.d = Deskd(cls.tmp)

    @classmethod
    def tearDownClass(cls):
        cls.d.stop()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_loopback_only(self):
        r = subprocess.run([sys.executable, DESKD, "--host", "0.0.0.0", "--port", "1"], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("loopback only", r.stderr + r.stdout)

    def test_port_busy_no_fallback(self):
        r = subprocess.run([sys.executable, DESKD, "--port", str(self.d.port)], capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 3)
        self.assertIn("port busy", r.stdout)

    def test_auth_refusals(self):
        self.assertEqual(raw_frames(self.d.port, [b'{"auth":"wrong","genie":"x"}']), [{"error": "auth"}])
        self.assertEqual(raw_frames(self.d.port, [b'{"cmd":"sys.ping"}']), [{"error": "auth"}])
        self.assertEqual(raw_frames(self.d.port, [b'not json']), [{"error": "bad_json"}])
        s = socket.create_connection(("127.0.0.1", self.d.port))
        s.sendall(struct.pack(">I", (1 << 20) + 1))
        n = struct.unpack(">I", s.recv(4))[0]
        self.assertEqual(lc.decode_reply(s.recv(n))["error"], "too_big")
        s.close()
        self.assertNotIn(self.d.key, self.d.log())        # the key is never logged

    def test_key_removed_from_environ(self):
        c = self.d.conn()
        r = c.cmd("shell.exec", sh="env")
        self.assertNotIn("LINUXDESK_KEY", r["stdout"])
        self.assertNotIn(self.d.key, r["stdout"])
        c.close()

    def test_token_hash_and_unpaired(self):
        tok = "T" * 43
        with open(self.d.hash_file, "w") as f:
            f.write(hashlib.sha256(tok.encode()).hexdigest() + "\n")
        os.chmod(self.d.hash_file, 0o600)
        c = self.d.conn(auth=tok)
        self.assertTrue(c.cmd("sys.ping")["ok"])
        os.unlink(self.d.hash_file)                        # "Forget my genie"
        with self.assertRaises(lc.RemoteError) as e:
            c.cmd("sys.ping")
        self.assertEqual(e.exception.code, "unpaired")
        with self.assertRaises(lc.RemoteError):
            self.d.conn(auth=tok)

    def test_exec(self):
        c = self.d.conn()
        r = c.cmd("shell.exec", argv=["sh", "-c", "echo out; echo err >&2; exit 3"])
        self.assertEqual((r["code"], r["stdout"], r["stderr"]), (3, "out\n", "err\n"))
        r = c.cmd("shell.exec", sh="cat", stdin_b64=base64.b64encode("שלום\n".encode()).decode())
        self.assertEqual(r["stdout"], "שלום\n")
        r = c.cmd("shell.exec", sh="head -c 5000 /dev/zero", max_bytes=100)
        self.assertTrue(r["truncated"])
        self.assertEqual(len(r["stdout"]), 100)
        r = c.cmd("shell.exec", sh="printf '\\377\\001'", binary=True)
        self.assertEqual(base64.b64decode(r["stdout_b64"]), b"\xff\x01")
        r = c.cmd("shell.exec", sh="sleep 5", timeout_s=0.5, _raise=False)
        self.assertEqual(r["error"], "timeout")
        r = c.cmd("shell.exec", _raise=False)
        self.assertEqual(r["error"], "bad_args")
        r = c.cmd("shell.exec", sh="pwd", cwd="/tmp")
        self.assertIn("tmp", r["stdout"])
        if os.geteuid() != 0:
            self.assertEqual(c.cmd("shell.exec", sh="id", **{"as": "root"}, _raise=False)["error"], "unsupported")
        c.close()

    def test_secret_text_not_logged(self):
        c = self.d.conn()
        c.cmd("shell.exec", sh="cat", stdin_b64=base64.b64encode(b"SECRET-STDIN-xyz").decode())
        c.close()
        self.assertNotIn("SECRET-STDIN-xyz", self.d.log())

    def test_spawn_stream(self):
        c = self.d.conn()
        r = c.cmd("shell.spawn", sh="read x; echo got:$x; echo e >&2; exit 4")
        sid = r["sid"]
        c.cmd("shell.write", sid=sid, data_b64=base64.b64encode(b"hi\n").decode())
        out, exit_ = b"", None
        for f in c.stream(sid, timeout=10):
            if isinstance(f, lc.Reply):
                if f["fd"] == 1:
                    out += f.data
            else:
                exit_ = f.get("exit")
        self.assertEqual(out, b"got:hi\n")
        self.assertEqual(exit_, 4)
        sids = [c.cmd("shell.spawn", sh="sleep 30")["sid"] for _ in range(4)]
        self.assertEqual(c.cmd("shell.spawn", sh="true", _raise=False)["error"], "busy")
        for s in sids:
            c.cmd("shell.kill", sid=s, signal="KILL")
        c.close()

    def test_multiplex(self):
        c = self.d.conn()
        import threading
        t = {}

        def slow():
            c.cmd("shell.exec", sh="sleep 1")
            t["slow"] = time.time()
        th = threading.Thread(target=slow)
        th.start()
        time.sleep(0.2)
        c.cmd("shell.exec", sh="true")
        t["fast"] = time.time()
        th.join()
        self.assertLess(t["fast"], t["slow"])
        c.close()

    def test_unsupported_and_unknown(self):
        c = self.d.conn()
        self.assertEqual(c.cmd("stream.start", kind="audio", _raise=False)["error"], "unsupported")
        self.assertEqual(c.cmd("bogus.verb", _raise=False)["error"], "unknown_cmd")
        self.assertEqual(c.cmd("browser.tabs", _raise=False)["error"], "not_ready")
        p = c.cmd("sys.ping")
        self.assertFalse(p["audio"])
        self.assertNotIn("browser", p["caps"])
        c.close()


class CapturePure(unittest.TestCase):
    def xwd(self, w, h, order=0):
        import capture
        hdr = [100, 7, 2, 24, w, h, 0, order, 32, order, 32, 32, w * 4, 4, 0xFF0000, 0xFF00, 0xFF, 8, 256, 0, 0, 0, 0, 0, 0]
        px = bytearray()
        for y in range(h):
            for x in range(w):
                r, g, b = (x * 40) % 256, (y * 60) % 256, 77
                px += bytes([b, g, r, 0]) if order == 0 else bytes([0, r, g, b])
        return struct.pack(">25I", *hdr) + bytes(px), capture

    def test_parse_both_orders(self):
        for order in (0, 1):
            data, capture = self.xwd(5, 3, order)
            w, h, rgb = capture.parse_xwd(data)
            self.assertEqual((w, h), (5, 3))
            self.assertEqual(rgb[3 * (2 * 5 + 4):3 * (2 * 5 + 4) + 3], bytes([160, 120, 77]))

    def test_encoders(self):
        data, capture = self.xwd(40, 20)
        png = capture.png_encode(*capture.crop_scale(*capture.parse_xwd(data), region=[0, 0, 20, 10], scale=0.5))
        from common import image_size
        self.assertEqual(image_size(png), (10, 5))
        out, w, h, mime, enc = capture.encode_xwd(data, "jpeg", 60, 0.5)
        self.assertEqual((w, h), (20, 10))
        self.assertTrue(out.startswith(b"\xff\xd8") or mime == "image/png")


class DeskdPipe(unittest.TestCase):
    """The fd 3/4 plumbing, with a fake CDP browser in place of start-browser.sh."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="deskd-pipe-")
        self.fake = os.path.join(self.tmp, "fake-browser.py")
        with open(self.fake, "w") as f:
            f.write(FAKE_BROWSER)
        os.chmod(self.fake, 0o755)
        self.argv_out = os.path.join(self.tmp, "argv.json")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_pipe_handshake(self):
        d = Deskd(self.tmp, extra=["--browser", self.fake], flags=["--no-sandbox", "--user-data-dir=/x"],
                  env={"FAKE_ARGV_OUT": self.argv_out})
        try:
            c = d.conn()
            for _ in range(50):
                p = c.cmd("sys.ping")
                if p["session"]["browser"]:
                    break
                time.sleep(0.2)
            self.assertEqual(p["session"]["cdp"], "pipe")
            self.assertEqual(p["session"]["cdp_pipe"], "ok")
            self.assertFalse(p["session"]["cdp_unauthed_port"])
            self.assertEqual(p["session"]["browser_version"], "FakeChrome/1.0")
            self.assertEqual(c.cmd("browser.tabs")["tabs"][0]["tab"], "T1")
            seen = json.load(open(self.argv_out))
            self.assertEqual(seen["argv"], ["--no-sandbox", "--user-data-dir=/x", "--remote-debugging-pipe"])
            self.assertFalse(seen["env_key"])              # the session key never reaches the browser
            self.assertEqual(seen["genie"], "0")           # start-browser.sh's legacy port-9222 block stays off
            self.assertIn("cdp-pipe ok", d.log())
            c.close()
        finally:
            d.stop()

    def test_pipe_failure_no_silent_port(self):
        bad = os.path.join(self.tmp, "bad.sh")
        with open(bad, "w") as f:
            f.write("#!/bin/sh\nexit 1\n")
        os.chmod(bad, 0o755)
        d = Deskd(self.tmp, extra=["--browser", bad, "--cdp-fallback", "none"], env={"LINUXDESK_CDP_WAIT": "2"})
        try:
            time.sleep(3)
            c = d.conn()
            p = c.cmd("sys.ping")
            self.assertFalse(p["session"]["browser"])
            self.assertTrue(str(p["session"]["cdp_pipe"]).startswith("fail"))
            self.assertIsNone(p["session"]["cdp"])        # --cdp-fallback none: no port
            self.assertFalse(p["session"]["cdp_unauthed_port"])
            c.close()
        finally:
            d.stop()


@unittest.skipUnless(CHROME, "no local Chrome/Chromium")
class DeskdLiveChrome(unittest.TestCase):
    """LIVE: deskd as Chrome's parent over the real --remote-debugging-pipe."""

    PAGE = ("data:text/html;charset=utf-8,<title>LD test</title><input id=q aria-label=Query>"
            "<button onclick=\"document.getElementById('out').textContent='clicked:'+document.getElementById('q').value\">Go now</button>"
            "<label><input type=checkbox id=cb> Agree</label><p id=out></p>")

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="deskd-live-")
        cls.profile = os.path.join(cls.tmp, "profile")
        flags = ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-gpu",
                 "--user-data-dir=" + cls.profile, "--window-size=800,600", "about:blank"]
        cls.d = Deskd(cls.tmp, extra=["--browser", CHROME], flags=flags)
        c = cls.d.conn()
        for _ in range(100):
            if c.cmd("sys.ping")["session"]["browser"]:
                break
            time.sleep(0.2)
        c.close()

    @classmethod
    def tearDownClass(cls):
        cls.d.stop()
        time.sleep(0.5)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_live_flow(self):
        c = self.d.conn()
        p = c.cmd("sys.ping")
        self.assertEqual(p["session"]["cdp"], "pipe", self.d.log()[-2000:])
        r = c.cmd("browser.open", url=self.PAGE)
        self.assertTrue(r["loaded"])
        self.assertEqual(r["title"], "LD test")
        t = c.cmd("browser.tree")
        names = {n["name"]: n for n in t["nodes"]}
        self.assertIn("Go now", names)
        self.assertIn("Query", names)
        self.assertTrue(t["snap"].startswith("s"))
        self.assertIn("xbox", names["Go now"])
        q = names["Query"]
        c.cmd("browser.type", text="hello שלום", ref=q["ref"], snap=t["snap"])
        r = c.cmd("browser.click", ref=names["Go now"]["ref"], snap=t["snap"])
        self.assertEqual(r["clicked"]["name"], "Go now")
        out = c.cmd("browser.eval", js="document.getElementById('out').textContent")["value"]
        self.assertEqual(out, "clicked:hello שלום")
        c.cmd("browser.click", text="Agree")
        self.assertTrue(c.cmd("browser.eval", js="document.getElementById('cb').checked")["value"])
        c.cmd("browser.type", text="X", selector="#q", clear=True)
        c.cmd("browser.key", keys="Control+A")
        c.cmd("browser.key", keys="Backspace")
        self.assertEqual(c.cmd("browser.eval", js="document.getElementById('q').value")["value"], "")
        shot = c.cmd("screen.capture", source="browser")
        self.assertIsInstance(shot, lc.Reply)
        self.assertTrue(shot.data.startswith(b"\xff\xd8"))
        self.assertEqual(shot["mime"], "image/jpeg")
        c.cmd("browser.open", url=self.PAGE + "<i>2</i>")
        r = c.cmd("browser.click", ref=names["Go now"]["ref"], snap=t["snap"], _raise=False)
        self.assertEqual(r["error"], "stale_ref")
        sid = c.cmd("stream.start", kind="screen", source="browser", fps=5)["sid"]
        frames = []
        c.cmd("browser.eval", js="document.body.style.background='red'")
        for f in c.stream(sid, timeout=10):
            frames.append(f)
            if len(frames) >= 1:
                break
        st = c.cmd("stream.stop", sid=sid)
        self.assertGreaterEqual(st["frames"], 1)
        self.assertTrue(frames[0].data.startswith(b"\xff\xd8"))
        old = c.cmd("chrome.snapshot")                 # v1 genie-daemon names still answer
        self.assertTrue(old["ok"])
        c.close()
        log = self.d.log()
        self.assertNotIn("שלום", log)                  # typed text never logged
        self.assertNotIn(self.d.key, log)


if __name__ == "__main__":
    unittest.main()
