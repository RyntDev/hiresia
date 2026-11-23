#include "audio_engine.h"
#include <android/log.h>
#include <cstdint>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <chrono>
#include <unistd.h>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine& AudioEngine::getInstance() {
    static AudioEngine instance;
    return instance;
}

AudioEngine::AudioEngine() {
    ringBuffer.resize(BUFFER_SIZE, 0);
}

AudioEngine::~AudioEngine() {
    shutdown();
}

bool AudioEngine::initAndRun() {
    if (running) return true;

    LOGI("Starting Audio Engine worker thread...");
    running = true;
    workerThread = std::thread(&AudioEngine::audioThreadLoop, this);
    return true;
}

void AudioEngine::shutdown() {
    if (!running) return;

    LOGI("Shutting down Audio Engine...");
    running = false;
    playing = false;
    ringBufferCond.notify_all();

    if (workerThread.joinable()) {
        workerThread.join();
    }

    closeAAudioStream();
    usbDriver.closeDevice();
}

bool AudioEngine::preparePlayback(const ESDTrackInfo& track) {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("Preparing playback: %s (SampleRate: %d, Channels: %d, BitDepth: %d, DSD: %d)",
         track.path.c_str(), track.format.sampleRate, track.format.channels,
         track.format.bitDepth, track.format.isDsd);

    // Explicitly stop previous stream and flush ring buffer before reconfiguring format
    stop();

    fileSampleRate = track.format.sampleRate > 0 ? track.format.sampleRate : 44100;
    channels = track.format.channels > 0 ? track.format.channels : 2;
    bitDepth = track.format.bitDepth > 0 ? track.format.bitDepth : 16;
    isDsdTrack = track.format.isDsd;
    dsdRate = track.format.dsdRate;
    totalDurationMs = track.durationMs;
    currentPositionMs = 0;

    // Initialize DSP & Resampler with track parameters
    dsp.init(fileSampleRate, channels);
    dsp.setReplayGainValues(track.replayGainTrack, track.replayGainAlbum, 0.0f);
    resampler.reset();

    // If USB DAC is connected and output is USB_DIRECT, configure USB driver
    if (usbDriver.isConnected() && outputType == OutputType::USB_DIRECT) {
        closeAAudioStream();
        if (isDsdTrack && dsdMode == DsdMode::DOP) {
            int effectiveDsdRate = dsdRate > 0 ? dsdRate : 64;
            int dopRate = (effectiveDsdRate * 44100) / 16;
            usbDriver.setAudioFormat(dopRate, 24, channels);
        } else {
            usbDriver.setAudioFormat(fileSampleRate, bitDepth, channels);
        }
        deviceSampleRate = usbDriver.getSampleRate();
    } else {
        closeAAudioStream();
        usbDriver.stopStreaming();
        if (isDsdTrack && dsdMode == DsdMode::DOP) {
            int effectiveDsdRate = dsdRate > 0 ? dsdRate : 64;
            deviceSampleRate = (effectiveDsdRate * 44100) / 16;
        } else {
            deviceSampleRate = fileSampleRate;
        }
        setupAAudioStream(deviceSampleRate, channels);
    }

    return true;
}

void AudioEngine::play() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("AudioEngine::play (outputType: %d)", static_cast<int>(outputType));
    if ((!isDsdTrack || dsdMode == DsdMode::DSD_TO_PCM) && (!bitPerfectEnabled || dsp.hasActiveDsp())) {
        dsp.triggerSoftFadeIn();
    }
    if (outputType == OutputType::USB_DIRECT && usbDriver.isConnected()) {
        closeAAudioStream();
        if (!usbDriver.startStreaming() || usbDriver.hasFatalError()) {
            LOGE("AudioEngine::play USB DAC startStreaming failed or fatal USB error");
            usbFatalErrorPending.store(true);
            playing = false;
            return;
        }
        playing = true;
    } else {
        playing = true;
        if (!isAAudioStreamHealthyLocked()) {
            setupAAudioStream(fileSampleRate > 0 ? fileSampleRate : 48000, channels > 0 ? channels : 2);
        }
        if (aaudioStream) {
            aaudio_stream_state_t st = AAudioStream_getState(aaudioStream);
            if (st != AAUDIO_STREAM_STATE_STARTED && st != AAUDIO_STREAM_STATE_STARTING) {
                aaudio_result_t r = AAudioStream_requestStart(aaudioStream);
                LOGI("AAudioStream_requestStart result: %d (previous state: %d)", r, st);
                if (r != AAUDIO_OK) {
                    LOGW("AAudioStream_requestStart failed (%d); recreating AAudio stream...", r);
                    if (setupAAudioStream(fileSampleRate > 0 ? fileSampleRate : 48000, channels > 0 ? channels : 2) && aaudioStream) {
                        aaudio_result_t r2 = AAudioStream_requestStart(aaudioStream);
                        LOGI("Recreated AAudioStream_requestStart result: %d", r2);
                    }
                }
            }
        }
    }
}

