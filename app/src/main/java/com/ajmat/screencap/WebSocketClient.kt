package com.ajmat.screencap

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import java.util.Map  // Added: For gson.fromJson(Map::class.java)
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClient(
    private val wsUrl: String,
    private val onAudioUrl: (String) -> Unit
) {

    companion object {
        private const val TAG = "WebSocketClient"
    }

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectAttempts = AtomicInteger(0)
    private var isClosedManually = false

    fun connect() {
        if (wsUrl.isBlank()) {
            Log.w(TAG, "Empty wsUrl, not connecting")
            return
        }
        isClosedManually = false
        tryConnect()
    }

    private fun tryConnect() {
        try {
            val req = Request.Builder().url(wsUrl).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "connected")
                    reconnectAttempts.set(0)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = gson.fromJson(text, Map::class.java)
                        val audioUrl = obj["audio_url"] as? String
                        if (!audioUrl.isNullOrBlank()) {
                            try {
                                onAudioUrl(audioUrl)
                            } catch (e: Exception) {
                                Log.e(TAG, "onAudioUrl handler failed", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "parse error", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "failure", t)
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "closed $reason")
                    if (!isClosedManually) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect exception", e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempts.incrementAndGet()
        val delayMs = when {
            attempt < 3 -> 3000L
            attempt < 6 -> 5000L
            else -> 10000L
        }
        scope.launch {
            delay(delayMs)
            if (!isClosedManually) tryConnect()
        }
    }

    fun close() {
        isClosedManually = true
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
            Log.e(TAG, "close failed", e)
        }
        scope.cancel()
    }
}
