package com.example.environmental_sensing_service

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.environmental_sensing_service.ui.theme.EnvironmentalSensingServiceTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
class MainActivity : ComponentActivity() {

    private val ESS_SERVICE_UUID =
        UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")

    private val TEMPERATURE_UUID =
        UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")

    private val HUMIDITY_UUID =
        UUID.fromString("00002A6F-0000-1000-8000-00805f9b34fb")

    private val PRESSURE_UUID =
        UUID.fromString("00002A6D-0000-1000-8000-00805f9b34fb")

    private val OXYGEN_UUID =
        UUID.fromString("00002A62-0000-1000-8000-00805f9b34fb")

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )

        setContent {
            EnvironmentalSensingServiceTheme {
                BLEScreen(
                    onDeviceSelected = { device ->
                        connectToDevice(device)
                    },
                    onDisconnect = {
                        disconnectDevice()
                    }
                )
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                BLEDevicesState.addDevice(device)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        BLEDevicesState.connectionStatus = "Connexion en cours..."
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnectDevice() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        BLEDevicesState.resetData()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BLEDevicesState.connectionStatus = "Connecté"
                    BLEDevicesState.isConnected = true
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    BLEDevicesState.connectionStatus = "Déconnecté"
                    BLEDevicesState.isConnected = false
                    BLEDevicesState.resetData()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val essService = gatt.getService(ESS_SERVICE_UUID)

                essService?.let {
                    setupCharacteristic(gatt, it.getCharacteristic(TEMPERATURE_UUID))
                    setupCharacteristic(gatt, it.getCharacteristic(HUMIDITY_UUID))
                    setupCharacteristic(gatt, it.getCharacteristic(PRESSURE_UUID))
                    setupCharacteristic(gatt, it.getCharacteristic(OXYGEN_UUID))
                }
            }
        }

        private fun setupCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.readCharacteristic(it)
                    gatt.setCharacteristicNotification(it, true)

                    val cccd = it.getDescriptor(CCCD_UUID)
                    cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicValue(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(characteristic.uuid, value)
        }
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            TEMPERATURE_UUID -> {
                val tempValue = ByteBuffer.wrap(value)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                BLEDevicesState.temperature = tempValue / 100.0
            }
            HUMIDITY_UUID -> {
                val humidityValue = ByteBuffer.wrap(value)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                BLEDevicesState.humidity = humidityValue / 100.0
            }
            PRESSURE_UUID -> {
                val pressureValue = ByteBuffer.wrap(value)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                BLEDevicesState.pressure = pressureValue / 10.0
            }
            OXYGEN_UUID -> {
                val oxygenValue = ByteBuffer.wrap(value)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                BLEDevicesState.oxygen = oxygenValue / 100.0
            }
        }
    }
}

object BLEDevicesState {
    var devices = mutableStateListOf<BluetoothDevice>()
    var temperature by mutableStateOf<Double?>(null)
    var humidity by mutableStateOf<Double?>(null)
    var pressure by mutableStateOf<Double?>(null)
    var oxygen by mutableStateOf<Double?>(null)
    var connectionStatus by mutableStateOf("Déconnecté")
    var isConnected by mutableStateOf(false)

    fun addDevice(device: BluetoothDevice) {
        if (!devices.contains(device)) {
            devices.add(device)
        }
    }

    fun resetData() {
        temperature = null
        humidity = null
        pressure = null
        oxygen = null
    }
}

@Composable
fun BLEScreen(
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    var showDeviceList by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (showDeviceList && !BLEDevicesState.isConnected) {
            DeviceListScreen(
                onDeviceSelected = { device ->
                    onDeviceSelected(device)
                    showDeviceList = false
                }
            )
        } else {
            DashboardScreen(
                onDisconnect = {
                    onDisconnect()
                    showDeviceList = true
                }
            )
        }
    }
}

@Composable
fun DeviceListScreen(onDeviceSelected: (BluetoothDevice) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Capteurs Disponibles",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Sélectionnez un périphérique pour commencer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        if (BLEDevicesState.devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Recherche de périphériques...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(BLEDevicesState.devices.size) { index ->
                    val device = BLEDevicesState.devices[index]
                    DeviceCard(device = device, onClick = { onDeviceSelected(device) })
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(16.dp))

            Column {
                Text(
                    text = device.name ?: "Périphérique Inconnu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Monitoring Environnemental",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (BLEDevicesState.isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = BLEDevicesState.connectionStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Déconnecter",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Cartes de données
        SensorCard(
            title = "Température",
            value = BLEDevicesState.temperature?.let { "%.1f".format(it) } ?: "--",
            unit = "°C",
            icon = Icons.Default.Thermostat,
            color = Color(0xFFFF6B6B),
            status = getTemperatureStatus(BLEDevicesState.temperature)
        )

        Spacer(Modifier.height(16.dp))

        SensorCard(
            title = "Humidité",
            value = BLEDevicesState.humidity?.let { "%.1f".format(it) } ?: "--",
            unit = "%",
            icon = Icons.Default.Water,
            color = Color(0xFF4ECDC4),
            status = getHumidityStatus(BLEDevicesState.humidity)
        )

        Spacer(Modifier.height(16.dp))

        SensorCard(
            title = "Pression",
            value = BLEDevicesState.pressure?.let { "%.1f".format(it) } ?: "--",
            unit = "hPa",
            icon = Icons.Default.Speed,
            color = Color(0xFF95E1D3),
            status = getPressureStatus(BLEDevicesState.pressure)
        )

        Spacer(Modifier.height(16.dp))

        SensorCard(
            title = "Oxygène",
            value = BLEDevicesState.oxygen?.let { "%.1f".format(it) } ?: "--",
            unit = "%",
            icon = Icons.Default.Air,
            color = Color(0xFF5DADE2),
            status = getOxygenStatus(BLEDevicesState.oxygen)
        )
    }
}

@Composable
fun SensorCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color,
    status: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        color.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

fun getTemperatureStatus(temp: Double?): String {
    return when {
        temp == null -> "En attente..."
        temp < 15 -> "Froid"
        temp < 22 -> "Confortable"
        temp < 28 -> "Chaud"
        else -> "Très chaud"
    }
}

fun getHumidityStatus(humidity: Double?): String {
    return when {
        humidity == null -> "En attente..."
        humidity < 30 -> "Sec"
        humidity < 60 -> "Optimal"
        else -> "Humide"
    }
}

fun getPressureStatus(pressure: Double?): String {
    return when {
        pressure == null -> "En attente..."
        pressure < 1000 -> "Basse pression"
        pressure < 1020 -> "Normal"
        else -> "Haute pression"
    }
}

fun getOxygenStatus(oxygen: Double?): String {
    return when {
        oxygen == null -> "En attente..."
        oxygen < 19 -> "Faible"
        oxygen < 23 -> "Normal"
        else -> "Élevé"
    }
}