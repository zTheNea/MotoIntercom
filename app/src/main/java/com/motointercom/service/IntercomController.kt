package com.motointercom.service

import android.content.Intent
import android.net.Uri
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface abstracting IntercomService operations and observable states.
 * Decouples presentation layers from Android Context/Service instances.
 */
interface IntercomController {
    val isMuted: StateFlow<Boolean>
    val isPttActive: StateFlow<Boolean>
    val amplitude: StateFlow<Float>
    val connectedClients: StateFlow<Int>
    val riders: StateFlow<List<Rider>>
    val sessionTerminated: StateFlow<Boolean>
    val reconnectionState: StateFlow<ReconnectionState>
    val musicTrack: StateFlow<String?>
    val isMusicPlaying: StateFlow<Boolean>
    val isMusicHost: StateFlow<Boolean>
    val musicSharerName: StateFlow<String?>
    val isSystemAudioActive: StateFlow<Boolean>
    val musicVolume: StateFlow<Float>
    val isSpeakerActive: StateFlow<Boolean>
    val isMultitaskingActive: StateFlow<Boolean>

    fun setMuted(muted: Boolean)
    fun setPttActive(active: Boolean)
    fun setVox(enabled: Boolean)
    fun setSpeaker(on: Boolean)
    fun playDemoMusic()
    fun playUriMusic(uri: Uri, title: String)
    fun pauseMusic()
    fun resumeMusic()
    fun stopMusic()
    fun setMusicVolume(volume: Float)
    fun setMultitasking(enabled: Boolean)
    fun startSystemAudioSharing(resultCode: Int, data: Intent)
    fun stopSystemAudioSharing()
    fun stopIntercom()
    fun resetTermination()
}
