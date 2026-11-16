package com.example.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager

data class NetworkTelemetrySnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Offline",
    val isMetered: Boolean = true,
    val downstreamMbps: Double = 0.0,
    val upstreamMbps: Double = 0.0,
    val totalDeviceRxBytes: Long = 0L,
    val totalDeviceTxBytes: Long = 0L,
    val batteryPct: Int = 100,
    val isCharging: Boolean = false
)

class NetworkTelemetryMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun captureSnapshot(): NetworkTelemetrySnapshot {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isConnected = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val transportLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Network"
        }

        val isMetered = caps?.let {
            !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } ?: true

        val downMbps = (caps?.linkDownstreamBandwidthKbps ?: 0) / 1000.0
        val upMbps = (caps?.linkUpstreamBandwidthKbps ?: 0) / 1000.0

        val rx = TrafficStats.getTotalRxBytes().let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
        val tx = TrafficStats.getTotalTxBytes().let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return NetworkTelemetrySnapshot(
            isConnected = isConnected,
            transportLabel = transportLabel,
            isMetered = isMetered,
            downstreamMbps = downMbps,
            upstreamMbps = upMbps,
            totalDeviceRxBytes = rx,
            totalDeviceTxBytes = tx,
            batteryPct = batteryPct,
            isCharging = isCharging
        )
    }
}
