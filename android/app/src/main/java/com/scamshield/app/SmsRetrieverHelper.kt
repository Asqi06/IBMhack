package com.scamshield.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.IntentCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Privacy-safe SMS handling via SMS Retriever API — no READ_SMS permission.
 * It only receives messages that contain the app's hash for auto-verification,
 * but we also use it as the approved pattern for scam checking (brief Section 2).
 *
 * For hackathon demo: registers SmsRetriever, forwards retrieved text to callback.
 * The text is then sent via ApiClient.analyze (same as screenshot flow).
 */
class SmsRetrieverHelper(
    private val context: Context,
    private val onSms: (String) -> Unit,
) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val client = SmsRetriever.getClient(context)
        client.startSmsRetriever()
            .addOnSuccessListener { /* listening */ }
            .addOnFailureListener { /* ignore for demo */ }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (SmsRetriever.SMS_RETRIEVED_ACTION == intent?.action) {
                    val extras = intent.extras ?: return
                    val status = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_STATUS, Status::class.java) ?: return
                    if (status.statusCode == CommonStatusCodes.SUCCESS) {
                        val msg = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return
                        onSms(msg)
                    }
                }
            }
        }
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch(_: Exception) {}
        }
        receiver = null
    }
}
