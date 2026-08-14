package com.foxhole.guard.core.webapps

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max

internal fun resolveWebAppIconFile(
    filesDir: File,
    relativePath: String?,
): File? {
    if (relativePath.isNullOrBlank() || File(relativePath).isAbsolute) return null
    return runCatching {
        val iconRoot = File(filesDir, WEB_APP_ICON_DIR).canonicalFile
        File(filesDir, relativePath).canonicalFile
            .takeIf { candidate -> candidate.parentFile == iconRoot && candidate.isFile }
    }.getOrNull()
}

internal fun decodeWebAppIcon(
    filesDir: File,
    relativePath: String?,
    targetSizePx: Int,
): Bitmap? {
    val file = resolveWebAppIconFile(filesDir, relativePath)
        ?.takeIf { it.length() in 1..WEB_APP_ICON_MAX_BYTES }
        ?: return null
    val target = targetSizePx.coerceAtLeast(1)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > target * DECODE_SIZE_MULTIPLIER) {
        sampleSize *= 2
    }
    return runCatching {
        android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull()
}

internal fun Bitmap.circularCrop(sizePx: Int): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    val output = createBitmap(size, size)
    val scale = max(size.toFloat() / width, size.toFloat() / height)
    val shader = BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - width * scale) / 2f, (size - height * scale) / 2f)
            },
        )
    }
    Canvas(output).drawCircle(
        size / 2f,
        size / 2f,
        size / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
    )
    return output
}

internal fun webAppBadgeBitmap(
    count: Int,
    sizePx: Int,
    backgroundColor: Int,
    textColor: Int,
): Bitmap {
    val label = if (count > 99) "99+" else count.coerceAtLeast(0).toString()
    return webAppCircleLabelBitmap(label, sizePx, backgroundColor, textColor)
}

internal fun webAppLetterIconBitmap(
    label: String,
    sizePx: Int,
    backgroundColor: Int,
    textColor: Int,
): Bitmap = webAppCircleLabelBitmap(label.take(1).uppercase(), sizePx, backgroundColor, textColor)

private fun webAppCircleLabelBitmap(
    label: String,
    sizePx: Int,
    backgroundColor: Int,
    textColor: Int,
): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    val output = createBitmap(size, size)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = size * if (label.length > 2) 0.34f else 0.45f
        typeface = Typeface.DEFAULT_BOLD
    }
    val center = size / 2f
    Canvas(output).apply {
        drawCircle(center, center, center, background)
        drawText(label, center, center - (text.ascent() + text.descent()) / 2f, text)
    }
    return output
}

internal const val WEB_APP_ICON_DIR = "webapps/icons"
private const val WEB_APP_ICON_MAX_BYTES = 512L * 1024L
private const val DECODE_SIZE_MULTIPLIER = 2
