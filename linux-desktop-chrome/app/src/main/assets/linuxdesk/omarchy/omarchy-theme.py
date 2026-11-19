#!/usr/bin/env python3
"""LinuxDesk Omarchy look: render an Omarchy theme (colors.toml) into i3, i3status, alacritty, rofi, dunst and the
Chrome/Chromium managed theme policy. Runs INSIDE the Arch guest (python3 stdlib only).

    omarchy-theme.py [theme]          default: tokyo-night (Omarchy's default)
    omarchy-theme.py --list

Themes come from Omarchy's own package (/usr/share/omarchy/themes/<name>/colors.toml, taken out of
omarchy-4.5.0 by setup-omarchy.sh). If that is missing, the built-in tokyo-night colors below are used (copied
from omacom/omarchy v4.5.0 themes/tokyo-night/colors.toml, MIT).

Keybinds: Omarchy 4.5's default/hypr/bindings/{tiling,applications,utilities}.lua translated to i3 (Super = Mod4).
Only the bindings that make sense on one phone screen without Hyprland are kept; see BINDINGS below.
"""
import os
import sys
import glob

try:
    import tomllib
except ImportError:  # python < 3.11
    tomllib = None

THEMES = "/usr/share/omarchy/themes"
OUT_HOME = os.environ.get("HOME", "/root") or "/root"
STATE = "/etc/linuxdesk/omarchy-theme"

TOKYO_NIGHT = {
    "mode": "dark", "accent": "#7aa2f7", "selection": "#292e42", "muted": "#414868",
    "background": "#1a1b26", "dark_background": "#13141c", "darker_background": "#0e0e14",
    "lighter_background": "#24283b", "foreground": "#a9b1d6", "dark_foreground": "#565f89",
    "light_foreground": "#b4bee6", "bright_foreground": "#c0caf5",
    "red": "#f7768e", "yellow": "#e0af68", "orange": "#eb927b", "green": "#9ece6a", "cyan": "#449dab",
    "blue": "#7aa2f7", "magenta": "#ad8ee6", "brown": "#75493d",
    "bright_red": "#ff7a93", "bright_yellow": "#ff9e64", "bright_green": "#b9f27c", "bright_cyan": "#0db9d7",
    "bright_blue": "#7da6ff", "bright_magenta": "#bb9af7",
}

FONT = "JetBrainsMono Nerd Font"


def load(name):
    path = os.path.join(THEMES, name, "colors.toml")
    c = dict(TOKYO_NIGHT)
    if os.path.isfile(path):
        with open(path, "rb") as f:
            data = tomllib.load(f) if tomllib else _mini_toml(f.read().decode())
        c.update({k: v for k, v in data.items() if isinstance(v, str)})
    elif name != "tokyo-night":
        print(f"[theme] {path} not found; using built-in tokyo-night", file=sys.stderr)
    # Older/smaller themes may lack some keys: derive them from the core ones.
    c.setdefault("selection", c["lighter_background"])
    for k in ("red", "yellow", "green", "cyan", "blue", "magenta"):
        c.setdefault("bright_" + k, c[k])
    return c


def _mini_toml(text):
    out = {}
    for line in text.splitlines():
        line = line.split("#", 1)[0] if not line.strip().startswith('"') else line
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"')
    return out


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.replace(tmp, path)


def wallpaper(name):
    files = sorted(glob.glob(os.path.join(THEMES, name, "backgrounds", "*")))
    return files[0] if files else ""


