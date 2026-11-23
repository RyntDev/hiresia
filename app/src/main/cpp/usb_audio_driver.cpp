#include "usb_audio_driver.h"
#include <android/log.h>
#include <unistd.h>
#include <poll.h>
#include <cerrno>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdlib>

#define LOG_TAG "UsbAudioDriver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// UAC1 / UAC2 constants
constexpr uint8_t UAC_REQUEST_CODE_CUR = 0x01;
constexpr uint8_t UAC_REQUEST_CODE_RANGE = 0x02;
constexpr uint8_t UAC_FU_VOLUME_CONTROL = 0x02;
constexpr uint8_t UAC2_CS_CONTROL_SAM_FREQ = 0x01;

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif
#ifndef USB_SPEED_FULL
#define USB_SPEED_FULL 2
#define USB_SPEED_HIGH 3
#endif

UsbAudioDriver::UsbAudioDriver() {
    supportedSampleRates = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 768000};
    pcmConversionBuffer.resize(65536, 0);
}

UsbAudioDriver::~UsbAudioDriver() {
    closeDevice();
}

void UsbAudioDriver::addSupportedSampleRate(int rate) {
    if (rate <= 0) return;
    for (int r : supportedSampleRates) {
        if (r == rate) return;
    }
    supportedSampleRates.push_back(rate);
    std::sort(supportedSampleRates.begin(), supportedSampleRates.end());
}

