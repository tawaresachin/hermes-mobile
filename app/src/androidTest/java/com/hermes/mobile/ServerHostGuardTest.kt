package com.hermes.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermes.mobile.ui.screens.settings.isTrustedServerHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for `isTrustedServerHost` — the app's only gate that stops
 * a cleartext Bearer token from reaching a public host. Instrumented (not JVM)
 * because the guard parses input with `android.net.Uri`, which is only real on
 * a device/emulator. Run: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ServerHostGuardTest {

    // ── RFC1918 / loopback / link-local / CGNAT IPv4 ────────────────────────
    @Test
    fun privateIpv4IsTrusted() {
        assertTrue(isTrustedServerHost("http://10.0.0.5:8080"))
        assertTrue(isTrustedServerHost("http://192.168.1.5"))
        assertTrue(isTrustedServerHost("http://172.16.0.1"))
        assertTrue(isTrustedServerHost("http://127.0.0.1"))
        assertTrue(isTrustedServerHost("http://169.254.10.10")) // link-local
    }

    @Test
    fun tailscaleCgnatIpv4IsTrusted() {
        assertTrue(isTrustedServerHost("http://100.64.0.1"))
        assertTrue(isTrustedServerHost("http://100.127.255.255"))
    }

    @Test
    fun publicIpv4IsRejected() {
        assertFalse(isTrustedServerHost("http://8.8.8.8"))
        assertFalse(isTrustedServerHost("http://172.15.0.1"))  // below 172.16
        assertFalse(isTrustedServerHost("http://172.32.0.1"))   // above 172.31
        assertFalse(isTrustedServerHost("http://100.63.0.1"))   // below CGNAT
        assertFalse(isTrustedServerHost("http://100.128.0.1"))  // above CGNAT
    }

    @Test
    fun malformedOctetIsRejected() {
        assertFalse(isTrustedServerHost("http://300.1.1.1")) // >255
        assertFalse(isTrustedServerHost("http://1.2.3"))
        assertFalse(isTrustedServerHost("http://not-a-host/"))
        assertFalse(isTrustedServerHost(""))
        assertFalse(isTrustedServerHost("http:///nohost"))
    }

    // ── IPv6: loopback / link-local / ULA (Tailscale) trusted, public not ───
    @Test
    fun privateIpv6IsTrusted() {
        assertTrue(isTrustedServerHost("http://[::1]"))
        assertTrue(isTrustedServerHost("http://[fe80::1]"))           // link-local
        assertTrue(isTrustedServerHost("http://[fd7a:115c:a1e0::1]")) // Tailscale ULA
        assertTrue(isTrustedServerHost("http://[fd00::1]"))
    }

    @Test
    fun malformedIpv6AuthorityFailsClosed() {
        // An unbracketed IPv6 authority is not a legal URL; it must be rejected,
        // not (mis)parsed as a trusted literal.
        assertFalse(isTrustedServerHost("http://::1"))
    }

    @Test
    fun publicIpv6IsRejected() {
        assertFalse(isTrustedServerHost("http://[2606:4700:4700::1111]")) // public
        assertFalse(isTrustedServerHost("http://[2001:db8::1]"))          // docs-range public
    }

    // ── hostnames: only private namespaces ───────────────────────────────────
    @Test
    fun privateHostnamesAreTrusted() {
        assertTrue(isTrustedServerHost("http://hermes.local:8642"))
        assertTrue(isTrustedServerHost("http://node1.ts.net"))
        assertTrue(isTrustedServerHost("http://host.lan"))
        assertTrue(isTrustedServerHost("http://host.internal"))
        assertTrue(isTrustedServerHost("http://host.home.arpa"))
        assertTrue(isTrustedServerHost("http://localhost"))
    }

    @Test
    fun publicHostnamesAreRejected() {
        assertFalse(isTrustedServerHost("http://example.com:8642"))
        assertFalse(isTrustedServerHost("http://192.168.1.1.evil.com")) // IP-as-prefix must not match
        assertFalse(isTrustedServerHost("https://github.com"))
    }
}
