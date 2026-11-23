#include "alac_mp4_decoder.h"
#include "third_party/alac/ALACDecoder.h"
#include "third_party/alac/ALACBitUtilities.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "AlacMp4Decoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

uint16_t readBE16(const uint8_t* p) {
    return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}

uint32_t readBE32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}

uint64_t readBE64(const uint8_t* p) {
    return (static_cast<uint64_t>(readBE32(p)) << 32) |
           static_cast<uint64_t>(readBE32(p + 4));
}

bool readBytesAt(FILE* fp, int64_t pos, void* dst, size_t len) {
    if (fseeko(fp, static_cast<off_t>(pos), SEEK_SET) != 0) return false;
    return std::fread(dst, 1, len, fp) == len;
}

} // namespace

AlacMp4Decoder::AlacMp4Decoder() = default;

AlacMp4Decoder::~AlacMp4Decoder() {
    close();
}

void AlacMp4Decoder::close() {
    if (decoder) {
        delete decoder;
        decoder = nullptr;
    }
    filePtr = nullptr;
    sampleRate = 44100;
    bitDepth = 16;
    channels = 2;
    frameLength = 4096;
    totalFrames = 0;
    durationMs = 0;
    magicCookie.clear();
    sttsEntries.clear();
    stscEntries.clear();
    sampleSizes.clear();
    constantSampleSize = 0;
    sampleCount = 0;
    chunkOffsets.clear();
    packetOffsets.clear();
    packetSizes.clear();
    packetStartFrame.clear();
    currentPacketIdx = 0;
    packetReadBuf.clear();
    decodedRawBuf.clear();
    decodedStereoBuf.clear();
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;
}

bool AlacMp4Decoder::open(FILE* fp) {
    close();
    if (!fp) return false;
    filePtr = fp;

    if (fseeko(fp, 0, SEEK_END) != 0) return false;
    int64_t fileSize = static_cast<int64_t>(ftello(fp));
    if (fileSize < 32) return false;

    if (!parseContainer(0, fileSize)) {
        close();
        return false;
    }

    if (magicCookie.size() < 24 || !buildPacketTable()) {
        close();
        return false;
    }

    decoder = new ALACDecoder();
    int32_t initErr = decoder->Init(magicCookie.data(), static_cast<uint32_t>(magicCookie.size()));
    if (initErr != 0) {
        LOGW("ALACDecoder::Init failed with error %d", initErr);
        close();
        return false;
    }

    sampleRate = static_cast<int>(decoder->mConfig.sampleRate);
    bitDepth = static_cast<int>(decoder->mConfig.bitDepth);
    channels = static_cast<int>(decoder->mConfig.numChannels);
    frameLength = decoder->mConfig.frameLength;

    if (sampleRate <= 0 || channels <= 0 || bitDepth <= 0 || frameLength == 0) {
        close();
        return false;
    }

    durationMs = (totalFrames > 0) ? ((totalFrames * 1000LL) / sampleRate) : 0;

    // Pre-allocate decode staging buffers (max bytes per sample = 4)
    decodedRawBuf.resize(static_cast<size_t>(frameLength) * static_cast<size_t>(channels) * 4);
    decodedStereoBuf.resize(static_cast<size_t>(frameLength) * 2);

    LOGI("Opened native ALAC: %d Hz, %d-bit, %d ch, %zu packets, %lld frames (%lld ms)",
         sampleRate, bitDepth, channels, packetOffsets.size(),
         static_cast<long long>(totalFrames), static_cast<long long>(durationMs));
    return true;
}