void UsbAudioDriver::parseDescriptors(const uint8_t* desc, int length) {
    if (!desc || length <= 0) {
        LOGI("No raw descriptors provided; using standard class 2 defaults");
        return;
    }

    LOGI("Parsing USB raw descriptors (length: %d bytes)...", length);
    int offset = 0;
    int currentInterface = -1;
    int currentAlt = 0;
    bool inAudioControl = false;
    bool inAudioStreaming = false;

    altSettings.clear();
    hidInterfaces.clear();
    supportedSampleRates.clear();
    UsbAltSettingInfo currentAltInfo;
    bool hasActiveAltInfo = false;

    volumeFeatureUnitId = -1;
    hasHardwareVolume = false;
    hardwareVolumeWorking = false;
    stereoHardwareVolumeSupported = false;

    struct TopoOutputTerm {
        uint8_t id = 0;
        uint16_t type = 0;
        uint8_t sourceId = 0;
    };
    struct TopoFeatureUnit {
        uint8_t id = 0;
        uint8_t sourceId = 0;
        bool hasVolCtrl = true;
    };
    struct TopoUnitLink {
        uint8_t id = 0;
        std::vector<uint8_t> sources;
    };
    std::vector<TopoOutputTerm> outputTerms;
    std::vector<TopoFeatureUnit> featureUnits;
    std::vector<TopoUnitLink> mixerSelectorUnits;

    while (offset + 2 <= length) {
        uint8_t bLength = desc[offset];
        if (bLength < 2 || offset + bLength > length) break;
        uint8_t bDescriptorType = desc[offset + 1];

        if (bDescriptorType == 0x04) { // INTERFACE
            // Save completed alt-setting IF it had a valid OUT (playback) endpoint (must not be Alt 0)
            if (hasActiveAltInfo && currentAltInfo.altSettingIndex > 0 && currentAltInfo.endpointAddress != 0 && (currentAltInfo.endpointAddress & 0x80) == 0) {
                if (currentAltInfo.channels <= 0) currentAltInfo.channels = 2;
                altSettings.push_back(currentAltInfo);
                hasActiveAltInfo = false;
            }

            if (bLength >= 9) {
                currentInterface = desc[offset + 2];
                currentAlt = desc[offset + 3];
                uint8_t numEndpoints = desc[offset + 4];
                uint8_t intfClass = desc[offset + 5];
                uint8_t intfSubClass = desc[offset + 6];
                uint8_t intfProtocol = desc[offset + 7];

                if (intfClass == 0x01 && intfSubClass == 0x01) { // Audio Control
                    inAudioControl = true;
                    inAudioStreaming = false;
                    audioControlInterface = currentInterface;
                    if (intfProtocol == 0x20) isClass2 = true;
                    LOGI("Found Audio Control Interface: %d (UAC %s)",
                         audioControlInterface, isClass2 ? "2.0" : "1.0");
                } else if (intfClass == 0x01 && intfSubClass == 0x02) { // Audio Streaming
                    inAudioControl = false;
                    inAudioStreaming = true;
                    currentAltInfo = UsbAltSettingInfo();
                    currentAltInfo.interfaceNumber = currentInterface;
                    currentAltInfo.altSettingIndex = currentAlt;
                    hasActiveAltInfo = true;
                    if (intfProtocol == 0x20) isClass2 = true;
                    LOGI("Found Audio Streaming Interface: %d, AltSetting: %d, Endpoints: %d",
                         currentInterface, currentAlt, numEndpoints);
                } else {
                    inAudioControl = false;
                    inAudioStreaming = false;
                    if (intfClass == 0x03) { // USB HID (Inline mic / headset button controller)
                        if (std::find(hidInterfaces.begin(), hidInterfaces.end(), currentInterface) == hidInterfaces.end()) {
                            hidInterfaces.push_back(currentInterface);
                        }
                        LOGI("Found USB HID Interface: %d (SubClass: 0x%02X, Protocol: 0x%02X)",
                             currentInterface, intfSubClass, intfProtocol);
                    }
                }
            }
        } else if (bDescriptorType == 0x24) { // CS_INTERFACE
            uint8_t subtype = desc[offset + 2];
            if (inAudioControl) {
                if (subtype == 0x01) { // HEADER
                    if (bLength >= 5) {
                        uint16_t bcdADC = desc[offset + 3] | (desc[offset + 4] << 8);
                        if (bcdADC >= 0x0200) isClass2 = true;
                        LOGI("Audio Control Header: bcdADC = 0x%04X (isClass2=%d)", bcdADC, isClass2);
                    }
                } else if (subtype == 0x03) { // OUTPUT_TERMINAL
                    if (bLength >= 8) {
                        TopoOutputTerm ot;
                        ot.id = desc[offset + 3];
                        ot.type = static_cast<uint16_t>(desc[offset + 4]) |
                                  (static_cast<uint16_t>(desc[offset + 5]) << 8);
                        ot.sourceId = desc[offset + 7];
                        outputTerms.push_back(ot);
                        LOGI("Found Output Terminal ID %d (Type: 0x%04X, SourceID: %d)",
                             ot.id, ot.type, ot.sourceId);
                    }
                } else if (subtype == 0x04 || subtype == 0x05) { // MIXER_UNIT / SELECTOR_UNIT
                    if (bLength >= 6) {
                        TopoUnitLink link;
                        link.id = desc[offset + 3];
                        uint8_t nrInPins = desc[offset + 4];
                        for (uint8_t p = 0; p < nrInPins && (5 + p) < bLength; ++p) {
                            link.sources.push_back(desc[offset + 5 + p]);
                        }
                        mixerSelectorUnits.push_back(std::move(link));
                    }
                } else if (subtype == 0x07 || subtype == 0x08) { // PROCESSING_UNIT / EXTENSION_UNIT
                    if (bLength >= 8) {
                        TopoUnitLink link;
                        link.id = desc[offset + 3];
                        uint8_t nrInPins = desc[offset + 6];
                        for (uint8_t p = 0; p < nrInPins && (7 + p) < bLength; ++p) {
                            link.sources.push_back(desc[offset + 7 + p]);
                        }
                        mixerSelectorUnits.push_back(std::move(link));
                    }
                } else if (subtype == 0x06) { // FEATURE_UNIT
                    if (bLength >= 4) {
                        TopoFeatureUnit fu;
                        fu.id = desc[offset + 3];
                        fu.sourceId = (bLength >= 5) ? desc[offset + 4] : 0;
                        fu.hasVolCtrl = false;
                        if (isClass2) {
                            if (bLength >= 10) {
                                int endOffset = static_cast<int>(bLength) - 1;
                                for (int k = 5; k + 3 < endOffset; k += 4) {
                                    if ((desc[offset + k] & 0x0C) != 0) {
                                        fu.hasVolCtrl = true;
                                        break;
                                    }
                                }
                            } else {
                                fu.hasVolCtrl = true;
                            }
                        } else {
                            if (bLength >= 7 && desc[offset + 5] >= 1) {
                                int ctrlSize = desc[offset + 5];
                                int endOffset = static_cast<int>(bLength) - 1;
                                for (int k = 6; k < endOffset; k += ctrlSize) {
                                    if ((desc[offset + k] & 0x02) != 0) {
                                        fu.hasVolCtrl = true;
                                        break;
                                    }
                                }
                            } else {
                                fu.hasVolCtrl = true;
                            }
                        }
                        featureUnits.push_back(fu);
                        LOGI("Found Feature Unit ID: %d (SourceID: %d, HasVolCtrl: %d)",
                             fu.id, fu.sourceId, fu.hasVolCtrl ? 1 : 0);
                    }
                } else if (subtype == 0x0A || subtype == 0x0B) { // CLOCK_SOURCE (UAC2)
                    if (bLength >= 4) {
                        clockSourceId = desc[offset + 3];
                        LOGI("Found UAC2 Clock Source ID: %d", clockSourceId);
                    }
                }
            } else if (inAudioStreaming) {
                if (subtype == 0x01) { // AS_GENERAL (UAC2 & UAC1)
                    if (isClass2 && bLength >= 11) {
                        currentAltInfo.channels = desc[offset + 10];
                        LOGI("UAC2 AS_GENERAL: Channels: %d", currentAltInfo.channels);
                    }
                } else if (subtype == 0x02) { // FORMAT_TYPE
                    uint8_t formatType = desc[offset + 3];
                    if (formatType == 0x01) { // FORMAT_TYPE_I (PCM)
                        if (!isClass2 && bLength >= 8) { // UAC1
                            currentAltInfo.channels = desc[offset + 4];
                            currentAltInfo.subslotSize = desc[offset + 5];
                            currentAltInfo.bitResolution = desc[offset + 6];
                            uint8_t samFreqType = desc[offset + 7];
                            LOGI("UAC1 Format Type: %d ch, %d-bit, %d bytes/subslot, freqCount: %d",
                                 currentAltInfo.channels, currentAltInfo.bitResolution,
                                 currentAltInfo.subslotSize, samFreqType);
                            if (samFreqType > 0) {
                                for (int i = 0; i < samFreqType && offset + 8 + (i * 3) + 3 <= length; ++i) {
                                    int r = desc[offset + 8 + (i * 3)] |
                                            (desc[offset + 8 + (i * 3) + 1] << 8) |
                                            (desc[offset + 8 + (i * 3) + 2] << 16);
                                    if (r > 0) {
                                        currentAltInfo.sampleRates.push_back(r);
                                        addSupportedSampleRate(r);
                                        LOGI("  Supported sample rate: %d Hz", r);
                                    }
                                }
                            }
                        } else if (isClass2 && bLength >= 6) { // UAC2
                            currentAltInfo.subslotSize = desc[offset + 4];
                            currentAltInfo.bitResolution = desc[offset + 5];
                            LOGI("UAC2 Format Type: %d-bit, %d bytes/subslot",
                                 currentAltInfo.bitResolution, currentAltInfo.subslotSize);
                        }
                    }
                }
            }
        } else if (bDescriptorType == 0x05) { // ENDPOINT
            if (inAudioStreaming && bLength >= 7) {
                uint8_t epAddr = desc[offset + 2];
                uint8_t epAttr = desc[offset + 3];
                uint16_t maxPkt = desc[offset + 4] | (desc[offset + 5] << 8);
                uint8_t epInterval = (bLength >= 7) ? desc[offset + 6] : 1;

                if ((epAddr & 0x80) == 0) { // OUT Endpoint (Audio Playback)
                    currentAltInfo.interfaceNumber = currentInterface;
                    currentAltInfo.endpointAddress = epAddr;
                    currentAltInfo.isIsochronous = ((epAttr & 0x03) == 0x01);
                    currentAltInfo.maxPacketSize = maxPkt & 0x07FF;
                    currentAltInfo.epInterval = (epInterval > 0) ? epInterval : 1;
                    LOGI("Found OUT Endpoint: 0x%02X on Iface %d, Alt %d (%s, MaxPacket: %d bytes, Interval: %d)",
                         epAddr, currentInterface, currentAlt,
                         currentAltInfo.isIsochronous ? "Isochronous" : "Bulk",
                         currentAltInfo.maxPacketSize, currentAltInfo.epInterval);
                } else if (((epAttr & 0x03) == 0x01) &&
                           (((epAttr & 0x30) == 0x10) || (maxPkt <= 8)) &&
                           currentAltInfo.endpointAddress != 0) {
                    // Asynchronous Isochronous IN Feedback Endpoint (UAC1 3-byte 10.14 or UAC2 4-byte 16.16)
                    currentAltInfo.feedbackEndpoint = epAddr;
                    currentAltInfo.feedbackEpInterval = (epInterval > 0) ? epInterval : 4;
                    LOGI("Found Async Feedback IN Endpoint: 0x%02X on Iface %d, Alt %d (MaxPacket: %d, Interval: %d)",
                         epAddr, currentInterface, currentAlt, maxPkt & 0x07FF, currentAltInfo.feedbackEpInterval);
                }
            }
        }
        offset += bLength;
    }

    if (hasActiveAltInfo && currentAltInfo.altSettingIndex > 0 && currentAltInfo.endpointAddress != 0 && (currentAltInfo.endpointAddress & 0x80) == 0) {
        if (currentAltInfo.channels <= 0) currentAltInfo.channels = 2;
        altSettings.push_back(currentAltInfo);
    }

    // Resolve the correct playback Feature Unit via UAC Topology Graph traversal
    // Priority: Output Terminal (0x0302 Headphones / 0x0301 Speaker / 0x03xx) -> Feature Unit with Volume Control
    if (!featureUnits.empty()) {
        std::sort(outputTerms.begin(), outputTerms.end(), [](const TopoOutputTerm& a, const TopoOutputTerm& b) {
            auto score = [](uint16_t t) {
                if (t == 0x0302) return 3; // Headphones
                if (t == 0x0301) return 2; // Speaker
                if (t >= 0x0300 && t <= 0x03FF) return 1; // Other playback output
                return 0;
            };
            return score(a.type) > score(b.type);
        });

        auto findFeatureUnitFromSource = [&](uint8_t startSourceId, bool requireVolBit) -> int {
            bool visited[256] = {false};
            struct Node { uint8_t id; int depth; };
            std::vector<Node> queue;
            if (startSourceId != 0) queue.push_back({startSourceId, 0});
            size_t head = 0;
            while (head < queue.size()) {
                Node cur = queue[head++];
                if (visited[cur.id] || cur.depth > 16) continue;
                visited[cur.id] = true;

                for (const auto& fu : featureUnits) {
                    if (fu.id == cur.id) {
                        if (!requireVolBit || fu.hasVolCtrl) {
                            return fu.id;
                        }
                        if (fu.sourceId != 0) {
                            queue.push_back({fu.sourceId, cur.depth + 1});
                        }
                    }
                }
                for (const auto& u : mixerSelectorUnits) {
                    if (u.id == cur.id) {
                        for (uint8_t src : u.sources) {
                            if (src != 0) queue.push_back({src, cur.depth + 1});
                        }
                    }
                }
            }
            return -1;
        };

        int resolvedFu = -1;
        for (const auto& ot : outputTerms) {
            if (ot.type >= 0x0300 && ot.type <= 0x03FF) {
                resolvedFu = findFeatureUnitFromSource(ot.sourceId, true);
                if (resolvedFu > 0) break;
            }
        }
        if (resolvedFu < 0) {
            for (const auto& ot : outputTerms) {
                if (ot.type >= 0x0300 && ot.type <= 0x03FF) {
                    resolvedFu = findFeatureUnitFromSource(ot.sourceId, false);
                    if (resolvedFu > 0) break;
                }
            }
        }
        if (resolvedFu < 0) {
            for (const auto& fu : featureUnits) {
                if (fu.hasVolCtrl) {
                    resolvedFu = fu.id;
                    break;
                }
            }
        }
        if (resolvedFu < 0) {
            resolvedFu = featureUnits.front().id;
        }
        volumeFeatureUnitId = resolvedFu;
        hasHardwareVolume = (volumeFeatureUnitId > 0);
        LOGI("Resolved Playback Feature Unit ID: %d (out of %zu Feature Units)",
             volumeFeatureUnitId, featureUnits.size());
    }

    // Default sample rates for UAC2 DACs if not listed in format descriptors
    if (supportedSampleRates.empty()) {
        supportedSampleRates = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 768000};
    }

    for (const auto& alt : altSettings) {
        if (alt.altSettingIndex > 0 && alt.endpointAddress != 0) {
            audioStreamingInterface = alt.interfaceNumber;
            streamingAltSetting = alt.altSettingIndex;
            isocOutEndpoint = alt.endpointAddress;
            endpointIsIsochronous = alt.isIsochronous;
            packetSize = alt.maxPacketSize > 0 ? alt.maxPacketSize : 1024;
            currentSubslotSize = alt.subslotSize > 0 ? alt.subslotSize : 2;
            LOGI("Audio OUT Default Target: Interface %d, Alt %d, Endpoint 0x%02X (%s, MaxPacket: %d, Subslot: %d)",
                 audioStreamingInterface, streamingAltSetting, isocOutEndpoint,
                 endpointIsIsochronous ? "ISO" : "BULK", packetSize, currentSubslotSize);
            break;
        }
    }

    LOGI("USB descriptor parsing completed: %zu playback alt-settings, %zu supported sample rates, %zu HID interfaces",
         altSettings.size(), supportedSampleRates.size(), hidInterfaces.size());
}

