package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BandwidthSession
import com.example.data.model.DataListing
import com.example.data.model.PayoutRequest
import com.example.data.model.UserProfile
import com.example.data.remote.GatewayDispatchResult
import com.example.data.remote.PayoutGatewayClient
import com.example.data.repository.DataMintRepository
import com.example.service.BandwidthNodeService
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class DataMintViewModel(
    private val repository: DataMintRepository,
    private val currentUserId: String,
    private val initialDisplayName: String,
    private val databaseId: String = "",
    private val gatewayClient: PayoutGatewayClient = PayoutGatewayClient()
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    val uncreditedMb: StateFlow<Double> = BandwidthNodeService.nodeState
        .map { if (it.isRunning) it.uncreditedMb else 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = 0.0
        )

    val uncreditedEarningsInr: StateFlow<Double> = BandwidthNodeService.nodeState
        .map { if (it.isRunning) it.uncreditedEarningsInr else 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = 0.0
        )

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(initialDisplayName).onFailure { err ->
                Log.w(TAG, "Failed to ensure profile", err)
                _actionMessage.value = unwrapErrorMessage(err, "Failed to initialize profile")
            }
        }
    }

    val userProfile: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error -> emitError(error, "profile") }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val sessions: StateFlow<UiState<List<BandwidthSession>>> = repository.observeSessions(currentUserId)
        .map<List<BandwidthSession>, UiState<List<BandwidthSession>>> { UiState.Success(it) }
        .catch { error -> emitError(error, "sessions") }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val payouts: StateFlow<UiState<List<PayoutRequest>>> = repository.observePayouts(currentUserId)
        .map<List<PayoutRequest>, UiState<List<PayoutRequest>>> { UiState.Success(it) }
        .catch { error -> emitError(error, "payouts") }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val activeListings: StateFlow<UiState<List<DataListing>>> = repository.observeActiveListings(currentUserId)
        .map<List<DataListing>, UiState<List<DataListing>>> { UiState.Success(it) }
        .catch { error -> emitError(error, "marketplace listings") }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val myListings: StateFlow<UiState<List<DataListing>>> = repository.observeMyListings(currentUserId)
        .map<List<DataListing>, UiState<List<DataListing>>> { UiState.Success(it) }
        .catch { error -> emitError(error, "my listings") }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<UiState<T>>.emitError(
        error: Throwable,
        label: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing $label", rootError)
        emit(UiState.Error(rootError.localizedMessage ?: "Failed to load $label"))
    }

    private fun unwrapErrorMessage(error: Throwable, fallback: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        return firestoreError?.localizedMessage ?: error.localizedMessage ?: fallback
    }

    fun clearMessage() {
        _actionMessage.value = null
    }

    fun saveNodeSettings(
        wifiOnly: Boolean,
        dailyCapMb: Int,
        minBatteryPct: Int,
        ratePer100MbInr: Double,
        defaultUpiId: String?
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.updateNodeSettings(
                wifiOnly = wifiOnly,
                dailyCapMb = dailyCapMb,
                minBatteryPct = minBatteryPct,
                ratePer100MbInr = ratePer100MbInr,
                defaultUpiId = defaultUpiId
            ).onSuccess {
                _actionMessage.value = "Node settings synced to cloud"
            }.onFailure { err ->
                _actionMessage.value = unwrapErrorMessage(err, "Could not update node settings")
            }
            _isSubmitting.value = false
        }
    }

    fun recordCompletedSession(
        networkType: String,
        mbShared: Double,
        estimatedEarningsInr: Double,
        avgSpeedMbps: Double,
        durationSeconds: Int,
        status: String
    ) {
        if (mbShared < 0.0001) {
            _actionMessage.value = "Node stopped. Keep active while browsing or syncing to meter MB."
            return
        }
        viewModelScope.launch {
            _isSubmitting.value = true
            val serviceState = BandwidthNodeService.nodeState.value
            val remainingMb = (mbShared - serviceState.persistedMb).coerceAtLeast(0.0)
            val remainingInr = (estimatedEarningsInr - serviceState.persistedEarningsInr).coerceAtLeast(0.0)
            repository.recordSessionAndCreditWallet(
                networkType = networkType,
                mbShared = mbShared,
                estimatedEarningsInr = estimatedEarningsInr,
                avgSpeedMbps = avgSpeedMbps,
                durationSeconds = durationSeconds,
                status = status,
                uncreditedMb = remainingMb,
                uncreditedEarningsInr = remainingInr
            ).onSuccess {
                _actionMessage.value = String.format(
                    java.util.Locale.US,
                    "Session saved: %.2f MB (₹%.4f credited)",
                    mbShared,
                    estimatedEarningsInr
                )
            }.onFailure { err ->
                _actionMessage.value = unwrapErrorMessage(err, "Failed to record session")
            }
            _isSubmitting.value = false
        }
    }

    fun requestPayout(
        amountInr: Double,
        method: String,
        destination: String,
        note: String?
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.requestPayout(
                amountInr = amountInr,
                method = method,
                destination = destination,
                note = note
            ).onSuccess { payoutId ->
                val dispatchResult = gatewayClient.dispatchPayout(
                    auth = Firebase.auth,
                    databaseId = databaseId,
                    payoutId = payoutId,
                    amountInr = amountInr,
                    method = method,
                    destination = destination,
                    note = note
                )
                _actionMessage.value = when (dispatchResult) {
                    is GatewayDispatchResult.Dispatched -> dispatchResult.message
                    is GatewayDispatchResult.NotConfigured -> String.format(
                        java.util.Locale.US,
                        "₹%.2f queued in Cloud Ledger. Set PAYOUT_WEBHOOK_URL in Secrets for live UPI/bank transfer.",
                        amountInr
                    )
                    is GatewayDispatchResult.Failed -> String.format(
                        java.util.Locale.US,
                        "₹%.2f queued as Pending; gateway dispatch failed (%s).",
                        amountInr,
                        dispatchResult.reason
                    )
                }
            }.onFailure { err ->
                _actionMessage.value = unwrapErrorMessage(err, "Payout request failed")
            }
            _isSubmitting.value = false
        }
    }

    fun cancelPayout(payoutId: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.cancelPayout(payoutId)
                .onSuccess { _actionMessage.value = "Payout cancelled & balance restored" }
                .onFailure { err ->
                    _actionMessage.value = unwrapErrorMessage(err, "Could not cancel payout")
                }
            _isSubmitting.value = false
        }
    }

    fun createListing(
        sellerAlias: String,
        packageTitle: String,
        mbAmount: Int,
        priceInr: Double,
        networkType: String
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.createListing(
                sellerAlias = sellerAlias,
                packageTitle = packageTitle,
                mbAmount = mbAmount,
                priceInr = priceInr,
                networkType = networkType
            ).onSuccess {
                _actionMessage.value = "MB offer published to marketplace"
            }.onFailure { err ->
                _actionMessage.value = unwrapErrorMessage(err, "Could not publish listing")
            }
            _isSubmitting.value = false
        }
    }

    fun updateListingStatus(listingId: String, newStatus: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.updateListingStatus(listingId, newStatus)
                .onSuccess { _actionMessage.value = "Listing updated to $newStatus" }
                .onFailure { err ->
                    _actionMessage.value = unwrapErrorMessage(err, "Could not update listing")
                }
            _isSubmitting.value = false
        }
    }

    fun deleteListing(listingId: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.deleteListing(listingId)
                .onSuccess { _actionMessage.value = "Listing removed" }
                .onFailure { err ->
                    _actionMessage.value = unwrapErrorMessage(err, "Could not remove listing")
                }
            _isSubmitting.value = false
        }
    }

    private companion object {
        const val TAG = "DataMintVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
