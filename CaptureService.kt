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
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val interval = intent.getIntExtra(EXTRA_INTERVAL, 1)
                val uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL) ?: ""
                val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: ""
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_INTENT)

                // Validate media projection permission BEFORE creating capturer
                if (resultCode == 0 || data == null) {
                    Log.e(TAG, "Missing MediaProjection permission data. Stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIF_ID, createNotification("Capturing..."))

                try {
                    capturer = ScreenshotCapturer(this, resultCode, data) { bitmap ->
                        scope.launch {
                            uploadBitmap(uploadUrl, bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create ScreenshotCapturer", e)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // connect websocket for audio triggers
                try {
                    wsClient = WebSocketClient(wsUrl) { audioUrl ->
                        // play audio via ExoPlayer safely
                        try {
                            ExoPlayerManager.play(this, audioUrl)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Exo playback error", e)
                        }
                    }
                    wsClient?.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket client init failed", e)
                    // continue anyway; audio optional
                }

                // schedule repeated capture
                scope.launch {
                    while (isActive) {
                        try {
                            val bmp = capturer?.captureOnce()
                            if (bmp != null && uploadUrl.isNotEmpty()) uploadBitmap(uploadUrl, bmp)
                        } catch (e: Exception) {
                            Log.e(TAG, "capture loop exception", e)
                        }
                        delay((interval.coerceIn(1, 10)) * 1000L)
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
            val bytes = stream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "screenshot.jpg",
                    RequestBody.create("image/jpeg".toMediaTypeOrNull(), bytes))
                .build()

            val req = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            val resp = client.newCall(req).execute()
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Screen Capture running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(NOTIF_CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { capturer?.stop() } catch (e: Throwable) { e.printStackTrace() }
        try { wsClient?.close() } catch (e: Throwable) { e.printStackTrace() }
        try { ExoPlayerManager.release(this) } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
