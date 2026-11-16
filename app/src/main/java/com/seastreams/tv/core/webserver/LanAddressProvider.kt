package com.seastreams.tv.core.webserver

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton

/** Finds the TV's LAN IPv4 address so the portal URL/QR code can be shown on screen. */
@Singleton
class LanAddressProvider @Inject constructor() {

    /** Best LAN IPv4 address (e.g. `192.168.1.42`), or `null` when the TV has no network. */
    fun currentAddress(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { nic ->
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .mapNotNull { address -> address.hostAddress?.let { Candidate(nic.name, it, address.isSiteLocalAddress) } }
                }
        } catch (e: SocketException) {
            emptyList()
        }
        return pickBest(candidates)
    }

    data class Candidate(val interfaceName: String, val address: String, val isSiteLocal: Boolean)

    companion object {
        private val PREFERRED_PREFIXES = listOf("eth", "wlan")

        /** Prefers private (site-local) addresses on Ethernet, then Wi-Fi, then anything else. */
        fun pickBest(candidates: List<Candidate>): String? = candidates.sortedWith(
            compareByDescending<Candidate> { it.isSiteLocal }
                .thenBy { c -> PREFERRED_PREFIXES.indexOfFirst { c.interfaceName.startsWith(it) }.let { if (it < 0) Int.MAX_VALUE else it } },
        ).firstOrNull()?.address

        fun portalUrl(address: String, port: Int): String = "http://$address:$port"
    }
}
