#include "dsp_processor.h"
#include <cmath>
#include <algorithm>
#include <cstring>

const float DspProcessor::BAND_FREQUENCIES[DspProcessor::NUM_BANDS] = {
    31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
};

BiquadFilter::BiquadFilter() {
    setSampleRate(44100.0);
    reset();
}

void BiquadFilter::setSampleRate(double sr) {
    double safeSr = sr > 8000.0 ? sr : 44100.0;
    // ~12ms exponential coefficient smoothing avoids zipper clicks during slider sweeps
    coeffSmooth = 1.0 - std::exp(-1.0 / (0.012 * safeSr));
}

void BiquadFilter::reset() {
    b0 = tb0 = 1.0;
    b1 = b2 = a1 = a2 = 0.0;
    tb1 = tb2 = ta1 = ta2 = 0.0;
    needsSmooth = false;
    s1 = s2 = 0.0;
}

void BiquadFilter::applyTargetCoeffs(double nb0, double nb1, double nb2, double na1, double na2, bool snap) {
    tb0 = nb0;
    tb1 = nb1;
    tb2 = nb2;
    ta1 = na1;
    ta2 = na2;
    if (snap) {
        b0 = tb0;
        b1 = tb1;
        b2 = tb2;
        a1 = ta1;
        a2 = ta2;
        needsSmooth = false;
    } else {
        needsSmooth = true;
    }
}

void BiquadFilter::setBypass(bool snap) {
    applyTargetCoeffs(1.0, 0.0, 0.0, 0.0, 0.0, snap);
}

void BiquadFilter::setPeakingEQ(double sr, double centerFreq, double gainDB, double q, bool snap) {
    if (std::abs(gainDB) < 0.02) {
        setBypass(snap);
        return;
    }

    if (centerFreq >= sr * 0.495) centerFreq = sr * 0.495;
    if (centerFreq < 10.0) centerFreq = 10.0;
    q = std::clamp(q, 0.1, 18.0);

    // Symmetrical Orfanidis / Massberg Parametric Equalizer
    // (exact mathematical reciprocal matching H_cut(z) = 1 / H_boost(z))
    double omega0 = 2.0 * M_PI * centerFreq / sr;
    double deltaW = omega0 / q;
    double tanBW = std::tan(deltaW * 0.5);
    double cosW0 = std::cos(omega0);
    double V0 = std::pow(10.0, std::abs(gainDB) / 20.0);

    if (gainDB > 0.0) {
        double a0 = 1.0 + tanBW;
        applyTargetCoeffs(
            (1.0 + V0 * tanBW) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - V0 * tanBW) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - tanBW) / a0,
            snap
        );
    } else {
        double a0 = 1.0 + V0 * tanBW;
        applyTargetCoeffs(
            (1.0 + tanBW) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - tanBW) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - V0 * tanBW) / a0,
            snap
        );
    }
}

void BiquadFilter::setLowShelf(double sr, double cutoffFreq, double gainDB, double q, bool snap) {
    if (std::abs(gainDB) < 0.02) {
        setBypass(snap);
        return;
    }
    if (cutoffFreq >= sr * 0.49) cutoffFreq = sr * 0.49;
    if (cutoffFreq < 10.0) cutoffFreq = 10.0;
    q = std::clamp(q, 0.1, 18.0);

    double A = std::pow(10.0, gainDB / 40.0);
    double omega0 = 2.0 * M_PI * cutoffFreq / sr;
    double sinW0 = std::sin(omega0);
    double cosW0 = std::cos(omega0);
    double alpha = sinW0 / (2.0 * q);
    double beta = 2.0 * std::sqrt(A) * alpha;

    double a0 = (A + 1.0) + (A - 1.0) * cosW0 + beta;
    applyTargetCoeffs(
        (A * ((A + 1.0) - (A - 1.0) * cosW0 + beta)) / a0,
        (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)) / a0,
        (A * ((A + 1.0) - (A - 1.0) * cosW0 - beta)) / a0,
        (-2.0 * ((A - 1.0) + (A + 1.0) * cosW0)) / a0,
        ((A + 1.0) + (A - 1.0) * cosW0 - beta) / a0,
        snap
    );
}

