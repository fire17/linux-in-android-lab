#!/usr/bin/env python3
"""LinuxDesk "Omarchy (Sway)" look: render an Omarchy theme into sway, waybar, foot, mako and wofi configs.
Runs INSIDE the Arch guest (python3 stdlib only), for the experimental sway desktop (notes/sway-desktop-plan.md).

    sway-theme.py [theme]          default: tokyo-night (Omarchy's default)

Colors, the UI scale and the theme lookup come from omarchy-theme.py next to this file (imported, not copied), so
i3 and sway always render the same theme. omarchy-theme.py stays the i3 renderer; this file only adds the sway side.

    sway-theme.py --stamp          print the stamp line start-sway.sh looks for (re-render on a new rev / UI scale)

Writes ~/.config/sway/config (no `bar` block: waybar replaces swaybar; `exec mako`), ~/.config/waybar/
{config.jsonc,style.css} (top bar), ~/.config/waybar/{dock.jsonc,dock.css} (bottom dock), ~/.config/foot/foot.ini,
~/.config/mako/config, ~/.config/wofi/style.css.
Keybinds: Omarchy 4.5's tiling/applications bindings, as in omarchy-theme.py, with Wayland tools: foot (terminal),
wofi (launcher; Omarchy 3's walker is not in ALARM), swaynag (system menu).
Waybar: Omarchy's layout and style minus cpu/battery/network/bluetooth (under PRoot they cannot read /proc/stat or
/sys and only log errors): workspaces | clock | memory, pulseaudio. Sized for a finger (Tami, 2026-11-20: "the button in
the top left corner is tiny, I literally can't press it"): a 48 px bar, big Omarchy button and workspace buttons.
Dock: a second waybar at the bottom, a centered macOS-style row of 64 px buttons: Apps, Terminal, Browser, Files,
zoom out, zoom in ("I don't have a launcher bar at the bottom like Apple's").
Zoom: zoom.sh steps sway's output scale (the whole desktop, not only Chrome): the dock's buttons, Super+minus /
Super+equal / Super+0 (reset). Omarchy's resize-width moves to Super+Ctrl+minus / equal; Super+0 no longer goes to
workspace 10 (Super+Tab, Super+Shift+0 still reach it).
Sway, like i3, splits commands on ',' and ';', so no generated command may contain either (asserted below).
"""
import importlib.util
import json
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("omarchy_theme", os.path.join(_here, "omarchy-theme.py"))
ot = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ot)

FONT = ot.FONT
px = ot.px
# Bumped when the generated sway config changes; start-sway.sh re-renders an older config at the next Start (like I3_REV).
# rev 2: bottom dock (second waybar), touch-sized top bar, zoom.sh on Super+minus / equal / 0.
# rev 4: style end4 (/etc/linuxdesk/sway-style, the default): matugen palette, pill bar, taskbar row, Mac dock /
#        nwg-dock, swaync, floating windows (/etc/linuxdesk/sway-layout). The stamp carries style + layout so changing
#        either file re-renders at the next Start.
SWAY_REV = "linuxdesk-sway-rev 4"


def _setting(path, allowed, default):
    """One-word settings file (e.g. /etc/linuxdesk/sway-style); missing/unreadable/unknown -> default."""
    try:
        with open(path) as f:
            v = f.read().strip().lower()
    except OSError:
        return default
    return v if v in allowed else default


# end4 = Tami's pick (2026-11-20, design #8, end-4 "illogical-impulse"): matugen palette, pill bar + taskbar, Mac dock,
# floating windows. omarchy = the previous look (flat 48 px bar, tiling). LINUXDESK_ETC lets tests point elsewhere.
ETC = os.environ.get("LINUXDESK_ETC", "/etc/linuxdesk")
STYLE = _setting(os.path.join(ETC, "sway-style"), ("end4", "omarchy"), "end4")
LAYOUT = _setting(os.path.join(ETC, "sway-layout"), ("floating", "tiling"), "floating" if STYLE == "end4" else "tiling")


# end-4 (illogical-impulse) default dark scheme: Material 3 roles. Used when matugen is missing, slow or fails.
END4_DEFAULT = {
    "background": "#141218", "on_background": "#e6e0e9", "surface": "#141218", "surface_dim": "#141218",
    "surface_bright": "#3b383e", "surface_container_lowest": "#0f0d13", "surface_container_low": "#1d1b20",
    "surface_container": "#211f26", "surface_container_high": "#2b2930", "surface_container_highest": "#36343b",
    "on_surface": "#e6e0e9", "on_surface_variant": "#cac4d0", "outline": "#938f99", "outline_variant": "#49454f",
    "primary": "#d0bcff", "on_primary": "#381e72", "primary_container": "#4f378b", "on_primary_container": "#eaddff",
    "secondary": "#ccc2dc", "on_secondary": "#332d41", "secondary_container": "#4a4458",
    "on_secondary_container": "#e8def8", "tertiary": "#efb8c8", "tertiary_container": "#633b48",
    "on_tertiary_container": "#ffd8e4", "error": "#f2b8b5", "on_error": "#601410", "error_container": "#8c1d18",
    "inverse_surface": "#e6e0e9", "inverse_on_surface": "#322f35", "inverse_primary": "#6750a4",
}


