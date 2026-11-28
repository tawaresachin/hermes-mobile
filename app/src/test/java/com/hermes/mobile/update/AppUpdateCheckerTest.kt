package com.hermes.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {

    private val hash = "98ceddfead7e5bb9e67f4b5e3e3cc966ea2b1e29db6ca8d3c93a9278ed9843c1"
    private val wanted = "Hermes-Mobile-v0.1.35.apk"

    @Test
    fun `parses release checksum with dot slash prefix`() {
        // The actual format published in SHA256SUMS on GitHub Releases.
        assertEquals(hash, AppUpdateChecker.sha256For("$hash  ./$wanted", wanted))
    }

    @Test
    fun `parses plain filename and binary marker`() {
        assertEquals(hash, AppUpdateChecker.sha256For("$hash  $wanted", wanted))
        assertEquals(hash, AppUpdateChecker.sha256For("$hash *$wanted", wanted))
    }

    @Test
    fun `does not match a different asset embedding the wanted name`() {
        assertNull(AppUpdateChecker.sha256For("$hash  ./old-$wanted", wanted))
        assertNull(AppUpdateChecker.sha256For("$hash  ./$wanted.sig", wanted))
    }

    @Test
    fun `rejects malformed lines`() {
        assertNull(AppUpdateChecker.sha256For("not a hash  ./$wanted", wanted))
        assertNull(AppUpdateChecker.sha256For(hash, wanted))
        // Any directory prefix is normalized away — only the basename counts.
        assertEquals(hash, AppUpdateChecker.sha256For("$hash  " + "./".repeat(3) + wanted, wanted))
    }

    @Test
    fun `version code matches release script lanes`() {
        assertEquals(135, AppUpdateChecker.versionCodeFromTag("v0.1.35"))
        assertEquals(133, AppUpdateChecker.versionCodeFromTag("0.1.33-rc.1"))
    }
}
