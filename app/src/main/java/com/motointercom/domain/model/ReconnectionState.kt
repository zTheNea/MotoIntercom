package com.motointercom.domain.model

/**
 * Represents the current automatic reconnection status of an intercom session.
 *
 * @param isReconnecting True if the connection was lost and reconnection attempts are in progress.
 * @param attempt Current attempt index (1 to maxAttempts).
 * @param maxAttempts Maximum number of reconnection attempts before concluding the session.
 * @param message Optional informative message describing current reconnection status.
 */
data class ReconnectionState(
    val isReconnecting: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val message: String = ""
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        val CONNECTED = ReconnectionState()
    }
}
