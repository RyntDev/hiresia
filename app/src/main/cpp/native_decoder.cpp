#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#include "third_party/dr_flac.h"

#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "third_party/dr_wav.h"

#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "third_party/dr_mp3.h"

#define STB_VORBIS_HEADER_ONLY
#include "third_party/stb_vorbis.c"

#include "third_party/wavpack/wavpack.h"

#include "native_decoder.h"
#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "NativeLosslessDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Lookup table for reversing bit order in a byte (DSF LSB-first -> DoP MSB-first)
constexpr uint8_t reverseByte(uint8_t b) {
    b = static_cast<uint8_t>(((b & 0xF0) >> 4) | ((b & 0x0F) << 4));
    b = static_cast<uint8_t>(((b & 0xCC) >> 2) | ((b & 0x33) << 2));
    b = static_cast<uint8_t>(((b & 0xAA) >> 1) | ((b & 0x55) << 1));
    return b;
}

struct BitRevTable {
    uint8_t table[256];
    constexpr BitRevTable() : table{} {
        for (int i = 0; i < 256; ++i) {
            table[i] = reverseByte(static_cast<uint8_t>(i));
        }
    }
};

constexpr BitRevTable BIT_REV{};

uint32_t readLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint64_t readLE64(const uint8_t* p) {
    return static_cast<uint64_t>(readLE32(p)) |
           (static_cast<uint64_t>(readLE32(p + 4)) << 32);
}

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

// Decode 80-bit IEEE 754 extended float (used for AIFF sample rate)
double parseIeee80Extended(const uint8_t* bytes) {
    int sign = (bytes[0] & 0x80) ? -1 : 1;
    int exponent = ((static_cast<int>(bytes[0] & 0x7F) << 8) | bytes[1]);
    uint64_t mantissa = readBE64(bytes + 2);
    if (exponent == 0 && mantissa == 0) return 0.0;
    if (exponent == 0x7FFF) return 0.0;
    double val = std::ldexp(static_cast<double>(mantissa), exponent - 16383 - 63);
    return sign * val;
}

bool endsWithIgnoreCase(const std::string& str, const char* suffix) {
    size_t slen = std::strlen(suffix);
    if (str.size() < slen) return false;
    size_t offset = str.size() - slen;
    for (size_t i = 0; i < slen; ++i) {
        char a = str[offset + i];
        char b = suffix[i];
        if (a >= 'A' && a <= 'Z') a = static_cast<char>(a - 'A' + 'a');
        if (b >= 'A' && b <= 'Z') b = static_cast<char>(b - 'A' + 'a');
        if (a != b) return false;
    }
    return true;
}

size_t drFlacRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    return fp ? std::fread(pBufferOut, 1, bytesToRead, fp) : 0;
}

drflac_bool32 drFlacSeek(void* pUserData, int offset, drflac_seek_origin origin) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp) return DRFLAC_FALSE;
    int whence = SEEK_SET;
    if (origin == DRFLAC_SEEK_CUR) whence = SEEK_CUR;
    else if (origin == DRFLAC_SEEK_END) whence = SEEK_END;
    return (fseeko(fp, static_cast<off_t>(offset), whence) == 0) ? DRFLAC_TRUE : DRFLAC_FALSE;
}

drflac_bool32 drFlacTell(void* pUserData, drflac_int64* pCursor) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp || !pCursor) return DRFLAC_FALSE;
    off_t pos = ftello(fp);
    if (pos < 0) return DRFLAC_FALSE;
    *pCursor = static_cast<drflac_int64>(pos);
    return DRFLAC_TRUE;
}

size_t drWavRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    return fp ? std::fread(pBufferOut, 1, bytesToRead, fp) : 0;
}

drwav_bool32 drWavSeek(void* pUserData, int offset, drwav_seek_origin origin) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp) return DRWAV_FALSE;
    int whence = SEEK_SET;
    if (origin == DRWAV_SEEK_CUR) whence = SEEK_CUR;
    else if (origin == DRWAV_SEEK_END) whence = SEEK_END;
    return (fseeko(fp, static_cast<off_t>(offset), whence) == 0) ? DRWAV_TRUE : DRWAV_FALSE;
}

drwav_bool32 drWavTell(void* pUserData, drwav_int64* pCursor) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp || !pCursor) return DRWAV_FALSE;
    off_t pos = ftello(fp);
    if (pos < 0) return DRWAV_FALSE;
    *pCursor = static_cast<drwav_int64>(pos);
    return DRWAV_TRUE;
}

size_t drMp3Read(void* pUserData, void* pBufferOut, size_t bytesToRead) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    return fp ? std::fread(pBufferOut, 1, bytesToRead, fp) : 0;
}

drmp3_bool32 drMp3Seek(void* pUserData, int offset, drmp3_seek_origin origin) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp) return DRMP3_FALSE;
    int whence = SEEK_SET;
    if (origin == DRMP3_SEEK_CUR) whence = SEEK_CUR;
    else if (origin == DRMP3_SEEK_END) whence = SEEK_END;
    return (fseeko(fp, static_cast<off_t>(offset), whence) == 0) ? DRMP3_TRUE : DRMP3_FALSE;
}

drmp3_bool32 drMp3Tell(void* pUserData, drmp3_int64* pCursor) {
    FILE* fp = reinterpret_cast<FILE*>(pUserData);
    if (!fp || !pCursor) return DRMP3_FALSE;
    off_t pos = ftello(fp);
    if (pos < 0) return DRMP3_FALSE;
    *pCursor = static_cast<drmp3_int64>(pos);
    return DRMP3_TRUE;
}

// WavPack 64-bit FILE* stream reader callbacks (non-owning close so NativeLosslessDecoder owns FILE*)
int32_t wpReadBytes(void* id, void* data, int32_t bcount) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    if (!fp || bcount <= 0) return 0;
    return static_cast<int32_t>(std::fread(data, 1, static_cast<size_t>(bcount), fp));
}

int64_t wpGetPos(void* id) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    return fp ? static_cast<int64_t>(ftello(fp)) : -1;
}

int wpSetPosAbs(void* id, int64_t pos) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    return (fp && fseeko(fp, static_cast<off_t>(pos), SEEK_SET) == 0) ? 0 : -1;
}

int wpSetPosRel(void* id, int64_t delta, int mode) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    return (fp && fseeko(fp, static_cast<off_t>(delta), mode) == 0) ? 0 : -1;
}

int wpPushBackByte(void* id, int c) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    return fp ? std::ungetc(c, fp) : EOF;
}

int64_t wpGetLength(void* id) {
    FILE* fp = reinterpret_cast<FILE*>(id);
    if (!fp) return 0;
    off_t cur = ftello(fp);
    if (fseeko(fp, 0, SEEK_END) != 0) return 0;
    off_t end = ftello(fp);
    fseeko(fp, cur, SEEK_SET);
    return static_cast<int64_t>(end);
}

int wpCanSeek(void* /*id*/) {
    return 1;
}