void AudioEngine::pause() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("AudioEngine::pause");
    playing = false;
    if (outputType == OutputType::USB_DIRECT && usbDriver.isConnected()) {
        usbDriver.stopStreaming();
    } else if (aaudioStream) {
        aaudio_stream_state_t st = AAudioStream_getState(aaudioStream);
        if (st == AAUDIO_STREAM_STATE_STARTED || st == AAUDIO_STREAM_STATE_STARTING) {
            aaudio_result_t r = AAudioStream_requestPause(aaudioStream);
            LOGI("AAudioStream_requestPause result: %d", r);
        }
    }
}

void AudioEngine::stop() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("AudioEngine::stop");
    playing = false;
    currentPositionMs = 0;
    if (outputType == OutputType::USB_DIRECT && usbDriver.isConnected()) {
        usbDriver.stopStreaming();
    } else if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
    }
    std::lock_guard<std::mutex> rLock(ringBufferMutex);
    readHead = writeHead = 0;
}

void AudioEngine::seek(int64_t positionMs) {
    LOGI("AudioEngine::seek to %lld ms", static_cast<long long>(positionMs));
    currentPositionMs = std::clamp<int64_t>(positionMs, 0, totalDurationMs.load());
    std::lock_guard<std::mutex> lock(ringBufferMutex);
    readHead = writeHead = 0;
    resampler.reset();
    if ((!isDsdTrack || dsdMode == DsdMode::DSD_TO_PCM) && (!bitPerfectEnabled || dsp.hasActiveDsp())) {
        dsp.triggerSoftFadeIn();
    }
}

void AudioEngine::flushRingBuffer() {
    std::lock_guard<std::mutex> lock(ringBufferMutex);
    readHead = writeHead = 0;
    std::fill(ringBuffer.begin(), ringBuffer.end(), 0);
    resampler.reset();
}

void AudioEngine::setOutputType(OutputType type) {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("AudioEngine::setOutputType: %d (previous: %d)", static_cast<int>(type), static_cast<int>(outputType));
    outputType = type;
    flushRingBuffer();

    if (outputType == OutputType::USB_DIRECT) {
        dsp.setSoftwareVolume(1.0f);
        closeAAudioStream();
        if (usbDriver.isConnected()) {
            usbDriver.setAudioFormat(fileSampleRate, bitDepth, channels);
            deviceSampleRate = usbDriver.getSampleRate();
            if (playing) {
                usbDriver.startStreaming();
            }
        }
    } else {
        usbDriver.stopStreaming();
        setupAAudioStream(deviceSampleRate, channels);
        if (playing && aaudioStream) {
            AAudioStream_requestStart(aaudioStream);
        }
    }
}

void AudioEngine::setBitPerfect(bool enable) {
    bitPerfectEnabled = enable;
    LOGI("Bit-Perfect mode: %s", bitPerfectEnabled ? "ENABLED" : "DISABLED");
}

bool AudioEngine::isBitPerfectPossibleForUSBAudio() const {
    if (!usbDriver.isConnected()) return false;
    return usbDriver.isSampleRateSupported(fileSampleRate);
}

