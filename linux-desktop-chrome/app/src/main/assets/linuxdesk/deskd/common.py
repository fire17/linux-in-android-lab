"""deskd shared bits: the protocol error type, the log line, small helpers. Python 3 stdlib only."""
import os
import shutil
import struct
import subprocess
import sys
import time


class VerbError(Exception):
    """A refusal with a linuxdesk/1 error code (spec 01 section 1.1)."""

    def __init__(self, code, detail=""):
        Exception.__init__(self, detail or code)
        self.code = code
        self.detail = detail


def log(msg):
    # Never pass auth values, typed text, stdin or file contents to this function.
    sys.stdout.write("[deskd] %s %s\n" % (time.strftime("%H:%M:%S"), msg))
    sys.stdout.flush()


def which(name):
    return shutil.which(name)


def num(a, key, default=None, lo=None, hi=None, kind=float):
    """Reads a numeric arg; raises bad_args on junk; clamps to [lo, hi]."""
    v = a.get(key, default)
    if v is None:
        return None
    try:
        v = kind(v)
    except (TypeError, ValueError):
        raise VerbError("bad_args", "%s must be a number" % key)
    if lo is not None and v < lo:
        v = kind(lo)
    if hi is not None and v > hi:
        v = kind(hi)
    return v


def run(argv, timeout=15.0, stdin=None, env=None):
    """Runs a helper binary (xdotool, xwd, ...) and returns (code, stdout bytes, stderr bytes)."""
    try:
        p = subprocess.run(argv, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, env=env)
    except FileNotFoundError:
        raise VerbError("not_ready", "%s is not installed" % os.path.basename(argv[0]))
    except subprocess.TimeoutExpired:
        raise VerbError("timeout", "%s took longer than %ss" % (os.path.basename(argv[0]), timeout))
    return p.returncode, p.stdout, p.stderr


def image_size(data):
    """(w, h) of a PNG or JPEG, or (None, None)."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        return struct.unpack(">II", data[16:24])
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    return None, None
