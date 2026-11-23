#include "tta_decoder.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "TtaDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t SHIFT_16[40] = {
    0x00000001, 0x00000002, 0x00000004, 0x00000008,
    0x00000010, 0x00000020, 0x00000040, 0x00000080,
    0x00000100, 0x00000200, 0x00000400, 0x00000800,
    0x00001000, 0x00002000, 0x00004000, 0x00008000,
    0x00010000, 0x00020000, 0x00040000, 0x00080000,
    0x00100000, 0x00200000, 0x00400000, 0x00800000,
    0x01000000, 0x02000000, 0x04000000, 0x08000000,
    0x10000000, 0x20000000, 0x40000000, 0x80000000,
    0x80000000, 0x80000000, 0x80000000, 0x80000000,
    0x80000000, 0x80000000, 0x80000000, 0x80000000
};

constexpr uint32_t BIT_MASK[33] = {
    0x00000000, 0x00000001, 0x00000003, 0x00000007,
    0x0000000f, 0x0000001f, 0x0000003f, 0x0000007f,
    0x000000ff, 0x000001ff, 0x000003ff, 0x000007ff,
    0x00000fff, 0x00001fff, 0x00003fff, 0x00007fff,
    0x0000ffff, 0x0001ffff, 0x0003ffff, 0x0007ffff,
    0x000fffff, 0x001fffff, 0x003fffff, 0x007fffff,
    0x00ffffff, 0x01ffffff, 0x03ffffff, 0x07ffffff,
    0x0fffffff, 0x1fffffff, 0x3fffffff, 0x7fffffff,
    0xffffffff
};

uint16_t readLE16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

struct BitReader {
    const uint8_t* data;
    size_t size;
    size_t pos = 0;
    uint32_t cache = 0;
    uint32_t count = 0;

    BitReader(const uint8_t* d, size_t s) : data(d), size(s) {}

    uint32_t getUnary() {
        uint32_t val = 0;
        while (true) {
            if (count == 0) {
                if (pos >= size) return val;
                cache = data[pos++];
                count = 8;
            }
            if ((cache & 1) == 0) {
                cache >>= 1;
                count--;
                return val;
            }
            cache >>= 1;
            count--;
            val++;
        }
    }

    uint32_t getBits(uint32_t bits) {
        if (bits == 0) return 0;
        while (count < bits) {
            if (pos >= size) {
                cache |= 0;
                count = bits;
                break;
            }
            cache |= static_cast<uint32_t>(data[pos++]) << count;
            count += 8;
        }
        uint32_t res = cache & BIT_MASK[bits];
        cache >>= bits;
        count -= bits;
        return res;
    }
};

} // namespace

TtaDecoder::TtaDecoder() = default;

TtaDecoder::~TtaDecoder() {
    close();
}

void TtaDecoder::close() {
    filePtr = nullptr;
    opened = false;
    sampleRate = 44100;
    bitDepth = 16;
    channels = 2;
    totalSamples = 0;
    durationMs = 0;
    frameLength = 0;
    lastFrameLength = 0;
    totalFramesCount = 0;
    dataStartOffset = 0;
    seekTableSizes.clear();
    frameOffsets.clear();
    currentFrameIdx = 0;
    frameBitBuf.clear();
    decodedStereoBuf.clear();
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;
}

