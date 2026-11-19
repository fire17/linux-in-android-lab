// LinuxDesk phone camera — multipart/x-mixed-replace (MJPEG) parser. Pure; used by bg.js, tested in node.
// Frames are "--boundary\r\n<headers incl. Content-Length>\r\n\r\n<JPEG bytes>\r\n". Without Content-Length it falls
// back to JPEG markers (FFD8 … FFD9).
(function (root) {
  "use strict";
  function indexOf(buf, len, pat, from) {
    outer: for (let i = from; i <= len - pat.length; i++) {
      for (let j = 0; j < pat.length; j++) if (buf[i + j] !== pat[j]) continue outer;
      return i;
    }
    return -1;
  }
  const CRLF2 = [13, 10, 13, 10];
  class MjpegParser {
    constructor(maxBytes) { this.buf = new Uint8Array(1 << 16); this.len = 0; this.max = maxBytes || (8 << 20); }
    push(chunk) {
      if (this.len + chunk.length > this.buf.length) {
        let n = this.buf.length; while (n < this.len + chunk.length) n *= 2;
        if (n > this.max) { this.len = 0; return []; }   // runaway: drop everything
        const b = new Uint8Array(n); b.set(this.buf.subarray(0, this.len)); this.buf = b;
      }
      this.buf.set(chunk, this.len); this.len += chunk.length;
      const frames = [];
      for (;;) {
        const h = indexOf(this.buf, this.len, CRLF2, 0);
        if (h < 0) break;
        const head = new TextDecoder().decode(this.buf.subarray(0, h));
        const m = /content-length:\s*(\d+)/i.exec(head);
        const start = h + 4;
        let end;
        if (m) {
          end = start + parseInt(m[1], 10);
          if (end > this.len) break;
        } else {
          const e = indexOf(this.buf, this.len, [0xff, 0xd9], start);
          if (e < 0) break;
          end = e + 2;
        }
        frames.push(this.buf.slice(start, end));
        this.buf.copyWithin(0, end, this.len); this.len -= end;
      }
      return frames;
    }
  }
  function toBase64(u8) {
    let s = ""; const CH = 0x8000;
    for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }
  function fromBase64(b64) {
    const s = atob(b64); const u8 = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }
  root.LinuxDeskMjpeg = { MjpegParser, toBase64, fromBase64 };
  if (typeof module === "object" && module.exports) module.exports = root.LinuxDeskMjpeg;
})(typeof self !== "undefined" ? self : globalThis);
