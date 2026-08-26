package com.foxhole.core.runtime;

/** Class and method names are JNI ABI and must match the native exports exactly. */
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

    public static native String nativeLastStopDiagnostics();

    public static native int nativeAbiVersion();

    public static native String nativeCapabilities();

    public static native String nativeImportLink(String link);

    public static native String nativeImportSubscription(String body);

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

    public static native int nativeRevokeFlows(long handle, String targetJson);

    public static native int nativeConfirmLanNetwork(
            long handle,
            long networkHandle,
            String localAddress,
            String interfaceName,
            String transport);

    public static native int nativeStartLanProxy(long handle, String configJson);

    /** Idempotent: tears down both listeners and invalidates the published credentials. */
    public static native int nativeStopLanProxy(long handle);

    public static native String nativeLanProxyStatus(long handle);

    public static native int nativeStartLoopbackInbound(long handle, String configJson);

    /** Idempotent by name: stops the listener and forgets its credentials. */
    public static native int nativeStopLoopbackInbound(long handle, String name);

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
