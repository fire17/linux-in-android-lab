// LinuxDesk keyboard bridge: service worker.
// config.js is written by the app at every session start and defines self.LINUXDESK = { port, token }.
// It points at a loopback-only HTTP endpoint inside the app (FocusBridge.kt), which can do exactly one
// thing: show or hide the Android soft keyboard.
importScripts("config.js");

let hideTimer = null;

function notify(show) {
  const cfg = self.LINUXDESK;
  if (!cfg || !cfg.port) return;
  fetch(`http://127.0.0.1:${cfg.port}/kbd?t=${cfg.token}&show=${show ? 1 : 0}`, { cache: "no-store" })
    .catch(() => { /* app not listening (session stopped); ignore */ });
}

chrome.runtime.onMessage.addListener((msg) => {
  if (!msg || typeof msg.linuxdeskKbd !== "boolean") return;
  if (msg.linuxdeskKbd) {
    // Focus moving from one field straight to another produces focusout then focusin. Don't flicker.
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
    notify(true);
  } else {
    if (hideTimer) clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { hideTimer = null; notify(false); }, 250);
  }
});
