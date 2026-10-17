package com.anvex.core.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PortableBackupBytesTest {
    @Test fun encryptedBytesRoundTrip() {
        val password = "strong-test-password".toCharArray()
        val source = "Anvex project bytes".toByteArray()
        val encoded = PortableBackup.encryptBytes(source, password)
        assertArrayEquals(source, PortableBackup.decryptBytes(encoded, password))
        assertNotEquals(encoded, PortableBackup.encryptBytes(source, password))
    }

    @Test(expected = Exception::class)
    fun wrongPasswordRejected() {
        val encoded = PortableBackup.encryptBytes("secret".toByteArray(), "correct-password".toCharArray())
        PortableBackup.decryptBytes(encoded, "wrong-password".toCharArray())
    }
}
