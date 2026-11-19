package com.fire17.linuxdesk.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class BundleVerifierFallbackTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 8032 §7.1 TEST 1 (empty message), TEST 2 (one byte), TEST 3 (two bytes). */
    @Test
    fun rfc8032Vectors() {
        val v = listOf(
            Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            Triple("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
        )
        for ((pk, msg, sig) in v) {
            assertTrue(pk, Ed25519Pure.verify(hex(pk), hex(msg), hex(sig)))
            val bad = hex(sig).also { it[5] = (it[5].toInt() xor 1).toByte() }
            assertFalse(pk, Ed25519Pure.verify(hex(pk), hex(msg), bad))
            assertFalse(pk, Ed25519Pure.verify(hex(pk), hex(msg + "00"), hex(sig)))
        }
    }

    /** The fallback and the platform agree on the same signed manifest bytes: good, wrong key, tampered. */
    @Test
    fun fallbackAgreesWithPlatform() {
        repeat(20) { i ->
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val pub = kp.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }
            val otherPub = other.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }
            val m = JSONObject().put("version", i + 1).put("min_app", 1).put("files", JSONObject().put("a.py", "a".repeat(64)))
            val bytes = BundleVerifier.canonicalBytes(m)
            val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(bytes)
            val sig = s.sign()
            val tampered = bytes.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() }
            for ((k, data) in listOf(pub to bytes, otherPub to bytes, pub to tampered)) {
                assertEquals(BundleVerifier.verifyEd25519(k, data, sig), BundleVerifier.verifyEd25519Pure(k, data, sig))
            }
            assertTrue(BundleVerifier.verifyEd25519Pure(pub, bytes, sig))
        }
    }

    /** Full verify() through the fallback only (platform path disabled): same Result as the platform. */
    @Test
    fun verifyWithFallbackOnlyGivesSameResult() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = kp.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }
        val m = JSONObject().put("version", 3).put("min_app", 1).put("files", JSONObject().put("a.py", "b".repeat(64)))
        val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(BundleVerifier.canonicalBytes(m))
        m.put("sig", Base64.getEncoder().encodeToString(s.sign()))
        val platform = BundleVerifier.verify(m.toString().toByteArray(), 2, 281, listOf(pub))
        // Pure path on every slot, exactly as the NoSuchAlgorithmException branch would do on API < 33.
        val bytes = BundleVerifier.canonicalBytes(m)
        val sig = Base64.getDecoder().decode(m.getString("sig"))
        assertTrue(platform is BundleVerifier.Result.Ok)
        assertTrue(BundleVerifier.verifyEd25519Pure(pub, bytes, sig))
    }

    /** Interop: the bundle signed by notes/ota-tools/make-bundle.py verifies on both paths. OTA_PY_BUNDLE + OTA_PY_PUB. */
    @Test
    fun pythonBundleAgrees() {
        val d = System.getenv("OTA_PY_BUNDLE"); val k = System.getenv("OTA_PY_PUB")
        assumeTrue(d != null && k != null)
        val m = JSONObject(File(d, "manifest.json").readText())
        val bytes = BundleVerifier.canonicalBytes(m)
        val sig = Base64.getDecoder().decode(m.getString("sig"))
        assertEquals(true, BundleVerifier.verifyEd25519(k!!, bytes, sig))
        assertTrue(BundleVerifier.verifyEd25519Pure(k, bytes, sig))
    }
}
