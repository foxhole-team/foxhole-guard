package com.foxhole.guard.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

public final class CallbackTestVpnService extends VpnService {
    private static final String CHANNEL_ID = "callback_test_vpn";
    private static final int NOTIFICATION_ID = 20601;
    private static final long MAX_LIFETIME_MS = 30000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::stopVpn;
    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CallbackTestVpnControlActivity.ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        String target = intent == null ? null : intent.getStringExtra(CallbackTestVpnControlActivity.EXTRA_TARGET_PACKAGE);
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("target package is missing");
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Callback test VPN", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Local callback test VPN")
                .setOngoing(true)
                .build());
        closeTun();
        try {
            tun = new Builder()
                    .setSession("FoxHole callback test")
                    .setMtu(1280)
                    .addAddress("10.207.0.2", 32)
                    .addDnsServer("10.207.0.1")
                    .addRoute("0.0.0.0", 0)
                    .addAllowedApplication(target)
                    .establish();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("target package is unavailable", error);
        }
        if (tun == null) {
            throw new IllegalStateException("test VPN consent is missing");
        }
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, MAX_LIFETIME_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(expire);
        closeTun();
        super.onDestroy();
    }

    private void stopVpn() {
        handler.removeCallbacks(expire);
        closeTun();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeTun() {
        if (tun == null) return;
        try {
            tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
    }
}
