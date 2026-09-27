package com.motointercom.viewmodel

import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider

/**
 * Consolidated UI state for the active intercom session screen.
 */
data class SessionUiState(
    val isMuted: Boolean = false,
    val isPttActive: Boolean = false,
    val isVox: Boolean = false,
    val isSpeaker: Boolean = false,
    val amplitude: Float = 0f,
    val connectedCount: Int = 1,
    val riders: List<Rider> = emptyList(),
    val sessionTerminated: Boolean = false,
    val reconnectionState: ReconnectionState = ReconnectionState(),
    val musicTrack: String? = null,
    val isMusicPlaying: Boolean = false,
    val isMusicHost: Boolean = false,
    val musicSharerName: String? = null,
    val musicVolume: Float = 0.85f,
    val isMultitasking: Boolean = true,
    val isSystemAudioActive: Boolean = false
)
