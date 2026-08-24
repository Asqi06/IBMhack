package com.scamshield.app

import android.app.Activity
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
import android.os.Looper
import android.util.DisplayMetrics
import kotlinx.coroutines.delay

/**
 * Thrown when there is no usable capture session and the user must grant consent again.
 * Distinct type so callers don't have to pattern-match on message text.
 */
class NeedsConsentException(message: String) : RuntimeException(message)

/**
 * Screenshot via MediaProjection — persistent single session.
 *
 * WHY THIS IS A PERSISTENT MIRROR AND NOT A PER-TAP CAPTURE:
 * Android 14 (targetSdk 34+) throws SecurityException if createVirtualDisplay() is called more
 * than once on the same MediaProjection instance — "each MediaProjection instance must be used
 * only once". The previous design created (and released) a fresh VirtualDisplay on every tap, so
 * the SECOND scan onward threw, was misread as "consent ended", and forced the user to grant
 * screen capture again and again.
 *
 * The fix: call createVirtualDisplay ONCE per consent grant and keep it mirroring the screen. A
 * background listener continuously holds the latest mirrored frame. Each tap just reads that frame
 * — no new createVirtualDisplay, so no repeat consent. The projection (and its virtual display)
 * live until the user revokes it, the service is destroyed, or the platform ends the session.
 */
class ScreenshotHelper(private val context: Context) {

    fun getProjectionIntent(): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun isProjectionGranted(resultCode: Int) = resultCode == Activity.RESULT_OK

    /** Live projection for this capture session. Null until acquireProjection(), and after onStop. */
    private var cachedProjection: MediaProjection? = null

    /** The single VirtualDisplay for this session (Android 14: only one createVirtualDisplay allowed). */
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var vdWidth = 0
    private var vdHeight = 0

    /** Latest mirrored frame, continuously refreshed by the reader's listener. Guarded by [frameLock]. */
    private val frameLock = Any()
    private var latestImage: Image? = null

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    /** Invoked when the platform ends the projection, so the service can ask for fresh consent. */
    var onProjectionStopped: (() -> Unit)? = null

    fun hasLiveProjection() = cachedProjection != null

