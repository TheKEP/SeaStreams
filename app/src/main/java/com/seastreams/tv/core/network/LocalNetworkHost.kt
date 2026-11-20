package com.seastreams.tv.core.network

/**
 * Recognises hosts that live on the user's own machine or LAN, where self-hosted addons are usually
 * served over plain `http://`. Only literals and well-known names are inspected; there is never a
 * DNS lookup, so this is safe on any thread.
 */
object LocalNetworkHost {

    /**
     * True for `localhost`, mDNS `*.local` names, IPv4 loopback/private/link-local ranges
     * (`127/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`) and IPv6 loopback, unique-local
     * (`fc00::/7`) and link-local (`fe80::/10`) literals. [host] may carry IPv6 brackets.
     */
    fun isLocal(host: String): Boolean {
        val bare = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (bare.isEmpty()) return false
        if (bare == "localhost" || bare.endsWith(".localhost") || bare.endsWith(".local")) return true
        ipv4Octets(bare)?.let { return isLocalIpv4(it) }
        return bare.contains(':') && isLocalIpv6(bare)
    }

    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    private fun isLocalIpv4(o: List<Int>): Boolean = when (o[0]) {
        127, 10 -> true
        172 -> o[1] in 16..31
        192 -> o[1] == 168
        169 -> o[1] == 254
        else -> false
    }

    private fun isLocalIpv6(host: String): Boolean {
        val address = host.substringBefore('%') // drop a zone id such as fe80::1%wlan0
        if (address == "::1" || address == "0:0:0:0:0:0:0:1") return true
        val firstGroup = address.substringBefore(':').ifEmpty { return false }
        val value = firstGroup.toIntOrNull(16)?.takeIf { firstGroup.length <= 4 } ?: return false
        return value and 0xFE00 == 0xFC00 || value and 0xFFC0 == 0xFE80
    }
}
