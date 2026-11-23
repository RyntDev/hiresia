package com.hiresia.audioplayer.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.hiresia.audioplayer.native.AudioUtilsJNI
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.service.MediaPlaybackService
import com.hiresia.audioplayer.usb.ConnectedUsbDac
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RepeatMode {
    OFF, ALL, ONE
}

data class OutputRoutingInfo(
    val outputType: Int, // 0 = USB_DIRECT, 1 = ANDROID_HIRES, 2 = AAUDIO
    val outputDeviceName: String,
    val pipelineChain: String,
    val isBitPerfect: Boolean,
    val isHiResDriver: Boolean,
    val isUsbExclusive: Boolean
)

data class PlaybackState(
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val vuLeft: Float = 0.0f,
    val vuRight: Float = 0.0f,
    val isBitPerfect: Boolean = true,
    val isHiResDriver: Boolean = true,
    val isUsbExclusive: Boolean = false,
    val pipelineChain: String = "Source → Bit-Perfect → Hi-Res Direct → DAC",
    val outputDeviceName: String = "Internal Audio",
    val outputRateStr: String = "44.1 kHz / 16-bit",
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val dsdModeBadge: String = ""
)

object AudioPlayerController {
    private const val TAG = "AudioPlayerController"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null
    private var playbackJob: Job? = null
    private var routeDebounceJob: Job? = null
    private val pipelineMutex = Mutex()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _routeNoticeEvent = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val routeNoticeEvent: SharedFlow<String> = _routeNoticeEvent.asSharedFlow()

    private val _dacVolume = MutableStateFlow(0.85f)
    val dacVolume: StateFlow<Float> = _dacVolume.asStateFlow()

    private val _effectiveVolumeType = MutableStateFlow(com.hiresia.audioplayer.repository.EffectiveVolumeType.SOFTWARE)
    val effectiveVolumeType: StateFlow<com.hiresia.audioplayer.repository.EffectiveVolumeType> = _effectiveVolumeType.asStateFlow()

    private val _volumeFraction = MutableStateFlow(0.85f)
    val volumeFraction: StateFlow<Float> = _volumeFraction.asStateFlow()

    private val _volumeDbText = MutableStateFlow("-3.2 dB")
    val volumeDbText: StateFlow<String> = _volumeDbText.asStateFlow()

    private var currentReplayGainInfo: ReplayGainInfo = ReplayGainInfo()