bool AudioEngine::initUSBDevice(int fd, int vid, int pid, const std::string& name,
                                const uint8_t* rawDesc, int rawDescLen) {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    playing = false; // Halt rendering loop immediately so it does not write into unconfigured DAC!
    usbFatalErrorPending.store(false);

    closeAAudioStream();
    flushRingBuffer();

    bool ok = usbDriver.initDevice(fd, vid, pid, name, rawDesc, rawDescLen);
    if (ok) {
        LOGI("USB DAC Attached! Switching output to Direct USB (Bypassing Android OS)");
        outputType = OutputType::USB_DIRECT;
        dsp.setSoftwareVolume(1.0f);

        // Pre-configure track format and lock hardware PLL clock before any playback can begin
        if (fileSampleRate > 0) {
            if (isDsdTrack && dsdMode == DsdMode::DOP) {
                int effectiveDsdRate = dsdRate > 0 ? dsdRate : 64;
                int dopRate = (effectiveDsdRate * 44100) / 16;
                usbDriver.setAudioFormat(dopRate, 24, channels);
            } else {
                usbDriver.setAudioFormat(fileSampleRate, bitDepth, channels);
            }
            deviceSampleRate = usbDriver.getSampleRate();
        }

        // Allow 30ms for DAC hardware PLL to lock clock frequency
        usleep(30000);
    } else {
        outputType = OutputType::AAUDIO;
        setupAAudioStream(fileSampleRate > 0 ? fileSampleRate : 48000, channels > 0 ? channels : 2);
    }
    return ok;
}

void AudioEngine::usbDeviceWasDetached() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    LOGI("USB DAC Detached! Stopping streaming, releasing USB interfaces & flushing buffers.");
    playing = false;
    usbFatalErrorPending.store(false);
    usbDriver.closeDevice(true);
    closeAAudioStream();
    flushRingBuffer();
    outputType = OutputType::AAUDIO;
}

bool AudioEngine::refreshAAudioRoute() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    if (outputType == OutputType::USB_DIRECT && usbDriver.isConnected()) {
        return true;
    }
    bool wasPlaying = playing.load();
    LOGI("Refreshing AAudio output route (wasPlaying=%d, sr=%d, ch=%d)", wasPlaying ? 1 : 0, fileSampleRate, channels);
    closeAAudioStream();
    int targetSr = (fileSampleRate > 0) ? fileSampleRate : 48000;
    int targetCh = (channels > 0) ? channels : 2;
    bool ok = setupAAudioStream(targetSr, targetCh);
    if (ok && wasPlaying && aaudioStream) {
        AAudioStream_requestStart(aaudioStream);
    }
    return ok;
}

void AudioEngine::setHardwareVolume(float vol) {
    usbDriver.setHardwareVolume(vol);
}

bool AudioEngine::isAAudioStreamHealthyLocked() const {
    if (!aaudioStream) return false;
    aaudio_stream_state_t st = AAudioStream_getState(aaudioStream);
    if (st == AAUDIO_STREAM_STATE_DISCONNECTED ||
        st == AAUDIO_STREAM_STATE_CLOSING ||
        st == AAUDIO_STREAM_STATE_CLOSED ||
        st == AAUDIO_STREAM_STATE_UNINITIALIZED) {
        return false;
    }
    return true;
}

