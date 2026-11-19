// Rewritten by the LinuxDesk app at every camera ON/OFF (CameraServer port + per-session token).
// This bundled copy means "camera off". Live state comes from state.json via the service worker.
var __LINUXDESK_CAM__ = { enabled: false, port: 0, token: "", ask: false };
