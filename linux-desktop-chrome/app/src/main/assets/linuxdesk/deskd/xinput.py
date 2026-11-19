"""deskd X input: `input.*` verbs through xdotool (XTEST) on DISPLAY=:0 (spec 01 section 1.3).

Coordinates are X screen pixels. xdotool `type` is Unicode-aware, so Hebrew works. Typed text is passed on
xdotool's stdin (`type --file -`), never on argv, so it does not show up in `ps`, and it is never logged.
"""
import os
import re

from common import VerbError, num, run

XDO = "xdotool"


def _xdo(args, stdin=None, timeout=15.0):
    code, out, err = run([XDO] + args, timeout=timeout, stdin=stdin)
    if code != 0:
        msg = err.decode("utf-8", "replace").strip()[:200]
        if "Can't open display" in msg or "cannot open display" in msg.lower():
            raise VerbError("not_ready", "the X display is not up")
        raise VerbError("internal", "xdotool failed: %s" % msg)
    return out.decode("utf-8", "replace")


def _xy(a, need=True):
    if a.get("x") is None or a.get("y") is None:
        if need:
            raise VerbError("bad_args", "x and y are required")
        return None
    return int(num(a, "x", lo=0, hi=65535)), int(num(a, "y", lo=0, hi=65535))


def _button(a):
    b = num(a, "button", 1, kind=int)
    if b not in (1, 2, 3):
        raise VerbError("bad_args", "button must be 1, 2 or 3")
    return str(b)


_KEYS_RE = re.compile(r"^[A-Za-z0-9_+\- ]{1,200}$")


def _keys(a):
    k = str(a.get("keys") or "").strip()
    if not k or not _KEYS_RE.match(k):
        raise VerbError("bad_args", "keys must be xdotool key names, e.g. \"ctrl+l\" or \"Return\"")
    return k.split()


def v_move(a):
    x, y = _xy(a)
    _xdo(["mousemove", str(x), str(y)])
    return {"ok": True}


def v_click(a):
    xy = _xy(a, need=False)
    if xy:
        _xdo(["mousemove", str(xy[0]), str(xy[1])])
    count = num(a, "count", 1, 1, 5, int)
    _xdo(["click", "--repeat", str(count), "--delay", "60", _button(a)])
    return {"ok": True}


def v_down(a):
    _xdo(["mousedown", _button(a)])
    return {"ok": True}


def v_up(a):
    _xdo(["mouseup", _button(a)])
    return {"ok": True}


def v_drag(a):
    try:
        fx, fy = [int(v) for v in a["from"]]
        tx, ty = [int(v) for v in a["to"]]
    except (KeyError, TypeError, ValueError):
        raise VerbError("bad_args", "from:[x,y] and to:[x,y] are required")
    ms = num(a, "ms", 300, 0, 10000, int)
    steps = max(1, min(30, ms // 20))
    b = _button(a)
    args = ["mousemove", str(fx), str(fy), "mousedown", b]
    for i in range(1, steps + 1):
        args += ["sleep", "%.3f" % (ms / 1000.0 / steps),
                 "mousemove", str(int(fx + (tx - fx) * i / steps)), str(int(fy + (ty - fy) * i / steps))]
    args += ["mouseup", b]
    _xdo(args, timeout=15 + ms / 1000.0)
    return {"ok": True}


def v_scroll(a):
    xy = _xy(a, need=False)
    if xy:
        _xdo(["mousemove", str(xy[0]), str(xy[1])])
    dy = num(a, "dy", 0, -100, 100, int)
    dx = num(a, "dx", 0, -100, 100, int)
    if dy:
        _xdo(["click", "--repeat", str(abs(dy)), "--delay", "20", "5" if dy > 0 else "4"])
    if dx:
        _xdo(["click", "--repeat", str(abs(dx)), "--delay", "20", "7" if dx > 0 else "6"])
    return {"ok": True}


def v_type(a):
    text = a.get("text")
    if text is None:
        raise VerbError("bad_args", "text is required")
    text = str(text)
    if len(text) > 100000:
        raise VerbError("bad_args", "text longer than 100000 chars")
    delay = num(a, "delay_ms", 12, 0, 1000, int)
    _xdo(["type", "--delay", str(delay), "--file", "-"], stdin=text.encode("utf-8"),
         timeout=15 + len(text) * (delay + 5) / 1000.0)
    return {"ok": True, "chars": len(text)}


def v_key(a):
    keys = _keys(a)
    repeat = num(a, "repeat", 1, 1, 100, int)
    _xdo(["key", "--repeat", str(repeat)] + keys)
    return {"ok": True}


def v_keydown(a):
    _xdo(["keydown"] + _keys(a))
    return {"ok": True}


def v_keyup(a):
    _xdo(["keyup"] + _keys(a))
    return {"ok": True}


def geometry(wid):
    out = _xdo(["getwindowgeometry", "--shell", str(wid)])
    g = {}
    for ln in out.splitlines():
        if "=" in ln:
            k, v = ln.split("=", 1)
            g[k.strip()] = v.strip()
    return {"x": int(g.get("X", 0)), "y": int(g.get("Y", 0)), "w": int(g.get("WIDTH", 0)), "h": int(g.get("HEIGHT", 0))}


def windows():
    out = _xdo(["search", "--onlyvisible", "--name", ""])
    res = []
    for wid in out.split()[:100]:
        try:
            g = geometry(wid)
        except VerbError:
            continue
        title = ""
        try:
            title = _xdo(["getwindowname", wid]).strip()
        except VerbError:
            pass
        cls = ""
        try:
            code, o, _ = run(["xprop", "-id", wid, "WM_CLASS"], timeout=5)
            m = re.search(r'"([^"]*)"\s*$', o.decode("utf-8", "replace"))
            cls = m.group(1) if (code == 0 and m) else ""
        except VerbError:
            pass
        res.append({"wid": int(wid), "title": title[:200], "class": cls, "x": g["x"], "y": g["y"], "w": g["w"], "h": g["h"]})
    return res


def v_windows(a):
    return {"ok": True, "windows": windows()}


def v_activate(a):
    wid = num(a, "wid", kind=int)
    if wid is None:
        raise VerbError("bad_args", "wid is required")
    _xdo(["windowactivate", "--sync", str(wid)], timeout=10)
    return {"ok": True}


def browser_window():
    """(x, y, w, h) of the biggest Chromium window, or None."""
    try:
        out = _xdo(["search", "--onlyvisible", "--class", "chromium"])
    except VerbError:
        return None
    best = None
    for wid in out.split():
        try:
            g = geometry(wid)
        except VerbError:
            continue
        if best is None or g["w"] * g["h"] > best["w"] * best["h"]:
            best = g
    return best


def screen_size():
    out = _xdo(["getdisplaygeometry"])
    w, h = out.split()[:2]
    return int(w), int(h)


def available():
    return run_ok([XDO, "version"])


def run_ok(argv):
    try:
        return run(argv, timeout=5)[0] == 0
    except VerbError:
        return False


VERBS = {
    "input.move": v_move, "input.click": v_click, "input.down": v_down, "input.up": v_up, "input.drag": v_drag,
    "input.scroll": v_scroll, "input.type": v_type, "input.key": v_key, "input.keydown": v_keydown,
    "input.keyup": v_keyup, "input.windows": v_windows, "input.activate": v_activate,
}
os.environ.setdefault("DISPLAY", ":0")
