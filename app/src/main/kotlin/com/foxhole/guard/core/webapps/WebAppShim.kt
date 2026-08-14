package com.foxhole.guard.core.webapps

import com.foxhole.guard.core.data.parseTitleBadge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The watchdog's JS shim, injected before the page's own scripts (addDocumentStartJavaScript, with
// evaluateJavascript in onPageStarted as fallback — hence the __fhgShim latch against double
// installation). It intercepts Notification/showNotification for a call count and
// navigator.setAppBadge for an exact number, sending each signal over the __fhgBridge.
private const val WEB_APP_SHIM_TEMPLATE = """
(function() {
  if (window.__fhgShim) return; window.__fhgShim = true;
  var count = __FHG_INITIAL_BADGE__;
  function post(title, body) {
    try {
      window.__fhgBridge.postMessage(JSON.stringify({
        badge: count,
        title: typeof title === 'string' ? title : null,
        body: typeof body === 'string' ? body : null
      }));
    } catch (e) {}
  }
  try {
    var N = function(title, opts) {
      count = Math.min(9999, count + 1);
      this.title = String(title || '');
      this.body = opts && typeof opts.body === 'string' ? opts.body : '';
      post(this.title, this.body);
    };
    N.prototype.close = function() {};
    N.prototype.addEventListener = function() {};
    N.prototype.removeEventListener = function() {};
    N.requestPermission = function(callback) {
      if (typeof callback === 'function') setTimeout(function() { callback('granted'); }, 0);
      return Promise.resolve('granted');
    };
    Object.defineProperty(N, 'permission', { get: function() { return 'granted'; } });
    Object.defineProperty(window, 'Notification', { value: N, configurable: false });
  } catch (e) {}
  try {
    navigator.setAppBadge = function(n) {
      count = (typeof n === 'number' && isFinite(n)) ? n : 0; post(); return Promise.resolve();
    };
    navigator.clearAppBadge = function() { count = 0; post(); return Promise.resolve(); };
  } catch (e) {}
  try {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function(title, opts) {
        count = Math.min(9999, count + 1);
        post(String(title || ''), opts && typeof opts.body === 'string' ? opts.body : '');
        return Promise.resolve();
      };
    }
  } catch (e) {}
})();
"""

internal fun webAppShimJs(initialBadge: Int): String =
    WEB_APP_SHIM_TEMPLATE.replace(
        "__FHG_INITIAL_BADGE__",
        initialBadge.coerceIn(0, WEB_APP_BADGE_MAX).toString(),
    )

/** Name of the bridge object addWebMessageListener publishes to the page. */
internal const val WEB_APP_SHIM_BRIDGE_NAME = "__fhgBridge"

/** Parses the bridge message `{"badge":N}`; junk from the page yields null, not an exception. */
internal data class WebAppNotificationContent(
    val title: String?,
    val body: String?,
)

internal data class WebAppShimSignal(
    val badge: Int?,
    val notification: WebAppNotificationContent?,
)

internal fun parseShimSignal(message: String?): WebAppShimSignal? {
    if (message.isNullOrBlank()) return null
    val objectValue = runCatching { SHIM_JSON.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    val badge = objectValue["badge"]?.jsonPrimitive?.intOrNull
    val title = objectValue["title"]?.jsonPrimitive?.contentOrNull?.normalizedNotificationText()
    val body = objectValue["body"]?.jsonPrimitive?.contentOrNull?.normalizedNotificationText()
    val notification = WebAppNotificationContent(title = title, body = body).takeIf {
        it.title != null || it.body != null
    }
    if (badge == null && notification == null) return null
    return WebAppShimSignal(badge = badge, notification = notification)
}

internal fun parseShimBadgeMessage(message: String?): Int? = parseShimSignal(message)?.badge

internal fun computeBadge(shimCount: Int?, title: String?, previous: Int): Int {
    if (shimCount != null) {
        return shimCount.coerceIn(0, WEB_APP_BADGE_MAX)
    }
    return parseTitleBadge(title)?.coerceIn(0, WEB_APP_BADGE_MAX) ?: previous
}

/** A grown badge notifies unless that app's frame is open — the user is already looking at it. */
internal fun shouldNotifyBadgeIncrease(
    previous: Int,
    updated: Int,
    appId: Long,
    foregroundAppId: Long?,
): Boolean = updated > previous && appId != foregroundAppId

private fun String.normalizedNotificationText(): String? =
    trim()
        .replace(NOTIFICATION_WHITESPACE, " ")
        .take(WEB_APP_NOTIFICATION_TEXT_MAX)
        .takeIf(String::isNotEmpty)

private const val WEB_APP_BADGE_MAX = 9999
private const val WEB_APP_NOTIFICATION_TEXT_MAX = 240
private val NOTIFICATION_WHITESPACE = Regex("\\s+")
private val SHIM_JSON = Json { ignoreUnknownKeys = true }
