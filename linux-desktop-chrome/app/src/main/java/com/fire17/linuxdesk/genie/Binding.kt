package com.fire17.linuxdesk.genie

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import computer.iroh.SecretKey
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.Random
import java.util.concurrent.CopyOnWriteArraySet

/**
 * This phone's iroh identity and the one genie it belongs to (a port of Storage Genie's Binding.kt;
 * spec 01 §1.2 and §4 rule 1).
 *
 * One phone, one genie: the 6-char code is good for exactly one successful pairing. It is then traded
 * for a long random token that only that genie holds, and the code dies. The phone stores only
 * sha256(token). While a binding exists, every other genie is refused ("bound"). Only the person
 * tapping "Forget my genie" in the app clears it; there is no verb for that.
 *
 * Secrets (the code, the token, the iroh secret key) are never logged and never leave this file,
 * except: the code and the public node id go into the share line, and the token goes back to the genie
 * once, in the pairing reply.
 */
object Binding {
    const val PREFS = "ld.binding"

    sealed class Auth {
        data class Ok(val genie: String, val newToken: String?) : Auth()
        data class Refused(val error: String) : Auth()
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /**
     * Called (on any thread) after a pairing, a Forget, a code rotation or a GenieSettings change. The
     * Pair screen re-renders on it; GenieRuntime can use it to start or stop the link and to close
     * live sessions after a Forget.
     */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    internal fun changed() { for (l in listeners) runCatching { l() } }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun core(ctx: Context) = BindingCore(prefs(ctx))

    // ---- iroh identity -------------------------------------------------------------------------------

    /** The iroh node secret key. Generated once, kept in ld.binding. Never logged, exported or shown. */
    fun secretKey(ctx: Context): SecretKey = synchronized(lock) {
        val p = prefs(ctx)
        p.getString("sk", null)?.let {
            try { return SecretKey.fromBytes(Base64.getDecoder().decode(it)) } catch (_: Throwable) {}
        }
        val k = SecretKey.generate()
        p.edit().putString("sk", Base64.getEncoder().encodeToString(k.toBytes())).commit()
        k
    }

    /** The public node id (hex), exactly as Storage Genie derives it: secretKey.public().toString(). */
    fun nodeId(ctx: Context): String = secretKey(ctx).public().toString()

    /** The node id, or null when the iroh native library is not loadable (then the share line says "local"). */
    fun nodeIdOrNull(ctx: Context): String? = runCatching { nodeId(ctx) }.getOrNull()

    // ---- the code ------------------------------------------------------------------------------------

    fun code(ctx: Context): String = synchronized(lock) { core(ctx).code() }

    /** The Pair screen calls this when it shows the code, so the 30-min clock starts when she sees it. */
    fun freshCodeIfStale(ctx: Context) {
        val rotated = synchronized(lock) { core(ctx).freshCodeIfStale() }
        if (rotated) changed()
    }

    /** `LINUXDESK <node-id-hex>:<CODE>`, or `LINUXDESK local:<CODE>` without iroh (phase A). */
    fun pairingLine(ctx: Context): String = BindingCore.line(nodeIdOrNull(ctx), code(ctx))

    // ---- the binding ---------------------------------------------------------------------------------

    fun isBound(ctx: Context): Boolean = synchronized(lock) { core(ctx).isBound() }
    fun genie(ctx: Context): String? = synchronized(lock) { core(ctx).genie() }
    fun pairedAt(ctx: Context): Long = synchronized(lock) { core(ctx).pairedAt() }

    /**
     * Bumped on every pairing and every Forget. A live session records it at auth time; when it changes,
     * that session answers its next command with {"error":"unpaired"} and closes (spec 01 §1.2 rule 3).
     */
    fun generation(ctx: Context): Long = synchronized(lock) { core(ctx).generation() }

    /** Decides the first frame of a connection. Constant-time compares; never logs secrets. */
    fun authenticate(ctx: Context, given: String, genie: String): Auth {
        var rotated = false
        val r = synchronized(lock) {
            val c = core(ctx)
            val before = c.codeAt()
            c.authenticate(given, genie).also { rotated = c.codeAt() != before }
        }
        if (r is Auth.Ok && r.newToken != null) {
            syncHashFile(ctx)
            runCatching { GenieNotes.paired(ctx, r.genie) }
            changed()
        } else if (rotated) {
            changed()
        }
        return r
    }

