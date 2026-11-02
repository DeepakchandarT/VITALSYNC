package com.example.healthmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAnalysisTest {
    @Test
    fun heartFindingUsesReviewLanguageWithoutDiagnosis() {
        val finding = ReportFinding("Heart rate", 120.0, "bpm", "60-100", "test")
        val result = ComparisonResult("Review suggested", "Mismatch", "Evidence", "Limit")
        assertEquals("Heart-related finding needs review", analysisHeadline(finding, result))
    }

    @Test
    fun reportRangeIsPreservedInGuidance() {
        val finding = ReportFinding("SpO2", 94.0, "%", "95-100", "test")
        assertTrue(rangeGuidance(finding).contains("Report reference: 95-100"))
    }

    @Test
    fun diagnosisSectionIsExtractedForVerification() {
        val text = "Patient report\nIMPRESSION: Sinus tachycardia\nSigned"
        assertEquals("Sinus tachycardia", findDiagnosis(text))
    }

    @Test
    fun reportVitalsAreExtractedAutomatically() {
        val text = "Heart rate: 112 bpm\nSpO2: 97%\nECG: 432\nBody temperature: 36.8 C"
        val values = findVitals(text)
        assertEquals(4, values.size)
        assertEquals(112.0, values.first { it.name == "Heart rate" }.value, 0.0)
    }

    @Test
    fun diagnosisIsFoundWithoutAHeadingAndEvidenceIsPreserved() {
        val match = findDiagnosisEvidence("Clinical summary\nThe patient has hypertension requiring follow-up.")
        assertEquals("hypertension", match.diagnosis.lowercase())
        assertTrue(match.evidence.contains("patient has hypertension"))
        assertEquals(88, match.confidence)
    }

    @Test
    fun transparentSimulationExplainsItsInputsWithoutProbabilityScore() {
        val result = simulateMonitoringPriority("Hypertension", 92.0, 97.0)
        assertTrue(result.label.contains("Elevated"))
        assertTrue(result.reasons.contains("hypertension"))
        assertTrue(result.reasons.contains("92 bpm"))
    }
}
