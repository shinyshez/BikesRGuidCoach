package com.mtbanalyzer.viewer

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Picks the address to advertise in the QR code.
 *
 * When the recorder is the hotspot its own address sits on an AP interface rather than
 * wlan0, and OEMs name those differently, so candidates are ranked rather than guessed at.
 * The chosen address is shown as text on the Viewer Link screen in case the ranking is
 * wrong on some device.
 */
object NetworkAddress {

    private const val TAG = "NetworkAddress"

    private val AP_PREFIXES = listOf("ap", "swlan", "softap", "wlan1")

    fun candidates(): List<InetAddress> {
        val ranked = mutableListOf<Pair<Int, InetAddress>>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                val rank = when {
                    AP_PREFIXES.any { name.startsWith(it) } -> 0
                    name.startsWith("wlan") -> 1
                    name.startsWith("eth") -> 2
                    else -> 3
                }
                for (address in Collections.list(nif.inetAddresses)) {
                    if (address.isLoopbackAddress) continue
                    if (address !is Inet4Address) continue
                    ranked.add(rank to address)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate network interfaces", e)
        }
        return ranked.sortedBy { it.first }.map { it.second }
    }

    fun best(): InetAddress? = candidates().firstOrNull()
}
