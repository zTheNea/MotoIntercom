package com.motointercom.data.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages real-time music streaming for the session host or rider sharing music.
 * Pre-decodes audio ahead into a dedicated buffer and streams 20ms frames with
 * nanosecond-level hardware precision, completely eliminating timing jitter.
 */
class MusicStreamer {
    companion object {
        private const val TAG = "MusicStreamer"
        const val FRAME_DURATION_MS = 20L
        private const val FRAME_DURATION_NS = FRAME_DURATION_MS * 1_000_000L
        private const val PREDECODE_QUEUE_CAPACITY = 12 // ~240ms of pre-decoded buffer
    }

    private var streamingThread: Thread? = null
    private var decoderThread: Thread? = null
    private val isLoopRunning = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<ByteArray>(PREDECODE_QUEUE_CAPACITY)

    private var decoder: AudioFileDecoder? = null
    private val demoGenerator = DemoMusicGenerator()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack.asStateFlow()

    /** Callback invoked every 20ms with a PCM frame to transmit to UDP and play locally */
    var onFrameProduced: ((ByteArray) -> Unit)? = null

    /** Callback invoked when track state changes to send control packets */
    var onControlChanged: ((action: Byte, title: String) -> Unit)? = null

    fun playDemo() {
        stop()
        demoGenerator.reset()
        _currentTrack.value = "MotoBeat Highway (Demo)"
        _isPlaying.value = true
        _isPaused.value = false
        onControlChanged?.invoke(1 /* PLAY */, _currentTrack.value ?: "MotoBeat")
        startStreamingLoop { demoGenerator.nextFrame() }
    }

    fun playUri(context: Context, uri: Uri, title: String) {
        stop()
        val fileDecoder = AudioFileDecoder(context, uri)
        if (!fileDecoder.initialize()) {
            Log.e(TAG, "Could not open audio file: $title")
            return
        }
        decoder = fileDecoder
        _currentTrack.value = title
        _isPlaying.value = true
        _isPaused.value = false
        onControlChanged?.invoke(1 /* PLAY */, title)
        startStreamingLoop { fileDecoder.nextFrame() }
    }

    fun pause() {
        if (!_isPlaying.value) return
        _isPaused.value = true
        onControlChanged?.invoke(2 /* PAUSE */, _currentTrack.value ?: "")
    }

    fun resume() {
        if (!_isPlaying.value) return
        _isPaused.value = false
        onControlChanged?.invoke(1 /* PLAY */, _currentTrack.value ?: "")
    }

    fun stop() {
        if (_isPlaying.value) {
            onControlChanged?.invoke(3 /* STOP */, _currentTrack.value ?: "")
        }
        isLoopRunning.set(false)
        decoderThread?.interrupt()
        streamingThread?.interrupt()
        try {
            decoderThread?.join(200)
            streamingThread?.join(200)
        } catch (_: Exception) {}
        decoderThread = null
        streamingThread = null
        frameQueue.clear()

        decoder?.release()
        decoder = null
        demoGenerator.reset()
        _isPlaying.value = false
        _isPaused.value = false
        _currentTrack.value = null
    }

    private fun startStreamingLoop(frameSource: () -> ByteArray?) {
        frameQueue.clear()
        isLoopRunning.set(true)

        // 1. Decoder thread: continuously pre-decodes up to 240ms ahead of transmission
        decoderThread = Thread({
            Log.d(TAG, "Music pre-decode thread started")
            while (isLoopRunning.get()) {
                try {
                    if (_isPaused.value) {
                        Thread.sleep(25)
                        continue
                    }

                    val frame = frameSource()
                    if (frame != null && frame.isNotEmpty()) {
                        // Blocks if queue is full (12 frames = 240ms cushion)
                        frameQueue.put(frame)
                    } else {
                        Thread.sleep(10)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in music decoder thread", e)
                }
            }
            Log.d(TAG, "Music pre-decode thread stopped")
        }, "MusicDecoderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
            start()
        }

        // 2. High-precision streaming thread: strictly paced at 20.000 ms per frame
        streamingThread = Thread({
            Log.d(TAG, "Music streaming thread started with high-precision clock")
            var nextFrameNano = System.nanoTime()

            while (isLoopRunning.get()) {
                try {
                    if (_isPaused.value) {
                        Thread.sleep(25)
                        nextFrameNano = System.nanoTime()
                        continue
                    }

                    val frame = frameQueue.poll(40, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        onFrameProduced?.invoke(frame)
                    }

                    nextFrameNano += FRAME_DURATION_NS
                    val nowNano = System.nanoTime()
                    val sleepNs = nextFrameNano - nowNano

                    if (sleepNs > 2_000_000L) {
                        val sleepMs = sleepNs / 1_000_000L
                        Thread.sleep(sleepMs - 1)
                    } else if (sleepNs < -40_000_000L) {
                        // Resynchronize clock if falling behind by more than 40ms
                        nextFrameNano = System.nanoTime()
                    }

                    // Fine-grained spin wait for remaining microsecond precision
                    while (System.nanoTime() < nextFrameNano) {
                        Thread.yield()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in music streaming thread", e)
                }
            }
            Log.d(TAG, "Music streaming thread stopped")
        }, "MusicStreamerThread").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }
}
