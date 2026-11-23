#pragma once

#include <cstdint>
#include "audio_types.h"
#include "dsp_processor.h"
#include "usb_audio_driver.h"
#include "dsd_decoder.h"
#include "sinc_resampler.h"
#include "native_decoder.h"
#include <aaudio/AAudio.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <string>

class AudioEngine {
public:
    static AudioEngine& getInstance();

    bool initAndRun();
    void shutdown();

    // Track Preparation & Playback Control
    bool preparePlayback(const ESDTrackInfo& trackInfo);
    void play();
    void pause();
    void stop();
    void seek(int64_t positionMs);
    void flushRingBuffer();

    bool isPlaying() const { return playing.load(); }
    int64_t getCurrentPositionMs() const { return currentPositionMs.load(); }
    int64_t getTotalDurationMs() const { return totalDurationMs.load(); }

    // Output Device & Bit-Perfect Configuration
    void setOutputType(OutputType type);
    OutputType getOutputType() const { return outputType; }
    void setBitPerfect(bool enable);
    bool isBitPerfect() const { return bitPerfectEnabled; }
    bool isBitPerfectPossibleForUSBAudio() const;

    // USB Audio Device Management
    bool initUSBDevice(int fd, int vid, int pid, const std::string& name,
                       const uint8_t* rawDesc = nullptr, int rawDescLen = 0);
    void usbDeviceWasDetached();
    bool consumeFatalUsbError() { return usbFatalErrorPending.exchange(false); }
    bool refreshAAudioRoute();
    bool isUSBConnected() const { return usbDriver.isConnected(); }
    bool isUSBUAC2() const { return usbDriver.isUAC2(); }
    std::string getUSBDeviceName() const { return usbDriver.getDeviceName(); }
    std::vector<int> getUSBSupportedSampleRates() const { return usbDriver.getSupportedSampleRates(); }
    void setBlockUsbHid(bool enable) { usbDriver.setBlockUsbHid(enable); }
    bool isBlockUsbHid() const { return usbDriver.isBlockUsbHid(); }

    // Volume & VU Meters
    void setHardwareVolume(float vol);
    float getHardwareVolume() const { return usbDriver.getHardwareVolume(); }
    void setHardwareVolumeLimit(float maxLimit) { usbDriver.setHardwareVolumeLimit(maxLimit); }
    void setHardwareVolumeCurve(int curve) { usbDriver.setHardwareVolumeCurve(curve); }
    void setHardwareBalance(float balance) { usbDriver.setHardwareBalance(balance); }
    void setForceVolumeSync(bool enable) { usbDriver.setForceVolumeSync(enable); }
    bool isStereoHardwareVolumeSupported() const { return usbDriver.isStereoHardwareVolumeSupported(); }
    void setDitherEnabled(bool enable) { usbDriver.setDitherEnabled(enable); }
    bool isHardwareVolumeSupported() const { return usbDriver.isHardwareVolumeSupported(); }
    int16_t getHardwareVolumeMin() const { return usbDriver.getVolumeMin(); }
    int16_t getHardwareVolumeMax() const { return usbDriver.getVolumeMax(); }
    int16_t getHardwareVolumeRes() const { return usbDriver.getVolumeRes(); }
    void setSoftwareVolume(float vol) {
        if (isDsdTrack && dsdMode != DsdMode::DSD_TO_PCM) {
            dsp.setSoftwareVolume(1.0f);
            return;
        }
        dsp.setSoftwareVolume(vol);
    }
    float getSoftwareVolume() const { return dsp.getSoftwareVolume(); }
    float getCurrentLeftVolumePostAll() const { return vuLevels.peakLeft; }
    float getCurrentRightVolumePostAll() const { return vuLevels.peakRight; }

    // DSP Access
    DspProcessor& getDsp() { return dsp; }
    int getFileSampleRate() const { return fileSampleRate; }
    int getDeviceSampleRate() const { return deviceSampleRate; }
    int getDSDRate() const { return isDsdTrack ? dsdRate : 0; }
    bool isDSD() const { return isDsdTrack; }
    void setDsdMode(int mode) { dsdMode = static_cast<DsdMode>(mode); }
    int getDsdMode() const { return static_cast<int>(dsdMode); }

    // PCM Stream Delivery (Bit-Exact Int32 + Float compatibility)
    void queuePcmData(const float* pcm, int numFrames);
    void queuePcmInt32(const int32_t* pcm, int numFrames);
    int getQueuedFrames() const;
    bool updateAudioFormat(int sampleRate, int bitDepth, int channels);

    // Native C++ Lossless Decoder (FLAC / WAV / DSF / DFF)
    NativeLosslessDecoder& getNativeDecoder() { return nativeDecoder; }
    int readAndQueueNativeFrames(int maxFrames);

private:
    AudioEngine();
    ~AudioEngine();

    void audioThreadLoop();
    bool setupAAudioStream(int sampleRate, int channels);
    void closeAAudioStream();
    bool isAAudioStreamHealthyLocked() const;
    bool renderAudioBlockInt32(int32_t* outBuffer, int numFrames);

    std::atomic<bool> running{false};
    std::atomic<bool> playing{false};
    std::atomic<bool> usbFatalErrorPending{false};
    std::thread workerThread;

    OutputType outputType = OutputType::AAUDIO;
    bool bitPerfectEnabled = true;

    // Active track properties
    std::atomic<int64_t> currentPositionMs{0};
    std::atomic<int64_t> totalDurationMs{0};
    int fileSampleRate = 44100;
    int deviceSampleRate = 44100;
    int channels = 2;
    int bitDepth = 16;
    bool isDsdTrack = false;
    int dsdRate = 0;
    DsdMode dsdMode = DsdMode::DOP;

    // Audio Subsystems
    UsbAudioDriver usbDriver;
    DspProcessor dsp;
    DsdDecoder dsdDecoder;
    SincResampler resampler;
    NativeLosslessDecoder nativeDecoder;
    std::vector<int32_t> nativeReadBuffer;
    AAudioStream* aaudioStream = nullptr;
    std::recursive_mutex aaudioMutex;

    // Bit-Exact Int32 Audio Ring Buffer (262144 int32s = 131072 stereo frames = ~2.7s at 48k, ~1.36s at 96k)
    static constexpr size_t BUFFER_SIZE = 262144;
    std::vector<int32_t> ringBuffer;
    size_t writeHead = 0;
    size_t readHead = 0;
    std::mutex ringBufferMutex;
    std::condition_variable ringBufferCond;

    // Real-time VU meter
    VUMeterLevel vuLevels;
    uint8_t dopMarker = 0x05;
};
