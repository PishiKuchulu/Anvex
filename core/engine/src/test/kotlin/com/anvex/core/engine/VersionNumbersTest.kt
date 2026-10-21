package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.7: the in-app updater's version logic — tags vs installed versionName. */
class VersionNumbersTest {

    @Test
    fun `parses release tags and plain versions`() {
        assertEquals(listOf(4, 6, 1), VersionNumbers.parse("v4.6.1"))
        assertEquals(listOf(4, 6, 1), VersionNumbers.parse("4.6.1"))
        assertEquals(listOf(5, 0, 0), VersionNumbers.parse("v5.0.0"))
        assertEquals(listOf(4, 7, 0), VersionNumbers.parse("v4.7.0-beta1"))
    }

    @Test
    fun `rejects non versions`() {
        assertNull(VersionNumbers.parse("latest"))
        assertNull(VersionNumbers.parse("v4.6"))
        assertNull(VersionNumbers.parse(""))
        assertNull(VersionNumbers.parse("release-4.6.1"))
    }

    @Test
    fun `newer detection across segments`() {
        assertTrue(VersionNumbers.isNewer("v4.7.0", "4.6.1"))
        assertTrue(VersionNumbers.isNewer("4.6.2", "4.6.1"))
        assertTrue(VersionNumbers.isNewer("5.0.0", "4.9.9"))
        assertFalse(VersionNumbers.isNewer("4.6.1", "4.6.1"))
        assertFalse(VersionNumbers.isNewer("4.6.0", "4.6.1"))
        assertFalse(VersionNumbers.isNewer("latest", "4.6.1"))
    }
}
