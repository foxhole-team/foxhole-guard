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

    public static final int RELOAD_INVALID = -1;
    public static final int RELOAD_UNKNOWN_OUTBOUND = -2;
    public static final int RELOAD_TOR_UNAVAILABLE = -3;
    public static final int RELOAD_I2P_UNAVAILABLE = -4;
    public static final int RELOAD_OVERLAY_WITHOUT_FAKE_IP = -5;
    public static final int RELOAD_NO_ATTRIBUTION = -6;
    public static final int RELOAD_REVISION_CONFLICT = -7;
    public static final int RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP = -8;

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

    public static native int nativeStop(long handle);

    public static native String nativeStats(long handle);

    public static native String nativeConnections(long handle);

    public static native String nativeDrainEvents(long handle, int max);

    public static native long nativeReloadPolicy(long handle, String policyJson);

    public static native String nativeLastPolicyError(long handle);

    public static native int nativeForceKill(long handle);

    public static native int nativeConfirmContinuity(long handle, long token);

    public static native void nativeNetworkChanged(long handle);

    public static native void nativeNetworkChangedWithHandle(long handle, long networkHandle);

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
            default:
                return "unknown_code=" + value;
        }
    }

    private FoxholeNativeEngine() {
    }
}