void BiquadFilter::setHighShelf(double sr, double cutoffFreq, double gainDB, double q, bool snap) {
    if (std::abs(gainDB) < 0.02) {
        setBypass(snap);
        return;
    }
    if (cutoffFreq >= sr * 0.49) cutoffFreq = sr * 0.49;
    if (cutoffFreq < 10.0) cutoffFreq = 10.0;
    q = std::clamp(q, 0.1, 18.0);

    double A = std::pow(10.0, gainDB / 40.0);
    double omega0 = 2.0 * M_PI * cutoffFreq / sr;
    double sinW0 = std::sin(omega0);
    double cosW0 = std::cos(omega0);
    double alpha = sinW0 / (2.0 * q);
    double beta = 2.0 * std::sqrt(A) * alpha;

    double a0 = (A + 1.0) - (A - 1.0) * cosW0 + beta;
    applyTargetCoeffs(
        (A * ((A + 1.0) + (A - 1.0) * cosW0 + beta)) / a0,
        (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0,
        (A * ((A + 1.0) + (A - 1.0) * cosW0 - beta)) / a0,
        (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0,
        ((A + 1.0) - (A - 1.0) * cosW0 - beta) / a0,
        snap
    );
}

void BiquadFilter::setLowPass(double sr, double cutoffFreq, double q, bool snap) {
    if (cutoffFreq >= sr * 0.49) cutoffFreq = sr * 0.49;
    if (cutoffFreq < 10.0) cutoffFreq = 10.0;
    q = std::clamp(q, 0.1, 18.0);

    double omega0 = 2.0 * M_PI * cutoffFreq / sr;
    double sinW0 = std::sin(omega0);
    double cosW0 = std::cos(omega0);
    double alpha = sinW0 / (2.0 * q);

    double a0 = 1.0 + alpha;
    double nb0 = ((1.0 - cosW0) * 0.5) / a0;
    applyTargetCoeffs(
        nb0,
        (1.0 - cosW0) / a0,
        nb0,
        (-2.0 * cosW0) / a0,
        (1.0 - alpha) / a0,
        snap
    );
}

void BiquadFilter::setHighPass(double sr, double cutoffFreq, double q, bool snap) {
    if (cutoffFreq >= sr * 0.49) cutoffFreq = sr * 0.49;
    if (cutoffFreq < 10.0) cutoffFreq = 10.0;
    q = std::clamp(q, 0.1, 18.0);

    double omega0 = 2.0 * M_PI * cutoffFreq / sr;
    double sinW0 = std::sin(omega0);
    double cosW0 = std::cos(omega0);
    double alpha = sinW0 / (2.0 * q);

    double a0 = 1.0 + alpha;
    double nb0 = ((1.0 + cosW0) * 0.5) / a0;
    applyTargetCoeffs(
        nb0,
        (-(1.0 + cosW0)) / a0,
        nb0,
        (-2.0 * cosW0) / a0,
        (1.0 - alpha) / a0,
        snap
    );
}

double BiquadFilter::getMagnitudeDb(double freqHz, double sr) const {
    if (sr <= 0.0 || freqHz <= 0.0) return 0.0;
    double w = 2.0 * M_PI * freqHz / sr;
    double cw = std::cos(w);
    double sw = std::sin(w);
    double c2w = std::cos(2.0 * w);
    double s2w = std::sin(2.0 * w);

    double numR = tb0 + tb1 * cw + tb2 * c2w;
    double numI = -(tb1 * sw + tb2 * s2w);
    double denR = 1.0 + ta1 * cw + ta2 * c2w;
    double denI = -(ta1 * sw + ta2 * s2w);

    double numSq = numR * numR + numI * numI;
    double denSq = std::max(1.0e-30, denR * denR + denI * denI);
    double magSq = std::max(1.0e-30, numSq / denSq);
    return 10.0 * std::log10(magSq);
}

CrossfeedFilter::CrossfeedFilter() {
    reset();
    updateCoefficients();
}

void CrossfeedFilter::reset() {
    asis[0] = asis[1] = 0.0;
    lo[0] = lo[1] = 0.0;
    hi[0] = hi[1] = 0.0;
}

void CrossfeedFilter::init(float sr) {
    sampleRate = sr > 8000.0f ? sr : 44100.0f;
    updateCoefficients();
    reset();
}

void CrossfeedFilter::setLevel(int lvl) {
    level = std::clamp(lvl, 0, 10);
    updateCoefficients();
    reset();
}

void CrossfeedFilter::updateCoefficients() {
    if (level <= 0) {
        gain = 1.0;
        return;
    }

    // Cutoff frequency for human head acoustic shadowing model (default ~700 Hz)
    double fcut = 700.0;

    // Crossfeed level mapping:
    // Level 1: 3.0 dB (subtle)
    // Level 5: 4.5 dB (standard Bauer reference, matches libbs2b BS2B_DEFAULT_CLEVEL)
    // Level 7: 6.0 dB (Chu Moy crossfeed)
    // Level 10: 9.5 dB (Jan Meier high crossfeed)
    double feed_db;
    if (level <= 3) {
        feed_db = 2.5 + (level * 0.5); // 3.0 to 4.0 dB
    } else if (level <= 6) {
        feed_db = 4.0 + ((level - 3) * 0.5); // 4.5 to 5.5 dB
    } else {
        feed_db = 6.0 + ((level - 7) * 1.16); // 6.0 to 9.5 dB
    }

    // Exact libbs2b mathematical formula (Bauer stereophonic-to-binaural DSP)
    double d0 = feed_db;
    double d1 = -5.0 * d0 / 6.0;
    d0 = d0 / 6.0;
    double d9 = d0 - 3.0;
    double d11 = d1 - 3.0;

    double x = std::pow(10.0, d11 / 20.0);
    double y = std::pow(10.0, d9 / 20.0);
    double z = 1.0 - y;

    double level_scale = std::exp2((d11 - 20.0 * std::log10(z)) / 12.0);
    double f_hi = fcut * level_scale;

    // Digital 1st-order IIR coefficients
    // Lowpass cross-channel filter:
    b1_lo = std::exp(-2.0 * M_PI * fcut / static_cast<double>(sampleRate));
    a0_lo = x * (1.0 - b1_lo);

    // Shelving highboost direct-channel filter:
    b1_hi = std::exp(-2.0 * M_PI * f_hi / static_cast<double>(sampleRate));
    a0_hi = 1.0 - z * (1.0 - b1_hi);
    a1_hi = -b1_hi;

    // Overall acoustic gain normalizer
    gain = 1.0 / (y + x);
}

void CrossfeedFilter::process(float& left, float& right) {
    if (level <= 0) return;

    if (std::isnan(left) || std::isinf(left)) left = 0.0f;
    if (std::isnan(right) || std::isinf(right)) right = 0.0f;

    double inL = static_cast<double>(left);
    double inR = static_cast<double>(right);

    // Channel 0 (Left)
    lo[0] = a0_lo * inL + b1_lo * lo[0];
    hi[0] = a0_hi * inL + a1_hi * asis[0] + b1_hi * hi[0];
    asis[0] = inL;

    // Channel 1 (Right)
    lo[1] = a0_lo * inR + b1_lo * lo[1];
    hi[1] = a0_hi * inR + a1_hi * asis[1] + b1_hi * hi[1];
    asis[1] = inR;

    if (std::abs(lo[0]) < 1.0e-20) lo[0] = 0.0;
    if (std::abs(lo[1]) < 1.0e-20) lo[1] = 0.0;
    if (std::abs(hi[0]) < 1.0e-20) hi[0] = 0.0;
    if (std::abs(hi[1]) < 1.0e-20) hi[1] = 0.0;

    // Bauer stereophonic cross-feed mixing
    double outL = gain * (hi[0] + lo[1]);
    double outR = gain * (hi[1] + lo[0]);

    if (std::isnan(outL) || std::isinf(outL)) outL = 0.0;
    if (std::isnan(outR) || std::isinf(outR)) outR = 0.0;

    // Safety saturation limiter to prevent digital clipping
    if (outL > 1.0) outL = 1.0;
    else if (outL < -1.0) outL = -1.0;

    if (outR > 1.0) outR = 1.0;
    else if (outR < -1.0) outR = -1.0;

    left = static_cast<float>(outL);
    right = static_cast<float>(outR);
}

DspProcessor::DspProcessor() {
    for (int i = 0; i < NUM_BANDS; ++i) {
        bandGains[i] = 0.0f;
        bandFreqs[i] = BAND_FREQUENCIES[i];
        bandEnabled[i] = true;
        if (i == 0) {
            bandTypes[i] = EqFilterType::LOW_SHELF;
            bandQs[i] = 0.70710678f;
        } else if (i == NUM_BANDS - 1) {
            bandTypes[i] = EqFilterType::HIGH_SHELF;
            bandQs[i] = 0.70710678f;
        } else {
            bandTypes[i] = EqFilterType::PEAKING;
            bandQs[i] = 1.41421356f;
        }
    }
    eqPreampDb = 0.0f;
    targetEqPreampLin = currentEqPreampLin = 1.0;
    currentAutoGainLin = 1.0;
    targetSoftwareVolume = currentSoftwareVolume = static_cast<double>(softwareVolume);
    currentBalanceGainL = 1.0;
    currentBalanceGainR = 1.0;
    targetReplayGainLin = currentReplayGainLin = 1.0;
    transportFadeLin = 1.0;
    updateFilters(true);
}

void DspProcessor::init(int sr, int ch) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    sampleRate = sr > 0 ? sr : 44100;
    channels = ch > 0 ? ch : 2;
    crossfeed.init(static_cast<float>(sampleRate));
    for (int i = 0; i < NUM_BANDS; ++i) {
        filtersL[i].setSampleRate(static_cast<double>(sampleRate));
        filtersR[i].setSampleRate(static_cast<double>(sampleRate));
    }
    currentSoftwareVolume = targetSoftwareVolume;
    currentBalanceGainL = static_cast<double>(balanceGainL);
    currentBalanceGainR = static_cast<double>(balanceGainR);
    currentReplayGainLin = targetReplayGainLin;
    transportFadeLin = 1.0;
    dynamicLimiterGain = 1.0f;
    updateFilters(true);
}

void DspProcessor::setUseGraphicEQ(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    useGraphicEQ = enable;
    calculateAutoGain();
}

void DspProcessor::setBandGain(int band, float gainDB) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) {
        bandGains[band] = std::clamp(gainDB, -15.0f, 15.0f);
        updateSingleBand(band, false);
        calculateAutoGain();
    }
}

