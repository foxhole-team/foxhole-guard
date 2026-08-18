package com.foxhole.core.runtime;

/**
 * Stable JNI facade for {@code libfoxhole_native.so}.
 *
 * <p>The package and class name are part of the native ABI and must stay in sync with the
 * {@code Java_com_foxhole_core_runtime_FoxholeNativeEngine_*} exports.
 */
public final class FoxholeNativeEngine {
    public static final int ABI_VERSION = 1;

    public static final int STOPPED = 0;
    public static final int ALREADY_STOPPED = 1;
    public static final int STOP_TIMED_OUT = 2;
    public static final int STOP_UNKNOWN_HANDLE = 3;
    public static final int STOP_PANICKED = -1;

    public static final int CONTINUITY_CONFIRMED = 0;
    public static final int CONTINUITY_NOTHING_PENDING = 1;
    public static final int CONTINUITY_STALE_TOKEN = 2;
    public static final int CONTINUITY_UNKNOWN_HANDLE = 3;

    // LAN proxy result codes. These are the core's existing component-ABI numbers, not a second
    // numbering invented for this call — keep them in step with foxcore-android's ecosystem codes.
    /** A panic crossed the JNI boundary. Same value the core's shared panic guard returns. */
    public static final int REVOKE_PANICKED = -1;
    public static final int REVOKE_NOT_RUNNING = -2;
    public static final int REVOKE_INVALID_TARGET = -3;

    public static final int LAN_OK = 0;
    public static final int LAN_INVALID_ARGUMENT = 1;
    public static final int LAN_NO_ENGINE = 2;
    public static final int LAN_ALREADY_EXISTS = 4;
    public static final int LAN_CAPACITY = 5;
    public static final int LAN_RUNTIME_UNAVAILABLE = 7;
    public static final int LAN_NETWORK_REFUSED = 9;
    public static final int LAN_NETWORK_UNCONFIRMED = 10;
    public static final int LAN_BIND_FAILED = 11;
    public static final int LAN_PANICKED = -1;

    public static final int RELOAD_INVALID = -1;
    public static final int RELOAD_UNKNOWN_OUTBOUND = -2;
    public static final int RELOAD_TOR_UNAVAILABLE = -3;
    public static final int RELOAD_I2P_UNAVAILABLE = -4;
    public static final int RELOAD_OVERLAY_WITHOUT_FAKE_IP = -5;
    public static final int RELOAD_NO_ATTRIBUTION = -6;
    public static final int RELOAD_REVISION_CONFLICT = -7;
    public static final int RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP = -8;
    public static final int RELOAD_PACKET_TUNNEL_REJECTS_PRIMARY_DNS = -9;

    static {
        System.loadLibrary("foxhole_native");
    }

    public static native String nativeVersion();

    /**
     * Where the last stop in this process spent its budget, as JSON.
     *
     * <p>Takes no handle on purpose: the caller that needs it has just been told its stop timed
     * out, and the handle it would pass is the one it is about to force-kill. Fields are {@code
     * phase} ({@code engine}, {@code runtime_shutdown} or {@code complete}), {@code engine_ms} and
     * {@code shutdown_ms} (null when that half was never reached) and the {@code generation} the
     * numbers belong to.
     */
    public static native String nativeLastStopDiagnostics();

    public static native int nativeAbiVersion();

    public static native String nativeCapabilities();

    public static native String nativeImportLink(String link);

    public static native String nativeImportSubscription(String body);

    /**
     * Starts FoxCore over a detached TUN descriptor and pins every protected socket and bootstrap
     * DNS lookup to the supplied Android network.
     *
     * <p>Native code takes ownership of {@code tunFd} on every path, including validation or start
     * failure.
     */
    public static native long nativeStartWithNetwork(
            int tunFd,
            String configJson,
            long networkHandle,
            Object host);

    public static native long nativeStartWithNetworkAndDnsRuleSet(
            int tunFd,
            String configJson,
            long networkHandle,
            String name,
            byte[] manifest,
            byte[] signature,
            byte[] artifact,
            Object host);

    /**
     * Starts with an FST artifact read from the signed APK. There is no unsigned update method:
     * network updates must use {@link #nativeInstallDnsRuleSet} and pass native signature checks.
     */
    public static native long nativeStartWithNetworkAndTrustedDnsRuleSet(
            int tunFd,
            String configJson,
            long networkHandle,
            String name,
            byte[] artifact,
            Object host);

    public static native long nativeInstallDnsRuleSet(
            long handle,
            String name,
            byte[] manifest,
            byte[] signature,
            byte[] artifact);

    public static native int nativeInstallTlsFingerprintTables(byte[] document);

    public static native void nativeClearTlsFingerprintTables();

    public static final int TLS_FINGERPRINT_TABLES_UNREADABLE = -1;
    public static final int TLS_FINGERPRINT_TABLES_REFUSED = -2;
    public static final int TLS_FINGERPRINT_TABLES_PANICKED = -3;

    public static native int nativeStop(long handle);

    public static native String nativeStats(long handle);

    public static native String nativeTrafficMap(long handle);

    public static native String nativeConnections(long handle);

    public static native String nativeDrainTrafficEvents(long handle, int max);

    public static native String nativeDrainEvents(long handle, int max);

