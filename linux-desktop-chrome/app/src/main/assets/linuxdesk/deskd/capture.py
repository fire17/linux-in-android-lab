"""deskd screen capture: `screen.capture` source x11 (xwd -root) and the encoders (spec 01 1.3, spec 02 #4).

x11 path: `xwd -root -silent` -> XWD parsed here (stdlib) -> crop/scale -> encode with the first that works:
  1. Pillow (py3-pillow, in-process, the cheapest under proot),
  2. ImageMagick (`magick` / `convert` xwd:- ... jpeg:-),
  3. stdlib PNG (zlib) when neither is installed (format falls back to png).
Defaults (spec 02 #4): scale 0.5, quality 60. `capture_ms_last` is kept for screen.info.
"""
import io
import struct
import time
import zlib

from common import VerbError, image_size, run, which

LAST = {"ms": None, "source": None, "encoder": None}


def _note(ms, source, encoder):
    LAST.update(ms=int(ms), source=source, encoder=encoder)


# ----------------------------------------------------------------------------------------------
# XWD (X Window Dump) parser: TrueColor ZPixmap 24/32 bpp, either byte order

def parse_xwd(data):
    """-> (width, height, rgb bytes). Raises VerbError for formats deskd does not handle."""
    if len(data) < 100:
        raise VerbError("internal", "xwd output too short")
    f = struct.unpack(">25I", data[:100])
    header_size, version, pix_fmt, depth, w, h = f[0], f[1], f[2], f[3], f[4], f[5]
    byte_order, bpp, bpl = f[7], f[11], f[12]
    rmask, gmask, bmask, ncolors = f[14], f[15], f[16], f[19]
    if version != 7 or pix_fmt != 2:
        raise VerbError("internal", "unsupported xwd (version %d, format %d)" % (version, pix_fmt))
    if bpp not in (24, 32):
        raise VerbError("internal", "unsupported xwd depth: %d bits per pixel" % bpp)
    off = header_size + ncolors * 12
    px = data[off:off + bpl * h]
    if len(px) < bpl * h:
        raise VerbError("internal", "xwd pixel data truncated")
    step = bpp // 8
    # Byte index of each channel inside a pixel, from the masks and the image byte order (0 = LSBFirst).
    def idx(mask):
        shift = (mask & -mask).bit_length() - 1
        b = shift // 8
        return b if byte_order == 0 else step - 1 - b
    ri, gi, bi = idx(rmask), idx(gmask), idx(bmask)
    rgb = bytearray(w * h * 3)
    row_px = w * step
    for y in range(h):
        row = px[y * bpl:y * bpl + row_px]
        o = y * w * 3
        rgb[o:o + w * 3:3] = row[ri::step]
        rgb[o + 1:o + w * 3:3] = row[gi::step]
        rgb[o + 2:o + w * 3:3] = row[bi::step]
    return w, h, bytes(rgb)


def crop_scale(w, h, rgb, region=None, scale=1.0):
    """Crop to region [x,y,w,h] then nearest-neighbour scale (stdlib; slices only, no per-pixel Python)."""
    if region:
        x0, y0, cw, ch = [int(v) for v in region]
        x0, y0 = max(0, min(x0, w - 1)), max(0, min(y0, h - 1))
        cw, ch = max(1, min(cw, w - x0)), max(1, min(ch, h - y0))
        out = bytearray(cw * ch * 3)
        for y in range(ch):
            s = ((y0 + y) * w + x0) * 3
            out[y * cw * 3:(y + 1) * cw * 3] = rgb[s:s + cw * 3]
        w, h, rgb = cw, ch, bytes(out)
    if scale >= 0.999:
        return w, h, rgb
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    xs = [int(x / scale) for x in range(nw)]
    out = bytearray(nw * nh * 3)
    for y in range(nh):
        sy = min(h - 1, int(y / scale))
        row = rgb[sy * w * 3:(sy + 1) * w * 3]
        o = y * nw * 3
        # Gather by channel with a precomputed index list (still Python-level, but only nw*3 per row).
        line = bytearray(nw * 3)
        for c in range(3):
            ch = row[c::3]
            line[c::3] = bytes(ch[x] for x in xs)
        out[o:o + nw * 3] = line
    return nw, nh, bytes(out)