float DspProcessor::getBandGain(int band) const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) {
        return bandGains[band];
    }
    return 0.0f;
}

void DspProcessor::setBandParam(int band, float freqHz, float gainDB, float q, int filterType, bool enabled) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band < 0 || band >= NUM_BANDS) return;
    bandFreqs[band] = std::clamp(freqHz, 10.0f, 24000.0f);
    bandGains[band] = std::clamp(gainDB, -15.0f, 15.0f);
    bandQs[band] = std::clamp(q, 0.1f, 18.0f);
    int clampedType = std::clamp(filterType, 0, 4);
    bandTypes[band] = static_cast<EqFilterType>(clampedType);
    bandEnabled[band] = enabled;
    updateSingleBand(band, false);
    calculateAutoGain();
}

float DspProcessor::getBandFreq(int band) const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) return bandFreqs[band];
    return 1000.0f;
}

float DspProcessor::getBandQ(int band) const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) return bandQs[band];
    return 1.41421356f;
}

int DspProcessor::getBandType(int band) const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) return static_cast<int>(bandTypes[band]);
    return static_cast<int>(EqFilterType::PEAKING);
}

bool DspProcessor::getBandEnabled(int band) const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (band >= 0 && band < NUM_BANDS) return bandEnabled[band];
    return true;
}

