package com.example.controlhub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// =======================================================
// Device & Relay Data Models
// =======================================================

/**
 * Represents a user-defined device (relay).
 * Up to 4 devices supported, each with a unique relay ID (1-4).
 */
data class DeviceInfo(
    val id: Int,  // 1-4, maps to relay pin on ESP32
    val name: MutableState<String>,
    val iconType: MutableState<String>  // "lightbulb", "co2", "water_drop", "thermostat"
)

data class RelaySettingsSnapshot(
    val onHour: Int,
    val onMinute: Int,
    val onPeriod: String,
    val offHour: Int,
    val offMinute: Int,
    val offPeriod: String,
    val isAutoMode: Boolean,
    val isRelayOn: Boolean,
    val daysMask: Int,
    val modeType: Int,
    val isInfinite: Boolean,
    val pulseOnSec: Int,
    val pulseOffSec: Int
)

/**
 * Represents a user-defined note.
 */
data class NoteItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val date: String
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
    daysMask: Int = 0x7F,  // All days by default (bit 0=Mon ... bit 6=Sun)
    modeType: Int = 0,     // 0 = Auto/Standard, 1 = Timer/Pulse
    isInfinite: Boolean = false,  // true = 24/7, false = Windowed
    pulseOnSec: Int = 10,  // Pulse ON duration in seconds
    pulseOffSec: Int = 10  // Pulse OFF duration in seconds
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
    val modeType: MutableState<Int> = mutableStateOf(modeType)
    val isInfinite: MutableState<Boolean> = mutableStateOf(isInfinite)
    val pulseOnSec: MutableState<Int> = mutableStateOf(pulseOnSec)
    val pulseOffSec: MutableState<Int> = mutableStateOf(pulseOffSec)
}

fun RelayState.toSnapshot(): RelaySettingsSnapshot {
    return RelaySettingsSnapshot(
        onHour = onHour.value,
        onMinute = onMinute.value,
        onPeriod = onPeriod.value,
        offHour = offHour.value,
        offMinute = offMinute.value,
        offPeriod = offPeriod.value,
        isAutoMode = isAutoMode.value,
        isRelayOn = isRelayOn.value,
        daysMask = daysMask.value,
        modeType = modeType.value,
        isInfinite = isInfinite.value,
        pulseOnSec = pulseOnSec.value,
        pulseOffSec = pulseOffSec.value
    )
}

private data class SaveTransaction(val id: Long, val timestamp: Long)

// Available device icon types
val DEVICE_ICON_OPTIONS = listOf("lightbulb", "co2", "water_drop", "thermostat")

