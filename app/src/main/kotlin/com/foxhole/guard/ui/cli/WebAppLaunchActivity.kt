package com.foxhole.guard.ui.cli

import android.app.Activity
import android.os.Bundle
import com.foxhole.guard.core.webapps.WebAppOpenIntents

class WebAppLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            WebAppOpenIntents.launchTarget(this, intent)?.let { appId ->
                startActivity(WebAppOpenIntents.authorizedMainIntent(this, appId))
            }
        }
        finish()
    }
}
