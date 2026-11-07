package com.anvex.core.android.harness

import com.anvex.core.android.db.PluginDao
import com.anvex.core.android.db.PluginEntity
import com.anvex.core.android.reliability.PluginPackageInstaller
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

class SkillGitInstallerTest {
    @Test fun parsesPlainGithubUrls() {
        assertEquals("owner" to "repo", SkillGitInstaller.parseOwnerRepo("https://github.com/owner/repo"))
        assertEquals("owner" to "repo", SkillGitInstaller.parseOwnerRepo("https://github.com/owner/repo.git"))
        assertEquals("owner" to "repo", SkillGitInstaller.parseOwnerRepo("https://github.com/owner/repo/tree/main"))
    }

    @Test fun rejectsOwnerOrRepoThatCouldReshapeTheDownloadUrl() {
        assertNull(SkillGitInstaller.parseOwnerRepo("https://github.com/owner/repo?x=1"))
        assertNull(SkillGitInstaller.parseOwnerRepo("https://github.com/owner/repo#frag"))
        assertNull(SkillGitInstaller.parseOwnerRepo("https://github.com/owner/..") )
        assertNull(SkillGitInstaller.parseOwnerRepo("https://github.com/o%2Fx/repo"))
    }

    // ---- TAR extraction (4.15.10: the reader previously misaligned on the
    // first pax global header of every real git-archive tarball) ----

    /** One ustar entry: 512-byte header + payload + block padding. */
    private fun tarEntry(name: String, type: Char, payload: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        (payload.size.toString(8).padStart(11, '0') + " ").toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        header[156] = type.code.toByte()
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(payload)
        out.write(ByteArray((512 - payload.size % 512) % 512))
        return out.toByteArray()
    }

