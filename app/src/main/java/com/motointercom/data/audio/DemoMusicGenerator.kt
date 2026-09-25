package com.motointercom.data.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Built-in audio synthesizer generating an upbeat rhythmic highway rock/synth theme.
 *
 * Provides a ready-to-test 16 kHz 16-bit PCM stream with clean headroom
 * and zero harmonic clipping.
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
            val chordIndex = ((t / 1.0).toInt()) % bassNotes.size

            // 1. Kick Drum (sine sweep with smooth quadratic decay)
            val kick = if (beatTime < 0.15) {
                val decay = 1.0 - beatTime / 0.15
                val kickFreq = 110.0 * decay + 45.0
                sin(TWO_PI * kickFreq * beatTime) * decay * decay * 0.28
            } else 0.0

            // 2. Hi-Hat on off-beats with smooth decay
            val hihatTime = (t + 0.25) % 0.5
            val hihat = if (hihatTime < 0.035) {
                val decay = 1.0 - hihatTime / 0.035
                sin(TWO_PI * 3200.0 * hihatTime) * decay * decay * 0.08
            } else 0.0

            // 3. Bass synth (clean fundamental + mild 2nd harmonic)
            val bassFreq = bassNotes[chordIndex]
            val bass = sin(TWO_PI * bassFreq * t) * 0.20 +
                       sin(TWO_PI * bassFreq * 2.0 * t) * 0.06

            // 4. Arpeggiator lead (changes every 0.125s) with smooth decay
            val arpIndex = ((t / 0.125).toInt()) % arpeggio.size
            val arpFreq = arpeggio[arpIndex]
            val arpTime = t % 0.125
            val arpDecay = (1.0 - arpTime / 0.125).coerceAtLeast(0.0)
            val lead = (sin(TWO_PI * arpFreq * t) + 0.3 * sin(TWO_PI * arpFreq * 2.0 * t)) * arpDecay * 0.14

            // Total mix safely within headroom (peak ~0.65)
            val total = (kick + hihat + bass + lead).coerceIn(-0.85, 0.85)
            val sampleShort = (total * 28000.0).toInt().coerceIn(-32768, 32767).toShort()

            val byteOffset = i * 2
            out[byteOffset] = (sampleShort.toInt() and 0xFF).toByte()
            out[byteOffset + 1] = ((sampleShort.toInt() shr 8) and 0xFF).toByte()
        }

        sampleIndex += FRAME_SIZE
        return out
    }
}
