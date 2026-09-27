package com.motointercom.data.wifi.transport

import java.net.InetSocketAddress

/**
 * Represents a remote intercom participant known to the HOST.
 * Mutable state tracker for active connections in [ClientRegistry].
 */
class RemoteClient(
    val id: String,
    val name: String,
    var address: InetSocketAddress,
    var lastSeen: Long = System.currentTimeMillis(),
    var isTalking: Boolean = false,
    var amplitude: Float = 0f,
    var isConnected: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteClient) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "RemoteClient(id='$id', name='$name', address=$address, isConnected=$isConnected, isTalking=$isTalking)"
}
