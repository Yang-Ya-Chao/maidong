package com.local.ktv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** Select a phone-reachable LAN address, never a Wi-Fi Direct or VPN interface. */
internal object LanAddressSelector {
    data class Candidate(
        val interfaceName: String,
        val address: String,
        val active: Boolean = false,
        val defaultRoute: Boolean = false
    )

    fun select(candidates: List<Candidate>): String? = candidates
        .filter { isUsableInterface(it.interfaceName) && isLanIpv4(it.address) }
        .sortedWith(compareByDescending<Candidate> { it.active }
            .thenByDescending { it.defaultRoute }
            .thenBy { it.interfaceName }
            .thenBy { it.address })
        .firstOrNull()?.address

    fun isUsableInterface(name: String): Boolean {
        val value = name.lowercase(Locale.ROOT)
        return value.isNotBlank() && listOf("lo", "p2p", "wifi-p2p", "aware", "tun", "tap",
            "vpn", "ppp", "rmnet", "ccmni", "dummy", "veth").none { value.startsWith(it) }
    }

    fun isFallbackLanInterface(name: String): Boolean {
        val value = name.lowercase(Locale.ROOT)
        return isUsableInterface(value) &&
            listOf("wlan", "wifi", "eth", "en", "lan", "ra", "tiwlan", "usb", "rndis")
                .any { value.startsWith(it) }
    }

    private fun isLanIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull()?.takeIf { b -> b in 0..255 } ?: return false }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 100 && bytes[1] in 64..127)
    }
}

internal object LanAddressResolver {
    fun resolve(context: Context): String? {
        val candidates = mutableListOf<LanAddressSelector.Candidate>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) runCatching {
            val active = if (Build.VERSION.SDK_INT >= 23) manager.activeNetwork else null
            // INTERNET/VALIDATED are deliberately not required: offline LANs also work.
            for (network in manager.allNetworks) runCatching networkCandidate@ {
                val capabilities = manager.getNetworkCapabilities(network) ?: return@networkCandidate
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@networkCandidate
                val properties = manager.getLinkProperties(network) ?: return@networkCandidate
                val name = properties.interfaceName ?: return@networkCandidate
                for (link in properties.linkAddresses) {
                    val address = link.address
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        candidates += LanAddressSelector.Candidate(name, address.hostAddress.orEmpty(),
                            network == active, properties.routes.any { it.isDefaultRoute })
                    }
                }
            }
        }
        LanAddressSelector.select(candidates)?.let { return it }
        // Some TV firmware omits network capabilities. Only fall back to physical LAN interfaces.
        val fallback = mutableListOf<LanAddressSelector.Candidate>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                runCatching {
                    if (network.isUp && !network.isLoopback &&
                        LanAddressSelector.isFallbackLanInterface(network.name)) {
                        val addresses = network.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val address = addresses.nextElement()
                            if (address is Inet4Address) {
                                fallback += LanAddressSelector.Candidate(network.name, address.hostAddress.orEmpty())
                            }
                        }
                    }
                }
            }
        }
        return LanAddressSelector.select(fallback)
    }
}