    private val playlist = mutableListOf<AudioTrack>()
    private var currentIndex = -1
    private lateinit var appContext: Context

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost permanently, pausing playback")
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently, pausing")
                if (_playbackState.value.isPlaying) {
                    resumeOnFocusGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus ducking")
                // Duck volume temporarily without overwriting user saved preference
                AudioUtilsJNI.AudioServer_setSoftwareVolume(0.20f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus gained, restoring volume")
                syncVolumeToNative()
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (UsbAudioDeviceManager.connectedDac.value != null &&
                    UsbAudioDeviceManager.isCurrentDacPhysicallyPresent()
                ) {
                    Log.i(TAG, "Ignoring ACTION_AUDIO_BECOMING_NOISY while USB DAC is physically attached.")
                    return
                }
                Log.i(TAG, "Audio becoming noisy (headphone/DAC unplugged), pausing playback and refreshing route.")
                pause()
                onInternalAudioRouteChanged(deviceRemoved = true)
            }
        }
    }

    private val systemVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType != AudioManager.STREAM_MUSIC && streamType != -1) return

            val dac = UsbAudioDeviceManager.connectedDac.value
            val isUsb = (dac != null)
            val isHwSupported = isHardwareVolumeSupported()
            val isDsd = _playbackState.value.currentTrack?.isDsd ?: false
            val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
            if (effType == com.hiresia.audioplayer.repository.EffectiveVolumeType.INTERNAL_SYSTEM) {
                val sysVol = getInternalSystemVolumeFraction()
                if (abs(_volumeFraction.value - sysVol) > 0.005f) {
                    applyInternalSystemVolumeState(sysVol)
                }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            appContext.registerReceiver(noisyReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register noisy receiver: ${e.message}")
        }

        try {
            val volFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(
                appContext,
                systemVolumeReceiver,
                volFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume receiver: ${e.message}")
        }

        startStatusTicker()
        syncVolumeToNative()
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            am.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun setPlaylist(
        tracks: List<AudioTrack>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        groupName: String = "Daftar Putar",
        playlistId: Long? = null
    ) {
        playlist.clear()
        playlist.addAll(tracks)
        QueueManager.setInitialPlaylist(groupName, tracks, startIndex, playlistId)
    }

    fun prepareTrack(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        val track = playlist[index]
        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            isPlaying = false,
            currentPositionMs = 0,
            totalDurationMs = track.durationMs
        )
    }

    fun addToQueue(track: AudioTrack) {
        playlist.add(track)
        QueueManager.addCustomTracks(listOf(track))
    }

    fun playNext(track: AudioTrack) {
        QueueManager.insertNextTrack(track)
    }

    fun removeFromQueue(index: Int) {
        if (index in playlist.indices) {
            playlist.removeAt(index)
        }
    }

    fun clearQueue() {
        stop()
        playlist.clear()
        currentIndex = -1
        _playbackState.value = _playbackState.value.copy(
            currentTrack = null,
            isPlaying = false,
            currentPositionMs = 0,
            totalDurationMs = 0
        )
    }

    fun playTrack(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        executePlayTrack(playlist[index])
    }

    fun playTrackDirectly(track: AudioTrack) {
        val foundIdx = playlist.indexOfFirst { it.id == track.id }
        if (foundIdx != -1) {
            currentIndex = foundIdx
        } else {
            playlist.add(track)
            currentIndex = playlist.size - 1
        }
        executePlayTrack(track)
    }

    private fun inspectPcmTrackFormat(track: AudioTrack): AudioTrack {
        var verifiedSampleRate = track.sampleRate
        val verifiedChannels = track.channels
        val verifiedBitDepth = track.bitDepth
        var verifiedDuration = track.durationMs

        if (!track.isDsd && (track.path.startsWith("content://") || java.io.File(track.path).exists())) {
            val extractor = android.media.MediaExtractor()
            try {
                if (track.path.startsWith("content://")) {
                    extractor.setDataSource(appContext, android.net.Uri.parse(track.path), null)
                } else {
                    extractor.setDataSource(track.path)
                }
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        if (fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                            val sr = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            if (sr > 0) verifiedSampleRate = sr
                        }
                        if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                            val dur = fmt.getLong(android.media.MediaFormat.KEY_DURATION) / 1000L
                            if (dur > 0) verifiedDuration = dur
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Quick container inspection skipped: ${e.message}")
            } finally {
                try { extractor.release() } catch (ignored: Exception) {}
            }
        }

        return track.copy(
            sampleRate = verifiedSampleRate,
            channels = verifiedChannels,
            bitDepth = verifiedBitDepth,
            durationMs = verifiedDuration
        )
    }

    private fun computeDsdBadge(isDsd: Boolean, effectiveDsd: com.hiresia.audioplayer.repository.DsdModeOption): String {
        return if (isDsd) {
            when (effectiveDsd) {
                com.hiresia.audioplayer.repository.DsdModeOption.DSD_TO_PCM -> "DSD→PCM"
                com.hiresia.audioplayer.repository.DsdModeOption.DOP -> "DoP"
                com.hiresia.audioplayer.repository.DsdModeOption.NATIVE_DSD -> "Native DSD"
            }
        } else ""
    }

    private fun computeRateString(
        track: AudioTrack?,
        effectiveDsd: com.hiresia.audioplayer.repository.DsdModeOption,
        dsdBadge: String
    ): String {
        if (track != null && track.isDsd) {
            return if (effectiveDsd == com.hiresia.audioplayer.repository.DsdModeOption.DSD_TO_PCM) {
                "PCM 176.4 kHz / 24-bit"
            } else {
                "DSD${track.dsdRate} ($dsdBadge)"
            }
        }
        return "${(track?.sampleRate ?: 48000) / 1000.0} kHz / ${track?.bitDepth ?: 16}-bit"
    }

    private fun executePlayTrack(track: AudioTrack) {
        track.lastTimePlayed = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                com.hiresia.audioplayer.database.HiresiaLibraryDatabase.getInstance(appContext).recordTrackPlayed(track.id)
            } catch (ignored: Exception) {}
        }

        Log.i(TAG, "User requested playback for track: ${track.title} [currentIndex $currentIndex]")

        val routing = getCurrentRouting()
        val isUsb = (routing.outputType == 0)
        val effectiveDsd = AudioSettingsRepository.getEffectiveDsdMode(isUsb, track.isDsd)
        val dsdBadge = computeDsdBadge(track.isDsd, effectiveDsd)
        val rateStr = computeRateString(track, effectiveDsd, dsdBadge)

        // 1. Immediately update UI state with complete pipeline routing info
        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            isPlaying = true,
            currentPositionMs = 0,
            totalDurationMs = track.durationMs,
            outputDeviceName = routing.outputDeviceName,
            outputRateStr = rateStr,
            isBitPerfect = routing.isBitPerfect,
            isHiResDriver = routing.isHiResDriver,
            isUsbExclusive = routing.isUsbExclusive,
            pipelineChain = routing.pipelineChain,
            dsdModeBadge = dsdBadge
        )
        updatePlaybackNotification(true, track)

        // 2. Offload hardware & native preparation to IO dispatcher
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            pipelineMutex.withLock {
                if (!isActive) return@withLock
                AudioDecoder.stopAndJoin()
                AudioUtilsJNI.AudioServer_stop()
                if (!isActive) return@withLock

                requestAudioFocus()

                AudioUtilsJNI.AudioServer_setOutputType(routing.outputType)
                AudioUtilsJNI.AudioServer_setBitPerfectWhenPossible(routing.isBitPerfect)
                AudioUtilsJNI.AudioServer_setDsdMode(effectiveDsd.id)
                syncVolumeToNative()

                val finalTrack = inspectPcmTrackFormat(track)
                val rg = ReplayGainParser.parse(appContext, finalTrack.path, finalTrack.rawPath)
                currentReplayGainInfo = rg

                val success = AudioUtilsJNI.AudioServer_preparePlayback(
                    finalTrack.path,
                    finalTrack.sampleRate,
                    finalTrack.channels,
                    finalTrack.bitDepth,
                    finalTrack.durationMs,
                    finalTrack.isDsd,
                    finalTrack.dsdRate
                )

                if (success && isActive) {
                    applyReplayGainSettings()
                    AudioUtilsJNI.AudioServer_play()
                    AudioDecoder.playTrack(finalTrack) {
                        // Track EOS Callback
                        scope.launch(Dispatchers.Main) {
                            QueueManager.onTrackFinished()
                        }
                    }

                    // Update actual hardware device rate string from AudioServer
                    val devRate = AudioUtilsJNI.AudioServer_getDeviceSampleRate()
                    val actualRateStr = "${(if (devRate > 0) devRate else finalTrack.sampleRate) / 1000.0} kHz / ${finalTrack.bitDepth}-bit"
                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(
                            currentTrack = finalTrack,
                            totalDurationMs = finalTrack.durationMs,
                            outputRateStr = actualRateStr
                        )
                    }
                } else if (!success) {
                    Log.e(TAG, "Native AudioServer_preparePlayback failed for ${track.title}")
                }
            }
        }
    }

    fun updateCurrentTrackFormat(sampleRate: Int, bitDepth: Int, channels: Int) {
        val current = _playbackState.value.currentTrack ?: return
        if (current.sampleRate == sampleRate && current.bitDepth == bitDepth && current.channels == channels) return

        val updatedTrack = current.copy(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels
        )
        if (currentIndex in playlist.indices) {
            playlist[currentIndex] = updatedTrack
        }

        val devRate = AudioUtilsJNI.AudioServer_getDeviceSampleRate()
        val rateStr = "${devRate / 1000.0} kHz / ${bitDepth}-bit"

        _playbackState.value = _playbackState.value.copy(
            currentTrack = updatedTrack,
            outputRateStr = rateStr
        )
        Log.i(TAG, "Updated current track format dynamically: ${sampleRate} Hz, ${bitDepth}-bit, ${channels} ch")
    }

    fun togglePlayPause() {
        if (_playbackState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val current = _playbackState.value.currentTrack
        if (current != null) {
            if (!AudioDecoder.isRunning()) {
                Log.i(TAG, "AudioDecoder is not running on play(); reviving full Hiresia pipeline cleanly...")
                reloadAudioPipeline(reason = "play_revive_decoder", autoResume = true)
                return
            }
            requestAudioFocus()
            AudioDecoder.resume()
            try {
                AudioUtilsJNI.AudioServer_play()
            } catch (e: Exception) {
                Log.e(TAG, "AudioServer_play error", e)
            }
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            updatePlaybackNotification(true, current)
        } else if (currentIndex in playlist.indices) {
            playTrack(currentIndex)
        } else if (playlist.isNotEmpty()) {
            playTrack(0)
        }
    }

    fun pause() {
        AudioDecoder.pause()
        try {
            AudioUtilsJNI.AudioServer_pause()
        } catch (e: Exception) {
            Log.e(TAG, "AudioServer_pause error", e)
        }
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
        _playbackState.value.currentTrack?.let { updatePlaybackNotification(false, it) }
    }

    fun stop() {
        abandonAudioFocus()
        AudioDecoder.stop()
        scope.launch(Dispatchers.IO) {
            AudioUtilsJNI.AudioServer_stop()
        }
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = 0,
            vuLeft = 0f,
            vuRight = 0f
        )
    }

    fun next() {
        if (QueueManager.groups.value.isNotEmpty()) {
            QueueManager.next()
        } else {
            if (playlist.isEmpty()) return
            val nextIdx = if (_playbackState.value.isShuffle) {
                (playlist.indices).random()
            } else {
                (currentIndex + 1) % playlist.size
            }
            playTrack(nextIdx)
        }
    }

    fun previous() {
        if (_playbackState.value.currentPositionMs > 3000) {
            seekTo(0)
            return
        }
        if (QueueManager.groups.value.isNotEmpty()) {
            QueueManager.previous()
        } else {
            if (playlist.isEmpty()) return
            val prevIdx = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
            playTrack(prevIdx)
        }
    }

    fun toggleShuffle(): Boolean {
        val newShuffle = !_playbackState.value.isShuffle
        _playbackState.value = _playbackState.value.copy(isShuffle = newShuffle)
        return newShuffle
    }

    fun cycleRepeatMode(): RepeatMode {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
        return nextMode
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackState.value = _playbackState.value.copy(repeatMode = mode)
    }

    fun getPlaylist(): List<AudioTrack> = playlist.toList()

    fun getCurrentIndex(): Int = currentIndex

    fun seekTo(positionMs: Long) {
        AudioUtilsJNI.AudioServer_seek(positionMs)
        AudioDecoder.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun getCurrentReplayGainInfo(): ReplayGainInfo = currentReplayGainInfo

    fun applyReplayGainSettings() {
        val rg = currentReplayGainInfo
        val mode = AudioSettingsRepository.replayGainMode
        val preamp = AudioSettingsRepository.replayGainPreampDb
        val preventClipping = AudioSettingsRepository.isReplayGainPreventClipping
        val relevantPeak = when (mode) {
            1 -> rg.trackPeak
            2 -> rg.albumPeak
            else -> 1.0f
        }
        AudioUtilsJNI.AudioServer_setReplayGainMode(mode)
        AudioUtilsJNI.AudioServer_setReplayGainValues(
            rg.trackGainDb,
            rg.albumGainDb,
            preamp,
            relevantPeak,
            preventClipping
        )
    }

    fun isHardwareVolumeSupported(): Boolean = AudioUtilsJNI.AudioServer_isHardwareVolumeSupported()
    fun isStereoHardwareVolumeSupported(): Boolean = AudioUtilsJNI.AudioServer_isStereoHardwareVolumeSupported()
    fun getHardwareVolumeMin(): Int = AudioUtilsJNI.AudioServer_getHardwareVolumeMin()
    fun getHardwareVolumeMax(): Int = AudioUtilsJNI.AudioServer_getHardwareVolumeMax()
    fun getHardwareVolumeRes(): Int = AudioUtilsJNI.AudioServer_getHardwareVolumeRes()

    fun getInternalSystemVolumeFraction(): Float {
        val am = audioManager ?: return 1.0f
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            (cur.toFloat() / max.toFloat()).coerceIn(0.0f, 1.0f)
        } catch (_: Exception) {
            1.0f
        }
    }

    private fun setInternalSystemVolumeFraction(vol: Float): Float {
        val am = audioManager ?: return 1.0f
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (vol.coerceIn(0.0f, 1.0f) * max).roundToInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            (target.toFloat() / max.toFloat()).coerceIn(0.0f, 1.0f)
        } catch (_: Exception) {
            vol.coerceIn(0.0f, 1.0f)
        }
    }

    private fun adjustInternalSystemVolume(deltaSteps: Int): Float {
        val am = audioManager ?: return 1.0f
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            val target = (cur + deltaSteps).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            (target.toFloat() / max.toFloat()).coerceIn(0.0f, 1.0f)
        } catch (_: Exception) {
            _volumeFraction.value
        }
    }

    private fun applyInternalSystemVolumeState(fraction: Float) {
        val clamped = fraction.coerceIn(0.0f, 1.0f)
        val pct = (clamped * 100f).roundToInt()
        _volumeFraction.value = clamped
        _dacVolume.value = clamped
        _volumeDbText.value = "System $pct% (0.0 dB)"
        AudioUtilsJNI.AudioServer_setSoftwareVolume(1.0f)
    }

    fun syncVolumeToNative() {
        val maxLimit = AudioSettingsRepository.getDacMaxVolumeLimit()
        AudioUtilsJNI.AudioServer_setHardwareVolumeLimit(maxLimit)
        AudioUtilsJNI.AudioServer_setHardwareVolumeCurve(AudioSettingsRepository.hardwareVolumeCurve)
        AudioUtilsJNI.AudioServer_setHardwareBalance(AudioSettingsRepository.hardwareBalance)
        AudioUtilsJNI.AudioServer_setForceVolumeSync(AudioSettingsRepository.isForceVolumeSync)
        AudioUtilsJNI.AudioServer_setDitherEnabled(AudioSettingsRepository.isDitherEnabled)
        applyReplayGainSettings()

        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val isHwSupported = isHardwareVolumeSupported()
        val isDsd = _playbackState.value.currentTrack?.isDsd ?: false
        val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
        _effectiveVolumeType.value = effType

        when (effType) {
            com.hiresia.audioplayer.repository.EffectiveVolumeType.HARDWARE_DAC -> {
                val vol = if (dac != null) {
                    AudioSettingsRepository.getHardwareVolumeForDevice(dac.vendorId, dac.productId)
                } else {
                    AudioSettingsRepository.hardwareVolume
                }.coerceIn(0.0f, maxLimit)
                _volumeFraction.value = vol
                _dacVolume.value = vol
                _volumeDbText.value = AudioSettingsRepository.formatDecibel(
                    vol,
                    isHardware = true,
                    minRaw = getHardwareVolumeMin(),
                    maxRaw = getHardwareVolumeMax()
                )
                AudioUtilsJNI.AudioServer_setSoftwareVolume(1.0f)
                AudioUtilsJNI.AudioServer_setHardwareVolume(vol)
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.SOFTWARE -> {
                val vol = AudioSettingsRepository.softwareVolume.coerceIn(0.0f, 1.0f)
                _volumeFraction.value = vol
                _dacVolume.value = vol
                _volumeDbText.value = AudioSettingsRepository.formatDecibel(vol, isHardware = false)
                val curved = AudioSettingsRepository.calculateCurvedGain(vol)
                AudioUtilsJNI.AudioServer_setSoftwareVolume(curved)
                if (isUsb && isHwSupported) {
                    // Open DAC hardware mixer without double-attenuating DSP output
                    AudioUtilsJNI.AudioServer_setHardwareVolume(maxLimit)
                }
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.INTERNAL_SYSTEM -> {
                applyInternalSystemVolumeState(getInternalSystemVolumeFraction())
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.BIT_PERFECT_LOCKED -> {
                _volumeFraction.value = 1.0f
                _dacVolume.value = 1.0f
                _volumeDbText.value = "0.0 dB (Locked)"
                AudioUtilsJNI.AudioServer_setSoftwareVolume(1.0f)
                if (isUsb && isHwSupported) {
                    AudioUtilsJNI.AudioServer_setHardwareVolume(maxLimit)
                }
            }
        }
    }

    fun setVolume(vol: Float) {
        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val isHwSupported = isHardwareVolumeSupported()
        val isDsd = _playbackState.value.currentTrack?.isDsd ?: false
        val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
        _effectiveVolumeType.value = effType

        val maxLimit = if (effType == com.hiresia.audioplayer.repository.EffectiveVolumeType.HARDWARE_DAC) {
            AudioSettingsRepository.getDacMaxVolumeLimit()
        } else {
            1.0f
        }
        val clamped = vol.coerceIn(0.0f, maxLimit)

        when (effType) {
            com.hiresia.audioplayer.repository.EffectiveVolumeType.HARDWARE_DAC -> {
                AudioUtilsJNI.AudioServer_setSoftwareVolume(1.0f)
                if (dac != null) {
                    AudioSettingsRepository.setHardwareVolumeForDevice(dac.vendorId, dac.productId, clamped)
                } else {
                    AudioSettingsRepository.hardwareVolume = clamped
                }
                _volumeFraction.value = clamped
                _dacVolume.value = clamped
                _volumeDbText.value = AudioSettingsRepository.formatDecibel(
                    clamped,
                    isHardware = true,
                    minRaw = getHardwareVolumeMin(),
                    maxRaw = getHardwareVolumeMax()
                )
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.SOFTWARE -> {
                AudioSettingsRepository.softwareVolume = clamped
                _volumeFraction.value = clamped
                _dacVolume.value = clamped
                _volumeDbText.value = AudioSettingsRepository.formatDecibel(clamped, isHardware = false)
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.INTERNAL_SYSTEM -> {
                val actualVol = setInternalSystemVolumeFraction(clamped)
                applyInternalSystemVolumeState(actualVol)
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.BIT_PERFECT_LOCKED -> {
                _volumeFraction.value = 1.0f
                _dacVolume.value = 1.0f
                _volumeDbText.value = "0.0 dB (Locked)"
                AudioUtilsJNI.AudioServer_setSoftwareVolume(1.0f)
                Log.i(TAG, "Volume Locked (0 dB Bit-Exact): attenuation disabled.")
            }
        }
    }

    fun adjustVolumeByDb(deltaDb: Float): Float {
        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val isHwSupported = isHardwareVolumeSupported()
        val isDsd = _playbackState.value.currentTrack?.isDsd ?: false
        val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
        _effectiveVolumeType.value = effType

        when (effType) {
            com.hiresia.audioplayer.repository.EffectiveVolumeType.BIT_PERFECT_LOCKED -> return 1.0f
            com.hiresia.audioplayer.repository.EffectiveVolumeType.INTERNAL_SYSTEM -> {
                val stepDelta = when {
                    deltaDb > 0.0f -> 1
                    deltaDb < 0.0f -> -1
                    else -> 0
                }
                val actualVol = adjustInternalSystemVolume(stepDelta)
                applyInternalSystemVolumeState(actualVol)
                return actualVol
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.HARDWARE_DAC -> {
                val maxLimit = AudioSettingsRepository.getDacMaxVolumeLimit()
                val cur = _volumeFraction.value.coerceIn(0.0f, maxLimit)
                val minRaw = getHardwareVolumeMin()
                val maxRaw = getHardwareVolumeMax()
                val spanDb = ((maxRaw - minRaw) / 256.0f).let { if (it > 1.0f) it else 96.0f }
                val resDb = (getHardwareVolumeRes() / 256.0f).let { if (it > 0.05f && it <= 12.0f) it else 0.5f }
                val effectiveDeltaDb = when {
                    deltaDb > 0.0f -> maxOf(deltaDb, resDb)
                    deltaDb < 0.0f -> minOf(deltaDb, -resDb)
                    else -> 0.0f
                }

                val newVol = if (effectiveDeltaDb > 0.0f && cur <= 0.0001f) {
                    val wakeDb = (spanDb * 0.25f).coerceAtMost(spanDb)
                    val wakeMapped = (wakeDb / spanDb).coerceIn(0.02f, 1.0f)
                    AudioSettingsRepository.inverseHardwareVolumeCurve(wakeMapped).coerceIn(0.0f, maxLimit)
                } else {
                    val curMapped = AudioSettingsRepository.applyHardwareVolumeCurve(cur)
                    val curDbFromFloor = curMapped * spanDb
                    val targetDbFromFloor = (curDbFromFloor + effectiveDeltaDb).coerceIn(0.0f, spanDb)
                    if (targetDbFromFloor <= 0.05f && effectiveDeltaDb < 0.0f) {
                        0.0f
                    } else {
                        val targetMapped = (targetDbFromFloor / spanDb).coerceIn(0.0f, 1.0f)
                        AudioSettingsRepository.inverseHardwareVolumeCurve(targetMapped).coerceIn(0.0f, maxLimit)
                    }
                }
                setVolume(newVol)
                return newVol
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.SOFTWARE -> {
                val cur = _volumeFraction.value.coerceIn(0.0f, 1.0f)
                val newVol = when (AudioSettingsRepository.volumeCurve) {
                    0 -> { // Linear: gain = v -> dB = 20*log10(v)
                        val curDb = if (cur <= 0.0001f) -60.0f else (20.0 * log10(cur.toDouble())).toFloat()
                        val targetDb = curDb + deltaDb
                        if (targetDb <= -72.0f && deltaDb < 0.0f) 0.0f
                        else 10.0.pow((targetDb / 20.0).toDouble()).toFloat().coerceIn(0.0f, 1.0f)
                    }
                    1 -> { // Cubic: gain = v^3 -> dB = 60*log10(v)
                        val curDb = if (cur <= 0.0001f) -60.0f else (60.0 * log10(cur.toDouble())).toFloat()
                        val targetDb = curDb + deltaDb
                        if (targetDb <= -72.0f && deltaDb < 0.0f) 0.0f
                        else 10.0.pow((targetDb / 60.0).toDouble()).toFloat().coerceIn(0.0f, 1.0f)
                    }
                    else -> { // Log -96 dB: dB = (v - 1) * 96
                        val curDb = if (cur <= 0.0001f) -72.0f else (cur - 1.0f) * 96.0f
                        val targetDb = curDb + deltaDb
                        if (targetDb <= -95.5f && deltaDb < 0.0f) 0.0f
                        else (1.0f + targetDb / 96.0f).coerceIn(0.0f, 1.0f)
                    }
                }
                setVolume(newVol)
                return newVol
            }
        }
    }

    fun adjustVolume(deltaSteps: Int): Float {
        return adjustVolumeByDb(deltaSteps * AudioSettingsRepository.volumeStepDb)
    }

    fun getNowPlayingVolumeLabel(): String {
        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val isHwSupported = isHardwareVolumeSupported()
        val isDsd = _playbackState.value.currentTrack?.isDsd ?: false
        val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
        val vol = _volumeFraction.value
        val pct = (vol * 100f).roundToInt().coerceIn(0, 100)
        val dsdBadge = _playbackState.value.dsdModeBadge

        return when (effType) {
            com.hiresia.audioplayer.repository.EffectiveVolumeType.HARDWARE_DAC -> {
                val db = AudioSettingsRepository.formatDecibel(
                    vol,
                    isHardware = true,
                    minRaw = getHardwareVolumeMin(),
                    maxRaw = getHardwareVolumeMax()
                )
                if (isDsd && dsdBadge.isNotEmpty()) "DAC Vol: $db ($dsdBadge)" else "DAC Vol: $db ($pct%)"
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.SOFTWARE -> {
                val db = AudioSettingsRepository.formatDecibel(vol, isHardware = false)
                "Vol: $db ($pct%)"
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.INTERNAL_SYSTEM -> {
                if (isDsd && dsdBadge.isNotEmpty()) "$dsdBadge: $pct% (0 dB)"
                else "Vol: $pct% (Bit-Perfect)"
            }
            com.hiresia.audioplayer.repository.EffectiveVolumeType.BIT_PERFECT_LOCKED -> {
                if (isDsd && dsdBadge.isNotEmpty()) "$dsdBadge: 0.0 dB (Locked)"
                else if (isUsb) "DAC: 0.0 dB (Locked)" else "Vol: 0.0 dB (Locked)"
            }
        }
    }

    fun onDsdSettingChanged() {
        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val track = _playbackState.value.currentTrack
        val isDsd = track?.isDsd ?: false
        val effectiveDsd = AudioSettingsRepository.getEffectiveDsdMode(isUsb, isDsd)
        AudioUtilsJNI.AudioServer_setDsdMode(effectiveDsd.id)

        val dsdBadge = if (isDsd) {
            when (effectiveDsd) {
                com.hiresia.audioplayer.repository.DsdModeOption.DSD_TO_PCM -> "DSD→PCM"
                com.hiresia.audioplayer.repository.DsdModeOption.DOP -> "DoP"
                com.hiresia.audioplayer.repository.DsdModeOption.NATIVE_DSD -> "Native DSD"
            }
        } else ""

        val rateStr = if (isDsd && track != null) {
            if (effectiveDsd == com.hiresia.audioplayer.repository.DsdModeOption.DSD_TO_PCM) {
                "PCM 176.4 kHz / 24-bit"
            } else {
                "DSD${track.dsdRate} ($dsdBadge)"
            }
        } else {
            _playbackState.value.outputRateStr
        }

        _playbackState.value = _playbackState.value.copy(
            dsdModeBadge = dsdBadge,
            outputRateStr = rateStr
        )
        syncVolumeToNative()
    }

    fun getHardwareVolumeDb(vol: Float = _volumeFraction.value): String {
        val isHardware = isHardwareVolumeSupported()
        val min = getHardwareVolumeMin()
        val max = getHardwareVolumeMax()
        return AudioSettingsRepository.formatDecibel(vol, isHardware, min, max)
    }

    fun getDacVolume(): Float = _volumeFraction.value
    fun setDacVolume(vol: Float) = setVolume(vol)
    fun setHardwareVolume(vol: Float) = setVolume(vol)
    fun setSoftwareVolume(vol: Float) = setVolume(vol)
    fun adjustDacVolume(deltaSteps: Int): Float = adjustVolume(deltaSteps)

    fun getCurrentRouting(): OutputRoutingInfo {
        val dac = UsbAudioDeviceManager.connectedDac.value
        val isUsb = (dac != null)
        val bitPerfect = AudioSettingsRepository.isBitPerfect
        val hiRes = AudioSettingsRepository.isHiResDriverEnabled
        val usbEx = AudioSettingsRepository.isUsbExclusiveEnabled && isUsb

        val outputType = when {
            isUsb && usbEx -> 0 // USB_DIRECT (Exclusive bypass to DAC)
            hiRes -> 1 // ANDROID_HIRES (AAudio exclusive / direct HAL)
            else -> 2 // AAUDIO (Standard Shared)
        }

        // Clean & concise labels: "DAC" when using external DAC, "Internal" when using phone audio
        val deviceName = if (isUsb) "DAC" else "Internal"

        val chain = AudioSettingsRepository.getPipelineChain(isUsb)

        return OutputRoutingInfo(
            outputType = outputType,
            outputDeviceName = deviceName,
            pipelineChain = chain,
            isBitPerfect = bitPerfect,
            isHiResDriver = hiRes,
            isUsbExclusive = usbEx
        )
    }

    private var wasPlayingBeforeUsbAttach = false

    fun prepareForUsbAttach() {
        val wasPlayingNow = _playbackState.value.isPlaying
        wasPlayingBeforeUsbAttach = wasPlayingBeforeUsbAttach || wasPlayingNow
        if (wasPlayingNow) {
            Log.i(TAG, "prepareForUsbAttach: Pre-pausing audio before USB connection handoff")
            AudioDecoder.pause()
            try { AudioUtilsJNI.AudioServer_pause() } catch (ignored: Exception) {}
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            _playbackState.value.currentTrack?.let { updatePlaybackNotification(false, it) }
        }
    }

    fun prepareForUsbDetach() {
        val wasPlayingNow = _playbackState.value.isPlaying
        if (wasPlayingNow) {
            Log.i(TAG, "prepareForUsbDetach: Immediately pausing audio on USB detach")
            AudioDecoder.pause()
            try { AudioUtilsJNI.AudioServer_pause() } catch (ignored: Exception) {}
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            _playbackState.value.currentTrack?.let { updatePlaybackNotification(false, it) }
        }
    }

    /**
     * Unified clean engine + decoder reload across route transitions (DAC <-> Internal Audio,
     * IEM plug/unplug, or waking an idle/stalled pipeline) without disturbing Bit-Perfect/FLAC/DSD fidelity.
     */
    private fun reloadAudioPipeline(
        reason: String,
        autoResume: Boolean,
        emitNotice: String? = null
    ) {
        val capturedTrack = _playbackState.value.currentTrack
        val capturedPosMs = maxOf(
            _playbackState.value.currentPositionMs,
            try { AudioUtilsJNI.AudioServer_getCurrentPosition() } catch (_: Exception) { 0L }
        ).coerceAtLeast(0L)

        Log.i(TAG, "reloadAudioPipeline(reason=$reason, autoResume=$autoResume, track=${capturedTrack?.title}, pos=${capturedPosMs}ms)")

        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            pipelineMutex.withLock {
                if (!isActive) return@withLock

                AudioDecoder.stopAndJoin()
                AudioUtilsJNI.AudioServer_stop()
                if (!isActive) return@withLock

                val routing = getCurrentRouting()
                val isUsb = (routing.outputType == 0)
                val isDsd = capturedTrack?.isDsd ?: false
                val effectiveDsd = AudioSettingsRepository.getEffectiveDsdMode(isUsb, isDsd)
                val dsdBadge = computeDsdBadge(isDsd, effectiveDsd)
                val initialRateStr = computeRateString(capturedTrack, effectiveDsd, dsdBadge)

                AudioUtilsJNI.AudioServer_setOutputType(routing.outputType)
                AudioUtilsJNI.AudioServer_setBitPerfectWhenPossible(routing.isBitPerfect)
                AudioUtilsJNI.AudioServer_setDsdMode(effectiveDsd.id)
                syncVolumeToNative()

                withContext(Dispatchers.Main) {
                    _playbackState.value = _playbackState.value.copy(
                        outputDeviceName = routing.outputDeviceName,
                        outputRateStr = initialRateStr,
                        isBitPerfect = routing.isBitPerfect,
                        isHiResDriver = routing.isHiResDriver,
                        isUsbExclusive = routing.isUsbExclusive,
                        pipelineChain = routing.pipelineChain,
                        dsdModeBadge = dsdBadge,
                        currentPositionMs = capturedPosMs,
                        isPlaying = false
                    )
                }

                if (capturedTrack != null && isActive) {
                    val finalTrack = inspectPcmTrackFormat(capturedTrack)
                    currentReplayGainInfo = ReplayGainParser.parse(appContext, finalTrack.path, finalTrack.rawPath)
                    val prepared = AudioUtilsJNI.AudioServer_preparePlayback(
                        finalTrack.path,
                        finalTrack.sampleRate,
                        finalTrack.channels,
                        finalTrack.bitDepth,
                        finalTrack.durationMs,
                        finalTrack.isDsd,
                        finalTrack.dsdRate
                    )
                    if (prepared && isActive) {
                        applyReplayGainSettings()
                        if (capturedPosMs > 0L) {
                            AudioUtilsJNI.AudioServer_seek(capturedPosMs)
                        }
                        AudioDecoder.playTrack(
                            track = finalTrack,
                            startPositionMs = capturedPosMs,
                            startPaused = !autoResume
                        ) {
                            scope.launch(Dispatchers.Main) {
                                QueueManager.onTrackFinished()
                            }
                        }

                        val devRate = AudioUtilsJNI.AudioServer_getDeviceSampleRate()
                        val updatedRate = if (devRate > 0 && !finalTrack.isDsd) {
                            "${devRate / 1000.0} kHz / ${finalTrack.bitDepth}-bit"
                        } else {
                            initialRateStr
                        }

                        if (autoResume && isActive) {
                            delay(100) // Safe settling delay for PLL / AAudio HAL
                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    requestAudioFocus()
                                    AudioUtilsJNI.AudioServer_play()
                                    AudioDecoder.resume()
                                    _playbackState.value = _playbackState.value.copy(
                                        currentTrack = finalTrack,
                                        totalDurationMs = finalTrack.durationMs,
                                        outputRateStr = updatedRate,
                                        currentPositionMs = capturedPosMs,
                                        isPlaying = true
                                    )
                                    updatePlaybackNotification(true, finalTrack)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                _playbackState.value = _playbackState.value.copy(
                                    currentTrack = finalTrack,
                                    totalDurationMs = finalTrack.durationMs,
                                    outputRateStr = updatedRate,
                                    currentPositionMs = capturedPosMs,
                                    isPlaying = false
                                )
                                updatePlaybackNotification(false, finalTrack)
                            }
                        }
                    }
                } else if (isActive) {
                    AudioUtilsJNI.AudioServer_refreshOutputRoute()
                }

                if (!emitNotice.isNullOrBlank()) {
                    _routeNoticeEvent.tryEmit(emitNotice)
                }
            }
        }
    }

    fun onUsbDeviceAttached(
        dac: ConnectedUsbDac,
        forceResume: Boolean = false,
        isRecovered: Boolean = false
    ) {
        Log.i(TAG, "onUsbDeviceAttached: ${dac.name} (forceResume=$forceResume, wasPlaying=$wasPlayingBeforeUsbAttach, recovered=$isRecovered)")
        routeDebounceJob?.cancel()
        val shouldResume = forceResume || wasPlayingBeforeUsbAttach
        wasPlayingBeforeUsbAttach = false
        val notice = if (isRecovered) "Koneksi USB DAC dipulihkan otomatis" else null
        reloadAudioPipeline(reason = "usb_attach", autoResume = shouldResume, emitNotice = notice)
    }

    fun onUsbDeviceDetached() {
        Log.i(TAG, "onUsbDeviceDetached: Reloading Hiresia pipeline cleanly onto Internal Audio")
        wasPlayingBeforeUsbAttach = false
        routeDebounceJob?.cancel()
        reloadAudioPipeline(
            reason = "usb_detach",
            autoResume = false,
            emitNotice = "DAC USB terputus — dialihkan ke Audio Internal"
        )
    }

    fun onInternalAudioRouteChanged(deviceRemoved: Boolean) {
        if (UsbAudioDeviceManager.connectedDac.value != null || UsbAudioDeviceManager.isUsbTransitionInProgress()) {
            Log.d(TAG, "Ignoring internal route change because USB DAC is active or transitioning")
            return
        }
        routeDebounceJob?.cancel()
        routeDebounceJob = scope.launch {
            delay(180)
            if (UsbAudioDeviceManager.connectedDac.value != null || UsbAudioDeviceManager.isUsbTransitionInProgress()) return@launch
            val wasPlaying = _playbackState.value.isPlaying
            val hasTrack = _playbackState.value.currentTrack != null
            Log.i(TAG, "Internal audio route changed (removed=$deviceRemoved, wasPlaying=$wasPlaying, hasTrack=$hasTrack)")
            if (deviceRemoved) {
                reloadAudioPipeline(reason = "internal_route_removed", autoResume = false)
            } else if (hasTrack) {
                reloadAudioPipeline(reason = "internal_route_added", autoResume = wasPlaying)
            } else {
                withContext(Dispatchers.IO) {
                    pipelineMutex.withLock {
                        AudioUtilsJNI.AudioServer_refreshOutputRoute()
                    }
                }
            }
        }
    }

    fun setBitPerfectMode(enabled: Boolean) {
        AudioSettingsRepository.isBitPerfect = enabled
        notifyAudioSettingsChanged()
    }

    fun setHiResDriverMode(enabled: Boolean) {
        AudioSettingsRepository.isHiResDriverEnabled = enabled
        notifyAudioSettingsChanged()
    }

    fun setUsbExclusiveMode(enabled: Boolean) {
        AudioSettingsRepository.isUsbExclusiveEnabled = enabled
        notifyAudioSettingsChanged()
    }

    fun notifyAudioSettingsChanged() {
        val routing = getCurrentRouting()
        _playbackState.value = _playbackState.value.copy(
            outputDeviceName = routing.outputDeviceName,
            isBitPerfect = routing.isBitPerfect,
            isHiResDriver = routing.isHiResDriver,
            isUsbExclusive = routing.isUsbExclusive,
            pipelineChain = routing.pipelineChain
        )
        scope.launch(Dispatchers.IO) {
            AudioUtilsJNI.AudioServer_setOutputType(routing.outputType)
            AudioUtilsJNI.AudioServer_setBitPerfectWhenPossible(routing.isBitPerfect)
            syncVolumeToNative()
        }
    }

    private fun updatePlaybackNotification(isPlaying: Boolean, track: AudioTrack) {
        scope.launch(Dispatchers.Default) {
            try {
                val intent = Intent(appContext, MediaPlaybackService::class.java).apply {
                    action = if (isPlaying) MediaPlaybackService.ACTION_PLAY else MediaPlaybackService.ACTION_PAUSE
                    putExtra(MediaPlaybackService.EXTRA_TITLE, track.title)
                    putExtra(MediaPlaybackService.EXTRA_ARTIST, track.artist)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                com.hiresia.audioplayer.service.MediaWidgetProvider.updateAllWidgets(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
            }
        }
    }

    private fun startStatusTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (AudioUtilsJNI.AudioServer_consumeFatalUsbError()) {
                    val wasPlaying = _playbackState.value.isPlaying
                    Log.w(TAG, "StatusTicker detected native USB fatal error (wasPlaying=$wasPlaying), invoking recovery")
                    UsbAudioDeviceManager.handleNativeUsbError(wasPlaying)
                }

                val curState = _playbackState.value
                if (curState.isPlaying) {
                    val pos = AudioUtilsJNI.AudioServer_getCurrentPosition()
                    val total = AudioUtilsJNI.AudioServer_getTotalLengthMillisecondsOfCurrentTrack()
                    val vuL = AudioUtilsJNI.AudioServer_getCurrentLeftVolumePostAll()
                    val vuR = AudioUtilsJNI.AudioServer_getCurrentRightVolumePostAll()

                    _playbackState.value = curState.copy(
                        currentPositionMs = pos,
                        totalDurationMs = if (total > 0) total else curState.totalDurationMs,
                        vuLeft = vuL,
                        vuRight = vuR
                    )
                    delay(50)
                } else {
                    if (curState.vuLeft > 0.002f || curState.vuRight > 0.002f) {
                        val nextL = if (curState.vuLeft > 0.002f) curState.vuLeft * 0.75f else 0f
                        val nextR = if (curState.vuRight > 0.002f) curState.vuRight * 0.75f else 0f
                        _playbackState.value = curState.copy(
                            vuLeft = nextL,
                            vuRight = nextR
                        )
                        delay(50)
                    } else {
                        delay(120)
                    }
                }
            }
        }
    }
}