// =======================================================
// RelayViewModel - Dynamic Device Management
// =======================================================

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
    private val writeMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var writeDeferred: kotlinx.coroutines.CompletableDeferred<Int>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messageDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var negotiatedMtu: Int = 23

    // Which device card is currently expanded (-1 = none)
    val expandedDeviceId: MutableState<Int> = mutableIntStateOf(-1)

    // Device detail screen navigation state
    val activeDetailDevice: MutableState<DeviceInfo?> = mutableStateOf(null)

    // Connection state
    val isConnected: MutableState<Boolean> = mutableStateOf(false)
    val isConnecting: MutableState<Boolean> = mutableStateOf(false)
    val disconnectionEvent: MutableState<Boolean> = mutableStateOf(false)
    val rtcTimeSetEvent: MutableState<Boolean> = mutableStateOf(false)
    val rtcTimeSyncFailedEvent: MutableState<Boolean> = mutableStateOf(false)
    val settingsSavedEvent: MutableState<Boolean> = mutableStateOf(false)
    val settingsSaveFailedEvent: MutableState<Boolean> = mutableStateOf(false)
    val rtcFailedState: MutableState<Boolean> = mutableStateOf(false)
    val rtcLostPowerState: MutableState<Boolean> = mutableStateOf(false)
    val connectionFailedEvent: MutableState<Boolean> = mutableStateOf(false)
    
    // Per-relay pending save tracking.
    // Using a Map keyed by relay ID so saves to different relays are fully independent.
    // Active transaction ID map and queues are used to correlate saves with responses.
    private val pendingSaveDeferreds = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val pendingSaveJobs     = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val saveMutexes         = List(MAX_DEVICES + 1) { kotlinx.coroutines.sync.Mutex() }
    private val pendingTransactionQueues = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentLinkedQueue<SaveTransaction>>()
    private val activeTxIdMap       = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val saveTxCounter       = java.util.concurrent.atomic.AtomicLong(0)
    
    var selectedDevice: BluetoothDevice? = null
        private set
    val selectedDeviceName: MutableState<String?> = mutableStateOf(null)

    // Notes, Theme and RTC States
    val notes = mutableStateListOf<NoteItem>()
    val themeMode: MutableState<Int> = mutableStateOf(0) // 0 = System, 1 = Light, 2 = Dark
    val lastRtcSyncTime: MutableState<String?> = mutableStateOf(null)
    private var isManualRtcSyncPending = false
    private var isRtcSyncPending = false
    // Set to true after sending Q on connect; cleared when we receive H,OK / E,RTC_* response.
    // Ensures auto-sync only fires after confirmed firmware health, not on a timer guess.
    private var awaitingHandshakeSync = false
    // Awaited by syncRtcTime(); resolved on R,OK (true) or ERR,R,... (false).
    // Null when no auto-sync-then-save flow is in flight.
    private var pendingRtcSyncDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val rtcSyncMutex = kotlinx.coroutines.sync.Mutex()

    // BLE Variables & Nordic UART Service UUIDs
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Scanning states
    val isScanning = mutableStateOf(false)
    val scannedDevices = mutableStateListOf<BluetoothDevice>()
    private var scanJob: Job? = null

    private var lastManualCommandTime: Long = 0
    private val lastManualCommandTimeMap = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastManualCommandStateMap = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val manualCommandTimeoutJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    private val incomingMessageBuffer = StringBuilder()

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
        loadNotes()
        loadThemeMode()
        loadLastRtcSyncTime()
        attemptAutoReconnect()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ===================================================
    // Device Management
    // ===================================================

    private fun loadNotes() {
        val json = sharedPreferences.getString("user_notes_list", null)
        if (json != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<NoteItem>>() {}.type
                val list: List<NoteItem> = com.google.gson.Gson().fromJson(json, type)
                notes.clear()
                notes.addAll(list)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing notes: ${e.message}")
            }
        }
    }

    fun saveNotes() {
        val json = com.google.gson.Gson().toJson(notes)
        sharedPreferences.edit().putString("user_notes_list", json).apply()
    }

    fun addNote(title: String, content: String) {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a")
        val dateStr = java.time.LocalDateTime.now().format(formatter)
        notes.add(0, NoteItem(title = title, content = content, date = dateStr))
        saveNotes()
    }

    fun updateNote(id: String, title: String, content: String) {
        val index = notes.indexOfFirst { it.id == id }
        if (index != -1) {
            val oldNote = notes[index]
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a")
            val dateStr = java.time.LocalDateTime.now().format(formatter)
            notes[index] = oldNote.copy(title = title, content = content, date = dateStr)
            saveNotes()
        }
    }

    fun deleteNote(id: String) {
        notes.removeAll { it.id == id }
        saveNotes()
    }

    private fun loadThemeMode() {
        themeMode.value = sharedPreferences.getInt("theme_mode", 0)
    }

    fun setThemeMode(mode: Int) {
        themeMode.value = mode
        sharedPreferences.edit().putInt("theme_mode", mode).apply()
    }

    private fun loadLastRtcSyncTime() {
        lastRtcSyncTime.value = sharedPreferences.getString("last_rtc_sync_time", null)
    }

    fun autoCreateDefaultDevices() {
        if (devices.isEmpty()) {
            addDevice("Light", "lightbulb", 1)
            addDevice("CO2", "co2", 2)
            addDevice("Pump", "water_drop", 3)
            addDevice("Heater", "thermostat", 4)
        }
    }

    private fun loadDevices() {
        val deviceCount = sharedPreferences.getInt("device_count", -1)

        if (deviceCount == -1) {
            // First launch or migration - start empty
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
                "offPeriod", "isAutoMode", "isRelayOn", "daysMask", "modeType", "isInfinite", "pulseOnSec", "pulseOffSec").forEach { key ->
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

    fun changeDeviceSocket(oldId: Int, newId: Int): Boolean {
        if (oldId == newId) return true
        val usedIds = devices.map { it.id }.toSet()
        if (newId in usedIds) return false

        val index = devices.indexOfFirst { it.id == oldId }
        if (index == -1) return false

        val oldDevice = devices[index]
        val newDevice = DeviceInfo(
            id = newId,
            name = oldDevice.name,
            iconType = oldDevice.iconType
        )
        devices[index] = newDevice

        val state = relayStates.remove(oldId)
        if (state != null) {
            relayStates[newId] = state
        } else {
            initializeRelayState(newId)
        }

        val oldPrefix = "relay_${oldId}_"
        val newPrefix = "relay_${newId}_"
        val keys = listOf("onHour", "onMinute", "onPeriod", "offHour", "offMinute",
            "offPeriod", "isAutoMode", "isRelayOn", "daysMask", "modeType", "isInfinite", "pulseOnSec", "pulseOffSec")
        
        with(sharedPreferences.edit()) {
            keys.forEach { key ->
                val oldKey = "${oldPrefix}${key}"
                val newKey = "${newPrefix}${key}"
                if (sharedPreferences.contains(oldKey)) {
                    when (val value = sharedPreferences.all[oldKey]) {
                        is Boolean -> putBoolean(newKey, value)
                        is Int -> putInt(newKey, value)
                        is String -> putString(newKey, value)
                        is Float -> putFloat(newKey, value)
                        is Long -> putLong(newKey, value)
                    }
                    remove(oldKey)
                }
            }
            apply()
        }

        saveDevices()

        if (activeDetailDevice.value?.id == oldId) {
            activeDetailDevice.value = newDevice
        }

        return true
    }

    // ===================================================
    // Relay State Management
    // ===================================================

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
            daysMask = sharedPreferences.getInt("${prefix}daysMask", 0x7F),
            modeType = sharedPreferences.getInt("${prefix}modeType", 0),
            isInfinite = sharedPreferences.getBoolean("${prefix}isInfinite", false),
            pulseOnSec = sharedPreferences.getInt("${prefix}pulseOnSec", 10),
            pulseOffSec = sharedPreferences.getInt("${prefix}pulseOffSec", 10)
        )
        relayStates[relayId] = state
    }

    fun getRelayState(relayId: Int): RelayState {
        return relayStates[relayId]
            ?: run {
                Log.w(TAG, "getRelayState: relay $relayId not found, initializing default")
                val defaultState = RelayState()
                relayStates[relayId] = defaultState
                defaultState
            }
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
        state.modeType.value = sharedPreferences.getInt("${prefix}modeType", 0)
        state.isInfinite.value = sharedPreferences.getBoolean("${prefix}isInfinite", false)
        state.pulseOnSec.value = sharedPreferences.getInt("${prefix}pulseOnSec", 10)
        state.pulseOffSec.value = sharedPreferences.getInt("${prefix}pulseOffSec", 10)
    }

    fun saveStateManually(relayId: Int, showConfirmation: Boolean = true) {
        if (!isConnected.value) {
            if (showConfirmation) settingsSaveFailedEvent.value = true
            return
        }

        // Cancel previous queued/running job to avoid queue buildup.
        pendingSaveDeferreds[relayId]?.cancel()
        pendingSaveJobs[relayId]?.cancel()

        val job = viewModelScope.launch(Dispatchers.Main) {
            val mutex = saveMutexes.getOrNull(relayId) ?: return@launch
            mutex.withLock {
                // Re-check connection inside the mutex before executing
                if (!isConnected.value) {
                    if (showConfirmation) settingsSaveFailedEvent.value = true
                    return@withLock
                }

                val state = getRelayState(relayId)
                val snapshot = state.toSnapshot()

                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                pendingSaveDeferreds[relayId] = deferred

                var transaction: SaveTransaction? = null

                try {
                    // Always sync RTC (if in Auto Mode) and then send schedule details and active status to ESP32.
                    val synced = if (snapshot.isAutoMode) {
                        withContext(Dispatchers.IO) { syncRtcTime(isManual = false) }
                    } else {
                        true
                    }

                    if (synced) {
                        // Re-check connection before sending BLE command
                        if (!isConnected.value) {
                            if (showConfirmation) settingsSaveFailedEvent.value = true
                            return@withLock
                        }

                        // Push transaction ID to queue immediately before sending
                        val txId = saveTxCounter.incrementAndGet()
                        val tx = SaveTransaction(txId, System.currentTimeMillis())
                        transaction = tx
                        pendingTransactionQueues.getOrPut(relayId) { java.util.concurrent.ConcurrentLinkedQueue() }.add(tx)
                        activeTxIdMap[relayId] = txId

                        val sent = withContext(Dispatchers.IO) { sendOnOffTimes(relayId, snapshot, txId) }
                        if (!sent) {
                            // Remove the transaction since we failed to send it
                            pendingTransactionQueues[relayId]?.remove(transaction)
                            if (activeTxIdMap[relayId] == txId) {
                                activeTxIdMap.remove(relayId)
                            }
                            if (showConfirmation) settingsSaveFailedEvent.value = true
                            return@withLock
                        }
                    } else {
                        if (showConfirmation) {
                            rtcTimeSyncFailedEvent.value = true
                            settingsSaveFailedEvent.value = true
                        }
                        return@withLock
                    }

                    Log.i(TAG, "Waiting for ACK,T confirmation for relay $relayId (timeout 8 s)")
                    val success = kotlinx.coroutines.withTimeoutOrNull(8000L) { deferred.await() }
                    if (success == true) {
                        Log.i(TAG, "Save confirmed by firmware for relay $relayId — persisting to prefs")
                        saveRelaySnapshotToPrefs(relayId, snapshot)
                        reloadState(relayId) // SYNC live UI memory state with the successfully saved snapshot!
                        if (showConfirmation) settingsSavedEvent.value = true
                    } else {
                        val reason = if (success == null) "timeout" else "ERR,T"
                        Log.w(TAG, "Save failed for relay $relayId ($reason) — prefs not written")
                        if (showConfirmation) settingsSaveFailedEvent.value = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Save job for relay $relayId cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Save job exception for relay $relayId: ${e.message}", e)
                    if (showConfirmation) settingsSaveFailedEvent.value = true
                } finally {
                    pendingSaveDeferreds.remove(relayId)
                    val currentJob = coroutineContext[kotlinx.coroutines.Job]
                    if (currentJob != null && pendingSaveJobs[relayId] == currentJob) {
                        pendingSaveJobs.remove(relayId)
                    }
                    // Clean up transaction registration on job completion/timeout
                    transaction?.let { tx ->
                        pendingTransactionQueues[relayId]?.remove(tx)
                        if (activeTxIdMap[relayId] == tx.id) {
                            activeTxIdMap.remove(relayId)
                        }
                    }
                }
            }
        }
        pendingSaveJobs[relayId] = job
    }


    /**
     * Saves relay state to SharedPreferences without sending any commands.
     * Used by processScheduleUpdate to persist received state without
     * triggering a send-back loop.
     */
    private fun saveRelaySnapshotToPrefs(relayId: Int, snapshot: RelaySettingsSnapshot) {
        val prefix = "relay_${relayId}_"
        with(sharedPreferences.edit()) {
            putInt("${prefix}onHour", snapshot.onHour)
            putInt("${prefix}onMinute", snapshot.onMinute)
            putString("${prefix}onPeriod", snapshot.onPeriod)
            putInt("${prefix}offHour", snapshot.offHour)
            putInt("${prefix}offMinute", snapshot.offMinute)
            putString("${prefix}offPeriod", snapshot.offPeriod)
            putBoolean("${prefix}isAutoMode", snapshot.isAutoMode)
            putInt("${prefix}daysMask", snapshot.daysMask)
            putInt("${prefix}modeType", snapshot.modeType)
            putBoolean("${prefix}isInfinite", snapshot.isInfinite)
            putInt("${prefix}pulseOnSec", snapshot.pulseOnSec)
            putInt("${prefix}pulseOffSec", snapshot.pulseOffSec)
            apply()
        }
    }

    private fun saveRelayToPrefs(relayId: Int, state: RelayState) {
        saveRelaySnapshotToPrefs(relayId, state.toSnapshot())
    }

    fun hasLastConnectedDevice(): Boolean {
        return sharedPreferences.getString("lastConnectedDevice", null) != null
    }

    // ===================================================
    // Bluetooth Permissions & Pairing
    // ===================================================

    fun hasBluetoothPermissions(): Boolean {
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
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun safeCloseGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasPermission) {
            Log.w(TAG, "Cannot close BluetoothGatt: BLUETOOTH_CONNECT permission missing")
            return
        }

        try {
            Log.d(TAG, "Closing BluetoothGatt connection")
            gatt.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException closing BluetoothGatt", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception closing BluetoothGatt", e)
        }
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    // ===================================================
    // BLE Scanning Logic
    // ===================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val recordName = result.scanRecord?.deviceName
            val deviceName = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            val serviceUuids = result.scanRecord?.serviceUuids
            val hasService = serviceUuids?.any {
                it.uuid.toString().equals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", ignoreCase = true)
            } == true
            val lastAddr = sharedPreferences.getString("lastConnectedDevice", null)
            val isLastDevice = lastAddr != null && device.address.equals(lastAddr, ignoreCase = true)

            // Strict Filter: If the device name is advertised or known, it must match "ControlCore".
            // If the name is null or empty, we permit matching on service UUID.
            val isNameValid = (recordName == "ControlCore" || deviceName == "ControlCore") ||
                              (recordName.isNullOrEmpty() && deviceName.isNullOrEmpty())

            if (recordName == "ControlCore" || deviceName == "ControlCore" || (hasService && isNameValid) || isLastDevice) {
                viewModelScope.launch(Dispatchers.Main) {
                    if (!scannedDevices.any { it.address == device.address }) {
                        scannedDevices.add(device)
                        Log.d(TAG, "Discovered ControlCore device: ${device.address}")
                        stopScan()
                    }
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            viewModelScope.launch(Dispatchers.Main) {
                var foundAny = false
                for (result in results) {
                    val device = result.device
                    val recordName = result.scanRecord?.deviceName
                    val deviceName = try {
                        device.name
                    } catch (e: SecurityException) {
                        null
                    }
                    val serviceUuids = result.scanRecord?.serviceUuids
                    val hasService = serviceUuids?.any {
                        it.uuid.toString().equals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", ignoreCase = true)
                    } == true
                    val lastAddr = sharedPreferences.getString("lastConnectedDevice", null)
                    val isLastDevice = lastAddr != null && device.address.equals(lastAddr, ignoreCase = true)

                    // Strict Filter: If the device name is advertised or known, it must match "ControlCore".
                    // If the name is null or empty, we permit matching on service UUID.
                    val isNameValid = (recordName == "ControlCore" || deviceName == "ControlCore") ||
                                      (recordName.isNullOrEmpty() && deviceName.isNullOrEmpty())

                    if (recordName == "ControlCore" || deviceName == "ControlCore" || (hasService && isNameValid) || isLastDevice) {
                        if (!scannedDevices.any { it.address == device.address }) {
                            scannedDevices.add(device)
                            foundAny = true
                        }
                    }
                }
                if (foundAny) {
                    stopScan()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
            viewModelScope.launch(Dispatchers.Main) {
                isScanning.value = false
            }
        }
    }

    fun startScan(durationMs: Long = 10000L) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot start BLE scan: missing permissions")
            return
        }
        val adapter = getBluetoothAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE Scanner not available (Bluetooth might be disabled)")
            return
        }

        scannedDevices.clear()
        isScanning.value = true
        connectionFailedEvent.value = false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "BLE scan started (unfiltered)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while starting scan: ${e.message}")
            isScanning.value = false
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        if (!isScanning.value) return
        val adapter = getBluetoothAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while stopping scan: ${e.message}")
        }
        isScanning.value = false
        scanJob?.cancel()
        scanJob = null
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        // BLE connection does not require pairing, returning scanned devices instead.
        // Kept for backward compatibility with UI connection selection list.
        return scannedDevices.toList()
    }

    // ===================================================
    // Bluetooth BLE Connection
    // ===================================================

    private fun attemptAutoReconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null && hasBluetoothPermissions()) {
            val bluetoothAdapter = getBluetoothAdapter()
            val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(lastDeviceAddress)
            if (device != null) {
                reconnectJob?.cancel()
                reconnectJob = viewModelScope.launch(Dispatchers.IO) {
                    connectToDeviceWithRetry(device, maxReconnectAttempts)
                }
            }
        }
    }

    private suspend fun connectToDeviceWithRetry(
        device: BluetoothDevice,
        attemptsLeft: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                isConnecting.value = true
            }
            var attempts = attemptsLeft
            while (attempts > 0) {
                if (connectGattCompletable(device)) {
                    return@withContext true
                }
                attempts--
                if (attempts > 0) {
                    val attemptNum = attemptsLeft - attempts
                    val delayMs = reconnectDelayMs * (1 shl (attemptNum - 1))
                    Log.d(TAG, "BLE Connection failed, retrying in ${delayMs}ms... ($attempts attempts left)")
                    delay(delayMs)
                }
            }
            withContext(Dispatchers.Main) {
                disconnectionEvent.value = true
            }
            false
        } finally {
            withContext(Dispatchers.Main) {
                isConnecting.value = false
            }
        }
    }

    private suspend fun connectGattCompletable(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val connectionResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val callback = object : BluetoothGattCallback() {
            private var deviceName: String = "ControlCore"

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "GATT error: status=$status, newState=$newState")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT connected. Requesting MTU 256...")
                    try {
                        gatt.requestMtu(256)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception requesting MTU: ${e.message}")
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT disconnected")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU changed to $mtu, status: $status. Discovering services...")
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception discovering services: ${e.message}")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                    return
                }

                // Log all discovered services for debugging
                Log.d(TAG, "=== Discovered Services & Characteristics ===")
                for (service in gatt.services) {
                    Log.d(TAG, "Service: ${service.uuid}")
                    for (char in service.characteristics) {
                        Log.d(TAG, "  Characteristic: ${char.uuid}, properties: ${char.properties}")
                    }
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Target Service $SERVICE_UUID not found")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                    return
                }

                val rxChar = service.getCharacteristic(RX_CHAR_UUID)
                val txChar = service.getCharacteristic(TX_CHAR_UUID)

                if (rxChar == null || txChar == null) {
                    Log.e(TAG, "Rx/Tx characteristics not found in service")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                    return
                }

                rxCharacteristic = rxChar
                txCharacteristic = txChar
                bluetoothGatt = gatt

                // Enable notifications on Tx Characteristic
                try {
                    val success = gatt.setCharacteristicNotification(txChar, true)
                    if (!success) {
                        Log.e(TAG, "Failed to set characteristic notifications for Tx")
                        safeCloseGatt(gatt)
                        if (connectionResult.isActive) {
                            connectionResult.complete(false)
                        }
                        handleDisconnection()
                        return
                    }

                    val descriptor = txChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val descWriteSuccess = gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "Tx Notification setup. Descriptor write success: $descWriteSuccess")
                        if (!descWriteSuccess) {
                            Log.e(TAG, "Failed to write CCCD descriptor")
                            safeCloseGatt(gatt)
                            if (connectionResult.isActive) {
                                connectionResult.complete(false)
                            }
                            handleDisconnection()
                            return
                        }
                    } else {
                        Log.e(TAG, "CCCD descriptor not found on Tx Characteristic")
                        safeCloseGatt(gatt)
                        if (connectionResult.isActive) {
                            connectionResult.complete(false)
                        }
                        handleDisconnection()
                        return
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception enabling notifications: ${e.message}")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                    return
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                    Log.d(TAG, "CCCD written successfully. Connection fully ready.")
                    selectedDevice = device
                    sharedPreferences.edit()
                        .putString("lastConnectedDevice", device.address)
                        .apply()

                    viewModelScope.launch(Dispatchers.Main) {
                        isConnected.value = true
                        selectedDeviceName.value = try {
                            val rawName = device.name
                            if (rawName.isNullOrBlank() || rawName.length > 20 ||
                                rawName.contains("dysl", ignoreCase = true) ||
                                rawName.matches(Regex(".*[0-9a-fA-F]{12,}.*"))
                            ) {
                                "ControlCore"
                            } else {
                                rawName
                            }
                        } catch (e: SecurityException) {
                            "ControlCore"
                        }
                    }

                    if (connectionResult.isActive) {
                        connectionResult.complete(true)
                    }

                    // Request initial status and sync clock upon ready
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(200) // Brief delay to stabilize channel
                        awaitingHandshakeSync = true
                        writeMessage("Q\n")
                    }
                } else {
                    Log.e(TAG, "Descriptor write failed with status: $status")
                    safeCloseGatt(gatt)
                    if (connectionResult.isActive) {
                        connectionResult.complete(false)
                    }
                    handleDisconnection()
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == TX_CHAR_UUID) {
                    val data = characteristic.value ?: return
                    val message = String(data, Charsets.UTF_8)
                    viewModelScope.launch(messageDispatcher) {
                        processIncomingData(message)
                    }
                }
            }

            @Deprecated("Used for older API levels")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeDeferred?.complete(status)
            }
        }

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            if (gatt == null) {
                connectionResult.complete(false)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting GATT: ${e.message}")
            connectionResult.complete(false)
        }

        try {
            kotlinx.coroutines.withTimeout(15000L) {
                connectionResult.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "connectGattCompletable timed out after 15s")
            safeCloseGatt(bluetoothGatt)
            false
        }
    }

    private fun handleDisconnection() {
        viewModelScope.launch(Dispatchers.Main) {
            if (isConnected.value) {
                disconnectionEvent.value = true
            }
            isConnected.value = false
            selectedDevice = null
            selectedDeviceName.value = null
            rxCharacteristic = null
            txCharacteristic = null
            bluetoothGatt = null
            
            // Clean up any in-flight saves immediately so the UI is not left spinning/hanging
            pendingSaveDeferreds.forEach { (_, deferred) ->
                deferred.cancel()
            }
            pendingSaveDeferreds.clear()
            pendingSaveJobs.values.forEach { it.cancel() }
            pendingSaveJobs.clear()
            pendingTransactionQueues.clear()
            activeTxIdMap.clear()
        }
    }

    fun setSelectedDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions to connect")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            val success = connectToDeviceWithRetry(device, 1)
            if (!success) {
                withContext(Dispatchers.Main) {
                    connectionFailedEvent.value = true
                }
            }
        }
    }

    fun reconnect() {
        val lastDeviceAddress = sharedPreferences.getString("lastConnectedDevice", null)
        if (lastDeviceAddress != null) {
            val bluetoothAdapter = getBluetoothAdapter()
            val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(lastDeviceAddress)
            if (device != null) {
                setSelectedDevice(device)
            }
        }
    }

    fun clearSelectedDevice() {
        sharedPreferences.edit().remove("lastConnectedDevice").apply()
        closeConnection()
        selectedDevice = null
        selectedDeviceName.value = null
    }

    fun closeConnection() {
        reconnectJob?.cancel()
        safeCloseGatt(bluetoothGatt)
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        viewModelScope.launch(Dispatchers.Main) {
            isConnected.value = false
            selectedDevice = null
            selectedDeviceName.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        closeConnection()
    }

    // ===================================================
    // BLE Communication Protocol
    // ===================================================

    private suspend fun writeMessage(message: String): Boolean = writeMutex.withLock {
        val gatt = bluetoothGatt ?: return false
        val char = rxCharacteristic ?: return false

        val bytes = message.toByteArray(Charsets.UTF_8)
        val maxChunk = if (negotiatedMtu > 23) (negotiatedMtu - 3) else 20

        try {
            var offset = 0
            while (offset < bytes.size) {
                val chunkSize = Math.min(bytes.size - offset, maxChunk)
                val chunk = bytes.copyOfRange(offset, offset + chunkSize)
                
                char.value = chunk
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                val deferred = kotlinx.coroutines.CompletableDeferred<Int>()
                writeDeferred = deferred

                val initiated = gatt.writeCharacteristic(char)
                if (!initiated) {
                    Log.e(TAG, "writeCharacteristic failed to initiate on chunk offset $offset")
                    writeDeferred = null
                    return false
                }

                val status = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    deferred.await()
                }

                writeDeferred = null

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Write characteristic failed with status: $status")
                    return false
                }

                offset += chunkSize
            }
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during BLE write: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during BLE write: ${e.message}")
            return false
        }
    }

    /**
     * Send manual relay command: S,<id>,<0|1>\n
     */
    fun sendCommand(relayId: Int, state: Boolean) {
        val stateStr = if (state) "1" else "0"
        val command = "S,$relayId,$stateStr\n"
        lastManualCommandTime = System.currentTimeMillis()
        lastManualCommandTimeMap[relayId] = lastManualCommandTime
        lastManualCommandStateMap[relayId] = state
        manualCommandTimeoutJobs[relayId]?.cancel()
        manualCommandTimeoutJobs[relayId] = viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            if (lastManualCommandTimeMap[relayId] == lastManualCommandTime) {
                lastManualCommandTimeMap.remove(relayId)
                lastManualCommandStateMap.remove(relayId)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val sent = writeMessage(command)
            if (sent) {
                Log.d(TAG, "Sent relay command: $command")
            } else {
                Log.e(TAG, "Failed to send relay command: $command")
            }
        }
    }

    /**
     * Send full schedule configuration to ESP32:
     * Format: T,<id>,<onHr24>,<onMin>,<offHr24>,<offMin>,<auto(0|1)>,<daysMask>,<modeType(0|1)>,<isInfinite(0|1)>,<pulseOnSec>,<pulseOffSec>[,<txId>]\n
     */
    private suspend fun sendOnOffTimes(relayId: Int, snapshot: RelaySettingsSnapshot, txId: Long? = null): Boolean {
        var onHour24 = snapshot.onHour
        if (snapshot.onPeriod.trim().equals("PM", ignoreCase = true) && onHour24 != 12) {
            onHour24 += 12
        } else if (snapshot.onPeriod.trim().equals("AM", ignoreCase = true) && onHour24 == 12) {
            onHour24 = 0
        }

        var offHour24 = snapshot.offHour
        if (snapshot.offPeriod.trim().equals("PM", ignoreCase = true) && offHour24 != 12) {
            offHour24 += 12
        } else if (snapshot.offPeriod.trim().equals("AM", ignoreCase = true) && offHour24 == 12) {
            offHour24 = 0
        }

        val autoFlag = if (snapshot.isAutoMode) "1" else "0"
        val infiniteFlag = if (snapshot.isInfinite) "1" else "0"
        
        val baseCommand = "T,$relayId,$onHour24,${snapshot.onMinute},$offHour24,${snapshot.offMinute},$autoFlag,${snapshot.daysMask},${snapshot.modeType},$infiniteFlag,${snapshot.pulseOnSec},${snapshot.pulseOffSec}"
        val command = if (txId != null) "$baseCommand,$txId\n" else "$baseCommand\n"
        
        Log.d(TAG, "Sending schedule payload: $command")
        return writeMessage(command)
    }

    /**
     * Helper to initiate manual RTC sync from UI.
     */
    fun setRTCTime(isManual: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            syncRtcTime(isManual = isManual)
        }
    }

    /**
     * Synchronizes phone's local time with RTC on ESP32:
     * Format: R,<epochSec>\n
     */
    private suspend fun syncRtcTime(isManual: Boolean = false): Boolean = rtcSyncMutex.withLock {
        if (!isConnected.value) return@withLock false

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingRtcSyncDeferred = deferred

        if (isManual) {
            isManualRtcSyncPending = true
        } else {
            isRtcSyncPending = true
        }

        val currentEpochSec = Instant.now().epochSecond
        val command = "R,$currentEpochSec\n"

        Log.d(TAG, "Sending RTC sync payload: $command (epoch: $currentEpochSec)")
        val sent = writeMessage(command)
        if (!sent) {
            pendingRtcSyncDeferred = null
            if (isManual) isManualRtcSyncPending = false else isRtcSyncPending = false
            return@withLock false
        }

        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(4000L) {
                deferred.await()
            } ?: false
            result
        } finally {
            pendingRtcSyncDeferred = null
            if (isManual) isManualRtcSyncPending = false else isRtcSyncPending = false
        }
    }

    // ===================================================
    // Inbound Message Parsing
    // ===================================================

    private fun processIncomingData(chunk: String) {
        incomingMessageBuffer.append(chunk)
        var newlineIndex: Int
        while (incomingMessageBuffer.indexOf('\n').also { newlineIndex = it } != -1) {
            val completeLine = incomingMessageBuffer.substring(0, newlineIndex).trim()
            incomingMessageBuffer.delete(0, newlineIndex + 1)
            if (completeLine.isNotEmpty()) {
                processCompleteMessage(completeLine)
            }
        }
    }

    private fun processCompleteMessage(message: String) {
        Log.d(TAG, "Received BLE Packet: $message")
        val parts = message.split(",")

        when (parts[0]) {
            // Heartbeat response: H,OK,<rtcGood(0|1)>,<rtcLostPower(0|1)>
            "H" -> {
                if (parts.size >= 4) {
                    val rtcGood = parts[2] == "1"
                    val rtcLostPower = parts[3] == "1"
                    viewModelScope.launch(Dispatchers.Main) {
                        rtcFailedState.value = !rtcGood
                        rtcLostPowerState.value = rtcLostPower
                    }
                    if (awaitingHandshakeSync) {
                        awaitingHandshakeSync = false
                        viewModelScope.launch(Dispatchers.IO) {
                            syncRtcTime(isManual = false)
                        }
                    }
                }
            }

            // Real-Time Clock Sync ACK: R,OK
            "R" -> {
                if (parts.size >= 2 && parts[1] == "OK") {
                    val wasManual = isManualRtcSyncPending
                    val now = ZonedDateTime.now(ZoneId.systemDefault())
                    val formatted = now.format(DateTimeFormatter.ofPattern("MMM dd, yyyy - hh:mm a"))
                    sharedPreferences.edit().putString("last_rtc_sync_time", formatted).apply()

                    viewModelScope.launch(Dispatchers.Main) {
                        lastRtcSyncTime.value = formatted
                        rtcFailedState.value = false
                        rtcLostPowerState.value = false
                        if (wasManual) {
                            rtcTimeSetEvent.value = true
                        }
                    }
                    pendingRtcSyncDeferred?.complete(true)
                }
            }

            // Command Acknowledgment: ACK,T,<relayId>[,<txId>]
            "ACK" -> {
                if (parts.size >= 3 && parts[1] == "T") {
                    val relayId = parts[2].toIntOrNull()
                    val returnedTxId = if (parts.size >= 4) parts[3].toLongOrNull() else null
                    if (relayId != null) {
                        val activeTxId = activeTxIdMap[relayId]
                        if (returnedTxId != null) {
                            if (returnedTxId == activeTxId) {
                                activeTxIdMap.remove(relayId)
                                pendingSaveDeferreds[relayId]?.complete(true)
                            } else {
                                Log.w(TAG, "Ignoring stale ACK,T for relay $relayId: got txId=$returnedTxId, active=$activeTxId")
                            }
                        } else {
                            activeTxIdMap.remove(relayId)
                            pendingSaveDeferreds[relayId]?.complete(true)
                        }
                    }
                }
            }

            // Command Error: ERR,T,<relayId>[,<txId>],<reason> or ERR,R,...
            "ERR" -> {
                if (parts.size >= 3) {
                    when (parts[1]) {
                        "T" -> {
                            val relayId = parts[2].toIntOrNull()
                            val returnedTxId = if (parts.size >= 5) parts[3].toLongOrNull() else null
                            if (relayId != null) {
                                val activeTxId = activeTxIdMap[relayId]
                                if (returnedTxId != null) {
                                    if (returnedTxId == activeTxId) {
                                        activeTxIdMap.remove(relayId)
                                        pendingSaveDeferreds[relayId]?.complete(false)
                                    } else {
                                        Log.w(TAG, "Ignoring stale ERR,T for relay $relayId: got txId=$returnedTxId, active=$activeTxId")
                                    }
                                } else {
                                    activeTxIdMap.remove(relayId)
                                    pendingSaveDeferreds[relayId]?.complete(false)
                                }
                            }
                        }
                        "R" -> {
                            pendingRtcSyncDeferred?.complete(false)
                        }
                    }
                }
            }

            // Real-Time Clock Error Notification: E,RTC_LOST_POWER or E,RTC_COMM_FAIL
            "E" -> {
                if (parts.size >= 2) {
                    when (parts[1]) {
                        "RTC_LOST_POWER" -> {
                            viewModelScope.launch(Dispatchers.Main) {
                                rtcLostPowerState.value = true
                                rtcFailedState.value = false
                            }
                        }
                        "RTC_COMM_FAIL" -> {
                            viewModelScope.launch(Dispatchers.Main) {
                                rtcFailedState.value = true
                            }
                        }
                    }
                }
            }

            // Live Relay State Confirmation: S,<relayId>,<0|1>
            "S" -> {
                if (parts.size >= 3) {
                    val relayId = parts[1].toIntOrNull()
                    val state = parts[2] == "1"
                    if (relayId != null) {
                        viewModelScope.launch(Dispatchers.Main) {
                            val rs = getRelayState(relayId)
                            val lastManualTime = lastManualCommandTimeMap[relayId] ?: 0L
                            val lastManualState = lastManualCommandStateMap[relayId]
                            val timeSinceManual = System.currentTimeMillis() - lastManualTime

                            if (timeSinceManual < 3000L && lastManualState != null) {
                                if (state != lastManualState) {
                                    Log.d(TAG, "Ignoring stale relay update S,$relayId,$state - waiting for $lastManualState")
                                    return@launch
                                } else {
                                    lastManualCommandTimeMap.remove(relayId)
                                    lastManualCommandStateMap.remove(relayId)
                                    manualCommandTimeoutJobs[relayId]?.cancel()
                                }
                            }

                            rs.isRelayOn.value = state
                            sharedPreferences.edit().putBoolean("relay_${relayId}_isRelayOn", state).apply()
                        }
                    }
                }
            }

            // Live Schedule & Auto State Broadcast:
            // SCHED,<relayId>,<onHr>,<onMin>,<offHr>,<offMin>,<isAuto(0|1)>,<daysMask>,<modeType>,<isInfinite>,<pulseOnSec>,<pulseOffSec>
            "SCHED" -> {
                if (parts.size >= 12) {
                    val relayId = parts[1].toIntOrNull()
                    if (relayId != null) {
                        val onHr24 = parts[2].toIntOrNull() ?: 12
                        val onMin = parts[3].toIntOrNull() ?: 0
                        val offHr24 = parts[4].toIntOrNull() ?: 12
                        val offMin = parts[5].toIntOrNull() ?: 0
                        val isAuto = parts[6] == "1"
                        val daysMask = parts[7].toIntOrNull() ?: 0x7F
                        val modeType = parts[8].toIntOrNull() ?: 0
                        val isInfinite = parts[9] == "1"
                        val pulseOnSec = parts[10].toIntOrNull() ?: 10
                        val pulseOffSec = parts[11].toIntOrNull() ?: 10

                        // Convert 24-hr into 12-hr display formats
                        val onPeriod = if (onHr24 >= 12) "PM" else "AM"
                        val onHr12 = when {
                            onHr24 == 0 -> 12
                            onHr24 > 12 -> onHr24 - 12
                            else -> onHr24
                        }

                        val offPeriod = if (offHr24 >= 12) "PM" else "AM"
                        val offHr12 = when {
                            offHr24 == 0 -> 12
                            offHr24 > 12 -> offHr24 - 12
                            else -> offHr24
                        }

                        viewModelScope.launch(Dispatchers.Main) {
                            val rs = getRelayState(relayId)
                            rs.onHour.value = onHr12
                            rs.onMinute.value = onMin
                            rs.onPeriod.value = onPeriod
                            rs.offHour.value = offHr12
                            rs.offMinute.value = offMin
                            rs.offPeriod.value = offPeriod
                            rs.isAutoMode.value = isAuto
                            rs.daysMask.value = daysMask
                            rs.modeType.value = modeType
                            rs.isInfinite.value = isInfinite
                            rs.pulseOnSec.value = pulseOnSec
                            rs.pulseOffSec.value = pulseOffSec

                            saveRelayToPrefs(relayId, rs)
                        }
                    }
                }
            }
        }
    }

    // ===================================================
    // Event Reset Helpers
    // ===================================================

    fun resetDisconnectionEvent() {
        disconnectionEvent.value = false
    }

    fun resetRtcTimeSetEvent() {
        rtcTimeSetEvent.value = false
    }

    fun resetRtcTimeSyncFailedEvent() {
        rtcTimeSyncFailedEvent.value = false
    }

    fun resetSettingsSavedEvent() {
        settingsSavedEvent.value = false
    }

    fun resetSettingsSaveFailedEvent() {
        settingsSaveFailedEvent.value = false
    }
}

class RelayViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RelayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RelayViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
