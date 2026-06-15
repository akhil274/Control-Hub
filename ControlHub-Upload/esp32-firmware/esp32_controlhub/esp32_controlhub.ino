#include <Wire.h>
#include <RTClib.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// ═══════════════════════════════════════════════════════
// ESP32 ControlHub — 4 Relay Support + Day Scheduling
// ═══════════════════════════════════════════════════════

// Pin definitions for 4 relays (Active LOW relays)
#define RELAY_1_PIN 25  // Device 1 (default: Light)
#define RELAY_2_PIN 26  // Device 2 (default: CO2)
#define RELAY_3_PIN 27  // Device 3
#define RELAY_4_PIN 32  // Device 4

const uint8_t RELAY_PINS[4] = {RELAY_1_PIN, RELAY_2_PIN, RELAY_3_PIN, RELAY_4_PIN};
const int NUM_RELAYS = 4;

// EEPROM configuration
#define EEPROM_SIZE 512
#define EEPROM_MAGIC_NUMBER 0xABCD5678  // New magic for v2 format
#define MAGIC_ADDRESS 0

// Structure to hold relay schedule (v2 — with day mask)
struct RelaySchedule {
  int onHour;
  int onMinute;
  int offHour;
  int offMinute;
  uint8_t daysMask;   // bit 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri, 5=Sat, 6=Sun
  bool isActive;
};

// Calculate schedule addresses dynamically
int getScheduleAddress(int relayIndex) {
  return MAGIC_ADDRESS + sizeof(uint32_t) + (relayIndex * sizeof(RelaySchedule));
}

// DS3231 RTC object
RTC_DS3231 rtc;

// Bluetooth Serial object
BluetoothSerial SerialBT;

// Relay states and schedules for all 4 relays
bool relayStates[4] = {false, false, false, false};
RelaySchedule schedules[4];

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
void sendStateUpdate(int relayId, bool state);
void sendSchedulesToApp();
void saveSchedulesToEEPROM();
void loadSchedulesFromEEPROM();
void initializeEEPROM();
bool isValidSchedule(const RelaySchedule& schedule);
void setRelayState(uint8_t pin, bool state);
void safeSerialPrint(const String& message);
int dayOfWeekToBit(int rtcDow);

void setup() {
  // Initialize Serial for debugging
  Serial.begin(115200);
  delay(100);
  Serial.println("\n=== ESP32 ControlHub v2 Starting ===");
  Serial.println("4-Relay Mode with Day Scheduling");

  // Initialize all relay pins (Active LOW, so HIGH = OFF)
  for (int i = 0; i < NUM_RELAYS; i++) {
    pinMode(RELAY_PINS[i], OUTPUT);
    setRelayState(RELAY_PINS[i], false); // Start with relays OFF
    
    // Initialize default schedules
    schedules[i] = {0, 0, 0, 0, 0x7F, false}; // All days, inactive
  }
  Serial.println("All relay pins initialized (OFF)");

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

  // Perform initial schedule check immediately on boot
  checkSchedules();
  lastRTCCheck = millis();
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
    Serial.println("EEPROM not initialized (or old format), writing v2 defaults");
    
    // Write magic number
    EEPROM.put(MAGIC_ADDRESS, EEPROM_MAGIC_NUMBER);
    
    // Write default schedules for all 4 relays
    RelaySchedule defaultSchedule = {0, 0, 0, 0, 0x7F, false};
    for (int i = 0; i < NUM_RELAYS; i++) {
      EEPROM.put(getScheduleAddress(i), defaultSchedule);
    }
    
    if (EEPROM.commit()) {
      Serial.println("EEPROM initialized with v2 default values");
    } else {
      Serial.println("ERROR: Failed to commit EEPROM initialization");
    }
  } else {
    Serial.println("EEPROM already initialized (v2)");
  }
}

