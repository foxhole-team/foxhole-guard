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
  var bridged = __FHG_BRIDGED__;
  var queue = [];
  var flushTimer = null;
  var flushAttempts = 0;
  var tags = {};

  function bridge() {
    var target = window.__fhgBridge;
    return target && typeof target.postMessage === 'function' ? target : null;
  }
  function flush() {
    var target = bridge();
    if (!target) { return false; }
    while (queue.length) {
      var payload = queue[0];
      try {
        target.postMessage(payload);
      } catch (e) {
        return false;
      }
      queue.shift();
    }
    return true;
  }
  function scheduleFlush() {
    if (flushTimer !== null || flushAttempts >= 20) { return; }
    flushTimer = setTimeout(function() {
      flushTimer = null;
      flushAttempts++;
      if (!flush()) { scheduleFlush(); }
    }, Math.min(2000, 50 * (flushAttempts + 1)));
  }
  function send(payload) {
    if (!bridged) { return; }
    if (queue.length >= 32) { queue.shift(); }
    queue.push(JSON.stringify(payload));
    if (!flush()) { scheduleFlush(); }
  }
  function post(title, body) {
    send({
      badge: count,
      title: typeof title === 'string' ? title : null,
      body: typeof body === 'string' ? body : null
    });
  }
  function report(stage, error) {
    send({ error: stage + ': ' + ((error && error.message) || error || 'failed') });
  }
  function bump(tag) {
    var key = typeof tag === 'string' && tag ? tag : null;
    if (key !== null && tags[key]) { return; }
    if (key !== null) { tags[key] = true; }
    count = Math.min(9999, count + 1);
  }

  try {
    document.addEventListener('DOMContentLoaded', flush);
    document.addEventListener('visibilitychange', flush);
    window.addEventListener('pageshow', flush);
  } catch (e) {
    scheduleFlush();
  }

  try {
    var N = function(title, opts) {
      var tag = opts && typeof opts.tag === 'string' ? opts.tag : null;
      bump(tag);
      this.title = String(title || '');
      this.body = opts && typeof opts.body === 'string' ? opts.body : '';
      this.tag = tag;
      this.onclick = null;
      this.onclose = null;
      post(this.title, this.body);
    };
    N.prototype.close = function() {
      if (this.tag && tags[this.tag]) { delete tags[this.tag]; }
      count = Math.max(0, count - 1);
      if (typeof this.onclose === 'function') {
        try { this.onclose(); } catch (e) { report('notification_onclose', e); }
      }
      post();
    };
    N.prototype.addEventListener = function() {};
    N.prototype.removeEventListener = function() {};
    N.requestPermission = function(callback) {
      if (typeof callback === 'function') setTimeout(function() { callback('granted'); }, 0);
      return Promise.resolve('granted');
    };
    Object.defineProperty(N, 'permission', { get: function() { return 'granted'; } });
    Object.defineProperty(N, 'maxActions', { get: function() { return 0; } });
    Object.defineProperty(window, 'Notification', { value: N, configurable: false });
  } catch (e) {
    report('notification_override', e);
  }
  try {
    navigator.setAppBadge = function(n) {
      if (typeof n === 'number' && isFinite(n) && n >= 0) {
        count = Math.min(9999, Math.floor(n));
      } else {
        count = Math.max(count, 1);
      }
      post();
      return Promise.resolve();
    };
    navigator.clearAppBadge = function() {
      count = 0; tags = {}; post(); return Promise.resolve();
    };
  } catch (e) {
    report('badge_override', e);
  }
  try {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function(title, opts) {
        bump(opts && typeof opts.tag === 'string' ? opts.tag : null);
        post(String(title || ''), opts && typeof opts.body === 'string' ? opts.body : '');
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function() {
        return Promise.resolve([]);
      };
    }
  } catch (e) {
    report('service_worker_override', e);
  }
})();
"""

internal fun webAppShimJs(
    initialBadge: Int,
    bridged: Boolean = true,
): String =
    WEB_APP_SHIM_TEMPLATE
        .replace("__FHG_INITIAL_BADGE__", initialBadge.coerceIn(0, WEB_APP_BADGE_MAX).toString())
        .replace("__FHG_BRIDGED__", bridged.toString())

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
    val installError: String? = null,
)

internal fun parseShimSignal(message: String?): WebAppShimSignal? {
    if (message.isNullOrBlank()) return null
    val objectValue = runCatching { SHIM_JSON.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    val badge = objectValue["badge"]?.jsonPrimitive?.intOrNull
    val title = objectValue["title"]?.jsonPrimitive?.contentOrNull?.normalizedNotificationText()
    val body = objectValue["body"]?.jsonPrimitive?.contentOrNull?.normalizedNotificationText()
    val installError = objectValue["error"]?.jsonPrimitive?.contentOrNull?.normalizedNotificationText()
    val notification = WebAppNotificationContent(title = title, body = body).takeIf {
        it.title != null || it.body != null
    }
    if (badge == null && notification == null && installError == null) return null
    return WebAppShimSignal(badge = badge, notification = notification, installError = installError)
}

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
