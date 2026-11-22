package com.hermes.mobile.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OkHttp choke-point guard must accept private gateways and reject
 * public cleartext hosts even if prefs were seeded outside pairing. */
class ServerHostGuardTest {
    @Test
    fun acceptsPrivateAndTailscaleHosts() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "192.168.1.5",
            "172.16.0.1", "172.31.255.255", "100.64.0.1", "169.254.1.1",
            "fd7a:115c:a1e0::1", "node.ts.net", "box.local"
        ).forEach { assertTrue(it, ServerHostGuard.isTrustedHttpHost(it)) }
    }

    @Test
    fun rejectsPublicHosts() {
        listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "100.63.0.1",
            "2001:4860:4860::8888", "example.com", ""
        ).forEach { assertFalse(it, ServerHostGuard.isTrustedHttpHost(it)) }
    }
}
