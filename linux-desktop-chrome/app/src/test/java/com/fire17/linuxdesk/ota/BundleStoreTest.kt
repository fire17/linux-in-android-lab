package com.fire17.linuxdesk.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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

class BundleStoreTest {
    private val kp: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub: String = kp.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }

    private fun bundle(version: Long, files: Map<String, String>, tamper: String? = null): Pair<ByteArray, ByteArray> {
        val zb = ByteArrayOutputStream()
        ZipOutputStream(zb).use { z ->
            for ((p, c) in files) {
                z.putNextEntry(ZipEntry(p)); z.write((if (p == tamper) "$c!" else c).toByteArray()); z.closeEntry()
            }
        }
        val fj = JSONObject()
        for ((p, c) in files) fj.put(p, BundleVerifier.sha256Hex(c.toByteArray()))
        val m = JSONObject().put("version", version).put("min_app", 1).put("files", fj)
        val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(BundleVerifier.canonicalBytes(m))
        m.put("sig", Base64.getEncoder().encodeToString(s.sign()))
        return m.toString().toByteArray() to zb.toByteArray()
    }

    private fun store(dir: File) = BundleStore(dir, appVersionCode = 281, appVersionName = "0.3.0", keys = listOf(pub))

    @Test
    fun stageActivateOverlayHealthy() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = store(dir)
        val (m, z) = bundle(1, mapOf("deskd/deskd.py" to "print(1)", "start-browser.sh" to "#!/bin/sh"))
        st.stage(m, z.inputStream())
        assertEquals(1L, st.activateStaged())
        st.beginSession()
        val dest = File(dir, "opt").apply { mkdirs() }
        assertEquals(2, st.overlayActive(dest))
        assertEquals("print(1)", File(dest, "deskd/deskd.py").readText())
        assertTrue(File(dest, "start-browser.sh").canExecute())
        assertTrue(st.reportHealth(pingOk = true, deskdUp = true))
        assertFalse(st.state().getBoolean("trial"))
        val events = st.ledgerRows().map { it.getString("event") }.reversed()
        assertEquals(listOf("verified", "applied", "health_ok"), events)
        assertTrue(st.ledgerRows().all { it.getString("schema") == "ld-ota/1" })
    }

    @Test
    fun healthFailRollsBackToPrev() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = store(dir)
        bundle(1, mapOf("a.py" to "v1")).let { (m, z) -> st.stage(m, z.inputStream()) }
        st.activateStaged(); st.beginSession(); st.reportHealth(true, true)
        bundle(2, mapOf("a.py" to "v2")).let { (m, z) -> st.stage(m, z.inputStream()) }
        st.activateStaged(); st.beginSession()
        assertFalse(st.reportHealth(pingOk = true, deskdUp = false))
        assertEquals(1L, st.activeVersion())
        val dest = File(dir, "opt").apply { mkdirs() }
        st.overlayActive(dest)
        assertEquals("v1", File(dest, "a.py").readText())
        assertEquals(2L, st.highestAccepted()) // anti-downgrade floor is never lowered
    }

    @Test
    fun appDiedDuringTrialRollsBackNextSession() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = store(dir)
        bundle(1, mapOf("a.py" to "v1")).let { (m, z) -> st.stage(m, z.inputStream()) }
        st.activateStaged()
        st.beginSession()          // trial session starts, then the app dies (no reportHealth)
        store(dir).beginSession()  // next process
        assertEquals(0L, store(dir).activeVersion()) // back to bundle 0 (APK assets)
        assertEquals("rolled_back", store(dir).ledgerRows().first().getString("event"))
    }

    @Test
    fun shaMismatchRejected() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = store(dir)
        val (m, z) = bundle(1, mapOf("a.py" to "v1"), tamper = "a.py")
        try { st.stage(m, z.inputStream()); fail("staged a tampered file") } catch (e: BundleStore.OtaException) {
            assertEquals("SHA_MISMATCH", e.reason)
        }
        assertFalse(File(st.staged, BundleStore.MANIFEST).exists())
    }

    @Test
    fun tamperedActiveIsNotOverlaid() {
        val dir = Files.createTempDirectory("ota").toFile()
        val st = store(dir)
        bundle(1, mapOf("a.py" to "v1")).let { (m, z) -> st.stage(m, z.inputStream()) }
        st.activateStaged()
        File(st.active, "a.py").writeText("evil")
        val dest = File(dir, "opt").apply { mkdirs() }
        assertEquals(0, st.overlayActive(dest))
        assertFalse(File(dest, "a.py").exists())
    }

    /** Interop: a bundle built + signed by notes/ota-tools/make-bundle.py. Set OTA_PY_BUNDLE=<dir> and OTA_PY_PUB=<b64>. */
    @Test
    fun pythonBuiltBundleVerifies() {
        val d = System.getenv("OTA_PY_BUNDLE"); val k = System.getenv("OTA_PY_PUB")
        assumeTrue(d != null && k != null)
        val dir = Files.createTempDirectory("ota").toFile()
        val st = BundleStore(dir, 1000, "x", listOf(k!!))
        val m = st.stage(File(d, "manifest.json").readBytes(), File(d, "bundle.zip").inputStream())
        assertTrue(m.files.isNotEmpty())
    }
}
