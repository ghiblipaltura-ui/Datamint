package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.UserProfile
import com.example.data.remote.RelayGatewayClient
import com.example.service.BandwidthNodeService
import com.example.service.NodePreferences
import com.example.telemetry.NetworkTelemetryMonitor
import com.example.telemetry.NetworkTelemetrySnapshot
import com.example.ui.components.AnimatedSellingCatStage
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun NodeDashboardScreen(
    profileState: UiState<UserProfile?>,
    liveUncreditedMb: Double = 0.0,
    liveUncreditedInr: Double = 0.0,
    isSubmitting: Boolean,
    onSaveSettings: (Boolean, Int, Int, Double, String?) -> Unit,
    onSessionFinished: (String, Double, Double, Double, Int, String) -> Unit
) {
    val context = LocalContext.current
    val liveNode by BandwidthNodeService.nodeState.collectAsStateWithLifecycle()
    val monitor = remember(context) { NetworkTelemetryMonitor(context.applicationContext) }
    var idleTelemetry by remember { mutableStateOf(monitor.captureSnapshot()) }

    LaunchedEffect(liveNode.isRunning) {
        if (!liveNode.isRunning) {
            while (true) {
                idleTelemetry = monitor.captureSnapshot()
                delay(2000L)
            }
        }
    }

    val activeTelemetry: NetworkTelemetrySnapshot =
        if (liveNode.isRunning) liveNode.telemetry else idleTelemetry

    val profile = (profileState as? UiState.Success)?.data
    var wifiOnly by remember(profile?.wifiOnly) { mutableStateOf(profile?.wifiOnly ?: true) }
    var dailyCapMb by remember(profile?.dailyCapMb) { mutableFloatStateOf((profile?.dailyCapMb ?: 1000).toFloat()) }
    var minBatteryPct by remember(profile?.minBatteryPct) { mutableFloatStateOf((profile?.minBatteryPct ?: 25).toFloat()) }
    var rateText by remember(profile?.ratePer100MbInr) {
        mutableStateOf(String.format(Locale.US, "%.2f", profile?.ratePer100MbInr ?: 0.85))
    }

    val nodePrefs = remember(context) { NodePreferences(context.applicationContext) }
    var backgroundRunningEnabled by remember { mutableStateOf(nodePrefs.load().backgroundRunningEnabled) }
    var selectedTargetMbPerMin by remember {
        mutableIntStateOf(
            if (liveNode.isRunning) liveNode.targetMbPerMinute.roundToInt() else nodePrefs.load().targetMbPerMin
        )
    }
    val relayClient = remember { RelayGatewayClient() }
    val relayConfigured = remember(relayClient) { relayClient.isConfigured() }
    val relayHost = remember(relayClient) { relayClient.configuredHostLabel() }

    LaunchedEffect(liveNode.isRunning, liveNode.targetMbPerMinute) {
        if (liveNode.isRunning) {
            selectedTargetMbPerMin = liveNode.targetMbPerMinute.roundToInt()
        }
    }

    val startServiceAction = {
        val rate = rateText.toDoubleOrNull()?.coerceIn(0.1, 100.0) ?: (profile?.ratePer100MbInr ?: 0.85)
        val intent = Intent(context, BandwidthNodeService::class.java).apply {
            action = BandwidthNodeService.ACTION_START
            putExtra(BandwidthNodeService.EXTRA_DAILY_CAP_MB, dailyCapMb.roundToInt())
            putExtra(BandwidthNodeService.EXTRA_RATE_PER_100MB, rate)
            putExtra(BandwidthNodeService.EXTRA_WIFI_ONLY, wifiOnly)
            putExtra(BandwidthNodeService.EXTRA_MIN_BATTERY_PCT, minBatteryPct.roundToInt())
            putExtra(BandwidthNodeService.EXTRA_TARGET_MB_PER_MIN, selectedTargetMbPerMin)
            putExtra(BandwidthNodeService.EXTRA_BACKGROUND_ENABLED, backgroundRunningEnabled)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val updateSpeedAction: (Int) -> Unit = { newTargetMbPerMin ->
        selectedTargetMbPerMin = newTargetMbPerMin
        if (liveNode.isRunning) {
            val intent = Intent(context, BandwidthNodeService::class.java).apply {
                action = BandwidthNodeService.ACTION_UPDATE_SPEED
                putExtra(BandwidthNodeService.EXTRA_TARGET_MB_PER_MIN, newTargetMbPerMin)
            }
            context.startService(intent)
        }
    }

    val updateBackgroundRunningAction: (Boolean) -> Unit = { enabled ->
        backgroundRunningEnabled = enabled
        nodePrefs.setBackgroundRunning(enabled, enabled)
        if (liveNode.isRunning) {
            val intent = Intent(context, BandwidthNodeService::class.java).apply {
                action = BandwidthNodeService.ACTION_UPDATE_BACKGROUND
                putExtra(BandwidthNodeService.EXTRA_BACKGROUND_ENABLED, enabled)
            }
            context.startService(intent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        startServiceAction()
    }

    val pulseTransition = rememberInfiniteTransition(label = "node_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 760.dp)) {
                // Live Instant Balance Strip (Very First on Dashboard)
                val instantAvailableTop = (profile?.availableBalanceInr ?: 0.0) + liveUncreditedInr
                val instantTotalMbTop = (profile?.totalMbSold ?: 0.0) + liveUncreditedMb
                val formattedTopBalance = if (instantAvailableTop > 0.0 && instantAvailableTop < 10.0) {
                    String.format(Locale.US, "₹%.4f", instantAvailableTop)
                } else {
                    String.format(Locale.US, "₹%.2f", instantAvailableTop)
                }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (liveNode.isRunning) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                        .testTag("top_instant_balance_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KpiColumn(
                            label = if (liveNode.isRunning) "BALANCE (AUTO-CREDITING)" else "AVAILABLE BALANCE",
                            value = formattedTopBalance,
                            color = MaterialTheme.colorScheme.primary
                        )
                        KpiColumn(
                            label = "PENDING",
                            value = String.format(Locale.US, "₹%.2f", profile?.pendingPayoutInr ?: 0.0),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        KpiColumn(
                            label = "TOTAL SOLD",
                            value = String.format(Locale.US, "%.2f MB", instantTotalMbTop),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Hero Node Status Card
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (liveNode.isRunning) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(144.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_hero_node),
                                contentDescription = "Live bandwidth node illustration",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.2f),
                                                MaterialTheme.colorScheme.surface
                                            )
                                        )
                                    )
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = if (liveNode.isRunning) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .alpha(if (liveNode.isRunning) pulseAlpha else 1f)
                                                .clip(CircleShape)
                                                .background(
                                                    if (liveNode.isRunning) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        )
                                        Text(
                                            text = if (liveNode.isRunning) {
                                                stringResource(R.string.node_active)
                                            } else {
                                                stringResource(R.string.node_paused)
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (liveNode.isRunning) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                val activeTarget = if (liveNode.isRunning) {
                                    liveNode.targetMbPerMinute.roundToInt()
                                } else {
                                    selectedTargetMbPerMin
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Bolt,
                                            contentDescription = "Turbo speed boost",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (activeTarget == 0) {
                                                "MAX SPEED • 50ms"
                                            } else {
                                                "$activeTarget MB/min • 50ms"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.2f MB",
                                        if (liveNode.isRunning) liveNode.sessionMbMeasured else (profile?.totalMbSold ?: 0.0)
                                    ),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (liveNode.isRunning) {
                                        if (liveNode.targetMbPerMinute <= 0.0) {
                                            String.format(
                                                Locale.US,
                                                "Est. ₹%.4f • %.2f MB/min (MAX) • %02d:%02d",
                                                liveNode.estimatedEarningsInr,
                                                liveNode.liveMbPerMinute,
                                                liveNode.elapsedSeconds / 60,
                                                liveNode.elapsedSeconds % 60
                                            )
                                        } else {
                                            String.format(
                                                Locale.US,
                                                "Est. ₹%.4f • %.2f/%.0f MB/min • %02d:%02d",
                                                liveNode.estimatedEarningsInr,
                                                liveNode.liveMbPerMinute,
                                                liveNode.targetMbPerMinute,
                                                liveNode.elapsedSeconds / 60,
                                                liveNode.elapsedSeconds % 60
                                            )
                                        }
                                    } else {
                                        "Cumulative MB metered across saved sessions"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            AnimatedVisibility(visible = liveNode.isRunning && liveNode.guardReason != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.WarningAmber,
                                            contentDescription = "Guard warning",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = liveNode.guardReason.orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            if (liveNode.isRunning) {
                                val progress = (liveNode.sessionMbMeasured / liveNode.dailyCapMb.coerceAtLeast(1))
                                    .toFloat().coerceIn(0f, 1f)
                                val isMaxMode = liveNode.targetMbPerMinute <= 0.0
                                val linkCeilingMbPerMin = (activeTelemetry.downstreamMbps.coerceAtLeast(10.0) * 60.0) / 8.0
                                val paceDenominator = if (isMaxMode) linkCeilingMbPerMin else liveNode.targetMbPerMinute.coerceAtLeast(1.0)
                                val paceProgress = (liveNode.liveMbPerMinute / paceDenominator).toFloat().coerceIn(0f, 1f)
                                val targetKbps = (paceDenominator * 1024.0) / 60.0

                                LinearProgressIndicator(
                                    progress = { paceProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isMaxMode) {
                                        String.format(
                                            Locale.US,
                                            "MAX Speed: %.2f MB/min (%.2f MB/s • %.2f Mbps)",
                                            liveNode.liveMbPerMinute,
                                            liveNode.liveSpeedKbps / 1024.0,
                                            liveNode.liveThroughputMbps
                                        )
                                    } else {
                                        String.format(
                                            Locale.US,
                                            "Selling Pace: %.2f / %.0f MB/min (%.2f / %.2f MB/s)",
                                            liveNode.liveMbPerMinute,
                                            liveNode.targetMbPerMinute,
                                            liveNode.liveSpeedKbps / 1024.0,
                                            targetKbps / 1024.0
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Daily Quota: %.2f / %d MB",
                                        liveNode.sessionMbMeasured,
                                        liveNode.dailyCapMb
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = "Speed boost selector",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Share Speed Mode",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = when (selectedTargetMbPerMin) {
                                        0 -> "MAX Link Speed • 64 streams (4 MB)"
                                        1024 -> "1 GB/min (17.07 MB/s • 136.5 Mbps)"
                                        2048 -> "2 GB/min (34.13 MB/s • 273.1 Mbps)"
                                        else -> String.format(
                                            Locale.US,
                                            "%d MB/min (%.2f MB/s)",
                                            selectedTargetMbPerMin,
                                            selectedTargetMbPerMin / 60.0
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(500, 1024, 2048, 0).forEach { targetMb ->
                                    val selected = selectedTargetMbPerMin == targetMb
                                    FilterChip(
                                        selected = selected,
                                        onClick = { updateSpeedAction(targetMb) },
                                        label = {
                                            Text(
                                                text = when (targetMb) {
                                                    0 -> "MAX"
                                                    1024 -> "1 GB"
                                                    2048 -> "2 GB"
                                                    else -> "$targetMb"
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag(
                                                when (targetMb) {
                                                    0 -> "speed_boost_max"
                                                    1024 -> "speed_boost_1gb"
                                                    2048 -> "speed_boost_2gb"
                                                    else -> "speed_boost_${targetMb}mb"
                                                }
                                            )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                color = if (relayConfigured) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("relay_status_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (relayConfigured) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                                        contentDescription = "Relay connection status",
                                        tint = if (relayConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (liveNode.isRunning) {
                                            liveNode.relayStatusLabel
                                        } else if (relayConfigured) {
                                            "Active relay ready (${relayHost}) • 64 unthrottled 4 MB HTTP/2 streams at full device link speed"
                                        } else {
                                            "64-stream MAX engine ready. Set BANDWIDTH_RELAY_URL in Secrets + use high-speed Wi-Fi/5G for 1–2+ GB/min active transfer."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if ((selectedTargetMbPerMin == 0 || selectedTargetMbPerMin >= 1024) && dailyCapMb < 2048f) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("daily_cap_warning_banner")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Daily cap is ${dailyCapMb.roundToInt()} MB",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                dailyCapMb = 25000f
                                                val parsedRate = (rateText.toDoubleOrNull() ?: 0.85).coerceIn(0.1, 100.0)
                                                onSaveSettings(
                                                    wifiOnly,
                                                    25000,
                                                    minBatteryPct.roundToInt(),
                                                    parsedRate,
                                                    profile?.defaultUpiId
                                                )
                                            },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("raise_cap_5gb_button")
                                        ) {
                                            Text("Set 25 GB Cap")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            val buttonContainerColor by animateColorAsState(
                                targetValue = if (liveNode.isRunning) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                animationSpec = tween(durationMillis = 280),
                                label = "toggle_button_container"
                            )
                            val buttonContentColor by animateColorAsState(
                                targetValue = if (liveNode.isRunning) {
                                    MaterialTheme.colorScheme.onSecondary
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                                animationSpec = tween(durationMillis = 280),
                                label = "toggle_button_content"
                            )
                            var pulseFlip by remember { mutableStateOf(false) }
                            LaunchedEffect(liveNode.isRunning) {
                                pulseFlip = true
                                delay(160)
                                pulseFlip = false
                            }
                            val buttonScale by animateFloatAsState(
                                targetValue = if (pulseFlip) 0.96f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "toggle_button_scale"
                            )
                            val podBorderColor by animateColorAsState(
                                targetValue = if (liveNode.isRunning) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                                animationSpec = tween(durationMillis = 280),
                                label = "cat_pod_border"
                            )

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, podBorderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("cat_action_pod")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AnimatedSellingCatStage(
                                        isRunning = liveNode.isRunning,
                                        isPausedByGuard = liveNode.isPausedByGuard,
                                        liveMbPerMinute = liveNode.liveMbPerMinute,
                                        liveSpeedKbps = liveNode.liveSpeedKbps
                                    )

                                    Button(
                                        onClick = {
                                            if (liveNode.isRunning) {
                                                val measuredMb = liveNode.sessionMbMeasured
                                                val earnings = liveNode.estimatedEarningsInr
                                                val netType = liveNode.telemetry.transportLabel
                                                val speed = liveNode.telemetry.downstreamMbps
                                                val secs = liveNode.elapsedSeconds
                                                val status = if (measuredMb >= liveNode.dailyCapMb) "capped" else "completed"
                                                val stopIntent = Intent(context, BandwidthNodeService::class.java).apply {
                                                    action = BandwidthNodeService.ACTION_STOP
                                                }
                                                context.startService(stopIntent)
                                                onSessionFinished(netType, measuredMb, earnings, speed, secs, status)
                                            } else {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.POST_NOTIFICATIONS
                                                    ) != PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    startServiceAction()
                                                }
                                            }
                                        },
                                        enabled = !isSubmitting,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = buttonContainerColor,
                                            contentColor = buttonContentColor
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 52.dp)
                                            .graphicsLayer {
                                                scaleX = buttonScale
                                                scaleY = buttonScale
                                            }
                                            .testTag("toggle_node_button")
                                    ) {
                                        Icon(
                                            imageVector = if (liveNode.isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                            contentDescription = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (liveNode.isRunning) {
                                                stringResource(R.string.stop_and_save)
                                            } else {
                                                stringResource(R.string.start_selling_mb)
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Live Device Telemetry Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TelemetryTile(
                        modifier = Modifier.weight(1f),
                        icon = if (activeTelemetry.transportLabel == "Wi-Fi") Icons.Rounded.Wifi else Icons.Rounded.CellTower,
                        label = "NETWORK",
                        value = activeTelemetry.transportLabel,
                        sub = if (activeTelemetry.isMetered) "Metered" else "Unmetered"
                    )
                    TelemetryTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Speed,
                        label = if (liveNode.isRunning) {
                            if (liveNode.targetMbPerMinute <= 0.0) "LIVE (MAX)" else "LIVE (${liveNode.targetMbPerMinute.roundToInt()} MB/m)"
                        } else "DOWNLINK",
                        value = if (liveNode.isRunning) {
                            when {
                                liveNode.liveThroughputMbps >= 1.0 ->
                                    String.format(Locale.US, "%.2f Mbps", liveNode.liveThroughputMbps)
                                liveNode.liveSpeedKbps >= 1024.0 ->
                                    String.format(Locale.US, "%.2f MB/s", liveNode.liveSpeedKbps / 1024.0)
                                else ->
                                    String.format(Locale.US, "%.1f KB/s", liveNode.liveSpeedKbps)
                            }
                        } else {
                            String.format(Locale.US, "%.1f Mbps", activeTelemetry.downstreamMbps)
                        },
                        sub = if (liveNode.isRunning) {
                            String.format(Locale.US, "Cap %.1f Mbps", activeTelemetry.downstreamMbps)
                        } else {
                            String.format(Locale.US, "Up %.1f Mbps", activeTelemetry.upstreamMbps)
                        }
                    )
                    TelemetryTile(
                        modifier = Modifier.weight(1f),
                        icon = if (activeTelemetry.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        label = "BATTERY",
                        value = "${activeTelemetry.batteryPct}%",
                        sub = if (activeTelemetry.isCharging) "Charging" else "On Battery"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Wallet KPI Snapshot
                when (profileState) {
                    is UiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = profileState.message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    is UiState.Success -> {
                        val p = profileState.data

                        // Node Rules & Partner Rate Config
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = "Node guard settings",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Node Safety & Quota Rules",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Keep Running in Background", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Continue MB selling, notification telemetry & auto-crediting when app is minimized or closed",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = backgroundRunningEnabled,
                                        onCheckedChange = updateBackgroundRunningAction,
                                        modifier = Modifier.testTag("background_running_switch")
                                    )
                                }

                                if (backgroundRunningEnabled) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                )
                                            } catch (_: Exception) {
                                                try {
                                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                                } catch (_: Exception) {
                                                    // No settings activity available
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("battery_optimization_button")
                                    ) {
                                        Text("Battery Optimization Settings (Unrestricted)")
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.outline
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Wi-Fi Only Mode", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Prevent mobile SIM charges during node sharing",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = wifiOnly,
                                        onCheckedChange = { wifiOnly = it },
                                        modifier = Modifier.testTag("wifi_only_switch")
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.outline
                                )

                                Text(
                                    text = "Daily Share Cap: ${dailyCapMb.roundToInt()} MB",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Slider(
                                    value = dailyCapMb,
                                    onValueChange = { dailyCapMb = it },
                                    valueRange = 100f..50000f,
                                    steps = 99,
                                    modifier = Modifier.testTag("daily_cap_slider")
                                )

                                Text(
                                    text = "Pause Below Battery: ${minBatteryPct.roundToInt()}%",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Slider(
                                    value = minBatteryPct,
                                    onValueChange = { minBatteryPct = it },
                                    valueRange = 15f..70f,
                                    steps = 10,
                                    modifier = Modifier.testTag("battery_guard_slider")
                                )

                                val previewRate100 = (rateText.toDoubleOrNull() ?: 0.85).coerceIn(0.1, 100.0)
                                OutlinedTextField(
                                    value = rateText,
                                    onValueChange = { rateText = it },
                                    label = { Text("Ledger Rate (₹ per 100 MB • Max ₹100)") },
                                    supportingText = {
                                        Text(
                                            String.format(
                                                Locale.US,
                                                "Equals ₹%.4f per 1 MB (custom ledger rate; live cash depends on buyer/webhook)",
                                                previewRate100 / 100.0
                                            )
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("rate_input")
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val ratePresets = listOf(
                                        0.85 to "₹0.85",
                                        10.0 to "₹10",
                                        50.0 to "₹50",
                                        100.0 to "₹1/MB"
                                    )
                                    ratePresets.forEach { (presetVal, presetLabel) ->
                                        val isSelected = kotlin.math.abs(previewRate100 - presetVal) < 0.01
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                rateText = String.format(Locale.US, "%.2f", presetVal)
                                            },
                                            label = {
                                                Text(
                                                    text = presetLabel,
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                )
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("rate_preset_${presetVal.toInt()}")
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        val parsedRate = (rateText.toDoubleOrNull() ?: 0.85).coerceIn(0.1, 100.0)
                                        onSaveSettings(
                                            wifiOnly,
                                            dailyCapMb.roundToInt(),
                                            minBatteryPct.roundToInt(),
                                            parsedRate,
                                            p?.defaultUpiId
                                        )
                                    },
                                    enabled = !isSubmitting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("save_node_settings_button")
                                ) {
                                    Icon(Icons.Rounded.Save, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Save Guard Rules")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    sub: String
) {
    Card(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KpiColumn(
    label: String,
    value: String,
    color: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = color
        )
    }
}
