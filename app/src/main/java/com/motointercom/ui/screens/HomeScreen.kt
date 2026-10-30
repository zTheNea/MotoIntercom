package com.motointercom.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.motointercom.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionState
import com.motointercom.ui.theme.*

import androidx.lifecycle.compose.LifecycleResumeEffect
import com.motointercom.data.wifi.DiscoveredSession
import androidx.compose.ui.platform.LocalContext
import com.motointercom.data.updater.ApkDownloader
import com.motointercom.data.updater.UpdateDownloadProgress
import com.motointercom.data.updater.UpdateInfo
import com.motointercom.ui.screens.home.components.*

@Composable
fun HomeScreen(
    session: Session,
    errorMessage: String?,
    discoveredSessions: List<DiscoveredSession> = emptyList(),
    updateInfo: UpdateInfo? = null,
    downloadProgress: UpdateDownloadProgress = UpdateDownloadProgress(),
    initialRiderName: String = "",
    onDismissUpdate: () -> Unit = {},
    onStartDownload: (url: String, version: String) -> Unit = { _, _ -> },
    onCancelDownload: () -> Unit = {},
    onInstallApk: () -> Unit = {},
    onCheckInstallPermission: () -> Unit = {},
    onCreateSession: (name: String) -> Unit,
    onJoinSession: (name: String, ip: String) -> Unit,
    onClearError: () -> Unit,
    onNavigateToSession: () -> Unit
) {
    LifecycleResumeEffect(Unit) {
        onCheckInstallPermission()
        onPauseOrDispose { }
    }

    // Navigate to session when it becomes active
    LaunchedEffect(session.state) {
        if (session.state == SessionState.ACTIVE) onNavigateToSession()
    }

    var riderName by remember(initialRiderName) { mutableStateOf(initialRiderName) }
    var activeTab by remember { mutableIntStateOf(0) }  // 0=crear, 1=unirse

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
    ) {
        // Background glow effect
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-60).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(OrangeFlame.copy(alpha = 0.15f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(10.dp))

            // ── Logo & Brand ────────────────────────────────────────────
            MotoLogo()

            Spacer(Modifier.height(6.dp))

            Text(
                stringResource(R.string.app_tagline).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(16.dp))

            // ── Tab Selector ────────────────────────────────────────────
            TabSelector(activeTab) { activeTab = it }

            Spacer(Modifier.height(14.dp))

            // ── Name Field (shared) ─────────────────────────────────────
            MotoTextField(
                value = riderName,
                onValueChange = { riderName = it },
                label = stringResource(R.string.your_name),
                icon = Icons.Default.Person,
                placeholder = stringResource(R.string.rider_name_placeholder)
            )

            Spacer(Modifier.height(14.dp))

            // ── Tab Content ─────────────────────────────────────────────
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    slideInHorizontally { if (targetState == 0) -it else it } togetherWith
                    slideOutHorizontally { if (targetState == 0) it else -it }
                }
            ) { tab ->
                if (tab == 0) {
                    CreateSessionContent(
                        isLoading = session.state == SessionState.CREATING,
                        onCreateSession = { onCreateSession(riderName.ifBlank { "Rider" }) }
                    )
                } else {
                    JoinSessionContent(
                        discoveredSessions = discoveredSessions,
                        onJoinSession = { ip ->
                            onJoinSession(
                                riderName.ifBlank { "Rider" },
                                ip
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Info Footer ─────────────────────────────────────────────
            InfoCard()

            Spacer(Modifier.height(24.dp))
        }

        // ── Update Available Dialog ──────────────────────────────────────
        if (updateInfo != null && (updateInfo.isUpdateAvailable || downloadProgress.status != UpdateDownloadProgress.Status.IDLE)) {
            UpdateAvailableDialog(
                updateInfo = updateInfo,
                downloadProgress = downloadProgress,
                onStartDownload = onStartDownload,
                onCancelDownload = onCancelDownload,
                onInstallApk = onInstallApk,
                onDismiss = onDismissUpdate
            )
        }

        // ── Error Snackbar ──────────────────────────────────────────────
        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                action = {
                    TextButton(onClick = onClearError) {
                        Text("OK", color = OrangeFlame)
                    }
                },
                containerColor = BackgroundElevated,
                contentColor = TextPrimary
            ) { Text(msg) }
        }
    }
}


