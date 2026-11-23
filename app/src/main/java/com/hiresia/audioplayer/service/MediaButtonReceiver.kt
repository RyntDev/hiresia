package com.hiresia.audioplayer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (AudioSettingsRepository.isBlockUsbHidEnabled && UsbAudioDeviceManager.connectedDac.value != null) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_ASSIST,
                    KeyEvent.KEYCODE_VOICE_ASSIST -> return
                }
            }
            if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        AudioPlayerController.togglePlayPause()
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> AudioPlayerController.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> AudioPlayerController.pause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> AudioPlayerController.next()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AudioPlayerController.previous()
                    KeyEvent.KEYCODE_MEDIA_STOP -> AudioPlayerController.stop()
                }
            }
        }
    }
}
