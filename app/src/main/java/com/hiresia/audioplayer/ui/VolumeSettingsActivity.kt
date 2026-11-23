package com.hiresia.audioplayer.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiresia.audioplayer.databinding.ActivityVolumeSettingsBinding
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.EffectiveVolumeType
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

class VolumeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVolumeSettingsBinding
    private val volumeController by lazy {
        AudiophileVolumeController(this) {
            updateHeroStatusCard()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (volumeController.handleDispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeController.handleKeyDown(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeController.handleKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        volumeController.release()
        super.onDestroy()
    }

    private fun getVolumeModeLabels(): Array<String> = arrayOf(
        tr("Otomatis (DAC = Perangkat Keras, Internal = Perangkat Lunak)", "Automatic (DAC = Hardware, Internal = Software)"),
        tr("Volume Perangkat Keras (Prioritas Mixer DAC)", "Hardware Volume (DAC Mixer Priority)"),
        tr("Volume Perangkat Lunak (Atenuasi DSP 64-bit)", "Software Volume (64-bit DSP Attenuation)"),
        tr("Tanpa Kontrol Volume (Kunci Murni 100% / 0 dB)", "No Volume Control (Bit-Exact Lock 100% / 0 dB)")
    )

    private val stepDbValues = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
    private fun getStepDbLabels(): Array<String> = arrayOf(
        tr("0.5 dB per langkah (Sangat Halus)", "0.5 dB per step (Ultra Fine)"),
        tr("1.0 dB per langkah (Standar Audiofil)", "1.0 dB per step (Audiophile Standard)"),
        tr("1.5 dB per langkah (Seimbang)", "1.5 dB per step (Balanced)"),
        tr("2.0 dB per langkah (Cepat)", "2.0 dB per step (Fast)")
    )

    private fun getHwCurveLabels(): Array<String> = arrayOf(
        tr("Linear dB Bawaan (Presisi Asli DAC)", "Native Linear dB (True DAC Precision)"),
        tr("Perseptual Halus (Rentang Atas Lebih Lebar)", "Smooth Perceptual (Wider Upper Range)"),
        tr("Khusus IEM Sensitif (Kendali Halus di Volume Rendah)", "Sensitive IEM (Fine Low-Level Control)")
    )

    private fun getVolumeCurveLabels(): Array<String> = arrayOf(
        tr("Linear (Proporsional Langsung)", "Linear (Direct Proportional)"),
        tr("Perseptual Kubik (Transisi Alami)", "Perceptual Cubic (Natural Taper)"),
        tr("Logaritmik (-96 dB hingga 0 dB)", "Logarithmic (-96 dB to 0 dB)")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVolumeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemePalette()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOpenVolumePanel.setOnClickListener {
            volumeController.showDialog(autoDismiss = false)
        }
        binding.cardVolumeStatusHero.setOnClickListener {
            volumeController.showDialog(autoDismiss = false)
        }

        // 1. Mode Kontrol Volume
        binding.rowVolumeMode.setOnClickListener {
            val labels = getVolumeModeLabels()
            val currentIdx = AudioSettingsRepository.volumeModePref.coerceIn(0, labels.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Mode Kontrol Volume", "Volume Control Mode"))
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    AudioSettingsRepository.volumeModePref = which
                    AudioPlayerController.syncVolumeToNative()
                    refreshAllDescriptions()
                    dialog.dismiss()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 2. Langkah Perubahan Volume
        binding.rowVolumeStepDb.setOnClickListener {
            val labels = getStepDbLabels()
            val cur = AudioSettingsRepository.volumeStepDb
            val curIdx = stepDbValues.indices.minByOrNull { abs(stepDbValues[it] - cur) } ?: 1
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Langkah Perubahan Volume", "Volume Step Size"))
                .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                    AudioSettingsRepository.volumeStepDb = stepDbValues[which]
                    refreshAllDescriptions()
                    dialog.dismiss()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 3. Kendali Tombol Volume Fisik
        binding.switchInterceptVolumeKeys.isChecked = AudioSettingsRepository.isInterceptVolumeKeys
        binding.switchInterceptVolumeKeys.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isInterceptVolumeKeys = isChecked
            val msg = if (isChecked) {
                tr("Kendali tombol volume fisik diaktifkan", "Physical volume key control enabled")
            } else {
                tr("Kendali tombol volume fisik dinonaktifkan", "Physical volume key control disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 4. Tutup Otomatis Panel Volume
        binding.switchVolumeAutoFade.isChecked = AudioSettingsRepository.isVolumeDialogAutoFade
        binding.switchVolumeAutoFade.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isVolumeDialogAutoFade = isChecked
            val msg = if (isChecked) {
                tr("Panel volume tertutup otomatis setelah 2,5 detik", "Volume panel auto-dismisses after 2.5 seconds")
            } else {
                tr("Panel volume tetap terbuka hingga ditutup manual", "Volume panel stays open until closed manually")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 5. Kurva Volume Perangkat Keras DAC
        binding.rowHardwareVolumeCurve.setOnClickListener {
            val labels = getHwCurveLabels()
            val cur = AudioSettingsRepository.hardwareVolumeCurve.coerceIn(0, labels.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Kurva Volume Perangkat Keras DAC", "DAC Hardware Volume Curve"))
                .setSingleChoiceItems(labels, cur) { dialog, which ->
                    AudioSettingsRepository.hardwareVolumeCurve = which
                    AudioPlayerController.syncVolumeToNative()
                    refreshAllDescriptions()
                    dialog.dismiss()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 6. Batas Aman Volume DAC (Maks. 80%)
        binding.switchDacSafeLimit.isChecked = AudioSettingsRepository.isDacSafeLimitEnabled
        binding.switchDacSafeLimit.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isDacSafeLimitEnabled = isChecked
            AudioPlayerController.syncVolumeToNative()
            updateHeroStatusCard()
            val msg = if (isChecked) {
                tr("Batas aman volume DAC 80% diaktifkan", "DAC 80% safe volume limit enabled")
            } else {
                tr("Batas penuh volume DAC 100% (0.0 dB) dibuka", "Full 100% (0.0 dB) DAC volume limit unlocked")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 7. Sinkronisasi Paksa Volume DAC
        binding.switchForceVolumeSync.isChecked = AudioSettingsRepository.isForceVolumeSync
        binding.switchForceVolumeSync.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isForceVolumeSync = isChecked
            AudioPlayerController.syncVolumeToNative()
            val msg = if (isChecked) {
                tr("Sinkronisasi paksa volume USB DAC diaktifkan", "Force USB DAC volume sync enabled")
            } else {
                tr("Sinkronisasi paksa volume USB DAC dinonaktifkan", "Force USB DAC volume sync disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 8. Kendali Volume Saat Layar Mati (Android 12+)
        binding.switchAndroid12VolumeFix.isChecked = AudioSettingsRepository.isAndroid12ScreenOffVolumeFix
        binding.switchAndroid12VolumeFix.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isAndroid12ScreenOffVolumeFix = isChecked
            val msg = if (isChecked) {
                tr("Kendali volume saat layar mati diaktifkan", "Screen-off volume control enabled")
            } else {
                tr("Kendali volume saat layar mati dinonaktifkan", "Screen-off volume control disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 9. Kurva Volume Perangkat Lunak
        binding.rowVolumeCurve.setOnClickListener {
            val labels = getVolumeCurveLabels()
            val currentIdx = AudioSettingsRepository.volumeCurve.coerceIn(0, labels.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Kurva Volume Perangkat Lunak", "Software Volume Curve"))
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    AudioSettingsRepository.volumeCurve = which
                    AudioPlayerController.syncVolumeToNative()
                    refreshAllDescriptions()
                    dialog.dismiss()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 10. Penghalus Kuantisasi (TPDF Dithering)
        binding.switchDither.isChecked = AudioSettingsRepository.isDitherEnabled
        binding.switchDither.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isDitherEnabled = isChecked
            updateHeroStatusCard()
            val msg = if (isChecked) {
                tr("Penghalus kuantisasi (TPDF Dithering) diaktifkan", "Quantization smoothing (TPDF Dithering) enabled")
            } else {
                tr("Penghalus kuantisasi (TPDF Dithering) dinonaktifkan", "Quantization smoothing (TPDF Dithering) disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 11. Normalisasi Kenyaringan (ReplayGain)
        binding.rowReplayGain.setOnClickListener {
            showReplayGainConfigDialog { refreshAllDescriptions() }
        }

        refreshAllDescriptions()
        AppLanguageManager.applyLanguageToViewTree(binding.root)

        lifecycleScope.launch {
            AudioPlayerController.volumeFraction.collectLatest {
                updateHeroStatusCard()
            }
        }
        lifecycleScope.launch {
            UsbAudioDeviceManager.connectedDac.collectLatest {
                updateHeroStatusCard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllDescriptions()
        AppLanguageManager.applyLanguageToViewTree(binding.root)
    }

    private fun refreshAllDescriptions() {
        val vmLabels = getVolumeModeLabels()
        binding.tvVolumeModeDesc.text = vmLabels[AudioSettingsRepository.volumeModePref.coerceIn(0, vmLabels.size - 1)]

        val stepLabels = getStepDbLabels()
        val curStep = AudioSettingsRepository.volumeStepDb
        val stepIdx = stepDbValues.indices.minByOrNull { abs(stepDbValues[it] - curStep) } ?: 1
        binding.tvVolumeStepDbDesc.text = stepLabels[stepIdx]

        val hwLabels = getHwCurveLabels()
        binding.tvHardwareVolumeCurveDesc.text = hwLabels[AudioSettingsRepository.hardwareVolumeCurve.coerceIn(0, hwLabels.size - 1)]

        val swLabels = getVolumeCurveLabels()
        binding.tvVolumeCurveDesc.text = swLabels[AudioSettingsRepository.volumeCurve.coerceIn(0, swLabels.size - 1)]

        val modeName = when (AudioSettingsRepository.replayGainMode) {
            1 -> tr("Per Lagu", "Track Gain")
            2 -> tr("Per Album", "Album Gain")
            else -> tr("Nonaktif", "Off")
        }
        val preamp = String.format(Locale.US, "%+.1f dB", AudioSettingsRepository.replayGainPreampDb)
        val clipStr = if (AudioSettingsRepository.isReplayGainPreventClipping) {
            tr("Anti-Kliping", "Anti-Clipping")
        } else {
            tr("Tanpa Pembatas", "Unclamped")
        }
        binding.tvReplayGainDesc.text = if (AudioSettingsRepository.replayGainMode == 0) {
            tr("Nonaktif • Penguatan Awal $preamp", "Off • Preamp $preamp")
        } else {
            tr("$modeName • Penguatan Awal $preamp • $clipStr", "$modeName • Preamp $preamp • $clipStr")
        }

        updateHeroStatusCard()
    }

    private fun updateHeroStatusCard() {
        val isUsb = UsbAudioDeviceManager.connectedDac.value != null
        val isHwSupported = AudioPlayerController.isHardwareVolumeSupported()
        val isDsd = AudioPlayerController.playbackState.value.currentTrack?.isDsd ?: false
        val effType = AudioSettingsRepository.getEffectiveVolumeType(isUsb, isHwSupported, isDsd)
        val vol = AudioPlayerController.volumeFraction.value
        val pct = round(vol * 100f).toInt().coerceIn(0, 100)

        binding.tvActiveVolumeModeTitle.text = when (effType) {
            EffectiveVolumeType.HARDWARE_DAC -> tr("Volume Perangkat Keras USB DAC", "USB DAC Hardware Volume")
            EffectiveVolumeType.SOFTWARE -> tr("Volume Perangkat Lunak (DSP 64-bit)", "Software Volume (64-bit DSP)")
            EffectiveVolumeType.INTERNAL_SYSTEM -> tr("Volume Sistem Internal (Bit-Perfect 0.0 dB)", "Internal System Volume (Bit-Perfect 0.0 dB)")
            EffectiveVolumeType.BIT_PERFECT_LOCKED -> tr("Terkunci Bit-Perfect Murni (0.0 dB)", "Bit-Perfect Locked (0.0 dB)")
        }

        binding.tvActiveVolumeLevelBadge.text = when (effType) {
            EffectiveVolumeType.HARDWARE_DAC -> {
                val db = AudioSettingsRepository.formatDecibel(
                    vol,
                    isHardware = true,
                    minRaw = AudioPlayerController.getHardwareVolumeMin(),
                    maxRaw = AudioPlayerController.getHardwareVolumeMax()
                )
                "$pct% ($db)"
            }
            EffectiveVolumeType.SOFTWARE -> {
                val db = AudioSettingsRepository.formatDecibel(vol, isHardware = false)
                "$pct% ($db)"
            }
            EffectiveVolumeType.INTERNAL_SYSTEM -> "$pct% (0.0 dB)"
            EffectiveVolumeType.BIT_PERFECT_LOCKED -> "100% (0.0 dB)"
        }

        val limitStr = if (AudioSettingsRepository.isDacSafeLimitEnabled) {
            tr("Maks. DAC 80%", "DAC Max 80%")
        } else {
            tr("DAC Penuh 100%", "DAC Full 100%")
        }
        val stepStr = String.format(
            Locale.US,
            tr("%.1f dB/langkah", "%.1f dB/step"),
            AudioSettingsRepository.volumeStepDb
        )
        val ditherStr = if (AudioSettingsRepository.isDitherEnabled) {
            tr("TPDF Aktif", "TPDF On")
        } else {
            tr("TPDF Mati", "TPDF Off")
        }
        binding.tvActiveVolumeDetail.text = "$limitStr • $stepStr • $ditherStr"
    }

    private fun applyThemePalette() {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val accent = scheme.accent

        if (AudioSettingsRepository.isObsidianGold) {
            binding.root.setBackgroundResource(com.hiresia.audioplayer.R.drawable.bg_obsidian_ambient)
            window.statusBarColor = Color.parseColor("#2E2E30")
            window.navigationBarColor = scheme.page
        } else {
            binding.root.setBackgroundColor(scheme.page)
            window.statusBarColor = scheme.page
            window.navigationBarColor = scheme.page
        }

        binding.tvVolumeSettingsTitle.setTextColor(scheme.text)
        binding.btnBack.imageTintList = ColorStateList.valueOf(scheme.text)
        binding.btnOpenVolumePanel.backgroundTintList = ColorStateList.valueOf(accent)
        binding.btnOpenVolumePanel.setTextColor(scheme.onAccent)

        val headers = listOf(
            binding.tvHeroStatusLabel,
            binding.tvSubSectionVolControl,
            binding.tvSubSectionVolHardware,
            binding.tvSubSectionVolDsp
        )
        headers.forEach { it.setTextColor(accent) }
        binding.tvActiveVolumeLevelBadge.setTextColor(accent)
        binding.tvActiveVolumeModeTitle.setTextColor(scheme.text)

        val surfaceCsl = ColorStateList.valueOf(scheme.surface)
        binding.cardVolumeStatusHero.backgroundTintList = surfaceCsl
        binding.cardVolControl.backgroundTintList = surfaceCsl
        binding.cardVolHardware.backgroundTintList = surfaceCsl
        binding.cardVolDsp.backgroundTintList = surfaceCsl

        val uncheckedTrack = if (AudioSettingsRepository.themeMode == AudioSettingsRepository.THEME_MODE_DARK) {
            Color.parseColor("#3A3A3D")
        } else {
            Color.parseColor("#8E9382")
        }
        val trackStates = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                accent,
                uncheckedTrack
            )
        )
        val thumbStates = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                scheme.onAccent,
                scheme.surface2
            )
        )

        val switches = listOf(
            binding.switchInterceptVolumeKeys,
            binding.switchVolumeAutoFade,
            binding.switchDacSafeLimit,
            binding.switchForceVolumeSync,
            binding.switchAndroid12VolumeFix,
            binding.switchDither
        )
        switches.forEach { s ->
            s.trackTintList = trackStates
            s.thumbTintList = thumbStates
        }
    }

    private fun showReplayGainConfigDialog(onUpdated: () -> Unit) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(52, 24, 52, 12)
        }

        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val rbOff = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = tr("Nonaktif", "Off") }
        val rbTrack = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = tr("Per Lagu (Samakan Kenyaringan Antarlagu)", "Track Gain (Normalize Each Song)")
        }
        val rbAlbum = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = tr("Per Album (Pertahankan Dinamika Asli Album)", "Album Gain (Preserve Album Dynamics)")
        }
        radioGroup.addView(rbOff)
        radioGroup.addView(rbTrack)
        radioGroup.addView(rbAlbum)
        when (AudioSettingsRepository.replayGainMode) {
            1 -> radioGroup.check(rbTrack.id)
            2 -> radioGroup.check(rbAlbum.id)
            else -> radioGroup.check(rbOff.id)
        }

        var selectedPreamp = AudioSettingsRepository.replayGainPreampDb
        val tvPreampLabel = android.widget.TextView(this).apply {
            text = String.format(Locale.US, tr("Penguatan Awal (Preamp): %+.1f dB", "Preamp Offset: %+.1f dB"), selectedPreamp)
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setTextColor(scheme.accent)
        }
        val seekPreamp = android.widget.SeekBar(this).apply {
            max = 48
            progress = round((selectedPreamp + 12.0f) * 2.0f).toInt().coerceIn(0, 48)
            progressTintList = ColorStateList.valueOf(scheme.accent)
            thumbTintList = ColorStateList.valueOf(scheme.accent)
        }
        seekPreamp.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                selectedPreamp = (progress - 24) * 0.5f
                tvPreampLabel.text = String.format(Locale.US, tr("Penguatan Awal (Preamp): %+.1f dB", "Preamp Offset: %+.1f dB"), selectedPreamp)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val checkPreventClip = android.widget.CheckBox(this).apply {
            text = tr("Cegah Distorsi Puncak Otomatis (Pembatas Puncak Sejati)", "Prevent Peak Clipping Automatically (True-Peak Limiting)")
            isChecked = AudioSettingsRepository.isReplayGainPreventClipping
            setPadding(8, 20, 8, 8)
        }

        container.addView(radioGroup)
        container.addView(tvPreampLabel)
        container.addView(seekPreamp)
        container.addView(checkPreventClip)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Pengaturan Normalisasi ReplayGain", "ReplayGain Normalization Settings"))
            .setView(container)
            .setPositiveButton(tr("Simpan", "Save")) { _, _ ->
                val mode = when (radioGroup.checkedRadioButtonId) {
                    rbTrack.id -> 1
                    rbAlbum.id -> 2
                    else -> 0
                }
                AudioSettingsRepository.replayGainPreampDb = selectedPreamp
                AudioSettingsRepository.isReplayGainPreventClipping = checkPreventClip.isChecked
                AudioSettingsRepository.replayGainMode = mode
                AudioPlayerController.applyReplayGainSettings()
                onUpdated()
                Toast.makeText(
                    this,
                    tr("Pengaturan ReplayGain disimpan", "ReplayGain settings saved"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }
}
