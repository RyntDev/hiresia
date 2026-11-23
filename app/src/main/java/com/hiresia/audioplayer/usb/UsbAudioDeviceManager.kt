package com.hiresia.audioplayer.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.native.AudioUtilsJNI
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

data class ConnectedUsbDac(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val isBitPerfectSupported: Boolean = true,
    val isUac2: Boolean = true,
    val supportedSampleRates: List<Int> = emptyList()
)

object UsbAudioDeviceManager {
    private const val TAG = "UsbAudioDeviceManager"
    const val ACTION_USB_PERMISSION = "com.hiresia.audioplayer.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var appContext: Context

    private val _connectedDac = MutableStateFlow<ConnectedUsbDac?>(null)
    val connectedDac: StateFlow<ConnectedUsbDac?> = _connectedDac.asStateFlow()

    private var activeConnection: UsbDeviceConnection? = null
    @Volatile var isDynamicReceiverRegistered = false
        private set
    @Volatile private var isPermissionRequested = false
    @Volatile private var lastPermissionRequestTime = 0L
    @Volatile private var isConnecting = false
    @Volatile private var isRecoveringUsb = false
    @Volatile private var lastAutoRecoverAtMs = 0L
    private var detachVerifyJob: Job? = null
    private val usbOpMutex = Mutex()
    private var currentActivityRef: WeakReference<Activity>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isUsbTransitionInProgress(): Boolean = isConnecting || isRecoveringUsb

    // Tracks devices that the user explicitly denied permission for during this physical session
    // (Rate limit session lock, completely preventing repeated spam permission dialogs)
    private val deniedDeviceKeys = mutableSetOf<String>()

    fun setCurrentActivity(activity: Activity?) {
        currentActivityRef = activity?.let { WeakReference(it) }
    }

    fun resetPermissionThrottle() {
        isPermissionRequested = false
        lastPermissionRequestTime = 0L
    }

    fun getDetectedAudioDevice(): UsbDevice? {
        if (!::usbManager.isInitialized) return null
        return usbManager.deviceList.values.firstOrNull { isUsbAudioDevice(it) }
    }

    fun isCurrentDacPhysicallyPresent(): Boolean {
        if (!::usbManager.isInitialized) return false
        val dac = _connectedDac.value ?: return false
        return usbManager.deviceList.values.any {
            it.vendorId == dac.vendorId && it.productId == dac.productId
        }
    }

