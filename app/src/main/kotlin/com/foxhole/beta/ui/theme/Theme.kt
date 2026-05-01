package com.foxhole.beta.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.model.ThemeMode

private val FoxholeDarkColorScheme =
    darkColorScheme(
        primary = FoxholeDarkPrimary,
        onPrimary = FoxholeDarkOnPrimary,
        primaryContainer = FoxholeDarkPrimaryContainer,
        onPrimaryContainer = FoxholeReadAccent,
        secondary = FoxholeBrandGreen,
        onSecondary = FoxholeDarkOnPrimary,
        secondaryContainer = FoxholeDarkSurfaceStrong,
        onSecondaryContainer = FoxholeBrandGreen,
        tertiary = FoxholeReadAccent,
        onTertiary = FoxholeDarkOnPrimary,
        tertiaryContainer = FoxholeDarkPrimaryContainer,
        onTertiaryContainer = FoxholeReadAccent,
        background = FoxholeDarkBackground,
        onBackground = FoxholeDarkOnBackground,
        surface = FoxholeDarkSurface,
        onSurface = FoxholeDarkOnBackground,
        surfaceVariant = FoxholeDarkSurfaceMuted,
        onSurfaceVariant = FoxholeDarkOnSurfaceVariant,
        outline = FoxholeDarkOutline,
        outlineVariant = FoxholeDarkSurfaceStrong,
        surfaceTint = FoxholeDarkPrimary,
        error = FoxholeError,
        onError = FoxholeDarkOnBackground,
    )

private val FoxholeLightColorScheme =
    lightColorScheme(
        primary = FoxholeLightPrimary,
        onPrimary = FoxholeLightOnPrimary,
        primaryContainer = FoxholeLightPrimaryContainer,
        onPrimaryContainer = FoxholeLightPrimary,
        secondary = Color(0xFF3F6E61),
        onSecondary = FoxholeLightOnPrimary,
        secondaryContainer = Color(0xFFD8E8E2),
        onSecondaryContainer = Color(0xFF21483E),
        tertiary = FoxholeLinkBluePressed,
        onTertiary = FoxholeLightOnPrimary,
        tertiaryContainer = Color(0xFFDCE9F8),
        onTertiaryContainer = FoxholeLightPrimary,
        background = FoxholeLightBackground,
        onBackground = FoxholeLightOnBackground,
        surface = FoxholeLightSurface,
        onSurface = FoxholeLightOnBackground,
        surfaceVariant = FoxholeLightSurfaceMuted,
        onSurfaceVariant = FoxholeLightOnSurfaceVariant,
        outline = FoxholeLightOutline,
        outlineVariant = FoxholeLightSurfaceStrong,
        surfaceTint = FoxholeLightPrimary,
        error = FoxholeError,
        onError = FoxholeLightOnPrimary,
    )