    /** "Forget my genie": in-app tap only. Clears the binding, deletes genie.hash, shows a fresh code. */
    fun forget(ctx: Context) {
        synchronized(lock) { core(ctx).forget() }
        syncHashFile(ctx)
        runCatching { GenieNotes.clearConnected(ctx) }
        changed()
    }

    /**
     * Makes `<rootfs>/run/linuxdesk/genie.hash` match the binding: sha256(token) hex at mode 0600 while
     * bound, absent while not. deskd reads it to accept the paired token directly (phase A, adb forward
     * to 8765). The rootfs can be replaced by RootfsInstaller, so SessionService must also call this once
     * per session after the rootfs is installed and before deskd starts. Returns false if the file could
     * not be written (for example, the rootfs is not installed yet).
     */
    fun syncHashFile(ctx: Context): Boolean {
        val hash = synchronized(lock) { core(ctx).tokenHash() }
        return GenieHashFile.write(File(ctx.filesDir, "rootfs"), hash)
    }
}

/**
 * The Pair-screen settings (prefs ld.genie, kept apart from ld.binding so settings never sit next to
 * secrets).
 *
 * TAMI OVERRIDE (spec 02 §03, 17:52) is binding here. The defaults are Storage Genie's behaviour; every
 * agent recommendation is a setting that is OFF by default:
 *  - genie_window = SG (default: the app is on screen or was within 2 min) | FGS (while the session runs);
 *  - refuse_while_locked: default OFF;
 *  - xor_guard ("All-files access XOR Genie shell"): default OFF, so both may be on together;
 *  - wide_window_gate: default OFF = only scan/delete requests need the app on screen (as in SG).
 * "Genie access" and "Developer: local link" are spec 01 §4 rule 2 and stay OFF by default.
 */
object GenieSettings {
    private const val PREFS = "ld.genie"

    enum class Window { SG, FGS }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun putBool(ctx: Context, k: String, v: Boolean) {
        prefs(ctx).edit().putBoolean(k, v).commit()
        Binding.changed()
    }

    /** "Genie access": a paired genie may connect at all (iroh link + deskd). Default OFF. */
    fun genieAccess(ctx: Context): Boolean = genieAccess(prefs(ctx))
    internal fun genieAccess(p: SharedPreferences) = p.getBoolean("access", false)

    /**
     * Turns Genie access on or off. Returns false (refused) only when the XOR guard is ON and All-files
     * access is granted. With the guard OFF (the default), both may be on.
     */
    fun setGenieAccess(ctx: Context, on: Boolean): Boolean {
        if (on && xorBlocks(xorGuard(ctx), allFilesAccess(ctx))) return false
        putBool(ctx, "access", on)
        return true
    }

    /** "Developer: local link": the WireServer on 127.0.0.1:8766 (phase A, adb forward). Default OFF. */
    fun localLink(ctx: Context): Boolean = localLink(prefs(ctx))
    internal fun localLink(p: SharedPreferences) = p.getBoolean("localLink", false)
    fun setLocalLink(ctx: Context, on: Boolean) = putBool(ctx, "localLink", on)

    /** `genie_window`: SG (default) = on screen or within 2 min; FGS = whenever the session runs. */
    fun genieWindow(ctx: Context): Window = genieWindow(prefs(ctx))
    internal fun genieWindow(p: SharedPreferences): Window =
        runCatching { Window.valueOf(p.getString("genie_window", null) ?: "") }.getOrDefault(Window.SG)
    /** The same as a wire string, "sg" or "fgs" (for sys.ping / logs). */
    fun genieWindowName(ctx: Context): String = genieWindow(ctx).name.lowercase(java.util.Locale.ROOT)
    fun setGenieWindow(ctx: Context, w: Window) {
        prefs(ctx).edit().putString("genie_window", w.name).commit()
        Binding.changed()
    }

    /** `refuse_while_locked`: when ON, shell.* and input.* are refused while the device is locked. Default OFF. */
    fun refuseWhileLocked(ctx: Context): Boolean = refuseWhileLocked(prefs(ctx))
    internal fun refuseWhileLocked(p: SharedPreferences) = p.getBoolean("refuse_while_locked", false)
    fun setRefuseWhileLocked(ctx: Context, on: Boolean) = putBool(ctx, "refuse_while_locked", on)

