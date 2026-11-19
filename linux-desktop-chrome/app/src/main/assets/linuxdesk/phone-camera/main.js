// LinuxDesk phone camera v2 — MAIN world, document_start, all frames.
// Adds EVERY phone camera as a videoinput in enumerateDevices() and serves getUserMedia({video}) for them from the
// app's loopback MJPEG server, drawn into canvas.captureStream().
//
// Live state (enabled, port, token, cameras) comes from state.json through isolated.js → bg.js, so the in-app switch
// can turn the camera ON/OFF without a Chromium restart. OFF → our devices disappear and getUserMedia for them
// rejects with NotAllowedError. config.js is only the bootstrap (and the fallback when the bridge is missing).
//
// Transport per stream: "direct" = <img src=http://127.0.0.1:PORT/stream?t=TOKEN&cam=ID> (fast); if that fails
// (Chromium Local Network Access, mixed content, CSP img-src…) → "relay" = the extension service worker fetches the
// MJPEG and relays JPEG frames (decoded with createImageBitmap). Audio always goes to the real getUserMedia.
//
// v3: relay-only privacy mode (setting, default OFF): the state this script gets has no port/token, so it never
// builds a 127.0.0.1 URL and every frame comes through the service worker. OFF mid-session: bg.js pushes the new
// state → every live phone-camera track ends (readyState "ended" + an "ended" event) and devicechange fires.
(() => {
  "use strict";
  const boot = (typeof __LINUXDESK_CAM__ === "object" && __LINUXDESK_CAM__) || {};
  try { delete window.__LINUXDESK_CAM__; } catch (_) { window.__LINUXDESK_CAM__ = undefined; }
  const md = navigator.mediaDevices;
  if (!md || md.__linuxdeskPhoneCam) return;
  Object.defineProperty(md, "__linuxdeskPhoneCam", { value: true });

  const PREFIX = "linuxdesk-cam-";
  const GROUP_ID = "linuxdesk-phone";
  const origEnum = md.enumerateDevices.bind(md);
  const origGUM = md.getUserMedia.bind(md);

  // ---------------------------------------------------------------- bridge to isolated.js / bg.js
  let seq = 0;
  const waiters = new Map();
  window.addEventListener("message", (ev) => {
    const m = ev.data;
    if (ev.source !== window || !m) return;
    if (m.__ldcam === "push") { onPush(m.state); return; }
    if (m.__ldcam !== "res") return;
    const w = waiters.get(m.id);
    if (w) w(m);
  });
  function request(op, extra, onMsg, timeoutMs) {
    const id = "p" + (++seq) + "-" + Math.random().toString(36).slice(2);
    return new Promise((resolve) => {
      let t = null;
      waiters.set(id, (m) => {
        if (onMsg) { onMsg(m, id); return; }
        clearTimeout(t); waiters.delete(id); resolve(m);
      });
      if (!onMsg) t = setTimeout(() => { waiters.delete(id); resolve(null); }, timeoutMs || 1500);
      window.postMessage(Object.assign({ __ldcam: "req", id, op }, extra || {}), "*");
      if (onMsg) resolve(id);
    });
  }

  // Without the bridge (e.g. a sandboxed frame) only config.js is left: direct mode only, never in relay-only.
  const bootState = () => (!boot.relayOnly && boot.enabled !== false && boot.port && boot.token)
    ? { enabled: true, port: boot.port, token: boot.token, ask: !!boot.ask,
        cams: [{ id: "", label: "Phone camera", facing: "back", width: 1280, height: 720 }] }
    : { enabled: false, cams: [] };

  let cached = null, cachedAt = 0;
  async function state() {
    if (cached && Date.now() - cachedAt < 1000) return cached;
    const r = await request("state", null, null, 1500);
    cached = (r && r.state) ? r.state : bootState();   // no bridge (e.g. sandboxed frame): fall back to config.js
    cachedAt = Date.now();
    noteState(cached);
    return cached;
  }

  // ---------------------------------------------------------------- live tracks + state changes
  const live = new Set();          // {track, end(reason)} of every running phone-camera track in this frame
  let lastKey = null;
  const keyOf = (st) => st && st.enabled ? (st.relayOnly ? "R" : "D") + ":" + (st.cams || []).map(c => c.id).join(",") : "off";
  function noteState(st) {
    const key = keyOf(st);
    const changed = lastKey !== null && key !== lastKey;
    lastKey = key;
    if (!changed) return;
    if (!st || !st.enabled) for (const t of [...live]) t.end("off");   // OFF: end every live track at once
    try { md.dispatchEvent(new Event("devicechange")); } catch (_) {}
  }
  function onPush(st) {
    if (!st) return;
    cached = st; cachedAt = Date.now();
    noteState(st);
  }

  // ---------------------------------------------------------------- devices
  const devId = (cam) => PREFIX + (cam.id === "" ? "default" : cam.id);
  function fakeInfo(cam) {
    const v = { deviceId: devId(cam), groupId: GROUP_ID, kind: "videoinput", label: cam.label || "Phone camera" };
    const o = {};
    try { Object.setPrototypeOf(o, (window.InputDeviceInfo || window.MediaDeviceInfo).prototype); } catch (_) {}
    for (const k of Object.keys(v)) Object.defineProperty(o, k, { value: v[k], enumerable: true });
    o.toJSON = () => Object.assign({}, v);
    o.getCapabilities = () => caps(cam);
    return o;
  }
  const facingMode = (cam) => cam.facing === "front" ? "user" : cam.facing === "back" ? "environment" : undefined;
  function caps(cam) {
    const c = { deviceId: devId(cam), groupId: GROUP_ID, width: { min: 1, max: cam.width || 1280 },
      height: { min: 1, max: cam.height || 720 }, frameRate: { min: 1, max: 15 } };
    if (facingMode(cam)) c.facingMode = [facingMode(cam)];
    return c;
  }

  md.enumerateDevices = async function () {
    const list = await origEnum();
    const st = await state();
    const ours = st.enabled ? st.cams.map(fakeInfo) : [];
    return [...list.filter(d => !String(d.deviceId).startsWith(PREFIX)), ...ours];
  };

  // ---------------------------------------------------------------- constraint matching
  const values = (v) => v == null ? [] : typeof v === "string" ? [v] : Array.isArray(v) ? v
    : typeof v === "object" ? [].concat(values(v.exact), values(v.ideal)) : [];
  const isExact = (v) => v && typeof v === "object" && !Array.isArray(v) && v.exact != null;

  /** null = not for us (real camera); else the cam object. Throws NotAllowedError when ours but disabled. */
  async function pick(video) {
    if (!video) return null;
    const v = typeof video === "object" ? video : {};
    const ids = values(v.deviceId);
    const asksOurs = ids.some(id => String(id).startsWith(PREFIX));
    const st = await state();
    if (asksOurs) {
      if (!st.enabled) throw new DOMException("The phone camera is turned off in LinuxDesk", "NotAllowedError");
      const want = ids.map(String).filter(id => id.startsWith(PREFIX));
      return st.cams.find(c => want.includes(devId(c))) ||
        (isExact(v.deviceId) ? (() => { throw new DOMException("Requested device not found", "OverconstrainedError"); })() : st.cams[0]);
    }
    if (ids.length && isExact(v.deviceId)) return null;               // exact id of a real camera
    let real = [];
    try { real = (await origEnum()).filter(d => d.kind === "videoinput" && !String(d.deviceId).startsWith(PREFIX)); } catch (_) {}
    if (real.length) return null;                                     // the guest has its own camera: leave it
    if (!st.enabled || !st.cams.length) return null;                  // let the real path fail as it would
    const fm = values(v.facingMode).map(String);
    return (fm.length && st.cams.find(c => fm.includes(facingMode(c)))) ||
      st.cams.find(c => c.facing === "back") || st.cams[0];
  }

  // ---------------------------------------------------------------- per-page consent (OFF by default)
  let allowed = false;
  function askOnce(st) {
    if (!st.ask || allowed) return true;
    let ok = false;
    try { ok = window.confirm("Allow " + (location.origin || "this page") + " to use the phone camera?"); } catch (_) {}
    allowed = ok;
    return ok;
  }

  // ---------------------------------------------------------------- tracks
  function makeTrack(cam, st, video) {
    return new Promise((resolve, reject) => {
      const canvas = document.createElement("canvas");
      const ctx = canvas.getContext("2d");
      const rate = video && video.frameRate;
      const fps = Math.max(1, Math.min(15, Number((rate && (rate.ideal || rate.max || rate.exact)) || rate) || 15));
      // Relay-only: there is no port/token here at all, so no URL is ever built in the page.
      const url = (!st.relayOnly && st.port && st.token) ? "http://127.0.0.1:" + st.port + "/stream?t=" +
        encodeURIComponent(st.token) + "&cam=" + encodeURIComponent(cam.id) : null;
      let stopped = false, started = false, timer = 0, relayId = null, img = null, transport = url ? "direct" : "pending";
      let entry = null;

      const begin = () => {
        if (started) return;
        started = true;
        clearTimeout(deadline);
        const stream = canvas.captureStream(fps);
        const track = stream.getVideoTracks()[0];
        const origStop = track.stop.bind(track);
        track.stop = () => { cleanup(); origStop(); };   // closes the HTTP stream → the phone camera turns off
        // end(): what Chromium does for a real camera that goes away: readyState "ended" + one "ended" event.
        entry = { track, end: (reason) => {
          if (!live.has(entry)) return;
          cleanup(); origStop();
          try { Object.defineProperty(track, "readyState", { value: "ended", configurable: true }); } catch (_) {}
          try { track.dispatchEvent(new Event("ended")); } catch (_) {}
          try { typeof track.onended === "function" && track.onended(new Event("ended")); } catch (_) {}
          void reason;
        } };
        live.add(entry);
        try { Object.defineProperty(track, "label", { value: cam.label || "Phone camera" }); } catch (_) {}
        const origSettings = track.getSettings.bind(track);
        track.getSettings = () => Object.assign(origSettings(), { deviceId: devId(cam), groupId: GROUP_ID,
          facingMode: facingMode(cam), width: canvas.width, height: canvas.height, frameRate: fps, transport });
        track.getCapabilities = () => caps(cam);
        resolve(track);
      };
      const size = (w, h) => { if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; } };
      const cleanup = () => {
        stopped = true;
        if (entry) live.delete(entry);
        clearTimeout(timer); clearTimeout(deadline);
        if (img) { img.onload = img.onerror = null; img.src = ""; img = null; }
        if (relayId) { window.postMessage({ __ldcam: "req", id: "c" + relayId, op: "close", ref: relayId }, "*"); waiters.delete(relayId); relayId = null; }
      };
      const fail = (name, msg) => { if (started) return; cleanup(); reject(new DOMException(msg || "Phone camera unavailable", name || "NotReadableError")); };
      const deadline = setTimeout(() => fail("NotReadableError", "Phone camera did not start"), 10000);

      // direct: <img> MJPEG, redrawn at fps
      const direct = () => {
        img = new Image();
        img.crossOrigin = "anonymous";   // CameraServer sends ACAO:*, keeps the canvas readable
        const draw = () => {
          if (stopped || transport !== "direct") return;
          if (img && img.naturalWidth) {
            size(img.naturalWidth, img.naturalHeight);
            try { ctx.drawImage(img, 0, 0); begin(); } catch (_) { relay(); return; }
          }
          timer = setTimeout(draw, 1000 / fps);
        };
        img.onerror = () => { if (!stopped) relay(); };
        img.src = url;
        draw();
      };
      // relay: service worker fetch → JPEG frames → createImageBitmap
      const relay = () => {
        if (stopped || transport === "relay") return;
        transport = "relay";
        clearTimeout(timer);
        if (img) { img.onload = img.onerror = null; img.src = ""; img = null; }
        request("open", { cam: cam.id }, async (m, id) => {
          relayId = id;
          if (stopped) return;
          if (m.error) { fail(m.error, m.error === "NotAllowedError" ? "The phone camera is turned off in LinuxDesk" : undefined); return; }
          if (m.ended) {
            if (started) { entry && entry.end(m.reason || "failed"); return; }
            fail(m.reason === "off" ? "NotAllowedError" : "NotReadableError",
              m.reason === "off" ? "The phone camera is turned off in LinuxDesk" : undefined);
            return;
          }
          if (!m.frame) return;
          try {
            const bmp = await createImageBitmap(new Blob([m.frame], { type: "image/jpeg" }));
            if (stopped) { bmp.close && bmp.close(); return; }
            size(bmp.width, bmp.height);
            ctx.drawImage(bmp, 0, 0);
            bmp.close && bmp.close();
            begin();
          } catch (_) {}
        }).then((id) => { relayId = id; });
      };
      if (url) direct(); else relay();
    });
  }

  md.getUserMedia = async function (constraints) {
    const c = constraints || {};
    const cam = await pick(c.video);
    if (!cam) return origGUM(constraints);
    const st = await state();
    if (!st.enabled) throw new DOMException("The phone camera is turned off in LinuxDesk", "NotAllowedError");
    if (!askOnce(st)) throw new DOMException("Permission denied", "NotAllowedError");
    const videoTrack = await makeTrack(cam, st, c.video);
    let tracks = [videoTrack];
    if (c.audio) {
      try { tracks = tracks.concat((await origGUM({ audio: c.audio })).getAudioTracks()); }
      catch (e) { videoTrack.stop(); throw e; }
    }
    return new MediaStream(tracks);
  };

  // Legacy callback API.
  if (navigator.getUserMedia || navigator.webkitGetUserMedia) {
    const legacy = function (cons, ok, fail) { md.getUserMedia(cons).then(ok, fail); };
    navigator.getUserMedia = legacy;
    navigator.webkitGetUserMedia = legacy;
  }

  // Fallback when no push arrives (old worker, bridge missing): poll while a track is live or the tab is visible.
  setInterval(() => { if (live.size || !document.hidden) state(); }, 3000);
})();
