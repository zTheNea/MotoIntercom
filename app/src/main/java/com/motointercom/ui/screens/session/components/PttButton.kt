package com.motointercom.ui.screens.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.ui.theme.*

@Composable
fun PttButton(
    isTalking: Boolean,
    scale: Float,
    pulseAlpha: Float,
    pulseScale: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    isSunMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring (graphicsLayer transformed)
        if (isTalking) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha * 0.35f
                    }
                    .background(
                        if (isSunMode) Color(0xFF2E7D32) else GreenActive,
                        CircleShape
                    )
            )
        }
        // Middle ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(
                    if (isTalking) (if (isSunMode) Color(0xFFC8E6C9) else GreenDim) else (if (isSunMode) Color(0xFFE5E7EB) else BackgroundElevated),
                    CircleShape
                )
                .border(
                    width = if (isSunMode) 3.dp else 2.dp,
                    color = if (isTalking) (if (isSunMode) Color(0xFF1B5E20) else GreenActive) else (if (isSunMode) Color(0xFF111827) else DividerColor),
                    shape = CircleShape
                )
        )
        // PTT button itself (graphicsLayer transformed)
        Box(
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    brush = if (isTalking)
                        Brush.radialGradient(
                            if (isSunMode) listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
                            else listOf(GreenActive, GreenPulse)
                        )
                    else
                        Brush.radialGradient(
                            if (isSunMode) listOf(Color(0xFFFF6D00), Color(0xFFD84315))
                            else listOf(OrangeFlame, OrangeDim)
                        ),
                    shape = CircleShape
                )
                .border(
                    width = if (isSunMode) 3.dp else 0.dp,
                    color = if (isSunMode) Color(0xFF000000) else Color.Transparent,
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onDown()
                            tryAwaitRelease()
                            onUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isTalking) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = "PTT",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

@Composable
fun VoxIndicator(
    isTalking: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    if (isTalking) GreenDim else BackgroundCard,
                    CircleShape
                )
                .border(2.dp, if (isTalking) GreenActive else DividerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isTalking) Icons.Default.GraphicEq else Icons.Default.Mic,
                null,
                tint = if (isTalking) GreenActive else TextSecondary,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            "MODO VOX ACTIVO",
            style = MaterialTheme.typography.labelMedium,
            color = GreenActive,
            letterSpacing = 2.sp
        )
        Text(
            "Transmisión automática por voz",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
