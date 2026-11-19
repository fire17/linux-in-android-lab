"""linuxdesk_client: the genie side of the linuxdesk/1 wire (spec notes/spec/01-protocol-and-lanes.md).

One frame codec for both transports:
  request  = u32 BE length || UTF-8 JSON                                  (<= 1 MiB)
  reply    = u32 BE length || 0x00 || JSON          (type 0)
           | u32 BE length || 0x01 || u16 hlen || JSON header || bytes     (type 1: screenshots, streams, fs.get)

    from linuxdesk_client import Conn
    c = Conn.tcp("127.0.0.1", 8766)              # phase A (adb forward to the app's WireServer)
    c = Conn.iroh("<node-id-hex>")               # phase B (pip iroh==1.1.0, ALPN linuxdesk/1)
    c.pair("ABC234", genie="agent:me")           # once; stores the token at ~/.config/linuxdesk/<id>.json (0600)
    c.hello(genie="agent:me")                    # later connects: token from the store
    r = c.cmd("browser.tree")                    # JSON reply (dict); binary replies come back as Reply
    for frame in c.stream(sid): ...

Replies to requests with an `id` are matched by id, so several threads may call cmd() at once.
The token is never printed or logged; it lives only in the token store and in this process's memory.
Stdlib only (iroh is optional and imported lazily).
"""
import asyncio
import json
import os
import queue
import socket
import stat
import struct
import threading
import time

PROTO = "linuxdesk/1"
ALPN = b"linuxdesk/1"
MAX_REQ = 1 << 20
MAX_REPLY = 8 << 20
STORE_DIR = os.path.expanduser(os.environ.get("LINUXDESK_HOME", "~/.config/linuxdesk"))


class LinkError(Exception):
    """Transport / protocol trouble (connection refused, closed, bad frame)."""


class RemoteError(Exception):
    """The other side answered {"error": ...}."""

    def __init__(self, reply):
        self.reply = reply
        self.code = reply.get("error")
        self.detail = reply.get("detail", "")
        Exception.__init__(self, "%s%s" % (self.code, (": " + self.detail) if self.detail else ""))


class Reply(dict):
    """A type-1 (binary) reply: the JSON header as a dict, plus .data bytes."""

    def __init__(self, header, data):
        dict.__init__(self, header)
        self.data = data


# ----------------------------------------------------------------------------------------------
# Codec

def encode_request(obj):
    body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(body) > MAX_REQ:
        raise LinkError("request of %d bytes > 1 MiB" % len(body))
    return struct.pack(">I", len(body)) + body


def decode_reply(payload):
    if not payload:
        raise LinkError("empty reply frame")
    typ, body = payload[0], payload[1:]
    if typ == 0:
        return json.loads(body.decode("utf-8"))
    if typ == 1:
        if len(body) < 2:
            raise LinkError("short binary frame")
        hl = struct.unpack(">H", body[:2])[0]
        return Reply(json.loads(body[2:2 + hl].decode("utf-8")), bytes(body[2 + hl:]))
    raise LinkError("unknown reply type byte %d" % typ)


# ----------------------------------------------------------------------------------------------
# Transports: send(bytes) / recv_exact(n) -> bytes / close()

