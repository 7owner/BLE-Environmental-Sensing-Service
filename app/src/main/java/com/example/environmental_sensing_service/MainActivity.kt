package com.example.environmental_sensing_service

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.app.ActivityCompat
import com.example.environmental_sensing_service.ui.theme.EnvironmentalSensingServiceTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.ArrayDeque

// Charts
import co.yml.charts.axis.AxisData
import co.yml.charts.axis.Gravity
import co.yml.charts.common.model.Point
import co.yml.charts.ui.linechart.LineChart
import co.yml.charts.ui.linechart.model.*

/* ================= DATA ================= */

data class Stats(val min: Double, val max: Double, val avg: Double)

/* ================= NAV ================= */

sealed class Screen {
    object Ble : Screen()
    object Live : Screen()
    object History : Screen()
    object Thresholds : Screen()
}

/* ================= ACTIVITY ================= */

class MainActivity : ComponentActivity() {

    private val ESS_UUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
    private val TEMP_UUID = UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")
    private val HUM_UUID = UUID.fromString("00002A6F-0000-1000-8000-00805f9b34fb")
    private val PRESS_UUID = UUID.fromString("00002A6D-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val TIME_SERVICE_UUID = UUID.fromString("0000FF10-0000-1000-8000-00805f9b34fb")
    private val TIME_UUID = UUID.fromString("0000FF11-0000-1000-8000-00805f9b34fb")
    private val DATA_SERVICE_UUID = UUID.fromString("0000FF20-0000-1000-8000-00805f9b34fb")
    private val DATA_REQ_UUID = UUID.fromString("0000FF21-0000-1000-8000-00805f9b34fb")
    private val DATA_CHUNK_UUID = UUID.fromString("0000FF22-0000-1000-8000-00805f9b34fb")

    private val FILE = "sensor_data.csv"

    private lateinit var scanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var writing = false
    private var dataOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!getFileStreamPath(FILE).exists()) {
            openFileOutput(FILE, Context.MODE_APPEND)
                .write("timestamp,temp,hum,press\n".toByteArray())
        }
        scanner = getSystemService(BluetoothManager::class.java)
            .adapter.bluetoothLeScanner

        requestPermissions()

