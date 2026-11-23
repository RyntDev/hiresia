#include <jni.h>
#include "audio_engine.h"
#include <android/log.h>
#include <string>

#define HIRESIA_JNI(return_type, method_name) \
    JNIEXPORT return_type JNICALL Java_com_hiresia_audioplayer_native_AudioUtilsJNI_##method_name

extern "C" {

HIRESIA_JNI(jboolean, AudioServer_1initAndRun)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().initAndRun() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1appIsExiting)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().shutdown();
}

HIRESIA_JNI(jboolean, AudioServer_1preparePlayback)(JNIEnv* env, jclass clazz,
                                                    jstring path, jint sampleRate,
                                                    jint channels, jint bitDepth,
                                                    jlong durationMs, jboolean isDsd,
                                                    jint dsdRate) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    ESDTrackInfo track;
    track.path = cPath ? cPath : "";
    track.format.sampleRate = sampleRate;
    track.format.channels = channels;
    track.format.bitDepth = bitDepth;
    track.format.isDsd = isDsd;
    track.format.dsdRate = dsdRate;
    track.durationMs = durationMs;

    if (cPath) env->ReleaseStringUTFChars(path, cPath);

    return AudioEngine::getInstance().preparePlayback(track) ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1play)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().play();
}

HIRESIA_JNI(void, AudioServer_1pause)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().pause();
}

HIRESIA_JNI(void, AudioServer_1stop)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().stop();
}

HIRESIA_JNI(void, AudioServer_1seek)(JNIEnv* env, jclass clazz, jlong positionMs) {
    AudioEngine::getInstance().seek(positionMs);
}

HIRESIA_JNI(jboolean, AudioServer_1isPlaying)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isPlaying() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jlong, AudioServer_1getCurrentPosition)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getCurrentPositionMs();
}

HIRESIA_JNI(jlong, AudioServer_1getTotalLengthMillisecondsOfCurrentTrack)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getTotalDurationMs();
}

// USB Host Device Connection
HIRESIA_JNI(jboolean, AudioServer_1InitUSBDevice)(JNIEnv* env, jclass clazz,
                                                  jint fd, jint vid, jint pid, jstring deviceName) {
    const char* cName = env->GetStringUTFChars(deviceName, nullptr);
    std::string name = cName ? cName : "USB DAC";
    if (cName) env->ReleaseStringUTFChars(deviceName, cName);

    return AudioEngine::getInstance().initUSBDevice(fd, vid, pid, name) ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jboolean, AudioServer_1InitUSBDeviceByName)(JNIEnv* env, jclass clazz,
                                                        jint fd, jint vid, jint pid,
                                                        jstring deviceName, jbyteArray rawDesc) {
    const char* cName = env->GetStringUTFChars(deviceName, nullptr);
    std::string name = cName ? cName : "USB DAC";
    if (cName) env->ReleaseStringUTFChars(deviceName, cName);

    jbyte* descBytes = nullptr;
    jsize descLen = 0;
    if (rawDesc != nullptr) {
        descLen = env->GetArrayLength(rawDesc);
        descBytes = env->GetByteArrayElements(rawDesc, nullptr);
    }

    bool success = AudioEngine::getInstance().initUSBDevice(
        fd, vid, pid, name,
        reinterpret_cast<const uint8_t*>(descBytes), descLen
    );

    if (rawDesc != nullptr && descBytes != nullptr) {
        env->ReleaseByteArrayElements(rawDesc, descBytes, JNI_ABORT);
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jboolean, AudioServer_1isUSBConnected)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isUSBConnected() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jboolean, AudioServer_1isUSBUAC2)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isUSBUAC2() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jstring, AudioServer_1getUSBDeviceName)(JNIEnv* env, jclass clazz) {
    std::string name = AudioEngine::getInstance().getUSBDeviceName();
    return env->NewStringUTF(name.c_str());
}

HIRESIA_JNI(jintArray, AudioServer_1getUSBSupportedSampleRates)(JNIEnv* env, jclass clazz) {
    std::vector<int> rates = AudioEngine::getInstance().getUSBSupportedSampleRates();
    jintArray result = env->NewIntArray(rates.size());
    if (result && !rates.empty()) {
        env->SetIntArrayRegion(result, 0, rates.size(), reinterpret_cast<const jint*>(rates.data()));
    }
    return result;
}

