package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PayLiftDarkColorScheme = darkColorScheme(
    primary = VividBlue,
    onPrimary = PureWhite,
    primaryContainer = DarkIceBlue,
    onPrimaryContainer = SoftIceBlue,
    secondary = DeepRoyalBlue,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFF132B52),
    onSecondaryContainer = SoftIceBlue,
    tertiary = VioletEscrow,
    onTertiary = PureWhite,
    background = DarkCanvas,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = Color(0xFF24324D),
    error = RoseEmergency,
    onError = PureWhite
)

private val PayLiftLightColorScheme = lightColorScheme(
    primary = VividBlue,
    onPrimary = PureWhite,
    primaryContainer = SoftIceBlue,
    onPrimaryContainer = DeepRoyalBlue,
    secondary = DeepRoyalBlue,
    onSecondary = PureWhite,
    secondaryContainer = SkyBlueSoft,
    onSecondaryContainer = DeepRoyalBlue,
    tertiary = VioletEscrow,
    onTertiary = PureWhite,
    background = SlateCanvas,
    onBackground = SlateTextPrimary,
    surface = PureWhite,
    onSurface = SlateTextPrimary,
    surfaceVariant = SkyBlueSoft,
    onSurfaceVariant = SlateTextSecondary,
    outline = SlateBorder,
    outlineVariant = Color(0xFFCBD5E1),
    error = RoseEmergency,
    onError = PureWhite
)

@Composable
fun PayLiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PayLiftDarkColorScheme else PayLiftLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    PayLiftTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
