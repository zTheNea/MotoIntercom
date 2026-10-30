package com.motointercom.ui.screens.session.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.ui.theme.GreenActive
import kotlinx.coroutines.delay

@Composable
fun RainLockOverlay(
    isTalking: Boolean,
    isSunMode: Boolean,
    pttScale: Float,
    pulseAlpha: Float,
    pulseScale: Float,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var unlockProgress by remember { mutableFloatStateOf(0f) }
    var isHoldingUnlock by remember { mutableStateOf(false) }

    LaunchedEffect(isHoldingUnlock) {
        if (isHoldingUnlock) {
            val startTime = System.currentTimeMillis()
            val holdDuration = 1500L // 1.5s continuous intentional hold to reject raindrops
            while (isHoldingUnlock) {
                val elapsed = System.currentTimeMillis() - startTime
                unlockProgress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                if (unlockProgress >= 1f) {
                    onUnlock()
                    break
                }
                delay(16)
            }
        } else {
            unlockProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF0080B14))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .pointerInput(Unit) {
                // Intercept all phantom taps from rain drops across the entire screen
                detectTapGestures { }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: Rain Shield Alert Banner ───────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B2238)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "MODO LLUVIA ACTIVO",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "Pantalla blindada contra gotas de lluvia. PTT habilitado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            // ── Center: High-Priority Rain PTT Button ───────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PttButton(
                    isTalking = isTalking,
                    scale = pttScale,
                    pulseAlpha = pulseAlpha,
                    pulseScale = pulseScale,
                    onDown = onPttDown,
                    onUp = onPttUp,
                    isSunMode = isSunMode
                )

                Text(
                    if (isTalking) "TRANSMITIENDO..." else "PULSA Y HABLA (PTT)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = if (isTalking) GreenActive else Color.White,
                    letterSpacing = 2.sp
                )

                Text(
                    "Operable con guantes mojados",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }

            // ── Bottom: Secure Rain Unlock Button (Hold 1.5s) ───────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E2235))
                        .border(
                            width = 2.dp,
                            color = if (isHoldingUnlock) Color(0xFF00E5FF) else Color(0xFF424B63),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isHoldingUnlock = true
                                    tryAwaitRelease()
                                    isHoldingUnlock = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Fill bar progress as rider holds down the unlock button
                    if (unlockProgress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(unlockProgress)
                                .align(Alignment.CenterStart)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.35f))
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isHoldingUnlock) {
                            CircularProgressIndicator(
                                progress = { unlockProgress },
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 3.dp,
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0x3300E5FF)
                            )
                        } else {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            if (isHoldingUnlock) "DESBLOQUEANDO..." else "MANTÉN 1.5s PARA DESBLOQUEAR",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isHoldingUnlock) Color(0xFF00E5FF) else Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Text(
                    "Evita toques fantasma causados por salpicaduras o lluvia",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 10.5.sp
                )
            }
        }
    }
}
