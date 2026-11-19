#!/usr/bin/env python3
"""linuxdesk: a genie's CLI for a LinuxDesk phone (wire linuxdesk/1; library: linuxdesk_client.py).

  linuxdesk pair --code ABC234                 (or --line "LINUXDESK local:ABC234")
  linuxdesk ping
  linuxdesk sh [--root] -- uname -a            (guest shell; --root = proot fake root, not Android root)
  linuxdesk open example.com [--new]
  linuxdesk tree [--mode dom] [--all]
  linuxdesk click n42 | --text "Sign in" | --selector "#go" | --xy 120 300
  linuxdesk type "hello שלום" [--into n17] [--clear] [--submit]
  linuxdesk key Enter | "Control+A" | --x11 ctrl+l
  linuxdesk shot out.jpg [--source browser] [--scale 0.5]
  linuxdesk watch --fps 2 [--out-dir frames/] [--frames 10]
  linuxdesk ls [PATH] · get PATH [DEST] · put SRC DST · propose-delete PATH... --reason "..."
  linuxdesk proposal ID · execute ID [--mode trash|delete] [--wait S] · cancel ID   (args travel as `proposal`)
  linuxdesk logs [--all | --tail N | --new]     (phone session log on demand; --new = only lines not yet read)
  linuxdesk raw VERB '{"json": "args"}'

Transport: --tcp HOST:PORT (default 127.0.0.1:8766 = the app's WireServer via `adb forward tcp:8766 tcp:8766`)
or --iroh NODE_ID (phase B). The token lives in ~/.config/linuxdesk/<local|node-id>.json (0600) and is never
printed. Exit codes: 0 ok, 1 the phone answered an error, 2 connection/usage error.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import linuxdesk_client  # noqa: E402
from linuxdesk_client import Conn, LinkError, RemoteError, Reply  # noqa: E402

CHUNK = 512 << 10


def parse_line(line):
    """'LINUXDESK <node-id-hex|local>:<CODE>' -> (node, code)."""
    m = re.search(r"LINUXDESK\s+([0-9a-fA-F]{16,}|local):([A-Za-z0-9]{6})", line or "")
    if not m:
        raise ValueError("not a LINUXDESK pairing line")
    return m.group(1).lower(), m.group(2).upper()


def connect(o, node=None):
    node = node or o.iroh
    if node and node != "local":
        return Conn.iroh(node, timeout=o.timeout)
    host, _, port = (o.tcp or "127.0.0.1:8766").rpartition(":")
    return Conn.tcp(host or "127.0.0.1", int(port), ident=o.ident or "local", timeout=o.timeout)


def print_tree(r, out):
    out.write("%s  %s  snap=%s  (%d nodes%s)\n" % (r.get("title", ""), r.get("url", ""), r.get("snap"),
                                                   r.get("count", len(r.get("nodes", []))),
                                                   ", truncated" if r.get("truncated") else ""))
    for n in r.get("nodes", []):
        flags = " ".join(k for k in ("focused", "disabled", "checked", "expanded", "selected") if n.get(k))
        val = (" = %r" % n["value"]) if "value" in n else ""
        box = n.get("box")
        out.write("%s%-7s %-12s %r%s%s%s\n" % (
            "  " * min(int(n.get("depth", 0)) // 3, 6) if r.get("mode") == "ax" else "",
            n["ref"], n.get("role", ""), n.get("name", ""), val, (" [" + flags + "]") if flags else "",
            (" @%d,%d %dx%d" % tuple(box)) if box else ""))


def _cursor_path(ident):
    """~/.config/linuxdesk/<ident>.logcursor.json, next to the token file (same 0700 dir)."""
    safe = "".join(ch for ch in ident if ch.isalnum() or ch in "-_.") or "local"
    return os.path.join(linuxdesk_client.STORE_DIR, safe + ".logcursor.json")


def load_log_cursor(ident):
    try:
        with open(_cursor_path(ident)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_log_cursor(ident, cur):
    path = _cursor_path(ident)
    linuxdesk_client._refuse_repo_path(path)
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cur, f)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def build_parser():
    ap = argparse.ArgumentParser(prog="linuxdesk", description="Drive a LinuxDesk phone from a genie (linuxdesk/1).")
    ap.add_argument("--tcp", default=os.environ.get("LINUXDESK_TCP"), help="HOST:PORT (default 127.0.0.1:8766)")
    ap.add_argument("--iroh", default=os.environ.get("LINUXDESK_NODE"), help="phone node id (phase B)")
    ap.add_argument("--ident", default=None, help="token-store name for TCP (default: local)")
    ap.add_argument("--genie", default=os.environ.get("LINUXDESK_GENIE_ID", "genie-client"), help="your agent id")
    ap.add_argument("--key-env", default=None, metavar="VAR",
                    help="debug: authenticate with the deskd session key held in env VAR (never stored)")
    ap.add_argument("--timeout", type=float, default=90.0)
    ap.add_argument("--tab", default=None)
    ap.add_argument("--json", action="store_true", help="print raw JSON replies")
    sp = ap.add_subparsers(dest="command", metavar="COMMAND")

    p = sp.add_parser("pair", help="trade a pairing code for a token (stored 0600, never printed)")
    p.add_argument("--code")
    p.add_argument("--line", help='the share line, e.g. "LINUXDESK local:ABC234"')
    sp.add_parser("ping", help="app/deskd version, caps, session")
    sp.add_parser("tabs", help="list browser tabs")

    p = sp.add_parser("sh", help="run a guest command: sh [--root] -- CMD...")
    p.add_argument("--root", action="store_true", help='as:"root" = proot fake root inside Alpine')
    p.add_argument("--timeout-s", type=float, default=30)
    p.add_argument("--cwd")
    p.add_argument("--stdin", action="store_true", help="send this process's stdin")
    p.add_argument("argv", nargs=argparse.REMAINDER)

    p = sp.add_parser("open", help="open URL in the browser")
    p.add_argument("url")
    p.add_argument("--new", action="store_true")

    p = sp.add_parser("nav", help="back | forward | reload | URL")
    p.add_argument("where")

    p = sp.add_parser("tree", help="AX (default) or DOM tree with refs and boxes")
    p.add_argument("--mode", choices=["ax", "dom"], default="ax")
    p.add_argument("--all", action="store_true", help="include non-interactive nodes")
    p.add_argument("--max", type=int, default=400)

    p = sp.add_parser("find", help="find nodes by text / role / selector")
    p.add_argument("text", nargs="?")
    p.add_argument("--role")
    p.add_argument("--selector")

    p = sp.add_parser("click", help="click REF | --text T | --selector S | --xy X Y (CSS px)")
    p.add_argument("ref", nargs="?")
    p.add_argument("--text", "-t")
    p.add_argument("--selector", "-s")
    p.add_argument("--xy", nargs=2, type=float, metavar=("X", "Y"))
    p.add_argument("--snap", help="the snap id the ref came from (stale refs are refused)")
    p.add_argument("--x11", action="store_true", help="click X screen px with input.click instead (needs --xy)")

    p = sp.add_parser("type", help="type TEXT (browser by default, --x11 = xdotool)")
    p.add_argument("text")
    p.add_argument("--into", help="ref to focus first")
    p.add_argument("--selector", "-s")
    p.add_argument("--clear", action="store_true")
    p.add_argument("--submit", action="store_true")
    p.add_argument("--x11", action="store_true")

    p = sp.add_parser("key", help='press keys: "Enter", "Control+A" (browser) or --x11 "ctrl+l"')
    p.add_argument("keys", nargs="+")
    p.add_argument("--x11", action="store_true")

    p = sp.add_parser("shot", help="screen capture to a file")
    p.add_argument("out", nargs="?", default="linuxdesk.jpg")
    p.add_argument("--source", choices=["x11", "browser"], default="x11")
    p.add_argument("--scale", type=float, default=None)
    p.add_argument("--quality", type=int, default=None)

    p = sp.add_parser("watch", help="screen stream: prints one line per frame, optionally saves them")
    p.add_argument("--fps", type=float, default=2)
    p.add_argument("--source", choices=["x11", "browser"], default="x11")
    p.add_argument("--scale", type=float, default=None)
    p.add_argument("--frames", type=int, default=0, help="stop after N frames (0 = until Ctrl-C)")
    p.add_argument("--out-dir")

    p = sp.add_parser("ls", help="list shared storage (relative to /storage/emulated/0)")
    p.add_argument("path", nargs="?", default="")
    p = sp.add_parser("get", help="download PATH to DEST")
    p.add_argument("path")
    p.add_argument("dest", nargs="?")
    p = sp.add_parser("put", help="upload SRC to DST (never overwrites)")
    p.add_argument("src")
    p.add_argument("dst")
    p = sp.add_parser("propose-delete", help="propose deleting PATHs (the phone asks the person)")
    p.add_argument("paths", nargs="+")
    p.add_argument("--reason", required=True)
    p = sp.add_parser("proposal", help="show a delete proposal's state")
    p.add_argument("proposal_id")
    p = sp.add_parser("execute", help="ask the phone to delete a proposal (the person must tap Approve)")
    p.add_argument("proposal_id")
    p.add_argument("--mode", choices=["trash", "delete"], default="trash")
    p.add_argument("--wait", type=float, default=0, metavar="S", help="poll fs.proposal up to S seconds for the outcome")
    p = sp.add_parser("cancel", help="cancel a delete proposal")
    p.add_argument("proposal_id")

    p = sp.add_parser("logs", help="pull the phone's session log: tail (default 200), --all, or --new since last time")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--all", action="store_true", help="every line the phone still holds")
    g.add_argument("--tail", type=int, metavar="N", help="the last N lines (max 5000)")
    g.add_argument("--new", action="store_true", help="only lines not read since the last --new (cursor kept per node)")
    p.add_argument("--audit", action="store_true", help="also print the genie audit rows (verb/ok/ms only)")

    p = sp.add_parser("raw", help="send any verb with JSON args")
    p.add_argument("verb")
    p.add_argument("args", nargs="?", default="{}")
    return ap


def out_json(r):
    print(json.dumps(dict(r), ensure_ascii=False, indent=2))


def run(o, c):
    cmd = o.command
    tab = {"tab": o.tab} if o.tab else {}
    if cmd == "ping":
        r = c.cmd("sys.ping")
    elif cmd == "tabs":
        r = c.cmd("browser.tabs")
        if not o.json:
            for t in r.get("tabs", []):
                print("%s %-10s %s  %s" % ("*" if t.get("active") else " ", t["tab"][:10], t.get("title", "")[:50], t.get("url", "")))
            return 0
    elif cmd == "sh":
        argv = o.argv[1:] if o.argv[:1] == ["--"] else o.argv
        if not argv:
            sys.stderr.write("linuxdesk sh: give a command after --\n")
            return 2
        a = {"argv": argv, "as": "root" if o.root else "user", "timeout_s": o.timeout_s, "cwd": o.cwd}
        if o.stdin:
            a["stdin_b64"] = base64.b64encode(sys.stdin.buffer.read()).decode()
        r = c.cmd("shell.exec", _timeout=o.timeout_s + 15, _raise=False, **a)
        if o.json:
            out_json(r)
        else:
            sys.stdout.write(r.get("stdout", ""))
            sys.stderr.write(r.get("stderr", ""))
            if r.get("truncated"):
                sys.stderr.write("\n[linuxdesk] output truncated\n")
        if "error" in r:
            sys.stderr.write("linuxdesk: %s %s\n" % (r["error"], r.get("detail", "")))
            return 1
        return r.get("code") or 0
    elif cmd == "open":
        r = c.cmd("browser.open", url=o.url, new_tab=o.new or None, **tab)
    elif cmd == "nav":
        a = {o.where: True} if o.where in ("back", "forward", "reload") else {"url": o.where}
        r = c.cmd("browser.navigate", **dict(a, **tab))
    elif cmd == "tree":
        r = c.cmd("browser.tree", mode=o.mode, interactive_only=not o.all, max_nodes=o.max, **tab)
        if not o.json:
            print_tree(r, sys.stdout)
            return 0
    elif cmd == "find":
        r = c.cmd("browser.find", text=o.text, role=o.role, selector=o.selector, **tab)
        if not o.json:
            print_tree(r, sys.stdout)
            return 0
    elif cmd == "click":
        if o.x11:
            if not o.xy:
                sys.stderr.write("linuxdesk click --x11 needs --xy X Y (X screen px)\n")
                return 2
            r = c.cmd("input.click", x=int(o.xy[0]), y=int(o.xy[1]))
        else:
            a = {"ref": o.ref, "text": o.text, "selector": o.selector, "snap": o.snap}
            if o.xy:
                a["x"], a["y"] = o.xy
            if not any(v is not None for v in (o.ref, o.text, o.selector, o.xy)):
                sys.stderr.write("linuxdesk click: give REF, --text, --selector or --xy\n")
                return 2
            r = c.cmd("browser.click", **dict(a, **tab))
    elif cmd == "type":
        if o.x11:
            r = c.cmd("input.type", text=o.text)
        else:
            r = c.cmd("browser.type", text=o.text, ref=o.into, selector=o.selector,
                      clear=o.clear or None, submit=o.submit or None, **tab)
    elif cmd == "key":
        r = c.cmd("input.key" if o.x11 else "browser.key", keys=" ".join(o.keys), **({} if o.x11 else tab))
    elif cmd == "shot":
        fmt = "png" if o.out.lower().endswith(".png") else "jpeg"
        r = c.cmd("screen.capture", source=o.source, format=fmt, scale=o.scale, quality=o.quality, **tab)
        if not isinstance(r, Reply):
            sys.stderr.write("linuxdesk: expected a binary frame\n")
            return 1
        with open(o.out, "wb") as f:
            f.write(r.data)
        print("%s %sx%s %d bytes (%s)" % (os.path.abspath(o.out), r.get("w"), r.get("h"), len(r.data), r.get("mime")))
        return 0
    elif cmd == "watch":
        r = c.cmd("stream.start", kind="screen", source=o.source, fps=o.fps, scale=o.scale, **tab)
        sid = r["sid"]
        if o.out_dir:
            os.makedirs(o.out_dir, exist_ok=True)
        n = 0
        try:
            for f in c.stream(sid):
                if not isinstance(f, Reply):
                    print(json.dumps(dict(f)))
                    break
                n += 1
                line = "seq=%s %sx%s %d bytes" % (f.get("seq"), f.get("w"), f.get("h"), len(f.data))
                if o.out_dir:
                    p = os.path.join(o.out_dir, "frame-%05d.%s" % (f["seq"], "png" if "png" in f.get("mime", "") else "jpg"))
                    with open(p, "wb") as fh:
                        fh.write(f.data)
                    line += " -> " + p
                print(line, flush=True)
                if o.frames and n >= o.frames:
                    break
        except KeyboardInterrupt:
            pass
        r = c.cmd("stream.stop", sid=sid, _raise=False)
        print(json.dumps(dict(r)))
        return 0
    elif cmd == "ls":
        r = c.cmd("fs.list", path=o.path)
        if not o.json:
            for e in r.get("entries", []):
                print("%s %12s  %s" % ("d" if e.get("dir") else "-", e.get("bytes", ""), e.get("name")))
            if r.get("truncated"):
                print("… truncated")
            return 0
    elif cmd == "get":
        dest = o.dest or os.path.basename(o.path.rstrip("/")) or "download"
        off, total = 0, None
        h = hashlib.sha256()
        with open(dest + ".part", "wb") as f:
            while True:
                r = c.cmd("fs.get", path=o.path, offset=off, length=CHUNK)
                f.write(r.data)
                h.update(r.data)
                off += len(r.data)
                total = r.get("total")
                if r.get("eof") or not r.data:
                    break
        os.replace(dest + ".part", dest)
        print("%s %d bytes sha256=%s" % (os.path.abspath(dest), off, h.hexdigest()))
        return 0
    elif cmd == "put":
        h = hashlib.sha256()
        off = 0
        with open(o.src, "rb") as f:
            while True:
                chunk = f.read(CHUNK)
                if not chunk and off > 0:
                    break
                h.update(chunk)
                r = c.cmd("fs.put", path=o.dst, offset=off, data_b64=base64.b64encode(chunk).decode())
                off = r.get("next_offset", off + len(chunk))
                if not chunk:
                    break
        r = c.cmd("fs.put_commit", path=o.dst, sha256=h.hexdigest())
    elif cmd == "propose-delete":
        r = c.cmd("fs.propose_delete", paths=o.paths, reason=o.reason)
        if not o.json:
            # The proposal id is r["proposal"]; r["id"] is only the frame id (the Router overwrites it).
            print("proposal %s: %s files, %s bytes; the phone now asks the person (rejected: %s)" % (
                r.get("proposal"), r.get("count"), r.get("bytes"), r.get("rejected") or "none"))
            return 0
    elif cmd == "proposal":
        r = c.cmd("fs.proposal", proposal=o.proposal_id)
    elif cmd == "cancel":
        r = c.cmd("fs.cancel", proposal=o.proposal_id)
    elif cmd == "execute":
        p = c.cmd("fs.proposal", proposal=o.proposal_id)
        r = c.cmd("fs.execute", proposal=o.proposal_id, mode=o.mode, nonce=p.get("nonce"),
                  selected_hash=p.get("selected_hash"))
        if r.get("state") == "awaiting_confirm":
            print("proposal %s: confirm on the phone (review sheet; nothing is deleted until the person taps Approve)"
                  % o.proposal_id, flush=True)
        deadline = time.time() + o.wait
        while o.wait and time.time() < deadline and r.get("state") in ("awaiting_confirm", "executing"):
            time.sleep(2)
            r = c.cmd("fs.proposal", proposal=o.proposal_id)
        if not o.json:
            print("state: %s%s" % (r.get("state"), (" (%s)" % r["result"]) if r.get("result") else ""))
            return 0
    elif cmd == "logs":
        ident = c.ident
        if o.new:
            cur = load_log_cursor(ident)
            a = {"mode": "delta", "since": int(cur.get("cursor", 0))}
            for k in ("epoch", "audit_since"):
                if k in cur:
                    a[k] = cur[k]
        elif o.all:
            a = {"mode": "all"}
        else:
            a = {"mode": "tail", "n": o.tail or 200}
        r = c.cmd("sys.logs", **a)
        if o.new:
            save_log_cursor(ident, {"cursor": r.get("cursor", 0), "epoch": r.get("epoch"),
                                    "audit_since": r.get("audit_since", 0), "saved_at": int(time.time())})
        if o.json:
            out_json(r)
            return 0
        if r.get("reset"):
            sys.stderr.write("[linuxdesk] the app restarted since the saved cursor; showing everything held\n")
        if r.get("dropped"):
            sys.stderr.write("[linuxdesk] %d lines were lost before this read (buffer rolled over)\n" % r["dropped"])
        if r.get("truncated"):
            sys.stderr.write("[linuxdesk] %d oldest lines cut to fit one reply\n" % r["truncated"])
        for line in r.get("lines", []):
            print(line)
        if o.audit:
            for row in r.get("audit", []):
                print("audit %s" % json.dumps(row, ensure_ascii=False))
        return 0
    elif cmd == "raw":
        try:
            a = json.loads(o.args)
        except ValueError as e:
            sys.stderr.write("raw: args must be a JSON object (%s)\n" % e)
            return 2
        r = c.cmd(o.verb, _raise=False, **a)
        if isinstance(r, Reply):
            print(json.dumps(dict(r, bytes=len(r.data))))
            return 0
        out_json(r)
        return 1 if "error" in r else 0
    else:
        return 2
    out_json(r)
    return 0


def main(argv=None):
    ap = build_parser()
    o = ap.parse_args(argv)
    if not o.command:
        ap.print_help()
        return 2
    node = None
    try:
        if o.command == "pair":
            code = o.code
            if o.line:
                node, code = parse_line(o.line)
            if not code:
                sys.stderr.write("linuxdesk pair: --code or --line is required\n")
                return 2
            with connect(o, node) as c:
                r = c.pair(code, genie=o.genie)
            print("paired with %s %s; token saved to %s (0600)" % (r.get("app"), r.get("version", ""), r["token_saved"]))
            return 0
        with connect(o) as c:
            if o.key_env:
                key = os.environ.get(o.key_env)
                if not key:
                    sys.stderr.write("linuxdesk: env %s is empty\n" % o.key_env)
                    return 2
                c.hello(genie=o.genie, token=key)
            else:
                c.hello(genie=o.genie)
            return run(o, c)
    except RemoteError as e:
        sys.stderr.write("linuxdesk: error %s\n" % e)
        return 1
    except (LinkError, OSError, ValueError) as e:
        sys.stderr.write("linuxdesk: %s\n" % e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
