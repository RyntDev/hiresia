#pragma once

#include <cstdint>
#include <cstdio>
#include <vector>

class ALACDecoder;

/**
 * Clean-room MP4/M4A demuxer + Apple official ALAC (Apache 2.0) decoder.
 * Decodes 16-bit, 20-bit, 24-bit, and 32-bit Apple Lossless streams directly
 * into bit-exact left-justified int32_t stereo frames (up to 384 kHz).
 */
class AlacMp4Decoder {
public:
    AlacMp4Decoder();
    ~AlacMp4Decoder();

    // Takes a non-owning FILE* positioned anywhere; returns true only if the stream
    // is a valid MP4/M4A container with an ALAC audio track.
    bool open(FILE* fp);
    void close();

    bool isOpen() const { return decoder != nullptr; }
    int getSampleRate() const { return sampleRate; }
    int getBitDepth() const { return bitDepth; }
    int getChannels() const { return channels; }
    int64_t getTotalFrames() const { return totalFrames; }
    int64_t getDurationMs() const { return durationMs; }

    int readFramesInt32(int32_t* outStereoInt32, int maxFrames);
    bool seekMs(int64_t positionMs);

private:
    struct SttsEntry {
        uint32_t count;
        uint32_t delta;
    };
    struct StscEntry {
        uint32_t firstChunk;
        uint32_t samplesPerChunk;
        uint32_t sampleDescIdx;
    };

    bool parseContainer(int64_t startPos, int64_t endPos);
    bool parseStsd(int64_t startPos, int64_t endPos);
    bool parseStts(int64_t startPos, int64_t endPos);
    bool parseStsc(int64_t startPos, int64_t endPos);
    bool parseStsz(int64_t startPos, int64_t endPos);
    bool parseStco(int64_t startPos, int64_t endPos, bool is64Bit);
    bool buildPacketTable();
    bool decodeNextPacket();

    FILE* filePtr = nullptr;
    ALACDecoder* decoder = nullptr;

    int sampleRate = 44100;
    int bitDepth = 16;
    int channels = 2;
    uint32_t frameLength = 4096;
    int64_t totalFrames = 0;
    int64_t durationMs = 0;

    std::vector<uint8_t> magicCookie;
    std::vector<SttsEntry> sttsEntries;
    std::vector<StscEntry> stscEntries;
    std::vector<uint32_t> sampleSizes;
    uint32_t constantSampleSize = 0;
    uint32_t sampleCount = 0;
    std::vector<uint64_t> chunkOffsets;

    // Flattened packet table for O(1) read & O(log N) seek
    std::vector<uint64_t> packetOffsets;
    std::vector<uint32_t> packetSizes;
    std::vector<int64_t> packetStartFrame;
    size_t currentPacketIdx = 0;

    // Decoded PCM staging buffer (stereo int32_t)
    std::vector<uint8_t> packetReadBuf;
    std::vector<uint8_t> decodedRawBuf;
    std::vector<int32_t> decodedStereoBuf;
    size_t decodedFramesTotal = 0;
    size_t decodedFramesOffset = 0;
};