bool AlacMp4Decoder::parseContainer(int64_t startPos, int64_t endPos) {
    int64_t pos = startPos;
    while (pos + 8 <= endPos) {
        uint8_t hdr[8];
        if (!readBytesAt(filePtr, pos, hdr, 8)) break;

        uint64_t boxSize = readBE32(hdr);
        int64_t headerLen = 8;
        if (boxSize == 1) {
            if (pos + 16 > endPos) break;
            uint8_t extSize[8];
            if (!readBytesAt(filePtr, pos + 8, extSize, 8)) break;
            boxSize = readBE64(extSize);
            headerLen = 16;
        } else if (boxSize == 0) {
            boxSize = static_cast<uint64_t>(endPos - pos);
        }

        if (boxSize < static_cast<uint64_t>(headerLen) || pos + static_cast<int64_t>(boxSize) > endPos) {
            break;
        }

        int64_t payloadStart = pos + headerLen;
        int64_t payloadEnd = pos + static_cast<int64_t>(boxSize);

        if (std::memcmp(hdr + 4, "moov", 4) == 0 ||
            std::memcmp(hdr + 4, "trak", 4) == 0 ||
            std::memcmp(hdr + 4, "mdia", 4) == 0 ||
            std::memcmp(hdr + 4, "minf", 4) == 0 ||
            std::memcmp(hdr + 4, "stbl", 4) == 0) {
            // If we already found a valid ALAC track, stop recursing into other tracks (e.g. cover art)
            bool alreadyFoundAlac = !magicCookie.empty() && !chunkOffsets.empty() && sampleCount > 0;
            if (!alreadyFoundAlac || std::memcmp(hdr + 4, "trak", 4) != 0) {
                parseContainer(payloadStart, payloadEnd);
            }
        } else if (std::memcmp(hdr + 4, "stsd", 4) == 0) {
            parseStsd(payloadStart, payloadEnd);
        } else if (std::memcmp(hdr + 4, "stts", 4) == 0) {
            if (!magicCookie.empty()) parseStts(payloadStart, payloadEnd);
        } else if (std::memcmp(hdr + 4, "stsc", 4) == 0) {
            if (!magicCookie.empty()) parseStsc(payloadStart, payloadEnd);
        } else if (std::memcmp(hdr + 4, "stsz", 4) == 0) {
            if (!magicCookie.empty()) parseStsz(payloadStart, payloadEnd);
        } else if (std::memcmp(hdr + 4, "stco", 4) == 0) {
            if (!magicCookie.empty()) parseStco(payloadStart, payloadEnd, false);
        } else if (std::memcmp(hdr + 4, "co64", 4) == 0) {
            if (!magicCookie.empty()) parseStco(payloadStart, payloadEnd, true);
        }

        pos = payloadEnd;
    }
    return !magicCookie.empty();
}

bool AlacMp4Decoder::parseStsd(int64_t startPos, int64_t endPos) {
    // FullBox: version(1) + flags(3) + entry_count(4)
    if (startPos + 8 > endPos) return false;
    uint8_t hdr[8];
    if (!readBytesAt(filePtr, startPos, hdr, 8)) return false;
    uint32_t entryCount = readBE32(hdr + 4);

    int64_t pos = startPos + 8;
    for (uint32_t i = 0; i < entryCount && pos + 8 <= endPos; ++i) {
        uint8_t entryHdr[8];
        if (!readBytesAt(filePtr, pos, entryHdr, 8)) break;
        uint32_t entrySize = readBE32(entryHdr);
        if (entrySize < 36 || pos + entrySize > endPos) break;

        if (std::memcmp(entryHdr + 4, "alac", 4) == 0) {
            // AudioSampleEntry header is 28 bytes after the 8-byte box header = 36 bytes total
            // Inside it sits the 'alac' magic cookie box: [size(4)]['alac'(4)][ver/flags(4)][24-byte ALACSpecificConfig]
            int64_t subPos = pos + 36;
            int64_t entryEnd = pos + entrySize;
            while (subPos + 8 <= entryEnd) {
                uint8_t subHdr[8];
                if (!readBytesAt(filePtr, subPos, subHdr, 8)) break;
                uint32_t subSize = readBE32(subHdr);
                if (subSize < 8 || subPos + subSize > entryEnd) break;
                if (std::memcmp(subHdr + 4, "alac", 4) == 0 && subSize >= 36) {
                    // Skip 8-byte box header + 4-byte version/flags => 24 bytes ALACSpecificConfig
                    magicCookie.resize(24);
                    if (readBytesAt(filePtr, subPos + 12, magicCookie.data(), 24)) {
                        return true;
                    }
                    magicCookie.clear();
                } else if (std::memcmp(subHdr + 4, "alac", 4) == 0 && subSize >= 32) {
                    magicCookie.resize(24);
                    if (readBytesAt(filePtr, subPos + 8, magicCookie.data(), 24)) {
                        return true;
                    }
                    magicCookie.clear();
                }
                subPos += subSize;
            }
        }
        pos += entrySize;
    }
    return false;
}