    private fun scheduleVerifiedDetachCheck(sourceTag: String) {
        detachVerifyJob?.cancel()
        detachVerifyJob = scope.launch {
            delay(350)
            val currentDac = _connectedDac.value ?: return@launch
            val stillConnected = ::usbManager.isInitialized && usbManager.deviceList.values.any {
                it.vendorId == currentDac.vendorId && it.productId == currentDac.productId
            }
            if (!stillConnected) {
                Log.w(TAG, "$sourceTag verified physical USB removal of ${currentDac.name}")
                onDeviceDetached(null)
            } else {
                Log.d(TAG, "$sourceTag event ignored: USB DAC ${currentDac.name} is still physically connected on bus")
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            Log.i(TAG, "USB Broadcast received: $action for device: ${device?.productName}")

            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    resetPermissionThrottle()
                    if (device != null) {
                        val key = "${device.vendorId}:${device.productId}"
                        deniedDeviceKeys.remove(key) // Physical re-attach resets previous denial
                        if (isUsbAudioDevice(device)) {
                            connectDevice(device)
                        }
                    } else {
                        scanForAudioDevices()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    resetPermissionThrottle()
                    if (device != null) {
                        val key = "${device.vendorId}:${device.productId}"
                        deniedDeviceKeys.remove(key)
                        onDeviceDetached(device)
                    } else {
                        onDeviceDetached(null)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    isPermissionRequested = false
                    lastPermissionRequestTime = 0L
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB Permission result: granted=$granted for ${device?.productName}")
                    val targetDev = device ?: getDetectedAudioDevice()
                    if (granted) {
                        if (targetDev != null) {
                            val key = "${targetDev.vendorId}:${targetDev.productId}"
                            deniedDeviceKeys.remove(key)
                            connectDevice(targetDev)
                        } else {
                            scanForAudioDevices()
                        }
                    } else {
                        if (targetDev != null) {
                            val key = "${targetDev.vendorId}:${targetDev.productId}"
                            deniedDeviceKeys.add(key)
                            Log.w(TAG, "User denied USB DAC permission for ${targetDev.productName} ($key). Will NOT prompt again automatically.")
                        }
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 1) {
                        scanForAudioDevices()
                        if (_connectedDac.value == null) {
                            AudioPlayerController.onInternalAudioRouteChanged(deviceRemoved = false)
                        }
                    } else if (state == 0) {
                        if (_connectedDac.value != null) {
                            scheduleVerifiedDetachCheck("ACTION_HEADSET_PLUG(state=0)")
                        } else {
                            AudioPlayerController.onInternalAudioRouteChanged(deviceRemoved = true)
                        }
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        // Register USB lifecycle and permission receiver dynamically
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(usbReceiver, filter)
            }
            isDynamicReceiverRegistered = true
            Log.i(TAG, "Dynamic USB receiver registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register dynamic USB receiver: ${e.message}")
        }

        // Register AudioManager AudioDeviceCallback for instant hardware insertion/removal callbacks
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
                audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        Log.i(TAG, "AudioManager AudioDeviceCallback: Devices added! Scanning USB DAC / checking internal route...")
                        scanForAudioDevices()
                        if (_connectedDac.value == null) {
                            AudioPlayerController.onInternalAudioRouteChanged(deviceRemoved = false)
                        }
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        Log.i(TAG, "AudioManager AudioDeviceCallback: Devices removed.")
                        if (_connectedDac.value != null) {
                            scheduleVerifiedDetachCheck("AudioDeviceCallback.onAudioDevicesRemoved")
                        } else {
                            AudioPlayerController.onInternalAudioRouteChanged(deviceRemoved = true)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register AudioDeviceCallback: ${e.message}")
        }

        // Initial event-driven scan on app start (no brutal watchdog polling loop!)
        scanForAudioDevices()
    }

    fun scanForAudioDevices(forcePrompt: Boolean = false) {
        val current = _connectedDac.value
        if (current != null && activeConnection != null) {
            val isPresent = usbManager.deviceList.values.any { 
                it.vendorId == current.vendorId && it.productId == current.productId 
            }
            if (isPresent) {
                Log.d(TAG, "USB Audio DAC ${current.name} is already connected, skipping re-scan.")
                return
            }
        }

        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isUsbAudioDevice(device)) {
                val key = "${device.vendorId}:${device.productId}"
                if (forcePrompt) {
                    deniedDeviceKeys.remove(key)
                }
                connectDevice(device)
                break
            }
        }
    }

    fun isUsbAudioDevice(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId

        // 1. Blacklist filter for non-audio USB devices
        if (vid == 0x046d && (pid == 0xc52b || pid == 0xc52f)) return false // Logitech Unifying
        if (vid == 0x1058 || vid == 0x152d || vid == 0x174c || vid == 0x05e1) return false // Mass Storage
        if (vid == 0x0480 || vid == 0x0bc2 || vid == 0x3206) return false // External HDDs / Seagate
        if (vid == 0x1a86 && pid == 0x7523) return false // CH340 Serial
        if (vid == 0x05c6 && (pid == 0x960b || pid == 0x9605)) return false // Qualcomm Diag

        // 2. Direct Audio Class
        val devClass = device.deviceClass
        if (devClass == UsbConstants.USB_CLASS_AUDIO) return true

        // 3. Known DAC Vendor IDs
        if (vid == 0x0bda || vid == 0x2fc6 || vid == 0x2972 || vid == 0x20b1 || vid == 0x0544 || 
            vid == 0x1a40 || vid == 0x6805 || vid == 0x12230 || vid == 0x10610 || vid == 0x9431 || 
            vid == 0x1348 || vid == 0x2462 || vid == 0x1204 || vid == 0x9318) {
            return true
        }

        // 4. Per-Interface / Composite classes (0, 0xEF, 0xFF)
        if (devClass == 0 || devClass == 0xEF || devClass == 0xFF) {
            if (usbManager.hasPermission(device)) {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                        return true
                    }
                }
                return false
            }
            return true
        }

