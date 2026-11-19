// LinuxDesk phone camera — MAIN world, document_start, all frames.
// Adds a "Phone camera" videoinput to enumerateDevices() and serves getUserMedia({video}) for it from the
// app's loopback MJPEG stream (CameraServer: http://127.0.0.1:PORT/stream?t=TOKEN) via canvas.captureStream().
// Audio constraints and every other camera go to the real implementation untouched (mic-in lane unaffected).
(() => {
  "use strict";
  const cfg = (typeof __LINUXDESK_CAM__ === "object" && __LINUXDESK_CAM__) || {};
  try { delete window.__LINUXDESK_CAM__; } catch (_) { window.__LINUXDESK_CAM__ = undefined; }
  if (!cfg.port || !cfg.token) return;
  const md = navigator.mediaDevices;
  if (!md || md.__linuxdeskPhoneCam) return;
  Object.defineProperty(md, "__linuxdeskPhoneCam", { value: true });

  const DEVICE_ID = "linuxdesk-phone-camera";
  const GROUP_ID = "linuxdesk-phone";
  const LABEL = "Phone camera";
  const URL_ = "http://127.0.0.1:" + cfg.port + "/stream?t=" + encodeURIComponent(cfg.token);
  const origEnum = md.enumerateDevices.bind(md);
  const origGUM = md.getUserMedia.bind(md);

  function fakeInfo() {
    const o = { deviceId: DEVICE_ID, groupId: GROUP_ID, kind: "videoinput", label: LABEL };
    o.toJSON = () => ({ deviceId: DEVICE_ID, groupId: GROUP_ID, kind: "videoinput", label: LABEL });
    try { Object.setPrototypeOf(o, (window.InputDeviceInfo || window.MediaDeviceInfo).prototype); } catch (_) {}
    // own data props (set above) shadow the prototype getters, so instanceof works and reads never throw
    o.getCapabilities = () => ({ deviceId: DEVICE_ID, groupId: GROUP_ID, width: { min: 1, max: 1280 }, height: { min: 1, max: 720 }, frameRate: { min: 1, max: 15 }, facingMode: ["environment"] });
    return o;
  }

  md.enumerateDevices = async function () {
    const list = await origEnum();
    return [...list.filter(d => d.deviceId !== DEVICE_ID), fakeInfo()];
  };

  function idMatches(v) {
    if (!v) return false;
    if (typeof v === "string") return v === DEVICE_ID;
    if (Array.isArray(v)) return v.includes(DEVICE_ID);
    if (typeof v === "object") return idMatches(v.exact) || idMatches(v.ideal);
    return false;
  }

  // Use the phone camera when it is asked for by id, OR when video is wanted and the guest has no real camera.
  async function wantsPhone(video) {
    if (!video) return false;
    if (typeof video === "object" && idMatches(video.deviceId)) return true;
    if (typeof video === "object" && video.deviceId && typeof video.deviceId === "object" && video.deviceId.exact) return false;
    try { return !(await origEnum()).some(d => d.kind === "videoinput" && d.deviceId !== DEVICE_ID); } catch (_) { return true; }
  }

  function phoneTrack(video) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.crossOrigin = "anonymous";   // CameraServer sends Access-Control-Allow-Origin: *, keeps the canvas clean
      const canvas = document.createElement("canvas");
      const ctx = canvas.getContext("2d");
      const fps = Math.min(15, (video && video.frameRate && (video.frameRate.ideal || video.frameRate.max || video.frameRate)) || 15) || 15;
      let raf = 0, stopped = false, started = false;
      const draw = () => {
        if (stopped) return;
        if (img.naturalWidth) {
          if (canvas.width !== img.naturalWidth) { canvas.width = img.naturalWidth; canvas.height = img.naturalHeight; }
          try { ctx.drawImage(img, 0, 0); } catch (_) {}
          if (!started) { started = true; finish(); }
        }
        raf = setTimeout(draw, 1000 / fps);
      };
      let stream = null;
      const finish = () => {
        stream = canvas.captureStream(fps);
        const track = stream.getVideoTracks()[0];
        const origStop = track.stop.bind(track);
        track.stop = () => { stopped = true; clearTimeout(raf); img.src = ""; origStop(); };   // closes the HTTP stream → camera off
        try { Object.defineProperty(track, "label", { value: LABEL }); } catch (_) {}
        const origSettings = track.getSettings.bind(track);
        track.getSettings = () => Object.assign(origSettings(), { deviceId: DEVICE_ID, groupId: GROUP_ID, facingMode: "environment", width: canvas.width, height: canvas.height, frameRate: fps });
        track.getCapabilities = () => fakeInfo().getCapabilities();
        resolve(track);
      };
      const t = setTimeout(() => { if (!started) { stopped = true; img.src = ""; reject(new DOMException("Phone camera did not start", "NotReadableError")); } }, 8000);
      img.onload = () => clearTimeout(t);
      img.onerror = () => { if (!started) { clearTimeout(t); stopped = true; reject(new DOMException("Phone camera unavailable", "NotReadableError")); } };
      img.src = URL_;
      draw();
    });
  }

  // The fake device bypasses Chromium's own camera prompt. Optional extra (cfg.ask, default OFF until Tami says so):
  // ask once per page load, per frame origin.
  let allowed = !cfg.ask;
  function askOnce() {
    if (allowed) return true;
    let ok = false;
    try { ok = window.confirm("Allow " + (location.origin || "this page") + " to use the phone camera?"); } catch (_) {}
    allowed = ok;
    return ok;
  }

  md.getUserMedia = async function (constraints) {
    const c = constraints || {};
    if (!(await wantsPhone(c.video))) return origGUM(constraints);
    if (!askOnce()) throw new DOMException("Permission denied", "NotAllowedError");
    const videoTrack = await phoneTrack(c.video);
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
})();