private val FoxholeShapes =
    Shapes(
        small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(30.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )

private val FixedSelectionColors =
    TextSelectionColors(
        handleColor = FoxholeReadAccent,
        backgroundColor = FoxholeSelectionBackground,
    )

internal data class FoxholeUiPalette(
    val chromeContainerAlpha: Float,
    val menuContainerColor: Color,
    val menuBorderColor: Color,
    val menuDividerColor: Color,
    val menuSelectedRowColor: Color,
    val cardContainerColor: Color,
    val cardBorderColor: Color,
    val leadingIconContainerColor: Color,
    val valuePillContainerColor: Color,
    val valuePillBorderColor: Color,
    val valuePillContentColor: Color,
    val bottomBarContainerColor: Color,
    val bottomBarBorderColor: Color,
    val bottomBarIndicatorColor: Color,
)

internal val LocalFoxholeUiPalette =
    staticCompositionLocalOf {
        val chromeContainerAlpha = foxholeChromeContainerAlpha(transparencyEnabled = true)
        FoxholeUiPalette(
            chromeContainerAlpha = chromeContainerAlpha,
            menuContainerColor = FoxholeDarkBackground.copy(alpha = chromeContainerAlpha),
            menuBorderColor = FoxholeDarkOutline.copy(alpha = 0.34f),
            menuDividerColor = FoxholeDarkOutline.copy(alpha = 0.20f),
            menuSelectedRowColor = FoxholeReadAccent.copy(alpha = 0.12f),
            cardContainerColor = FoxholeDarkSurface.copy(alpha = chromeContainerAlpha),
            cardBorderColor = FoxholeDarkSurfaceStrong,
            leadingIconContainerColor = FoxholeDarkSurfaceMuted.copy(alpha = chromeContainerAlpha),
            valuePillContainerColor = FoxholeDarkPrimaryContainer.copy(alpha = chromeContainerAlpha),
            valuePillBorderColor = Color.Transparent,
            valuePillContentColor = FoxholeDarkPrimary,
            bottomBarContainerColor = FoxholeDarkSurface.copy(alpha = chromeContainerAlpha),
            bottomBarBorderColor = FoxholeDarkSurfaceStrong.copy(alpha = 0.46f),
            bottomBarIndicatorColor = Color.White.copy(alpha = 0.10f),
        )
    }

internal val LocalFoxholeThemeMode =
    staticCompositionLocalOf {
        ThemeMode.DARK
    }

internal fun foxholeChromeContainerAlpha(transparencyEnabled: Boolean): Float =
    if (transparencyEnabled) 0.88f else 1f

private fun defaultFoxholeUiPalette(
    colorScheme: androidx.compose.material3.ColorScheme,
    useDarkPalette: Boolean,
    transparencyEnabled: Boolean,
): FoxholeUiPalette =
    foxholeChromeContainerAlpha(transparencyEnabled).let { chromeContainerAlpha ->
        FoxholeUiPalette(
            chromeContainerAlpha = chromeContainerAlpha,
            menuContainerColor = colorScheme.surface.copy(alpha = chromeContainerAlpha),
            menuBorderColor = colorScheme.outlineVariant.copy(alpha = if (useDarkPalette) 0.34f else 0.38f),
            menuDividerColor = colorScheme.outlineVariant.copy(alpha = if (useDarkPalette) 0.18f else 0.28f),
            menuSelectedRowColor =
                if (useDarkPalette) {
                    colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    colorScheme.primaryContainer.copy(alpha = 0.62f)
                },
            cardContainerColor = colorScheme.surface.copy(alpha = chromeContainerAlpha),
            cardBorderColor = colorScheme.outlineVariant.copy(alpha = 0.72f),
            leadingIconContainerColor = colorScheme.surfaceVariant.copy(alpha = chromeContainerAlpha),
            valuePillContainerColor = colorScheme.primaryContainer.copy(alpha = chromeContainerAlpha),
            valuePillBorderColor = Color.Transparent,
            valuePillContentColor = colorScheme.primary,
            bottomBarContainerColor = colorScheme.surface.copy(alpha = chromeContainerAlpha),
            bottomBarBorderColor = colorScheme.outlineVariant.copy(alpha = if (useDarkPalette) 0.46f else 0.48f),
            bottomBarIndicatorColor =
                if (useDarkPalette) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.Black.copy(alpha = 0.06f)
                },
        )
    }

@Composable
fun FoxholeTheme(
    themeMode: ThemeMode,
    transparencyEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val useDarkPalette =
        when (themeMode) {
            ThemeMode.SYSTEM -> systemDarkTheme
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    val colorScheme =
        when (themeMode) {
            ThemeMode.SYSTEM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (systemDarkTheme) {
                        dynamicDarkColorScheme(context)
                    } else {
                        dynamicLightColorScheme(context)
                    }
                } else if (systemDarkTheme) {
                    FoxholeDarkColorScheme
                } else {
                    FoxholeLightColorScheme
                }
            ThemeMode.DARK -> FoxholeDarkColorScheme
            ThemeMode.LIGHT -> FoxholeLightColorScheme
        }
    val selectionColors =
        remember(themeMode, colorScheme) {
            if (themeMode == ThemeMode.SYSTEM) {
                TextSelectionColors(
                    handleColor = colorScheme.primary,
                    backgroundColor = colorScheme.primary.copy(alpha = 0.32f),
                )
            } else {
                FixedSelectionColors
            }
        }
    val uiPalette =
        remember(themeMode, colorScheme, useDarkPalette, transparencyEnabled) {
            defaultFoxholeUiPalette(
                colorScheme = colorScheme,
                useDarkPalette = useDarkPalette,
                transparencyEnabled = transparencyEnabled,
            )
        }
    CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors,
        LocalFoxholeUiPalette provides uiPalette,
        LocalFoxholeThemeMode provides themeMode,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FoxholeShapes,
            content = content,
        )
    }
}
