# 🎛️ ControlHub — Release Notes v2.0.0

**Release Date:** June 16, 2026  
**Tag:** `v2.0.0`  
**Previous Version:** `v1.0.0` (December 2025)

---

## 📌 Release Summary

ControlHub v2.0.0 is a **major upgrade** over v1.0.0. It overhauls the ESP32 firmware architecture from a fixed 2-relay system to a scalable 4-relay engine, introduces day-of-week scheduling, redesigns the Android app with a premium "Abyssal Flow" dark theme, adds dynamic device management, and fixes **9 critical bugs** across the entire stack.

---

## 🔧 ESP32 Firmware Changes

### Architecture Overhaul — 2 Relays → 4 Relays

| Aspect | v1.0.0 | v2.0.0 |
|:---|:---|:---|
| **Relay Count** | 2 fixed (Light + CO₂) | 4 configurable relays |
| **Relay Pins** | `GPIO 26` (Light), `GPIO 27` (CO₂) | `GPIO 25`, `GPIO 26`, `GPIO 27`, `GPIO 32` |
| **Relay Variables** | Two separate booleans: `lightRelayState`, `co2RelayState` | Single array: `bool relayStates[4]` |
| **Schedule Storage** | Two separate structs: `lightSchedule`, `co2Schedule` | Single array: `RelaySchedule schedules[4]` |
| **EEPROM Layout** | Fixed addresses: `LIGHT_SCHEDULE_ADDRESS`, `CO2_SCHEDULE_ADDRESS` | Dynamic: `getScheduleAddress(relayIndex)` calculates offset |
| **EEPROM Magic** | `0xABCD1234` | `0xABCD5678` (forces re-initialization on upgrade) |

### New Feature — Day-of-Week Scheduling

v1.0.0 had **no day selection** — schedules ran every single day, 7 days a week.

v2.0.0 adds a `daysMask` bitmask (7-bit, `bit 0 = Mon` through `bit 6 = Sun`) to each `RelaySchedule` struct:

```diff
 struct RelaySchedule {
   int onHour;
   int onMinute;
   int offHour;
   int offMinute;
+  uint8_t daysMask;   // bit 0=Mon, 1=Tue, ..., 6=Sun
   bool isActive;
 };
```

The scheduler now checks `daysMask & (1 << dayBit)` before evaluating whether a relay should be on. This lets users set schedules like "Light ON only on weekdays" or "CO₂ ON only on Mon/Wed/Fri".

### Bluetooth Command Protocol Changes

**Manual Control Commands:**

| v1.0.0 | v2.0.0 | Description |
|:---|:---|:---|
| `L1` / `L0` | `11` / `10` | Toggle relay 1 (was Light) |
| `C1` / `C0` | `21` / `20` | Toggle relay 2 (was CO₂) |
| — | `31` / `30` | Toggle relay 3 (new) |
| — | `41` / `40` | Toggle relay 4 (new) |

v1.0.0 used letter-based codes (`L` for Light, `C` for CO₂). v2.0.0 uses **numeric relay IDs** (`1`–`4`) to support the dynamic device model.

**Schedule Commands (T):**

| v1.0.0 | v2.0.0 |
|:---|:---|
| `T,L,HH,MM,HH,MM` (6 params) | `T,<id>,HH,MM,HH,MM,<daysMask>,<active>` (8 params) |

- Parameter 2 changed from letter code (`L`/`C`) to numeric relay ID (`1`–`4`)
- Added parameter 7: `daysMask` (integer, 7-bit bitmask)
- Added parameter 8: `active` flag (`1` or `0`) — allows the app to explicitly enable or disable a schedule

**Schedule Sync Messages (P):**

| v1.0.0 | v2.0.0 |
|:---|:---|
| `P,L,HH,MM,HH,MM,<active>` (7 params) | `P,<id>,HH,MM,HH,MM,<daysMask>,<active>` (8 params) |

- Added `daysMask` field so the app knows which days are selected
- Relay identifier changed from `L`/`C` to numeric `1`–`4`

**State Update Messages (S):**

| v1.0.0 | v2.0.0 |
|:---|:---|
| `S,L,<0\|1>` | `S,<id>,<0\|1>` |
| `S,C,<0\|1>` | (uses numeric id) |

**RTC Time Set (R):** No changes. Both versions use `R,yyyy,MM,dd,HH,mm,ss`.

### Bug Fix — Overnight Schedule + Day Mask (Relay Stuck ON)

**v1.0.0 Bug:** The scheduler evaluated schedules only for the current day. If a schedule was set for **Monday only** with ON at 22:00 and OFF at 02:00, the relay would turn ON Monday at 22:00 but would **never turn OFF** on Tuesday because Tuesday is not in the day mask — the entire evaluation was skipped.