void DspProcessor::setEQPreamp(float pDb) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    eqPreampDb = std::clamp(pDb, -15.0f, 12.0f);
    targetEqPreampLin = std::pow(10.0, static_cast<double>(eqPreampDb) / 20.0);
    calculateAutoGain();
}

void DspProcessor::resetEQToDefault() {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    for (int i = 0; i < NUM_BANDS; ++i) {
        bandGains[i] = 0.0f;
        bandFreqs[i] = BAND_FREQUENCIES[i];
        bandEnabled[i] = true;
        if (i == 0) {
            bandTypes[i] = EqFilterType::LOW_SHELF;
            bandQs[i] = 0.70710678f;
        } else if (i == NUM_BANDS - 1) {
            bandTypes[i] = EqFilterType::HIGH_SHELF;
            bandQs[i] = 0.70710678f;
        } else {
            bandTypes[i] = EqFilterType::PEAKING;
            bandQs[i] = 1.41421356f;
        }
    }
    eqPreampDb = 0.0f;
    targetEqPreampLin = 1.0;
    updateFilters(false);
    calculateAutoGain();
}

void DspProcessor::setEQAutoGain(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    useAutoGain = enable;
    if (!enable) {
        dynamicLimiterGain = 1.0f;
    }
    calculateAutoGain();
}