        return false
    }

    fun connectDevice(device: UsbDevice) {
        val current = _connectedDac.value
        if (current != null && current.vendorId == device.vendorId && current.productId == device.productId && activeConnection != null) {
            Log.d(TAG, "Device ${device.productName} is already connected, skipping duplicate connectDevice()")
            return
        }

        val key = "${device.vendorId}:${device.productId}"

        if (!usbManager.hasPermission(device)) {
            // Check if user previously denied permission during this physical connection session
            if (deniedDeviceKeys.contains(key)) {
                Log.d(TAG, "Device $key was previously denied by user; suppressing automatic permission prompt.")
                return
            }

            val now = System.currentTimeMillis()
            if (isPermissionRequested && (now - lastPermissionRequestTime < 4000)) {
                Log.d(TAG, "USB Permission request already in flight for ${device.productName}, waiting for user action")
                return
            }
            Log.i(TAG, "Requesting USB permission for ${device.productName} ($key)")
            isPermissionRequested = true
            lastPermissionRequestTime = now
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(appContext.packageName)
                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
            val reqContext = currentActivityRef?.get() ?: appContext
            val permissionIntent = PendingIntent.getBroadcast(
                reqContext,
                0,
                intent,
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        // Permission is granted
        isPermissionRequested = false
        lastPermissionRequestTime = 0L
        deniedDeviceKeys.remove(key)

        if (isConnecting) {
            Log.d(TAG, "Connection already in progress for ${device.productName}, skipping")
            return
        }

        isConnecting = true
        scope.launch {
            usbOpMutex.withLock {
                try {
                    val curInLock = _connectedDac.value
                    if (curInLock != null && curInLock.vendorId == device.vendorId &&
                        curInLock.productId == device.productId && activeConnection != null
                    ) {
                        Log.d(TAG, "Device ${device.productName} already connected inside lock, skipping")
                        return@withLock
                    }

                    // Pre-pause audio before native connection handoff to prevent glitches / clicks
                    withContext(Dispatchers.Main) {
                        AudioPlayerController.prepareForUsbAttach()
                    }

                    // Close any stale connection cleanly via C++ FIRST so interfaces/kernel driver are consistent
                    if (activeConnection != null) {
                        AudioUtilsJNI.AudioServer_USBAudioDeviceWasDetached()
                        try { activeConnection?.close() } catch (_: Exception) {}
                        activeConnection = null
                    }

                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        activeConnection = connection

                        // Note: C++ Engine USB Audio architecture:
                        // DO NOT call connection.claimInterface(intf, true) in Java!
                        // Interface claiming is safely and cleanly performed inside C++ native driver!

                        val fd = connection.fileDescriptor
                        val deviceName = device.productName ?: "USB Audio DAC"
                        val rawDescriptors = connection.rawDescriptors ?: ByteArray(0)

                        Log.i(TAG, "Opening Linux USB FS direct connection (FD: $fd, RawDescriptors: ${rawDescriptors.size} bytes)...")

                        AudioUtilsJNI.AudioServer_setBlockUsbHid(AudioSettingsRepository.isBlockUsbHidEnabled)
                        val success = AudioUtilsJNI.AudioServer_InitUSBDeviceByName(
                            fd,
                            device.vendorId,
                            device.productId,
                            deviceName,
                            rawDescriptors
                        )

                        if (success) {
                            val isUac2 = AudioUtilsJNI.AudioServer_isUSBUAC2()
                            val rates = AudioUtilsJNI.AudioServer_getUSBSupportedSampleRates()
                            val dac = ConnectedUsbDac(
                                name = deviceName,
                                vendorId = device.vendorId,
                                productId = device.productId,
                                isBitPerfectSupported = AudioUtilsJNI.AudioServer_isBitPerfectPossibleForUSBAudio(),
                                isUac2 = isUac2,
                                supportedSampleRates = rates.toList()
                            )
                            _connectedDac.value = dac
                            Log.i(TAG, "Hiresia connected to Bit-Perfect USB DAC: $deviceName (UAC2: $isUac2, Supported Rates: ${rates.joinToString()} Hz)")

                            withContext(Dispatchers.Main) {
                                AudioPlayerController.onUsbDeviceAttached(dac)
                            }
                        } else {
                            Log.e(TAG, "Native AudioServer_InitUSBDeviceByName failed for $deviceName")
                            AudioUtilsJNI.AudioServer_USBAudioDeviceWasDetached()
                            try { activeConnection?.close() } catch (_: Exception) {}
                            activeConnection = null
                        }
                    } else {
                        Log.e(TAG, "Failed to open UsbDeviceConnection for ${device.productName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception connecting to USB device", e)
                } finally {
                    isConnecting = false
                }
            }
        }
    }

    fun onDeviceDetached(device: UsbDevice? = null) {
        val current = _connectedDac.value
        if (device != null && current != null &&
            (device.vendorId != current.vendorId || device.productId != current.productId)
        ) {
            Log.d(TAG, "Ignoring detach of non-active USB device: ${device.productName}")
            return
        }
        if (device != null && current == null && !isUsbAudioDevice(device)) {
            return
        }

        isPermissionRequested = false
        lastPermissionRequestTime = 0L
        isConnecting = false
        detachVerifyJob?.cancel()
        if (device != null) {
            val key = "${device.vendorId}:${device.productId}"
            deniedDeviceKeys.remove(key)
        }
        if (current == null && activeConnection == null) {
            return
        }

        Log.i(TAG, "USB Audio Device detached: ${device?.productName ?: current?.name ?: "Current DAC"}")
        scope.launch {
            usbOpMutex.withLock {
                val activeNow = _connectedDac.value
                if (activeNow == null && activeConnection == null) return@withLock
                _connectedDac.value = null

                withContext(Dispatchers.Main) {
                    AudioPlayerController.prepareForUsbDetach()
                }
                // Release native USB interfaces & reconnect kernel driver BEFORE closing activeConnection FD!
                AudioUtilsJNI.AudioServer_USBAudioDeviceWasDetached()
                try { activeConnection?.close() } catch (_: Exception) {}
                activeConnection = null

                withContext(Dispatchers.Main) {
                    AudioPlayerController.onUsbDeviceDetached()
                }
            }
        }
    }

    fun handleNativeUsbError(wasPlaying: Boolean) {
        scope.launch {
            usbOpMutex.withLock {
                val failedDac = _connectedDac.value ?: return@withLock
                isRecoveringUsb = true
                try {
                    Log.w(TAG, "Native USB stream error reported for ${failedDac.name} (wasPlaying=$wasPlaying)")

                    withContext(Dispatchers.Main) {
                        AudioPlayerController.prepareForUsbDetach()
                    }
                    AudioUtilsJNI.AudioServer_USBAudioDeviceWasDetached()
                    try { activeConnection?.close() } catch (_: Exception) {}
                    activeConnection = null
                    _connectedDac.value = null

                    delay(250)

                    val now = System.currentTimeMillis()
                    val dev = if (::usbManager.isInitialized) {
                        usbManager.deviceList.values.firstOrNull {
                            it.vendorId == failedDac.vendorId && it.productId == failedDac.productId
                        }
                    } else null

                    if (dev != null && usbManager.hasPermission(dev) && (now - lastAutoRecoverAtMs) >= 8000L) {
                        lastAutoRecoverAtMs = now
                        Log.i(TAG, "USB DAC ${failedDac.name} is still physically connected! Attempting clean auto-recovery...")
                        val conn = usbManager.openDevice(dev)
                        if (conn != null) {
                            activeConnection = conn
                            AudioUtilsJNI.AudioServer_setBlockUsbHid(AudioSettingsRepository.isBlockUsbHidEnabled)
                            val ok = AudioUtilsJNI.AudioServer_InitUSBDeviceByName(
                                conn.fileDescriptor,
                                dev.vendorId,
                                dev.productId,
                                dev.productName ?: failedDac.name,
                                conn.rawDescriptors ?: ByteArray(0)
                            )
                            if (ok) {
                                val recoveredDac = ConnectedUsbDac(
                                    name = dev.productName ?: failedDac.name,
                                    vendorId = dev.vendorId,
                                    productId = dev.productId,
                                    isBitPerfectSupported = AudioUtilsJNI.AudioServer_isBitPerfectPossibleForUSBAudio(),
                                    isUac2 = AudioUtilsJNI.AudioServer_isUSBUAC2(),
                                    supportedSampleRates = AudioUtilsJNI.AudioServer_getUSBSupportedSampleRates().toList()
                                )
                                _connectedDac.value = recoveredDac
                                Log.i(TAG, "Auto-recovery succeeded for USB DAC: ${recoveredDac.name}")
                                withContext(Dispatchers.Main) {
                                    AudioPlayerController.onUsbDeviceAttached(
                                        recoveredDac,
                                        forceResume = wasPlaying,
                                        isRecovered = true
                                    )
                                }
                                return@withLock
                            } else {
                                AudioUtilsJNI.AudioServer_USBAudioDeviceWasDetached()
                                try { activeConnection?.close() } catch (_: Exception) {}
                                activeConnection = null
                            }
                        }
                    }

                    Log.i(TAG, "Switching cleanly to Internal Audio route after USB failure/removal")
                    withContext(Dispatchers.Main) {
                        AudioPlayerController.onUsbDeviceDetached()
                    }
                } finally {
                    isRecoveringUsb = false
                }
            }
        }
    }
}