**v2.0.0 Fix:** `checkSchedules()` was rewritten to evaluate **two scenarios** for each relay:
1. **Today's schedule** — if today's day bit is set, check if the current time is within the schedule window.
2. **Yesterday's overnight schedule** — if yesterday's day bit was set AND the schedule is an overnight schedule (ON time > OFF time), check if the current time is before yesterday's OFF time.

This ensures overnight schedules that cross midnight always reach their OFF transition.

### Bug Fix — Manual Toggle Overridden by Auto-Mode (60s Revert)

**v1.0.0 Bug:** When a manual toggle command (e.g., `L0`) was received, the firmware set `isActive = false` and saved to EEPROM. But the scheduler ran every 60 seconds and checked if `isActive == true` — since the schedule data had already been loaded and `isActive` was set to `false`, the manual toggle was permanent. This actually caused the **opposite** problem: toggling manually killed auto mode forever until a new schedule was sent.

Additionally, the app's `saveStateManually()` in manual mode called `sendCommand()`, which sent a redundant manual command to the ESP32. This side-effected `schedules[idx].isActive = false`, permanently disabling auto mode on the firmware even though the app UI still showed auto mode as enabled.

**v2.0.0 Fix:** 
- The 8th parameter (`active`) in the `T` command now explicitly syncs the auto mode state. When the user toggles Auto→Manual in the app, it sends a `T` command with `active=0`, which properly disables the schedule without corrupting state.
- In manual mode, `saveStateManually()` now only persists state locally — it does NOT send a redundant manual command to the ESP32.

### Bug Fix — EEPROM Wear (Excessive Writes)

**v1.0.0 Bug:** Every single manual ON/OFF toggle (`L1`, `L0`, `C1`, `C0`) called `saveSchedulesToEEPROM()`, writing all schedules to EEPROM even though manual toggles don't change schedule data. EEPROM has a ~100,000 write cycle limit. Rapid toggling (e.g., during testing) could wear it out in days.

**v2.0.0 Fix:** `saveSchedulesToEEPROM()` is removed from manual command handlers. Manual toggles only change the volatile `relayStates[]` array. EEPROM writes only happen when a schedule is actually modified (the `T` command).

### Bug Fix — I2C Bus Re-initialization

**v1.0.0 Bug:** `checkSchedules()` called `rtc.begin()` every 60 seconds as a "health check". `rtc.begin()` re-initializes the entire I2C bus, which can cause brief communication glitches and bus errors, especially with electrically noisy relay switching nearby.

**v2.0.0 Fix:** Replaced `rtc.begin()` with `rtc.now()` and validates `year()` is in the 2000–2099 range. This reads from the RTC without re-initializing the bus.

### New Feature — Instant Schedule Evaluation on Save

**v1.0.0 Behavior:** After saving a schedule or syncing the RTC time, the relay state was only evaluated on the next 60-second RTC check interval. If the user saved a schedule that should activate immediately (e.g., current time is 1:40 AM and ON time is set to 1:40 AM), they would wait up to 60 seconds before seeing the relay turn on.

**v2.0.0 Feature:** `checkSchedules()` is now called immediately:
- **On boot** (at the end of `setup()`) — relay states are corrected instantly on startup
- **After saving a schedule** (`T` command) — relay reacts immediately to the new schedule
- **After syncing RTC time** (`R` command) — schedules are immediately re-evaluated against the new time
- The RTC check timer (`lastRTCCheck`) is also reset to prevent a redundant check seconds later

### New Feature — Day-of-Week Bit Helper

Added `dayOfWeekToBit()` function that converts the RTC library's `dayOfTheWeek()` (0=Sunday through 6=Saturday) to the bitmask convention (bit 0=Monday through bit 6=Sunday):

```cpp
int dayOfWeekToBit(int rtcDow) {
  // RTC: 0=Sun, 1=Mon, ..., 6=Sat
  // Bitmask: 0=Mon, 1=Tue, ..., 6=Sun
  return (rtcDow == 0) ? 6 : (rtcDow - 1);
}
```

---

## 📱 Android App Changes

### Architecture Overhaul — Fixed Relays → Dynamic Device Management

