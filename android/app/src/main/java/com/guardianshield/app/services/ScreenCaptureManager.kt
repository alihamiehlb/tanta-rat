package com.guardianshield.app.services

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
import com.guardianshield.app.Config
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Manages screen capture via MediaProjection API.
 *
 * Captures screen frames at up to 30 FPS, compresses to WebP,
 * and sends binary data to the server via MainService.
 * Includes duplicate frame detection to save bandwidth.
 */
class ScreenCaptureManager(
    private val service: MainService,
    private val resultCode: Int,
    private val data: Intent
) {
    companion object {
        private const val TAG = "GS_ScreenCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var isRunning = false
    private var lastFrameChecksum: Long = 0L

    // Capture dimensions (scaled down)
    private var captureWidth = 0
    private var captureHeight = 0
    private var screenDensity = 0

    /**
     * Start screen capture.
     * Creates MediaProjection, VirtualDisplay, and begins frame capture loop.
     */
    fun start() {
        if (isRunning) return
        Log.i(TAG, "Starting screen capture")

        // Get screen metrics
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenDensity = metrics.densityDpi
        captureWidth = (metrics.widthPixels * Config.streamScale).toInt()
        captureHeight = (metrics.heightPixels * Config.streamScale).toInt()

        // Ensure even dimensions (required by some encoders)
        captureWidth = captureWidth and 0xFFFE.toInt()
        captureHeight = captureHeight and 0xFFFE.toInt()

        Log.i(TAG, "Capture size: ${captureWidth}x${captureHeight} (scale: ${Config.streamScale})")

        // Create capture thread
        captureThread = HandlerThread("ScreenCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // Create ImageReader
        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888, 2 // Double buffer
        )

        // Create MediaProjection
        val projMgr = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, data)

        // Register callback for projection stop
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped")
                isRunning = false
            }
        }, captureHandler)

        // Create VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GuardianShield",
            captureWidth, captureHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler
        )

        isRunning = true

        // Start frame capture loop
        startCaptureLoop()
    }

    /**
     * Frame capture loop running at target FPS.
     * Uses Handler.postDelayed for timing control.
     */
    private fun startCaptureLoop() {
        val frameIntervalMs = 1000L / Config.streamFps // ~33ms for 30 FPS

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isRunning) return@setOnImageAvailableListener

            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                // Convert Image to Bitmap
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val bitmap = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop if there's padding
                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight).also {
                        bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                // Quick duplicate check using CRC32 on first 4KB of pixels
                val checksum = computeQuickChecksum(croppedBitmap)
                if (checksum == lastFrameChecksum) {
                    croppedBitmap.recycle()
                    return@setOnImageAvailableListener // Skip duplicate frame
                }
                lastFrameChecksum = checksum

                // Compress to WebP
                val outputStream = ByteArrayOutputStream()
                croppedBitmap.compress(
                    Bitmap.CompressFormat.WEBP, // WEBP for efficiency
                    Config.streamQuality,
                    outputStream
                )
                croppedBitmap.recycle()

                val frameBytes = outputStream.toByteArray()

                // Send to server
                service.emitFrame(frameBytes, captureWidth, captureHeight)
            } catch (e: Exception) {
                Log.e(TAG, "Frame capture error", e)
            } finally {
                image?.close()
            }
        }, captureHandler)
    }

    /**
     * Compute a quick CRC32 checksum on the first few KB of bitmap pixels.
     * Used for duplicate frame detection without comparing full frame data.
     */
    private fun computeQuickChecksum(bitmap: Bitmap): Long {
        val width = bitmap.width.coerceAtMost(64) // Sample a 64x64 region
        val height = bitmap.height.coerceAtMost(64)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val crc = CRC32()
        for (pixel in pixels) {
            crc.update(pixel)
        }
        return crc.value
    }

    /**
     * Stop screen capture and release resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping screen capture")
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }
}
