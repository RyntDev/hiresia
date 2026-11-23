# AGENTS.md — Hiresia Project Map & Rules

Compact guide for coding agents working on **Hiresia** (`com.hiresia.audioplayer`). Read this first instead of scanning the repository.

## Core Rules
1. **Protect the Audio Engine:** Never modify `app/src/main/cpp/*`, `AudioDecoder.kt`, `AudioUtilsJNI.kt`, `UsbAudioDeviceManager.kt`, or the bit-perfect / USB DAC / DSD signal path unless the user explicitly asks for an audio engine change.
2. **Default First-Install Experience:** Default palette is **Obsidian Gold (Dark)** (`PALETTE_D_OBSIDIAN_GOLD`, `THEME_MODE_DARK`), default language is **English** (`"en"`), with instant in-app switching to Bahasa Indonesia (`"id"`), `Zest Light`, and `Aegean Light`.
3. **Bilingual Parity:** Any new user-facing string in XML/Kotlin must have both Bahasa Indonesia and English mappings in `AppLanguageManager.kt` (or `tr(id, en)`).
4. **Token-Efficient Workflow:**
   - Read only the target file/line range listed below instead of running broad repo scans.
   - Batch independent edits; run a single quiet build (`./gradlew assembleDebug --quiet`) after finishing all edits.
   - Keep `CHANGELOG.md` updated only when preparing a version bump or release.
   - Never commit local paths, keystores, APKs, or `.cache/` files.
5. **UI/UX Performance Guardrails:**
   - Never call JNI/volume formatters or unconditional `TextView.setText()` inside the 20–30 Hz `playbackState` loop; skip updates when the Now Playing sheet is hidden.
   - Keep notification/widget IPC off the Main thread; preserve `RecyclerView` `LayoutManager`/adapter across tab switches; keep `NestedScrollView` child `RecyclerView`s diff-guarded and batched.
6. **Kotlin Resource Import Check:** When adding `R.drawable.*` or `R.color.*` in `EqualizerActivity.kt` or `VolumeSettingsActivity.kt`, verify or qualify `com.hiresia.audioplayer.R`.

## Quick File Map (`app/src/main/`)

### Native C++20 Engine (`cpp/`) — `libusbaudioengine.so`
- `CMakeLists.txt` — CMake build (C++20, `arm64-v8a`, `armeabi-v7a`, `x86_64`) + `third_party/` (ALAC Apache-2.0, WavPack 5.7.0 BSD-3, `dr_libs` PD/MIT-0, `stb_vorbis` PD/MIT).
- `audio_engine.h/.cpp` — AudioServer thread, ring buffer, AAudio Exclusive HAL + USB routing & self-healing.
- `usb_audio_driver.h/.cpp` — Direct UAC1/UAC2 USB DAC isochronous driver & hardware Feature Unit volume.
- `dsp_processor.h/.cpp` — 64-bit Orfanidis 10-band PEQ, AutoGain anti-clipping, Chu-Moy Crossfeed, Balance, TPDF Dither.
- `dsd_decoder.h/.cpp`, `native_decoder.h/.cpp`, `alac_mp4_decoder.h/.cpp`, `tta_decoder.h/.cpp`, `sinc_resampler.h/.cpp` — Lossless/Hi-Res decoders & resampler.
- `audio_utils_jni.cpp` — JNI bridge for `com.hiresia.audioplayer.native.AudioUtilsJNI`.

### Kotlin App (`java/com/hiresia/audioplayer/`)
- `HiresiaApplication.kt` — App init (`AudioSettingsRepository.init` + `applyThemeMode` + `AudioServer_initAndRun`).
- `database/HiresiaLibraryDatabase.kt` — SQLite library (`FolderSearch`, `Song`, `Album`, `Artist`, `Genre`, `Composer`, `PlayList`).
- `repository/AudioSettingsRepository.kt` — All SharedPreferences, palettes (`PALETTE_D_OBSIDIAN_GOLD`, `PALETTE_B_ZAITUN`, `PALETTE_C_AEGEAN`), language default, EQ/DSP & volume settings.
- `repository/AppLanguageManager.kt` — Instant ID ↔ EN view-tree translator & dynamic string helpers (`tr`, `formatSongsCount`, etc.).
- `repository/MediaRepository.kt` & `StoragePathUtils.kt` — Folder scanner, SAF/storage helpers, album/artist grouping.
- `engine/AudioPlayerController.kt` — Playback orchestration, state flow, USB/internal route reload, volume control, notification dispatch.
- `engine/AudioDecoder.kt` — Coroutine bridge feeding native decoder frames to `AudioServer`.
- `engine/QueueManager.kt`, `LiveLyricsManager.kt` (LRCLIB HTTPS), `ReplayGainParser.kt`, `SleepTimerManager.kt`.
- `service/MediaPlaybackService.kt`, `MediaWidgetProvider.kt`, `MediaButtonReceiver.kt` — Foreground service, MediaSession, wake lock, widget.
- `usb/UsbAudioDeviceManager.kt`, `UsbDeviceReceiver.kt` — USB DAC hotplug, permission, debounced detach/recovery.
- `ui/MainActivity.kt` — 4 main tabs (`BERANDA`, `ANTREAN`, `CARI`, `PUSTAKA`), Mini Player, Now Playing sheet, theme/language application.
- `ui/QueueAdapter.kt`, `TrackAdapter.kt`, `HomeCarouselAdapters.kt` — RecyclerView adapters (diff-guarded, batched queue rendering, 120–128px Glide thumbs).
- `ui/SettingsActivity.kt` — Audio/USB/DSD settings, palette & language pickers, Report Feedback form & Support Developer link.
- `ui/EqualizerActivity.kt`, `VolumeSettingsActivity.kt`, `AudioOutputBottomSheet.kt`, `AudiophileVolumeController.kt`, `AlbumArtBlurHelper.kt`.

### Key Resources (`res/`)
- `values/colors.xml`, `values/themes.xml` — Light palette defaults (`Zest Light` / `Aegean Light`).
- `values-night/colors.xml`, `values-night/themes.xml` — Dark **Obsidian Gold** tokens (`#161617`, `#1E1E20`, `#2A2A2C`, `#F3EAC3`, `#14130F`).
- `drawable/bg_obsidian_ambient.xml`, `drawable/cover_obsidian_wave.png`, `drawable-night/bg_format_pill.xml`, `drawable-night/bg_hero_cover_edge_vignette.xml`.
- `layout/activity_main.xml`, `activity_settings.xml`, `activity_equalizer.xml`, `activity_volume_settings.xml`, `item_track.xml`, `item_queue_group.xml`.

## Build & Verify Commands
- Compile Kotlin check: `./gradlew :app:compileDebugKotlin --quiet`
- Full debug APK: `./gradlew assembleDebug --quiet` (output: `app/build/outputs/apk/debug/app-debug.apk`)
- Install via ADB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

