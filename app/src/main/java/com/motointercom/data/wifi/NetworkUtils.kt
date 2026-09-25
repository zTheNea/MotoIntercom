package com.motointercom.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"
    private const val FALLBACK_HOTSPOT_IP = "192.168.43.1"

    /**
     * Finds the real active IPv4 address on this device (WiFi, Hotspot, AP).
     */
    fun getLocalIpAddress(context: Context? = null): String {
        try {
            // First check active WiFi connection from WifiManager if available
            context?.let { ctx ->
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val formatted = intToIpString(ipInt)
                    if (formatted != "0.0.0.0" && formatted.isNotBlank()) {
                        Log.d(TAG, "Detected IP from WifiManager: $formatted")
                        return formatted
                    }
                }
            }

            // Inspect network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return FALLBACK_HOTSPOT_IP
            val candidates = mutableListOf<Pair<String, String>>() // (ifaceName, ip)

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        Log.d(TAG, "Found candidate IP: $ip on interface $name")
                        candidates.add(name to ip)
                    }
                }
            }

            // Priority 1: Hotspot/AP interfaces (ap0, softap, swlan...)
            val apCandidate = candidates.firstOrNull { (name, _) ->
                name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan")
            }
            if (apCandidate != null) return apCandidate.second

            // Priority 2: Standard WiFi interface (wlan0, wlan1...)
            val wlanCandidate = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }
            if (wlanCandidate != null) return wlanCandidate.second

            // Priority 3: Any other private IPv4 (192.168.x.x, 10.x.x.x, 172.x.x.x)
            val otherPrivate = candidates.firstOrNull { (_, ip) ->
                ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
            }
            if (otherPrivate != null) return otherPrivate.second

            if (candidates.isNotEmpty()) return candidates.first().second

        } catch (e: Exception) {
            Log.w(TAG, "Error detecting IP address", e)
        }

        return FALLBACK_HOTSPOT_IP
    }

    /**
     * If this device is connected to a WiFi network (or another phone's hotspot),
     * returns the Gateway IP (which is the Host phone's IP!).
     */
    fun getGatewayIpAddress(context: Context?): String? {
        return try {
            val ctx = context?.applicationContext ?: return null
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            val gateway = wm.dhcpInfo?.gateway ?: 0
            if (gateway != 0) {
                val formatted = intToIpString(gateway)
                if (formatted != "0.0.0.0" && formatted.isNotBlank()) {
                    Log.d(TAG, "Detected Gateway (Host) IP from DHCP: $formatted")
                    formatted
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting gateway IP", e)
            null
        }
    }

    /**
     * Gets all valid broadcast addresses for active network interfaces,
     * including calculated subnet broadcasts (/24) for every local IPv4 interface.
     */
    fun getAllBroadcastAddresses(extraIp: String? = null): List<InetAddress> {
        val bcasts = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val b = ifaceAddr.broadcast
                    if (b != null) bcasts.add(b)

                    val addr = ifaceAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            try {
                                bcasts.add(InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255"))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating broadcast addresses", e)
        }

        // Subnet broadcast for extraIp (e.g. host IP)
        if (!extraIp.isNullOrBlank()) {
            val parts = extraIp.split(".")
            if (parts.size == 4) {
                try {
                    bcasts.add(InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255"))
                } catch (_: Exception) {}
            }
        }

        // Fallback common hotspot subnets
        try { bcasts.add(InetAddress.getByName("192.168.43.255")) } catch (_: Exception) {}
        try { bcasts.add(InetAddress.getByName("192.168.49.255")) } catch (_: Exception) {}
        try { bcasts.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}

        Log.d(TAG, "Calculated broadcast addresses: ${bcasts.map { it.hostAddress }}")
        return bcasts.toList()
    }

    /**
     * Converts an integer IP address (as returned by WifiManager, little-endian)
     * to a human-readable dotted-quad string.
     */
    internal fun intToIpString(ip: Int): String =
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"

    /**
     * Checks whether the given IP address belongs to this local device on any network interface.
     */
    fun isLocalIpAddress(ip: String): Boolean {
        if (ip.isBlank() || ip == "127.0.0.1" || ip == "0.0.0.0" || ip.equals("localhost", ignoreCase = true)) return true
        val cleanIp = ip.substringBefore("%")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val addrStr = addr.hostAddress?.substringBefore("%")
                    if (addrStr == cleanIp) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if IP $ip is local: ${e.message}")
        }
        return false
    }
}
