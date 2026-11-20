#!/bin/sh
# LinuxDesk "Omarchy (clean)": loginctl under PRoot. No logind: answer the calls Omarchy makes with this one session,
# lock through the Omarchy shell, and exit 0 for the rest (scripts keep going).
for a in "$@"; do case "$a" in -*) ;; *) VERB=$a; break ;; esac; done
case "$VERB" in
  list-users) printf '%s %s\n' "$(id -u)" "$(id -un)" ;;
  list-sessions) printf '1 %s %s seat0\n' "$(id -u)" "$(id -un)" ;;
  lock-session|lock-sessions) command -v omarchy-lock-screen >/dev/null 2>&1 && exec omarchy-lock-screen ;;
  show-session|show-user|session-status|user-status) printf 'Id=1\nName=%s\nState=active\nActive=yes\nType=wayland\n' "$(id -un)" ;;
esac
exit 0
