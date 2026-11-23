#pragma once

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <cstdint>

struct UsbAltSettingInfo {
    int interfaceNumber = 1;
    int altSettingIndex = 0;
    int endpointAddress = 0;
    int feedbackEndpoint = 0;
    int feedbackEpInterval = 4;
    bool isIsochronous = true;
    int maxPacketSize = 1024;
    int channels = 2;
    int bitResolution = 16;
    int subslotSize = 2; // bytes per sample
    int epInterval = 1;
    std::vector<int> sampleRates;
};

struct IsoUrbBuffer {
    std::vector<uint8_t> urbStorage;
    std::vector<uint8_t> dataBuffer;
    bool inFlight = false;
};

class UsbAudioDriver {
public:
    UsbAudioDriver();
    ~UsbAudioDriver();

    // Initialize with open Linux USB file descriptor and raw descriptors from Android UsbDeviceConnection
    bool initDevice(int fileDescriptor, int vendorId, int productId,
                    const std::string& deviceName,
                    const uint8_t* rawDesc = nullptr, int rawDescLen = 0);
    void closeDevice(bool reconnectKernelDriver = true);

    bool isConnected() const { return isDeviceOpen.load(); }
    bool isStreamingActive() const { return isStreaming.load(); }
    bool hasFatalError() const { return fatalError.load(); }
    void clearFatalError() { fatalError.store(false); consecutiveSubmitErrors = 0; }
    bool isUAC2() const { return isClass2; }
    std::string getDeviceName() const { return deviceName; }
    int getSampleRate() const { return currentSampleRate; }
    int getBitDepth() const { return currentBitDepth; }
    int getSubslotSize() const { return currentSubslotSize; }

    // Stream Configuration & Dynamic Clock Switching
    bool setAudioFormat(int sampleRate, int bitDepth, int channels);
    bool startStreaming();
    void stopStreaming();

    // Write audio frames directly to USB DAC endpoint (Bit-Perfect conversion to DAC integer format)
    int writeFrames(const void* audioData, int frameCount);
    int writeInt32FramesBitPerfect(const int32_t* intPcm, int frameCount);
    int writeFloatFramesBitPerfect(const float* floatPcm, int frameCount);

    static constexpr float DAC_MAX_VOLUME_SAFETY_LIMIT = 0.80f;

    // Hardware Mixer / Volume control via USB Audio Control Interface (bypasses software digital scaling)
    bool setHardwareVolume(float volume); // 0.0f to hardwareVolumeLimit
    float getHardwareVolume() const { return hardwareVolume; }
    void setHardwareVolumeLimit(float maxLimit);
    float getHardwareVolumeLimit() const { return hardwareVolumeLimit; }
    void setHardwareVolumeCurve(int curve);
    void setHardwareBalance(float balance);
    float getHardwareBalance() const { return hardwareBalance; }
    void setForceVolumeSync(bool enable);
    void setDitherEnabled(bool enable) { ditherEnabled.store(enable); }
    bool isDitherEnabled() const { return ditherEnabled.load(); }
    bool isHardwareVolumeSupported() const { return hasHardwareVolume; }
    bool isStereoHardwareVolumeSupported() const { return stereoHardwareVolumeSupported; }
    int16_t getVolumeMin() const { return volumeMin; }
    int16_t getVolumeMax() const { return volumeMax; }
    int16_t getVolumeRes() const { return volumeRes; }

    // Supported formats probe
    bool isSampleRateSupported(int rate) const;
    std::vector<int> getSupportedSampleRates() const { return supportedSampleRates; }

    // USB HID interface blocking (detaches usbhid driver to block phantom headset button events)
    void setBlockUsbHid(bool enable);
    bool isBlockUsbHid() const { return blockUsbHid.load(); }

private:
    void parseDescriptors(const uint8_t* desc, int length);
    void addSupportedSampleRate(int rate);
    bool claimAudioInterfaces();
    void releaseAudioInterfaces(bool reconnectKernelDriver);
    void claimHidInterfacesLocked();
    void releaseHidInterfacesLocked(bool reconnectKernelDriver);
    void computeIsoTimingLocked(int& packetsPerSec, int& packetsPerUrb) const;
    void queryHardwareVolumeRange();
    bool setSampleRateUAC2(int sampleRate);
    bool setSampleRateUAC1(int sampleRate);
    void unmuteHardware();
    bool setHardwareVolumeLocked(float volume);
    inline double nextTpdfDither();
    void reapUrbs(bool block = false);
    void submitFeedbackUrbLocked();
    void handleFeedbackCompletion(struct usbdevfs_urb* urb);

    int usbFd = -1;
    int vid = 0;
    int pid = 0;
    int cachedUsbSpeed = -1;
    std::string deviceName;
    std::atomic<bool> isDeviceOpen{false};
    std::atomic<bool> isStreaming{false};
    std::atomic<bool> fatalError{false};
    int consecutiveSubmitErrors = 0;
    std::atomic<bool> blockUsbHid{true};
    bool isClass2 = true;

    int audioControlInterface = 0;
    int audioStreamingInterface = 1;
    int streamingAltSetting = 1;
    int isocOutEndpoint = 0x01;
    int feedbackInEndpoint = 0;
    int feedbackEpInterval = 4;
    bool endpointIsIsochronous = true;
    int packetSize = 1024;
    int clockSourceId = 1;

    int currentSampleRate = 44100;
    double feedbackSampleRate = 44100.0;
    bool hasFeedbackLock = false;
    int currentBitDepth = 16;
    int currentChannels = 2;
    int currentSubslotSize = 2;
    int currentEpInterval = 1;
    uint32_t isoPhaseAccumulator = 0;
    double isoPhaseFrac = 0.0;

    bool hasHardwareVolume = false;
    bool hardwareVolumeWorking = false;
    bool stereoHardwareVolumeSupported = false;
    int volumeFeatureUnitId = -1;
    int16_t volumeMin = -96 * 256;
    int16_t volumeMax = 0;
    int16_t volumeRes = 128;
    float hardwareVolume = 0.20f;
    float hardwareVolumeLimit = DAC_MAX_VOLUME_SAFETY_LIMIT;
    int hardwareVolumeCurve = 0; // 0 = Native Linear dB, 1 = Perceptual Top-Spread, 2 = IEM Low-Fine
    float hardwareBalance = 0.0f;
    bool forceVolumeSync = false;
    std::atomic<bool> ditherEnabled{true};
    uint32_t ditherRngState = 0x2468ACE1u;

    std::vector<UsbAltSettingInfo> altSettings;
    std::vector<int> hidInterfaces;
    std::vector<int> claimedHidInterfaces;
    std::vector<int> supportedSampleRates;
    std::vector<uint8_t> pcmConversionBuffer;
    std::vector<uint8_t> residualFramesBuffer;
    std::vector<IsoUrbBuffer> urbRing;
    IsoUrbBuffer feedbackUrb;
    size_t urbRingIndex = 0;
    std::recursive_mutex usbMutex;
};
