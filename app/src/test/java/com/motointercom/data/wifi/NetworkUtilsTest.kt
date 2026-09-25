package com.motointercom.data.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkUtilsTest {

    @Test
    fun `intToIpString converts little-endian integer to correct dotted-quad IP`() {
        // 192.168.43.1 in little endian:
        // byte 0: 192 (0xC0), byte 1: 168 (0xA8), byte 2: 43 (0x2B), byte 3: 1 (0x01)
        // int = 192 | (168 << 8) | (43 << 16) | (1 << 24)
        val ipInt = 192 or (168 shl 8) or (43 shl 16) or (1 shl 24)

        val result = NetworkUtils.intToIpString(ipInt)
        assertEquals("192.168.43.1", result)
    }

    @Test
    fun `intToIpString converts zero to 0 0 0 0`() {
        assertEquals("0.0.0.0", NetworkUtils.intToIpString(0))
    }

    @Test
    fun `intToIpString converts local IP correctly`() {
        // 10.0.2.15
        val ipInt = 10 or (0 shl 8) or (2 shl 16) or (15 shl 24)
        assertEquals("10.0.2.15", NetworkUtils.intToIpString(ipInt))
    }

    @Test
    fun `getAllBroadcastAddresses includes standard fallbacks`() {
        val bcasts = NetworkUtils.getAllBroadcastAddresses()
        val hosts = bcasts.map { it.hostAddress }

        assertTrue(hosts.contains("192.168.43.255"))
        assertTrue(hosts.contains("192.168.49.255"))
        assertTrue(hosts.contains("255.255.255.255"))
    }

    @Test
    fun `getAllBroadcastAddresses with extraIp adds calculated subnet broadcast`() {
        val bcasts = NetworkUtils.getAllBroadcastAddresses(extraIp = "172.20.10.5")
        val hosts = bcasts.map { it.hostAddress }

        assertTrue(hosts.contains("172.20.10.255"))
    }
}
