"""genie-client against fake_desk: codec, pairing/token store, multiplexing, binary frames, streams, fs, CLI.

Run: python3 -m unittest discover -s genie-client/tests -v
"""
import base64
import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import fake_desk  # noqa: E402
import linuxdesk  # noqa: E402
import linuxdesk_client as lc  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ld-test-")
        self.old_store = lc.STORE_DIR
        lc.STORE_DIR = os.path.join(self.tmp, "cfg", "linuxdesk")
        self.fd = fake_desk.FakeDesk(code="ABC234").start()

    def tearDown(self):
        self.fd.stop()
        lc.STORE_DIR = self.old_store
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def conn(self):
        return lc.Conn.tcp("127.0.0.1", self.fd.port, timeout=10)

    def paired(self):
        with self.conn() as c:
            c.pair("abc234", genie="agent:test")
        c = self.conn()
        c.hello()
        return c

    def cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = linuxdesk.main(["--tcp", "127.0.0.1:%d" % self.fd.port, "--genie", "agent:test"] + list(args))
        return code, out.getvalue(), err.getvalue()


class CodecTest(unittest.TestCase):
    def test_json_and_binary_frames(self):
        self.assertEqual(lc.decode_reply(b"\x00" + b'{"ok":true}'), {"ok": True})
        h = json.dumps({"id": 3, "mime": "image/jpeg"}).encode()
        r = lc.decode_reply(b"\x01" + len(h).to_bytes(2, "big") + h + b"BYTES")
        self.assertIsInstance(r, lc.Reply)
        self.assertEqual(r["id"], 3)
        self.assertEqual(r.data, b"BYTES")
        with self.assertRaises(lc.LinkError):
            lc.decode_reply(b"\x07x")

    def test_request_cap(self):
        with self.assertRaises(lc.LinkError):
            lc.encode_request({"cmd": "x", "pad": "a" * (1 << 20)})
        self.assertEqual(lc.encode_request({"cmd": "x"})[:4], (len(b'{"cmd":"x"}')).to_bytes(4, "big"))

    def test_pair_line(self):
        self.assertEqual(linuxdesk.parse_line("hi LINUXDESK local:abc234 bye"), ("local", "ABC234"))
        node = "ab" * 32
        self.assertEqual(linuxdesk.parse_line("LINUXDESK %s:QWERTY" % node), (node, "QWERTY"))
        with self.assertRaises(ValueError):
            linuxdesk.parse_line("STORAGEGENIE x:ABCDEF")


class PairTest(Base):
    def test_pair_stores_token_0600_and_hides_it(self):
        with self.conn() as c:
            r = c.pair("abc234", genie="agent:test")
        self.assertNotIn("token", r)
        path = r["token_saved"]
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode), 0o700)
        tok = json.load(open(path))["token"]
        self.assertEqual(len(tok), 43)
        c2 = self.conn()
        self.assertTrue(c2.hello()["ok"])
        self.assertEqual(c2.cmd("sys.ping")["genie"], "agent:test")
        c2.close()

    def test_code_single_use_and_bound(self):
        self.paired().close()
        with self.conn() as c:
            with self.assertRaises(lc.RemoteError) as e:
                c.pair("ABC234")
        self.assertEqual(e.exception.code, "bound")

    def test_wrong_code_and_rotation(self):
        for _ in range(5):
            with self.conn() as c:
                with self.assertRaises(lc.RemoteError) as e:
                    c.pair("ZZZZZZ")
                self.assertEqual(e.exception.code, "auth")
        self.assertNotEqual(self.fd.code, "ABC234")    # 5 strikes rotate the code

    def test_forget_unpairs_live_session(self):
        c = self.paired()
        self.fd.forget()
        with self.assertRaises(lc.RemoteError) as e:
            c.cmd("sys.ping")
        self.assertEqual(e.exception.code, "unpaired")

    def test_cli_pair_never_prints_token(self):
        code, out, err = self.cli("pair", "--line", "LINUXDESK local:ABC234")
        self.assertEqual(code, 0, err)
        tok = json.load(open(os.path.join(lc.STORE_DIR, "local.json")))["token"]
        self.assertNotIn(tok, out + err)
        code, out, err = self.cli("--json", "ping")
        self.assertEqual(code, 0, err)
        self.assertNotIn(tok, out + err)

    def test_refuses_repo_store(self):
        lc.STORE_DIR = os.path.join(self.tmp, "ideas-1911-lab", "x")
        with self.assertRaises(lc.LinkError):
            lc.save_token("local", "t" * 43, "g")


