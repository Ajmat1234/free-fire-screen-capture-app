package com.example.screencap

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

                startForeground(NOTIF_ID, createNotification("Capturing..."))

                capturer = ScreenshotCapturer(this, resultCode, data) { bitmap ->
                    // When a screenshot is ready, upload it
                    scope.launch {
                        uploadBitmap(uploadUrl, bitmap)
                    }
                }

                // connect websocket for audio triggers
                wsClient = WebSocketClient(wsUrl) { audioUrl ->
                    // play audio via ExoPlayer
                    ExoPlayerManager.play(this, audioUrl)
                }
                wsClient?.connect()

                // schedule repeated capture
                scope.launch {
                    while (isActive) {
                        try {
                            val bmp = capturer?.captureOnce()
                            // upload handled in callback too, but captureOnce returns bitmap for sync use
                            if (bmp != null) uploadBitmap(uploadUrl, bmp)
                        } catch (e: Exception) {
                            Log.e(TAG, "capture loop exception", e)
                        }
                        delay(interval * 1000L)
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
        try {
            val stream = ByteArrayOutputStream()
            // compress to jpeg low quality for smaller size
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
            val bytes = stream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "screenshot.jpg", RequestBody.create("image/jpeg".toMediaTypeOrNull(), bytes))
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
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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
        capturer?.stop()
        wsClient?.close()
        ExoPlayerManager.release(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
