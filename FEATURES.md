# Hiresia — Full Feature Specification

Complete technical overview of **Hiresia** (`com.hiresia.audioplayer`), an audiophile-grade Hi-Res & Bit-Perfect audio player for Android powered by a custom **C++20 NDK native audio engine** (`libusbaudioengine.so`).

---

## 1. Native C++20 Audio Engine (`libusbaudioengine.so`)
- **Custom C++20 Signal Pipeline:** Compiled natively for `arm64-v8a`, `armeabi-v7a`, and `x86_64` (`-std=c++20 -O3 -ffast-math`).
- **Bit-Perfect Mode:** Bypasses the Android AudioFlinger mixer and software DSP chain to deliver bit-exact PCM/DSD streams to supported DACs.
- **Direct USB Audio Class Driver (`UAC1` & `UAC2`):** Direct userspace communication over Android USB Host / Linux `usbdevfs` with isochronous transfers, automatic USB DAC hotplug detection, debounced detach handling, and stream recovery.
- **Direct AAudio Exclusive HAL Path:** Low-latency exclusive audio output for internal DACs and wired headphones/earphones with automatic route reload and recovery.
- **AudioServer Ring-Buffer Architecture:** Dedicated native `AudioServer` thread fed via a low-overhead Kotlin coroutine + JNI bridge (`AudioDecoder` → `AudioUtilsJNI`).

---

## 2. Native Audio Decoders & Resampling
Zero reliance on Android's lossy/resampling MediaPlayer decoders — all core formats are decoded natively in C/C++:
- **Lossless Hi-Res Formats:** **FLAC**, **WAV**, **ALAC** (`.m4a` / MP4 container), **WavPack** (PCM, Float, & DSD), **TTA** (True Audio).
- **Direct Stream Digital (DSD):** **DSF** and **DFF** containers.
- **Compressed Formats:** **Ogg Vorbis** and **MP3**.
- **Windowed Sinc Resampler:** High-precision sample rate conversion when the active output device or non-bit-perfect mode requires resampling.

---

## 3. DSD & USB DAC Controls
- **Three DSD Playback Modes:**
  1. `DSD to PCM conversion` — High-precision FIR DSD-to-PCM decimation (compatible with EQ & DSP).
  2. `DoP (DSD over PCM)` — Packs 1-bit DSD inside PCM frames using v1.1 markers (`0x05` / `0xFA`).
  3. `Native DSD` — Sends pure 1-bit DSD bitstream directly to compatible USB DAC chips.
- **USB Hardware Options:** USB Exclusive mode toggle, USB HID button/interception block toggle (`KEY_BLOCK_USB_HID`), and configurable USB buffer size.
- **USB DAC Safety Guardrails:** Safe **20%** default initial volume upon DAC plug-in and a configurable **80% max safety ceiling** (`DAC_MAX_SAFETY_VOLUME`) to protect hearing and IEMs/headphones.

---

## 4. 64-Bit DSP Suite
*(Automatically bypassed when Bit-Perfect playback is active)*
- **10-Band 64-Bit Parametric EQ (Orfanidis):** Symmetrical biquad filters with adjustable **-15 dB to +15 dB** gain, frequency, and Q factor.
- **AutoEQ & Custom Presets:** Save/manage custom EQ presets (`JSON`) and import standard AutoEQ text profiles (`Preamp` + `Filter ON PK/LS/HS` lines).
- **Dynamic AutoGain Anti-Clipping:** Automatic headroom compensation paired with manual EQ Preamp to prevent digital clipping.
- **Binaural Crossfeed (Bauer / Chu-Moy):** Configurable acoustic crossfeed level (`KEY_CROSSFEED_LEVEL`) to reduce extreme stereo fatigue on headphones.
- **Channel Balance:** Software balance and USB hardware channel balance support.
- **TPDF Dither:** Triangular Probability Density Function dithering when reducing bit depth.
- **ReplayGain Support:** Tag parser (`ReplayGainParser`) with selectable track/album gain mode, preamp adjustment, and clipping prevention.