HIRESIA_JNI(void, AudioServer_1USBAudioDeviceWasDetached)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().usbDeviceWasDetached();
}

HIRESIA_JNI(jboolean, AudioServer_1consumeFatalUsbError)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().consumeFatalUsbError() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jboolean, AudioServer_1refreshOutputRoute)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().refreshAAudioRoute() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setBitPerfectWhenPossible)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().setBitPerfect(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1isBitPerfectPossibleForUSBAudio)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isBitPerfectPossibleForUSBAudio() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setOutputType)(JNIEnv* env, jclass clazz, jint type) {
    AudioEngine::getInstance().setOutputType(static_cast<OutputType>(type));
}

HIRESIA_JNI(jint, AudioServer_1getOutputType)(JNIEnv* env, jclass clazz) {
    return static_cast<jint>(AudioEngine::getInstance().getOutputType());
}

HIRESIA_JNI(void, AudioServer_1setBlockUsbHid)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().setBlockUsbHid(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1isBlockUsbHid)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isBlockUsbHid() ? JNI_TRUE : JNI_FALSE;
}

// Volume & Metering
HIRESIA_JNI(void, AudioServer_1setHardwareVolume)(JNIEnv* env, jclass clazz, jfloat volume) {
    AudioEngine::getInstance().setHardwareVolume(volume);
}

HIRESIA_JNI(jfloat, AudioServer_1getHardwareVolume)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getHardwareVolume();
}

HIRESIA_JNI(jboolean, AudioServer_1isHardwareVolumeSupported)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isHardwareVolumeSupported();
}

HIRESIA_JNI(jint, AudioServer_1getHardwareVolumeMin)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getHardwareVolumeMin();
}

HIRESIA_JNI(jint, AudioServer_1getHardwareVolumeMax)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getHardwareVolumeMax();
}

HIRESIA_JNI(jint, AudioServer_1getHardwareVolumeRes)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getHardwareVolumeRes();
}

HIRESIA_JNI(void, AudioServer_1setHardwareVolumeLimit)(JNIEnv* env, jclass clazz, jfloat maxLimit) {
    AudioEngine::getInstance().setHardwareVolumeLimit(maxLimit);
}

HIRESIA_JNI(void, AudioServer_1setHardwareVolumeCurve)(JNIEnv* env, jclass clazz, jint curve) {
    AudioEngine::getInstance().setHardwareVolumeCurve(curve);
}

HIRESIA_JNI(void, AudioServer_1setHardwareBalance)(JNIEnv* env, jclass clazz, jfloat balance) {
    AudioEngine::getInstance().setHardwareBalance(balance);
}

HIRESIA_JNI(void, AudioServer_1setForceVolumeSync)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().setForceVolumeSync(enable == JNI_TRUE);
}

HIRESIA_JNI(jboolean, AudioServer_1isStereoHardwareVolumeSupported)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isStereoHardwareVolumeSupported() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setDitherEnabled)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().setDitherEnabled(enable == JNI_TRUE);
}

HIRESIA_JNI(void, AudioServer_1setReplayGainMode)(JNIEnv* env, jclass clazz, jint mode) {
    AudioEngine::getInstance().getDsp().setReplayGainMode(static_cast<ReplayGainMode>(mode));
}

HIRESIA_JNI(void, AudioServer_1setReplayGainValues)(JNIEnv* env, jclass clazz,
                                                   jfloat trackGainDb, jfloat albumGainDb,
                                                   jfloat preampDb, jfloat peak,
                                                   jboolean preventClipping) {
    AudioEngine::getInstance().getDsp().setReplayGainValues(
        trackGainDb, albumGainDb, preampDb, peak, preventClipping == JNI_TRUE
    );
}

HIRESIA_JNI(void, AudioServer_1setSoftwareVolume)(JNIEnv* env, jclass clazz, jfloat volume) {
    AudioEngine::getInstance().setSoftwareVolume(volume);
}

