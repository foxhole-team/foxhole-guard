package com.foxhole.guard.ui.cli.settings

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val ICON_CACHE_ENTRIES = 256
private const val ICON_LOAD_PARALLELISM = 2
private val iconCache = LruCache<CliAppIconKey, ImageBitmap>(ICON_CACHE_ENTRIES)
private val iconLoadSlots = Semaphore(ICON_LOAD_PARALLELISM)

@Composable
internal fun rememberCliAppIcon(
    packageName: String,
    versionCode: Long?,
    lastUpdateTime: Long?,
    bitmapSize: Dp,
): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { bitmapSize.roundToPx() }
    val key = remember(packageName, versionCode, lastUpdateTime, sizePx) {
        CliAppIconKey(packageName, versionCode, lastUpdateTime, sizePx)
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = iconCache.get(key), key) {
        value = iconCache.get(key) ?: loadCliAppIcon(context, key)?.also { iconCache.put(key, it) }
    }
    return bitmap
}

private suspend fun loadCliAppIcon(context: Context, key: CliAppIconKey): ImageBitmap? =
    withContext(Dispatchers.IO) {
        iconLoadSlots.withPermit {
            runCatching {
                context.packageManager
                    .getApplicationIcon(key.packageName)
                    .toBitmap(key.sizePx, key.sizePx, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

private data class CliAppIconKey(
    val packageName: String,
    val versionCode: Long?,
    val lastUpdateTime: Long?,
    val sizePx: Int,
)
