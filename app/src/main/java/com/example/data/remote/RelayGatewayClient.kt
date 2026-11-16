package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RelayGatewayClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 256
                maxRequestsPerHost = 128
            }
        )
        .connectionPool(ConnectionPool(64, 2, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    fun isConfigured(): Boolean {
        val url = BuildConfig.BANDWIDTH_RELAY_URL.trim()
        return url.startsWith("https://", ignoreCase = true)
    }

    fun configuredHostLabel(): String? {
        val url = BuildConfig.BANDWIDTH_RELAY_URL.trim()
        if (!isConfigured()) return null
        return try {
            URI(url).host ?: "Custom HTTPS Relay"
        } catch (_: Exception) {
            "Custom HTTPS Relay"
        }
    }

    suspend fun runPacedRelayPool(
        auth: FirebaseAuth,
        targetMbPerMinuteProvider: () -> Int,
        isPausedProvider: () -> Boolean,
        onStatusChanged: (String) -> Unit
    ) {
        val url = BuildConfig.BANDWIDTH_RELAY_URL.trim()
        if (!isConfigured()) {
            onStatusChanged("Passive metering (set BANDWIDTH_RELAY_URL in Secrets for 64-stream MAX active relay)")
            return
        }

        val octetStream = "application/octet-stream".toMediaType()
        val turboBody = ByteArray(TURBO_CHUNK_BYTES) { index -> (index and 0x7F).toByte() }.toRequestBody(octetStream)
        val pacedBody = ByteArray(PACED_CHUNK_BYTES) { index -> (index and 0x7F).toByte() }.toRequestBody(octetStream)

        withContext(Dispatchers.IO) {
            var activeTarget = targetMbPerMinuteProvider().coerceIn(0, MAX_TARGET_MB_PER_MIN)
            onStatusChanged(formatRelayStatus(activeTarget))

            while (isActive) {
                if (isPausedProvider()) {
                    delay(200L)
                    continue
                }

                val targetMbPerMin = targetMbPerMinuteProvider().coerceIn(0, MAX_TARGET_MB_PER_MIN)
                if (targetMbPerMin != activeTarget) {
                    activeTarget = targetMbPerMin
                    onStatusChanged(formatRelayStatus(activeTarget))
                }

                val user = auth.currentUser
                if (user == null) {
                    onStatusChanged("Relay paused: sign in required")
                    delay(1500L)
                    continue
                }

                val idToken = try {
                    user.getIdToken(false).await()?.token
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read auth token for relay", e)
                    null
                }

                if (idToken.isNullOrBlank()) {
                    onStatusChanged("Relay waiting for auth token")
                    delay(1500L)
                    continue
                }

                val isUnlimitedMax = targetMbPerMin == 0
                val useTurboChunks = isUnlimitedMax || targetMbPerMin >= 500
                val workerCount = if (isUnlimitedMax || targetMbPerMin >= 1024) {
                    MAX_CONCURRENT_WORKERS
                } else {
                    (targetMbPerMin / 32).coerceIn(4, MAX_CONCURRENT_WORKERS)
                }
                val chunkBytes = if (useTurboChunks) TURBO_CHUNK_BYTES else PACED_CHUNK_BYTES
                val chunkBody = if (useTurboChunks) turboBody else pacedBody

                // Adaptive byte-rate pacer so 32 streams sustain exact 1 GB/min (17.07 MB/s) without lockstep drift
                val poolStartNs = System.nanoTime()
                val bytesDispatched = AtomicLong(0L)
                val targetBytesPerSec = if (isUnlimitedMax) 0.0 else (targetMbPerMin * BYTES_PER_MB) / 60.0
                val errorCounter = AtomicLong(0L)

                coroutineScope {
                    repeat(workerCount) { workerIndex ->
                        launch {
                            while (isActive && !isPausedProvider() &&
                                targetMbPerMinuteProvider().coerceIn(0, MAX_TARGET_MB_PER_MIN) == targetMbPerMin
                            ) {
                                if (!isUnlimitedMax && targetBytesPerSec > 0.0) {
                                    val elapsedSec = ((System.nanoTime() - poolStartNs).coerceAtLeast(1_000_000L)) / 1_000_000_000.0
                                    val allowedBytes = elapsedSec * targetBytesPerSec + (chunkBytes * 2.0)
                                    val currentSent = bytesDispatched.get().toDouble()
                                    if (currentSent > allowedBytes) {
                                        val aheadBytes = currentSent - allowedBytes
                                        val waitMs = ((aheadBytes / targetBytesPerSec) * 1000.0)
                                            .toLong()
                                            .coerceIn(5L, 250L)
                                        delay(waitMs)
                                        continue
                                    }
                                }

                                val ok = sendRelayChunk(url, idToken, workerIndex, chunkBody)
                                if (!ok) {
                                    val errs = errorCounter.incrementAndGet()
                                    onStatusChanged("Relay endpoint error #$errs — retrying")
                                    delay(1500L)
                                    break
                                } else {
                                    bytesDispatched.addAndGet(chunkBytes.toLong())
                                    if (errorCounter.get() > 0L) {
                                        errorCounter.set(0L)
                                        onStatusChanged(formatRelayStatus(targetMbPerMin))
                                    }
                                }
                                yield()
                            }
                        }
                    }
                }
                delay(50L)
            }
        }
    }

    private fun formatRelayStatus(targetMbPerMin: Int): String {
        val host = configuredHostLabel()
        return when {
            targetMbPerMin == 0 ->
                "Relay MAX ($host) • 64 streams (4 MB) • Unthrottled max speed"
            targetMbPerMin >= 1024 ->
                "Relay ${String.format(java.util.Locale.US, "%.2f", targetMbPerMin / 1024.0)} GB/min ($host) • 64 streams (4 MB)"
            else ->
                "Relay active ($host) • $targetMbPerMin MB/min target"
        }
    }

    private fun sendRelayChunk(
        url: String,
        idToken: String,
        workerIndex: Int,
        body: RequestBody
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("X-DataMint-Worker", workerIndex.toString())
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Relay chunk transfer failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "RelayGatewayClient"
        const val MAX_CONCURRENT_WORKERS = 64
        const val MAX_TARGET_MB_PER_MIN = 4096
        const val PACED_CHUNK_BYTES = 512 * 1024     // 512 KB chunks for lower paced targets
        const val TURBO_CHUNK_BYTES = 4 * 1024 * 1024 // 4 MB chunks to saturate link at max speed
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