bool UsbAudioDriver::initDevice(int fd, int vendorId, int productId,
                                const std::string& name,
                                const uint8_t* rawDesc, int rawDescLen) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    closeDevice(false);

    if (fd < 0) {
        LOGE("Invalid USB file descriptor: %d", fd);
        return false;
    }

    usbFd = fd;
    vid = vendorId;
    pid = productId;
    deviceName = name.empty() ? "USB Audio DAC" : name;
    fatalError.store(false);
    consecutiveSubmitErrors = 0;

    cachedUsbSpeed = ioctl(usbFd, USBDEVFS_GET_SPEED, 0);
    if (cachedUsbSpeed < 0) {
        cachedUsbSpeed = -1;
    }

    LOGI("Initializing USB Audio Device: %s (VID: 0x%04X, PID: 0x%04X, FD: %d, Speed: %d)",
         deviceName.c_str(), vid, pid, usbFd, cachedUsbSpeed);

    // Parse hardware descriptors
    parseDescriptors(rawDesc, rawDescLen);

    if (!claimAudioInterfaces()) {
        LOGW("Could not claim standard USB Audio interfaces; will attempt direct stream write");
    }

    if (blockUsbHid.load()) {
        claimHidInterfacesLocked();
    }

    isDeviceOpen = true;

    // Probe hardware volume mixer range directly from DAC Feature Unit
    queryHardwareVolumeRange();
    setHardwareVolumeLocked(hardwareVolume);

    LOGI("USB Audio Driver initialized successfully (FD: %d, AC Iface: %d, AS Iface: %d, UAC2: %d)",
         usbFd, audioControlInterface, audioStreamingInterface, isClass2);
    return true;
}

void UsbAudioDriver::closeDevice(bool reconnectKernelDriver) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    if (!isDeviceOpen && usbFd < 0) return;

    LOGI("Closing USB Audio Device (FD: %d, reconnectKernelDriver=%d)", usbFd, reconnectKernelDriver ? 1 : 0);
    stopStreaming();
    releaseHidInterfacesLocked(reconnectKernelDriver);
    releaseAudioInterfaces(reconnectKernelDriver);
    hidInterfaces.clear();

    usbFd = -1;
    cachedUsbSpeed = -1;
    isDeviceOpen = false;
    fatalError.store(false);
    consecutiveSubmitErrors = 0;
}

bool UsbAudioDriver::claimAudioInterfaces() {
    if (usbFd < 0) return false;

    std::vector<int> ifaces;
    if (audioControlInterface >= 0) ifaces.push_back(audioControlInterface);
    if (audioStreamingInterface >= 0 && std::find(ifaces.begin(), ifaces.end(), audioStreamingInterface) == ifaces.end()) {
        ifaces.push_back(audioStreamingInterface);
    }
    for (const auto& alt : altSettings) {
        if (alt.interfaceNumber >= 0 && std::find(ifaces.begin(), ifaces.end(), alt.interfaceNumber) == ifaces.end()) {
            ifaces.push_back(alt.interfaceNumber);
        }
    }

    for (int ifaceNum : ifaces) {
        struct usbdevfs_ioctl disconnect;
        disconnect.ifno = ifaceNum;
        disconnect.ioctl_code = USBDEVFS_DISCONNECT;
        disconnect.data = nullptr;
        int dcRes = ioctl(usbFd, USBDEVFS_IOCTL, &disconnect);
        LOGI("USBDEVFS_DISCONNECT on Iface %d returned %d (%s)",
             ifaceNum, dcRes, strerror(errno));

        int iface = ifaceNum;
        if (ioctl(usbFd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
            LOGW("Failed to claim USB Interface %d: %s", iface, strerror(errno));
        } else {
            LOGI("Claimed USB Interface %d", iface);
        }
    }

    return true;
}

void UsbAudioDriver::releaseAudioInterfaces(bool reconnectKernelDriver) {
    if (usbFd < 0) return;

    // Release streaming Alt setting 0 (idle)
    struct usbdevfs_setinterface setIntf;
    setIntf.interface = audioStreamingInterface;
    setIntf.altsetting = 0;
    ioctl(usbFd, USBDEVFS_SETINTERFACE, &setIntf);

    std::vector<int> ifaces;
    if (audioStreamingInterface >= 0) ifaces.push_back(audioStreamingInterface);
    if (audioControlInterface >= 0 && std::find(ifaces.begin(), ifaces.end(), audioControlInterface) == ifaces.end()) {
        ifaces.push_back(audioControlInterface);
    }
    for (const auto& alt : altSettings) {
        if (alt.interfaceNumber >= 0 && std::find(ifaces.begin(), ifaces.end(), alt.interfaceNumber) == ifaces.end()) {
            ifaces.push_back(alt.interfaceNumber);
        }
    }
    for (int ifaceNum : ifaces) {
        int iface = ifaceNum;
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface);
        if (reconnectKernelDriver) {
            struct usbdevfs_ioctl connect;
            std::memset(&connect, 0, sizeof(connect));
            connect.ifno = ifaceNum;
            connect.ioctl_code = USBDEVFS_CONNECT;
            connect.data = nullptr;
            int connRes = ioctl(usbFd, USBDEVFS_IOCTL, &connect);
            LOGI("Released USB Audio Iface %d & reconnected kernel driver (connRes=%d)", ifaceNum, connRes);
        }
    }
}

void UsbAudioDriver::claimHidInterfacesLocked() {
    if (usbFd < 0 || hidInterfaces.empty()) return;

    for (int ifaceNum : hidInterfaces) {
        if (std::find(claimedHidInterfaces.begin(), claimedHidInterfaces.end(), ifaceNum) != claimedHidInterfaces.end()) {
            continue;
        }

        struct usbdevfs_ioctl disconnect;
        std::memset(&disconnect, 0, sizeof(disconnect));
        disconnect.ifno = ifaceNum;
        disconnect.ioctl_code = USBDEVFS_DISCONNECT;
        disconnect.data = nullptr;
        int dcRes = ioctl(usbFd, USBDEVFS_IOCTL, &disconnect);

        int iface = ifaceNum;
        if (ioctl(usbFd, USBDEVFS_CLAIMINTERFACE, &iface) >= 0) {
            claimedHidInterfaces.push_back(ifaceNum);
            LOGI("Blocked & claimed USB HID Interface %d (dcRes=%d)", ifaceNum, dcRes);
        } else {
            LOGW("Failed to claim USB HID Interface %d (dcRes=%d): %s",
                 ifaceNum, dcRes, strerror(errno));
        }
    }
}

void UsbAudioDriver::releaseHidInterfacesLocked(bool reconnectKernelDriver) {
    if (usbFd >= 0) {
        for (int ifaceNum : claimedHidInterfaces) {
            int iface = ifaceNum;
            int relRes = ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface);
            int connRes = -1;
            if (reconnectKernelDriver) {
                struct usbdevfs_ioctl connect;
                std::memset(&connect, 0, sizeof(connect));
                connect.ifno = ifaceNum;
                connect.ioctl_code = USBDEVFS_CONNECT;
                connect.data = nullptr;
                connRes = ioctl(usbFd, USBDEVFS_IOCTL, &connect);
            }
            LOGI("Released USB HID Interface %d (relRes=%d, reconnect=%d, connRes=%d)",
                 ifaceNum, relRes, reconnectKernelDriver ? 1 : 0, connRes);
        }
    }
    claimedHidInterfaces.clear();
}

void UsbAudioDriver::setBlockUsbHid(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    blockUsbHid.store(enable);
    LOGI("USB HID Blocking: %s", enable ? "ENABLED" : "DISABLED");
    if (isDeviceOpen && usbFd >= 0) {
        if (enable) {
            claimHidInterfacesLocked();
        } else {
            releaseHidInterfacesLocked(true);
        }
    }
}

