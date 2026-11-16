package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.PayoutRequest
import com.example.data.model.UserProfile
import com.example.data.remote.PayoutGatewayClient
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun WalletScreen(
    profileState: UiState<UserProfile?>,
    payoutsState: UiState<List<PayoutRequest>>,
    liveUncreditedInr: Double = 0.0,
    isSubmitting: Boolean,
    onRequestPayout: (Double, String, String, String?) -> Unit,
    onCancelPayout: (String) -> Unit
) {
    val profile = (profileState as? UiState.Success)?.data
    val available = (profile?.availableBalanceInr ?: 0.0) + liveUncreditedInr
    val ratePer100Mb = profile?.ratePer100MbInr ?: 0.85
    var method by remember { mutableStateOf("UPI") }
    var destination by remember(profile?.defaultUpiId) {
        mutableStateOf(profile?.defaultUpiId.orEmpty())
    }
    var amountText by remember { mutableStateOf("1.00") }
    var noteText by remember { mutableStateOf("") }
    val methods = listOf("UPI", "PayPal", "Bank Transfer")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 760.dp)) {
                // Vault Balance Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_wallet_vault),
                        contentDescription = "Wallet vault background",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.72f),
                                        Color.Black.copy(alpha = 0.48f)
                                    )
                                )
                            )
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "AVAILABLE LEDGER BALANCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (available > 0.0 && available < 10.0) {
                                String.format(Locale.US, "₹%.4f", available)
                            } else {
                                String.format(Locale.US, "₹%.2f", available)
                            },
                            style = MaterialTheme.typography.displayLarge,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "PENDING SETTLEMENT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = String.format(Locale.US, "₹%.2f", profile?.pendingPayoutInr ?: 0.0),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "WITHDRAWN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = String.format(Locale.US, "₹%.2f", profile?.totalWithdrawnInr ?: 0.0),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // MB Selling Rate Notification Card
                MbRateNotificationCard(
                    ratePer100Mb = ratePer100Mb,
                    availableBalance = available
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Transparent Settlement & Gateway Status Card
                GatewayStatusCard()

                Spacer(modifier = Modifier.height(14.dp))

                // Payout Request Form
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Request Payout",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            methods.forEach { option ->
                                FilterChip(
                                    selected = method == option,
                                    onClick = { method = option },
                                    label = { Text(option) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = {
                                Text(
                                    when (method) {
                                        "UPI" -> "UPI ID (e.g. name@okaxis)"
                                        "PayPal" -> "PayPal Email"
                                        else -> "Bank Account / IFSC Reference"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("payout_destination_input")
                        )

                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { amountText = it },
                            label = { Text("Amount in ₹ INR (Min ₹1.00)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("payout_amount_input")
                        )

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text("Memo (Optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("payout_note_input")
                        )

                        val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
                        val canSubmit = !isSubmitting &&
                            parsedAmount >= 1.0 &&
                            parsedAmount <= available &&
                            destination.trim().length >= 3

                        Button(
                            onClick = {
                                onRequestPayout(
                                    parsedAmount,
                                    method,
                                    destination.trim(),
                                    noteText.trim().ifBlank { null }
                                )
                            },
                            enabled = canSubmit,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("submit_payout_button")
                        ) {
                            Text(
                                text = if (available < 1.0) {
                                    "Minimum ₹1.00 Required to Request Payout"
                                } else {
                                    "Submit Payout Request"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Payout Requests",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        when (payoutsState) {
            is UiState.Loading -> {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
            is UiState.Error -> {
                item {
                    Text(
                        text = payoutsState.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            is UiState.Success -> {
                val list = payoutsState.data
                if (list.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                        ) {
                            Text(
                                text = "No payout requests yet. Meter active bandwidth sessions to build your withdrawable ledger balance.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(list, key = { it.id }) { payout ->
                        PayoutItemCard(
                            payout = payout,
                            isSubmitting = isSubmitting,
                            onCancel = { onCancelPayout(payout.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PayoutItemCard(
    payout: PayoutRequest,
    isSubmitting: Boolean,
    onCancel: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.US) }
    val dateStr = payout.createdAt?.toDate()?.let { formatter.format(it) } ?: "Just now"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = String.format(Locale.US, "₹%.2f • %s", payout.amountInr, payout.method),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${payout.destination} • $dateStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!payout.transactionRef.isNullOrBlank()) {
                        Text(
                            text = "Ref/UTR: ${payout.transactionRef}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val (badgeBg, badgeTextColor, badgeLabel) = when (payout.status) {
                    "settled" -> Triple(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.primary,
                        "SETTLED"
                    )
                    "processing" -> Triple(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.tertiary,
                        "PROCESSING"
                    )
                    "failed" -> Triple(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.error,
                        "FAILED"
                    )
                    "cancelled" -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "CANCELLED"
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.secondary,
                        "PENDING"
                    )
                }

                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (payout.status == "pending_settlement") {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cancel_payout_${payout.id}")
                ) {
                    Text("Cancel & Restore Balance")
                }
            }
        }
    }
}

@Composable
private fun GatewayStatusCard() {
    val gatewayClient = remember { PayoutGatewayClient() }
    val isConnected = remember { gatewayClient.isConfigured() }
    val hostLabel = remember { gatewayClient.configuredHostLabel() }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("payout_gateway_status_card")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                contentDescription = "Payout gateway status",
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isConnected) {
                        "Live Payout Gateway Connected ($hostLabel)"
                    } else {
                        "Live Payout Gateway Not Connected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = if (isConnected) {
                        "Withdrawal requests are signed with your Firebase ID token and dispatched directly to your backend payout server (UPI / PayPal / Bank)."
                    } else {
                        stringResource(R.string.disclosure_banner) + " Set PAYOUT_WEBHOOK_URL in AI Studio Secrets to enable automatic live transfers."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MbRateNotificationCard(
    ratePer100Mb: Double,
    availableBalance: Double
) {
    val tiers = listOf(
        1 to "1 MB",
        100 to "100 MB",
        1024 to "1 GB",
        5120 to "5 GB"
    )
    val remainingInr = (1.0 - availableBalance).coerceAtLeast(0.0)
    val mbNeededForMinPayout = if (ratePer100Mb > 0.0) (remainingInr / ratePer100Mb) * 100.0 else 0.0

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("mb_rate_notification_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = "MB earning rate notice",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(6.dp)
                    )
                }
                Column {
                    Text(
                        text = "MB Selling Earning Rate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Rate: ₹%.2f / 100 MB (₹%.4f / 1 MB)",
                            ratePer100Mb,
                            ratePer100Mb / 100.0
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tiers.forEach { (mb, label) ->
                    val payout = (mb / 100.0) * ratePer100Mb
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (payout > 0.0 && payout < 1.0) {
                                    String.format(Locale.US, "₹%.3f", payout)
                                } else {
                                    String.format(Locale.US, "₹%.2f", payout)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Text(
                text = if (remainingInr <= 0.0) {
                    "Minimum ₹1.00 payout threshold reached! You can submit a payout request below."
                } else {
                    String.format(
                        Locale.US,
                        "Sell ~%.1f MB more at your current rate to reach the ₹1.00 minimum payout.",
                        mbNeededForMinPayout
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
