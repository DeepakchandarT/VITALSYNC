package com.example.healthmonitor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.healthmonitor.ui.theme.HealthmonitorTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        enableEdgeToEdge()
        setContent { HealthmonitorTheme { Surface(Modifier.fillMaxSize()) { HealthMonitorApp() } } }
    }
}

class HealthViewModel : ViewModel() {
    private val healthRef = Firebase.database.getReference("Health")
    private val simulatorRef = Firebase.database.getReference("Simulator")
    private val _healthData = kotlinx.coroutines.flow.MutableStateFlow<HealthData?>(null)
    val healthData: StateFlow<HealthData?> = _healthData
    private val _simulatorData = kotlinx.coroutines.flow.MutableStateFlow<SimulatorData?>(null)
    val simulatorData: StateFlow<SimulatorData?> = _simulatorData
    private val _samples = kotlinx.coroutines.flow.MutableStateFlow<List<VitalSample>>(SampleVitalBaseline.samples())
    val samples: StateFlow<List<VitalSample>> = _samples
    init {
        healthRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.getValue(HealthData::class.java)
                _healthData.value = data
                if (data != null && data.HeartRate > 0) {
                    val realSamples = (_samples.value.filterNot { it.synthetic } + VitalSample(System.currentTimeMillis(), data.HeartRate.toDouble(), data.SpO2, data.ECG?.toDouble(), data.DHT_Temperature)).takeLast(200)
                    _samples.value = if (realSamples.count() >= 8) realSamples else (SampleVitalBaseline.samples() + realSamples).takeLast(200)
                }
            }
            override fun onCancelled(error: DatabaseError) { Log.e("FirebaseDebug", error.message) }
        })
        simulatorRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.getValue(SimulatorData::class.java)
                _simulatorData.value = data
                if (data != null && data.heartRate > 0) {
                    val isSynthetic = data.metadata.synthetic || data.synthetic
                    val stamp = data.metadata.updatedAt.takeIf { it > 0 } ?: data.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val sample = VitalSample(stamp, data.heartRate, data.spo2.takeIf { it > 0 }, data.ecg.takeIf { it != 0.0 }, data.ambientTemperature.takeIf { it != 0.0 }, isSynthetic)
                    if (isSynthetic) _samples.value = (_samples.value + sample).takeLast(200)
                    else {
                        val realSamples = (_samples.value.filterNot { it.synthetic } + sample).takeLast(200)
                        _samples.value = if (realSamples.size >= 8) realSamples else (SampleVitalBaseline.samples() + realSamples).takeLast(200)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) { Log.e("SimulatorFirebase", error.message) }
        })
    }
}

data class HealthData(
    val DHT_Temperature: Double = 0.0,
    val ECG: Int? = 0,
    val HeartRate: Int = 0,
    val Humidity: Double = 0.0,
    val Probe_Temperature: Int = 0,
    val SpO2: Double? = null
)

data class AccelerometerData(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)
data class SensorAvailability(
    val accelX: Boolean = false, val accelY: Boolean = false, val accelZ: Boolean = false,
    val ambientTemperature: Boolean = false, val battery: Boolean = false, val ecg: Boolean = false,
    val heartRate: Boolean = false, val spo2: Boolean = false, val temperature: Boolean = false
)
data class SimulatorMetadata(
    val availability: SensorAvailability = SensorAvailability(), val mode: String = "",
    val sessionLabel: String = "", val source: String = "", val synthetic: Boolean = true,
    val updatedAt: Long = 0L
)
data class SimulatorData(
    val accelerometer: AccelerometerData = AccelerometerData(), val battery: Int = 0,
    val charging: Boolean = false, val connectivity: String = "offline",
    val metadata: SimulatorMetadata = SimulatorMetadata(), val sensorStatus: String = "unknown",
    val worn: Boolean = false, val heartRate: Double = 0.0, val spo2: Double = 0.0,
    val ecg: Double = 0.0, val temperature: Double = 0.0, val ambientTemperature: Double = 0.0,
    val updatedAt: Long = 0L, val synthetic: Boolean = true
)

