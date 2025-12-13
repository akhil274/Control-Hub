#include <Wire.h>
#include <RTClib.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// Pin definitions for relays (Active LOW relays)
#define LIGHT_RELAY_PIN 26
#define CO2_RELAY_PIN 27

// EEPROM configuration
#define EEPROM_SIZE 512
#define EEPROM_MAGIC_NUMBER 0xABCD1234
#define MAGIC_ADDRESS 0
#define LIGHT_SCHEDULE_ADDRESS (MAGIC_ADDRESS + sizeof(uint32_t))
#define CO2_SCHEDULE_ADDRESS (LIGHT_SCHEDULE_ADDRESS + sizeof(RelaySchedule))

// DS3231 RTC object
RTC_DS3231 rtc;

// Bluetooth Serial object
BluetoothSerial SerialBT;

// Structure to hold relay schedule
struct RelaySchedule {
  int onHour;
  int onMinute;
  int offHour;
  int offMinute;
  bool isActive;
};

// Relay states
bool lightRelayState = false;
bool co2RelayState = false;

// Schedules for Light and CO2 relays
RelaySchedule lightSchedule = {0, 0, 0, 0, false};
RelaySchedule co2Schedule = {0, 0, 0, 0, false};

// Buffer for incoming Bluetooth commands
String commandBuffer = "";
const size_t MAX_COMMAND_LENGTH = 100;

// Track Bluetooth connection state
bool wasConnected = false;

// Watchdog timer for RTC check
unsigned long lastRTCCheck = 0;
const unsigned long RTC_CHECK_INTERVAL = 60000; // Check every minute

// Function prototypes
void processCommand(String cmd);
void checkSchedules();
void sendStateUpdate(String relay, bool state);
void sendSchedulesToApp();
void saveSchedulesToEEPROM();
void loadSchedulesFromEEPROM();
void initializeEEPROM();
bool isValidSchedule(const RelaySchedule& schedule);
void setRelayState(uint8_t pin, bool state);
void safeSerialPrint(const String& message);

void setup() {
  // Initialize Serial for debugging
  Serial.begin(115200);
  delay(100);
  Serial.println("\n=== ESP32 ControlHub Starting ===");

  // Initialize relay pins (Active LOW, so HIGH = OFF)
  pinMode(LIGHT_RELAY_PIN, OUTPUT);
  pinMode(CO2_RELAY_PIN, OUTPUT);
  setRelayState(LIGHT_RELAY_PIN, false); // Start with relays OFF
  setRelayState(CO2_RELAY_PIN, false);
  Serial.println("Relay pins initialized (both OFF)");

  // Initialize I2C and RTC
  Wire.begin();
  if (!rtc.begin()) {
    Serial.println("ERROR: Couldn't find RTC!");
    Serial.println("Please check RTC wiring and restart");
    while (1) {
      delay(1000); // Halt if RTC not found
    }
  }
  Serial.println("RTC initialized successfully");

  // Check if RTC lost power and needs time set
  if (rtc.lostPower()) {
    Serial.println("WARNING: RTC lost power! Time needs to be set.");
  }

  // Initialize EEPROM
  if (!EEPROM.begin(EEPROM_SIZE)) {
    Serial.println("ERROR: Failed to initialize EEPROM");
    while (1) {
      delay(1000); // Halt if EEPROM fails
    }
  }
  Serial.println("EEPROM initialized");

  // Check if EEPROM is initialized, if not, initialize it
  initializeEEPROM();

  // Load schedules from EEPROM
  loadSchedulesFromEEPROM();

  // Initialize Bluetooth
  if (!SerialBT.begin("esp32_controlhub")) {
    Serial.println("ERROR: Bluetooth initialization failed!");
    while (1) {
      delay(1000); // Halt if Bluetooth fails
    }
  }
  Serial.println("Bluetooth started successfully");
  Serial.println("Bluetooth device name: esp32_controlhub");
  Serial.println("=== Setup Complete ===\n");
}

void loop() {
  unsigned long currentMillis = millis();

  // Check for Bluetooth connection and send schedules if newly connected
  bool isConnected = SerialBT.hasClient();

  if (isConnected && !wasConnected) {
    Serial.println(">>> Bluetooth client connected");
    delay(500); // Small delay to ensure connection is stable
    sendSchedulesToApp();
  } else if (!isConnected && wasConnected) {
    Serial.println("<<< Bluetooth client disconnected");
  }
  wasConnected = isConnected;

  // Handle incoming Bluetooth commands
  while (SerialBT.available()) {
    char c = SerialBT.read();
    
    if (c == '\n') {
      if (commandBuffer.length() > 0) {
        processCommand(commandBuffer);
        commandBuffer = "";
      }
    } else if (c != '\r') { // Ignore carriage returns
      if (commandBuffer.length() < MAX_COMMAND_LENGTH) {
        commandBuffer += c;
      } else {
        Serial.println("ERROR: Command buffer overflow, clearing");
        commandBuffer = "";
      }
    }
  }

  // Check schedules and update relays in Auto mode
  if (currentMillis - lastRTCCheck >= RTC_CHECK_INTERVAL) {
    lastRTCCheck = currentMillis;
    checkSchedules();
  }

  // Small delay to prevent tight looping
  delay(50);
}

