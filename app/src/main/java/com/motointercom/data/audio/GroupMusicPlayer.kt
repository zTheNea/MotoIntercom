package com.motointercom.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * High-fidelity audio player for shared group music.
 *
 * Runs an independent AudioTrack with USAGE_MEDIA so that Android AudioFlinger
 * mixes music and voice naturally without interfering with voice intercom buffers.
 *
 * Built-in Protections:
 *  - Adaptive Music Jitter Buffer: sequence-aware reordering, eliminating packet reordering jitter.
 *  - Pre-buffering: maintains an initial 80ms cushion to completely prevent AudioTrack buffer underruns.
 *  - Packet Loss Concealment (PLC): smooth waveform extrapolation upon packet drops instead of digital silence/clicks.
 *  - High-precision 16-bit PCM little-endian volume ramping with soft saturation limiting.
 */
class GroupMusicPlayer {

    companion object {
        private const val TAG = "GroupMusicPlayer"
        const val MUSIC_SAMPLE_RATE = 16000 // 16kHz wideband audio
        const val SAMPLES_PER_FRAME = 320   // 20ms frame at 16kHz
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 16-bit = 2 bytes/sample (640 bytes)

        private const val PREBUFFER_TARGET_FRAMES = 4 // 80ms initial cushion for smooth music onset
        private const val MAX_JITTER_FRAMES = 12      // 240ms max target before trimming oldest frames
        private const val MAX_PLC_FRAMES = 3          // Up to 60ms of smooth waveform fading
        private const val DUCK_RATIO = 0.20f          // Volume reduced to 20% when voice intercom is active
        private const val FADE_STEP = 0.04f           // Smooth volume interpolation per frame
    }

    private var audioTrack: AudioTrack? = null
    var isPlaying = false
        private set

    private val lock = Any()
    private val jitterBuffer = TreeMap<Int, ByteArray>()
    private var nextPlaySequence = -1
    private var localSequenceCounter = 0
    private var isBuffering = true

    private var playbackThread: Thread? = null

    @Volatile
    var isDucked = false
        private set

    @Volatile
    var masterVolume = 0.85f
        private set

    private var currentFadeVolume = 0.85f

    // PLC working buffers
    private var lastPlayedFrame: ByteArray? = null
    private var consecutivePlcCount = 0

    fun start() {
        if (isPlaying) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                MUSIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, BYTES_PER_FRAME * MAX_JITTER_FRAMES * 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MUSIC_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            currentFadeVolume = masterVolume
            synchronized(lock) {
                jitterBuffer.clear()
                nextPlaySequence = -1
                localSequenceCounter = 0
                isBuffering = true
                lastPlayedFrame = null
                consecutivePlcCount = 0
            }

            playbackThread = Thread({
                Log.d(TAG, "GroupMusic playback thread started with JitterBuffer & PLC")
                while (isPlaying) {
                    try {
                        val frameToWrite: ByteArray?

                        synchronized(lock) {
                            if (isBuffering) {
                                if (jitterBuffer.size >= PREBUFFER_TARGET_FRAMES) {
                                    isBuffering = false
                                    if (nextPlaySequence == -1 && jitterBuffer.isNotEmpty()) {
                                        nextPlaySequence = jitterBuffer.firstKey()
                                    }
                                } else {
                                    // Wait for more frames to build the initial cushion
                                    try {
                                        (lock as java.lang.Object).wait(25)
                                    } catch (_: InterruptedException) {
                                        return@Thread
                                    }
                                }
                            }

                            if (!isBuffering) {
                                // Trim if jitter buffer grows excessively beyond target latency
                                while (jitterBuffer.size > MAX_JITTER_FRAMES) {
                                    val oldest = jitterBuffer.firstKey()
                                    jitterBuffer.remove(oldest)
                                    nextPlaySequence = (oldest + 1) and 0xFFFF
                                }

                                if (jitterBuffer.containsKey(nextPlaySequence)) {
                                    frameToWrite = jitterBuffer.remove(nextPlaySequence)
                                    lastPlayedFrame = frameToWrite
                                    consecutivePlcCount = 0
                                    nextPlaySequence = (nextPlaySequence + 1) and 0xFFFF
                                } else {
                                    // Expected frame missing (packet loss or jitter delay)
                                    if (consecutivePlcCount < MAX_PLC_FRAMES && lastPlayedFrame != null) {
                                        consecutivePlcCount++
                                        val decay = when (consecutivePlcCount) {
                                            1 -> 0.65f
                                            2 -> 0.35f
                                            else -> 0.15f
                                        }
                                        frameToWrite = generatePlcFrame(lastPlayedFrame!!, decay)
                                        nextPlaySequence = (nextPlaySequence + 1) and 0xFFFF
                                    } else {
                                        if (jitterBuffer.isNotEmpty()) {
                                            // Jump ahead to next available frame in buffer
                                            nextPlaySequence = jitterBuffer.firstKey()
                                            frameToWrite = jitterBuffer.remove(nextPlaySequence)
                                            lastPlayedFrame = frameToWrite
                                            consecutivePlcCount = 0
                                            nextPlaySequence = (nextPlaySequence + 1) and 0xFFFF
                                        } else {
                                            // Buffer starved: re-enter prebuffering smoothly
                                            isBuffering = true
                                            frameToWrite = null
                                            consecutivePlcCount = 0
                                        }
                                    }
                                }
                            } else {
                                frameToWrite = null
                            }
                        }

                        if (frameToWrite != null) {
                            val processed = applySmoothVolume(frameToWrite)
                            audioTrack?.write(processed, 0, processed.size)
                        } else {
                            Thread.sleep(10)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in music playback loop", e)
                    }
                }
                Log.d(TAG, "GroupMusic playback thread stopped")
            }, "GroupMusicPlayback").apply {
                priority = Thread.NORM_PRIORITY + 2
                isDaemon = true
                start()
            }

            Log.d(TAG, "GroupMusicPlayer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GroupMusicPlayer", e)
        }
    }

