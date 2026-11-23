#include "sinc_resampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

SincResampler::SincResampler() {
    filterTable.resize(PHASES * TAPS, 0.0f);
    reset();
    buildFilterTable();
}

double SincResampler::besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    double halfX = x * 0.5;
    for (int k = 1; k <= 25; ++k) {
        double ratio = halfX / static_cast<double>(k);
        term *= ratio * ratio;
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

void SincResampler::configure(int srcRate, int dstRate) {
    int s = (srcRate > 0) ? srcRate : 44100;
    int d = (dstRate > 0) ? dstRate : 44100;
    if (s == sourceRate && d == targetRate && !filterTable.empty()) {
        return;
    }
    sourceRate = s;
    targetRate = d;
    step = static_cast<double>(sourceRate) / static_cast<double>(targetRate);
    buildFilterTable();
    reset();
}

void SincResampler::reset() {
    std::memset(historyL, 0, sizeof(historyL));
    std::memset(historyR, 0, sizeof(historyR));
    cursor = static_cast<double>(HALF_TAPS);
}

void SincResampler::buildFilterTable() {
    constexpr double BETA = 8.5;
    const double invI0Beta = 1.0 / besselI0(BETA);

    // Adaptive anti-aliasing cutoff (normalized to sample rate, Nyquist = 0.5)
    double ratio = static_cast<double>(targetRate) / static_cast<double>(sourceRate);
    double cutoff = (ratio < 1.0) ? (0.465 * ratio) : 0.475;
    cutoff = std::clamp(cutoff, 0.01, 0.48);

    const double halfWidth = static_cast<double>(HALF_TAPS);

    for (int p = 0; p < PHASES; ++p) {
        double frac = static_cast<double>(p) / static_cast<double>(PHASES);
        double row[TAPS];
        double sum = 0.0;

        for (int t = 0; t < TAPS; ++t) {
            double x = static_cast<double>(t - (HALF_TAPS - 1)) - frac;
            double sincVal;
            if (std::abs(x) < 1e-9) {
                sincVal = 2.0 * cutoff;
            } else {
                sincVal = std::sin(2.0 * M_PI * cutoff * x) / (M_PI * x);
            }

            double normPos = x / halfWidth;
            double win = 0.0;
            if (std::abs(normPos) <= 1.0) {
                win = besselI0(BETA * std::sqrt(1.0 - normPos * normPos)) * invI0Beta;
            }

            row[t] = sincVal * win;
            sum += row[t];
        }

        double invSum = (std::abs(sum) > 1e-12) ? (1.0 / sum) : 1.0;
        float* dstRow = filterTable.data() + (p * TAPS);
        for (int t = 0; t < TAPS; ++t) {
            dstRow[t] = static_cast<float>(row[t] * invSum);
        }
    }
}

int SincResampler::processInterleaved(const float* in, int inFrames, std::vector<float>& out) {
    if (!in || inFrames <= 0) return 0;

    if (sourceRate == targetRate) {
        out.resize(static_cast<size_t>(inFrames) * 2);
        std::memcpy(out.data(), in, static_cast<size_t>(inFrames) * 2 * sizeof(float));
        return inFrames;
    }

    const int totalLen = TAPS + inFrames;
    if (static_cast<int>(workL.size()) < totalLen) {
        workL.resize(totalLen);
        workR.resize(totalLen);
    }

    // Prepend history from previous block for seamless continuity
    std::memcpy(workL.data(), historyL, TAPS * sizeof(float));
    std::memcpy(workR.data(), historyR, TAPS * sizeof(float));

    for (int i = 0; i < inFrames; ++i) {
        workL[TAPS + i] = in[i * 2];
        workR[TAPS + i] = in[i * 2 + 1];
    }

    const int estimatedOut = static_cast<int>(std::ceil(inFrames / step)) + 8;
    if (static_cast<int>(out.size()) < estimatedOut * 2) {
        out.resize(static_cast<size_t>(estimatedOut) * 2);
    }

    const double maxCursor = static_cast<double>(totalLen - 1 - HALF_TAPS);
    int outFrames = 0;

    while (cursor <= maxCursor) {
        int base = static_cast<int>(cursor);
        double frac = cursor - static_cast<double>(base);
        int phaseIdx = static_cast<int>(frac * PHASES);
        if (phaseIdx < 0) phaseIdx = 0;
        else if (phaseIdx >= PHASES) phaseIdx = PHASES - 1;

        const int start = base - (HALF_TAPS - 1);
        const float* coeffs = filterTable.data() + (phaseIdx * TAPS);
        const float* srcL = workL.data() + start;
        const float* srcR = workR.data() + start;

        double accL = 0.0;
        double accR = 0.0;
        for (int t = 0; t < TAPS; ++t) {
            double c = static_cast<double>(coeffs[t]);
            accL += static_cast<double>(srcL[t]) * c;
            accR += static_cast<double>(srcR[t]) * c;
        }

        if (static_cast<size_t>((outFrames + 1) * 2) > out.size()) {
            out.resize(out.size() * 2);
        }
        out[outFrames * 2] = static_cast<float>(accL);
        out[outFrames * 2 + 1] = static_cast<float>(accR);
        outFrames++;

        cursor += step;
    }

    // Shift cursor & retain last TAPS frames as history for next block
    cursor -= static_cast<double>(inFrames);
    if (cursor < static_cast<double>(HALF_TAPS - 1)) {
        cursor = static_cast<double>(HALF_TAPS);
    }
    std::memcpy(historyL, workL.data() + inFrames, TAPS * sizeof(float));
    std::memcpy(historyR, workR.data() + inFrames, TAPS * sizeof(float));

    return outFrames;
}