void DspProcessor::calculateAutoGain() {
    if (!useAutoGain || !useGraphicEQ) {
        autoGainFactor = 1.0f;
        return;
    }
    // Evaluate combined frequency response curve over 64 log-spaced points (20 Hz - Nyquist*0.48)
    double sr = static_cast<double>(sampleRate > 0 ? sampleRate : 44100);
    double minFreq = 20.0;
    double maxFreq = std::min(20000.0, sr * 0.48);
    if (maxFreq <= minFreq) maxFreq = minFreq * 2.0;

    double logMin = std::log10(minFreq);
    double logMax = std::log10(maxFreq);
    constexpr int STEPS = 64;

    double maxPeakDb = static_cast<double>(eqPreampDb);
    for (int s = 0; s < STEPS; ++s) {
        double frac = static_cast<double>(s) / static_cast<double>(STEPS - 1);
        double f = std::pow(10.0, logMin + frac * (logMax - logMin));
        double totalDb = static_cast<double>(eqPreampDb);
        for (int b = 0; b < NUM_BANDS; ++b) {
            if (bandEnabled[b]) {
                totalDb += filtersL[b].getMagnitudeDb(f, sr);
            }
        }
        if (totalDb > maxPeakDb) {
            maxPeakDb = totalDb;
        }
    }

    if (maxPeakDb > 0.05) {
        // Exact negative headroom attenuation so peak stays below 0 dBFS
        autoGainFactor = static_cast<float>(std::pow(10.0, -maxPeakDb / 20.0));
    } else {
        autoGainFactor = 1.0f;
    }
}

void DspProcessor::updateSingleBand(int band, bool snap) {
    if (band < 0 || band >= NUM_BANDS) return;
    double sr = static_cast<double>(sampleRate > 0 ? sampleRate : 44100);
    if (!bandEnabled[band]) {
        filtersL[band].setBypass(snap);
        filtersR[band].setBypass(snap);
        return;
    }

    double f = static_cast<double>(bandFreqs[band]);
    double g = static_cast<double>(bandGains[band]);
    double q = static_cast<double>(bandQs[band]);

    switch (bandTypes[band]) {
        case EqFilterType::LOW_SHELF:
            filtersL[band].setLowShelf(sr, f, g, q, snap);
            filtersR[band].setLowShelf(sr, f, g, q, snap);
            break;
        case EqFilterType::HIGH_SHELF:
            filtersL[band].setHighShelf(sr, f, g, q, snap);
            filtersR[band].setHighShelf(sr, f, g, q, snap);
            break;
        case EqFilterType::LOW_PASS:
            filtersL[band].setLowPass(sr, f, q, snap);
            filtersR[band].setLowPass(sr, f, q, snap);
            break;
        case EqFilterType::HIGH_PASS:
            filtersL[band].setHighPass(sr, f, q, snap);
            filtersR[band].setHighPass(sr, f, q, snap);
            break;
        case EqFilterType::PEAKING:
        default:
            filtersL[band].setPeakingEQ(sr, f, g, q, snap);
            filtersR[band].setPeakingEQ(sr, f, g, q, snap);
            break;
    }
}

void DspProcessor::updateFilters(bool snap) {
    for (int i = 0; i < NUM_BANDS; ++i) {
        updateSingleBand(i, snap);
    }
    calculateAutoGain();
}

void DspProcessor::setCrossFeedState(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    useCrossfeed = enable;
}

void DspProcessor::setCrossFeedLevel(int level) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    crossfeedLevel = level;
    crossfeed.setLevel(level);
}

