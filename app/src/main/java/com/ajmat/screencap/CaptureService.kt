package com.ajmat.screencap

import android.app.*
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
    private var captureCount = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapturing(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startCapturing(intent: Intent) {
        val interval = intent.getIntExtra(EXTRA_INTERVAL, 3)
        val uploadUrl = intent.getStringExtra(EXTRA_UPLOAD_URL) ?: ""
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_RESULT_INTENT, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_INTENT)

        if (data == null || resultCode == 0) {
            Toast.makeText(this, "Missing projection data", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Missing media projection permission", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, createNotification("Capturing screen..."))
        capturer = ScreenshotCapturer(this, resultCode, data)

        scope.launch {
            val safeInterval = interval.coerceIn(1, 10)
            while (isActive) {
                captureCount++
                try {
                    val bmp = capturer?.captureOnce()
                    if (bmp != null) {
                        uploadBitmap(uploadUrl, bmp)
                        updateNotification("Captured #$captureCount ✅")
                    } else {
                        updateNotification("Capture failed ❌ ($captureCount)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error", e)
                    updateNotification("Error #$captureCount: ${e.message}")
                }
                delay(safeInterval * 1000L)
            }
        }
    }

    private suspend fun uploadBitmap(url: String, bitmap: Bitmap) {
        if (url.isEmpty()) return
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "screenshot_${System.currentTimeMillis()}.jpg",
                stream.toByteArray().toRequestBody("image/jpeg".toMediaType())
            ).build()
        val req = Request.Builder().url(url).post(body).build()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) Log.e(TAG, "Upload failed: ${resp.code}")
        }
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Screen Capture Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        capturer?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
