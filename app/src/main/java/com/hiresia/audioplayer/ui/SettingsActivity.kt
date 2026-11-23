package com.hiresia.audioplayer.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hiresia.audioplayer.R
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiresia.audioplayer.database.HiresiaLibraryDatabase
import com.hiresia.audioplayer.databinding.ActivitySettingsBinding
import com.hiresia.audioplayer.databinding.DialogScanLoadingBinding
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.engine.SleepTimerManager
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val volumeController by lazy { AudiophileVolumeController(this) }

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

    private fun getDsdModeLabels(): Array<String> = arrayOf(
        tr("Konversi DSD ke PCM (176.4 kHz / 24-bit)", "DSD to PCM Conversion (176.4 kHz / 24-bit)"),
        tr("DoP (DSD di Atas PCM) [Standar DAC]", "DoP (DSD over PCM) [DAC Standard]"),
        tr("DSD Murni (Native Bitstream)", "Native DSD (Raw Bitstream)")
    )

    private fun getPaletteLabels(): Array<String> = arrayOf(
        tr("Obsidian Gold (Gelap)", "Obsidian Gold (Dark)"),
        tr("Zest Terang", "Zest Light"),
        tr("Aegean Terang", "Aegean Light")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemePalette()

        binding.btnBack.setOnClickListener { finish() }

        val db = HiresiaLibraryDatabase.getInstance(this)

        // Pindai Ulang Pustaka (dengan Animasi Loading)
        binding.rowRescanLibrary.setOnClickListener {
            startRescanWithLoadingDialog(db)
        }

        // Folder Musik
        binding.rowMusicFolders.setOnClickListener {
            Toast.makeText(
                this,
                tr("Kelola folder musik melalui menu Pustaka", "Manage music folders via the Library tab"),
                Toast.LENGTH_SHORT
            ).show()
        }

        fun updatePipelinePreview() {
            val isUsb = com.hiresia.audioplayer.usb.UsbAudioDeviceManager.connectedDac.value != null
            binding.tvPipelineChainPreview.text = AudioSettingsRepository.getPipelineChain(isUsb)
        }
        updatePipelinePreview()

        // 1. Mode Bit-Perfect (Jalur Murni)
        binding.switchBitPerfect.isChecked = AudioSettingsRepository.isBitPerfect
        binding.switchBitPerfect.setOnCheckedChangeListener { _, isChecked ->
            AudioPlayerController.setBitPerfectMode(isChecked)
            updatePipelinePreview()
            val msg = if (isChecked) {
                tr("Mode Bit-Perfect diaktifkan (DSP & EQ dilewati)", "Bit-Perfect mode enabled (DSP & EQ bypassed)")
            } else {
                tr("Mode Bit-Perfect dinonaktifkan", "Bit-Perfect mode disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. Driver Hi-Res Langsung (Lewati Sistem)
        binding.switchHiResDriver.isChecked = AudioSettingsRepository.isHiResDriverEnabled
        binding.switchHiResDriver.setOnCheckedChangeListener { _, isChecked ->
            AudioPlayerController.setHiResDriverMode(isChecked)
            updatePipelinePreview()
            val msg = if (isChecked) {
                tr("Driver Hi-Res Langsung diaktifkan (lewati AudioFlinger)", "Direct Hi-Res Driver enabled (AudioFlinger bypassed)")
            } else {
                tr("Driver Hi-Res Langsung dinonaktifkan", "Direct Hi-Res Driver disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2b. Gunakan DoP / DSD Murni Bila Didukung
        binding.switchDsdHiResWhenPossible.isChecked = AudioSettingsRepository.isDsdHiResWhenPossible
        binding.switchDsdHiResWhenPossible.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isDsdHiResWhenPossible = isChecked
            AudioPlayerController.onDsdSettingChanged()
            val msg = if (isChecked) {
                tr("DoP / DSD Murni diaktifkan (pemutaran DSD bit-exact)", "DoP / Native DSD enabled (bit-exact DSD playback)")
            } else {
                tr("Konversi DSD ke PCM diaktifkan (EQ & DSP dapat digunakan)", "DSD to PCM conversion enabled (EQ & DSP available)")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 3. Mode Eksklusif USB DAC
        binding.switchUsbExclusive.isChecked = AudioSettingsRepository.isUsbExclusiveEnabled
        binding.switchUsbExclusive.setOnCheckedChangeListener { _, isChecked ->
            AudioPlayerController.setUsbExclusiveMode(isChecked)
            updatePipelinePreview()
            val msg = if (isChecked) {
                tr("Mode Eksklusif USB DAC diaktifkan (notifikasi ponsel dibisukan)", "USB DAC Exclusive mode enabled (phone notifications muted)")
            } else {
                tr("Mode Eksklusif USB DAC dinonaktifkan", "USB DAC Exclusive mode disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 3b. Mode Pemutaran DSD (USB DAC)
        binding.rowUsbDsdMode.setOnClickListener {
            val labels = getDsdModeLabels()
            val currentIdx = AudioSettingsRepository.usbDsdMode.coerceIn(0, labels.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Mode Pemutaran DSD (USB DAC)", "DSD Playback Mode (USB DAC)"))
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    AudioSettingsRepository.usbDsdMode = which
                    refreshDynamicLabels()
                    AudioPlayerController.onDsdSettingChanged()
                    dialog.dismiss()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 3c. Blokir USB HID
        binding.switchBlockUsbHid.isChecked = AudioSettingsRepository.isBlockUsbHidEnabled
        binding.switchBlockUsbHid.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isBlockUsbHidEnabled = isChecked
            val msg = if (isChecked) {
                tr("Blokir USB HID diaktifkan (pemicu palsu & asisten suara diblokir)", "Block USB HID enabled (phantom triggers & voice assistant blocked)")
            } else {
                tr("Blokir USB HID dinonaktifkan", "Block USB HID disabled")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 4. Navigasi ke Layar Baru: Sistem Volume Audiophile
        binding.rowVolumeCategoryMenu.setOnClickListener {
            startActivity(Intent(this, VolumeSettingsActivity::class.java))
        }

        // Ekualiser
        binding.rowEqualizer.setOnClickListener {
            startActivity(Intent(this, EqualizerActivity::class.java))
        }

        // Pengatur Waktu Tidur
        binding.rowSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }

        lifecycleScope.launch {
            SleepTimerManager.remainingSeconds.collect {
                if (SleepTimerManager.isActive.value) {
                    val scheme = AudioSettingsRepository.getCurrentScheme()
                    binding.tvSleepTimer.text = SleepTimerManager.getFormattedTime()
                    binding.tvSleepTimer.setTextColor(scheme.accent)
                } else {
                    binding.tvSleepTimer.text = tr("Mati", "Off")
                    binding.tvSleepTimer.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                }
            }
        }

        // 5. Tampilan (Tema Gelap/Terang disatukan ke Palet Warna)
        binding.rowTheme.visibility = View.GONE

        binding.rowAccentColor.setOnClickListener {
            val labels = getPaletteLabels()
            val currentChoice = when (AudioSettingsRepository.paletteIndex) {
                AudioSettingsRepository.PALETTE_D_OBSIDIAN_GOLD -> 0
                AudioSettingsRepository.PALETTE_C_AEGEAN -> 2
                else -> 1
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Pilih Palet Warna", "Select Color Palette"))
                .setSingleChoiceItems(labels, currentChoice) { dialog, which ->
                    val prevThemeMode = AudioSettingsRepository.themeMode
                    when (which) {
                        0 -> {
                            AudioSettingsRepository.paletteIndex = AudioSettingsRepository.PALETTE_D_OBSIDIAN_GOLD
                            AudioSettingsRepository.themeMode = AudioSettingsRepository.THEME_MODE_DARK
                        }
                        2 -> {
                            AudioSettingsRepository.paletteIndex = AudioSettingsRepository.PALETTE_C_AEGEAN
                            AudioSettingsRepository.themeMode = AudioSettingsRepository.THEME_MODE_LIGHT
                        }
                        else -> {
                            AudioSettingsRepository.paletteIndex = AudioSettingsRepository.PALETTE_B_ZAITUN
                            AudioSettingsRepository.themeMode = AudioSettingsRepository.THEME_MODE_LIGHT
                        }
                    }
                    dialog.dismiss()
                    if (prevThemeMode != AudioSettingsRepository.themeMode) {
                        AudioSettingsRepository.applyThemeMode()
                    } else {
                        refreshDynamicLabels()
                        applyThemePalette()
                    }
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 6. Bahasa Aplikasi / App Language (Indonesia & English)
        val languageLabels = arrayOf("Bahasa Indonesia", "English")
        binding.rowLanguage.setOnClickListener {
            val currentLangIdx = if (AudioSettingsRepository.isEnglish) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Pilih Bahasa Aplikasi", "Select App Language"))
                .setSingleChoiceItems(languageLabels, currentLangIdx) { dialog, which ->
                    AudioSettingsRepository.appLanguage = if (which == 1) "en" else "id"
                    refreshDynamicLabels()
                    AppLanguageManager.applyLanguageToViewTree(binding.root)
                    dialog.dismiss()
                    val msg = tr("Bahasa diubah ke Bahasa Indonesia", "Language changed to English")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show()
        }

        // 7. Dukungan & Masukan (Report Bug / Improve Hiresia & Donate)
        binding.rowReportFeedback.setOnClickListener {
            openExternalUrl("https://docs.google.com/forms/d/e/1FAIpQLSfe809fZb4Sg_37t5HjpQ0gW2WjYzNYuhyE4QPkKRHKc0DDBw/viewform?usp=publish-editor")
        }

        binding.rowDonate.setOnClickListener {
            openExternalUrl("https://sociabuzz.com/rynodev/tribe")
        }

        refreshDynamicLabels()
        AppLanguageManager.applyLanguageToViewTree(binding.root)
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                tr("Tidak ada browser untuk membuka tautan", "No browser found to open the link"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDynamicLabels()
        AppLanguageManager.applyLanguageToViewTree(binding.root)
    }

    private fun refreshDynamicLabels() {
        val db = HiresiaLibraryDatabase.getInstance(this)
        val folderCount = db.getFolderSearches().size
        binding.tvFolderCount.text = if (AudioSettingsRepository.isEnglish) {
            if (folderCount == 1) "1 folder" else "$folderCount folders"
        } else {
            "$folderCount folder"
        }

        val dsdLabels = getDsdModeLabels()
        val dsdIdx = AudioSettingsRepository.usbDsdMode.coerceIn(0, dsdLabels.size - 1)
        binding.tvUsbDsdModeDesc.text = dsdLabels[dsdIdx]

        val scheme = AudioSettingsRepository.getCurrentScheme()
        binding.tvAccentColorDesc.text = when (AudioSettingsRepository.paletteIndex) {
            AudioSettingsRepository.PALETTE_D_OBSIDIAN_GOLD -> tr("Obsidian Gold (Gelap)", "Obsidian Gold (Dark)")
            AudioSettingsRepository.PALETTE_C_AEGEAN -> tr("Aegean Terang", "Aegean Light")
            else -> tr("Zest Terang", "Zest Light")
        }
        binding.tvAccentColorDesc.setTextColor(scheme.accent)

        binding.tvLanguageDesc.text = if (AudioSettingsRepository.isEnglish) "English" else "Bahasa Indonesia"
        binding.tvLanguageDesc.setTextColor(scheme.accent)

        updateEqualizerSummaryText()
        if (!SleepTimerManager.isActive.value) {
            binding.tvSleepTimer.text = tr("Mati", "Off")
        }
    }

    private fun updateEqualizerSummaryText() {
        val eqOn = AudioSettingsRepository.isGraphicEqEnabled
        binding.tvEqualizer.text = if (eqOn) {
            AppLanguageManager.translateString(AudioSettingsRepository.activeEqPresetName)
        } else {
            tr("Mati", "Off")
        }
    }

    private fun startRescanWithLoadingDialog(db: HiresiaLibraryDatabase) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val dlgBinding = DialogScanLoadingBinding.inflate(layoutInflater)
        dlgBinding.rootScanLoadingDialog.setBackgroundColor(scheme.surface)
        dlgBinding.tvScanLoadingTitle.setTextColor(scheme.text)
        dlgBinding.tvScanLoadingTitle.text = tr("Memindai Ulang Pustaka...", "Rescanning Library...")
        dlgBinding.tvScanLoadingCount.setTextColor(scheme.accent)
        dlgBinding.tvScanLoadingCount.text = tr("0 lagu ditemukan", "0 songs found")
        dlgBinding.tvScanLoadingFile.setTextColor(scheme.muted)
        dlgBinding.tvScanLoadingFile.text = tr("Menyiapkan daftar folder...", "Preparing folder list...")
        dlgBinding.pbScanSpinner.indeterminateTintList = ColorStateList.valueOf(scheme.accent)
        dlgBinding.pbScanBar.indeterminateTintList = ColorStateList.valueOf(scheme.accent)
        dlgBinding.btnCancelScanLoading.backgroundTintList = ColorStateList.valueOf(scheme.surface2)
        dlgBinding.btnCancelScanLoading.setTextColor(scheme.text)
        dlgBinding.btnCancelScanLoading.text = tr("Batal", "Cancel")

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dlgBinding.root)
            .setCancelable(false)
            .create()

        dlgBinding.btnCancelScanLoading.setOnClickListener {
            MediaRepository.abortScan()
            dlgBinding.btnCancelScanLoading.isEnabled = false
            dlgBinding.tvScanLoadingFile.text = tr("Menghentikan pemindaian...", "Stopping scan...")
        }
        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            var lastUiUpdate = 0L
            var totalTracks = 0
            try {
                val tracks = MediaRepository.rescanLibraryFolders(this@SettingsActivity) { folderName, curFile, totalCount ->
                    totalTracks = totalCount
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= 80L) {
                        lastUiUpdate = now
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                                dlgBinding.tvScanLoadingTitle.text = tr("Memindai: $folderName", "Scanning: $folderName")
                                dlgBinding.tvScanLoadingCount.text = tr("$totalCount lagu ditemukan", "$totalCount songs found")
                                dlgBinding.tvScanLoadingFile.text = curFile
                            }
                        }
                    }
                }
                totalTracks = tracks.size
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    refreshDynamicLabels()
                    val msg = if (MediaRepository.isScanAborted) {
                        tr("Pemindaian dihentikan ($totalTracks lagu tersimpan)", "Scan stopped ($totalTracks songs saved)")
                    } else {
                        tr("Pustaka diperbarui ($totalTracks lagu)", "Library updated ($totalTracks songs)")
                    }
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyThemePalette() {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val accent = scheme.accent

        if (AudioSettingsRepository.isObsidianGold) {
            binding.root.setBackgroundResource(R.drawable.bg_obsidian_ambient)
            window.statusBarColor = Color.parseColor("#2E2E30")
            window.navigationBarColor = scheme.page
        } else {
            binding.root.setBackgroundColor(scheme.page)
            window.statusBarColor = scheme.page
            window.navigationBarColor = scheme.page
        }

        binding.tvSettingsTitle.setTextColor(scheme.text)
        binding.btnBack.imageTintList = ColorStateList.valueOf(scheme.text)
        binding.divHeaderTop.setBackgroundColor(scheme.line)

        val container = binding.layoutSettingsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag == "section_divider" || child.tag == "divider") {
                child.setBackgroundColor(scheme.line)
            }
        }

        val headers = listOf(
            binding.tvSectionPustaka,
            binding.tvSectionPemutaran,
            binding.tvSectionAudio,
            binding.tvSectionPipeline,
            binding.tvSectionVolume,
            binding.tvSectionTampilan,
            binding.tvSectionDukungan,
            binding.tvSectionTentang
        )
        headers.forEach { it.setTextColor(accent) }

        binding.tvPipelineChainPreview.setTextColor(scheme.text)
        binding.tvUsbDsdModeDesc.setTextColor(accent)
        binding.ivVolumeCategoryChevron.imageTintList = ColorStateList.valueOf(accent)
        binding.tvAccentColorDesc.setTextColor(accent)
        binding.tvLanguageDesc.setTextColor(accent)
        binding.tvReportFeedbackDesc.setTextColor(accent)

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
            binding.switchHideShortTracks,
            binding.switchGapless,
            binding.switchBitPerfect,
            binding.switchHiResDriver,
            binding.switchDsdHiResWhenPossible,
            binding.switchUsbExclusive,
            binding.switchBlockUsbHid
        )
        switches.forEach { s ->
            s.trackTintList = trackStates
            s.thumbTintList = thumbStates
        }
    }

    private fun showSleepTimerDialog() {
        if (SleepTimerManager.isActive.value) {
            val finishTrackText = if (SleepTimerManager.finishTrackBeforeSleep) {
                tr("• Selesaikan lagu saat ini: Aktif", "• Finish current track: Active")
            } else {
                tr("• Selesaikan lagu saat ini: Tidak (langsung berhenti)", "• Finish current track: No (stop immediately)")
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Pengatur Waktu Tidur Aktif", "Sleep Timer Active"))
                .setMessage(tr("Sisa waktu: ${SleepTimerManager.getFormattedTime()}\n$finishTrackText", "Remaining time: ${SleepTimerManager.getFormattedTime()}\n$finishTrackText"))
                .setPositiveButton(tr("Matikan", "Turn Off")) { _, _ ->
                    SleepTimerManager.cancel()
                    Toast.makeText(this, tr("Pengatur waktu tidur dimatikan", "Sleep timer turned off"), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(tr("Ubah Durasi", "Change Duration")) { _, _ ->
                    showSleepTimerOptionsDialog()
                }
                .setNeutralButton(tr("Tutup", "Close"), null)
                .show()
        } else {
            showSleepTimerOptionsDialog()
        }
    }

    private fun showSleepTimerOptionsDialog() {
        val options = arrayOf(
            tr("15 Menit", "15 Minutes"),
            tr("30 Menit", "30 Minutes"),
            tr("45 Menit", "45 Minutes"),
            tr("60 Menit", "60 Minutes"),
            tr("Kustom (tentukan durasi sendiri)", "Custom (enter minutes)")
        )
        val durations = intArrayOf(15, 30, 45, 60, -1)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Atur Waktu Tidur", "Set Sleep Timer"))
            .setItems(options) { _, which ->
                val chosenMinutes = durations[which]
                if (chosenMinutes > 0) {
                    showSleepTimerConfirmationDialog(chosenMinutes)
                } else {
                    showCustomSleepTimerDialog()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showSleepTimerConfirmationDialog(minutes: Int) {
        val checkView = android.widget.CheckBox(this).apply {
            text = tr("Selesaikan lagu yang sedang diputar sebelum berhenti", "Finish currently playing song before stopping")
            isChecked = SleepTimerManager.finishTrackBeforeSleep
            setPadding(32, 24, 32, 24)
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(48, 16, 48, 8)
            addView(checkView)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Mulai Waktu Tidur ($minutes menit)", "Start Sleep Timer ($minutes mins)"))
            .setView(container)
            .setPositiveButton(tr("Mulai", "Start")) { _, _ ->
                SleepTimerManager.start(this, minutes, checkView.isChecked)
                Toast.makeText(
                    this,
                    tr("Pengatur waktu tidur diatur ke $minutes menit", "Sleep timer set to $minutes minutes"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showCustomSleepTimerDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
        }

        val input = android.widget.EditText(this).apply {
            hint = tr("Contoh: 20 atau 90 menit", "Example: 20 or 90 minutes")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(SleepTimerManager.selectedMinutes.value.toString())
            setSelectAllOnFocus(true)
        }
        val checkBox = android.widget.CheckBox(this).apply {
            text = tr("Selesaikan lagu yang sedang diputar sebelum berhenti", "Finish currently playing song before stopping")
            isChecked = SleepTimerManager.finishTrackBeforeSleep
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }

        layout.addView(input)
        layout.addView(checkBox)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Masukkan Durasi Waktu Tidur", "Enter Sleep Timer Duration"))
            .setView(layout)
            .setPositiveButton(tr("Mulai", "Start")) { _, _ ->
                val mins = input.text.toString().trim().toIntOrNull()
                if (mins != null && mins in 1..720) {
                    SleepTimerManager.start(this, mins, checkBox.isChecked)
                    Toast.makeText(
                        this,
                        tr("Pengatur waktu tidur diatur ke $mins menit", "Sleep timer set to $mins minutes"),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        tr("Masukkan durasi antara 1 hingga 720 menit", "Please enter a duration between 1 and 720 minutes"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }
}
