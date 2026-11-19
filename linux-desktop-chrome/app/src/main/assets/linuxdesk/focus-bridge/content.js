// LinuxDesk keyboard bridge: content script.
// Reports when an editable element gains or loses focus. background.js forwards that to the Android app.
(() => {
  const TEXT_INPUTS = new Set([
    "text", "search", "email", "url", "tel", "password", "number", "date", "datetime-local", "month", "time", "week", "",
  ]);

  function isEditable(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.isContentEditable) return true;
    const tag = el.tagName;
    if (tag === "TEXTAREA") return true;
    if (tag === "INPUT") return TEXT_INPUTS.has((el.getAttribute("type") || "").toLowerCase());
    return false;
  }

  function send(show) {
    try { chrome.runtime.sendMessage({ linuxdeskKbd: show }); } catch (_) { /* extension reloaded */ }
  }

  document.addEventListener("focusin", (e) => { if (isEditable(e.target)) send(true); }, true);
  document.addEventListener("focusout", (e) => { if (isEditable(e.target)) send(false); }, true);
})();