void initializeEEPROM() {
  uint32_t magic;
  EEPROM.get(MAGIC_ADDRESS, magic);
  
  if (magic != EEPROM_MAGIC_NUMBER) {
    Serial.println("EEPROM not initialized, writing default values");
    
    // Write magic number
    EEPROM.put(MAGIC_ADDRESS, EEPROM_MAGIC_NUMBER);
    
    // Write default schedules
    RelaySchedule defaultSchedule = {0, 0, 0, 0, false};
    EEPROM.put(LIGHT_SCHEDULE_ADDRESS, defaultSchedule);
    EEPROM.put(CO2_SCHEDULE_ADDRESS, defaultSchedule);
    
    if (EEPROM.commit()) {
      Serial.println("EEPROM initialized with default values");
    } else {
      Serial.println("ERROR: Failed to commit EEPROM initialization");
    }
  } else {
    Serial.println("EEPROM already initialized");
  }
}

void processCommand(String cmd) {
  cmd.trim(); // Remove any whitespace
  
  Serial.print("Received command: ");
  Serial.println(cmd);

  // Split the command into parts
  int commaIndex;
  String parts[7];
  int partCount = 0;

  String tempCmd = cmd + ",";
  while ((commaIndex = tempCmd.indexOf(',')) != -1 && partCount < 7) {
    parts[partCount] = tempCmd.substring(0, commaIndex);
    parts[partCount].trim();
    tempCmd = tempCmd.substring(commaIndex + 1);
    partCount++;
  }

  if (partCount == 0) {
    Serial.println("ERROR: Empty command received");
    return;
  }

  // Manual control commands
  if (cmd == "L1") {
    lightRelayState = true;
    lightSchedule.isActive = false;
    setRelayState(LIGHT_RELAY_PIN, true);
    Serial.println("Light Relay ON (Manual)");
    saveSchedulesToEEPROM();
    sendStateUpdate("L", true);
    
  } else if (cmd == "L0") {
    lightRelayState = false;
    lightSchedule.isActive = false;
    setRelayState(LIGHT_RELAY_PIN, false);
    Serial.println("Light Relay OFF (Manual)");
    saveSchedulesToEEPROM();
    sendStateUpdate("L", false);
    
  } else if (cmd == "C1") {
    co2RelayState = true;
    co2Schedule.isActive = false;
    setRelayState(CO2_RELAY_PIN, true);
    Serial.println("CO2 Relay ON (Manual)");
    saveSchedulesToEEPROM();
    sendStateUpdate("C", true);
    
  } else if (cmd == "C0") {
    co2RelayState = false;
    co2Schedule.isActive = false;
    setRelayState(CO2_RELAY_PIN, false);
    Serial.println("CO2 Relay OFF (Manual)");
    saveSchedulesToEEPROM();
    sendStateUpdate("C", false);
    
  // Timer schedule commands: T,<relay>,<onHour>,<onMin>,<offHour>,<offMin>
  } else if (parts[0] == "T" && partCount == 6) {
    String relay = parts[1];
    RelaySchedule* schedule = (relay == "L") ? &lightSchedule : &co2Schedule;

    schedule->onHour = parts[2].toInt();
    schedule->onMinute = parts[3].toInt();
    schedule->offHour = parts[4].toInt();
    schedule->offMinute = parts[5].toInt();
    schedule->isActive = true;

    // Validate schedule
    if (!isValidSchedule(*schedule)) {
      Serial.println("ERROR: Invalid schedule values received");
      schedule->isActive = false;
      return;
    }

    Serial.print(relay);
    Serial.print(" Schedule set: ON at ");
    Serial.print(schedule->onHour);
    Serial.print(":");
    Serial.print(schedule->onMinute);
    Serial.print(", OFF at ");
    Serial.print(schedule->offHour);
    Serial.print(":");
    Serial.println(schedule->offMinute);

    saveSchedulesToEEPROM();
    
  // RTC time set command: R,<year>,<month>,<day>,<hour>,<minute>,<second>
  } else if (parts[0] == "R" && partCount == 7) {
    int year = parts[1].toInt();
    int month = parts[2].toInt();
    int day = parts[3].toInt();
    int hour = parts[4].toInt();
    int minute = parts[5].toInt();
    int second = parts[6].toInt();

    // Validate values
    if (year >= 2000 && year <= 2099 &&
        month >= 1 && month <= 12 &&
        day >= 1 && day <= 31 &&
        hour >= 0 && hour <= 23 &&
        minute >= 0 && minute <= 59 &&
        second >= 0 && second <= 59) {
      
      rtc.adjust(DateTime(year, month, day, hour, minute, second));
      Serial.println("RTC time set successfully");
      Serial.print("New RTC time: ");
      Serial.print(year);
      Serial.print("-");
      Serial.print(month);
      Serial.print("-");
      Serial.print(day);
      Serial.print(" ");
      Serial.print(hour);
      Serial.print(":");
      Serial.print(minute);
      Serial.print(":");
      Serial.println(second);
      
      safeSerialPrint("R,OK\n");
    } else {
      Serial.println("ERROR: Invalid RTC time values");
      Serial.print("Received: ");
      Serial.print(year); Serial.print(",");
      Serial.print(month); Serial.print(",");
      Serial.print(day); Serial.print(",");
      Serial.print(hour); Serial.print(",");
      Serial.print(minute); Serial.print(",");
      Serial.println(second);
    }
    
  } else {
    Serial.print("ERROR: Unknown or malformed command: ");
    Serial.println(cmd);
  }
}

