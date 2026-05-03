package com.foxhole.beta.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.model.ThemeMode
import android.graphics.Color as AndroidColor

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
        tertiaryContainer = FoxholeLightPrimaryContainer,
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
        FoxholeUiPalette(
            menuSelectedRowColor = FoxholeReadAccent.copy(alpha = 0.18f),
            cardContainerColor = FoxholeDarkSurface,
            cardBorderColor = FoxholeDarkSurfaceStrong,
            leadingIconContainerColor = FoxholeDarkSurfaceMuted,
            valuePillContainerColor = FoxholeDarkPrimaryContainer,
            valuePillBorderColor = Color.Transparent,
            valuePillContentColor = FoxholeDarkPrimary,
            bottomBarContainerColor = FoxholeDarkSurface.copy(alpha = 0.88f),
            bottomBarBorderColor = FoxholeDarkSurfaceStrong.copy(alpha = 0.46f),
            bottomBarIndicatorColor = Color.White.copy(alpha = 0.10f),
        )
    }

internal val LocalFoxholeThemeMode =
    staticCompositionLocalOf {
        ThemeMode.DARK
    }

internal val LocalFoxholeDarkTheme =
    staticCompositionLocalOf {
        true
    }

private fun defaultFoxholeUiPalette(
    colorScheme: androidx.compose.material3.ColorScheme,
    useDarkPalette: Boolean,
    themeMode: ThemeMode,
): FoxholeUiPalette =
    if (themeMode == ThemeMode.SYSTEM) {
        systemFoxholeUiPalette(colorScheme, useDarkPalette)
    } else {
        FoxholeUiPalette(
            menuSelectedRowColor =
                if (useDarkPalette) {
                    colorScheme.primary.copy(alpha = 0.20f)
                } else {
                    colorScheme.primaryContainer.copy(alpha = 0.72f)
                },
            cardContainerColor =
                if (useDarkPalette) {
                    colorScheme.surfaceContainerHigh
                } else {
                    colorScheme.surfaceContainerLowest
                },
            cardBorderColor = colorScheme.outlineVariant.copy(alpha = 0.72f),
            leadingIconContainerColor = colorScheme.surfaceVariant,
            valuePillContainerColor = colorScheme.primaryContainer,
            valuePillBorderColor = colorScheme.outline.copy(alpha = if (useDarkPalette) 0.70f else 0.62f),
            valuePillContentColor = colorScheme.primary,
            bottomBarContainerColor = colorScheme.surface.copy(alpha = 0.88f),
            bottomBarBorderColor = colorScheme.outlineVariant.copy(alpha = if (useDarkPalette) 0.46f else 0.48f),
            bottomBarIndicatorColor =
                if (useDarkPalette) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.Black.copy(alpha = 0.06f)
                },
        )
    }

private fun systemFoxholeUiPalette(
    colorScheme: androidx.compose.material3.ColorScheme,
    useDarkPalette: Boolean,
): FoxholeUiPalette =
    FoxholeUiPalette(
        menuSelectedRowColor = colorScheme.secondaryContainer,
        cardContainerColor = colorScheme.surfaceContainerHighest,
        cardBorderColor = colorScheme.outlineVariant,
        leadingIconContainerColor = colorScheme.surfaceContainerHigh,
        valuePillContainerColor = colorScheme.primaryContainer,
        valuePillBorderColor = colorScheme.outlineVariant,
        valuePillContentColor = colorScheme.onPrimaryContainer,
        bottomBarContainerColor = colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        bottomBarBorderColor = colorScheme.outlineVariant.copy(alpha = if (useDarkPalette) 0.50f else 0.56f),
        bottomBarIndicatorColor =
            if (useDarkPalette) {
                Color.White.copy(alpha = 0.10f)
            } else {
                Color.Black.copy(alpha = 0.06f)
            },
    )

