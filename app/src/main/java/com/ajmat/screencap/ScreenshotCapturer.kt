package com.ajmat.screencap

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ScreenshotCapturer(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent?,
    private val onBitmapReady: ((Bitmap) -> Unit)? = null
) {

    companion object {
        private const val TAG = "ScreenshotCapturer"
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var width = 0
    private var height = 0
    private var density = 0
    private var currentRotation = 0  // Added: Current display rotation

    @Volatile
    private var capturing = false

    init {
        try {
            if (resultData != null && resultCode != 0) {
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mediaProjection = mpm?.getMediaProjection(resultCode, resultData)
                if (mediaProjection != null) {
                    Log.d(TAG, "MediaProjection created successfully")
                    prepare()
                } else {
                    Log.e(TAG, "MediaProjection is null in init")
                }
            } else {
                Log.w(TAG, "ScreenshotCapturer created without valid projection data.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
        }
    }

    private fun prepare() {
        try {
            Log.d(TAG, "prepare() called")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val display = wm?.defaultDisplay ?: run {
                Log.e(TAG, "No default display")
                return
            }
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi

            // Added: Get current rotation for landscape support
            currentRotation = display.rotation
            Log.d(TAG, "Current rotation: $currentRotation")

            Log.d(TAG, "Display size: $width x $height, density: $density")

            val pixelFormat = PixelFormat.RGBA_8888
            imageReader = ImageReader.newInstance(width, height, pixelFormat, 2)
            Log.d(TAG, "ImageReader created with format: $pixelFormat")

            handlerThread = HandlerThread("ScreenCapture").apply { start() }
            handler = Handler(handlerThread!!.looper)
            Log.d(TAG, "Handler thread started")

            val callbackHandler = Handler(Looper.getMainLooper())
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system – cleaning up")
                    stop()
                }
            }, callbackHandler)
            Log.d(TAG, "MediaProjection callback registered")

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            Log.d(TAG, "Creating VirtualDisplay with flags: $flags (Rotation: $currentRotation)")

            virtualDisplay = try {
                mediaProjection?.createVirtualDisplay(
                    "screencap",
                    width,
                    height,
                    density,
                    flags,
                    imageReader?.surface,
                    null,
                    handler
                ).also {
                    if (it != null) {
                        Log.i(TAG, "VirtualDisplay created successfully")
                    } else {
                        Log.e(TAG, "createVirtualDisplay returned null")
                    }
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "createVirtualDisplay failed (illegal state)", e)
                null
            } catch (e: SecurityException) {
                Log.e(TAG, "createVirtualDisplay failed (security – check 'Entire screen' permission)", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "createVirtualDisplay failed", e)
                null
            }

            if (virtualDisplay == null) {
                Log.e(TAG, "virtualDisplay is null after create – stopping")
                stop()
            } else {
                Log.i(TAG, "Virtual display created successfully with flags")
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            stop()
        }
    }

    fun captureOnce(): Bitmap? {
        if (capturing) {
            Log.w(TAG, "captureOnce already running")
            return null
        }
        capturing = true
        try {
            val reader = imageReader ?: run {
                Log.w(TAG, "captureOnce: imageReader is null")
                return null
            }

            var img: Image? = reader.acquireLatestImage()
            if (img == null) {
                Log.d(TAG, "First acquireLatestImage returned null – retrying...")
                TimeUnit.MILLISECONDS.sleep(200)
                img = reader.acquireLatestImage()
                if (img == null) {
                    TimeUnit.MILLISECONDS.sleep(100)
                    img = reader.acquireLatestImage()
                }
            }
            if (img == null) {
                Log.w(TAG, "No image available after retry")
                return null
            }

            val plane = img.planes.firstOrNull()
            if (plane == null) {
                img.close()
                Log.w(TAG, "captureOnce: image has no planes")
                return null
            }

            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride == 0) {
                img.close()
                Log.w(TAG, "captureOnce: pixelStride == 0")
                return null
            }

            val bitmapWidth = rowStride / pixelStride
            val bitmapHeight = height
            val bmpW = max(1, bitmapWidth)
            val bmpH = max(1, bitmapHeight)

            buffer.rewind()

            val config = Bitmap.Config.ARGB_8888
            val bmp = try {
                Bitmap.createBitmap(bmpW, bmpH, config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create bitmap", e)
                img.close()
                return null
            }

            try {
                bmp.copyPixelsFromBuffer(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "copyPixelsFromBuffer failed", e)
                img.close()
                bmp.recycle()
                return null
            } finally {
                try { img.close() } catch (_: Throwable) {}
            }

            // Added: Rotate bitmap based on current rotation for landscape support
            var finalBitmap = bmp
            if (currentRotation != Surface.ROTATION_0) {
                val matrix = Matrix()
                when (currentRotation) {
                    Surface.ROTATION_90 -> matrix.postRotate(90f)
                    Surface.ROTATION_180 -> matrix.postRotate(180f)
                    Surface.ROTATION_270 -> matrix.postRotate(270f)
                }
                finalBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()  // Old bitmap recycle
                Log.d(TAG, "Bitmap rotated for landscape (rotation: $currentRotation)")
            }

            if (bmp.width != width || bmp.height != height) {
                try {
                    Bitmap.createBitmap(bmp, 0, 0, width.coerceAtMost(bmp.width), height.coerceAtMost(bmp.height))
                } catch (e: Exception) {
                    Log.e(TAG, "cropping failed", e)
                    bmp
                }
            }

            onBitmapReady?.let { cb ->
                try {
                    cb(finalBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "onBitmapReady callback failed", e)
                }
            }

            return finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureOnce failed", e)
            return null
        } finally {
            capturing = false
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "virtualDisplay release failed", e)
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "imageReader close failed", e)
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "mediaProjection stop failed", e)
        }
        mediaProjection = null

        try {
            handlerThread?.quitSafely()
            handlerThread?.join(200)
        } catch (e: Throwable) {
            Log.w(TAG, "handlerThread stop failed", e)
        } finally {
            handlerThread = null
            handler = null
        }
    }
}