bool AlacMp4Decoder::parseStts(int64_t startPos, int64_t endPos) {
    if (startPos + 8 > endPos) return false;
    uint8_t hdr[8];
    if (!readBytesAt(filePtr, startPos, hdr, 8)) return false;
    uint32_t count = readBE32(hdr + 4);
    if (startPos + 8 + static_cast<int64_t>(count) * 8 > endPos) return false;

    sttsEntries.clear();
    sttsEntries.reserve(count);
    std::vector<uint8_t> raw(static_cast<size_t>(count) * 8);
    if (!readBytesAt(filePtr, startPos + 8, raw.data(), raw.size())) return false;
    for (uint32_t i = 0; i < count; ++i) {
        sttsEntries.push_back({readBE32(raw.data() + i * 8), readBE32(raw.data() + i * 8 + 4)});
    }
    return true;
}

bool AlacMp4Decoder::parseStsc(int64_t startPos, int64_t endPos) {
    if (startPos + 8 > endPos) return false;
    uint8_t hdr[8];
    if (!readBytesAt(filePtr, startPos, hdr, 8)) return false;
    uint32_t count = readBE32(hdr + 4);
    if (startPos + 8 + static_cast<int64_t>(count) * 12 > endPos) return false;

    stscEntries.clear();
    stscEntries.reserve(count);
    std::vector<uint8_t> raw(static_cast<size_t>(count) * 12);
    if (!readBytesAt(filePtr, startPos + 8, raw.data(), raw.size())) return false;
    for (uint32_t i = 0; i < count; ++i) {
        stscEntries.push_back({
            readBE32(raw.data() + i * 12),
            readBE32(raw.data() + i * 12 + 4),
            readBE32(raw.data() + i * 12 + 8)
        });
    }
    return true;
}

bool AlacMp4Decoder::parseStsz(int64_t startPos, int64_t endPos) {
    if (startPos + 12 > endPos) return false;
    uint8_t hdr[12];
    if (!readBytesAt(filePtr, startPos, hdr, 12)) return false;
    constantSampleSize = readBE32(hdr + 4);
    sampleCount = readBE32(hdr + 8);
    sampleSizes.clear();

    if (constantSampleSize == 0 && sampleCount > 0) {
        if (startPos + 12 + static_cast<int64_t>(sampleCount) * 4 > endPos) return false;
        std::vector<uint8_t> raw(static_cast<size_t>(sampleCount) * 4);
        if (!readBytesAt(filePtr, startPos + 12, raw.data(), raw.size())) return false;
        sampleSizes.resize(sampleCount);
        for (uint32_t i = 0; i < sampleCount; ++i) {
            sampleSizes[i] = readBE32(raw.data() + i * 4);
        }
    }
    return true;
}

bool AlacMp4Decoder::parseStco(int64_t startPos, int64_t endPos, bool is64Bit) {
    if (startPos + 8 > endPos) return false;
    uint8_t hdr[8];
    if (!readBytesAt(filePtr, startPos, hdr, 8)) return false;
    uint32_t count = readBE32(hdr + 4);
    size_t elemSize = is64Bit ? 8 : 4;
    if (startPos + 8 + static_cast<int64_t>(count) * static_cast<int64_t>(elemSize) > endPos) return false;

    std::vector<uint8_t> raw(static_cast<size_t>(count) * elemSize);
    if (!readBytesAt(filePtr, startPos + 8, raw.data(), raw.size())) return false;
    chunkOffsets.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        chunkOffsets[i] = is64Bit ? readBE64(raw.data() + i * 8) : readBE32(raw.data() + i * 4);
    }
    return true;
}

