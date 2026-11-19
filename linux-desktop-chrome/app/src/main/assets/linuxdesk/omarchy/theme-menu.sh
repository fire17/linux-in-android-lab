#!/bin/sh
# Pick an Omarchy theme with rofi, re-render the configs, reload i3 (Super+Ctrl+Shift+Space).
t=$(python3 /opt/linuxdesk/omarchy/omarchy-theme.py --list | rofi -dmenu -p "Omarchy theme") || exit 0
[ -n "$t" ] || exit 0
python3 /opt/linuxdesk/omarchy/omarchy-theme.py "$t" && i3-msg reload >/dev/null
