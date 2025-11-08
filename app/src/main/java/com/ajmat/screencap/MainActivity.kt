package com.ajmat.screencap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.media.projection.MediaProjectionManager
import android.os.Build

class MainActivity : AppCompatActivity() {

    private lateinit var intervalInput: EditText
    private lateinit var uploadUrlInput: EditText
    private lateinit var wsUrlInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusTv: TextView

    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private val requestProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            resultCode = result.resultCode
            resultData = result.data
            statusTv.text = "Status: Permission granted"
            startCaptureService()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            statusTv.text = "Status: permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // restore projection data if activity recreated
        if (savedInstanceState != null) {
            resultCode = savedInstanceState.getInt("resultCode", 0)
            resultData = savedInstanceState.getParcelable("resultData")
        }

        intervalInput = findViewById(R.id.intervalInput)
        uploadUrlInput = findViewById(R.id.uploadUrlInput)
        wsUrlInput = findViewById(R.id.wsUrlInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        statusTv = findViewById(R.id.status)

        startBtn.setOnClickListener {
            val interval = intervalInput.text.toString().toIntOrNull() ?: 1
            if (interval < 1 || interval > 10) {
                Toast.makeText(this, "Enter 1-10 seconds", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uploadUrl = uploadUrlInput.text.toString().trim()
            val wsUrl = wsUrlInput.text.toString().trim()
            if (uploadUrl.isEmpty() || wsUrl.isEmpty()) {
                Toast.makeText(this, "Provide upload and WebSocket URLs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // request MediaProjection permission if not already
            if (resultData == null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mpm.createScreenCaptureIntent()
                requestProjection.launch(intent)
            } else {
                startCaptureService()
            }
        }

        stopBtn.setOnClickListener {
            val stopIntent = Intent(this, CaptureService::class.java)
            stopIntent.action = CaptureService.ACTION_STOP
            startService(stopIntent)
            statusTv.text = "Status: stopped"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("resultCode", resultCode)
        outState.putParcelable("resultData", resultData)
    }

    private fun startCaptureService() {
        val interval = intervalInput.text.toString().toIntOrNull() ?: 1
        val uploadUrl = uploadUrlInput.text.toString().trim()
        val wsUrl = wsUrlInput.text.toString().trim()

        if (resultData == null || resultCode == 0) {
            Toast.makeText(this, "Please grant screen capture permission first", Toast.LENGTH_SHORT).show()
            return
        }

        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_INTERVAL, interval)
            putExtra(CaptureService.EXTRA_UPLOAD_URL, uploadUrl)
            putExtra(CaptureService.EXTRA_WS_URL, wsUrl)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_INTENT, resultData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }

        statusTv.text = "Status: started (interval ${interval}s)"
    }
}