void UsbAudioDriver::queryHardwareVolumeRange() {
    if (usbFd < 0 || volumeFeatureUnitId <= 0) {
        hasHardwareVolume = false;
        return;
    }

    uint16_t fuIndex = (volumeFeatureUnitId << 8) | audioControlInterface;
    bool foundRange = false;

    if (isClass2) {
        // UAC2: Probe Volume Control Range (CS_CONTROL_RANGE = 0x02, VOLUME_CONTROL = 0x02)
        // Probe Channel 1 (Left), Channel 2 (Right), or Master (0x00) with a 64-byte buffer
        for (uint8_t ch : {0x01, 0x02, 0x00}) {
            uint16_t wValue = (0x02 << 8) | ch;
            uint8_t rangeBuf[64] = {0};

            struct usbdevfs_ctrltransfer ctrl;
            std::memset(&ctrl, 0, sizeof(ctrl));
            ctrl.bRequestType = 0xA1; // Device to Host | Class | Interface
            ctrl.bRequest = 0x02;     // UAC2 RANGE
            ctrl.wValue = wValue;
            ctrl.wIndex = fuIndex;
            ctrl.wLength = sizeof(rangeBuf);
            ctrl.timeout = 100;
            ctrl.data = rangeBuf;

            int res = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);
            if (res >= 8) {
                uint16_t numSubRanges = rangeBuf[0] | (rangeBuf[1] << 8);
                if (numSubRanges >= 1) {
                    int16_t rMin = static_cast<int16_t>(rangeBuf[2] | (rangeBuf[3] << 8));
                    int16_t rMax = static_cast<int16_t>(rangeBuf[4] | (rangeBuf[5] << 8));
                    int16_t rRes = static_cast<int16_t>(rangeBuf[6] | (rangeBuf[7] << 8));

                    if (rMax > rMin) {
                        volumeMin = rMin;
                        volumeMax = rMax;
                        volumeRes = (rRes > 0) ? rRes : 128;
                        hasHardwareVolume = true;
                        hardwareVolumeWorking = true;
                        foundRange = true;
                        LOGI("UAC2 DAC Hardware Volume Range probed on Ch %d: Min=%d (%.2f dB), Max=%d (%.2f dB), Res=%d (%.2f dB)",
                             ch, volumeMin, volumeMin / 256.0f, volumeMax, volumeMax / 256.0f, volumeRes, volumeRes / 256.0f);
                        break;
                    }
                }
            }
        }
    }

    if (!foundRange) {
        // UAC1: Probe GET_MIN (0x82), GET_MAX (0x83), GET_RES (0x84)
        for (uint8_t ch : {0x01, 0x02, 0x00}) {
            uint16_t wValue = (0x02 << 8) | ch;
            int16_t rMin = 0, rMax = 0, rRes = 0;

            struct usbdevfs_ctrltransfer ctrl;
            std::memset(&ctrl, 0, sizeof(ctrl));
            ctrl.bRequestType = 0xA1; // Device to Host | Class | Interface
            ctrl.wValue = wValue;
            ctrl.wIndex = fuIndex;
            ctrl.wLength = 2;
            ctrl.timeout = 100;

            ctrl.bRequest = 0x82; // GET_MIN
            ctrl.data = &rMin;
            int resMin = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);

            ctrl.bRequest = 0x83; // GET_MAX
            ctrl.data = &rMax;
            int resMax = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);

            ctrl.bRequest = 0x84; // GET_RES
            ctrl.data = &rRes;
            int resRes = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);

            if (resMin >= 2 && resMax >= 2 && rMax > rMin) {
                volumeMin = rMin;
                volumeMax = rMax;
                volumeRes = (resRes >= 2 && rRes > 0) ? rRes : 128;
                hasHardwareVolume = true;
                hardwareVolumeWorking = true;
                foundRange = true;
                LOGI("UAC1 DAC Hardware Volume Range probed on Ch %d: Min=%d (%.2f dB), Max=%d (%.2f dB), Res=%d (%.2f dB)",
                     ch, volumeMin, volumeMin / 256.0f, volumeMax, volumeMax / 256.0f, volumeRes, volumeRes / 256.0f);
                break;
            }
        }
    }

    if (!foundRange) {
        LOGI("Hardware Volume Range probe unhandled by DAC; defaulting to standard UAC mixer range (-96 dB to 0 dB) on Feature Unit %d", volumeFeatureUnitId);
        volumeMin = -96 * 256;
        volumeMax = 0;
        volumeRes = 128;
        hasHardwareVolume = true;
        hardwareVolumeWorking = true;
    }
}

bool UsbAudioDriver::setAudioFormat(int sampleRate, int bitDepth, int channels) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    currentSampleRate = sampleRate;
    currentBitDepth = bitDepth;
    currentChannels = channels > 0 ? channels : 2;

    LOGI("Configuring USB Audio format: %d Hz, %d-bit, %d ch (Bit-Perfect Direct)",
         sampleRate, bitDepth, channels);

    // Pick best matching alternate setting (rate + bit-depth aware scoring)
    currentSubslotSize = (bitDepth >= 24) ? 4 : 2;
    feedbackInEndpoint = 0;

    const UsbAltSettingInfo* bestAlt = nullptr;
    int bestScore = -10000;
    for (const auto& alt : altSettings) {
        if (alt.altSettingIndex == 0 || alt.endpointAddress == 0) continue;

        int score = 0;
        // Check explicit sample rate support (important for UAC1 alt-settings)
        if (!alt.sampleRates.empty()) {
            bool supportsRate = (std::find(alt.sampleRates.begin(), alt.sampleRates.end(), sampleRate) != alt.sampleRates.end());
            score += supportsRate ? 200 : -200;
        } else {
            score += 50; // UAC2 alt-settings use clock source rather than per-alt list
        }

        // Bit depth preference: exact match > higher bit depth (zero-padded lossless) > lower bit depth
        if (alt.bitResolution == bitDepth) {
            score += 100;
        } else if (alt.bitResolution > bitDepth) {
            score += 70 - (alt.bitResolution - bitDepth); // Prefer 24-bit over 32-bit for 24-bit, etc.
        } else {
            score -= (bitDepth - alt.bitResolution) * 4;
        }

        // Prefer matching channel count & async feedback support
        if (alt.channels == currentChannels) score += 20;
        if (alt.feedbackEndpoint != 0) score += 10;

        if (score > bestScore) {
            bestScore = score;
            bestAlt = &alt;
        }
    }

    if (bestAlt != nullptr) {
        audioStreamingInterface = bestAlt->interfaceNumber;
        streamingAltSetting = bestAlt->altSettingIndex;
        isocOutEndpoint = bestAlt->endpointAddress;
        feedbackInEndpoint = bestAlt->feedbackEndpoint;
        feedbackEpInterval = bestAlt->feedbackEpInterval;
        endpointIsIsochronous = bestAlt->isIsochronous;
        packetSize = bestAlt->maxPacketSize > 0 ? bestAlt->maxPacketSize : 1024;
        currentSubslotSize = bestAlt->subslotSize > 0 ? bestAlt->subslotSize : currentSubslotSize;
        currentEpInterval = bestAlt->epInterval > 0 ? bestAlt->epInterval : 1;
    }

    isoPhaseAccumulator = 0;
    isoPhaseFrac = 0.0;
    hasFeedbackLock = false;
    feedbackSampleRate = static_cast<double>(sampleRate);

    LOGI("Selected Playback Target: Interface %d, Alt Setting: %d, Endpoint: 0x%02X (%s), FeedbackEP: 0x%02X, Subslot: %d bytes, PktSize: %d",
         audioStreamingInterface, streamingAltSetting, isocOutEndpoint,
         endpointIsIsochronous ? "ISO" : "BULK", feedbackInEndpoint, currentSubslotSize, packetSize);

    // Crucial: Set interface to Alt 0 (Zero-Bandwidth) so the DAC PLL can lock clock cleanly!
    if (usbFd >= 0 && audioStreamingInterface >= 0) {
        struct usbdevfs_setinterface setIntf;
        setIntf.interface = audioStreamingInterface;
        setIntf.altsetting = 0;
        ioctl(usbFd, USBDEVFS_SETINTERFACE, &setIntf);
    }

    // Configure DAC Clock Source sample rate
    bool rateOk = false;
    if (isClass2) {
        rateOk = setSampleRateUAC2(sampleRate);
    } else {
        rateOk = setSampleRateUAC1(sampleRate);
    }

    if (!rateOk && sampleRate != 48000 && sampleRate != 44100) {
        // If DAC rejected ultra-high or unusual clock, fallback to highest standard rate
        int fallbackRate = (sampleRate % 44100 == 0) ? 44100 : 48000;
        LOGW("DAC rejected %d Hz clock! Attempting fallback rate %d Hz", sampleRate, fallbackRate);
        bool fallbackOk = isClass2 ? setSampleRateUAC2(fallbackRate) : setSampleRateUAC1(fallbackRate);
        if (fallbackOk) {
            currentSampleRate = fallbackRate;
        }
    }

    return true;
}

bool UsbAudioDriver::setSampleRateUAC2(int sampleRate) {
    if (usbFd < 0) return false;

    struct usbdevfs_ctrltransfer ctrl;
    std::memset(&ctrl, 0, sizeof(ctrl));
    uint32_t rate = sampleRate;

    // Control transfer to Clock Source ID
    ctrl.bRequestType = 0x21; // Host to Device | Class | Interface
    ctrl.bRequest = UAC_REQUEST_CODE_CUR;
    ctrl.wValue = (UAC2_CS_CONTROL_SAM_FREQ << 8); // CS Clock frequency control
    ctrl.wIndex = (clockSourceId << 8) | audioControlInterface;
    ctrl.wLength = 4;
    ctrl.timeout = 250;
    ctrl.data = &rate;

    int res = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);
    if (res < 0) {
        // Fallback search across standard UAC2 Clock Source IDs (1, 0x29, 0x10, 2, 4)
        const uint8_t fallbackIds[] = {1, 0x29, 0x10, 2, 4};
        for (uint8_t id : fallbackIds) {
            if (id == clockSourceId) continue;
            ctrl.wIndex = (id << 8) | audioControlInterface;
            res = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);
            if (res >= 0) {
                clockSourceId = id;
                LOGI("UAC2 sample rate accepted on fallback Clock ID %d", id);
                break;
            }
        }
    }

    if (res >= 0) {
        LOGI("UAC2 DAC SET_CUR sent for %d Hz (Clock ID %d)", sampleRate, clockSourceId);
        // Query GET_CUR to verify what clock rate the hardware actually locked to (UAC2 standard)
        uint32_t activeClock = 0;
        struct usbdevfs_ctrltransfer getCtrl;
        std::memset(&getCtrl, 0, sizeof(getCtrl));
        getCtrl.bRequestType = 0xA1; // Device to Host | Class | Interface
        getCtrl.bRequest = UAC_REQUEST_CODE_CUR;
        getCtrl.wValue = (UAC2_CS_CONTROL_SAM_FREQ << 8);
        getCtrl.wIndex = (clockSourceId << 8) | audioControlInterface;
        getCtrl.wLength = 4;
        getCtrl.timeout = 250;
        getCtrl.data = &activeClock;

        int getRes = ioctl(usbFd, USBDEVFS_CONTROL, &getCtrl);
        if (getRes >= 0 && activeClock > 0) {
            LOGI("UAC2 DAC GET_CUR verified hardware clock frequency: %u Hz", activeClock);
            currentSampleRate = activeClock;
        } else {
            currentSampleRate = sampleRate;
        }

        // Allow hardware PLL 10ms to settle clock lock before audio streaming begins
        usleep(10000);
        return true;
    }

    LOGW("UAC2 sample rate control transfer returned: %d (%s)", res, strerror(errno));
    return false;
}

