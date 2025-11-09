package com.ajmat.screencap

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.internal.ws.RealWebSocket  // For internal access if needed, but avoid
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random  // For jitter
import okhttp3.ByteString  // For sendPong

class WebSocketClient(
    private val wsUrl: String,
    private val onAudioUrl: (String) -> Unit,
    private val onStatusChange: (String) -> Unit  // New: For UI status updates
) {

    companion object {
        private const val TAG = "WebSocketClient"
        private const val PING_INTERVAL_MS = 25000L  // 25s ping to keep Render alive
    }

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)  // Built-in ping support
        .build()
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectAttempts = AtomicInteger(0)
    private var isClosedManually = false
    private var pingJob: Job? = null
    private var isConnected = false  // Manual health flag

    fun connect() {
        if (wsUrl.isBlank()) {
            Log.w(TAG, "Empty wsUrl, not connecting")
            onStatusChange("Invalid URL")
            return
        }
        isClosedManually = false
        onStatusChange("Connecting...")
        tryConnect()
    }

    private fun tryConnect() {
        try {
            val req = Request.Builder().url(wsUrl).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Connected successfully")
                    reconnectAttempts.set(0)
                    isConnected = true
                    onStatusChange("Connected")  // UI update
                    startPingScheduler()  // Start pings
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = gson.fromJson(text, Map::class.java)
                        val audioUrl = obj["audio_url"] as? String
                        if (!audioUrl.isNullOrBlank()) {
                            try {
                                onAudioUrl(audioUrl)
                                Log.i(TAG, "Audio URL received: $audioUrl")
                            } catch (e: Exception) {
                                Log.e(TAG, "onAudioUrl handler failed", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse error: $text", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Connection failure: ${t.message}", t)
                    isConnected = false
                    onStatusChange("Reconnecting... (Attempt ${reconnectAttempts.get() + 1})")
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Connection closed: $code - $reason")
                    isConnected = false
                    stopPingScheduler()
                    if (!isClosedManually) {
                        onStatusChange("Disconnected - Reconnecting...")
                        scheduleReconnect()
                    } else {
                        onStatusChange("Disconnected")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connect exception", e)
            onStatusChange("Connection Error")
            scheduleReconnect()
        }
    }

    private fun startPingScheduler() {
        stopPingScheduler()  // Cancel previous if any
        pingJob = scope.launch {
            while (isActive && isConnected) {  // Use manual flag
                delay(PING_INTERVAL_MS)
                try {
                    ws?.sendPong(ByteString.EMPTY)  // Correct: ByteString.EMPTY
                    Log.d(TAG, "Ping sent to keep Render alive")
                } catch (e: Exception) {
                    Log.e(TAG, "Ping failed", e)
                    isConnected = false
                    break  // Trigger reconnect
                }
            }
        }
    }

    private fun stopPingScheduler() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > 10) {  // Max attempts
            Log.e(TAG, "Max reconnect attempts reached. Giving up.")
            onStatusChange("Failed to Connect - Check Server")
            return
        }
        val baseDelay = when {
            attempt < 3 -> 2000L
            attempt < 6 -> 5000L
            else -> 10000L
        }
        val jitter = Random.nextLong(0, 1000)  // Random 0-1s jitter
        val delayMs = baseDelay + jitter
        scope.launch {
            delay(delayMs)
            if (!isClosedManually) {
                tryConnect()
            }
        }
    }

    fun close() {
        isClosedManually = true
        isConnected = false
        stopPingScheduler()
        try {
            ws?.close(1000, "Manual close")
        } catch (e: Exception) {
            Log.e(TAG, "Close failed", e)
        }
        scope.cancel()
        onStatusChange("Disconnected")
    }
}
