package com.foxhole.guard.ui.cli.webapps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.webapps.WebAppProfiles
import com.foxhole.guard.core.webapps.WebAppProxyCredentials
import com.foxhole.guard.core.webapps.isAllowedWebAppUrl
import com.foxhole.guard.core.webapps.webAppShimJs
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDivider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun CliWebAppFrame(
    app: WebAppEntity,
    proxyCredentials: WebAppProxyCredentials?,
    onClose: () -> Unit,
    onReleased: () -> Unit,
    onExternalBlocked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    BackHandler(onBack = onClose)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CliActionRow(
            label = app.name.lowercase(),
            value = stringResource(R.string.cli_webapps_close),
            onTap = onClose,
            modifier = Modifier.padding(horizontal = CliSpacing.md),
        )
        CliDivider()
        AndroidView(
            factory = { context ->
                buildWebAppWebView(
                    context = context,
                    appId = app.id,
                    appUrl = app.url,
                    proxyCredentials = proxyCredentials,
                    onExternalBlocked = onExternalBlocked,
                )
                    .apply { loadUrl(app.url) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onRelease = { webView ->
                WebAppProfiles.cookieManager(webView).flush()
                webView.destroy()
                onReleased()
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun buildWebAppWebView(
    context: Context,
    appId: Long,
    appUrl: String,
    proxyCredentials: WebAppProxyCredentials?,
    onExternalBlocked: (String) -> Unit,
): WebView {
    var documentStartScriptInstalled = false
    val shimScript = webAppShimJs(initialBadge = 0, bridged = false)
    return WebView(context).apply {
        WebAppProfiles.install(this, appId)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setGeolocationEnabled(false)
        WebAppProfiles.cookieManager(this).also { cookies ->
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(this, false)
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String?,
            ) {
                if (proxyCredentials != null && host == proxyCredentials.host) {
                    handler.proceed(proxyCredentials.username, proxyCredentials.password)
                } else {
                    handler.cancel()
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!isAllowedWebAppUrl(appUrl, url)) {
                    view.stopLoading()
                    onExternalBlocked(url?.toHttpUrlOrNull()?.host.orEmpty().ifEmpty { "invalid" })
                    return
                }
                if (!documentStartScriptInstalled) {
                    view.evaluateJavascript(shimScript, null)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url.toString()
                if (isAllowedWebAppUrl(appUrl, target)) {
                    return false
                }
                onExternalBlocked(request.url.host.orEmpty().ifEmpty { target })
                return true
            }
        }
    }.also { view ->
        documentStartScriptInstalled =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, shimScript, setOf("*"))
            }.isSuccess
    }
}