bool UsbAudioDriver::setSampleRateUAC1(int sampleRate) {
    if (usbFd < 0) return false;

    struct usbdevfs_ctrltransfer ctrl;
    std::memset(&ctrl, 0, sizeof(ctrl));
    uint8_t rateBytes[3];
    rateBytes[0] = static_cast<uint8_t>(sampleRate & 0xFF);
    rateBytes[1] = static_cast<uint8_t>((sampleRate >> 8) & 0xFF);
    rateBytes[2] = static_cast<uint8_t>((sampleRate >> 16) & 0xFF);

    ctrl.bRequestType = 0x22; // Host to Device | Class | Endpoint
    ctrl.bRequest = UAC_REQUEST_CODE_CUR;
    ctrl.wValue = (0x01 << 8); // Sampling Frequency Control
    ctrl.wIndex = isocOutEndpoint;
    ctrl.wLength = 3;
    ctrl.timeout = 250;
    ctrl.data = rateBytes;

    int res = ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);
    if (res < 0) {
        LOGW("UAC1 sample rate control transfer result: %d (%s)", res, strerror(errno));
        return false;
    }

    // Verify clock rate with GET_CUR on endpoint
    uint8_t readBytes[3] = {0, 0, 0};
    struct usbdevfs_ctrltransfer getCtrl;
    std::memset(&getCtrl, 0, sizeof(getCtrl));
    getCtrl.bRequestType = 0xA2; // Device to Host | Class | Endpoint
    getCtrl.bRequest = UAC_REQUEST_CODE_CUR;
    getCtrl.wValue = (0x01 << 8);
    getCtrl.wIndex = isocOutEndpoint;
    getCtrl.wLength = 3;
    getCtrl.timeout = 250;
    getCtrl.data = readBytes;

    int getRes = ioctl(usbFd, USBDEVFS_CONTROL, &getCtrl);
    if (getRes >= 0) {
        int readRate = readBytes[0] | (readBytes[1] << 8) | (readBytes[2] << 16);
        if (readRate > 0) {
            LOGI("UAC1 DAC GET_CUR verified hardware rate: %d Hz", readRate);
            currentSampleRate = readRate;
        } else {
            currentSampleRate = sampleRate;
        }
    } else {
        currentSampleRate = sampleRate;
    }

    LOGI("UAC1 DAC sample rate locked to %d Hz", currentSampleRate);
    return true;
}

void UsbAudioDriver::computeIsoTimingLocked(int& packetsPerSec, int& packetsPerUrb) const {
    if (cachedUsbSpeed == USB_SPEED_FULL) {
        int intervalMs = std::max(1, currentEpInterval);
        packetsPerSec = 1000 / intervalMs;
        if (packetsPerSec <= 0) packetsPerSec = 1000;
        packetsPerUrb = 4;
    } else if (cachedUsbSpeed == USB_SPEED_HIGH || cachedUsbSpeed > USB_SPEED_FULL) {
        int shift = std::clamp(currentEpInterval - 1, 0, 4);
        packetsPerSec = 8000 >> shift;
        if (packetsPerSec <= 0) packetsPerSec = 8000;
        packetsPerUrb = 8;
    } else {
        if (isClass2) {
            int shift = std::clamp(currentEpInterval - 1, 0, 4);
            packetsPerSec = 8000 >> shift;
            if (packetsPerSec <= 0) packetsPerSec = 8000;
            packetsPerUrb = 8;
        } else {
            int intervalMs = std::max(1, currentEpInterval);
            packetsPerSec = 1000 / intervalMs;
            if (packetsPerSec <= 0) packetsPerSec = 1000;
            packetsPerUrb = 4;
        }
    }
}

bool UsbAudioDriver::startStreaming() {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    if (!isDeviceOpen || usbFd < 0) return false;
    if (isStreaming) return true; // Already streaming; do not reset active URBs

    fatalError.store(false);
    consecutiveSubmitErrors = 0;

    // Allocate persistent URB ring
    constexpr int NUM_URBS = 8;
    constexpr int MAX_PACKETS_PER_URB = 128;
    constexpr size_t URB_DATA_CAPACITY = 65536; // 64 KB per URB

    urbRing.clear();
    urbRingIndex = 0;
    isoPhaseAccumulator = 0;
    for (int i = 0; i < NUM_URBS; ++i) {
        IsoUrbBuffer buf;
        size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                               MAX_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
        buf.urbStorage.assign(urbStructSize, 0);
        buf.dataBuffer.assign(URB_DATA_CAPACITY, 0);
        buf.inFlight = false;
        urbRing.push_back(std::move(buf));
    }

    // Activate the isochronous audio streaming alternate interface with retry
    struct usbdevfs_setinterface setIntf;
    setIntf.interface = audioStreamingInterface;
    setIntf.altsetting = streamingAltSetting;

    int res = -1;
    int lastErrno = 0;
    for (int attempt = 0; attempt < 3; ++attempt) {
        res = ioctl(usbFd, USBDEVFS_SETINTERFACE, &setIntf);
        if (res >= 0) break;
        lastErrno = errno;
        LOGW("SETINTERFACE alt %d on iface %d attempt %d failed: %s (errno %d)",
             streamingAltSetting, audioStreamingInterface, attempt + 1, strerror(lastErrno), lastErrno);
        if (lastErrno == ENODEV || lastErrno == ESHUTDOWN || lastErrno == EBADF) {
            fatalError.store(true);
            return false;
        }
        usleep(5000);
    }

    if (res < 0) {
        LOGE("Failed to set alternate setting %d on interface %d: %s (errno %d)",
             streamingAltSetting, audioStreamingInterface, strerror(lastErrno), lastErrno);
        if (lastErrno == ENODEV || lastErrno == ESHUTDOWN || lastErrno == EBADF) {
            fatalError.store(true);
        }
        return false;
    }

    LOGI("Audio Streaming Alternate Setting %d activated on Interface %d",
         streamingAltSetting, audioStreamingInterface);

    usleep(10000); // 10ms hardware interface settling delay

    if (endpointIsIsochronous && !urbRing.empty()) {
        int bytesPerSample = currentSubslotSize > 0 ? currentSubslotSize : ((currentBitDepth >= 24) ? 4 : 2);
        int channels = currentChannels > 0 ? currentChannels : 2;
        int frameBytes = channels * bytesPerSample;
        int packetsPerSec = 8000;
        int packetsPerUrb = 8;
        computeIsoTimingLocked(packetsPerSec, packetsPerUrb);
        int framesPerPkt = currentSampleRate / packetsPerSec;
        if (framesPerPkt <= 0) framesPerPkt = 1;
        int pktLen = framesPerPkt * frameBytes;
        if (packetSize > 0 && pktLen > packetSize) {
            pktLen = (packetSize / frameBytes) * frameBytes;
            if (pktLen < frameBytes) pktLen = frameBytes;
        }
        constexpr int WARMUP_PACKETS = 8;
        int totLen = pktLen * WARMUP_PACKETS;

        // Submit 2 silent URBs to prime the DAC's FIFO and clock sync before audio frames arrive
        for (int w = 0; w < 2 && w < static_cast<int>(urbRing.size()); ++w) {
            auto& slot = urbRing[w];
            if (slot.dataBuffer.size() < static_cast<size_t>(totLen)) {
                slot.dataBuffer.resize(totLen * 2, 0);
            } else {
                std::memset(slot.dataBuffer.data(), 0, totLen);
            }

            size_t requiredUrbSize = sizeof(struct usbdevfs_urb) +
                                     WARMUP_PACKETS * sizeof(struct usbdevfs_iso_packet_desc);
            if (slot.urbStorage.size() < requiredUrbSize) {
                slot.urbStorage.resize(requiredUrbSize, 0);
            }

            struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(slot.urbStorage.data());
            urb->type = USBDEVFS_URB_TYPE_ISO;
            urb->endpoint = isocOutEndpoint;
            urb->flags = USBDEVFS_URB_ISO_ASAP;
            urb->buffer = slot.dataBuffer.data();
            urb->buffer_length = totLen;
            urb->actual_length = 0;
            urb->start_frame = 0;
            urb->number_of_packets = WARMUP_PACKETS;
            urb->error_count = 0;
            urb->signr = 0;
            urb->usercontext = &slot;

            for (int p = 0; p < WARMUP_PACKETS; ++p) {
                urb->iso_frame_desc[p].length = pktLen;
                urb->iso_frame_desc[p].actual_length = 0;
                urb->iso_frame_desc[p].status = 0;
            }

            if (ioctl(usbFd, USBDEVFS_SUBMITURB, urb) >= 0) {
                slot.inFlight = true;
                urbRingIndex = (urbRingIndex + 1) % urbRing.size();
            } else if (errno == ENODEV || errno == ESHUTDOWN || errno == EBADF) {
                fatalError.store(true);
                return false;
            }
        }
    }

    isStreaming = true;
    isoPhaseFrac = 0.0;
    hasFeedbackLock = false;
    feedbackSampleRate = static_cast<double>(currentSampleRate);

    // Start closed-loop asynchronous feedback URB if DAC provides a Feedback IN endpoint
    if (endpointIsIsochronous && feedbackInEndpoint != 0) {
        submitFeedbackUrbLocked();
    }

    // Unmute hardware channels symmetrically (Left + Right)
    unmuteHardware();

    return true;
}

