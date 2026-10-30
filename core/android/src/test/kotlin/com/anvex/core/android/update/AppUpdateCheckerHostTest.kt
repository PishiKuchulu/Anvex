package com.anvex.core.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the updater's redirect allowlist to the hosts GitHub actually
 * uses for release assets. The 4.11.x updater allowed only github.com
 * and the legacy objects.githubusercontent.com, so every real download
 * (which redirects to release-assets.githubusercontent.com) was
 * refused with "redirected to an unexpected host".
 */
class AppUpdateCheckerHostTest {

    @Test
    fun allowsGithubAndItsReleaseAssetCdnHosts() {
        assertTrue(AppUpdateChecker.isAllowedUpdateRedirectHost("github.com"))
        assertTrue(AppUpdateChecker.isAllowedUpdateRedirectHost("objects.githubusercontent.com"))
        assertTrue(AppUpdateChecker.isAllowedUpdateRedirectHost("release-assets.githubusercontent.com"))
    }

    @Test
    fun rejectsLookalikeAndUnrelatedHosts() {
        assertFalse(
            AppUpdateChecker.isAllowedUpdateRedirectHost(
                "release-assets.githubusercontent.com.evil.example",
            ),
        )
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("evil-release-assets.githubusercontent.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("githubusercontent.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("raw.githubusercontent.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("github.com.evil.example"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("example.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost(""))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost(null))
    }
}
