# ControlHub 3.0 — Full Project Audit Prompt

## Instructions for Auditor AI

You are performing a **comprehensive production-readiness code audit** of the ControlHub 3.0 project. This is a real IoT project that controls physical hardware (aquarium relay switches) over Bluetooth. The developer is a **first-time developer** who built this with AI assistance, so pay special attention to safety-critical logic, edge cases, and best practices that a beginner might miss.

**Audit scope:** All source code — logic correctness, thread safety, protocol reliability, error handling, memory safety, UI correctness, and production readiness. DST (Daylight Saving Time) is out of scope (the user's country doesn't use it).

**Do NOT suggest:** Migrating to Room/Hilt/MVVM-Clean architecture, adding unit tests (this is a solo hobby project), or restructuring the entire project. Focus on **bugs, logic errors, safety issues, and practical improvements** that can be fixed without major rewrites.

---

## 1. Project Overview

**ControlHub** is a Bluetooth Low Energy (BLE) aquarium automation system consisting of:

- **ESP32 microcontroller** ("ControlCore") — Controls 4 physical relays (lights, CO2, pumps, etc.) with schedule-based automation and manual toggle
- **Android app** — Jetpack Compose UI that communicates with the ESP32 over BLE to configure schedules, toggle relays, and monitor status
- **DS3231 RTC module** — Real-time clock for offline schedule execution (survives power loss)

### What it does:
1. User connects phone → ESP32 via BLE
2. User configures ON/OFF schedules per relay (with day-of-week selection)
3. ESP32 runs schedules autonomously using RTC, even without phone
4. User can manually override relay states
5. Supports "Timer/Pulse" mode (cyclic ON/OFF with configurable durations)
6. All schedules and relay states persist in EEPROM across power cycles

---

## 2. Architecture

```
┌─────────────────────────────────────────────────┐
│                 Android App                      │
│  ┌──────────┐  ┌──────────────┐  ┌───────────┐ │
│  │ Compose  │  │ RelayViewModel│  │ BLE GATT  │ │
│  │ UI Screens│←→│ (State +     │←→│ Client    │ │
│  │          │  │  Business     │  │           │ │
│  │          │  │  Logic)       │  │           │ │
│  └──────────┘  └──────────────┘  └─────┬─────┘ │
└────────────────────────────────────────┼────────┘
                                         │ BLE (Nordic UART Service)
                                         │ Text protocol over NUS
┌────────────────────────────────────────┼────────┐
│                 ESP32                   │        │
│  ┌──────────┐  ┌──────────────┐  ┌─────┴─────┐ │
│  │ 4x Relay │  │ Schedule     │  │ BLE GATT  │ │
│  │ Outputs  │←─│ Engine +     │←→│ Server    │ │
│  │ (GPIO)   │  │ Command      │  │ (NUS)     │ │
│  │          │  │ Parser       │  │           │ │
│  └──────────┘  └──────┬───────┘  └───────────┘ │
│                       │                          │
│              ┌────────┴────────┐                │
│              │ DS3231 RTC      │                │
│              │ (I2C)           │                │
│              └────────┬────────┘                │
│              ┌────────┴────────┐                │
│              │ EEPROM          │                │
│              │ (512 bytes)     │                │
│              └─────────────────┘                │
└──────────────────────────────────────────────────┘
```

---

## 3. File Structure

### ESP32 Firmware
```
esp32-firmware/esp32_controlhub/
└── esp32_controlhub.ino          # Single-file firmware (~1100 lines)
```

### Android App
```
android-app/app/src/main/java/com/example/controlhub/
├── MainActivity.kt               # Entry point, sets up Compose + ViewModel
├── SplashActivity.kt             # Animated splash screen
├── AppNavigation.kt              # Tab navigation (Devices/Notes/Settings) + centralized event consumption
├── RelayViewModel.kt             # Core logic: BLE, state management, command protocol (~2135 lines)
└── ui/
    ├── screens/
    │   ├── DevicesScreen.kt       # Main device list (connected state)
    │   ├── DeviceDetailScreen.kt  # Per-relay schedule editor (largest UI file, ~70KB)
    │   ├── DisconnectedScreen.kt  # BLE scanning + connection UI
    │   ├── NotesScreen.kt         # Placeholder for future feature
    │   └── SettingsScreen.kt      # RTC sync, about dialog, app settings
    ├── components/
    │   ├── ClockDialPicker.kt     # Custom circular time picker widget
    │   ├── DaySelector.kt         # Day-of-week toggle buttons
    │   └── DeviceCard.kt          # Relay card with status indicators
    └── theme/
        ├── Color.kt               # Color palette definitions
        ├── Theme.kt               # Material3 dark theme setup
        └── Type.kt                # Typography definitions
```

