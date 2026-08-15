# ControlHub - Release Notes v3.0.0

**Release Date:** August 16, 2026  
**Tag:** `v3.0.0`  
**Previous Versions:** `v2.0.0` (June 2026), `v1.0.0` (December 2025)  
**Maintained by:** [akhil274/Control-Hub](https://github.com/akhil274/Control-Hub)

---

## 🌟 Release Summary

ControlHub **v3.0.0 (ControlCore Architecture)** is the definitive production milestone for the ControlHub ecosystem. Building upon the 4-relay day scheduling foundation introduced in v2.0.0, v3.0.0 delivers **enterprise-grade reliability, real-time pulse execution, transactional BLE communications, and a completely elevated Compose UI design system**.

### Key Highlights:
1. **Precision Timer & Pulse Mode:** High-precision cyclic on/off pulse intervals (1s to 86,400s) for dosing pumps, wavemakers, misting systems, and auto-feeders.
2. **Deterministic FreeRTOS Queue Architecture:** Decoupled BLE RX callbacks from main execution loops using FreeRTOS queues, preventing BLE task starvation and eliminating packet drops.
3. **Strict EEPROM-Before-RAM Transaction Safety:** All state mutations and schedule changes are committed to non-volatile flash before altering volatile memory, with automatic RAM rollback on commit failure.
4. **Resilient Transaction ID (txId) Protocol:** `AtomicLong` request/response packet tracking with deterministic 5-second deferred timeout handlers and buffer cleanup.
5. **Conflict-Free Manual Override Engine:** Manual toggle commands immediately cancel active schedules in EEPROM, eliminating relay chatter and rapid ON/OFF flickering.
6. **Hardware Watchdog Recovery:** Integrated ESP32 hardware watchdog timer (30s `esp_task_wdt`) that automatically recovers from I2C bus hangs.
7. **Stitch Luxury Design System:** Re-engineered Compose UI with fluid layout spacing, native Material 3 vector icons, seamless status bar transitions, and animated background blends.

---

## 📊 Three-Version Architectural Comparison (v1 vs v2 vs v3)

| Architectural Domain | v1.0.0 (Dec 2025) | v2.0.0 (Jun 2026) | v3.0.0 (Aug 2026) - Current |
| :--- | :--- | :--- | :--- |
| **Relay Channels** | 2 fixed (Light, CO2) | 4 configurable relays | **4 fully independent configurable channels** |
| **GPIO Pinout** | GPIO 26 (L), 27 (C) | GPIO 25, 26, 27, 32 | **GPIO 25, 26, 27, 32 (Active-LOW verified)** |
| **Scheduling Modes** | Standard 24h Daily | Standard + Day-of-Week | **Auto (7-Day Mask) + Precision Timer/Pulse (24/7 or Windowed)** |
| **Pulse Mode Support** | ❌ None | ❌ None | **✅ Full (1s–86,400s ON / 1s–86,400s OFF)** |
| **BLE Concurrency** | Synchronous string read | Buffer string concatenation | **FreeRTOS Queue (`xQueueSend` / `xQueueReceive`)** |
| **BLE State Handshake** | Basic `H` ping | `H` ping | **TxId-tracked `ACK,T,<id>,<txId>` / `ERR,T` + AtomicLong map** |
| **EEPROM Safety** | Volatile RAM first | RAM first | **Strict EEPROM-Before-RAM with RAM Rollback** |
| **EEPROM Magic** | `0xABCD1234` | `0xABCD5678` | **`0xABCD5682` (Auto-initializes v3 structures)** |
| **RTC Fault Handling** | Bus re-init in loop | Year range check (2000–2099) | **Safe Relay Shutdown on Disconnect/Power Loss + Auto-Recovery** |
| **Watchdog Timer** | ❌ None | ❌ None | **✅ 30-Second Hardware WDT (`esp_task_wdt`)** |
| **Android App Framework** | Basic Views / XML | Jetpack Compose (Initial) | **Modern Compose M3 + Luxury Stitch Design Tokens** |
| **Icon System** | Static Drawables | Text Emojis | **100% Vector Material Icons (`Lightbulb`, `Co2`, `WaterDrop`, etc.)** |
| **Status Bar Transitions** | Standard OS bar | Animated DisposableEffect (flashing) | **Stable `SideEffect` + `DisposableEffect(Unit)` (zero flicker)** |

---

## 🛠️ ESP32 Firmware Deep Dive (`esp32_controlhub.ino`)

### 1. Structure Evolution: `RelaySchedule v3`
The EEPROM storage structure has expanded to natively store pulse mode parameters without breaking dynamic address calculation:

```diff
 struct RelaySchedule {
   int onHour;
   int onMinute;
   int offHour;
   int offMinute;
   uint8_t daysMask;   // bit 0=Mon, 1=Tue, ..., 6=Sun
   bool isActive;
+  int modeType;       // 0 = Auto/Standard, 1 = Timer/Pulse
+  bool isInfinite;    // true = 24/7 continuous pulsing, false = windowed pulsing
+  int pulseOnSec;     // ON duration in seconds (1 - 86,400s)
+  int pulseOffSec;    // OFF duration in seconds (1 - 86,400s)
 };
```

### 2. EEPROM-Before-RAM Reliability Pattern
In v1/v2, if an EEPROM write failed or power was cut mid-write, the microcontroller's RAM held a state that did not match flash memory. In v3.0.0, all schedule and state modifications follow a transactional rollback pattern:

```cpp
// Save to EEPROM first, then update RAM (EEPROM-before-RAM safety)
bool prevState = relayStates[idx];
relayStates[idx] = newState;
EEPROM.put(getRelayStatesAddress(), relayStates);
if (!EEPROM.commit()) {
  Serial.println("ERROR: Failed to save relay states to EEPROM");
  relayStates[idx] = prevState; // Revert RAM on EEPROM failure
  setRelayState(RELAY_PINS[idx], prevState);
  safeSerialPrint("ERR,S,EEPROM_FAIL\n");
  return;
}
setRelayState(RELAY_PINS[idx], newState);
```

### 3. Bluetooth Command Protocol Specification (v3.0.0)

#### Schedule Command (`T`):
```text
T,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>,<active>,<modeType>,<isInfinite>,<pulseOnSec>,<pulseOffSec>,<txId>
```
* **Parameters (13 Total):**
  1. `T`: Command identifier
  2. `<id>`: Relay ID (`1`–`4`)
  3. `<onH>`: ON Hour (`0`–`23`)
  4. `<onM>`: ON Minute (`0`–`59`)
  5. `<offH>`: OFF Hour (`0`–`23`)
  6. `<offM>`: OFF Minute (`0`–`59`)
  7. `<daysMask>`: 7-bit active days mask (`1`–`127`)
  8. `<active>`: `1` for enabled, `0` for disabled
  9. `<modeType>`: `0` for Standard Auto, `1` for Timer/Pulse
  10. `<isInfinite>`: `1` for 24/7 Continuous, `0` for Schedule Windowed
  11. `<pulseOnSec>`: ON phase duration (`1`–`86400`)
  12. `<pulseOffSec>`: OFF phase duration (`1`–`86400`)
  13. `<txId>`: Optional transaction sequence ID for atomic ACK/ERR confirmation

#### Response Packets:
* `ACK,T,<id>,<txId>`: Confirms successful EEPROM commit and activation.
* `ERR,T,<id>,<txId>,<REASON>`: Returns explicit failure reason (`INVALID_SCHEDULE`, `EEPROM_COMMIT_FAILED`, etc.).
* `P,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>,<active>,<modeType>,<isInfinite>,<pulseOnSec>,<pulseOffSec>`: Full state dump on connection.

---

## 📱 Android Mobile Application Deep Dive

### 1. Concurrency & Bluetooth Lifecycle Engine (`RelayViewModel.kt`)
* **Transaction Map & Timeout:** Requests utilize an `AtomicLong` transaction generator with a 5000ms `CompletableDeferred` timeout. Stale requests are automatically cancelled in `finally` blocks.
* **Synchronized Buffer Flushing:** `synchronized(incomingMessageBuffer) { incomingMessageBuffer.setLength(0) }` guarantees zero packet corruption across rapid reconnects.
* **Thread-Safe Memory Barriers:** Critical write deferred variables use `@Volatile` to ensure visibility across Bluetooth Gatt callbacks and UI coroutine dispatchers.

### 2. Modern UI / UX Refactor
* **Material Design 3 Vector Icons:** Fully migrated from text emojis to vector assets (`Lightbulb`, `Co2`, `WaterDrop`, `Thermostat`, `Bolt`).
* **Luxury Status Formatting:** Replaced verbose 30-character strings with elegant, concise Stitch tokens (`Auto · On 10:00 AM`, `Auto · Off 10:00 PM`, `Timer · Pulse`).
* **Anti-Overlap Spacing:** Added dedicated $16\text{dp}$ right margins and isolated $1.05\times$ toggle switch bounding boxes, providing $\ge 25\text{dp}$ of guaranteed clearance.
* **3D Cylindrical Tumbler Dial:** Infinite scrolling time selector with haptic feedback gated to physical user drag events (`isScrollInProgress`).
* **State Preservation:** Integrated `rememberSaveable` across all dialogs, tab selections, and input sheets for orientation-change survival.

---

## 🐛 Bug Fixes & Resolved Issues

1. **Relay Flickering on Mode Transition (Fixed):** Resolved an issue where disabling Auto mode without saving and toggling Manual mode caused a rapid ON/OFF relay flicker due to the ESP32 schedule engine. Manual commands now unconditionally deactivate active schedules.
2. **Buffer Residue on BLE Reconnect (Fixed):** Old fragmented packets in the BLE receiver buffer are now cleared atomically upon disconnection.
3. **System Bar Flickering (Fixed):** Replaced continuous `DisposableEffect(animatedBgColor)` with static `SideEffect` to prevent screen bar flickering during color transitions.
4. **Clock Dial Haptic Stutter (Fixed):** Gated haptic feedback triggers strictly to active user scrolling, eliminating background haptic clicks during programmatic time syncs.
5. **EEPROM Byte Normalization (Fixed):** Added raw byte sanitization during boot to catch uninitialized flash memory without causing infinite reset loops.
6. **Minute Slider Off-By-One (Fixed):** Fixed pulse duration minute slider range from `0..60` to valid `0..59` minutes.
7. **BLE Advertising Interval Parameter Typo (Fixed):** Fixed `setMinPreferred` called twice to `setMinPreferred(0x06)` and `setMaxPreferred(0x12)`.
8. **UI Text/Switch Overlap (Fixed):** Shortened status text and added layout padding to prevent schedule strings from touching the toggle switch.

---

## 📦 Release Assets

| Asset Name | Description | Size |
| :--- | :--- | :---: |
| **`ControlHub-v3.0.0.apk`** | Production Android Application APK (minSdk 29, targetSdk 35) | ~8.3 MB |
| **`ControlHub-ESP32-Firmware-v3.0.0.zip`** | Complete ESP32 Arduino sketch, dependencies, and pin configurations | ~11 KB |
| **`ControlHub-v3.0.0-Source-Code.zip`** | Full clean repository source code archive | ~340 KB |

---

## 🚀 Upgrade Guide

### 1. Microcontroller Firmware Flash
1. Open Arduino IDE or Arduino CLI.
2. Select target board: **ESP32 Dev Module** (FQBN: `esp32:esp32:esp32`).
3. Flash `esp32_controlhub.ino` to your ESP32 board over USB/Serial.
4. *Note:* Magic number `0xABCD5682` will automatically format and initialize all 4 EEPROM schedule slots with v3 default structures.

### 2. Android Mobile App Installation
1. Download **`ControlHub-v3.0.0.apk`** to your Android smartphone (Android 10+).
2. Install or update over the existing app (`adb install -r ControlHub-v3.0.0.apk`).
3. Open the app, grant Bluetooth permissions, and connect to **ControlCore**.

---

⭐ **Star and follow the project on GitHub:** [akhil274/Control-Hub](https://github.com/akhil274/Control-Hub)