        setContent {
            EnvironmentalSensingServiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Ble) }

                    when (screen) {
                        Screen.Ble -> BLEScreen(
                            onDeviceSelected = {
                                stopScan()
                                connect(it)
                            },
                            onOpenLive = { screen = Screen.Live },
                            onStartScan = { startScan() },
                            onStopScan = { stopScan() }
                        )
                        Screen.Live -> Dashboard(
                            onDisconnect = {
                                disconnect()
                                startScan()
                            },
                            onViewHistory = { screen = Screen.History },
                            onStartScan = { startScan() },
                            onStopScan = { stopScan() },
                            onEditThresholds = { screen = Screen.Thresholds }
                        )

                        Screen.History -> HistoryScreen(
                            onBack = { screen = Screen.Ble },
                            onEditThresholds = { screen = Screen.Thresholds }
                        )
                        Screen.Thresholds -> ThresholdsScreen { screen = Screen.Ble }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    private fun has(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        if (has(Manifest.permission.BLUETOOTH_SCAN)) {
            try {
                scanner.startScan(scanCallback)
                BLEState.isScanning = true
            } catch (e: SecurityException) {
                BLEState.reset()
            }
        }
    }

    private fun stopScan() {
        if (has(Manifest.permission.BLUETOOTH_SCAN)) {
            try {
                scanner.stopScan(scanCallback)
                BLEState.isScanning = false
            } catch (e: SecurityException) {
                BLEState.reset()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
            try {
                BLEState.addDevice(r.device)
            } catch (e: SecurityException) {
                BLEState.reset()
            }
        }
    }

    private fun connect(d: BluetoothDevice) {
        if (has(Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                gatt = d.connectGatt(this, false, gattCallback)
            } catch (e: SecurityException) {
                BLEState.reset()
            }
        }
    }

    private fun disconnect() {
        if (has(Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (e: SecurityException) {
                BLEState.reset()
            }
        }
        gatt = null
        BLEState.reset()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
            if (n == BluetoothProfile.STATE_CONNECTED) {
                BLEState.isConnected = true
                if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
                try {
                    g.discoverServices()
                } catch (e: SecurityException) {
                    BLEState.reset()
                }
            } else BLEState.reset()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
            val ess = g.getService(ESS_UUID) ?: return
            listOf(TEMP_UUID, HUM_UUID, PRESS_UUID).forEach {
                val c = ess.getCharacteristic(it) ?: return@forEach
                try {
                    g.setCharacteristicNotification(c, true)
                    c.getDescriptor(CCCD_UUID)?.apply {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        cccdQueue.add(this)
                    }
                } catch (e: SecurityException) {
                    BLEState.reset()
                }
            }
            val dataService = g.getService(DATA_SERVICE_UUID)
            val dataChar = dataService?.getCharacteristic(DATA_CHUNK_UUID)
            if (dataChar != null) {
                try {
                    g.setCharacteristicNotification(dataChar, true)
                    dataChar.getDescriptor(CCCD_UUID)?.apply {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        cccdQueue.add(this)
                    }
                } catch (e: SecurityException) {
                    BLEState.reset()
                }
            }
            syncTime(g)
            requestData(g, 0)
            writeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
            writing = false
            writeNext(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            v: ByteArray
        ) {
            if (c.uuid == DATA_CHUNK_UUID) {
                appendChunk(v)
                return
            }
            val bb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
            when (c.uuid) {
                TEMP_UUID -> BLEState.addTemp(bb.short / 100.0)
                HUM_UUID -> BLEState.addHum(bb.short / 100.0)
                PRESS_UUID -> BLEState.pressure = bb.int / 100.0
            }
            save(System.currentTimeMillis())
        }
    }

    private fun writeNext(g: BluetoothGatt) {
        if (writing) return
        val d = cccdQueue.poll() ?: return
        writing = true
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            g.writeDescriptor(d)
        } catch (e: SecurityException) {
            BLEState.reset()
        }
    }

    private fun syncTime(g: BluetoothGatt) {
        val timeService = g.getService(TIME_SERVICE_UUID) ?: return
        val timeChar = timeService.getCharacteristic(TIME_UUID) ?: return
        val epoch = (System.currentTimeMillis() / 1000L).toInt()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(epoch).array()
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            timeChar.value = payload
            g.writeCharacteristic(timeChar)
        } catch (e: SecurityException) {
            BLEState.reset()
        }
    }

    private fun requestData(g: BluetoothGatt, offset: Int) {
        val dataService = g.getService(DATA_SERVICE_UUID) ?: return
        val reqChar = dataService.getCharacteristic(DATA_REQ_UUID) ?: return
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(offset).array()
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            reqChar.value = payload
            g.writeCharacteristic(reqChar)
            dataOffset = offset
        } catch (e: SecurityException) {
            BLEState.reset()
        }
    }

    private fun appendChunk(chunk: ByteArray) {
        val line = chunk.toString(Charsets.UTF_8)
        openFileOutput(FILE, Context.MODE_APPEND).use {
            it.write(line.toByteArray())
        }
        dataOffset += chunk.size
    }

    private fun save(t: Long) {
        openFileOutput(FILE, Context.MODE_APPEND).use {
            it.write(
                "$t,${BLEState.temperature},${BLEState.humidity},${BLEState.pressure}\n"
                    .toByteArray()
            )
        }
    }
}

/* ================= STATE ================= */

object BLEState {
    val devices = mutableStateListOf<BluetoothDevice>()
    var temperature by mutableStateOf<Double?>(null)
    var humidity by mutableStateOf<Double?>(null)
    var pressure by mutableStateOf<Double?>(null)
    var isConnected by mutableStateOf(false)
    var isScanning by mutableStateOf(false)

    val tempHistory = mutableStateListOf<Pair<Long, Double>>()
    val humHistory = mutableStateListOf<Pair<Long, Double>>()