WavpackStreamReader64 WAVPACK_READER_64 = {
    wpReadBytes,
    nullptr, // write_bytes
    wpGetPos,
    wpSetPosAbs,
    wpSetPosRel,
    wpPushBackByte,
    wpGetLength,
    wpCanSeek,
    nullptr, // truncate_here
    nullptr  // close (FILE* closed by NativeLosslessDecoder::close)
};

} // namespace

NativeLosslessDecoder::NativeLosslessDecoder() = default;

NativeLosslessDecoder::~NativeLosslessDecoder() {
    close();
}

void NativeLosslessDecoder::close() {
    std::lock_guard<std::recursive_mutex> lock(decoderMutex);
    if (flacHandle) {
        drflac_close(reinterpret_cast<drflac*>(flacHandle));
        flacHandle = nullptr;
    }
    if (wavHandle) {
        drwav* w = reinterpret_cast<drwav*>(wavHandle);
        drwav_uninit(w);
        delete w;
        wavHandle = nullptr;
    }
    if (wavpackHandle) {
        WavpackCloseFile(reinterpret_cast<WavpackContext*>(wavpackHandle));
        wavpackHandle = nullptr;
    }
    if (vorbisHandle) {
        stb_vorbis_close(reinterpret_cast<stb_vorbis*>(vorbisHandle));
        vorbisHandle = nullptr;
    }
    if (mp3Handle) {
        drmp3* m = reinterpret_cast<drmp3*>(mp3Handle);
        drmp3_uninit(m);
        delete m;
        mp3Handle = nullptr;
    }
    alacDecoder.close();
    ttaDecoder.close();

    if (filePtr) {
        std::fclose(filePtr);
        filePtr = nullptr;
    }
    formatType = FormatType::NONE;
    sourceSampleRate = 44100;
    outputSampleRate = 44100;
    bitDepth = 16;
    channels = 2;
    totalPcmFrames = 0;
    durationMs = 0;

    aiffDataOffset = 0;
    aiffFramesRead = 0;
    aiffBytesPerSample = 2;
    aiffLittleEndian = false;
    aiffIsFloat = false;
    aiffReadBuf.clear();

    wavpackIsFloat = false;
    wavpackBitsPerSample = 16;

    dsdRate = 0;
    dsdDataOffset = 0;
    dsdDataSize = 0;
    dsdBytesRead = 0;
    dsdBufferedBytes = 0;
    dsdBufferedOffset = 0;
    dopMarkerState = 0x05;
    dsdDecoder.reset();
}

bool NativeLosslessDecoder::openPath(const std::string& path) {
    std::lock_guard<std::recursive_mutex> lock(decoderMutex);
    close();
    if (path.empty()) return false;

    FILE* fp = std::fopen(path.c_str(), "rb");
    if (!fp) {
        LOGW("openPath failed to open: %s", path.c_str());
        return false;
    }
    return openFileStream(fp, path);
}

bool NativeLosslessDecoder::openFd(int fd, const std::string& hintName) {
    std::lock_guard<std::recursive_mutex> lock(decoderMutex);
    close();
    if (fd < 0) return false;

    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGW("openFd dup(%d) failed", fd);
        return false;
    }
    lseek(dupFd, 0, SEEK_SET);
    FILE* fp = fdopen(dupFd, "rb");
    if (!fp) {
        ::close(dupFd);
        LOGW("openFd fdopen failed");
        return false;
    }
    return openFileStream(fp, hintName);
}

