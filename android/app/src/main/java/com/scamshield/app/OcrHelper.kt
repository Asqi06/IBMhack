package com.scamshield.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR via Google ML Kit.
 * Raw screenshot bitmap NEVER leaves device — only the extracted String is returned.
 */
object OcrHelper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return result.text ?: ""
    }
}