    fun addDevice(d: BluetoothDevice) {
        if (!devices.contains(d)) devices.add(d)
    }

    fun addTemp(v: Double) {
        temperature = v
        tempHistory.add(System.currentTimeMillis() to v)
    }

    fun addHum(v: Double) {
        humidity = v
        humHistory.add(System.currentTimeMillis() to v)
    }

    fun reset() {
        devices.clear()
        tempHistory.clear()
        humHistory.clear()
        temperature = null
        humidity = null
        pressure = null
        isConnected = false
        isScanning = false
    }
}

/* ================= UI ================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BLEScreen(
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onOpenLive: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (BLEState.isConnected) "Live" else "Appareils",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            DeviceList(onDeviceSelected, onStartScan, onStopScan, onOpenLive)
        }
    }
}

@Composable
fun DeviceList(
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onOpenLive: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = "Selectionner un capteur",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onStartScan,
                modifier = Modifier.weight(1f),
                enabled = !BLEState.isScanning
            ) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Scanner")
            }
            OutlinedButton(
                onClick = onStopScan,
                modifier = Modifier.weight(1f),
                enabled = BLEState.isScanning
            ) {
                Text("Stop")
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenLive, modifier = Modifier.fillMaxWidth()) {
            Text("Ouvrir le live")
        }
        if (BLEState.isScanning) {
            Spacer(Modifier.height(10.dp))
            AssistChip(
                onClick = {},
                label = { Text("Scanning...") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(BLEState.devices.size) {
                val d = BLEState.devices[it]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(d) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            d.name ?: "Capteur BLE",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            d.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Dashboard(
    onDisconnect: () -> Unit,
    onViewHistory: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onEditThresholds: () -> Unit
) {
    var analysis by remember { mutableStateOf<AnalysisTarget?>(null) }

    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Resume instantane",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onStartScan,
                modifier = Modifier.weight(1f),
                enabled = !BLEState.isScanning
            ) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Scanner")
            }
            OutlinedButton(
                onClick = onStopScan,
                modifier = Modifier.weight(1f),
                enabled = BLEState.isScanning
            ) {
                Text("Stop")
            }
        }
        if (BLEState.isScanning) {
            val pulse = rememberInfiniteTransition(label = "scanPulse")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "scanPulseScale"
            )
            Spacer(Modifier.height(10.dp))
            AssistChip(
                onClick = {},
                label = { Text("Scanning...") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.scale(scale)
            )
        }
        Spacer(Modifier.height(12.dp))
        Sensor(
            label = "Temperature",
            icon = Icons.Filled.Thermostat,
            v = BLEState.temperature,
            unit = "C",
            onView = { analysis = AnalysisTarget.Temperature }
        )
        Sensor(
            label = "Humidite",
            icon = Icons.Filled.WaterDrop,
            v = BLEState.humidity,
            unit = "%",
            onView = { analysis = AnalysisTarget.Humidity }
        )
        Sensor(
            label = "Pression",
            icon = Icons.Filled.Speed,
            v = BLEState.pressure,
            unit = "hPa",
            onView = { analysis = AnalysisTarget.Pressure }
        )
        LiveOverlayChart(
            buildPointsFromHistory(BLEState.tempHistory),
            buildPointsFromHistory(BLEState.humHistory)
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.PowerSettingsNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Deconnecter")
            }
            Button(
                onClick = onViewHistory,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.History, null)
                Spacer(Modifier.width(8.dp))
                Text("Historique")
            }
        }
    }

    analysis?.let { target ->
        AnalysisDialog(
            target = target,
            onDismiss = { analysis = null },
            onEditThresholds = onEditThresholds
        )
    }
}

@Composable
fun Sensor(
    label: String,
    icon: ImageVector,
    v: Double?,
    unit: String,
    onView: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                v?.let { "%.2f".format(it) } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onView) {
                Icon(Icons.Filled.ShowChart, null)
                Spacer(Modifier.width(6.dp))
                Text("Voir")
            }
        }
    }
}

enum class AnalysisTarget {
    Temperature,
    Humidity,
    Pressure
}

@Composable
fun AnalysisDialog(
    target: AnalysisTarget,
    onDismiss: () -> Unit,
    onEditThresholds: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            val title = when (target) {
                AnalysisTarget.Temperature -> "Temperature"
                AnalysisTarget.Humidity -> "Humidite"
                AnalysisTarget.Pressure -> "Pression"
            }
            val unit = when (target) {
                AnalysisTarget.Temperature -> "C"
                AnalysisTarget.Humidity -> "%"
                AnalysisTarget.Pressure -> "hPa"
            }
            val value = when (target) {
                AnalysisTarget.Temperature -> BLEState.temperature
                AnalysisTarget.Humidity -> BLEState.humidity
                AnalysisTarget.Pressure -> BLEState.pressure
            }
            val points = when (target) {
                AnalysisTarget.Temperature -> buildPointsFromHistory(BLEState.tempHistory)
                AnalysisTarget.Humidity -> buildPointsFromHistory(BLEState.humHistory)
                AnalysisTarget.Pressure -> emptyList()
            }

            Column(
                Modifier
                    .padding(20.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface
                            )
                        ),
                        shape = MaterialTheme.shapes.large
                    )
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = {},
                        label = { Text("Live") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (target == AnalysisTarget.Temperature || target == AnalysisTarget.Humidity) {
                    val quality = when (target) {
                        AnalysisTarget.Temperature -> qualityForTemperature(value)
                        AnalysisTarget.Humidity -> qualityForHumidity(value)
                        else -> Quality.Medium
                    }
                    val gaugeRange = if (target == AnalysisTarget.Temperature) 10f..35f else 20f..80f
                    HealthGauge(value, unit, gaugeRange, quality)
                    Spacer(Modifier.height(12.dp))
                    MiniBars(points)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            value?.let { "%.2f".format(it) } ?: "--",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (points.isNotEmpty()) {
                        LiveOverlayChart(points, emptyList())
                    } else {
                        Text(
                            text = "Pas assez de donnees",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onEditThresholds,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Editer seuils")
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Fermer")
                    }
                }
            }
        }
    }
}

enum class Quality {
    Good,
    Medium,
    Bad
}

data class Thresholds(
    val goodMin: Float,
    val goodMax: Float,
    val warnMin: Float,
    val warnMax: Float
)

object ThresholdState {
    var temperature by mutableStateOf(Thresholds(20f, 24f, 18f, 26f))
    var humidity by mutableStateOf(Thresholds(40f, 60f, 30f, 70f))
}

fun qualityForTemperature(value: Double?): Quality = qualityFor(value, ThresholdState.temperature)

fun qualityForHumidity(value: Double?): Quality = qualityFor(value, ThresholdState.humidity)

fun qualityFor(value: Double?, thresholds: Thresholds): Quality = when {
    value == null -> Quality.Medium
    value < thresholds.warnMin || value > thresholds.warnMax -> Quality.Bad
    value < thresholds.goodMin || value > thresholds.goodMax -> Quality.Medium
    else -> Quality.Good
}

@Composable
fun HealthGauge(value: Double?, unit: String, range: ClosedFloatingPointRange<Float>, quality: Quality) {
    val normalized = if (value == null) 0f else {
        ((value.toFloat() - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    }
    val color = when (quality) {
        Quality.Good -> Color(0xFF2ECC71)
        Quality.Medium -> Color(0xFFF4B000)
        Quality.Bad -> Color(0xFFE74C3C)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 20f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val startAngle = 135f
            val sweep = 270f
            drawArc(
                color = Color(0xFFE8ECF4),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep * normalized,
                useCenter = false,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value?.let { "%.1f".format(it) } ?: "--",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(unit, style = MaterialTheme.typography.titleMedium, color = color)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("${range.start.toInt()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${((range.start + range.endInclusive) / 2).toInt()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${range.endInclusive.toInt()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MiniBars(points: List<Point>) {
    if (points.isEmpty()) {
        Text(
            text = "Pas assez de donnees",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val values = points.takeLast(24).map { it.y }
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val barWidth = size.width / (values.size * 1.5f)
        val gap = barWidth / 2f
        values.forEachIndexed { index, v ->
            val height = (v / max) * size.height
            val x = index * (barWidth + gap)
            drawRect(
                color = Color(0xFF4D7CFE),
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("thresholds", Context.MODE_PRIVATE) }
    var temp by remember {
        mutableStateOf(
            Thresholds(
                prefs.getFloat("tempGoodMin", ThresholdState.temperature.goodMin),
                prefs.getFloat("tempGoodMax", ThresholdState.temperature.goodMax),
                prefs.getFloat("tempWarnMin", ThresholdState.temperature.warnMin),
                prefs.getFloat("tempWarnMax", ThresholdState.temperature.warnMax)
            )
        )
    }
    var hum by remember {
        mutableStateOf(
            Thresholds(
                prefs.getFloat("humGoodMin", ThresholdState.humidity.goodMin),
                prefs.getFloat("humGoodMax", ThresholdState.humidity.goodMax),
                prefs.getFloat("humWarnMin", ThresholdState.humidity.warnMin),
                prefs.getFloat("humWarnMax", ThresholdState.humidity.warnMax)
            )
        )
    }

    LaunchedEffect(temp, hum) {
        ThresholdState.temperature = temp
        ThresholdState.humidity = hum
        prefs.edit()
            .putFloat("tempGoodMin", temp.goodMin)
            .putFloat("tempGoodMax", temp.goodMax)
            .putFloat("tempWarnMin", temp.warnMin)
            .putFloat("tempWarnMax", temp.warnMax)
            .putFloat("humGoodMin", hum.goodMin)
            .putFloat("humGoodMax", hum.goodMax)
            .putFloat("humWarnMin", hum.warnMin)
            .putFloat("humWarnMax", hum.warnMax)
            .apply()
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Seuils", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            ThresholdEditor(
                title = "Temperature",
                unit = "C",
                thresholds = temp,
                onChange = { temp = it },
                range = 10f..35f
            )
            Spacer(Modifier.height(16.dp))
            ThresholdEditor(
                title = "Humidite",
                unit = "%",
                thresholds = hum,
                onChange = { hum = it },
                range = 20f..80f
            )
        }
    }
}

@Composable
fun ThresholdEditor(
    title: String,
    unit: String,
    thresholds: Thresholds,
    onChange: (Thresholds) -> Unit,
    range: ClosedFloatingPointRange<Float>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("Bon: ${thresholds.goodMin.toInt()}$unit - ${thresholds.goodMax.toInt()}$unit")
            RangeSlider(
                value = thresholds.goodMin..thresholds.goodMax,
                onValueChange = { values ->
                    val goodMin = values.start.coerceIn(range.start, thresholds.warnMax)
                    val goodMax = values.endInclusive.coerceIn(goodMin, thresholds.warnMax)
                    onChange(thresholds.copy(goodMin = goodMin, goodMax = goodMax))
                },
                valueRange = range
            )
            Spacer(Modifier.height(12.dp))
            Text("Moyen: ${thresholds.warnMin.toInt()}$unit - ${thresholds.warnMax.toInt()}$unit")
            RangeSlider(
                value = thresholds.warnMin..thresholds.warnMax,
                onValueChange = { values ->
                    val warnMin = values.start.coerceIn(range.start, thresholds.goodMin)
                    val warnMax = values.endInclusive.coerceIn(thresholds.goodMax, range.endInclusive)
                    onChange(thresholds.copy(warnMin = warnMin, warnMax = warnMax))
                },
                valueRange = range
            )
        }
    }
}

/* ================= HISTORY ================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onEditThresholds: () -> Unit) {

    val context = LocalContext.current
    var data by remember { mutableStateOf<List<SensorData>>(emptyList()) }
    var analysis by remember { mutableStateOf<AnalysisTarget?>(null) }
    var range by remember { mutableStateOf(HistoryRange.Week) }

    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use {
            it.write("timestamp,temp,hum,press\n".toByteArray())
            data.forEach { d -> it.write((d.toCsv() + "\n").toByteArray()) }
        }
        Toast.makeText(context, "CSV exporté ✔", Toast.LENGTH_SHORT).show()
    }

    fun load() {
        val list = mutableListOf<SensorData>()
        context.openFileInput("sensor_data.csv").use {
            BufferedReader(InputStreamReader(it)).readLines().drop(1).forEach { l ->
                val p = l.split(",")
                if (p.size >= 4)
                    list.add(
                        SensorData(
                            p[0].toLong(),
                            p[1].toDoubleOrNull(),
                            p[2].toDoubleOrNull(),
                            p[3].toDoubleOrNull()
                        )
                    )
            }
        }
        data = list
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Historique", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) {
        Column(
            Modifier.padding(it).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Apercu",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { load() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Charger")
                }
                Button(
                    onClick = { exportCsv.launch("sensor_export.csv") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.History, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Exporter")
                }
            }

            if (false) {
            Button(onClick = { load() }) { Text("Charger données") }
            Button(onClick = { exportCsv.launch("sensor_export.csv") }) { Text("Exporter CSV") }
            }

            RangeSelector(selected = range, onSelected = { range = it })
            Spacer(Modifier.height(12.dp))

            val filtered = filterByRange(data, range)
            val temps = filtered.mapNotNull { it.temperature }
            val hums = filtered.mapNotNull { it.humidity }

            computeStats(temps)?.let {
                StatsCard(
                    "Temperature",
                    "C",
                    it,
                    Color.Red,
                    onView = { analysis = AnalysisTarget.Temperature }
                )
            }
            computeStats(hums)?.let {
                StatsCard(
                    "Humidite",
                    "%",
                    it,
                    Color.Blue,
                    onView = { analysis = AnalysisTarget.Humidity }
                )
            }

            val baseTimestamp = filtered.firstOrNull()?.timestamp
            OverlayChart(
                buildPoints(filtered) { it.temperature },
                buildPoints(filtered) { it.humidity },
                baseTimestamp
            )
        }
    }

    analysis?.let { target ->
        AnalysisDialog(
            target = target,
            onDismiss = { analysis = null },
            onEditThresholds = onEditThresholds
        )
    }
}

enum class HistoryRange {
    Day,
    Week,
    Month
}

@Composable
fun RangeSelector(selected: HistoryRange, onSelected: (HistoryRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryRange.values().forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = {
                    Text(
                        when (option) {
                            HistoryRange.Day -> "Jour"
                            HistoryRange.Week -> "Semaine"
                            HistoryRange.Month -> "Mois"
                        }
                    )
                }
            )
        }
    }
}

fun filterByRange(data: List<SensorData>, range: HistoryRange): List<SensorData> {
    if (data.isEmpty()) return data
    val latest = data.maxOf { it.timestamp }
    val cal = Calendar.getInstance()
    cal.timeInMillis = latest
    when (range) {
        HistoryRange.Day -> cal.add(Calendar.DAY_OF_YEAR, -1)
        HistoryRange.Week -> cal.add(Calendar.DAY_OF_YEAR, -7)
        HistoryRange.Month -> cal.add(Calendar.MONTH, -1)
    }
    val threshold = cal.timeInMillis
    return data.filter { it.timestamp >= threshold }
}

/* ================= STATS & CHARTS ================= */

