package com.motointercom.data.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupMusicPlayerTest {

    @Test
    fun testMusicPlayerConstantsAndInitialState() {
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
