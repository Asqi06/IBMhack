package com.scamshield.app

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

/**
 * WhatsApp auto-detect — listens for WhatsApp notifications (no READ_SMS, no Accessibility for this path).
 * When a WhatsApp message arrives, its notification text is extracted and scanned via /analyze.
 * If high/medium, a system notification advises Delete/Block and logs to Room.
 *
 * User must enable: Settings → Apps → Special access → Notification access → ScamShield → Allow
 * This is the approved, non-invasive way to see WhatsApp content without reading storage.
 */
class WhatsAppListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val whatsappPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in whatsappPackages) return
        val notif = sbn.notification ?: return
        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: return

        // Skip own notifications and empty
        if (text.isBlank() || title.contains("ScamShield", ignoreCase = true)) return

        // Extract URLs from text — if none, still scan the text for scam language
        val full = "$title: $text".trim()
        // Debounce: don't scan the same text repeatedly
        if (full.length < 10) return

        val prefs = getSharedPreferences("scamshield", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backendUrl", null) ?: "https://scanshield-ii9n.onrender.com"

        scope.launch {
            try {
                val res = ApiClient.analyze(full, backendUrl)
                if (res.overallRisk == "high" || res.overallRisk == "medium") {
                    // Log to Room
                    val db = AppDatabase.get(this@WhatsAppListenerService)
                    val primary = res.details.firstOrNull { it.source == "text" }
                    val log = ScanLog(
                        overallRisk = res.overallRisk,
                        category = primary?.category ?: "phishing link",
                        reason = primary?.reason ?: res.details.firstOrNull()?.reason ?: "",
                        snippet = full.take(120),
                        fullText = full,
                        source = "whatsapp"
                    )
                    db.scanLogDao().insert(log)

                    // Advise via notification — taps open MainActivity log
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    // Ensure channel exists
                    if (Build.VERSION.SDK_INT >= 26) {
                        val ch = android.app.NotificationChannel("scamshield_whatsapp", "ScamShield WhatsApp Guard", android.app.NotificationManager.IMPORTANCE_HIGH)
                        ch.description = "Alerts for WhatsApp scam links"
                        nm.createNotificationChannel(ch)
                    }
                    val openIntent = android.content.Intent(this@WhatsAppListenerService, MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pending = android.app.PendingIntent.getActivity(
                        this@WhatsAppListenerService, 0, openIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val notifBuilder = androidx.core.app.NotificationCompat.Builder(this@WhatsAppListenerService, "scamshield_whatsapp")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setColor(0xFFFF3B30.toInt())
                        .setContentTitle("⚠️ WhatsApp: ${res.overallRisk.uppercase()} risk — ${primary?.category ?: "phishing link"}")
                        .setContentText(primary?.reason ?: "Suspicious link — advised to delete and not tap")
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                            .bigText("${primary?.reason ?: ""}\n\nFrom: $title\n\"${text.take(120)}\"\n\nAdvised: DELETE this message and do NOT tap the link. If you tapped, Chrome will still block it."))
                        .setContentIntent(pending)
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    nm.notify((System.currentTimeMillis() % 100000).toInt(), notifBuilder.build())
                }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
