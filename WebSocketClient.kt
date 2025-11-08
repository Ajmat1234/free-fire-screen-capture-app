package com.example.screencap

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*

class WebSocketClient(private val wsUrl: String, private val onAudioUrl: (String) -> Unit) {

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()

    fun connect() {
        try {
            val req = Request.Builder().url(wsUrl).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i("WS", "connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = gson.fromJson(text, Map::class.java)
                        val audioUrl = obj["audio_url"] as? String
                        if (audioUrl != null) {
                            onAudioUrl(audioUrl)
                        }
                    } catch (e: Exception) {
                        Log.e("WS", "parse error", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WS", "failure", t)
                    reconnectWithDelay()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i("WS", "closed $reason")
                }
            })
        } catch (e: Exception) {
            Log.e("WS", "connect exception", e)
            reconnectWithDelay()
        }
    }

    private fun reconnectWithDelay() {
        GlobalScope.launch {
            delay(3000)
            connect()
        }
    }

    fun close() {
        ws?.close(1000, "bye")
    }
}
