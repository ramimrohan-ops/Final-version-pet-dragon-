package com.ramim.homedragon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.enabled(context) && Settings.canDrawOverlays(context)
        ) {
            context.startForegroundService(Intent(context, DragonService::class.java))
        }
    }
}
