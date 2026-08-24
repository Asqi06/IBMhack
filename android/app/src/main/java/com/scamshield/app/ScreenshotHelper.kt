package com.scamshield.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thrown when there is no usable capture session and the user must grant consent again.
 * Distinct type so callers don't have to pattern-match on message text.
 */
class NeedsConsentException(message: String) : RuntimeException(message)

/**
 * One-shot screenshot via MediaProjection.
 * Robust against concurrent screen sharing (Meet/Zoom) — catches SecurityException
 * and does NOT stop the MediaProjection after each capture (reuse for next tap).
 * Only called on explicit user tap.
 */
class ScreenshotHelper(private val context: Context) {

    fun getProjectionIntent(): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun isProjectionGranted(resultCode: Int) = resultCode == Activity.RESULT_OK

    /** Live projection for this capture session. Null until acquireProjection(), and after onStop. */
    private var cachedProjection: MediaProjection? = null

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
     * then throws SecurityException — which is what produced the "screen capture permission
     * ended" toast on the very first bubble tap.
     *
     * The token is single-use, so this is called exactly once per consent grant.
     */
    fun acquireProjection(resultCode: Int, data: Intent) {
        invalidate()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection() returned null — consent was not valid")
        // Android 14+ requires a callback registered before createVirtualDisplay().
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cachedProjection = null
                onProjectionStopped?.invoke()
            }
        }, Handler(Looper.getMainLooper()))
        cachedProjection = p
    }

    /** Called when a fresh consent token arrives, so the next capture builds a new projection. */
    fun invalidate() {
        try { cachedProjection?.stop() } catch(_: Exception) {}
        cachedProjection = null
    }

    fun release() {
        onProjectionStopped = null
        invalidate()
    }

    suspend fun capture_once(
        metrics: DisplayMetrics
    ): Bitmap = suspendCancellableCoroutine { cont ->
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        if (width <= 0 || height <= 0) {
            cont.resumeWithException(IllegalArgumentException("Invalid display metrics $width x $height — try again"))
            return@suspendCancellableCoroutine
        }

        val projection = cachedProjection
        if (projection == null) {
            cont.resumeWithException(NeedsConsentException("Screen capture session has ended"))
            return@suspendCancellableCoroutine
        }

        var reader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var timedOut = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (cont.isActive) {
                timedOut = true
                try { virtualDisplay?.release() } catch(_: Exception) {}
                try { reader?.close() } catch(_: Exception) {}
                // Don't stop projection here — keep for next tap, just fail this capture
                cont.resumeWithException(RuntimeException("Screenshot timed out — another app may be sharing full screen. Stop that share and try again, or use Manual paste in app."))
            }
        }
        handler.postDelayed(timeoutRunnable, 7000)

        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            cont.resumeWithException(RuntimeException("Failed to create image buffer: ${e.message}"))
            return@suspendCancellableCoroutine
        }

        val localReader = reader
        localReader.setOnImageAvailableListener({ r ->
            if (timedOut) return@setOnImageAvailableListener
            handler.removeCallbacks(timeoutRunnable)
            try {
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmapWidth = width + rowPadding / pixelStride
                // Guard against invalid bitmapWidth
                val safeWidth = if (bitmapWidth < width) width else bitmapWidth
                val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                val cropped = try {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } catch (e: Exception) {
                    bitmap
                }
                if (cropped != bitmap) {
                    try { bitmap.recycle() } catch(_: Exception) {}
                }
                try { virtualDisplay?.release() } catch(_: Exception) {}
                try { localReader.close() } catch(_: Exception) {}
                // Keep projection alive for next capture
                if (cont.isActive) cont.resume(cropped)
            } catch (e: Exception) {
                try { virtualDisplay?.release() } catch(_: Exception) {}
                try { localReader.close() } catch(_: Exception) {}
                if (cont.isActive) cont.resumeWithException(RuntimeException("Capture failed: ${e.message}"))
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ScamShieldScreenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                localReader.surface, null, null
            )
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeoutRunnable)
            try { localReader.close() } catch(_: Exception) {}
            // Most common cause on Android 14+: the service is not (or no longer) holding
            // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. Keep the platform text — it names the
            // actual reason far better than any guess we could substitute.
            cont.resumeWithException(NeedsConsentException("Screen capture rejected by system: ${e.message}"))
            return@suspendCancellableCoroutine
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            try { localReader.close() } catch(_: Exception) {}
            cont.resumeWithException(RuntimeException("Virtual display failed: ${e.message}"))
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            try { virtualDisplay?.release() } catch(_: Exception) {}
            try { localReader.close() } catch(_: Exception) {}
        }
    }
}
