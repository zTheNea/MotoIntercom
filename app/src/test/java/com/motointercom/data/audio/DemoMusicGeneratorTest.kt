package com.motointercom.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DemoMusicGeneratorTest {

    @Test
    fun `nextFrame generates valid 640 byte PCM frames without clipping`() {
        val generator = DemoMusicGenerator()
        var maxAmplitude = 0

        // Test over 50 consecutive frames (1 second of synthesized audio)
        for (f in 0 until 50) {
            val frame = generator.nextFrame()
            assertEquals(GroupMusicPlayer.BYTES_PER_FRAME, frame.size)

            var i = 0
            while (i < frame.size - 1) {
                val low = frame[i].toInt() and 0xFF
                val high = frame[i + 1].toInt() and 0xFF
                val sample = ((high shl 8) or low).toShort()
                val amp = abs(sample.toInt())
                if (amp > maxAmplitude) {
                    maxAmplitude = amp
                }
                i += 2
            }
        }

        // Verify audio is actively playing (non-zero)
        assertTrue("Max amplitude should be significant", maxAmplitude > 5000)
        // Verify audio has safe headroom and does not hard clip at 32767
        assertTrue("Max amplitude should not exceed safe headroom (30000)", maxAmplitude <= 30000)
    }
}