    /**
     * Consume the screen-capture consent token and hold the resulting MediaProjection.
     *
     * Must be called as soon as the token arrives, NOT lazily on first capture. The documented
     * Android 14 order is: createScreenCaptureIntent() -> user grants ->
     * startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) -> getMediaProjection(). A token
     * left unconsumed while the user hunts for the bubble goes stale, and getMediaProjection()
     * then throws SecurityException. The token is single-use, so this runs once per consent grant.
     *
     * The VirtualDisplay is NOT created here (we need real display metrics first) — it is created
     * exactly once on the first capture, then reused for the whole session.
     */
    fun acquireProjection(resultCode: Int, data: Intent) {
        invalidate()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection() returned null — consent was not valid")
        // Android 14+ requires a callback registered before createVirtualDisplay().
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseDisplay()
                cachedProjection = null
                onProjectionStopped?.invoke()
            }
        }, Handler(Looper.getMainLooper()))
        cachedProjection = p
    }

    /** Called when a fresh consent token arrives, so the next session builds a new projection. */
    fun invalidate() {
        releaseDisplay()
        try { cachedProjection?.stop() } catch (_: Exception) {}
        cachedProjection = null
    }

    fun release() {
        onProjectionStopped = null
        invalidate()
    }

    /** Tear down the mirror (virtual display + reader + frame + worker thread). Projection untouched. */
    private fun releaseDisplay() {
        synchronized(frameLock) {
            try { latestImage?.close() } catch (_: Exception) {}
            latestImage = null
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        imageHandler = null
        vdWidth = 0
        vdHeight = 0
    }

    private fun ensureImageThread() {
        if (imageThread == null) {
            val t = HandlerThread("ScamShieldCapture").also { it.start() }
            imageThread = t
            imageHandler = Handler(t.looper)
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        // maxImages=3: the producer keeps mirroring while we hold the most-recent frame for the
        // next tap, so we need one extra slot beyond the transient acquire.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            synchronized(frameLock) {
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) {
                    try { latestImage?.close() } catch (_: Exception) {}
                    latestImage = img
                }
            }
        }, imageHandler)
        return reader
    }

    /**
     * Guarantee a live VirtualDisplay for the current projection.
     *  - First capture: the single allowed createVirtualDisplay() for this MediaProjection.
     *  - Rotation / size change: Android 14 forbids a second createVirtualDisplay on the same
     *    instance, so resize the existing one and hand it a new surface (the documented path).
     */
    private fun ensureDisplay(metrics: DisplayMetrics) {
        val projection = cachedProjection
            ?: throw NeedsConsentException("Screen capture session has ended")
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid display metrics $width x $height — try again")
        }

        val vd = virtualDisplay
        if (vd != null && imageReader != null) {
            if (width == vdWidth && height == vdHeight) return
            // Screen rotated / resized: reuse the SAME MediaProjection via resize + setSurface.
            val newReader = newReader(width, height)
            try {
                vd.resize(width, height, density)
                @Suppress("DEPRECATION")
                vd.surface = newReader.surface
            } catch (e: Exception) {
                try { newReader.close() } catch (_: Exception) {}
                throw RuntimeException("Failed to resize capture surface: ${e.message}")
            }
            val old = imageReader
            imageReader = newReader
            synchronized(frameLock) {
                try { latestImage?.close() } catch (_: Exception) {}
                latestImage = null
            }
            try { old?.close() } catch (_: Exception) {}
            vdWidth = width
            vdHeight = height
            return
        }

        // First capture of this session — the one and only createVirtualDisplay call.
        ensureImageThread()
        val reader = newReader(width, height)
        val display = try {
            projection.createVirtualDisplay(
                "ScamShieldCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, imageHandler
            )
        } catch (e: SecurityException) {
            try { reader.close() } catch (_: Exception) {}
            // The FGS lost the mediaProjection type, or the session is no longer valid.
            throw NeedsConsentException("Screen capture rejected by system: ${e.message}")
        } catch (e: Exception) {
            try { reader.close() } catch (_: Exception) {}
            throw RuntimeException("Virtual display failed: ${e.message}")
        }
        imageReader = reader
        virtualDisplay = display
        vdWidth = width
        vdHeight = height
    }

    /**
     * Grab the current screen as a bitmap. Reuses the persistent mirror — no new consent.
     *
     * The current frame is discarded first and we wait for the NEXT one, so the shot reflects the
     * screen as it is right now (e.g. after the caller hid its own overlay) rather than a stale
     * frame that still shows the bubble.
     */
    suspend fun capture_once(metrics: DisplayMetrics): Bitmap {
        ensureDisplay(metrics)

        synchronized(frameLock) {
            try { latestImage?.close() } catch (_: Exception) {}
            latestImage = null
        }

        var waited = 0
        val step = 50
        while (waited < 5000) {
            val ready = synchronized(frameLock) { latestImage != null }
            if (ready) break
            delay(step.toLong())
            waited += step
        }

        synchronized(frameLock) {
            val image = latestImage ?: throw RuntimeException(
                "Screenshot timed out — another app may be sharing the full screen. Stop that share and try again, or use Manual paste in app."
            )
            // Copy pixels out but DO NOT close the Image — the listener owns its lifecycle and keeps
            // it as the last-known frame for a static screen. Held under lock so the listener can't
            // swap it mid-read.
            return imageToBitmap(image, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        buffer.rewind()
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride
        val safeWidth = if (bitmapWidth < width) width else bitmapWidth
        val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } catch (e: Exception) {
            bitmap
        }
        if (cropped != bitmap) {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
        return cropped
    }
}