void DspProcessor::setUseBalance(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    useBalance = enable;
}

void DspProcessor::setBalance(float bal) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    balance = std::clamp(bal, -1.0f, 1.0f);
    // Automatically engage balance when offset from center
    if (std::abs(balance) > 0.001f) {
        useBalance = true;
    }
    if (balance < 0.0f) {
        balanceGainL = 1.0f;
        balanceGainR = 1.0f + balance; // e.g. -1.0 gives 0.0 on Right
    } else {
        balanceGainL = 1.0f - balance; // e.g. 1.0 gives 0.0 on Left
        balanceGainR = 1.0f;
    }
}

bool DspProcessor::hasActiveDsp() const {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    bool eqActive = useGraphicEQ;
    bool balanceActive = useBalance && (std::abs(balance) > 0.001f);
    bool rgActive = (replayGainMode != ReplayGainMode::OFF) && (std::abs(targetReplayGainLin - 1.0) > 1e-4);
    bool rampActive = (std::abs(currentSoftwareVolume - 1.0) > 1e-5) || (transportFadeLin < 0.9999);
    return eqActive || useAutoGain || balanceActive || useCrossfeed || useMono ||
           (softwareVolume < 0.999f) || rgActive || rampActive;
}

void DspProcessor::setUseMono(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    useMono = enable;
}

void DspProcessor::updateReplayGainTarget() {
    float gain = 0.0f;
    if (replayGainMode == ReplayGainMode::TRACK) gain = trackGain + preampDb;
    else if (replayGainMode == ReplayGainMode::ALBUM) gain = albumGain + preampDb;

    double lin = (replayGainMode != ReplayGainMode::OFF)
        ? std::pow(10.0, static_cast<double>(gain) / 20.0)
        : 1.0;
    if (replayGainMode != ReplayGainMode::OFF && replayGainPreventClipping && replayGainPeak > 0.0001f) {
        if (lin * static_cast<double>(replayGainPeak) > 1.0) {
            lin = 1.0 / static_cast<double>(replayGainPeak);
        }
    }
    targetReplayGainLin = lin;
    replayGainFactor = static_cast<float>(lin);
}

void DspProcessor::setReplayGainMode(ReplayGainMode mode) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    replayGainMode = mode;
    updateReplayGainTarget();
}

void DspProcessor::setReplayGainValues(float tGain, float aGain, float pDb, float peak, bool preventClipping) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    trackGain = tGain;
    albumGain = aGain;
    preampDb = pDb;
    replayGainPeak = peak;
    replayGainPreventClipping = preventClipping;
    updateReplayGainTarget();
}

void DspProcessor::setReplayGainPreventClipping(bool enable) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    replayGainPreventClipping = enable;
    updateReplayGainTarget();
}

void DspProcessor::setSoftwareVolume(float vol) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    float clamped = std::clamp(vol, 0.0f, 1.0f);
    if (clamped <= 1.0e-5f) {
        softwareVolume = 0.0f;
        targetSoftwareVolume = 0.0;
    } else {
        softwareVolume = clamped;
        targetSoftwareVolume = static_cast<double>(clamped);
    }
}

void DspProcessor::triggerSoftFadeIn() {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    transportFadeLin = 0.0;
}

