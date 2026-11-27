package com.hermes.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compare logic is the load-bearing part of the self-update flow:
 * a false "update available" loop or a missed patch both destroy trust.
 * It must mirror scripts/release.sh's versionCode formula exactly. */
class AppUpdateCheckerTest {

    @Test
    fun `tag maps to the release-script versionCode formula`() {
        assertEquals(52, AppUpdateChecker.versionCodeFromTag("v0.0.52"))
        assertEquals(52, AppUpdateChecker.versionCodeFromTag("0.0.52"))
        assertEquals(10_203, AppUpdateChecker.versionCodeFromTag("v1.2.3"))
        assertEquals(0, AppUpdateChecker.versionCodeFromTag(""))
        assertEquals(0, AppUpdateChecker.versionCodeFromTag("garbage"))
    }

    @Test
    fun `patch ordering is numeric not lexicographic`() {
        // "v0.0.9" > "v0.0.10" string-wise — the formula must not.
        assertTrue(AppUpdateChecker.versionCodeFromTag("v0.0.10") >
            AppUpdateChecker.versionCodeFromTag("v0.0.9"))
        assertFalse(AppUpdateChecker.versionCodeFromTag("v0.0.52") >
            AppUpdateChecker.versionCodeFromTag("v0.0.52"))   // equal = up to date
        // The installed 0.1.32 is code 132 and tag v0.1.32 must map to 132 too,
        // not 1032 (the old 1e6/1e3 formula) — otherwise About offers an update.
        assertEquals(132, AppUpdateChecker.versionCodeFromTag("v0.1.32"))
        assertEquals(132, AppUpdateChecker.versionCodeFromTag("0.1.32"))
        assertTrue(AppUpdateChecker.versionCodeFromTag("v0.1.0") >
            AppUpdateChecker.versionCodeFromTag("v0.0.99"))
    }

    @Test
    fun `semver metadata and prerelease are stripped`() {
        assertEquals(133, AppUpdateChecker.versionCodeFromTag("v0.1.33+build5"))
        assertEquals(133, AppUpdateChecker.versionCodeFromTag("v0.1.33-rc.1"))
        assertEquals(133, AppUpdateChecker.versionCodeFromTag("0.1.33-rc.1+meta"))
    }
}
