package com.scamshield.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Activity log — every scan that was high/medium risk is recorded locally.
 * User is advised to Delete/Block; log persists for review.
 * No PII beyond the snippet + risk — same minimal data as ledger (number/URL + category).
 */
@Entity(tableName = "scan_logs")
data class ScanLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val overallRisk: String, // high/medium/low
    val category: String, // OTP scam etc.
    val reason: String,
    val snippet: String, // first 120 chars of scanned text
    val fullText: String, // full text for review (on-device only)
    val advisedAction: String = "pending", // pending / deleted / blocked / ignored
    val source: String = "bubble" // bubble / manual / whatsapp / sms
)