void checkSchedules() {
  // Check if RTC is running
  if (!rtc.begin()) {
    Serial.println("ERROR: RTC communication lost!");
    return;
  }

  DateTime now = rtc.now();
  int currentHour = now.hour();
  int currentMinute = now.minute();
  int currentTimeInMinutes = currentHour * 60 + currentMinute;

  // Log current time
  char timeStr[20];
  sprintf(timeStr, "%04d-%02d-%02d %02d:%02d:%02d", 
          now.year(), now.month(), now.day(), 
          now.hour(), now.minute(), now.second());
  Serial.print("Checking schedules at: ");
  Serial.println(timeStr);

  // Check Light schedule
  if (lightSchedule.isActive) {
    int onTimeInMinutes = lightSchedule.onHour * 60 + lightSchedule.onMinute;
    int offTimeInMinutes = lightSchedule.offHour * 60 + lightSchedule.offMinute;
    bool shouldBeOn = false;

    // Handle overnight schedules (e.g., ON at 23:00, OFF at 01:00)
    if (onTimeInMinutes <= offTimeInMinutes) {
      shouldBeOn = (currentTimeInMinutes >= onTimeInMinutes && currentTimeInMinutes < offTimeInMinutes);
    } else {
      shouldBeOn = (currentTimeInMinutes >= onTimeInMinutes || currentTimeInMinutes < offTimeInMinutes);
    }

    Serial.print("Light Schedule - Current: ");
    Serial.print(currentTimeInMinutes);
    Serial.print(" min, ON: ");
    Serial.print(onTimeInMinutes);
    Serial.print(" min, OFF: ");
    Serial.print(offTimeInMinutes);
    Serial.print(" min, Should be ON: ");
    Serial.println(shouldBeOn ? "YES" : "NO");

    if (shouldBeOn && !lightRelayState) {
      lightRelayState = true;
      setRelayState(LIGHT_RELAY_PIN, true);
      Serial.println(">>> Light Relay turned ON (Auto)");
      sendStateUpdate("L", true);
    } else if (!shouldBeOn && lightRelayState) {
      lightRelayState = false;
      setRelayState(LIGHT_RELAY_PIN, false);
      Serial.println("<<< Light Relay turned OFF (Auto)");
      sendStateUpdate("L", false);
    }
  } else {
    Serial.println("Light Schedule: INACTIVE");
  }

  // Check CO2 schedule
  if (co2Schedule.isActive) {
    int onTimeInMinutes = co2Schedule.onHour * 60 + co2Schedule.onMinute;
    int offTimeInMinutes = co2Schedule.offHour * 60 + co2Schedule.offMinute;
    bool shouldBeOn = false;

    if (onTimeInMinutes <= offTimeInMinutes) {
      shouldBeOn = (currentTimeInMinutes >= onTimeInMinutes && currentTimeInMinutes < offTimeInMinutes);
    } else {
      shouldBeOn = (currentTimeInMinutes >= onTimeInMinutes || currentTimeInMinutes < offTimeInMinutes);
    }

    Serial.print("CO2 Schedule - Current: ");
    Serial.print(currentTimeInMinutes);
    Serial.print(" min, ON: ");
    Serial.print(onTimeInMinutes);
    Serial.print(" min, OFF: ");
    Serial.print(offTimeInMinutes);
    Serial.print(" min, Should be ON: ");
    Serial.println(shouldBeOn ? "YES" : "NO");

    if (shouldBeOn && !co2RelayState) {
      co2RelayState = true;
      setRelayState(CO2_RELAY_PIN, true);
      Serial.println(">>> CO2 Relay turned ON (Auto)");
      sendStateUpdate("C", true);
    } else if (!shouldBeOn && co2RelayState) {
      co2RelayState = false;
      setRelayState(CO2_RELAY_PIN, false);
      Serial.println("<<< CO2 Relay turned OFF (Auto)");
      sendStateUpdate("C", false);
    }
  } else {
    Serial.println("CO2 Schedule: INACTIVE");
  }
  
  Serial.println("--- Schedule check complete ---\n");
}