    private fun tarGzOf(vararg chunks: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        chunks.forEach { raw.write(it) }
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    /** The git-archive shape: a 52-byte pax global header FIRST (odd size, so padding matters). */
    private fun gitArchiveHeader(): ByteArray =
        tarEntry("pax_global_header", 'g', "52 comment=96608e078b2d5ea607c5c3b8b5a54d1c1fba6846\n".toByteArray())

    /** A well-formed pax record: "<len> <key>=<value>\n" where len counts itself. */
    private fun paxRecord(key: String, value: String): ByteArray {
        val body = "$key=$value"
        var digits = 2
        while (true) {
            val total = digits + 1 + body.length + 1
            if (total.toString().length == digits) return "$total $body\n".toByteArray()
            digits = total.toString().length
        }
    }

    @Test fun extractsGitArchiveShapedTarball() {
        val target = java.nio.file.Files.createTempDirectory("anvex-tar").toFile()
        try {
            val tar = tarGzOf(
                gitArchiveHeader(),
                tarEntry("repo-HEAD/", '5', ByteArray(0)),
                tarEntry("repo-HEAD/README.md", '0', "hello".toByteArray()),
                tarEntry("repo-HEAD/src/Main.kt", '0', "fun main() {}".toByteArray()),
            )
            SkillGitInstaller.extractTarGz(tar, target)
            assertEquals("hello", File(target, "repo-HEAD/README.md").readText())
            assertEquals("fun main() {}", File(target, "repo-HEAD/src/Main.kt").readText())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test fun extractsTarballWithMoreThan256Entries() {
        val target = java.nio.file.Files.createTempDirectory("anvex-tar300").toFile()
        try {
            val chunks = mutableListOf(gitArchiveHeader())
            repeat(300) { i -> chunks += tarEntry("repo-HEAD/f$i.txt", '0', "x".toByteArray()) }
            SkillGitInstaller.extractTarGz(tarGzOf(*chunks.toTypedArray()), target)
            assertEquals(300, File(target, "repo-HEAD").listFiles()?.size)
        } finally {
            target.deleteRecursively()
        }
    }

    @Test fun honoursGnuLongName() {
        val target = java.nio.file.Files.createTempDirectory("anvex-tarlong").toFile()
        try {
            val longName = "repo-HEAD/" + "deep/".repeat(12) + "leaf.txt"
            val tar = tarGzOf(
                gitArchiveHeader(),
                tarEntry("././@LongLink", 'L', longName.toByteArray()),
                tarEntry("repo-HEAD/deep/deep/deep/deep/deep/de", '0', "leaf".toByteArray()),
            )
            SkillGitInstaller.extractTarGz(tar, target)
            assertEquals("leaf", File(target, longName).readText())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test fun honoursPaxPathOverride() {
        val target = java.nio.file.Files.createTempDirectory("anvex-tarpax").toFile()
        try {
            val tar = tarGzOf(
                gitArchiveHeader(),
                tarEntry("repo-HEAD/PaxHeaders/x", 'x', paxRecord("path", "repo-HEAD/renamed/actual.txt")),
                tarEntry("repo-HEAD/short.txt", '0', "data".toByteArray()),
            )
            SkillGitInstaller.extractTarGz(tar, target)
            assertEquals("data", File(target, "repo-HEAD/renamed/actual.txt").readText())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalInTar() {
        val target = java.nio.file.Files.createTempDirectory("anvex-tarslip").toFile()
        try {
            val tar = tarGzOf(
                gitArchiveHeader(),
                tarEntry("../evil.txt", '0', "evil".toByteArray()),
            )
            val error = assertThrows(IllegalStateException::class.java) {
                SkillGitInstaller.extractTarGz(tar, target)
            }
            assertTrue(error.message!!.contains("Blocked tarball path"))
        } finally {
            target.deleteRecursively()
        }
    }

    // ---- SKILL.md discovery and synthesis ----

    private fun skillMd(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: $description\n---\n$body"

    @Test fun discoversSkillsFromSkillsDirectory() {
        val root = java.nio.file.Files.createTempDirectory("anvex-skills").toFile()
        try {
            File(root, "skills/alpha").mkdirs()
            File(root, "skills/alpha/SKILL.md").writeText(skillMd("alpha", "A", "body a"))
            File(root, "skills/beta").mkdirs()
            File(root, "skills/beta/SKILL.md").writeText(skillMd("beta", "B", "body b"))
            File(root, ".openclaw/skills/gamma").mkdirs()
            File(root, ".openclaw/skills/gamma/SKILL.md").writeText(skillMd("gamma", "G", "body g"))
            val found = SkillGitInstaller.discoverSkills(root)
            assertEquals(listOf("alpha", "beta"), found.map { it.dir.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun parsesSkillFrontmatter() {
        val meta = SkillGitInstaller.parseSkillMeta("---\nname: \"Demo Skill\"\ndescription: 'Does things'\nversion: 2.1\n---\n\nBody line.")
        assertEquals("Demo Skill", meta.name)
        assertEquals("Does things", meta.description)
        assertEquals("2.1", meta.version)
        assertEquals("Body line.", meta.body)
        val bare = SkillGitInstaller.parseSkillMeta("Just a body.")
        assertNull(bare.name)
        assertEquals("Just a body.", bare.body)
    }

    @Test fun sanitizesSkillIds() {
        assertEquals("ponytail-audit", SkillGitInstaller.sanitizeSkillId("Ponytail Audit"))
        assertEquals("weird-name", SkillGitInstaller.sanitizeSkillId("  Weird Name! "))
        assertEquals("skill-", SkillGitInstaller.sanitizeSkillId(""))
    }

    private class FakePluginDao : PluginDao {
        val stored = mutableListOf<PluginEntity>()
        override fun observeAll(): Flow<List<PluginEntity>> = flowOf(stored)
        override suspend fun all(): List<PluginEntity> = stored
        override suspend fun get(id: String): PluginEntity? = stored.firstOrNull { it.id == id }
        override suspend fun upsert(plugin: PluginEntity) {
            stored.removeAll { it.id == plugin.id }
            stored += plugin
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun delete(id: String) {
            stored.removeAll { it.id == id }
        }
    }

    @Test fun synthesizedSkillInstallsEndToEnd() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("anvex-synth").toFile()
        try {
            val skillDir = File(root, "demo").apply { mkdirs() }
            File(skillDir, "SKILL.md").writeText(skillMd("Demo Skill", "A demo", "Do the demo thing."))
            File(skillDir, "reference.txt").writeText("reference")
            val dao = FakePluginDao()
            val installer = PluginPackageInstaller(File(root, "plugins").apply { mkdirs() }, dao)
            val zip = SkillGitInstaller.synthesizeSkillZip(
                SkillGitInstaller.SkillSource(skillDir, File(skillDir, "SKILL.md")),
                "https://github.com/owner/repo",
                File(root, "skill.zip"),
            )
            ZipFile(zip).use { z ->
                val names = z.entries().toList().map { it.name }
                assertTrue(names.containsAll(listOf("plugin.json", "SKILL.md", "reference.txt")))
            }
            val result = installer.installArchive(zip, expectedGitUrl = "https://github.com/owner/repo")
            assertEquals("demo-skill", result.id)
            assertEquals("Do the demo thing.", dao.stored.single().skillInstructions)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Farzad's exact failing scenario, offline: a ponytail-shaped collection repo. */
    @Test fun ponytailShapedRepoInstallsAllSkills() = runBlocking {
        val target = java.nio.file.Files.createTempDirectory("anvex-ponytail").toFile()
        try {
            val tar = tarGzOf(
                gitArchiveHeader(),
                tarEntry("ponytail-HEAD/", '5', ByteArray(0)),
                tarEntry("ponytail-HEAD/.claude-plugin/plugin.json", '0', """{"name":"ponytail","version":"1.0.0"}""".toByteArray()),
                tarEntry("ponytail-HEAD/skills/ponytail/SKILL.md", '0', skillMd("ponytail", "Lazy senior dev", "Be lazy.").toByteArray()),
                tarEntry("ponytail-HEAD/skills/ponytail-review/SKILL.md", '0', skillMd("ponytail-review", "Review", "Review it.").toByteArray()),
            )
            val staging = File(target, "staging").apply { mkdirs() }
            SkillGitInstaller.extractTarGz(tar, staging)
            val packageRoot = staging.listFiles()!!.single()
            // The harness-shaped manifests must NOT take the Anvex-package
            // path: with several of them present, no single candidate wins.
            File(packageRoot, ".codex-plugin").mkdirs()
            File(packageRoot, ".codex-plugin/plugin.json").writeText("""{"name":"ponytail"}""")
            assertNull(SkillGitInstaller.findPluginManifest(packageRoot))
            val skills = SkillGitInstaller.discoverSkills(packageRoot)
            assertEquals(2, skills.size)
            val dao = FakePluginDao()
            val installer = PluginPackageInstaller(File(target, "plugins").apply { mkdirs() }, dao)
            val ids = skills.map { skill ->
                val zip = SkillGitInstaller.synthesizeSkillZip(skill, "https://github.com/DietrichGebert/ponytail", File(target, "s-${skill.dir.name}.zip"))
                installer.installArchive(zip, expectedGitUrl = "https://github.com/DietrichGebert/ponytail").id
            }
            assertEquals(listOf("ponytail", "ponytail-review"), ids)
        } finally {
            target.deleteRecursively()
        }
    }
}
