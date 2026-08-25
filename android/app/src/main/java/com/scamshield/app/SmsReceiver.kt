package com.scamshield.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.auth.api.phone.SmsRetrieverStatusCodes
import com.google.android.gms.common.api.CommonStatusCodes

/**
 * Privacy-safe SMS handling via SMS Retriever API — no READ_SMS permission.
 * Receives SMS messages that match the app's hash pattern and forwards
 * the text to the MainActivity callback for scam analysis.
 * 
 * Per brief Section 2: only SMS text (no images, audio, contacts) is sent
 * to the backend /analyze endpoint. User chooses Delete/Block/Report.
 */
class SmsReceiver : BroadcastReceiver() {

    private val tag = "SmsReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == SmsRetriever.SMS_RETRIEVED_ACTION) {
            val extras = intent.extras ?: return
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? com.google.android.gms.common.api.Status ?: return

            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                    if (message != null && message.isNotEmpty()) {
                        Log.i(tag, "SMS Retriever received: ${message.take(100)}")
                        val forwardIntent = Intent(context, MainActivity::class.java).apply {
                            putExtra("sms_from_receiver", true)
                            putExtra("sms_text", message)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(forwardIntent)
                    }
                }
                CommonStatusCodes.TIMEOUT -> {
                    Log.w(tag, "SMS Retriever timeout - no matching messages")
                }
                else -> {
                    Log.w(tag, "SMS Retriever failed: ${status.statusCode}")
                }
            }
        }
    }
}