package com.motointercom.data.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages real-time music streaming for the session host or rider sharing music.
 * Generates or decodes 20ms frames and dispatches them to the network and local player.
 */
class MusicStreamer(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MusicStreamer"
        const val FRAME_DURATION_MS = 20L
    }

    private var streamJob: Job? = null
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
        _currentTrack.value = "⚡ MotoBeat Highway (Demo)"
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
        streamJob?.cancel()
        streamJob = null
        decoder?.release()
        decoder = null
        demoGenerator.reset()
        _isPlaying.value = false
        _isPaused.value = false
        _currentTrack.value = null
    }

    private fun startStreamingLoop(frameSource: () -> ByteArray?) {
        streamJob = scope.launch(Dispatchers.Default) {
            var nextFrameTime = System.currentTimeMillis()

            while (isActive) {
                if (_isPaused.value) {
                    delay(50)
                    nextFrameTime = System.currentTimeMillis()
                    continue
                }

                val frame = frameSource()
                if (frame != null && frame.isNotEmpty()) {
                    onFrameProduced?.invoke(frame)
                }

                nextFrameTime += FRAME_DURATION_MS
                val sleepTime = nextFrameTime - System.currentTimeMillis()
                if (sleepTime > 0) {
                    delay(sleepTime)
                } else if (sleepTime < -100) {
                    // Reset clock if lagging significantly
                    nextFrameTime = System.currentTimeMillis()
                }
            }
        }
    }
}
