# Release Notes — ControlHub v2.0.0

Welcome to **ControlHub v2.0.0**! This release introduces critical reliability fixes for the scheduling engine, timezone correction, UI enhancements, and seamless hardware-to-app state synchronization.

---

## What's New & Key Changes

### 1. ESP32 Firmware Improvements (Reliability & Speed)
* **Instant Relay Actions:** Clicking "Save Settings" or toggling Auto/Manual mode in the app now updates the physical relays immediately. The 60-second RTC sync delay has been bypassed for setting/syncing actions.
* **Overnight Day-Mask Resolution:** Rewrote the scheduling logic to evaluate both the current day's active schedules and overnight schedules originating from the previous day. This prevents relays from getting stuck ON at midnight.
* **Auto-Mode Active Flag Sync:** Added an 8th parameter to the `T` (schedule) command to sync the active/inactive state of schedules. Toggling Auto mode OFF now immediately disables schedule evaluation on the hardware, preventing it from fighting manual overrides.

### 2. Android App Fixes (User Experience & Stability)
* **Time Wheel AM/PM Preservation:** Fixed a bug in the custom clock dial picker where toggling between the ON and OFF tabs would leak AM/PM selections or reset hours. Selecting AM/PM now remains fully locked and remembered.
* **Timezone Day-Mask Correction:** Corrected the bit-shift direction in the local-to-UTC day-mask rotation. Repeat days (Monday–Sunday) now translate accurately to UTC and back without drifting by a day.
* **Crash Prevention (Concurrency):** Changed the internal relay states cache to a thread-safe implementation. This eliminates random application crashes caused by background Bluetooth threads writing to the state map while the UI thread was reading it.
* **Silent Mode Toggles:** Toggling the Auto/Manual mode switch now runs silently in the background, updating the ESP32 instantly without displaying a confirmation pop-up. The confirmation pop-up only displays when explicitly pressing the "Save Settings" button.
* **Refined Layouts:** Reduced the day selector button sizes (from `38.dp` to `32.dp`) to ensure standard margins and clean spacing on narrower mobile screens.

---

## Detailed Version Comparison (v1.0.0 vs v2.0.0)

| Feature / Bug | Old Version (v1.0.0) | New Version (v2.0.0) |
| :--- | :--- | :--- |
| **Relay Toggle Latency** | Delayed by up to 60 seconds (waited for the next RTC check interval). | **Instantaneous** on saving schedules, syncing time, or toggling modes. |
| **Overnight Schedules** | Broke at midnight if a schedule crossed day boundaries (e.g. Mon 10 PM to Tue 2 AM). | **Fully Supported**; tracks active schedules crossing midnight correctly. |
| **Auto/Manual Override** | Changing manual mode could be overridden by scheduling checks within 60 seconds. | **Synced instantly** via active flags; manual switches are respected. |
| **AM/PM Preservation** | AM/PM wheels leaked states, resetting morning/evening hours during tab switches. | **Locked state**; wheels remember and separate AM/PM times correctly. |
| **Timezone Translation** | Bit-shifting was inverted, causing active repeat days to shift forward/backward. | **Accurate shifts** based on local timezone offsets relative to UTC. |
| **App Crashes** | Suffered occasional `ConcurrentModificationException` during Bluetooth updates. | **Thread-safe cache** eliminates concurrent read/write UI crashes. |
| **User Interface** | Tight day-selector layouts could wrap/overlap; confirmation pop-ups appeared on every switch toggle. | **Optimized spacing** and silent background saving for toggle buttons. |
