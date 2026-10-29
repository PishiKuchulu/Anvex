package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-02: the shell reassembles quoted/escaped fragments before resolving
 * a path, so `cat .e''nv`, `cat .e"n"v` and `cat .e\nv` all read `.env`.
 * The old check only trimmed quotes at the token edges and missed all
 * three. H-03: the translated `cat` path had no sensitivity check at all.
 */
class SensitivePathsBypassTest {

    @Test
    fun `quoted and escaped secret names are still detected`() {
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e''nv"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e\"n\"v"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e\\nv"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat '.env'"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .env"))
        assertTrue(SensitivePaths.commandReferencesSensitiveOutsideExtracted("cat .e''nv"))
    }

    @Test
    fun `ordinary names are not flagged`() {
        assertFalse(SensitivePaths.commandReferencesSensitive("cat README.md"))
        assertFalse(SensitivePaths.commandReferencesSensitive("cat token.ts"))
        assertFalse(SensitivePaths.commandReferencesSensitive("ls src"))
    }

    @Test
    fun `extracted attachments keep their carve-out`() {
        assertFalse(
            SensitivePaths.commandReferencesSensitiveOutsideExtracted(
                "cat .anvex/attachments/abc-extracted/notes.txt"
            )
        )
        // But a secret inside an extracted tree is still a secret.
        assertTrue(
            SensitivePaths.commandReferencesSensitiveOutsideExtracted(
                "cat .anvex/attachments/abc-extracted/.env"
            )
        )
    }
}