| Aspect | v1.0.0 | v2.0.0 |
|:---|:---|:---|
| **Device Model** | Hardcoded "Light" and "CO₂" string keys | Dynamic `DeviceInfo` data class with `id`, `name`, `iconType` |
| **Max Devices** | 2 (fixed) | 4 (user-configurable) |
| **Device Names** | Fixed "Light" and "CO₂" | User-editable (inline `BasicTextField`) |
| **Device Icons** | None | Selectable: 💡 lightbulb, 🫧 CO₂, 💧 water_drop, 🌡️ thermostat |
| **State Map Key** | `String` (relay name) | `Int` (relay ID 1–4) |
| **SharedPrefs Key** | `"RelayPrefs"` with `"Light_onHour"` format | `"RelayPrefs_v2"` with `"relay_1_onHour"` format |
| **Add/Remove** | Not possible | `addDevice()` / `removeDevice()` with socket ID selection |

**New Data Models:**
```kotlin
// v2.0.0 — Dynamic device info
data class DeviceInfo(
    val id: Int,                      // 1-4, maps to relay pin
    val name: MutableState<String>,   // User-editable
    val iconType: MutableState<String> // "lightbulb", "co2", "water_drop", "thermostat"
)
```

### New Feature — Day-of-Week Selection (DaySelector)

**v1.0.0:** No day selection existed. Schedules ran every day.

**v2.0.0:** Added a new `DaySelector.kt` composable with 7 circular toggle buttons (M, T, W, T, F, S, S). Each button maps to a bit in the `daysMask`. Selected days glow with a teal accent (`0xFF44DDC1`), unselected days appear as dim outlines.

Button size was initially `38.dp` but was reduced to `32.dp` in the final fix to prevent buttons from touching on narrow screens.

### New Feature — Clock Dial Time Picker (ClockDialPicker)

**v1.0.0:** Used a simple number picker or Material time picker for setting ON/OFF times.

**v2.0.0:** Replaced with a premium custom `ClockDialPicker.kt` — a drum-wheel style time selector with separate scrollable columns for Hours (1–12), Minutes (00–59), and AM/PM. Features:
- Infinite scrolling for hours and minutes wheels (the values wrap around)
- Finite scrolling for AM/PM (only 2 options)
- Tabbed interface with "Set ON Time" and "Set OFF Time" tabs
- Live "Run Duration" calculation displayed below the picker
- Smooth snap-to-center scroll physics with `DecayFlingBehavior`
- Center highlight box with glass-morphism-style semi-transparent overlay

### New Feature — Expandable Device Cards (DeviceCard)

**v1.0.0:** All controls were visible at once in a flat layout.

**v2.0.0:** Each device is a collapsible card (`DeviceCard.kt`):
- **Collapsed state:** Shows device icon, name, ON/OFF toggle, and auto-mode status indicator
- **Expanded state (tap to expand):** Reveals the full scheduling panel with clock picker, day selector, save button, and delete device option
- Only one card can be expanded at a time (`expandedDeviceId` state in ViewModel)
- Smooth expand/collapse animation with `AnimatedVisibility`

### UI Theme Overhaul — "Abyssal Flow" Design Language

**v1.0.0 Theme:** Standard Material 3 / Material You with dynamic color theming (follows wallpaper). Light and dark mode support. Generic system colors.

**v2.0.0 Theme:** Custom "Abyssal Flow" dark-only design language:

| Element | v1.0.0 | v2.0.0 |
|:---|:---|:---|
| **Background** | System dynamic color | Deep navy `#0B1326` |
| **Card Surface** | Material surface color | Layered surfaces: `#12192D` → `#1A2238` → `#2D3449` |
| **Primary Accent** | System dynamic (wallpaper-based) | Neon teal `#44DDC1` |
| **Secondary Accent** | System dynamic | Coral accent `#FF6B8A` |
| **Typography** | System default | Google Fonts (Inter/Outfit) |
| **Card Style** | Rounded corners with elevation | Rounded corners + no dividers + glassmorphism overlays |
| **Status Indicators** | Static colored dots | Animated pulsing status dots |
| **Design Mode** | Light + Dark support | Dark-only premium theme |
| **Delete Button** | Harsh pure red | Muted container with red confirmation dialog |

**New Color Palette (Color.kt):**
```kotlin
val AbyssalBase     = Color(0xFF0B1326)  // Deepest background
val AbyssalSurface1 = Color(0xFF12192D)  // Card backgrounds
val AbyssalSurface2 = Color(0xFF1A2238)  // Elevated surfaces
val AbyssalSurface3 = Color(0xFF2D3449)  // Highest elevation
val NeonTeal        = Color(0xFF44DDC1)  // Primary accent
val CoralAccent     = Color(0xFFFF6B8A)  // Secondary/warning accent
```

### New Feature — Connection Status Improvements

**v1.0.0:** Basic `isConnected` boolean. No visual feedback during connection attempts.

