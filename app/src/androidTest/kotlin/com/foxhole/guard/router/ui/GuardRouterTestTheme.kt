package com.foxhole.guard.router.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.foxhole.guard.ui.cli.CliTheme

@Composable
internal fun GuardRouterNoticeScope(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    val notices = remember(scope, host) { GuardRouterNotices(scope, host) }
    CompositionLocalProvider(LocalGuardRouterNotices provides notices) {
        Box { content(); SnackbarHost(host) }
    }
}

@Composable
internal fun GuardRouterTestTheme(content: @Composable () -> Unit) {
    CliTheme { GuardRouterTypography { GuardRouterNoticeScope(content) } }
}
