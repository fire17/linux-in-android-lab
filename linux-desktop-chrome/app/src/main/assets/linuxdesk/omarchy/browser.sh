#!/bin/sh
# Super+Shift+Return / Super+Shift+B: open another browser window (Omarchy's "Browser" binding).
for b in google-chrome-stable chromium; do
  if command -v "$b" >/dev/null 2>&1; then
    exec "$b" --no-sandbox --test-type --ozone-platform=x11 --touch-events=enabled --password-store=basic \
      --user-data-dir=/root/.config/linuxdesk-chromium --disable-dev-shm-usage "$@"
  fi
done
echo "no browser installed"; exit 127
