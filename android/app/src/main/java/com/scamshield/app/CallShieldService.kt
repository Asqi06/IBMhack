package com.scamshield.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.*

/**
 * v1.2 Live Call Shield Service — detects call state + streams STT to Granite.
 * Foreground service with microphone|phoneCall type (Android 14+).
 * Disclosure: only TRANSCRIPT text is sent to backend; audio never leaves device.
 * Remote voice captured only when call is on speaker (platform limitation).
 */
class CallShieldService : Service() {

    companion object {
        const val ACTION_START = "START_CALL_SHIELD"
        const val ACTION_STOP = "STOP_CALL_SHIELD"
        private const val NOTIF_ID = 3001
        private const val CHANNEL_ID = "scamshield_call"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var telephonyManager: TelephonyManager? = null
    private var transcription: CallTranscriptionHelper? = null
    private var isInCall = false
    private var chunkId = 0
    private var callerId: String? = null
    private var backendUrl: String = "https://scanshield-ii9n.onrender.com"
    private val callBuffer = StringBuilder()
    private var lastAnalyzeJob: Job? = null

    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    callerId = phoneNumber
                    if (!isInCall) {
                        isInCall = true
                        onCallStarted(phoneNumber)
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (isInCall) {
                        isInCall = false
                        onCallEnded()
                    }
                    callerId = null
                }
                TelephonyManager.CALL_STATE_RINGING -> {
                    callerId = phoneNumber
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        backendUrl = getPrefs().getString("backendUrl", backendUrl) ?: backendUrl
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("backendUrl")?.let { backendUrl = it; getPrefs().edit { putString("backendUrl", it) } }
        when (intent?.action) {
            ACTION_STOP -> {
                stopShield()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startShield()
        }
        return START_STICKY
    }

    private fun startShield() {
        val notif = buildNotification("Call Shield active — listening for scam keywords")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallShield", "startForeground failed", e)
        }
        // Register phone state listener
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Call Shield: READ_PHONE_STATE permission needed", Toast.LENGTH_LONG).show()
        }
        // Check current state
        val tm = telephonyManager
        if (tm != null && tm.callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            isInCall = true
            onCallStarted(callerId)
        }
        Toast.makeText(this, "Call Shield ON — put call on speaker for best detection", Toast.LENGTH_LONG).show()
    }

    private fun stopShield() {
        try { @Suppress("DEPRECATION") telephonyManager?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        stopTranscription()
        scope.cancel()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { nm.cancel(NOTIF_ID) } catch (_: Exception) {}
        Toast.makeText(this, "Call Shield OFF", Toast.LENGTH_SHORT).show()
    }

    private fun onCallStarted(number: String?) {
        chunkId = 0
        callBuffer.clear()
        updateNotification("Call active — analyzing for OTP/kyc scams… (speaker ON recommended)")
        startTranscription()
    }

    private fun onCallEnded() {
        stopTranscription()
        updateNotification("Call Shield active — waiting for next call")
        // Final summary analyze if buffer non-empty
        val full = callBuffer.toString().trim()
        if (full.length > 10) {
            scope.launch {
                try {
                    val res = ApiClient.analyze(full, backendUrl)
                    if (res.overallRisk.equals("high", true) || res.overallRisk.equals("medium", true)) {
                        showDanger(full, res)
                    }
                } catch (_: Exception) {}
            }
        }
        callBuffer.clear()
    }

    private fun startTranscription() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed for Call Shield", Toast.LENGTH_LONG).show()
            return
        }
        transcription?.stop()
        transcription = CallTranscriptionHelper(this) { chunk ->
            onTranscriptChunk(chunk)
        }
        transcription?.start()
    }

    private fun stopTranscription() {
        transcription?.stop()
        transcription = null
    }

    private fun onTranscriptChunk(chunk: String) {
        chunkId++
        callBuffer.append(" ").append(chunk)
        // Keep buffer bounded (last 800 chars for sliding window)
        if (callBuffer.length > 2000) {
            val trimmed = callBuffer.substring(callBuffer.length - 1200)
            callBuffer.clear().append(trimmed)
        }
        // Debounce analyze: 1.5s after chunk
        lastAnalyzeJob?.cancel()
        lastAnalyzeJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            analyzeChunk(chunk)
        }
    }

    private suspend fun analyzeChunk(chunk: String) {
        try {
            // Use analyze-call so callerId ledger is included
            val res = ApiClient.analyzeCall(chunk, backendUrl, callerId, chunkId)
            if (res.overallRisk.equals("high", true)) {
                showDanger(chunk, res)
                // Haptic + alert
            } else if (res.overallRisk.equals("medium", true)) {
                updateNotification("⚠️ Suspicious phrase detected: ${res.details.firstOrNull()?.reason?.take(60)}")
            }
            // Also log dangerous to Room
            if (res.overallRisk.equals("high", true) || res.overallRisk.equals("medium", true)) {
                logToRoom(chunk, res)
            }
        } catch (e: Exception) {
            android.util.Log.w("CallShield", "analyze failed", e)
        }
    }

    private fun showDanger(chunk: String, res: AnalyzeResult) {
        val primary = res.details.firstOrNull { it.source == "text" }
        val reason = primary?.reason ?: res.details.firstOrNull()?.reason ?: "Possible scam detected"
        val title = "🔴 SCAM ALERT — ${res.overallRisk.uppercase()} (${primary?.category ?: "OTP scam"})"
        // High-importance notification
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("scamshield_call_alert", "Call Shield Alerts", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
        val open = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = android.app.PendingIntent.getActivity(this, 99, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, "scamshield_call_alert")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(0xFFFF3B30.toInt())
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\n\nHeard: \"${chunk.take(120)}\"\n\nAdvised: Do NOT share OTP/PIN. Hang up and verify via official app."))
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        nm.notify(4000 + chunkId, notif)
        updateNotification("🔴 SCAM DETECTED — ${primary?.category}: $reason")
        // Toast for immediate feedback
        Toast.makeText(this, "⚠️ SCAM DETECTED: $reason", Toast.LENGTH_LONG).show()
    }

    private fun logToRoom(chunk: String, res: AnalyzeResult) {
        val primary = res.details.firstOrNull { it.source == "text" }
        val log = ScanLog(
            overallRisk = res.overallRisk,
            category = primary?.category ?: "OTP scam",
            reason = primary?.reason ?: res.details.firstOrNull()?.reason ?: "",
            snippet = chunk.take(120),
            fullText = callBuffer.toString().take(500),
            source = "call",
            target = callerId
        )
        scope.launch {
            try { AppDatabase.get(this@CallShieldService).scanLogDao().insert(log) } catch (_: Exception) {}
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Call Shield", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Live call scam detection"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ScamShield Call Shield")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_secure)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .build()

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun getPrefs(): SharedPreferences = getSharedPreferences("scamshield", MODE_PRIVATE)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopShield()
        super.onDestroy()
    }
}
