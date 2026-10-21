#include <SoftwareSerial.h>

SoftwareSerial BTSerial(2, 3);

const int ADC_PIN = A0;
const unsigned long SAMPLE_INTERVAL_US = 4000;

unsigned long nextSampleTime = 0;

void setup() {
  Serial.begin(9600);
  BTSerial.begin(9600);
  nextSampleTime = micros();
}

void loop() {
  if ((long)(micros() - nextSampleTime) >= 0) {
    nextSampleTime += SAMPLE_INTERVAL_US;

    int adcValue = analogRead(ADC_PIN);

    // Binary packet: 0xAA + ADC high + ADC low
    BTSerial.write((uint8_t)0xAA);
    BTSerial.write((uint8_t)(adcValue >> 8));
    BTSerial.write((uint8_t)(adcValue & 0xFF));
  }
}
