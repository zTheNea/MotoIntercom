package com.motointercom.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class DiscoveredSession(
    val hostName: String,
    val hostIp: String,
    val ridersCount: Int,
    val maxRiders: Int = 4,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Handles automatic discovery of active intercom sessions over local WiFi/Hotspot using UDP beacons.
 */
class SessionDiscovery(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SessionDiscovery"
        const val DISCOVERY_PORT = 12347
        private const val BEACON_PREFIX = "MOTO_BEACON_V1"
        private const val PROBE_PREFIX = "MOTO_PROBE_V1"
    }

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var probeJob: Job? = null
    private var cleanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredSessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = _discoveredSessions.asStateFlow()

    /**
     * Host: broadcast discovery beacons to all subnet broadcast addresses every 1.2s
     * and listen for client probes to reply immediately.
     */
    /**
     * Host: broadcast discovery beacons to all subnet broadcast addresses every 1.0s
     * and listen for client probes to reply immediately.
     */
    fun startBroadcasting(hostName: String, hostIp: String, ridersCountProvider: () -> Int) {
        stopBroadcasting()

        broadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                    broadcast = true
                }
                Log.d(TAG, "Host beacon broadcaster bound on port $DISCOVERY_PORT for $hostName @ $hostIp")

                // Launch incoming probe listener job on the same socket
                val probeResponder = launch(Dispatchers.IO) {
                    val buffer = ByteArray(256)
                    while (isActive) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket?.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (text.startsWith(PROBE_PREFIX)) {
                                val count = ridersCountProvider()
                                val replyPayload = "$BEACON_PREFIX|$hostName|$hostIp|$count|4".toByteArray(Charsets.UTF_8)
                                
                                // 1. Reply to DISCOVERY_PORT on the sender (for listeners on 12347)
                                try {
                                    val replyPacket1 = DatagramPacket(replyPayload, replyPayload.size, packet.address, DISCOVERY_PORT)
                                    socket?.send(replyPacket1)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Reply to DISCOVERY_PORT failed: ${e.message}")
                                }

                                // 2. Also reply to the exact port that sent the probe (ephemeral socket)
                                if (packet.port != DISCOVERY_PORT) {
                                    try {
                                        val replyPacket2 = DatagramPacket(replyPayload, replyPayload.size, packet.address, packet.port)
                                        socket?.send(replyPacket2)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Reply to ephemeral port failed: ${e.message}")
                                    }
                                }

                                Log.d(TAG, "Replied to probe from ${packet.address.hostAddress}:${packet.port}")
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                        }
                    }
                }

                // Periodic beacon to all broadcast addresses
                while (isActive) {
                    val count = ridersCountProvider()
                    val payload = "$BEACON_PREFIX|$hostName|$hostIp|$count|4".toByteArray(Charsets.UTF_8)
                    val targets = NetworkUtils.getAllBroadcastAddresses(hostIp)

                    for (target in targets) {
                        try {
                            val packet = DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)
                            socket?.send(packet)
                        } catch (e: Exception) {
                            // ENETUNREACH is normal for 255.255.255.255 without default route; ignore
                        }
                    }
                    delay(1000)
                }

                probeResponder.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Host beacon exception", e)
            } finally {
                socket?.close()
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
        Log.d(TAG, "Beacon broadcast stopped")
    }

    /**
     * Client: listen on DISCOVERY_PORT for active Host beacons and periodically send active probes.
     */
    fun startListening() {
        if (listenJob != null && listenJob?.isActive == true) return

        // Acquire Android MulticastLock so WiFi chip passes broadcast/multicast packets to the CPU
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("motointercom_discovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired: ${multicastLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock", e)
        }

        // Listener on fixed DISCOVERY_PORT
        listenJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                    broadcast = true
                }
                val buffer = ByteArray(512)
                Log.d(TAG, "Client discovery listener active on port $DISCOVERY_PORT")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        handleIncomingPacket(packet)
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Discovery receive warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client discovery listener exception", e)
            } finally {
                socket?.close()
            }
        }

        // Active prober: send query packets to find hosts immediately and listen on response socket
        probeJob = scope.launch(Dispatchers.IO) {
            var probeSocket: DatagramSocket? = null
            try {
                probeSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1500
                }
                val probePayload = "$PROBE_PREFIX|SEARCH".toByteArray(Charsets.UTF_8)

                // Sub-coroutine to receive replies directed to this ephemeral probe socket
                val probeReceiver = launch(Dispatchers.IO) {
                    val buf = ByteArray(512)
                    while (isActive) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            probeSocket?.receive(p)
                            handleIncomingPacket(p)
                        } catch (_: Exception) {
                            if (!isActive) break
                        }
                    }
                }

                while (isActive) {
                    val targets = mutableSetOf<InetAddress>()
                    targets.addAll(NetworkUtils.getAllBroadcastAddresses())

                    // Probe gateway directly if known (host IP when connected to host's hotspot)
                    NetworkUtils.getGatewayIpAddress(context)?.let { gw ->
                        try { targets.add(InetAddress.getByName(gw)) } catch (_: Exception) {}
                    }
                    // Standard hotspot gateway
                    try { targets.add(InetAddress.getByName("192.168.43.1")) } catch (_: Exception) {}
                    try { targets.add(InetAddress.getByName("192.168.49.1")) } catch (_: Exception) {}

                    for (target in targets) {
                        try {
                            val p = DatagramPacket(probePayload, probePayload.size, target, DISCOVERY_PORT)
                            probeSocket.send(p)
                        } catch (_: Exception) {}
                    }
                    delay(1200)
                }

                probeReceiver.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Probe job exception", e)
            } finally {
                probeSocket?.close()
            }
        }

        // Clean up stale sessions not heard from in 8 seconds
        cleanJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                val now = System.currentTimeMillis()
                _discoveredSessions.value = _discoveredSessions.value.filter { now - it.lastSeen < 8000 }
            }
        }
    }

    internal fun handleIncomingPacket(packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val parts = text.split("|")
        if (parts.size >= 5 && parts[0] == BEACON_PREFIX) {
            val hostName = parts[1].trim()
            val rawSenderIp = packet.address?.hostAddress?.substringBefore("%") ?: ""
            val senderIp = if (rawSenderIp.contains(":")) "" else rawSenderIp
            val reportedIp = parts[2].trim().substringBefore("%")

            // Physical sender IP is guaranteed reachable across the active network link; prioritize it
            val finalIp = when {
                senderIp.isNotBlank() && senderIp != "127.0.0.1" && senderIp != "0.0.0.0" -> senderIp
                reportedIp.isNotBlank() && reportedIp != "127.0.0.1" && reportedIp != "0.0.0.0" -> reportedIp
                else -> senderIp
            }

            // Do not discover ourselves!
            if (NetworkUtils.isLocalIpAddress(finalIp)) {
                return
            }

            val riders = parts[3].toIntOrNull() ?: 1
            val max = parts[4].toIntOrNull() ?: 4

            val discovered = DiscoveredSession(
                hostName = hostName.ifBlank { "Anfitrión Moto" },
                hostIp = finalIp,
                ridersCount = riders,
                maxRiders = max,
                lastSeen = System.currentTimeMillis()
            )
            Log.d(TAG, "Discovered active session: $discovered from physical sender $senderIp (reported=$reportedIp)")
            updateDiscoveredSession(discovered)
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        probeJob?.cancel()
        probeJob = null
        cleanJob?.cancel()
        cleanJob = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
        _discoveredSessions.value = emptyList()
        Log.d(TAG, "Discovery listener stopped and MulticastLock released")
    }

    internal fun updateDiscoveredSession(session: DiscoveredSession) {
        val current = _discoveredSessions.value.toMutableList()
        val index = current.indexOfFirst { it.hostIp == session.hostIp }
        if (index >= 0) {
            current[index] = session
        } else {
            current.add(session)
        }
        _discoveredSessions.value = current
    }
}