void DspProcessor::processInterleaved(float* buffer, int numFrames) {
    std::lock_guard<std::recursive_mutex> lock(dspMutex);
    if (!buffer || numFrames <= 0) return;

    double safeSr = static_cast<double>(std::max(sampleRate, 8000));
    double gainSmooth = 1.0 - std::exp(-1.0 / (0.010 * safeSr));
    double volSmooth = 1.0 - std::exp(-1.0 / (0.015 * safeSr));
    double limiterRelease = 1.0 - std::exp(-1.0 / (0.060 * safeSr));
    double desiredPreampLin = useGraphicEQ ? targetEqPreampLin : 1.0;
    double desiredAutoGainLin = static_cast<double>(autoGainFactor);
    double desiredBalL = (useBalance && channels >= 2) ? static_cast<double>(balanceGainL) : 1.0;
    double desiredBalR = (useBalance && channels >= 2) ? static_cast<double>(balanceGainR) : 1.0;

    auto smoothStep = [volSmooth](double& cur, double target) {
        double diff = target - cur;
        if (std::abs(diff) < 1e-7) cur = target;
        else cur += diff * volSmooth;
    };

    for (int i = 0; i < numFrames; ++i) {
        currentEqPreampLin += (desiredPreampLin - currentEqPreampLin) * gainSmooth;
        currentAutoGainLin += (desiredAutoGainLin - currentAutoGainLin) * gainSmooth;
        smoothStep(currentSoftwareVolume, targetSoftwareVolume);
        smoothStep(currentBalanceGainL, desiredBalL);
        smoothStep(currentBalanceGainR, desiredBalR);
        smoothStep(currentReplayGainLin, targetReplayGainLin);
        smoothStep(transportFadeLin, 1.0);

        double dl = static_cast<double>(buffer[i * channels]);
        double dr = (channels > 1) ? static_cast<double>(buffer[i * channels + 1]) : dl;

        // 10-Band Parametric EQ + Preamp in 64-bit Transposed Direct Form II
        if (useGraphicEQ) {
            dl *= currentEqPreampLin;
            dr *= currentEqPreampLin;
            for (int b = 0; b < NUM_BANDS; ++b) {
                dl = filtersL[b].process(dl);
                dr = filtersR[b].process(dr);
            }
        }

        // Apply Crossfeed
        if (useCrossfeed && channels >= 2) {
            float fl = static_cast<float>(dl);
            float fr = static_cast<float>(dr);
            crossfeed.process(fl, fr);
            dl = static_cast<double>(fl);
            dr = static_cast<double>(fr);
        }

        // Apply Smoothed Balance in 64-bit double
        if (channels >= 2) {
            dl *= currentBalanceGainL;
            dr *= currentBalanceGainR;
        }

        // Apply Mono Downmix
        if (useMono && channels >= 2) {
            double mono = (dl + dr) * 0.5;
            dl = mono;
            dr = mono;
        }

        // Apply Smoothed Overall Scaler (Software Volume, ReplayGain, AutoGain, Soft Fade)
        double frameGain = currentSoftwareVolume * currentReplayGainLin * currentAutoGainLin * transportFadeLin;
        dl *= frameGain;
        dr *= frameGain;

        // Transparent stereo-linked safety peak limiter:
        // 100% bypassed (gain == 1.0) whenever signal stays <= 0.985 (-0.13 dBFS).
        // Prevents harsh clipping/crackle when EQ boost pushes peaks > 0 dBFS at 0 dB volume/preamp.
        double peak = std::max(std::abs(dl), std::abs(dr));
        if (peak > 0.985) {
            double target = 0.985 / peak;
            if (target < static_cast<double>(dynamicLimiterGain)) {
                dynamicLimiterGain = static_cast<float>(target);
            }
        } else if (dynamicLimiterGain < 1.0f) {
            double recovered = static_cast<double>(dynamicLimiterGain) +
                (1.0 - static_cast<double>(dynamicLimiterGain)) * limiterRelease;
            dynamicLimiterGain = (1.0 - recovered < 1e-6) ? 1.0f : static_cast<float>(recovered);
        }
        if (dynamicLimiterGain < 1.0f) {
            dl *= static_cast<double>(dynamicLimiterGain);
            dr *= static_cast<double>(dynamicLimiterGain);
        }

        // Continuous C1 soft-knee safety ceiling (100% linear/untouched for |x| <= 0.985,
        // smoothly asymptotes to +/-0.9995 without any discontinuity or waveform inversion)
        constexpr double KNEE = 0.985;
        constexpr double HEADROOM = 0.0145;
        auto softClip = [](double x) -> float {
            if (x > KNEE) {
                return static_cast<float>(KNEE + HEADROOM * std::tanh((x - KNEE) / HEADROOM));
            } else if (x < -KNEE) {
                return static_cast<float>(-KNEE + HEADROOM * std::tanh((x + KNEE) / HEADROOM));
            }
            return static_cast<float>(x);
        };
        float l = softClip(dl);
        float r = softClip(dr);

        buffer[i * channels] = l;
        if (channels > 1) {
            buffer[i * channels + 1] = r;
        }
    }
}
