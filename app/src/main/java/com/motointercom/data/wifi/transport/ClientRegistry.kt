package com.motointercom.data.wifi.transport

import android.util.Log
import com.motointercom.domain.model.Rider
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of connected remote clients managed by the HOST.
 * Handles client lifecycle, inactivity timeouts, and roster generation.
 */
class ClientRegistry {
    companion object {
        private const val TAG = "ClientRegistry"
        const val CLIENT_INACTIVE_THRESHOLD_MS = 4500L
        const val CLIENT_EVICTION_THRESHOLD_MS = 15000L
    }

    private val clients = ConcurrentHashMap<String, RemoteClient>()

    fun get(id: String): RemoteClient? = clients[id]

    fun all(): Collection<RemoteClient> = clients.values

    fun count(): Int = clients.size

    /**
     * Registers or updates a client from a HELLO packet.
     * @return Pair(isNewClient, RemoteClient)
     */
    fun registerOrUpdate(id: String, name: String, address: InetSocketAddress): Pair<Boolean, RemoteClient> {
        val existing = clients[id]
        return if (existing == null) {
            val newClient = RemoteClient(
                id = id,
                name = name.ifBlank { "Rider" },
                address = address,
                lastSeen = System.currentTimeMillis(),
                isConnected = true
            )
            clients[id] = newClient
            Log.d(TAG, "Client registered: ${newClient.name} ($id) @ ${address.hostString}:${address.port}")
            true to newClient
        } else {
            existing.address = address
            existing.lastSeen = System.currentTimeMillis()
            existing.isConnected = true
            Log.d(TAG, "Client refreshed: ${existing.name} ($id) @ ${address.hostString}:${address.port}")
            false to existing
        }
    }

    fun remove(id: String): RemoteClient? {
        val removed = clients.remove(id)
        if (removed != null) {
            Log.d(TAG, "Client removed: ${removed.name} ($id)")
        }
        return removed
    }

    fun clear() {
        clients.clear()
    }

    fun getRemoteRiders(): List<Rider> {
        return clients.values.map { client ->
            Rider(
                id = client.id,
                name = client.name.ifBlank { "Rider" },
                isTalking = client.isTalking,
                signalLevel = client.amplitude,
                isConnected = client.isConnected,
                isHost = false
            )
        }
    }

    /**
     * Periodic sweep to detect inactive clients and evict dead connections.
     * @return true if the roster changed
     */
    fun sweep(now: Long, onClientLeft: (String) -> Unit): Boolean {
        var changed = false
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val client = entry.value
            val silence = now - client.lastSeen

            if (silence > CLIENT_EVICTION_THRESHOLD_MS) {
                iterator.remove()
                Log.d(TAG, "Client evicted after timeout (>15s): ${client.name} (${client.id})")
                onClientLeft(client.id)
                changed = true
            } else if (silence > CLIENT_INACTIVE_THRESHOLD_MS) {
                if (client.isConnected) {
                    client.isConnected = false
                    changed = true
                    Log.d(TAG, "Client temporarily silent: ${client.name} (${client.id})")
                }
            }
        }

        // Reset talking flags if no audio packet in recent window
        clients.values.forEach {
            if (now - it.lastSeen > 350) {
                it.isTalking = false
            }
        }

        return changed
    }
}