bool AlacMp4Decoder::buildPacketTable() {
    if (sampleCount == 0 || chunkOffsets.empty() || stscEntries.empty()) return false;

    packetOffsets.clear();
    packetSizes.clear();
    packetStartFrame.clear();
    packetOffsets.reserve(sampleCount);
    packetSizes.reserve(sampleCount);
    packetStartFrame.reserve(sampleCount);

    uint32_t sampleIdx = 0;
    size_t numChunks = chunkOffsets.size();

    for (size_t stscIdx = 0; stscIdx < stscEntries.size() && sampleIdx < sampleCount; ++stscIdx) {
        uint32_t firstChunk = stscEntries[stscIdx].firstChunk; // 1-based
        uint32_t nextFirstChunk = (stscIdx + 1 < stscEntries.size())
                                      ? stscEntries[stscIdx + 1].firstChunk
                                      : static_cast<uint32_t>(numChunks + 1);
        uint32_t samplesPerChunk = stscEntries[stscIdx].samplesPerChunk;
        if (firstChunk == 0 || samplesPerChunk == 0) continue;

        for (uint32_t chunk = firstChunk; chunk < nextFirstChunk && chunk <= numChunks && sampleIdx < sampleCount; ++chunk) {
            uint64_t offset = chunkOffsets[chunk - 1];
            for (uint32_t s = 0; s < samplesPerChunk && sampleIdx < sampleCount; ++s) {
                uint32_t sz = (constantSampleSize > 0) ? constantSampleSize : sampleSizes[sampleIdx];
                packetOffsets.push_back(offset);
                packetSizes.push_back(sz);
                offset += sz;
                sampleIdx++;
            }
        }
    }

    if (packetOffsets.empty()) return false;

    // Map each packet to its starting PCM frame using stts (fallback: 4096 frames/packet)
    int64_t frameCursor = 0;
    size_t sttsIdx = 0;
    uint32_t sttsRemaining = sttsEntries.empty() ? 0 : sttsEntries[0].count;
    for (size_t i = 0; i < packetOffsets.size(); ++i) {
        packetStartFrame.push_back(frameCursor);
        uint32_t delta = 4096;
        if (sttsIdx < sttsEntries.size()) {
            delta = sttsEntries[sttsIdx].delta;
            if (sttsRemaining > 0) sttsRemaining--;
            if (sttsRemaining == 0) {
                sttsIdx++;
                if (sttsIdx < sttsEntries.size()) sttsRemaining = sttsEntries[sttsIdx].count;
            }
        }
        frameCursor += delta;
    }
    totalFrames = frameCursor;
    currentPacketIdx = 0;
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;
    return true;
}

