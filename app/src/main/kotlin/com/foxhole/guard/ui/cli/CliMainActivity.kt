package com.foxhole.guard.ui.cli

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.core.data.profileDatabaseDowngradeDetected
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.applyBenchmarkIntent
import com.foxhole.guard.ui.applyNetworkRuleSwitchIntent
import com.foxhole.guard.ui.applyWebAppOpenIntent
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

/**
 * The single launcher of FoxHole Guard: database downgrade gate, VPN consent,
 * notifications, secure-screen policy and app-lock foreground/background timers around
 * the lightweight terminal UI.
 */
class CliMainActivity : AppCompatActivity() {

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        applyDarkEdgeToEdge()
        // A downgraded (newer-schema) database is fail-loud
        // everywhere, so it must be detected before the first homeViewModel touch.
        if (profileDatabaseDowngradeDetected(this)) {
            setContent { CliTheme { CliDatabaseDowngradeScreen() } }
            return
        }
        homeViewModel.applyBenchmarkIntent(intent)
        homeViewModel.applyNetworkRuleSwitchIntent(intent)
        // The frame state lives in the view model above the lock gate, so it opens after unlock.
        homeViewModel.applyWebAppOpenIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.secureScreenEnabled.collect(::applySecureScreenPolicy)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                window.decorView.post {
                    homeViewModel.onAppForegrounded()
                }
            }
        }

        setContent {
            CliTheme {
                LaunchedEffect(Unit) {
                    homeViewModel.requestVpnPermission.collect {
                        val prepareIntent = android.net.VpnService.prepare(this@CliMainActivity)
                        if (prepareIntent == null) {
                            homeViewModel.onVpnPermissionResult(true)
                        } else {
                            vpnPermissionLauncher.launch(prepareIntent)
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    homeViewModel.requestNotificationPermission.collect {
                        requestPostNotificationsIfNeeded()
                    }
                }
                CliApp(viewModel = homeViewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Arms the app-lock away timer, same as the classic UI.
        homeViewModel.onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // No lock gate here, exactly as in onCreate: the activity is singleTask, so every relaunch
        // of a live process lands here, and the gate silently lost a web-app tap made while
        // locked.
        homeViewModel.applyBenchmarkIntent(intent)
        homeViewModel.applyNetworkRuleSwitchIntent(intent)
        homeViewModel.applyWebAppOpenIntent(intent)
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
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

    // The CLI chrome is dark-only: both bars stay transparent over the terminal background.
    private fun applyDarkEdgeToEdge() {
        val dark = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    companion object {
        /** Long extra: the id of the web app whose frame should open. */
        const val EXTRA_OPEN_WEB_APP_ID = "open_web_app_id"
    }
}
