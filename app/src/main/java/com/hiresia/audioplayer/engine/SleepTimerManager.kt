package com.hiresia.audioplayer.engine

import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audiophile Sleep Timer Manager (C++ Engine).
 * Supports minute-based timer, custom minute input, finish-current-track option,
 * and smooth fade-out volume attenuation before pause.
 */
object SleepTimerManager {
    private const val TAG = "SleepTimerManager"
    private const val PREFS_NAME = "hiresia_sleep_timer"
    private const val KEY_LAST_MINUTES = "LastSleepTimerMinutes"
    private const val KEY_FINISH_TRACK = "SleepTimerFinishTrack"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countDownTimer: CountDownTimer? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _selectedMinutes = MutableStateFlow(30)
    val selectedMinutes: StateFlow<Int> = _selectedMinutes.asStateFlow()

    var finishTrackBeforeSleep: Boolean = false
        private set

    private var isPendingPauseOnTrackFinished = false

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _selectedMinutes.value = sp.getInt(KEY_LAST_MINUTES, 30).coerceAtLeast(1)
        finishTrackBeforeSleep = sp.getBoolean(KEY_FINISH_TRACK, false)
    }

    fun start(context: Context, minutes: Int, finishTrack: Boolean = false) {
        cancel()
        if (minutes <= 0) return

        finishTrackBeforeSleep = finishTrack
        isPendingPauseOnTrackFinished = false
        _selectedMinutes.value = minutes
        _remainingSeconds.value = minutes * 60L
        _isActive.value = true

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_LAST_MINUTES, minutes)
            .putBoolean(KEY_FINISH_TRACK, finishTrack)
            .apply()

        Log.i(TAG, "Sleep timer started: $minutes min (finishTrack=$finishTrack)")

        countDownTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                _remainingSeconds.value = (millisUntilFinished / 1000L).coerceAtLeast(0L)
            }

            override fun onFinish() {
                _remainingSeconds.value = 0L
                _isActive.value = false
                Log.i(TAG, "Sleep timer deadline reached!")

                if (finishTrackBeforeSleep && AudioPlayerController.playbackState.value.isPlaying) {
                    Log.i(TAG, "Sleep timer waiting for current track to end...")
                    isPendingPauseOnTrackFinished = true
                } else {
                    triggerSleepPause()
                }
            }
        }.start()
    }

    fun cancel() {
        countDownTimer?.cancel()
        countDownTimer = null
        _isActive.value = false
        _remainingSeconds.value = 0L
        isPendingPauseOnTrackFinished = false
        Log.i(TAG, "Sleep timer cancelled")
    }

    /**
     * Called when a track finishes playing in QueueManager.
     * If the timer expired and finishTrackBeforeSleep was set, pause immediately.
     */
    fun checkAndHandleTrackFinished(): Boolean {
        if (isPendingPauseOnTrackFinished) {
            Log.i(TAG, "Current track finished, executing sleep timer pause now.")
            isPendingPauseOnTrackFinished = false
            triggerSleepPause()
            return true
        }
        return false
    }

    private fun triggerSleepPause() {
        scope.launch {
            if (AudioPlayerController.playbackState.value.isPlaying) {
                // Smooth 2.5s fade out to avoid abrupt cutoff
                val initialVol = AudioPlayerController.dacVolume.value
                val steps = 15
                val stepDelay = 2500L / steps
                for (i in 1..steps) {
                    val factor = 1.0f - (i.toFloat() / steps)
                    AudioPlayerController.setVolume(initialVol * factor)
                    delay(stepDelay)
                }
                AudioPlayerController.pause()
                // Restore original volume so subsequent playback starts at proper level
                AudioPlayerController.setVolume(initialVol)
            } else {
                AudioPlayerController.pause()
            }
            cancel()
        }
    }

    fun getFormattedTime(): String {
        val totalSec = _remainingSeconds.value
        if (totalSec <= 0) return "Mati"
        val mins = totalSec / 60
        val secs = totalSec % 60
        return if (mins >= 60) {
            val hrs = mins / 60
            val remMins = mins % 60
            String.format("%d j %02d m", hrs, remMins)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }
}
