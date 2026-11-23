#include "dsd_decoder.h"
#include <cstring>

// 64-tap low-pass decimation FIR filter windowed sinc (cutoff ~45kHz normalized to DSD64)
const float DsdDecoder::FIR_COEFFS[64] = {
    -0.0001f, -0.0003f, -0.0006f, -0.0010f, -0.0014f, -0.0017f, -0.0017f, -0.0013f,
    -0.0004f,  0.0011f,  0.0033f,  0.0061f,  0.0094f,  0.0131f,  0.0170f,  0.0207f,
     0.0239f,  0.0264f,  0.0279f,  0.0280f,  0.0267f,  0.0238f,  0.0194f,  0.0135f,
     0.0064f, -0.0016f, -0.0101f, -0.0187f, -0.0268f, -0.0337f, -0.0389f, -0.0418f,
     0.9418f, -0.0418f, -0.0389f, -0.0337f, -0.0268f, -0.0187f, -0.0101f, -0.0016f,
     0.0064f,  0.0135f,  0.0194f,  0.0238f,  0.0267f,  0.0280f,  0.0279f,  0.0264f,
     0.0239f,  0.0207f,  0.0170f,  0.0131f,  0.0094f,  0.0061f,  0.0033f,  0.0011f,
    -0.0004f, -0.0013f, -0.0017f, -0.0017f, -0.0014f, -0.0010f, -0.0006f, -0.0003f
};

DsdDecoder::DsdDecoder() {
    reset();
}

void DsdDecoder::reset() {
    std::memset(histL, 0, sizeof(histL));
    std::memset(histR, 0, sizeof(histR));
    histIndex = 0;
}

int DsdDecoder::packDoP(const uint8_t* dsdLeft, const uint8_t* dsdRight, int dsdBytesPerChannel,
                        int32_t* pcmOut, uint8_t& dopMarkerState) {
    if (!dsdLeft || !dsdRight || !pcmOut || dsdBytesPerChannel < 2) return 0;

    int pcmFramesWritten = 0;
    // Each PCM frame consumes 2 DSD bytes (16 bits) per channel
    int numFrames = dsdBytesPerChannel / 2;

    for (int i = 0; i < numFrames; ++i) {
        // Toggle DoP marker: 0x05 <-> 0xFA
        uint8_t marker = (dopMarkerState == 0x05) ? 0xFA : 0x05;
        dopMarkerState = marker;

        // Left channel DSD word (16 bits)
        uint16_t dsdWordL = (static_cast<uint16_t>(dsdLeft[i * 2]) << 8) | dsdLeft[i * 2 + 1];
        // Right channel DSD word (16 bits)
        uint16_t dsdWordR = (static_cast<uint16_t>(dsdRight[i * 2]) << 8) | dsdRight[i * 2 + 1];

        // Format: [Marker (8-bit) | DSD_MSB (8-bit) | DSD_LSB (8-bit) | 0x00]
        int32_t sampleL = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(dsdWordL) << 8);
        int32_t sampleR = (static_cast<int32_t>(marker) << 24) | (static_cast<int32_t>(dsdWordR) << 8);

        pcmOut[pcmFramesWritten * 2] = sampleL;
        pcmOut[pcmFramesWritten * 2 + 1] = sampleR;
        pcmFramesWritten++;
    }

    return pcmFramesWritten;
}

int DsdDecoder::convertDsdToPcm(const uint8_t* dsdLeft, const uint8_t* dsdRight, int dsdBytesPerChannel,
                                float* pcmOutFloat, int decimationRatio) {
    if (!dsdLeft || !dsdRight || !pcmOutFloat) return 0;

    int totalBits = dsdBytesPerChannel * 8;
    int pcmFrames = totalBits / decimationRatio;
    int frameCount = 0;

    for (int byteIdx = 0; byteIdx < dsdBytesPerChannel; ++byteIdx) {
        uint8_t byteL = dsdLeft[byteIdx];
        uint8_t byteR = dsdRight[byteIdx];

        for (int bit = 7; bit >= 0; --bit) {
            float bitValL = ((byteL >> bit) & 1) ? 1.0f : -1.0f;
            float bitValR = ((byteR >> bit) & 1) ? 1.0f : -1.0f;

            histL[histIndex] = bitValL;
            histR[histIndex] = bitValR;
            histIndex = (histIndex + 1) % 64;

            // Decimate at regular intervals
            if ((byteIdx * 8 + (7 - bit) + 1) % decimationRatio == 0) {
                float sumL = 0.0f;
                float sumR = 0.0f;
                for (int tap = 0; tap < 64; ++tap) {
                    int idx = (histIndex - 1 - tap + 64) % 64;
                    sumL += histL[idx] * FIR_COEFFS[tap];
                    sumR += histR[idx] * FIR_COEFFS[tap];
                }

                // Normalization
                pcmOutFloat[frameCount * 2] = sumL * 0.5f;
                pcmOutFloat[frameCount * 2 + 1] = sumR * 0.5f;
                frameCount++;
            }
        }
    }

    return frameCount;
}
