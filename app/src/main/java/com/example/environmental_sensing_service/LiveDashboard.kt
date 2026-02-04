package com.example.environmental_sensing_service

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// ============================================================================
// CONSTANTS & CONFIGURATION
// ============================================================================

object DashboardConstants {
    const val MAX_HISTORY_POINTS = 40
    const val SPARKLINE_HEIGHT_DP = 70
    const val CARD_ELEVATION_DP = 2
    const val ANIMATION_DURATION_MS = 600
    const val STATUS_CHIP_BORDER_ALPHA = 0.35f
    const val STATUS_CHIP_BACKGROUND_ALPHA = 0.12f
    const val DISCONNECTED_ALPHA = 0.5f
    const val SPARKLINE_STROKE_WIDTH_DP = 4
    const val GRID_LINE_ALPHA = 0.06f
}

object AirQualityThresholds {
    const val PM25_GOOD = 12.0
    const val PM25_WARNING = 35.0
    const val PM10_GOOD = 20.0
    const val PM10_WARNING = 50.0
    const val FALLBACK_VALUE = 999.0
}

// ============================================================================
// THEME & COLORS
// ============================================================================

object AppColors {
    val GoodGreen = Color(0xFF22C55E)
    val WarningOrange = Color(0xFFF59E0B)
    val DangerRed = Color(0xFFEF4444)
    val NeutralGray = Color(0xFF6B7280)
    val SoftSurface = Color(0xFFF8FAFC)
    val CardBorder = Color(0xFFE5E7EB)
}

enum class LiveQuality {
    Good, Warning, Bad, Unknown;

    val labelRes: Int
        get() = when (this) {
            Good -> R.string.quality_good
            Warning -> R.string.quality_warning
            Bad -> R.string.quality_bad
            Unknown -> R.string.quality_unknown
        }

    val color: Color
        get() = when (this) {
            Good -> AppColors.GoodGreen
            Warning -> AppColors.WarningOrange
            Bad -> AppColors.DangerRed
            Unknown -> AppColors.NeutralGray
        }
}

// ============================================================================
// DATA CLASSES & STATE
// ============================================================================

// ============================================================================
// QUALITY EVALUATION
// ============================================================================

interface QualityEvaluator {
    fun evaluate(value: Double?): LiveQuality
}

class ThresholdQualityEvaluator(private val thresholds: Thresholds) : QualityEvaluator {
    override fun evaluate(value: Double?): LiveQuality = when {
        value == null -> LiveQuality.Unknown
        value < thresholds.warnMin.toDouble() || value > thresholds.warnMax.toDouble() -> LiveQuality.Bad
        value < thresholds.goodMin.toDouble() || value > thresholds.goodMax.toDouble() -> LiveQuality.Warning
        else -> LiveQuality.Good
    }
}

object AirQualityEvaluator {
    fun evaluate(pm25: Double?, pm10: Double?): LiveQuality {
        val safePm25 = pm25 ?: AirQualityThresholds.FALLBACK_VALUE
        val safePm10 = pm10 ?: AirQualityThresholds.FALLBACK_VALUE

        return when {
            safePm25 <= AirQualityThresholds.PM25_GOOD &&
                    safePm10 <= AirQualityThresholds.PM10_GOOD -> LiveQuality.Good

            safePm25 <= AirQualityThresholds.PM25_WARNING ||
                    safePm10 <= AirQualityThresholds.PM10_WARNING -> LiveQuality.Warning

            else -> LiveQuality.Bad
        }
    }
}

