package com.motointercom.data.wifi.transport

import android.util.Log
import com.motointercom.domain.model.ReconnectionState
import kotlinx.coroutines.delay

/**
 * Manages client-side watchdog, silence detection, and auto-reconnection bursts.
 */
class ReconnectionManager(
    private val maxReconnectAttempts: Int = 5,
    private val silenceThresholdMs: Long = 5000L,
    private val reconnectIntervalMs: Long = 2500L,
    private val initialGracePeriodMs: Long = 12000L
) {
    companion object {
        private const val TAG = "ReconnectionManager"
    }

    var isReconnecting: Boolean = false
        private set

    var reconnectAttempt: Int = 0
        private set

    var lastPacketFromHost: Long = System.currentTimeMillis()
    private var joinTimestamp: Long = System.currentTimeMillis()
    private var hasReceivedAnyPacketFromHost: Boolean = false
    private var lastAttemptTimestamp: Long = 0L

    var onReconnectionStateChanged: ((ReconnectionState) -> Unit)? = null
    var onSessionClosedByHost: (() -> Unit)? = null

    fun reset() {
        isReconnecting = false
        reconnectAttempt = 0
        joinTimestamp = System.currentTimeMillis()
        lastPacketFromHost = System.currentTimeMillis()
        hasReceivedAnyPacketFromHost = false
        lastAttemptTimestamp = 0L
    }

    /**
     * Called whenever any valid packet is received from the host.
     */
    fun onPacketReceivedFromHost() {
        lastPacketFromHost = System.currentTimeMillis()
        hasReceivedAnyPacketFromHost = true
        if (isReconnecting) {
            val prevAttempts = reconnectAttempt
            isReconnecting = false
            reconnectAttempt = 0
            Log.i(TAG, "Auto-reconnection SUCCESSFUL! Restored after $prevAttempts attempts.")
            onReconnectionStateChanged?.invoke(
                ReconnectionState(
                    isReconnecting = false,
                    attempt = 0,
                    maxAttempts = maxReconnectAttempts,
                    message = "Conexión restablecida"
                )
            )
        }
    }

    /**
     * Periodic watchdog tick to detect silence and perform auto-reconnect bursts.
     * @return true if the loop should continue, false if session should terminate
     */
    suspend fun tick(
        now: Long,
        onPerformBurst: suspend () -> Unit,
        onSendHeartbeat: suspend () -> Unit
    ): Boolean {
        // While within initial grace period and waiting for first packet from host,
        // do not trigger the silence watchdog yet. Just keep pulsing handshakes/heartbeats.
        if (!hasReceivedAnyPacketFromHost && (now - joinTimestamp < initialGracePeriodMs)) {
            onSendHeartbeat()
            delay(600)
            return true
        }

        val silenceMs = now - lastPacketFromHost

        if (!isReconnecting) {
            if (silenceMs > silenceThresholdMs) {
                // Host has been silent for > silenceThresholdMs! Initiate auto-reconnection mode
                isReconnecting = true
                reconnectAttempt = 1
                lastAttemptTimestamp = now
                Log.w(TAG, "Host packet silence (${silenceMs}ms). Initiating auto-reconnect attempt 1/$maxReconnectAttempts")
                onReconnectionStateChanged?.invoke(
                    ReconnectionState(
                        isReconnecting = true,
                        attempt = 1,
                        maxAttempts = maxReconnectAttempts,
                        message = "Reconectando con la sala..."
                    )
                )
                onPerformBurst()
            } else {
                onSendHeartbeat()
            }
            delay(1000)
            return true
        } else {
            // Currently in auto-reconnecting mode
            if (now - lastAttemptTimestamp >= reconnectIntervalMs) {
                reconnectAttempt++
                if (reconnectAttempt > maxReconnectAttempts) {
                    Log.e(TAG, "Max reconnection attempts reached ($maxReconnectAttempts). Concluding session.")
                    isReconnecting = false
                    onReconnectionStateChanged?.invoke(
                        ReconnectionState(
                            isReconnecting = false,
                            attempt = maxReconnectAttempts,
                            maxAttempts = maxReconnectAttempts,
                            message = "No se pudo restablecer la conexión"
                        )
                    )
                    onSessionClosedByHost?.invoke()
                    return false
                } else {
                    lastAttemptTimestamp = now
                    Log.w(TAG, "Auto-reconnect attempt $reconnectAttempt/$maxReconnectAttempts...")
                    onReconnectionStateChanged?.invoke(
                        ReconnectionState(
                            isReconnecting = true,
                            attempt = reconnectAttempt,
                            maxAttempts = maxReconnectAttempts,
                            message = "Reintentando conexión ($reconnectAttempt/$maxReconnectAttempts)..."
                        )
                    )
                    onPerformBurst()
                }
            }
            delay(500)
            return true
        }
    }
}
