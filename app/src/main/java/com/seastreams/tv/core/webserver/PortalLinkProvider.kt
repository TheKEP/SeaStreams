package com.seastreams.tv.core.webserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** What the TV shows so a phone can reach the portal. */
data class PortalLink(val url: String?, val problem: String?)

@Singleton
class PortalLinkProvider @Inject constructor(
    private val server: LocalPortalServer,
    private val lanAddresses: LanAddressProvider,
) {
    /** Re-checks the LAN address periodically, so a Wi-Fi reconnect updates the URL/QR on screen. */
    fun observe(): Flow<PortalLink> = combine(server.status, addressUpdates()) { status, address ->
        when {
            status is PortalStatus.Failed -> PortalLink(null, "Web Portal couldn't start: ${status.reason}")
            address == null -> PortalLink(null, "Connect the TV to your home network to use the Web Portal")
            status is PortalStatus.Running -> PortalLink(LanAddressProvider.portalUrl(address, status.port), null)
            else -> PortalLink(null, "Starting Web Portal…")
        }
    }.distinctUntilChanged()

    private fun addressUpdates(): Flow<String?> = flow {
        while (true) {
            emit(lanAddresses.currentAddress())
            delay(ADDRESS_REFRESH_MILLIS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    companion object {
        private const val ADDRESS_REFRESH_MILLIS = 5_000L
    }
}
