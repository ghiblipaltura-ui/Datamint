package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.BandwidthSession
import com.example.data.model.DataListing
import com.example.data.model.PayoutRequest
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class DataMintRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun logAndRethrow(error: Throwable, op: OperationType, path: String, expectedUid: String?) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && (expectedUid == null || auth.currentUser?.uid == expectedUid)) {
            handleFirestoreError(exceptionToLog, op, path, auth)
        }
    }

    suspend fun ensureUserProfile(displayName: String): Result<UserProfile> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val existing = snapshot.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserProfile(id = uid, userId = uid, displayName = displayName)
                Result.success(existing)
            } else {
                val safeName = displayName.trim().ifBlank { "DataMint Seller" }.take(60)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "wifiOnly" to true,
                    "dailyCapMb" to 5000,
                    "minBatteryPct" to 25,
                    "ratePer100MbInr" to 0.85,
                    "totalMbSold" to 0.0,
                    "availableBalanceInr" to 0.0,
                    "pendingPayoutInr" to 0.0,
                    "totalWithdrawnInr" to 0.0,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                val created = docRef.get().await().toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserProfile(id = uid, userId = uid, displayName = safeName)
                Result.success(created)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logAndRethrow(error, OperationType.GET, docRef.path, userId)
                    throw error
                }
        )
    }

    suspend fun getUserProfile(userId: String): Result<UserProfile?> {
        val docRef = db.collection("users").document(userId)
        return try {
            val snap = docRef.get().await()
            val profile = if (snap.exists()) {
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateNodeSettings(
        wifiOnly: Boolean,
        dailyCapMb: Int,
        minBatteryPct: Int,
        ratePer100MbInr: Double,
        defaultUpiId: String? = null
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val clampedCap = dailyCapMb.coerceIn(50, 50000)
        val clampedBattery = minBatteryPct.coerceIn(10, 90)
        val clampedRate = ((ratePer100MbInr.coerceIn(0.1, 100.0)) * 100.0).roundToInt() / 100.0
        val payload = buildMap<String, Any> {
            put("wifiOnly", wifiOnly)
            put("dailyCapMb", clampedCap)
            put("minBatteryPct", clampedBattery)
            put("ratePer100MbInr", clampedRate)
            if (defaultUpiId != null) {
                put("defaultUpiId", defaultUpiId.trim().take(80))
            }
            put("updatedAt", FieldValue.serverTimestamp())
        }
        return try {
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeSessions(userId: String): Flow<List<BandwidthSession>> = flow {
        val path = "users/$userId/sessions"
        emitAll(
            db.collection("users").document(userId).collection("sessions")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BandwidthSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logAndRethrow(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun creditIncrementalWallet(
        deltaMb: Double,
        deltaEarningsInr: Double
    ): Result<Pair<Double, Double>> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val safeMb = ((deltaMb.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        val safeEarnings = ((deltaEarningsInr.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        if (safeMb <= 0.0 && safeEarnings <= 0.0) return Result.success(0.0 to 0.0)

        return try {
            userRef.update(
                mapOf(
                    "totalMbSold" to FieldValue.increment(safeMb),
                    "availableBalanceInr" to FieldValue.increment(safeEarnings),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(safeMb to safeEarnings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun recordSessionAndCreditWallet(
        networkType: String,
        mbShared: Double,
        estimatedEarningsInr: Double,
        avgSpeedMbps: Double,
        durationSeconds: Int,
        status: String = "completed",
        uncreditedMb: Double = mbShared,
        uncreditedEarningsInr: Double = estimatedEarningsInr
    ): Result<String> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val sessionRef = userRef.collection("sessions").document()

        val safeMb = ((mbShared.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        val safeEarnings = ((estimatedEarningsInr.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        val safeDeltaMb = ((uncreditedMb.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        val safeDeltaEarnings = ((uncreditedEarningsInr.coerceIn(0.0, 50000.0)) * 1_000_000.0).roundToLong() / 1_000_000.0
        val safeSpeed = ((avgSpeedMbps.coerceIn(0.0, 10000.0)) * 10.0).roundToInt() / 10.0
        val safeDuration = durationSeconds.coerceIn(0, 86400)
        val safeNetwork = if (networkType in listOf("Wi-Fi", "5G", "4G LTE", "Mobile", "Ethernet")) {
            networkType
        } else {
            "Mobile"
        }
        val safeStatus = if (status == "capped") "capped" else "completed"

        val sessionPayload = mapOf(
            "userId" to uid,
            "networkType" to safeNetwork,
            "mbShared" to safeMb,
            "estimatedEarningsInr" to safeEarnings,
            "avgSpeedMbps" to safeSpeed,
            "durationSeconds" to safeDuration,
            "status" to safeStatus,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            val batch = db.batch()
            batch.set(sessionRef, sessionPayload)
            if (safeDeltaMb > 0.0 || safeDeltaEarnings > 0.0) {
                batch.update(
                    userRef,
                    mapOf(
                        "totalMbSold" to FieldValue.increment(safeDeltaMb),
                        "availableBalanceInr" to FieldValue.increment(safeDeltaEarnings),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(sessionRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, sessionRef.path, auth)
            Result.failure(e)
        }
    }

    fun observePayouts(userId: String): Flow<List<PayoutRequest>> = flow {
        val path = "users/$userId/payouts"
        emitAll(
            db.collection("users").document(userId).collection("payouts")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PayoutRequest::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logAndRethrow(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun requestPayout(
        amountInr: Double,
        method: String,
        destination: String,
        note: String? = null
    ): Result<String> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val payoutRef = userRef.collection("payouts").document()

        val safeAmount = ((amountInr.coerceIn(1.0, 50000.0)) * 100.0).roundToInt() / 100.0
        val safeMethod = if (method in listOf("UPI", "PayPal", "Bank Transfer")) method else "UPI"
        val safeDest = destination.trim().take(80)
        require(safeDest.length >= 3) { "Payout destination must be at least 3 characters." }

        val payoutPayload = buildMap<String, Any> {
            put("userId", uid)
            put("amountInr", safeAmount)
            put("method", safeMethod)
            put("destination", safeDest)
            put("status", "pending_settlement")
            if (!note.isNullOrBlank()) {
                put("note", note.trim().take(160))
            }
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }

        return try {
            db.runTransaction { transaction ->
                val userSnap = transaction.get(userRef)
                val currentBalance = userSnap.getDouble("availableBalanceInr") ?: 0.0
                if (currentBalance < safeAmount) {
                    throw IllegalStateException("Insufficient available balance for ₹$safeAmount payout.")
                }
                val currentPending = userSnap.getDouble("pendingPayoutInr") ?: 0.0
                val newAvailable = ((currentBalance - safeAmount).coerceAtLeast(0.0) * 100.0).roundToInt() / 100.0
                val newPending = ((currentPending + safeAmount) * 100.0).roundToInt() / 100.0

                transaction.set(payoutRef, payoutPayload)
                transaction.update(
                    userRef,
                    mapOf(
                        "availableBalanceInr" to newAvailable,
                        "pendingPayoutInr" to newPending,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                payoutRef.id
            }.await()
            Result.success(payoutRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, payoutRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun cancelPayout(payoutId: String): Result<Unit> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val payoutRef = userRef.collection("payouts").document(payoutId)
        return try {
            db.runTransaction { transaction ->
                val payoutSnap = transaction.get(payoutRef)
                val status = payoutSnap.getString("status")
                if (status != "pending_settlement") {
                    throw IllegalStateException("Only pending payout requests can be cancelled.")
                }
                val amount = payoutSnap.getDouble("amountInr") ?: 0.0
                val userSnap = transaction.get(userRef)
                val currentAvailable = userSnap.getDouble("availableBalanceInr") ?: 0.0
                val currentPending = userSnap.getDouble("pendingPayoutInr") ?: 0.0
                val newAvailable = ((currentAvailable + amount) * 100.0).roundToInt() / 100.0
                val newPending = ((currentPending - amount).coerceAtLeast(0.0) * 100.0).roundToInt() / 100.0

                transaction.update(
                    payoutRef,
                    mapOf(
                        "status" to "cancelled",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                transaction.update(
                    userRef,
                    mapOf(
                        "availableBalanceInr" to newAvailable,
                        "pendingPayoutInr" to newPending,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, payoutRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeActiveListings(currentUserId: String): Flow<List<DataListing>> = flow {
        val path = "listings"
        emitAll(
            db.collection("listings")
                .whereEqualTo("status", "active")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DataListing::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logAndRethrow(error, OperationType.LIST, path, currentUserId)
                    throw error
                }
        )
    }

    fun observeMyListings(userId: String): Flow<List<DataListing>> = flow {
        val path = "listings"
        emitAll(
            db.collection("listings")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DataListing::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logAndRethrow(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun createListing(
        sellerAlias: String,
        packageTitle: String,
        mbAmount: Int,
        priceInr: Double,
        networkType: String
    ): Result<String> {
        val uid = requireUserId()
        val docRef = db.collection("listings").document()
        val safeAlias = sellerAlias.trim().ifBlank { "NodeSeller" }.take(40)
        val safeTitle = packageTitle.trim().take(60)
        require(safeTitle.length >= 3) { "Listing title must be at least 3 characters." }
        val safeMb = mbAmount.coerceIn(50, 50000)
        val safePrice = ((priceInr.coerceIn(1.0, 10000.0)) * 100.0).roundToInt() / 100.0
        val safeNetwork = if (networkType in listOf("Wi-Fi", "5G", "4G LTE", "Any")) networkType else "Any"

        val payload = mapOf(
            "userId" to uid,
            "sellerAlias" to safeAlias,
            "packageTitle" to safeTitle,
            "mbAmount" to safeMb,
            "priceInr" to safePrice,
            "networkType" to safeNetwork,
            "status" to "active",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateListingStatus(listingId: String, newStatus: String): Result<Unit> {
        requireUserId()
        val safeStatus = if (newStatus in listOf("active", "paused", "closed")) newStatus else "paused"
        val docRef = db.collection("listings").document(listingId)
        return try {
            docRef.update(
                mapOf(
                    "status" to safeStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun deleteListing(listingId: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection("listings").document(listingId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            Result.failure(e)
        }
    }
}
