package com.foxhole.guard.core.webapps

import com.foxhole.guard.core.data.parseTitleBadge

// The watchdog's JS shim, injected before the page's own scripts (addDocumentStartJavaScript, with
// evaluateJavascript in onPageStarted as fallback — hence the __fhgShim latch against double
// installation). It intercepts Notification/showNotification for a call count and
// navigator.setAppBadge for an exact number, sending each signal over the __fhgBridge.
internal const val WEB_APP_SHIM_JS = """
(function() {
  if (window.__fhgShim) return; window.__fhgShim = true;
  var count = null;
  function post() {
    try { window.__fhgBridge.postMessage(JSON.stringify({ badge: count })); } catch (e) {}
  }
  try {
    var N = function(title, opts) { count = (count || 0) + 1; post(); };
    N.requestPermission = function() { return Promise.resolve('granted'); };
    N.permission = 'granted';
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
      ServiceWorkerRegistration.prototype.showNotification = function() {
        count = (count || 0) + 1; post(); return Promise.resolve();
      };
    }
  } catch (e) {}
})();
"""

/** Name of the bridge object addWebMessageListener publishes to the page. */
internal const val WEB_APP_SHIM_BRIDGE_NAME = "__fhgBridge"

/** Parses the bridge message `{"badge":N}`; junk from the page yields null, not an exception. */
internal fun parseShimBadgeMessage(message: String?): Int? {
    if (message.isNullOrBlank()) {
        return null
    }
    val match = SHIM_BADGE_REGEX.find(message) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun computeBadge(shimCount: Int?, title: String?, previous: Int): Int {
    if (shimCount != null) {
        return shimCount.coerceIn(0, WEB_APP_BADGE_MAX)
    }
    return parseTitleBadge(title)?.coerceIn(0, WEB_APP_BADGE_MAX) ?: previous
}

private const val WEB_APP_BADGE_MAX = 9999
private val SHIM_BADGE_REGEX = Regex(""""badge"\s*:\s*(-?\d+)""")