    /**
     * `wide_window_gate`: when ON, EVERY genie verb needs the app on screen (or within 2 min) under
     * genie_window=sg. Default OFF = like Storage Genie: only scan and delete requests need it.
     * The wire Router reads prefs "ld.genie" key "wide_window_gate" directly; keep the key exactly.
     */
    fun wideWindowGate(ctx: Context): Boolean = wideWindowGate(prefs(ctx))
    internal fun wideWindowGate(p: SharedPreferences) = p.getBoolean("wide_window_gate", false)
    fun setWideWindowGate(ctx: Context, on: Boolean) = putBool(ctx, "wide_window_gate", on)

    /** `xor_guard`: when ON, All-files access and Genie access cannot both be on. Default OFF. */
    fun xorGuard(ctx: Context): Boolean = xorGuard(prefs(ctx))
    internal fun xorGuard(p: SharedPreferences) = p.getBoolean("xor_guard", false)

    /**
     * Turns the XOR guard on or off. Turning it ON while both are already on also turns Genie access
     * OFF, so the guard is never ON while being broken. Returns true when that happened.
     */
    fun setXorGuard(ctx: Context, on: Boolean): Boolean {
        val conflict = on && genieAccess(ctx) && allFilesAccess(ctx)
        val e = prefs(ctx).edit().putBoolean("xor_guard", on)
        if (conflict) e.putBoolean("access", false)
        e.commit()
        Binding.changed()
        return conflict
    }

    /**
     * For the wire Router / GenieRuntime: true when Genie shell must be refused right now because the
     * guard is ON and All-files access was granted (possibly in Android Settings, behind the app's back).
     */
    fun xorBlocksNow(ctx: Context): Boolean = xorBlocks(xorGuard(ctx), allFilesAccess(ctx))

    internal fun xorBlocks(guard: Boolean, allFiles: Boolean) = guard && allFiles

    /** True when this app can read and write all of shared storage (API 30+: All-files access). */
    fun allFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

/**
 * When the genie may act (spec 02 §03 #10). A port of Storage Genie's Link.appOpen():
 * SG mode = some LinuxDesk activity is on screen, or was within 2 minutes. FGS mode = always while the
 * session runs (the link itself only runs inside SessionService).
 *
 * Note: SG's appOpen() also counts its own visible LinkService. In LinuxDesk the foreground service is
 * SessionService, which runs for the whole session; counting it would make SG mode equal FGS mode, so it
 * is not counted here. That is exactly the `fgs` setting.
 *
 * Wiring: the integrator calls GenieWindow.install(this) in LinuxDeskApp.onCreate (main process only).
 * The wire Router answers {"error":"app_closed"} when [mayAct] is false and calls GenieNotes.appClosed.
 */
object GenieWindow {
    const val GRACE_MS = 2L * 60 * 1000

    @Volatile private var started = 0
    @Volatile var lastVisibleAt = 0L
        private set

    val visible: Boolean get() = started > 0