fun computeStats(v: List<Double>) =
    if (v.isEmpty()) null else Stats(v.min(), v.max(), v.average())

fun buildPoints(d: List<SensorData>, f: (SensorData) -> Double?): List<Point> {
    if (d.isEmpty()) return emptyList()
    val t0 = d.first().timestamp
    return d.mapNotNull {
        val v = f(it) ?: return@mapNotNull null
        Point(((it.timestamp - t0) / 60000f), v.toFloat())
    }
}

fun buildPointsFromHistory(d: List<Pair<Long, Double>>): List<Point> {
    if (d.isEmpty()) return emptyList()
    val t0 = d.first().first
    return d.map {
        Point(((it.first - t0) / 60000f), it.second.toFloat())
    }
}

@Composable
fun StatsCard(
    title: String,
    unit: String,
    s: Stats,
    c: Color,
    onView: (() -> Unit)? = null
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c)
            Spacer(Modifier.height(6.dp))
            Text("Min : ${"%.2f".format(s.min)} $unit", style = MaterialTheme.typography.bodyMedium)
            Text("Max : ${"%.2f".format(s.max)} $unit", style = MaterialTheme.typography.bodyMedium)
            Text("Moyenne : ${"%.2f".format(s.avg)} $unit", style = MaterialTheme.typography.bodyMedium)
            if (onView != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onView) {
                    Icon(Icons.Filled.ShowChart, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Voir")
                }
            }
        }
    }
}

