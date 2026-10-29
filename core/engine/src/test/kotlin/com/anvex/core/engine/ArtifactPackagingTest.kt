package com.anvex.core.engine

import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class ArtifactPackagingTest {
    @Test fun `keystores and private keys are never included`() {
        for (name in listOf("release.p12", "key.pfx", "app.keystore", "debug.jks", "id.key", "cert.pem")) {
            assertFalse("must exclude $name", ArtifactHygienePolicy.include(name))
            assertFalse("must exclude nested $name", ArtifactHygienePolicy.include("app/$name"))
        }
        assertTrue(ArtifactHygienePolicy.include("app/MainActivity.kt"))
    }

    @Test fun zipIsDeterministicAndSorted() {
        val a = ArtifactPackager.zip("source", listOf(ArtifactFileSpec("a.txt", "A".toByteArray()), ArtifactFileSpec("b.txt", "B".toByteArray())))
        ZipInputStream(a.bytes.inputStream()).use { zip ->
            assertEquals("a.txt", zip.nextEntry.name)
            assertEquals("A", zip.readBytes().toString(Charsets.UTF_8))
            assertEquals("b.txt", zip.nextEntry.name)
        }
        assertEquals("source.zip", a.fileName)
    }

    @Test fun zipRejectsTraversal() {
        try {
            ArtifactPackager.zip("x.zip", listOf(ArtifactFileSpec("../secret", byteArrayOf(1))))
            fail("Expected traversal rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sourcePackageKeepsLegitimateBuildPackageUnderSrc() {
        assertTrue(ArtifactHygienePolicy.include("core/android/src/main/kotlin/com/example/build/BuildTool.kt"))
        assertFalse(ArtifactHygienePolicy.include("core/android/build/tmp/classes.jar"))
        assertFalse(ArtifactHygienePolicy.include("app/build/outputs/apk/debug/app.apk"))
    }

    @Test fun `internal attachment directory is excluded from source artifacts`() {
        assertFalse(ArtifactHygienePolicy.include(".anvex/attachments/file.bin"))
    }

    @Test fun duplicateArchivePathsAreRejected() {
        try {
            ArtifactPackager.zip("x.zip", listOf(ArtifactFileSpec("a.txt", byteArrayOf(1)), ArtifactFileSpec("a.txt", byteArrayOf(2))))
            fail("Expected duplicate path rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun documentExtensionsAreNormalized() {
        assertEquals("report.json", ArtifactPackager.text("report", "{}", ArtifactFormat.JSON).fileName)
        assertEquals("notes.md", ArtifactPackager.text("notes.md", "# x", ArtifactFormat.MD).fileName)
    }

    @Test fun streamingWriterPreservesCallerOrderWithoutMaterializingSequence() {
        val out = java.io.ByteArrayOutputStream()
        ArtifactPackager.zipTo("stream.zip", sequenceOf(
            ArtifactFileSpec("z.txt", byteArrayOf(1)),
            ArtifactFileSpec("a.txt", byteArrayOf(2))
        ), out)
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            assertEquals("z.txt", zip.nextEntry.name)
            assertEquals("a.txt", zip.nextEntry.name)
        }
    }

    @Test fun generatedTextPolicyRejectsLikelySecrets() {
        try {
            ArtifactHygienePolicy.requireSafeGeneratedText("secret.txt", "api_key=abcdefghijklmnopqrstuvwxyz")
            fail("Expected secret detection")
        } catch (_: IllegalArgumentException) { }
    }

    // M-04: known token shapes are findings even under a non-secret key
    // name (FOO=ghp_…) or next to a placeholder word (a value containing
    // "token" used to count as a placeholder and sail through).
    // Token literals are built by concatenation so tooling never mistakes
    // them for real credentials in this test file.
    @Test fun `known token under a non-secret key name is a finding`() {
        val token = "ghp_" + "abcdefghij1234567890"
        val found = ArtifactHygienePolicy.findSecrets("notes.txt", "FOO=$token")
        assertTrue("ghp_ token must be detected", found.isNotEmpty())
    }

    @Test fun `token-shaped value containing the word token is not a placeholder`() {
        val token = "ghp_" + "tokenvalue1234567890"
        val found = ArtifactHygienePolicy.findSecrets("config.txt", "token = \"$token\"")
        assertTrue("value containing 'token' must still be detected when token-shaped", found.isNotEmpty())
    }

    @Test fun `plain placeholder values are still placeholders`() {
        val found = ArtifactHygienePolicy.findSecrets("config.txt", "password = \"your-password-here\"")
        assertTrue("obvious placeholders must not block sync", found.isEmpty())
    }

    @Test fun `newly excluded secret filenames are not included`() {
        for (name in listOf(".env.development", ".npmrc", "service-account.json", ".dev.vars")) {
            assertFalse("must exclude $name", ArtifactHygienePolicy.include(name))
        }
    }
}


class BuildScriptRiskScannerTest {
    @Test fun `detects command execution in gradle script`() {
        val findings = BuildScriptRiskScanner.scan("build.gradle.kts", "tasks.register(\"x\") { doLast { exec { commandLine(\"sh\", \"-c\", \"echo x\") } } }")
        assertTrue(findings.isNotEmpty())
    }

    @Test fun `ignores normal kotlin source`() {
        assertTrue(BuildScriptRiskScanner.scan("src/Main.kt", "ProcessBuilder(\"x\")").isEmpty())
    }
}