HIRESIA_JNI(jfloat, AudioServer_1getSoftwareVolume)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getSoftwareVolume();
}

HIRESIA_JNI(jfloat, AudioServer_1getCurrentLeftVolumePostAll)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getCurrentLeftVolumePostAll();
}

HIRESIA_JNI(jfloat, AudioServer_1getCurrentRightVolumePostAll)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getCurrentRightVolumePostAll();
}

// Track and Device Sampling Rates
HIRESIA_JNI(jint, AudioServer_1getFileSampleRate)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getFileSampleRate();
}

HIRESIA_JNI(jint, AudioServer_1getDeviceSampleRate)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDeviceSampleRate();
}

HIRESIA_JNI(jint, AudioServer_1getDSDRate)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDSDRate();
}

HIRESIA_JNI(jboolean, AudioServer_1isDSDFile)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().isDSD() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setDsdMode)(JNIEnv* env, jclass clazz, jint mode) {
    AudioEngine::getInstance().setDsdMode(mode);
}

HIRESIA_JNI(jint, AudioServer_1getDsdMode)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsdMode();
}

// DSP - Equalizer
HIRESIA_JNI(void, AudioServer_1setUseGraphicEQ)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().getDsp().setUseGraphicEQ(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1getUseGraphicEQ)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getUseGraphicEQ() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setBandGain)(JNIEnv* env, jclass clazz, jint band, jfloat gainDB) {
    AudioEngine::getInstance().getDsp().setBandGain(band, gainDB);
}

HIRESIA_JNI(jfloat, AudioServer_1getBandGain)(JNIEnv* env, jclass clazz, jint band) {
    return AudioEngine::getInstance().getDsp().getBandGain(band);
}

HIRESIA_JNI(void, AudioServer_1setEQAutoGain)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().getDsp().setEQAutoGain(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1getEQAutoGain)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getEQAutoGain() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setBandParam)(JNIEnv* env, jclass clazz, jint band,
                                             jfloat freqHz, jfloat gainDB, jfloat q,
                                             jint filterType, jboolean enabled) {
    AudioEngine::getInstance().getDsp().setBandParam(
        band, freqHz, gainDB, q, filterType, enabled == JNI_TRUE
    );
}

HIRESIA_JNI(jfloat, AudioServer_1getBandFreq)(JNIEnv* env, jclass clazz, jint band) {
    return AudioEngine::getInstance().getDsp().getBandFreq(band);
}

HIRESIA_JNI(jfloat, AudioServer_1getBandQ)(JNIEnv* env, jclass clazz, jint band) {
    return AudioEngine::getInstance().getDsp().getBandQ(band);
}

HIRESIA_JNI(jint, AudioServer_1getBandType)(JNIEnv* env, jclass clazz, jint band) {
    return AudioEngine::getInstance().getDsp().getBandType(band);
}

HIRESIA_JNI(void, AudioServer_1setEQPreamp)(JNIEnv* env, jclass clazz, jfloat preampDb) {
    AudioEngine::getInstance().getDsp().setEQPreamp(preampDb);
}

HIRESIA_JNI(jfloat, AudioServer_1getEQPreamp)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getEQPreamp();
}

// DSP - Crossfeed
HIRESIA_JNI(void, AudioServer_1setCrossFeedState)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().getDsp().setCrossFeedState(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1getCrossFeedState)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getCrossFeedState() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setCrossFeedLevel)(JNIEnv* env, jclass clazz, jint level) {
    AudioEngine::getInstance().getDsp().setCrossFeedLevel(level);
}

HIRESIA_JNI(jint, AudioServer_1getCrossFeedLevel)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getCrossFeedLevel();
}

// DSP - Balance & Mono
HIRESIA_JNI(void, AudioServer_1setUseBalance)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().getDsp().setUseBalance(enable);
}

HIRESIA_JNI(jboolean, AudioServer_1getUseBalance)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getUseBalance() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1setBalance)(JNIEnv* env, jclass clazz, jfloat balance) {
    AudioEngine::getInstance().getDsp().setBalance(balance);
}

HIRESIA_JNI(jfloat, AudioServer_1getBalance)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getDsp().getBalance();
}

