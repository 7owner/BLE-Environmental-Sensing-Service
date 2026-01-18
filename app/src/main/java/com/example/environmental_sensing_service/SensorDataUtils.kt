package com.example.environmental_sensing_service

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SensorData(
    val timestamp: Long,
    val temperature: Double?,
    val humidity: Double?,
    val pressure: Double?,
    val pm25: Double?,
    val pm10: Double?
) {
    fun toCsv() = toCsvString()

    fun toFormattedString(): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val date = dateFormat.format(Date(timestamp))
        val temp = temperature?.let { "%.2f °C".format(it) } ?: "--"
        val hum = humidity?.let { "%.2f %%".format(it) } ?: "--"
        val press = pressure?.let { "%.2f hPa".format(it) } ?: "--"
        return "Date: $date, Temp: $temp, Hum: $hum, Press: $press"
    }

    fun toCsvString(): String {
        val temp = temperature?.let { "%.2f".format(it) } ?: ""
        val hum = humidity?.let { "%.2f".format(it) } ?: ""
        val press = pressure?.let { "%.2f".format(it) } ?: ""
        val p25 = pm25?.let { "%.2f".format(it) } ?: ""
        val p10 = pm10?.let { "%.2f".format(it) } ?: ""
        return "${timestamp},${temp},${hum},${press},${p25},${p10}"
    }
}

fun readAndFilterSensorData(
    context: Context,
    fileName: String,
    startDateMillis: Long?,
    endDateMillis: Long?
): List<SensorData> {
    val sensorDataList = mutableListOf<SensorData>()
    try {
        context.openFileInput(fileName).use { fis ->
            InputStreamReader(fis).use { isr ->
                BufferedReader(isr).use { br ->
                    // Skip header line
                    br.readLine()

                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val parts = line?.split(',')
                        if (parts != null && parts.size >= 4) {
                            try {
                                val timestamp = parts[0].toLong()
                                val temperature = parts[1].toDoubleOrNull()
                                val humidity = parts[2].toDoubleOrNull()
                                val pressure = parts[3].toDoubleOrNull()
                                val pm25 = parts.getOrNull(4)?.toDoubleOrNull()
                                val pm10 = parts.getOrNull(5)?.toDoubleOrNull()

                                // Apply date filter
                                val isAfterStartDate = startDateMillis == null || timestamp >= startDateMillis
                                val isBeforeEndDate = endDateMillis == null || timestamp <= (endDateMillis + 86399999) // Add 23h59m59s to endDate to include the whole day

                                if (isAfterStartDate && isBeforeEndDate) {
                                    sensorDataList.add(
                                        SensorData(
                                            timestamp,
                                            temperature,
                                            humidity,
                                            pressure,
                                            pm25,
                                            pm10
                                        )
                                    )
                                }
                            } catch (e: NumberFormatException) {
                                // Log error or skip malformed line
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return sensorDataList
}