def _hexstr(v):
    """A matugen color value: '#rrggbb' itself, or a dict holding it (matugen's JSON shape changed across versions)."""
    if isinstance(v, str) and v.startswith("#") and len(v) in (7, 9):
        return v[:7].lower()
    if isinstance(v, dict):
        for k in ("color", "hex", "dark", "default"):
            if k in v:
                return _hexstr(v[k])
    return None


def _parse_matugen(data):
    """matugen 2.x: {"colors": {"dark": {role: hex}}}; 3.x/4.x: {"colors": {role: {"dark": hex | {"color": hex}}}}."""
    colors = data.get("colors", data) if isinstance(data, dict) else {}
    if isinstance(colors.get("dark"), dict):
        colors = colors["dark"]
    out = {}
    for role, v in colors.items():
        if isinstance(v, dict) and "dark" in v:
            v = v["dark"]
        h = _hexstr(v)
        if h:
            out[role] = h
    return out


def matugen_palette(wallpaper, timeout=10):
    """M3 dark roles from the wallpaper via matugen; (palette, source). Never raises; 10 s budget in total."""
    import shutil
    import subprocess
    import time
    if not wallpaper or not os.path.isfile(wallpaper) or not shutil.which("matugen"):
        return dict(END4_DEFAULT), "end-4 default (no matugen or wallpaper)"
    deadline = time.monotonic() + timeout
    # --source-color-index 0: newer matugen asks on the tty which source color to use when an image has several.
    for extra in (["--source-color-index", "0"], []):
        left = deadline - time.monotonic()
        if left <= 0.5:
            break
        try:
            r = subprocess.run(["matugen", "image", wallpaper, "-m", "dark", "--json", "hex", *extra],
                               stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=left)
            if r.returncode != 0:
                continue
            got = _parse_matugen(json.loads(r.stdout[r.stdout.index("{"):]))
        except (OSError, ValueError, subprocess.SubprocessError):
            continue
        if all(k in got for k in ("primary", "surface", "on_surface", "primary_container")):
            pal = dict(END4_DEFAULT)
            pal.update(got)
            return pal, "matugen " + os.path.basename(wallpaper)
    return dict(END4_DEFAULT), "end-4 default (matugen failed)"


def end4_colors(c, m):
    """The Omarchy color slots (used by foot, wofi, mako, sway) re-pointed at M3 roles; ANSI hues stay the theme's."""
    c = dict(c)
    c.update({
        "accent": m["primary"], "background": m["surface"], "foreground": m["on_surface"],
        "lighter_background": m["surface_container_high"], "dark_background": m["surface_container_low"],
        "selection": m["secondary_container"], "muted": m["outline_variant"],
        "dark_foreground": m["on_surface_variant"], "bright_foreground": m["inverse_surface"],
        "light_foreground": m["on_surface"], "red": m["error"],
    })
    return c


def pt(n):
    """A font size of n points at 96 dpi, scaled like px(): sway's x11 output runs at scale 1 on the phone's pixels."""
    return max(6, round(n * ot.S))


def stamp():
    return f"{SWAY_REV} scale {ot.scale_str()} style {STYLE} layout {LAYOUT}"


TERMINAL = "foot"
LAUNCHER = "wofi --show drun"
BROWSER = "sh /opt/linuxdesk/omarchy/browser.sh"
ZOOM = "sh /opt/linuxdesk/omarchy/zoom.sh"
# thunar comes with Omarchy's apps (setup-omarchy.sh apps, installed in the background); a shell until it is there.
FILES = "command -v thunar >/dev/null 2>&1 && exec thunar || exec foot"