@Composable
fun OverlayChart(t: List<Point>, h: List<Point>, baseTimestamp: Long?) {
    if (t.isEmpty() || h.isEmpty()) return

    val axisSteps = 5
    val xAxisData = buildXAxisData(t + h, baseTimestamp, axisSteps)
    val yAxisData = buildYAxisData(t + h, axisSteps)

    val data = LineChartData(
        linePlotData = LinePlotData(
            lines = listOf(
                Line(t, LineStyle(color = Color.Red)),
                Line(h, LineStyle(color = Color.Blue))
            )
        ),
        xAxisData = xAxisData,
        yAxisData = yAxisData
    )

    Card(Modifier.fillMaxWidth().height(280.dp).padding(8.dp)) {
        LineChart(Modifier.fillMaxSize(), data)
    }
}

@Composable
fun LiveOverlayChart(t: List<Point>, h: List<Point>) {
    if (t.isEmpty() && h.isEmpty()) return

    val baseTimestamp = BLEState.tempHistory.firstOrNull()?.first
        ?: BLEState.humHistory.firstOrNull()?.first
    val axisSteps = 5
    val xAxisData = buildXAxisData(t + h, baseTimestamp, axisSteps)
    val yAxisData = buildYAxisData(t + h, axisSteps)

    val lines = mutableListOf<Line>()
    if (t.isNotEmpty()) lines.add(Line(t, LineStyle(color = Color.Red)))
    if (h.isNotEmpty()) lines.add(Line(h, LineStyle(color = Color.Blue)))

    val data = LineChartData(
        linePlotData = LinePlotData(lines = lines),
        xAxisData = xAxisData,
        yAxisData = yAxisData
    )

    Card(Modifier.fillMaxWidth().height(300.dp).padding(8.dp)) {
        LineChart(Modifier.fillMaxSize(), data)
    }
}

