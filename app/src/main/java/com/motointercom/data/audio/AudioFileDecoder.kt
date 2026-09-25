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
            sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
            sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            isInitialized = true
            Log.d(TAG, "AudioFileDecoder initialized: $mime | $sourceSampleRate Hz | $sourceChannels channels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioFileDecoder for $uri", e)
            release()
            false
        }
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
            if (outputIndex >= 0) {
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
    }

    /**
     * Converts raw 16-bit PCM from [srcRate] and [srcChannels] to 16,000 Hz mono.
     */
    private fun resampleTo16kMono(input: ByteArray, srcRate: Int, srcChannels: Int): ByteArray {
        val totalSrcSamples = input.size / 2
        val framesCount = totalSrcSamples / srcChannels
        if (framesCount <= 0) return ByteArray(0)

        // 1. Downmix to mono shorts
        val monoShorts = ShortArray(framesCount)
        var byteIdx = 0
        for (i in 0 until framesCount) {
            var sum = 0
            for (ch in 0 until srcChannels) {
                if (byteIdx + 1 < input.size) {
                    val sample = ((input[byteIdx].toInt() and 0xFF) or (input[byteIdx + 1].toInt() shl 8)).toShort()
                    sum += sample.toInt()
                    byteIdx += 2
                }
            }
            monoShorts[i] = (sum / srcChannels).toShort()
        }

        // 2. Resample to 16,000 Hz via linear interpolation
        if (srcRate == TARGET_SAMPLE_RATE) {
            val out = ByteArray(framesCount * 2)
            for (i in 0 until framesCount) {
                val s = monoShorts[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }

        val ratio = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val targetFrames = (framesCount / ratio).roundToInt().coerceAtLeast(1)
        val out = ByteArray(targetFrames * 2)

        for (i in 0 until targetFrames) {
            val srcPos = i * ratio
            val index0 = srcPos.toInt().coerceIn(0, framesCount - 1)
            val index1 = (index0 + 1).coerceIn(0, framesCount - 1)
            val frac = srcPos - index0

            val sample0 = monoShorts[index0].toDouble()
            val sample1 = monoShorts[index1].toDouble()
            val interpolated = (sample0 + (sample1 - sample0) * frac).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            out[i * 2] = (interpolated and 0xFF).toByte()
            out[i * 2 + 1] = ((interpolated shr 8) and 0xFF).toByte()
        }

        return out
    }
}