/**
 * Process incoming Bluetooth commands.
 * 
 * Protocol v2:
 *   Manual:    "<id><0|1>"          e.g., "11" = relay 1 ON, "20" = relay 2 OFF
 *   Schedule:  "T,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>"
 *   RTC time:  "R,yyyy,MM,dd,HH,mm,ss"
 */
void processCommand(String cmd) {
  cmd.trim(); // Remove any whitespace
  
  Serial.print("Received command: ");
  Serial.println(cmd);

  // Split the command into parts
  int commaIndex;
  String parts[8];
  int partCount = 0;

  String tempCmd = cmd + ",";
  while ((commaIndex = tempCmd.indexOf(',')) != -1 && partCount < 8) {
    parts[partCount] = tempCmd.substring(0, commaIndex);
    parts[partCount].trim();
    tempCmd = tempCmd.substring(commaIndex + 1);
    partCount++;
  }

  if (cmd.length() == 0) {
    Serial.println("ERROR: Empty command received");
    return;
  }

  // ═══════════════════════════════════════
  // Manual control: "<id><state>" (2 chars)
  // id = 1-4, state = 0 or 1
  // ═══════════════════════════════════════
  if (cmd.length() == 2 && partCount <= 1) {
    int relayId = (cmd.charAt(0) - '0');
    int state = (cmd.charAt(1) - '0');
    
    if (relayId >= 1 && relayId <= NUM_RELAYS && (state == 0 || state == 1)) {
      int idx = relayId - 1;
      relayStates[idx] = (state == 1);
      setRelayState(RELAY_PINS[idx], relayStates[idx]);
      
      Serial.print("Relay ");
      Serial.print(relayId);
      Serial.print(state == 1 ? " ON" : " OFF");
      Serial.println(" (Manual)");
      
      // NOTE: Do NOT call saveSchedulesToEEPROM() here.
      // Manual toggles don't change schedule data, and frequent
      // writes would wear out the EEPROM (100K cycle limit).
      sendStateUpdate(relayId, relayStates[idx]);
      return;
    }
  }

  // ═══════════════════════════════════════
  // Schedule: T,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>[,<active>]
  // 7 or 8 parts total
  // ═══════════════════════════════════════
  if (parts[0] == "T" && (partCount == 7 || partCount == 8)) {
    int relayId = parts[1].toInt();
    
    if (relayId >= 1 && relayId <= NUM_RELAYS) {
      int idx = relayId - 1;
      
      schedules[idx].onHour = parts[2].toInt();
      schedules[idx].onMinute = parts[3].toInt();
      schedules[idx].offHour = parts[4].toInt();
      schedules[idx].offMinute = parts[5].toInt();
      schedules[idx].daysMask = (uint8_t)parts[6].toInt();
      
      if (partCount == 8) {
        schedules[idx].isActive = (parts[7].toInt() == 1);
      } else {
        schedules[idx].isActive = true;
      }

      // Validate schedule
      if (!isValidSchedule(schedules[idx])) {
        Serial.println("ERROR: Invalid schedule values received");
        schedules[idx].isActive = false;
        return;
      }

      Serial.print("Relay ");
      Serial.print(relayId);
      Serial.print(" Schedule set: ON at ");
      Serial.print(schedules[idx].onHour);
      Serial.print(":");
      Serial.printf("%02d", schedules[idx].onMinute);
      Serial.print(", OFF at ");
      Serial.print(schedules[idx].offHour);
      Serial.print(":");
      Serial.printf("%02d", schedules[idx].offMinute);
      Serial.print(", Days mask: ");
      Serial.println(schedules[idx].daysMask, BIN);

      saveSchedulesToEEPROM();

      // Check schedules immediately after saving new settings
      checkSchedules();
      lastRTCCheck = millis();
    } else {
      Serial.print("ERROR: Invalid relay ID: ");
      Serial.println(relayId);
    }
    return;
  }

  // ═══════════════════════════════════════
  // RTC time set: R,yyyy,MM,dd,HH,mm,ss
  // 7 parts total (unchanged from v1)
  // ═══════════════════════════════════════
  if (parts[0] == "R" && partCount == 7) {
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
      Serial.printf("New RTC time: %04d-%02d-%02d %02d:%02d:%02d\n",
                     year, month, day, hour, minute, second);
      
      safeSerialPrint("R,OK\n");

      // Check schedules immediately after RTC time sync
      checkSchedules();
      lastRTCCheck = millis();
    } else {
      Serial.println("ERROR: Invalid RTC time values");
      Serial.printf("Received: %d,%d,%d,%d,%d,%d\n",
                     year, month, day, hour, minute, second);
    }
    return;
  }

  Serial.print("ERROR: Unknown or malformed command: ");
  Serial.println(cmd);
}