    public static native long nativeReloadPolicy(long handle, String policyJson);

    public static native String nativeLastPolicyError(long handle);

    public static native int nativeForceKill(long handle);

    public static native int nativeConfirmContinuity(long handle, long token);

    public static native void nativeNetworkChanged(long handle);

    public static native void nativeNetworkChangedWithHandle(long handle, long networkHandle);

    /**
     * Cuts the live flows matching {@code targetJson} — the primitive behind "block this app now".
     *
     * <p>A policy reload deliberately preserves flows that are already open, because a routing
     * change must not kill a download. Blocking is the opposite promise: the user expects the app
     * to be off the network at once, not when its TCP connections happen to close. This is that
     * second half, kept separate so the caller decides which one it wants.
     *
     * <p>The target is internally tagged on {@code kind}, with exactly one argument field:
     * {@code {"kind":"all"}}, {@code {"kind":"lane","lane":"vpn|tor|i2p|direct"}},
     * {@code {"kind":"uid","uid":10123}}, {@code {"kind":"package","package":"com.example"}},
     * {@code {"kind":"outbound","outbound":"default"}} or {@code {"kind":"flow","flow":42}}.
     * Unknown kinds, missing fields and extra fields are all refused rather than widened.
     *
     * <p>Returns the number of flows revoked ({@code 0} is success — nothing was talking), or one
     * of the negative {@code REVOKE_*} refusals.
     */
    public static native int nativeRevokeFlows(long handle, String targetJson);

    /**
     * Confirms, for this session only, that the Wi-Fi the LAN proxy is about to be published on is
     * one the user trusts. The core refuses to bind a LAN listener on an unconfirmed network, so
     * this must succeed before {@link #nativeStartLanProxy}.
     */
    public static native int nativeConfirmLanNetwork(
            long handle,
            long networkHandle,
            String localAddress,
            String interfaceName,
            String transport);

    /**
     * Publishes the LAN proxy on the confirmed network. The JSON carries the upstream preset, the
     * two ports (0 = do not offer that protocol), the mandatory credentials and the network binding.
     * Returns {@link #LAN_OK} or a negative refusal code — never a partially-bound surface.
     */
    public static native int nativeStartLanProxy(long handle, String configJson);

    /** Idempotent: tears down both listeners and invalidates the published credentials. */
    public static native int nativeStopLanProxy(long handle);

    /**
     * The core's own account of the LAN proxy: {@code state}, {@code socks_address},
     * {@code http_address}, {@code preset}, {@code local_address}, {@code network_handle} and
     * {@code last_error}. This is the single source of truth for the UI — the saved switch is a
     * request, not a state.
     */
    public static native String nativeLanProxyStatus(long handle);

    /**
     * Raises one named loopback inbound on the live engine: an authenticated — or, by the owner's
     * choice, anonymous — HTTP CONNECT listener on {@code 127.0.0.1} whose upstream is named rather
     * than inferred.
     *
     * <p>The JSON is {@code {name, http_port?, username?, password?, upstream, max_sessions?}}.
     * A {@code http_port} of 0 asks the kernel for an ephemeral port, which is the recommended
     * form: a phone has no port registry, so a fixed number is a coin flip against every other app
     * on the device. The port that was actually bound is read back from
     * {@link #nativeLoopbackInbounds}, never assumed.
     *
     * <p>Credentials are absent together or present together. Absent is an anonymous listener, and
     * it is allowed here and nowhere else: this one binds loopback, so what it opens to is the apps
     * already on the phone rather than the network around it.
     */
    public static native int nativeStartLoopbackInbound(long handle, String configJson);

    /** Idempotent by name: stops the listener and forgets its credentials. */
    public static native int nativeStopLoopbackInbound(long handle, String name);

    /**
     * Every named loopback inbound that is listening, as {@code {"inbounds":[{name, upstream,
     * state, http_address}]}}.
     *
     * <p>The single source of truth for the screen: the port may have been ephemeral, and a saved
     * preference is a request rather than a state. A stopped engine answers with an empty list
     * rather than an error, so the caller never has to tell "nothing running" from "call failed".
     */
    public static native String nativeLoopbackInbounds(long handle);

    public static String reloadCode(long value) {
        if (value > 0) {
            return "revision=" + value;
        }
        switch ((int) value) {
            case 0:
                return "not_running";
            case RELOAD_INVALID:
                return "invalid_policy";
            case RELOAD_UNKNOWN_OUTBOUND:
                return "unknown_outbound";
            case RELOAD_TOR_UNAVAILABLE:
                return "tor_unavailable";
            case RELOAD_I2P_UNAVAILABLE:
                return "i2p_unavailable";
            case RELOAD_OVERLAY_WITHOUT_FAKE_IP:
                return "overlay_without_fake_ip";
            case RELOAD_NO_ATTRIBUTION:
                return "no_attribution";
            case RELOAD_REVISION_CONFLICT:
                return "revision_conflict";
            case RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP:
                return "packet_tunnel_rejects_fake_ip";
            case RELOAD_PACKET_TUNNEL_REJECTS_PRIMARY_DNS:
                return "packet_tunnel_rejects_primary_dns";
            default:
                return "unknown_code=" + value;
        }
    }

    private FoxholeNativeEngine() {
    }
}
