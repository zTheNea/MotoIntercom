package com.motointercom.data.wifi.transport

import java.net.InetSocketAddress

/**
 * Represents a remote intercom participant known to the HOST.
 */
data class RemoteClient(
    val id: String,
    val name: String,
    var address: InetSocketAddress,
    var lastSeen: Long = System.currentTimeMillis(),
    var isTalking: Boolean = false,
    var amplitude: Float = 0f,
    var isConnected: Boolean = true
)