/**
 * Convert RTC dayOfTheWeek (0=Sun) to our bitmask position.
 * Our mask: bit 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri, 5=Sat, 6=Sun
 * RTC:      0=Sun, 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat
 */
int dayOfWeekToBit(int rtcDow) {
  if (rtcDow == 0) return 6;  // Sunday -> bit 6
  return rtcDow - 1;           // Mon=1->0, Tue=2->1, etc.
}

void checkSchedules() {
  // Validate RTC is still returning sane data (don't call rtc.begin()
  // every cycle — that re-initializes the I2C bus unnecessarily).
  DateTime now = rtc.now();
  if (now.year() < 2000 || now.year() > 2099) {
    Serial.println("ERROR: RTC returned invalid time — communication issue?");
    return;
  }

  int currentHour = now.hour();
  int currentMinute = now.minute();
  int currentTimeInMinutes = currentHour * 60 + currentMinute;
  int todayBit = dayOfWeekToBit(now.dayOfTheWeek());
  int yesterdayBit = (todayBit + 6) % 7;

  // Log current time
  char timeStr[20];
  sprintf(timeStr, "%04d-%02d-%02d %02d:%02d:%02d", 
          now.year(), now.month(), now.day(), 
          now.hour(), now.minute(), now.second());
  Serial.print("Checking schedules at: ");
  Serial.print(timeStr);
  Serial.printf(" (today bit: %d, yesterday bit: %d)\n", todayBit, yesterdayBit);

  // Check each relay schedule
  for (int i = 0; i < NUM_RELAYS; i++) {
    int relayId = i + 1;
    
    if (!schedules[i].isActive) {
      Serial.printf("Relay %d Schedule: INACTIVE\n", relayId);
      continue;
    }

    int onTimeInMinutes = schedules[i].onHour * 60 + schedules[i].onMinute;
    int offTimeInMinutes = schedules[i].offHour * 60 + schedules[i].offMinute;
    bool shouldBeOn = false;

    // 1. Evaluate today's schedule if today is in the daysMask
    bool todayActive = (schedules[i].daysMask & (1 << todayBit)) != 0;
    if (todayActive) {
      if (onTimeInMinutes < offTimeInMinutes) {
        if (currentTimeInMinutes >= onTimeInMinutes && currentTimeInMinutes < offTimeInMinutes) {
          shouldBeOn = true;
        }
      } else if (onTimeInMinutes > offTimeInMinutes) {
        if (currentTimeInMinutes >= onTimeInMinutes) {
          shouldBeOn = true;
        }
      }
    }

    // 2. Evaluate yesterday's schedule (for overnight crossover) if yesterday was in the daysMask
    bool yesterdayActive = (schedules[i].daysMask & (1 << yesterdayBit)) != 0;
    if (yesterdayActive) {
      if (onTimeInMinutes > offTimeInMinutes) {
        if (currentTimeInMinutes < offTimeInMinutes) {
          shouldBeOn = true;
        }
      }
    }

    Serial.printf("Relay %d - Current: %d min, ON: %d min, OFF: %d min, Should be ON: %s\n",
                   relayId, currentTimeInMinutes, onTimeInMinutes, offTimeInMinutes,
                   shouldBeOn ? "YES" : "NO");

    if (shouldBeOn && !relayStates[i]) {
      relayStates[i] = true;
      setRelayState(RELAY_PINS[i], true);
      Serial.printf(">>> Relay %d turned ON (Auto)\n", relayId);
      sendStateUpdate(relayId, true);
    } else if (!shouldBeOn && relayStates[i]) {
      relayStates[i] = false;
      setRelayState(RELAY_PINS[i], false);
      Serial.printf("<<< Relay %d turned OFF (Auto)\n", relayId);
      sendStateUpdate(relayId, false);
    }
  }
  
  Serial.println("--- Schedule check complete ---\n");
}

