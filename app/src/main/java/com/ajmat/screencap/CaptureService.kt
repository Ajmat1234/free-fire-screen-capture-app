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
import kotlin.system.measureTimeMillis

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
    private var isCapturing = false  // NEW: Flag to prevent overlap

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

        if (interval < 2) {
            Log.w(TAG, "Interval <2s may cause high CPU/battery—consider increasing")
        }

        startForeground(NOTIF_ID, createNotification("Capturing screen..."))
        capturer = ScreenshotCapturer(this, resultCode, data)

        // NEW: Timed scheduler—start captures at exact intervals from now
        val startTime = System.currentTimeMillis()
        scope.launch {
            while (isActive) {
                val nextCaptureTime = startTime + (captureCount * interval * 1000L)
                val now = System.currentTimeMillis()
                val delayUntilNext = nextCaptureTime - now
                if (delayUntilNext > 0) {
                    Log.d(TAG, "Scheduling next capture in ${delayUntilNext}ms (target interval: ${interval}s)")
                    delay(delayUntilNext)
                } else {
                    Log.w(TAG, "Missed schedule by ${-delayUntilNext}ms—capturing immediately")
                }

                if (isCapturing) {
                    Log.w(TAG, "Capture in progress—skipping this slot")
                    captureCount++  // Still count to keep schedule
                    continue
                }

                isCapturing = true
                captureCount++
                val captureTime = measureTimeMillis {  // NEW: Measure capture time
                    try {
                        val bmp = capturer?.captureOnce()
                        if (bmp != null) {
                            // NEW: Async upload—non-blocking
                            scope.launch(Dispatchers.IO) {
                                val uploadTime = measureTimeMillis {
                                    uploadBitmap(uploadUrl, bmp)
                                }
                                Log.d(TAG, "Upload #$captureCount completed in ${uploadTime}ms")
                            }
                            updateNotification("Captured #$captureCount ✅")
                            Log.d(TAG, "Capture #$captureCount successful in ${captureTime}ms")
                        } else {
                            updateNotification("Capture failed ❌ ($captureCount)")
                            Log.w(TAG, "Capture #$captureCount failed (time: ${captureTime}ms)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture error #$captureCount", e)
                        updateNotification("Error #$captureCount: ${e.message}")
                    }
                }
                Log.d(TAG, "Full cycle #$captureCount: ${captureTime}ms (excluding upload)")
                isCapturing = false
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
            else Log.d(TAG, "Upload successful for #$captureCount")
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
        isCapturing = false
        scope.cancel()
        capturer?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