void UsbAudioDriver::submitFeedbackUrbLocked() {
    if (usbFd < 0 || feedbackInEndpoint == 0 || !isStreaming) return;
    if (feedbackUrb.inFlight) return;

    constexpr int FB_PKTS = 1;
    size_t reqSize = sizeof(struct usbdevfs_urb) + FB_PKTS * sizeof(struct usbdevfs_iso_packet_desc);
    if (feedbackUrb.urbStorage.size() < reqSize) {
        feedbackUrb.urbStorage.assign(reqSize, 0);
    }
    if (feedbackUrb.dataBuffer.size() < 8) {
        feedbackUrb.dataBuffer.assign(8, 0);
    }
    std::memset(feedbackUrb.dataBuffer.data(), 0, 8);

    struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(feedbackUrb.urbStorage.data());
    std::memset(urb, 0, reqSize);
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = feedbackInEndpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = feedbackUrb.dataBuffer.data();
    urb->buffer_length = isClass2 ? 4 : 3;
    urb->number_of_packets = FB_PKTS;
    urb->usercontext = &feedbackUrb;
    urb->iso_frame_desc[0].length = isClass2 ? 4 : 3;

    if (ioctl(usbFd, USBDEVFS_SUBMITURB, urb) >= 0) {
        feedbackUrb.inFlight = true;
    }
}

void UsbAudioDriver::handleFeedbackCompletion(struct usbdevfs_urb* urb) {
    feedbackUrb.inFlight = false;
    if (!urb || !isStreaming || urb->number_of_packets < 1) return;

    int actualLen = urb->iso_frame_desc[0].actual_length;
    if (urb->iso_frame_desc[0].status == 0 && actualLen >= 3) {
        const uint8_t* b = feedbackUrb.dataBuffer.data();
        double measuredRate = 0.0;
        if (actualLen >= 4) {
            // UAC2 High-Speed 16.16 fixed-point frames per 125us microframe (8000 uframes/sec)
            uint32_t raw = static_cast<uint32_t>(b[0]) |
                           (static_cast<uint32_t>(b[1]) << 8) |
                           (static_cast<uint32_t>(b[2]) << 16) |
                           (static_cast<uint32_t>(b[3]) << 24);
            double framesPerMicroframe = static_cast<double>(raw) / 65536.0;
            measuredRate = framesPerMicroframe * 8000.0;
        } else {
            // UAC1 Full-Speed 10.14 fixed-point frames per 1ms frame (1000 frames/sec)
            uint32_t raw = static_cast<uint32_t>(b[0]) |
                           (static_cast<uint32_t>(b[1]) << 8) |
                           (static_cast<uint32_t>(b[2]) << 16);
            double framesPerMs = static_cast<double>(raw) / 16384.0;
            measuredRate = framesPerMs * 1000.0;
        }

        // Sanity clamp within +/- 1.5% of nominal DAC clock to reject corrupt packets
        double nominal = static_cast<double>(currentSampleRate);
        if (measuredRate > nominal * 0.985 && measuredRate < nominal * 1.015) {
            if (!hasFeedbackLock) {
                feedbackSampleRate = measuredRate;
                hasFeedbackLock = true;
                LOGI("Async USB Feedback Clock Locked: %.2f Hz (nominal: %d Hz)", measuredRate, currentSampleRate);
            } else {
                // Smooth low-pass filter to track crystal drift without jitter
                feedbackSampleRate = feedbackSampleRate * 0.90 + measuredRate * 0.10;
            }
        }
    }

    if (isStreaming && isDeviceOpen && feedbackInEndpoint != 0) {
        submitFeedbackUrbLocked();
    }
}

void UsbAudioDriver::reapUrbs(bool block) {
    if (usbFd < 0 || urbRing.empty()) return;

    auto markReaped = [this](struct usbdevfs_urb* urb) {
        if (!urb) return;
        if (urb->usercontext == &feedbackUrb) {
            handleFeedbackCompletion(urb);
            return;
        }
        if (urb->usercontext != nullptr) {
            IsoUrbBuffer* slot = reinterpret_cast<IsoUrbBuffer*>(urb->usercontext);
            slot->inFlight = false;
        } else {
            for (auto& slot : urbRing) {
                if (reinterpret_cast<struct usbdevfs_urb*>(slot.urbStorage.data()) == urb) {
                    slot.inFlight = false;
                    break;
                }
            }
        }
    };

    auto drainNonBlocking = [&]() -> bool {
        struct usbdevfs_urb* reaped = nullptr;
        while (true) {
            int r = ioctl(usbFd, USBDEVFS_REAPURBNDELAY, &reaped);
            if (r == 0 && reaped != nullptr) {
                markReaped(reaped);
                reaped = nullptr;
                continue;
            }
            if (r < 0 && (errno == ENODEV || errno == ESHUTDOWN || errno == EBADF)) {
                LOGW("USB device disconnected during non-blocking reap (errno=%d: %s)", errno, strerror(errno));
                fatalError.store(true);
                isStreaming = false;
                return false;
            }
            break;
        }
        return true;
    };

    // 1. First drain all completed URBs non-blockingly
    if (!drainNonBlocking()) return;

    // 2. If block requested and the next needed slot is still in-flight:
    // Poll on the USBDEVFS fd cleanly instead of uncancellable blocking ioctl!
    if (block && isStreaming && isDeviceOpen && urbRing[urbRingIndex].inFlight) {
        for (int attempt = 0; attempt < 4 && urbRing[urbRingIndex].inFlight && isStreaming && isDeviceOpen; ++attempt) {
            struct pollfd pfd;
            pfd.fd = usbFd;
            pfd.events = POLLOUT;
            pfd.revents = 0;

            int pr = poll(&pfd, 1, 15);
            if (pr < 0) {
                if (errno == EINTR) continue;
                LOGW("poll() on USB FD failed (errno=%d: %s)", errno, strerror(errno));
                fatalError.store(true);
                isStreaming = false;
                return;
            }
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                LOGW("poll() reported USB disconnect/error (revents=0x%x)", pfd.revents);
                fatalError.store(true);
                isStreaming = false;
                return;
            }
            if (!drainNonBlocking()) return;
        }

        if (urbRing[urbRingIndex].inFlight && usbFd >= 0) {
            // Slot stalled past deadline: explicitly DISCARD before touching its DMA buffer!
            struct usbdevfs_urb* targetUrb =
                reinterpret_cast<struct usbdevfs_urb*>(urbRing[urbRingIndex].urbStorage.data());
            int dRes = ioctl(usbFd, USBDEVFS_DISCARDURB, targetUrb);
            if (dRes < 0 && (errno == ENODEV || errno == ESHUTDOWN || errno == EBADF)) {
                fatalError.store(true);
                isStreaming = false;
                return;
            }
            if (!drainNonBlocking()) return;
            if (urbRing[urbRingIndex].inFlight) {
                struct pollfd pfd{usbFd, POLLOUT, 0};
                poll(&pfd, 1, 5);
                if (!drainNonBlocking()) return;
            }
            if (urbRing[urbRingIndex].inFlight) {
                urbRing[urbRingIndex].inFlight = false;
            }
            consecutiveSubmitErrors++;
            LOGW("USB URB slot %zu timed out and was discarded (consecutiveErrors=%d)",
                 urbRingIndex, consecutiveSubmitErrors);
            if (consecutiveSubmitErrors >= 4) {
                LOGE("USB DAC stalled %d times consecutively, signaling fatal USB error", consecutiveSubmitErrors);
                fatalError.store(true);
                isStreaming = false;
            }
        }
    }
}

void UsbAudioDriver::stopStreaming() {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    if (!isStreaming) return;
    isStreaming = false;
    isoPhaseAccumulator = 0;
    residualFramesBuffer.clear();

    if (usbFd >= 0) {
        // Discard any remaining in-flight URBs on stream stop/pause
        if (feedbackUrb.inFlight && !feedbackUrb.urbStorage.empty()) {
            struct usbdevfs_urb* fbUrb = reinterpret_cast<struct usbdevfs_urb*>(feedbackUrb.urbStorage.data());
            ioctl(usbFd, USBDEVFS_DISCARDURB, fbUrb);
        }
        for (auto& slot : urbRing) {
            if (slot.inFlight) {
                struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(slot.urbStorage.data());
                ioctl(usbFd, USBDEVFS_DISCARDURB, urb);
            }
        }
        // Drain discarded completions
        struct usbdevfs_urb* reaped = nullptr;
        while (ioctl(usbFd, USBDEVFS_REAPURBNDELAY, &reaped) == 0 && reaped != nullptr) {
            reaped = nullptr;
        }
        feedbackUrb.inFlight = false;
        hasFeedbackLock = false;
        isoPhaseFrac = 0.0;
        for (auto& slot : urbRing) {
            slot.inFlight = false;
        }
        urbRing.clear();

        // Set back to Alt setting 0 to stop DAC isochronous reception (bandwidth release)
        struct usbdevfs_setinterface setIntf;
        setIntf.interface = audioStreamingInterface;
        setIntf.altsetting = 0;
        ioctl(usbFd, USBDEVFS_SETINTERFACE, &setIntf);
        LOGI("Stopped USB Audio streaming, reverted Interface %d to Alt 0", audioStreamingInterface);
    }
}

