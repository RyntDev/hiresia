package com.hiresia.audioplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.native.AudioUtilsJNI
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager

class HiresiaApplication : Application() {

    companion object {
        const val CHANNEL_ID = "hiresia_media_playback_channel"
        lateinit var instance: HiresiaApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Settings Repository early and apply saved DayNight theme before any Activity starts
        AudioSettingsRepository.init(this)
        AudioSettingsRepository.applyThemeMode()

        createNotificationChannel()

        // Initialize Native Audio Server
        try {
            val initialized = AudioUtilsJNI.AudioServer_initAndRun()
            Log.i("HiresiaApplication", "Hiresia Native AudioServer initialized: $initialized")
        } catch (e: Throwable) {
            Log.e("HiresiaApplication", "Error initializing Hiresia Native AudioServer", e)
        }

        // Initialize Audio Controller & USB Device Manager
        AudioPlayerController.init(this)
        UsbAudioDeviceManager.init(this)
        com.hiresia.audioplayer.engine.SleepTimerManager.init(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hiresia Bit-Perfect Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "High-Resolution and Bit-Perfect USB Audio Playback Service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
