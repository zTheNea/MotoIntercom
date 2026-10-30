package com.motointercom.ui.screens.session.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.SessionRole
import com.motointercom.ui.theme.*

@Composable
fun SessionHeader(
    role: SessionRole,
    hostIp: String,
    localIp: String,
    connectedCount: Int,
    maxRiders: Int,
    reconnectionState: ReconnectionState,
    isSunMode: Boolean,
    onToggleSunMode: () -> Unit,
    onToggleRainLock: () -> Unit,
    onEnd: () -> Unit,
    containerColor: Color = BackgroundCard,
    borderColor: Color = DividerColor,
    borderWidth: Dp = 1.dp,
    textColor: Color = TextPrimary,
    textSecColor: Color = TextSecondary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            val isReconnecting = reconnectionState.isReconnecting
            val dotAlpha by rememberInfiniteTransition(label = "dot").animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(if (isReconnecting) 350 else 600),
                    RepeatMode.Reverse
                ),
                label = "dot_alpha"
            )
            val dotColor = if (isReconnecting) AmberGlow else if (role == SessionRole.HOST) OrangeFlame else GreenActive
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = dotAlpha), CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = when {
                        isReconnecting -> "RECONECTANDO..."
                        role == SessionRole.HOST -> "ANFITRIÓN"
                        else -> "CONECTADO"
                    }
                    val statusColor = when {
                        isReconnecting -> AmberGlow
                        role == SessionRole.HOST -> if (isSunMode) Color(0xFFD84315) else OrangeFlame
                        else -> if (isSunMode) Color(0xFF1B5E20) else GreenActive
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSunMode) FontWeight.Black else FontWeight.Bold,
                        color = statusColor,
                        letterSpacing = 1.sp
                    )
                }
                if (role == SessionRole.HOST) {
                    if (hostIp.isNotBlank()) {
                        Text(
                            "IP sala: $hostIp",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSunMode) Color(0xFFB78103) else AmberGlow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSunMode) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                } else {
                    Text(
                        "Host: $hostIp • Tu IP: ${localIp.ifBlank { "..." }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textSecColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "$connectedCount / $maxRiders motociclistas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Action Buttons Row: Sun Mode, Rain Lock, End Session
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Outdoor Sun Mode Toggle (High Contrast)
            IconButton(
                onClick = onToggleSunMode,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isSunMode) Color(0xFFFFD54F) else BackgroundElevated)
                    .border(1.dp, if (isSunMode) Color(0xFFFFA000) else borderColor, CircleShape)
            ) {
                Icon(
                    Icons.Default.WbSunny,
                    contentDescription = "Modo Sol",
                    tint = if (isSunMode) Color(0xFFBF360C) else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Water / Rain Lock Toggle
            IconButton(
                onClick = onToggleRainLock,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(BackgroundElevated)
                    .border(1.dp, borderColor, CircleShape)
            ) {
                Icon(
                    Icons.Default.WaterDrop,
                    contentDescription = "Modo Lluvia",
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            // End Session Button
            IconButton(
                onClick = onEnd,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RedDim)
                    .border(1.dp, RedDanger.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(Icons.Default.CallEnd, "Terminar", tint = RedDanger, modifier = Modifier.size(20.dp))
            }
        }
    }
}