bool NativeLosslessDecoder::openFileStream(FILE* fp, const std::string& hintName) {
    uint8_t magic[12] = {0};
    size_t readLen = std::fread(magic, 1, sizeof(magic), fp);
    fseeko(fp, 0, SEEK_SET);

    if (readLen < 4) {
        std::fclose(fp);
        return false;
    }

    // 1. Check DSF ("DSD ")
    if (std::memcmp(magic, "DSD ", 4) == 0) {
        if (initDsf(fp)) {
            filePtr = fp;
            formatType = FormatType::DSF;
            return true;
        }
        std::fclose(fp);
        return false;
    }

    // 2. Check DFF / DSDIFF ("FRM8" ... "DSD ")
    if (readLen >= 12 && std::memcmp(magic, "FRM8", 4) == 0 && std::memcmp(magic + 8, "DSD ", 4) == 0) {
        if (initDff(fp)) {
            filePtr = fp;
            formatType = FormatType::DFF;
            return true;
        }
        std::fclose(fp);
        return false;
    }

    // 3. Check FLAC ("fLaC" or ID3-tagged FLAC)
    bool maybeFlac = (std::memcmp(magic, "fLaC", 4) == 0) ||
                     (std::memcmp(magic, "ID3", 3) == 0 && endsWithIgnoreCase(hintName, ".flac")) ||
                     endsWithIgnoreCase(hintName, ".flac");
    if (maybeFlac) {
        drflac* flac = drflac_open(drFlacRead, drFlacSeek, drFlacTell, fp, nullptr);
        if (flac) {
            filePtr = fp;
            flacHandle = flac;
            formatType = FormatType::FLAC;
            sourceSampleRate = static_cast<int>(flac->sampleRate);
            outputSampleRate = sourceSampleRate;
            bitDepth = static_cast<int>(flac->bitsPerSample);
            channels = static_cast<int>(flac->channels);
            totalPcmFrames = static_cast<int64_t>(flac->totalPCMFrameCount);
            durationMs = (sourceSampleRate > 0 && totalPcmFrames > 0)
                             ? (totalPcmFrames * 1000LL) / sourceSampleRate
                             : 0;
            LOGI("Opened native FLAC: %d Hz, %d-bit, %d ch, %lld frames (%lld ms)",
                 sourceSampleRate, bitDepth, channels,
                 static_cast<long long>(totalPcmFrames), static_cast<long long>(durationMs));
            return true;
        }
        fseeko(fp, 0, SEEK_SET);
    }

    // 4. Check WAV / RF64 / W64 ("RIFF", "RF64", "riff")
    bool maybeWav = (std::memcmp(magic, "RIFF", 4) == 0) ||
                    (std::memcmp(magic, "RF64", 4) == 0) ||
                    (std::memcmp(magic, "riff", 4) == 0) ||
                    endsWithIgnoreCase(hintName, ".wav");
    if (maybeWav) {
        drwav* wav = new drwav();
        if (drwav_init(wav, drWavRead, drWavSeek, drWavTell, fp, nullptr)) {
            filePtr = fp;
            wavHandle = wav;
            formatType = FormatType::WAV;
            sourceSampleRate = static_cast<int>(wav->sampleRate);
            outputSampleRate = sourceSampleRate;
            bitDepth = static_cast<int>(wav->bitsPerSample);
            channels = static_cast<int>(wav->channels);
            totalPcmFrames = static_cast<int64_t>(wav->totalPCMFrameCount);
            durationMs = (sourceSampleRate > 0 && totalPcmFrames > 0)
                             ? (totalPcmFrames * 1000LL) / sourceSampleRate
                             : 0;
            LOGI("Opened native WAV: %d Hz, %d-bit, %d ch, %lld frames (%lld ms)",
                 sourceSampleRate, bitDepth, channels,
                 static_cast<long long>(totalPcmFrames), static_cast<long long>(durationMs));
            return true;
        }
        delete wav;
        fseeko(fp, 0, SEEK_SET);
    }

    // 5. Check AIFF / AIFF-C ("FORM" ... "AIFF" or "AIFC")
    if (readLen >= 12 && std::memcmp(magic, "FORM", 4) == 0 &&
        (std::memcmp(magic + 8, "AIFF", 4) == 0 || std::memcmp(magic + 8, "AIFC", 4) == 0)) {
        if (initAiff(fp)) {
            filePtr = fp;
            formatType = FormatType::AIFF;
            return true;
        }
        fseeko(fp, 0, SEEK_SET);
    }

    // 6. Check WavPack ("wvpk")
    if (std::memcmp(magic, "wvpk", 4) == 0 || endsWithIgnoreCase(hintName, ".wv")) {
        if (initWavPack(fp)) {
            filePtr = fp;
            formatType = FormatType::WAVPACK;
            return true;
        }
        fseeko(fp, 0, SEEK_SET);
    }

    // 7. Check TTA1 ("TTA1" or ID3-tagged .tta)
    if (std::memcmp(magic, "TTA1", 4) == 0 || endsWithIgnoreCase(hintName, ".tta")) {
        if (ttaDecoder.open(fp)) {
            filePtr = fp;
            formatType = FormatType::TTA;
            sourceSampleRate = ttaDecoder.getSampleRate();
            outputSampleRate = sourceSampleRate;
            bitDepth = ttaDecoder.getBitDepth();
            channels = ttaDecoder.getChannels();
            totalPcmFrames = ttaDecoder.getTotalFrames();
            durationMs = ttaDecoder.getDurationMs();
            return true;
        }
        fseeko(fp, 0, SEEK_SET);
    }

    // 8. Check ALAC in MP4/M4A ("ftyp" at offset 4)
    if ((readLen >= 8 && std::memcmp(magic + 4, "ftyp", 4) == 0) ||
        endsWithIgnoreCase(hintName, ".alac") || endsWithIgnoreCase(hintName, ".m4a")) {
        if (alacDecoder.open(fp)) {
            filePtr = fp;
            formatType = FormatType::ALAC;
            sourceSampleRate = alacDecoder.getSampleRate();
            outputSampleRate = sourceSampleRate;
            bitDepth = alacDecoder.getBitDepth();
            channels = alacDecoder.getChannels();
            totalPcmFrames = alacDecoder.getTotalFrames();
            durationMs = alacDecoder.getDurationMs();
            return true;
        }
        // Not ALAC (e.g., AAC-in-M4A) -> return false cleanly so AudioDecoder falls back to MediaCodec
        std::fclose(fp);
        return false;
    }

    // 9. Check OGG Vorbis ("OggS")
    if (std::memcmp(magic, "OggS", 4) == 0) {
        if (initVorbis(fp)) {
            filePtr = fp;
            formatType = FormatType::VORBIS;
            return true;
        }
        // Not Vorbis (e.g., Opus-in-OGG) -> fall back to MediaCodec
        std::fclose(fp);
        return false;
    }

    // 10. Check MP3 (ID3v2 tag, MPEG frame sync, or .mp3 hint)
    bool maybeMp3 = endsWithIgnoreCase(hintName, ".mp3") ||
                    (std::memcmp(magic, "ID3", 3) == 0) ||
                    (magic[0] == 0xFF && (magic[1] & 0xE0) == 0xE0);
    if (maybeMp3) {
        if (initMp3(fp)) {
            filePtr = fp;
            formatType = FormatType::MP3;
            return true;
        }
    }

    std::fclose(fp);
    return false;
}

bool NativeLosslessDecoder::initAiff(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    uint8_t formHdr[12];
    if (std::fread(formHdr, 1, 12, fp) != 12) return false;
    if (std::memcmp(formHdr, "FORM", 4) != 0) return false;
    bool isAifc = (std::memcmp(formHdr + 8, "AIFC", 4) == 0);
    if (!isAifc && std::memcmp(formHdr + 8, "AIFF", 4) != 0) return false;

    bool foundComm = false;
    bool foundSsnd = false;
    aiffLittleEndian = false;
    aiffIsFloat = false;
    aiffDataOffset = 0;
    aiffFramesRead = 0;

    while (!std::feof(fp)) {
        uint8_t chunkHdr[8];
        if (std::fread(chunkHdr, 1, 8, fp) != 8) break;
        uint32_t chunkSize = readBE32(chunkHdr + 4);
        off_t chunkStart = ftello(fp);

        if (std::memcmp(chunkHdr, "COMM", 4) == 0 && chunkSize >= 18) {
            uint8_t comm[26] = {0};
            size_t toRead = std::min<size_t>(chunkSize, sizeof(comm));
            if (std::fread(comm, 1, toRead, fp) < 18) break;

            channels = static_cast<int>(readBE16(comm + 0));
            totalPcmFrames = static_cast<int64_t>(readBE32(comm + 2));
            bitDepth = static_cast<int>(readBE16(comm + 6));
            double sr = parseIeee80Extended(comm + 8);
            sourceSampleRate = static_cast<int>(std::llround(sr));
            outputSampleRate = sourceSampleRate;

            if (isAifc && toRead >= 22) {
                if (std::memcmp(comm + 18, "NONE", 4) == 0 || std::memcmp(comm + 18, "twos", 4) == 0) {
                    aiffLittleEndian = false;
                    aiffIsFloat = false;
                } else if (std::memcmp(comm + 18, "sowt", 4) == 0) {
                    aiffLittleEndian = true;
                    aiffIsFloat = false;
                } else if (std::memcmp(comm + 18, "fl32", 4) == 0 || std::memcmp(comm + 18, "FL32", 4) == 0) {
                    aiffLittleEndian = false;
                    aiffIsFloat = true;
                    bitDepth = 32;
                } else if (std::memcmp(comm + 18, "fl64", 4) == 0 || std::memcmp(comm + 18, "FL64", 4) == 0) {
                    aiffLittleEndian = false;
                    aiffIsFloat = true;
                    bitDepth = 64;
                } else {
                    LOGW("Unsupported AIFF-C compression type: %.4s", reinterpret_cast<const char*>(comm + 18));
                    return false;
                }
            }
            foundComm = true;
        } else if (std::memcmp(chunkHdr, "SSND", 4) == 0 && chunkSize >= 8) {
            uint8_t ssnd[8];
            if (std::fread(ssnd, 1, 8, fp) != 8) break;
            uint32_t offset = readBE32(ssnd);
            aiffDataOffset = static_cast<int64_t>(chunkStart) + 8 + static_cast<int64_t>(offset);
            foundSsnd = true;
        }

        if (foundComm && foundSsnd) break;
        fseeko(fp, chunkStart + static_cast<off_t>(chunkSize + (chunkSize & 1)), SEEK_SET);
    }

    if (!foundComm || !foundSsnd || channels <= 0 || sourceSampleRate <= 0 ||
        (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32 && bitDepth != 64)) {
        return false;
    }

    aiffBytesPerSample = bitDepth / 8;
    if (bitDepth == 64) bitDepth = 32; // Report 32-bit Hi-Res PCM to engine
    durationMs = (totalPcmFrames > 0 && sourceSampleRate > 0)
                     ? (totalPcmFrames * 1000LL) / sourceSampleRate
                     : 0;
    fseeko(fp, static_cast<off_t>(aiffDataOffset), SEEK_SET);

    LOGI("Opened native AIFF: %d Hz, %d-bit (%s%s), %d ch, %lld frames (%lld ms)",
         sourceSampleRate, bitDepth, aiffIsFloat ? "float" : "int",
         aiffLittleEndian ? " LE" : " BE", channels,
         static_cast<long long>(totalPcmFrames), static_cast<long long>(durationMs));
    return true;
}

