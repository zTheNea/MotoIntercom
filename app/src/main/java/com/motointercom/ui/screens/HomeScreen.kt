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

import com.motointercom.data.wifi.DiscoveredSession
import androidx.compose.ui.platform.LocalContext
import com.motointercom.data.updater.ApkDownloader
import com.motointercom.data.updater.UpdateInfo

@Composable
fun HomeScreen(
    session: Session,
    errorMessage: String?,
    discoveredSessions: List<DiscoveredSession> = emptyList(),
    updateInfo: UpdateInfo? = null,
    isCheckingUpdate: Boolean = false,
    updateStatusMessage: String? = null,
    onCheckForUpdates: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onClearUpdateStatusMessage: () -> Unit = {},
    onCreateSession: (name: String) -> Unit,
    onJoinSession: (name: String, ip: String) -> Unit,
    onClearError: () -> Unit,
    onNavigateToSession: () -> Unit
) {
    // Navigate to session when it becomes active
    LaunchedEffect(session.state) {
        if (session.state == SessionState.ACTIVE) onNavigateToSession()
    }

    var riderName by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) }  // 0=crear, 1=unirse

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

            Spacer(Modifier.height(14.dp))

            // ── App Version & Updates ───────────────────────────────────
            val context = LocalContext.current
            val currentVersion = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.0"
                } catch (_: Exception) {
                    "1.1.0"
                }
            }

            AppUpdateCard(
                currentVersion = currentVersion,
                isChecking = isCheckingUpdate,
                statusMessage = updateStatusMessage,
                onCheckForUpdates = onCheckForUpdates,
                onClearStatusMessage = onClearUpdateStatusMessage
            )

            Spacer(Modifier.height(24.dp))
        }

        // ── Update Available Dialog ──────────────────────────────────────
        if (updateInfo != null && updateInfo.isUpdateAvailable) {
            UpdateAvailableDialog(
                updateInfo = updateInfo,
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

// ── Subcomposables ────────────────────────────────────────────────────────────

@Composable
private fun MotoLogo() {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    OrangeFlame.copy(alpha = 0.12f * glowAlpha),
                    CircleShape
                )
        )
        // Inner circle
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(OrangeHot, OrangeDim)
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.TwoWheeler,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            "MOTO ",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                brush = Brush.horizontalGradient(listOf(OrangeFlame, AmberGlow))
            ),
            letterSpacing = 2.sp
        )
        Text(
            "INTERCOM",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            letterSpacing = 4.sp
        )
    }
}

@Composable
private fun TabSelector(activeTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BackgroundCard)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("🏁  CREAR GRUPO", "📡  UNIRSE").forEachIndexed { index, label ->
            val isSelected = activeTab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected)
                            Brush.horizontalGradient(listOf(OrangeFlame, OrangeHot))
                        else
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.White else TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CreateSessionContent(
    isLoading: Boolean,
    onCreateSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BackgroundCard)
            .border(1.dp, DividerColor, RoundedCornerShape(18.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Router, null, tint = OrangeFlame, modifier = Modifier.size(32.dp))
        Text(
            "Crear Punto de Acceso",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )
        Text(
            "Tu teléfono creará una red WiFi local para el grupo, sin necesidad de conexión a internet ni datos móviles.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        // 3 Feature chips in a horizontal responsive row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "🔴 Offline" to "Sin internet",
                "📶 300m" to "Alcance WiFi",
                "👥 4 Motos" to "Voz en vivo"
            ).forEach { (title, subtitle) ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundElevated)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        Button(
            onClick = onCreateSession,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(listOf(OrangeFlame, AmberGlow)),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiTethering, null, tint = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "CREAR GRUPO",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinSessionContent(
    discoveredSessions: List<DiscoveredSession>,
    onJoinSession: (hostIp: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BackgroundCard)
            .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.WifiFind, null, tint = AmberGlow, modifier = Modifier.size(40.dp))
        Text(
            "Unirse a un Grupo",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // ── Auto-Discovered Sessions ─────────────────────────────────────
        if (discoveredSessions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotAlpha by rememberInfiniteTransition(label = "pulse_radar").animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "radar_alpha"
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(GreenActive.copy(alpha = dotAlpha), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SESIONES ACTIVAS DETECTADAS (${discoveredSessions.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = GreenActive,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            discoveredSessions.forEach { discovered ->
                DiscoveredSessionCard(
                    discovered = discovered,
                    onJoin = {
                        onJoinSession(discovered.hostIp)
                    }
                )
            }
        } else {
            // Radar pulse search banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BackgroundElevated)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AmberGlow,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Escaneando sesiones activas en tu red WiFi...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun DiscoveredSessionCard(
    discovered: DiscoveredSession,
    onJoin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BackgroundElevated)
            .border(1.dp, GreenActive.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TwoWheeler, null, tint = GreenActive, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        discovered.hostName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "IP: ${discovered.hostIp}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberGlow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GreenDim)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "${discovered.ridersCount}/${discovered.maxRiders} Motos",
                    style = MaterialTheme.typography.labelSmall,
                    color = GreenActive,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Button(
            onClick = onJoin,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(listOf(GreenActive, Color(0xFF00B0FF))),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Login, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "UNIRSE CON 1 TOQUE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


@Composable
private fun MotoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        placeholder = { Text(placeholder, color = TextMuted) },
        leadingIcon = { Icon(icon, null, tint = OrangeFlame) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = OrangeFlame,
            unfocusedBorderColor = DividerColor,
            cursorColor = OrangeFlame,
            focusedContainerColor = BackgroundElevated,
            unfocusedContainerColor = BackgroundCard
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

@Composable
private fun InfoCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundCard)
            .border(1.dp, OrangeGlass, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, null, tint = AmberGlow, modifier = Modifier.size(28.dp))
        Text(
            "Conecta tu casco Bluetooth antes de iniciar para que el audio se enrute automáticamente.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun AppUpdateCard(
    currentVersion: String,
    isChecking: Boolean,
    statusMessage: String?,
    onCheckForUpdates: () -> Unit,
    onClearStatusMessage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundCard)
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "VERSION DE LA APP",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    "v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = OrangeFlame,
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OrangeFlame.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeFlame),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "BUSCAR ACTUALIZACIONES",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BackgroundElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onClearStatusMessage,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("CERRAR", style = MaterialTheme.typography.labelSmall, color = OrangeFlame)
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "ACTUALIZACION DISPONIBLE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeFlame
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Nueva version: v${updateInfo.latestVersion}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    "Version instalada: v${updateInfo.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (updateInfo.apkSize > 0) {
                    val sizeMb = updateInfo.apkSize / (1024f * 1024f)
                    Text(
                        "Tamaño del APK: ${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Novedades:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberGlow,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundCard)
                            .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            updateInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = updateInfo.apkUrl ?: updateInfo.releaseUrl
                    ApkDownloader.downloadApk(context, url, updateInfo.latestVersion)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeFlame),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("DESCARGAR APK", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("MAS TARDE", color = TextSecondary)
            }
        },
        containerColor = BackgroundElevated,
        shape = RoundedCornerShape(16.dp)
    )
}