@Composable
fun FoxholeTheme(
    themeMode: ThemeMode,
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
    val baseColorScheme =
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
    val colorScheme =
        if (themeMode == ThemeMode.SYSTEM) {
            if (useDarkPalette) {
                baseColorScheme
            } else {
                baseColorScheme.withFoxholeReadableLightText()
            }
        } else {
            baseColorScheme.withFoxholeSurfaces(useDarkPalette)
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
        remember(themeMode, colorScheme, useDarkPalette) {
            defaultFoxholeUiPalette(
                colorScheme = colorScheme,
                useDarkPalette = useDarkPalette,
                themeMode = themeMode,
            )
        }
    CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors,
        LocalFoxholeDarkTheme provides useDarkPalette,
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

private fun ColorScheme.withFoxholeSurfaces(dark: Boolean): ColorScheme =
    if (dark) {
        copy(
            background = FoxholeDarkBackground,
            onBackground = Color(0xFFE9E9EA),
            surface = Color(0xFF101011),
            onSurface = Color(0xFFE9E9EA),
            surfaceDim = FoxholeDarkBackground,
            surfaceBright = Color(0xFF202022),
            surfaceContainerLowest = Color(0xFF09090A),
            surfaceContainerLow = Color(0xFF0E0E0F),
            surfaceContainer = Color(0xFF121213),
            surfaceContainerHigh = Color(0xFF171719),
            surfaceContainerHighest = Color(0xFF1D1D20),
            surfaceVariant = Color(0xFF1D1D20),
            onSurfaceVariant = Color(0xFFC7C7CA),
            outline = Color(0xFF3A3B3D),
            outlineVariant = Color(0xFF292A2C),
        )
    } else {
        copy(
            background = FoxholeLightBackground,
            onBackground = FoxholeLightOnBackground,
            surface = Color.White,
            onSurface = FoxholeLightOnBackground,
            surfaceDim = Color(0xFFE4E4E1),
            surfaceBright = Color(0xFFFAFAF8),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF8F8F6),
            surfaceContainer = Color(0xFFF0F0EE),
            surfaceContainerHigh = Color(0xFFE9E9E6),
            surfaceContainerHighest = Color(0xFFE2E2DF),
            surfaceVariant = Color(0xFFE9E9E6),
            onSurfaceVariant = FoxholeLightOnSurfaceVariant,
            outline = Color(0xFFD0D0CC),
            outlineVariant = Color(0xFFE0E0DD),
        )
    }

private fun ColorScheme.withFoxholeReadableLightText(): ColorScheme =
    copy(
        onBackground = FoxholeLightOnBackground,
        onSurface = FoxholeLightOnBackground,
        onSurfaceVariant = FoxholeLightOnSurfaceVariant,
    )

@Composable
fun FoxholeAppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalFoxholeDarkTheme.current
    val themeMode = LocalFoxholeThemeMode.current
    val colorScheme = MaterialTheme.colorScheme
    val base =
        if (themeMode == ThemeMode.SYSTEM) {
            colorScheme.background
        } else if (dark) {
            FoxholeDarkBackground
        } else {
            FoxholeLightBackground
        }
    val top =
        if (themeMode == ThemeMode.SYSTEM) {
            if (dark) {
                colorScheme.surfaceContainerHigh
            } else {
                colorScheme.surfaceBright
            }
        } else if (dark) {
            Color(0xFF09090A)
        } else {
            Color(0xFFFAFAF8)
        }
    val bottom =
        if (themeMode == ThemeMode.SYSTEM) {
            if (dark) {
                colorScheme.surfaceDim
            } else {
                colorScheme.surfaceContainerLow
            }
        } else if (dark) {
            Color(0xFF050506)
        } else {
            Color(0xFFECECEA)
        }
    val noiseBitmap =
        remember(dark) {
            createFoxholeNoiseBitmap(
                size = 128,
                dark = dark,
                maxAlpha = if (dark) 3 else 2,
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val gradientPaint =
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                            isDither = true
                            shader =
                                LinearGradient(
                                    0f,
                                    0f,
                                    0f,
                                    size.height,
                                    intArrayOf(
                                        top.toArgb(),
                                        base.toArgb(),
                                        bottom.toArgb(),
                                    ),
                                    floatArrayOf(0f, 0.55f, 1f),
                                    Shader.TileMode.CLAMP,
                                )
                        }
                    val noisePaint =
                        Paint(Paint.DITHER_FLAG).apply {
                            isDither = true
                            shader =
                                BitmapShader(
                                    noiseBitmap,
                                    Shader.TileMode.REPEAT,
                                    Shader.TileMode.REPEAT,
                                )
                        }

                    onDrawBehind {
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            nativeCanvas.drawRect(0f, 0f, size.width, size.height, gradientPaint)
                            nativeCanvas.drawRect(0f, 0f, size.width, size.height, noisePaint)
                        }
                    }
                },
        content = content,
    )
}

private fun createFoxholeNoiseBitmap(
    size: Int,
    dark: Boolean,
    maxAlpha: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    var seed = if (dark) 0x13579BDF.toInt() else 0x2468ACE0.toInt()

    for (index in pixels.indices) {
        seed = seed * 1_664_525 + 1_013_904_223
        val alpha = (seed ushr 24) % (maxAlpha + 1)
        pixels[index] =
            if (dark) {
                AndroidColor.argb(alpha, 255, 255, 255)
            } else {
                AndroidColor.argb(alpha, 0, 0, 0)
            }
    }

    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}
