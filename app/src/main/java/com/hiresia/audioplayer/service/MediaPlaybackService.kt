package com.hiresia.audioplayer.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import android.util.Log
import androidx.media.VolumeProviderCompat
import com.hiresia.audioplayer.HiresiaApplication
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.EffectiveVolumeType
import com.hiresia.audioplayer.ui.MainActivity
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MediaPlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.hiresia.audioplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.hiresia.audioplayer.ACTION_PAUSE"
        const val ACTION_NEXT = "com.hiresia.audioplayer.ACTION_NEXT"
        const val ACTION_PREV = "com.hiresia.audioplayer.ACTION_PREV"
        const val ACTION_STOP = "com.hiresia.audioplayer.ACTION_STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val NOTIFICATION_ID = 2001
        private const val TAG = "MediaPlaybackService"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeProvider: VolumeProviderCompat? = null
    private var lastUseRemoteRouting: Boolean? = null
    private var keepaliveAudioTrack: AudioTrack? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mediaSession = MediaSessionCompat(this, "HiresiaMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    AudioPlayerController.play()
                }

                override fun onPause() {
                    AudioPlayerController.pause()
                }

                override fun onSkipToNext() {
                    AudioPlayerController.next()
                }

                override fun onSkipToPrevious() {
                    AudioPlayerController.previous()
                }

                override fun onStop() {
                    AudioPlayerController.stop()
                    stopForeground(true)
                    stopSelf()
                }
            })
            isActive = true
        }

        serviceScope.launch {
            data class RoutingTuple(
                val isUsbDac: Boolean,
                val effType: EffectiveVolumeType,
                val isPlaying: Boolean,
                val screenOffFix: Boolean,
                val interceptKeys: Boolean
            )
            combine(
                UsbAudioDeviceManager.connectedDac,
                AudioPlayerController.effectiveVolumeType,
                AudioPlayerController.playbackState.map { it.isPlaying }.distinctUntilChanged(),
                AudioSettingsRepository.android12VolumeFixFlow,
                AudioSettingsRepository.interceptVolumeKeysFlow
            ) { dac, effType, isPlaying, screenOffFix, interceptKeys ->
                RoutingTuple(dac != null, effType, isPlaying, screenOffFix, interceptKeys)
            }.distinctUntilChanged().collectLatest { state ->
                updatePlaybackVolumeRouting(state.isUsbDac, state.effType, state.interceptKeys)
                updateScreenOffKeepalive(state.isUsbDac, state.isPlaying, state.screenOffFix)
                updatePlaybackWakeLock(state.isPlaying)
            }
        }

        serviceScope.launch {
            AudioPlayerController.volumeFraction.collectLatest { vol ->
                volumeProvider?.currentVolume = (vol * 100f).roundToInt().coerceIn(0, 100)
            }
        }
    }

    private fun updatePlaybackVolumeRouting(
        isUsbDac: Boolean,
        effType: EffectiveVolumeType,
        interceptKeys: Boolean
    ) {
        val useRemoteVolumeProvider = isUsbDac ||
            (effType == EffectiveVolumeType.SOFTWARE && interceptKeys)
        if (lastUseRemoteRouting == useRemoteVolumeProvider) return
        lastUseRemoteRouting = useRemoteVolumeProvider

        if (useRemoteVolumeProvider) {
            val curVol = (AudioPlayerController.volumeFraction.value * 100f).roundToInt().coerceIn(0, 100)
            val provider = object : VolumeProviderCompat(
                VOLUME_CONTROL_ABSOLUTE,
                100,
                curVol
            ) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) {
                        val newVol = AudioPlayerController.adjustVolume(direction)
                        currentVolume = (newVol * 100f).roundToInt().coerceIn(0, 100)
                    }
                }

                override fun onSetVolumeTo(volume: Int) {
                    val newVol = (volume / 100f).coerceIn(0f, 1f)
                    AudioPlayerController.setVolume(newVol)
                    currentVolume = (AudioPlayerController.volumeFraction.value * 100f).roundToInt().coerceIn(0, 100)
                }
            }
            volumeProvider = provider
            mediaSession.setPlaybackToRemote(provider)
            Log.i(TAG, "MediaSession set to REMOTE volume (isUsb=$isUsbDac, mode=$effType)")
        } else {
            volumeProvider = null
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "MediaSession set to LOCAL volume (Android OS STREAM_MUSIC)")
        }
    }

    private fun updateScreenOffKeepalive(isUsbDac: Boolean, isPlaying: Boolean, screenOffFix: Boolean) {
        val shouldRun = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isUsbDac &&
            isPlaying &&
            screenOffFix
        if (shouldRun) {
            startSilentKeepaliveTrack()
        } else {
            stopSilentKeepaliveTrack()
        }
    }

    private fun startSilentKeepaliveTrack() {
        if (keepaliveAudioTrack != null) return
        try {
            val sampleRate = 48000
            val bufBytes = sampleRate / 10 * 4 // 100 ms silent stereo 16-bit buffer
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(bufBytes)
            val alignedBytes = ((minBuf + 3) / 4) * 4
            val zeros = ByteArray(alignedBytes)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(alignedBytes)
                .build()

            track.write(zeros, 0, alignedBytes)
            track.setLoopPoints(0, alignedBytes / 4, -1)
            track.setVolume(0.0f)
            track.play()
            keepaliveAudioTrack = track
            Log.i(TAG, "Android 12+ screen-off volume keepalive track started")
        } catch (e: Exception) {
            Log.w(TAG, "Screen-off keepalive track unavailable: ${e.message}")
            stopSilentKeepaliveTrack()
        }
    }

    private fun updatePlaybackWakeLock(isPlaying: Boolean) {
        try {
            if (isPlaying) {
                if (playbackWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    playbackWakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Hiresia::BitPerfectAudioWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (playbackWakeLock?.isHeld == false) {
                    playbackWakeLock?.acquire(4 * 60 * 60 * 1000L)
                }
            } else {
                releasePlaybackWakeLock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock update error: ${e.message}")
        }
    }

    private fun releasePlaybackWakeLock() {
        try {
            if (playbackWakeLock?.isHeld == true) {
                playbackWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun stopSilentKeepaliveTrack() {
        val track = keepaliveAudioTrack ?: return
        keepaliveAudioTrack = null
        try {
            track.pause()
            track.flush()
            track.release()
            Log.i(TAG, "Android 12+ screen-off volume keepalive track stopped")
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Hiresia"
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Bit-Perfect Audio Engine"

        when (action) {
            ACTION_PLAY -> {
                startForeground(NOTIFICATION_ID, buildNotification(title, artist, true))
            }
            ACTION_PAUSE -> {
                startForeground(NOTIFICATION_ID, buildNotification(title, artist, false))
            }
            ACTION_NEXT -> AudioPlayerController.next()
            ACTION_PREV -> AudioPlayerController.previous()
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags)

        val prevIntent = PendingIntent.getService(this, 1, Intent(this, MediaPlaybackService::class.java).apply {
            this.action = ACTION_PREV
        }, flags)

        val playPauseAction = if (isPlaying) {
            val pauseIntent = PendingIntent.getService(this, 2, Intent(this, MediaPlaybackService::class.java).apply {
                this.action = ACTION_PAUSE
            }, flags)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
        } else {
            val playIntent = PendingIntent.getService(this, 2, Intent(this, MediaPlaybackService::class.java).apply {
                this.action = ACTION_PLAY
            }, flags)
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", playIntent)
        }

        val nextIntent = PendingIntent.getService(this, 3, Intent(this, MediaPlaybackService::class.java).apply {
            this.action = ACTION_NEXT
        }, flags)

        val stopIntent = PendingIntent.getService(this, 4, Intent(this, MediaPlaybackService::class.java).apply {
            this.action = ACTION_STOP
        }, flags)

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .build()
        )

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    AudioPlayerController.playbackState.value.currentPositionMs,
                    1.0f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, HiresiaApplication.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Hiresia Bit-Perfect Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setStyle(style)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .build()
    }

    override fun onDestroy() {
        releasePlaybackWakeLock()
        stopSilentKeepaliveTrack()
        serviceScope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