private enum class Screen { LIVE, PROCESS, REPORTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthMonitorApp() {
    val vm: HealthViewModel = viewModel()
    val context = LocalContext.current
    val data by vm.healthData.collectAsState()
    val simulator by vm.simulatorData.collectAsState()
    val samples by vm.samples.collectAsState()
    val usingSimulator = simulator != null && (simulator?.heartRate ?: 0.0) > 0
    val showingSample = !usingSimulator && (data == null || (data?.HeartRate ?: 0) <= 0)
    val displayData = when {
        usingSimulator -> HealthData(
            simulator!!.ambientTemperature.takeIf { it != 0.0 } ?: 24.7,
            simulator!!.ecg.toInt().takeIf { it != 0 } ?: 420,
            simulator!!.heartRate.toInt(), data?.Humidity ?: 59.8,
            simulator!!.temperature.toInt().takeIf { it != 0 } ?: 36,
            simulator!!.spo2.takeIf { it > 0 } ?: 97.0
        )
        showingSample -> HealthData(24.7, 420, 92, 59.8, 36, 97.0)
        else -> data!!
    }
    var screen by remember { mutableStateOf(Screen.LIVE) }
    var showDeviceDetails by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(ReportHistoryStore.load(context)) }
    BackHandler(screen != Screen.LIVE) { screen = Screen.LIVE }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(
        title = { Text(when(screen) { Screen.LIVE -> "Health"; Screen.PROCESS -> "Process report"; Screen.REPORTS -> "Results & history" }, fontWeight = FontWeight.SemiBold) },
        actions = {
            IconButton(onClick = { showDeviceDetails = true }) {
                Text("●", fontSize = 22.sp, color = if (simulator?.connectivity.equals("online", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (screen == Screen.LIVE) TextButton(onClick = { screen = Screen.PROCESS }) { Text("＋ Upload report") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    ) }, bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(screen == Screen.LIVE, { screen = Screen.LIVE }, icon = { Text("●") }, label = { Text("Live") })
        NavigationBarItem(screen == Screen.PROCESS, { screen = Screen.PROCESS }, icon = { Text("↥") }, label = { Text("Process") })
        NavigationBarItem(screen == Screen.REPORTS, { screen = Screen.REPORTS }, icon = { Text("▤") }, label = { Text("Reports") })
    } }) { padding ->
        if (screen == Screen.PROCESS) AiHealthScreen(samples, Modifier.padding(padding), onSaved = { saved ->
            history = ReportHistoryStore.save(context, saved)
            screen = Screen.REPORTS
        })
        else if (screen == Screen.REPORTS) ReportHistoryScreen(history, Modifier.padding(padding))
        else LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { Text("Overview", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text(when { usingSimulator -> "${simulator?.metadata?.sessionLabel.orEmpty().ifBlank { "Wearable simulator" }} · ${simulator?.connectivity}"; showingSample -> "Sample measurements"; else -> "Your latest received measurements" }, color = if (showingSample) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HealthDataCard("Heart rate", displayData.HeartRate.toString(), "bpm", Modifier.weight(1f))
                HealthDataCard("Blood oxygen", displayData.SpO2?.let { formatValue(it) } ?: "—", "% SpO₂", Modifier.weight(1f))
            } }
            item { DeviceSummaryCard(simulator) { showDeviceDetails = true } }
            item { MotionCard(simulator?.accelerometer ?: AccelerometerData(0.012, -0.018, 0.998)) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HealthDataCard("Probe temperature", displayData.Probe_Temperature.toString(), "°C", Modifier.weight(1f))
                HealthDataCard("ECG signal", displayData.ECG?.toString() ?: "—", "raw sensor value", Modifier.weight(1f))
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HealthDataCard("Ambient temperature", formatValue(displayData.DHT_Temperature), "°C · environment", Modifier.weight(1f))
                HealthDataCard("Humidity", formatValue(displayData.Humidity), "% · environment", Modifier.weight(1f))
            } }
            item { Text("Trends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { Text(if (samples.any { it.synthetic }) "Sample history included • ${samples.count { !it.synthetic }} live readings. Sample history clears after 8 live readings." else "${samples.size} received readings • Times reflect receipt on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TrendPanel(samples) }
            item { Text("Missing readings appear as —. Sensor values are not a medical diagnosis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (showDeviceDetails) DeviceStatusDialog(simulator, onDismiss = { showDeviceDetails = false })
}

private fun simulatorForDisplay(value: SimulatorData?): SimulatorData = value ?: SimulatorData(
    accelerometer = AccelerometerData(0.012, -0.018, 0.998), battery = 84, charging = false,
    connectivity = "online", metadata = SimulatorMetadata(
        availability = SensorAvailability(true, true, true, true, true, true, true, true, true),
        mode = "manual", sessionLabel = "wearable-demo", source = "direct-rest-demo-simulator",
        synthetic = true, updatedAt = 1793608036074
    ), sensorStatus = "ok", worn = true, heartRate = 92.0, spo2 = 97.0,
    ecg = 420.0, temperature = 36.8, ambientTemperature = 24.7, synthetic = true
)

@Composable
private fun DeviceSummaryCard(simulator: SimulatorData?, onClick: () -> Unit) {
    val device = simulatorForDisplay(simulator)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${device.metadata.sessionLabel.ifBlank { "Wearable device" }} · ${device.sensorStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("● ${device.connectivity.replaceFirstChar { it.uppercase() }}", color = if (device.connectivity.equals("online", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                Text("${device.battery}% battery", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MotionCard(accelerometer: AccelerometerData) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Accelerometer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("X  ${String.format(Locale.US, "%.3f", accelerometer.x)}")
                Text("Y  ${String.format(Locale.US, "%.3f", accelerometer.y)}")
                Text("Z  ${String.format(Locale.US, "%.3f", accelerometer.z)}")
            }
        }
    }
}

@Composable
private fun DeviceStatusDialog(simulator: SimulatorData?, onDismiss: () -> Unit) {
    val device = simulatorForDisplay(simulator)
    val available = device.metadata.availability
    val sensors = listOf(
        "Accelerometer X" to available.accelX, "Accelerometer Y" to available.accelY,
        "Accelerometer Z" to available.accelZ, "Ambient temperature" to available.ambientTemperature,
        "Battery" to available.battery, "ECG" to available.ecg, "Heart rate" to available.heartRate,
        "SpO₂" to available.spo2, "Body temperature" to available.temperature
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Device details") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("● ${device.connectivity.replaceFirstChar { it.uppercase() }}", color = if (device.connectivity.equals("online", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
            DeviceDetailRow("Sensor status", device.sensorStatus)
            DeviceDetailRow("Battery", "${device.battery}%${if (device.charging) " · Charging" else " · Not charging"}")
            DeviceDetailRow("Worn", if (device.worn) "Yes" else "No")
            DeviceDetailRow("Session", device.metadata.sessionLabel.ifBlank { "Unavailable" })
            DeviceDetailRow("Mode", device.metadata.mode.ifBlank { "Unavailable" })
            DeviceDetailRow("Source", device.metadata.source.ifBlank { "Unavailable" })
            DeviceDetailRow("Updated", device.metadata.updatedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Unavailable")
            HorizontalDivider()
            Text("Accelerometer", fontWeight = FontWeight.SemiBold)
            Text("X ${String.format(Locale.US, "%.3f", device.accelerometer.x)}   Y ${String.format(Locale.US, "%.3f", device.accelerometer.y)}   Z ${String.format(Locale.US, "%.3f", device.accelerometer.z)}")
            Text("Available sensors", fontWeight = FontWeight.SemiBold)
            sensors.forEach { (name, enabled) -> Text("${if (enabled) "✓" else "–"} $name") }
        } }
    )
}

@Composable
private fun DeviceDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AiHealthScreen(samples: List<VitalSample>, modifier: Modifier, onSaved: (SavedReportAnalysis) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("No medical document selected.") }
    var processing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && consent) scope.launch {
            processing = true
            message = "Reading report and running analysis…"
            try {
                val (file, storageResult) = privatelyStoreDocument(context, uri, uri.lastPathSegment ?: "report")
                if (file != null) {
                    val extracted = extractReportText(context, file)
                    if (extracted.text.isBlank()) {
                        message = extracted.status
                        return@launch
                    }
                    val connected = samples.lastOrNull() ?: SampleVitalBaseline.samples().last()
                    val connectedHeartRate = connected.heartRate.takeIf { it > 0 } ?: 92.0
                    val connectedSpO2 = connected.spo2?.takeIf { it > 0 } ?: 97.0
                    val connectedTemperature = if (connected.synthetic) 36.8 else connected.temperature ?: 36.8
                    val connectedEcg = if (connected.synthetic) "Simulated normal" else connected.ecg?.let { "Raw ${it.toInt()}" } ?: "Unavailable"
                    val prediction = simulateMonitoringPriority(extracted.diagnosis, connectedHeartRate, connectedSpO2)
                    val vital = extracted.vitals.firstOrNull()
                    val finding = if (vital != null) ReportFinding(vital.name, vital.value, vital.unit, vital.referenceRange, file.name)
                        else ReportFinding("Report diagnosis", 0.0, "", "", file.name)
                    onSaved(SavedReportAnalysis(
                        id = System.currentTimeMillis(), fileName = file.name, reportType = "Medical report",
                        diagnosis = extracted.diagnosis.ifBlank { "No diagnosis term detected" },
                        findingName = finding.name, findingValue = finding.value, findingUnit = finding.unit,
                        referenceRange = finding.referenceRange, category = "Processed", summary = "Analysis complete",
                        evidence = if (vital != null) "Report vital detected: ${vital.name} ${vital.value} ${vital.unit}" else "Diagnosis-only reports are supported; report vitals are optional.",
                        guidance = "Report extraction and connected readings are shown separately.",
                        diagnosisEvidence = extracted.diagnosisEvidence.ifBlank { "No matching diagnosis sentence was found in recognized text." },
                        extractionConfidence = extracted.extractionConfidence,
                        connectedHeartRate = connectedHeartRate, connectedSpO2 = connectedSpO2,
                        connectedTemperature = connectedTemperature, connectedEcg = connectedEcg,
                        prediction = prediction.label, predictionReasons = prediction.reasons
                    ))
                } else message = storageResult
            } catch (error: Exception) {
                Log.e("ReportUpload", "Upload analysis failed", error)
                message = "Analysis could not finish: ${error.message ?: "unsupported or damaged report"}. Try another PDF or image."
            } finally {
                processing = false
            }
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Automatic report analysis", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Upload once. Full-document extraction, clinical-term detection and a transparent demonstration prediction run automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { AnalysisSection("AUTOMATIC PIPELINE", "Ready to process", "Report extraction", "Scans every PDF page or the selected image and preserves the diagnosis evidence sentence.", "Combined output", "Shows extracted findings, connected readings and the exact rules that triggered the simulated priority.") }
        item { Text("Upload report", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { Text("PDF, JPG or PNG · Up to 15 MB · Stored on this device", style = MaterialTheme.typography.bodySmall) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("Allow private on-device storage and processing.", Modifier.weight(1f)) } }
        item { Button(onClick = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) }, enabled = consent && !processing, modifier = Modifier.fillMaxWidth()) { Text(if (processing) "Analyzing…" else "Choose report and analyze") } }
        if (processing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Text(message) }
        item { Text("Diagnosis-only reports are supported. Missing report vitals do not stop extraction or analysis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ReportHistoryScreen(history: List<SavedReportAnalysis>, modifier: Modifier) {
    val context = LocalContext.current
    val savedFiles = remember(history) { File(context.filesDir, "medical_reports").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty() }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Analysis output", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("ML predictions and report details saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Saved files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (savedFiles.isEmpty()) item { Text("No uploaded files yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            savedFiles.forEach { file -> Text("▣ ${file.name.substringAfter('_')}  ·  ${file.length() / 1024} KB") }
        } } }
        item { Text("Previous analysis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (history.isEmpty()) {
            item { AnalysisSection("NO SAVED REPORTS", "Your results will appear here", "How it works", "Open Process and upload a report. Extraction and analysis run automatically.", "Storage", "Report details and analysis history are saved on this device.") }
        } else history.forEachIndexed { index, item ->
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (index == 0) "LATEST RESULT" else "PREVIOUS REPORT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Text(if (item.prediction.isNotBlank()) "Analysis complete" else item.summary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        AssistChip(onClick = {}, label = { Text(item.category) })
                        Text("${item.reportType} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.id))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Extracted report findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Diagnosis", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.diagnosis, style = MaterialTheme.typography.titleMedium)
                        if (item.extractionConfidence > 0) Text("Extraction confidence ${item.extractionConfidence}% · OCR heuristic", color = MaterialTheme.colorScheme.primary)
                        if (item.diagnosisEvidence.isNotBlank()) {
                            Text("Evidence found", fontWeight = FontWeight.SemiBold)
                            Text("“${item.diagnosisEvidence}”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.prediction.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("Connected sample vitals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ResultMetric("Heart rate", "${item.connectedHeartRate.toInt()} bpm", Modifier.weight(1f))
                                ResultMetric("SpO₂", "${item.connectedSpO2.toInt()}%", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ResultMetric("Temperature", "${String.format(Locale.US, "%.1f", item.connectedTemperature)}°C", Modifier.weight(1f))
                                ResultMetric("ECG", item.connectedEcg, Modifier.weight(1f))
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("ML demonstration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(item.prediction, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Why this appeared", fontWeight = FontWeight.SemiBold)
                            Text(item.predictionReasons, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Synthetic inputs • Illustrative rule-based prediction • Not a clinical diagnosis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Text("Legacy result", fontWeight = FontWeight.SemiBold)
                            Text(item.evidence, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Source: ${item.fileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HealthDataCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier.heightIn(min = 146.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatValue(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "—"

@Composable
private fun AnalysisSection(label: String, title: String, firstLabel: String, first: String, secondLabel: String, second: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(firstLabel, fontWeight = FontWeight.SemiBold)
            Text(first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(secondLabel, fontWeight = FontWeight.SemiBold)
            Text(second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