bool TtaDecoder::open(FILE* fp) {
    close();
    if (!fp) return false;
    filePtr = fp;

    fseeko(fp, 0, SEEK_SET);
    uint8_t magic[10] = {0};
    if (std::fread(magic, 1, 10, fp) != 10) return false;

    int64_t headerOffset = 0;
    // Skip optional ID3v2 header
    if (std::memcmp(magic, "ID3", 3) == 0) {
        uint32_t tagSize = ((static_cast<uint32_t>(magic[6]) & 0x7F) << 21) |
                           ((static_cast<uint32_t>(magic[7]) & 0x7F) << 14) |
                           ((static_cast<uint32_t>(magic[8]) & 0x7F) << 7) |
                           (static_cast<uint32_t>(magic[9]) & 0x7F);
        headerOffset = 10 + static_cast<int64_t>(tagSize);
        if (magic[5] & 0x10) headerOffset += 10; // footer
    }

    if (fseeko(fp, static_cast<off_t>(headerOffset), SEEK_SET) != 0) return false;
    uint8_t hdr[22];
    if (std::fread(hdr, 1, 22, fp) != 22) return false;
    if (std::memcmp(hdr, "TTA1", 4) != 0) return false;

    uint16_t audioFormat = readLE16(hdr + 4);
    uint16_t numCh = readLE16(hdr + 6);
    uint16_t bps = readLE16(hdr + 8);
    uint32_t sr = readLE32(hdr + 10);
    uint32_t dataLen = readLE32(hdr + 14);
    // hdr[18..21] is header CRC32

    if (audioFormat != 1 || numCh == 0 || numCh > 8 || (bps != 8 && bps != 16 && bps != 24) || sr == 0 || dataLen == 0) {
        LOGW("Unsupported TTA1 parameters: fmt=%u ch=%u bps=%u sr=%u", audioFormat, numCh, bps, sr);
        return false;
    }

    channels = static_cast<int>(numCh);
    bitDepth = static_cast<int>(bps);
    sampleRate = static_cast<int>(sr);
    totalSamples = static_cast<int64_t>(dataLen);
    durationMs = (totalSamples * 1000LL) / sampleRate;

    // TTA1 standard frame length: 1.04489795918367346939s = (sampleRate * 256) / 245
    frameLength = static_cast<uint32_t>((static_cast<uint64_t>(sampleRate) * 256ULL) / 245ULL);
    if (frameLength == 0) return false;
    lastFrameLength = dataLen % frameLength;
    totalFramesCount = (dataLen / frameLength) + (lastFrameLength > 0 ? 1 : 0);
    if (lastFrameLength == 0) lastFrameLength = frameLength;

    if (totalFramesCount == 0 || totalFramesCount > 500000) return false;

    // Read seek table (totalFramesCount * 4 bytes) + 4-byte seektable CRC32
    std::vector<uint8_t> rawSeek(static_cast<size_t>(totalFramesCount) * 4 + 4);
    if (std::fread(rawSeek.data(), 1, rawSeek.size(), fp) != rawSeek.size()) return false;

    dataStartOffset = static_cast<int64_t>(ftello(fp));
    seekTableSizes.resize(totalFramesCount);
    frameOffsets.resize(totalFramesCount);
    int64_t cursor = dataStartOffset;
    for (uint32_t i = 0; i < totalFramesCount; ++i) {
        uint32_t sz = readLE32(rawSeek.data() + static_cast<size_t>(i) * 4);
        seekTableSizes[i] = sz;
        frameOffsets[i] = cursor;
        cursor += sz;
    }

    currentFrameIdx = 0;
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;
    opened = true;

    LOGI("Opened native TTA1: %d Hz, %d-bit, %d ch, %u frames (%lld ms)",
         sampleRate, bitDepth, channels, totalFramesCount, static_cast<long long>(durationMs));
    return true;
}

void TtaDecoder::filterInit(FltState& f, int32_t shift) {
    std::memset(&f, 0, sizeof(FltState));
    f.shift = shift;
    f.round = 1 << (shift - 1);
}

void TtaDecoder::hybridFilter(FltState& f, int32_t& in) {
    int32_t* pA = f.dl;
    int32_t* pB = f.qm;
    int32_t* pM = f.dx;
    int32_t sum = f.round;

    if (f.error < 0) {
        pB[0] -= pM[0]; pB[1] -= pM[1]; pB[2] -= pM[2]; pB[3] -= pM[3];
        pB[4] -= pM[4]; pB[5] -= pM[5]; pB[6] -= pM[6]; pB[7] -= pM[7];
    } else if (f.error > 0) {
        pB[0] += pM[0]; pB[1] += pM[1]; pB[2] += pM[2]; pB[3] += pM[3];
        pB[4] += pM[4]; pB[5] += pM[5]; pB[6] += pM[6]; pB[7] += pM[7];
    }

    sum += pA[0] * pB[0] + pA[1] * pB[1] + pA[2] * pB[2] + pA[3] * pB[3] +
           pA[4] * pB[4] + pA[5] * pB[5] + pA[6] * pB[6] + pA[7] * pB[7];

    pM[0] = pM[1]; pM[1] = pM[2]; pM[2] = pM[3]; pM[3] = pM[4];
    pM[4] = ((pA[4] >> 30) | 1);
    pM[5] = ((pA[5] >> 30) | 1) << 1;
    pM[6] = ((pA[6] >> 30) | 1) << 1;
    pM[7] = ((pA[7] >> 30) | 1) << 2;

    f.error = in;
    in += (sum >> f.shift);

    pA[0] = pA[1]; pA[1] = pA[2]; pA[2] = pA[3]; pA[3] = pA[4];
    pA[4] = -pA[5];
    pA[5] = -pA[6];
    pA[6] = in - pA[7];
    pA[7] = in;
    pA[5] += pA[6];
    pA[4] += pA[5];
}

void TtaDecoder::riceInit(AdaptState& r, uint32_t k0, uint32_t k1) {
    r.k0 = k0;
    r.k1 = k1;
    r.sum0 = SHIFT_16[k0];
    r.sum1 = SHIFT_16[k1];
}

