package com.example.controlhub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RelayState(
    onHour: Int = 12,
    onMinute: Int = 0,
    onPeriod: String = "AM",
    offHour: Int = 12,
    offMinute: Int = 0,
    offPeriod: String = "PM",
    isAutoMode: Boolean = false,
    isRelayOn: Boolean = false
) {
    val onHour: MutableState<Int> = mutableStateOf(onHour)
    val onMinute: MutableState<Int> = mutableStateOf(onMinute)
    val onPeriod: MutableState<String> = mutableStateOf(onPeriod)
    val offHour: MutableState<Int> = mutableStateOf(offHour)
    val offMinute: MutableState<Int> = mutableStateOf(offMinute)
    val offPeriod: MutableState<String> = mutableStateOf(offPeriod)
    val isAutoMode: MutableState<Boolean> = mutableStateOf(isAutoMode)
    val isRelayOn: MutableState<Boolean> = mutableStateOf(isRelayOn)
}

class RelayViewModel(private val context: Context) : ViewModel() {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("RelayPrefs", Context.MODE_PRIVATE)

    private val relayStates = mutableMapOf<String, RelayState>()
    val isConnected: MutableState<Boolean> = mutableStateOf(false)
    val disconnectionEvent: MutableState<Boolean> = mutableStateOf(false)
    val rtcTimeSetEvent: MutableState<Boolean> = mutableStateOf(false)
    val settingsSavedEvent: MutableState<Boolean> = mutableStateOf(false)
    var selectedDevice: BluetoothDevice? = null
        private set
    val selectedDeviceName: MutableState<String?> = mutableStateOf(null)

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var lastManualCommandTime: Long = 0
    private val ignoreStateUpdateWindow = 2000L

    private var listenerJob: Job? = null
    private val isListening = AtomicBoolean(false)

