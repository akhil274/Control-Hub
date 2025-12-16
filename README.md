# 🎛️ ControlHub – ESP32 Bluetooth Aquarium Controller (Android App)

---

## 📌 Introduction

**ControlHub** is an open-source aquarium automation system designed to control **lighting and CO₂ equipment using time-based scheduling**, without relying on Wi-Fi, cloud services, or physical button-based timer setups.

The system consists of:
- An **ESP32 + DS3231 RTC** that handles all automation logic
- A custom **Android application with a modern Material 3 design** used for setup, monitoring, and manual control via **Bluetooth**

The Android app allows users to:
- Connect to the ESP32 over Bluetooth
- Manually control Light and CO₂ relays
- Set ON/OFF schedules in Auto mode
- Sync the ESP32 RTC with the phone time
- Save schedules permanently to the ESP32

Once schedules are saved, the ESP32 operates **fully independently** using the RTC.  
The app is **not required to be running** for automation to work.

---

## 🤔 Why Bluetooth and NOT Wi-Fi?

Many ESP32 aquarium automation projects rely on Wi-Fi and cloud-based services, which introduces several limitations:

- ❌ Wi-Fi may not be available everywhere     
- ❌ Some time Stop working when the internet is down
- ❌ Dependence on cloud services for control and scheduling  
- ❌ Time synchronization often relies on internet-based NTP services

  ControlHub solves these issues by using **Bluetooth for setup and control**, and a **hardware RTC for accurate timekeeping**, making it reliable, private, and fully offline-capable, without requiring routers, credentials, or internet access.

### ✅ Why Bluetooth + RTC
- Works anywhere  
- No internet required
- Simple phone connection  
- Stable local control  
- RTC keeps time even without phone  

👉 **That is why Bluetooth + RTC was chosen.**

---

## 📱 Android App Preview

<p align="center">
  <img src="ControlHub-Upload/images/Screenshot-1-App-starting.png" width="220">
  <img src="ControlHub-Upload/images/Screenshot-2-Connection.png" width="220">
  <img src="ControlHub-Upload/images/Screenshot-3-Connected.png" width="220">
  <img src="ControlHub-Upload/images/Screenshot-4-light-control.png" width="220">
</p>
The ControlHub Android app is designed for **local, offline control** and configuration.

### App Features
- Bluetooth connection to ESP32 (no internet required)
- Manual ON / OFF relay control  
- Designed for Android 12+
- Auto mode with ON & OFF time  
- RTC time synchronization  
- Works fully offline

  ### Design Features:
