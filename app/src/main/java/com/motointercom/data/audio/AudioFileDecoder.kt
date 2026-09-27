package com.motointercom.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Decodes local audio files (MP3, AAC, WAV, M4A, OGG) using Android's MediaExtractor + MediaCodec,
 * resampling them into 16 kHz 16-bit mono PCM frames for real-time network streaming.
 */
class AudioFileDecoder(
    private val context: Context,
    private val uri: Uri
) {
    companion object {
        private const val TAG = "AudioFileDecoder"
        private const val TARGET_SAMPLE_RATE = GroupMusicPlayer.MUSIC_SAMPLE_RATE // 16000
        private const val TARGET_FRAME_BYTES = GroupMusicPlayer.BYTES_PER_FRAME    // 640
        private const val TIMEOUT_US = 10000L
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var isInitialized = false

    private var sourceSampleRate = 44100
    private var sourceChannels = 2

    private val pcmResidualBuffer = ByteArrayOutputStream()
    private var resamplePhase = 0.0
    private val resampleInputBuffer = ArrayDeque<Short>()

    fun initialize(): Boolean {
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until (extractor?.trackCount ?: 0)) {
                val format = extractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.e(TAG, "No audio track found in selected media file")
                release()
                return false
            }

            extractor!!.selectTrack(audioTrackIndex)
            sourceSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            sourceChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            isInitialized = true
            updateFilterCoefficients(sourceSampleRate)
            Log.d(TAG, "AudioFileDecoder initialized: $mime | $sourceSampleRate Hz | $sourceChannels channels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioFileDecoder for $uri", e)
            release()
            false
        }
    }

    // ── Anti-Aliasing Biquad Filter (2nd-order Butterworth Low-Pass at 7000 Hz) ──
    private var filterB0 = 1.0
    private var filterB1 = 0.0
    private var filterB2 = 0.0
    private var filterA1 = 0.0
    private var filterA2 = 0.0
    private var filterX1 = 0.0
    private var filterX2 = 0.0
    private var filterY1 = 0.0
    private var filterY2 = 0.0
    private var hasFilter = false

    private fun updateFilterCoefficients(sampleRate: Int) {
        if (sampleRate <= TARGET_SAMPLE_RATE) {
            hasFilter = false
            return
        }
        val cutoff = 7000.0 // Cut off frequencies above 7 kHz to prevent Nyquist foldback at 16 kHz
        val omega = 2.0 * Math.PI * cutoff / sampleRate.toDouble()
        val sinOmega = Math.sin(omega)
        val cosOmega = Math.cos(omega)
        val alpha = sinOmega / (2.0 * 0.70710678) // Q = 1 / sqrt(2) for maximally flat Butterworth

        val a0 = 1.0 + alpha
        filterB0 = ((1.0 - cosOmega) / 2.0) / a0
        filterB1 = (1.0 - cosOmega) / a0
        filterB2 = ((1.0 - cosOmega) / 2.0) / a0
        filterA1 = (-2.0 * cosOmega) / a0
        filterA2 = (1.0 - alpha) / a0

        filterX1 = 0.0; filterX2 = 0.0; filterY1 = 0.0; filterY2 = 0.0
        hasFilter = true
    }

    private fun applyFilter(x: Double): Double {
        if (!hasFilter) return x
        val y = filterB0 * x + filterB1 * filterX1 + filterB2 * filterX2 - filterA1 * filterY1 - filterA2 * filterY2
        filterX2 = filterX1
        filterX1 = x
        filterY2 = filterY1
        filterY1 = y
        return y
    }

    /**
     * Reads and decodes the next 20ms (640 bytes) frame of 16 kHz 16-bit mono PCM.
     * Automatically loops back to beginning if the file reaches EOF.
     */
    fun nextFrame(): ByteArray? {
        if (!isInitialized || codec == null || extractor == null) return null

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false

        while (pcmResidualBuffer.size() < TARGET_FRAME_BYTES) {
            // Feed input
            if (!sawInputEos) {
                val inputIndex = codec!!.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer? = codec!!.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor!!.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec!!.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec!!.queueInputBuffer(inputIndex, 0, sampleSize, extractor!!.sampleTime, 0)
                            extractor!!.advance()
                        }
                    }
                }
            }

            // Drain output
            val outputIndex = codec!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec!!.outputFormat
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    sourceChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                updateFilterCoefficients(sourceSampleRate)
                Log.d(TAG, "MediaCodec format changed: $sourceSampleRate Hz, $sourceChannels channels")
            } else if (outputIndex >= 0) {
                val outputBuffer: ByteBuffer? = codec!!.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)

                    val resampled = resampleTo16kMono(chunk, sourceSampleRate, sourceChannels)
                    pcmResidualBuffer.write(resampled)
                }
                codec!!.releaseOutputBuffer(outputIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // Loop playback: rewind extractor to 0 and flush codec
                    extractor!!.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec!!.flush()
                    sawInputEos = false
                }
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEos) {
                break
            }
        }

        val allBytes = pcmResidualBuffer.toByteArray()
        if (allBytes.size >= TARGET_FRAME_BYTES) {
            val frame = allBytes.copyOfRange(0, TARGET_FRAME_BYTES)
            pcmResidualBuffer.reset()
            if (allBytes.size > TARGET_FRAME_BYTES) {
                pcmResidualBuffer.write(allBytes, TARGET_FRAME_BYTES, allBytes.size - TARGET_FRAME_BYTES)
            }
            return frame
        }

        return null
    }

    fun release() {
        isInitialized = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        codec = null
        extractor = null
        pcmResidualBuffer.reset()
        resampleInputBuffer.clear()
        resamplePhase = 0.0
        filterX1 = 0.0; filterX2 = 0.0; filterY1 = 0.0; filterY2 = 0.0
    }

    /**
     * Converts raw 16-bit PCM from [srcRate] and [srcChannels] to 16,000 Hz mono
     * using Butterworth anti-aliasing filtering and high-precision fractional-phase linear interpolation.
     */
    private fun resampleTo16kMono(input: ByteArray, srcRate: Int, srcChannels: Int): ByteArray {
        val totalSrcSamples = input.size / 2
        val framesCount = totalSrcSamples / srcChannels
        if (framesCount <= 0) return ByteArray(0)

        // 1. Downmix to mono shorts, apply anti-aliasing low-pass, and enqueue
        var byteIdx = 0
        for (i in 0 until framesCount) {
            var sum = 0
            for (ch in 0 until srcChannels) {
                if (byteIdx + 1 < input.size) {
                    val low = input[byteIdx].toInt() and 0xFF
                    val high = input[byteIdx + 1].toInt() and 0xFF
                    val sample = ((high shl 8) or low).toShort()
                    sum += sample.toInt()
                    byteIdx += 2
                }
            }
            val monoSample = (sum / srcChannels).toDouble()
            val filtered = applyFilter(monoSample).roundToInt().coerceIn(-32768, 32767).toShort()
            resampleInputBuffer.add(filtered)
        }

        // If source matches target exactly, drain directly
        if (srcRate == TARGET_SAMPLE_RATE) {
            val out = ByteArray(resampleInputBuffer.size * 2)
            var oIdx = 0
            while (resampleInputBuffer.isNotEmpty()) {
                val s = resampleInputBuffer.removeFirst().toInt()
                out[oIdx++] = (s and 0xFF).toByte()
                out[oIdx++] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }

        val ratio = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val outStream = ByteArrayOutputStream()

        // Robust continuous streaming linear interpolation
        while (true) {
            val advance = resamplePhase.toInt()
            if (resampleInputBuffer.size < advance + 2) {
                // Wait for more input samples before interpolating across boundary
                break
            }

            if (advance > 0) {
                repeat(advance) {
                    resampleInputBuffer.removeFirst()
                }
                resamplePhase -= advance.toDouble()
            }

            // Guaranteed: 0.0 <= resamplePhase < 1.0 and resampleInputBuffer.size >= 2
            val s0 = resampleInputBuffer[0].toDouble()
            val s1 = resampleInputBuffer[1].toDouble()
            val interpolated = (s0 + resamplePhase * (s1 - s0)).roundToInt().coerceIn(-32768, 32767)

            outStream.write(interpolated and 0xFF)
            outStream.write((interpolated shr 8) and 0xFF)

            resamplePhase += ratio
        }

        return outStream.toByteArray()
    }
}
