package com.foxhole.beta

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.settings.readFastStoredThemeMode
import com.foxhole.beta.ui.FoxholeApp
import com.foxhole.beta.ui.FoxholeBannerAction
import com.foxhole.beta.ui.FoxholeBannerHapticGate
import com.foxhole.beta.ui.HomeViewModel
import com.foxhole.beta.ui.handleSnackbarHaptic
import com.foxhole.beta.ui.showBanner
import com.foxhole.beta.ui.theme.FoxholeAppBackground
import com.foxhole.beta.ui.theme.FoxholeTheme
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

class MainActivity : AppCompatActivity() {
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.factory(application) }
    private var appliedSecureScreenPolicy: Boolean? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            homeViewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
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
        val initialThemeMode = readFastStoredThemeMode(this)
        applyEdgeToEdgeSystemBars(
            themeMode = initialThemeMode,
            systemDarkTheme = isSystemDarkTheme(),
        )
        val contentRoot =
            FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
            }
        val blurTarget =
            BlurTarget(this).apply {
                clipChildren = false
                clipToPadding = false
            }
        val composeView =
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            }
        blurTarget.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        contentRoot.addView(
            blurTarget,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(contentRoot)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.secureScreenEnabled.collect(::applySecureScreenPolicy)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                homeViewModel.onAppForegrounded()
            }
        }

        composeView.setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarHapticGate = remember { FoxholeBannerHapticGate() }
            val themeMode = homeViewModel.themeMode.collectAsStateWithLifecycle(initialValue = initialThemeMode).value

            LaunchedEffect(themeMode, systemDarkTheme) {
                applyEdgeToEdgeSystemBars(
                    themeMode = themeMode,
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
                themeMode = themeMode,
            ) {
                FoxholeAppBackground {
                    FoxholeApp(
                        viewModel = homeViewModel,
                        snackbarHostState = snackbarHostState,
                        bottomDockOverlayHost = contentRoot,
                        bottomDockBlurTarget = blurTarget,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    @Suppress("DEPRECATION")
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
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