def png_encode(w, h, rgb):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgb[y * w * 3:(y + 1) * w * 3]

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def _pillow():
    try:
        from PIL import Image  # py3-pillow; optional
        return Image
    except Exception:
        return None


def _magick():
    return which("magick") or which("convert")


def encode_xwd(xwd, fmt="jpeg", quality=60, scale=0.5, region=None):
    """-> (bytes, w, h, mime, encoder)."""
    Image = _pillow()
    if Image is not None:
        w, h, rgb = parse_xwd(xwd)
        im = Image.frombytes("RGB", (w, h), rgb)
        if region:
            x, y, cw, ch = [int(v) for v in region]
            im = im.crop((x, y, x + cw, y + ch))
        if scale < 0.999:
            im = im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.BILINEAR)
        buf = io.BytesIO()
        if fmt == "png":
            im.save(buf, "PNG", compress_level=3)
        else:
            im.save(buf, "JPEG", quality=int(quality))
        return buf.getvalue(), im.width, im.height, "image/" + fmt, "pillow"
    mg = _magick()
    if mg:
        args = [mg, "xwd:-"]
        if region:
            x, y, cw, ch = [int(v) for v in region]
            args += ["-crop", "%dx%d+%d+%d" % (cw, ch, x, y), "+repage"]
        if scale < 0.999:
            args += ["-resize", "%g%%" % (scale * 100)]
        if fmt == "jpeg":
            args += ["-quality", str(int(quality)), "jpeg:-"]
        else:
            args += ["png:-"]
        code, out, err = run(args, timeout=30, stdin=xwd)
        if code == 0 and out:
            w, h = image_size(out)
            return out, w, h, "image/" + fmt, "imagemagick"
    w, h, rgb = parse_xwd(xwd)
    w, h, rgb = crop_scale(w, h, rgb, region, scale)
    return png_encode(w, h, rgb), w, h, "image/png", "stdlib-png"


def grab_xwd():
    if not which("xwd"):
        raise VerbError("not_ready", "xwd is not installed (setup-genie.sh)")
    code, out, err = run(["xwd", "-root", "-silent"], timeout=20)
    if code != 0 or not out:
        msg = err.decode("utf-8", "replace").strip()[:200]
        raise VerbError("not_ready" if "display" in msg.lower() else "internal", "xwd failed: %s" % msg)
    return out


def capture_x11(fmt="jpeg", quality=60, scale=0.5, region=None):
    t0 = time.time()
    data, w, h, mime, enc = encode_xwd(grab_xwd(), fmt, quality, scale, region)
    _note((time.time() - t0) * 1000, "x11", enc)
    return data, w, h, mime


def args_of(a, fps_max=None):
    """Validated capture args with the spec 02 #4 defaults."""
    from common import num
    fmt = str(a.get("format", "jpeg")).lower()
    if fmt not in ("jpeg", "png"):
        raise VerbError("bad_args", "format must be jpeg or png")
    region = a.get("region")
    if region is not None:
        if not (isinstance(region, list) and len(region) == 4):
            raise VerbError("bad_args", "region must be [x, y, w, h]")
        try:
            region = [int(v) for v in region]
        except (TypeError, ValueError):
            raise VerbError("bad_args", "region must be 4 integers")
    out = {"format": fmt, "quality": num(a, "quality", 60, 1, 100, int), "scale": num(a, "scale", 0.5, 0.1, 1.0),
           "region": region, "source": str(a.get("source", "x11"))}
    if out["source"] not in ("x11", "browser"):
        raise VerbError("bad_args", "source must be x11 or browser")
    if fps_max is not None:
        out["fps"] = num(a, "fps", 2, 0.1, fps_max)
    return out


def xwd_cost(n=10):
    """Self-test #4: n timed captures at scale 0.5 -> (p50, p90) ms."""
    ts = []
    for _ in range(n):
        t0 = time.time()
        capture_x11("jpeg", 60, 0.5)
        ts.append((time.time() - t0) * 1000)
    ts.sort()
    return int(ts[len(ts) // 2]), int(ts[min(len(ts) - 1, int(len(ts) * 0.9))])
