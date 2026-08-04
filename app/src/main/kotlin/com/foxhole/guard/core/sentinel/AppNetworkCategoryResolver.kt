package com.foxhole.guard.core.sentinel

import android.content.Context
import android.content.pm.ApplicationInfo
import com.foxhole.core.model.AppNetworkUsageCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a package's [AppNetworkUsageCategory] from the developer-declared platform category
 * (`ApplicationInfo.category`). Video, audio, and game apps legitimately move large download
 * volumes, so FOXHOLE SENTINEL dampens download-shaped spikes from them; everything else — and
 * anything unresolvable — scores as STANDARD, keeping full sensitivity.
 *
 * Results are cached per package: the anomaly pipeline asks on every traffic window and a
 * declared category effectively never changes within a process lifetime.
 */
class AppNetworkCategoryResolver(
    context: Context,
) {
    private val packageManager = context.applicationContext.packageManager
    private val cache = ConcurrentHashMap<String, AppNetworkUsageCategory>()

    fun categoryOf(packageName: String): AppNetworkUsageCategory =
        cache.getOrPut(packageName) {
            runCatching {
                when (packageManager.getApplicationInfo(packageName, 0).category) {
                    ApplicationInfo.CATEGORY_VIDEO,
                    ApplicationInfo.CATEGORY_AUDIO,
                    ApplicationInfo.CATEGORY_GAME,
                    -> AppNetworkUsageCategory.CONTENT_HEAVY
                    else -> AppNetworkUsageCategory.STANDARD
                }
            }.getOrDefault(AppNetworkUsageCategory.STANDARD)
        }
}
