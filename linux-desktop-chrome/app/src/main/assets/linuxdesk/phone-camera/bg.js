// LinuxDesk phone camera — extension service worker.
//  - "ldcam-state": reads state.json LIVE (cache: no-store; the app rewrites it on every ON/OFF) and returns the
//    PAGE view of it: in relay-only mode (state.relayOnly, a setting that is OFF by default) the port and token are
//    stripped, so page scripts never see them; only this worker uses them.
//  - port "ldcam-relay": fetches http://127.0.0.1:PORT/stream?t=TOKEN&cam=ID here (the extension has host
//    permission, so the page's Local Network Access / CORS rules do not apply) and relays each JPEG to the content
//    script as base64 (MV3 messaging is JSON). Only the newest frame is kept while the content script has not acked
//    the previous one, so a slow page never builds a queue. The fallback transport, or the only one in relay-only.
//  - port "ldcam-watch": every frame keeps one open; while any is open this worker polls state.json each second and
//    pushes {type:"state"} on change, so OFF reaches live tracks (→ "ended") and devicechange fires promptly.
importScripts("mjpeg.js");

async function readState() {
  try {
    const r = await fetch(chrome.runtime.getURL("state.json") + "?ts=" + Date.now(), { cache: "no-store" });
    const s = await r.json();
    if (!s || !s.enabled || !s.port || !s.token) return { enabled: false, cams: [] };
    return { enabled: true, port: s.port, token: s.token, ask: !!s.ask, relayOnly: !!s.relayOnly,
      cams: Array.isArray(s.cams) ? s.cams : [] };
  } catch (_) {
    return { enabled: false, cams: [] };
  }
}

/** What a page may see. Pure (exported for the node test via self.LinuxDeskPageView). */
function pageView(st) {
  if (!st || !st.enabled) return { enabled: false, cams: [] };
  const v = { enabled: true, ask: !!st.ask, relayOnly: !!st.relayOnly, cams: st.cams || [] };
  if (!st.relayOnly) { v.port = st.port; v.token = st.token; }
  return v;
}
self.LinuxDeskPageView = pageView;

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg && msg.type === "ldcam-state") { readState().then((st) => reply(pageView(st))); return true; }
  return false;
});

// ------------------------------------------------------------------ live state push
const watchers = new Set();
let watchTimer = null, lastKey = null;
const stateKey = (st) => st.enabled ? (st.relayOnly ? "R" : "D") + ":" + st.port + ":" + st.token + ":" +
  (st.cams || []).map(c => c.id).join(",") : "off";
async function pollState() {
  const st = await readState();
  const key = stateKey(st);
  if (key === lastKey) return;
  lastKey = key;
  const v = pageView(st);
  for (const p of watchers) { try { p.postMessage({ type: "state", state: v }); } catch (_) { watchers.delete(p); } }
}
function ensureWatch() {
  if (watchers.size && !watchTimer) watchTimer = setInterval(pollState, 1000);
  if (!watchers.size && watchTimer) { clearInterval(watchTimer); watchTimer = null; lastKey = null; }
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name === "ldcam-watch") {
    watchers.add(port);
    port.onDisconnect.addListener(() => { watchers.delete(port); ensureWatch(); });
    ensureWatch();
    readState().then((st) => { lastKey = lastKey ?? stateKey(st); try { port.postMessage({ type: "state", state: pageView(st) }); } catch (_) {} });
    return;
  }
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
    // Stream ended (server stopped = switch OFF, or the camera failed): tell the page why.
    if (!closed) {
      const now = await readState();
      try { port.postMessage({ type: "ended", reason: now.enabled ? "failed" : "off" }); } catch (_) {}
    }
    stop();
  });
});