// ============================================================================
// MAIN DASHBOARD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDashboard(
    onDisconnect: () -> Unit,
    onViewHistory: () -> Unit,
    onEditThresholds: () -> Unit,
    onViewTemp: () -> Unit,
    onViewHum: () -> Unit,
    onViewPm25: () -> Unit,
    onViewPm10: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tempEvaluator = remember(ThresholdState.temperature) {
        ThresholdQualityEvaluator(ThresholdState.temperature)
    }
    val humEvaluator = remember(ThresholdState.humidity) {
        ThresholdQualityEvaluator(ThresholdState.humidity)
    }

    val tempQuality by remember(BLEState.temperature) {
        derivedStateOf { tempEvaluator.evaluate(BLEState.temperature) }
    }
    val humQuality by remember(BLEState.humidity) {
        derivedStateOf { humEvaluator.evaluate(BLEState.humidity) }
    }
    val airQuality by remember(BLEState.pm25, BLEState.pm10) {
        derivedStateOf { AirQualityEvaluator.evaluate(BLEState.pm25, BLEState.pm10) }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppColors.SoftSurface
    ) {
        Scaffold(
            containerColor = AppColors.SoftSurface,
            topBar = {
                DashboardTopBar(
                    title = stringResource(R.string.dashboard_title),
                    subtitle = stringResource(R.string.device_info),
                    isConnected = BLEState.isConnected,
                    isPolling = BLEState.isPolling,
                    onDisconnect = onDisconnect,
                    onHistory = onViewHistory,
                    onThresholds = onEditThresholds
                )
            }
        ) { innerPadding ->
            DashboardContent(
                temp = BLEState.temperature,
                hum = BLEState.humidity,
                pm25 = BLEState.pm25,
                pm10 = BLEState.pm10,
                tempQuality = tempQuality,
                humQuality = humQuality,
                airQuality = airQuality,
                isConnected = BLEState.isConnected,
                onViewTemp = onViewTemp,
                onViewHum = onViewHum,
                onViewPm25 = onViewPm25,
                onViewPm10 = onViewPm10,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
private fun DashboardContent(
    temp: Double?,
    hum: Double?,
    pm25: Double?,
    pm10: Double?,
    tempQuality: LiveQuality,
    humQuality: LiveQuality,
    airQuality: LiveQuality,
    isConnected: Boolean,
    onViewTemp: () -> Unit,
    onViewHum: () -> Unit,
    onViewPm25: () -> Unit,
    onViewPm10: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))

        // Temperature & Humidity Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GaugeCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Thermostat,
                iconContentDesc = stringResource(R.string.temperature_icon_desc),
                label = stringResource(R.string.temperature_label),
                value = formatValue(temp, "%.1f"),
                unit = stringResource(R.string.temperature_unit),
                quality = tempQuality,
                isConnected = isConnected,
                onView = onViewTemp,
                viewLabel = stringResource(R.string.details_button)
            )
            GaugeCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.WaterDrop,
                iconContentDesc = stringResource(R.string.humidity_icon_desc),
                label = stringResource(R.string.humidity_label),
                value = formatValue(hum, "%.0f"),
                unit = stringResource(R.string.humidity_unit),
                quality = humQuality,
                isConnected = isConnected,
                onView = onViewHum,
                viewLabel = stringResource(R.string.details_button)
            )
        }

        Spacer(Modifier.height(30.dp))

        // Air Quality Card
        AirQualityCard(
            pm25 = pm25,
            pm10 = pm10,
            quality = airQuality,
            isConnected = isConnected,
            modifier = Modifier.fillMaxWidth(),
            onViewPm25 = onViewPm25,
            onViewPm10 = onViewPm10
        )

        Spacer(Modifier.height(30.dp))

        // Sparkline Section
        SparklineSection(
            tempHistory = BLEState.tempHistory.takeLast(DashboardConstants.MAX_HISTORY_POINTS),
            humHistory = BLEState.humHistory.takeLast(DashboardConstants.MAX_HISTORY_POINTS),
            pm25History = BLEState.pm25History.takeLast(DashboardConstants.MAX_HISTORY_POINTS),
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
        )

        Spacer(Modifier.height(30.dp))

        // Status Bar
        StatusBar(
            lastUpdate = BLEState.lastUpdate,
            isConnected = isConnected,
            isPolling = BLEState.isPolling
        )
    }
}

// ============================================================================
// TOP BAR
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    title: String,
    subtitle: String,
    isConnected: Boolean,
    isPolling: Boolean,
    onDisconnect: () -> Unit,
    onHistory: () -> Unit,
    onThresholds: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onDisconnect,
                modifier = Modifier.semantics {
                    contentDescription = "Disconnect"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        actions = {
            ConnectionStatusChip(
                isConnected = isConnected,
                isPolling = isPolling
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onHistory,
                modifier = Modifier.semantics {
                    contentDescription = "View history"
                }
            ) {
                Icon(Icons.Default.History, contentDescription = null)
            }
            IconButton(
                onClick = onThresholds,
                modifier = Modifier.semantics {
                    contentDescription = "Edit thresholds"
                }
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
        }
    )
}

