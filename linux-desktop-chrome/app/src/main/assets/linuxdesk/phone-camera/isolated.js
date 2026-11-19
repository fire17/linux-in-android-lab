// LinuxDesk phone camera — ISOLATED world bridge between main.js (page world) and bg.js (service worker).
// Page → here: window.postMessage({__ldcam:"req", id, op:"state"|"open"|"close", cam})
// Here → page: window.postMessage({__ldcam:"res", id, ...}) and, for "open", JPEG frames as transferred ArrayBuffers.
(() => {
  "use strict";
  if (!globalThis.chrome || !chrome.runtime || !chrome.runtime.id) return;
  const relays = new Map();
  const reply = (id, data, transfer) => window.postMessage(Object.assign({ __ldcam: "res", id }, data), "*", transfer || []);
  function fromBase64(b64) {
    const s = atob(b64); const u8 = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }
  window.addEventListener("message", (ev) => {
    const m = ev.data;
    if (ev.source !== window || !m || m.__ldcam !== "req") return;
    if (m.op === "state") {
      try {
        chrome.runtime.sendMessage({ type: "ldcam-state" }, (st) => {
          void chrome.runtime.lastError;
          reply(m.id, { state: st || { enabled: false, cams: [] } });
        });
      } catch (_) { reply(m.id, { state: { enabled: false, cams: [] } }); }
    } else if (m.op === "open") {
      let port;
      try { port = chrome.runtime.connect({ name: "ldcam-relay" }); } catch (_) { reply(m.id, { error: "NotReadableError" }); return; }
      relays.set(m.id, port);
      port.onMessage.addListener((f) => {
        if (f.type === "frame") {
          const u8 = fromBase64(f.b64);
          reply(m.id, { frame: u8.buffer }, [u8.buffer]);
          try { port.postMessage({ type: "ack" }); } catch (_) {}
        } else if (f.type === "error") {
          reply(m.id, { error: f.name || "NotReadableError" });
        }
      });
      port.onDisconnect.addListener(() => { relays.delete(m.id); reply(m.id, { ended: true }); });
      port.postMessage({ type: "open", cam: m.cam });
    } else if (m.op === "close") {
      const p = relays.get(m.ref); relays.delete(m.ref);
      try { p && p.disconnect(); } catch (_) {}
    }
  });
})();
