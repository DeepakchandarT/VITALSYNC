package com.example.healthmonitor

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

data class ExtractedVital(val name: String, val value: Double, val unit: String, val referenceRange: String = "")
data class DiagnosisMatch(val diagnosis: String, val evidence: String, val confidence: Int)
data class ExtractedReport(val text: String, val diagnosis: String, val diagnosisEvidence: String, val extractionConfidence: Int, val vitals: List<ExtractedVital>, val status: String)

suspend fun extractReportText(context: Context, file: File): ExtractedReport = withContext(Dispatchers.IO) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val isPdf = file.extension.equals("pdf", true) || file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && String(header, Charsets.US_ASCII) == "%PDF"
        }
        val text = if (isPdf) {
            val pages = mutableListOf<String>()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val desiredScale = 1200f / page.width.coerceAtLeast(1)
                            val rawPixels = page.width.toDouble() * page.height.toDouble() * desiredScale * desiredScale
                            val pixelScale = if (rawPixels > 2_000_000.0) sqrt(2_000_000.0 / rawPixels).toFloat() else 1f
                            val scale = (desiredScale * pixelScale).coerceIn(0.1f, 2.5f)
                            val width = (page.width * scale).toInt().coerceIn(1, 1800)
                            val height = (page.height * scale).toInt().coerceIn(1, 2400)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages += recognize(recognizer, InputImage.fromBitmap(bitmap, 0))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            pages.joinToString("\n")
        } else {
            recognize(recognizer, InputImage.fromFilePath(context, Uri.fromFile(file)))
        }
        val diagnosis = findDiagnosisEvidence(text)
        ExtractedReport(text, diagnosis.diagnosis, diagnosis.evidence, diagnosis.confidence, findVitals(text), if (text.isBlank()) "No readable text found" else "Text extracted on device")
    } catch (error: OutOfMemoryError) {
        ExtractedReport("", "", "", 0, emptyList(), "This report is too complex for on-device OCR. Try a lower-resolution scan.")
    } catch (error: Exception) {
        ExtractedReport("", "", "", 0, emptyList(), "Extraction could not read this document: ${error.message ?: "unknown error"}")
    } finally {
        recognizer.close()
    }
}

private suspend fun recognize(recognizer: com.google.mlkit.vision.text.TextRecognizer, image: InputImage): String =
    suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume("") }
    }

fun findDiagnosis(text: String): String = findDiagnosisEvidence(text).diagnosis

fun findDiagnosisEvidence(text: String): DiagnosisMatch {
    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
    val labels = Regex("^(diagnosis|impression|assessment|conclusion|clinical impression)\\s*[:\\-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    lines.forEachIndexed { index, line ->
        val match = labels.find(line) ?: return@forEachIndexed
        val sameLine = match.groupValues[2].trim()
        if (sameLine.isNotBlank()) return DiagnosisMatch(sameLine.take(500), line.take(700), 96)
        val next = lines.drop(index + 1).firstOrNull().orEmpty()
        if (next.isNotBlank()) return DiagnosisMatch(next.take(500), "$line $next".take(700), 94)
    }
    val clinicalTerms = listOf(
        "hypertension", "hypotension", "sinus tachycardia", "tachycardia", "sinus bradycardia", "bradycardia",
        "atrial fibrillation", "arrhythmia", "irregular rhythm", "premature ventricular contractions", "premature beats",
        "hypoxemia", "reduced oxygen saturation", "borderline low oxygen saturation", "normal sinus rhythm",
        "normal cardiovascular screening findings", "no acute abnormality identified"
    )
    lines.forEach { line ->
        val term = clinicalTerms.firstOrNull { line.contains(it, ignoreCase = true) }
        if (term != null) {
            val exact = Regex(Regex.escape(term), RegexOption.IGNORE_CASE).find(line)?.value ?: term
            return DiagnosisMatch(exact, line.take(700), 88)
        }
    }
    return DiagnosisMatch("", "", 0)
}

fun findVitals(text: String): List<ExtractedVital> {
    val patterns = listOf(
        Triple("Heart rate", Regex("(?:heart\\s*rate|pulse)\\s*[:\\-]?\\s*(\\d{2,3}(?:\\.\\d+)?)\\s*(?:bpm)?", RegexOption.IGNORE_CASE), "bpm"),
        Triple("SpO2", Regex("(?:spo2|oxygen\\s*saturation)\\s*[:\\-]?\\s*(\\d{2,3}(?:\\.\\d+)?)\\s*%?", RegexOption.IGNORE_CASE), "%"),
        Triple("ECG", Regex("(?:ecg|electrocardiogram)\\s*[:\\-]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE), "raw"),
        Triple("Temperature", Regex("(?:body\\s*temperature|temperature)\\s*[:\\-]?\\s*(\\d{2}(?:\\.\\d+)?)\\s*(?:°?c)?", RegexOption.IGNORE_CASE), "°C")
    )
    return patterns.mapNotNull { (name, regex, unit) ->
        regex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { ExtractedVital(name, it, unit) }
    }
}

