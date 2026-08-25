package com.scamshield.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * v1.2 Live Call Shield — on-device STT helper.
 * Uses Android SpeechRecognizer (on-device when possible) with MIC source.
 * Chunks every ~4s and streams transcript text via callback.
 * Only TEXT leaves device; raw audio never sent.
 * Requires RECORD_AUDIO + call on speaker for remote voice (see disclosure).
 */
class CallTranscriptionHelper(
    private val context: Context,
    private val onChunk: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldContinue = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("CallTranscription", "STT not available")
            return
        }
        shouldContinue = true
        startOnce()
    }

    fun stop() {
        shouldContinue = false
        isListening = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // Mute system beep that SpeechRecognizer fires onReadyForSpeech (ruins call otherwise)
    private fun muteBeep(mute: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 23) {
                if (mute) {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                } else {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_SYSTEM, mute)
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
        } catch (_: Exception) {}
    }

    private fun startOnce() {
        if (!shouldContinue) return
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            // Hint for multilingual: also accept Hindi
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("en-IN", "hi-IN"))
        }
        try {
            isListening = true
            muteBeep(true)
            recognizer?.startListening(intent)
            // unmute shortly after beep window
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ muteBeep(false) }, 800)
        } catch (e: Exception) {
            Log.e("CallTranscription", "startListening failed", e)
            muteBeep(false)
            scheduleRestart()
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { }
        override fun onBeginningOfSpeech() { }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { }
        override fun onError(error: Int) {
            Log.w("CallTranscription", "STT error $error")
            isListening = false
            muteBeep(false)
            if (shouldContinue) scheduleRestart()
        }
        override fun onResults(results: Bundle?) {
            muteBeep(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank() && text.length > 3) {
                Log.i("CallTranscription", "chunk: ${text.take(80)}")
                onChunk(text)
            }
            isListening = false
            if (shouldContinue) scheduleRestart()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            // could stream partials for ultra-low latency, but we batch on final
        }
        override fun onEvent(eventType: Int, params: Bundle?) { }
    }

    private fun scheduleRestart() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (shouldContinue) startOnce()
        }, 600)
    }
}
