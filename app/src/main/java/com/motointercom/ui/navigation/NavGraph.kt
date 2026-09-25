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
            val isCheckingUpdate by homeVm.isCheckingUpdate.collectAsState()
            val updateStatusMessage by homeVm.updateStatusMessage.collectAsState()

            HomeScreen(
                session = session,
                errorMessage = error,
                discoveredSessions = discoveredSessions,
                updateInfo = updateInfo,
                isCheckingUpdate = isCheckingUpdate,
                updateStatusMessage = updateStatusMessage,
                onCheckForUpdates = { homeVm.checkForUpdates(isManual = true) },
                onDismissUpdate = { homeVm.dismissUpdate() },
                onClearUpdateStatusMessage = { homeVm.clearUpdateStatusMessage() },
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
            val riders by sessionVm.riders.collectAsState()
            val isMuted by sessionVm.isMuted.collectAsState()
            val isPttActive by sessionVm.isPttActive.collectAsState()
            val isVox by sessionVm.isVox.collectAsState()
            val isSpeaker by sessionVm.isSpeaker.collectAsState()
            val connectedCount by sessionVm.connectedCount.collectAsState()
            val sessionTerminated by sessionVm.sessionTerminated.collectAsState()
            val reconnectionState by sessionVm.reconnectionState.collectAsState()
            val musicTrack by sessionVm.musicTrack.collectAsState()
            val musicSharerName by sessionVm.musicSharerName.collectAsState()
            val isMusicPlaying by sessionVm.isMusicPlaying.collectAsState()
            val isMusicHost by sessionVm.isMusicHost.collectAsState()
            val musicVolume by sessionVm.musicVolume.collectAsState()
            val isMultitasking by sessionVm.isMultitasking.collectAsState()
            val isSystemAudioActive by sessionVm.isSystemAudioActive.collectAsState()

            LaunchedEffect(sessionTerminated) {
                if (sessionTerminated) {
                    val wasReconnecting = sessionVm.reconnectionState.value.attempt >= sessionVm.reconnectionState.value.maxAttempts
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
                riders = riders,
                reconnectionState = reconnectionState,
                isMuted = isMuted,
                isPttActive = isPttActive,
                isVox = isVox,
                isSpeaker = isSpeaker,
                connectedCount = connectedCount,
                musicTrack = musicTrack,
                musicSharerName = musicSharerName,
                isMusicPlaying = isMusicPlaying,
                isMusicHost = isMusicHost,
                musicVolume = musicVolume,
                isMultitasking = isMultitasking,
                isSystemAudioActive = isSystemAudioActive,
                onMuteToggle = { sessionVm.setMuted(!isMuted) },
                onPttDown = { sessionVm.setPttActive(true) },
                onPttUp = { sessionVm.setPttActive(false) },
                onVoxToggle = { sessionVm.setVox(!isVox) },
                onSpeakerToggle = { sessionVm.setSpeaker(!isSpeaker) },
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
