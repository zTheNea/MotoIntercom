package com.motointercom.domain.model

import java.net.InetAddress

/**
 * Represents a single motorcyclist participant in an intercom session.
 */
data class Rider(
    val id: String,
    val name: String,
    val address: InetAddress? = null,
    val port: Int = 0,
    val isTalking: Boolean = false,
    val signalLevel: Float = 0f,    // 0.0 to 1.0 — voice amplitude
    val isConnected: Boolean = true,
    val isHost: Boolean = false
)
