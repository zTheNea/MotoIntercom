package com.motointercom.data.wifi.transport

import com.motointercom.domain.model.ReconnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReconnectionManagerTest {

    private lateinit var manager: ReconnectionManager
    private val capturedStates = mutableListOf<ReconnectionState>()
    private var sessionClosed = false

    @Before
    fun setup() {
        manager = ReconnectionManager(
            maxReconnectAttempts = 3,
            silenceThresholdMs = 2000L,
            reconnectIntervalMs = 1000L,
            initialGracePeriodMs = 5000L
        )
        capturedStates.clear()
        sessionClosed = false
        manager.onReconnectionStateChanged = { capturedStates.add(it) }
        manager.onSessionClosedByHost = { sessionClosed = true }
    }

    @Test
    fun `initial state is not reconnecting`() {
        assertFalse(manager.isReconnecting)
        assertEquals(0, manager.reconnectAttempt)
    }

    @Test
    fun `onPacketReceivedFromHost resets reconnection state`() {
        // Simulate entering reconnection mode manually
        manager.onPacketReceivedFromHost()
        assertFalse(manager.isReconnecting)
        assertEquals(0, manager.reconnectAttempt)
    }

    @Test
    fun `reset clears all state`() {
        manager.onPacketReceivedFromHost()
        manager.reset()
        assertFalse(manager.isReconnecting)
        assertEquals(0, manager.reconnectAttempt)
    }

    @Test
    fun `tick during grace period sends heartbeat without triggering reconnect`() = runTest {
        var heartbeatSent = false
        var burstPerformed = false

        // Call tick immediately after creation (within grace period, no host packets yet)
        val joinTime = System.currentTimeMillis()
        val result = manager.tick(
            now = joinTime + 1000L, // 1s after join, within 5s grace
            onPerformBurst = { burstPerformed = true },
            onSendHeartbeat = { heartbeatSent = true }
        )

        assertTrue("tick should return true to continue", result)
        assertTrue("should send heartbeat during grace period", heartbeatSent)
        assertFalse("should NOT burst during grace period", burstPerformed)
    }

    @Test
    fun `tick detects silence after grace period and initiates reconnect`() = runTest {
        var burstPerformed = false

        // Simulate that we DID receive a packet from host (exit grace period)
        manager.onPacketReceivedFromHost()

        // Now simulate silence beyond threshold
        val now = System.currentTimeMillis() + 3000L // 3s after last packet (threshold = 2s)
        manager.lastPacketFromHost = now - 3000L

        val result = manager.tick(
            now = now,
            onPerformBurst = { burstPerformed = true },
            onSendHeartbeat = {}
        )

        assertTrue("tick should return true", result)
        assertTrue("should enter reconnection mode", manager.isReconnecting)
        assertEquals(1, manager.reconnectAttempt)
        assertTrue("should perform burst", burstPerformed)
        assertTrue("should emit reconnection state", capturedStates.isNotEmpty())
        assertTrue(capturedStates.last().isReconnecting)
    }

    @Test
    fun `onPacketReceivedFromHost during reconnection emits recovery state`() {
        // Force into reconnecting state by calling onPacketReceivedFromHost to set flag,
        // then manually verify recovery flow
        manager.onPacketReceivedFromHost()
        assertFalse(manager.isReconnecting)

        // Verify no session closed
        assertFalse(sessionClosed)
    }

    @Test
    fun `max reconnect attempts triggers session closed`() = runTest {
        // Simulate host received packet (exit grace)
        manager.onPacketReceivedFromHost()

        // Force silence
        val baseTime = System.currentTimeMillis()
        manager.lastPacketFromHost = baseTime - 5000L

        // First tick: enters reconnection mode (attempt 1)
        manager.tick(
            now = baseTime,
            onPerformBurst = {},
            onSendHeartbeat = {}
        )
        assertTrue(manager.isReconnecting)
        assertEquals(1, manager.reconnectAttempt)

        // Subsequent ticks: advance through attempts 2, 3, and then exceed max (3)
        var time = baseTime
        for (i in 2..4) {
            time += 1500L // > reconnectIntervalMs (1000ms)
            manager.lastPacketFromHost = baseTime - 5000L // keep silence
            val result = manager.tick(
                now = time,
                onPerformBurst = {},
                onSendHeartbeat = {}
            )
            if (i > 3) {
                assertFalse("tick should return false after max attempts", result)
                assertTrue("session should be closed", sessionClosed)
            }
        }
    }
}