---

## 5. Audiophile Volume System (`VolumeSettingsActivity`)
- **4 Volume Control Modes:**
  - `Auto` (Hardware DAC when USB DAC is attached, Software when using internal output)
  - `Hardware Volume Control` (direct USB DAC Feature Unit `UAC_SET_CUR`)
  - `Software Volume Control`
  - `No Volume Control` (fixed full-scale line-out style)
- **3 Perceptual Volume Curves:**
  - `Linear`
  - `Perceptual (Cubic)` *(default)*
  - `Logarithmic (-96 dB to 0 dB)`
- **Hardware & Key Ergonomics:** Configurable volume step in dB, Force Volume Sync, hardware volume key interception, Android 12+ screen-off volume key fix, and auto-fading custom volume dialog.

---

## 6. Real-Time Signal Path Inspector & Metering
- **Audio Output / Signal Path Bottom Sheet:** Live inspection of source file format, sample rate/bit depth, active DSP modules, Bit-Perfect lock status, and active DAC / AAudio HAL route.
- **Stereo LED Bargraph VU Meter (`VUMeterView`):** Real-time left/right channel level telemetry driven directly by the native engine (automatically throttles down when paused).

---

## 7. Library, Queue & Playback Ecosystem
- **Indexed SQLite Music Library (`HiresiaLibraryDatabase`):** Dedicated tables for `FolderSearch`, `Song`, `Album`, `Artist`, `Genre`, `Composer`, and `PlayList` with Storage Access Framework (SAF) & folder scanning.
- **Album-Grouped Queue Management:** Fast queue navigation (`QueueManager`), play-next / add-to-queue / reorder support, and batched lazy expansion (`QueueAdapter`) for large libraries.
- **Live Synced Lyrics (`LiveLyricsManager`):** Fetches synced LRC / plain lyrics via HTTPS from the open [LRCLIB](https://lrclib.net/) API (zero tracking; falls back cleanly offline).
- **Sleep Timer (`SleepTimerManager`):** Countdown playback stop timer.
- **System Integration:**
  - Foreground playback service (`MediaPlaybackService`) with `MediaSession` lockscreen & notification transport controls + wake lock.
  - Wired/Bluetooth headset media button receiver (`MediaButtonReceiver`).
  - Home Screen App Widget (`MediaWidgetProvider`).
  - External audio file intent handling (`ACTION_VIEW` for `audio/*`, FLAC, OGG, etc.).

---

## 8. UI/UX, Theming & Localization
- **4-Tab Navigation Layout:** **Home**, **Queue**, **Search**, and **Library**, paired with a persistent floating navigation pill, Mini Player, and expandable Now Playing sheet.
- **3 Built-In Color Palettes & Theme Modes:**
  - **Obsidian Gold (Dark)** *(default first-install palette)* — `#161617` obsidian charcoal surfaces with warm `#F3EAC3` champagne gold accents and ambient glow.
  - **Zest Light** — Warm olive/citrus light aesthetic.
  - **Aegean Light** — Cool coastal blue light aesthetic.
  - Independent **Dark / Light / Follow System** mode and **Compact / Cozy** list density.
- **Instant Bilingual Localization (`AppLanguageManager`):** Switch in real time between **English** *(default)* and **Bahasa Indonesia** across views, dialogs, and format labels without restarting the app.
- **In-App Support & Feedback (`SettingsActivity`):** Direct links to the Bug Report / Feature Feedback form and Developer Support page.

---

## 9. Privacy, Security & Build
- **100% Offline Local Playback:** No user accounts, analytics SDKs, crash trackers, or background telemetry.
- **HTTPS-Only Network Policy:** `android:usesCleartextTraffic="false"` (network permission used solely for optional LRCLIB HTTPS lyrics lookup and user-opened feedback/support links).
- **Minimum Privilege Manifest:** `SettingsActivity` is non-exported (`android:exported="false"`).
- **Optimized Release Builds:** R8 code shrinking + resource shrinking enabled for `release` builds with safe optional `keystore.properties` signing.