    /** Counts started activities of this process (DesktopActivity, LauncherActivity, PairActivity, …). */
    fun install(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                synchronized(this@GenieWindow) { started += 1; lastVisibleAt = System.currentTimeMillis() }
            }
            override fun onActivityStopped(a: android.app.Activity) {
                synchronized(this@GenieWindow) {
                    started = (started - 1).coerceAtLeast(0); lastVisibleAt = System.currentTimeMillis()
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** True when the genie window is open right now under the chosen setting. */
    fun isOpen(ctx: Context): Boolean =
        open(GenieSettings.genieWindow(ctx), visible, lastVisibleAt, System.currentTimeMillis())

    /** True when shell.* and input.* must be refused now: refuse_while_locked is ON and the device is locked. */
    fun lockedRefusal(ctx: Context): Boolean = GenieSettings.refuseWhileLocked(ctx) &&
        (ctx.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true)

    internal fun open(w: GenieSettings.Window, visible: Boolean, lastVisibleAt: Long, now: Long): Boolean =
        when (w) {
            GenieSettings.Window.FGS -> true
            GenieSettings.Window.SG -> visible || (lastVisibleAt > 0 && now - lastVisibleAt in 0 until GRACE_MS)
        }
}

/**
 * The pure pairing rules, with no Android or iroh calls, so BindingTest can run them on the JVM.
 * Not thread-safe by itself: Binding holds a lock around every call.
 */
internal class BindingCore(
    private val p: SharedPreferences,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val rng: Random = SECURE,
) {
    companion object {
        const val CODE_TTL_MS = 30L * 60 * 1000
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LEN = 6
        const val MAX_FAILS = 5
        const val TOKEN_BYTES = 32
        const val GENIE_MAX = 80
        private val SECURE = SecureRandom()
        private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{43}")

        fun line(nodeId: String?, code: String) = "LINUXDESK ${nodeId ?: "local"}:$code"

        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun eq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        /** Display-safe genie name: no control or bidi-format characters, at most 80 chars. */
        fun cleanGenie(g: String): String = g
            .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(GENIE_MAX).ifEmpty { "genie" }
    }

    fun code(): String = p.getString("code", null) ?: newCode()
    fun codeAt(): Long = p.getLong("codeAt", 0L)

    private fun newCode(): String {
        val c = (1..CODE_LEN).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")
        p.edit().putString("code", c).putInt("fails", 0).putLong("codeAt", now()).commit()
        return c
    }

    /** A code older than 30 min is dead. A clock moved back by more than 30 min also kills it. */
    private fun codeStale(): Boolean {
        val age = now() - codeAt()
        return age > CODE_TTL_MS || age < -CODE_TTL_MS
    }

    /** Returns true when it rotated the code. */
    fun freshCodeIfStale(): Boolean {
        if (isBound() || !codeStale()) return false
        newCode()
        return true
    }

    fun tokenHash(): String? = p.getString("tokenHash", null)
    fun isBound() = tokenHash() != null
    fun genie(): String? = p.getString("genie", null)
    fun pairedAt(): Long = p.getLong("pairedAt", 0L)
    fun generation(): Long = p.getLong("gen", 0L)

    fun authenticate(given: String, genie: String): Binding.Auth {
        val bound = tokenHash()
        if (bound != null) {
            return if (eq(sha256Hex(given), bound)) Binding.Auth.Ok(genie() ?: "", null)
            else Binding.Auth.Refused("bound")
        }
        // A code is good for 30 minutes after it is first shown; a stale leaked line pairs nothing.
        if (codeStale()) newCode()
        val attempt = given.trim().uppercase(Locale.ROOT)
        // Something that cannot be a code is no guess at the code, so it costs no strike. This keeps a
        // forgotten genie, still retrying with its old token, from rotating the code the person is about
        // to share. That genie gets "unpaired", so it knows to ask for a new code.
        if (attempt.length != CODE_LEN) {
            return Binding.Auth.Refused(if (TOKEN_SHAPE.matches(given.trim())) "unpaired" else "auth")
        }
        if (eq(attempt, code())) {
            val tok = ByteArray(TOKEN_BYTES).also { rng.nextBytes(it) }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tok)
            val name = cleanGenie(genie)
            p.edit().putString("tokenHash", sha256Hex(token)).putString("genie", name)
                .remove("code").putInt("fails", 0).putLong("pairedAt", now())
                .putLong("gen", generation() + 1).commit()
            return Binding.Auth.Ok(name, token)
        }
        val fails = p.getInt("fails", 0) + 1
        if (fails >= MAX_FAILS) newCode() else p.edit().putInt("fails", fails).commit()
        return Binding.Auth.Refused("auth")
    }

    fun forget() {
        p.edit().remove("tokenHash").remove("genie").remove("pairedAt")
            .putLong("gen", generation() + 1).commit()
        newCode()
    }
}

/** `<rootfs>/run/linuxdesk/genie.hash`: sha256(token) hex + "\n", mode 0600, in a 0700 directory. */
internal object GenieHashFile {
    const val REL = "run/linuxdesk/genie.hash"

    fun write(rootfs: File, hashHex: String?): Boolean {
        val f = File(rootfs, REL)
        val tmp = File(f.parentFile, "genie.hash.tmp")
        if (hashHex == null) {
            tmp.delete()
            return !f.exists() || f.delete()
        }
        // Not installed yet: SessionService's per-session syncHashFile writes it later.
        if (!File(rootfs, "etc").isDirectory) return false
        val dir = f.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        ownerOnly(dir, exec = true)
        tmp.delete()
        return try {
            if (!tmp.createNewFile()) return false
            ownerOnly(tmp, exec = false) // before any byte is written
            tmp.writeText(hashHex + "\n")
            if (tmp.renameTo(f)) true else { tmp.delete(); false }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun ownerOnly(f: File, exec: Boolean) {
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
        if (exec) f.setExecutable(true, true)
    }
}
