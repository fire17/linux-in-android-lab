#!/bin/sh
# LinuxDesk bwrap shim: bubblewrap cannot create namespaces under PRoot, so glycin (GTK/gdk-pixbuf image loading:
# wallpapers, icons, wofi) failed every image with "Loader process exited early". This drops bwrap's sandbox
# options, applies its --setenv/--chdir, and execs the wrapped command directly. Installed at /usr/local/bin/bwrap.
while [ $# -gt 0 ]; do
  case "$1" in
    --unshare-*|--share-net|--die-with-parent|--clearenv|--new-session|--as-pid-1) shift ;;
    --chdir) cd "$2" 2>/dev/null; shift 2 ;;
    --setenv) export "$2=$3"; shift 3 ;;
    --unsetenv) unset "$2"; shift 2 ;;
    --dev|--proc|--tmpfs|--dir|--seccomp|--add-seccomp-fd|--info-fd|--json-status-fd|--block-fd|--sync-fd|--userns-block-fd|--lock-file|--remount-ro|--hostname|--uid|--gid|--cap-add|--cap-drop|--argv0|--size|--perms|--chmod|--file|--bind-data|--ro-bind-data) shift 2 ;;
    --bind|--bind-try|--ro-bind|--ro-bind-try|--dev-bind|--dev-bind-try|--symlink) shift 3 ;;
    --) shift; break ;;
    -*) shift ;;
    *) break ;;
  esac
done
exec "$@"
