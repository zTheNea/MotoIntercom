package com.motointercom.data.wifi.transport

import com.motointercom.data.crypto.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class PacketCodecTest {

    private val testToken = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val localId = "HOS1"

    @Test
    fun `padId pads shorter strings to 4 bytes`() {
        val padded = PacketCodec.padId("A")
        assertEquals(4, padded.size)
        assertEquals('A'.code.toByte(), padded[0])
        assertEquals(0.toByte(), padded[1])
        assertEquals(0.toByte(), padded[2])
        assertEquals(0.toByte(), padded[3])
    }

    @Test
    fun `padId truncates longer strings to 4 bytes`() {
        val padded = PacketCodec.padId("ABCDEFG")
        assertEquals(4, padded.size)
        assertEquals("ABCD", String(padded, Charsets.UTF_8))
    }

    @Test
    fun `buildLocalId returns 4-character string from allowed charset`() {
        val id = PacketCodec.buildLocalId()
        assertEquals(4, id.length)
        assertTrue(id.all { it.isLetterOrDigit() })
    }

    @Test
    fun `buildHelloPacket encodes header and name correctly`() {
        val name = "Carlos"
        val packet = PacketCodec.buildHelloPacket(testToken, localId, name)

        // 2 (magic) + 1 (type) + 4 (token) + 4 (id) + 2 (nameLen) + 6 (name) = 19
        assertEquals(19, packet.size)
        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_HELLO, packet[2])

        val token = packet.copyOfRange(3, 7)
        assertArrayEquals(testToken, token)

        val id = String(packet, 7, 4, Charsets.UTF_8)
        assertEquals(localId, id)

        val nameLen = ByteBuffer.wrap(packet, 11, 2).short.toInt()
        assertEquals(6, nameLen)

        val extractedName = String(packet, 13, nameLen, Charsets.UTF_8)
        assertEquals(name, extractedName)
    }

    @Test
    fun `buildHeartbeatPacket encodes 11-byte header`() {
        val packet = PacketCodec.buildHeartbeatPacket(testToken, localId)
        assertEquals(11, packet.size)
        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_HEARTBEAT, packet[2])
        assertArrayEquals(testToken, packet.copyOfRange(3, 7))
        assertEquals(localId, String(packet, 7, 4, Charsets.UTF_8))
    }

    @Test
    fun `buildByePacket encodes 11-byte header`() {
        val packet = PacketCodec.buildByePacket(testToken, localId)
        assertEquals(11, packet.size)
        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_BYE, packet[2])
    }

    @Test
    fun `buildAudioPacket creates valid audio packet with encryption`() {
        val key = SessionCrypto.generateSessionKey()
        val originalPcm = ByteArray(320) { (it % 127).toByte() }
        val sequence = 42

        val packet = PacketCodec.buildAudioPacket(
            sequence = sequence,
            sessionToken = testToken,
            localId = localId,
            pcm = originalPcm,
            amplitude = 0.5f,
            encryptionKey = key
        )

        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_AUDIO, packet[2])
        assertArrayEquals(testToken, packet.copyOfRange(3, 7))
        assertEquals(localId, String(packet, 7, 4, Charsets.UTF_8))

        val seq = ByteBuffer.wrap(packet, 11, 2).short.toInt()
        assertEquals(sequence, seq)

        val payloadLen = ByteBuffer.wrap(packet, 13, 2).short.toInt()
        val ampByte = packet[15].toInt() and 0xFF
        assertEquals(50, ampByte) // 0.5 * 100

        // Encrypted payload is IV (16) + PCM (320) = 336 bytes
        assertEquals(336, payloadLen)

        val encryptedPayload = packet.copyOfRange(16, 16 + payloadLen)
        val decrypted = SessionCrypto.decrypt(encryptedPayload, key)
        assertArrayEquals(originalPcm, decrypted)
    }

    @Test
    fun `buildRosterPacket and parseRosterPacket roundtrip preserves riders`() {
        val clients = listOf(
            RemoteClient(
                id = "CLI1",
                name = "Rider Uno",
                address = InetSocketAddress("192.168.43.2", 12346),
                isTalking = true,
                amplitude = 0.75f,
                isConnected = true
            ),
            RemoteClient(
                id = "CLI2",
                name = "Rider Dos",
                address = InetSocketAddress("192.168.43.3", 12346),
                isTalking = false,
                amplitude = 0.0f,
                isConnected = true
            )
        )

        val rosterPacket = PacketCodec.buildRosterPacket(
            sessionToken = testToken,
            localId = "HOST",
            localRiderName = "Host Rider",
            clients = clients
        )

        assertEquals(PacketCodec.MSG_ROSTER, rosterPacket[2])

        // When a client parses the roster packet, it excludes its own ID
        val parsedByCli1 = PacketCodec.parseRosterPacket(rosterPacket, rosterPacket.size, localId = "CLI1")
        assertNotNull(parsedByCli1)
        assertArrayEquals(testToken, parsedByCli1?.sessionToken)

        // Should contain HOST and CLI2
        assertEquals(2, parsedByCli1!!.riders.size)

        val hostRider = parsedByCli1.riders.find { it.id == "HOST" }
        assertNotNull(hostRider)
        assertEquals("Host Rider", hostRider?.name)
        assertTrue(hostRider?.isHost == true)

        val cli2Rider = parsedByCli1.riders.find { it.id == "CLI2" }
        assertNotNull(cli2Rider)
        assertEquals("Rider Dos", cli2Rider?.name)
        assertTrue(cli2Rider?.isHost == false)
    }

    @Test
    fun `buildRosterPacket with encryptionKey transmits key and recovers it via parseRosterPacket`() {
        val encKey = SessionCrypto.generateSessionKey()
        val rosterPacket = PacketCodec.buildRosterPacket(
            sessionToken = testToken,
            localId = "HOST",
            localRiderName = "Host Rider",
            clients = emptyList(),
            encryptionKey = encKey
        )

        val parsed = PacketCodec.parseRosterPacket(rosterPacket, rosterPacket.size, localId = "CLI1")
        assertNotNull(parsed)
        assertArrayEquals(testToken, parsed?.sessionToken)
        assertNotNull(parsed?.encryptionKey)
        assertArrayEquals(encKey.encoded, parsed?.encryptionKey?.encoded)
        assertEquals(1, parsed!!.riders.size)
        assertEquals("HOST", parsed.riders[0].id)
    }

    @Test
    fun `buildMusicFramePacket and parseMusicFramePacket roundtrip accurately`() {
        val testPcm = ByteArray(640) { (it % 100).toByte() }
        val seq = 1234
        val sampleRate = 16000

        val packet = PacketCodec.buildMusicFramePacket(
            sequence = seq,
            sessionToken = testToken,
            localId = localId,
            pcm = testPcm,
            sampleRate = sampleRate
        )

        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_MUSIC_FRAME, packet[2])

        val parsed = PacketCodec.parseMusicFramePacket(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(localId, parsed?.senderId)
        assertEquals(seq, parsed?.sequence)
        assertEquals(sampleRate, parsed?.sampleRate)
        assertArrayEquals(testPcm, parsed?.pcm)
    }

    @Test
    fun `buildMusicCtrlPacket and parseMusicCtrlPacket roundtrip accurately`() {
        val action = PacketCodec.MUSIC_ACTION_PLAY
        val title = "Highway To Hell - AC/DC"

        val packet = PacketCodec.buildMusicCtrlPacket(
            sessionToken = testToken,
            localId = localId,
            action = action,
            trackTitle = title
        )

        assertEquals(PacketCodec.MAGIC_0, packet[0])
        assertEquals(PacketCodec.MAGIC_1, packet[1])
        assertEquals(PacketCodec.MSG_MUSIC_CTRL, packet[2])

        val parsed = PacketCodec.parseMusicCtrlPacket(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(localId, parsed?.senderId)
        assertEquals(action, parsed?.action)
        assertEquals(title, parsed?.trackTitle)
    }
}

