package com.foxhole.guard.flags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import java.io.IOException

private val flags = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

internal fun countryFlagBitmap(context: Context, countryCode: String?): Bitmap? {
    val code = countryFlagCode(countryCode) ?: return null
    return synchronized(flags) {
        flags.get(code)?.let { return@synchronized it }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth != 256 || bounds.outHeight != 192) return@synchronized null
            context.assets.open("flags/$code.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
            }?.also { flags.put(code, it) }
        } catch (_: IOException) { null }
    }
}

internal fun capitalLetterHeightPx(paint: Paint): Float {
    val bounds = Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    return bounds.height().toFloat()
}
