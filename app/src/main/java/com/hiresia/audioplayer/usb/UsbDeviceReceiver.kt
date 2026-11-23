package com.hiresia.audioplayer.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return

        if (UsbAudioDeviceManager.isDynamicReceiverRegistered) {
            Log.d("UsbDeviceReceiver", "Skipping manifest duplicate $action (handled by dynamic receiver)")
            return
        }

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i("UsbDeviceReceiver", "USB device attached: ${device.productName}")
                if (UsbAudioDeviceManager.isUsbAudioDevice(device)) {
                    UsbAudioDeviceManager.connectDevice(device)
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i("UsbDeviceReceiver", "USB device detached: ${device.productName}")
                UsbAudioDeviceManager.onDeviceDetached(device)
            }
        }
    }
}
