package com.motointercom.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Ultra-low latency, high-fidelity audio player for shared group music.
 *
 * Runs an independent AudioTrack with USAGE_MEDIA so that Android AudioFlinger
 * mixes music and voice naturally without interfering with voice intercom buffers.
 *
 * Key Protections:
 *  - Ultra-Low Latency Pipeline: AudioTrack buffer is kept lean (~100ms) with a 3-frame (60ms)
 *    pre-buffer and 8-frame (160ms) cap, eliminating noticeable playback delay.
 *  - Sequence-Aware Dynamic Resync: If a packet is lost, playback immediately jumps to the next
 *    available real frame instead of looping repetitive waveform extrapolations, completely
 *    eliminating the metallic/robotic buzz.
 *  - Linear Sample-Level Volume Ramping: Soft-saturation volume scaling eliminates pops and zipper noise.
 */
class GroupMusicPlayer {

    companion object {
        private const val TAG = "GroupMusicPlayer"
        const val MUSIC_SAMPLE_RATE = 16000 // 16kHz audio
        const val SAMPLES_PER_FRAME = 320   // 20ms frame at 16kHz
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 16-bit = 2 bytes/sample (640 bytes)

        private const val PREBUFFER_TARGET_FRAMES = 3 // 60ms initial cushion for fast, smooth music onset
        private const val MAX_LATENCY_FRAMES = 8      // 160ms max target before dropping stale frames
        private const val DUCK_RATIO = 0.20f          // Volume reduced to 20% when voice intercom is active
        private const val FADE_STEP = 0.05f           // Smooth volume interpolation per frame
    }

    private var audioTrack: AudioTrack? = null
    var isPlaying = false
        private set

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val frameMap = TreeMap<Int, ByteArray>()
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

    fun start() {
        if (isPlaying) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                MUSIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Lean buffer size (~80ms-120ms) to ensure minimal hardware playback delay
            val bufSize = maxOf(minBuf, BYTES_PER_FRAME * 4)

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
            lock.withLock {
                frameMap.clear()
                nextPlaySequence = -1
                localSequenceCounter = 0
                isBuffering = true
            }

            playbackThread = Thread({
                Log.d(TAG, "GroupMusic playback thread started (low-latency mode)")
                while (isPlaying) {
                    try {
                        var frameToWrite: ByteArray? = null

                        lock.withLock {
                            // 1. Initial pre-buffering (60ms cushion)
                            if (isBuffering) {
                                if (frameMap.size >= PREBUFFER_TARGET_FRAMES) {
                                    isBuffering = false
                                    nextPlaySequence = frameMap.firstKey()
                                } else {
                                    try {
                                        condition.await(30, TimeUnit.MILLISECONDS)
                                    } catch (_: InterruptedException) {
                                        return@Thread
                                    }
                                }
                            }

                            // 2. Playback state
                            if (!isBuffering) {
                                if (frameMap.containsKey(nextPlaySequence)) {
                                    frameToWrite = frameMap.remove(nextPlaySequence)
                                    nextPlaySequence = (nextPlaySequence + 1) and 0xFFFF
                                } else {
                                    if (frameMap.isEmpty()) {
                                        // Buffer starvation: wait briefly for incoming packets
                                        try {
                                            condition.await(25, TimeUnit.MILLISECONDS)
                                        } catch (_: InterruptedException) {
                                            return@Thread
                                        }
                                        if (frameMap.isEmpty()) {
                                            isBuffering = true
                                            nextPlaySequence = -1
                                        }
                                    } else {
                                        // A packet was missed or delayed, but newer packets are available.
                                        // Wait up to 8ms in case of small network jitter
                                        try {
                                            condition.await(8, TimeUnit.MILLISECONDS)
                                        } catch (_: InterruptedException) {
                                            return@Thread
                                        }

                                        if (frameMap.containsKey(nextPlaySequence)) {
                                            frameToWrite = frameMap.remove(nextPlaySequence)
                                            nextPlaySequence = (nextPlaySequence + 1) and 0xFFFF
                                        } else if (frameMap.isNotEmpty()) {
                                            // Skip directly to next available packet without repeating old frames
                                            val nextAvailable = frameMap.firstKey()
                                            frameToWrite = frameMap.remove(nextAvailable)
                                            nextPlaySequence = (nextAvailable + 1) and 0xFFFF
                                        }
                                    }
                                }
                            }
                        }

                        val toWrite = frameToWrite
                        if (toWrite != null) {
                            val processed = applySmoothVolume(toWrite)
                            audioTrack?.write(processed, 0, processed.size)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in music playback loop", e)
                    }
                }
                Log.d(TAG, "GroupMusic playback thread stopped")
            }, "GroupMusicPlayback").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }

            Log.d(TAG, "GroupMusicPlayer initialized successfully with bufSize=$bufSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GroupMusicPlayer", e)
        }
    }

    fun playFrame(pcmData: ByteArray, sequence: Int = -1) {
        if (!isPlaying || pcmData.isEmpty()) return
        lock.withLock {
            val seq = if (sequence >= 0) {
                sequence
            } else {
                val s = localSequenceCounter
                localSequenceCounter = (localSequenceCounter + 1) and 0xFFFF
                s
            }

            // Discard ancient packets that arrived after their playback window
            if (!isBuffering && nextPlaySequence != -1) {
                val diff = (nextPlaySequence - seq + 65536) % 65536
                if (diff in 1..32767) {
                    return
                }
            }

            frameMap[seq] = pcmData

            // Prune excess frames to keep strictly bounded latency (<160ms)
            while (frameMap.size > MAX_LATENCY_FRAMES) {
                val oldest = frameMap.firstKey()
                frameMap.remove(oldest)
                nextPlaySequence = (oldest + 1) and 0xFFFF
            }

            condition.signalAll()
        }
    }

    fun setDucked(ducked: Boolean) {
        this.isDucked = ducked
    }

    fun setMasterVolume(volume: Float) {
        this.masterVolume = volume.coerceIn(0f, 1f)
    }

    fun clear() {
        lock.withLock {
            frameMap.clear()
            nextPlaySequence = -1
            isBuffering = true
        }
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread?.join(300)
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
     * applying per-sample linear interpolation to eliminate zipper noise and clicks.
     */
    private fun applySmoothVolume(chunk: ByteArray): ByteArray {
        val targetVolume = if (isDucked) masterVolume * DUCK_RATIO else masterVolume
        val startVol = currentFadeVolume
        val endVol = if (startVol < targetVolume) {
            (startVol + FADE_STEP).coerceAtMost(targetVolume)
        } else if (startVol > targetVolume) {
            (startVol - FADE_STEP).coerceAtLeast(targetVolume)
        } else {
            targetVolume
        }
        currentFadeVolume = endVol

        val out = ByteArray(chunk.size)
        val numSamples = chunk.size / 2
        val step = if (numSamples > 1) (endVol - startVol) / (numSamples - 1) else 0f

        var sampleIdx = 0
        var i = 0
        while (i < chunk.size - 1) {
            val vol = startVol + step * sampleIdx
            val low = chunk[i].toInt() and 0xFF
            val high = chunk[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()

            val scaled = (sample.toFloat() * vol).roundToInt().coerceIn(-32768, 32767).toShort()
            out[i] = (scaled.toInt() and 0xFF).toByte()
            out[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
            sampleIdx++
        }
        return out
    }
}
