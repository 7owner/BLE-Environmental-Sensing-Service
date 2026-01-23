package com.example.environmental_sensing_service

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

val GoodGreen = Color(0xFF4CAF50)
val WarningOrange = Color(0xFFFF9800)
val DangerRed = Color(0xFFF44336)
val NeutralGray = Color(0xFF757575)

enum class Quality { Good, Warning, Bad, Unknown }

fun evaluateTempQuality(temp: Double?): Quality = when {
    temp == null -> Quality.Unknown
    temp in 18.0..26.0 -> Quality.Good
    temp in 15.0..18.0 || temp in 26.0..30.0 -> Quality.Warning
    else -> Quality.Bad
}

fun evaluateHumidityQuality(hum: Double?): Quality = when {
    hum == null -> Quality.Unknown
    hum in 40.0..60.0 -> Quality.Good
    hum in 30.0..40.0 || hum in 60.0..70.0 -> Quality.Warning
    else -> Quality.Bad
}

fun evaluateAirQuality(pm25: Double?, pm10: Double?): Quality {
    val p25 = pm25 ?: 999.0
    val p10 = pm10 ?: 999.0
    return when {
        p25 <= 12 && p10 <= 20 -> Quality.Good
        p25 <= 35 || p10 <= 50 -> Quality.Warning
        else -> Quality.Bad
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDashboard(
    onDisconnect: () -> Unit,
    onViewHistory: () -> Unit,
    onEditThresholds: () -> Unit,
    modifier: Modifier = Modifier
) {
    val temp = BLEState.temperature
    val hum = BLEState.humidity
    val pm25 = BLEState.pm25
    val pm10 = BLEState.pm10

    val tempQuality = remember(temp) { evaluateTempQuality(temp) }
    val humQuality = remember(hum) { evaluateHumidityQuality(hum) }
    val airQuality = remember(pm25, pm10) { evaluateAirQuality(pm25, pm10) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CompactTopBar(
                title = "Monitoring Live",
                onDisconnect = onDisconnect,
                onHistory = onViewHistory,
                onThresholds = onEditThresholds
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompactGaugeCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Thermostat,
                    label = "Temp",
                    value = temp?.let { "%.1f".format(it) } ?: "--",
                    unit = "°C",
                    quality = tempQuality
                )

                CompactGaugeCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.WaterDrop,
                    label = "Hum",
                    value = hum?.let { "%.0f".format(it) } ?: "--",
                    unit = "%",
                    quality = humQuality
                )
            }

            AirQualityCompactCard(
                pm25 = pm25,
                pm10 = pm10,
                quality = airQuality,
                modifier = Modifier.fillMaxWidth()
            )

            MiniSparklineSection(
                tempHistory = BLEState.tempHistory.takeLast(40),
                humHistory = BLEState.humHistory.takeLast(40),
                pm25History = BLEState.pm25History.takeLast(40),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            LastUpdateBar(
                lastUpdate = BLEState.lastUpdate,
                isConnected = BLEState.isConnected
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTopBar(
    title: String,
    onDisconnect: () -> Unit,
    onHistory: () -> Unit,
    onThresholds: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, null) }
            IconButton(onClick = onThresholds) { Icon(Icons.Default.Warning, null) }
        }
    )
}

@Composable
private fun CompactGaugeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    unit: String,
    quality: Quality,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (quality) {
            Quality.Good -> GoodGreen
            Quality.Warning -> WarningOrange
            Quality.Bad -> DangerRed
            else -> NeutralGray
        },
        animationSpec = tween(700),
        label = "gaugeColor"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                lineHeight = 28.sp
            )
            Text(text = unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AirQualityCompactCard(
    pm25: Double?,
    pm10: Double?,
    quality: Quality,
    modifier: Modifier = Modifier
) {
    val bgAlpha = when (quality) {
        Quality.Good -> 0.10f
        Quality.Warning -> 0.14f
        Quality.Bad -> 0.18f
        else -> 0.06f
    }
    val textColor = when (quality) {
        Quality.Good -> GoodGreen
        Quality.Warning -> WarningOrange
        Quality.Bad -> DangerRed
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bgColor = textColor.copy(alpha = bgAlpha)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Cloud, null, tint = textColor, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (quality) {
                        Quality.Good -> "Air EXCELLENT"
                        Quality.Warning -> "Air MOYEN"
                        Quality.Bad -> "Air MAUVAIS"
                        else -> "Qualite inconnue"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("PM2.5: ${pm25?.toInt() ?: 0} ug/m3", fontSize = 12.sp, color = textColor)
                    Text("PM10: ${pm10?.toInt() ?: 0} ug/m3", fontSize = 12.sp, color = textColor)
                }
            }
        }
    }
}

@Composable
private fun MiniSparklineSection(
    tempHistory: List<Pair<Long, Double>>,
    humHistory: List<Pair<Long, Double>>,
    pm25History: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Tendance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Sparkline(tempHistory.map { it.second.toFloat() }, Color(0xFF4D7CFE))
            Spacer(Modifier.height(6.dp))
            Sparkline(humHistory.map { it.second.toFloat() }, Color(0xFF00ACC1))
            Spacer(Modifier.height(6.dp))
            Sparkline(pm25History.map { it.second.toFloat() }, Color(0xFF7E57C2))
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color) {
    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(26.dp)) {
        drawSparkline(values, color)
    }
}

private fun DrawScope.drawSparkline(values: List<Float>, color: Color) {
    if (values.isEmpty()) return
    val maxValue = max(values.maxOrNull() ?: 1f, 1f)
    val stepX = size.width / max(values.size - 1, 1)
    val path = Path()
    values.forEachIndexed { index, v ->
        val x = index * stepX
        val y = size.height - (v / maxValue) * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
}

@Composable
private fun LastUpdateBar(lastUpdate: Long?, isConnected: Boolean) {
    val status = if (isConnected) "Connecte" else "Hors ligne"
    val time = lastUpdate?.let { "Maj: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(it))}" }
        ?: "Maj: --"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
