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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Permissions Android 12+
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
                    }
                )
            }
        }
    }

    // ----------------------- BLE FUNCTIONS ------------------------

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

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val essService = gatt.getService(ESS_SERVICE_UUID)
            val tempChar = essService?.getCharacteristic(TEMPERATURE_UUID)

            // Lecture simple
            gatt.readCharacteristic(tempChar)

            // Notifications
            gatt.setCharacteristicNotification(tempChar, true)

            val cccd = tempChar?.getDescriptor(CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleTemperature(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleTemperature(value)
        }
    }

    private fun handleTemperature(value: ByteArray) {
        val tempValue = ByteBuffer.wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short

        val temperature = tempValue / 100.0

        BLEDevicesState.temperature = temperature
    }
}


object BLEDevicesState {
    var devices = mutableStateListOf<BluetoothDevice>()
    var temperature by mutableStateOf<Double?>(null)

    fun addDevice(device: BluetoothDevice) {
        if (!devices.contains(device)) {
            devices.add(device)
        }
    }
}

@Composable
fun BLEScreen(onDeviceSelected: (BluetoothDevice) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Environmental Sensing Service", style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(16.dp))

        Text("Température : ${BLEDevicesState.temperature ?: "--"} °C")

        Spacer(Modifier.height(16.dp))

        Text("Périphériques trouvés :")

        LazyColumn {
            items(BLEDevicesState.devices.size) { index ->
                val device = BLEDevicesState.devices[index]
                Text(
                    text = device.name ?: "Unknown Device",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDeviceSelected(device)
                        }
                        .padding(8.dp)
                )
            }
        }
    }
}