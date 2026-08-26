package com.foxhole.guard.core.sentinel

import android.content.Context
import android.content.pm.ApplicationInfo
import com.foxhole.core.model.AppNetworkUsageCategory
import java.util.concurrent.ConcurrentHashMap

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
