#include <Wire.h>
#include <RTClib.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <EEPROM.h>
#include <esp_task_wdt.h>

// =======================================================
// ESP32 ControlHub - 4 Relay Support + Day Scheduling (BLE)
// =======================================================

// Pin definitions for 4 relays (Active LOW relays)
#define RELAY_1_PIN 25  // Device 1 (default: Light)
#define RELAY_2_PIN 26  // Device 2 (default: CO2)
#define RELAY_3_PIN 27  // Device 3
#define RELAY_4_PIN 32  // Device 4

const uint8_t RELAY_PINS[4] = {RELAY_1_PIN, RELAY_2_PIN, RELAY_3_PIN, RELAY_4_PIN};
const int NUM_RELAYS = 4;

// EEPROM configuration
#define EEPROM_SIZE 512
#define EEPROM_MAGIC_NUMBER 0xABCD5682  // New magic for v3 format (with Timer/Pulse settings)
#define MAGIC_ADDRESS 0

// Structure to hold relay schedule (v3 - with day mask and timer/pulse settings)
struct RelaySchedule {
  int onHour;
  int onMinute;
  int offHour;
  int offMinute;
  uint8_t daysMask;   // bit 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri, 5=Sat, 6=Sun
  bool isActive;
  int modeType;       // 0 = Auto/Standard, 1 = Timer/Pulse
  bool isInfinite;    // true = 24/7, false = windowed pulsing
  int pulseOnSec;     // duration of ON phase in seconds
  int pulseOffSec;    // duration of OFF phase in seconds
};

// Calculate schedule addresses dynamically
int getScheduleAddress(int relayIndex) {
  return MAGIC_ADDRESS + sizeof(uint32_t) + (relayIndex * sizeof(RelaySchedule));
}

// DS3231 RTC object
RTC_DS3231 rtc;

// BLE UART (Nordic UART Service) UUIDs
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define RX_CHARACTERISTIC_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define TX_CHARACTERISTIC_UUID "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
bool deviceConnected = false;

// Buffer for incoming Bluetooth commands
String commandBuffer = "";
const size_t MAX_COMMAND_LENGTH = 100;

#define CMD_QUEUE_LEN 8
QueueHandle_t cmdQueue = NULL;

// Track Bluetooth connection state
bool wasConnected = false;

// Watchdog timer for RTC check
unsigned long lastRTCCheck = 0;
const unsigned long RTC_CHECK_INTERVAL = 200; // Check every 200ms for Timer/Pulse responsiveness

// Function prototypes
bool safeParseInt(const String& str, int& result);
bool isLeapYear(int year);
bool isValidCalendarDate(int year, int month, int day);
void processCommand(String cmd);
void checkSchedules(bool forceRTCRead = false);
void updateActiveWindows(bool forceLog = false);
void sendStateUpdate(int relayId, bool state);
void sendSchedulesToApp();
bool saveScheduleToEEPROM(int relayIndex, const RelaySchedule& sched);
void loadSchedulesFromEEPROM();
void initializeEEPROM();
bool isValidSchedule(const RelaySchedule& schedule);
void setRelayState(uint8_t pin, bool state);
void safeSerialPrint(const String& message);
int dayOfWeekToBit(int rtcDow);
int getRelayStatesAddress();
void loadRelayStatesFromEEPROM();
void saveRelayStatesToEEPROM();

// BLE Server Callbacks
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("BLE Client Connected Event");
    }

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("BLE Client Disconnected Event");
    }
};

