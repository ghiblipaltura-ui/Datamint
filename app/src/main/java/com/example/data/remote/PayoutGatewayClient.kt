package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

sealed interface GatewayDispatchResult {
    data object NotConfigured : GatewayDispatchResult
    data class Dispatched(val message: String) : GatewayDispatchResult
    data class Failed(val reason: String) : GatewayDispatchResult
}

class PayoutGatewayClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    fun isConfigured(): Boolean {
        val url = BuildConfig.PAYOUT_WEBHOOK_URL.trim()
        return url.startsWith("https://", ignoreCase = true)
    }

    fun configuredHostLabel(): String? {
        val url = BuildConfig.PAYOUT_WEBHOOK_URL.trim()
        if (!isConfigured()) return null
        return try {
            URI(url).host ?: "Custom HTTPS Endpoint"
        } catch (_: Exception) {
            "Custom HTTPS Endpoint"
        }
    }

    suspend fun dispatchPayout(
        auth: FirebaseAuth,
        databaseId: String,
        payoutId: String,
        amountInr: Double,
        method: String,
        destination: String,
        note: String?
    ): GatewayDispatchResult {
        val url = BuildConfig.PAYOUT_WEBHOOK_URL.trim()
        if (!isConfigured()) {
            return GatewayDispatchResult.NotConfigured
        }

        val user = auth.currentUser
            ?: return GatewayDispatchResult.Failed("User not signed in")

        return withContext(Dispatchers.IO) {
            try {
                val idToken = user.getIdToken(false).await()?.token
                    ?: return@withContext GatewayDispatchResult.Failed("Missing Firebase ID token")

                val payload = JSONObject().apply {
                    put("payoutId", payoutId)
                    put("userId", user.uid)
                    put("databaseId", databaseId)
                    put("amountInr", amountInr)
                    put("currency", "INR")
                    put("method", method)
                    put("destination", destination)
                    if (!note.isNullOrBlank()) {
                        put("note", note)
                    }
                }

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val ref = try {
                            JSONObject(rawBody).optString("transactionRef").takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        }
                        val msg = if (ref != null) {
                            "Dispatched to payout gateway (Ref: $ref)"
                        } else {
                            "Dispatched to payout gateway for live processing"
                        }
                        GatewayDispatchResult.Dispatched(msg)
                    } else {
                        Log.w("PayoutGateway", "Gateway HTTP ${response.code}: $rawBody")
                        GatewayDispatchResult.Failed("Gateway returned HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("PayoutGateway", "Gateway dispatch error", e)
                GatewayDispatchResult.Failed(e.localizedMessage ?: "Network error contacting gateway")
            }
        }
    }
}
