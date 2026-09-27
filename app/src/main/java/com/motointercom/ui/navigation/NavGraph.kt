package com.motointercom.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.motointercom.domain.model.SessionState
import com.motointercom.ui.screens.HomeScreen
import com.motointercom.ui.screens.SessionScreen
import com.motointercom.viewmodel.HomeViewModel
import com.motointercom.viewmodel.SessionViewModel

object Routes {
    const val HOME = "home"
    const val SESSION = "session"
}

@Composable
fun NavGraph(navController: NavHostController) {
    val homeVm: HomeViewModel = hiltViewModel()
    val sessionVm: SessionViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val session by homeVm.session.collectAsState()
            val error by homeVm.errorMessage.collectAsState()
            val discoveredSessions by homeVm.discoveredSessions.collectAsState()
            val updateInfo by homeVm.updateInfo.collectAsState()

            HomeScreen(
                session = session,
                errorMessage = error,
                discoveredSessions = discoveredSessions,
                updateInfo = updateInfo,
                initialRiderName = homeVm.savedRiderName,
                onDismissUpdate = { homeVm.dismissUpdate() },
                onCreateSession = { name -> homeVm.createSession(name) },
                onJoinSession = { name, ip -> homeVm.joinSession(name, ip) },
                onClearError = { homeVm.clearError() },
                onNavigateToSession = {
                    val s = homeVm.session.value
                    if (s.state == SessionState.ACTIVE) {
                        sessionVm.startAndBind(s)
                        navController.navigate(Routes.SESSION) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(Routes.SESSION) {
            val session by homeVm.session.collectAsState()
            val localIp by homeVm.localIp.collectAsState()
            val uiState by sessionVm.uiState.collectAsState()

            LaunchedEffect(uiState.sessionTerminated) {
                if (uiState.sessionTerminated) {
                    val wasReconnecting = uiState.reconnectionState.attempt >= uiState.reconnectionState.maxAttempts
                    sessionVm.stopAndUnbind()
                    homeVm.endSession()
                    if (wasReconnecting) {
                        homeVm.setErrorMessage("Se perdió la conexión con la sala tras varios intentos de reconexión.")
                    }
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
            }

            SessionScreen(
                session = session,
                localIp = localIp,
                riders = uiState.riders,
                reconnectionState = uiState.reconnectionState,
                isMuted = uiState.isMuted,
                isPttActive = uiState.isPttActive,
                isVox = uiState.isVox,
                isSpeaker = uiState.isSpeaker,
                connectedCount = uiState.connectedCount,
                musicTrack = uiState.musicTrack,
                musicSharerName = uiState.musicSharerName,
                isMusicPlaying = uiState.isMusicPlaying,
                isMusicHost = uiState.isMusicHost,
                musicVolume = uiState.musicVolume,
                isMultitasking = uiState.isMultitasking,
                isSystemAudioActive = uiState.isSystemAudioActive,
                onMuteToggle = { sessionVm.setMuted(!uiState.isMuted) },
                onPttDown = { sessionVm.setPttActive(true) },
                onPttUp = { sessionVm.setPttActive(false) },
                onVoxToggle = { sessionVm.setVox(!uiState.isVox) },
                onSpeakerToggle = { sessionVm.setSpeaker(!uiState.isSpeaker) },
                onPlayDemoMusic = { sessionVm.playDemoMusic() },
                onPlayUriMusic = { uri, title -> sessionVm.playUriMusic(uri, title) },
                onStartSystemAudio = { resultCode, data -> sessionVm.startSystemAudioSharing(resultCode, data) },
                onStopSystemAudio = { sessionVm.stopSystemAudioSharing() },
                onTogglePlayPauseMusic = { sessionVm.togglePlayPauseMusic() },
                onStopMusic = { sessionVm.stopMusic() },
                onSetMusicVolume = { vol -> sessionVm.setMusicVolume(vol) },
                onToggleMultitasking = { sessionVm.toggleMultitasking() },
                onEndSession = {
                    sessionVm.stopAndUnbind()
                    homeVm.endSession()
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
            )
        }
    }
}