bool NativeLosslessDecoder::initWavPack(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    char errMsg[128] = {0};
    int flags = OPEN_2CH_MAX | OPEN_NORMALIZE | OPEN_DSD_AS_PCM;
    WavpackContext* wpc = WavpackOpenFileInputEx64(&WAVPACK_READER_64, fp, nullptr, errMsg, flags, 0);
    if (!wpc) {
        LOGW("WavPack open failed: %s", errMsg);
        return false;
    }

    wavpackHandle = wpc;
    sourceSampleRate = static_cast<int>(WavpackGetSampleRate(wpc));
    outputSampleRate = sourceSampleRate;
    channels = WavpackGetReducedChannels(wpc);
    if (channels <= 0) channels = WavpackGetNumChannels(wpc);
    wavpackBitsPerSample = WavpackGetBitsPerSample(wpc);
    bitDepth = wavpackBitsPerSample;
    wavpackIsFloat = (WavpackGetMode(wpc) & MODE_FLOAT) != 0;
    totalPcmFrames = WavpackGetNumSamples64(wpc);
    durationMs = (sourceSampleRate > 0 && totalPcmFrames > 0)
                     ? (totalPcmFrames * 1000LL) / sourceSampleRate
                     : 0;

    LOGI("Opened native WavPack: %d Hz, %d-bit (%s), %d ch, %lld frames (%lld ms)",
         sourceSampleRate, bitDepth, wavpackIsFloat ? "float" : "int", channels,
         static_cast<long long>(totalPcmFrames), static_cast<long long>(durationMs));
    return (sourceSampleRate > 0 && channels > 0);
}

bool NativeLosslessDecoder::initVorbis(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    int error = 0;
    stb_vorbis* v = stb_vorbis_open_file(fp, 0, &error, nullptr);
    if (!v) {
        return false;
    }

    stb_vorbis_info info = stb_vorbis_get_info(v);
    if (info.sample_rate == 0 || info.channels <= 0) {
        stb_vorbis_close(v);
        return false;
    }

    vorbisHandle = v;
    sourceSampleRate = static_cast<int>(info.sample_rate);
    outputSampleRate = sourceSampleRate;
    channels = info.channels;
    bitDepth = 24; // Decoded directly from float32 -> 32-bit int without 16-bit truncation
    totalPcmFrames = static_cast<int64_t>(stb_vorbis_stream_length_in_samples(v));
    durationMs = (sourceSampleRate > 0 && totalPcmFrames > 0)
                     ? (totalPcmFrames * 1000LL) / sourceSampleRate
                     : 0;

    LOGI("Opened native OGG Vorbis: %d Hz, %d ch, %lld frames (%lld ms)",
         sourceSampleRate, channels, static_cast<long long>(totalPcmFrames),
         static_cast<long long>(durationMs));
    return true;
}

bool NativeLosslessDecoder::initMp3(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    drmp3* mp3 = new drmp3();
    if (!drmp3_init(mp3, drMp3Read, drMp3Seek, drMp3Tell, nullptr, fp, nullptr)) {
        delete mp3;
        return false;
    }

    if (mp3->sampleRate == 0 || mp3->channels == 0) {
        drmp3_uninit(mp3);
        delete mp3;
        return false;
    }

    mp3Handle = mp3;
    sourceSampleRate = static_cast<int>(mp3->sampleRate);
    outputSampleRate = sourceSampleRate;
    channels = static_cast<int>(mp3->channels);
    bitDepth = 24; // Decoded via float32 -> 32-bit int preserving full dynamic range
    totalPcmFrames = static_cast<int64_t>(drmp3_get_pcm_frame_count(mp3));
    durationMs = (sourceSampleRate > 0 && totalPcmFrames > 0)
                     ? (totalPcmFrames * 1000LL) / sourceSampleRate
                     : 0;

    LOGI("Opened native MP3: %d Hz, %d ch, %lld frames (%lld ms)",
         sourceSampleRate, channels, static_cast<long long>(totalPcmFrames),
         static_cast<long long>(durationMs));
    return true;
}