bool AlacMp4Decoder::decodeNextPacket() {
    if (!decoder || !filePtr || currentPacketIdx >= packetOffsets.size()) return false;

    uint64_t offset = packetOffsets[currentPacketIdx];
    uint32_t size = packetSizes[currentPacketIdx];
    currentPacketIdx++;

    if (size == 0) return false;
    // Pad extra 16 bytes for safe BitBuffer read-ahead
    if (packetReadBuf.size() < static_cast<size_t>(size) + 16) {
        packetReadBuf.resize(static_cast<size_t>(size) + 16);
    }
    if (!readBytesAt(filePtr, static_cast<int64_t>(offset), packetReadBuf.data(), size)) {
        return false;
    }
    std::memset(packetReadBuf.data() + size, 0, 16);

    BitBuffer bits;
    BitBufferInit(&bits, packetReadBuf.data(), size);

    uint32_t outFrames = 0;
    int32_t err = decoder->Decode(&bits, decodedRawBuf.data(), frameLength,
                                  static_cast<uint32_t>(channels), &outFrames);
    if (err != 0 || outFrames == 0) {
        return false;
    }

    if (decodedStereoBuf.size() < static_cast<size_t>(outFrames) * 2) {
        decodedStereoBuf.resize(static_cast<size_t>(outFrames) * 2);
    }

    // Apple ALACDecoder outputs little-endian packed PCM (bytesPerSample = (bitDepth + 7) / 8)
    const uint8_t* src = decodedRawBuf.data();
    int bytesPerSample = (bitDepth + 7) / 8;

    auto readSampleInt32 = [&](const uint8_t* p) -> int32_t {
        if (bytesPerSample == 2) {
            int16_t s = static_cast<int16_t>(static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8));
            return static_cast<int32_t>(s) << 16;
        } else if (bytesPerSample == 3) {
            // 20-bit or 24-bit signed little-endian packed in 3 bytes -> left-justified int32_t
            int32_t v = (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 24) |
                        (static_cast<int32_t>(p[1]) << 16) |
                        (static_cast<int32_t>(p[0]) << 8);
            if (bitDepth == 20) v <<= 4;
            return v;
        } else if (bytesPerSample == 4) {
            return static_cast<int32_t>(
                static_cast<uint32_t>(p[0]) |
                (static_cast<uint32_t>(p[1]) << 8) |
                (static_cast<uint32_t>(p[2]) << 16) |
                (static_cast<uint32_t>(p[3]) << 24));
        } else {
            return static_cast<int32_t>(static_cast<int8_t>(p[0])) << 24;
        }
    };

    size_t frameStride = static_cast<size_t>(channels) * static_cast<size_t>(bytesPerSample);
    for (uint32_t f = 0; f < outFrames; ++f) {
        const uint8_t* fp = src + static_cast<size_t>(f) * frameStride;
        int32_t l = readSampleInt32(fp);
        int32_t r = (channels >= 2) ? readSampleInt32(fp + bytesPerSample) : l;
        decodedStereoBuf[f * 2] = l;
        decodedStereoBuf[f * 2 + 1] = r;
    }

    decodedFramesTotal = outFrames;
    decodedFramesOffset = 0;
    return true;
}

int AlacMp4Decoder::readFramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!decoder || !outStereoInt32 || maxFrames <= 0) return 0;

    int totalWritten = 0;
    while (totalWritten < maxFrames) {
        if (decodedFramesOffset >= decodedFramesTotal) {
            if (!decodeNextPacket()) {
                break;
            }
        }
        size_t avail = decodedFramesTotal - decodedFramesOffset;
        size_t toCopy = std::min<size_t>(avail, static_cast<size_t>(maxFrames - totalWritten));
        std::memcpy(outStereoInt32 + static_cast<size_t>(totalWritten) * 2,
                    decodedStereoBuf.data() + decodedFramesOffset * 2,
                    toCopy * 2 * sizeof(int32_t));
        decodedFramesOffset += toCopy;
        totalWritten += static_cast<int>(toCopy);
    }
    return totalWritten;
}

bool AlacMp4Decoder::seekMs(int64_t positionMs) {
    if (!decoder || packetStartFrame.empty()) return false;
    if (positionMs < 0) positionMs = 0;
    int64_t targetFrame = (positionMs * sampleRate) / 1000LL;
    if (totalFrames > 0 && targetFrame > totalFrames) targetFrame = totalFrames;

    auto it = std::upper_bound(packetStartFrame.begin(), packetStartFrame.end(), targetFrame);
    size_t idx = (it == packetStartFrame.begin())
                     ? 0
                     : static_cast<size_t>(std::distance(packetStartFrame.begin(), it) - 1);
    if (idx >= packetOffsets.size()) idx = packetOffsets.size() - 1;

    currentPacketIdx = idx;
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;

    // Decode the target packet and advance intra-packet offset for sample-exact seek
    if (decodeNextPacket()) {
        int64_t pktStart = packetStartFrame[idx];
        if (targetFrame > pktStart) {
            size_t skip = static_cast<size_t>(targetFrame - pktStart);
            if (skip < decodedFramesTotal) {
                decodedFramesOffset = skip;
            }
        }
    }
    return true;
}

