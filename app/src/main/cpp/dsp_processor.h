#pragma once

#include "audio_types.h"
#include <algorithm>
#include <cmath>
#include <mutex>

enum class EqFilterType : int {
    PEAKING = 0,
    LOW_SHELF = 1,
    HIGH_SHELF = 2,
    LOW_PASS = 3,
    HIGH_PASS = 4
};

class BiquadFilter {
public:
    BiquadFilter();
    void setSampleRate(double sampleRate);
    void setBypass(bool snap = false);
    void setPeakingEQ(double sampleRate, double centerFreq, double gainDB, double q = 1.41421356, bool snap = false);
    void setLowShelf(double sampleRate, double cutoffFreq, double gainDB, double q = 0.70710678, bool snap = false);
    void setHighShelf(double sampleRate, double cutoffFreq, double gainDB, double q = 0.70710678, bool snap = false);
    void setLowPass(double sampleRate, double cutoffFreq, double q = 0.70710678, bool snap = false);
    void setHighPass(double sampleRate, double cutoffFreq, double q = 0.70710678, bool snap = false);
    double getMagnitudeDb(double freqHz, double sampleRate) const;
    void reset();

    inline double process(double in) {
        if (std::isnan(in) || std::isinf(in)) in = 0.0;
        if (needsSmooth) {
            b0 += (tb0 - b0) * coeffSmooth;
            b1 += (tb1 - b1) * coeffSmooth;
            b2 += (tb2 - b2) * coeffSmooth;
            a1 += (ta1 - a1) * coeffSmooth;
            a2 += (ta2 - a2) * coeffSmooth;
            double maxDiff = std::max({
                std::abs(tb0 - b0), std::abs(tb1 - b1), std::abs(tb2 - b2),
                std::abs(ta1 - a1), std::abs(ta2 - a2)
            });
            if (maxDiff < 1.0e-9) {
                b0 = tb0; b1 = tb1; b2 = tb2;
                a1 = ta1; a2 = ta2;
                needsSmooth = false;
            }
        }
        // Transposed Direct Form II: Lowest round-off noise (< -280 dBFS) & highest dynamic range
        double out = b0 * in + s1;
        if (std::isnan(out) || std::isinf(out)) {
            out = 0.0;
            s1 = s2 = 0.0;
        }
        s1 = b1 * in - a1 * out + s2;
        s2 = b2 * in - a2 * out;
        // Flush subnormal denormals to prevent hardware pipeline stalls
        if (std::abs(s1) < 1.0e-20) s1 = 0.0;
        if (std::abs(s2) < 1.0e-20) s2 = 0.0;
        return out;
    }

private:
    void applyTargetCoeffs(double nb0, double nb1, double nb2, double na1, double na2, bool snap);

    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double tb0 = 1.0, tb1 = 0.0, tb2 = 0.0;
    double ta1 = 0.0, ta2 = 0.0;
    double coeffSmooth = 0.002;
    bool needsSmooth = false;
    double s1 = 0.0, s2 = 0.0; // TDF-II internal delay registers
};

class CrossfeedFilter {
public:
    CrossfeedFilter();
    void init(float sampleRate);
    void setLevel(int level); // 0 (off) to 10 (max)
    void process(float& left, float& right);
    void reset();

private:
    void updateCoefficients();

    float sampleRate = 44100.0f;
    int level = 5;

    // libbs2b / Bauer stereophonic-to-binaural DSP coefficients (double precision)
    double a0_lo = 0.0;
    double b1_lo = 0.0;
    double a0_hi = 0.0;
    double a1_hi = 0.0;
    double b1_hi = 0.0;
    double gain = 1.0;

    // Filter states for Left [0] and Right [1]
    double asis[2] = {0.0, 0.0};
    double lo[2] = {0.0, 0.0};
    double hi[2] = {0.0, 0.0};
};

class DspProcessor {
public:
    DspProcessor();
    void init(int sampleRate, int channels);