// BLE Characteristic Callbacks
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String rxValue = pCharacteristic->getValue();
      if (rxValue.length() > 0) {
        for (int i = 0; i < rxValue.length(); i++) {
          char c = rxValue[i];
          if (c == '\n') {
            if (commandBuffer.length() > 0) {
              char buf[MAX_COMMAND_LENGTH + 1];
              commandBuffer.toCharArray(buf, sizeof(buf));
              if (cmdQueue != NULL) {
                if (xQueueSend(cmdQueue, buf, 0) != pdTRUE) {
                  Serial.println("ERROR: Command queue full, dropping command");
                }
              }
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
      }
    }
};

// Relay states and schedules for all 4 relays
bool relayStates[4] = {false, false, false, false};
RelaySchedule schedules[4];
bool rtcHealthy = true; // Global RTC health state
bool rtcLostPower = false; // Global RTC lost-power state

// Timer/Pulse and window tracking states
bool pulseState[4] = {false, false, false, false};      // true = ON phase, false = OFF phase
unsigned long lastPulseTransition[4] = {0, 0, 0, 0};   // timestamp of last phase transition
bool isWithinActiveWindow[4] = {false, false, false, false};

void setup() {
  // Initialize Serial for debugging
  Serial.begin(115200);
  delay(100);
  Serial.println("\n=== ESP32 ControlHub v3 Starting ===");
  Serial.println("4-Relay Mode with Day Scheduling (BLE)");

  // Initialize all relay pins (Active LOW, so HIGH = OFF)
  for (int i = 0; i < NUM_RELAYS; i++) {
    pinMode(RELAY_PINS[i], OUTPUT);
    setRelayState(RELAY_PINS[i], false); // Start with relays OFF
    
    // Initialize default schedules
    schedules[i] = {0, 0, 0, 0, 0x7F, false, 0, false, 10, 10}; // All days, inactive, auto mode, 10s pulse
  }
  Serial.println("All relay pins initialized (OFF)");

  // Initialize BLE command queue
  cmdQueue = xQueueCreate(CMD_QUEUE_LEN, MAX_COMMAND_LENGTH + 1);
  if (cmdQueue == NULL) {
    Serial.println("ERROR: Failed to create BLE command queue");
  }

  // Initialize I2C and RTC
  Wire.begin();
  Wire.setTimeOut(50);
  if (!rtc.begin()) {
    Serial.println("ERROR: Couldn't find RTC! Continuing boot with rtcHealthy = false...");
    rtcHealthy = false;
  } else {
    Serial.println("RTC initialized successfully");
    rtcHealthy = true;
  }

  // Check if RTC lost power and needs time set
  if (rtcHealthy && rtc.lostPower()) {
    Serial.println("WARNING: RTC lost power! Time needs to be set.");
    rtcLostPower = true;
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

  // Load relay states from EEPROM
  loadRelayStatesFromEEPROM();

  // Initialize BLE
  BLEDevice::init("ControlCore"); // Name shown in BLE scans
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create BLE Service
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create BLE TX Characteristic (to notify the app)
  pTxCharacteristic = pService->createCharacteristic(
                      TX_CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Create BLE RX Characteristic (to write from the app)
  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                       RX_CHARACTERISTIC_UUID,
                       BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
                     );
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  // Start the service
  pService->start();

  // Start advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("BLE started successfully");
  Serial.println("BLE device name: ControlCore");

  // Initialize hardware watchdog timer (30s timeout, panic on timeout)
  // This recovers the device if the main loop ever hangs (e.g., I2C freeze)
  esp_task_wdt_config_t wdt_config = {
    .timeout_ms = 30000,
    .idle_core_mask = 0,
    .trigger_panic = true
  };
  esp_task_wdt_init(&wdt_config);
  esp_task_wdt_add(NULL); // Subscribe the current (main) task to the WDT

  Serial.println("=== Setup Complete ===\n");

  // Perform initial schedule check immediately on boot
  checkSchedules(true);
  lastRTCCheck = millis();
}

void loop() {
  unsigned long currentMillis = millis();

  // Process queued BLE commands
  char cmdBuf[MAX_COMMAND_LENGTH + 1];
  while (cmdQueue != NULL && xQueueReceive(cmdQueue, cmdBuf, 0) == pdTRUE) {
    processCommand(String(cmdBuf));
  }

  // Connection & disconnection management
  if (deviceConnected && !wasConnected) {
    wasConnected = true;
    Serial.println(">>> BLE client connected");
    delay(500); // Small delay to ensure connection is stable
    if (!rtcHealthy) {
      safeSerialPrint("E,RTC_FAIL\n");
    } else if (rtcLostPower) {
      safeSerialPrint("E,RTC_LOST_POWER\n");
    }
    sendSchedulesToApp();
  }
  
  if (!deviceConnected && wasConnected) {
    wasConnected = false;
    Serial.println("<<< BLE client disconnected");
    delay(500); // Give the BLE stack time to disconnect
    pServer->startAdvertising(); // Restart advertising to allow new connections
    Serial.println("Restarted BLE advertising");
  }

  // Check schedules and update relays in Auto mode
  if (currentMillis - lastRTCCheck >= RTC_CHECK_INTERVAL) {
    lastRTCCheck = currentMillis;
    checkSchedules();
  }

  // Read Serial commands for testing/simulation
  if (Serial.available()) {
    String serialCmd = Serial.readStringUntil('\n');
    serialCmd.trim();
    if (serialCmd.length() > 0) {
      processCommand(serialCmd);
    }
  }

  // Feed hardware watchdog and small delay to prevent tight looping
  esp_task_wdt_reset();
  delay(50);
}

void initializeEEPROM() {
  uint32_t magic;
  EEPROM.get(MAGIC_ADDRESS, magic);
  
  if (magic != EEPROM_MAGIC_NUMBER) {
    Serial.println("EEPROM not initialized (or old format), writing v3 defaults");
    
    // Write magic number
    EEPROM.put(MAGIC_ADDRESS, EEPROM_MAGIC_NUMBER);
    
    // Write default schedules for all 4 relays
    RelaySchedule defaultSchedule = {0, 0, 0, 0, 0x7F, false, 0, false, 10, 10};
    for (int i = 0; i < NUM_RELAYS; i++) {
      EEPROM.put(getScheduleAddress(i), defaultSchedule);
    }
    
    // Write default relay states (all false)
    bool defaultStates[4] = {false, false, false, false};
    EEPROM.put(getRelayStatesAddress(), defaultStates);
    
    if (EEPROM.commit()) {
      Serial.println("EEPROM initialized with v3 default values");
    } else {
      Serial.println("ERROR: Failed to commit EEPROM initialization");
    }
  } else {
    Serial.println("EEPROM already initialized (v3)");
  }
}

bool safeParseInt(const String& str, int& result) {
  String s = str;
  s.trim();
  if (s.length() == 0) return false;
  
  int startIdx = 0;
  if (s[0] == '-' || s[0] == '+') {
    if (s.length() == 1) return false;
    startIdx = 1;
  }
  
  for (int i = startIdx; i < s.length(); i++) {
    if (!isDigit(s[i])) {
      return false;
    }
  }
  
  result = s.toInt();
  return true;
}

/**
 * Returns true if the given year is a leap year under Gregorian rules:
 *   divisible by 400  -> leap year
 *   divisible by 100  -> NOT leap year
 *   divisible by 4    -> leap year
 *   otherwise         -> NOT leap year
 */
bool isLeapYear(int year) {
  return (year % 400 == 0) || (year % 100 != 0 && year % 4 == 0);
}

/**
 * Returns true only if year/month/day form a valid Gregorian calendar date.
 * Rejects impossible combinations such as Feb 30, Apr 31, etc.
 */
bool isValidCalendarDate(int year, int month, int day) {
  if (month < 1 || month > 12) return false;
  if (day < 1)                 return false;

  // Days per month (index 1-12); February handled separately for leap years.
  const int daysInMonth[13] = { 0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };

  int maxDay = (month == 2 && isLeapYear(year)) ? 29 : daysInMonth[month];
  return day <= maxDay;
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
  String parts[13];
  int partCount = 0;

  String tempCmd = cmd + ",";
  while ((commaIndex = tempCmd.indexOf(',')) != -1 && partCount < 13) {
    parts[partCount] = tempCmd.substring(0, commaIndex);
    parts[partCount].trim();
    tempCmd = tempCmd.substring(commaIndex + 1);
    partCount++;
  }

  if (cmd.length() == 0) {
    Serial.println("ERROR: Empty command received");
    return;
  }

  // =======================================
  // Query status & schedules: "Q"
  // =======================================
  if (cmd == "Q") {
    Serial.println("Query command received. Sending status and schedules...");
    if (!rtcHealthy) {
      safeSerialPrint("E,RTC_FAIL\n");
    } else if (rtcLostPower) {
      safeSerialPrint("E,RTC_LOST_POWER\n");
    } else {
      safeSerialPrint("H,OK\n");
    }
    sendSchedulesToApp();
    return;
  }

  // X deep-sleep test command removed for production safety.
  // Deep sleep floats relay GPIOs (active-LOW → relays could glitch ON).


  // =======================================
  // Manual control: "<id><state>" (2 chars)
  // id = 1-4, state = 0 or 1
  // =======================================
  if (cmd.length() == 2 && partCount <= 1) {
    int relayId = (cmd.charAt(0) - '0');
    int state = (cmd.charAt(1) - '0');
    
    if (relayId >= 1 && relayId <= NUM_RELAYS && (state == 0 || state == 1)) {
      int idx = relayId - 1;
      
      // Safety: never allow manual ON while RTC is failed or time is unknown.
      // OFF commands are still allowed.
      if (state == 1 && (!rtcHealthy || rtcLostPower)) {
        Serial.printf("ERROR: Rejecting manual ON for relay %d because RTC is not safe\n", relayId);
        
        if (!rtcHealthy) {
          safeSerialPrint("E,RTC_FAIL\n");
        } else if (rtcLostPower) {
          safeSerialPrint("E,RTC_LOST_POWER\n");
        }
        
        relayStates[idx] = false;
        setRelayState(RELAY_PINS[idx], false);
        sendStateUpdate(relayId, false);
        saveRelayStatesToEEPROM();
        return;
      }
      
      bool newState = (state == 1);
      
      Serial.print("Relay ");
      Serial.print(relayId);
      Serial.print(newState ? " ON" : " OFF");
      Serial.println(" (Manual)");
      
      // If this relay currently has an active Auto/Pulse schedule, disable it
      // so checkSchedules() does not fight or immediately override the manual command
      if (schedules[idx].isActive) {
        Serial.printf("Disabling active schedule for relay %d due to manual command\n", relayId);
        schedules[idx].isActive = false;
        pulseState[idx] = false;
        lastPulseTransition[idx] = 0;
        isWithinActiveWindow[idx] = false;
        saveScheduleToEEPROM(idx, schedules[idx]);
      }
      
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
      
      sendStateUpdate(relayId, newState);
      return;
    }
  }

  // =======================================
  // Schedule: T,<id>,<onH>,<onM>,<offH>,<offM>,<daysMask>[,<active>,<modeType>,<isInfinite>,<pulseOnSec>,<pulseOffSec>][,<txId>]
  // 7, 8, 9, 12, or 13 parts total
  // =======================================
  if (parts[0] == "T" && (partCount == 7 || partCount == 8 || partCount == 9 || partCount == 12 || partCount == 13)) {
    int relayId;
    if (!safeParseInt(parts[1], relayId)) {
      Serial.println("ERROR: Malformed relay ID in T command");
      return;
    }
    
    // Extract txId early so it's available for error responses too
    String txIdStr = "";
    if (partCount == 13) {
      txIdStr = parts[12];
    } else if (partCount == 9) {
      txIdStr = parts[8];
    }

    if (relayId >= 1 && relayId <= NUM_RELAYS) {
      int idx = relayId - 1;
      
      int onH, onM, offH, offM, daysM;
      if (!safeParseInt(parts[2], onH) ||
          !safeParseInt(parts[3], onM) ||
          !safeParseInt(parts[4], offH) ||
          !safeParseInt(parts[5], offM) ||
          !safeParseInt(parts[6], daysM)) {
        Serial.println("ERROR: Malformed numeric field in T command");
        return;
      }
      
      int modeTypeVal = 0;
      int isInfiniteVal = 0;
      int pulseOnSecVal = 10;
      int pulseOffSecVal = 10;
      int isActiveVal = 1;

      if (partCount == 13) {
        if (!safeParseInt(parts[7], isActiveVal) ||
            !safeParseInt(parts[8], modeTypeVal) ||
            !safeParseInt(parts[9], isInfiniteVal) ||
            !safeParseInt(parts[10], pulseOnSecVal) ||
            !safeParseInt(parts[11], pulseOffSecVal)) {
          Serial.println("ERROR: Malformed extended T command");
          return;
        }
      } else if (partCount == 12) {
        if (!safeParseInt(parts[7], isActiveVal) ||
            !safeParseInt(parts[8], modeTypeVal) ||
            !safeParseInt(parts[9], isInfiniteVal) ||
            !safeParseInt(parts[10], pulseOnSecVal) ||
            !safeParseInt(parts[11], pulseOffSecVal)) {
          Serial.println("ERROR: Malformed extended T command");
          return;
        }
      } else if (partCount == 9) {
        if (!safeParseInt(parts[7], isActiveVal)) {
          Serial.println("ERROR: Malformed active status field in T command");
          return;
        }
      } else if (partCount == 8) {
        if (!safeParseInt(parts[7], isActiveVal)) {
          Serial.println("ERROR: Malformed active status field in T command");
          return;
        }
      }

      RelaySchedule tempSchedule;
      tempSchedule.onHour = onH;
      tempSchedule.onMinute = onM;
      tempSchedule.offHour = offH;
      tempSchedule.offMinute = offM;
      tempSchedule.daysMask = (uint8_t)daysM;
      tempSchedule.modeType = modeTypeVal;
      tempSchedule.isInfinite = (isInfiniteVal == 1);
      tempSchedule.pulseOnSec = pulseOnSecVal;
      tempSchedule.pulseOffSec = pulseOffSecVal;
      tempSchedule.isActive = (isActiveVal == 1);

      // Validate schedule
      if (!isValidSchedule(tempSchedule)) {
        Serial.println("ERROR: Invalid schedule values received");
        String errMsg = "ERR,T," + String(relayId);
        if (txIdStr.length() > 0) {
          errMsg += "," + txIdStr;
        }
        errMsg += ",INVALID_SCHEDULE\n";
        safeSerialPrint(errMsg);
        return;
      }

      Serial.print("Relay ");
      Serial.print(relayId);
      Serial.print(" Schedule parsed: ON at ");
      Serial.print(tempSchedule.onHour);
      Serial.print(":");
      Serial.printf("%02d", tempSchedule.onMinute);
      Serial.print(", OFF at ");
      Serial.print(tempSchedule.offHour);
      Serial.print(":");
      Serial.printf("%02d", tempSchedule.offMinute);
      Serial.print(", Days mask: ");
      Serial.println(tempSchedule.daysMask, BIN);

      if (saveScheduleToEEPROM(idx, tempSchedule)) {
        // Only make the new schedule active in RAM after successful EEPROM commit
        schedules[idx] = tempSchedule;
        Serial.printf("+ Relay %d schedule activated in RAM\n", relayId);
        // Send ACK back to app to confirm successful save. Include txId if provided.
        String ackMsg = "ACK,T," + String(relayId);
        if (txIdStr.length() > 0) {
          ackMsg += "," + txIdStr;
        }
        ackMsg += "\n";
        safeSerialPrint(ackMsg);
      } else {
        // Send failure notification if save failed
        String errMsg = "ERR,T," + String(relayId);
        if (txIdStr.length() > 0) {
          errMsg += "," + txIdStr;
        }
        errMsg += ",EEPROM_COMMIT_FAILED\n";
        safeSerialPrint(errMsg);
      }

      // Check schedules immediately after saving new settings
      checkSchedules(true);
      lastRTCCheck = millis();
    } else {
      Serial.print("ERROR: Invalid relay ID: ");
      Serial.println(relayId);
      String errMsg = "ERR,T," + String(relayId);
      if (txIdStr.length() > 0) {
        errMsg += "," + txIdStr;
      }
      errMsg += ",INVALID_RELAY_ID\n";
      safeSerialPrint(errMsg);
    }
    return;
  }

  // =======================================
  // RTC time set: R,yyyy,MM,dd,HH,mm,ss
  // 7 parts total (unchanged from v1)
  // =======================================
  if (parts[0] == "R" && partCount == 7) {
    int year, month, day, hour, minute, second;
    if (!safeParseInt(parts[1], year) ||
        !safeParseInt(parts[2], month) ||
        !safeParseInt(parts[3], day) ||
        !safeParseInt(parts[4], hour) ||
        !safeParseInt(parts[5], minute) ||
        !safeParseInt(parts[6], second)) {
      Serial.println("ERROR: Malformed numeric field in R command");
      return;
    }

    // Validate all RTC field ranges, including a proper calendar-date check
    // so impossible dates like Feb 31 or Apr 31 are rejected before rtc.adjust().
    if (year >= 2000 && year <= 2099 &&
        isValidCalendarDate(year, month, day) &&
        hour >= 0 && hour <= 23 &&
        minute >= 0 && minute <= 59 &&
        second >= 0 && second <= 59) {
      
      if (!rtc.begin()) {
        rtcHealthy = false;
        rtcLostPower = true;
        Serial.println("ERROR: RTC not available, cannot sync time");
        safeSerialPrint("E,RTC_FAIL\n");
        safeSerialPrint("ERR,R,RTC_FAIL\n");
        return;
      }

      rtc.adjust(DateTime(year, month, day, hour, minute, second));
      delay(20); // short settle time after I2C write

      DateTime verifyNow = rtc.now();
      if (verifyNow.year() < 2000 || verifyNow.year() > 2099) {
        rtcHealthy = false;
        rtcLostPower = true;
        Serial.println("ERROR: RTC sync verification failed (invalid year)");
        safeSerialPrint("E,RTC_FAIL\n");
        safeSerialPrint("ERR,R,VERIFY_FAIL\n");
        return;
      }

      DateTime expected(year, month, day, hour, minute, second);
      uint32_t diff = verifyNow.unixtime() > expected.unixtime()
        ? verifyNow.unixtime() - expected.unixtime()
        : expected.unixtime() - verifyNow.unixtime();

      if (diff > 5) {
        rtcHealthy = false;
        rtcLostPower = true;
        Serial.println("ERROR: RTC readback time does not match set time");
        safeSerialPrint("E,RTC_FAIL\n");
        safeSerialPrint("ERR,R,TIME_MISMATCH\n");
        return;
      }

      rtcHealthy = true;
      rtcLostPower = false; // Clear lost power flag since it has been synced and verified!
      Serial.println("RTC time set and verified successfully");
      Serial.printf("New RTC time: %04d-%02d-%02d %02d:%02d:%02d\n",
                     verifyNow.year(), verifyNow.month(), verifyNow.day(), verifyNow.hour(), verifyNow.minute(), verifyNow.second());
      
      safeSerialPrint("R,OK\n");

      // Check schedules immediately after RTC time sync
      checkSchedules(true);
      lastRTCCheck = millis();
    } else {
      Serial.println("ERROR: Invalid RTC time values");
      Serial.printf("Received: %d,%d,%d,%d,%d,%d\n",
                     year, month, day, hour, minute, second);
      safeSerialPrint("ERR,R,INVALID_VALUES\n");
    }
    return;
  }

  Serial.print("ERROR: Unknown or malformed command: ");
  Serial.println(cmd);
  safeSerialPrint("ERR,UNKNOWN_CMD\n");
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

// Window tracking states
unsigned long lastRTCTimeCheck = 0;
const unsigned long RTC_READ_INTERVAL = 10000; // Read RTC every 10 seconds

void updateActiveWindows(bool forceLog) {
  // Try to recover RTC if it was previously unhealthy
  if (!rtcHealthy) {
    if (rtc.begin()) {
      DateTime testNow = rtc.now();
      if (testNow.year() >= 2000 && testNow.year() <= 2099) {
        rtcHealthy = true;
        Serial.println("+ RTC recovered and communication restored.");
      }
    }
  }

  if (!rtcHealthy) {
    return;
  }

  // Validate RTC is still returning sane data (don't call rtc.begin()
  // every cycle - that re-initializes the I2C bus unnecessarily).
  DateTime now = rtc.now();
  if (now.year() < 2000 || now.year() > 2099) {
    Serial.println("ERROR: RTC returned invalid time - communication issue?");
    rtcHealthy = false;
    return;
  }

  int currentHour = now.hour();
  int currentMinute = now.minute();
  int currentTimeInMinutes = currentHour * 60 + currentMinute;
  int todayBit = dayOfWeekToBit(now.dayOfTheWeek());
  int yesterdayBit = (todayBit + 6) % 7;

  static unsigned long lastLogTime = 0;
  bool shouldLog = forceLog || (millis() - lastLogTime >= 60000) || (lastLogTime == 0);
  if (shouldLog) {
    lastLogTime = millis();
    char timeStr[20];
    sprintf(timeStr, "%04d-%02d-%02d %02d:%02d:%02d", 
            now.year(), now.month(), now.day(), 
            now.hour(), now.minute(), now.second());
    Serial.print("Checking RTC time at: ");
    Serial.print(timeStr);
    Serial.printf(" (today bit: %d, yesterday bit: %d)\n", todayBit, yesterdayBit);
  }

  // Check each relay schedule
  for (int i = 0; i < NUM_RELAYS; i++) {
    if (!schedules[i].isActive) {
      isWithinActiveWindow[i] = false;
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

    isWithinActiveWindow[i] = shouldBeOn;

    if (shouldLog) {
      if (schedules[i].modeType == 0) {
        Serial.printf("Relay %d (Auto) - Current: %d min, ON: %d min, OFF: %d min, In Window: %s\n",
                       i + 1, currentTimeInMinutes, onTimeInMinutes, offTimeInMinutes,
                       isWithinActiveWindow[i] ? "YES" : "NO");
      } else {
        Serial.printf("Relay %d (Pulse) - In Window: %s, Infinite: %s, ON: %ds, OFF: %ds\n",
                       i + 1, isWithinActiveWindow[i] ? "YES" : "NO",
                       schedules[i].isInfinite ? "YES" : "NO",
                       schedules[i].pulseOnSec, schedules[i].pulseOffSec);
      }
    }
  }
}

void checkSchedules(bool forceRTCRead) {
  unsigned long currentMillis = millis();
  
  // Read RTC and update windows periodically or if forced
  if (forceRTCRead || currentMillis - lastRTCTimeCheck >= RTC_READ_INTERVAL || lastRTCTimeCheck == 0) {
    lastRTCTimeCheck = currentMillis;
    if (rtcHealthy && rtc.lostPower()) {
      rtcLostPower = true;
    }
    updateActiveWindows(forceRTCRead);
  }

  if (!rtcHealthy || rtcLostPower) {
    // Skip schedules, force all relays OFF, and periodically broadcast error/warning
    for (int i = 0; i < NUM_RELAYS; i++) {
      if (relayStates[i]) {
        relayStates[i] = false;
        setRelayState(RELAY_PINS[i], false);
        sendStateUpdate(i + 1, false);
      }
    }
    
    static unsigned long lastErrorBroadcast = 0;
    if (currentMillis - lastErrorBroadcast >= 10000 || lastErrorBroadcast == 0) {
      lastErrorBroadcast = currentMillis;
      if (!rtcHealthy) {
        safeSerialPrint("E,RTC_FAIL\n");
      } else if (rtcLostPower) {
        safeSerialPrint("E,RTC_LOST_POWER\n");
      }
    }
    return;
  }

  for (int i = 0; i < NUM_RELAYS; i++) {
    int relayId = i + 1;
    
    if (!schedules[i].isActive) {
      // If inactive, we are in manual mode; do not override or touch the relay states.
      pulseState[i] = false;
      lastPulseTransition[i] = 0;
      continue;
    }

    // Auto Mode (Standard)
    if (schedules[i].modeType == 0) {
      bool shouldBeOn = isWithinActiveWindow[i];
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
      pulseState[i] = false;
      lastPulseTransition[i] = 0;
    }
    // Timer/Pulse Mode
    else if (schedules[i].modeType == 1) {
      bool windowActive = schedules[i].isInfinite || isWithinActiveWindow[i];
      if (windowActive) {
        if (lastPulseTransition[i] == 0) {
          // Initialize pulsing cycle
          pulseState[i] = true;
          lastPulseTransition[i] = currentMillis;
          if (!relayStates[i]) {
            relayStates[i] = true;
            setRelayState(RELAY_PINS[i], true);
            Serial.printf(">>> Relay %d turned ON (Pulse Start)\n", relayId);
            sendStateUpdate(relayId, true);
          }
        } else {
          unsigned long elapsed = currentMillis - lastPulseTransition[i];
          if (pulseState[i]) {
            // ON phase: wait until pulseOnSec elapsed
            if (elapsed >= (unsigned long)schedules[i].pulseOnSec * 1000) {
              pulseState[i] = false;
              lastPulseTransition[i] = currentMillis;
              if (relayStates[i]) {
                relayStates[i] = false;
                setRelayState(RELAY_PINS[i], false);
                Serial.printf("<<< Relay %d turned OFF (Pulse Toggle)\n", relayId);
                sendStateUpdate(relayId, false);
              }
            }
          } else {
            // OFF phase: wait until pulseOffSec elapsed
            if (elapsed >= (unsigned long)schedules[i].pulseOffSec * 1000) {
              pulseState[i] = true;
              lastPulseTransition[i] = currentMillis;
              if (!relayStates[i]) {
                relayStates[i] = true;
                setRelayState(RELAY_PINS[i], true);
                Serial.printf(">>> Relay %d turned ON (Pulse Toggle)\n", relayId);
                sendStateUpdate(relayId, true);
              }
            }
          }
        }
      } else {
        // Outside the active pulse window, ensure OFF
        if (relayStates[i]) {
          relayStates[i] = false;
          setRelayState(RELAY_PINS[i], false);
          Serial.printf("<<< Relay %d turned OFF (Pulse Window End)\n", relayId);
          sendStateUpdate(relayId, false);
        }
        pulseState[i] = false;
        lastPulseTransition[i] = 0;
      }
    }
  }
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
    char msg[80];
    snprintf(msg, sizeof(msg), "P,%d,%02d,%02d,%02d,%02d,%d,%d,%d,%d,%d,%d\n",
            relayId,
            schedules[i].onHour, schedules[i].onMinute,
            schedules[i].offHour, schedules[i].offMinute,
            schedules[i].daysMask,
            schedules[i].isActive ? 1 : 0,
            schedules[i].modeType,
            schedules[i].isInfinite ? 1 : 0,
            schedules[i].pulseOnSec,
            schedules[i].pulseOffSec);
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

bool saveScheduleToEEPROM(int relayIndex, const RelaySchedule& sched) {
  if (relayIndex < 0 || relayIndex >= NUM_RELAYS) {
    Serial.println("ERROR: Invalid relay index for EEPROM save");
    return false;
  }
  
  EEPROM.put(getScheduleAddress(relayIndex), sched);
  
  if (EEPROM.commit()) {
    Serial.printf("+ Schedule for relay %d saved to EEPROM\n", relayIndex + 1);
    return true;
  } else {
    Serial.printf("ERROR: Failed to save schedule for relay %d to EEPROM\n", relayIndex + 1);
    return false;
  }
}

// Note: Bulk saveSchedulesToEEPROM() was removed — individual relay saves
// use saveScheduleToEEPROM(relayIndex, sched) for EEPROM-before-RAM safety.

void loadSchedulesFromEEPROM() {
  Serial.println("Loading schedules from EEPROM...");
  
  for (int i = 0; i < NUM_RELAYS; i++) {
    int relayId = i + 1;
    EEPROM.get(getScheduleAddress(i), schedules[i]);

    // Sanitize and normalize raw bool fields in schedules[i] to prevent undefined behavior
    uint8_t* isActivePtr = (uint8_t*)&schedules[i].isActive;
    uint8_t* isInfinitePtr = (uint8_t*)&schedules[i].isInfinite;

    if (*isActivePtr != 0 && *isActivePtr != 1) {
      Serial.printf("WARNING: Corrupt isActive byte (0x%02X) in schedule %d, resetting to false.\n", *isActivePtr, relayId);
      *isActivePtr = 0;
    }
    if (*isInfinitePtr != 0 && *isInfinitePtr != 1) {
      Serial.printf("WARNING: Corrupt isInfinite byte (0x%02X) in schedule %d, resetting to false.\n", *isInfinitePtr, relayId);
      *isInfinitePtr = 0;
    }

    // Validate loaded schedule (all slots, not just active — corrupt inactive slots
    // could overflow sprintf in sendSchedulesToApp)
    if (!isValidSchedule(schedules[i])) {
      Serial.printf("WARNING: Invalid Relay %d Schedule detected, resetting\n", relayId);
      schedules[i] = {0, 0, 0, 0, 0x7F, false, 0, false, 10, 10};
    }

    if (schedules[i].isActive) {
      Serial.printf("+ Relay %d Schedule: ON at %d:%02d, OFF at %d:%02d, Days: ",
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
  // If schedule is active and not 24/7 infinite pulsing, it must repeat on at least one day (daysMask > 0)
  // and must not have identical ON and OFF times.
  if (schedule.isActive && !(schedule.modeType == 1 && schedule.isInfinite)) {
    if (schedule.daysMask == 0) {
      return false;
    }
    if (schedule.onHour == schedule.offHour && schedule.onMinute == schedule.offMinute) {
      return false;
    }
  }
  return (schedule.onHour >= 0 && schedule.onHour <= 23 &&
          schedule.onMinute >= 0 && schedule.onMinute <= 59 &&
          schedule.offHour >= 0 && schedule.offHour <= 23 &&
          schedule.offMinute >= 0 && schedule.offMinute <= 59 &&
          schedule.daysMask <= 0x7F &&
          (schedule.modeType == 0 || schedule.modeType == 1) &&
          schedule.pulseOnSec >= 1 && schedule.pulseOnSec <= 86400 &&
          schedule.pulseOffSec >= 1 && schedule.pulseOffSec <= 86400);
}

void setRelayState(uint8_t pin, bool state) {
  // For active LOW relays: LOW = ON, HIGH = OFF
  digitalWrite(pin, state ? LOW : HIGH);
}

void safeSerialPrint(const String& message) {
  if (deviceConnected && pTxCharacteristic != NULL) {
    pTxCharacteristic->setValue((uint8_t*)message.c_str(), message.length());
    pTxCharacteristic->notify();
    Serial.print("Sent to app (BLE): ");
    Serial.print(message);
  } else {
    Serial.println("WARNING: Cannot send message, no BLE client connected");
  }
}

int getRelayStatesAddress() {
  return getScheduleAddress(NUM_RELAYS);
}

void loadRelayStatesFromEEPROM() {
  Serial.println("Loading relay states from EEPROM...");
  uint8_t tempStates[NUM_RELAYS];
  EEPROM.get(getRelayStatesAddress(), tempStates);
  for (int i = 0; i < NUM_RELAYS; i++) {
    if (tempStates[i] == 1) {
      relayStates[i] = true;
    } else {
      relayStates[i] = false;
      if (tempStates[i] != 0) {
        Serial.printf("WARNING: Corrupt relay state byte %d (0x%02X) in EEPROM, resetting to OFF.\n", i + 1, tempStates[i]);
      }
    }
    if (relayStates[i] && (!rtcHealthy || rtcLostPower)) {
      Serial.printf("Relay %d loaded state is ON but RTC is unhealthy/lost power. Forcing OFF.\n", i + 1);
      relayStates[i] = false;
    }
    Serial.printf("Relay %d loaded state: %s\n", i + 1, relayStates[i] ? "ON" : "OFF");
    setRelayState(RELAY_PINS[i], relayStates[i]);
  }
}

void saveRelayStatesToEEPROM() {
  EEPROM.put(getRelayStatesAddress(), relayStates);
  if (EEPROM.commit()) {
    Serial.println("+ Relay states saved to EEPROM");
  } else {
    Serial.println("ERROR: Failed to save relay states to EEPROM");
    safeSerialPrint("ERR,S,EEPROM_FAIL\n");
  }
}
