package com.anvex.core.engine.vcs

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * GitLite correctness + real-git-CLI compatibility (GOAL 2).
 *
 * Environments providing a `git` binary get the stronger compatibility assertions
 * (git reads the repository GitLite wrote); environments without git still run the
 * pure-object-model assertions.
 */
class GitLiteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun git(vararg args: String, cwd: File): String {
        val p = ProcessBuilder("git", *args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    @Test
    fun `init creates a valid empty repository`() {
        val root = tmp.newFolder()
        GitLite.init(root)
        assertTrue(File(root, ".git/HEAD").isFile)
        assertEquals("ref: refs/heads/main\n", File(root, ".git/HEAD").readText())
        assertTrue(File(root, ".git/objects").isDirectory)
        val status = GitLite.open(root)!!.status()
        assertEquals("main", status.branch)
        assertNull(status.headCommit)
        assertTrue(status.isClean)
        if (gitAvailable()) {
            assertEquals("", git("status", "--porcelain", cwd = root).trim())
        }
    }

    @Test
    fun `add and commit produce a readable history`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "index.html").writeText("<h1>hello</h1>\n")
        File(root, "src").mkdirs()
        File(root, "src/app.js").writeText("console.log('hi');\n")

        assertEquals(2, repo.add(listOf("index.html", "src/app.js")))
        val commit = repo.commit("initial commit")
        assertNotNull(commit)
        val firstCommit = commit!!
        assertFalse(firstCommit.parents.isNotEmpty())

        val status = repo.status()
        assertTrue(status.isClean)
        assertEquals(firstCommit.sha, status.headCommit)

        val log = repo.log()
        assertEquals(1, log.size)
        assertEquals("initial commit", log.first().message)

        if (gitAvailable()) {
            assertEquals("initial commit", git("log", "-1", "--pretty=%s", cwd = root).trim())
            val porcelain = git("status", "--porcelain", cwd = root).trim()
            assertEquals("git status must be clean after a GitLite commit", "", porcelain)
        }
    }

    @Test
    fun `modify and recommit records parent chain`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "a.txt").writeText("one\n")
        repo.add(listOf("a.txt"))
        val first = repo.commit("first")
        File(root, "a.txt").writeText("one\ntwo\n")
        repo.add(listOf("a.txt"))
        val second = repo.commit("second")
        assertNotNull(first); assertNotNull(second)
        assertEquals(listOf(first!!.sha), second!!.parents)
        assertEquals(2, repo.log().size)
        if (gitAvailable()) {
            assertEquals("second", git("log", "-1", "--pretty=%s", cwd = root).trim())
            assertEquals("2", git("rev-list", "--count", "HEAD", cwd = root).trim())
        }
    }

    @Test
    fun `status detects untracked, staged and unstaged changes`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "a.txt").writeText("v1\n")
        repo.add(listOf("a.txt"))
        repo.commit("base")
        File(root, "b.txt").writeText("new file\n") // untracked
        File(root, "a.txt").writeText("v2\n") // unstaged modification

        val status = repo.status()
        assertEquals(listOf("b.txt"), status.untracked)
        assertEquals(1, status.unstaged.size)
        assertEquals("a.txt", status.unstaged.first().path)

        repo.add(listOf("a.txt"))
        val afterStaging = repo.status()
        assertTrue(afterStaging.unstaged.isEmpty())
        assertEquals(1, afterStaging.staged.size)
    }

    @Test
    fun `diff shows unified changes between index and worktree`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "f.txt").writeText("alpha\nbeta\n")
        repo.add(listOf("f.txt"))
        repo.commit("base")
        File(root, "f.txt").writeText("alpha\ngamma\n")
        val diff = repo.diff("f.txt")
        assertNotNull(diff)
        assertTrue(diff!!.contains("-beta"))
        assertTrue(diff.contains("+gamma"))
    }

    @Test
    fun `branches checkout and tags roundtrip`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "x.txt").writeText("1\n")
        repo.add(listOf("x.txt"))
        repo.commit("base")
        assertTrue(repo.createBranch("feature"))
        assertTrue(repo.checkout("feature"))
        assertEquals("feature", repo.currentBranch())
        File(root, "x.txt").writeText("2\n")
        repo.add(listOf("x.txt"))
        repo.commit("feature commit")
        assertTrue(repo.checkout("main"))
        assertEquals("1\n", File(root, "x.txt").readText())
        assertTrue(repo.createTag("v1.0.0", "first tag"))
        assertEquals(listOf("v1.0.0"), repo.tags())
        if (gitAvailable()) {
            assertEquals("1", git("cat-file", "-p", "HEAD:x.txt", cwd = root).trim())
            assertTrue(git("tag", "--list", cwd = root).contains("v1.0.0"))
            assertTrue(git("branch", "--list", cwd = root).contains("feature"))
        }
    }

    @Test
    fun `rm stages deletion and removes the file`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "gone.txt").writeText("bye\n")
        repo.add(listOf("gone.txt"))
        repo.commit("base")
        assertEquals(1, repo.rm(listOf("gone.txt")))
        assertFalse(File(root, "gone.txt").isFile)
        val status = repo.status()
        assertEquals(1, status.staged.size)
        assertEquals("deleted", status.staged.first().change)
        if (gitAvailable()) {
            assertEquals("D  gone.txt", git("status", "--porcelain", cwd = root).trim())
        }
    }

    @Test
    fun `fileAtHead returns committed content`() {
        val root = tmp.newFolder()
        val repo = GitLite.init(root)
        File(root, "doc.md").writeText("# doc\n")
        repo.add(listOf("doc.md"))
        repo.commit("docs")
        File(root, "doc.md").writeText("# changed\n")
        assertEquals("# doc\n", repo.fileAtHead("doc.md"))
        assertNull(repo.fileAtHead("missing.md"))
    }
}
