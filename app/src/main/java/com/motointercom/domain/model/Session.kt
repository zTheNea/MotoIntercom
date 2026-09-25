package com.motointercom.domain.model

/**
 * Role of the local device in the intercom session.
 * HOST: creates the WiFi hotspot and acts as audio relay.
 * CLIENT: connects to the HOST's hotspot.
 */
enum class SessionRole { HOST, CLIENT }

/**
 * Current lifecycle state of the intercom session.
 */
enum class SessionState {
    IDLE,           // Not in a session
    CREATING,       // HOST is setting up the hotspot
    JOINING,        // CLIENT is connecting
    ACTIVE,         // Session is running
    DISCONNECTED    // Lost connection
}

/**
 * Represents a full intercom group session.
 */
data class Session(
    val id: String = "",
    val localRiderName: String = "",
    val role: SessionRole = SessionRole.HOST,
    val state: SessionState = SessionState.IDLE,

    // WiFi hotspot credentials (shown to other riders to join)
    val ssid: String = "",
    val passphrase: String = "",
    val hostIp: String = "",

    // Participants (excluding self)
    val riders: List<Rider> = emptyList(),
    val maxRiders: Int = 4,

    // Audio controls
    val isMuted: Boolean = false,
    val isPttActive: Boolean = false,
    val isVoxEnabled: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isBluetoothHeadsetConnected: Boolean = false
) {
    val isActive get() = state == SessionState.ACTIVE
    val connectedCount get() = riders.count { it.isConnected } + 1 // +1 for self
    val canAcceptMore get() = connectedCount < maxRiders
}
