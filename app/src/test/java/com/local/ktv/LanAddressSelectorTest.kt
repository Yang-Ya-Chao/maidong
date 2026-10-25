package com.local.ktv

import org.junit.Assert.*
import org.junit.Test

class LanAddressSelectorTest {
    private fun candidate(name: String, ip: String, active: Boolean = false, route: Boolean = false) =
        LanAddressSelector.Candidate(name, ip, active, route)

    @Test fun ignoresWifiDirectEvenWhenItIsEnumeratedFirst() {
        val candidates = listOf(candidate("p2p0", "192.168.49.1"),
            candidate("wlan0", "192.168.31.20", true, true))
        assertEquals("192.168.31.20", LanAddressSelector.select(candidates))
        assertEquals("192.168.31.20", LanAddressSelector.select(candidates.reversed()))
    }

    @Test fun prefersActiveLanOverOtherPhysicalNetworks() {
        assertEquals("192.168.31.20", LanAddressSelector.select(listOf(
            candidate("eth0", "10.0.0.2", route = true),
            candidate("wlan0", "192.168.31.20", active = true))))
    }

    @Test fun supportsApi21DefaultRouteAndOfflineLan() {
        assertEquals("192.168.1.8", LanAddressSelector.select(listOf(
            candidate("eth0", "10.0.0.2"),
            candidate("wlan0", "192.168.1.8", route = true))))
        assertEquals("10.0.0.2", LanAddressSelector.select(listOf(candidate("eth0", "10.0.0.2"))))
    }

    @Test fun rejectsVpnCellularAndPeerOnlyConnections() {
        for (name in listOf("tun0", "tap0", "p2p-wlan0-0", "wifi-p2p0", "rmnet0", "ccmni0", "lo")) {
            assertNull(LanAddressSelector.select(listOf(candidate(name, "192.168.49.1", true, true))))
            assertFalse(LanAddressSelector.isFallbackLanInterface(name))
        }
    }

    @Test fun rejectsLoopbackLinkLocalIpv6PublicAndMalformedAddresses() {
        for (ip in listOf("127.0.0.1", "169.254.1.2", "fe80::1", "8.8.8.8",
            "192.168.1.999", "192.168.1", "host.local")) {
            assertNull(LanAddressSelector.select(listOf(candidate("wlan0", ip))))
        }
    }

    @Test fun sameSubnetNumberIsNotMistakenForWifiDirect() {
        assertEquals("192.168.49.1", LanAddressSelector.select(
            listOf(candidate("eth0", "192.168.49.1", true))))
        assertEquals("100.64.0.2", LanAddressSelector.select(
            listOf(candidate("eth0", "100.64.0.2"))))
    }

    @Test fun missingNetworkCannotStartServerOnLoopback() {
        assertFalse(LocalRemoteServer.isPrivateIpv4(null))
        assertFalse(LocalRemoteServer.isPrivateIpv4(""))
        assertFalse(LocalRemoteServer.isPrivateIpv4("   "))
    }

    @Test fun fallbackAcceptsOnlyKnownPhysicalLanInterfaces() {
        for (name in listOf("wlan0", "eth0", "en0", "usb0", "rndis0")) {
            assertTrue(LanAddressSelector.isFallbackLanInterface(name))
        }
        assertFalse(LanAddressSelector.isFallbackLanInterface("unknown0"))
        assertNull(LanAddressSelector.select(emptyList()))
    }
}
