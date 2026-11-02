package com.example.healthmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedReportAnalysis(
    val id: Long,
    val fileName: String,
    val reportType: String,
    val diagnosis: String,
    val findingName: String,
    val findingValue: Double,
    val findingUnit: String,
    val referenceRange: String,
    val category: String,
    val summary: String,
    val evidence: String,
    val guidance: String,
    val diagnosisEvidence: String = "",
    val extractionConfidence: Int = 0,
    val connectedHeartRate: Double = 0.0,
    val connectedSpO2: Double = 0.0,
    val connectedTemperature: Double = 0.0,
    val connectedEcg: String = "Unavailable",
    val prediction: String = "",
    val predictionReasons: String = ""
)

object ReportHistoryStore {
    private const val PREFS = "private_report_history"
    private const val KEY = "verified_analyses"

    fun load(context: Context): List<SavedReportAnalysis> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SavedReportAnalysis(
                id = item.getLong("id"), fileName = item.getString("fileName"), reportType = item.getString("reportType"),
                diagnosis = item.optString("diagnosis"), findingName = item.getString("findingName"),
                findingValue = item.getDouble("findingValue"), findingUnit = item.getString("findingUnit"),
                referenceRange = item.getString("referenceRange"), category = item.getString("category"),
                summary = item.getString("summary"), evidence = item.getString("evidence"), guidance = item.getString("guidance"),
                diagnosisEvidence = item.optString("diagnosisEvidence"), extractionConfidence = item.optInt("extractionConfidence"),
                connectedHeartRate = item.optDouble("connectedHeartRate", 0.0).finiteOrZero(),
                connectedSpO2 = item.optDouble("connectedSpO2", 0.0).finiteOrZero(),
                connectedTemperature = item.optDouble("connectedTemperature", 0.0).finiteOrZero(), connectedEcg = item.optString("connectedEcg", "Unavailable"),
                prediction = item.optString("prediction"), predictionReasons = item.optString("predictionReasons")
            )
        }.sortedByDescending { it.id }
    }.getOrDefault(emptyList())

    fun save(context: Context, analysis: SavedReportAnalysis): List<SavedReportAnalysis> {
        val updated = (listOf(analysis) + load(context)).distinctBy { it.id }.take(50)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("fileName", item.fileName); put("reportType", item.reportType); put("diagnosis", item.diagnosis)
                put("findingName", item.findingName); put("findingValue", item.findingValue.finiteOrZero()); put("findingUnit", item.findingUnit)
                put("referenceRange", item.referenceRange); put("category", item.category); put("summary", item.summary)
                put("evidence", item.evidence); put("guidance", item.guidance)
                put("diagnosisEvidence", item.diagnosisEvidence); put("extractionConfidence", item.extractionConfidence)
                put("connectedHeartRate", item.connectedHeartRate.finiteOrZero()); put("connectedSpO2", item.connectedSpO2.finiteOrZero())
                put("connectedTemperature", item.connectedTemperature.finiteOrZero()); put("connectedEcg", item.connectedEcg)
                put("prediction", item.prediction); put("predictionReasons", item.predictionReasons)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        return updated
    }
}

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

data class TransparentPrediction(val label: String, val reasons: String)

fun simulateMonitoringPriority(diagnosis: String, heartRate: Double, spo2: Double): TransparentPrediction {
    val reasons = mutableListOf<String>()
    var priority = 0
    val clinicalTerms = listOf("hypertension", "tachycardia", "bradycardia", "arrhythmia", "fibrillation", "hypoxemia", "irregular", "premature")
    val matched = clinicalTerms.firstOrNull { diagnosis.contains(it, ignoreCase = true) }
    if (matched != null) { priority += 2; reasons += "Report text contains '$matched'" }
    if (heartRate >= 90) { priority += 1; reasons += "Connected heart rate is ${heartRate.toInt()} bpm" }
    if (heartRate < 60) { priority += 2; reasons += "Connected heart rate is below 60 bpm" }
    if (spo2 in 1.0..94.9) { priority += 2; reasons += "Connected SpO2 is below 95%" }
    if (reasons.isEmpty()) reasons += "No configured priority rule was triggered"
    val level = when { priority >= 3 -> "Elevated"; priority >= 1 -> "Moderate"; else -> "Routine" }
    return TransparentPrediction("Pattern detected: $level cardiovascular monitoring priority (simulated).", reasons.joinToString(" • "))
}

fun analysisHeadline(finding: ReportFinding, result: ComparisonResult): String {
    val name = finding.name.lowercase()
    return when {
        result.category == "Review suggested" && (name.contains("heart") || name.contains("pulse") || name.contains("ecg")) -> "Heart-related finding needs review"
        result.category == "Review suggested" && (name.contains("spo2") || name.contains("oxygen")) -> "Blood oxygen finding needs review"
        result.category == "Review suggested" -> "Finding needs clinical review"
        result.category == "Consistent" -> "No mismatch found in comparable readings"
        else -> "More matching data is needed"
    }
}

fun rangeGuidance(finding: ReportFinding): String {
    val name = finding.name.lowercase()
    val printed = finding.referenceRange.trim()
    val guide = when {
        name.contains("heart") || name.contains("pulse") || name.contains("bpm") -> "Configured adult resting check band: 60–100 bpm."
        name.contains("spo2") || name.contains("oxygen") -> "Configured check band: 95–100% SpO₂."
        name.contains("ecg") -> "This device supplies a raw ECG value, so no clinical range can be assigned."
        else -> "No matching sensor range is configured for this finding."
    }
    return if (printed.isNotBlank()) "Report reference: $printed. $guide Use the clinician-provided range for personal decisions." else "$guide Ask a clinician for a personal target range."
}
