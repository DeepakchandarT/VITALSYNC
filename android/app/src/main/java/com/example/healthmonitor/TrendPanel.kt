package com.example.healthmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrendPanel(samples: List<VitalSample>) {
    var metric by remember { mutableStateOf("Heart rate") }
    var window by remember { mutableStateOf(0) }
    var sampleHistory by remember { mutableStateOf(false) }
    val history = samples.filter { it.synthetic == sampleHistory }.sortedBy { it.timestamp }
    val unit = when(metric) { "Heart rate" -> "bpm"; "SpO₂" -> "%"; else -> "°C" }
    val end = history.lastOrNull()?.timestamp ?: 0L
    val selected = history.filter { window == 0 || it.timestamp >= end - window * 60_000L }
    val points = selected.map { it.timestamp to when(metric) {
        "Heart rate" -> it.heartRate.takeIf { value -> value > 0 && value.isFinite() }
        "SpO₂" -> it.spo2?.takeIf { value -> value > 0 && value <= 100 && value.isFinite() }
        else -> it.temperature?.takeIf { value -> value.isFinite() }
    } }
    val valid = points.mapNotNull { it.second }
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    fun number(value: Double) = String.format(Locale.US, "%.1f", value)
    fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Vital trends", style = MaterialTheme.typography.titleLarge)
            if (samples.any { it.synthetic }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !sampleHistory, onClick = { sampleHistory = false }, label = { Text("Live readings") })
                FilterChip(selected = sampleHistory, onClick = { sampleHistory = true }, label = { Text("Sample history") })
            }
            if (sampleHistory) Text("SAMPLE DATA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Heart rate", "SpO₂", "Ambient temperature").forEach { name ->
                    FilterChip(selected = metric == name, onClick = { metric = name }, label = { Text(name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5 to "5 min", 15 to "15 min", 0 to "Session").forEach { (minutes, label) ->
                    FilterChip(selected = window == minutes, onClick = { window = minutes }, label = { Text(label) })
                }
            }
            if(valid.size < 2) {
                Text("Waiting for ${if(valid.isEmpty()) "readings" else "another reading"}", style = MaterialTheme.typography.titleMedium)
                Text("Keep the monitor open. A trend appears after two available readings. No history is generated.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${number(valid.last())} $unit", style = MaterialTheme.typography.headlineLarge, color = accent)
                Text("Average ${number(valid.average())} • Min ${number(valid.min())} • Max ${number(valid.max())} $unit", style = MaterialTheme.typography.bodySmall)
                val low = valid.min() - (valid.max() - valid.min()).coerceAtLeast(2.0) * 0.1
                val high = valid.max() + (valid.max() - valid.min()).coerceAtLeast(2.0) * 0.1
                Text("${number(high)} $unit", style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    for(i in 0..4) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
                    val start = points.first().first
                    val span = (points.last().first - start).coerceAtLeast(1).toDouble()
                    var previous: Offset? = null
                    points.forEach { (stamp, value) ->
                        if(value == null) previous = null else {
                            val point = Offset(((stamp-start)/span * size.width).toFloat(), (size.height * (1 - (value-low)/(high-low))).toFloat())
                            previous?.let { drawLine(accent, it, point, 2.dp.toPx()) }
                            drawCircle(accent, 2.5.dp.toPx(), point)
                            previous = point
                        }
                    }
                }
                Text("${number(low)} $unit", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(points.first().first), style = MaterialTheme.typography.labelSmall)
                    Text(time(points.last().first), style = MaterialTheme.typography.labelSmall)
                }
                Text("${valid.size} readings • Auto-scaled axis • Session history resets when the app closes", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
