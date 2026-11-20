-- LinuxDesk: Android app windows (omarchy-android: one scrcpy per app, class "scrcpy"). Loaded by a pcall(dofile)
-- line in ~/.config/hypr/looknfeel.lua and by `hyprctl eval` from omarchy-android; named rules, so loading it again
-- replaces them instead of stacking (Hyprland 0.56 Lua: hl.window_rule with the same name updates the rule).
hl.window_rule({ name = "linuxdesk-android-tag", match = { class = "^(scrcpy)$" }, tag = "+android" })
-- Tiled like any app window; the virtual display follows the window size (scrcpy rescales, no letterbox per resize).
hl.window_rule({ name = "linuxdesk-android-tile", match = { tag = "android" }, tile = true })
