package com.ajmat.screencap

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.*
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max

class ScreenshotCapturer(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent?
) {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var width = 0
    private var height = 0
    private var density = 0

    init { prepare() }

    private fun prepare() {
        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
            val metrics = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            handlerThread = HandlerThread("ScreenCap").apply { start() }
            handler = Handler(handlerThread!!.looper)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "screencap", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
        } catch (e: Exception) {
            Log.e("ScreenshotCapturer", "prepare failed", e)
        }
    }

    fun captureOnce(): Bitmap? {
        var img: Image? = null
        try {
            img = imageReader?.acquireLatestImage()
            if (img == null) {
                TimeUnit.MILLISECONDS.sleep(100)
                img = imageReader?.acquireLatestImage()
            }
            val plane = img?.planes?.firstOrNull() ?: return null
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bmpW = rowStride / pixelStride
            val bmp = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bmp, 0, 0, width, height)
        } catch (e: Exception) {
            Log.e("ScreenshotCapturer", "capture failed", e)
        } finally {
            try { img?.close() } catch (_: Throwable) {}
        }
        return null
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        try { handlerThread?.quitSafely() } catch (_: Throwable) {}
    }
}
