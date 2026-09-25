package com.motointercom.data.wifi

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress

import android.content.ContextWrapper

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDiscoveryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var discovery: SessionDiscovery

    @Before
    fun setUp() {
        val mockContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }

        discovery = SessionDiscovery(mockContext, testScope)
    }

    @Test
    fun `updateDiscoveredSession adds new session`() {
        val session = DiscoveredSession(
            hostName = "Carlos",
            hostIp = "192.168.43.10",
            ridersCount = 2,
            maxRiders = 4
        )

        discovery.updateDiscoveredSession(session)
        val sessions = discovery.discoveredSessions.value

        assertEquals(1, sessions.size)
        assertEquals("Carlos", sessions[0].hostName)
        assertEquals("192.168.43.10", sessions[0].hostIp)
    }

    @Test
    fun `updateDiscoveredSession updates existing session with same hostIp`() {
        val session1 = DiscoveredSession(
            hostName = "Carlos",
            hostIp = "192.168.43.10",
            ridersCount = 1
        )
        val session2 = DiscoveredSession(
            hostName = "Carlos El Veloz",
            hostIp = "192.168.43.10",
            ridersCount = 3
        )

        discovery.updateDiscoveredSession(session1)
        discovery.updateDiscoveredSession(session2)

        val sessions = discovery.discoveredSessions.value
        assertEquals(1, sessions.size)
        assertEquals("Carlos El Veloz", sessions[0].hostName)
        assertEquals(3, sessions[0].ridersCount)
    }

    @Test
    fun `handleIncomingPacket with valid beacon adds session`() {
        val payload = "MOTO_BEACON_V1|MotoGrupo|192.168.43.50|3|4".toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("192.168.43.50"), 12347)

        discovery.handleIncomingPacket(packet)

        val sessions = discovery.discoveredSessions.value
        assertEquals(1, sessions.size)
        assertEquals("MotoGrupo", sessions[0].hostName)
        assertEquals("192.168.43.50", sessions[0].hostIp)
        assertEquals(3, sessions[0].ridersCount)
        assertEquals(4, sessions[0].maxRiders)
    }

    @Test
    fun `handleIncomingPacket with invalid prefix is ignored`() {
        val payload = "UNKNOWN_PACKET|MotoGrupo|192.168.43.50|3|4".toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("192.168.43.50"), 12347)

        discovery.handleIncomingPacket(packet)

        val sessions = discovery.discoveredSessions.value
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `stopListening clears discovered sessions`() {
        val session = DiscoveredSession("Host", "192.168.43.20", 1)
        discovery.updateDiscoveredSession(session)
        assertEquals(1, discovery.discoveredSessions.value.size)

        discovery.stopListening()
        assertTrue(discovery.discoveredSessions.value.isEmpty())
    }
}
