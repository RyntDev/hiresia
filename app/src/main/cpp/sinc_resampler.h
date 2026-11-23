#pragma once

#include <vector>
#include <cstdint>

/**
 * High-precision stateful polyphase Kaiser-windowed Sinc Resampler (32-tap, 1024 phases).
 * Features:
 * - Adaptive anti-aliasing low-pass cutoff for clean downsampling (e.g. 192kHz -> 48kHz)
 * - Kaiser window (beta = 8.5, >90 dB stopband attenuation)
 * - Continuous sub-sample phase & stereo sample history across block boundaries (zero clicks)
 * - Precomputed polyphase filter bank for real-time mobile ARM64 performance
 */
class SincResampler {
public:
    SincResampler();

    void configure(int srcRate, int dstRate);
    void reset();

    // Resamples interleaved stereo float audio. Returns number of output frames written.
    int processInterleaved(const float* in, int inFrames, std::vector<float>& out);

    int getSrcRate() const { return sourceRate; }
    int getDstRate() const { return targetRate; }

private:
    static constexpr int TAPS = 32;
    static constexpr int HALF_TAPS = TAPS / 2;
    static constexpr int PHASES = 1024;

    static double besselI0(double x);
    void buildFilterTable();

    int sourceRate = 44100;
    int targetRate = 44100;
    double step = 1.0;
    double cursor = static_cast<double>(HALF_TAPS);

    std::vector<float> filterTable; // [PHASES * TAPS]
    float historyL[TAPS];
    float historyR[TAPS];
    std::vector<float> workL;
    std::vector<float> workR;
};