---

## 4. BLE Communication Protocol

Communication uses **Nordic UART Service (NUS)** — text-based commands over BLE characteristics.

### Commands (App → ESP32)

| Command | Format | Description |
|---------|--------|-------------|
| **Manual toggle** | `S,<relayId>,<0\|1>` | Turn relay ON (1) or OFF (0) |
| **Schedule save** | `T,<relayId>,<onH>,<onM>,<offH>,<offM>,<daysMask>[,<active>,<modeType>,<isInfinite>,<pulseOnSec>,<pulseOffSec>][,<txId>]` | Save schedule (7, 8, 9, 12, or 13 parts) |
| **RTC time set** | `R,<yyyy>,<MM>,<dd>,<HH>,<mm>,<ss>` | Sync RTC clock (24-hour format) |

### Responses (ESP32 → App)

| Response | Format | Description |
|----------|--------|-------------|
| **State update** | `U,<relayId>,<0\|1>` | Relay state changed |
| **Schedule ACK** | `ACK,T,<relayId>[,<txId>]` | Schedule saved successfully |
| **Schedule error** | `ERR,T,<relayId>[,<txId>],<reason>` | Schedule save failed |
| **RTC success** | `R,OK` | RTC time set successfully |
| **RTC error** | `ERR,R,INVALID_VALUES` | Invalid RTC values |
| **RTC failure** | `E,RTC_FAIL` | RTC hardware failure detected |
| **RTC lost power** | `E,RTC_LOST_POWER` | RTC lost power, time invalid |
| **EEPROM failure** | `ERR,S,EEPROM_FAIL` | Relay state EEPROM save failed |
| **Unknown command** | `ERR,UNKNOWN_CMD` | Unrecognized command |

### Transaction ID System
The app generates a unique `txId` (AtomicLong counter) for each schedule save. The ESP32 echoes it back in `ACK,T` / `ERR,T`. This ensures the app matches responses to the correct pending save, preventing stale-ACK bugs when saves are rapid-fired.

---

## 5. ESP32 Firmware Key Logic

### Safety-Critical Patterns
- **EEPROM-before-RAM**: Schedule saves write to EEPROM first, then update RAM only on successful commit. If EEPROM fails, RAM stays unchanged (the relay keeps its old schedule).
- **Manual relay EEPROM-before-RAM**: Same pattern — if EEPROM commit fails, RAM state is reverted and relay pin is set back to previous state.
- **RTC failure shutdown**: If RTC hardware fails or loses power, ALL relays are forced OFF as a safety measure (no schedule can run without reliable time).
- **Hardware watchdog**: 30-second WDT timeout with panic reset — recovers from I2C hangs or firmware freezes.

### Schedule Engine
- `checkSchedules()` runs every 5 seconds in `loop()`
- For each relay: checks if current RTC time falls within the ON window for today's day-of-week
- Supports "Timer/Pulse" mode: cyclic ON for X seconds → OFF for Y seconds (for CO2 solenoids, dosing pumps)
- Pulse mode can be "infinite" (24/7 cycling) or "windowed" (only pulse within the schedule window)

### EEPROM Layout
```
Address 0:          Magic number (0xABCD5682) — v3 format identifier
Address 4+:         RelaySchedule[0..3] — 4 schedule structs
After schedules:    Manual relay states (bool[4])
```

---

## 6. Android App Key Logic

### RelayViewModel.kt (~2135 lines)
This is the **single largest and most critical file**. It handles:
- BLE scanning, connection, GATT operations
- Command sending with chunking (BLE MTU splitting for messages > 20 bytes)
- Response parsing and routing
- Transaction-based save confirmation with timeout
- SharedPreferences persistence of all relay settings
- Auto RTC sync on connection
- Reconnection logic with exponential backoff
- State management via Compose `MutableState`

### Key Concurrency Patterns
- `AtomicLong` for `saveTxCounter` (transaction ID generation)
- `Mutex` for critical BLE write sections
- `pendingTransactionQueues` (per-relay `Channel`) to serialize concurrent saves
- `activeTxIdMap` tracks which txId is pending per relay
- `finally` blocks ensure transaction cleanup on timeout or cancellation

