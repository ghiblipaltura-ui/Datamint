package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.DataListing
import com.example.ui.viewmodel.UiState
import java.util.Locale

@Composable
fun MarketplaceScreen(
    currentUserId: String,
    sellerAlias: String,
    activeListingsState: UiState<List<DataListing>>,
    myListingsState: UiState<List<DataListing>>,
    isSubmitting: Boolean,
    onCreateListing: (String, String, Int, Double, String) -> Unit,
    onUpdateStatus: (String, String) -> Unit,
    onDeleteListing: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("create_listing_fab")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Publish MB Sell Offer")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.widthIn(max = 760.dp)) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Active Offers") },
                        modifier = Modifier.testTag("tab_active_offers")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("My Listings") },
                        modifier = Modifier.testTag("tab_my_listings")
                    )
                }

                val currentState = if (selectedTab == 0) activeListingsState else myListingsState
                when (currentState) {
                    is UiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentState.message,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is UiState.Success -> {
                        val list = currentState.data
                        if (list.isEmpty()) {
                            EmptyMarketplaceState(
                                isMyTab = selectedTab == 1,
                                onCreateClick = { showCreateDialog = true }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(list, key = { it.id }) { listing ->
                                    ListingCard(
                                        listing = listing,
                                        isOwner = listing.userId == currentUserId,
                                        isSubmitting = isSubmitting,
                                        onTogglePause = {
                                            val next = if (listing.status == "active") "paused" else "active"
                                            onUpdateStatus(listing.id, next)
                                        },
                                        onClose = { onUpdateStatus(listing.id, "closed") },
                                        onDelete = { onDeleteListing(listing.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateListingDialog(
            defaultAlias = sellerAlias,
            isSubmitting = isSubmitting,
            onDismiss = { showCreateDialog = false },
            onSubmit = { title, mb, price, network ->
                onCreateListing(sellerAlias, title, mb, price, network)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun ListingCard(
    listing: DataListing,
    isOwner: Boolean,
    isSubmitting: Boolean,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val per100Mb = if (listing.mbAmount > 0) (listing.priceInr / listing.mbAmount) * 100.0 else 0.0
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CellTower,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.height(14.dp)
                        )
                        Text(
                            text = listing.networkType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Surface(
                    color = when (listing.status) {
                        "active" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        "paused" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = listing.status.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (listing.status) {
                            "active" -> MaterialTheme.colorScheme.primary
                            "paused" -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = listing.packageTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Seller: ${listing.sellerAlias}" + if (isOwner) " (You)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "${listing.mbAmount} MB Pool",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = String.format(Locale.US, "₹%.2f / 100 MB", per100Mb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = String.format(Locale.US, "₹%.2f", listing.priceInr),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (isOwner) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (listing.status != "closed") {
                        OutlinedButton(
                            onClick = onTogglePause,
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("toggle_listing_${listing.id}")
                        ) {
                            Icon(
                                imageVector = if (listing.status == "active") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (listing.status == "active") "Pause" else "Resume")
                        }
                        OutlinedButton(
                            onClick = onClose,
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("close_listing_${listing.id}")
                        ) {
                            Text("Close Offer")
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = !isSubmitting,
                        modifier = Modifier.testTag("delete_listing_${listing.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete listing",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMarketplaceState(
    isMyTab: Boolean,
    onCreateClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Storefront,
            contentDescription = "Empty marketplace",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isMyTab) "No MB Offers Published Yet" else "No Active Marketplace Offers",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Publish an unused MB pool package with your asking price to list it on the cloud marketplace.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onCreateClick,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("empty_create_listing_button")
        ) {
            Text("Publish MB Package")
        }
    }
}

@Composable
private fun CreateListingDialog(
    defaultAlias: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, Int, Double, String) -> Unit
) {
    var title by remember { mutableStateOf("500 MB High-Speed Data") }
    var mbText by remember { mutableStateOf("500") }
    var priceText by remember { mutableStateOf("4.50") }
    var networkType by remember { mutableStateOf("5G") }
    val networkOptions = listOf("Wi-Fi", "5G", "4G LTE", "Any")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sell MB Package") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Published under alias: $defaultAlias",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Package Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("listing_title_input")
                )
                OutlinedTextField(
                    value = mbText,
                    onValueChange = { mbText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Data Amount (MB, 50 - 50000)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("listing_mb_input")
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Asking Price (₹ INR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("listing_price_input")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    networkOptions.forEach { net ->
                        FilterChip(
                            selected = networkType == net,
                            onClick = { networkType = net },
                            label = { Text(net) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mb = mbText.toIntOrNull()?.coerceIn(50, 50000) ?: 500
                    val price = priceText.toDoubleOrNull()?.coerceIn(1.0, 10000.0) ?: 4.5
                    if (title.trim().length >= 3) {
                        onSubmit(title.trim(), mb, price, networkType)
                    }
                },
                enabled = !isSubmitting && title.trim().length >= 3,
                modifier = Modifier.testTag("submit_listing_button")
            ) {
                Text("Publish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