**v2.0.0:**
- Added `isConnecting` state for showing a loading indicator during Bluetooth connection
- Connection retry progress is visible to the user
- Animated pulsing glow on the status dot (green = connected, yellow = connecting, red = disconnected)

### Bug Fix — AM/PM Leakage Between ON/OFF Tabs (Clock Picker)

**v1.0.0 Bug:** (This bug was in the v2.0.0 initial implementation of ClockDialPicker, fixed before release)

When the user set ON time to 6:00 **AM** and OFF time to 7:00 **PM**, then switched back to the ON time tab, the AM/PM wheel would incorrectly show **PM** instead of AM. The AM/PM selection from the OFF tab "leaked" into the ON tab.

**Root Cause:** The `DrumWheel` composable used shortest-path circular wrap-around logic to calculate the programmatic scroll target. For the AM/PM wheel (which is non-infinite, `count = 2`), trying to scroll from PM (index 1) to AM (index 0) calculated a target of `2`, which coerced back to `1` (PM). The wheel got stuck.

**Fix:** When `infinite = false`, the programmatic scroll target is set to exactly `selectedIndex`, bypassing the infinite wrap-around math. Also added `rememberUpdatedState` for the `onSelected` and `selectedIndex` references inside `LaunchedEffect` to prevent stale closure captures.

### Bug Fix — Timezone Day-Mask Rotation (Wrong Day Scheduling)

**v1.0.0 Bug:** The `rotateDaysMask()` function performed a circular **right-shift** when a **left-shift** was needed. For users in timezones ahead of UTC (e.g., India, IST = UTC+5:30), a Monday schedule (bit 0) would incorrectly rotate to Sunday (bit 6) instead of staying on Monday when converting to UTC.

Additionally, when receiving schedules back from the ESP32, the same forward shift was applied instead of the reverse, causing a double-error.

**v2.0.0 Fix:**
- Corrected the normalized shift calculation in `rotateDaysMask()`: `val normalized = ((-shift % 7) + 7) % 7` 
- When receiving schedules from ESP32 (UTC→local conversion), now applies `-onDayShift` to properly reverse the rotation

### Bug Fix — Thread Safety (ConcurrentModificationException Crashes)

**v1.0.0 Bug:** `relayStates` was a standard `mutableMapOf<String, RelayState>()`. The Bluetooth listener thread (running on `Dispatchers.IO`) modified this map while the Compose UI thread read it simultaneously, causing random `ConcurrentModificationException` crashes.

**v2.0.0 Fix:** Changed `relayStates` to `ConcurrentHashMap<Int, RelayState>()` for thread-safe concurrent read/write access.

### Bug Fix — Silent Auto-Mode Toggle (No More Pop-up Spam)

**v1.0.0 Behavior:** Toggling the Auto/Manual switch in `DeviceCard.kt` called `saveStateManually()`, which always triggered the "Settings Saved" confirmation dialog — even though the user just flipped a switch and didn't explicitly press "Save".

**v2.0.0 Fix:** Added a `showConfirmation` parameter to `saveStateManually()`. When the Auto Mode switch is toggled, it calls `saveStateManually(device.id, showConfirmation = false)`, which sends the `T` command silently. The dialog only appears when the user explicitly clicks the "Save Settings" button.

### Bug Fix — Always Sync Auto-Mode to ESP32

**v1.0.0 Bug:** `saveStateManually()` only called `sendOnOffTimes()` when in auto mode. When switching from auto→manual, no command was sent to the ESP32, so the firmware continued running the active schedule and overriding manual toggles for up to 60 seconds.

**v2.0.0 Fix:** `sendOnOffTimes()` is always called regardless of mode, and includes the 8th `active` parameter (`state.isAutoMode.value` as `1` or `0`). This immediately tells the ESP32 to activate or deactivate the schedule.

### Android Version Compatibility

| Aspect | v1.0.0 | v2.0.0 |
|:---|:---|:---|
| **Min SDK** | 29 (Android 10) | 29 (Android 10) — unchanged |
| **Target SDK** | 34 | 34 — unchanged |
| **Permissions (Android 10-11)** | `BLUETOOTH` + `ACCESS_FINE_LOCATION` | Same |
| **Permissions (Android 12+)** | `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` | Same |
| **RFComm Fallback** | Reflection-based `createRfcommSocket(1)` | Same — crucial for Android 10 devices |

---

## 📊 Full File-by-File Change Summary

### ESP32 Firmware

| File | Change Type | Description |
|:---|:---|:---|
| `esp32_controlhub.ino` | **Rewritten** | 2→4 relay support, day mask scheduling, new command protocol, overnight fix, EEPROM wear fix, I2C fix, instant evaluation |

