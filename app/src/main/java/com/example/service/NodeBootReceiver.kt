package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class NodeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val config = NodePreferences(context).load()
        if (!config.backgroundRunningEnabled || !config.autoStartOnBoot || !config.wasRunning) {
            return
        }
        // Only resume automatically if the user is signed in to Firebase Auth
        if (Firebase.auth.currentUser == null) {
            return
        }
        try {
            val serviceIntent = Intent(context, BandwidthNodeService::class.java).apply {
                this.action = BandwidthNodeService.ACTION_START
                putExtra(BandwidthNodeService.EXTRA_DAILY_CAP_MB, config.dailyCapMb)
                putExtra(BandwidthNodeService.EXTRA_RATE_PER_100MB, config.ratePer100MbInr)
                putExtra(BandwidthNodeService.EXTRA_WIFI_ONLY, config.wifiOnly)
                putExtra(BandwidthNodeService.EXTRA_MIN_BATTERY_PCT, config.minBatteryPct)
                putExtra(BandwidthNodeService.EXTRA_TARGET_MB_PER_MIN, config.targetMbPerMin)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.w("NodeBootReceiver", "Could not auto-start background node service", e)
        }
    }
}
