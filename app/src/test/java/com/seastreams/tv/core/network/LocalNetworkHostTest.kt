package com.seastreams.tv.core.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalNetworkHostTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "localhost", "LOCALHOST", "api.localhost", "nas.local",
            "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.4.173", "169.254.10.20",
            "::1", "[::1]", "0:0:0:0:0:0:0:1", "fc00::1", "fd12:3456::1", "fe80::1", "fe80::1%wlan0", "febf::1",
        ],
    )
    fun `LAN and loopback hosts are local`(host: String) {
        assertTrue(LocalNetworkHost.isLocal(host), host)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "example.com", "localhost.example.com", "local", "torrentio.strem.fun",
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "169.253.0.1", "11.0.0.1",
            "999.1.1.1", "192.168.1", "192.168.1.1.1", "192.168.01x.1", "1.2.3.-4",
            "2001:db8::1", "fec0::1", "fbff::1", "::ffff:192.168.1.1", "12345::1",
        ],
    )
    fun `public, malformed and other hosts are not local`(host: String) {
        assertFalse(LocalNetworkHost.isLocal(host), host)
    }
}
