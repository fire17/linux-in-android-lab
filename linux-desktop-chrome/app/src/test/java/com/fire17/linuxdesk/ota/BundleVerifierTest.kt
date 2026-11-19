package com.fire17.linuxdesk.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class BundleVerifierTest {

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Raw 32-byte public key = last 32 bytes of the X.509 SPKI encoding. */
    private fun rawPubB64(kp: KeyPair): String {
        val enc = kp.public.encoded
        return Base64.getEncoder().encodeToString(enc.copyOfRange(enc.size - 32, enc.size))
    }

    private fun signedManifest(kp: KeyPair, version: Long, minApp: Long = 100): ByteArray {
        val m = JSONObject()
        m.put("version", version)
        m.put("min_app", minApp)
        m.put("files", JSONObject().put("deskd/deskd.py", "a".repeat(64)).put("setup-x.sh", "b".repeat(64)))
        val s = Signature.getInstance("Ed25519")
        s.initSign(kp.private)
        s.update(BundleVerifier.canonicalBytes(m))
        m.put("sig", Base64.getEncoder().encodeToString(s.sign()))
        return m.toString().toByteArray()
    }

    @Test
    fun goodSignaturePasses() {
        val kp = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 7), highestAccepted = 6, appVersionCode = 281, keys = listOf(rawPubB64(kp)))
        assertTrue("$r", r is BundleVerifier.Result.Ok)
        assertEquals(7L, (r as BundleVerifier.Result.Ok).manifest.version)
        assertEquals(0, r.keySlot)
    }

    @Test
    fun secondKeySlotPasses() {
        val kp = keyPair()
        val other = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 2), 1, 281, listOf(rawPubB64(other), rawPubB64(kp)))
        assertTrue("$r", r is BundleVerifier.Result.Ok)
        assertEquals(1, (r as BundleVerifier.Result.Ok).keySlot)
    }

    @Test
    fun wrongKeyFails() {
        val kp = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 7), 6, 281, listOf(rawPubB64(keyPair())))
        assertEquals(BundleVerifier.Result.Rejected("BAD_SIGNATURE"), r)
    }

    @Test
    fun tamperedManifestFails() {
        val kp = keyPair()
        val tampered = String(signedManifest(kp, 7)).replace("\"min_app\":100", "\"min_app\":1").toByteArray()
        val r = BundleVerifier.verify(tampered, 6, 281, listOf(rawPubB64(kp)))
        assertEquals(BundleVerifier.Result.Rejected("BAD_SIGNATURE"), r)
    }

    @Test
    fun downgradeFails() {
        val kp = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 5), highestAccepted = 6, appVersionCode = 281, keys = listOf(rawPubB64(kp)))
        assertTrue("$r", r is BundleVerifier.Result.Rejected && r.reason == "DOWNGRADE")
    }

    @Test
    fun sameVersionFails() {
        val kp = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 6), 6, 281, listOf(rawPubB64(kp)))
        assertTrue("$r", r is BundleVerifier.Result.Rejected && r.reason == "SAME_VERSION")
    }

    @Test
    fun minAppAboveAppFails() {
        val kp = keyPair()
        val r = BundleVerifier.verify(signedManifest(kp, 7, minApp = 500), 6, 281, listOf(rawPubB64(kp)))
        assertTrue("$r", r is BundleVerifier.Result.Rejected && r.reason == "APP_TOO_OLD")
    }

    @Test
    fun unsafePathsRejected() {
        assertFalse(BundleVerifier.isSafeRelPath("../etc/passwd"))
        assertFalse(BundleVerifier.isSafeRelPath("/abs"))
        assertFalse(BundleVerifier.isSafeRelPath("a//b"))
        assertFalse(BundleVerifier.isSafeRelPath("a\\b"))
        assertTrue(BundleVerifier.isSafeRelPath("deskd/deskd.py"))
    }

    @Test
    fun canonicalMatchesPythonForm() {
        // python3: json.dumps({"version":1,"min_app":2,"files":{"b":"x","a":"é"},"sig":"z"} minus sig,
        //   sort_keys=True, separators=(",",":"), ensure_ascii=True)
        val o = JSONObject("""{"version":1,"min_app":2,"files":{"b":"x","a":"é"},"sig":"z"}""")
        val bs = "\\"
        assertEquals("{\"files\":{\"a\":\"${bs}u00e9\",\"b\":\"x\"},\"min_app\":2,\"version\":1}", String(BundleVerifier.canonicalBytes(o)))
    }
}