bool NativeLosslessDecoder::initDsf(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    uint8_t header[28];
    if (std::fread(header, 1, sizeof(header), fp) != sizeof(header)) return false;
    if (std::memcmp(header, "DSD ", 4) != 0) return false;
    uint64_t dsdChunkSize = readLE64(header + 4);
    if (dsdChunkSize < 28) return false;
    if (dsdChunkSize > 28) {
        fseeko(fp, static_cast<off_t>(dsdChunkSize), SEEK_SET);
    }

    uint8_t fmtChunk[52];
    if (std::fread(fmtChunk, 1, sizeof(fmtChunk), fp) != sizeof(fmtChunk)) return false;
    if (std::memcmp(fmtChunk, "fmt ", 4) != 0) return false;
    uint64_t fmtSize = readLE64(fmtChunk + 4);
    uint32_t formatId = readLE32(fmtChunk + 16);
    if (formatId != 0) {
        LOGW("Unsupported DSF formatId: %u", formatId);
        return false;
    }
    channels = static_cast<int>(readLE32(fmtChunk + 24));
    sourceSampleRate = static_cast<int>(readLE32(fmtChunk + 28)); // e.g. 2822400
    uint32_t bitsPerSample = readLE32(fmtChunk + 32);
    uint64_t sampleCount = readLE64(fmtChunk + 36);
    dsfBlockSizePerChannel = readLE32(fmtChunk + 44);
    if (dsfBlockSizePerChannel == 0 || dsfBlockSizePerChannel > 65536) {
        dsfBlockSizePerChannel = 4096;
    }
    dsfLsbFirst = (bitsPerSample == 1);

    if (fmtSize > 52) {
        fseeko(fp, static_cast<off_t>(fmtSize - 52), SEEK_CUR);
    }

    uint8_t dataHeader[12];
    if (std::fread(dataHeader, 1, sizeof(dataHeader), fp) != sizeof(dataHeader)) return false;
    if (std::memcmp(dataHeader, "data", 4) != 0) return false;
    uint64_t dataChunkSize = readLE64(dataHeader + 4);
    if (dataChunkSize <= 12) return false;

    dsdDataOffset = static_cast<int64_t>(ftello(fp));
    dsdDataSize = static_cast<int64_t>(dataChunkSize - 12);
    dsdBytesRead = 0;

    dsdRate = (sourceSampleRate > 0) ? (sourceSampleRate / 44100) : 64;
    outputSampleRate = (sourceSampleRate > 0) ? (sourceSampleRate / 16) : 176400; // DoP carrier rate
    bitDepth = 24;
    durationMs = (sourceSampleRate > 0 && sampleCount > 0)
                     ? static_cast<int64_t>((sampleCount * 1000ULL) / static_cast<uint64_t>(sourceSampleRate))
                     : 0;

    dsdBlockBuf.resize(static_cast<size_t>(dsfBlockSizePerChannel) * std::max(1, channels));
    dsdLeftBuf.resize(dsfBlockSizePerChannel);
    dsdRightBuf.resize(dsfBlockSizePerChannel);
    dsdBufferedBytes = 0;
    dsdBufferedOffset = 0;
    dopMarkerState = 0x05;
    dsdDecoder.reset();

    LOGI("Opened native DSF: DSD%d (%d Hz), %d ch, blockSize=%u, lsbFirst=%d, duration=%lld ms",
         dsdRate, sourceSampleRate, channels, dsfBlockSizePerChannel, dsfLsbFirst ? 1 : 0,
         static_cast<long long>(durationMs));
    return (channels > 0 && sourceSampleRate > 0);
}

bool NativeLosslessDecoder::initDff(FILE* fp) {
    fseeko(fp, 0, SEEK_SET);
    uint8_t frm8[16];
    if (std::fread(frm8, 1, sizeof(frm8), fp) != sizeof(frm8)) return false;
    if (std::memcmp(frm8, "FRM8", 4) != 0 || std::memcmp(frm8 + 12, "DSD ", 4) != 0) {
        return false;
    }

    channels = 2;
    sourceSampleRate = 2822400;
    bool foundDsdData = false;
    bool isCompressed = false;

    while (!std::feof(fp)) {
        uint8_t chunkHdr[12];
        if (std::fread(chunkHdr, 1, sizeof(chunkHdr), fp) != sizeof(chunkHdr)) break;
        uint64_t chunkSize = readBE64(chunkHdr + 4);
        off_t chunkStart = ftello(fp);

        if (std::memcmp(chunkHdr, "PROP", 4) == 0 && chunkSize >= 4) {
            uint8_t propType[4];
            if (std::fread(propType, 1, 4, fp) == 4 && std::memcmp(propType, "SND ", 4) == 0) {
                off_t propEnd = chunkStart + static_cast<off_t>(chunkSize);
                while (ftello(fp) + 12 <= propEnd) {
                    uint8_t subHdr[12];
                    if (std::fread(subHdr, 1, 12, fp) != 12) break;
                    uint64_t subSize = readBE64(subHdr + 4);
                    off_t subStart = ftello(fp);
                    if (std::memcmp(subHdr, "FS  ", 4) == 0 && subSize >= 4) {
                        uint8_t fsBuf[4];
                        if (std::fread(fsBuf, 1, 4, fp) == 4) {
                            sourceSampleRate = static_cast<int>(readBE32(fsBuf));
                        }
                    } else if (std::memcmp(subHdr, "CHNL", 4) == 0 && subSize >= 2) {
                        uint8_t chBuf[2];
                        if (std::fread(chBuf, 1, 2, fp) == 2) {
                            channels = static_cast<int>(readBE16(chBuf));
                        }
                    } else if (std::memcmp(subHdr, "CMPR", 4) == 0 && subSize >= 4) {
                        uint8_t cmpr[4];
                        if (std::fread(cmpr, 1, 4, fp) == 4 && std::memcmp(cmpr, "DSD ", 4) != 0) {
                            isCompressed = true;
                        }
                    }
                    fseeko(fp, subStart + static_cast<off_t>(subSize + (subSize & 1)), SEEK_SET);
                }
            }
        } else if (std::memcmp(chunkHdr, "DSD ", 4) == 0) {
            dsdDataOffset = static_cast<int64_t>(chunkStart);
            dsdDataSize = static_cast<int64_t>(chunkSize);
            foundDsdData = true;
            break;
        }

        fseeko(fp, chunkStart + static_cast<off_t>(chunkSize + (chunkSize & 1)), SEEK_SET);
    }

    if (!foundDsdData || isCompressed || channels <= 0 || sourceSampleRate <= 0) {
        LOGW("Unsupported or compressed DFF file (found=%d, compressed=%d)", foundDsdData ? 1 : 0, isCompressed ? 1 : 0);
        return false;
    }

    fseeko(fp, static_cast<off_t>(dsdDataOffset), SEEK_SET);
    dsdBytesRead = 0;
    dsfLsbFirst = false; // DSDIFF is MSB-first
    dsdRate = sourceSampleRate / 44100;
    outputSampleRate = sourceSampleRate / 16;
    bitDepth = 24;
    int64_t bytesPerCh = dsdDataSize / std::max(1, channels);
    durationMs = (bytesPerCh * 8LL * 1000LL) / sourceSampleRate;

    dsdLeftBuf.resize(4096);
    dsdRightBuf.resize(4096);
    dsdBlockBuf.resize(4096 * std::max(1, channels));
    dsdBufferedBytes = 0;
    dsdBufferedOffset = 0;
    dopMarkerState = 0x05;
    dsdDecoder.reset();

    LOGI("Opened native DFF: DSD%d (%d Hz), %d ch, dataSize=%lld, duration=%lld ms",
         dsdRate, sourceSampleRate, channels,
         static_cast<long long>(dsdDataSize), static_cast<long long>(durationMs));
    return true;
}

