package com.sagesearch.android

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class AndroidOcrAnalyzer(private val context: Context) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(
        uri: Uri,
        onSuccess: (ImageAnalysisResult) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (error: Exception) {
            onError(error)
            return
        }

        recognizer.process(image)
            .addOnSuccessListener { recognized ->
                onSuccess(ImageAnalysisInterpreter.interpret(recognized.text))
            }
            .addOnFailureListener(onError)
    }

    override fun close() {
        recognizer.close()
    }
}