    private var reconnectJob: Job? = null
    private val maxReconnectAttempts = 3
    private val reconnectDelayMs = 2000L

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> reconnect()
                BluetoothAdapter.STATE_OFF -> {
                    closeConnection()
                    disconnectionEvent.value = true
                }
            }
        }
    }

    init {
        initializeRelayState("Light")
        initializeRelayState("CO2")
        attemptAutoReconnect()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    private fun initializeRelayState(relayName: String) {
        val state = RelayState(
            onHour = sharedPreferences.getInt("${relayName}_onHour", 12),
            onMinute = sharedPreferences.getInt("${relayName}_onMinute", 0),
            onPeriod = sharedPreferences.getString("${relayName}_onPeriod", "AM") ?: "AM",
            offHour = sharedPreferences.getInt("${relayName}_offHour", 12),
            offMinute = sharedPreferences.getInt("${relayName}_offMinute", 0),
            offPeriod = sharedPreferences.getString("${relayName}_offPeriod", "PM") ?: "PM",
            isAutoMode = sharedPreferences.getBoolean("${relayName}_isAutoMode", false),
            isRelayOn = sharedPreferences.getBoolean("${relayName}_isRelayOn", false)
        )
        relayStates[relayName] = state
    }

    private fun attemptAutoReconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null && hasBluetoothPermissions()) {
            val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
            val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(lastDeviceAddress)
            if (device != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    connectToDeviceWithRetry(device, maxReconnectAttempts)
                }
            }
        }
    }

    fun hasLastConnectedDevice(): Boolean {
        return sharedPreferences.getString("lastConnectedDevice", null) != null
    }

    fun getRelayState(relayName: String): RelayState {
        return relayStates[relayName]
            ?: throw IllegalArgumentException("Relay $relayName not found")
    }

    fun saveStateManually(relayName: String) {
        val state = getRelayState(relayName)
        with(sharedPreferences.edit()) {
            putInt("${relayName}_onHour", state.onHour.value)
            putInt("${relayName}_onMinute", state.onMinute.value)
            putString("${relayName}_onPeriod", state.onPeriod.value)
            putInt("${relayName}_offHour", state.offHour.value)
            putInt("${relayName}_offMinute", state.offMinute.value)
            putString("${relayName}_offPeriod", state.offPeriod.value)
            putBoolean("${relayName}_isAutoMode", state.isAutoMode.value)
            putBoolean("${relayName}_isRelayOn", state.isRelayOn.value)
            apply()
        }
        if (state.isAutoMode.value) {
            sendOnOffTimes(relayName)
            // Show confirmation after sending schedule
            viewModelScope.launch(Dispatchers.Main) {
                delay(500) // Small delay to ensure command is sent
                settingsSavedEvent.value = true
            }
        } else {
            sendCommand(relayName, state.isRelayOn.value)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermissions()) {
            println("Bluetooth permissions not granted, cannot get paired devices")
            return emptyList()
        }

        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        return if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            try {
                bluetoothAdapter.bondedDevices.toList()
            } catch (e: SecurityException) {
                println("Security exception while getting paired devices: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private suspend fun connectToDeviceWithRetry(
        device: BluetoothDevice,
        attemptsLeft: Int
    ): Boolean = withContext(Dispatchers.IO) {
        var attempts = attemptsLeft
        while (attempts > 0) {
            if (connectToDevice(device)) {
                return@withContext true
            }
            attempts--
            if (attempts > 0) {
                println("Connection failed, retrying... ($attempts attempts left)")
                delay(reconnectDelayMs)
            }
        }
        withContext(Dispatchers.Main) {
            disconnectionEvent.value = true
        }
        return@withContext false
    }

    private fun connectToDevice(device: BluetoothDevice): Boolean {
        if (!hasBluetoothPermissions()) {
            println("Bluetooth permissions not granted, cannot connect to device")
            return false
        }

        return try {
            closeConnection()

            bluetoothSocket = try {
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                println("Standard socket creation failed, trying fallback method")
                val m = device.javaClass.getMethod(
                    "createRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                m.invoke(device, 1) as BluetoothSocket
            }

            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            inputStream = bluetoothSocket?.inputStream
            selectedDevice = device
            selectedDeviceName.value = try {
                device.name
            } catch (e: SecurityException) {
                "Unknown Device"
            }
            isConnected.value = true
            disconnectionEvent.value = false
            rtcTimeSetEvent.value = false
            settingsSavedEvent.value = false

            with(sharedPreferences.edit()) {
                putString("lastConnectedDevice", device.address)
                apply()
            }

            startListeningForStateUpdates()
            println("Successfully connected to device: ${selectedDeviceName.value}")
            true
        } catch (e: IOException) {
            println("Failed to connect to device: ${e.message}")
            closeConnection()
            false
        } catch (e: SecurityException) {
            println("Security exception during connection: ${e.message}")
            closeConnection()
            false
        } catch (e: Exception) {
            println("Unexpected error during connection: ${e.message}")
            closeConnection()
            false
        }
    }

    fun setSelectedDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            println("Bluetooth permissions not granted, cannot set selected device")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            if (connectToDeviceWithRetry(device, maxReconnectAttempts)) {
                selectedDevice = device
            } else {
                withContext(Dispatchers.Main) {
                    isConnected.value = false
                    selectedDevice = null
                    selectedDeviceName.value = null
                }
            }
        }
    }

    fun sendCommand(relayName: String, state: Boolean) {
        if (!isConnected.value || outputStream == null) {
            println("Cannot send command: not connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val command = when (relayName) {
                    "Light" -> if (state) "L1" else "L0"
                    "CO2" -> if (state) "C1" else "C0"
                    else -> return@launch
                }
                val commandWithNewline = "$command\n"
                outputStream?.write(commandWithNewline.toByteArray())
                outputStream?.flush()
                lastManualCommandTime = System.currentTimeMillis()
                println("Sent command: $commandWithNewline")
            } catch (e: IOException) {
                println("Failed to send command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                println("Unexpected error sending command: ${e.message}")
                handleConnectionError()
            }
        }
    }

    /**
     * Syncs the RTC time on ESP32 with current UTC time from device
     * CRITICAL: Always sends UTC time regardless of device timezone
     * ESP32 will store this UTC time and use it for all scheduling
     */
    fun setRTCTime() {
        if (!isConnected.value || outputStream == null) {
            println("Cannot set RTC time: not connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get current time in UTC - THIS IS CRITICAL
                val instant = Instant.now()
                val utcTime = instant.atZone(ZoneId.of("UTC"))

                // Format as: R,YYYY,MM,DD,HH,MM,SS (all in UTC)
                val formatter = DateTimeFormatter.ofPattern("yyyy,MM,dd,HH,mm,ss")
                val formattedTime = utcTime.format(formatter)
                val command = "R,$formattedTime\n"

                println("=== RTC TIME SYNC ===")
                println("Device timezone: ${ZoneId.systemDefault()}")
                println("Local time: ${ZonedDateTime.now()}")
                println("UTC time being sent: $utcTime")
                println("Command: $command")
                println("====================")

                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                println("Sent RTC time command: $command")
            } catch (e: IOException) {
                println("Failed to send RTC time command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                println("Unexpected error sending RTC time: ${e.message}")
                handleConnectionError()
            }
        }
    }

    /**
     * Converts user's local schedule times to UTC before sending to ESP32
     * CRITICAL: ESP32 operates entirely in UTC for timezone independence
     *
     * Example: User in New York (UTC-5) sets ON at 8:00 AM local
     *   -> Converts to 13:00 UTC
     *   -> ESP32 receives and stores 13:00 UTC
     *   -> When RTC reads 13:00 UTC, relay turns ON (which is 8:00 AM in NY)
     *
     * If user travels to Tokyo (UTC+9), the schedule still works correctly:
     *   -> RTC still triggers at 13:00 UTC (which is 10:00 PM Tokyo time)
     *   -> But this is exactly when it should trigger (8:00 AM NY = 10:00 PM Tokyo)
     */
    private fun sendOnOffTimes(relayName: String) {
        if (!isConnected.value || outputStream == null) {
            println("Cannot send schedule: not connected")
            return
        }

        val state = getRelayState(relayName)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Convert 12-hour format to 24-hour format (local time)
                val onHour24Local = convertTo24Hour(state.onHour.value, state.onPeriod.value)
                val offHour24Local = convertTo24Hour(state.offHour.value, state.offPeriod.value)

                // Get device's timezone offset in minutes
                val deviceZone = ZoneId.systemDefault()
                val now = Instant.now()
                val offset = deviceZone.rules.getOffset(now)
                val offsetMinutes = offset.totalSeconds / 60

                println("=== SCHEDULE TIME CONVERSION ===")
                println("Relay: $relayName")
                println("Device timezone: $deviceZone")
                println("Timezone offset: ${offsetMinutes / 60}h ${offsetMinutes % 60}m")
                println("Local ON time: ${onHour24Local}:${state.onMinute.value}")
                println("Local OFF time: ${offHour24Local}:${state.offMinute.value}")

                // Convert local time to minutes since midnight
                var onTimeMinutesLocal = onHour24Local * 60 + state.onMinute.value
                var offTimeMinutesLocal = offHour24Local * 60 + state.offMinute.value

                // Convert local time to UTC by subtracting timezone offset
                var onTimeMinutesUtc = onTimeMinutesLocal - offsetMinutes
                var offTimeMinutesUtc = offTimeMinutesLocal - offsetMinutes

                // Handle day wraparound (negative times or times >= 1440)
                if (onTimeMinutesUtc < 0) onTimeMinutesUtc += 1440
                if (onTimeMinutesUtc >= 1440) onTimeMinutesUtc -= 1440
                if (offTimeMinutesUtc < 0) offTimeMinutesUtc += 1440
                if (offTimeMinutesUtc >= 1440) offTimeMinutesUtc -= 1440

                // Convert back to hours and minutes (in UTC)
                val onHourUtc = onTimeMinutesUtc / 60
                val onMinuteUtc = onTimeMinutesUtc % 60
                val offHourUtc = offTimeMinutesUtc / 60
                val offMinuteUtc = offTimeMinutesUtc % 60

                println("UTC ON time: ${onHourUtc}:${onMinuteUtc}")
                println("UTC OFF time: ${offHourUtc}:${offMinuteUtc}")
                println("================================")

                // Send schedule command in UTC
                val command = when (relayName) {
                    "Light" -> String.format(
                        "T,L,%02d,%02d,%02d,%02d\n",
                        onHourUtc, onMinuteUtc, offHourUtc, offMinuteUtc
                    )
                    "CO2" -> String.format(
                        "T,C,%02d,%02d,%02d,%02d\n",
                        onHourUtc, onMinuteUtc, offHourUtc, offMinuteUtc
                    )
                    else -> return@launch
                }
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                println("Sent schedule command: $command")
            } catch (e: IOException) {
                println("Failed to send schedule command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                println("Unexpected error sending schedule: ${e.message}")
                handleConnectionError()
            }
        }
    }

    private fun startListeningForStateUpdates() {
        if (isListening.getAndSet(true)) {
            println("Already listening for state updates")
            return
        }

        listenerJob?.cancel()
        listenerJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            var messageBuffer = StringBuilder()
            println("Starting to listen for Bluetooth messages")

            while (isConnected.value && inputStream != null && !Thread.currentThread().isInterrupted) {
                try {
                    val bytes = inputStream?.read(buffer) ?: 0
                    if (bytes > 0) {
                        val received = String(buffer, 0, bytes)
                        messageBuffer.append(received)
                        println("Raw received data: $received")

                        while (messageBuffer.contains("\n")) {
                            val newlineIndex = messageBuffer.indexOf("\n")
                            val message = messageBuffer.substring(0, newlineIndex).trim()
                            messageBuffer.delete(0, newlineIndex + 1)

                            if (message.isNotEmpty()) {
                                processReceivedMessage(message)
                            }
                        }
                    } else if (bytes == -1) {
                        println("End of stream reached, connection closed")
                        handleConnectionError()
                        break
                    }
                } catch (e: IOException) {
                    println("Error reading from Bluetooth: ${e.message}")
                    handleConnectionError()
                    break
                } catch (e: Exception) {
                    println("Unexpected error in listener: ${e.message}")
                    handleConnectionError()
                    break
                }
            }

            isListening.set(false)
            println("Stopped listening for Bluetooth messages")
        }
    }

    private suspend fun processReceivedMessage(message: String) {
        println("Processed message: $message")

        when {
            message == "R,OK" -> {
                withContext(Dispatchers.Main) {
                    rtcTimeSetEvent.value = true
                }
                println("RTC time set confirmation received")
            }

            message.startsWith("S,") -> {
                processStateUpdate(message)
            }

            message.startsWith("P,") -> {
                processScheduleUpdate(message)
            }

            else -> {
                println("Unknown message format: $message")
            }
        }
    }

    private suspend fun processStateUpdate(message: String) {
        val parts = message.split(",")
        if (parts.size != 3) {
            println("Invalid S message format: $message")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastManualCommandTime < ignoreStateUpdateWindow) {
            println("Ignoring state update due to recent manual command")
            return
        }

        val relay = parts[1]
        val state = parts[2] == "1"
        val relayName = if (relay == "L") "Light" else "CO2"

        withContext(Dispatchers.Main) {
            relayStates[relayName]?.isRelayOn?.value = state
        }
        println("Updated $relayName relay state to: $state")
    }

    /**
     * Processes schedule updates received from ESP32
     * ESP32 always sends schedules in UTC
     * We convert back to local time for display in the app
     */
    private suspend fun processScheduleUpdate(message: String) {
        val parts = message.split(",")
        if (parts.size != 7) {
            println("Invalid P message format: $message")
            return
        }

        val relay = parts[1]
        val onHourUtc = parts[2].toIntOrNull() ?: 0
        val onMinuteUtc = parts[3].toIntOrNull() ?: 0
        val offHourUtc = parts[4].toIntOrNull() ?: 0
        val offMinuteUtc = parts[5].toIntOrNull() ?: 0
        val isActive = parts[6] == "1"

        val relayName = if (relay == "L") "Light" else "CO2"
        val state = relayStates[relayName]

        if (state != null) {
            // Get device's timezone offset
            val deviceZone = ZoneId.systemDefault()
            val now = Instant.now()
            val offset = deviceZone.rules.getOffset(now)
            val offsetMinutes = offset.totalSeconds / 60

            println("=== RECEIVED SCHEDULE FROM ESP32 ===")
            println("Relay: $relayName")
            println("UTC ON: ${onHourUtc}:${onMinuteUtc}")
            println("UTC OFF: ${offHourUtc}:${offMinuteUtc}")
            println("Converting to local time (offset: ${offsetMinutes}min)...")

            // Convert UTC to minutes
            var onTimeMinutesUtc = onHourUtc * 60 + onMinuteUtc
            var offTimeMinutesUtc = offHourUtc * 60 + offMinuteUtc

            // Convert UTC to local time by adding timezone offset
            var onTimeMinutesLocal = onTimeMinutesUtc + offsetMinutes
            var offTimeMinutesLocal = offTimeMinutesUtc + offsetMinutes

            // Handle day wraparound
            if (onTimeMinutesLocal < 0) onTimeMinutesLocal += 1440
            if (onTimeMinutesLocal >= 1440) onTimeMinutesLocal -= 1440
            if (offTimeMinutesLocal < 0) offTimeMinutesLocal += 1440
            if (offTimeMinutesLocal >= 1440) offTimeMinutesLocal -= 1440

            // Convert to hours and minutes (local time)
            val onHour24Local = onTimeMinutesLocal / 60
            val onMinuteLocal = onTimeMinutesLocal % 60
            val offHour24Local = offTimeMinutesLocal / 60
            val offMinuteLocal = offTimeMinutesLocal % 60

            // Convert to 12-hour format
            val (onHour12, onPeriod) = convertFrom24Hour(onHour24Local)
            val (offHour12, offPeriod) = convertFrom24Hour(offHour24Local)

            println("Local ON: ${onHour12}:${onMinuteLocal} ${onPeriod}")
            println("Local OFF: ${offHour12}:${offMinuteLocal} ${offPeriod}")
            println("====================================")

            withContext(Dispatchers.Main) {
                state.onHour.value = onHour12
                state.onMinute.value = onMinuteLocal
                state.onPeriod.value = onPeriod
                state.offHour.value = offHour12
                state.offMinute.value = offMinuteLocal
                state.offPeriod.value = offPeriod
                state.isAutoMode.value = isActive
            }

            saveStateManually(relayName)
        } else {
            println("Relay $relayName not found in relayStates")
        }
    }

    private fun handleConnectionError() {
        closeConnection()
        viewModelScope.launch(Dispatchers.Main) {
            disconnectionEvent.value = true
        }
    }

    fun resetRtcTimeSetEvent() {
        rtcTimeSetEvent.value = false
    }

    fun resetSettingsSavedEvent() {
        settingsSavedEvent.value = false
    }

    fun clearSelectedDevice() {
        reconnectJob?.cancel()
        closeConnection()
        selectedDevice = null
        selectedDeviceName.value = null
        isConnected.value = false
        disconnectionEvent.value = false
        rtcTimeSetEvent.value = false
        settingsSavedEvent.value = false
    }

    fun reconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null && hasBluetoothPermissions()) {
            reconnectJob?.cancel()
            reconnectJob = viewModelScope.launch(Dispatchers.IO) {
                val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
                val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(lastDeviceAddress)
                device?.let { connectToDeviceWithRetry(it, maxReconnectAttempts) }
            }
        }
    }

    private fun closeConnection() {
        isListening.set(false)
        listenerJob?.cancel()

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            println("Error closing connection: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            isConnected.value = false
        }
    }

    private fun convertTo24Hour(hour: Int, period: String): Int {
        return when (period) {
            "AM" -> if (hour == 12) 0 else hour
            "PM" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
    }

    private fun convertFrom24Hour(hour24: Int): Pair<Int, String> {
        return if (hour24 == 0) {
            Pair(12, "AM")
        } else if (hour24 == 12) {
            Pair(12, "PM")
        } else if (hour24 < 12) {
            Pair(hour24, "AM")
        } else {
            Pair(hour24 - 12, "PM")
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            println("Error unregister receiver: ${e.message}")
        }
        reconnectJob?.cancel()
        closeConnection()
    }

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }
}

class RelayViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RelayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RelayViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}