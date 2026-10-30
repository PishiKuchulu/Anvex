package com.anvex.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the issues found in the full code review (H-02, H-03, H-07, M-05,
 * M-06, M-17). Each test pins one concrete bypass so it cannot come back.
 */
class SecurityFixesRegressionTest {

    // ---- H-02: lexical filter bypasses ------------------------------------------------

    @Test
    fun `quote splitting cannot hide a secret file name`() {
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e''nv"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e\"n\"v"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .en\\v"))
        assertTrue(SensitivePaths.commandReferencesSensitive("head -n 5 id_r''sa"))
    }

    @Test
    fun `globs that could expand to a secret are flagged`() {
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .e?v"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .env*"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat *.pem"))
        assertTrue(SensitivePaths.commandReferencesSensitive("cat .[e]nv"))
    }

    @Test
    fun `ordinary commands are not flagged`() {
        assertFalse(SensitivePaths.commandReferencesSensitive("ls src"))
        assertFalse(SensitivePaths.commandReferencesSensitive("cat README.md"))
        assertFalse(SensitivePaths.commandReferencesSensitive("grep -n token src/token.ts"))
        // a leading wildcard never matches a dotfile in a shell
        assertFalse(SensitivePaths.commandReferencesSensitive("cat *.md"))
    }

    @Test
    fun `more credential locations are secret files`() {
        assertTrue(SensitivePaths.isSecretFile(".dev.vars"))
        assertTrue(SensitivePaths.isSecretFile(".netrc"))
        assertTrue(SensitivePaths.isSecretFile("home/.ssh/config"))
        assertTrue(SensitivePaths.isSecretFile("infra/terraform.tfstate"))
        assertTrue(SensitivePaths.isSecretFile("keys/service-account-prod.json"))
        assertFalse(SensitivePaths.isSecretFile("src/token.ts"))
    }

    // ---- H-07: workflows -----------------------------------------------------------------

    @Test
    fun `workflow paths are recognised`() {
        assertTrue(SensitivePaths.isWorkflowPath(".github/workflows/build.yml"))
        assertFalse(SensitivePaths.isWorkflowPath(".github/dependabot.yml"))
        assertFalse(SensitivePaths.isWorkflowPath("docs/workflows/readme.md"))
    }

    // ---- M-17: secrets never enter model context ---------------------------------------------

    @Test
    fun `open secret file is not pasted into the prompt`() {
        val fs = FakeFs()
        fs.writeFile("package.json", "{}")
        fs.writeFile(".env", "OPENAI_API_KEY=sk-live-should-never-leave-the-device")
        val context = ContextBuilder.build(fs, openFilePath = ".env")
        assertFalse(context.contains("sk-live-should-never-leave-the-device"))
        // Merged behavior: the open secret file gets a "contents withheld"
        // note (its name is not secret; its content never appears). The
        // stricter no-header shape from the external pass was not kept.
    }

    // ---- M-06: share exports leave secrets out ------------------------------------------------

    @Test
    fun `export with excludeSecrets drops env and app state`() {
        val fs = FakeFs()
        fs.writeFile("index.html", "<html></html>")
        fs.writeFile(".env", "SECRET=1")
        fs.writeFile(".anvex/attachments/x.txt", "private")
        val bytes = java.io.ByteArrayOutputStream().also { ZipCodec.export(fs, it, excludeSecrets = true) }.toByteArray()
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.map { it.name }.toList()
        }
        assertTrue(names.contains("index.html"))
        assertFalse(names.contains(".env"))
        assertFalse(names.any { it.startsWith(".anvex/") })
    }

    @Test
    fun `backup export keeps the users own files`() {
        val fs = FakeFs()
        fs.writeFile(".env", "SECRET=1")
        // Backup mode is explicit in the merged tree (the export default is
        // the safe share mode, excludeSecrets=true).
        val bytes = java.io.ByteArrayOutputStream().also { ZipCodec.export(fs, it, excludeSecrets = false) }.toByteArray()
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.map { it.name }.toList()
        }
        assertTrue(names.contains(".env"))
    }

    @Test
    fun `leftover atomic-write temp files are not exported`() {
        assertFalse(ZipCodec.isExportable(".index.html.tmp-3f2a9c1e-0b1d-4d7e-9c1a-1234567890ab"))
        assertTrue(ZipCodec.isExportable("index.html"))
    }

    // ---- M-04: sync/package secret hygiene ---------------------------------------------------

    @Test
    fun `known token formats are detected whatever the variable is called`() {
        val text = "const OPENAI = \"sk-proj-abc123def456ghi789jkl012\"\nconst GH = \"ghp_" + "a".repeat(36) + "\"\n"
        val findings = ArtifactHygienePolicy.findSecrets("src/app.js", text)
        assertTrue(findings.any { it.line == 1 })
        assertTrue(findings.any { it.line == 2 })
        assertTrue(ArtifactHygienePolicy.findSecrets("src/app.html", "<div class=\"sk-chase-dot\"></div>").isEmpty())
    }

    @Test
    fun `env templates are synced but real env variants are not`() {
        assertTrue(ArtifactHygienePolicy.include(".env.example"))
        assertFalse(ArtifactHygienePolicy.include(".env.development"))
        assertFalse(ArtifactHygienePolicy.include(".netrc"))
        assertTrue(ArtifactHygienePolicy.include(".github/workflows/build.yml"))
    }
}
