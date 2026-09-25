package com.motointercom.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.service.IntercomService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the active session screen.
 * Binds to [IntercomService] and exposes reactive audio state.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _isMuted    = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isPttActive = MutableStateFlow(false)
    val isPttActive: StateFlow<Boolean> = _isPttActive.asStateFlow()

    private val _isVox      = MutableStateFlow(false)
    val isVox: StateFlow<Boolean> = _isVox.asStateFlow()

    private val _isSpeaker  = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _amplitude  = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _connectedCount = MutableStateFlow(1) // at least self
    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    private val _riders = MutableStateFlow<List<Rider>>(emptyList())
    val riders: StateFlow<List<Rider>> = _riders.asStateFlow()

    private val _sessionTerminated = MutableStateFlow(false)
    val sessionTerminated: StateFlow<Boolean> = _sessionTerminated.asStateFlow()

    private val _reconnectionState = MutableStateFlow(ReconnectionState())
    val reconnectionState: StateFlow<ReconnectionState> = _reconnectionState.asStateFlow()

    private val _musicTrack = MutableStateFlow<String?>(null)
    val musicTrack: StateFlow<String?> = _musicTrack.asStateFlow()

    private val _isMusicPlaying = MutableStateFlow(false)
    val isMusicPlaying: StateFlow<Boolean> = _isMusicPlaying.asStateFlow()

    private val _isMusicHost = MutableStateFlow(false)
    val isMusicHost: StateFlow<Boolean> = _isMusicHost.asStateFlow()

    private val _musicSharerName = MutableStateFlow<String?>(null)
    val musicSharerName: StateFlow<String?> = _musicSharerName.asStateFlow()

    private val _musicVolume = MutableStateFlow(0.85f)
    val musicVolume: StateFlow<Float> = _musicVolume.asStateFlow()

    private val _isMultitasking = MutableStateFlow(true)
    val isMultitasking: StateFlow<Boolean> = _isMultitasking.asStateFlow()

    private val _isSystemAudioActive = MutableStateFlow(false)
    val isSystemAudioActive: StateFlow<Boolean> = _isSystemAudioActive.asStateFlow()

    private var service: IntercomService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localService = (binder as IntercomService.LocalBinder).getService()
            service = localService
            localService.resetTermination()
            _sessionTerminated.value = false

            // Sync amplitude from service
            viewModelScope.launch {
                localService.amplitude.collectLatest { amp ->
                    _amplitude.value = amp
                }
            }
            // Sync dynamic rider count from service
            viewModelScope.launch {
                localService.connectedClients.collectLatest { count ->
                    _connectedCount.value = count
                }
            }
            // Sync remote riders roster from service
            viewModelScope.launch {
                localService.riders.collectLatest { list ->
                    _riders.value = list
                }
            }
            // Sync session termination from service
            viewModelScope.launch {
                localService.sessionTerminated.collectLatest { terminated ->
                    if (terminated) {
                        _sessionTerminated.value = true
                    }
                }
            }
            // Sync reconnection state from service
            viewModelScope.launch {
                localService.reconnectionState.collectLatest { state ->
                    _reconnectionState.value = state
                }
            }
            // Sync speaker vs headset routing state from service
            viewModelScope.launch {
                localService.isSpeakerActive.collectLatest { speakerOn ->
                    _isSpeaker.value = speakerOn
                }
            }
            // Sync music track title from service
            viewModelScope.launch {
                localService.musicTrack.collectLatest { track ->
                    _musicTrack.value = track
                }
            }
            // Sync music playback state from service
            viewModelScope.launch {
                localService.isMusicPlaying.collectLatest { playing ->
                    _isMusicPlaying.value = playing
                }
            }
            // Sync music host state from service
            viewModelScope.launch {
                localService.isMusicHost.collectLatest { host ->
                    _isMusicHost.value = host
                }
            }
            // Sync music sharer name from service
            viewModelScope.launch {
                localService.musicSharerName.collectLatest { sharer ->
                    _musicSharerName.value = sharer
                }
            }
            // Sync music volume from service
            viewModelScope.launch {
                localService.musicVolume.collectLatest { vol ->
                    _musicVolume.value = vol
                }
            }
            // Sync audio multitasking state from service
            viewModelScope.launch {
                localService.isMultitaskingActive.collectLatest { multi ->
                    _isMultitasking.value = multi
                }
            }
            // Sync system audio sharing state from service
            viewModelScope.launch {
                localService.isSystemAudioActive.collectLatest { active ->
                    _isSystemAudioActive.value = active
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun startAndBind(session: Session) {
        _sessionTerminated.value = false
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, IntercomService::class.java).apply {
            putExtra(IntercomService.EXTRA_ROLE, session.role)
            putExtra(IntercomService.EXTRA_HOST_IP, session.hostIp)
            putExtra(IntercomService.EXTRA_RIDER_NAME, session.localRiderName)
        }
        ctx.startForegroundService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stopAndUnbind() {
        val ctx = getApplication<Application>()
        service?.stopIntercom()
        try { ctx.unbindService(connection) } catch (_: Exception) {}
        val stopIntent = Intent(ctx, IntercomService::class.java).apply {
            action = IntercomService.ACTION_STOP
        }
        try { ctx.startService(stopIntent) } catch (_: Exception) {}
        ctx.stopService(Intent(ctx, IntercomService::class.java))
        service = null
        _riders.value = emptyList()
        _connectedCount.value = 1
        _sessionTerminated.value = false
        _reconnectionState.value = ReconnectionState()
    }

    // ── Audio Controls ───────────────────────────────────────────────────

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        service?.setMuted(muted)
    }

    fun setPttActive(active: Boolean) {
        _isPttActive.value = active
        service?.setPttActive(active)
    }

    fun setVox(enabled: Boolean) {
        _isVox.value = enabled
        service?.setVox(enabled)
    }

    fun setSpeaker(on: Boolean) {
        _isSpeaker.value = on
        service?.setSpeaker(on)
    }

    // ── Music & Multitasking Controls ─────────────────────────────────────

    fun playDemoMusic() {
        service?.playDemoMusic()
    }

    fun playUriMusic(uri: Uri, title: String) {
        service?.playUriMusic(uri, title)
    }

    fun togglePlayPauseMusic() {
        if (_isMusicPlaying.value) {
            service?.pauseMusic()
        } else {
            service?.resumeMusic()
        }
    }

    fun stopMusic() {
        service?.stopMusic()
    }

    fun setMusicVolume(volume: Float) {
        _musicVolume.value = volume
        service?.setMusicVolume(volume)
    }

    fun toggleMultitasking() {
        val next = !_isMultitasking.value
        _isMultitasking.value = next
        service?.setMultitasking(next)
    }

    fun startSystemAudioSharing(resultCode: Int, data: Intent) {
        service?.startSystemAudioSharing(resultCode, data)
    }

    fun stopSystemAudioSharing() {
        service?.stopSystemAudioSharing()
    }

    override fun onCleared() {
        stopAndUnbind()
        super.onCleared()
    }
}