bool TtaDecoder::decodeNextFrame() {
    if (!opened || !filePtr || currentFrameIdx >= totalFramesCount) return false;

    uint32_t frameBytes = seekTableSizes[currentFrameIdx];
    int64_t offset = frameOffsets[currentFrameIdx];
    uint32_t samplesInFrame = (currentFrameIdx == totalFramesCount - 1) ? lastFrameLength : frameLength;
    currentFrameIdx++;

    if (frameBytes < 4) return false;
    if (frameBitBuf.size() < frameBytes) frameBitBuf.resize(frameBytes);

    if (fseeko(filePtr, static_cast<off_t>(offset), SEEK_SET) != 0) return false;
    if (std::fread(frameBitBuf.data(), 1, frameBytes, filePtr) != frameBytes) return false;

    // Exclude trailing 4-byte frame CRC32
    BitReader br(frameBitBuf.data(), frameBytes - 4);

    int32_t fltShift = (bitDepth == 8) ? 10 : ((bitDepth == 16) ? 9 : 10);
    std::vector<ChannelCodec> codecs(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) {
        filterInit(codecs[c].flt, fltShift);
        riceInit(codecs[c].rice, 10, 10);
        codecs[c].last = 0;
    }

    if (decodedStereoBuf.size() < static_cast<size_t>(samplesInFrame) * 2) {
        decodedStereoBuf.resize(static_cast<size_t>(samplesInFrame) * 2);
    }

    std::vector<int32_t> chSamples(static_cast<size_t>(channels), 0);
    int leftShiftToInt32 = 32 - bitDepth;

    for (uint32_t s = 0; s < samplesInFrame; ++s) {
        for (int c = 0; c < channels; ++c) {
            AdaptState& r = codecs[c].rice;
            uint32_t unary = br.getUnary();
            uint32_t depth = 0;
            uint32_t k = 0;
            uint32_t value = 0;

            if (unary == 0) {
                depth = 0;
                k = r.k0;
            } else {
                depth = 1;
                k = r.k1;
                unary--;
            }

            if (k > 0) {
                uint32_t binary = br.getBits(k);
                value = (unary << k) + binary;
            } else {
                value = unary;
            }

            if (depth == 1) {
                r.sum1 += value - (r.sum1 >> 4);
                if (r.k1 > 0 && r.sum1 < SHIFT_16[r.k1]) r.k1--;
                else if (r.sum1 > SHIFT_16[r.k1 + 1]) r.k1++;
                value += SHIFT_16[r.k0];
            }

            r.sum0 += value - (r.sum0 >> 4);
            if (r.k0 > 0 && r.sum0 < SHIFT_16[r.k0]) r.k0--;
            else if (r.sum0 > SHIFT_16[r.k0 + 1]) r.k0++;

            // Unsigned -> signed zigzag decode
            int32_t decoded = (value & 1) ? static_cast<int32_t>((value + 1) >> 1)
                                          : -static_cast<int32_t>(value >> 1);

            // Adaptive 8-tap hybrid filter
            hybridFilter(codecs[c].flt, decoded);

            // Fixed predictor
            int32_t prev = codecs[c].last;
            if (bitDepth == 8) {
                decoded += ((prev << 4) - prev) >> 4;
            } else {
                decoded += ((prev << 5) - prev) >> 5;
            }
            codecs[c].last = decoded;
            chSamples[c] = decoded;
        }

        // Multi-channel / stereo decorrelation reconstruction
        if (channels > 1) {
            int32_t last = chSamples[channels - 1] + (chSamples[channels - 2] / 2);
            chSamples[channels - 1] = last;
            for (int c = channels - 2; c >= 0; --c) {
                chSamples[c] = last - chSamples[c];
                last = chSamples[c];
            }
        }

        int32_t l = static_cast<int32_t>(static_cast<uint32_t>(chSamples[0]) << leftShiftToInt32);
        int32_t r = (channels >= 2)
                        ? static_cast<int32_t>(static_cast<uint32_t>(chSamples[1]) << leftShiftToInt32)
                        : l;
        decodedStereoBuf[s * 2] = l;
        decodedStereoBuf[s * 2 + 1] = r;
    }

    decodedFramesTotal = samplesInFrame;
    decodedFramesOffset = 0;
    return true;
}

int TtaDecoder::readFramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!opened || !outStereoInt32 || maxFrames <= 0) return 0;

    int totalWritten = 0;
    while (totalWritten < maxFrames) {
        if (decodedFramesOffset >= decodedFramesTotal) {
            if (!decodeNextFrame()) {
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

bool TtaDecoder::seekMs(int64_t positionMs) {
    if (!opened || frameLength == 0 || totalFramesCount == 0) return false;
    if (positionMs < 0) positionMs = 0;
    int64_t targetSample = (positionMs * sampleRate) / 1000LL;
    if (targetSample > totalSamples) targetSample = totalSamples;

    uint32_t frameIdx = static_cast<uint32_t>(targetSample / frameLength);
    if (frameIdx >= totalFramesCount) frameIdx = totalFramesCount - 1;
    size_t intraOffset = static_cast<size_t>(targetSample % frameLength);

    currentFrameIdx = frameIdx;
    decodedFramesTotal = 0;
    decodedFramesOffset = 0;

    if (decodeNextFrame() && intraOffset < decodedFramesTotal) {
        decodedFramesOffset = intraOffset;
    }
    return true;
}

