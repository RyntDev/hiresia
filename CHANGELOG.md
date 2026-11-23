# Changelog

All notable changes to **Hiresia** are documented in this file.

## [Unreleased]

### Added
- **Obsidian Gold (Dark) Palette** as the default first-install theme, featuring a soft luminous ambient background (`bg_obsidian_ambient`), obsidian-gold Hero Mix card, coral-pink Liked Songs banner, Champagne Gold (`#F3EAC3`) accents, mint-green format badges (`#2ED89A`), and matching default cover art (`cover_obsidian_wave`).
- **Bilingual UI (English & Bahasa Indonesia)** with **English** set as the default first-install language and instant in-app switching via *Settings → App Language*.
- **Support & Feedback section in Settings** with direct access to the *Report Bug & Feedback* form and *Support Developer* link.
- **New Hiresia Launcher Icon** across adaptive (`anydpi-v26`) and legacy (`mdpi`–`xxxhdpi`) mipmaps.

### Improved
- **UI/UX Performance Optimization (zero changes to C++ audio engine):**
  - Eliminated Play/Pause tap lag by offloading foreground notification & widget IPC updates to a background thread, removing 30 Hz JNI volume-label polling from the UI loop, and stopping idle state emissions while paused.
  - Eliminated freeze when opening the **Queue** tab during playback via smart adapter diffing, 25-track initial batch rendering with on-demand expansion, disabled redundant item animators, and 120–128 px Glide thumbnail decoding.
  - Accelerated tab switching and **Library** scrolling by preserving `RecyclerView` `LayoutManager`/adapter state, caching `onResume()` theme/language checks, and restricting marquee animation to the active track.
- **USB DAC Stability & Audio Pipeline Self-Healing:**
  - Hardened UAC1/UAC2 URB handling, kernel driver handback on close, debounced detach verification, automatic AAudio stream rebuild on disconnect, unified pipeline reload on route transitions, and playback wake lock.

### Changed
- Cleaned repository documentation, comments, and leftover local files for public GitHub readiness.

## [1.0.0]
- Initial standalone C++20 NDK bit-perfect Hi-Res audio player release (`com.hiresia.audioplayer`) supporting Direct USB DAC (UAC1/UAC2), AAudio Exclusive HAL, native FLAC/WAV/ALAC/WavPack/TTA/DSD/Vorbis/MP3 decoding, 64-bit 10-band Parametric EQ + AutoEQ import, Crossfeed, ReplayGain, SQLite library, and Live Lyrics via LRCLIB.

