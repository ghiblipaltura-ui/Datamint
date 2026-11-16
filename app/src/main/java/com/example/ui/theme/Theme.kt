package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DataMintDarkScheme = darkColorScheme(
    primary = MintPrimary,
    onPrimary = OnMintPrimary,
    primaryContainer = MintContainer,
    onPrimaryContainer = OnMintContainer,
    secondary = GoldSecondary,
    onSecondary = OnGoldSecondary,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = OnGoldContainer,
    tertiary = CyanTertiary,
    onTertiary = OnCyanTertiary,
    background = ObsidianBackground,
    onBackground = IceOnSurface,
    surface = SlateSurface,
    onSurface = IceOnSurface,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = MutedOnSurfaceVariant,
    surfaceContainerHigh = SlateSurfaceElevated,
    outline = EmeraldOutline,
    error = ErrorCoral
)

private val DataMintLightScheme = lightColorScheme(
    primary = LightEmeraldPrimary,
    onPrimary = LightOnEmerald,
    primaryContainer = LightEmeraldContainer,
    onPrimaryContainer = LightOnEmeraldContainer,
    secondary = LightGoldSecondary,
    onSecondary = LightOnEmerald,
    secondaryContainer = LightGoldContainer,
    onSecondaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = ErrorCoral
)

@Composable
fun DataMintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DataMintDarkScheme
        else -> DataMintLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DataMintTypography,
        content = content
    )
}
