package com.foxhole.guard.router.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.router.GuardRouterPage
import com.foxhole.guard.router.GuardRouterViewModel
import com.foxhole.guard.router.ROUTER_LOCAL_NETWORK_PERMISSION
import com.foxhole.guard.router.RouterBetaNoticePreferences
import com.foxhole.guard.router.RouterWizardStage
import com.foxhole.guard.router.routerLocalNetworkAccessGranted
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun GuardRouterManagementScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val betaNoticePreferences = remember(context.applicationContext) { RouterBetaNoticePreferences(context) }
    var betaNoticeVisible by rememberSaveable { mutableStateOf(!betaNoticePreferences.isSuppressed()) }
    val model = rememberGuardRouterViewModel()
    val page by model.page.collectAsStateWithLifecycle()
    val initialising by model.initialising.collectAsStateWithLifecycle()
    val state by model.wizard.state.collectAsStateWithLifecycle()
    val profileProbe by model.profileProbe.state.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notices = remember(scope, snackbar) { GuardRouterNotices(scope, snackbar) }
    val permissionError = stringResource(R.string.guard_router_vpn_router_error_local_network_permission)
    val unavailableError = stringResource(R.string.guard_router_vpn_router_error_unavailable)
    val networkError = stringResource(R.string.guard_router_vpn_router_error_network)
    val wizardError = state.error?.let { stringResource(routerWizardErrorLabel(it)) }
    val noticeResources by rememberUpdatedState(LocalResources.current)
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = permissionAction; permissionAction = null
        if (granted && routerLocalNetworkAccessGranted(context)) action?.invoke()
        else if (action != null) notices.show(permissionError, error = true)
    }
    fun requestAccess(action: () -> Unit) {
        if (permissionAction != null) return
        if (routerLocalNetworkAccessGranted(context)) action()
        else {
            permissionAction = action
            try { permission.launch(ROUTER_LOCAL_NETWORK_PERMISSION) }
            catch (_: IllegalStateException) {
                permissionAction = null
                notices.show(permissionError, error = true)
            }
        }
    }
    fun close() { permissionAction = null; model.closeScreen(); onBack() }
    LaunchedEffect(model) { model.open() }
    DisposableEffect(model) { onDispose { permissionAction = null; model.closeScreen() } }
    LaunchedEffect(model, context) { model.notices.collect { notices.show(noticeResources.getString(it), error = true) } }
    LaunchedEffect(state.error) { wizardError?.let { notices.show(it, error = true) } }
    LaunchedEffect(state.stage) { if (state.stage == RouterWizardStage.COMPLETE) model.completed() }
    BackHandler(enabled = !betaNoticeVisible) { if (page == GuardRouterPage.WIZARD) model.entry() else close() }
    CompositionLocalProvider(LocalGuardRouterNotices provides notices) { GuardRouterTypography {
        Scaffold(modifier = modifier.fillMaxSize().padding(bottom = LocalCliBottomChromeClearance.current),
            containerColor = LocalCliColors.current.bg) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (betaNoticeVisible) {
                            GuardRouterBetaNoticeSheet(betaNoticePreferences, onDismiss = { betaNoticeVisible = false })
                        } else if (initialising) CircularProgressIndicator(Modifier.align(Alignment.Center)) else when (page) {
                            GuardRouterPage.ENTRY -> GuardRouterEntrySheet(
                                setupEnabled = model.wizard.setupAvailable, installationCatalog = model.wizard.installationCatalog,
                                discovery = state.discovery, finding = state.busy,
                                onFind = {
                                    if (!model.wizard.hasPhysicalWifi()) notices.show(
                                        noticeResources.getString(R.string.guard_router_vpn_router_error_wifi), error = true)
                                    else requestAccess(model::find)
                                },
                                onSetup = { requestAccess { model.discovered(null) } },
                                onPair = { model.manualPair() }, onConnect = { reset -> requestAccess { model.discovered(reset) } },
                            )
                            GuardRouterPage.CONTROL -> GuardRouterControlSheet(model.control,
                                profileProbe = profileProbe,
                                onRetryPairing = {
                                    if (!model.wizard.hasPhysicalWifi()) notices.show(
                                        noticeResources.getString(R.string.guard_router_router_profile_pair_wifi), error = true)
                                    else requestAccess { model.retryPairing() }
                                },
                                onPair = { model.entry() }, onAddRouter = { model.entry(adding = true) },
                                onUnpaired = { if (model.control.state.value.selectedRouterId == null) model.entry() },
                                requestAccess = ::requestAccess,
                                requestHomeEnable = { notices.show(unavailableError, error = true) },
                            )
                            GuardRouterPage.WIZARD -> GuardRouterWizardSheet(state, profiles,
                                model.wizard.supportsHomeAutodetection, model.wizard.supportsRemoteProfile, model.wizard.installationCatalog,
                                RouterWizardActions(
                                    inspect = { address -> requestAccess { model.action { model.inspect(address) } } },
                                    trust = { model.action { model.wizard.acceptIdentity() } },
                                    installProtocols = { model.wizard.installProtocols(it) },
                                    installCompactSelection = { protocols, server -> model.wizard.installProtocols(protocols, server) },
                                    confirmInstallProtocols = { model.action { model.wizard.confirmInstallProtocols() } },
                                    login = { credential -> val owned = credential.copyOf(); model.action(owned) { model.wizard.login(owned) } },
                                    newPin = { credential -> model.action(credential) { model.wizard.changePin(credential) } },
                                    install = { model.action { model.wizard.install() } }, options = model.wizard::options,
                                    finish = { model.action { model.wizard.finish() } },
                                    failure = { notices.show(networkError, error = true) },
                                    openRouterSetup = {
                                        if (state.address == "192.168.1.1") try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://192.168.1.1/cgi-bin/luci/admin/services/foxhole/pin")))
                                        } catch (_: Exception) { notices.show(unavailableError, error = true) }
                                    },
                                ), onBack = { model.entry() },
                            )
                        }
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    } }
}

@Composable
internal fun rememberGuardRouterViewModel(key: String = "guard-router-management"): GuardRouterViewModel {
    val context = LocalContext.current
    val activity = remember(context) { requireNotNull(routerActivity(context)) }
    return remember(activity, key) { ViewModelProvider(activity)[key, GuardRouterViewModel::class.java] }
}

private tailrec fun routerActivity(context: Context): ComponentActivity? = when (context) {
    is ComponentActivity -> context
    is ContextWrapper -> if (context.baseContext !== context) routerActivity(context.baseContext) else null
    else -> null
}
