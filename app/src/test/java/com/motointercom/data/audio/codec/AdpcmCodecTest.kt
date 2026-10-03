package com.motointercom.data.audio.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AdpcmCodecTest {

    @Test
    fun testFrameDimensions() {
        assertEquals(320, AdpcmCodec.SAMPLES_PER_FRAME)
        assertEquals(640, AdpcmCodec.PCM_FRAME_BYTES)
        assertEquals(164, AdpcmCodec.ADPCM_FRAME_BYTES)
    }

    @Test
    fun testEncodeSineWaveProducesExactCompressedSize() {
        val pcm = generateSineWave(440.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)
        assertEquals(AdpcmCodec.PCM_FRAME_BYTES, pcm.size)

        val adpcm = AdpcmCodec.encode(pcm)
        assertEquals(AdpcmCodec.ADPCM_FRAME_BYTES, adpcm.size)
        assertTrue(AdpcmCodec.isAdpcmFrame(adpcm))
    }

    @Test
    fun testDecodeReconstructsOriginalWaveformFidelity() {
        val pcm = generateSineWave(440.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)
        val adpcm = AdpcmCodec.encode(pcm)
        val decoded = AdpcmCodec.decode(adpcm)

        assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decoded.size)

        // Compare original vs decoded PCM samples (measure mean error)
        var maxDiff = 0
        var totalDiff = 0L
        for (i in 0 until AdpcmCodec.SAMPLES_PER_FRAME) {
            val origSample = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val decSample = ((decoded[i * 2].toInt() and 0xFF) or (decoded[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val diff = abs(origSample - decSample)
            if (diff > maxDiff) maxDiff = diff
            totalDiff += diff
        }

        val avgDiff = totalDiff.toDouble() / AdpcmCodec.SAMPLES_PER_FRAME
        // 4-bit ADPCM should have average quantization difference well under 5% of full-scale (32768 * 0.05 = ~1600)
        assertTrue("Average error too high: $avgDiff", avgDiff < 800.0)
    }

    @Test
    fun testSilenceEncodeDecode() {
        val silence = ByteArray(AdpcmCodec.PCM_FRAME_BYTES)
        val adpcm = AdpcmCodec.encode(silence)
        assertEquals(AdpcmCodec.ADPCM_FRAME_BYTES, adpcm.size)

        val decoded = AdpcmCodec.decode(adpcm)
        assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decoded.size)

        // All samples should be within near-zero quantization step
        for (i in 0 until AdpcmCodec.SAMPLES_PER_FRAME) {
            val sample = ((decoded[i * 2].toInt() and 0xFF) or (decoded[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            assertTrue("Expected near-silence, got $sample", abs(sample) < 50)
        }
    }

    @Test
    fun testAutonomousFrameResilienceAgainstPacketLoss() {
        // Frame 1
        val frame1 = generateSineWave(300.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)
        // Frame 2
        val frame2 = generateSineWave(800.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)

        // Simulate Frame 1 lost in transit: only Frame 2 arrives at receiver
        val adpcm2 = AdpcmCodec.encode(frame2)
        val decoded2 = AdpcmCodec.decode(adpcm2)

        assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decoded2.size)

        // Verify Frame 2 decoded cleanly without needing Frame 1 state
        var totalDiff = 0L
        for (i in 0 until AdpcmCodec.SAMPLES_PER_FRAME) {
            val origSample = ((frame2[i * 2].toInt() and 0xFF) or (frame2[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val decSample = ((decoded2[i * 2].toInt() and 0xFF) or (decoded2[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            totalDiff += abs(origSample - decSample)
        }
        val avgDiff = totalDiff.toDouble() / AdpcmCodec.SAMPLES_PER_FRAME
        assertTrue("Packet loss recovery error too high: $avgDiff", avgDiff < 800.0)
    }

    @Test
    fun testInPlaceZeroAllocationEncoding() {
        val pcm = generateSineWave(500.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)
        val adpcmOut = ByteArray(AdpcmCodec.ADPCM_FRAME_BYTES)
        val pcmOut = ByteArray(AdpcmCodec.PCM_FRAME_BYTES)

        val encBytes = AdpcmCodec.encode(pcm, 0, pcm.size, adpcmOut, 0)
        assertEquals(AdpcmCodec.ADPCM_FRAME_BYTES, encBytes)

        val decBytes = AdpcmCodec.decode(adpcmOut, 0, encBytes, pcmOut, 0)
        assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decBytes)
    }

    @Test
    fun testIsAdpcmFrameIdentification() {
        val validAdpcm = ByteArray(AdpcmCodec.ADPCM_FRAME_BYTES).apply {
            this[3] = AdpcmCodec.CODEC_IDENTIFIER
        }
        assertTrue(AdpcmCodec.isAdpcmFrame(validAdpcm))

        // Raw PCM is 640 bytes
        val rawPcm = ByteArray(640)
        assertFalse(AdpcmCodec.isAdpcmFrame(rawPcm))

        // Wrong identifier
        val wrongId = ByteArray(AdpcmCodec.ADPCM_FRAME_BYTES).apply {
            this[3] = 0x99.toByte()
        }
        assertFalse(AdpcmCodec.isAdpcmFrame(wrongId))
    }

    @Test
    fun testStreamingEncoderContinuousSpeech() {
        val encoder = AdpcmCodec.Encoder()
        val frameCount = 5
        val sampleRate = 16000
        val freq = 440.0
        val totalSamples = frameCount * AdpcmCodec.SAMPLES_PER_FRAME
        val fullWave = ByteArray(totalSamples * 2)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (sin(2.0 * Math.PI * freq * t) * 18000.0).toInt().coerceIn(-32768, 32767)
            fullWave[i * 2] = (sample and 0xFF).toByte()
            fullWave[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        // Encode each frame sequentially with streaming encoder
        var totalDiff = 0L
        for (f in 0 until frameCount) {
            val offset = f * AdpcmCodec.PCM_FRAME_BYTES
            val adpcm = encoder.encode(fullWave, offset, AdpcmCodec.PCM_FRAME_BYTES)
            assertEquals(AdpcmCodec.ADPCM_FRAME_BYTES, adpcm.size)
            assertTrue(AdpcmCodec.isAdpcmFrame(adpcm))

            // Decode independently (stateless per-packet decode)
            val decoded = AdpcmCodec.decode(adpcm)
            assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decoded.size)

            for (s in 0 until AdpcmCodec.SAMPLES_PER_FRAME) {
                val origSample = ((fullWave[offset + s * 2].toInt() and 0xFF) or (fullWave[offset + s * 2 + 1].toInt() shl 8)).toShort().toInt()
                val decSample = ((decoded[s * 2].toInt() and 0xFF) or (decoded[s * 2 + 1].toInt() shl 8)).toShort().toInt()
                totalDiff += abs(origSample - decSample)
            }
        }

        val avgDiff = totalDiff.toDouble() / totalSamples
        assertTrue("Streaming encoder average error too high: $avgDiff", avgDiff < 600.0)
    }

    @Test
    fun testStreamingEncoderReset() {
        val encoder = AdpcmCodec.Encoder()
        val pcm = generateSineWave(440.0, 16000, AdpcmCodec.SAMPLES_PER_FRAME)

        val frame1 = encoder.encode(pcm)
        encoder.reset()
        val frameAfterReset = encoder.encode(pcm)

        // After reset, re-encoding the same initial frame should produce identical bytes
        assertTrue(frame1.contentEquals(frameAfterReset))
    }

    private fun generateSineWave(freq: Double, sampleRate: Int, samplesCount: Int): ByteArray {
        val out = ByteArray(samplesCount * 2)
        val amplitude = 18000.0 // Moderate speech level
        for (i in 0 until samplesCount) {
            val t = i.toDouble() / sampleRate
            val sample = (sin(2.0 * Math.PI * freq * t) * amplitude).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }
}