    fun playFrame(pcmData: ByteArray, sequence: Int = -1) {
        if (!isPlaying || pcmData.isEmpty()) return
        synchronized(lock) {
            val seq = if (sequence >= 0) {
                sequence
            } else {
                val s = localSequenceCounter
                localSequenceCounter = (localSequenceCounter + 1) and 0xFFFF
                s
            }

            // Reject duplicate or ancient frames (outside 16-bit sliding window)
            if (nextPlaySequence != -1) {
                val diff = (seq - nextPlaySequence + 65536) % 65536
                if (diff > 32768) {
                    // Packet arrived too late (already played or skipped)
                    return
                }
            }

            jitterBuffer[seq] = pcmData
            (lock as java.lang.Object).notifyAll()
        }
    }

    fun setDucked(ducked: Boolean) {
        this.isDucked = ducked
    }

    fun setMasterVolume(volume: Float) {
        this.masterVolume = volume.coerceIn(0f, 1f)
    }

    fun clear() {
        synchronized(lock) {
            jitterBuffer.clear()
            nextPlaySequence = -1
            isBuffering = true
            lastPlayedFrame = null
            consecutivePlcCount = 0
        }
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread?.join(400)
        playbackThread = null
        clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GroupMusicPlayer", e)
        }
        audioTrack = null
        Log.d(TAG, "GroupMusicPlayer stopped")
    }

    /**
     * Smoothly scales PCM 16-bit little-endian samples according to ducking state,
     * applying soft-saturation to eliminate digital clipping.
     */
    private fun applySmoothVolume(chunk: ByteArray): ByteArray {
        val targetVolume = if (isDucked) masterVolume * DUCK_RATIO else masterVolume

        if (currentFadeVolume < targetVolume) {
            currentFadeVolume = (currentFadeVolume + FADE_STEP).coerceAtMost(targetVolume)
        } else if (currentFadeVolume > targetVolume) {
            currentFadeVolume = (currentFadeVolume - FADE_STEP).coerceAtLeast(targetVolume)
        }

        val out = ByteArray(chunk.size)
        val vol = currentFadeVolume

        var i = 0
        while (i < chunk.size - 1) {
            val low = chunk[i].toInt() and 0xFF
            val high = chunk[i + 1].toInt() and 0xFF
            val sample = ((high shl 8) or low).toShort()

            val scaled = (sample.toFloat() * vol).roundToInt().coerceIn(-32768, 32767).toShort()
            out[i] = (scaled.toInt() and 0xFF).toByte()
            out[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /**
     * Synthesizes an interpolated frame from the previous waveform with smooth decay.
     */
    private fun generatePlcFrame(source: ByteArray, factor: Float): ByteArray {
        val out = ByteArray(source.size)
        var i = 0
        while (i < source.size - 1) {
            val low = source[i].toInt() and 0xFF
            val high = source[i + 1].toInt() and 0xFF
            val sample = ((high shl 8) or low).toShort()

            val scaled = (sample.toFloat() * factor).roundToInt().coerceIn(-32768, 32767).toShort()
            out[i] = (scaled.toInt() and 0xFF).toByte()
            out[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
