#pragma once

#include <cstdint>

class DsdDecoder {
public:
  DsdDecoder();
  void reset();

  // Pack raw 1-bit DSD stream into DSD-over-PCM (DoP 1.1) 24-bit/32-bit
  // container Input: raw DSD bytes for Left and Right channels Output: 32-bit
  // integer PCM frames [Marker 8-bit | DSD 16-bit | Pad 8-bit]
  static int packDoP(const uint8_t *dsdLeft, const uint8_t *dsdRight,
                     int dsdBytesPerChannel, int32_t *pcmOut,
                     uint8_t &dopMarkerState);

  // Decimate raw 1-bit DSD stream to standard floating-point PCM
  // e.g. DSD64 (2.8224 MHz) / 16 = 176.4 kHz PCM
  int convertDsdToPcm(const uint8_t *dsdLeft, const uint8_t *dsdRight,
                      int dsdBytesPerChannel, float *pcmOutFloat,
                      int decimationRatio = 16);

private:
  static const float FIR_COEFFS[64];
  float histL[64];
  float histR[64];
  int histIndex = 0;
};
