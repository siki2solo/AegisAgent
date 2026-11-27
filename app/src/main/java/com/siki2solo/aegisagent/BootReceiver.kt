package com.siki2solo.aegisagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("aegis_agent_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_on_boot", false)
        if (!autoStart || !Settings.canDrawOverlays(context)) return

        try {
            val serviceIntent = Intent(context, AegisOverlayService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            // Android 12+ background FGS restrictions may defer startup until next user launch
        }
    }
}
