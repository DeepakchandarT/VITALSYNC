package com.example.healthmonitor

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class VitalSample(
    val timestamp: Long,
    val heartRate: Double,
    val spo2: Double?,
    val ecg: Double?,
    val temperature: Double?,
    val synthetic: Boolean = false
)

object SampleVitalBaseline {
    fun samples(now: Long = System.currentTimeMillis()): List<VitalSample> {
        val values = listOf(84.0 to 98.0, 86.0 to 98.0, 88.0 to 97.0, 87.0 to 98.0, 90.0 to 97.0, 91.0 to 97.0, 89.0 to 98.0, 92.0 to 97.0)
        return values.mapIndexed { index, pair ->
            VitalSample(now - (values.size - index) * 60_000L, pair.first, pair.second, 420.0 + index, 24.7, synthetic = true)
        }
    }
}

data class AnomalyInsight(
    val status: String,
    val score: Double,
    val explanation: String,
    val sampleCount: Int,
    val model: String = "Experimental isolation-style baseline model v1"
)

data class ReportFinding(
    val name: String,
    val value: Double,
    val unit: String,
    val referenceRange: String,
    val source: String
)

data class ReportRecord(
    val fileName: String,
    val storedPath: String,
    val reportType: String,
    val consentGranted: Boolean,
    val extractionStatus: String,
    val finding: ReportFinding?
)

data class ComparisonResult(
    val category: String,
    val explanation: String,
    val evidence: String,
    val limitation: String
)

/** Experimental, deterministic Isolation Forest trained on the user's received vital history. */
object VitalAnomalyModel {
    private data class Node(val feature: Int = -1, val split: Double = 0.0, val left: Node? = null, val right: Node? = null, val size: Int = 0)

    fun analyze(samples: List<VitalSample>): AnomalyInsight {
        val valid = samples.filter { it.heartRate > 0.0 }.map { vector(it) }
        if (valid.size < 8) {
            return AnomalyInsight("Insufficient data", 0.0,
                "At least eight valid samples are required to train the experimental Isolation Forest baseline.", valid.size)
        }
        val forest = (0 until 48).map { buildTree(valid.shuffled(Random(1000 + it)).take(minOf(64, valid.size)), 0, 8, Random(1000 + it)) }
        val latest = valid.last()
        val averagePath = forest.map { pathLength(latest, it, 0) }.average()
        val score = 2.0.pow(-averagePath / c(valid.size)).coerceIn(0.0, 1.0)
        val hr = valid.map { it[0] }
        val mean = hr.average()
        val deviation = sqrt(hr.map { (it - mean) * (it - mean) }.average()).coerceAtLeast(1.0)
        val recent = hr.last()
        val status = if (score >= 0.65) "Review suggested" else "No unusual pattern detected"
        val explanation = String.format(Locale.US,
            "Isolation Forest score %.2f for the latest multivariate sample. Heart rate %.1f bpm; personal baseline %.1f bpm with variability %.1f. This is an experimental pattern flag, not a disease probability.",
            score, recent, mean, deviation)
        return AnomalyInsight(status, score, explanation, valid.size, "Experimental Isolation Forest v1 (48 trees, deterministic seed)")
    }

    private fun vector(sample: VitalSample): DoubleArray = doubleArrayOf(sample.heartRate, sample.spo2 ?: 0.0, sample.temperature ?: 0.0)
    private fun buildTree(rows: List<DoubleArray>, depth: Int, limit: Int, random: Random): Node {
        if (rows.size <= 1 || depth >= limit) return Node(size = rows.size)
        val feature = random.nextInt(3)
        val values = rows.map { it[feature] }
        val min = values.minOrNull() ?: return Node(size = rows.size)
        val max = values.maxOrNull() ?: return Node(size = rows.size)
        if (min == max) return Node(size = rows.size)
        val split = min + random.nextDouble() * (max - min)
        val left = rows.filter { it[feature] < split }
        val right = rows.filter { it[feature] >= split }
        if (left.isEmpty() || right.isEmpty()) return Node(size = rows.size)
        return Node(feature, split, buildTree(left, depth + 1, limit, random), buildTree(right, depth + 1, limit, random), rows.size)
    }
    private fun pathLength(row: DoubleArray, node: Node, depth: Int): Double {
        if (node.feature < 0 || node.left == null || node.right == null) return depth + c(node.size)
        return if (row[node.feature] < node.split) pathLength(row, node.left, depth + 1) else pathLength(row, node.right, depth + 1)
    }
    private fun c(size: Int): Double = if (size <= 1) 0.0 else 2.0 * (ln(size - 1.0) + 0.5772156649) - 2.0 * (size - 1.0) / size
}

fun compareFindingToVitals(finding: ReportFinding?, samples: List<VitalSample>): ComparisonResult {
    if (finding == null || samples.isEmpty()) {
        return ComparisonResult("Insufficient data", "No verified finding and matching sensor window are available.", "No supporting measurements.", "Wearable vitals cannot verify unrelated laboratory values.")
    }
    val key = finding.name.lowercase(Locale.US)
    val values = when {
        key.contains("heart") || key.contains("pulse") || key.contains("bpm") -> samples.map { it.heartRate }.filter { it > 0 }
        key.contains("oxygen") || key.contains("spo2") -> samples.mapNotNull { it.spo2 }.filter { it > 0 }
        else -> emptyList()
    }
    if (values.isEmpty()) {
        return ComparisonResult("Insufficient data", "The verified report finding has no technically relevant sensor measurement.", "Finding: ${finding.name} ${finding.value} ${finding.unit}", "This sensor cannot validate the reported laboratory or diagnostic value.")
    }
    val avg = values.average()
    val delta = abs(avg - finding.value)
    val category = if (delta <= max(1.0, abs(finding.value) * 0.1)) "Consistent" else "Review suggested"
    return ComparisonResult(category,
        String.format(Locale.US, "The report lists %.2f %s; the matching sensor average is %.2f %s over %d samples.", finding.value, finding.unit, avg, finding.unit, values.size),
        "Report finding: ${finding.name} ${finding.value} ${finding.unit} (${finding.referenceRange}). Sensor samples: ${values.size}.",
        "This is a technical comparison, not a diagnosis or medical clearance.")
}

suspend fun privatelyStoreDocument(context: Context, uri: Uri, displayName: String): Pair<File?, String> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val type = resolver.getType(uri).orEmpty().lowercase(Locale.US)
    val allowed = type.contains("pdf") || type.contains("jpeg") || type.contains("jpg") || type.contains("png") || displayName.lowercase(Locale.US).endsWith(".pdf") || displayName.lowercase(Locale.US).matches(Regex(".*\\.(jpg|jpeg|png)$"))
    if (!allowed) return@withContext null to "Unsupported file type. Choose PDF, JPG, JPEG, or PNG."
    val dir = File(context.filesDir, "medical_reports").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
    val target = File(dir, "${System.currentTimeMillis()}_$safeName")
    val maximum = 15L * 1024L * 1024L
    var exceeded = false
    resolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximum) { exceeded = true; break }
                output.write(buffer, 0, count)
            }
        }
    } ?: return@withContext null to "The selected document could not be read."
    if (exceeded) { target.delete(); return@withContext null to "Document exceeds the 15 MB limit." }
    target to "Stored privately on this device. Extraction requires user verification."
}
