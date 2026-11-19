#!/usr/bin/env bash
# Exposes the Mac's Android Emulator to PhoneInPhone via an adb SERVER on the LAN/tailnet.
# The emulator's own adbd listens on 127.0.0.1 only; an adb server bound to all interfaces
# (-a) relays to it. In the app pick "adb server (:5037)", host = the Mac's tailnet IP,
# serial = emulator-5554 (or blank if it's the only device).
#
# SECURITY: -a exposes FULL adb control of every attached device to anyone who can reach 5037.
# Only do this on a private tailnet / firewalled LAN, and stop it when done (adb kill-server).
set -euo pipefail
adb kill-server || true
adb -a -P 5037 nodaemon server
