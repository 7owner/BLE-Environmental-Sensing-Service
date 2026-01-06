package com.example.environmental_sensing_service

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.environmental_sensing_service.ui.theme.EnvironmentalSensingServiceTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : ComponentActivity() {

    // ===== UUID BLE ESS =====
    private val ESS_UUID =
        UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
    private val TEMP_UUID =
        UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")
    private val HUM_UUID =
        UUID.fromString("00002A6F-0000-1000-8000-00805f9b34fb")
    private val PRESS_UUID =
        UUID.fromString("00002A6D-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var bleScanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        bleScanner = adapter.bluetoothLeScanner

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bleScanner.startScan(scanCallback)
        }

        setContent {
            EnvironmentalSensingServiceTheme {
                BLEScreen(
                    onDeviceSelected = { device ->
                        bleScanner.stopScan(scanCallback)
                        connect(device)
                    },
                    onDisconnect = {
                        disconnect()
                        bleScanner.startScan(scanCallback)
                    }
                )
            }
        }
    }

    // ===== SCAN CALLBACK =====
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            BLEState.addDevice(result.device)
        }
    }

    // ===== CONNECTION =====
    private fun connect(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        BLEState.status = "Connexion..."
        gatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        BLEState.reset()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BLEState.isConnected = true
                BLEState.status = "Connecté"
                g.discoverServices()
            } else {
                BLEState.reset()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ess = gatt.getService(ESS_UUID) ?: return

            listOf(TEMP_UUID, HUM_UUID, PRESS_UUID).forEach { uuid ->
                ess.getCharacteristic(uuid)?.let { c ->
                    gatt.setCharacteristicNotification(c, true)
                    c.getDescriptor(CCCD_UUID)?.let { d ->
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        cccdQueue.add(d)
                    }
                }
            }

            // Lance l'écriture du PREMIER descripteur
            cccdQueue.poll()?.let { gatt.writeDescriptor(it) }
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val bb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            when (characteristic.uuid) {
                TEMP_UUID -> BLEState.temperature = bb.short / 100.0
                HUM_UUID -> BLEState.humidity = bb.short / 100.0
                PRESS_UUID -> BLEState.pressure = (bb.int / 10.0) / 100.0
            }
        }
    }
}

/* ======================= STATE ======================= */

object BLEState {
    val devices = mutableStateListOf<BluetoothDevice>()
    var temperature by mutableStateOf<Double?>(null)
    var humidity by mutableStateOf<Double?>(null)
    var pressure by mutableStateOf<Double?>(null)
    var status by mutableStateOf("Déconnecté")
    var isConnected by mutableStateOf(false)

    fun addDevice(d: BluetoothDevice) {
        if (!devices.contains(d)) devices.add(d)
    }

    fun reset() {
        devices.clear()
        temperature = null
        humidity = null
        pressure = null
        status = "Déconnecté"
        isConnected = false
    }
}

/* ======================= UI ======================= */

@Composable
fun BLEScreen(
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    var showList by remember { mutableStateOf(true) }

    if (showList && !BLEState.isConnected) {
        DeviceListScreen {
            onDeviceSelected(it)
            showList = false
        }
    } else {
        DashboardScreen {
            onDisconnect()
            showList = true
        }
    }
}

@Composable
fun DeviceListScreen(onDeviceSelected: (BluetoothDevice) -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("Capteurs BLE", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (BLEState.devices.isEmpty()) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(BLEState.devices.size) {
                    val d = BLEState.devices[it]
                    Text(
                        text = d.name ?: d.address,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(d) }
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(onDisconnect: () -> Unit) {
    Column(
        Modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Monitoring", style = MaterialTheme.typography.headlineMedium)
        Text("Statut : ${BLEState.status}")

        Spacer(Modifier.height(16.dp))

        Sensor("Température", BLEState.temperature, "°C")
        Sensor("Humidité", BLEState.humidity, "%")
        Sensor("Pression", BLEState.pressure, "hPa")

        Spacer(Modifier.height(24.dp))

        Button(onClick = onDisconnect) {
            Text("Déconnecter")
        }
    }
}

@Composable
fun Sensor(label: String, value: Double?, unit: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(16.dp)) {
            Text("$label : ", fontWeight = FontWeight.Bold)
            Text(value?.let { "%.2f $unit".format(it) } ?: "--")
        }
    }
}
