package com.scamshield.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.*

/**
 * Chat-head floating widget via WindowManager (SYSTEM_ALERT_WINDOW).
 * Single bubble + results card. Draggable, tap to scan.
 *
 * Scan flow (explicit tap only, no background):
 *  1. Requires MediaProjection grant (obtained in MainActivity, passed via intent)
 *  2. ScreenshotHelper.capture_once() -> bitmap
 *  3. OcrHelper.extractText(bitmap) -> String (on-device, image never leaves device)
 *  4. ApiClient.analyze(text, backendUrl) -> risk + reason
 *  5. Show color-coded card (red/yellow/green) + Report button
 */
class FloatingWidgetService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_BACKEND_URL = "backendUrl"
        const val ACTION_SCAN = "com.scamshield.app.ACTION_SCAN"
        private const val CHANNEL_ID = "scamshield_fg"
        private const val NOTIF_ID = 1001
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isScanning = false
    private var backendUrl: String = "http://10.0.2.2:8000"
    private var mediaResultCode: Int = Activity.RESULT_CANCELED
    private var mediaResultData: Intent? = null
    private var pulseAnimator: ValueAnimator? = null
    private var bobAnimator: ObjectAnimator? = null
    private var screenshotHelper: ScreenshotHelper? = null
    /** True once we hold the mediaProjection FGS type — required before getMediaProjection(). */
    private var hasProjectionFgsType = false

    override fun onCreate() {
        super.onCreate()
        // NOTE: startForeground() deliberately does NOT happen here. It is the first thing
        // onStartCommand() does, because only there do we know whether a screen-capture
        // consent token exists — and on Android 14+ claiming the mediaProjection FGS type
        // without consent throws SecurityException.
        createChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenshotHelper = ScreenshotHelper(this)
    }

    /**
     * Promote to foreground with a type we are actually allowed to claim.
     *
     * Android 14+ throws SecurityException from startForeground() when the runtime
     * prerequisites of the declared type are unmet — for mediaProjection that means
     * "user has granted createScreenCaptureIntent()". Claiming mediaProjection before
     * consent was the original crash: the exception was swallowed, the service never
     * reached foreground state, and ~5s later the platform killed the process with
     * ForegroundServiceDidNotStartInTimeException.
     *
     * So: claim dataSync until consent exists, upgrade to mediaProjection once it does.
     * Sets [hasProjectionFgsType] to reflect what we actually got — a dataSync fallback must
     * NOT be mistaken for success by callers that are about to touch MediaProjection APIs.
     * Returns false only if every attempt failed (caller must stopSelf to avoid the watchdog).
     */
    private fun promoteToForeground(text: String, withProjection: Boolean): Boolean {
        val notif = buildNotification(text)
        val attempts = if (withProjection) {
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                0
            )
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 0)
        }
        for (type in attempts) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
                hasProjectionFgsType = type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (withProjection && !hasProjectionFgsType) {
                    // Foreground, so no watchdog kill — but capture cannot work under this type.
                    android.util.Log.w("ScamShield", "Fell back to FGS type $type; screen capture will not work")
                }
                return true
            } catch (e: Exception) {
                android.util.Log.w("ScamShield", "startForeground(type=$type) failed: ${e.message}")
            }
        }
        android.util.Log.e("ScamShield", "Could not enter foreground with any service type")
        return false
    }

    private fun hasProjectionToken() = mediaResultCode == Activity.RESULT_OK && mediaResultData != null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read any new consent token BEFORE promoting, so we can claim the right FGS type.
        // Only overwrite if the intent actually carries it (ACTION_SCAN / bring-to-front must not clear it).
        var freshToken = false
        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            mediaResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            mediaResultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            freshToken = hasProjectionToken()
        }
        intent?.getStringExtra(EXTRA_BACKEND_URL)?.let { u -> backendUrl = u }
        if (backendUrl == "http://10.0.2.2:8000") {
            getPrefs().getString("backendUrl", null)?.let { backendUrl = it }
        }
        getPrefs().edit().putString("backendUrl", backendUrl).apply()

        // Must happen within ~5s of startForegroundService() or the platform kills us.
        if (!promoteToForeground("ScamShield bubble active — tap ◈ to scan", hasProjectionToken())) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Consume the consent token NOW, while it is seconds old and we hold the mediaProjection
        // FGS type. Deferring this to the first bubble tap let the token go stale, so the very
        // first tap failed with SecurityException ("screen capture permission ended").
        if (freshToken) acquireProjectionNow()

        showWidgetIfNeeded()

        // Notification action "Scan now" — works even when the bubble is hidden by a full-screen share
        if (intent?.action == ACTION_SCAN) {
            onBubbleTap()
        }

        // NOT sticky: a system restart hands us a null intent with no consent token, which
        // would loop through the failure path above.
        return START_NOT_STICKY
    }

    /**
     * Turn the one-shot consent token into a live MediaProjection, held for the whole session.
     * Each capture then only needs a fresh VirtualDisplay, not fresh consent.
     */
    private fun acquireProjectionNow() {
        val data = mediaResultData ?: return
        if (!hasProjectionFgsType) {
            android.util.Log.e("ScamShield", "Refusing to acquire projection without mediaProjection FGS type")
            Toast.makeText(this, "Screen capture unavailable: service could not claim the mediaProjection type", Toast.LENGTH_LONG).show()
            return
        }
        val helper = screenshotHelper ?: ScreenshotHelper(this).also { screenshotHelper = it }
        helper.onProjectionStopped = {
            // Platform ended the session (user revoked, another app took over, or it expired).
            mediaResultCode = Activity.RESULT_CANCELED
            mediaResultData = null
            updateNotification("Screen capture ended — tap the bubble to grant it again")
        }
        try {
            helper.acquireProjection(mediaResultCode, data)
            updateNotification("ScamShield ready — tap ◈ to scan")
        } catch (e: Exception) {
            android.util.Log.e("ScamShield", "acquireProjection failed", e)
            // Token is spent either way; force a fresh grant on the next tap.
            mediaResultCode = Activity.RESULT_CANCELED
            mediaResultData = null
            Toast.makeText(this, "Screen capture setup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getPrefs(): SharedPreferences =
        getSharedPreferences("scamshield", Context.MODE_PRIVATE)

    private fun showWidgetIfNeeded() {
        // Overlay permission can be revoked at any time; addView would throw BadTokenException.
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable \"Display over other apps\" for ScamShield, then start the bubble again", Toast.LENGTH_LONG).show()
            updateNotification("Overlay permission missing — open ScamShield to enable it")
            return
        }

        val existing = floatView
        if (existing != null) {
            // Already inflated. Make sure it is attached AND visible — a previous full-screen
            // share or a failed updateViewLayout can leave it detached or hidden.
            try {
                existing.visibility = View.VISIBLE
                if (existing.isAttachedToWindow) {
                    windowManager?.updateViewLayout(existing, layoutParams)
                } else {
                    windowManager?.addView(existing, layoutParams)
                }
                existing.bringToFront()
                startPulseAnimation()
                updateNotification("ScamShield bubble active — tap ◈ to scan")
                return
            } catch (e: Exception) {
                // Stale view/token — drop it and rebuild from scratch below.
                android.util.Log.w("ScamShield", "Re-attach failed, rebuilding bubble: ${e.message}")
                try { windowManager?.removeViewImmediate(existing) } catch (_: Exception) {}
                floatView = null
            }
        }

        // Inflate + attach. This whole block used to sit outside any try/catch, so a single
        // inflation or addView failure propagated out of onStartCommand and killed the app.
        try {
            // A Service context does NOT inherit <application android:theme> — ContextImpl
            // resolves it to the platform DeviceDefault theme. MaterialCardView/MaterialButton
            // run a theme-enforcement check and throw
            // "requires your app theme to be Theme.MaterialComponents (or a descendant)",
            // surfacing as InflateException on the MaterialCardView tag. So inflate through an
            // explicitly themed context.
            val themedContext = ContextThemeWrapper(this, R.style.Theme_ScamShield)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.view_floating_widget, null, false)
            val bubble = view.findViewById<View>(R.id.bubble)

            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 300
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            attachDragHandler(bubble, params)

            windowManager?.addView(view, params)
            floatView = view
            layoutParams = params
            startPulseAnimation()
            updateNotification("ScamShield bubble active — tap ◈ to scan")
        } catch (e: Exception) {
            floatView = null
            layoutParams = null
            android.util.Log.e("ScamShield", "Could not show bubble", e)
            Toast.makeText(this, "Bubble failed to show: ${e.message}", Toast.LENGTH_LONG).show()
            updateNotification("Bubble failed to show — use \"Scan now\" or the app's manual paste")
        }
    }

    private fun attachDragHandler(bubble: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (dx * dx + dy * dy > 25) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try { windowManager?.updateViewLayout(floatView, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    /** Fallback verdict delivery when there is no overlay to draw the result card into. */
    private fun notifyResult(title: String, body: String) {
        Toast.makeText(this, "$title — $body", Toast.LENGTH_LONG).show()
        updateNotification("$title — $body")
    }

    private fun startPulseAnimation() {
        val pulse = floatView?.findViewById<View>(R.id.pulseRing) ?: return
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                val scale = 1f + v * 0.45f
                pulse.scaleX = scale
                pulse.scaleY = scale
                pulse.alpha = 0.55f * (1f - v)
            }
            start()
        }
        // Subtle float for bubble — cancel any previous one so repeated calls don't stack animators
        floatView?.findViewById<View>(R.id.bubble)?.let { b ->
            bobAnimator?.cancel()
            bobAnimator = ObjectAnimator.ofFloat(b, "translationY", 0f, -6f, 0f).apply {
                duration = 2200
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    /**
     * Open MainActivity to ask for a new screen-capture grant. Only an Activity can start the
     * consent dialog, so this is the sole recovery path once a projection has ended.
     */
    private fun requestFreshConsent(message: String) {
        mediaResultCode = Activity.RESULT_CANCELED
        mediaResultData = null
        screenshotHelper?.invalidate()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateNotification(message)
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("requestProjection", true)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.e("ScamShield", "Could not open MainActivity", e)
            Toast.makeText(this, "Open ScamShield manually to grant screen capture.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onBubbleTap() {
        if (isScanning) {
            Toast.makeText(this, "Already scanning…", Toast.LENGTH_SHORT).show()
            return
        }
        // The live projection is what matters, not the spent token. If we don't have one, the
        // only recovery is a fresh consent grant, which only an Activity can request.
        if (screenshotHelper?.hasLiveProjection() != true) {
            requestFreshConsent("Grant screen capture to scan (opening ScamShield…)")
            return
        }

        isScanning = true
        updateBubbleScanning(true)
        updateNotification("ScamShield scanning…")
        scope.launch {
            // Hide our own overlay so the bubble and result card are not part of the captured
            // frame (they would otherwise be OCR'd along with the real screen content).
            val overlay = floatView
            overlay?.visibility = View.INVISIBLE
            try {
                val metrics = currentDisplayMetrics()
                val helper = screenshotHelper ?: ScreenshotHelper(this@FloatingWidgetService).also { screenshotHelper = it }
                val bitmap = helper.capture_once(metrics)
                // On-device OCR — bitmap never leaves device
                val text = OcrHelper.extractText(bitmap)
                bitmap.recycle()
                overlay?.visibility = View.VISIBLE
                if (text.isBlank()) {
                    showResult("low", "No text detected on screen — try a screen with text, or use the app's Manual paste", emptyList())
                    return@launch
                }
                // Send TEXT ONLY to backend
                val result = ApiClient.analyze(text, backendUrl)
                showResult(result.overallRisk, result.details)
            } catch (e: NeedsConsentException) {
                // Session is gone — re-grant is the only fix. Keep the platform's own wording
                // visible so the real reason isn't hidden behind a guess.
                overlay?.visibility = View.VISIBLE
                android.util.Log.w("ScamShield", "Capture needs fresh consent", e)
                requestFreshConsent("Screen capture session ended — re-granting. (${e.message})")
            } catch (e: Exception) {
                overlay?.visibility = View.VISIBLE
                android.util.Log.e("ScamShield", "Scan failed", e)
                val msg = e.message ?: "Scan failed"
                val friendly = when {
                    msg.contains("timed out", ignoreCase = true) && msg.contains("Screenshot", ignoreCase = true) ->
                        "Screen capture timed out. If another app is sharing the full screen (Meet/Zoom), stop that share, then tap the bubble again. Or paste text in ScamShield's Manual test."
                    // Match only the platform's own concurrency wording, not our own prose —
                    // a loose contains("stop") previously mislabelled unrelated failures.
                    msg.contains("Unable to create virtual display", ignoreCase = true) ->
                        "Another app is using screen capture. Stop the full-screen share, then tap the bubble again."
                    msg.contains("CLEARTEXT", ignoreCase = true) ->
                        "Backend blocked as insecure HTTP. Reinstall this build — it ships the cleartext policy fix."
                    e is java.net.UnknownHostException ->
                        "Cannot resolve backend host in $backendUrl. Check the URL in the ScamShield app."
                    e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
                        "Cannot reach backend at $backendUrl. On a real phone use your PC's LAN IP (10.0.2.2 is emulator-only) and start the backend with --host 0.0.0.0 on the same Wi-Fi."
                    else -> "${e.javaClass.simpleName}: $msg"
                }
                showError(friendly)
                updateNotification("ScamShield: $friendly")
            } finally {
                isScanning = false
                updateBubbleScanning(false)
                overlay?.visibility = View.VISIBLE
                updateNotification("ScamShield bubble active — tap ◈ to scan. Not visible? Pull down this notification and tap Scan now.")
            }
        }
    }

    /**
     * Real display metrics from a Service context.
     * WindowManager.getDefaultDisplay() is deprecated since API 30 and unreliable off an
     * Activity, so go through DisplayManager instead.
     */
    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } catch (e: Exception) {
            android.util.Log.w("ScamShield", "DisplayManager metrics failed, falling back", e)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val r = resources.displayMetrics
            metrics.widthPixels = r.widthPixels
            metrics.heightPixels = r.heightPixels
            metrics.densityDpi = r.densityDpi
        }
        return metrics
    }

    private fun updateBubbleScanning(scanning: Boolean) {
        val bubble = floatView?.findViewById<View>(R.id.bubble) ?: return
        val pulse = floatView?.findViewById<View>(R.id.pulseRing)
        if (scanning) {
            bubble.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.85f).setDuration(220).start()
            pulse?.animate()?.alpha(0.9f)?.setDuration(220)?.start()
            // Faster pulse while scanning
            pulseAnimator?.duration = 700
            // Subtle rotation
            ObjectAnimator.ofFloat(bubble, "rotation", 0f, 8f, -8f, 0f).apply {
                duration = 900
                repeatCount = 1
                start()
            }
        } else {
            bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start()
            pulse?.animate()?.alpha(0.55f)?.setDuration(300)?.start()
            pulseAnimator?.duration = 1800
        }
    }

    private fun showResult(overallRisk: String, reason: String, details: List<AnalyzeDetail>) {
        if (floatView == null) {
            // No overlay — deliver the reason directly, since the 2-arg path can only see `details`
            // (which is empty for cases like "no text detected").
            notifyResult("Risk: ${overallRisk.uppercase()}", reason)
            return
        }
        showResult(overallRisk, details)
        // Update reason view separately
        floatView?.findViewById<android.widget.TextView>(R.id.floatReason)?.text = reason
    }

    private fun showResult(overallRisk: String, details: List<AnalyzeDetail>) {
        // No overlay (inflate/attach failed, or overlay permission revoked)? Scanning still worked,
        // so deliver the verdict through the notification rather than dropping it silently.
        val card = floatView?.findViewById<View>(R.id.floatResultCard) ?: run {
            val summary = details.firstOrNull { it.source == "text" }?.reason
                ?: details.firstOrNull()?.reason ?: ""
            notifyResult("Risk: ${overallRisk.uppercase()}", summary)
            return
        }
        val overallView = floatView!!.findViewById<android.widget.TextView>(R.id.floatOverallRisk)
        val reasonView = floatView!!.findViewById<android.widget.TextView>(R.id.floatReason)
        val detailsView = floatView!!.findViewById<android.widget.TextView>(R.id.floatDetails)
        val reportBtn = floatView!!.findViewById<View>(R.id.floatReportBtn)

        val primary = details.firstOrNull { it.source == "text" }
        val risk = overallRisk.lowercase()
        val color = when (risk) {
            "high" -> Color.parseColor("#E53935")
            "medium" -> Color.parseColor("#FB8C00")
            else -> Color.parseColor("#43A047")
        }
        overallView.text = "Risk: ${overallRisk.uppercase()}"
        overallView.setTextColor(color)
        // Card stroke matches risk
        (card as? com.google.android.material.card.MaterialCardView)?.strokeColor = color

        reasonView.text = primary?.reason ?: (details.firstOrNull()?.reason ?: "")
        // Build details lines
        val lines = details.joinToString("\n") { d ->
            when (d.source) {
                "text" -> "• text: ${d.risk} — ${d.category}"
                "url" -> "• url ${d.url ?: ""}: ${d.risk} — ${d.reason}"
                "number" -> "• number ${d.number ?: ""}: reported=${d.reported} count=${d.reportCount} (${d.riskLevel})"
                else -> "• ${d.source}: ${d.reason}"
            }
        }
        detailsView.text = lines

        // Report button only for high/medium
        if (risk == "high" || risk == "medium") {
            reportBtn.visibility = View.VISIBLE
            reportBtn.setOnClickListener {
                scope.launch {
                    try {
                        // Report the most suspicious number/url, else first detail's text category
                        val target = details.firstOrNull { it.source == "number" }?.number
                            ?: details.firstOrNull { it.source == "url" }?.url
                            ?: "screen-text" // fallback category signal
                        val category = primary?.category ?: "phishing link"
                        val key = if (target == "screen-text") {
                            // Try to extract a phone/url from details for ledger, else use text snippet hash key
                            details.firstNotNullOfOrNull { it.number ?: it.url } ?: "manual-report"
                        } else target
                        ApiClient.report(key, category, backendUrl)
                        Toast.makeText(this@FloatingWidgetService, "Reported to ledger ✓", Toast.LENGTH_SHORT).show()
                        reportBtn.visibility = View.GONE
                    } catch (e: Exception) {
                        Toast.makeText(this@FloatingWidgetService, "Report failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            reportBtn.visibility = View.GONE
        }

        card.alpha = 0f
        card.translationY = 22f
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(AccelerateDecelerateInterpolator()).start()
        // Auto-hide after 18s with fade
        card.removeCallbacks(hideRunnable)
        card.postDelayed(hideRunnable, 18000)
    }

    private val hideRunnable = Runnable {
        val c = floatView?.findViewById<View>(R.id.floatResultCard) ?: return@Runnable
        c.animate().alpha(0f).translationY(12f).setDuration(220).withEndAction { c.visibility = View.GONE; c.alpha = 1f; c.translationY = 0f }.start()
    }

    private fun showError(msg: String) {
        val card = floatView?.findViewById<View>(R.id.floatResultCard) ?: run {
            notifyResult("ScamShield error", msg)
            return
        }
        val overallView = floatView!!.findViewById<android.widget.TextView>(R.id.floatOverallRisk)
        val reasonView = floatView!!.findViewById<android.widget.TextView>(R.id.floatReason)
        val detailsView = floatView!!.findViewById<android.widget.TextView>(R.id.floatDetails)
        overallView.text = "Error"
        overallView.setTextColor(Color.parseColor("#E53935"))
        reasonView.text = msg
        detailsView.text = ""
        floatView!!.findViewById<View>(R.id.floatReportBtn).visibility = View.GONE
        card.visibility = View.VISIBLE
        card.postDelayed(hideRunnable, 8000)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "ScamShield", NotificationManager.IMPORTANCE_LOW)
            ch.description = "ScamShield • Tap to scan"
            ch.enableLights(false)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        // Tap notification → open app (so user can re-grant or use Manual paste if bubble hidden)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Action: Scan now (same as bubble tap) — works even when bubble hidden by full-screen share
        val scanIntent = Intent(this, FloatingWidgetService::class.java).apply {
            action = ACTION_SCAN
        }
        val pendingScan = android.app.PendingIntent.getService(
            this, 1, scanIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScamShield")
            .setContentText(text)
            .setSubText("On-device • Tap to scan")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setColor(0xFF007AFF.toInt())
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_camera, "Scan", pendingScan)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    override fun onDestroy() {
        try { pulseAnimator?.cancel() } catch(_: Exception) {}
        try { bobAnimator?.cancel() } catch(_: Exception) {}
        try { screenshotHelper?.release() } catch(_: Exception) {}
        scope.cancel()
        floatView?.let { try { windowManager?.removeViewImmediate(it) } catch(_: Exception) {} }
        floatView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
