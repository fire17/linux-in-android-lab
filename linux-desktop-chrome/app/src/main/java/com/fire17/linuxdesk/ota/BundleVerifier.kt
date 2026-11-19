package com.fire17.linuxdesk.ota

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * OTA v1 — verifies a signed guest-bundle manifest (notes/ota-plan.md §2a, §3.1–3.2). Pure JVM, no Android imports.
 *
 * Manifest JSON:
 * ```
 * {"version": 7, "min_app": 281, "files": {"deskd/deskd.py": "<sha256 hex>", ...}, "sig": "<base64 Ed25519 sig>"}
 * ```
 * Signed bytes = the canonical form of the manifest WITHOUT `sig`: keys sorted (by UTF-16 code unit, same as Python's
 * `sort_keys`), no whitespace, ASCII-only escaping. It is byte-identical to Python's
 * `json.dumps(m, sort_keys=True, separators=(",", ":"), ensure_ascii=True)` for the value types we allow
 * (objects, arrays, strings, integers, booleans, null). Floats are refused so the two encoders can't disagree.
 * notes/ota-tools/make-bundle.py produces exactly this.
 *
 * Ed25519: uses the platform `Signature.getInstance("Ed25519")` (JDK 15+; Android API 33+ via Conscrypt — Tami's phone
 * is API 35). On API 26–32 the platform provider is UNVERIFIED/likely absent: [verify] then returns
 * [Result.Rejected] with reason `NO_ED25519_PROVIDER` (fail closed — never accept). Fallback plan for old APIs: bundle a
 * BouncyCastle-free pure-Java Ed25519 verifier (e.g. Tink's `Ed25519Verify`, Apache-2.0) — not done in v1.
 */
object BundleVerifier {

    /** Raw 32-byte Ed25519 public keys, base64. Slot 0 = current, slot 1 = next (rotation without an APK). */
    val PUBLIC_KEYS: List<String> = listOf(
        "bL/nCCcRDMwfRQBh5rx09sQtCCEu0KTMWxJrJhYUbv8=", // slot 0 — Mac key ~/.config/linuxdesk-signing (2026-11-19), from make-bundle.py --print-pubkey
        // slot 1 — next key, empty until a rotation is prepared
    )

    const val MAX_MANIFEST_BYTES = 16 * 1024

    data class Manifest(
        val version: Long,
        val minApp: Long,
        val files: Map<String, String>,
        val raw: JSONObject,
    )

    sealed class Result {
        data class Ok(val manifest: Manifest, val keySlot: Int) : Result()
        data class Rejected(val reason: String, val detail: String = "") : Result()
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    // SubjectPublicKeyInfo DER prefix for Ed25519 (OID 1.3.101.112) + 32-byte key.
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /**
     * @param manifestBytes the manifest.json as downloaded
     * @param highestAccepted highest bundle version this phone ever accepted (0 = none; APK assets are bundle 0)
     * @param appVersionCode BuildConfig.VERSION_CODE of the running APK
     * @param keys public key slots (defaults to [PUBLIC_KEYS]); blank entries are skipped
     */
    fun verify(
        manifestBytes: ByteArray,
        highestAccepted: Long,
        appVersionCode: Long,
        keys: List<String> = PUBLIC_KEYS,
    ): Result {
        if (manifestBytes.size > MAX_MANIFEST_BYTES) return Result.Rejected("TOO_LARGE", "${manifestBytes.size} bytes")
        val obj = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return Result.Rejected("BAD_JSON", e.message ?: "")
        }
        val m = try {
            parse(obj)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected("BAD_MANIFEST", e.message ?: "")
        }
        val sigB64 = obj.optString("sig", "")
        val sig = try {
            Base64.getDecoder().decode(sigB64)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected("BAD_SIG_ENCODING")
        }
        if (sig.size != 64) return Result.Rejected("BAD_SIG_ENCODING", "sig must be 64 bytes")

        val signed = try {
            canonicalBytes(obj)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected("BAD_MANIFEST", e.message ?: "")
        }

        var slotOk = -1
        for ((i, k) in keys.withIndex()) {
            if (k.isBlank()) continue
            when (verifyEd25519(k, signed, sig)) {
                true -> { slotOk = i; break }
                false -> {}
                null -> return Result.Rejected("NO_ED25519_PROVIDER", "platform lacks Ed25519 (API < 33?)")
            }
        }
        if (slotOk < 0) return Result.Rejected("BAD_SIGNATURE")
        // Policy checks only AFTER the signature: never act on unsigned claims.
        if (m.version < highestAccepted) return Result.Rejected("DOWNGRADE", "${m.version} < $highestAccepted")
        if (m.version == highestAccepted) return Result.Rejected("SAME_VERSION", "${m.version}")
        if (m.minApp > appVersionCode) return Result.Rejected("APP_TOO_OLD", "min_app ${m.minApp} > $appVersionCode")
        return Result.Ok(m, slotOk)
    }

    fun parse(obj: JSONObject): Manifest {
        val version = intField(obj, "version")
        require(version >= 1) { "version must be >= 1" }
        val minApp = intField(obj, "min_app")
        val filesObj = obj.optJSONObject("files") ?: throw IllegalArgumentException("files missing")
        val files = LinkedHashMap<String, String>()
        for (p in filesObj.keys()) {
            require(isSafeRelPath(p)) { "unsafe path: $p" }
            val h = filesObj.get(p)
            require(h is String && SHA256_HEX.matches(h)) { "bad sha256 for $p" }
            files[p] = h
        }
        require(files.isNotEmpty()) { "files empty" }
        return Manifest(version, minApp, files, obj)
    }

    private fun intField(obj: JSONObject, k: String): Long {
        val v = obj.opt(k) ?: throw IllegalArgumentException("$k missing")
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            else -> throw IllegalArgumentException("$k must be an integer")
        }
    }

    /** Relative, forward-slash, no `..`, no empty/`.` segments, no absolute, no backslash, no NUL. */
    fun isSafeRelPath(p: String): Boolean {
        if (p.isEmpty() || p.length > 512) return false
        if (p.startsWith("/") || p.contains('\\') || p.contains('\u0000')) return false
        return p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Returns true/false, or null if the platform has no Ed25519 provider. */
    fun verifyEd25519(pubKeyB64: String, data: ByteArray, sig: ByteArray): Boolean? {
        val raw = try {
            Base64.getDecoder().decode(pubKeyB64.trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (raw.size != 32) return false
        return try {
            val kf = KeyFactory.getInstance("Ed25519")
            val pub = kf.generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
            val s = Signature.getInstance("Ed25519")
            s.initVerify(pub)
            s.update(data)
            s.verify(sig)
        } catch (e: java.security.NoSuchAlgorithmException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    /** Canonical bytes of [obj] minus `sig`. */
    fun canonicalBytes(obj: JSONObject): ByteArray {
        val sb = StringBuilder()
        writeObject(obj, sb, skipSig = true)
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun writeValue(v: Any?, sb: StringBuilder) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> writeObject(v, sb, skipSig = false)
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) {
                    if (i > 0) sb.append(',')
                    writeValue(v.get(i), sb)
                }
                sb.append(']')
            }
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is java.math.BigInteger -> sb.append(v.toString())
            else -> throw IllegalArgumentException("unsupported JSON value type ${v::class.java.simpleName} (no floats)")
        }
    }

    private fun writeObject(o: JSONObject, sb: StringBuilder, skipSig: Boolean) {
        val keys = o.keys().asSequence().filter { !(skipSig && it == "sig") }.sorted().toList()
        sb.append('{')
        keys.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            writeString(k, sb)
            sb.append(':')
            writeValue(o.get(k), sb)
        }
        sb.append('}')
    }

    /** Matches Python json ensure_ascii=True escaping exactly (lowercase \\uXXXX). */
    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20 || c.code > 0x7e) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }
}
