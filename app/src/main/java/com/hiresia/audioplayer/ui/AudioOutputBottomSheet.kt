package com.hiresia.audioplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.BottomSheetAudioOutputBinding
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.engine.PlaybackState
import com.hiresia.audioplayer.native.AudioUtilsJNI
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.EffectiveVolumeType
import com.hiresia.audioplayer.repository.StoragePathUtils
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

object AudioOutputBottomSheet {

    fun show(context: Context) {
        val dialog = BottomSheetDialog(context, R.style.Theme_Hiresia)
        val binding = BottomSheetAudioOutputBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val scheme = AudioSettingsRepository.getCurrentScheme()
        val accentCsl = ColorStateList.valueOf(scheme.accent)

        binding.root.backgroundTintList = ColorStateList.valueOf(scheme.surface)
        binding.viewDragHandle.backgroundTintList = ColorStateList.valueOf(scheme.line)
        binding.viewSheetDivider.backgroundTintList = ColorStateList.valueOf(scheme.line)

        binding.tvSheetBitPerfectBadge.backgroundTintList = accentCsl
        binding.tvSheetBitPerfectBadge.setTextColor(scheme.onAccent)
        binding.btnSheetClose.imageTintList = ColorStateList.valueOf(scheme.muted)

        listOf(
            binding.lineNode1,
            binding.lineNode2,
            binding.lineNode3,
            binding.lineNode4,
            binding.lineNode5
        ).forEach {
            it.backgroundTintList = ColorStateList.valueOf(scheme.line)
        }

        binding.btnSheetClose.setOnClickListener {
            dialog.dismiss()
        }

        val dialogScope = CoroutineScope(Dispatchers.Main + Job())

        fun updateUi(state: PlaybackState) {
            val track = state.currentTrack

            // 1. Top Bit-Perfect Badge
            binding.tvSheetBitPerfectBadge.text = when {
                track?.isDsd == true && state.dsdModeBadge.isNotEmpty() -> "BIT-PERFECT • ${state.dsdModeBadge.uppercase()}"
                state.isBitPerfect -> "BIT-PERFECT"
                state.isHiResDriver -> "HI-RES DIRECT"
                state.isUsbExclusive -> "USB EXCLUSIVE"
                else -> "ANDROID MIXER"
            }
            binding.tvSheetBitPerfectBadge.backgroundTintList = accentCsl
            binding.tvSheetBitPerfectBadge.setTextColor(scheme.onAccent)

            binding.tvSourceFormatDetails.setTextColor(scheme.accent)
            binding.tvEngineProcessing.setTextColor(scheme.accent)
            binding.tvVolumeMode.setTextColor(scheme.accent)
            binding.tvOutputFormat.setTextColor(scheme.accent)

            // 2. Node 1: Audio Source
            val rawPath = track?.rawPath?.takeIf { it.isNotEmpty() } ?: track?.path ?: tr("Belum ada trek yang diputar", "No track playing")
            binding.tvSourcePath.text = "Path: ${StoragePathUtils.getDisplayablePath(context, rawPath)}"

            val isSourceActive = track != null
            setNodeActive(binding.layoutNodeSourceCircle, binding.ivNodeSourceIcon, isSourceActive, context)

            if (track != null) {
                if (track.isDsd) {
                    val mhz = String.format("%.2f", (track.dsdRate * 44.1) / 1000.0)
                    binding.tvSourceFormatDetails.text = "DSD${track.dsdRate} • $mhz MHz / 1-bit • 2 Channels"
                } else {
                    val rateKhz = track.sampleRate / 1000.0
                    val rateStr = if (rateKhz % 1.0 == 0.0) "${rateKhz.toInt()} kHz" else "$rateKhz kHz"
                    binding.tvSourceFormatDetails.text = "${track.format} • $rateStr / ${track.bitDepth}-bit • 2 Channels"
                }
            } else {
                binding.tvSourceFormatDetails.text = tr("Tidak aktif", "Inactive")
            }

            // 3. Node 2: Hiresia Engine
            val inputSr = if (track?.isDsd == true) {
                "DSD${track.dsdRate} (${String.format("%.2f", (track.dsdRate * 44.1) / 1000.0)} MHz)"
            } else {
                "${(track?.sampleRate ?: 44100) / 1000.0} kHz"
            }
            binding.tvEngineInput.text = "Input: $inputSr"

            val devRate = AudioUtilsJNI.AudioServer_getDeviceSampleRate().let {
                if (it > 0) it else track?.sampleRate ?: 44100
            }
            binding.tvEngineOutput.text = "Output: ${devRate / 1000.0} kHz"

            binding.tvEngineProcessing.text = when {
                state.isBitPerfect && track?.isDsd == true && state.dsdModeBadge != "DSD→PCM" ->
                    "Processing: Bit-Perfect Direct DSD (${state.dsdModeBadge})"
                state.isBitPerfect ->
                    "Processing: Bit-Perfect Direct Pass (0 dB unity gain)"
                state.isHiResDriver ->
                    "Processing: Direct HAL Bypass (AAudio Exclusive)"
                else ->
                    "Processing: Android AudioFlinger Mixer"
            }

            val isEngineActive = state.isBitPerfect || state.isHiResDriver || state.isUsbExclusive
            setNodeActive(binding.layoutNodeEngineCircle, binding.ivNodeEngineIcon, isEngineActive, context)

            // 4. Node 3: EQ (Graphic Equalizer)
            val isDsdBitstream = track?.isDsd == true && state.dsdModeBadge != "DSD→PCM"
            val isEqUserEnabled = AudioSettingsRepository.isGraphicEqEnabled

            when {
                isDsdBitstream -> {
                    binding.tvEqState.text = tr("State: Bypassed (${state.dsdModeBadge} murni)", "State: Bypassed (Pure ${state.dsdModeBadge})")
                    binding.tvEqConfig.text = "Config: Flat (0.0 dB)"
                    setNodeActive(binding.layoutNodeEqCircle, binding.ivNodeEqIcon, false, context)
                }
                isEqUserEnabled -> {
                    val presetName = AppLanguageManager.translateString(AudioSettingsRepository.activeEqPresetName)
                    val preampStr = String.format(java.util.Locale.US, "%+.1f", AudioSettingsRepository.eqPreampDb)
                    binding.tvEqState.text = "State: Enabled (10-Band 64-bit PEQ · $presetName)"
                    val ag = if (AudioSettingsRepository.isAutoGainEnabled) tr("AutoGain: Aktif", "AutoGain: On") else tr("AutoGain: Nonaktif", "AutoGain: Off")
                    binding.tvEqConfig.text = "Config: Preamp $preampStr dB · $ag"
                    setNodeActive(binding.layoutNodeEqCircle, binding.ivNodeEqIcon, true, context)
                }
                else -> {
                    binding.tvEqState.text = "State: Disabled"
                    binding.tvEqConfig.text = "Config: None"
                    setNodeActive(binding.layoutNodeEqCircle, binding.ivNodeEqIcon, false, context)
                }
            }

            // 5. Node 4: Crossfeed (Bauer bs2b)
            val isCrossfeedUserEnabled = AudioSettingsRepository.isCrossfeedEnabled
            when {
                isDsdBitstream -> {
                    binding.tvCrossfeedState.text = tr("State: Bypassed (${state.dsdModeBadge} murni)", "State: Bypassed (Pure ${state.dsdModeBadge})")
                    setNodeActive(binding.layoutNodeCrossfeedCircle, binding.ivNodeCrossfeedIcon, false, context)
                }
                isCrossfeedUserEnabled -> {
                    binding.tvCrossfeedState.text = "State: Enabled (Level ${AudioSettingsRepository.crossfeedLevel} / Bauer bs2b)"
                    setNodeActive(binding.layoutNodeCrossfeedCircle, binding.ivNodeCrossfeedIcon, true, context)
                }
                else -> {
                    binding.tvCrossfeedState.text = "State: Disabled"
                    setNodeActive(binding.layoutNodeCrossfeedCircle, binding.ivNodeCrossfeedIcon, false, context)
                }
            }

            // 6. Node 5: Volume Engine
            val isUsb = UsbAudioDeviceManager.connectedDac.value != null
            val effType = AudioPlayerController.effectiveVolumeType.value

            binding.tvVolumeMode.text = when (effType) {
                EffectiveVolumeType.HARDWARE_DAC -> {
                    val limStr = if (AudioSettingsRepository.isDacSafeLimitEnabled) tr("Maks. 80%", "Max 80%") else tr("Penuh 100% / 0 dB", "Full 100% / 0 dB")
                    "Mode: Hardware DAC Volume (Analog UAC Mixer, $limStr)"
                }
                EffectiveVolumeType.SOFTWARE -> {
                    val curve = when (AudioSettingsRepository.volumeCurve) {
                        0 -> "Linear"
                        1 -> "Perceptual Cubic"
                        else -> "Logarithmic -96dB"
                    }
                    val dither = if (AudioSettingsRepository.isDitherEnabled) " + TPDF Dither" else ""
                    "Mode: Software Volume 64-bit Ramp ($curve$dither)"
                }
                EffectiveVolumeType.INTERNAL_SYSTEM ->
                    "Mode: Internal System Gain (C++ DSP 0.0 dB Bit-Perfect)"
                EffectiveVolumeType.BIT_PERFECT_LOCKED ->
                    "Mode: Bit-Perfect Locked (0.0 dB Unity Gain)"
            }

            binding.tvVolumeLevel.text = "Level: ${AudioPlayerController.getNowPlayingVolumeLabel()}"
            val isVolActive = (effType != EffectiveVolumeType.BIT_PERFECT_LOCKED)
            setNodeActive(binding.layoutNodeVolumeCircle, binding.ivNodeVolumeIcon, isVolActive, context)

            // 7. Node 6: Signal Output
            binding.tvOutputDriver.text = when {
                state.isUsbExclusive -> "Output: USB Exclusive Driver (Bypass Android Audio)"
                state.isHiResDriver -> "Output: Hi-Res Direct Driver (AAudio Exclusive HAL)"
                else -> "Output: Android System Mixer (AudioFlinger)"
            }

            val dac = UsbAudioDeviceManager.connectedDac.value
            binding.tvOutputDevice.text = if (isUsb) {
                "Device: DAC (${dac?.name ?: "USB DAC"})"
            } else {
                "Device: Internal Phone Audio (Speaker / Headphone)"
            }

            binding.tvOutputRate.text = "Sampling Rate: ${devRate / 1000.0} kHz"
            binding.tvOutputBits.text = "Bits: ${track?.bitDepth ?: 16} bits"
            binding.tvOutputChannels.text = "Channels: 2 (Stereo)"

            binding.tvOutputFormat.text = "Format: " + when {
                track?.isDsd == true && state.dsdModeBadge == "DoP" ->
                    "DoP 1.1 (DSD over PCM • Marker 0x05/0xFA)"
                track?.isDsd == true && state.dsdModeBadge == "Native DSD" ->
                    "Native DSD (1-bit Raw bitstream)"
                track?.isDsd == true ->
                    "DSD to PCM Decimated (176.4 kHz / 24-bit)"
                else ->
                    "Direct Integer PCM"
            }

            val isOutputActive = (track != null || state.isPlaying)
            setNodeActive(binding.layoutNodeOutputCircle, binding.ivNodeOutputIcon, isOutputActive, context)
        }

        // Live observation while bottom sheet is open
        dialogScope.launch {
            AudioPlayerController.playbackState.collectLatest { state ->
                updateUi(state)
            }
        }
        dialogScope.launch {
            AudioPlayerController.volumeFraction.collectLatest {
                updateUi(AudioPlayerController.playbackState.value)
            }
        }

        dialog.setOnDismissListener {
            dialogScope.cancel()
        }

        dialog.show()
    }

    private fun setNodeActive(circleLayout: View, iconView: android.widget.ImageView, isActive: Boolean, context: Context) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        if (isActive) {
            circleLayout.setBackgroundResource(R.drawable.bg_node_circle_active)
            circleLayout.backgroundTintList = ColorStateList.valueOf(scheme.accent)
            iconView.imageTintList = ColorStateList.valueOf(scheme.onAccent)
        } else {
            circleLayout.setBackgroundResource(R.drawable.bg_node_circle_inactive)
            circleLayout.backgroundTintList = ColorStateList.valueOf(scheme.surface2)
            iconView.imageTintList = ColorStateList.valueOf(scheme.muted)
        }
    }
}
