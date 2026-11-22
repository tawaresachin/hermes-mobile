package com.hermes.mobile.network

/** Host allowlist for cleartext gateway traffic. Android's network security
 * config cannot express raw-IP/CIDR ranges, so the OkHttp interceptor enforces
 * this at the choke point: public hosts may only be reached over HTTPS. */
object ServerHostGuard {
    fun isTrustedHttpHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local")) return true

        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (ipv4 != null) {
            val o = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (o.any { it > 255 }) return false
            val (a, b) = o[0] to o[1]
            return when {
                a == 10 -> true
                a == 192 && b == 168 -> true
                a == 172 && b in 16..31 -> true
                a == 127 -> true
                a == 100 && b in 64..127 -> true // Tailscale CGNAT
                a == 169 && b == 254 -> true     // link-local
                else -> false
            }
        }

        val isV6Literal = h.isNotEmpty() && h.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }
        if (isV6Literal) {
            return when {
                h == "::1" -> true
                h.startsWith("fe") && h.length >= 3 && h[2] in '8'..'b' -> true
                h.startsWith("fc") || h.startsWith("fd") -> true // ULA / Tailscale
                else -> false
            }
        }
        return h.endsWith(".ts.net") || h.endsWith(".internal") ||
            h.endsWith(".lan") || h.endsWith(".home.arpa")
    }
}
