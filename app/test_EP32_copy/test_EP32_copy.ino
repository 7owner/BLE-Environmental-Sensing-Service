#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Adafruit_BME280.h>
#include <Preferences.h>
#include <LittleFS.h>

/* =========================================================
   UUID SIG – Environmental Sensing Service
   ========================================================= */
static BLEUUID ESS_UUID   ("0000181A-0000-1000-8000-00805f9b34fb");
static BLEUUID TEMP_UUID  ("00002A6E-0000-1000-8000-00805f9b34fb");
static BLEUUID HUM_UUID   ("00002A6F-0000-1000-8000-00805f9b34fb");
static BLEUUID PRESS_UUID ("00002A6D-0000-1000-8000-00805f9b34fb");
static BLEUUID TIME_SERVICE_UUID("0000FF10-0000-1000-8000-00805f9b34fb");
static BLEUUID TIME_UUID("0000FF11-0000-1000-8000-00805f9b34fb");
static BLEUUID DATA_SERVICE_UUID("0000FF20-0000-1000-8000-00805f9b34fb");
static BLEUUID DATA_REQ_UUID("0000FF21-0000-1000-8000-00805f9b34fb");
static BLEUUID DATA_CHUNK_UUID("0000FF22-0000-1000-8000-00805f9b34fb");
static BLEUUID PMS_SERVICE_UUID("0000FF30-0000-1000-8000-00805f9b34fb");
static BLEUUID PM25_UUID("0000FF31-0000-1000-8000-00805f9b34fb");
static BLEUUID PM10_UUID("0000FF32-0000-1000-8000-00805f9b34fb");

static const char *DATA_FILE = "/data.csv";
static const uint32_t RETENTION_SECONDS = 4UL * 30UL * 24UL * 3600UL;

/* =========================================================
   OBJETS
   ========================================================= */
BLECharacteristic *tempChar;
BLECharacteristic *humChar;
BLECharacteristic *pressChar;
BLECharacteristic *timeChar;
BLECharacteristic *dataReqChar;
BLECharacteristic *dataChunkChar;
BLECharacteristic *pm25Char;
BLECharacteristic *pm10Char;

Adafruit_BME280 bme;
Preferences prefs;

bool deviceConnected = false;
bool notifyTemp  = false;
bool notifyHum   = false;
bool notifyPress = false;
bool notifyPm25  = false;
bool notifyPm10  = false;
bool timeSynced = false;
int64_t epochOffset = 0;
uint32_t writeCounter = 0;
uint32_t readOffset = 0;

const size_t CHUNK_SIZE = 180;
const int PMS_RX_PIN = 16;
const int PMS_TX_PIN = 17;

uint16_t lastPm25 = 0;
uint16_t lastPm10 = 0;

/* =========================================================
   CALLBACKS SERVEUR
   ========================================================= */
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    deviceConnected = true;
    Serial.println("[BLE] ✅ CONNECTÉ");
  }

  void onDisconnect(BLEServer*) override {
    deviceConnected = false;
    notifyTemp = notifyHum = notifyPress = false;
    BLEDevice::startAdvertising();
    Serial.println("[BLE] ❌ DÉCONNECTÉ → Advertising relancé");
  }
};

/* =========================================================
   CALLBACKS CCCD (NOTIFICATIONS)
   ========================================================= */
class CCCDCallbacks : public BLEDescriptorCallbacks {
  void onWrite(BLEDescriptor *d) override {
    bool enabled = d->getValue()[0] == 0x01;
    BLEUUID u = d->getCharacteristic()->getUUID();

    if (u.equals(TEMP_UUID))  notifyTemp  = enabled;
    if (u.equals(HUM_UUID))   notifyHum   = enabled;
    if (u.equals(PRESS_UUID)) notifyPress = enabled;
    if (u.equals(PM25_UUID))  notifyPm25  = enabled;
    if (u.equals(PM10_UUID))  notifyPm10  = enabled;

    Serial.printf(
      "[CCCD] Temp=%d | Hum=%d | Press=%d\n",
      notifyTemp, notifyHum, notifyPress
    );
  }
};

class TimeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string value = c->getValue();
    if (value.size() < sizeof(uint32_t)) return;
    uint32_t epoch = 0;
    memcpy(&epoch, value.data(), sizeof(uint32_t));
    epochOffset = (int64_t)epoch - (int64_t)(millis() / 1000);
    timeSynced = true;
    prefs.putULong("epoch", epoch);
    prefs.putLong("offset", epochOffset);
    Serial.printf("[TIME] Sync OK: %lu\n", (unsigned long)epoch);
  }
};

class DataReqCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string value = c->getValue();
    if (value.size() < sizeof(uint32_t)) return;
    uint32_t offset = 0;
    memcpy(&offset, value.data(), sizeof(uint32_t));
    readOffset = offset;
  }
};

/* =========================================================
   SETUP
   ========================================================= */
void setup() {
  Serial.begin(115200);
  delay(500);

  Serial.println("===================================");
  Serial.println(" ESP32 BLE ESS + BME280");
  Serial.println("===================================");

  /* ===== BME280 ===== */
  Wire.begin();

  Serial2.begin(9600, SERIAL_8N1, PMS_RX_PIN, PMS_TX_PIN);
  Serial.println("[PMS7003] UART2 init");

  if (!LittleFS.begin(true)) {
    Serial.println("[FS] ? LittleFS init fail");
    while (1);
  }

  prefs.begin("clock", false);
  if (prefs.isKey("epoch") && prefs.isKey("offset")) {
    uint32_t lastEpoch = prefs.getULong("epoch", 0);
    epochOffset = prefs.getLong("offset", 0);
    timeSynced = lastEpoch > 0;
    Serial.printf("[TIME] Repris: %lu\n", (unsigned long)lastEpoch);
  }
  if (!bme.begin(0x76)) {
    Serial.println("[BME280] ❌ Capteur non détecté");
    while (1);
  }
  Serial.println("[BME280] ✅ Initialisé");

  /* ===== BLE INIT ===== */
  BLEDevice::init("ESP32-BME");

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *ess = server->createService(ESS_UUID);
  BLEService *timeService = server->createService(TIME_SERVICE_UUID);
  BLEService *dataService = server->createService(DATA_SERVICE_UUID);
  BLEService *pmsService = server->createService(PMS_SERVICE_UUID);

  auto createChar = [&](BLEUUID uuid) {
    BLECharacteristic *c = ess->createCharacteristic(
      uuid,
      BLECharacteristic::PROPERTY_READ |
      BLECharacteristic::PROPERTY_NOTIFY
    );
    BLE2902 *cccd = new BLE2902();
    cccd->setCallbacks(new CCCDCallbacks());
    c->addDescriptor(cccd);
    return c;
  };

  tempChar  = createChar(TEMP_UUID);
  humChar   = createChar(HUM_UUID);
  pressChar = createChar(PRESS_UUID);

  timeChar = timeService->createCharacteristic(
    TIME_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ
  );
  timeChar->setCallbacks(new TimeCallbacks());
  if (timeSynced) {
    uint32_t epoch = (uint32_t)((millis() / 1000) + epochOffset);
    timeChar->setValue((uint8_t*)&epoch, sizeof(epoch));
  }

  dataReqChar = dataService->createCharacteristic(
    DATA_REQ_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  dataReqChar->setCallbacks(new DataReqCallbacks());

  dataChunkChar = dataService->createCharacteristic(
    DATA_CHUNK_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  dataChunkChar->addDescriptor(new BLE2902());

  pm25Char = pmsService->createCharacteristic(
    PM25_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pm25Char->addDescriptor(new BLE2902());

  pm10Char = pmsService->createCharacteristic(
    PM10_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pm10Char->addDescriptor(new BLE2902());

  ess->start();
  timeService->start();
  dataService->start();
  pmsService->start();
  Serial.println("[GATT] Service ESS démarré");

  /* ===== ADVERTISING (VERSION COMPATIBLE) ===== */
  BLEAdvertising *adv = BLEDevice::getAdvertising();

  // Advertising principal → NOM SEUL
  BLEAdvertisementData advData;
  advData.setName("ESP32-BME");
  adv->setAdvertisementData(advData);

  // Scan response → SERVICE ESS
  BLEAdvertisementData scanData;
  scanData.setCompleteServices(ESS_UUID);
  adv->setScanResponseData(scanData);

  adv->setMinPreferred(0x06);
  adv->setMinPreferred(0x12);

  BLEDevice::startAdvertising();
  Serial.println("[ADV] 📡 BLE visible au scan");
}

/* =========================================================
   LOOP
   ========================================================= */
void loop() {
  static unsigned long last = 0;
  if (millis() - last < 1000) return;
  last = millis();

  if (!timeSynced) return;

  float t = bme.readTemperature();
  float h = bme.readHumidity();
  float p = bme.readPressure() / 100.0;

  readPms();

  uint32_t epoch = (uint32_t)((millis() / 1000) + epochOffset);

  File f = LittleFS.open(DATA_FILE, FILE_APPEND);
  if (f) {
    f.printf("%lu,%.2f,%.2f,%.2f\n", (unsigned long)epoch, t, h, p);
    f.close();
  }

  writeCounter++;
  if (writeCounter % 60 == 0) {
    uint32_t cutoff = epoch - RETENTION_SECONDS;
    File in = LittleFS.open(DATA_FILE, FILE_READ);
    File out = LittleFS.open("/tmp.csv", FILE_WRITE);
    if (in && out) {
      while (in.available()) {
        String line = in.readStringUntil('\n');
        int idx = line.indexOf(',');
        if (idx > 0) {
          uint32_t ts = (uint32_t)line.substring(0, idx).toInt();
          if (ts >= cutoff) out.println(line);
        }
      }
    }
    if (in) in.close();
    if (out) out.close();
    LittleFS.remove(DATA_FILE);
    LittleFS.rename("/tmp.csv", DATA_FILE);
  }

  Serial.printf("T=%.2f °C | H=%.2f %% | P=%.2f hPa\n", t, h, p);

  if (notifyTemp) {
    int16_t v = (int16_t)(t * 100);
    tempChar->setValue((uint8_t*)&v, 2);
    tempChar->notify();
  }

  if (notifyHum) {
    uint16_t v = (uint16_t)(h * 100);
    humChar->setValue((uint8_t*)&v, 2);
    humChar->notify();
  }

  if (notifyPress) {
    uint32_t v = (uint32_t)(p * 100);
    pressChar->setValue((uint8_t*)&v, 4);
    pressChar->notify();
  }

  if (notifyPm25) {
    pm25Char->setValue((uint8_t*)&lastPm25, 2);
    pm25Char->notify();
  }

  if (notifyPm10) {
    pm10Char->setValue((uint8_t*)&lastPm10, 2);
    pm10Char->notify();
  }

  if (deviceConnected) {
    File in = LittleFS.open(DATA_FILE, FILE_READ);
    if (in) {
      if (readOffset < in.size()) {
        in.seek(readOffset, SeekSet);
        uint8_t buf[CHUNK_SIZE];
        size_t n = in.read(buf, CHUNK_SIZE);
        if (n > 0) {
          dataChunkChar->setValue(buf, n);
          dataChunkChar->notify();
          readOffset += n;
        }
      }
      in.close();
    }
  }
}

bool readPms() {
  static uint8_t buffer[32];
  while (Serial2.available() >= 32) {
    if (Serial2.read() != 0x42) continue;
    if (Serial2.read() != 0x4D) continue;
    buffer[0] = 0x42;
    buffer[1] = 0x4D;
    Serial2.readBytes(buffer + 2, 30);
    uint16_t pm25 = (buffer[12] << 8) | buffer[13];
    uint16_t pm10 = (buffer[14] << 8) | buffer[15];
    lastPm25 = pm25;
    lastPm10 = pm10;
    return true;
  }
  return false;
}