- Modern Material 3 (Material You) design
- Dynamic color theming (adapts to user's wallpaper)
- Dark and light mode support
- Smooth animations and transitions

---

## 🧰 Hardware Used

| Component | Description |
|--------|------------|
| ESP32 Dev Board | Main controller |
| DS3231 RTC | Accurate time keeping |
| 2-Channel Relay Module | Controls AC loads |
| 5V Power Supply | Old mobile charger (used by me) |
| Perf / Breadboard | Prototype setup |
| Wires & Enclosure | Switch socket box / jumper wires |

---

## ⚡ HIGH VOLTAGE WARNING (IMPORTANT)

⚠️ **DANGER – READ CAREFULLY**

- This project switches **AC mains voltage**
- My setup is **ONLY A PROTOTYPE**
- Wiring is **NOT professional**
- No proper isolation for AC side

❗ DO NOT touch relay side when powered  
❗ DO NOT blindly copy AC wiring  
❗ USE proper enclosure for real use  
❗ CONSULT a qualified electrician if unsure  

**Power Note:**  
I used an **old 5V mobile charger** to power the ESP32 and relay module.  
For real installations, **use a proper regulated 5V supply**.

👉 This project is for **learning only**, not production use.

---

## 🔌 Hardware Setup (Photos)

<p align="center">
  <img src="ControlHub-Upload/images/Circuit-Esp32.JPG" width="500">
  <img src="ControlHub-Upload/images/Circuit-Socket.JPG" width="500">
</p>

### Basic Connections

**ESP32 → DS3231**
- SDA → GPIO 21  
- SCL → GPIO 22  
- VCC → 3.3V  
- GND → GND  

**ESP32 → Relay**
- IN1 → GPIO 27 (Light)  
- IN2 → GPIO 26 (CO₂)  
- VCC → 5V  
- GND → GND  

---

## 🚀 Step-by-Step Setup

### 1️⃣ Upload ESP32 Firmware

1. Install [Arduino IDE](https://www.arduino.cc/en/software)
2. Install [ESP32 Board Support](https://docs.espressif.com/projects/arduino-esp32/en/latest/installing.html)
3. Download firmware from [GitHub Releases](https://github.com/akhil274/Control-Hub/releases/tag/v1.0.0)
4. Unzip and open the `.ino` file
5. Need to install RTClib by Adafruit Librar by going to **Sketch → Include Library → Manage Libraries → Search "RTClib" → Install**
6. Select **ESP32 Dev Module**
7. Upload code
8. Open Serial Monitor (115200 baud) and confirm Bluetooth started

---

### 2️⃣ Install Android App

**Option A: APK**
1. Download APK from [GitHub Releases](https://github.com/akhil274/Control-Hub/releases/tag/v1.0.0)  
2. Enable *Install unknown apps*  
3. Install APK  

**Option B: Build from source**
1. Open [android-app](ControlHub-Upload/android-app) in Android Studio  
2. Let Gradle sync  
3. Run app on phone  

---

### 3️⃣ Connect App to ESP32

1. Power ON ESP32  
2. Enable Bluetooth on phone  
3. Open ControlHub app  
4. Tap **Connect to Device**  
5. Pair and connect to ESP32  

---

### 4️⃣ Sync RTC Time

1. Press **Sync RTC Time**  
2. ESP32 saves phone time  
3. RTC keeps time even after power loss  

---

### 5️⃣ Use Relay Controls

- **Manual Mode** → Instant ON / OFF  
- **Auto Mode** → Set ON & OFF time  
- Click **Save Settings**  
- ESP32 runs independently

  ## My Hardware Steup
<p align="center">
  <img src="ControlHub-Upload/images/harware-setup.jpg" width="500">
  <img src="ControlHub-Upload/images/hardware-final.jpg" width="500">
</p>

More images you can find in [images](ControlHub-Upload/images)
---

## 🧠 Notes from the Creator
This project was created mainly for learning, using AI-assisted coding and experimentation.

- Not a professional developer  
- No coding background  
- used AI for coding
- Made for learning and hobby  
- Code may not be perfect, but it works  

Feel free to improve or modify.

## 🚧 Roadmap

### 🔴 Phase 1 – Refinement & Stability 
- Refactor firmware and Android app code for better structure and maintainability
- Improve and extend validation and safety checks for scheduling logic and user input
- Improve app UI/UX and add clearer device and schedule status feedback
- Add better user feedback for invalid inputs and failed operations and RTC status

### 🟠 Phase 2 – Advanced Scheduling Features 
- Add calendar-based scheduling (day-wise control) for dosing pumps
- Add cycling / interval mode for devices like misting systems
- Support configurable ON/OFF cycle durations within active time windows

### 🟡 Phase 3 – Configurable Devices & Scalability 
- Add configurable devices with user-defined names
- Allow users to assign GPIO pins to devices from the app 
- Allow users to add and manage multiple relays without modifying firmware

These plans may evolve as the project grows and learning continues.


---

## 📜 License

MIT License – free to use, modify, and learn from.

---

## ⭐ Final Words

If you are a beginner, this project is for you.  
If you are experienced, please improve it and share back.
Feedback and contributions are welcome.

If you have ideas or improvements, feel free to open an issue.


⭐ **If this helped you, please star the repository.**
