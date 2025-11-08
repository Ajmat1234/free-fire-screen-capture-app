package com.ajmat.screencap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.Manifest
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody

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
        Log.d(TAG, "Service created")
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

                Log.d(TAG, "Starting service with interval=$interval, uploadUrl=$uploadUrl, wsUrl=$wsUrl")

                // ✅ Safe Parcelable fetch for Android 13+
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
                    Toast.makeText(this, "Screen capture permission denied. Service stopping.", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                // ✅ Android 14+ (API 34) check for FOREGROUND_SERVICE_MEDIA_PROJECTION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing FOREGROUND_SERVICE_MEDIA_PROJECTION permission. Service stopping.")
                        Toast.makeText(this, "Foreground service permission missing. Check app settings.", Toast.LENGTH_LONG).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                Log.d(TAG, "Starting foreground notification")
                startForeground(NOTIF_ID, createNotification("Capturing screen..."))

                try {
                    Log.d(TAG, "Initializing ScreenshotCapturer")
                    capturer = ScreenshotCapturer(this, resultCode, data) { bitmap ->
                        scope.launch {
                            if (uploadUrl.isNotEmpty()) uploadBitmap(uploadUrl, bitmap)
                        }
                    }
                    Log.i(TAG, "ScreenshotCapturer initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize ScreenshotCapturer", e)
                    Toast.makeText(this, "Screenshot capturer init failed: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Optional websocket for sound triggers
                if (wsUrl.isNotEmpty()) {
                    try {
                        Log.d(TAG, "Initializing WebSocket")
                        wsClient = WebSocketClient(wsUrl) { audioUrl ->
                            try {
                                ExoPlayerManager.play(this, audioUrl)
                            } catch (e: Throwable) {
                                Log.e(TAG, "ExoPlayerManager.play failed", e)
                            }
                        }
                        wsClient?.connect()
                        Log.i(TAG, "WebSocket connected")
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket init failed: ${e.message}")
                    }
                }

                // Capture loop
                scope.launch {
                    val safeInterval = interval.coerceIn(1, 10)
                    Log.d(TAG, "Starting capture loop with interval $safeInterval seconds")
                    while (isActive) {
                        try {
                            Log.d(TAG, "Attempting to capture screenshot...")
                            val bmp = capturer?.captureOnce()
                            if (bmp != null) {
                                Log.i(TAG, "Screenshot captured successfully, size: ${bmp.width}x${bmp.height}, bytes: ${bmp.byteCount}")
                                if (uploadUrl.isNotEmpty()) {
                                    Log.d(TAG, "Uploading screenshot to $uploadUrl...")
                                    uploadBitmap(uploadUrl, bmp)
                                } else {
                                    Log.w(TAG, "Screenshot captured but no upload URL provided")
                                }
                            } else {
                                Log.w(TAG, "Screenshot capture returned null - skipping upload")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture loop error", e)
                        }
                        delay(safeInterval * 1000L)
                    }
                    Log.d(TAG, "Capture loop ended")
                }
                Log.i(TAG, "Service started successfully")
            }

            ACTION_STOP -> {
                Log.d(TAG, "Stopping service")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun uploadBitmap(uploadUrl: String, bitmap: Bitmap) {
        if (uploadUrl.isEmpty()) return
        try {
            Log.d(TAG, "Compressing bitmap for upload (quality 60%)")
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()
            Log.d(TAG, "Bitmap compressed to ${bytes.size} bytes")

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

            Log.d(TAG, "Sending POST request to $uploadUrl")
            val resp = client.newCall(req).execute()
            val responseCode = resp.code
            val responseBody = resp.body?.string() ?: "No response body"
            resp.close()
            
            if (resp.isSuccessful) {
                Log.i(TAG, "Screenshot uploaded successfully to $uploadUrl (code $responseCode). Response: $responseBody")
                // Optional: Update notification or Toast for user feedback
                updateNotification("Screenshot uploaded (size: ${bytes.size} bytes)")
            } else {
                Log.e(TAG, "Upload failed with code $responseCode to $uploadUrl. Response: $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        }
    }

    // New: Update notification text for feedback
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val nm = getSystemService(NotificationManager::class.java) as NotificationManager
        nm.notify(NOTIF_ID, notification)
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
        Log.d(TAG, "Service destroying...")
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
            ExoPlayerManager.release()
        } catch (e: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", e)
        }
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