fun buildXAxisData(points: List<Point>, baseTimestamp: Long?, steps: Int): AxisData {
    val minX = points.minOfOrNull { it.x } ?: 0f
    val maxX = points.maxOfOrNull { it.x } ?: minX
    val span = (maxX - minX).takeIf { it > 0f } ?: 1f
    val step = span / steps

    return AxisData.Builder()
        .steps(steps)
        .axisPosition(Gravity.BOTTOM)
        .axisLabelAngle(45f)
        .axisLabelFontSize(12.sp)
        .axisLabelColor(Color.Black)
        .axisLineColor(Color.Black)
        .bottomPadding(12.dp)
        .labelData { index ->
            val value = minX + step * index
            if (baseTimestamp != null) {
                val millis = baseTimestamp + (value * 60000f).toLong()
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
            } else {
                "%.1f".format(value)
            }
        }
        .build()
}

fun buildYAxisData(points: List<Point>, steps: Int): AxisData {
    val minY = points.minOfOrNull { it.y } ?: 0f
    val maxY = points.maxOfOrNull { it.y } ?: minY
    val span = (maxY - minY).takeIf { it > 0f } ?: 1f
    val step = span / steps

    return AxisData.Builder()
        .steps(steps)
        .axisPosition(Gravity.LEFT)
        .axisLabelFontSize(12.sp)
        .axisLabelColor(Color.Black)
        .axisLineColor(Color.Black)
        .startPadding(8.dp)
        .labelData { index ->
            val value = minY + step * index
            "%.1f".format(value)
        }
        .build()
}