bool AudioEngine::setupAAudioStream(int sr, int ch) {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
        AAudioStream_close(aaudioStream);
        aaudioStream = nullptr;
    }

    int targetSr = (sr > 0) ? sr : 48000;
    int targetCh = (ch > 0) ? ch : 2;

    auto tryOpen = [&](int reqRate, aaudio_sharing_mode_t sharing, aaudio_performance_mode_t perf) -> aaudio_result_t {
        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t bRes = AAudio_createStreamBuilder(&builder);
        if (bRes != AAUDIO_OK || !builder) {
            return bRes != AAUDIO_OK ? bRes : AAUDIO_ERROR_INTERNAL;
        }
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
#if __ANDROID_API__ >= 28
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif
        AAudioStreamBuilder_setChannelCount(builder, targetCh);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        if (reqRate > 0) {
            AAudioStreamBuilder_setSampleRate(builder, reqRate);
        }
        AAudioStreamBuilder_setSharingMode(builder, sharing);
        AAudioStreamBuilder_setPerformanceMode(builder, perf);

        aaudio_result_t openRes = AAudioStreamBuilder_openStream(builder, &aaudioStream);
        AAudioStreamBuilder_delete(builder);
        if (openRes != AAUDIO_OK) {
            aaudioStream = nullptr;
        }
        return openRes;
    };

    aaudio_result_t res = AAUDIO_ERROR_INTERNAL;
    if (outputType == OutputType::ANDROID_HIRES) {
        res = tryOpen(targetSr, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        if (res != AAUDIO_OK) {
            LOGW("Exclusive Hi-Res mode unavailable (%d), trying shared low-latency...", res);
        }
    }
    if (res != AAUDIO_OK) {
        res = tryOpen(targetSr, AAUDIO_SHARING_MODE_SHARED, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    }
    if (res != AAUDIO_OK) {
        LOGW("Low-latency AAudio open failed (%d), trying standard shared mode...", res);
        res = tryOpen(targetSr, AAUDIO_SHARING_MODE_SHARED, AAUDIO_PERFORMANCE_MODE_NONE);
    }
    if (res != AAUDIO_OK) {
        LOGW("Targeted %d Hz AAudio open failed (%d), opening device-native rate with Polyphase Sinc Resampler...", targetSr, res);
        res = tryOpen(AAUDIO_UNSPECIFIED, AAUDIO_SHARING_MODE_SHARED, AAUDIO_PERFORMANCE_MODE_NONE);
    }

    if (res != AAUDIO_OK || !aaudioStream) {
        LOGE("Failed to open AAudio stream across all fallbacks: %d", res);
        aaudioStream = nullptr;
        return false;
    }

    int actualRate = AAudioStream_getSampleRate(aaudioStream);
    if (actualRate > 0) {
        deviceSampleRate = actualRate;
    }
    LOGI("AAudio stream opened successfully (SampleRate: %d, Channels: %d, BufferCapacity: %d)",
         deviceSampleRate, targetCh, AAudioStream_getBufferCapacityInFrames(aaudioStream));
    return true;
}

void AudioEngine::closeAAudioStream() {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    if (aaudioStream) {
        AAudioStream_requestStop(aaudioStream);
        AAudioStream_close(aaudioStream);
        aaudioStream = nullptr;
    }
}

bool AudioEngine::updateAudioFormat(int sampleRate, int newBitDepth, int newChannels) {
    std::lock_guard<std::recursive_mutex> lock(aaudioMutex);
    if (sampleRate <= 0) return false;
    LOGI("AudioEngine::updateAudioFormat: %d Hz (was %d Hz), %d-bit (was %d), %d ch (was %d)",
         sampleRate, fileSampleRate, newBitDepth, bitDepth, newChannels, channels);

    if (fileSampleRate == sampleRate && (newBitDepth <= 0 || bitDepth == newBitDepth) &&
        (newChannels <= 0 || channels == newChannels)) {
        return true;
    }

    fileSampleRate = sampleRate;
    if (newBitDepth > 0) bitDepth = newBitDepth;
    if (newChannels > 0) channels = newChannels;

    dsp.init(fileSampleRate, channels);
    resampler.reset();

    if (outputType == OutputType::USB_DIRECT && usbDriver.isConnected()) {
        bool wasPlaying = playing.load();
        usbDriver.stopStreaming();
        usbDriver.setAudioFormat(fileSampleRate, bitDepth, channels);
        deviceSampleRate = usbDriver.getSampleRate();
        std::lock_guard<std::mutex> rLock(ringBufferMutex);
        readHead = writeHead = 0;
        if (wasPlaying) {
            usbDriver.startStreaming();
        }
    } else if (outputType == OutputType::AAUDIO || outputType == OutputType::ANDROID_HIRES) {
        setupAAudioStream(fileSampleRate, channels);
        if (playing && aaudioStream) {
            AAudioStream_requestStart(aaudioStream);
        }
    }
    return true;
}

void AudioEngine::queuePcmInt32(const int32_t* pcm, int numFrames) {
    if (!pcm || numFrames <= 0) return;

    std::unique_lock<std::mutex> lock(ringBufferMutex);
    constexpr size_t SAMPLES_PER_FRAME = 2; // Strict stereo pipeline (L + R)

    for (int f = 0; f < numFrames; ++f) {
        size_t nextWriteL = writeHead;
        size_t nextWriteR = (writeHead + 1) % BUFFER_SIZE;
        size_t afterWrite = (writeHead + SAMPLES_PER_FRAME) % BUFFER_SIZE;

        if (afterWrite == readHead) {
            readHead = (readHead + SAMPLES_PER_FRAME) % BUFFER_SIZE;
        }

        ringBuffer[nextWriteL] = pcm[f * 2];
        ringBuffer[nextWriteR] = pcm[f * 2 + 1];
        writeHead = afterWrite;
    }
    ringBufferCond.notify_one();
}

void AudioEngine::queuePcmData(const float* pcm, int numFrames) {
    if (!pcm || numFrames <= 0) return;

    std::unique_lock<std::mutex> lock(ringBufferMutex);
    constexpr size_t SAMPLES_PER_FRAME = 2; // Strict stereo pipeline (L + R)

    for (int f = 0; f < numFrames; ++f) {
        size_t nextWriteL = writeHead;
        size_t nextWriteR = (writeHead + 1) % BUFFER_SIZE;
        size_t afterWrite = (writeHead + SAMPLES_PER_FRAME) % BUFFER_SIZE;

        if (afterWrite == readHead) {
            readHead = (readHead + SAMPLES_PER_FRAME) % BUFFER_SIZE;
        }

        double l = std::clamp(static_cast<double>(pcm[f * 2]), -1.0, 1.0);
        double r = std::clamp(static_cast<double>(pcm[f * 2 + 1]), -1.0, 1.0);
        ringBuffer[nextWriteL] = static_cast<int32_t>(std::llround(l * 2147483647.0));
        ringBuffer[nextWriteR] = static_cast<int32_t>(std::llround(r * 2147483647.0));
        writeHead = afterWrite;
    }
    ringBufferCond.notify_one();
}

int AudioEngine::readAndQueueNativeFrames(int maxFrames) {
    if (maxFrames <= 0 || !nativeDecoder.isOpen()) return 0;
    size_t samplesNeeded = static_cast<size_t>(maxFrames) * 2;
    if (nativeReadBuffer.size() < samplesNeeded) {
        nativeReadBuffer.resize(samplesNeeded);
    }
    int framesRead = nativeDecoder.readFramesInt32(nativeReadBuffer.data(), maxFrames, dsdMode);
    if (framesRead > 0) {
        queuePcmInt32(nativeReadBuffer.data(), framesRead);
    }
    return framesRead;
}

int AudioEngine::getQueuedFrames() const {
    size_t wh = writeHead;
    size_t rh = readHead;
    size_t samples = (wh >= rh) ? (wh - rh) : (BUFFER_SIZE - rh + wh);
    return static_cast<int>(samples / 2);
}

bool AudioEngine::renderAudioBlockInt32(int32_t* outBuffer, int numFrames) {
    size_t samplesNeeded = static_cast<size_t>(numFrames * 2); // Strictly stereo (2 channels)

    std::unique_lock<std::mutex> lock(ringBufferMutex);
    size_t available = (writeHead >= readHead) ? (writeHead - readHead) : (BUFFER_SIZE - readHead + writeHead);
    available &= ~1ULL; // Mask to strictly even number of samples (complete frames)

    if (available == 0) {
        std::memset(outBuffer, 0, samplesNeeded * sizeof(int32_t));
        return false;
    }

    size_t samplesToCopy = std::min(available, samplesNeeded);
    for (size_t i = 0; i < samplesToCopy; ++i) {
        outBuffer[i] = ringBuffer[readHead];
        readHead = (readHead + 1) % BUFFER_SIZE;
    }
    if (samplesToCopy < samplesNeeded) {
        std::memset(outBuffer + samplesToCopy, 0, (samplesNeeded - samplesToCopy) * sizeof(int32_t));
    }
    return true;
}

void AudioEngine::audioThreadLoop() {
    LOGI("Audio worker thread entered running state.");
    constexpr int BLOCK_FRAMES = 512;
    std::vector<int32_t> intBlock(BLOCK_FRAMES * 2, 0);
    std::vector<float> audioBlock(BLOCK_FRAMES * 2, 0.0f);
    std::vector<float> resampleBlock;
    constexpr double INV_INT32_MAX = 1.0 / 2147483648.0;

    while (running) {
        if (!playing) {
            vuLevels.peakLeft = 0.0f;
            vuLevels.peakRight = 0.0f;
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        bool hasAudio = renderAudioBlockInt32(intBlock.data(), BLOCK_FRAMES);
        if (!hasAudio) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }

        // DSP processing (Equalizer, AutoGain, Crossfeed, Balance, Volume)
        // Raw DSD (DoP or Native) cannot undergo PCM DSP.
        bool isRawDsd = isDsdTrack && dsdMode != DsdMode::DSD_TO_PCM;
        bool shouldProcessDsp = !isRawDsd && (!bitPerfectEnabled || dsp.hasActiveDsp());

        bool isUsbDirect = (outputType == OutputType::USB_DIRECT && usbDriver.isConnected());
        bool writeSucceeded = true;

        // TRUE BIT-PERFECT ZERO-FLOAT FAST PATH:
        // Direct int32_t -> DAC integer format (S16_LE / S24_3LE / S32_LE / DoP / Native DSD)
        // Zero floating-point conversion touches the samples!
        if (isUsbDirect && (isRawDsd || (!shouldProcessDsp && fileSampleRate == deviceSampleRate))) {
            if (!usbDriver.isStreamingActive()) {
                if (!usbDriver.startStreaming()) {
                    usbFatalErrorPending.store(true);
                    playing = false;
                    writeSucceeded = false;
                }
            }

            if (writeSucceeded) {
                // Read-only VU meter calculation (does not modify audio bits)
                float maxL = 0.0f, maxR = 0.0f;
                if (!isRawDsd) {
                    for (int i = 0; i < BLOCK_FRAMES; ++i) {
                        float absL = static_cast<float>(std::abs(static_cast<double>(intBlock[i * 2])) * INV_INT32_MAX);
                        float absR = static_cast<float>(std::abs(static_cast<double>(intBlock[i * 2 + 1])) * INV_INT32_MAX);
                        if (absL > maxL) maxL = absL;
                        if (absR > maxR) maxR = absR;
                    }
                } else {
                    maxL = 0.45f;
                    maxR = 0.45f;
                }
                vuLevels.peakLeft = std::max(maxL, vuLevels.peakLeft * 0.88f);
                vuLevels.peakRight = std::max(maxR, vuLevels.peakRight * 0.88f);

                usbDriver.writeInt32FramesBitPerfect(intBlock.data(), BLOCK_FRAMES);
                if (usbDriver.hasFatalError() || !usbDriver.isStreamingActive()) {
                    LOGW("USB Direct zero-float path encountered fatal error, flagging recovery");
                    usbFatalErrorPending.store(true);
                    playing = false;
                    writeSucceeded = false;
                }
            }
        } else {
            // Convert int32_t -> normalized float for 64-bit DSP / Polyphase Sinc Resampling / AAudio
            for (int i = 0; i < BLOCK_FRAMES * 2; ++i) {
                audioBlock[i] = static_cast<float>(static_cast<double>(intBlock[i]) * INV_INT32_MAX);
            }

            if (shouldProcessDsp) {
                dsp.processInterleaved(audioBlock.data(), BLOCK_FRAMES);
            }

            float maxL = 0.0f, maxR = 0.0f;
            for (int i = 0; i < BLOCK_FRAMES; ++i) {
                float absL = std::abs(audioBlock[i * 2]);
                float absR = std::abs(audioBlock[i * 2 + 1]);
                if (absL > maxL) maxL = absL;
                if (absR > maxR) maxR = absR;
            }
            vuLevels.peakLeft = std::max(maxL, vuLevels.peakLeft * 0.88f);
            vuLevels.peakRight = std::max(maxR, vuLevels.peakRight * 0.88f);

            if (isUsbDirect) {
                if (!usbDriver.isStreamingActive()) {
                    if (!usbDriver.startStreaming()) {
                        usbFatalErrorPending.store(true);
                        playing = false;
                        writeSucceeded = false;
                    }
                }
                if (writeSucceeded) {
                    if (fileSampleRate == deviceSampleRate) {
                        usbDriver.writeFloatFramesBitPerfect(audioBlock.data(), BLOCK_FRAMES);
                    } else {
                        resampler.configure(fileSampleRate, deviceSampleRate);
                        int outFrames = resampler.processInterleaved(audioBlock.data(), BLOCK_FRAMES, resampleBlock);
                        if (outFrames > 0) {
                            usbDriver.writeFloatFramesBitPerfect(resampleBlock.data(), outFrames);
                        }
                    }
                    if (usbDriver.hasFatalError() || !usbDriver.isStreamingActive()) {
                        LOGW("USB Direct DSP/float path encountered fatal error, flagging recovery");
                        usbFatalErrorPending.store(true);
                        playing = false;
                        writeSucceeded = false;
                    }
                }
            } else {
                bool needSleepRecovery = false;
                {
                    std::unique_lock<std::recursive_mutex> lock(aaudioMutex);
                    if (playing && !isAAudioStreamHealthyLocked()) {
                        LOGW("AAudio stream unhealthy/disconnected before write, reopening...");
                        if (setupAAudioStream(fileSampleRate > 0 ? fileSampleRate : 48000, channels > 0 ? channels : 2) && aaudioStream) {
                            AAudioStream_requestStart(aaudioStream);
                        }
                    }
                    if (aaudioStream && playing) {
                        int64_t timeoutNs = 100 * 1000000;
                        aaudio_result_t writeRes = 0;
                        if (deviceSampleRate == fileSampleRate) {
                            writeRes = AAudioStream_write(aaudioStream, audioBlock.data(), BLOCK_FRAMES, timeoutNs);
                        } else {
                            resampler.configure(fileSampleRate, deviceSampleRate);
                            int outFrames = resampler.processInterleaved(audioBlock.data(), BLOCK_FRAMES, resampleBlock);
                            if (outFrames > 0) {
                                writeRes = AAudioStream_write(aaudioStream, resampleBlock.data(), outFrames, timeoutNs);
                            }
                        }
                        if (writeRes < 0) {
                            LOGW("AAudioStream_write failed (res=%d: %s), closing stale stream for auto-rebuild",
                                 writeRes, AAudio_convertResultToText(writeRes));
                            writeSucceeded = false;
                            closeAAudioStream();
                            needSleepRecovery = true;
                        }
                    } else {
                        writeSucceeded = false;
                    }
                }
                if (needSleepRecovery) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(60));
                    std::unique_lock<std::recursive_mutex> lock(aaudioMutex);
                    if (playing && !(outputType == OutputType::USB_DIRECT && usbDriver.isConnected())) {
                        if (setupAAudioStream(fileSampleRate > 0 ? fileSampleRate : 48000, channels > 0 ? channels : 2) && aaudioStream) {
                            aaudio_result_t r = AAudioStream_requestStart(aaudioStream);
                            LOGI("AAudio stream rebuilt after route change (start res=%d)", r);
                        }
                    }
                }
            }
        }

        // Advance playback position clock only if audio frames were successfully written
        if (writeSucceeded) {
            int effectiveRate = (fileSampleRate > 0) ? fileSampleRate : 44100;
            int64_t addedNs = (static_cast<int64_t>(BLOCK_FRAMES) * 1000000000LL) / effectiveRate;
            int64_t curMs = currentPositionMs.load();
            int64_t newMs = curMs + (addedNs / 1000000LL);
            if (totalDurationMs > 0 && newMs > totalDurationMs.load()) {
                newMs = totalDurationMs.load();
            }
            currentPositionMs = newMs;
        }
    }

    LOGI("Audio worker thread exited.");
}
