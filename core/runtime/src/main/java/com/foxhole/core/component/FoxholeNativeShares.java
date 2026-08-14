package com.foxhole.core.component;

/** Exact static JNI surface for the root-owned encrypted share vault. */
public final class FoxholeNativeShares {
    public static native int nativeOpenVault(long handle, String root, byte[] key);

    public static native long nativeCreateShare(
            long handle,
            long nowMs,
            long expiresAtMs,
            int maxDownloads,
            byte[] password);

    public static native String nativeAddFile(
            long shareHandle,
            long nowMs,
            String displayName,
            String mediaType,
            String sourcePath);

    public static native int nativeRevokeShare(long shareHandle);

    public static native String nativeDrainShareEvents(long shareHandle, int max);

    public static native long nativePublishShare(long handle, String nickname, int virtualPort);

    public static native String nativePublicationAddress(long publicationHandle);

    public static native int nativeWithdrawShare(long publicationHandle);

    public static native int nativeWriteInvitation(
            long publicationHandle,
            long shareHandle,
            String fileIdHex,
            String destinationPath);

    private FoxholeNativeShares() {
    }
}