/**
 * Send state update: S,<id>,<0|1>
 */
void sendStateUpdate(int relayId, bool state) {
  String message = "S," + String(relayId) + "," + (state ? "1" : "0") + "\n";
  safeSerialPrint(message);
}

/**
 * Send all schedules to the app.
 * Format: P,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>,<active>
 */
void sendSchedulesToApp() {
  Serial.println("Sending schedules to app...");
  
  for (int i = 0; i < NUM_RELAYS; i++) {
    int relayId = i + 1;
    char msg[48];
    sprintf(msg, "P,%d,%02d,%02d,%02d,%02d,%d,%d\n",
            relayId,
            schedules[i].onHour, schedules[i].onMinute,
            schedules[i].offHour, schedules[i].offMinute,
            schedules[i].daysMask,
            schedules[i].isActive ? 1 : 0);
    safeSerialPrint(String(msg));
    delay(100); // Small delay between messages
  }
  
  // Also send current relay states
  for (int i = 0; i < NUM_RELAYS; i++) {
    sendStateUpdate(i + 1, relayStates[i]);
    delay(50);
  }
  
  Serial.println("Schedules and states sent successfully");
}

void saveSchedulesToEEPROM() {
  for (int i = 0; i < NUM_RELAYS; i++) {
    EEPROM.put(getScheduleAddress(i), schedules[i]);
  }
  
  if (EEPROM.commit()) {
    Serial.println("✓ Schedules saved to EEPROM");
  } else {
    Serial.println("ERROR: Failed to save schedules to EEPROM");
  }
}

void loadSchedulesFromEEPROM() {
  Serial.println("Loading schedules from EEPROM...");
  
  for (int i = 0; i < NUM_RELAYS; i++) {
    int relayId = i + 1;
    EEPROM.get(getScheduleAddress(i), schedules[i]);

    // Validate loaded schedule
    if (schedules[i].isActive && !isValidSchedule(schedules[i])) {
      Serial.printf("WARNING: Invalid Relay %d Schedule detected, resetting\n", relayId);
      schedules[i] = {0, 0, 0, 0, 0x7F, false};
    }

    if (schedules[i].isActive) {
      Serial.printf("✓ Relay %d Schedule: ON at %d:%02d, OFF at %d:%02d, Days: ",
                     relayId,
                     schedules[i].onHour, schedules[i].onMinute,
                     schedules[i].offHour, schedules[i].offMinute);
      // Print day names
      const char* dayNames[] = {"M", "Tu", "W", "Th", "F", "Sa", "Su"};
      for (int d = 0; d < 7; d++) {
        if (schedules[i].daysMask & (1 << d)) {
          Serial.print(dayNames[d]);
          Serial.print(" ");
        }
      }
      Serial.println();
    } else {
      Serial.printf("Relay %d Schedule: INACTIVE\n", relayId);
    }
  }
}

bool isValidSchedule(const RelaySchedule& schedule) {
  return (schedule.onHour >= 0 && schedule.onHour <= 23 &&
          schedule.onMinute >= 0 && schedule.onMinute <= 59 &&
          schedule.offHour >= 0 && schedule.offHour <= 23 &&
          schedule.offMinute >= 0 && schedule.offMinute <= 59 &&
          schedule.daysMask <= 0x7F);
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