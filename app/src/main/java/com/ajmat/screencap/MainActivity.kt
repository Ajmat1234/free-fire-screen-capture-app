package com.ajmat.screencap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var intervalInput: EditText
    private lateinit var uploadUrlInput: EditText
    private lateinit var wsUrlInput: EditText  // Now used as baseUrl for polling
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusTv: TextView

    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var audioPoller: AudioPoller? = null  // Updated: Poller instead of WS

    private val requestProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                resultCode = result.resultCode
                resultData = result.data
                statusTv.text = "✅ Screen capture permission granted"
                checkAndRequestNotificationPermission()
            } else {
                Toast.makeText(this, "❌ Permission denied", Toast.LENGTH_SHORT).show()
                statusTv.text = "Status: Permission denied"
            }
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCaptureService()
            else Toast.makeText(this, "Notification permission required!", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intervalInput = findViewById(R.id.intervalInput)
        uploadUrlInput = findViewById(R.id.uploadUrlInput)
        wsUrlInput = findViewById(R.id.wsUrlInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        statusTv = findViewById(R.id.status)

        // Default values
        intervalInput.setText("3")
        uploadUrlInput.setText("https://practice-ppaz.onrender.com/upload")
        wsUrlInput.setText("https://practice-ppaz.onrender.com")  // Now baseUrl for polling

        startBtn.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mpm.createScreenCaptureIntent()
            requestProjection.launch(intent)
        }

        stopBtn.setOnClickListener {
            stopCaptureService()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startCaptureService()
    }

    private fun startCaptureService() {
        val interval = intervalInput.text.toString().toIntOrNull() ?: 3
        val uploadUrl = uploadUrlInput.text.toString().trim()
        val baseUrl = wsUrlInput.text.toString().trim()  // Updated: Use as base for polling

        if (resultData == null || resultCode == 0) {
            Toast.makeText(this, "Grant screen capture permission first", Toast.LENGTH_SHORT).show()
            return
        }

        // Start Poller (replaces WS)
        audioPoller = AudioPoller(
            baseUrl = baseUrl,
            onAudioUrl = { audioUrl ->
                // Trigger ExoPlayer
                ExoPlayerManager.play(this, audioUrl)
                Log.i("MainActivity", "Playing audio: $audioUrl")
            },
            onStatusChange = { status ->
                runOnUiThread {
                    statusTv.text = "Poller: $status"
                }
            }
        )
        audioPoller?.startPolling()

        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_INTERVAL, interval)
            putExtra(CaptureService.EXTRA_UPLOAD_URL, uploadUrl)
            // Removed WS_URL extra - not needed now
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_INTENT, resultData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)

        statusTv.text = "▶️ Running every ${interval}s | Polling for audio..."
    }

    private fun stopCaptureService() {
        val stopIntent = Intent(this, CaptureService::class.java)
        stopIntent.action = CaptureService.ACTION_STOP
        startService(stopIntent)
        audioPoller?.stopPolling()
        statusTv.text = "🛑 Stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPoller?.stopPolling()
    }
}