### Android App — Core

| File | Change Type | Description |
|:---|:---|:---|
| `RelayViewModel.kt` | **Rewritten** | Dynamic device management, ConcurrentHashMap, day mask rotation fix, 8-param command, silent save, always-sync mode |
| `MainActivity.kt` | **Modified** | Updated to use dynamic device list, new theme, expandable card layout |

### Android App — UI Components

| File | Change Type | Description |
|:---|:---|:---|
| `DeviceCard.kt` | **New** | Expandable device cards with icon, rename, schedule, delete |
| `ClockDialPicker.kt` | **New** | Custom drum-wheel time picker with AM/PM fix |
| `DaySelector.kt` | **New** | 7-day circular toggle buttons with bitmask |
| `ScheduleCard.kt` | **New** | Schedule display and save button container |
| `BluetoothConnectionSection.kt` | **Modified** | Added connecting state animation |

### Android App — Theme

| File | Change Type | Description |
|:---|:---|:---|
| `Theme.kt` | **Rewritten** | Abyssal Flow dark theme replacing Material You dynamic |
| `Color.kt` | **Rewritten** | Custom deep navy + neon teal palette |

---

## 🔢 Statistics

| Metric | v1.0.0 | v2.0.0 |
|:---|:---|:---|
| ESP32 Relay Channels | 2 | 4 |
| Bluetooth Command Types | 3 (Manual, Schedule, RTC) | 3 (same categories, new protocol) |
| Schedule Parameters | 6 (relay, onH, onM, offH, offM) | 8 (+daysMask, +active flag) |
| Android Composable Files | ~5 | ~10 |
| Bugs Fixed | — | 9 |
| EEPROM Format Version | Magic `0xABCD1234` | Magic `0xABCD5678` |

---

## ⚠️ Breaking Changes

1. **EEPROM Format:** v2.0.0 uses a new magic number (`0xABCD5678`). On first boot after upgrading, all saved schedules from v1.0.0 will be reset to defaults. Users must re-configure their schedules via the app.

2. **Bluetooth Command Protocol:** The command format has changed completely (letter codes → numeric IDs, 6-param → 8-param schedules). The v2.0.0 Android app is **not compatible** with v1.0.0 firmware and vice versa. Both must be upgraded together.

3. **SharedPreferences:** The app uses a new preference file (`"RelayPrefs_v2"` instead of `"RelayPrefs"`). On first launch of the v2.0.0 app, users will need to reconfigure their devices and schedules.

4. **Relay Pin Assignment:** Relay 1 moved from `GPIO 26` to `GPIO 25`. Relay 2 stays on `GPIO 26`. Old relay wiring for Light (GPIO 26) now maps to Relay 2 instead of Relay 1.

---

## 📝 Known Limitations

- **No watchdog timer:** The ESP32 hardware watchdog (WDT) is not enabled. If the I2C bus hangs due to electrical noise, the ESP32 will freeze and require a manual reset.
- **Device name validation:** Empty device names are allowed in the text field — leaving the name blank causes the input field to shrink to near-invisible width.
- **Same ON/OFF time:** Users can save a schedule where ON time equals OFF time, which results in the relay never activating. A warning is displayed but saving is not blocked.

---

## 🚀 Upgrade Instructions

### Step 1: Flash ESP32 Firmware
1. Download the new `esp32_controlhub.ino` from `ControlHub-Upload/esp32-firmware/`
2. Open in Arduino IDE
3. Ensure RTClib by Adafruit is installed
4. Select **ESP32 Dev Module** and upload
5. ⚠️ All saved schedules will be reset on first boot

### Step 2: Install Android App
1. Download the v2.0.0 APK from [GitHub Releases](https://github.com/akhil274/Control-Hub/releases/tag/v2.0.0)
2. Uninstall the old v1.0.0 app (recommended, to clear old SharedPreferences)
3. Install the new APK
4. Re-pair with the ESP32 via Bluetooth
5. Add your devices and set up schedules

### Step 3: Verify Relay Wiring
If upgrading from v1.0.0, check your relay wiring:
- **Relay 1 (Device 1):** GPIO 25 (was GPIO 26 in v1.0.0)
- **Relay 2 (Device 2):** GPIO 26 (was GPIO 27 in v1.0.0)
- **Relay 3 (Device 3):** GPIO 27 (new)
- **Relay 4 (Device 4):** GPIO 32 (new)

---

⭐ **If this project helped you, please star the repository: [akhil274/Control-Hub](https://github.com/akhil274/Control-Hub)**
