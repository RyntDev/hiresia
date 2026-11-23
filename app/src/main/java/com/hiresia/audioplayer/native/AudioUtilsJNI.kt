package com.hiresia.audioplayer.native

object AudioUtilsJNI {
    init {
        try {
            System.loadLibrary("usbaudioengine")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // Engine Lifecycle
    @JvmStatic external fun AudioServer_initAndRun(): Boolean
    @JvmStatic external fun AudioServer_appIsExiting()

    // Playback Operations
    @JvmStatic external fun AudioServer_preparePlayback(
        path: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        durationMs: Long,
        isDsd: Boolean,
        dsdRate: Int
    ): Boolean

    @JvmStatic external fun AudioServer_play()
    @JvmStatic external fun AudioServer_pause()
    @JvmStatic external fun AudioServer_stop()
    @JvmStatic external fun AudioServer_seek(positionMs: Long)
    @JvmStatic external fun AudioServer_isPlaying(): Boolean
    @JvmStatic external fun AudioServer_getCurrentPosition(): Long
    @JvmStatic external fun AudioServer_getTotalLengthMillisecondsOfCurrentTrack(): Long

    // USB DAC Direct Driver (Bit-Perfect)
    @JvmStatic external fun AudioServer_InitUSBDevice(fd: Int, vid: Int, pid: Int, deviceName: String): Boolean
    @JvmStatic external fun AudioServer_InitUSBDeviceByName(fd: Int, vid: Int, pid: Int, deviceName: String, rawDesc: ByteArray): Boolean
    @JvmStatic external fun AudioServer_isUSBConnected(): Boolean
    @JvmStatic external fun AudioServer_isUSBUAC2(): Boolean
    @JvmStatic external fun AudioServer_getUSBDeviceName(): String
    @JvmStatic external fun AudioServer_getUSBSupportedSampleRates(): IntArray
    @JvmStatic external fun AudioServer_USBAudioDeviceWasDetached()
    @JvmStatic external fun AudioServer_consumeFatalUsbError(): Boolean
    @JvmStatic external fun AudioServer_refreshOutputRoute(): Boolean
    @JvmStatic external fun AudioServer_setBitPerfectWhenPossible(enable: Boolean)
    @JvmStatic external fun AudioServer_isBitPerfectPossibleForUSBAudio(): Boolean
    @JvmStatic external fun AudioServer_setOutputType(type: Int)
    @JvmStatic external fun AudioServer_getOutputType(): Int
    @JvmStatic external fun AudioServer_setBlockUsbHid(enable: Boolean)
    @JvmStatic external fun AudioServer_isBlockUsbHid(): Boolean

    // Volume & Metering
    @JvmStatic external fun AudioServer_setHardwareVolume(volume: Float)
    @JvmStatic external fun AudioServer_getHardwareVolume(): Float
    @JvmStatic external fun AudioServer_isHardwareVolumeSupported(): Boolean
    @JvmStatic external fun AudioServer_getHardwareVolumeMin(): Int
    @JvmStatic external fun AudioServer_getHardwareVolumeMax(): Int
    @JvmStatic external fun AudioServer_getHardwareVolumeRes(): Int
    @JvmStatic external fun AudioServer_setHardwareVolumeLimit(maxLimit: Float)
    @JvmStatic external fun AudioServer_setHardwareVolumeCurve(curve: Int)
    @JvmStatic external fun AudioServer_setHardwareBalance(balance: Float)
    @JvmStatic external fun AudioServer_setForceVolumeSync(enable: Boolean)
    @JvmStatic external fun AudioServer_isStereoHardwareVolumeSupported(): Boolean
    @JvmStatic external fun AudioServer_setDitherEnabled(enable: Boolean)
    @JvmStatic external fun AudioServer_setReplayGainMode(mode: Int)
    @JvmStatic external fun AudioServer_setReplayGainValues(
        trackGainDb: Float,
        albumGainDb: Float,
        preampDb: Float,
        peak: Float,
        preventClipping: Boolean
    )
    @JvmStatic external fun AudioServer_setSoftwareVolume(volume: Float)
    @JvmStatic external fun AudioServer_getSoftwareVolume(): Float
    @JvmStatic external fun AudioServer_getCurrentLeftVolumePostAll(): Float
    @JvmStatic external fun AudioServer_getCurrentRightVolumePostAll(): Float

    // Audio Formats & Sampling Rates
    @JvmStatic external fun AudioServer_getFileSampleRate(): Int
    @JvmStatic external fun AudioServer_getDeviceSampleRate(): Int
    @JvmStatic external fun AudioServer_getDSDRate(): Int
    @JvmStatic external fun AudioServer_isDSDFile(): Boolean
    @JvmStatic external fun AudioServer_setDsdMode(mode: Int)
    @JvmStatic external fun AudioServer_getDsdMode(): Int

    // 10-Band Graphic Equalizer
    @JvmStatic external fun AudioServer_setUseGraphicEQ(enable: Boolean)
    @JvmStatic external fun AudioServer_getUseGraphicEQ(): Boolean
    @JvmStatic external fun AudioServer_setBandGain(band: Int, gainDB: Float)
    @JvmStatic external fun AudioServer_getBandGain(band: Int): Float
    @JvmStatic external fun AudioServer_setEQAutoGain(enable: Boolean)
    @JvmStatic external fun AudioServer_getEQAutoGain(): Boolean
    @JvmStatic external fun AudioServer_setBandParam(
        band: Int,
        freqHz: Float,
        gainDB: Float,
        q: Float,
        filterType: Int,
        enabled: Boolean
    )
    @JvmStatic external fun AudioServer_getBandFreq(band: Int): Float
    @JvmStatic external fun AudioServer_getBandQ(band: Int): Float
    @JvmStatic external fun AudioServer_getBandType(band: Int): Int
    @JvmStatic external fun AudioServer_setEQPreamp(preampDb: Float)
    @JvmStatic external fun AudioServer_getEQPreamp(): Float

    // Crossfeed
    @JvmStatic external fun AudioServer_setCrossFeedState(enable: Boolean)
    @JvmStatic external fun AudioServer_getCrossFeedState(): Boolean
    @JvmStatic external fun AudioServer_setCrossFeedLevel(level: Int)
    @JvmStatic external fun AudioServer_getCrossFeedLevel(): Int

    // Balance & Mono
    @JvmStatic external fun AudioServer_setUseBalance(enable: Boolean)
    @JvmStatic external fun AudioServer_getUseBalance(): Boolean
    @JvmStatic external fun AudioServer_setBalance(balance: Float)
    @JvmStatic external fun AudioServer_getBalance(): Float
    @JvmStatic external fun AudioServer_setUseMono(enable: Boolean)

    // PCM Stream Delivery (Bit-Exact Int32 + Float compatibility)
    @JvmStatic external fun AudioServer_queueAudioFloat(audioData: FloatArray, numFrames: Int)
    @JvmStatic external fun AudioServer_queueAudioInt32(audioData: IntArray, numFrames: Int)
    @JvmStatic external fun AudioServer_getQueuedFrames(): Int
    @JvmStatic external fun AudioServer_updateAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Boolean

    // Native C++ Lossless Decoder (FLAC / WAV / DSF / DFF — bypasses Android MediaCodec)
    @JvmStatic external fun AudioServer_nativeDecoderOpenPath(path: String): Boolean
    @JvmStatic external fun AudioServer_nativeDecoderOpenFd(fd: Int, hintName: String): Boolean
    @JvmStatic external fun AudioServer_nativeDecoderGetSampleRate(): Int
    @JvmStatic external fun AudioServer_nativeDecoderGetBitDepth(): Int
    @JvmStatic external fun AudioServer_nativeDecoderGetChannels(): Int
    @JvmStatic external fun AudioServer_nativeDecoderGetDurationMs(): Long
    @JvmStatic external fun AudioServer_nativeDecoderIsDsd(): Boolean
    @JvmStatic external fun AudioServer_nativeDecoderGetDsdRate(): Int
    @JvmStatic external fun AudioServer_nativeDecoderReadAndQueue(maxFrames: Int): Int
    @JvmStatic external fun AudioServer_nativeDecoderSeek(positionMs: Long): Boolean
    @JvmStatic external fun AudioServer_nativeDecoderClose()
}

