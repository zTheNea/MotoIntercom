package com.motointercom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MotoColorScheme = darkColorScheme(
    primary            = OrangeFlame,
    onPrimary          = TextOnOrange,
    primaryContainer   = OrangeDim,
    onPrimaryContainer = AmberGlow,
    secondary          = AmberGlow,
    onSecondary        = BackgroundDeep,
    secondaryContainer = Color(0xFF3D2A00),
    tertiary           = CyanAccent,
    background         = BackgroundDeep,
    onBackground       = TextPrimary,
    surface            = BackgroundCard,
    onSurface          = TextPrimary,
    surfaceVariant     = BackgroundElevated,
    onSurfaceVariant   = TextSecondary,
    outline            = DividerColor,
    error              = RedDanger,
    onError            = Color.White
)

@Composable
fun MotoIntercomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MotoColorScheme,
        typography  = Typography,
        content     = content
    )
}
