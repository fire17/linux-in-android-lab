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
SWAY_REV = "linuxdesk-sway-rev 3"


def pt(n):
    """A font size of n points at 96 dpi, scaled like px(): sway's x11 output runs at scale 1 on the phone's pixels."""
    return max(6, round(n * ot.S))


def stamp():
    return f"{SWAY_REV} scale {ot.scale_str()}"


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
    ("$mod+space", f"exec {LAUNCHER}", "SUPER+SPACE Omarchy menu (wofi here)"),
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


def sway_config(c, name, cfg):
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
            "on-click": LAUNCHER,
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
#entry {{ padding: {px(6)}px; color: {c['foreground']}; }}
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
    ot.write(os.path.join(cfg, "sway", "config"), sway_config(c, name, cfg))
    ot.write(os.path.join(cfg, "waybar", "config.jsonc"), waybar_config())
    ot.write(os.path.join(cfg, "waybar", "style.css"), waybar_style(c))
    ot.write(os.path.join(cfg, "waybar", "dock.jsonc"), dock_config())
    ot.write(os.path.join(cfg, "waybar", "dock.css"), dock_style(c))
    ot.write(os.path.join(cfg, "foot", "foot.ini"), foot(c))
    ot.write(os.path.join(cfg, "mako", "config"), mako(c))
    ot.write(os.path.join(cfg, "wofi", "style.css"), wofi(c))
    print(f"[sway-theme] Omarchy theme '{name}' rendered for sway (accent {c['accent']}, background {c['background']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