@Composable
private fun ConnectionStatusChip(
    isConnected: Boolean,
    isPolling: Boolean
) {
    val label = when {
        !isConnected -> stringResource(R.string.status_offline)
        isPolling -> stringResource(R.string.status_live_polling)
        else -> stringResource(R.string.status_live_notify)
    }
    val color = if (isConnected) AppColors.GoodGreen else AppColors.DangerRed

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = DashboardConstants.STATUS_CHIP_BACKGROUND_ALPHA),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            color.copy(alpha = DashboardConstants.STATUS_CHIP_BORDER_ALPHA)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

// ============================================================================
// CARDS
// ============================================================================

@Composable
private fun GaugeCard(
    icon: ImageVector,
    iconContentDesc: String,
    label: String,
    value: String,
    unit: String,
    quality: LiveQuality,
    isConnected: Boolean,
    onView: () -> Unit,
    viewLabel: String,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = quality.color,
        animationSpec = tween(DashboardConstants.ANIMATION_DURATION_MS),
        label = "gaugeColorAnimation"
    )

    val contentAlpha = if (isConnected) 1f else DashboardConstants.DISCONNECTED_ALPHA

    Card(
        modifier = modifier
            .height(142.dp)
            .alpha(contentAlpha),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(DashboardConstants.CARD_ELEVATION_DP.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxHeight()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconSurface(
                    icon = icon,
                    contentDescription = iconContentDesc,
                    tint = animatedColor
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$value $unit",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QualityBadge(quality = quality)
                TextButton(onClick = onView) {
                    Text(viewLabel)
                }
            }
        }
    }
}

