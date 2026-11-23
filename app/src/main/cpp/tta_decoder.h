#pragma once

#include <cstdint>
#include <cstdio>
#include <vector>

/**
 * Clean-room C++ TTA1 (True Audio Lossless) decoder.
 * Supports 8-bit, 16-bit, and 24-bit lossless PCM up to 384 kHz,
 * with bit-exact left-justified int32_t output and seektable-based seeking.
 */
class TtaDecoder {
public:
    TtaDecoder();
    ~TtaDecoder();

    bool open(FILE* fp);
    void close();

    bool isOpen() const { return opened; }
    int getSampleRate() const { return sampleRate; }
    int getBitDepth() const { return bitDepth; }
    int getChannels() const { return channels; }
    int64_t getTotalFrames() const { return totalSamples; }
    int64_t getDurationMs() const { return durationMs; }

    int readFramesInt32(int32_t* outStereoInt32, int maxFrames);
    bool seekMs(int64_t positionMs);

private:
    struct FltState {
        int32_t shift = 0;
        int32_t round = 0;
        int32_t error = 0;
        int32_t qm[8] = {0};
        int32_t dx[8] = {0};
        int32_t dl[8] = {0};
    };

    struct AdaptState {
        uint32_t k0 = 10;
        uint32_t k1 = 10;
        uint32_t sum0 = 1 << 14;
        uint32_t sum1 = 1 << 14;
    };

    struct ChannelCodec {
        FltState flt;
        AdaptState rice;
        int32_t last = 0;
    };

    bool decodeNextFrame();
    static void filterInit(FltState& f, int32_t shift);
    static void hybridFilter(FltState& f, int32_t& in);
    static void riceInit(AdaptState& r, uint32_t k0, uint32_t k1);

    FILE* filePtr = nullptr;
    bool opened = false;

    int sampleRate = 44100;
    int bitDepth = 16;
    int channels = 2;
    int64_t totalSamples = 0;
    int64_t durationMs = 0;

    uint32_t frameLength = 0;
    uint32_t lastFrameLength = 0;
    uint32_t totalFramesCount = 0;
    int64_t dataStartOffset = 0;

    std::vector<uint32_t> seekTableSizes;
    std::vector<int64_t> frameOffsets;
    uint32_t currentFrameIdx = 0;

    std::vector<uint8_t> frameBitBuf;
    std::vector<int32_t> decodedStereoBuf;
    size_t decodedFramesTotal = 0;
    size_t decodedFramesOffset = 0;
};