class TcpTransport(object):
    def __init__(self, host, port, timeout=15.0):
        try:
            self.sock = socket.create_connection((host, int(port)), timeout=timeout)
        except OSError as e:
            raise LinkError("cannot connect to %s:%s (%s); for phase A run `adb forward tcp:%s tcp:%s`"
                            % (host, port, e, port, port))
        self.sock.settimeout(None)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.name = "%s:%s" % (host, port)

    def send(self, data):
        self.sock.sendall(data)

    def recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(min(1 << 20, n - len(buf)))
            if not chunk:
                raise LinkError("connection closed")
            buf += chunk
        return bytes(buf)

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class IrohTransport(object):
    """pip iroh==1.1.0: Endpoint.bind -> connect(EndpointAddr, ALPN) -> open_bi. Runs its own asyncio loop thread."""

    def __init__(self, node_id, relay_url=None, addrs=None, timeout=30.0):
        try:
            import iroh
        except ImportError:
            raise LinkError("Conn.iroh needs `pip install iroh==1.1.0`")
        self.name = "iroh:%s" % node_id[:12]
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, name="iroh-loop", daemon=True).start()

        async def setup():
            iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
            ep = await iroh.Endpoint.bind(iroh.EndpointOptions(preset=iroh.preset_n0()))
            addr = iroh.EndpointAddr(iroh.EndpointId.from_string(node_id), relay_url, list(addrs or []))
            conn = await ep.connect(addr, ALPN)
            bi = await conn.open_bi()
            return ep, conn, bi.send(), bi.recv()
        try:
            self.ep, self.conn, self.tx, self.rx = self._run(setup(), timeout)
        except LinkError:
            raise
        except Exception as e:
            raise LinkError("iroh connect to %s failed: %s" % (node_id[:12], e))

    def _run(self, coro, timeout=None):
        fut = asyncio.run_coroutine_threadsafe(coro, self.loop)
        try:
            return fut.result(timeout)
        except Exception as e:
            if isinstance(e, LinkError):
                raise
            raise LinkError("iroh: %s" % e)

    def send(self, data):
        self._run(self.tx.write_all(data))

    def recv_exact(self, n):
        return bytes(self._run(self.rx.read_exact(n)))

    def close(self):
        async def down():
            try:
                await self.tx.finish()
            except Exception:
                pass
            try:
                self.conn.close(0, b"bye")
            except Exception:
                pass
            try:
                await self.ep.close()
            except Exception:
                pass
        try:
            self._run(down(), 5)
        except LinkError:
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)


# ----------------------------------------------------------------------------------------------
# Token store: ~/.config/linuxdesk/<node-or-local>.json, mode 0600, never under a repo

def _store_path(ident):
    safe = "".join(ch for ch in ident if ch.isalnum() or ch in "-_.") or "local"
    return os.path.join(STORE_DIR, safe + ".json")


def _refuse_repo_path(path):
    real = os.path.realpath(path)
    if "ideas-1911-lab" in real.split(os.sep):
        raise LinkError("refusing to store a token under ideas-1911-lab (public repo): %s" % os.path.dirname(real))


def save_token(ident, token, genie, extra=None):
    path = _store_path(ident)
    _refuse_repo_path(path)
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    os.chmod(os.path.dirname(path), 0o700)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(dict(extra or {}, id=ident, token=token, genie=genie, paired_at=int(time.time())), f)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return path


def load_token(ident):
    path = _store_path(ident)
    try:
        st = os.stat(path)
    except OSError:
        return None
    if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        os.chmod(path, 0o600)
    with open(path) as f:
        return json.load(f)


# ----------------------------------------------------------------------------------------------
# Connection

