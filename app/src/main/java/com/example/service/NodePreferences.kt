package com.example.service

import android.content.Context

data class PersistedNodeConfig(
    val wasRunning: Boolean = false,
    val backgroundRunningEnabled: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val dailyCapMb: Int = 10000,
    val ratePer100MbInr: Double = 0.85,
    val wifiOnly: Boolean = true,
    val minBatteryPct: Int = 25,
    val targetMbPerMin: Int = 0 // 0 = MAX Unlimited Link Speed (up to 4096 MB/min paced)
)

class NodePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedNodeConfig = PersistedNodeConfig(
        wasRunning = prefs.getBoolean(KEY_WAS_RUNNING, false),
        backgroundRunningEnabled = prefs.getBoolean(KEY_BACKGROUND_ENABLED, true),
        autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START_BOOT, true),
        dailyCapMb = prefs.getInt(KEY_DAILY_CAP_MB, 10000).coerceIn(50, 50000),
        ratePer100MbInr = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_RATE_BITS, java.lang.Double.doubleToRawLongBits(0.85))
        ).takeIf { it in 0.1..100.0 } ?: 0.85,
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        minBatteryPct = prefs.getInt(KEY_MIN_BATTERY, 25).coerceIn(10, 90),
        targetMbPerMin = prefs.getInt(KEY_TARGET_MB_MIN, 0).coerceIn(0, 4096)
    )

    fun saveNodeStarted(
        dailyCapMb: Int,
        ratePer100MbInr: Double,
        wifiOnly: Boolean,
        minBatteryPct: Int,
        targetMbPerMin: Int
    ) {
        prefs.edit()
            .putBoolean(KEY_WAS_RUNNING, true)
            .putInt(KEY_DAILY_CAP_MB, dailyCapMb.coerceIn(50, 50000))
            .putLong(KEY_RATE_BITS, java.lang.Double.doubleToRawLongBits(ratePer100MbInr))
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .putInt(KEY_MIN_BATTERY, minBatteryPct)
            .putInt(KEY_TARGET_MB_MIN, targetMbPerMin.coerceIn(0, 4096))
            .apply()
    }

    fun updateTargetSpeed(targetMbPerMin: Int) {
        prefs.edit()
            .putInt(KEY_TARGET_MB_MIN, targetMbPerMin.coerceIn(0, 4096))
            .apply()
    }

    fun saveNodeStopped() {
        prefs.edit()
            .putBoolean(KEY_WAS_RUNNING, false)
            .apply()
    }

    fun setBackgroundRunning(enabled: Boolean, autoStartOnBoot: Boolean = enabled) {
        prefs.edit()
            .putBoolean(KEY_BACKGROUND_ENABLED, enabled)
            .putBoolean(KEY_AUTO_START_BOOT, autoStartOnBoot)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "datamint_node_prefs"
        const val KEY_WAS_RUNNING = "was_running"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_AUTO_START_BOOT = "auto_start_boot"
        const val KEY_DAILY_CAP_MB = "daily_cap_mb"
        const val KEY_RATE_BITS = "rate_bits"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_MIN_BATTERY = "min_battery"
        const val KEY_TARGET_MB_MIN = "target_mb_min"
    }
}
