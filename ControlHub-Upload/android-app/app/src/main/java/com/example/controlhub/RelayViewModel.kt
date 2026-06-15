package com.example.controlhub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
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

// ═══════════════════════════════════════════════════════
// Device & Relay Data Models
// ═══════════════════════════════════════════════════════

/**
 * Represents a user-defined device (relay).
 * Up to 4 devices supported, each with a unique relay ID (1-4).
 */
data class DeviceInfo(
    val id: Int,  // 1-4, maps to relay pin on ESP32
    val name: MutableState<String>,
    val iconType: MutableState<String>  // "lightbulb", "co2", "water_drop", "thermostat"
)

/**
 * State for a single relay: schedule times, mode, on/off state, and day-of-week mask.
 */
class RelayState(
    onHour: Int = 12,
    onMinute: Int = 0,
    onPeriod: String = "AM",
    offHour: Int = 12,
    offMinute: Int = 0,
    offPeriod: String = "PM",
    isAutoMode: Boolean = false,
    isRelayOn: Boolean = false,
    daysMask: Int = 0x7F  // All days by default (bit 0=Mon ... bit 6=Sun)
) {
    val onHour: MutableState<Int> = mutableStateOf(onHour)
    val onMinute: MutableState<Int> = mutableStateOf(onMinute)
    val onPeriod: MutableState<String> = mutableStateOf(onPeriod)
    val offHour: MutableState<Int> = mutableStateOf(offHour)
    val offMinute: MutableState<Int> = mutableStateOf(offMinute)
    val offPeriod: MutableState<String> = mutableStateOf(offPeriod)
    val isAutoMode: MutableState<Boolean> = mutableStateOf(isAutoMode)
    val isRelayOn: MutableState<Boolean> = mutableStateOf(isRelayOn)
    val daysMask: MutableState<Int> = mutableStateOf(daysMask)
}

// Available device icon types
val DEVICE_ICON_OPTIONS = listOf("lightbulb", "co2", "water_drop", "thermostat")

// ═══════════════════════════════════════════════════════
// RelayViewModel — Dynamic Device Management
// ═══════════════════════════════════════════════════════

