package com.motointercom.data.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Built-in audio synthesizer generating an upbeat rhythmic motorcycle highway rock/synth theme.
 *
 * Provides a ready-to-test 16 kHz 16-bit PCM stream without requiring the user to have
 * audio/MP3 files stored on their device.
 */
class DemoMusicGenerator {

    companion object {
        private const val SAMPLE_RATE = GroupMusicPlayer.MUSIC_SAMPLE_RATE // 16000 Hz
        private const val FRAME_SIZE = GroupMusicPlayer.SAMPLES_PER_FRAME  // 320 samples (20ms)
        private const val TWO_PI = 2.0 * PI
    }

    private var sampleIndex = 0L

    // Chord progression in Hz (Root notes)
    private val bassNotes = listOf(110.0, 130.81, 146.83, 164.81) // A2, C3, D3, E3
    private val arpeggio = listOf(220.0, 261.63, 329.63, 440.0, 523.25, 440.0, 329.63, 261.63)

    fun reset() {
        sampleIndex = 0L
    }

    /**
     * Generates a single 20ms frame of 16-bit mono PCM audio (640 bytes).
     */
    fun nextFrame(): ByteArray {
        val out = ByteArray(FRAME_SIZE * 2)

        for (i in 0 until FRAME_SIZE) {
            val t = (sampleIndex + i).toDouble() / SAMPLE_RATE

            // 120 BPM = 2 beats per second (beat period = 0.5s)
            val beatTime = t % 0.5
            val barTime = t % 4.0 // 4 beat bar (2 seconds)
            val chordIndex = ((t / 1.0).toInt()) % bassNotes.size

            // 1. Kick Drum (low frequency sine sweeping down + fast decay)
            val kick = if (beatTime < 0.15) {
                val kickFreq = 120.0 * (1.0 - beatTime / 0.15) + 45.0
                sin(TWO_PI * kickFreq * beatTime) * (1.0 - beatTime / 0.15) * 0.45
            } else 0.0

            // 2. Snare / Hi-Hat on off-beats
            val hihatTime = (t + 0.25) % 0.5
            val hihat = if (hihatTime < 0.04) {
                // High frequency metallic click
                sin(TWO_PI * 3400.0 * hihatTime) * (1.0 - hihatTime / 0.04) * 0.15
            } else 0.0

            // 3. Bass synth
            val bassFreq = bassNotes[chordIndex]
            val bass = sin(TWO_PI * bassFreq * t) * 0.30 +
                       sin(TWO_PI * bassFreq * 2.0 * t) * 0.15

            // 4. Arpeggiator lead (changes every 0.125s)
            val arpIndex = ((t / 0.125).toInt()) % arpeggio.size
            val arpFreq = arpeggio[arpIndex]
            val arpTime = t % 0.125
            val arpDecay = (1.0 - arpTime / 0.125).coerceAtLeast(0.0)
            val lead = (sin(TWO_PI * arpFreq * t) + 0.5 * sin(TWO_PI * arpFreq * 2.0 * t)) * arpDecay * 0.22

            // Total mix (normalized to [-1.0, 1.0])
            val total = (kick + hihat + bass + lead).coerceIn(-0.95, 0.95)
            val sampleShort = (total * Short.MAX_VALUE).toInt().toShort()

            val byteOffset = i * 2
            out[byteOffset] = (sampleShort.toInt() and 0xFF).toByte()
            out[byteOffset + 1] = ((sampleShort.toInt() shr 8) and 0xFF).toByte()
        }

        sampleIndex += FRAME_SIZE
        return out
    }
}
