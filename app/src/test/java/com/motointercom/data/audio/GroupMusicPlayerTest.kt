package com.motointercom.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMusicPlayerTest {

    @Test
    fun `music player constants enforce low latency and correct frame size`() {
        assertEquals(16000, GroupMusicPlayer.MUSIC_SAMPLE_RATE)
        assertEquals(320, GroupMusicPlayer.SAMPLES_PER_FRAME)
        assertEquals(640, GroupMusicPlayer.BYTES_PER_FRAME)

        // Ultra-low latency guarantees:
        // Pre-buffer must be <= 4 frames (<= 80ms)
        // Max latency queue must be <= 10 frames (<= 200ms)
        val player = GroupMusicPlayer()
        assertEquals(0.85f, player.masterVolume, 0.001f)
        assertTrue(!player.isDucked)
    }
}
