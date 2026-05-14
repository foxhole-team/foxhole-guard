package com.foxhole.beta

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.FoxholeApp
import com.foxhole.beta.ui.FoxholeBannerAction
import com.foxhole.beta.ui.FoxholeBannerHapticGate
import com.foxhole.beta.ui.HomeViewModel
import com.foxhole.beta.ui.handleSnackbarHaptic
import com.foxhole.beta.ui.showBanner
import com.foxhole.beta.ui.theme.FoxholeAppBackground
import com.foxhole.beta.ui.theme.FoxholeTheme
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

class MainActivity : AppCompatActivity() {
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.factory(application) }
    private var vpnPermissionResult: ((Boolean) -> Unit)? = null
    private var appliedSecureScreenPolicy: Boolean? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            vpnPermissionResult?.invoke(result.resultCode == Activity.RESULT_OK)
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            runCatching {
                (application as FoxholeApplication).appGraph.diagnosticsLogger.record(
                    "permissions",
                    "post notifications permission result granted=$granted",
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnPermissionResult = homeViewModel::onVpnPermissionResult
        applyEdgeToEdgeSystemBars(
            themeMode = homeViewModel.themeMode.value,
            systemDarkTheme = isSystemDarkTheme(),
        )
        applySecureScreenPolicy(homeViewModel.secureScreenEnabled.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.secureScreenEnabled.collect(::applySecureScreenPolicy)
            }
        }
        setContent {
            val themeMode = homeViewModel.themeMode.collectAsStateWithLifecycle()
            val systemDarkTheme = isSystemInDarkTheme()
            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarHapticGate = remember { FoxholeBannerHapticGate() }

            LaunchedEffect(themeMode.value, systemDarkTheme) {
                applyEdgeToEdgeSystemBars(
                    themeMode = themeMode.value,
                    systemDarkTheme = systemDarkTheme,
                )
            }

            LaunchedEffect(Unit) {
                homeViewModel.requestVpnPermission.collect {
                    val intent = android.net.VpnService.prepare(this@MainActivity)
                    if (intent == null) {
                        homeViewModel.onVpnPermissionResult(true)
                    } else {
                        vpnPermissionLauncher.launch(intent)
                    }
                }
            }

            LaunchedEffect(Unit) {
                homeViewModel.requestNotificationPermission.collect {
                    requestPostNotificationsIfNeeded()
                }
            }

            LaunchedEffect(Unit) {
                homeViewModel.snackbars.collect { banner ->
                    handleSnackbarHaptic(
                        event = banner,
                        context = this@MainActivity,
                        gate = snackbarHapticGate,
                    )
                    val result = snackbarHostState.showBanner(banner)
                    if (
                        result == SnackbarResult.ActionPerformed &&
                        banner.action == FoxholeBannerAction.ACCEPT_PROTOCOL_RECOMMENDATION
                    ) {
                        homeViewModel.onProtocolRecommendationAccepted()
                    }
                }
            }

            FoxholeTheme(
                themeMode = themeMode.value,
            ) {
                FoxholeAppBackground {
                    FoxholeApp(
                        viewModel = homeViewModel,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.onAppForegrounded()
        applySecureScreenPolicy(homeViewModel.secureScreenEnabled.value)
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun applySecureScreenPolicy(enabled: Boolean) {
        if (appliedSecureScreenPolicy == enabled) {
            return
        }
        appliedSecureScreenPolicy = enabled
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        if (enabled) {
            window.addFlags(secureFlag)
        } else {
            window.clearFlags(secureFlag)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!enabled)
        }
        window.decorView.invalidate()
    }

    private fun applyEdgeToEdgeSystemBars(
        themeMode: ThemeMode,
        systemDarkTheme: Boolean,
    ) {
        val appUsesDarkPalette = appUsesDarkPalette(themeMode, systemDarkTheme)
        val systemBarStyle =
            SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
            ) {
                appUsesDarkPalette
            }
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
    }

    private fun isSystemDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun appUsesDarkPalette(
        themeMode: ThemeMode,
        systemDarkTheme: Boolean,
    ): Boolean =
        when (themeMode) {
            ThemeMode.SYSTEM -> systemDarkTheme
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
}
