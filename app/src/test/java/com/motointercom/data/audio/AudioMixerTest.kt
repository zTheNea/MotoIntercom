package com.motointercom.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioMixerTest {

    private lateinit var mixer: AudioMixer

    @Before
    fun setUp() {
        mixer = AudioMixer()
    }

    private fun shortsToBytes(vararg shorts: Short): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `mix with no streams returns empty byte array`() {
        val mixed = mixer.mix()
        assertEquals(0, mixed.size)
    }

    @Test
    fun `mix with single stream returns exact same audio`() {
        val pcm = shortsToBytes(100, 200, -300, 1500)
        mixer.submitFrame("rider1", pcm)

        val mixed = mixer.mix()
        assertArrayEquals(pcm, mixed)
    }

    @Test
    fun `mix with two streams sums samples accurately`() {
        val pcm1 = shortsToBytes(1000, 2000, -500)
        val pcm2 = shortsToBytes(500, -1000, 200)

        mixer.submitFrame("rider1", pcm1)
        mixer.submitFrame("rider2", pcm2)

        val expected = shortsToBytes(1500, 1000, -300)
        val mixed = mixer.mix()

        assertArrayEquals(expected, mixed)
    }

    @Test
    fun `mix clips symmetrically on overflow`() {
        val pcm1 = shortsToBytes(30000, -30000)
        val pcm2 = shortsToBytes(10000, -10000)

        mixer.submitFrame("rider1", pcm1)
        mixer.submitFrame("rider2", pcm2)

        // 30000 + 10000 = 40000 -> clipped to Short.MAX_VALUE (32767)
        // -30000 + -10000 = -40000 -> clipped to Short.MIN_VALUE (-32768)
        val expected = shortsToBytes(Short.MAX_VALUE, Short.MIN_VALUE)
        val mixed = mixer.mix()

        assertArrayEquals(expected, mixed)
    }

    @Test
    fun `mix with excludeId skips that stream`() {
        val pcmHost = shortsToBytes(1000, 1000)
        val pcmClient = shortsToBytes(500, 500)

        mixer.submitFrame("host", pcmHost)
        mixer.submitFrame("client1", pcmClient)

        val mixedExcludingHost = mixer.mix(excludeId = "host")
        assertArrayEquals(pcmClient, mixedExcludingHost)
    }

    @Test
    fun `mix drains active streams so subsequent mix is empty`() {
        val pcm = shortsToBytes(100, 200)
        mixer.submitFrame("rider1", pcm)

        val firstMix = mixer.mix()
        assertTrue(firstMix.isNotEmpty())

        val secondMix = mixer.mix()
        assertEquals(0, secondMix.size)
    }

    @Test
    fun `removeStream and clear work properly`() {
        mixer.submitFrame("r1", shortsToBytes(10))
        mixer.submitFrame("r2", shortsToBytes(20))
        assertEquals(2, mixer.activeStreamCount())

        mixer.removeStream("r1")
        assertEquals(1, mixer.activeStreamCount())

        mixer.clear()
        assertEquals(0, mixer.activeStreamCount())
    }
}
