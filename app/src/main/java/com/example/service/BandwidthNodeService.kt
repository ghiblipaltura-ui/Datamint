package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.remote.RelayGatewayClient
import com.example.data.repository.DataMintRepository
import com.example.telemetry.NetworkTelemetryMonitor
import com.example.telemetry.NetworkTelemetrySnapshot
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class LiveNodeState(
    val isRunning: Boolean = false,
    val isPausedByGuard: Boolean = false,
    val guardReason: String? = null,
    val elapsedSeconds: Int = 0,
    val sessionMbMeasured: Double = 0.0,
    val estimatedEarningsInr: Double = 0.0,
    val persistedMb: Double = 0.0,
    val persistedEarningsInr: Double = 0.0,
    val uncreditedMb: Double = 0.0,
    val uncreditedEarningsInr: Double = 0.0,
    val telemetry: NetworkTelemetrySnapshot = NetworkTelemetrySnapshot(),
    val dailyCapMb: Int = 10000,
    val ratePer100MbInr: Double = 0.85,
    val wifiOnly: Boolean = true,
    val minBatteryPct: Int = 25,
    val speedMultiplier: Int = 64,
    val allocatedStreams: Int = 64,
    val samplingIntervalMs: Long = 50L,
    val liveSpeedKbps: Double = 0.0,
    val liveThroughputMbps: Double = 0.0,
    val targetMbPerMinute: Double = 0.0, // 0.0 = MAX Unthrottled Link Speed
    val liveMbPerMinute: Double = 0.0,
    val relayConfigured: Boolean = false,
    val relayStatusLabel: String = "Passive device metering",
    val backgroundRunningEnabled: Boolean = true
)

class BandwidthNodeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var relayJob: Job? = null
    private lateinit var telemetryMonitor: NetworkTelemetryMonitor
    private lateinit var repository: DataMintRepository
    private lateinit var nodePrefs: NodePreferences
    private val relayClient = RelayGatewayClient()
    private var connectivityManager: ConnectivityManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val creditMutex = Mutex()

    private var baselineRxTxBytes: Long = 0L
    private var persistedSessionMb: Double = 0.0
    private var persistedSessionInr: Double = 0.0
    private var lastCreditFlushMs: Long = 0L
    private val isFlushingCredits = java.util.concurrent.atomic.AtomicBoolean(false)
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wakeSignal.trySend(Unit)
        }

        override fun onLost(network: Network) {
            wakeSignal.trySend(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            wakeSignal.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        telemetryMonitor = NetworkTelemetryMonitor(applicationContext)
        repository = DataMintRepository(applicationContext)
        nodePrefs = NodePreferences(applicationContext)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataMint:BandwidthNodeWakeLock")
            ?.apply { setReferenceCounted(false) }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm?.createWifiLock(wifiLockMode, "DataMint:BandwidthNodeWifiLock")
            ?.apply { setReferenceCounted(false) }
        registerNetworkCallbackSafe()
        createNotificationChannel()
    }

    private fun acquireWakeLockSafe() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock/WifiLock acquire skipped", e)
        }
    }

    private fun releaseWakeLockSafe() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock/WifiLock release skipped", e)
        }
    }

    private fun registerNetworkCallbackSafe() {
        if (networkCallbackRegistered) return
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
    }

    private fun unregisterNetworkCallbackSafe() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Ignore if already unregistered
        } finally {
            networkCallbackRegistered = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val saved = nodePrefs.load()
        when (intent?.action) {
            ACTION_STOP -> {
                nodePrefs.saveNodeStopped()
                stopNode(recordToLedger = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_AND_RECORD -> {
                nodePrefs.saveNodeStopped()
                stopNode(recordToLedger = true)
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                val newTarget = intent.getIntExtra(EXTRA_TARGET_MB_PER_MIN, saved.targetMbPerMin).coerceIn(0, 4096)
                val streams = if (newTarget == 0 || newTarget >= 1024) 64 else (newTarget / 32).coerceIn(4, 64)
                nodePrefs.updateTargetSpeed(newTarget)
                _nodeState.update {
                    it.copy(
                        speedMultiplier = streams,
                        allocatedStreams = streams,
                        samplingIntervalMs = 50L,
                        targetMbPerMinute = newTarget.toDouble()
                    )
                }
                wakeSignal.trySend(Unit)
                return START_STICKY
            }
            ACTION_UPDATE_BACKGROUND -> {
                val bgEnabled = intent.getBooleanExtra(EXTRA_BACKGROUND_ENABLED, true)
                nodePrefs.setBackgroundRunning(bgEnabled, bgEnabled)
                _nodeState.update { it.copy(backgroundRunningEnabled = bgEnabled) }
                return if (_nodeState.value.isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_START -> {
                val dailyCap = intent.getIntExtra(EXTRA_DAILY_CAP_MB, saved.dailyCapMb)
                val rate = intent.getDoubleExtra(EXTRA_RATE_PER_100MB, saved.ratePer100MbInr)
                val wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, saved.wifiOnly)
                val minBatt = intent.getIntExtra(EXTRA_MIN_BATTERY_PCT, saved.minBatteryPct)
                val targetMbMin = intent.getIntExtra(EXTRA_TARGET_MB_PER_MIN, saved.targetMbPerMin).coerceIn(0, 4096)
                val bgEnabled = intent.getBooleanExtra(EXTRA_BACKGROUND_ENABLED, saved.backgroundRunningEnabled)
                nodePrefs.setBackgroundRunning(bgEnabled, bgEnabled)
                nodePrefs.saveNodeStarted(dailyCap, rate, wifiOnly, minBatt, targetMbMin)
                startNode(dailyCap, rate, wifiOnly, minBatt, targetMbMin, bgEnabled)
            }
            null -> {
                // Sticky restart by Android OS after low-memory kill
                if (!saved.wasRunning || !saved.backgroundRunningEnabled || Firebase.auth.currentUser == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startNode(
                    dailyCapMb = saved.dailyCapMb,
                    ratePer100MbInr = saved.ratePer100MbInr,
                    wifiOnly = saved.wifiOnly,
                    minBatteryPct = saved.minBatteryPct,
                    targetMbPerMin = saved.targetMbPerMin,
                    backgroundRunningEnabled = saved.backgroundRunningEnabled
                )
            }
        }
        return START_STICKY
    }

    private fun startNode(
        dailyCapMb: Int,
        ratePer100MbInr: Double,
        wifiOnly: Boolean,
        minBatteryPct: Int,
        targetMbPerMin: Int,
        backgroundRunningEnabled: Boolean
    ) {
        acquireWakeLockSafe()
        val initialSnapshot = telemetryMonitor.captureSnapshot()
        baselineRxTxBytes = initialSnapshot.totalDeviceRxBytes + initialSnapshot.totalDeviceTxBytes
        persistedSessionMb = 0.0
        persistedSessionInr = 0.0
        lastCreditFlushMs = 0L

        val streams = if (targetMbPerMin == 0 || targetMbPerMin >= 1024) 64 else (targetMbPerMin / 32).coerceIn(4, 64)
        val isRelayConfigured = relayClient.isConfigured()
        val targetLabel = if (targetMbPerMin == 0) {
            "MAX (64 streams)"
        } else if (targetMbPerMin >= 1024) {
            String.format(java.util.Locale.US, "%.2f GB/min", targetMbPerMin / 1024.0)
        } else {
            "$targetMbPerMin MB/min"
        }
        val initialRelayLabel = if (isRelayConfigured) {
            "Connecting relay (${relayClient.configuredHostLabel()}) • $targetLabel"
        } else {
            "64-stream MAX metering active (set BANDWIDTH_RELAY_URL in Secrets for active high-speed relay)"
        }

        _nodeState.value = LiveNodeState(
            isRunning = true,
            telemetry = initialSnapshot,
            dailyCapMb = dailyCapMb,
            ratePer100MbInr = ratePer100MbInr,
            wifiOnly = wifiOnly,
            minBatteryPct = minBatteryPct,
            speedMultiplier = streams,
            allocatedStreams = streams,
            samplingIntervalMs = 50L,
            liveSpeedKbps = 0.0,
            liveThroughputMbps = 0.0,
            targetMbPerMinute = targetMbPerMin.toDouble(),
            liveMbPerMinute = 0.0,
            relayConfigured = isRelayConfigured,
            relayStatusLabel = initialRelayLabel,
            backgroundRunningEnabled = backgroundRunningEnabled
        )

        val notification = buildNotification("Background • $targetLabel • 0.00 MB")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        relayJob?.cancel()
        if (isRelayConfigured) {
            relayJob = serviceScope.launch {
                relayClient.runPacedRelayPool(
                    auth = Firebase.auth,
                    targetMbPerMinuteProvider = { _nodeState.value.targetMbPerMinute.toInt().coerceIn(0, 4096) },
                    isPausedProvider = {
                        val s = _nodeState.value
                        !s.isRunning || s.isPausedByGuard
                    },
                    onStatusChanged = { label ->
                        _nodeState.update { it.copy(relayStatusLabel = label) }
                    }
                )
            }
        }

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val sessionStartNanos = SystemClock.elapsedRealtimeNanos()
            var windowStartNanos = sessionStartNanos
            var windowStartBytes = baselineRxTxBytes
            var smoothedKbps = 0.0
            var smoothedMbps = 0.0
            var lastNotificationMs = 0L
            var lastWakeRefreshMs = SystemClock.elapsedRealtime()

            while (isActive) {
                withTimeoutOrNull(50L) {
                    wakeSignal.receive()
                }
                if (!isActive) break

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastWakeRefreshMs >= 30 * 60 * 1000L) {
                    lastWakeRefreshMs = nowMs
                    acquireWakeLockSafe()
                }

                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val snap = telemetryMonitor.captureSnapshot()
                val currentTotal = snap.totalDeviceRxBytes + snap.totalDeviceTxBytes
                val deltaBytes = (currentTotal - baselineRxTxBytes).coerceAtLeast(0L)
                val measuredMb = deltaBytes / (1024.0 * 1024.0)

                val windowElapsedSec = ((nowNanos - windowStartNanos).coerceAtLeast(1_000_000L)) / 1_000_000_000.0
                if (windowElapsedSec >= 0.25) {
                    val windowBytes = (currentTotal - windowStartBytes).coerceAtLeast(0L)
                    val instantKbps = (windowBytes / 1024.0) / windowElapsedSec
                    val instantMbps = (windowBytes * 8.0) / (windowElapsedSec * 1_000_000.0)
                    smoothedKbps = if (smoothedKbps == 0.0) instantKbps else (smoothedKbps * 0.4 + instantKbps * 0.6)
                    smoothedMbps = if (smoothedMbps == 0.0) instantMbps else (smoothedMbps * 0.4 + instantMbps * 0.6)
                    windowStartNanos = nowNanos
                    windowStartBytes = currentTotal
                }

                val guardReason = when {
                    !snap.isConnected -> "Waiting for internet connection"
                    wifiOnly && snap.transportLabel != "Wi-Fi" && snap.transportLabel != "Ethernet" ->
                        "Paused: Wi-Fi Only guard active"
                    snap.batteryPct < minBatteryPct && !snap.isCharging ->
                        "Paused: Battery below $minBatteryPct%"
                    measuredMb >= dailyCapMb.toDouble() ->
                        "Daily cap of $dailyCapMb MB reached"
                    else -> null
                }

                val cappedMb = measuredMb.coerceAtMost(dailyCapMb.toDouble())
                val estEarnings = (cappedMb / 100.0) * ratePer100MbInr
                val elapsedSecs = ((nowNanos - sessionStartNanos) / 1_000_000_000L).toInt().coerceAtLeast(0)
                val currentTarget = _nodeState.value.targetMbPerMinute.coerceIn(0.0, 4096.0)
                val currentStreams = if (currentTarget == 0.0 || currentTarget >= 1024.0) 64 else (currentTarget.toInt() / 32).coerceIn(4, 64)
                val activeKbps = if (guardReason != null) 0.0 else smoothedKbps
                val activeMbps = if (guardReason != null) 0.0 else smoothedMbps
                val liveMbPerMin = (activeKbps * 60.0) / 1024.0

                val pendingMb = (cappedMb - persistedSessionMb).coerceAtLeast(0.0)
                val pendingInr = (estEarnings - persistedSessionInr).coerceAtLeast(0.0)

                _nodeState.update { state ->
                    state.copy(
                        isPausedByGuard = guardReason != null,
                        guardReason = guardReason,
                        elapsedSeconds = elapsedSecs,
                        sessionMbMeasured = cappedMb,
                        estimatedEarningsInr = estEarnings,
                        persistedMb = persistedSessionMb,
                        persistedEarningsInr = persistedSessionInr,
                        uncreditedMb = pendingMb,
                        uncreditedEarningsInr = pendingInr,
                        telemetry = snap,
                        speedMultiplier = currentStreams,
                        allocatedStreams = currentStreams,
                        samplingIntervalMs = 50L,
                        liveSpeedKbps = activeKbps,
                        liveThroughputMbps = activeMbps,
                        targetMbPerMinute = currentTarget,
                        liveMbPerMinute = liveMbPerMin
                    )
                }

                // Background-safe Firestore auto-crediting independent of MainActivity lifecycle
                if ((pendingMb >= 0.0005 || pendingInr >= 0.000005) &&
                    (nowMs - lastCreditFlushMs >= 800L || pendingMb >= 0.05)
                ) {
                    launchBackgroundCreditFlush(cappedMb, estEarnings)
                }

                if (nowMs - lastNotificationMs >= 1000L) {
                    lastNotificationMs = nowMs
                    val statusLine = if (guardReason != null) {
                        guardReason
                    } else if (currentTarget == 0.0) {
                        String.format(
                            java.util.Locale.US,
                            "%s MAX • %.1f MB/min • %.2f MB • ₹%.4f",
                            snap.transportLabel,
                            liveMbPerMin,
                            cappedMb,
                            estEarnings
                        )
                    } else {
                        String.format(
                            java.util.Locale.US,
                            "%s • %.1f/%.0f MB/min • %.2f MB • ₹%.4f",
                            snap.transportLabel,
                            liveMbPerMin,
                            currentTarget,
                            cappedMb,
                            estEarnings
                        )
                    }
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(statusLine))
                }
            }
        }
    }

    private fun launchBackgroundCreditFlush(totalMeasuredMb: Double, totalEarningsInr: Double) {
        if (Firebase.auth.currentUser == null) return
        if (!isFlushingCredits.compareAndSet(false, true)) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                flushCreditsLocked(totalMeasuredMb, totalEarningsInr)
            } finally {
                isFlushingCredits.set(false)
            }
        }
    }

    private suspend fun flushCreditsLocked(totalMeasuredMb: Double, totalEarningsInr: Double) {
        if (Firebase.auth.currentUser == null) return
        creditMutex.withLock {
            val deltaMb = (totalMeasuredMb - persistedSessionMb).coerceAtLeast(0.0)
            val deltaInr = (totalEarningsInr - persistedSessionInr).coerceAtLeast(0.0)
            if (deltaMb >= 0.0005 || deltaInr >= 0.000005) {
                repository.creditIncrementalWallet(deltaMb, deltaInr).onSuccess { (savedMb, savedInr) ->
                    lastCreditFlushMs = SystemClock.elapsedRealtime()
                    persistedSessionMb += savedMb
                    persistedSessionInr += savedInr
                    _nodeState.update { state ->
                        state.copy(
                            persistedMb = persistedSessionMb,
                            persistedEarningsInr = persistedSessionInr,
                            uncreditedMb = (state.sessionMbMeasured - persistedSessionMb).coerceAtLeast(0.0),
                            uncreditedEarningsInr = (state.estimatedEarningsInr - persistedSessionInr).coerceAtLeast(0.0)
                        )
                    }
                }
            }
        }
    }

    private fun stopNode(recordToLedger: Boolean) {
        val finalState = _nodeState.value
        relayJob?.cancel()
        relayJob = null
        monitorJob?.cancel()
        monitorJob = null

        _nodeState.update {
            it.copy(
                isRunning = false,
                liveSpeedKbps = 0.0,
                liveThroughputMbps = 0.0,
                liveMbPerMinute = 0.0,
                uncreditedMb = if (recordToLedger) 0.0 else it.uncreditedMb,
                uncreditedEarningsInr = if (recordToLedger) 0.0 else it.uncreditedEarningsInr
            )
        }

        if (recordToLedger && finalState.sessionMbMeasured >= 0.0001 && Firebase.auth.currentUser != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    creditMutex.withLock {
                        val remainingMb = (finalState.sessionMbMeasured - persistedSessionMb).coerceAtLeast(0.0)
                        val remainingInr = (finalState.estimatedEarningsInr - persistedSessionInr).coerceAtLeast(0.0)
                        repository.recordSessionAndCreditWallet(
                            networkType = finalState.telemetry.transportLabel,
                            mbShared = finalState.sessionMbMeasured,
                            estimatedEarningsInr = finalState.estimatedEarningsInr,
                            avgSpeedMbps = finalState.telemetry.downstreamMbps,
                            durationSeconds = finalState.elapsedSeconds,
                            status = if (finalState.sessionMbMeasured >= finalState.dailyCapMb) "capped" else "completed",
                            uncreditedMb = remainingMb,
                            uncreditedEarningsInr = remainingInr
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error recording session on background stop", e)
                } finally {
                    releaseWakeLockSafe()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            releaseWakeLockSafe()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = _nodeState.value
        val config = nodePrefs.load()
        if (current.isRunning) {
            if (config.backgroundRunningEnabled) {
                launchBackgroundCreditFlush(current.sessionMbMeasured, current.estimatedEarningsInr)
            } else {
                nodePrefs.saveNodeStopped()
                stopNode(recordToLedger = true)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached; saving background session cleanly")
        nodePrefs.saveNodeStopped()
        stopNode(recordToLedger = true)
        super.onTimeout(startId, fgsType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bandwidth Node",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live network telemetry and MB sharing quota status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BandwidthNodeService::class.java).apply {
                action = ACTION_STOP_AND_RECORD
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.node_active))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop & Save", stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkCallbackSafe()
        releaseWakeLockSafe()
        relayJob?.cancel()
        monitorJob?.cancel()
        serviceScope.cancel()
        wakeSignal.close()
        _nodeState.update {
            it.copy(
                isRunning = false,
                liveSpeedKbps = 0.0,
                liveThroughputMbps = 0.0,
                liveMbPerMinute = 0.0
            )
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BandwidthNodeService"

        const val ACTION_START = "com.example.action.START_NODE"
        const val ACTION_STOP = "com.example.action.STOP_NODE"
        const val ACTION_STOP_AND_RECORD = "com.example.action.STOP_AND_RECORD"
        const val ACTION_UPDATE_SPEED = "com.example.action.UPDATE_SPEED"
        const val ACTION_UPDATE_BACKGROUND = "com.example.action.UPDATE_BACKGROUND"

        const val EXTRA_DAILY_CAP_MB = "extra_daily_cap_mb"
        const val EXTRA_RATE_PER_100MB = "extra_rate_per_100mb"
        const val EXTRA_WIFI_ONLY = "extra_wifi_only"
        const val EXTRA_MIN_BATTERY_PCT = "extra_min_battery_pct"
        const val EXTRA_TARGET_MB_PER_MIN = "extra_target_mb_per_min"
        const val EXTRA_BACKGROUND_ENABLED = "extra_background_enabled"

        private const val CHANNEL_ID = "datamint_node_channel"
        private const val NOTIFICATION_ID = 4201

        private val _nodeState = MutableStateFlow(LiveNodeState())
        val nodeState: StateFlow<LiveNodeState> = _nodeState.asStateFlow()
    }
}