class RelayViewModel(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = "RelayViewModel"
        const val MAX_DEVICES = 4
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("RelayPrefs_v2", Context.MODE_PRIVATE)

    // Dynamic device list
    val devices: SnapshotStateList<DeviceInfo> = mutableStateListOf()
    private val relayStates = java.util.concurrent.ConcurrentHashMap<Int, RelayState>()

    // Which device card is currently expanded (-1 = none)
    val expandedDeviceId: MutableState<Int> = mutableIntStateOf(-1)

    // Connection state
    val isConnected: MutableState<Boolean> = mutableStateOf(false)
    val isConnecting: MutableState<Boolean> = mutableStateOf(false)
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
                    viewModelScope.launch(Dispatchers.Main) {
                        disconnectionEvent.value = true
                    }
                }
            }
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter
    }

    init {
        loadDevices()
        attemptAutoReconnect()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    // ═══════════════════════════════════════════════════
    // Device Management
    // ═══════════════════════════════════════════════════

    private fun loadDevices() {
        val deviceCount = sharedPreferences.getInt("device_count", -1)

        if (deviceCount == -1) {
            // First launch or migration — start empty
            saveDevices()
            return
        }

        for (i in 0 until deviceCount) {
            val id = sharedPreferences.getInt("device_${i}_id", i + 1)
            val name = sharedPreferences.getString("device_${i}_name", "Device ${id}") ?: "Device ${id}"
            val icon = sharedPreferences.getString("device_${i}_icon", "lightbulb") ?: "lightbulb"

            val deviceInfo = DeviceInfo(
                id = id,
                name = mutableStateOf(name),
                iconType = mutableStateOf(icon)
            )
            devices.add(deviceInfo)
            initializeRelayState(id)
        }
    }

    private fun saveDevices() {
        with(sharedPreferences.edit()) {
            putInt("device_count", devices.size)
            devices.forEachIndexed { index, device ->
                putInt("device_${index}_id", device.id)
                putString("device_${index}_name", device.name.value)
                putString("device_${index}_icon", device.iconType.value)
            }
            apply()
        }
    }

    fun addDevice(name: String, iconType: String, socketId: Int = -1): Boolean {
        if (devices.size >= MAX_DEVICES) return false

        // Use provided socketId, or find next available
        val usedIds = devices.map { it.id }.toSet()
        val targetId = if (socketId in 1..MAX_DEVICES && socketId !in usedIds) {
            socketId
        } else {
            (1..MAX_DEVICES).firstOrNull { it !in usedIds } ?: return false
        }

        val deviceInfo = DeviceInfo(
            id = targetId,
            name = mutableStateOf(name),
            iconType = mutableStateOf(iconType)
        )
        devices.add(deviceInfo)
        initializeRelayState(targetId)
        saveDevices()
        return true
    }

    fun getUsedSocketIds(): Set<Int> = devices.map { it.id }.toSet()

    fun removeDevice(id: Int) {
        // Clear expanded state if this device was expanded
        if (expandedDeviceId.value == id) {
            expandedDeviceId.value = -1
        }
        devices.removeAll { it.id == id }
        relayStates.remove(id)
        // Clean up SharedPreferences for this relay
        with(sharedPreferences.edit()) {
            listOf("onHour", "onMinute", "onPeriod", "offHour", "offMinute",
                "offPeriod", "isAutoMode", "isRelayOn", "daysMask").forEach { key ->
                remove("relay_${id}_${key}")
            }
            apply()
        }
        saveDevices()
    }

    fun renameDevice(id: Int, newName: String) {
        devices.find { it.id == id }?.name?.value = newName
        saveDevices()
    }

    fun changeDeviceIcon(id: Int, newIcon: String) {
        devices.find { it.id == id }?.iconType?.value = newIcon
        saveDevices()
    }

    // ═══════════════════════════════════════════════════
    // Relay State Management
    // ═══════════════════════════════════════════════════

    private fun initializeRelayState(relayId: Int) {
        val prefix = "relay_${relayId}_"
        val state = RelayState(
            onHour = sharedPreferences.getInt("${prefix}onHour", 12),
            onMinute = sharedPreferences.getInt("${prefix}onMinute", 0),
            onPeriod = sharedPreferences.getString("${prefix}onPeriod", "AM") ?: "AM",
            offHour = sharedPreferences.getInt("${prefix}offHour", 12),
            offMinute = sharedPreferences.getInt("${prefix}offMinute", 0),
            offPeriod = sharedPreferences.getString("${prefix}offPeriod", "PM") ?: "PM",
            isAutoMode = sharedPreferences.getBoolean("${prefix}isAutoMode", false),
            isRelayOn = sharedPreferences.getBoolean("${prefix}isRelayOn", false),
            daysMask = sharedPreferences.getInt("${prefix}daysMask", 0x7F)
        )
        relayStates[relayId] = state
    }

    fun getRelayState(relayId: Int): RelayState {
        return relayStates[relayId]
            ?: throw IllegalArgumentException("Relay $relayId not found")
    }

    fun reloadState(relayId: Int) {
        val state = getRelayState(relayId)
        val prefix = "relay_${relayId}_"
        state.onHour.value = sharedPreferences.getInt("${prefix}onHour", 12)
        state.onMinute.value = sharedPreferences.getInt("${prefix}onMinute", 0)
        state.onPeriod.value = sharedPreferences.getString("${prefix}onPeriod", "AM") ?: "AM"
        state.offHour.value = sharedPreferences.getInt("${prefix}offHour", 12)
        state.offMinute.value = sharedPreferences.getInt("${prefix}offMinute", 0)
        state.offPeriod.value = sharedPreferences.getString("${prefix}offPeriod", "PM") ?: "PM"
        state.isAutoMode.value = sharedPreferences.getBoolean("${prefix}isAutoMode", false)
        state.isRelayOn.value = sharedPreferences.getBoolean("${prefix}isRelayOn", false)
        state.daysMask.value = sharedPreferences.getInt("${prefix}daysMask", 0x7F)
    }

    fun saveStateManually(relayId: Int, showConfirmation: Boolean = true) {
        val state = getRelayState(relayId)
        saveRelayToPrefs(relayId, state)
        
        // Always send schedule details and active status to ESP32
        sendOnOffTimes(relayId)
        
        if (showConfirmation && state.isAutoMode.value) {
            viewModelScope.launch(Dispatchers.Main) {
                delay(500)
                settingsSavedEvent.value = true
            }
        }
    }

    /**
     * Saves relay state to SharedPreferences without sending any commands.
     * Used by processScheduleUpdate to persist received state without
     * triggering a send-back loop.
     */
    private fun saveRelayToPrefs(relayId: Int, state: RelayState) {
        val prefix = "relay_${relayId}_"
        with(sharedPreferences.edit()) {
            putInt("${prefix}onHour", state.onHour.value)
            putInt("${prefix}onMinute", state.onMinute.value)
            putString("${prefix}onPeriod", state.onPeriod.value)
            putInt("${prefix}offHour", state.offHour.value)
            putInt("${prefix}offMinute", state.offMinute.value)
            putString("${prefix}offPeriod", state.offPeriod.value)
            putBoolean("${prefix}isAutoMode", state.isAutoMode.value)
            putBoolean("${prefix}isRelayOn", state.isRelayOn.value)
            putInt("${prefix}daysMask", state.daysMask.value)
            apply()
        }
    }

    fun hasLastConnectedDevice(): Boolean {
        return sharedPreferences.getString("lastConnectedDevice", null) != null
    }

    // ═══════════════════════════════════════════════════
    // Bluetooth Permissions & Pairing
    // ═══════════════════════════════════════════════════

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
            Log.w(TAG, "Bluetooth permissions not granted, cannot get paired devices")
            return emptyList()
        }

        val bluetoothAdapter = getBluetoothAdapter()
        return if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            try {
                bluetoothAdapter.bondedDevices.toList()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while getting paired devices: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // Bluetooth Connection
    // ═══════════════════════════════════════════════════

    private fun attemptAutoReconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null && hasBluetoothPermissions()) {
            val bluetoothAdapter = getBluetoothAdapter()
            val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(lastDeviceAddress)
            if (device != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    connectToDeviceWithRetry(device, maxReconnectAttempts)
                }
            }
        }
    }

    private suspend fun connectToDeviceWithRetry(
        device: BluetoothDevice,
        attemptsLeft: Int
    ): Boolean = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            isConnecting.value = true
        }
        var attempts = attemptsLeft
        while (attempts > 0) {
            if (connectToDevice(device)) {
                withContext(Dispatchers.Main) {
                    isConnecting.value = false
                }
                return@withContext true
            }
            attempts--
            if (attempts > 0) {
                Log.d(TAG, "Connection failed, retrying... ($attempts attempts left)")
                delay(reconnectDelayMs)
            }
        }
        withContext(Dispatchers.Main) {
            disconnectionEvent.value = true
            isConnecting.value = false
        }
        return@withContext false
    }

    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Bluetooth permissions not granted, cannot connect to device")
            return false
        }

        return try {
            closeConnection()

            bluetoothSocket = try {
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                Log.d(TAG, "Standard socket creation failed, trying fallback method")
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
            val deviceName = try {
                device.name
            } catch (e: SecurityException) {
                "Unknown Device"
            }

            withContext(Dispatchers.Main) {
                selectedDeviceName.value = deviceName
                isConnected.value = true
                disconnectionEvent.value = false
                rtcTimeSetEvent.value = false
                settingsSavedEvent.value = false
            }

            with(sharedPreferences.edit()) {
                putString("lastConnectedDevice", device.address)
                apply()
            }

            startListeningForStateUpdates()
            Log.i(TAG, "Successfully connected to device: $deviceName")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
            closeConnection()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connection: ${e.message}")
            closeConnection()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connection: ${e.message}")
            closeConnection()
            false
        }
    }

    fun setSelectedDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Bluetooth permissions not granted, cannot set selected device")
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

    // ═══════════════════════════════════════════════════
    // Commands — New Protocol (numeric relay IDs)
    // Manual: "<id>1" / "<id>0"
    // Schedule: "T,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>"
    // ═══════════════════════════════════════════════════

    fun sendCommand(relayId: Int, state: Boolean) {
        if (!isConnected.value || outputStream == null) {
            Log.d(TAG, "Cannot send command: not connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val command = "${relayId}${if (state) "1" else "0"}"
                val commandWithNewline = "$command\n"
                outputStream?.write(commandWithNewline.toByteArray())
                outputStream?.flush()
                lastManualCommandTime = System.currentTimeMillis()
                Log.d(TAG, "Sent command: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending command: ${e.message}")
                handleConnectionError()
            }
        }
    }

    /**
     * Syncs the RTC time on ESP32 with current UTC time from device.
     * CRITICAL: Always sends UTC time regardless of device timezone.
     * ESP32 will store this UTC time and use it for all scheduling.
     */
    fun setRTCTime() {
        if (!isConnected.value || outputStream == null) {
            Log.d(TAG, "Cannot set RTC time: not connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val instant = Instant.now()
                val utcTime = instant.atZone(ZoneId.of("UTC"))

                val formatter = DateTimeFormatter.ofPattern("yyyy,MM,dd,HH,mm,ss")
                val formattedTime = utcTime.format(formatter)
                val command = "R,$formattedTime\n"

                Log.d(TAG, "=== RTC TIME SYNC ===")
                Log.d(TAG, "Device timezone: ${ZoneId.systemDefault()}")
                Log.d(TAG, "Local time: ${ZonedDateTime.now()}")
                Log.d(TAG, "UTC time being sent: $utcTime")
                Log.d(TAG, "Command: $command")

                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent RTC time command: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send RTC time command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending RTC time: ${e.message}")
                handleConnectionError()
            }
        }
    }

    /**
     * Converts user's local schedule times to UTC before sending to ESP32.
     * Now includes day-of-week mask.
     * CRITICAL: ESP32 operates entirely in UTC for timezone independence.
     */
    private fun sendOnOffTimes(relayId: Int) {
        if (!isConnected.value || outputStream == null) {
            Log.d(TAG, "Cannot send schedule: not connected")
            return
        }

        val state = getRelayState(relayId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val onHour24Local = convertTo24Hour(state.onHour.value, state.onPeriod.value)
                val offHour24Local = convertTo24Hour(state.offHour.value, state.offPeriod.value)

                val deviceZone = ZoneId.systemDefault()
                val now = Instant.now()
                val offset = deviceZone.rules.getOffset(now)
                val offsetMinutes = offset.totalSeconds / 60

                Log.d(TAG, "=== SCHEDULE TIME CONVERSION ===")
                Log.d(TAG, "Relay: $relayId")
                Log.d(TAG, "Device timezone: $deviceZone (offset: ${offsetMinutes / 60}h ${offsetMinutes % 60}m)")
                Log.d(TAG, "Local ON: ${onHour24Local}:${state.onMinute.value}, OFF: ${offHour24Local}:${state.offMinute.value}")

                var onTimeMinutesLocal = onHour24Local * 60 + state.onMinute.value
                var offTimeMinutesLocal = offHour24Local * 60 + state.offMinute.value

                var onTimeMinutesUtc = onTimeMinutesLocal - offsetMinutes
                var offTimeMinutesUtc = offTimeMinutesLocal - offsetMinutes

                // Track if time conversion crosses a day boundary
                val onDayShift = when {
                    onTimeMinutesUtc < 0    -> -1  // crossed back to previous day
                    onTimeMinutesUtc >= 1440 ->  1  // crossed forward to next day
                    else                     ->  0
                }
                val offDayShift = when {
                    offTimeMinutesUtc < 0    -> -1
                    offTimeMinutesUtc >= 1440 ->  1
                    else                      ->  0
                }

                if (onTimeMinutesUtc < 0) onTimeMinutesUtc += 1440
                if (onTimeMinutesUtc >= 1440) onTimeMinutesUtc -= 1440
                if (offTimeMinutesUtc < 0) offTimeMinutesUtc += 1440
                if (offTimeMinutesUtc >= 1440) offTimeMinutesUtc -= 1440

                val onHourUtc = onTimeMinutesUtc / 60
                val onMinuteUtc = onTimeMinutesUtc % 60
                val offHourUtc = offTimeMinutesUtc / 60
                val offMinuteUtc = offTimeMinutesUtc % 60

                // Shift the day mask if the UTC conversion crossed midnight.
                // Both ON and OFF should agree on the day shift; use the ON shift
                // since that determines which day the schedule starts on.
                val adjustedDaysMask = if (onDayShift != 0) {
                    rotateDaysMask(state.daysMask.value, onDayShift)
                } else {
                    state.daysMask.value
                }

                Log.d(TAG, "UTC ON: ${onHourUtc}:${onMinuteUtc}, OFF: ${offHourUtc}:${offMinuteUtc}")
                Log.d(TAG, "Days mask: ${adjustedDaysMask} (binary: ${Integer.toBinaryString(adjustedDaysMask)})")
                if (onDayShift != 0) {
                    Log.d(TAG, "Day mask shifted by $onDayShift day(s) due to midnight crossing")
                }

                val command = String.format(
                    "T,%d,%02d,%02d,%02d,%02d,%d,%d\n",
                    relayId, onHourUtc, onMinuteUtc, offHourUtc, offMinuteUtc, adjustedDaysMask, if (state.isAutoMode.value) 1 else 0
                )
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent schedule command: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send schedule command: ${e.message}")
                handleConnectionError()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending schedule: ${e.message}")
                handleConnectionError()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Bluetooth Listener
    // ═══════════════════════════════════════════════════

    private fun startListeningForStateUpdates() {
        if (isListening.getAndSet(true)) {
            Log.d(TAG, "Already listening for state updates")
            return
        }

        listenerJob?.cancel()
        listenerJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            val messageBuffer = StringBuilder()
            Log.d(TAG, "Starting to listen for Bluetooth messages")

            while (isConnected.value && inputStream != null && !Thread.currentThread().isInterrupted) {
                try {
                    val bytes = inputStream?.read(buffer) ?: 0
                    if (bytes > 0) {
                        val received = String(buffer, 0, bytes)
                        messageBuffer.append(received)
                        Log.v(TAG, "Raw received data: $received")

                        while (messageBuffer.contains("\n")) {
                            val newlineIndex = messageBuffer.indexOf("\n")
                            val message = messageBuffer.substring(0, newlineIndex).trim()
                            messageBuffer.delete(0, newlineIndex + 1)

                            if (message.isNotEmpty()) {
                                processReceivedMessage(message)
                            }
                        }
                    } else if (bytes == -1) {
                        Log.w(TAG, "End of stream reached, connection closed")
                        handleConnectionError()
                        break
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from Bluetooth: ${e.message}")
                    handleConnectionError()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in listener: ${e.message}")
                    handleConnectionError()
                    break
                }
            }

            isListening.set(false)
            Log.d(TAG, "Stopped listening for Bluetooth messages")
        }
    }

    private suspend fun processReceivedMessage(message: String) {
        Log.d(TAG, "Processed message: $message")

        when {
            message == "R,OK" -> {
                withContext(Dispatchers.Main) {
                    rtcTimeSetEvent.value = true
                }
                Log.d(TAG, "RTC time set confirmation received")
            }

            message.startsWith("S,") -> {
                processStateUpdate(message)
            }

            message.startsWith("P,") -> {
                processScheduleUpdate(message)
            }

            else -> {
                Log.w(TAG, "Unknown message format: $message")
            }
        }
    }

    /**
     * Process state update: S,<id>,<0|1>
     * id is now numeric (1-4)
     */
    private suspend fun processStateUpdate(message: String) {
        val parts = message.split(",")
        if (parts.size != 3) {
            Log.w(TAG, "Invalid S message format: $message")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastManualCommandTime < ignoreStateUpdateWindow) {
            Log.d(TAG, "Ignoring state update due to recent manual command")
            return
        }

        val relayId = parts[1].toIntOrNull()
        val state = parts[2] == "1"

        if (relayId == null || relayId !in 1..MAX_DEVICES) {
            Log.w(TAG, "Unknown relay id in state update: ${parts[1]}")
            return
        }

        withContext(Dispatchers.Main) {
            relayStates[relayId]?.isRelayOn?.value = state
        }
        Log.d(TAG, "Updated relay $relayId state to: $state")
    }

    /**
     * Processes schedule updates received from ESP32.
     * New format: P,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>,<active>
     * ESP32 always sends schedules in UTC — we convert back to local time for display.
     * NOTE: Only saves to SharedPreferences; does NOT send commands back to avoid loops.
     */
    private suspend fun processScheduleUpdate(message: String) {
        val parts = message.split(",")
        if (parts.size != 8) {
            Log.w(TAG, "Invalid P message format: $message")
            return
        }

        val relayId = parts[1].toIntOrNull()
        val onHourUtc = parts[2].toIntOrNull() ?: 0
        val onMinuteUtc = parts[3].toIntOrNull() ?: 0
        val offHourUtc = parts[4].toIntOrNull() ?: 0
        val offMinuteUtc = parts[5].toIntOrNull() ?: 0
        val daysMask = parts[6].toIntOrNull() ?: 0x7F
        val isActive = parts[7] == "1"

        if (relayId == null || relayId !in 1..MAX_DEVICES) {
            Log.w(TAG, "Unknown relay id in schedule update: ${parts[1]}")
            return
        }

        val state = relayStates[relayId]

        if (state != null) {
            val deviceZone = ZoneId.systemDefault()
            val now = Instant.now()
            val offset = deviceZone.rules.getOffset(now)
            val offsetMinutes = offset.totalSeconds / 60

            Log.d(TAG, "=== RECEIVED SCHEDULE FROM ESP32 ===")
            Log.d(TAG, "Relay: $relayId, UTC ON: ${onHourUtc}:${onMinuteUtc}, OFF: ${offHourUtc}:${offMinuteUtc}")

            var onTimeMinutesLocal = onHourUtc * 60 + onMinuteUtc + offsetMinutes
            var offTimeMinutesLocal = offHourUtc * 60 + offMinuteUtc + offsetMinutes

            // Track day boundary crossing for daysMask rotation
            val onDayShift = when {
                onTimeMinutesLocal < 0    ->  -1
                onTimeMinutesLocal >= 1440 ->  1
                else                       ->  0
            }

            if (onTimeMinutesLocal < 0) onTimeMinutesLocal += 1440
            if (onTimeMinutesLocal >= 1440) onTimeMinutesLocal -= 1440
            if (offTimeMinutesLocal < 0) offTimeMinutesLocal += 1440
            if (offTimeMinutesLocal >= 1440) offTimeMinutesLocal -= 1440

            val onHour24Local = onTimeMinutesLocal / 60
            val onMinuteLocal = onTimeMinutesLocal % 60
            val offHour24Local = offTimeMinutesLocal / 60
            val offMinuteLocal = offTimeMinutesLocal % 60

            val (onHour12, onPeriod) = convertFrom24Hour(onHour24Local)
            val (offHour12, offPeriod) = convertFrom24Hour(offHour24Local)

            // Rotate daysMask back from UTC to local (reverse of what sendOnOffTimes does)
            val localDaysMask = if (onDayShift != 0) {
                rotateDaysMask(daysMask, -onDayShift)
            } else {
                daysMask
            }

            Log.d(TAG, "Local ON: ${onHour12}:${onMinuteLocal} ${onPeriod}, OFF: ${offHour12}:${offMinuteLocal} ${offPeriod}")
            if (onDayShift != 0) {
                Log.d(TAG, "Day mask rotated by $onDayShift for local display (UTC mask: $daysMask -> local: $localDaysMask)")
            }

            withContext(Dispatchers.Main) {
                state.onHour.value = onHour12
                state.onMinute.value = onMinuteLocal
                state.onPeriod.value = onPeriod
                state.offHour.value = offHour12
                state.offMinute.value = offMinuteLocal
                state.offPeriod.value = offPeriod
                state.isAutoMode.value = isActive
                state.daysMask.value = localDaysMask

                // Save to prefs on main thread so we read Compose state safely
                saveRelayToPrefs(relayId, state)
            }
        } else {
            Log.w(TAG, "Relay $relayId not found in relayStates")
        }
    }

    // ═══════════════════════════════════════════════════
    // Connection Management
    // ═══════════════════════════════════════════════════

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
        viewModelScope.launch(Dispatchers.Main) {
            selectedDeviceName.value = null
            isConnected.value = false
            disconnectionEvent.value = false
            rtcTimeSetEvent.value = false
            settingsSavedEvent.value = false
        }
    }

    fun reconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null && hasBluetoothPermissions()) {
            reconnectJob?.cancel()
            reconnectJob = viewModelScope.launch(Dispatchers.IO) {
                val bluetoothAdapter = getBluetoothAdapter()
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
            Log.e(TAG, "Error closing connection: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            viewModelScope.launch(Dispatchers.Main) {
                isConnected.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Time Conversion Utilities
    // ═══════════════════════════════════════════════════

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

    /**
     * Rotates a 7-bit day-of-week bitmask by [shift] positions.
     *   shift = -1 → each day moves back by one (Mon→Sun, Tue→Mon, etc.)
     *   shift = +1 → each day moves forward by one (Mon→Tue, Sun→Mon, etc.)
     *
     * This is needed when local→UTC time conversion crosses midnight,
     * pushing the schedule into the previous or next calendar day.
     *
     * Bitmask: bit 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri, 5=Sat, 6=Sun
     */
    private fun rotateDaysMask(mask: Int, shift: Int): Int {
        val normalized = ((-shift % 7) + 7) % 7  // always positive 0..6
        val m = mask and 0x7F                      // ensure 7 bits only
        // Circular right-rotate of a 7-bit field
        return ((m shr normalized) or (m shl (7 - normalized))) and 0x7F
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
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