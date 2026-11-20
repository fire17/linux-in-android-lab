#!/bin/sh
# LinuxDesk: a bottom dock for Hyprland (nwg-dock in ALARM is the sway build and needs $SWAYSOCK).
# A second waybar: launchers (Apps, Terminal, Browser, Files) + the open-windows row (wlr/taskbar).
D="$HOME/.config/waybar"; mkdir -p "$D"
term=foot; command -v alacritty >/dev/null && term=alacritty
apps="walker"; command -v walker >/dev/null || apps="wofi --show drun"; command -v wofi >/dev/null || command -v walker >/dev/null || apps="fuzzel"
cat > "$D/hypr-dock.jsonc" <<J
{
  "name": "hyprdock", "layer": "top", "position": "bottom", "height": 72, "margin-bottom": 6,
  "exclusive": true, "spacing": 8,
  "modules-center": ["custom/apps", "custom/terminal", "custom/browser", "custom/files", "wlr/taskbar"],
  "custom/apps":     {"format": "", "on-click": "$apps", "tooltip-format": "Apps"},
  "custom/terminal": {"format": "", "on-click": "$term", "tooltip-format": "Terminal"},
  "custom/browser":  {"format": "", "on-click": "google-chrome-stable --ozone-platform=wayland", "tooltip-format": "Browser"},
  "custom/files":    {"format": "", "on-click": "thunar", "tooltip-format": "Files"},
  "wlr/taskbar": {"format": "{icon}", "icon-size": 40, "on-click": "activate", "on-click-middle": "close", "tooltip-format": "{title}"}
}
J
cat > "$D/hypr-dock.css" <<'C'
* { border: none; border-radius: 0; min-height: 0; font-family: "JetBrainsMono Nerd Font", "Symbols Nerd Font"; font-size: 30px; }
window#waybar { background: transparent; }
.modules-center { background: rgba(26,27,38,0.88); border: 1px solid rgba(122,162,247,0.45); border-radius: 22px; padding: 4px 12px; }
#custom-apps, #custom-terminal, #custom-browser, #custom-files { color: #c0caf5; min-width: 60px; padding: 0 6px; }
#custom-apps:hover, #custom-terminal:hover, #custom-browser:hover, #custom-files:hover { color: #7aa2f7; }
#taskbar button { padding: 0 6px; border-radius: 14px; }
#taskbar button.active { background: rgba(122,162,247,0.30); }
C
exec waybar -c "$D/hypr-dock.jsonc" -s "$D/hypr-dock.css"
