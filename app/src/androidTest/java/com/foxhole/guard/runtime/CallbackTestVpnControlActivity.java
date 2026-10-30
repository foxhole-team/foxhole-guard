package com.foxhole.guard.runtime;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

public final class CallbackTestVpnControlActivity extends Activity {
    public static final String ACTION_PREPARE = "com.foxhole.guard.testvpn.PREPARE";
    public static final String ACTION_START = "com.foxhole.guard.testvpn.START";
    public static final String ACTION_STOP = "com.foxhole.guard.testvpn.STOP";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final int VPN_PERMISSION_REQUEST = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (ACTION_STOP.equals(getIntent().getAction())) {
            startService(new Intent(this, CallbackTestVpnService.class).setAction(ACTION_STOP));
            finish();
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission == null) {
            startIfRequested();
        } else {
            startActivityForResult(permission, VPN_PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startIfRequested();
        } else {
            finish();
        }
    }

    private void startIfRequested() {
        if (ACTION_START.equals(getIntent().getAction())) {
            String target = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
            if (target == null || target.isEmpty()) {
                throw new IllegalArgumentException("target package is missing");
            }
            startForegroundService(new Intent(this, CallbackTestVpnService.class).putExtra(EXTRA_TARGET_PACKAGE, target));
        }
        finish();
    }
}
