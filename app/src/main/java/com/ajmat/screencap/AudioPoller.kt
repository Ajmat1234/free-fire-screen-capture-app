package com.ajmat.screencap

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioPoller(
    private val baseUrl: String,  // e.g., "https://practice-ppaz.onrender.com"
    private val onAudioUrl: (String) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioPoller"
        private const val POLL_INTERVAL_MS = 3000L  // 3s poll
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTimestamp: String? = null  // Track last received
    private val isRunning = AtomicBoolean(false)

    fun startPolling() {
        if (baseUrl.isBlank()) {
            Log.w(TAG, "Empty baseUrl, not polling")
            onStatusChange("Invalid URL")
            return
        }
        isRunning.set(true)
        onStatusChange("Polling started...")
        scope.launch {
            while (isRunning.get()) {
                pollForAudio()
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Polling started every ${POLL_INTERVAL_MS/1000}s")
    }

    private suspend fun pollForAudio() {
        try {
            val request = Request.Builder()
                .url("$baseUrl/latest-audio")
                .build()
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val json = response.body?.string()
                Log.d(TAG, "Poll response: $json")
                val obj = gson.fromJson(json, Map::class.java)
                val newUrl = obj["audio_url"] as? String
                val newTs = obj["timestamp"] as? String

                if (!newUrl.isNullOrBlank() && newTs != lastTimestamp) {
                    lastTimestamp = newTs
                    withContext(Dispatchers.Main) {
                        onAudioUrl(newUrl)
                        onStatusChange("Playing new audio...")
                    }
                    Log.i(TAG, "New audio detected: $newUrl")
                } else {
                    Log.d(TAG, "No new audio")
                }
            } else {
                Log.w(TAG, "Poll failed: ${response.code}")
                onStatusChange("Poll error - retrying...")
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Poll exception", e)
            onStatusChange("Connection issue - retrying...")
        }
    }

    fun stopPolling() {
        isRunning.set(false)
        scope.cancel()
        onStatusChange("Polling stopped")
        Log.i(TAG, "Polling stopped")
    }
}
