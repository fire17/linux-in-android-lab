/*
 * LinuxDesk: in-process entry point into Termux:X11's lorie X server.
 * Copyright (C) 2026 Tami (fire17). GPL-3.0-or-later (it links lorie, which is GPL-3.0).
 *
 * WHY this file is in package com.termux.x11:
 * upstream's CmdEntryPoint has a package-private constructor, and upstream only ever starts it through
 * CmdEntryPoint.main(), via app_process from a Termux shell (shell-loader/Loader.java). An app that
 * embeds lorie has no app_process. So this helper does what main() does, inside our own ":x11" service
 * process:
 *
 *   upstream main():                          here:
 *     initEntryPoint()                          System.loadLibrary("Xlorie"); CmdEntryPoint.ctx = appContext
 *       ctx = createContext()  (hidden API)       (the native method table is registered by
 *       System.load(<apk>!lib/.../libXlorie)       libXlorie's JNI_OnLoad -> registerCmdEntryPointNatives)
 *     handler.post(new CmdEntryPoint(args))     new CmdEntryPoint(args) on the service's main thread
 *     Looper.loop()                             (the service process already has a main Looper)
 *
 * The constructor calls the native start(args). It sets up TMPDIR, XKB_CONFIG_ROOT and fonts, starts
 * dix_main on its own thread, and returns. On failure it calls System.exit(1), which kills only our
 * :x11 process; SessionService sees the binder die. Once the server listens, CmdEntryPoint broadcasts
 * ACTION_START (package = BuildConfig.APPLICATION_ID = com.fire17.linuxdesk) with its binder.
 * LorieApp$Receiver in our main process hands that binder to DesktopActivity (a MainActivity subclass),
 * which then attaches its Surface. This is upstream's own handshake, unchanged.
 *
 * Precedent: DroidDesk (GPL-3) also runs CmdEntryPoint inside a dedicated ":x11" service process of its
 * own app, though from a forked copy of the Java sources. This file uses the UNMODIFIED upstream
 * library instead. NOT yet verified on a device.
 */
package com.termux.x11;

import android.content.Context;
import android.util.Log;

public final class LinuxDeskX11Entry {
    private static final String TAG = "LinuxDeskX11";
    private static CmdEntryPoint instance;

    private LinuxDeskX11Entry() {}

    /** Must be called on the main (Looper) thread of the dedicated X server process. Idempotent. */
    public static synchronized boolean start(Context appContext, String[] args) {
        if (instance != null)
            return true;
        System.loadLibrary("Xlorie");
        CmdEntryPoint.ctx = appContext;
        Log.i(TAG, "starting lorie X server, args=" + String.join(" ", args));
        instance = new CmdEntryPoint(args); // native start(); System.exit(1) on failure
        return true;
    }

    public static synchronized boolean isStarted() {
        return instance != null;
    }
}
