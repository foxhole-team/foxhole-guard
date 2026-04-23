package com.foxhole.beta

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.beta.ui.FoxholeApp
import com.foxhole.beta.ui.HomeViewModel
import com.foxhole.beta.ui.showBanner
import com.foxhole.beta.ui.theme.FoxholeTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.factory(application) }
    private var vpnPermissionResult: ((Boolean) -> Unit)? = null
    private var appliedSecureScreenPolicy: Boolean? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            vpnPermissionResult?.invoke(result.resultCode == Activity.RESULT_OK)
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureScreenPolicy(homeViewModel.secureScreenEnabled.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.secureScreenEnabled.collect(::applySecureScreenPolicy)
            }
        }
        setContent {
            val themeMode = homeViewModel.themeMode.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            vpnPermissionResult = homeViewModel::onVpnPermissionResult

            LaunchedEffect(Unit) {
                requestPostNotificationsIfNeeded()
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
                homeViewModel.snackbars.collect { banner ->
                    snackbarHostState.showBanner(banner)
                }
            }

            FoxholeTheme(themeMode = themeMode.value) {
                FoxholeApp(
                    viewModel = homeViewModel,
                    snackbarHostState = snackbarHostState,
                )
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
}