int NativeLosslessDecoder::readFramesInt32(int32_t* outStereoInt32, int maxFrames, DsdMode dsdMode) {
    std::lock_guard<std::recursive_mutex> lock(decoderMutex);
    if (!outStereoInt32 || maxFrames <= 0 || formatType == FormatType::NONE) return 0;

    // PRIMARY FAST PATH: FLAC (100% untouched)
    if (formatType == FormatType::FLAC && flacHandle) {
        size_t rawSamplesNeeded = static_cast<size_t>(maxFrames) * static_cast<size_t>(channels);
        if (scratchInt32.size() < rawSamplesNeeded) {
            scratchInt32.resize(rawSamplesNeeded);
        }
        drflac_uint64 framesRead = drflac_read_pcm_frames_s32(
            reinterpret_cast<drflac*>(flacHandle), static_cast<drflac_uint64>(maxFrames), scratchInt32.data());
        int n = static_cast<int>(framesRead);
        if (channels == 2) {
            std::memcpy(outStereoInt32, scratchInt32.data(), static_cast<size_t>(n) * 2 * sizeof(int32_t));
        } else if (channels == 1) {
            for (int i = 0; i < n; ++i) {
                int32_t s = scratchInt32[i];
                outStereoInt32[i * 2] = s;
                outStereoInt32[i * 2 + 1] = s;
            }
        } else {
            for (int i = 0; i < n; ++i) {
                outStereoInt32[i * 2] = scratchInt32[i * channels];
                outStereoInt32[i * 2 + 1] = scratchInt32[i * channels + 1];
            }
        }
        return n;
    }

    // PRIMARY FAST PATH: WAV (100% untouched)
    if (formatType == FormatType::WAV && wavHandle) {
        size_t rawSamplesNeeded = static_cast<size_t>(maxFrames) * static_cast<size_t>(channels);
        if (scratchInt32.size() < rawSamplesNeeded) {
            scratchInt32.resize(rawSamplesNeeded);
        }
        drwav_uint64 framesRead = drwav_read_pcm_frames_s32(
            reinterpret_cast<drwav*>(wavHandle), static_cast<drwav_uint64>(maxFrames), scratchInt32.data());
        int n = static_cast<int>(framesRead);
        if (channels == 2) {
            std::memcpy(outStereoInt32, scratchInt32.data(), static_cast<size_t>(n) * 2 * sizeof(int32_t));
        } else if (channels == 1) {
            for (int i = 0; i < n; ++i) {
                int32_t s = scratchInt32[i];
                outStereoInt32[i * 2] = s;
                outStereoInt32[i * 2 + 1] = s;
            }
        } else {
            for (int i = 0; i < n; ++i) {
                outStereoInt32[i * 2] = scratchInt32[i * channels];
                outStereoInt32[i * 2 + 1] = scratchInt32[i * channels + 1];
            }
        }
        return n;
    }

    // PRIMARY FAST PATH: DSD (DSF / DFF)
    if (formatType == FormatType::DSF || formatType == FormatType::DFF) {
        return readDsdFramesInt32(outStereoInt32, maxFrames, dsdMode);
    }

    // EXTENDED NATIVE HI-RES FORMATS
    switch (formatType) {
        case FormatType::AIFF:
            return readAiffFramesInt32(outStereoInt32, maxFrames);
        case FormatType::ALAC:
            return alacDecoder.readFramesInt32(outStereoInt32, maxFrames);
        case FormatType::WAVPACK:
            return readWavPackFramesInt32(outStereoInt32, maxFrames);
        case FormatType::TTA:
            return ttaDecoder.readFramesInt32(outStereoInt32, maxFrames);
        case FormatType::VORBIS:
            return readVorbisFramesInt32(outStereoInt32, maxFrames);
        case FormatType::MP3:
            return readMp3FramesInt32(outStereoInt32, maxFrames);
        default:
            return 0;
    }
}

int NativeLosslessDecoder::readAiffFramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!filePtr || aiffFramesRead >= totalPcmFrames) return 0;
    int64_t remaining = totalPcmFrames - aiffFramesRead;
    int toReadFrames = static_cast<int>(std::min<int64_t>(remaining, maxFrames));
    if (toReadFrames <= 0) return 0;

    size_t frameStride = static_cast<size_t>(channels) * static_cast<size_t>(aiffBytesPerSample);
    size_t bytesNeeded = static_cast<size_t>(toReadFrames) * frameStride;
    if (aiffReadBuf.size() < bytesNeeded) aiffReadBuf.resize(bytesNeeded);

    size_t gotBytes = std::fread(aiffReadBuf.data(), 1, bytesNeeded, filePtr);
    int gotFrames = static_cast<int>(gotBytes / frameStride);
    if (gotFrames <= 0) return 0;
    aiffFramesRead += gotFrames;

    auto decodeSample = [&](const uint8_t* p) -> int32_t {
        if (aiffIsFloat) {
            if (aiffBytesPerSample == 4) {
                uint32_t bits = aiffLittleEndian ? readLE32(p) : readBE32(p);
                float fv = 0.0f;
                std::memcpy(&fv, &bits, sizeof(float));
                double clamped = std::clamp(static_cast<double>(fv), -1.0, 1.0);
                return static_cast<int32_t>(std::llround(clamped * 2147483647.0));
            } else {
                uint64_t bits = aiffLittleEndian ? readLE64(p) : readBE64(p);
                double dv = 0.0;
                std::memcpy(&dv, &bits, sizeof(double));
                double clamped = std::clamp(dv, -1.0, 1.0);
                return static_cast<int32_t>(std::llround(clamped * 2147483647.0));
            }
        }
        if (aiffBytesPerSample == 2) {
            int16_t s = aiffLittleEndian
                            ? static_cast<int16_t>(static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8))
                            : static_cast<int16_t>(readBE16(p));
            return static_cast<int32_t>(s) << 16;
        } else if (aiffBytesPerSample == 3) {
            if (aiffLittleEndian) {
                return (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 24) |
                       (static_cast<int32_t>(p[1]) << 16) |
                       (static_cast<int32_t>(p[0]) << 8);
            } else {
                return (static_cast<int32_t>(static_cast<int8_t>(p[0])) << 24) |
                       (static_cast<int32_t>(p[1]) << 16) |
                       (static_cast<int32_t>(p[2]) << 8);
            }
        } else if (aiffBytesPerSample == 4) {
            return static_cast<int32_t>(aiffLittleEndian ? readLE32(p) : readBE32(p));
        } else {
            return static_cast<int32_t>(static_cast<int8_t>(p[0])) << 24;
        }
    };

    const uint8_t* base = aiffReadBuf.data();
    for (int f = 0; f < gotFrames; ++f) {
        const uint8_t* fp = base + static_cast<size_t>(f) * frameStride;
        int32_t l = decodeSample(fp);
        int32_t r = (channels >= 2) ? decodeSample(fp + aiffBytesPerSample) : l;
        outStereoInt32[f * 2] = l;
        outStereoInt32[f * 2 + 1] = r;
    }
    return gotFrames;
}

