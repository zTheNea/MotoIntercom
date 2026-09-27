package com.motointercom.data.wifi

import com.motointercom.data.wifi.transport.RemoteClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.InetSocketAddress

class UdpAudioTransportTest {

    @Test
    fun `voice and music channels use dedicated distinct UDP ports`() {
        assertEquals(12346, UdpAudioTransport.VOICE_PORT)
        assertEquals(12348, UdpAudioTransport.MUSIC_PORT)
        assertEquals(UdpAudioTransport.VOICE_PORT, UdpAudioTransport.AUDIO_PORT)
        assertNotEquals(UdpAudioTransport.VOICE_PORT, UdpAudioTransport.MUSIC_PORT)
    }

    @Test
    fun `remote client defaults to music port and allows dynamic port update`() {
        val client = RemoteClient(
            id = "c123",
            name = "Rider Test",
            address = InetSocketAddress("192.168.43.50", 12346)
        )
        assertEquals(UdpAudioTransport.MUSIC_PORT, client.musicPort)
        client.musicPort = 55432
        assertEquals(55432, client.musicPort)
    }
}