### Event Architecture
- One-shot events (`rtcTimeSetEvent`, `rtcTimeSyncFailedEvent`, `disconnectionEvent`) are centralized in `AppNavigation.kt`
- `LaunchedEffect` is keyed on the event value (not `Unit`) to ensure rapid successive events all trigger

### Navigation
- 3 tabs: Devices, Notes, Settings
- Devices tab switches between `ConnectedDevicesScreen` (device list) and `DisconnectedScreen` (BLE scanner)
- Tapping a device card opens `DeviceDetailScreen` (full-screen schedule editor)

---

## 7. Recent Fixes Already Applied

The following issues have already been fixed in this codebase. **Do NOT re-report these:**

1. ✅ **Transaction ID protocol** — App sends txId, ESP32 echoes it, app matches responses
2. ✅ **LaunchedEffect(Unit) not re-triggering** — Now keyed on event values
3. ✅ **Duplicate RTC event consumption** — Centralized in AppNavigation
4. ✅ **disconnectionEvent never observed** — Now shows toast in AppNavigation
5. ✅ **Permanently-denied permissions** — Shows dialog redirecting to App Settings
6. ✅ **Hardware watchdog added** — 30s WDT with auto-reset
7. ✅ **Silent failures** — Invalid relay IDs and unknown commands now send BLE errors
8. ✅ **Manual relay RAM-before-EEPROM** — Fixed to EEPROM-before-RAM with rollback
9. ✅ **EEPROM failure notifications** — Now sent via BLE
10. ✅ **Dead code removed** — Bulk `saveSchedulesToEEPROM()` removed
11. ✅ **Version string fixed** — v2 → v3
12. ✅ **"Retry" button renamed** — Now says "Dismiss" (it wasn't retrying)
13. ✅ **About dialog text** — Notes correctly says "coming in v3.1"
14. ✅ **Hardcoded colors** — Replaced with theme references
15. ✅ **Deprecated AppTab.values()** — Replaced with AppTab.entries
16. ✅ **getRelayState() crash** — No longer throws, returns safe default
17. ✅ **RTC Sync button** — Stays enabled even when rtcFailed
18. ✅ **Text emojis** — Replaced with Material Design vector icons

---

## 8. What to Audit

Please analyze the **entire codebase** for:

### Critical / High
- **Logic errors** that could cause incorrect relay states, missed schedules, or data corruption
- **Race conditions** in BLE communication or Compose state management
- **Memory leaks** (BLE connections, coroutines, callbacks not cleaned up)
- **Crash paths** (null dereference, array bounds, uncaught exceptions)
- **Safety issues** (relays stuck ON when they should be OFF, schedule not executing)

### Medium
- **Protocol edge cases** (malformed BLE data, partial messages, MTU boundary issues)
- **Reconnection reliability** (what happens on flaky BLE connections)
- **EEPROM wear** (excessive writes shortening flash lifespan)
- **State synchronization** (app state vs. ESP32 state drift)
- **UI state bugs** (dialog state lost on rotation, composable recomposition issues)

### Low
- **Code quality** (unused imports, dead code, naming conventions)
- **Performance** (unnecessary recompositions, inefficient string operations on ESP32)
- **Accessibility** (missing content descriptions, touch targets too small)
- **Thread safety** (plain Boolean flags accessed from multiple threads)

### Output Format
For each issue found, provide:
1. **Severity**: Critical / High / Medium / Low
2. **File and line number** (or line range)
3. **Description** of the problem
4. **Risk**: What could go wrong in production
5. **Suggested fix**: Concrete code change (not just "refactor this")

Score the project out of 10 for production readiness at the end.

---

## 9. Hardware Context

- **ESP32-WROOM-32** (ESP32-D0WD-V3 rev 3.1), Dual Core 240MHz
- **DS3231 RTC** via I2C (Wire library)
- **4x relay module** (Active LOW — HIGH = relay OFF, LOW = relay ON)
- **Relay pins**: GPIO 25, 26, 27, 32
- **BLE**: Nordic UART Service (NUS) with 20-byte MTU default
- **EEPROM**: 512 bytes (ESP32 flash-emulated)
- **Power**: USB or 5V DC adapter
- **Target**: Aquarium automation (lights, CO2, pumps, heaters)

---

## 10. Build Information

- **Android**: Kotlin, Jetpack Compose, Material3, minSdk 24, targetSdk 35
- **ESP32**: Arduino framework, ESP32 Arduino Core 3.3.2, ESP-IDF v5.5
- **Libraries**: RTClib 2.1.4, native ESP32 BLE (not ArduinoBLE)
- **Build tools**: Gradle 8.13, arduino-cli