@Composable
private fun IconSurface(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = DashboardConstants.STATUS_CHIP_BACKGROUND_ALPHA),
        modifier = modifier.size(42.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun QualityBadge(quality: LiveQuality) {
    val text = when (quality) {
        LiveQuality.Good -> stringResource(R.string.badge_ok)
        LiveQuality.Warning -> stringResource(R.string.badge_warning)
        LiveQuality.Bad -> stringResource(R.string.badge_critical)
        LiveQuality.Unknown -> stringResource(R.string.badge_na)
    }
    val color = quality.color

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = DashboardConstants.STATUS_CHIP_BACKGROUND_ALPHA),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            color.copy(alpha = 0.30f)
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun AirQualityCard(
    pm25: Double?,
    pm10: Double?,
    quality: LiveQuality,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    onViewPm25: () -> Unit,
    onViewPm10: () -> Unit
) {
    val contentAlpha = if (isConnected) 1f else DashboardConstants.DISCONNECTED_ALPHA
    val qualityText = when (quality) {
        LiveQuality.Good -> stringResource(R.string.air_quality_excellent)
        LiveQuality.Warning -> stringResource(R.string.air_quality_average)
        LiveQuality.Bad -> stringResource(R.string.air_quality_poor)
        LiveQuality.Unknown -> stringResource(R.string.air_quality_unknown)
    }

    Card(
        modifier = modifier.alpha(contentAlpha),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(DashboardConstants.CARD_ELEVATION_DP.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            AirQualityHeader(
                quality = quality,
                qualityText = qualityText
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricBox(
                    label = stringResource(R.string.pm25_label),
                    value = formatValue(pm25, "%.0f"),
                    unit = stringResource(R.string.pm_unit),
                    onClick = onViewPm25,
                    modifier = Modifier.weight(1f)
                )
                MetricBox(
                    label = stringResource(R.string.pm10_label),
                    value = formatValue(pm10, "%.0f"),
                    unit = stringResource(R.string.pm_unit),
                    onClick = onViewPm10,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AirQualityHeader(
    quality: LiveQuality,
    qualityText: String
) {
    val color = quality.color

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = color.copy(alpha = DashboardConstants.STATUS_CHIP_BACKGROUND_ALPHA),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = stringResource(R.string.air_quality_icon_desc),
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.air_quality_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = qualityText,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }

        QualityBadge(quality = quality)
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    unit: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = AppColors.SoftSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$value $unit",
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.ArrowForwardIos,
                    contentDescription = stringResource(R.string.view_details_icon_desc),
                    tint = AppColors.NeutralGray
                )
            }
        }
    }
}

// ============================================================================
// SPARKLINE SECTION
// ============================================================================

@Composable
private fun SparklineSection(
    tempHistory: List<Pair<Long, Double>>,
    humHistory: List<Pair<Long, Double>>,
    pm25History: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(DashboardConstants.CARD_ELEVATION_DP.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ShowChart,
                    contentDescription = stringResource(R.string.chart_icon_desc),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.recent_evolution_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SparklineCard(
                    points = tempHistory.map { it.second.toFloat() },
                    label = stringResource(R.string.sparkline_temp_label),
                    modifier = Modifier.weight(1f)
                )
                SparklineCard(
                    points = humHistory.map { it.second.toFloat() },
                    label = stringResource(R.string.sparkline_hum_label),
                    modifier = Modifier.weight(1f)
                )
                SparklineCard(
                    points = pm25History.map { it.second.toFloat() },
                    label = stringResource(R.string.sparkline_pm25_label),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SparklineCard(
    points: List<Float>,
    label: String,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = AppColors.SoftSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DashboardConstants.SPARKLINE_HEIGHT_DP.dp),
                contentAlignment = Alignment.Center
            ) {
                if (points.size < 2) {
                    Text(
                        text = "—",
                        color = AppColors.NeutralGray,
                        fontSize = 18.sp
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawSparkline(points, primaryColor)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSparkline(values: List<Float>, color: Color) {
    if (values.isEmpty()) return

    val minValue = values.minOrNull() ?: 0f
    val maxValue = values.maxOrNull() ?: 1f
    val range = max(1f, maxValue - minValue)
    val stepX = size.width / max(values.size - 1, 1)

    // Draw subtle baseline grid
    val gridColor = Color.Black.copy(alpha = DashboardConstants.GRID_LINE_ALPHA)
    listOf(0.25f, 0.50f, 0.75f).forEach { position ->
        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(0f, size.height * position),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height * position),
            strokeWidth = 2f
        )
    }

    // Build and draw the path
    val path = Path().apply {
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height * (1 - (value - minValue) / range)
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color.copy(alpha = 0.85f),
        style = Stroke(
            width = DashboardConstants.SPARKLINE_STROKE_WIDTH_DP.dp.toPx(),
            cap = StrokeCap.Round
        )
    )
}

// ============================================================================
// STATUS BAR
// ============================================================================

@Composable
private fun StatusBar(
    lastUpdate: Long?,
    isConnected: Boolean,
    isPolling: Boolean
) {
    val status = if (isConnected) {
        stringResource(R.string.status_connected)
    } else {
        stringResource(R.string.status_offline_short)
    }
    val mode = if (isPolling) "Polling" else "Notify"

    val updateText = when {
        lastUpdate == null -> stringResource(R.string.no_data)
        isConnected -> stringResource(R.string.last_update, timeAgo(lastUpdate))
        else -> stringResource(R.string.last_update_offline, timeAgo(lastUpdate))
    }

    val containerColor = if (isConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(18.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AppColors.GoodGreen else AppColors.DangerRed)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = updateText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isPolling) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
            Text(
                text = "$status · $mode",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

// ============================================================================
// UTILITIES
// ============================================================================

private fun formatValue(value: Double?, format: String): String {
    return value?.let { format.format(it) } ?: "--"
}

private fun timeAgo(timestamp: Long): String {
    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

// ============================================================================
// PREVIEWS
// ============================================================================

@Preview(showBackground = true, device = "id:pixel_5")
@Composable
private fun LiveDashboardPreview() {
    MaterialTheme {
        LiveDashboard(
            onDisconnect = {},
            onViewHistory = {},
            onEditThresholds = {},
            onViewTemp = {},
            onViewHum = {},
            onViewPm25 = {},
            onViewPm10 = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GaugeCardPreview() {
    MaterialTheme {
        GaugeCard(
            icon = Icons.Default.Thermostat,
            iconContentDesc = "Temperature",
            label = "Température",
            value = "23.5",
            unit = "°C",
            quality = LiveQuality.Good,
            isConnected = true,
            onView = {},
            viewLabel = "Détails"
        )
    }
}
