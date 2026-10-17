package com.anvex.core.android.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