# (i3 key, i3 command, Omarchy binding it comes from)
BINDINGS = [
    ("$mod+Return", "exec alacritty", "SUPER+RETURN Terminal"),
    ("$mod+Shift+Return", "exec /opt/linuxdesk/omarchy/browser.sh", "SUPER+SHIFT+RETURN Browser"),
    ("$mod+Shift+b", "exec /opt/linuxdesk/omarchy/browser.sh", "SUPER+SHIFT+B Browser"),
    ("$mod+Shift+n", "exec alacritty -e nvim", "SUPER+SHIFT+N Editor"),
    ("$mod+space", "exec rofi -show drun", "SUPER+SPACE Omarchy menu (rofi here)"),
    ("$mod+Mod1+space", "exec rofi -show drun", "SUPER+ALT+SPACE Apps"),
    ("$mod+Ctrl+Shift+space", "exec /opt/linuxdesk/omarchy/theme-menu.sh", "SUPER+CTRL+SHIFT+SPACE Theme menu"),
    ("$mod+Escape", "exec i3-nagbar -t warning -m 'Leave the i3 session?' -B 'Restart i3' 'i3-msg restart'",
     "SUPER+ESCAPE System menu"),
    ("$mod+w", "kill", "SUPER+W Close window"),
    ("$mod+q", "kill", "SUPER+Q Close window"),
    ("$mod+j", "split toggle", "SUPER+J Toggle split"),
    ("$mod+t", "floating toggle", "SUPER+T Float/tile"),
    ("$mod+f", "fullscreen toggle", "SUPER+F Full screen"),
    ("$mod+Left", "focus left", "SUPER+LEFT"), ("$mod+Right", "focus right", "SUPER+RIGHT"),
    ("$mod+Up", "focus up", "SUPER+UP"), ("$mod+Down", "focus down", "SUPER+DOWN"),
    ("$mod+Shift+Left", "move left", "SUPER+SHIFT+LEFT Swap"), ("$mod+Shift+Right", "move right", "SUPER+SHIFT+RIGHT"),
    ("$mod+Shift+Up", "move up", "SUPER+SHIFT+UP"), ("$mod+Shift+Down", "move down", "SUPER+SHIFT+DOWN"),
    ("$mod+s", "scratchpad show", "SUPER+S Scratchpad"),
    ("$mod+Mod1+s", "move scratchpad", "SUPER+ALT+S Move to scratchpad"),
    ("$mod+Tab", "workspace next", "SUPER+TAB Next workspace"),
    ("$mod+Shift+Tab", "workspace prev", "SUPER+SHIFT+TAB Previous workspace"),
    ("$mod+Ctrl+Tab", "workspace back_and_forth", "SUPER+CTRL+TAB Former workspace"),
    ("Mod1+Tab", "focus next", "ALT+TAB Next window"),
    ("Mod1+Shift+Tab", "focus prev", "ALT+SHIFT+TAB Previous window"),
    ("$mod+minus", "resize shrink width 100 px", "SUPER+code:20 (minus) Expand left"),
    ("$mod+equal", "resize grow width 100 px", "SUPER+code:21 (equal) Shrink left"),
    ("$mod+Shift+minus", "resize shrink height 100 px", "SUPER+SHIFT+code:20"),
    ("$mod+Shift+equal", "resize grow height 100 px", "SUPER+SHIFT+code:21"),
]


def i3_config(c, name):
    lines = [
        f"# Generated by /opt/linuxdesk/omarchy/omarchy-theme.py from Omarchy theme '{name}'. Re-run it to change themes.",
        "set $mod Mod4",
        f"font pango:{FONT} 10",
        "default_border pixel 2",
        "default_floating_border pixel 2",
        "hide_edge_borders smart",
        "gaps inner 5",
        "gaps outer 0",
        "smart_gaps on",
        "focus_follows_mouse no",
        "floating_modifier $mod",
        "workspace_auto_back_and_forth yes",
        "",
        "# class                 border        backgr.       text          indicator     child_border",
        f"client.focused          {c['accent']} {c['background']} {c['foreground']} {c['accent']} {c['accent']}",
        f"client.focused_inactive {c['muted']} {c['background']} {c['dark_foreground']} {c['muted']} {c['muted']}",
        f"client.unfocused        {c['muted']} {c['background']} {c['dark_foreground']} {c['muted']} {c['background']}",
        f"client.urgent           {c['red']} {c['background']} {c['foreground']} {c['red']} {c['red']}",
        f"client.background       {c['background']}",
        "",
        "# Keybinds translated from Omarchy 4.5 (Hyprland → i3)",
    ]
    for key, cmd, src in BINDINGS:
        lines.append(f"bindsym {key} {cmd}    # {src}")
    for i in range(1, 11):
        k = str(i % 10)
        lines.append(f"bindsym $mod+{k} workspace number {i}")
        lines.append(f"bindsym $mod+Shift+{k} move container to workspace number {i}")
    lines += [
        "",
        "# Dialogs and pop-ups float, like on Omarchy.",
        'for_window [window_role="pop-up"] floating enable',
        'for_window [window_role="bubble"] floating enable',
        'for_window [window_type="dialog"] floating enable',
        "",
        "bar {",
        "    position top",
        "    status_command i3status",
        "    tray_output none",
        f"    font pango:{FONT} 10",
        "    colors {",
        f"        background {c['background']}",
        f"        statusline {c['foreground']}",
        f"        separator  {c['muted']}",
        f"        focused_workspace  {c['accent']} {c['accent']} {c['background']}",
        f"        active_workspace   {c['lighter_background']} {c['lighter_background']} {c['foreground']}",
        f"        inactive_workspace {c['background']} {c['background']} {c['dark_foreground']}",
        f"        urgent_workspace   {c['red']} {c['red']} {c['background']}",
        "    }",
        "}",
        "",
        "exec --no-startup-id dunst",
    ]
    wp = wallpaper(name)
    if wp:
        lines.append(f"exec_always --no-startup-id feh --no-fehbg --bg-fill '{wp}' || xsetroot -solid '{c['background']}'")
    else:
        lines.append(f"exec_always --no-startup-id xsetroot -solid '{c['background']}'")
    return "\n".join(lines) + "\n"


