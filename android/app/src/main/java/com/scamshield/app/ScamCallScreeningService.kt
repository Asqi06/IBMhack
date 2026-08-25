package com.scamshield.app

import android.content.SharedPreferences
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v1.2 Caller-ID Pre-check — checks incoming number against ledger before you answer.
 * Never blocks the call (avoid false-positive harm), but posts a heads-up notification
 * if the number is reported as high-risk.
 */
class ScamCallScreeningService : CallScreeningService() {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val backendUrl = getPrefs().getString("backendUrl", "https://scanshield-ii9n.onrender.com") ?: "https://scanshield-ii9n.onrender.com"
        var risk: String? = null
        var count = 0
        var category: String? = null

        if (number.isNotBlank()) {
            try {
                // Synchronous quick check within CallScreeningService time budget (~5s)
                val enc = URLEncoder.encode(number, "UTF-8")
                val req = Request.Builder().url(backendUrl.trimEnd('/') + "/check/$enc").get().build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = resp.body?.string() ?: "{}"
                        val o = JSONObject(txt)
                        risk = o.optString("riskLevel", o.optString("risk"))
                        count = o.optInt("reportCount", 0)
                        category = o.optJSONArray("entries")?.let { arr ->
                            if (arr.length() > 0) arr.getJSONObject(0).optString("category") else null
                        }
                        if (category.isNullOrEmpty()) category = "scam"
                    }
                }
            } catch (e: Exception) {
                Log.w("CallScreening", "ledger check failed for $number", e)
            }
        }

        // Build response: never outright block, just warn via notification if high
        val response = CallResponse.Builder().apply {
            setDisallowCall(false)
            setRejectCall(false)
            setSkipCallLog(false)
            setSkipNotification(false)
        }.build()
        respondToCall(callDetails, response)

        if (risk == "high" || (count > 0 && risk == "high")) {
            showWarning(number, count, category)
        } else if (count > 2) {
            showWarning(number, count, category)
        }
    }

    private fun showWarning(number: String, count: Int, category: String?) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel("scamshield_callerid", "Caller ID Guard", android.app.NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
            val intent = android.content.Intent(this, MainActivity::class.java).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP }
            val pending = android.app.PendingIntent.getActivity(this, 77, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val notif = androidx.core.app.NotificationCompat.Builder(this, "scamshield_callerid")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setColor(0xFFFF3B30.toInt())
                .setContentTitle("⚠️ Scam number: $number")
                .setContentText("Reported $count times • $category • Be cautious, don't share OTP")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("This number was reported $count times as ${category ?: "scam"}.\nAdvice: Do not share OTP/PIN. Verify via official bank app."))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(number.hashCode(), notif)
        } catch (_: Exception) {}
    }

    private fun getPrefs(): SharedPreferences = getSharedPreferences("scamshield", MODE_PRIVATE)
}
