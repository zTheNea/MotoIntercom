package com.motointercom.data.wifi.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ClientRegistryTest {

    private lateinit var registry: ClientRegistry

    @Before
    fun setup() {
        registry = ClientRegistry()
    }

    // ── Registration ────────────────────────────────────────────────────

    @Test
    fun `registerOrUpdate creates new client on first call`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        val (isNew, client) = registry.registerOrUpdate("CLI1", "Rider One", address)

        assertTrue("first registration should be new", isNew)
        assertEquals("CLI1", client.id)
        assertEquals("Rider One", client.name)
        assertEquals(address, client.address)
        assertTrue(client.isConnected)
        assertEquals(1, registry.count())
    }

    @Test
    fun `registerOrUpdate updates existing client on second call`() {
        val addr1 = InetSocketAddress("192.168.43.2", 12346)
        val addr2 = InetSocketAddress("192.168.43.2", 54321)

        registry.registerOrUpdate("CLI1", "Rider One", addr1)
        val (isNew, client) = registry.registerOrUpdate("CLI1", "Rider One", addr2)

        assertFalse("second registration should NOT be new", isNew)
        assertEquals(addr2, client.address)
        assertEquals(1, registry.count())
    }

    @Test
    fun `registerOrUpdate uses fallback name for blank rider name`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        val (_, client) = registry.registerOrUpdate("CLI1", "", address)
        assertEquals("Rider", client.name)
    }

    @Test
    fun `multiple clients are tracked independently`() {
        val addr1 = InetSocketAddress("192.168.43.2", 12346)
        val addr2 = InetSocketAddress("192.168.43.3", 12346)

        registry.registerOrUpdate("CLI1", "Rider 1", addr1)
        registry.registerOrUpdate("CLI2", "Rider 2", addr2)

        assertEquals(2, registry.count())
        assertNotNull(registry.get("CLI1"))
        assertNotNull(registry.get("CLI2"))
    }

    // ── Removal ─────────────────────────────────────────────────────────

    @Test
    fun `remove returns the removed client`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "Rider One", address)

        val removed = registry.remove("CLI1")
        assertNotNull(removed)
        assertEquals("CLI1", removed?.id)
        assertEquals(0, registry.count())
    }

    @Test
    fun `remove returns null for unknown client`() {
        val removed = registry.remove("UNKNOWN")
        assertNull(removed)
    }

    @Test
    fun `clear removes all clients`() {
        val addr1 = InetSocketAddress("192.168.43.2", 12346)
        val addr2 = InetSocketAddress("192.168.43.3", 12346)
        registry.registerOrUpdate("CLI1", "Rider 1", addr1)
        registry.registerOrUpdate("CLI2", "Rider 2", addr2)

        registry.clear()
        assertEquals(0, registry.count())
    }

    // ── Sweep (timeout logic) ───────────────────────────────────────────

    @Test
    fun `sweep marks client as disconnected after inactivity threshold`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "Rider One", address)

        val client = registry.get("CLI1")!!
        // Set lastSeen to 5 seconds ago (above CLIENT_INACTIVE_THRESHOLD_MS = 4500)
        client.lastSeen = System.currentTimeMillis() - 5000L

        val leftClients = mutableListOf<String>()
        val changed = registry.sweep(System.currentTimeMillis()) { leftClients.add(it) }

        assertTrue("roster should have changed", changed)
        assertFalse("client should be marked disconnected", client.isConnected)
        assertTrue("client should NOT be evicted yet", leftClients.isEmpty())
        assertEquals(1, registry.count()) // still registered
    }

    @Test
    fun `sweep evicts client after eviction threshold`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "Rider One", address)

        val client = registry.get("CLI1")!!
        // Set lastSeen to 16 seconds ago (above CLIENT_EVICTION_THRESHOLD_MS = 15000)
        client.lastSeen = System.currentTimeMillis() - 16000L

        val leftClients = mutableListOf<String>()
        val changed = registry.sweep(System.currentTimeMillis()) { leftClients.add(it) }

        assertTrue("roster should have changed", changed)
        assertEquals(listOf("CLI1"), leftClients)
        assertEquals(0, registry.count())
    }

    @Test
    fun `sweep resets talking flag after 350ms silence`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "Rider One", address)

        val client = registry.get("CLI1")!!
        client.isTalking = true
        client.lastSeen = System.currentTimeMillis() - 400L // 400ms ago

        registry.sweep(System.currentTimeMillis()) {}
        assertFalse("talking should be reset after 350ms silence", client.isTalking)
    }

    @Test
    fun `sweep returns false when nothing changed`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "Rider One", address)

        // Client is fresh, lastSeen is now
        val changed = registry.sweep(System.currentTimeMillis()) {}
        assertFalse("nothing should change for a fresh client", changed)
    }

    // ── Rider Mapping ───────────────────────────────────────────────────

    @Test
    fun `getRemoteRiders maps clients to domain Rider model`() {
        val addr1 = InetSocketAddress("192.168.43.2", 12346)
        val addr2 = InetSocketAddress("192.168.43.3", 12346)
        registry.registerOrUpdate("CLI1", "Rider 1", addr1)
        registry.registerOrUpdate("CLI2", "Rider 2", addr2)

        val riders = registry.getRemoteRiders()
        assertEquals(2, riders.size)

        val rider1 = riders.find { it.id == "CLI1" }
        assertNotNull(rider1)
        assertEquals("Rider 1", rider1?.name)
        assertFalse(rider1!!.isHost)
        assertTrue(rider1.isConnected)
    }

    @Test
    fun `getRemoteRiders uses Rider fallback for blank names`() {
        val address = InetSocketAddress("192.168.43.2", 12346)
        registry.registerOrUpdate("CLI1", "", address)

        val riders = registry.getRemoteRiders()
        assertEquals("Rider", riders[0].name)
    }
}
