package com.fire17.linuxdesk.ota

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pure-JVM Ed25519 signature VERIFICATION, the fallback for Android API 26–32 where the platform has no "Ed25519"
 * provider (notes/ota-plan.md §3.1). It is a line-by-line port of the reference algorithm in RFC 8032 §6 (the
 * Python `verify`), using java.math.BigInteger and SHA-512. No dependencies, about 100 lines.
 *
 * Provenance, honestly: this is the RFC's own reference algorithm, not an independently audited library. It is
 * checked against the RFC 8032 §7.1 test vectors and against the platform verifier (BundleVerifierFallbackTest).
 * It is not constant-time, which is fine here: verification only touches public data (key, message, signature).
 * Alternative if a vetted library is preferred: Tink's `com.google.crypto.tink.subtle.Ed25519Verify` (tink 1.7.0 jar
 * is 1.8 MB before R8, a new dependency).
 */
object Ed25519Pure {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val G: Array<BigInteger> = run {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0)!!
        arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun inv(x: BigInteger) = x.modPow(P.subtract(BigInteger.TWO), P)

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    /** Extended coordinates (X, Y, Z, T), RFC 8032 §6 point_add. */
    private fun add(p: Array<BigInteger>, q: Array<BigInteger>): Array<BigInteger> {
        val a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P)
        val b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P)
        val c = BigInteger.TWO.multiply(p[3]).multiply(q[3]).multiply(D).mod(P)
        val d = BigInteger.TWO.multiply(p[2]).multiply(q[2]).mod(P)
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return arrayOf(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s0: BigInteger, p0: Array<BigInteger>): Array<BigInteger> {
        var s = s0; var p = p0
        var q = arrayOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Array<BigInteger>, q: Array<BigInteger>): Boolean =
        p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0 &&
            p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun decompress(b: ByteArray): Array<BigInteger>? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() shr 7) and 1
        val yb = b.copyOf(); yb[31] = (yb[31].toInt() and 0x7f).toByte()
        val y = le(yb)
        val x = recoverX(y, sign) ?: return null
        return arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** RFC 8032 §5.1.7 verify (cofactorless, as the RFC reference and the JDK). */
    fun verify(publicKey: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (publicKey.size != 32 || sig.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = sig.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = le(sig.copyOfRange(32, 64))
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(rs); md.update(publicKey); md.update(msg)
        val h = le(md.digest()).mod(L)
        return equal(mul(s, G), add(r, mul(h, a)))
    }
}