# (sway key, sway command, Omarchy binding it comes from)
BINDINGS = [
    ("$mod+Return", f"exec {TERMINAL}", "SUPER+RETURN Terminal"),
    ("$mod+Shift+Return", f"exec {BROWSER}", "SUPER+SHIFT+RETURN Browser"),
    ("$mod+Shift+b", f"exec {BROWSER}", "SUPER+SHIFT+B Browser"),
    ("$mod+Shift+n", f"exec {TERMINAL} nvim", "SUPER+SHIFT+N Editor"),
    ("$mod+space", "exec sh /opt/linuxdesk/omarchy/omarchy-menu.sh", "SUPER+SPACE Omarchy menu"),
    ("$mod+Mod1+space", f"exec {LAUNCHER}", "SUPER+ALT+SPACE Apps"),
    ("$mod+d", "exec wofi --show run", "i3/sway default: run any command"),
    ("$mod+Escape", "exec swaynag -t warning -m 'Leave the sway session?' -B 'Reload sway' 'swaymsg reload'",
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
    ("$mod+equal", f"exec {ZOOM} in", "LinuxDesk: zoom the whole desktop in (dock +)"),
    ("$mod+minus", f"exec {ZOOM} out", "LinuxDesk: zoom out (dock -)"),
    ("$mod+0", f"exec {ZOOM} reset", "LinuxDesk: zoom back to 1.5"),
    ("$mod+Ctrl+minus", "resize shrink width 100 px", "SUPER+code:20 (minus) Expand left (Ctrl here: zoom has Super+minus)"),
    ("$mod+Ctrl+equal", "resize grow width 100 px", "SUPER+code:21 (equal) Shrink left"),
    ("$mod+Shift+minus", "resize shrink height 100 px", "SUPER+SHIFT+code:20"),
    ("$mod+Shift+equal", "resize grow height 100 px", "SUPER+SHIFT+code:21"),
]


def _cmd(cmd):
    assert "," not in cmd and ";" not in cmd, cmd  # sway command separators
    return cmd


def sway_config(c, name, cfg, m=None):
    """m: the M3 palette (style end4); None = the omarchy style."""
    lines = [
        f"# Generated by /opt/linuxdesk/omarchy/sway-theme.py from Omarchy theme '{name}'. Re-run it to change themes.",
        f"# {stamp()}",
        "set $mod Mod4",
        f"font pango:{FONT} {pt(10)}",
        "xwayland enable",
        "",
        "# Omarchy's Hyprland look: 2 px borders, gaps_in 5 / gaps_out 10 (sway adds outer to inner).",
        f"default_border pixel {px(2)}",
        f"default_floating_border pixel {px(2)}",
        "hide_edge_borders smart",
        f"gaps inner {px(5)}",
        f"gaps outer {px(5)}",
        "focus_follows_mouse no",
        "floating_modifier $mod normal",
        "workspace_auto_back_and_forth yes",
        "",
    ]
    if m:
        # Touch: title bars >= 40 px (font ~15 px + 2 x 13 px padding) so a finger can grab and drag a floating window.
        lines = lines[:3] + [f"font pango:Inter Semi-Bold {pt(11)}", "xwayland enable", "",
                             "# end4: rounded-look pills live in waybar; windows get a thick touch title bar.",
                             f"default_border normal {px(2)}",
                             f"default_floating_border normal {px(2)}",
                             f"titlebar_padding {px(12)} {px(13)}",
                             "title_align center",
                             "hide_edge_borders smart",
                             f"gaps inner {px(6)}",
                             f"gaps outer {px(4)}",
                             "focus_follows_mouse no",
                             "floating_modifier $mod normal",
                             "workspace_auto_back_and_forth yes",
                             "",
                             "# class                 border    backgr.   text      indicator child_border (M3 roles)",
                             f"client.focused          {m['primary']} {m['primary_container']} {m['on_primary_container']} {m['primary']} {m['primary']}",
                             f"client.focused_inactive {m['outline_variant']} {m['surface_container_high']} {m['on_surface_variant']} {m['outline_variant']} {m['outline_variant']}",
                             f"client.unfocused        {m['outline_variant']} {m['surface_container']} {m['on_surface_variant']} {m['outline_variant']} {m['outline_variant']}",
                             f"client.urgent           {m['error']} {m['error_container']} {m['on_surface']} {m['error']} {m['error']}",
                             "",
                             "# Keybinds translated from Omarchy 4.5 (Hyprland → sway)"]
    else:
        lines += [
        "# class                 border        backgr.       text          indicator     child_border",
        f"client.focused          {c['accent']} {c['background']} {c['foreground']} {c['accent']} {c['accent']}",
        f"client.focused_inactive {c['muted']} {c['background']} {c['dark_foreground']} {c['muted']} {c['muted']}",
        f"client.unfocused        {c['muted']} {c['background']} {c['dark_foreground']} {c['muted']} {c['background']}",
        f"client.urgent           {c['red']} {c['background']} {c['foreground']} {c['red']} {c['red']}",
        "",
        "# Keybinds translated from Omarchy 4.5 (Hyprland → sway)",
        ]
    for key, cmd, src in BINDINGS:
        lines.append(f"# {src}")
        lines.append(f"bindsym {key} {_cmd(cmd)}")
    for i in range(1, 11):
        k = str(i % 10)
        if k != "0":  # Super+0 is zoom reset
            lines.append(f"bindsym $mod+{k} workspace number {i}")
        lines.append(f"bindsym $mod+Shift+{k} move container to workspace number {i}")
    lines += [
        "",
        "# Dialogs and pop-ups float, like on Omarchy.",
        'for_window [window_role="pop-up"] floating enable',
        'for_window [window_role="bubble"] floating enable',
        'for_window [window_type="dialog"] floating enable',
        'for_window [app_id="wofi"] floating enable',
        "",
    ]
    if LAYOUT == "floating":
        # /etc/linuxdesk/sway-layout = floating (end4's default): Windows-like windows. sway runs every matching
        # for_window in order and splits commands on ',' -- so one command per line. Super+T tiles one window.
        lines += [
            "# Floating by default (/etc/linuxdesk/sway-layout). Wayland windows have an app_id, XWayland ones a class.",
            'for_window [app_id=".*"] floating enable',
            'for_window [class=".*"] floating enable',
            'for_window [app_id=".*"] resize set width 92 ppt height 70 ppt',
            'for_window [class=".*"] resize set width 92 ppt height 70 ppt',
            'for_window [app_id=".*"] move position center',
            'for_window [class=".*"] move position center',
            "# The browser keeps the whole workspace (the output minus the bar and the dock, their exclusive zones).",
        ]
        for crit in ('app_id="(?i)chrom"', 'class="(?i)chrom"'):
            # `resize set` on a floating window sizes its CONTENT (sway resize.c), so borders + title bar would push
            # a 100 ppt browser under the dock: the maximized browser has no border (Super+drag still moves it).
            lines += [f"for_window [{crit}] border none",
                      f"for_window [{crit}] resize set width 100 ppt height 100 ppt",
                      f"for_window [{crit}] move position 0 px 0 px"]
        lines += [f'for_window [app_id="wofi"] resize set width 85 ppt height 60 ppt', ""]
    wp = ot.wallpaper(name)
    if wp and "," not in wp and ";" not in wp and "'" not in wp:
        lines.append(f"output * bg '{wp}' fill {c['background']}")
    else:
        lines.append(f"output * bg {c['background']} solid_color")
    lines += [
        "",
        "# No bar block: waybar is the bar (top) and the dock (bottom). mako shows notifications.",
        _cmd("exec waybar"),
        _cmd(f"exec waybar -c {cfg}/waybar/dock.jsonc -s {cfg}/waybar/dock.css"),
        _cmd("exec mako"),
    ]
    if m:
        lines[-3:] = [
            "# end4: waybar = the top bar; dock.sh = nwg-dock or the waybar taskbar + dock; swaync (else mako).",
            _cmd("exec waybar"),
            _cmd(f"exec sh {cfg}/sway/dock.sh"),
            _cmd(f"exec {NOTIFY}"),
        ]
        del lines[-5]   # the omarchy comment line
    return "\n".join(lines) + "\n"


def waybar_config():
    ws_icons = {str(i): str(i) for i in range(1, 10)}
    ws_icons.update({"10": "0", "default": "", "focused": "\U000f04fb"})
    cfg = {
        "layer": "top",
        "position": "top",
        "spacing": 0,
        "height": px(48),
        "reload_style_on_change": True,
        "modules-left": ["custom/omarchy", "sway/workspaces"],
        "modules-center": ["clock"],
        "modules-right": ["pulseaudio"],   # phone width at zoom 150% is ~645 px: memory left out
        "custom/omarchy": {
            "format": "",
            "on-click": "sh /opt/linuxdesk/omarchy/omarchy-menu.sh",
            "tooltip-format": "Omarchy Menu (Super + Space)",
        },
        "sway/workspaces": {
            "format": "{icon}",
            "disable-scroll": False,
            "format-icons": ws_icons,
            "persistent-workspaces": {str(i): [] for i in range(1, 4)},
        },
        "clock": {
            "format": "{:%H:%M}",
            "format-alt": "{:L%d %B W%V %Y}",
            "tooltip": False,
        },
        "memory": {
            "interval": 5,
            "format": " {percentage}%",
            "tooltip-format": "{used:0.1f} / {total:0.1f} GiB",
        },
        "pulseaudio": {
            "format": "{icon}",
            "format-muted": "\U000f075f",
            "format-icons": {"default": ["", "", ""]},
            "on-click": "pactl set-sink-mute @DEFAULT_SINK@ toggle",
            "scroll-step": 5,
            "tooltip-format": "Playing at {volume}%",
        },
    }
    head = "// Generated by /opt/linuxdesk/omarchy/sway-theme.py (Omarchy's waybar, minus cpu/battery/network: PRoot)\n"
    return head + json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"


def waybar_style(c):
    return f"""/* Generated by sway-theme.py from the Omarchy theme (Omarchy's waybar/style.css + theme waybar.css) */
@define-color foreground {c['foreground']};
@define-color background {c['background']};
@define-color accent {c['accent']};

* {{
  background-color: @background;
  color: @foreground;
  border: none;
  border-radius: 0;
  min-height: 0;
  font-family: '{FONT}';
  font-size: {px(16)}px;
}}

.modules-left {{ margin-left: {px(4)}px; }}
.modules-right {{ margin-right: {px(8)}px; }}

/* Touch targets: every button is at least 44 px wide and the full bar height. */
#custom-omarchy {{
  font-size: {px(26)}px;
  padding: 0 {px(14)}px;
  min-width: {px(48)}px;
  margin-right: {px(6)}px;
}}
#workspaces button {{
  all: initial;
  font-family: '{FONT}';
  font-size: {px(18)}px;
  padding: 0 {px(8)}px;
  margin: 0 {px(1)}px;
  min-width: {px(40)}px;
  min-height: {px(48)}px;
}}
#workspaces button label {{ min-width: {px(20)}px; }}
#workspaces button.focused {{ color: @accent; }}
#workspaces button.persistent {{ opacity: 0.5; }}

#memory, #pulseaudio {{
  min-width: {px(20)}px;
  padding: 0 {px(8)}px;
  margin: 0 {px(4)}px;
}}
#pulseaudio {{ font-size: {px(20)}px; }}
#clock {{ margin-left: {px(8.75)}px; }}
tooltip {{ padding: {px(2)}px; }}
.hidden {{ opacity: 0; }}
"""


# (module name, Nerd Font glyph, on-click, tooltip). Font Awesome glyphs: in every Nerd Font build.
DOCK = [
    ("apps", "\uf00a", LAUNCHER, "Apps (Super + Space)"),
    ("terminal", "\uf120", TERMINAL, "Terminal (Super + Return)"),
    ("browser", "\uf268", BROWSER, "Browser (Super + Shift + B)"),
    ("files", "\uf07b", FILES, "Files"),
    ("zoom-out", "\uf010", f"{ZOOM} out", "Zoom out (Super + minus)"),
    ("zoom-in", "\uf00e", f"{ZOOM} in", "Zoom in (Super + equal)"),
]


def dock_config():
    cfg = {
        "name": "dock",
        "layer": "top",
        "position": "bottom",
        "height": px(84),
        "margin-bottom": px(6),
        "spacing": 0,
        "reload_style_on_change": True,
        "modules-left": [],
        "modules-center": [f"custom/{m}" for m, _, _, _ in DOCK],
        "modules-right": [],
    }
    for m, glyph, click, tip in DOCK:
        cfg[f"custom/{m}"] = {"format": glyph, "on-click": click, "tooltip-format": tip}
    head = "// Generated by /opt/linuxdesk/omarchy/sway-theme.py: the LinuxDesk dock (a second waybar, bottom)\n"
    return head + json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"


def dock_style(c):
    buttons = ", ".join(f"#custom-{m}" for m, _, _, _ in DOCK)
    return f"""/* Generated by sway-theme.py from the Omarchy theme: the dock. A transparent full-width bar; the centered
   module box is the dock itself (layer-shell bars span the output, so the pill is drawn by CSS). */
@define-color foreground {c['foreground']};
@define-color background {c['background']};
@define-color lighter {c['lighter_background']};
@define-color accent {c['accent']};

* {{
  border: none;
  min-height: 0;
  font-family: '{FONT}';
}}
window#waybar {{ background-color: transparent; }}
.modules-center {{
  background-color: alpha(@background, 0.92);
  border: {px(2)}px solid alpha(@accent, 0.55);
  border-radius: {px(22)}px;
  padding: {px(6)}px {px(10)}px;
}}
{buttons} {{
  color: @foreground;
  background-color: transparent;
  font-size: {px(30)}px;
  min-width: {px(64)}px;
  min-height: {px(64)}px;
  padding: 0 {px(6)}px;
  margin: 0 {px(4)}px;
  border-radius: {px(16)}px;
}}
{", ".join(f"#custom-{m}:hover" for m, _, _, _ in DOCK)} {{ background-color: @lighter; color: @accent; }}
#custom-zoom-out {{ margin-left: {px(16)}px; }}
#custom-apps {{ color: @accent; }}
tooltip {{ background-color: @background; border: {px(1)}px solid @accent; padding: {px(4)}px; }}
tooltip label {{ color: @foreground; font-size: {px(14)}px; }}
"""


# ---------------------------------------------------------------------------------------------------------------
# end4 style (Tami, 2026-11-20: design #8 end-4 "illogical-impulse" + a Mac dock + a taskbar + floating windows).
# Layout, ~645 logical px wide (portrait 968 px at scale 1.5):
#   top bar  : pills  [logo][workspaces 1-3]  ...  [clock]  ...  [zoom -][zoom +][volume]
#   above dock: the taskbar row, open windows as icon pills (wlr/taskbar)
#   bottom   : the Mac dock, a centered rounded container: Apps, Terminal, Browser, Files
# Why the taskbar is NOT in the top bar: logo + 3 workspaces + clock + zoom + volume already take ~460 of 645 px with
# 48 px touch pills, leaving room for ~2 task pills -- a third window would push the clock off-center or clip.
# It sits in the dock's waybar as a vertical group (one layer surface) rather than as a third bar: two bars anchored
# at the bottom stack in launch order, which is a race between two `exec`s.
# Why zoom -/+ moved to the top bar: nwg-dock (preferred when installed) has no custom buttons, so the zoom buttons
# would vanish with it; the top bar is always waybar.
END4_FONT = f"'Inter', '{FONT}'"   # inter-font for text; the Nerd Font still supplies every glyph (fontconfig fallback)
NOTIFY = "sh -c 'command -v swaync >/dev/null && exec swaync || exec mako'"


def _m3_colors(m):
    return "\n".join(f"@define-color {k} {v};" for k, v in sorted(m.items()))


def end4_bar_config():
    cfg = json.loads(waybar_config().split("\n", 1)[1])
    cfg.update({
        "height": px(56),
        "margin-top": px(4), "margin-left": px(6), "margin-right": px(6),
        "spacing": px(6),
        "modules-right": ["custom/zoom-out", "custom/zoom-in", "pulseaudio"],
        "custom/zoom-out": {"format": "", "on-click": f"{ZOOM} out", "tooltip-format": "Zoom out (Super + minus)"},
        "custom/zoom-in": {"format": "", "on-click": f"{ZOOM} in", "tooltip-format": "Zoom in (Super + equal)"},
    })
    cfg["sway/workspaces"]["format"] = "{name}"   # plain numbers; the focused one is a primary_container pill
    del cfg["sway/workspaces"]["format-icons"]
    head = "// Generated by /opt/linuxdesk/omarchy/sway-theme.py, style end4: the top bar of pills\n"
    return head + json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"


def end4_bar_style(m):
    return f"""/* Generated by sway-theme.py, style end4 (end-4 illogical-impulse pills; matugen/M3 colors) */
{_m3_colors(m)}

* {{
  border: none;
  border-radius: 0;
  min-height: 0;
  font-family: {END4_FONT};
  font-size: {px(16)}px;
  color: @on_surface;
}}
window#waybar {{ background-color: transparent; }}

/* Every module is a pill on surface_container; touch targets are at least 48 px. */
#custom-omarchy, #workspaces, #clock, #custom-zoom-out, #custom-zoom-in, #pulseaudio {{
  background-color: alpha(@surface_container, 0.94);
  border: {px(1)}px solid @outline_variant;
  border-radius: 999px;
  min-height: {px(48)}px;
}}
#custom-omarchy {{
  font-family: '{FONT}';
  font-size: {px(24)}px;
  min-width: {px(48)}px;
  padding: 0 {px(10)}px;
  color: @primary;
}}
#workspaces {{ padding: 0 {px(3)}px; }}
#workspaces button {{
  all: initial;
  font-family: {END4_FONT};
  font-size: {px(16)}px;
  font-weight: bold;
  color: @on_surface_variant;
  min-width: {px(42)}px;
  min-height: {px(42)}px;
  margin: {px(3)}px {px(1)}px;
  border-radius: 999px;
}}
#workspaces button label {{ min-width: {px(42)}px; }}
#workspaces button.focused {{ background-color: @primary_container; color: @on_primary_container; }}
#workspaces button.urgent {{ background-color: @error; color: @on_error; }}
#workspaces button.persistent {{ color: alpha(@on_surface_variant, 0.55); }}
#clock {{ padding: 0 {px(16)}px; font-weight: bold; }}
#custom-zoom-out, #custom-zoom-in, #pulseaudio {{
  font-family: '{FONT}';
  font-size: {px(20)}px;
  min-width: {px(48)}px;
  padding: 0 {px(4)}px;
}}
#pulseaudio.muted {{ color: @error; }}
tooltip {{ background-color: @surface_container_high; border: {px(1)}px solid @outline_variant; border-radius: {px(12)}px; }}
tooltip label {{ color: @on_surface; font-size: {px(14)}px; }}
"""


END4_DOCK = DOCK[:4]   # Apps, Terminal, Browser, Files (zoom is in the top bar, see above)


def end4_taskbar():
    return {
        "format": "{icon}",
        "icon-size": px(28),
        "tooltip-format": "{title}",
        "on-click": "activate",
        "on-click-middle": "close",
        "all-outputs": True,
    }


def end4_dock_config():
    cfg = {
        "name": "dock",
        "layer": "top",
        "position": "bottom",
        "margin-bottom": px(10),
        "spacing": 0,
        "reload_style_on_change": True,
        "modules-left": [],
        "modules-center": ["group/stack"],
        "modules-right": [],
        "group/stack": {"orientation": "vertical", "modules": ["wlr/taskbar", "group/dockrow"]},
        "group/dockrow": {"orientation": "horizontal", "modules": [f"custom/{m}" for m, _, _, _ in END4_DOCK]},
        "wlr/taskbar": end4_taskbar(),
    }
    for m, glyph, click, tip in END4_DOCK:
        cfg[f"custom/{m}"] = {"format": glyph, "on-click": click, "tooltip-format": tip}
    head = ("// Generated by /opt/linuxdesk/omarchy/sway-theme.py, style end4: taskbar row + Mac dock (the fallback\n"
            "// when nwg-dock is not installed; see ~/.config/sway/dock.sh)\n")
    return head + json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"


def end4_dock_style(m):
    buttons = ", ".join(f"#custom-{x}" for x, _, _, _ in END4_DOCK)
    return f"""/* Generated by sway-theme.py, style end4: the taskbar row and the Mac dock. The bar window is transparent and
   full width; the centered rounded boxes are drawn by CSS (pixman: no blur, no shadow -> a 1 px outline instead). */
{_m3_colors(m)}

* {{ border: none; min-height: 0; font-family: {END4_FONT}; color: @on_surface; }}
window#waybar {{ background-color: transparent; }}

#taskbar {{
  background-color: alpha(@surface_container, 0.94);
  border: {px(1)}px solid @outline_variant;
  border-radius: 999px;
  padding: {px(4)}px;
  margin-bottom: {px(8)}px;
}}
#taskbar.empty {{ background-color: transparent; border-color: transparent; padding: 0; margin: 0; }}
#taskbar button {{
  min-width: {px(48)}px;
  min-height: {px(48)}px;
  padding: 0 {px(6)}px;
  margin: 0 {px(2)}px;
  border-radius: 999px;
  background-color: transparent;
}}
#taskbar button.active {{ background-color: @primary_container; }}
#taskbar button:hover {{ background-color: @surface_container_highest; }}

#dockrow {{
  background-color: alpha(@surface_container_low, 0.94);
  border: {px(1)}px solid @outline_variant;
  border-radius: {px(20)}px;
  padding: {px(8)}px {px(10)}px;
}}
{buttons} {{
  font-family: '{FONT}';
  font-size: {px(30)}px;
  color: @on_surface;
  min-width: {px(64)}px;
  min-height: {px(64)}px;
  margin: 0 {px(4)}px;
  border-radius: {px(16)}px;
  background-color: @surface_container_high;
}}
{", ".join(f"#custom-{x}:hover" for x, _, _, _ in END4_DOCK)} {{ background-color: @secondary_container; color: @on_secondary_container; }}
#custom-apps {{ background-color: @primary_container; color: @on_primary_container; }}
tooltip {{ background-color: @surface_container_high; border: {px(1)}px solid @outline_variant; border-radius: {px(12)}px; }}
tooltip label {{ color: @on_surface; font-size: {px(14)}px; }}
"""


def end4_dock_sh(cfg):
    """~/.config/sway/dock.sh: nwg-dock when installed (pinned apps + running windows + launcher), else the waybar dock.
    No -d (auto-hide): its hotspot needs a hovering pointer, which a finger never gives. -x reserves the dock's height
    so floating Chrome (sized to the workspace) never sits under it. -nows: the top bar has the workspaces."""
    return f"""#!/bin/sh
# Generated by /opt/linuxdesk/omarchy/sway-theme.py (style end4): the bottom dock.
if command -v nwg-dock >/dev/null 2>&1; then
  exec nwg-dock -x -nows -i {px(48)} -mb {px(10)} -c "{LAUNCHER}"
fi
exec waybar -c "{cfg}/waybar/dock.jsonc" -s "{cfg}/waybar/dock.css"
"""


def end4_nwg_dock_style(m):
    return f"""/* Generated by sway-theme.py, style end4: nwg-dock as the Mac dock (M3 colors from matugen) */
window {{
  background-color: alpha({m['surface_container_low']}, 0.94);
  border: {px(1)}px solid {m['outline_variant']};
  border-radius: {px(20)}px;
}}
#box {{ padding: {px(8)}px {px(10)}px; }}
button, image {{ background: none; border-style: none; box-shadow: none; color: {m['on_surface']}; }}
button {{
  min-width: {px(64)}px;
  min-height: {px(64)}px;
  padding: {px(4)}px;
  margin: 0 {px(4)}px;
  border-radius: {px(16)}px;
  font-family: {END4_FONT};
  font-size: {px(12)}px;
}}
button:hover {{ background-color: {m['secondary_container']}; }}
button:focus, button:active, button:checked {{ background-color: {m['primary_container']}; color: {m['on_primary_container']}; }}
"""


def end4_swaync_style(m):
    return f"""/* Generated by sway-theme.py, style end4: swaync recolored from the matugen/M3 palette. Keeps swaync's own
   layout (imported) and only overrides colors, radii and the font. */
@import url("/etc/xdg/swaync/style.css");
{_m3_colors(m)}

* {{ font-family: {END4_FONT}; }}
.notification-row {{ outline: none; }}
.notification-row .notification-background .notification,
.notification {{
  background: @surface_container_high;
  color: @on_surface;
  border: {px(1)}px solid @outline_variant;
  border-radius: {px(20)}px;
}}
.notification.critical {{ border-color: @error; }}
.notification .summary {{ color: @on_surface; font-weight: bold; }}
.notification .body {{ color: @on_surface_variant; }}
.close-button {{ background: @surface_container_highest; color: @on_surface; border-radius: 999px; }}
.control-center {{
  background: alpha(@surface, 0.97);
  color: @on_surface;
  border: {px(1)}px solid @outline_variant;
  border-radius: {px(24)}px;
}}
.widget-title > button, .widget-dnd > switch {{ background: @surface_container_highest; border-radius: 999px; }}
.widget-dnd > switch:checked {{ background: @primary_container; }}
.widget-dnd > switch slider {{ background: @primary; border-radius: 999px; }}
"""


def end4_wofi(c, m):
    return wofi(c).replace(
        "window {", f"window {{ border-radius: {px(24)}px;", 1
    ) + (f"#input {{ border-radius: 999px; background-color: {m['surface_container_high']}; }}\n"
         f"#entry {{ border-radius: 999px; }}\n"
         f"#entry:selected {{ background-color: {m['primary_container']}; }}\n"
         f"#entry:selected #text {{ color: {m['on_primary_container']}; }}\n"
         f"window {{ border-color: {m['outline_variant']}; background-color: {m['surface_container']}; }}\n"
         f"#inner-box, #outer-box, #scroll {{ background-color: {m['surface_container']}; }}\n")


def foot(c):
    h = lambda k: c[k].lstrip("#")  # noqa: E731  (foot wants RRGGBB without '#')
    return f"""# Generated from the Omarchy theme (colors.toml) by sway-theme.py
[main]
font={FONT}:size={pt(10)}
pad={px(10)}x{px(10)}

# foot 1.28 (ALARM 2026-11) renamed [colors] to [colors-dark]; the old name is an error that every terminal prints.
[colors-dark]
background={h('background')}
foreground={h('foreground')}
selection-foreground={h('bright_foreground')}
selection-background={h('selection')}
regular0={h('background')}
regular1={h('red')}
regular2={h('green')}
regular3={h('yellow')}
regular4={h('blue')}
regular5={h('magenta')}
regular6={h('cyan')}
regular7={h('foreground')}
bright0={h('muted')}
bright1={h('bright_red')}
bright2={h('bright_green')}
bright3={h('bright_yellow')}
bright4={h('bright_blue')}
bright5={h('bright_magenta')}
bright6={h('bright_cyan')}
bright7={h('bright_foreground')}
"""


def mako(c):
    return f"""# Generated from the Omarchy theme by sway-theme.py (Omarchy's mako: accent border, no rounding)
font={FONT} {pt(10)}
background-color={c['background']}
text-color={c['foreground']}
border-color={c['accent']}
border-size={px(2)}
border-radius=0
width={px(420)}
padding={px(10)}
outer-margin={px(20)}
anchor=top-right
default-timeout=5000
max-icon-size={px(32)}

[urgency=critical]
text-color={c['red']}
border-color={c['red']}
default-timeout=0
"""


def wofi(c):
    return f"""/* Generated from the Omarchy theme by sway-theme.py */
window {{ background-color: {c['background']}; border: {px(2)}px solid {c['accent']}; font-family: '{FONT}'; font-size: {px(14)}px; }}
#input {{ margin: {px(6)}px; padding: {px(6)}px; border: none; background-color: {c['lighter_background']}; color: {c['foreground']}; }}
#inner-box, #outer-box, #scroll {{ background-color: {c['background']}; }}
#entry {{ padding: {px(6)}px; min-height: {px(56)}px; color: {c['foreground']}; }}
#entry:selected {{ background-color: {c['selection']}; }}
#entry:selected #text {{ color: {c['accent']}; }}
"""


def main(argv):
    if argv[1:2] == ["--stamp"]:
        # start-sway.sh re-renders when the generated sway config lacks this line (new SWAY_REV or new UI scale).
        print(f"# {stamp()}")
        return 0
    name = argv[1] if len(argv) > 1 else "tokyo-night"
    c = ot.load(name)
    cfg = os.path.join(ot.OUT_HOME, ".config")
    if STYLE == "end4":
        m, src = matugen_palette(ot.wallpaper(name))
        c = end4_colors(c, m)
        ot.write(os.path.join(cfg, "sway", "config"), sway_config(c, name, cfg, m))
        ot.write(os.path.join(cfg, "sway", "dock.sh"), end4_dock_sh(cfg))
        ot.write(os.path.join(cfg, "waybar", "config.jsonc"), end4_bar_config())
        ot.write(os.path.join(cfg, "waybar", "style.css"), end4_bar_style(m))
        ot.write(os.path.join(cfg, "waybar", "dock.jsonc"), end4_dock_config())
        ot.write(os.path.join(cfg, "waybar", "dock.css"), end4_dock_style(m))
        ot.write(os.path.join(cfg, "nwg-dock", "style.css"), end4_nwg_dock_style(m))
        ot.write(os.path.join(cfg, "swaync", "style.css"), end4_swaync_style(m))
        ot.write(os.path.join(cfg, "foot", "foot.ini"), foot(c))
        ot.write(os.path.join(cfg, "mako", "config"), mako(c).replace("border-radius=0", f"border-radius={px(20)}"))
        ot.write(os.path.join(cfg, "wofi", "style.css"), end4_wofi(c, m))
        print(f"[sway-theme] style end4 ({LAYOUT}), Omarchy theme '{name}', palette: {src} "
              f"(primary {m['primary']}, surface {m['surface']})")
        return 0
    ot.write(os.path.join(cfg, "sway", "config"), sway_config(c, name, cfg))
    ot.write(os.path.join(cfg, "waybar", "config.jsonc"), waybar_config())
    ot.write(os.path.join(cfg, "waybar", "style.css"), waybar_style(c))
    ot.write(os.path.join(cfg, "waybar", "dock.jsonc"), dock_config())
    ot.write(os.path.join(cfg, "waybar", "dock.css"), dock_style(c))
    ot.write(os.path.join(cfg, "foot", "foot.ini"), foot(c))
    ot.write(os.path.join(cfg, "mako", "config"), mako(c))
    ot.write(os.path.join(cfg, "wofi", "style.css"), wofi(c))
    print(f"[sway-theme] style omarchy ({LAYOUT}): Omarchy theme '{name}' rendered for sway (accent {c['accent']}, background {c['background']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
