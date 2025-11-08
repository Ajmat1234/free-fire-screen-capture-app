package com.ajmat.screencap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class CaptureService : Service() {

    companion object {
        const val TAG = "CaptureService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_INTERVAL = "EXTRA_INTERVAL"
        const val EXTRA_UPLOAD_URL = "EXTRA_UPLOAD_URL"
        const val EXTRA_WS_URL = "EXTRA_WS_URL"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_INTENT = "EXTRA_RESULT_INTENT"

        const val NOTIF_CHANNEL = "capture_channel"
        const val NOTIF_ID = 142
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capturer: ScreenshotCapturer? = null
    private var wsClient: WebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand: intent is null")
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val interval = intent.getIntExtra(EXTRA_INTERVAL, 1)
                val uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL) ?: ""
                val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: ""
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                // ✅ Safe way to get Parcelable Intent on Android 13+
                val data: Intent? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_INTENT)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse EXTRA_RESULT_INTENT", e)
                    null
                }

                if (resultCode == 0 || data == null) {
                    Log.e(TAG, "Missing MediaProjection permission data. Service stopping.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIF_ID, createNotification("Capturing screen..."))

                try {
                    capturer = ScreenshotCapturer(this, resultCode, data) { bitmap ->
                        scope.launch {
                            if (uploadUrl.isNotEmpty()) uploadBitmap(uploadUrl, bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize ScreenshotCapturer", e)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // optional websocket (audio trigger)
                if (wsUrl.isNotEmpty()) {
                    try {
                        wsClient = WebSocketClient(wsUrl) { audioUrl ->
                            try {
                                ExoPlayerManager.play(this, audioUrl)
                            } catch (e: Throwable) {
                                Log.e(TAG, "ExoPlayerManager.play failed", e)
                            }
                        }
                        wsClient?.connect()
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket initialization failed: ${e.message}")
                    }
                }

                // start capturing loop
                scope.launch {
                    val safeInterval = interval.coerceIn(1, 10)
                    while (isActive) {
                        try {
                            val bmp = capturer?.captureOnce()
                            if (bmp != null && uploadUrl.isNotEmpty()) {
                                uploadBitmap(uploadUrl, bmp)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture loop exception", e)
                        }
                        delay(safeInterval * 1000L)
                    }
                }
            }

            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun uploadBitmap(uploadUrl: String, bitmap: Bitmap) {
        if (uploadUrl.isEmpty()) return
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "screenshot.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val req = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val resp = client.newCall(req).execute()
            resp.close()

            Log.i(TAG, "Screenshot uploaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Screen Capture Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                NOTIF_CHANNEL,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            capturer?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Capturer stop failed", e)
        }

        try {
            wsClient?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "WebSocket close failed", e)
        }

        try {
            ExoPlayerManager.release(this)
        } catch (e: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
