package com.ajmat.screencap

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
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

    init {
        try {
            if (resultData != null && resultCode != 0) {
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mediaProjection = mpm?.getMediaProjection(resultCode, resultData)
                if (mediaProjection != null) {
                    prepare()
                } else {
                    Log.e(TAG, "MediaProjection is null in init")
                }
            } else {
                Log.w(TAG, "ScreenshotCapturer created without valid projection data.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            // don't throw further — caller (service) should handle null capturer
        }
    }

    private fun prepare() {
        try {
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

            // create image reader with safe pixel format
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            handlerThread = HandlerThread("ScreenCapture").apply { start() }
            handler = Handler(handlerThread!!.looper)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "screencap",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            // cleanup on failure
            stop()
        }
    }

    fun captureOnce(): Bitmap? {
        try {
            val reader = imageReader ?: return null

            // Try acquire; if null, wait briefly and try again
            var img: Image? = reader.acquireLatestImage()
            if (img == null) {
                Thread.sleep(120) // small wait
                img = reader.acquireLatestImage()
            }
            img ?: return null

            val plane = img.planes.firstOrNull() ?: run {
                img.close()
                return null
            }
            val buffer: ByteBuffer = plane.buffer
            buffer.rewind()

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + if (pixelStride != 0) rowPadding / pixelStride else 0

            // create bitmap and copy safely
            val bmp = Bitmap.createBitmap(max(1, bitmapWidth), max(1, height), Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)

            // close image asap
            img.close()

            // crop to actual width/height if needed
            val cropped = if (bitmapWidth != width) {
                Bitmap.createBitmap(bmp, 0, 0, width, height)
            } else {
                bmp
            }

            // callback (do not block)
            onBitmapReady?.let {
                try {
                    it(cropped)
                } catch (e: Exception) {
                    Log.e(TAG, "onBitmapReady callback failed", e)
                }
            }

            return cropped
        } catch (e: Exception) {
            Log.e(TAG, "captureOnce failed", e)
            return null
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