void UsbAudioDriver::unmuteHardware() {
    if (usbFd < 0 || !hasHardwareVolume || volumeFeatureUnitId <= 0) return;
    setHardwareVolumeLocked(hardwareVolume);
}

int UsbAudioDriver::writeInt32FramesBitPerfect(const int32_t* intPcm, int frameCount) {
    if (!isStreaming || usbFd < 0 || !intPcm || frameCount <= 0) return 0;

    int channels = currentChannels > 0 ? currentChannels : 2;
    int bytesPerSample = currentSubslotSize > 0 ? currentSubslotSize : ((currentBitDepth >= 24) ? 4 : 2);
    int totalSamples = frameCount * channels;
    size_t totalBytes = static_cast<size_t>(totalSamples * bytesPerSample);

    if (pcmConversionBuffer.size() < totalBytes) {
        pcmConversionBuffer.resize(totalBytes * 2);
    }

    uint8_t* outPtr = pcmConversionBuffer.data();

    // TRUE BIT-EXACT INTEGER PATH:
    // Input intPcm[] is left-justified signed 32-bit PCM:
    // - 16-bit source: s16 << 16  -> S16_LE extracts exact (s >> 16)
    // - 24-bit source: s24 << 8   -> S24_3LE extracts exact bytes [1..3]
    // - 32-bit source: s32        -> S32_LE passes raw int32_t untouched!
    // Software attenuation (when needed) is already performed smoothly in 64-bit DSP;
    // never apply duplicate attenuation here.
    if (hasHardwareVolume && hardwareVolume <= 0.0001f) {
        std::memset(outPtr, 0, totalBytes);
        return writeFrames(outPtr, frameCount);
    }

    if (bytesPerSample == 2) {
        int16_t* pcm16 = reinterpret_cast<int16_t*>(outPtr);
        for (int i = 0; i < totalSamples; ++i) {
            pcm16[i] = static_cast<int16_t>(intPcm[i] >> 16);
        }
    } else if (bytesPerSample == 3) {
        for (int i = 0; i < totalSamples; ++i) {
            uint32_t u = static_cast<uint32_t>(intPcm[i]);
            outPtr[i * 3 + 0] = static_cast<uint8_t>((u >> 8) & 0xFF);
            outPtr[i * 3 + 1] = static_cast<uint8_t>((u >> 16) & 0xFF);
            outPtr[i * 3 + 2] = static_cast<uint8_t>((u >> 24) & 0xFF);
        }
    } else {
        std::memcpy(outPtr, intPcm, totalBytes);
    }

    return writeFrames(outPtr, frameCount);
}

inline double UsbAudioDriver::nextTpdfDither() {
    uint32_t x = ditherRngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    uint32_t r1 = x;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    ditherRngState = x ? x : 0x2468ACE1u;
    constexpr double INV_2P32 = 1.0 / 4294967296.0;
    return (static_cast<double>(r1) + static_cast<double>(ditherRngState)) * INV_2P32 - 1.0;
}

int UsbAudioDriver::writeFloatFramesBitPerfect(const float* floatPcm, int frameCount) {
    if (!isStreaming || usbFd < 0 || !floatPcm || frameCount <= 0) return 0;

    int channels = currentChannels > 0 ? currentChannels : 2;
    int bytesPerSample = currentSubslotSize > 0 ? currentSubslotSize : ((currentBitDepth >= 24) ? 4 : 2);
    int totalSamples = frameCount * channels;
    size_t totalBytes = static_cast<size_t>(totalSamples * bytesPerSample);

    if (pcmConversionBuffer.size() < totalBytes) {
        pcmConversionBuffer.resize(totalBytes * 2);
    }

    uint8_t* outPtr = pcmConversionBuffer.data();

    // DAC output converts DSP float stream at unity gain (1.0); all software attenuation
    // already happened smoothly in DspProcessor (no double-attenuation!), and any analog DAC
    // volume runs in the USB Feature Unit.
    if (hasHardwareVolume && hardwareVolume <= 0.0001f) {
        std::memset(outPtr, 0, totalBytes);
        return writeFrames(outPtr, frameCount);
    }

    const bool useDither = ditherEnabled.load();

    if (bytesPerSample == 2) {
        int16_t* pcm16 = reinterpret_cast<int16_t*>(outPtr);
        for (int i = 0; i < totalSamples; ++i) {
            double s = static_cast<double>(floatPcm[i]);
            if (std::isnan(s) || std::isinf(s)) s = 0.0;
            s = std::clamp(s, -1.0, 1.0);
            double d = useDither ? nextTpdfDither() : 0.0;
            double scaled = s * 32767.0 + d;
            pcm16[i] = static_cast<int16_t>(std::clamp(std::llround(scaled), -32768LL, 32767LL));
        }
    } else if (bytesPerSample == 3) {
        for (int i = 0; i < totalSamples; ++i) {
            double s = static_cast<double>(floatPcm[i]);
            if (std::isnan(s) || std::isinf(s)) s = 0.0;
            s = std::clamp(s, -1.0, 1.0);
            double d = useDither ? nextTpdfDither() : 0.0;
            double scaled = s * 8388607.0 + d;
            int32_t val = static_cast<int32_t>(std::clamp(std::llround(scaled), -8388608LL, 8388607LL));
            outPtr[i * 3 + 0] = static_cast<uint8_t>(val & 0xFF);
            outPtr[i * 3 + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
            outPtr[i * 3 + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
        }
    } else {
        int32_t* pcm32 = reinterpret_cast<int32_t*>(outPtr);
        for (int i = 0; i < totalSamples; ++i) {
            double s = static_cast<double>(floatPcm[i]);
            if (std::isnan(s) || std::isinf(s)) s = 0.0;
            s = std::clamp(s, -1.0, 1.0);
            pcm32[i] = static_cast<int32_t>(std::clamp(std::llround(s * 2147483647.0), -2147483648LL, 2147483647LL));
        }
    }

    return writeFrames(outPtr, frameCount);
}

int UsbAudioDriver::writeFrames(const void* audioData, int frameCount) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    if (!isStreaming || usbFd < 0 || !audioData || frameCount <= 0 || urbRing.empty()) return 0;

    int bytesPerSample = currentSubslotSize > 0 ? currentSubslotSize : ((currentBitDepth >= 24) ? 4 : 2);
    int channels = currentChannels > 0 ? currentChannels : 2;
    int frameBytes = channels * bytesPerSample;
    size_t inBytes = static_cast<size_t>(frameCount * frameBytes);

    // 1. Append incoming audio data to residual staging buffer
    const uint8_t* inPtr = reinterpret_cast<const uint8_t*>(audioData);
    residualFramesBuffer.insert(residualFramesBuffer.end(), inPtr, inPtr + inBytes);
    constexpr size_t MAX_RESIDUAL_BYTES = 262144;
    if (residualFramesBuffer.size() > MAX_RESIDUAL_BYTES) {
        size_t excess = residualFramesBuffer.size() - MAX_RESIDUAL_BYTES;
        size_t alignedExcess = (excess / frameBytes) * frameBytes;
        if (alignedExcess > 0 && alignedExcess < residualFramesBuffer.size()) {
            residualFramesBuffer.erase(residualFramesBuffer.begin(), residualFramesBuffer.begin() + alignedExcess);
        }
    }

    if (endpointIsIsochronous) {
        // Compute packets per second according to cached USB speed and bInterval
        int packetsPerSec = 8000;
        int packetsPerUrb = 8;
        computeIsoTimingLocked(packetsPerSec, packetsPerUrb);

        while (isStreaming && usbFd >= 0) {
            // Drain finished URBs from the kernel non-blockingly
            reapUrbs(false);

            // Compute packet lengths for one full URB without mutating accumulator yet
            // When async feedback clock is locked, pace frames to DAC's actual crystal rate!
            double activeRate = hasFeedbackLock ? feedbackSampleRate : static_cast<double>(currentSampleRate);
            double framesPerPacketExact = activeRate / static_cast<double>(packetsPerSec);
            double tempFrac = isoPhaseFrac;
            constexpr int MAX_PKTS = 16;
            int numPkts = std::min(packetsPerUrb, MAX_PKTS);
            int packetLengths[MAX_PKTS];
            int bytesNeededThisUrb = 0;

            for (int p = 0; p < numPkts; ++p) {
                tempFrac += framesPerPacketExact;
                int framesForPkt = static_cast<int>(tempFrac);
                tempFrac -= static_cast<double>(framesForPkt);

                if (framesForPkt <= 0) framesForPkt = 1;
                int pktLen = framesForPkt * frameBytes;
                if (packetSize > 0 && pktLen > packetSize) {
                    pktLen = (packetSize / frameBytes) * frameBytes;
                }

                packetLengths[p] = pktLen;
                bytesNeededThisUrb += pktLen;
            }

            // If residual buffer does not yet have enough frames for a complete URB,
            // hold remaining frames in the buffer for the next writeFrames call!
            // This completely eliminates short/truncated packets and phase drift!
            if (residualFramesBuffer.size() < static_cast<size_t>(bytesNeededThisUrb)) {
                break;
            }

            // Commit accumulator now that this URB is confirmed
            isoPhaseFrac = tempFrac;

            // Flow control: wait cleanly on USB hardware completion if the target ring slot is still in-flight
            auto& slot = urbRing[urbRingIndex];
            if (slot.inFlight) {
                reapUrbs(true);
                if (slot.inFlight) {
                    break; // Error or disconnect
                }
            }

            if (slot.dataBuffer.size() < static_cast<size_t>(bytesNeededThisUrb)) {
                slot.dataBuffer.resize(bytesNeededThisUrb * 2);
            }
            std::memcpy(slot.dataBuffer.data(), residualFramesBuffer.data(), bytesNeededThisUrb);
            residualFramesBuffer.erase(residualFramesBuffer.begin(), residualFramesBuffer.begin() + bytesNeededThisUrb);

            size_t requiredUrbSize = sizeof(struct usbdevfs_urb) +
                                     numPkts * sizeof(struct usbdevfs_iso_packet_desc);
            if (slot.urbStorage.size() < requiredUrbSize) {
                slot.urbStorage.resize(requiredUrbSize, 0);
            }

            struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(slot.urbStorage.data());
            urb->type = USBDEVFS_URB_TYPE_ISO;
            urb->endpoint = isocOutEndpoint;
            urb->flags = USBDEVFS_URB_ISO_ASAP;
            urb->buffer = slot.dataBuffer.data();
            urb->actual_length = 0;
            urb->start_frame = 0;
            urb->number_of_packets = numPkts;
            urb->error_count = 0;
            urb->signr = 0;
            urb->usercontext = &slot;

            for (int i = 0; i < numPkts; ++i) {
                urb->iso_frame_desc[i].length = packetLengths[i];
                urb->iso_frame_desc[i].actual_length = 0;
                urb->iso_frame_desc[i].status = 0;
            }
            urb->buffer_length = bytesNeededThisUrb;

            int res = ioctl(usbFd, USBDEVFS_SUBMITURB, urb);
            if (res >= 0) {
                slot.inFlight = true;
                urbRingIndex = (urbRingIndex + 1) % urbRing.size();
                consecutiveSubmitErrors = 0;
            } else {
                int err = errno;
                LOGW("USBDEVFS_SUBMITURB failed: %d (%s), EP: 0x%02X, bytes: %d, pkts: %d",
                     res, strerror(err), isocOutEndpoint, bytesNeededThisUrb, numPkts);
                if (err == ENODEV || err == ESHUTDOWN || err == EBADF || err == ENOENT) {
                    fatalError.store(true);
                    isStreaming = false;
                } else {
                    consecutiveSubmitErrors++;
                    if (consecutiveSubmitErrors >= 6) {
                        fatalError.store(true);
                        isStreaming = false;
                    }
                }
                break;
            }
        }
        reapUrbs(false);
        return frameCount;
    } else {
        // Bulk transfer fallback
        if (residualFramesBuffer.size() >= inBytes) {
            auto& slot = urbRing[urbRingIndex];
            struct usbdevfs_bulktransfer bulk;
            bulk.ep = isocOutEndpoint;
            bulk.len = inBytes;
            bulk.timeout = 500;
            bulk.data = residualFramesBuffer.data();
            int res = ioctl(usbFd, USBDEVFS_BULK, &bulk);
            if (res >= 0) {
                residualFramesBuffer.erase(residualFramesBuffer.begin(), residualFramesBuffer.begin() + inBytes);
                consecutiveSubmitErrors = 0;
                return frameCount;
            } else if (errno == ENODEV || errno == ESHUTDOWN || errno == EBADF) {
                fatalError.store(true);
                isStreaming = false;
            }
        }
    }

    return frameCount;
}

bool UsbAudioDriver::setHardwareVolume(float volume) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    return setHardwareVolumeLocked(volume);
}