HIRESIA_JNI(void, AudioServer_1setUseMono)(JNIEnv* env, jclass clazz, jboolean enable) {
    AudioEngine::getInstance().getDsp().setUseMono(enable);
}

HIRESIA_JNI(void, AudioServer_1queueAudioFloat)(JNIEnv* env, jclass clazz,
                                                jfloatArray audioData, jint numFrames) {
    jfloat* pcm = env->GetFloatArrayElements(audioData, nullptr);
    if (pcm) {
        AudioEngine::getInstance().queuePcmData(pcm, numFrames);
        env->ReleaseFloatArrayElements(audioData, pcm, JNI_ABORT);
    }
}

HIRESIA_JNI(void, AudioServer_1queueAudioInt32)(JNIEnv* env, jclass clazz,
                                                jintArray audioData, jint numFrames) {
    jint* pcm = env->GetIntArrayElements(audioData, nullptr);
    if (pcm) {
        AudioEngine::getInstance().queuePcmInt32(reinterpret_cast<const int32_t*>(pcm), numFrames);
        env->ReleaseIntArrayElements(audioData, pcm, JNI_ABORT);
    }
}

HIRESIA_JNI(jint, AudioServer_1getQueuedFrames)(JNIEnv* env, jclass clazz) {
    return static_cast<jint>(AudioEngine::getInstance().getQueuedFrames());
}

HIRESIA_JNI(jboolean, AudioServer_1updateAudioFormat)(JNIEnv* env, jclass clazz,
                                                      jint sampleRate, jint bitDepth, jint channels) {
    return AudioEngine::getInstance().updateAudioFormat(sampleRate, bitDepth, channels) ? JNI_TRUE : JNI_FALSE;
}

// Native C++ Lossless Decoder (FLAC / WAV / DSF / DFF)
HIRESIA_JNI(jboolean, AudioServer_1nativeDecoderOpenPath)(JNIEnv* env, jclass clazz, jstring path) {
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    std::string p = cPath ? cPath : "";
    if (cPath) env->ReleaseStringUTFChars(path, cPath);
    return AudioEngine::getInstance().getNativeDecoder().openPath(p) ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jboolean, AudioServer_1nativeDecoderOpenFd)(JNIEnv* env, jclass clazz, jint fd, jstring hintName) {
    const char* cHint = hintName ? env->GetStringUTFChars(hintName, nullptr) : nullptr;
    std::string hint = cHint ? cHint : "";
    if (cHint) env->ReleaseStringUTFChars(hintName, cHint);
    return AudioEngine::getInstance().getNativeDecoder().openFd(fd, hint) ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jint, AudioServer_1nativeDecoderGetSampleRate)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().getSampleRate();
}

HIRESIA_JNI(jint, AudioServer_1nativeDecoderGetBitDepth)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().getBitDepth();
}

HIRESIA_JNI(jint, AudioServer_1nativeDecoderGetChannels)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().getChannels();
}

HIRESIA_JNI(jlong, AudioServer_1nativeDecoderGetDurationMs)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().getDurationMs();
}

HIRESIA_JNI(jboolean, AudioServer_1nativeDecoderIsDsd)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().isDsd() ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(jint, AudioServer_1nativeDecoderGetDsdRate)(JNIEnv* env, jclass clazz) {
    return AudioEngine::getInstance().getNativeDecoder().getDsdRate();
}

HIRESIA_JNI(jint, AudioServer_1nativeDecoderReadAndQueue)(JNIEnv* env, jclass clazz, jint maxFrames) {
    return AudioEngine::getInstance().readAndQueueNativeFrames(maxFrames);
}

HIRESIA_JNI(jboolean, AudioServer_1nativeDecoderSeek)(JNIEnv* env, jclass clazz, jlong positionMs) {
    return AudioEngine::getInstance().getNativeDecoder().seekMs(positionMs) ? JNI_TRUE : JNI_FALSE;
}

HIRESIA_JNI(void, AudioServer_1nativeDecoderClose)(JNIEnv* env, jclass clazz) {
    AudioEngine::getInstance().getNativeDecoder().close();
}

} // extern "C"

