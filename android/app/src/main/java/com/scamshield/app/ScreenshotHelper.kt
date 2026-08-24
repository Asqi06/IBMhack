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
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thrown when there is no usable capture session and the user must grant consent again.
 * Distinct type so callers don't have to pattern-match on message text.
 */
class NeedsConsentException(message: String) : RuntimeException(message)

/**
 * Screenshot via MediaProjection — one consent, and pixels flow ONLY during a scan.
 *
 * Two platform facts drive this design:
 *
 * 1. Android 14 (targetSdk 34) throws SecurityException if createVirtualDisplay() is called more
 *    than once on the same MediaProjection instance — "each MediaProjection instance must be used
 *    only once". Creating a display per tap therefore made every scan after the first demand a new
 *    screen-capture grant. So the VirtualDisplay is created exactly ONCE per consent and kept.
 *
 * 2. A VirtualDisplay renders nothing while its surface is null, and the docs sanction
 *    VirtualDisplay#resize / #setSurface as the way to update a display without a second
 *    createVirtualDisplay. So between scans the surface is detached: the session stays valid (no
 *    re-prompt) but not a single frame is captured while idle.
 *
 * Attaching a fresh surface also forces the compositor to redraw into it, which is what makes a
 * scan work on a completely static screen — waiting for the *next* frame of a continuous mirror
 * never returned on an unchanging screen, which is why scans appeared to hang with no result.
 */
class ScreenshotHelper(private val context: Context) {

    fun getProjectionIntent(): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun isProjectionGranted(resultCode: Int) = resultCode == Activity.RESULT_OK

    /** Live projection for this capture session. Null until acquireProjection(), and after onStop. */
    private var cachedProjection: MediaProjection? = null

    /** The single VirtualDisplay for this session. Surface attached only while capturing. */
    private var virtualDisplay: VirtualDisplay? = null
    private var vdWidth = 0
    private var vdHeight = 0

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    /** True only for the few hundred ms of an actual capture — drives the UI's honesty about state. */
    @Volatile
    var isCapturing: Boolean = false
        private set

    /** Invoked when the platform ends the projection, so the service can ask for fresh consent. */
    var onProjectionStopped: (() -> Unit)? = null

    fun hasLiveProjection() = cachedProjection != null

    /**
     * Consume the screen-capture consent token and hold the resulting MediaProjection.
     *
     * Must be called as soon as the token arrives, NOT lazily on first capture: the documented
     * Android 14 order is createScreenCaptureIntent() -> user grants ->
     * startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) -> getMediaProjection(). A token
     * left unconsumed goes stale and getMediaProjection() then throws SecurityException. The token
     * is single-use, so this runs exactly once per consent grant.
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

    /** Ends the session entirely. The next scan needs a fresh consent grant. */
    fun invalidate() {
        releaseDisplay()
        try { cachedProjection?.stop() } catch (_: Exception) {}
        cachedProjection = null
    }

    fun release() {
        onProjectionStopped = null
        invalidate()
    }

    private fun releaseDisplay() {
        isCapturing = false
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        imageHandler = null
        vdWidth = 0
        vdHeight = 0
    }

    private fun ensureImageThread(): Handler {
        val existing = imageHandler
        if (existing != null) return existing
        val t = HandlerThread("ScamShieldCapture").also { it.start() }
        imageThread = t
        return Handler(t.looper).also { imageHandler = it }
    }

    /**
     * Attach [surface] to the session's single VirtualDisplay, creating that display the first time.
     * Never calls createVirtualDisplay twice — see the class doc.
     */
    private fun attachDisplay(metrics: DisplayMetrics, surface: Surface): VirtualDisplay {
        val projection = cachedProjection
            ?: throw NeedsConsentException("Screen capture session has ended")
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val existing = virtualDisplay
        if (existing != null) {
            if (width != vdWidth || height != vdHeight) {
                // Rotation / size change: resize rather than create a second display.
                try {
                    existing.resize(width, height, density)
                    vdWidth = width
                    vdHeight = height
                } catch (e: Exception) {
                    throw RuntimeException("Failed to resize capture display: ${e.message}")
                }
            }
            try {
                existing.setSurface(surface)
            } catch (e: Exception) {
                throw RuntimeException("Failed to attach capture surface: ${e.message}")
            }
            return existing
        }

        val handler = ensureImageThread()
        val created = try {
            projection.createVirtualDisplay(
                "ScamShieldCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler
            )
        } catch (e: SecurityException) {
            // The FGS lost the mediaProjection type, or the session is no longer valid.
            throw NeedsConsentException("Screen capture rejected by system: ${e.message}")
        } catch (e: Exception) {
            throw RuntimeException("Virtual display failed: ${e.message}")
        } ?: throw RuntimeException("createVirtualDisplay returned null")

        virtualDisplay = created
        vdWidth = width
        vdHeight = height
        return created
    }

    /**
     * Grab the screen as it is right now. Reuses the existing consent, attaches a surface for the
     * duration of this call only, and detaches it before returning so nothing is captured while idle.
     */
    suspend fun capture_once(metrics: DisplayMetrics): Bitmap {
        if (cachedProjection == null) {
            throw NeedsConsentException("Screen capture session has ended")
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid display metrics $width x $height — try again")
        }

        val handler = ensureImageThread()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val frame = CompletableDeferred<Bitmap>()

        reader.setOnImageAvailableListener({ r ->
            if (frame.isCompleted) {
                // Drain trailing frames so the buffer queue never wedges.
                try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            var image: Image? = null
            try {
                image = r.acquireLatestImage()
                if (image != null) {
                    frame.complete(imageToBitmap(image, width, height))
                }
            } catch (e: Exception) {
                frame.completeExceptionally(RuntimeException("Capture failed: ${e.message}"))
            } finally {
                try { image?.close() } catch (_: Exception) {}
            }
        }, handler)

        val display = try {
            isCapturing = true
            attachDisplay(metrics, reader.surface)
        } catch (e: Exception) {
            isCapturing = false
            try { reader.close() } catch (_: Exception) {}
            throw e
        }

        try {
            // A fresh surface normally yields a frame within a couple of vsyncs.
            var bitmap = withTimeoutOrNull(2500) { frame.await() }
            if (bitmap == null) {
                // Re-attach once: some devices need the surface swap to trigger a redraw when the
                // screen is perfectly static.
                try {
                    display.setSurface(null)
                    display.setSurface(reader.surface)
                } catch (_: Exception) {}
                bitmap = withTimeoutOrNull(3500) { frame.await() }
            }
            return bitmap ?: throw RuntimeException(
                "Screen capture produced no frame. If another app is sharing the full screen (Meet/Zoom), stop that share and tap again — or use Manual paste in the app."
            )
        } finally {
            // Idle from here on: no surface means the display renders nothing at all.
            isCapturing = false
            try { display.setSurface(null) } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
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