int NativeLosslessDecoder::readWavPackFramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!wavpackHandle) return 0;
    WavpackContext* wpc = reinterpret_cast<WavpackContext*>(wavpackHandle);
    size_t rawSamples = static_cast<size_t>(maxFrames) * static_cast<size_t>(channels);
    if (scratchInt32.size() < rawSamples) scratchInt32.resize(rawSamples);

    uint32_t framesRead = WavpackUnpackSamples(wpc, scratchInt32.data(), static_cast<uint32_t>(maxFrames));
    int n = static_cast<int>(framesRead);
    if (n <= 0) return 0;

    if (wavpackIsFloat) {
        const float* fSrc = reinterpret_cast<const float*>(scratchInt32.data());
        for (int i = 0; i < n; ++i) {
            double l = std::clamp(static_cast<double>(fSrc[i * channels]), -1.0, 1.0);
            double r = (channels >= 2) ? std::clamp(static_cast<double>(fSrc[i * channels + 1]), -1.0, 1.0) : l;
            outStereoInt32[i * 2] = static_cast<int32_t>(std::llround(l * 2147483647.0));
            outStereoInt32[i * 2 + 1] = static_cast<int32_t>(std::llround(r * 2147483647.0));
        }
    } else {
        int shift = std::clamp(32 - wavpackBitsPerSample, 0, 24);
        for (int i = 0; i < n; ++i) {
            int32_t l = static_cast<int32_t>(static_cast<uint32_t>(scratchInt32[i * channels]) << shift);
            int32_t r = (channels >= 2)
                            ? static_cast<int32_t>(static_cast<uint32_t>(scratchInt32[i * channels + 1]) << shift)
                            : l;
            outStereoInt32[i * 2] = l;
            outStereoInt32[i * 2 + 1] = r;
        }
    }
    return n;
}

int NativeLosslessDecoder::readVorbisFramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!vorbisHandle) return 0;
    stb_vorbis* v = reinterpret_cast<stb_vorbis*>(vorbisHandle);
    size_t samplesNeeded = static_cast<size_t>(maxFrames) * 2;
    if (scratchFloat.size() < samplesNeeded) scratchFloat.resize(samplesNeeded);

    int framesRead = stb_vorbis_get_samples_float_interleaved(v, 2, scratchFloat.data(), static_cast<int>(samplesNeeded));
    for (int i = 0; i < framesRead * 2; ++i) {
        double clamped = std::clamp(static_cast<double>(scratchFloat[i]), -1.0, 1.0);
        outStereoInt32[i] = static_cast<int32_t>(std::llround(clamped * 2147483647.0));
    }
    return framesRead;
}

int NativeLosslessDecoder::readMp3FramesInt32(int32_t* outStereoInt32, int maxFrames) {
    if (!mp3Handle) return 0;
    drmp3* mp3 = reinterpret_cast<drmp3*>(mp3Handle);
    size_t rawSamplesNeeded = static_cast<size_t>(maxFrames) * static_cast<size_t>(channels);
    if (scratchFloat.size() < rawSamplesNeeded) scratchFloat.resize(rawSamplesNeeded);

    drmp3_uint64 framesRead = drmp3_read_pcm_frames_f32(mp3, static_cast<drmp3_uint64>(maxFrames), scratchFloat.data());
    int n = static_cast<int>(framesRead);
    for (int i = 0; i < n; ++i) {
        double l = std::clamp(static_cast<double>(scratchFloat[i * channels]), -1.0, 1.0);
        double r = (channels >= 2) ? std::clamp(static_cast<double>(scratchFloat[i * channels + 1]), -1.0, 1.0) : l;
        outStereoInt32[i * 2] = static_cast<int32_t>(std::llround(l * 2147483647.0));
        outStereoInt32[i * 2 + 1] = static_cast<int32_t>(std::llround(r * 2147483647.0));
    }
    return n;
}

int NativeLosslessDecoder::readDsdFramesInt32(int32_t* outStereoInt32, int maxFrames, DsdMode dsdMode) {
    if (!filePtr || dsdBytesRead >= dsdDataSize) return 0;

    // Refill planar L/R DSD byte buffers when empty
    if (dsdBufferedOffset >= dsdBufferedBytes) {
        if (formatType == FormatType::DSF) {
            size_t blockTotalBytes = static_cast<size_t>(dsfBlockSizePerChannel) * static_cast<size_t>(channels);
            int64_t remaining = dsdDataSize - dsdBytesRead;
            if (remaining < static_cast<int64_t>(blockTotalBytes)) return 0;

            size_t got = std::fread(dsdBlockBuf.data(), 1, blockTotalBytes, filePtr);
            if (got < blockTotalBytes) return 0;
            dsdBytesRead += static_cast<int64_t>(got);

            const uint8_t* srcL = dsdBlockBuf.data();
            const uint8_t* srcR = (channels >= 2) ? (dsdBlockBuf.data() + dsfBlockSizePerChannel) : srcL;
            for (size_t i = 0; i < dsfBlockSizePerChannel; ++i) {
                dsdLeftBuf[i] = dsfLsbFirst ? BIT_REV.table[srcL[i]] : srcL[i];
                dsdRightBuf[i] = dsfLsbFirst ? BIT_REV.table[srcR[i]] : srcR[i];
            }
            dsdBufferedBytes = dsfBlockSizePerChannel;
            dsdBufferedOffset = 0;
        } else {
            // DFF byte-interleaved [L0, R0, L1, R1, ...]
            size_t bytesPerChWanted = std::min<size_t>(dsdLeftBuf.size(), static_cast<size_t>((dsdDataSize - dsdBytesRead) / std::max(1, channels)));
            bytesPerChWanted &= ~3ULL; // Keep multiple of 4 bytes for DoP / Native DSD alignment
            if (bytesPerChWanted < 4) return 0;

            size_t totalToRead = bytesPerChWanted * static_cast<size_t>(channels);
            if (dsdBlockBuf.size() < totalToRead) dsdBlockBuf.resize(totalToRead);
            size_t got = std::fread(dsdBlockBuf.data(), 1, totalToRead, filePtr);
            size_t gotPerCh = (got / static_cast<size_t>(channels)) & ~3ULL;
            if (gotPerCh < 2) return 0;
            dsdBytesRead += static_cast<int64_t>(got);

            for (size_t i = 0; i < gotPerCh; ++i) {
                dsdLeftBuf[i] = dsdBlockBuf[i * channels];
                dsdRightBuf[i] = (channels >= 2) ? dsdBlockBuf[i * channels + 1] : dsdLeftBuf[i];
            }
            dsdBufferedBytes = gotPerCh;
            dsdBufferedOffset = 0;
        }
    }

    size_t availBytes = dsdBufferedBytes - dsdBufferedOffset;
    const uint8_t* ptrL = dsdLeftBuf.data() + dsdBufferedOffset;
    const uint8_t* ptrR = dsdRightBuf.data() + dsdBufferedOffset;

    if (dsdMode == DsdMode::DOP) {
        size_t bytesToConsume = std::min<size_t>(availBytes, static_cast<size_t>(maxFrames) * 2) & ~1ULL;
        if (bytesToConsume < 2) {
            dsdBufferedOffset = dsdBufferedBytes;
            return 0;
        }
        int produced = DsdDecoder::packDoP(ptrL, ptrR, static_cast<int>(bytesToConsume), outStereoInt32, dopMarkerState);
        dsdBufferedOffset += bytesToConsume;
        return produced;
    } else if (dsdMode == DsdMode::NATIVE_DSD) {
        size_t bytesToConsume = std::min<size_t>(availBytes, static_cast<size_t>(maxFrames) * 4) & ~3ULL;
        if (bytesToConsume < 4) {
            dsdBufferedOffset = dsdBufferedBytes;
            return 0;
        }
        int frames = static_cast<int>(bytesToConsume / 4);
        for (int i = 0; i < frames; ++i) {
            outStereoInt32[i * 2] = static_cast<int32_t>(
                (static_cast<uint32_t>(ptrL[i * 4 + 0]) << 24) |
                (static_cast<uint32_t>(ptrL[i * 4 + 1]) << 16) |
                (static_cast<uint32_t>(ptrL[i * 4 + 2]) << 8) |
                static_cast<uint32_t>(ptrL[i * 4 + 3]));
            outStereoInt32[i * 2 + 1] = static_cast<int32_t>(
                (static_cast<uint32_t>(ptrR[i * 4 + 0]) << 24) |
                (static_cast<uint32_t>(ptrR[i * 4 + 1]) << 16) |
                (static_cast<uint32_t>(ptrR[i * 4 + 2]) << 8) |
                static_cast<uint32_t>(ptrR[i * 4 + 3]));
        }
        dsdBufferedOffset += bytesToConsume;
        return frames;
    } else {
        int decimation = std::max(16, (sourceSampleRate + 88200) / 176400);
        decimation = (decimation / 8) * 8;
        if (decimation < 16) decimation = 16;

        size_t bytesPerOutFrame = static_cast<size_t>(decimation / 8);
        size_t maxConsumableBytes = static_cast<size_t>(maxFrames) * bytesPerOutFrame;
        size_t bytesToConsume = std::min(availBytes, maxConsumableBytes);
        bytesToConsume = (bytesToConsume / bytesPerOutFrame) * bytesPerOutFrame;
        if (bytesToConsume < bytesPerOutFrame) {
            dsdBufferedOffset = dsdBufferedBytes;
            return 0;
        }

        size_t expectedFrames = (bytesToConsume * 8) / static_cast<size_t>(decimation);
        if (dsdPcmFloatBuf.size() < expectedFrames * 2) {
            dsdPcmFloatBuf.resize(expectedFrames * 2);
        }
        int produced = dsdDecoder.convertDsdToPcm(ptrL, ptrR, static_cast<int>(bytesToConsume),
                                                  dsdPcmFloatBuf.data(), decimation);
        for (int i = 0; i < produced * 2; ++i) {
            float clamped = std::clamp(dsdPcmFloatBuf[i], -1.0f, 1.0f);
            outStereoInt32[i] = static_cast<int32_t>(std::llround(static_cast<double>(clamped) * 2147483647.0));
        }
        dsdBufferedOffset += bytesToConsume;
        return produced;
    }
}