void sendStateUpdate(String relay, bool state) {
  String message = "S," + relay + "," + (state ? "1" : "0") + "\n";
  safeSerialPrint(message);
}

void sendSchedulesToApp() {
  Serial.println("Sending schedules to app...");
  
  // Send Light schedule: "P,L,HH,MM,HH,MM,A\n"
  char lightMsg[32];
  sprintf(lightMsg, "P,L,%02d,%02d,%02d,%02d,%d\n",
          lightSchedule.onHour, lightSchedule.onMinute,
          lightSchedule.offHour, lightSchedule.offMinute,
          lightSchedule.isActive ? 1 : 0);
  safeSerialPrint(String(lightMsg));

  delay(100); // Small delay between messages

  // Send CO2 schedule: "P,C,HH,MM,HH,MM,A\n"
  char co2Msg[32];
  sprintf(co2Msg, "P,C,%02d,%02d,%02d,%02d,%d\n",
          co2Schedule.onHour, co2Schedule.onMinute,
          co2Schedule.offHour, co2Schedule.offMinute,
          co2Schedule.isActive ? 1 : 0);
  safeSerialPrint(String(co2Msg));
  
  Serial.println("Schedules sent successfully");
}

void saveSchedulesToEEPROM() {
  EEPROM.put(LIGHT_SCHEDULE_ADDRESS, lightSchedule);
  EEPROM.put(CO2_SCHEDULE_ADDRESS, co2Schedule);
  
  if (EEPROM.commit()) {
    Serial.println("✓ Schedules saved to EEPROM");
  } else {
    Serial.println("ERROR: Failed to save schedules to EEPROM");
  }
}

void loadSchedulesFromEEPROM() {
  Serial.println("Loading schedules from EEPROM...");
  
  EEPROM.get(LIGHT_SCHEDULE_ADDRESS, lightSchedule);
  EEPROM.get(CO2_SCHEDULE_ADDRESS, co2Schedule);

  // Validate loaded schedules
  if (lightSchedule.isActive && !isValidSchedule(lightSchedule)) {
    Serial.println("WARNING: Invalid Light Schedule detected, resetting");
    lightSchedule = {0, 0, 0, 0, false};
  }

  if (co2Schedule.isActive && !isValidSchedule(co2Schedule)) {
    Serial.println("WARNING: Invalid CO2 Schedule detected, resetting");
    co2Schedule = {0, 0, 0, 0, false};
  }

  // Log loaded schedules
  if (lightSchedule.isActive) {
    Serial.print("✓ Light Schedule: ON at ");
    Serial.print(lightSchedule.onHour);
    Serial.print(":");
    Serial.printf("%02d", lightSchedule.onMinute);
    Serial.print(", OFF at ");
    Serial.print(lightSchedule.offHour);
    Serial.print(":");
    Serial.printf("%02d\n", lightSchedule.offMinute);
  } else {
    Serial.println("Light Schedule: INACTIVE");
  }

  if (co2Schedule.isActive) {
    Serial.print("✓ CO2 Schedule: ON at ");
    Serial.print(co2Schedule.onHour);
    Serial.print(":");
    Serial.printf("%02d", co2Schedule.onMinute);
    Serial.print(", OFF at ");
    Serial.print(co2Schedule.offHour);
    Serial.print(":");
    Serial.printf("%02d\n", co2Schedule.offMinute);
  } else {
    Serial.println("CO2 Schedule: INACTIVE");
  }
}

bool isValidSchedule(const RelaySchedule& schedule) {
  return (schedule.onHour >= 0 && schedule.onHour <= 23 &&
          schedule.onMinute >= 0 && schedule.onMinute <= 59 &&
          schedule.offHour >= 0 && schedule.offHour <= 23 &&
          schedule.offMinute >= 0 && schedule.offMinute <= 59);
}

void setRelayState(uint8_t pin, bool state) {
  // For active LOW relays: LOW = ON, HIGH = OFF
  digitalWrite(pin, state ? LOW : HIGH);
}

void safeSerialPrint(const String& message) {
  if (SerialBT.hasClient()) {
    SerialBT.print(message);
    SerialBT.flush();
    Serial.print("Sent to app: ");
    Serial.print(message);
  } else {
    Serial.println("WARNING: Cannot send message, no Bluetooth client connected");
  }
}