class Conn(object):
    def __init__(self, transport, ident, timeout=90.0):
        self.t = transport
        self.ident = ident
        self.timeout = timeout
        self.wlock = threading.Lock()
        self.plock = threading.Lock()
        self.next_id = 0
        self.waiting = {}            # id -> Queue
        self.unsolicited = queue.Queue()   # replies without id (hello, legacy servers)
        self.streams = {}            # sid -> Queue
        self.closed = False
        self.close_reason = None
        self.hello_reply = None
        self.reader = threading.Thread(target=self._read_loop, name="linuxdesk-reader", daemon=True)
        self.reader.start()

    # --- constructors ---
    @classmethod
    def tcp(cls, host="127.0.0.1", port=8766, ident="local", timeout=90.0):
        return cls(TcpTransport(host, port), ident, timeout)

    @classmethod
    def iroh(cls, node_id, relay_url=None, addrs=None, timeout=90.0):
        return cls(IrohTransport(node_id, relay_url, addrs), node_id, timeout)

    # --- reader ---
    def _read_loop(self):
        try:
            while True:
                n = struct.unpack(">I", self.t.recv_exact(4))[0]
                if n > MAX_REPLY + 16:
                    raise LinkError("reply frame of %d bytes > 8 MiB" % n)
                msg = decode_reply(self.t.recv_exact(n))
                self._route(msg)
        except (LinkError, OSError, ValueError, struct.error) as e:
            self.close_reason = str(e)
        finally:
            self.closed = True
            with self.plock:
                waits = list(self.waiting.values()) + list(self.streams.values()) + [self.unsolicited]
            for q in waits:
                q.put(None)

    def _route(self, msg):
        mid = msg.get("id")
        if mid is not None:
            with self.plock:
                q = self.waiting.pop(mid, None)
            (q or self.unsolicited).put(msg)
            return
        sid = msg.get("sid")
        if sid is not None:
            with self.plock:
                q = self.streams.get(sid)
                if q is None:
                    q = self.streams[sid] = queue.Queue()
            q.put(msg)
            return
        self.unsolicited.put(msg)

    # --- requests ---
    def send(self, obj):
        if self.closed:
            raise LinkError("connection closed" + (": " + self.close_reason if self.close_reason else ""))
        with self.wlock:
            self.t.send(encode_request(obj))

    def request(self, obj, timeout=None):
        with self.plock:
            self.next_id += 1
            mid = self.next_id
            q = self.waiting[mid] = queue.Queue()
        obj = dict(obj, id=mid)
        self.send(obj)
        try:
            r = q.get(timeout=timeout or self.timeout)
        except queue.Empty:
            with self.plock:
                self.waiting.pop(mid, None)
            raise LinkError("no reply to %s within %ss" % (obj.get("cmd"), timeout or self.timeout))
        if r is None:
            raise LinkError("connection closed" + (": " + self.close_reason if self.close_reason else ""))
        return r

    def cmd(self, verb, _timeout=None, _raise=True, **args):
        msg = {"cmd": verb}
        msg.update({k: v for k, v in args.items() if v is not None})
        r = self.request(msg, timeout=_timeout)
        if _raise and "error" in r:
            raise RemoteError(r)
        return r

    def notify(self, verb, **args):
        """A frame that gets no reply (stream.ack)."""
        msg = {"cmd": verb}
        msg.update(args)
        self.send(msg)

    # --- auth ---
    def _hello(self, auth, genie):
        self.send({"auth": auth, "genie": genie, "proto": PROTO})
        try:
            r = self.unsolicited.get(timeout=30)
        except queue.Empty:
            raise LinkError("no hello reply")
        if r is None:
            raise LinkError("connection closed during hello" + (": " + self.close_reason if self.close_reason else ""))
        if "error" in r:
            raise RemoteError(r)
        self.hello_reply = r
        return r

    def pair(self, code, genie="genie-client"):
        """Trades a 6-char pairing code for a token; stores the token (0600) and never returns it."""
        r = self._hello(str(code).strip().upper(), genie)
        tok = r.pop("token", None)
        if not tok:
            raise LinkError("pairing reply carried no token (already paired? use hello)")
        path = save_token(self.ident, tok, genie, {"app": r.get("app")})
        r["token_saved"] = path
        return r

    def hello(self, genie=None, token=None):
        """Authenticates with the stored token (or an explicit token / deskd key)."""
        if token is None:
            st = load_token(self.ident)
            if not st:
                raise LinkError("no token for %s; pair first (linuxdesk pair --code ...)" % self.ident)
            token, genie = st["token"], genie or st.get("genie")
        r = self._hello(token, genie or "genie-client")
        r.pop("token", None)
        return r

    # --- streams ---
    def stream(self, sid, ack=True, timeout=None):
        """Yields frames (Reply for data, dict for exit/error) of stream `sid` until it ends; acks each data frame."""
        with self.plock:
            q = self.streams.get(sid)
            if q is None:
                q = self.streams[sid] = queue.Queue()
        while True:
            try:
                m = q.get(timeout=timeout)
            except queue.Empty:
                return
            if m is None:
                return
            yield m
            if isinstance(m, Reply) and ack and "seq" in m and not str(sid).startswith("p"):
                try:
                    self.notify("stream.ack", sid=sid, seq=m["seq"])
                except LinkError:
                    return
            if not isinstance(m, Reply) and ("exit" in m or "error" in m):
                return

    def close(self):
        self.closed = True
        self.t.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
