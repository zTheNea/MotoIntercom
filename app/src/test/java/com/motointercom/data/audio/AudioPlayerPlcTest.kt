package com.motointercom.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerPlcTest {

    private val player = AudioPlayer()

    private fun shortsToBytes(vararg shorts: Short): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shorts
    }

    @Test
    fun `generatePlcFrame applies 55 percent attenuation smoothly on first lost packet`() {
        val sourcePcm = shortsToBytes(10000, -10000, 2000, -2000)
        val plcBytes = player.generatePlcFrame(sourcePcm, 0.55f)
        val plcShorts = bytesToShorts(plcBytes)

        assertEquals(5500.toShort(), plcShorts[0])
        assertEquals((-5500).toShort(), plcShorts[1])
        assertEquals(1100.toShort(), plcShorts[2])
        assertEquals((-1100).toShort(), plcShorts[3])
    }

    @Test
    fun `generatePlcFrame applies 22 percent attenuation on second consecutive lost packet`() {
        val sourcePcm = shortsToBytes(10000, -10000, 1000)
        val plcBytes = player.generatePlcFrame(sourcePcm, 0.22f)
        val plcShorts = bytesToShorts(plcBytes)

        assertEquals(2200.toShort(), plcShorts[0])
        assertEquals((-2200).toShort(), plcShorts[1])
        assertEquals(220.toShort(), plcShorts[2])
    }

    @Test
    fun `generatePlcFrame clips safely at 16-bit boundaries`() {
        val sourcePcm = shortsToBytes(Short.MAX_VALUE, Short.MIN_VALUE)
        val plcBytes = player.generatePlcFrame(sourcePcm, 1.0f)
        val plcShorts = bytesToShorts(plcBytes)

        assertEquals(Short.MAX_VALUE, plcShorts[0])
        assertEquals(Short.MIN_VALUE, plcShorts[1])
    }
}
