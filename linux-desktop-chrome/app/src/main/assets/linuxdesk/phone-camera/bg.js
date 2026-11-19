// LinuxDesk phone camera — extension service worker.
//  - "state": reads state.json LIVE (cache: no-store; the app rewrites it on every ON/OFF) and returns it.
//  - port "ldcam-relay": the fallback transport. Fetches http://127.0.0.1:PORT/stream?t=TOKEN&cam=ID here (the
//    extension has host permission, so the page's Local Network Access / CORS rules do not apply) and relays
//    each JPEG to the content script as base64 (MV3 messaging is JSON). Only the newest frame is kept while the
//    content script has not acked the previous one, so a slow page never builds a queue.
importScripts("mjpeg.js");

async function readState() {
  try {
    const r = await fetch(chrome.runtime.getURL("state.json") + "?ts=" + Date.now(), { cache: "no-store" });
    const s = await r.json();
    if (!s || !s.enabled || !s.port || !s.token) return { enabled: false, cams: [] };
    return { enabled: true, port: s.port, token: s.token, ask: !!s.ask, cams: Array.isArray(s.cams) ? s.cams : [] };
  } catch (_) {
    return { enabled: false, cams: [] };
  }
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg && msg.type === "ldcam-state") { readState().then(reply); return true; }
  return false;
});

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "ldcam-relay") return;
  let ctrl = null, closed = false, waiting = false, pending = null;
  const send = (f) => {
    if (closed) return;
    if (waiting) { pending = f; return; }
    waiting = true;
    try { port.postMessage({ type: "frame", b64: LinuxDeskMjpeg.toBase64(f) }); } catch (_) { stop(); }
  };
  const stop = () => {
    if (closed) return;
    closed = true;
    try { ctrl && ctrl.abort(); } catch (_) {}
    try { port.disconnect(); } catch (_) {}
  };
  port.onDisconnect.addListener(() => { closed = true; try { ctrl && ctrl.abort(); } catch (_) {} });
  port.onMessage.addListener(async (msg) => {
    if (!msg) return;
    if (msg.type === "ack") { waiting = false; if (pending) { const f = pending; pending = null; send(f); } return; }
    if (msg.type !== "open" || ctrl) return;
    const st = await readState();
    if (!st.enabled) { try { port.postMessage({ type: "error", name: "NotAllowedError" }); } catch (_) {} stop(); return; }
    ctrl = new AbortController();
    const url = "http://127.0.0.1:" + st.port + "/stream?t=" + encodeURIComponent(st.token) +
      "&cam=" + encodeURIComponent(String(msg.cam || ""));
    try {
      const r = await fetch(url, { signal: ctrl.signal, cache: "no-store" });
      if (!r.ok || !r.body) throw new Error("HTTP " + r.status);
      const parser = new LinuxDeskMjpeg.MjpegParser();
      const rd = r.body.getReader();
      for (;;) {
        const { value, done } = await rd.read();
        if (done || closed) break;
        const frames = parser.push(value);
        if (frames.length) send(frames[frames.length - 1]);
      }
    } catch (e) {
      if (!closed) try { port.postMessage({ type: "error", name: "NotReadableError", detail: String(e) }); } catch (_) {}
    }
    stop();
  });
});
