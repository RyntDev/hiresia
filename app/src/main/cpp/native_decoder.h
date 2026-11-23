#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include <mutex>
#include "audio_types.h"
#include "dsd_decoder.h"
#include "alac_mp4_decoder.h"
#include "tta_decoder.h"

/**
 * Native C++ Hi-Res Audio Decoder for:
 * - Lossless PCM: FLAC, WAV/RF64/W64, AIFF/AIFF-C, ALAC (M4A), WavPack (.wv incl. DSD), TTA (.tta)
 * - Native DSD: DSF, DFF (DSDIFF) with DoP v1.1, Native DSD, and 64-tap FIR DSD-to-PCM
 * - High-Precision Lossy: OGG Vorbis (stb_vorbis), MP3 (dr_mp3 float32 -> int32)
 *
 * Bypasses Android MediaCodec completely:
 * - Decodes 16-bit, 24-bit, and 32-bit PCM directly into bit-exact left-justified int32_t frames
 * - Supports high-resolution sample rates up to 768 kHz
 * - Supports both filesystem paths and Android content:// file descriptors (dup(fd))
 * - Zero overhead on the primary FLAC / WAV / DSD Bit-Perfect fast path
 */
class NativeLosslessDecoder {
public:
    enum class FormatType {
        NONE = 0,
        FLAC = 1,
        WAV = 2,
        DSF = 3,
        DFF = 4,
        AIFF = 5,
        ALAC = 6,
        WAVPACK = 7,
        TTA = 8,
        VORBIS = 9,
        MP3 = 10
    };

    NativeLosslessDecoder();
    ~NativeLosslessDecoder();

    bool openPath(const std::string& path);
    bool openFd(int fd, const std::string& hintName);
    void close();

    bool isOpen() const { return formatType != FormatType::NONE; }
    int getSampleRate() const { return outputSampleRate; }
    int getBitDepth() const { return bitDepth; }
    int getChannels() const { return channels; }
    int64_t getDurationMs() const { return durationMs; }
    bool isDsd() const { return formatType == FormatType::DSF || formatType == FormatType::DFF; }
    int getDsdRate() const { return dsdRate; }

    // Decodes up to maxFrames stereo frames as bit-exact left-justified int32_t
    // Returns number of stereo frames decoded (0 = EOF, <0 = error)
    int readFramesInt32(int32_t* outStereoInt32, int maxFrames, DsdMode dsdMode);

    bool seekMs(int64_t positionMs);

private:
    bool openFileStream(FILE* fp, const std::string& hintName);
    bool initDsf(FILE* fp);
    bool initDff(FILE* fp);
    bool initAiff(FILE* fp);
    bool initWavPack(FILE* fp);
    bool initVorbis(FILE* fp);
    bool initMp3(FILE* fp);

    int readDsdFramesInt32(int32_t* outStereoInt32, int maxFrames, DsdMode dsdMode);
    int readAiffFramesInt32(int32_t* outStereoInt32, int maxFrames);
    int readWavPackFramesInt32(int32_t* outStereoInt32, int maxFrames);
    int readVorbisFramesInt32(int32_t* outStereoInt32, int maxFrames);
    int readMp3FramesInt32(int32_t* outStereoInt32, int maxFrames);

    std::recursive_mutex decoderMutex;
    FormatType formatType = FormatType::NONE;
    FILE* filePtr = nullptr;

    void* flacHandle = nullptr;
    void* wavHandle = nullptr;
    void* wavpackHandle = nullptr;
    void* vorbisHandle = nullptr;
    void* mp3Handle = nullptr;

    AlacMp4Decoder alacDecoder;
    TtaDecoder ttaDecoder;

    int sourceSampleRate = 44100;
    int outputSampleRate = 44100;
    int bitDepth = 16;
    int channels = 2;
    int64_t totalPcmFrames = 0;
    int64_t durationMs = 0;

    // AIFF / AIFF-C metadata
    int64_t aiffDataOffset = 0;
    int64_t aiffFramesRead = 0;
    int aiffBytesPerSample = 2;
    bool aiffLittleEndian = false;
    bool aiffIsFloat = false;
    std::vector<uint8_t> aiffReadBuf;

    // WavPack flags
    bool wavpackIsFloat = false;
    int wavpackBitsPerSample = 16;

    // DSD container metadata
    int dsdRate = 0; // 64, 128, 256, 512
    int64_t dsdDataOffset = 0;
    int64_t dsdDataSize = 0;
    int64_t dsdBytesRead = 0;
    uint32_t dsfBlockSizePerChannel = 4096;
    bool dsfLsbFirst = true;
    uint8_t dopMarkerState = 0x05;
    DsdDecoder dsdDecoder;

    std::vector<int32_t> scratchInt32;
    std::vector<float> scratchFloat;
    std::vector<uint8_t> dsdBlockBuf;
    std::vector<uint8_t> dsdLeftBuf;
    std::vector<uint8_t> dsdRightBuf;
    std::vector<float> dsdPcmFloatBuf;
    size_t dsdBufferedBytes = 0;
    size_t dsdBufferedOffset = 0;
};
