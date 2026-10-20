package com.anvex.core.android.signing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known-answer tests pinning the sealed-box primitives to libsodium.
 *
 * Both vectors were produced with libsodium itself (PyNaCl bindings):
 * the nonce vector via crypto_generichash (Blake2b, outlen 24) and the
 * HSalsa20 vector via crypto_box_beforenm for the fixed key pair below.
 * They guard the two historical HTTP-422 bugs: a zero nonce instead of
 * the Blake2b-derived one, and a non-canonical HSalsa20 state layout /
 * quarter-round order.
 */
class SigningSealedBoxTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun nonceMatchesLibsodiumGenerichashVector() {
        // epk = 07a37cbc... (X25519 base of esk 01..20), pk = 4c29f499...
        val epk = unhex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c")
        val pk = unhex("4c29f4996985e80d247e80b70303824592ff9fbad74904ea7c3d2bda24e51c12")
        assertEquals(
            "e66c495c78d0ca03ebe4d32ebe98a346bd6b77a89e500d3b",
            hex(SigningKeyManager.sealedBoxNonce(epk, pk)),
        )
    }

    @Test
    fun hsalsa20MatchesLibsodiumBeforenmVector() {
        // shared = X25519(esk = 01..20, pk above), from crypto_scalarmult.
        val shared = unhex("f53faed0127c4765a2588ec2c3df0218f36a375af662173be4636661b3a58d7b")
        assertEquals(
            "0c2d2d2eba5b346bf7105b2aa08c403991b3d02263b1e3d3ff19589891c0aa1f",
            hex(SigningKeyManager.hsalsa20(shared, ByteArray(16))),
        )
    }
}
