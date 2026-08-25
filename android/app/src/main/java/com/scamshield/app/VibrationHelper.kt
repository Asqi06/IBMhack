package com.scamshield.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Helper — vibrate 3 times on any scam detection (call, bubble, SMS, WhatsApp, QR, manual).
 * Uses waveform: 3 short bursts (400ms on, 200ms off) — distinct from single notification buzz.
 * Works from Service or Activity; requires android.permission.VIBRATE (normal permission, no runtime).
 */
object VibrationHelper {
    fun vibrateTriple(context: Context) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            // Pattern: wait 0, vibrate 400, pause 200, vibrate 400, pause 200, vibrate 400
            val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }
}
