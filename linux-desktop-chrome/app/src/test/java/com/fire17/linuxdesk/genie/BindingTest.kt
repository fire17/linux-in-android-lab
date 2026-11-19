package com.fire17.linuxdesk.genie

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Random

/** The pairing rules of spec 01 §1.2 / §4 rule 1, run on the plain JVM (no Android, no iroh). */
class BindingTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000L
    private val prefs = FakePrefs()
    private fun core(seed: Long = 7) = BindingCore(prefs, { clock }, Random(seed))

    private fun wrong(c: String): String {
        // Same length, same alphabet, guaranteed different from c.
        val a = BindingCore.ALPHABET
        return c.map { a[(a.indexOf(it) + 1) % a.length] }.joinToString("")
    }

    @Test fun codeShapeAndAlphabet() {
        val c = core().code()
        assertEquals(6, c.length)
        assertTrue(c.all { it in BindingCore.ALPHABET })
        for (bad in "01ILO") assertFalse("$bad must not be in the alphabet", bad in BindingCore.ALPHABET)
        assertEquals(c, core().code()) // stable until used or rotated
    }

    @Test fun pairingLine() {
        assertEquals("LINUXDESK abc123:ABC234", BindingCore.line("abc123", "ABC234"))
        assertEquals("LINUXDESK local:ABC234", BindingCore.line(null, "ABC234"))
    }

    @Test fun goodCodePairsOnceAndStoresOnlyTheHash() {
        val b = core()
        val code = b.code()
        val r = b.authenticate(" ${code.lowercase()} ", "agent:mag21")
        assertTrue(r is Binding.Auth.Ok)
        r as Binding.Auth.Ok
        assertEquals("agent:mag21", r.genie)
        val token = r.newToken!!
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(b.isBound())
        assertEquals(BindingCore.sha256Hex(token), b.tokenHash())
        assertFalse("token must never be stored", prefs.map.values.any { it == token })
        assertNull("the code dies on success", prefs.map["code"])
        assertEquals(1L, b.generation())

        // The token works later, without a new token.
        assertEquals(Binding.Auth.Ok("agent:mag21", null), b.authenticate(token, "whoever"))
        // The old code no longer works: once bound, anything but the token is "bound".
        assertEquals(Binding.Auth.Refused("bound"), b.authenticate(code, "agent:other"))
        assertEquals(Binding.Auth.Refused("bound"), b.authenticate("", "agent:other"))
        assertEquals(Binding.Auth.Refused("bound"), b.authenticate(token + "x", "agent:other"))
    }

    @Test fun wrongCodeIsAuthAndFiveFailsRotate() {
        val b = core()
        val first = b.code()
        repeat(4) { assertEquals(Binding.Auth.Refused("auth"), b.authenticate(wrong(first), "g")) }
        assertEquals("4 fails keep the code", first, b.code())
        assertEquals(Binding.Auth.Refused("auth"), b.authenticate(wrong(first), "g"))
        val second = b.code()
        assertNotEquals("the 5th fail rotates the code", first, second)
        assertEquals(0, prefs.getInt("fails", -1))
        assertTrue(b.authenticate(first, "g") is Binding.Auth.Refused)
        assertTrue(b.authenticate(second, "g") is Binding.Auth.Ok)
    }

    @Test fun nonCodeAttemptsCostNoStrike() {
        val b = core()
        val code = b.code()
        val oldToken = "A".repeat(43)
        repeat(10) { assertEquals(Binding.Auth.Refused("unpaired"), b.authenticate(oldToken, "g")) }
        repeat(10) { assertEquals(Binding.Auth.Refused("auth"), b.authenticate("", "g")) }
        assertEquals(code, b.code())
        assertEquals(0, prefs.getInt("fails", 0))
    }

    @Test fun codeExpiresAfter30Minutes() {
        val b = core()
        val code = b.code()
        clock += BindingCore.CODE_TTL_MS + 1
        assertEquals(Binding.Auth.Refused("auth"), b.authenticate(code, "g"))
        assertNotEquals(code, b.code())
    }

    @Test fun codeValidJustBefore30Minutes() {
        val b = core()
        val code = b.code()
        clock += BindingCore.CODE_TTL_MS - 1
        assertTrue(b.authenticate(code, "g") is Binding.Auth.Ok)
    }

    @Test fun freshCodeIfStale() {
        val b = core()
        val code = b.code()
        assertFalse(b.freshCodeIfStale())
        clock += BindingCore.CODE_TTL_MS + 1
        assertTrue(b.freshCodeIfStale())
        assertNotEquals(code, b.code())
        // A big clock jump backwards also kills the code.
        val c2 = b.code()
        clock -= 3 * BindingCore.CODE_TTL_MS
        assertTrue(b.freshCodeIfStale())
        assertNotEquals(c2, b.code())
    }

    @Test fun freshCodeIfStaleDoesNothingWhileBound() {
        val b = core()
        b.authenticate(b.code(), "g")
        clock += 10 * BindingCore.CODE_TTL_MS
        assertFalse(b.freshCodeIfStale())
        assertTrue(b.isBound())
    }

    @Test fun forgetUnbindsAndShowsAFreshCode() {
        val b = core()
        val code = b.code()
        val token = (b.authenticate(code, "g") as Binding.Auth.Ok).newToken!!
        b.forget()
        assertFalse(b.isBound())
        assertNull(b.genie())
        assertEquals(2L, b.generation())
        val fresh = b.code()
        assertEquals(6, fresh.length)
        // The old token no longer authenticates.
        assertEquals(Binding.Auth.Refused("unpaired"), b.authenticate(token, "g"))
        // A new pairing works and gives a different token.
        val t2 = (b.authenticate(fresh, "g2") as Binding.Auth.Ok).newToken!!
        assertNotEquals(token, t2)
        assertEquals(3L, b.generation())
    }

    @Test fun genieNameIsSanitized() {
        val b = core()
        val r = b.authenticate(b.code(), "agent:‮evil\u0007" + "x".repeat(200)) as Binding.Auth.Ok
        assertFalse(r.genie.contains('‮'))
        assertFalse(r.genie.contains('\u0007'))
        assertTrue(r.genie.length <= BindingCore.GENIE_MAX)
        assertEquals("genie", BindingCore.cleanGenie("\u0000 "))
    }

    @Test fun constantTimeEqIsPlainEquality() {
        assertTrue(BindingCore.eq("abc", "abc"))
        assertFalse(BindingCore.eq("abc", "abd"))
        assertFalse(BindingCore.eq("abc", "abcd"))
    }

    @Test fun sha256HexMatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BindingCore.sha256Hex("abc"))
    }

    // ---- Tami override (spec 02 §03): Storage Genie defaults, recommendations OFF ------------------

    @Test fun defaultsAreStorageGenieBehaviour() {
        val fresh = FakePrefs()
        assertFalse("Genie access OFF", GenieSettings.genieAccess(fresh))
        assertFalse("local link OFF", GenieSettings.localLink(fresh))
        assertEquals("window = sg", GenieSettings.Window.SG, GenieSettings.genieWindow(fresh))
        assertFalse("refuse while locked OFF: commands work while locked", GenieSettings.refuseWhileLocked(fresh))
        assertFalse("XOR guard OFF: All-files + shell may both be on", GenieSettings.xorGuard(fresh))
        fresh.map["genie_window"] = "garbage"
        assertEquals("unknown value falls back to sg", GenieSettings.Window.SG, GenieSettings.genieWindow(fresh))
        fresh.map["genie_window"] = "FGS"
        assertEquals(GenieSettings.Window.FGS, GenieSettings.genieWindow(fresh))
    }

    @Test fun sgWindowIsOnScreenOrWithinTwoMinutes() {
        val sg = GenieSettings.Window.SG
        val now = 10_000_000L
        assertTrue("on screen", GenieWindow.open(sg, true, 0L, now))
        assertTrue("just left", GenieWindow.open(sg, false, now - 1_000, now))
        assertTrue("119 s ago", GenieWindow.open(sg, false, now - GenieWindow.GRACE_MS + 1, now))
        assertFalse("2 min ago", GenieWindow.open(sg, false, now - GenieWindow.GRACE_MS, now))
        assertFalse("never shown", GenieWindow.open(sg, false, 0L, now))
        assertFalse("clock moved back", GenieWindow.open(sg, false, now + 5_000, now))
    }

    @Test fun fgsWindowIsAlwaysOpen() {
        assertTrue(GenieWindow.open(GenieSettings.Window.FGS, false, 0L, 10_000_000L))
    }

    @Test fun xorGuardBlocksOnlyWhenOnAndAllFilesGranted() {
        assertFalse(GenieSettings.xorBlocks(guard = false, allFiles = true)) // the default: both may be on
        assertFalse(GenieSettings.xorBlocks(guard = true, allFiles = false))
        assertTrue(GenieSettings.xorBlocks(guard = true, allFiles = true))
    }

    // ---- genie.hash ------------------------------------------------------------------------------

    @Test fun hashFileWrittenOwnerOnlyAndDeleted() {
        val rootfs = tmp.newFolder("rootfs").also { File(it, "etc").mkdirs() }
        val h = BindingCore.sha256Hex("tok")
        assertTrue(GenieHashFile.write(rootfs, h))
        val f = File(rootfs, GenieHashFile.REL)
        assertEquals(h + "\n", f.readText())
        // deskd compares f.read().strip().lower() with sha256(token).hexdigest().
        assertEquals(h, f.readText().trim().lowercase())
        if (posix(f)) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(f.toPath()))
            assertFalse(Files.getPosixFilePermissions(f.parentFile.toPath()).any {
                it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
        }
        assertFalse(File(f.parentFile, "genie.hash.tmp").exists())

        // Re-pairing replaces it.
        val h2 = BindingCore.sha256Hex("tok2")
        assertTrue(GenieHashFile.write(rootfs, h2))
        assertEquals(h2, f.readText().trim())

        // Forget deletes it.
        assertTrue(GenieHashFile.write(rootfs, null))
        assertFalse(f.exists())
        assertTrue("deleting an absent file is fine", GenieHashFile.write(rootfs, null))
    }

    @Test fun hashFileNotWrittenWithoutRootfs() {
        val rootfs = File(tmp.root, "absent")
        assertFalse(GenieHashFile.write(rootfs, BindingCore.sha256Hex("tok")))
        assertFalse(File(rootfs, GenieHashFile.REL).exists())
    }

    private fun posix(f: File) = runCatching { Files.getPosixFilePermissions(f.toPath()); true }.getOrDefault(false)

    // ---- a minimal in-memory SharedPreferences ---------------------------------------------------

    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String, d: MutableSet<String>?) = map[k] as MutableSet<String>? ?: d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = Ed()

        inner class Ed : SharedPreferences.Editor {
            private val put = HashMap<String, Any?>()
            private val rm = HashSet<String>()
            private var clear = false
            override fun putString(k: String, v: String?): SharedPreferences.Editor { put[k] = v; return this }
            override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor { put[k] = v; return this }
            override fun putInt(k: String, v: Int): SharedPreferences.Editor { put[k] = v; return this }
            override fun putLong(k: String, v: Long): SharedPreferences.Editor { put[k] = v; return this }
            override fun putFloat(k: String, v: Float): SharedPreferences.Editor { put[k] = v; return this }
            override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { put[k] = v; return this }
            override fun remove(k: String): SharedPreferences.Editor { rm.add(k); return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                if (clear) map.clear()
                rm.forEach { map.remove(it) }
                map.putAll(put)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
