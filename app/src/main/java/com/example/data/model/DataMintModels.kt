package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val wifiOnly: Boolean = true,
    val dailyCapMb: Int = 1000,
    val minBatteryPct: Int = 25,
    val ratePer100MbInr: Double = 0.85,
    val totalMbSold: Double = 0.0,
    val availableBalanceInr: Double = 0.0,
    val pendingPayoutInr: Double = 0.0,
    val totalWithdrawnInr: Double = 0.0,
    val defaultUpiId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BandwidthSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val networkType: String = "Wi-Fi",
    val mbShared: Double = 0.0,
    val estimatedEarningsInr: Double = 0.0,
    val avgSpeedMbps: Double = 0.0,
    val durationSeconds: Int = 0,
    val status: String = "completed",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PayoutRequest(
    @DocumentId val id: String = "",
    val userId: String = "",
    val amountInr: Double = 0.0,
    val method: String = "UPI",
    val destination: String = "",
    val status: String = "pending_settlement",
    val note: String? = null,
    val transactionRef: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DataListing(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sellerAlias: String = "",
    val packageTitle: String = "",
    val mbAmount: Int = 500,
    val priceInr: Double = 4.5,
    val networkType: String = "5G",
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