void UsbAudioDriver::setHardwareVolumeLimit(float maxLimit) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    hardwareVolumeLimit = std::clamp(maxLimit, 0.20f, 1.0f);
    if (hardwareVolume > hardwareVolumeLimit) {
        setHardwareVolumeLocked(hardwareVolumeLimit);
    }
}

void UsbAudioDriver::setHardwareVolumeCurve(int curve) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    hardwareVolumeCurve = std::clamp(curve, 0, 2);
    if (isDeviceOpen && hasHardwareVolume) {
        setHardwareVolumeLocked(hardwareVolume);
    }
}

void UsbAudioDriver::setHardwareBalance(float balance) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    hardwareBalance = std::clamp(balance, -1.0f, 1.0f);
    if (isDeviceOpen && hasHardwareVolume) {
        setHardwareVolumeLocked(hardwareVolume);
    }
}

void UsbAudioDriver::setForceVolumeSync(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(usbMutex);
    forceVolumeSync = enable;
    if (isDeviceOpen && hasHardwareVolume) {
        setHardwareVolumeLocked(hardwareVolume);
    }
}

bool UsbAudioDriver::setHardwareVolumeLocked(float volume) {
    hardwareVolume = std::clamp(volume, 0.0f, hardwareVolumeLimit);

    if (usbFd >= 0 && hasHardwareVolume && volumeFeatureUnitId > 0) {
        uint16_t fuIndex = (volumeFeatureUnitId << 8) | audioControlInterface;
        int16_t rawL = volumeMin;
        int16_t rawR = volumeMin;

        auto quantizeRaw = [this](int32_t rawCalc) -> int16_t {
            if (volumeRes > 0) {
                int32_t steps = (rawCalc - volumeMin + (volumeRes / 2)) / volumeRes;
                rawCalc = volumeMin + steps * volumeRes;
            }
            return static_cast<int16_t>(std::clamp<int32_t>(rawCalc, volumeMin, volumeMax));
        };

        if (hardwareVolume <= 0.0001f) {
            rawL = volumeMin;
            rawR = volumeMin;
        } else {
            double v = std::clamp(static_cast<double>(hardwareVolume), 0.0, 1.0);
            double mapped = v;
            if (hardwareVolumeCurve == 1) {
                mapped = std::pow(v, 0.65); // Perceptual smooth (more headroom at top)
            } else if (hardwareVolumeCurve == 2) {
                mapped = std::pow(v, 1.5);  // Fine low-end for sensitive IEMs
            }
            int32_t span = static_cast<int32_t>(volumeMax) - static_cast<int32_t>(volumeMin);
            int32_t baseRaw = static_cast<int32_t>(volumeMin) + static_cast<int32_t>(std::llround(mapped * span));

            // Hardware L/R balance trim up to -24 dB on opposite channel (0..24 * 256 UAC raw units)
            int32_t trimRaw = static_cast<int32_t>(std::llround(std::abs(static_cast<double>(hardwareBalance)) * 24.0 * 256.0));
            int32_t calcL = baseRaw - ((hardwareBalance > 0.001f) ? trimRaw : 0);
            int32_t calcR = baseRaw - ((hardwareBalance < -0.001f) ? trimRaw : 0);
            rawL = quantizeRaw(calcL);
            rawR = quantizeRaw(calcR);
        }

        auto sendVol = [this, fuIndex](uint8_t ch, int16_t val) -> int {
            struct usbdevfs_ctrltransfer ctrl;
            std::memset(&ctrl, 0, sizeof(ctrl));
            ctrl.bRequestType = 0x21; // Host to Device | Class | Interface
            ctrl.bRequest = UAC_REQUEST_CODE_CUR;
            ctrl.wIndex = fuIndex;
            ctrl.wLength = 2;
            ctrl.timeout = 50;
            ctrl.wValue = (0x02 << 8) | ch; // Volume Selector
            ctrl.data = &val;
            return ioctl(usbFd, USBDEVFS_CONTROL, &ctrl);
        };

        int r1 = sendVol(0x01, rawL);
        int r2 = sendVol(0x02, rawR);
        stereoHardwareVolumeSupported = (r1 >= 0 && r2 >= 0);

        int r0 = -1;
        if ((r1 < 0 && r2 < 0) || (forceVolumeSync && rawL == rawR)) {
            int16_t avgRaw = static_cast<int16_t>((static_cast<int32_t>(rawL) + static_cast<int32_t>(rawR)) / 2);
            r0 = sendVol(0x00, avgRaw);
        }
        hardwareVolumeWorking = (r1 >= 0 || r2 >= 0 || r0 >= 0);
        LOGI("Hardware Volume set to %.2f (curve=%d, bal=%.2f, rawL=%d, rawR=%d) -> Ch1:%d Ch2:%d Ch0:%d (working:%d)",
             hardwareVolume, hardwareVolumeCurve, hardwareBalance, rawL, rawR, r1, r2, r0, hardwareVolumeWorking);
    } else {
        hardwareVolumeWorking = false;
        stereoHardwareVolumeSupported = false;
    }

    return true;
}

bool UsbAudioDriver::isSampleRateSupported(int rate) const {
    for (int r : supportedSampleRates) {
        if (r == rate) return true;
    }
    return false;
}