bool NativeLosslessDecoder::seekMs(int64_t positionMs) {
    std::lock_guard<std::recursive_mutex> lock(decoderMutex);
    if (positionMs < 0) positionMs = 0;

    if (formatType == FormatType::FLAC && flacHandle) {
        drflac_uint64 targetFrame = static_cast<drflac_uint64>((positionMs * sourceSampleRate) / 1000LL);
        if (totalPcmFrames > 0 && targetFrame > static_cast<drflac_uint64>(totalPcmFrames)) {
            targetFrame = static_cast<drflac_uint64>(totalPcmFrames);
        }
        return drflac_seek_to_pcm_frame(reinterpret_cast<drflac*>(flacHandle), targetFrame) == DRFLAC_TRUE;
    }

    if (formatType == FormatType::WAV && wavHandle) {
        drwav_uint64 targetFrame = static_cast<drwav_uint64>((positionMs * sourceSampleRate) / 1000LL);
        if (totalPcmFrames > 0 && targetFrame > static_cast<drwav_uint64>(totalPcmFrames)) {
            targetFrame = static_cast<drwav_uint64>(totalPcmFrames);
        }
        return drwav_seek_to_pcm_frame(reinterpret_cast<drwav*>(wavHandle), targetFrame) == DRWAV_TRUE;
    }

    if ((formatType == FormatType::DSF || formatType == FormatType::DFF) && filePtr) {
        int64_t targetBitsPerCh = (positionMs * sourceSampleRate) / 1000LL;
        int64_t targetBytesPerCh = targetBitsPerCh / 8;
        int64_t targetOffset = 0;

        if (formatType == FormatType::DSF) {
            int64_t blockIndex = targetBytesPerCh / static_cast<int64_t>(dsfBlockSizePerChannel);
            targetOffset = blockIndex * static_cast<int64_t>(dsfBlockSizePerChannel) * std::max(1, channels);
        } else {
            targetOffset = (targetBytesPerCh & ~3LL) * std::max(1, channels);
        }
        targetOffset = std::clamp<int64_t>(targetOffset, 0, dsdDataSize);
        if (fseeko(filePtr, static_cast<off_t>(dsdDataOffset + targetOffset), SEEK_SET) == 0) {
            dsdBytesRead = targetOffset;
            dsdBufferedBytes = 0;
            dsdBufferedOffset = 0;
            dsdDecoder.reset();
            return true;
        }
        return false;
    }

    if (formatType == FormatType::AIFF && filePtr) {
        int64_t targetFrame = (positionMs * sourceSampleRate) / 1000LL;
        targetFrame = std::clamp<int64_t>(targetFrame, 0, totalPcmFrames);
        int64_t byteOffset = targetFrame * static_cast<int64_t>(channels) * static_cast<int64_t>(aiffBytesPerSample);
        if (fseeko(filePtr, static_cast<off_t>(aiffDataOffset + byteOffset), SEEK_SET) == 0) {
            aiffFramesRead = targetFrame;
            return true;
        }
        return false;
    }

    if (formatType == FormatType::ALAC) {
        return alacDecoder.seekMs(positionMs);
    }

    if (formatType == FormatType::WAVPACK && wavpackHandle) {
        int64_t targetFrame = (positionMs * sourceSampleRate) / 1000LL;
        if (totalPcmFrames > 0 && targetFrame > totalPcmFrames) targetFrame = totalPcmFrames;
        return WavpackSeekSample64(reinterpret_cast<WavpackContext*>(wavpackHandle), targetFrame) != 0;
    }

    if (formatType == FormatType::TTA) {
        return ttaDecoder.seekMs(positionMs);
    }

    if (formatType == FormatType::VORBIS && vorbisHandle) {
        unsigned int targetFrame = static_cast<unsigned int>((positionMs * sourceSampleRate) / 1000LL);
        return stb_vorbis_seek(reinterpret_cast<stb_vorbis*>(vorbisHandle), targetFrame) != 0;
    }

    if (formatType == FormatType::MP3 && mp3Handle) {
        drmp3_uint64 targetFrame = static_cast<drmp3_uint64>((positionMs * sourceSampleRate) / 1000LL);
        return drmp3_seek_to_pcm_frame(reinterpret_cast<drmp3*>(mp3Handle), targetFrame) == DRMP3_TRUE;
    }

    return false;
}