def i3status_config(c):
    return f"""general {{
    colors = true
    color_good = "{c['green']}"
    color_degraded = "{c['yellow']}"
    color_bad = "{c['red']}"
    interval = 5
}}
order += "cpu_usage"
order += "memory"
order += "tztime local"
cpu_usage {{ format = "  %usage" }}
memory {{ format = "  %used" }}
tztime local {{ format = "%a %d %b  %H:%M" }}
"""


def alacritty(c):
    return f"""# Generated from the Omarchy theme (colors.toml) by omarchy-theme.py
[font]
normal = {{ family = "{FONT}" }}
size = 10

[window]
padding = {{ x = 10, y = 10 }}

[colors.primary]
background = "{c['background']}"
foreground = "{c['foreground']}"

[colors.cursor]
text = "{c['background']}"
cursor = "{c['bright_foreground']}"

[colors.selection]
text = "{c['bright_foreground']}"
background = "{c['selection']}"

[colors.normal]
black = "{c['background']}"
red = "{c['red']}"
green = "{c['green']}"
yellow = "{c['yellow']}"
blue = "{c['blue']}"
magenta = "{c['magenta']}"
cyan = "{c['cyan']}"
white = "{c['foreground']}"

[colors.bright]
black = "{c['muted']}"
red = "{c['bright_red']}"
green = "{c['bright_green']}"
yellow = "{c['bright_yellow']}"
blue = "{c['bright_blue']}"
magenta = "{c['bright_magenta']}"
cyan = "{c['bright_cyan']}"
white = "{c['bright_foreground']}"
"""


def rofi(c):
    return f"""configuration {{ show-icons: false; font: "{FONT} 11"; }}
* {{
    bg: {c['background']}; fg: {c['foreground']}; ac: {c['accent']}; sel: {c['selection']};
    background-color: @bg; text-color: @fg; border-color: @ac;
}}
window {{ border: 2px; padding: 12px; width: 40%; }}
inputbar {{ padding: 6px; children: [prompt, entry]; }}
prompt {{ text-color: @ac; padding: 0 8px 0 0; }}
element {{ padding: 6px; }}
element selected {{ background-color: @sel; text-color: @ac; }}
element-text {{ background-color: inherit; text-color: inherit; }}
"""


def dunst(c):
    return f"""[global]
font = {FONT} 10
frame_color = "{c['accent']}"
frame_width = 2
corner_radius = 0
offset = (10, 40)
[urgency_low]
background = "{c['background']}"
foreground = "{c['foreground']}"
[urgency_normal]
background = "{c['background']}"
foreground = "{c['foreground']}"
[urgency_critical]
background = "{c['background']}"
foreground = "{c['red']}"
frame_color = "{c['red']}"
"""


def chrome_policy(c):
    # Same mechanism as Omarchy's omarchy-theme-set-browser-policy: a managed BrowserThemeColor policy.
    return '{\n  "BrowserThemeColor": "%s"\n}\n' % c["background"]


def main(argv):
    if argv[1:2] == ["--list"]:
        names = sorted(os.path.basename(os.path.dirname(p)) for p in glob.glob(os.path.join(THEMES, "*", "colors.toml")))
        print("\n".join(names or ["tokyo-night"]))
        return 0
    name = argv[1] if len(argv) > 1 else "tokyo-night"
    c = load(name)
    cfg = os.path.join(OUT_HOME, ".config")
    write(os.path.join(cfg, "i3", "config"), i3_config(c, name))
    write(os.path.join(cfg, "i3status", "config"), i3status_config(c))
    write(os.path.join(cfg, "alacritty", "alacritty.toml"), alacritty(c))
    write(os.path.join(cfg, "rofi", "config.rasi"), rofi(c))
    write(os.path.join(cfg, "dunst", "dunstrc"), dunst(c))
    for d in ("/etc/opt/chrome/policies/managed", "/etc/chromium/policies/managed"):
        try:
            write(os.path.join(d, "linuxdesk-omarchy-theme.json"), chrome_policy(c))
        except OSError as e:
            print(f"[theme] {d}: {e}", file=sys.stderr)
    try:
        write(STATE, name + "\n")
    except OSError:
        pass
    print(f"[theme] Omarchy theme '{name}' rendered (accent {c['accent']}, background {c['background']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
