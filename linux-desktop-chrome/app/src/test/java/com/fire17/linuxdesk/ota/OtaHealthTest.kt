package com.fire17.linuxdesk.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OtaHealthTest {
    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = kp.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }

    private fun applied(dir: File, appCode: Long = 281): BundleStore {
        val st = BundleStore(dir, appCode, "x", listOf(pub))
        val zb = ByteArrayOutputStream()
        ZipOutputStream(zb).use { z -> z.putNextEntry(ZipEntry("a.py")); z.write("v1".toByteArray()); z.closeEntry() }
        val m = JSONObject().put("version", 1).put("min_app", 1)
            .put("files", JSONObject().put("a.py", BundleVerifier.sha256Hex("v1".toByteArray())))
        val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(BundleVerifier.canonicalBytes(m))
        m.put("sig", Base64.getEncoder().encodeToString(s.sign()))
        st.stage(m.toString().toByteArray(), zb.toByteArray().inputStream())
        st.activateStaged()
        return st
    }

    private fun run(st: BundleStore, up: Boolean, failed: Boolean, deskd: Boolean, stopping: Boolean = false) =
        OtaHealth.start(st, { up }, { failed }, { deskd }, { stopping }, timeoutMs = 300, deskdGraceMs = 300, async = false)

    @Test
    fun windowUpAndDeskdUpKeepsTrial() {
        val st = applied(Files.createTempDirectory("ota").toFile())
        run(st, up = true, failed = false, deskd = true)
        assertFalse(st.state().getBoolean("trial"))
        assertEquals(1L, st.activeVersion())
    }

    @Test
    fun browserFailedRollsBack() {
        val st = applied(Files.createTempDirectory("ota").toFile())
        run(st, up = false, failed = true, deskd = true)
        assertEquals(0L, st.activeVersion())
        assertEquals("rolled_back", st.ledgerRows().first().getString("event"))
    }

    @Test
    fun deskdNeverUpRollsBack() {
        val st = applied(Files.createTempDirectory("ota").toFile())
        run(st, up = true, failed = false, deskd = false)
        assertEquals(0L, st.activeVersion())
    }

    @Test
    fun userStopKeepsTrialForNextSession() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = applied(dir)
        run(st, up = false, failed = false, deskd = false, stopping = true)
        assertTrue(st.state().getBoolean("trial"))
        assertFalse(st.state().getBoolean("trial_started"))
        run(BundleStore(dir, 281, "x", listOf(pub)), up = true, failed = false, deskd = true)
        assertEquals(1L, st.activeVersion())
        assertFalse(st.state().getBoolean("trial"))
    }

    @Test
    fun apkUpgradeClearsActiveBundle0Wins() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = applied(dir, appCode = 281)
        run(st, up = true, failed = false, deskd = true)          // healthy under APK 281
        val upgraded = BundleStore(dir, 290, "y", listOf(pub))
        upgraded.beginSession()                                   // first run after the APK upgrade
        assertEquals(0L, upgraded.activeVersion())
        assertFalse(upgraded.active.exists())
        assertEquals(1L, upgraded.highestAccepted())               // anti-downgrade floor kept
        assertEquals("apk_installed", upgraded.ledgerRows().first().getString("event"))
        val dest = File(dir, "opt").apply { mkdirs() }
        assertEquals(0, upgraded.overlayActive(dest))
    }

    @Test
    fun sameApkDoesNotClear() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = applied(dir, appCode = 281)
        run(st, up = true, failed = false, deskd = true)
        BundleStore(dir, 281, "x", listOf(pub)).beginSession()
        assertEquals(1L, st.activeVersion())
    }
}