    // 10-Band 64-bit Parametric EQ (backward compatible with Graphic EQ calls)
    void setUseGraphicEQ(bool enable);
    bool getUseGraphicEQ() const { return useGraphicEQ; }
    void setBandGain(int band, float gainDB);
    float getBandGain(int band) const;
    void setBandParam(int band, float freqHz, float gainDB, float q, int filterType, bool enabled);
    float getBandFreq(int band) const;
    float getBandQ(int band) const;
    int getBandType(int band) const;
    bool getBandEnabled(int band) const;
    void setEQPreamp(float preampDb);
    float getEQPreamp() const { return eqPreampDb; }
    void resetEQToDefault();
    void setEQAutoGain(bool enable);
    bool getEQAutoGain() const { return useAutoGain; }

    // Crossfeed
    void setCrossFeedState(bool enable);
    bool getCrossFeedState() const { return useCrossfeed; }
    void setCrossFeedLevel(int level);
    int getCrossFeedLevel() const { return crossfeedLevel; }

    // Balance & Mono
    void setUseBalance(bool enable);
    bool getUseBalance() const { return useBalance; }
    void setBalance(float balance); // -1.0 (left) to 1.0 (right)
    float getBalance() const { return balance; }
    void setUseMono(bool enable);
    bool getUseMono() const { return useMono; }

    // ReplayGain
    void setReplayGainMode(ReplayGainMode mode);
    void setReplayGainValues(float trackGain, float albumGain, float preampDb, float peak = 1.0f, bool preventClipping = true);
    void setReplayGainPreventClipping(bool enable);
    ReplayGainMode getReplayGainMode() const { return replayGainMode; }

    // Master Software Volume & Soft Transport Ramp
    void setSoftwareVolume(float volume); // 0.0f to 1.0f
    float getSoftwareVolume() const { return softwareVolume; }
    void triggerSoftFadeIn();

    // Check if any DSP stage (EQ, AutoGain, Balance, Crossfeed, Mono, Volume/ReplayGain) is active
    bool hasActiveDsp() const;

    // Process interleaved audio buffer in-place
    void processInterleaved(float* buffer, int numFrames);

private:
    void updateFilters(bool snap = false);
    void updateSingleBand(int band, bool snap = false);
    void calculateAutoGain();
    void updateReplayGainTarget();

    int sampleRate = 44100;
    int channels = 2;
    mutable std::recursive_mutex dspMutex;

    // 10-Band Parametric EQ
    bool useGraphicEQ = false;
    bool useAutoGain = false;
    static constexpr int NUM_BANDS = 10;
    static const float BAND_FREQUENCIES[NUM_BANDS];
    float bandGains[NUM_BANDS];
    float bandFreqs[NUM_BANDS];
    float bandQs[NUM_BANDS];
    EqFilterType bandTypes[NUM_BANDS];
    bool bandEnabled[NUM_BANDS];
    float eqPreampDb = 0.0f;
    double targetEqPreampLin = 1.0;
    double currentEqPreampLin = 1.0;
    double currentAutoGainLin = 1.0;
    BiquadFilter filtersL[NUM_BANDS];
    BiquadFilter filtersR[NUM_BANDS];
    float autoGainFactor = 1.0f;
    float dynamicLimiterGain = 1.0f;

    // Crossfeed
    bool useCrossfeed = false;
    int crossfeedLevel = 5;
    CrossfeedFilter crossfeed;

    // Balance & Mono
    bool useBalance = false;
    float balance = 0.0f;
    float balanceGainL = 1.0f;
    float balanceGainR = 1.0f;
    double currentBalanceGainL = 1.0;
    double currentBalanceGainR = 1.0;
    bool useMono = false;

    // ReplayGain
    ReplayGainMode replayGainMode = ReplayGainMode::OFF;
    float trackGain = 0.0f;
    float albumGain = 0.0f;
    float preampDb = 0.0f;
    float replayGainPeak = 1.0f;
    bool replayGainPreventClipping = true;
    float replayGainFactor = 1.0f;
    double targetReplayGainLin = 1.0;
    double currentReplayGainLin = 1.0;

    // Volume (Default initial safe volume: 20%)
    float softwareVolume = 0.20f;
    double targetSoftwareVolume = 0.20;
    double currentSoftwareVolume = 0.20;
    double transportFadeLin = 1.0;
};