class VerbTest(Base):
    def test_out_of_order_replies(self):
        c = self.paired()
        res = {}

        def slow():
            res["slow"] = (c.cmd("sys.sleep", s=0.6), time.time())
        th = threading.Thread(target=slow)
        th.start()
        time.sleep(0.1)
        fast = c.cmd("sys.ping")
        t_fast = time.time()
        th.join()
        self.assertTrue(fast["ok"])
        self.assertLess(t_fast, res["slow"][1])            # the fast reply overtook the slow one
        self.assertEqual(res["slow"][0]["slept"], 0.6)
        c.close()

    def test_errors_raise(self):
        c = self.paired()
        with self.assertRaises(lc.RemoteError) as e:
            c.cmd("nope.verb")
        self.assertEqual(e.exception.code, "unknown_cmd")
        self.assertEqual(c.cmd("nope.verb", _raise=False)["error"], "unknown_cmd")
        c.close()

    def test_cli_browser_flow(self):
        self.paired().close()
        self.assertEqual(self.cli("open", "example.com")[0], 0)
        code, out, _ = self.cli("tree")
        self.assertEqual(code, 0)
        self.assertIn("n11", out)
        self.assertIn("snap=s1", out)
        code, out, _ = self.cli("click", "--text", "Go now")
        self.assertEqual(code, 0)
        self.assertEqual(self.fd.page["clicked"], "n11")
        self.assertEqual(self.cli("click", "n10", "--snap", "s0")[0], 1)     # stale snapshot refused
        self.assertEqual(self.cli("type", "שלום hello")[0], 0)
        self.assertEqual(self.fd.page["typed"], "שלום hello")
        self.assertEqual(self.cli("key", "Enter")[0], 0)

    def test_cli_sh(self):
        self.paired().close()
        code, out, _ = self.cli("sh", "--", "whoami")
        self.assertEqual((code, out), (0, "desk\n"))
        code, out, _ = self.cli("sh", "--root", "--", "whoami")
        self.assertEqual((code, out), (0, "root\n"))
        self.assertEqual(self.cli("sh", "--", "false")[0], 1)

    def test_cli_shot_binary(self):
        self.paired().close()
        dest = os.path.join(self.tmp, "s.jpg")
        code, out, _ = self.cli("shot", dest)
        self.assertEqual(code, 0)
        self.assertEqual(open(dest, "rb").read(), fake_desk.JPEG_1PX)

    def test_stream_ack_window(self):
        c = self.paired()
        sid = c.cmd("stream.start", kind="screen", fps=15)["sid"]
        # Don't read: the server may only send 4 unacked frames.
        time.sleep(1.0)
        st = c.cmd("stream.stop", sid=sid)
        self.assertLessEqual(st["frames"], 4)
        self.assertGreater(st["dropped"], 0)
        sid = c.cmd("stream.start", kind="screen", fps=15)["sid"]
        got = []
        for f in c.stream(sid):
            got.append(f["seq"])
            if len(got) >= 8:
                break
        c.cmd("stream.stop", sid=sid)
        self.assertEqual(got, list(range(1, 9)))           # acking keeps it flowing past the window
        self.assertEqual(c.cmd("stream.start", kind="audio", _raise=False)["error"], "unsupported")
        c.close()

    def test_cli_watch(self):
        self.paired().close()
        code, out, _ = self.cli("watch", "--fps", "10", "--frames", "3", "--out-dir", os.path.join(self.tmp, "fr"))
        self.assertEqual(code, 0)
        self.assertEqual(len(os.listdir(os.path.join(self.tmp, "fr"))), 3)

    def test_cli_fs_roundtrip(self):
        self.paired().close()
        code, out, _ = self.cli("ls")
        self.assertIn("DCIM", out)
        dest = os.path.join(self.tmp, "big.bin")
        self.assertEqual(self.cli("get", "Download/big.bin", dest)[0], 0)
        self.assertEqual(open(dest, "rb").read(), self.fd.fs["Download/big.bin"])   # 1 MiB = 2 chunks
        self.assertEqual(self.cli("put", dest, "Download/copy.bin")[0], 0)
        self.assertEqual(self.fd.fs["Download/copy.bin"], self.fd.fs["Download/big.bin"])
        self.assertEqual(self.cli("put", dest, "Download/copy.bin")[0], 1)          # never overwrites
        code, out, _ = self.cli("propose-delete", "DCIM/a.jpg", "nope", "--reason", "dupes")
        self.assertEqual(code, 0)
        self.assertIn("1 files", out)
        self.assertIn("DCIM/a.jpg", self.fd.fs)                                       # proposing deletes nothing
        self.assertIn("proposal P1:", out)                    # the proposal id, not the frame id

    def test_cli_delete_gate(self):
        self.paired().close()
        code, out, _ = self.cli("propose-delete", "DCIM/a.jpg", "--reason", "dupes")
        self.assertIn("proposal P1:", out)
        code, out, _ = self.cli("execute", "P1")
        self.assertEqual(code, 0)
        self.assertIn("confirm on the phone", out)
        self.assertIn("state: awaiting_confirm", out)
        self.assertIn("DCIM/a.jpg", self.fd.fs)               # execute alone deletes nothing
        threading.Timer(0.5, self.fd.approve, args=("P1",)).start()
        code, out, _ = self.cli("proposal", "P1")
        self.assertEqual(json.loads(out)["state"], "awaiting_confirm")
        code, out, _ = self.cli("execute", "P1", "--wait", "10")
        self.assertIn("state: done", out)
        self.assertNotIn("DCIM/a.jpg", self.fd.fs)
        self.cli("propose-delete", "Download/big.bin", "--reason", "x")
        code, out, _ = self.cli("cancel", "P2")
        self.assertEqual((code, json.loads(out)["state"]), (0, "cancelled"))
        self.assertEqual(self.cli("proposal", "P9")[0], 1)    # unknown proposal -> error

    def test_cli_logs_tail_all_and_new_cursor(self):
        self.paired().close()
        for i in range(5):
            self.fd.add_session_line("line %d" % i)
        code, out, _ = self.cli("logs", "--tail", "2")
        self.assertEqual((code, out), (0, "line 3\nline 4\n"))
        self.assertEqual(self.cli("logs")[1].count("\n"), 5)
        self.assertEqual(self.cli("logs", "--all")[1].count("\n"), 5)
        code, out, _ = self.cli("logs", "--new")
        self.assertEqual((code, out.count("\n")), (0, 5))              # first --new: everything held
        path = os.path.join(lc.STORE_DIR, "local.logcursor.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        self.assertEqual(json.load(open(path))["cursor"], 5)
        tok = json.load(open(os.path.join(lc.STORE_DIR, "local.json")))["token"]
        self.assertNotIn(tok, open(path).read())                          # the cursor file holds no secret
        self.assertEqual(self.cli("logs", "--new")[1], "")                # nothing new
        self.fd.add_session_line("line 5")
        self.assertEqual(self.cli("logs", "--new")[1], "line 5\n")       # only the delta
        self.fd.session_cap = 3
        for i in range(6, 12):
            self.fd.add_session_line("line %d" % i)
        code, out, err = self.cli("logs", "--new")
        self.assertEqual(out, "line 9\nline 10\nline 11\n")
        self.assertIn("3 lines were lost", err)                           # seqs 6..8 fell out of the ring
        with self.assertRaises(SystemExit) as e:                          # mutually exclusive (argparse)
            self.cli("logs", "--all", "--new")
        self.assertEqual(e.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
