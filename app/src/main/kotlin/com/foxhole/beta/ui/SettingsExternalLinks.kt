package com.foxhole.beta.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

internal fun openFoxholeRepository(context: Context): Boolean {
    return openExternalUrl(context, FOXHOLE_REPOSITORY_URL)
}

internal fun openSingBoxRepository(context: Context): Boolean =
    openExternalUrl(context, SING_BOX_REPOSITORY_URL)

internal fun openTorRepository(context: Context): Boolean =
    openExternalUrl(context, TOR_REPOSITORY_URL)

internal fun openAdGuardDnsFilterRepository(context: Context): Boolean =
    openExternalUrl(context, ADGUARD_DNS_FILTER_REPOSITORY_URL)

internal fun installedSupportAppPackage(packageManager: PackageManager): String? =
    SUPPORT_APP_PACKAGE_CANDIDATES.firstOrNull { packageName ->
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
    }

internal fun openSupportChannel(context: Context): Boolean {
    val browserIntent =
        Intent(Intent.ACTION_VIEW, supportChannelBrowserUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    val supportAppPackage = installedSupportAppPackage(context.packageManager)
    if (supportAppPackage != null) {
        val supportAppIntent =
            Intent(Intent.ACTION_VIEW, supportChannelAppUri())
                .setPackage(supportAppPackage)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(supportAppIntent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return try {
        context.startActivity(browserIntent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun supportChannelBrowserUri(): Uri = "https://t.me/$FOXHOLE_SUPPORT_CHANNEL".toUri()

private fun supportChannelAppUri(): Uri = "tg://resolve?domain=$FOXHOLE_SUPPORT_CHANNEL".toUri()

private fun openExternalUrl(
    context: Context,
    url: String,
): Boolean {
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private const val FOXHOLE_REPOSITORY_URL = "https://github.com/foxhole-repo/foxhole-app"
private const val SING_BOX_REPOSITORY_URL = "https://github.com/SagerNet/sing-box"
private const val TOR_REPOSITORY_URL = "https://github.com/torproject/tor"
private const val ADGUARD_DNS_FILTER_REPOSITORY_URL = "https://github.com/AdguardTeam/AdGuardSDNSFilter"
private const val FOXHOLE_SUPPORT_CHANNEL = "foxhole_repo"
private val SUPPORT_APP_PACKAGE_CANDIDATES =
    listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    )
