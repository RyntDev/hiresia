#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <atomic>

enum class OutputType : int {
    USB_DIRECT = 0,     // Direct bit-perfect USB Audio Class driver (bypasses Android SRC)
    ANDROID_HIRES = 1,  // Direct to internal Hi-Res DAC (Snapdragon/AKM/ESS)
    AAUDIO = 2,         // Google AAudio low-latency API
    OPENSLES = 3,       // OpenSL ES standard API
    UPNP_STREAM = 4,    // UPnP / DLNA streaming output
    CAST = 5            // Google Cast output
};

enum class DsdMode : int {
    DSD_TO_PCM = 0,     // Decimate DSD stream to high-resolution PCM (e.g. 176.4kHz/24-bit)
    DOP = 1,            // DSD over PCM (DoP v1.1 standard: 0x05 / 0xFA marker)
    NATIVE_DSD = 2      // Raw Native DSD transfer (for supported DACs)
};

enum class ReplayGainMode : int {
    OFF = 0,
    TRACK = 1,
    ALBUM = 2
};

struct AudioFormatInfo {
    int sampleRate = 44100;
    int channels = 2;
    int bitDepth = 16;
    bool isFloat = false;
    bool isDsd = false;
    int dsdRate = 0;       // 64, 128, 256, 512
    std::string codecName = "PCM";
};

struct ESDTrackInfo {
    std::string path;
    std::string title;
    std::string artist;
    std::string album;
    int64_t durationMs = 0;
    AudioFormatInfo format;
    float replayGainTrack = 0.0f;
    float replayGainAlbum = 0.0f;
    float replayGainPeak = 1.0f;
};

struct VUMeterLevel {
    float peakLeft = 0.0f;   // 0.0f to 1.0f (or in dB)
    float peakRight = 0.0f;
    float rmsLeft = 0.0f;
    float rmsRight = 0.0f;
};
