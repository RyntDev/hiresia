package com.hiresia.audioplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiresia.audioplayer.databinding.ActivityEqualizerBinding
import com.hiresia.audioplayer.model.AutoEqParser
import com.hiresia.audioplayer.model.EqBandParam
import com.hiresia.audioplayer.model.EqDefaults
import com.hiresia.audioplayer.model.EqFilterType
import com.hiresia.audioplayer.model.EqProfile
import com.hiresia.audioplayer.native.AudioUtilsJNI
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.ui.views.VerticalFaderView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class EqualizerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqualizerBinding

    private val builtInProfiles by lazy { EqDefaults.builtInProfiles() }
    private var selectedPresetName = "Normal"
    private var isDspDetailsExpanded = false
    private var isUpdatingUi = false
    private lateinit var faders: List<VerticalFaderView>
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

    private val importAutoEqFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleAutoEqFileUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        faders = listOf(
            binding.fader0, binding.fader1, binding.fader2, binding.fader3, binding.fader4,
            binding.fader5, binding.fader6, binding.fader7, binding.fader8, binding.fader9
        )

        applyThemePalette()
        setupHeaderAndMaster()
        setupPreampControl()
        setupFaders()
        setupScrollSliders()
        setupPresetChips()
        setupPeqStudioActions()
        setupInteractiveGfxCurve()
        setupAdvancedDspFeatures()
        setupResetButton()
        refreshAllEqViews()
        AppLanguageManager.applyLanguageToViewTree(binding.root)
    }

    override fun onResume() {
        super.onResume()
        AppLanguageManager.applyLanguageToViewTree(binding.root)
        refreshAllEqViews()
        updateBalanceUi(AudioSettingsRepository.stereoBalance)
    }

    private fun applyThemePalette() {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        if (AudioSettingsRepository.isObsidianGold) {
            binding.rootEqualizer.setBackgroundResource(com.hiresia.audioplayer.R.drawable.bg_obsidian_ambient)
            binding.headerEqualizer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            window.statusBarColor = android.graphics.Color.parseColor("#2E2E30")
            window.navigationBarColor = scheme.bg
        } else {
            binding.rootEqualizer.setBackgroundColor(scheme.bg)
            binding.headerEqualizer.setBackgroundColor(scheme.bg)
            window.statusBarColor = scheme.bg
            window.navigationBarColor = scheme.bg
        }

        binding.tvTitle.setTextColor(scheme.text)
        binding.btnBack.imageTintList = ColorStateList.valueOf(scheme.text)

        binding.cardEqGraph.setCardBackgroundColor(scheme.surface)
        binding.cardPreamp.setCardBackgroundColor(scheme.surface)
        binding.cardFaders.setCardBackgroundColor(scheme.surface)
        binding.cardAutoGain.setCardBackgroundColor(scheme.surface)
        binding.cardCrossfeed.setCardBackgroundColor(scheme.surface)
        binding.cardBalance.setCardBackgroundColor(scheme.surface)

        binding.tvPreampTitle.setTextColor(scheme.text)
        binding.tvPreampValue.setTextColor(scheme.accent)
        binding.tvPresetInfo.setTextColor(scheme.muted)
        binding.tvAdvancedDspTitle.setTextColor(scheme.accent)

        binding.btnResetEq.backgroundTintList = ColorStateList.valueOf(scheme.surface2)
        binding.btnResetEq.setTextColor(scheme.text)

        val actionBtnCsl = ColorStateList.valueOf(scheme.surface2)
        listOf(binding.btnImportAutoEq, binding.btnSavePreset, binding.btnMyPresets).forEach { btn ->
            btn.backgroundTintList = actionBtnCsl
            btn.setTextColor(scheme.text)
        }

        val thumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(scheme.onAccent, scheme.muted)
        )
        val trackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(scheme.accent, scheme.line2)
        )
        binding.switchEq.thumbTintList = thumbStates
        binding.switchEq.trackTintList = trackStates
        binding.switchAutoGain.thumbTintList = thumbStates
        binding.switchAutoGain.trackTintList = trackStates
        binding.switchCrossfeed.thumbTintList = thumbStates
        binding.switchCrossfeed.trackTintList = trackStates

        val accentStateList = ColorStateList.valueOf(scheme.accent)
        binding.seekPreamp.progressTintList = accentStateList
        binding.seekPreamp.thumbTintList = accentStateList

        binding.seekCrossfeed.progressTintList = accentStateList
        binding.seekCrossfeed.thumbTintList = accentStateList
        binding.tvCrossfeedVal.setTextColor(scheme.accent)

        binding.seekBalance.progressTintList = accentStateList
        binding.seekBalance.thumbTintList = accentStateList
        binding.tvBalanceValue.setTextColor(scheme.accent)

        binding.seekEqHorizontalScroll.progressTintList = accentStateList
        binding.seekEqHorizontalScroll.thumbTintList = accentStateList
        binding.seekEqHorizontalScroll.progressBackgroundTintList = ColorStateList.valueOf(scheme.line)
        binding.tvEqScrollLeft.setTextColor(scheme.accent)
        binding.tvEqScrollRight.setTextColor(scheme.accent)
        binding.eqVerticalScrollRail.invalidate()
    }

    private fun setupHeaderAndMaster() {
        binding.btnBack.setOnClickListener { finish() }

        val isBitPerfect = AudioSettingsRepository.isBitPerfect
        val isEqActive = AudioSettingsRepository.isGraphicEqEnabled

        binding.layoutBitPerfectBanner.visibility = if (isBitPerfect) View.VISIBLE else View.GONE
        binding.switchEq.isChecked = isEqActive

        updateControlsEnabledState(isEqActive)

        binding.switchEq.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isGraphicEqEnabled = isChecked
            AudioUtilsJNI.AudioServer_setUseGraphicEQ(isChecked)
            updateControlsEnabledState(isChecked)
            val msg = if (isChecked) {
                if (isBitPerfect) {
                    tr("Equalizer 64-bit PEQ Aktif (Bypass Mode Bit-Perfect)", "64-bit PEQ Equalizer Active (Bit-Perfect Bypass)")
                } else {
                    tr("Equalizer 64-bit PEQ Aktif", "64-bit PEQ Equalizer Active")
                }
            } else {
                tr("Equalizer Dinonaktifkan (Flat 0.0 dB)", "Equalizer Disabled (Flat 0.0 dB)")
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateControlsEnabledState(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.50f
        binding.cardEqGraph.alpha = alpha
        binding.cardPreamp.alpha = alpha
        binding.cardFaders.alpha = alpha
        binding.layoutChips.alpha = alpha
        binding.btnResetEq.isEnabled = enabled

        for (fader in faders) {
            fader.isEnabled = enabled
        }
        binding.seekPreamp.isEnabled = enabled
        binding.eqGfxView.isEnabled = enabled
    }

    private fun setupPreampControl() {
        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isUpdatingUi || !fromUser) return
                var db = (progress - 150) / 10.0f
                if (abs(db) < 0.15f) db = 0.0f
                AudioSettingsRepository.eqPreampDb = db
                updatePreampTextOnly(db)
                binding.eqGfxView.setPreamp(db)
                detectAndHighlightMatchingPreset()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Snap thumb visually to 0.0 dB after release if within deadzone
                updatePreampUi(AudioSettingsRepository.eqPreampDb)
            }
        })

        binding.tvPreampValue.setOnClickListener {
            if (!binding.switchEq.isChecked) return@setOnClickListener
            AudioSettingsRepository.eqPreampDb = 0.0f
            updatePreampUi(0.0f)
            binding.eqGfxView.setPreamp(0.0f)
            detectAndHighlightMatchingPreset()
            Toast.makeText(this, tr("Preamp diatur ke 0.0 dB", "Preamp reset to 0.0 dB"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePreampTextOnly(preampDb: Float) {
        binding.tvPreampValue.text = if (abs(preampDb) < 0.05f) {
            "0.0 dB"
        } else {
            String.format(Locale.US, "%+.1f dB", preampDb)
        }
    }

    private fun updatePreampUi(preampDb: Float) {
        isUpdatingUi = true
        val progress = ((preampDb + 15.0f) * 10.0f).roundToInt().coerceIn(0, 270)
        binding.seekPreamp.progress = progress
        updatePreampTextOnly(preampDb)
        isUpdatingUi = false
    }

    private fun setupScrollSliders() {
        var isSyncingHorizontal = false
        var isSyncingVertical = false

        fun updateHorizontalSliderFromScroll() {
            val childW = binding.layoutEqBands.width
            val viewW = binding.hsvEqFaders.width
            val maxScrollX = (childW - viewW).coerceAtLeast(0)
            binding.layoutEqHorizontalScroller.visibility = if (maxScrollX > 0) View.VISIBLE else View.GONE
            if (maxScrollX > 0 && !isSyncingHorizontal) {
                isSyncingHorizontal = true
                val prog = ((binding.hsvEqFaders.scrollX.toFloat() / maxScrollX.toFloat()) * 1000f)
                    .roundToInt().coerceIn(0, 1000)
                binding.seekEqHorizontalScroll.progress = prog
                isSyncingHorizontal = false
            }
        }

        binding.seekEqHorizontalScroll.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncingHorizontal) return
                val maxScrollX = (binding.layoutEqBands.width - binding.hsvEqFaders.width).coerceAtLeast(0)
                if (maxScrollX > 0) {
                    isSyncingHorizontal = true
                    val targetX = ((progress / 1000f) * maxScrollX).roundToInt().coerceIn(0, maxScrollX)
                    binding.hsvEqFaders.scrollTo(targetX, 0)
                    isSyncingHorizontal = false
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.hsvEqFaders.viewTreeObserver.addOnScrollChangedListener {
            updateHorizontalSliderFromScroll()
        }

        binding.tvEqScrollLeft.setOnClickListener {
            binding.hsvEqFaders.smoothScrollTo(0, 0)
        }
        binding.tvEqScrollRight.setOnClickListener {
            val maxScrollX = (binding.layoutEqBands.width - binding.hsvEqFaders.width).coerceAtLeast(0)
            binding.hsvEqFaders.smoothScrollTo(maxScrollX, 0)
        }

        fun updateVerticalRailMetrics() {
            val contentH = binding.layoutScrollEqualizerContent.height
            val viewH = binding.scrollEqualizer.height
            val maxScrollY = (contentH - viewH).coerceAtLeast(0)
            binding.eqVerticalScrollRail.isScrollable = maxScrollY > 0
            if (contentH > 0 && viewH > 0) {
                binding.eqVerticalScrollRail.viewportFraction = (viewH.toFloat() / contentH.toFloat()).coerceIn(0.18f, 1.0f)
            }
            if (maxScrollY > 0 && !isSyncingVertical) {
                isSyncingVertical = true
                binding.eqVerticalScrollRail.progress =
                    (binding.scrollEqualizer.scrollY.toFloat() / maxScrollY.toFloat()).coerceIn(0.0f, 1.0f)
                isSyncingVertical = false
            } else if (maxScrollY == 0) {
                binding.eqVerticalScrollRail.progress = 0.0f
            }
        }

        binding.eqVerticalScrollRail.onUserScrollChanged = { frac ->
            val maxScrollY = (binding.layoutScrollEqualizerContent.height - binding.scrollEqualizer.height).coerceAtLeast(0)
            if (maxScrollY > 0) {
                isSyncingVertical = true
                val targetY = (frac * maxScrollY).roundToInt().coerceIn(0, maxScrollY)
                binding.scrollEqualizer.scrollTo(0, targetY)
                isSyncingVertical = false
            }
        }

        binding.scrollEqualizer.viewTreeObserver.addOnScrollChangedListener {
            updateVerticalRailMetrics()
        }

        binding.layoutScrollEqualizerContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVerticalRailMetrics()
        }
        binding.layoutEqBands.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateHorizontalSliderFromScroll()
        }

        binding.rootEqualizer.post {
            updateHorizontalSliderFromScroll()
            updateVerticalRailMetrics()
        }
    }

    private fun applyUserBandGainDrag(bandIdx: Int, gainDb: Float) {
        val cur = AudioSettingsRepository.getBandParam(bandIdx)
        val updated = cur.copy(gainDb = gainDb, enabled = true)
        AudioSettingsRepository.setBandParam(bandIdx, updated)
        if (bandIdx in faders.indices) {
            bindFaderFromParam(faders[bandIdx], updated)
        }
        binding.eqGfxView.setBandParam(bandIdx, updated)
        detectAndHighlightMatchingPreset()
    }

    private fun bindFaderFromParam(fader: VerticalFaderView, p: EqBandParam) {
        fader.gainDb = p.gainDb
        fader.frequencyText = EqDefaults.formatFreqShort(p.freqHz)
        fader.filterMetaText = if (p.enabled) {
            "${p.type.shortCode} ${String.format(Locale.US, "%.1f", p.q)}"
        } else {
            "OFF"
        }
        fader.isBandEnabled = p.enabled
        fader.gainDisabledLabel = when (p.type) {
            EqFilterType.LOW_PASS -> "LPF"
            EqFilterType.HIGH_PASS -> "HPF"
            else -> null
        }
    }

    private fun setupFaders() {
        for (i in faders.indices) {
            val fader = faders[i]
            val bandIdx = i

            fader.onGainChanged = { gainDb ->
                applyUserBandGainDrag(bandIdx, gainDb)
            }

            fader.onEditBandRequested = {
                showBandEditorDialog(bandIdx)
            }
        }
    }

    private fun setupInteractiveGfxCurve() {
        binding.eqGfxView.onBandGainChanged = { bandIndex, gainDb ->
            applyUserBandGainDrag(bandIndex, gainDb)
        }

        binding.eqGfxView.onBandEditRequested = { bandIndex ->
            showBandEditorDialog(bandIndex)
        }
    }

    private fun refreshAllEqViews() {
        val params = AudioSettingsRepository.getAllBandParams()
        val preamp = AudioSettingsRepository.eqPreampDb

        updatePreampUi(preamp)
        binding.eqGfxView.setBands(params, preamp)

        for (i in faders.indices) {
            bindFaderFromParam(faders[i], params[i])
        }

        detectAndHighlightMatchingPreset()
    }

    private fun setupPresetChips() {
        binding.chipNormal.setOnClickListener { applyBuiltInProfile("Normal") }
        binding.chipBass.setOnClickListener { applyBuiltInProfile("Bass") }
        binding.chipVocal.setOnClickListener { applyBuiltInProfile("Vokal") }
        binding.chipTreble.setOnClickListener { applyBuiltInProfile("Treble") }
        binding.chipHarman.setOnClickListener { applyBuiltInProfile("Harman") }
        binding.chipStudio.setOnClickListener { applyBuiltInProfile("Studio") }
        binding.chipCustom.setOnClickListener {
            selectChip("Kustom")
            showSavedPresetsDialog()
        }
    }

    private fun ensureEqEnabledOnProfileApply() {
        if (!binding.switchEq.isChecked) {
            binding.switchEq.isChecked = true
        }
    }

    private fun applyBuiltInProfile(presetName: String) {
        val profile = builtInProfiles.firstOrNull { it.name.equals(presetName, ignoreCase = true) } ?: return
        ensureEqEnabledOnProfileApply()
        AudioSettingsRepository.applyEqProfile(profile)
        refreshAllEqViews()
    }

    private fun detectAndHighlightMatchingPreset() {
        val currentBands = AudioSettingsRepository.getAllBandParams()
        val currentPreamp = AudioSettingsRepository.eqPreampDb

        var matchedBuiltIn: String? = null
        for (profile in builtInProfiles) {
            if (abs(currentPreamp - profile.preampDb) > 0.25f) continue
            var allMatch = true
            for (i in 0 until EqDefaults.NUM_BANDS) {
                val cur = currentBands[i]
                val ref = profile.bands[i]
                if (cur.enabled != ref.enabled ||
                    cur.type != ref.type ||
                    abs(cur.freqHz - ref.freqHz) > 1.0f ||
                    abs(cur.q - ref.q) > 0.05f ||
                    abs(cur.gainDb - ref.gainDb) > 0.2f
                ) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                matchedBuiltIn = profile.name
                break
            }
        }

        if (matchedBuiltIn != null) {
            AudioSettingsRepository.activeEqPresetName = matchedBuiltIn
            selectChip(matchedBuiltIn)
        } else {
            val savedCustom = AudioSettingsRepository.getCustomEqPresets().firstOrNull { profile ->
                abs(currentPreamp - profile.preampDb) <= 0.25f &&
                    profile.bands.indices.all { i ->
                        val cur = currentBands[i]
                        val ref = profile.bands[i]
                        cur.enabled == ref.enabled &&
                            cur.type == ref.type &&
                            abs(cur.freqHz - ref.freqHz) <= 1.0f &&
                            abs(cur.q - ref.q) <= 0.05f &&
                            abs(cur.gainDb - ref.gainDb) <= 0.2f
                    }
            }
            AudioSettingsRepository.activeEqPresetName = savedCustom?.name ?: "Kustom"
            selectChip("Kustom", customDisplayName = savedCustom?.name)
        }
    }

    private fun selectChip(chipName: String, customDisplayName: String? = null) {
        selectedPresetName = chipName
        val scheme = AudioSettingsRepository.getCurrentScheme()

        val chips = listOf(
            "Normal" to binding.chipNormal,
            "Bass" to binding.chipBass,
            "Vokal" to binding.chipVocal,
            "Treble" to binding.chipTreble,
            "Harman" to binding.chipHarman,
            "Studio" to binding.chipStudio,
            "Kustom" to binding.chipCustom
        )

        for ((name, chip) in chips) {
            val isSelected = name.equals(chipName, ignoreCase = true)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 19f * resources.displayMetrics.density
                setColor(if (isSelected) scheme.accent else scheme.surface)
                if (!isSelected) {
                    setStroke((1f * resources.displayMetrics.density).toInt(), scheme.line2)
                }
            }
            chip.background = bg
            chip.setTextColor(if (isSelected) scheme.onAccent else scheme.text)
        }

        val rawLabel = customDisplayName ?: chipName
        val activeLabel = AppLanguageManager.translateString(rawLabel)
        binding.tvPresetInfo.text = tr(
            "Profil: $activeLabel. Ketuk label frekuensi/Q di bawah pita untuk atur Filter, Fc & Q.",
            "Profile: $activeLabel. Tap the frequency/Q label below any band to edit Filter, Fc & Q."
        )
    }

    /* -------------------------------------------------------------
     * PEQ Studio Actions: AutoEQ Import, Save Preset, My Presets
     * ------------------------------------------------------------- */
    private fun setupPeqStudioActions() {
        binding.btnImportAutoEq.setOnClickListener {
            showImportAutoEqOptionsDialog()
        }
        binding.btnSavePreset.setOnClickListener {
            showSaveCustomPresetDialog()
        }
        binding.btnMyPresets.setOnClickListener {
            showSavedPresetsDialog()
        }
    }

    private fun showImportAutoEqOptionsDialog() {
        val options = arrayOf(
            tr("Pilih File AutoEQ (.txt)", "Select AutoEQ File (.txt)"),
            tr("Tempel Teks AutoEQ / Squig.link", "Paste AutoEQ / Squig.link Text"),
            tr("Salin Konfigurasi EQ Saat Ini", "Copy Current EQ Configuration")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Impor / Ekspor AutoEQ", "Import / Export AutoEQ"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importAutoEqFileLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                    1 -> showPasteAutoEqDialog()
                    2 -> copyCurrentEqToClipboard()
                }
            }
            .setNegativeButton(tr("Tutup", "Close"), null)
            .show()
    }

    private fun handleAutoEqFileUri(uri: Uri) {
        var displayName = "AutoEQ Import"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) {
                    val raw = cursor.getString(idx)
                    if (!raw.isNullOrBlank()) {
                        displayName = raw.substringBeforeLast('.')
                    }
                }
            }
        } catch (_: Exception) {}

        val content = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }

        if (content.isNullOrBlank()) {
            Toast.makeText(this, tr("Gagal membaca file AutoEQ", "Failed to read AutoEQ file"), Toast.LENGTH_SHORT).show()
            return
        }
        applyImportedAutoEqText(content, displayName)
    }

    private fun showPasteAutoEqDialog() {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, 0)
        }
        val etName = EditText(this).apply {
            hint = tr("Nama Profil (mis. Moondrop Chu 2)", "Profile Name (e.g. Moondrop Chu 2)")
            isSingleLine = true
        }
        val etText = EditText(this).apply {
            hint = tr(
                "Tempel teks AutoEQ di sini:\nPreamp: -4.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 3.5 dB Q 0.71\n...",
                "Paste AutoEQ text here:\nPreamp: -4.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 3.5 dB Q 0.71\n..."
            )
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        layout.addView(etName)
        layout.addView(etText)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Tempel Profil AutoEQ / Squig.link", "Paste AutoEQ / Squig.link Profile"))
            .setView(layout)
            .setPositiveButton(tr("Terapkan", "Apply")) { _, _ ->
                val raw = etText.text.toString()
                val name = etName.text.toString().trim().ifEmpty { "AutoEQ Import" }
                applyImportedAutoEqText(raw, name)
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun applyImportedAutoEqText(rawText: String, profileName: String) {
        val parsed = AutoEqParser.parse(rawText, profileName)
        if (parsed == null) {
            Toast.makeText(
                this,
                tr("Format AutoEQ tidak dikenali. Pastikan ada baris Preamp / Filter N.", "Unrecognized AutoEQ format. Make sure Preamp / Filter N lines are present."),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        ensureEqEnabledOnProfileApply()
        AudioSettingsRepository.applyEqProfile(parsed)
        refreshAllEqViews()

        val activeCount = parsed.bands.count { it.enabled }
        val preampStr = String.format(Locale.US, "%+.1f", parsed.preampDb)
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("AutoEQ Diterapkan", "AutoEQ Applied"))
            .setMessage(
                tr(
                    "Profil '${parsed.name}' berhasil dimuat ($activeCount filter aktif, Preamp $preampStr dB).\n\nSimpan ke daftar Preset Saya?",
                    "Profile '${parsed.name}' loaded ($activeCount active filters, Preamp $preampStr dB).\n\nSave to My Presets?"
                )
            )
            .setPositiveButton(tr("Simpan", "Save")) { _, _ ->
                AudioSettingsRepository.saveCustomEqPreset(parsed.name, parsed.preampDb, parsed.bands)
                detectAndHighlightMatchingPreset()
                Toast.makeText(this, tr("Preset '${parsed.name}' tersimpan", "Preset '${parsed.name}' saved"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("Tidak", "No"), null)
            .show()
    }

    private fun showSaveCustomPresetDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, (10 * resources.displayMetrics.density).toInt(), pad, 0) }
        val input = EditText(this).apply {
            hint = tr("Nama preset (mis. IEM Favorit)", "Preset name (e.g. Favorite IEM)")
            isSingleLine = true
            val current = AudioSettingsRepository.activeEqPresetName
            if (current != "Normal" && current != "Kustom") {
                setText(current)
                setSelection(text.length)
            }
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Simpan Preset EQ", "Save EQ Preset"))
            .setView(container)
            .setPositiveButton(tr("Simpan", "Save")) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, tr("Nama preset tidak boleh kosong", "Preset name cannot be empty"), Toast.LENGTH_SHORT).show()
                } else {
                    AudioSettingsRepository.saveCustomEqPreset(
                        name = name,
                        preampDb = AudioSettingsRepository.eqPreampDb,
                        bands = AudioSettingsRepository.getAllBandParams()
                    )
                    detectAndHighlightMatchingPreset()
                    Toast.makeText(this, tr("Preset '$name' berhasil disimpan", "Preset '$name' saved"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun showSavedPresetsDialog() {
        val customPresets = AudioSettingsRepository.getCustomEqPresets()
        if (customPresets.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(tr("Preset Saya", "My Presets"))
                .setMessage(
                    tr(
                        "Belum ada preset kustom tersimpan.\nGunakan 'Simpan Preset' atau 'Import AutoEQ' untuk menambahkan profil IEM/headphone Anda.",
                        "No custom presets saved yet.\nUse 'Save Preset' or 'Import AutoEQ' to add your IEM/headphone profiles."
                    )
                )
                .setPositiveButton(tr("Simpan Sekarang", "Save Now")) { _, _ -> showSaveCustomPresetDialog() }
                .setNeutralButton(tr("Salin Teks AutoEQ", "Copy AutoEQ Text")) { _, _ -> copyCurrentEqToClipboard() }
                .setNegativeButton(tr("Tutup", "Close"), null)
                .show()
            return
        }

        val items = customPresets.map { p ->
            val enabledCount = p.bands.count { it.enabled }
            val bandLabel = tr("$enabledCount pita", "$enabledCount bands")
            "${p.name} (Preamp ${String.format(Locale.US, "%+.1f", p.preampDb)} dB · $bandLabel)"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("${tr("Preset Saya", "My Presets")} (${customPresets.size})")
            .setItems(items) { _, which ->
                showSingleCustomPresetActions(customPresets[which])
            }
            .setNeutralButton(tr("Salin Saat Ini", "Copy Current")) { _, _ -> copyCurrentEqToClipboard() }
            .setNegativeButton(tr("Tutup", "Close"), null)
            .show()
    }

    private fun showSingleCustomPresetActions(profile: EqProfile) {
        val actions = arrayOf(
            tr("Muat Preset Ini", "Load This Preset"),
            tr("Salin sebagai Teks AutoEQ", "Copy as AutoEQ Text"),
            tr("Hapus Preset", "Delete Preset")
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        ensureEqEnabledOnProfileApply()
                        AudioSettingsRepository.applyEqProfile(profile)
                        refreshAllEqViews()
                        Toast.makeText(this, tr("Preset '${profile.name}' dimuat", "Preset '${profile.name}' loaded"), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val txt = AutoEqParser.exportToText(profile)
                        copyTextToClipboard(profile.name, txt)
                        Toast.makeText(this, tr("Teks AutoEQ '${profile.name}' disalin", "AutoEQ text '${profile.name}' copied"), Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        AudioSettingsRepository.deleteCustomEqPreset(profile.name)
                        detectAndHighlightMatchingPreset()
                        Toast.makeText(this, tr("Preset '${profile.name}' dihapus", "Preset '${profile.name}' deleted"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    private fun copyCurrentEqToClipboard() {
        val profile = EqProfile(
            name = AudioSettingsRepository.activeEqPresetName,
            preampDb = AudioSettingsRepository.eqPreampDb,
            bands = AudioSettingsRepository.getAllBandParams()
        )
        copyTextToClipboard("HiResia EQ", AutoEqParser.exportToText(profile))
        Toast.makeText(this, tr("Konfigurasi PEQ disalin dalam format AutoEQ (.txt)", "PEQ configuration copied in AutoEQ (.txt) format"), Toast.LENGTH_SHORT).show()
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /* -------------------------------------------------------------
     * Per-Band Parametric EQ Editor Dialog
     * ------------------------------------------------------------- */
    private fun showBandEditorDialog(bandIndex: Int) {
        val current = AudioSettingsRepository.getBandParam(bandIndex)
        binding.eqGfxView.setSelectedBand(bandIndex)

        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * d).toInt(), pad, (4 * d).toInt())
        }

        val switchBand = SwitchCompat(this).apply {
            text = tr("Aktifkan Pita ${bandIndex + 1}", "Enable Band ${bandIndex + 1}")
            isChecked = current.enabled
        }
        root.addView(switchBand)

        val lblType = TextView(this).apply {
            text = tr("Tipe Filter", "Filter Type")
            textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
        }
        root.addView(lblType)

        val typeLabels = EqFilterType.entries.map { "${it.displayName} [${it.shortCode}]" }.toTypedArray()
        val spinnerType = Spinner(this).apply {
            adapter = ArrayAdapter(this@EqualizerActivity, android.R.layout.simple_spinner_dropdown_item, typeLabels)
            setSelection(current.type.ordinal)
        }
        root.addView(spinnerType)

        // Frequency input + log slider (20..20000 Hz)
        val lblFreq = TextView(this).apply {
            text = tr("Frekuensi / Fc (10 – 24000 Hz)", "Frequency / Fc (10 – 24000 Hz)")
            textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        val etFreq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.0f", current.freqHz))
        }
        val seekFreq = SeekBar(this).apply {
            max = 1000
            val frac = ((log10(current.freqHz.toDouble().coerceIn(20.0, 20000.0)) - log10(20.0)) /
                (log10(20000.0) - log10(20.0)))
            progress = (frac * 1000.0).roundToInt().coerceIn(0, 1000)
        }
        var syncingFreq = false
        seekFreq.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                if (!fromUser || syncingFreq) return
                syncingFreq = true
                val f = 10.0.pow(log10(20.0) + (prog / 1000.0) * (log10(20000.0) - log10(20.0)))
                etFreq.setText(String.format(Locale.US, "%.0f", f))
                syncingFreq = false
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        etFreq.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingFreq) return
                val f = s?.toString()?.replace(',', '.')?.toFloatOrNull() ?: return
                if (f in 20f..20000f) {
                    syncingFreq = true
                    val frac = ((log10(f.toDouble()) - log10(20.0)) / (log10(20000.0) - log10(20.0)))
                    seekFreq.progress = (frac * 1000.0).roundToInt().coerceIn(0, 1000)
                    syncingFreq = false
                }
            }
        })
        root.addView(lblFreq)
        root.addView(etFreq)
        root.addView(seekFreq)

        // Gain input (-15..+15 dB)
        val lblGain = TextView(this).apply {
            text = tr("Gain (-15.0 s/d +15.0 dB)", "Gain (-15.0 to +15.0 dB)")
            textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        val etGain = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.1f", current.gainDb))
        }
        root.addView(lblGain)
        root.addView(etGain)

        fun updateGainVisibilityForType(pos: Int) {
            val type = EqFilterType.entries.getOrElse(pos) { EqFilterType.PEAKING }
            val hasGain = (type != EqFilterType.LOW_PASS && type != EqFilterType.HIGH_PASS)
            etGain.isEnabled = hasGain
            etGain.alpha = if (hasGain) 1.0f else 0.45f
        }
        updateGainVisibilityForType(current.type.ordinal)
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateGainVisibilityForType(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Q-Factor input (0.10..18.00) + quick pills
        val lblQ = TextView(this).apply {
            text = "Q-Factor (0.10 – 18.00)"
            textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        val etQ = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", current.q))
        }
        val quickQRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        for (qVal in listOf(0.71f, 1.41f, 2.50f, 4.00f)) {
            val btn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = String.format(Locale.US, "Q %.2f", qVal)
                textSize = 11f
                isSingleLine = true
                minWidth = 0
                minimumWidth = 0
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { etQ.setText(String.format(Locale.US, "%.2f", qVal)) }
            }
            quickQRow.addView(btn)
        }
        root.addView(lblQ)
        root.addView(etQ)
        root.addView(quickQRow)

        val scroll = ScrollView(this).apply { addView(root) }

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Parametric EQ — Pita ${bandIndex + 1}", "Parametric EQ — Band ${bandIndex + 1}"))
            .setView(scroll)
            .setPositiveButton(tr("Terapkan", "Apply")) { _, _ ->
                val newType = EqFilterType.entries.getOrElse(spinnerType.selectedItemPosition) { current.type }
                val newFreq = etFreq.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(10f, 24000f)
                    ?: current.freqHz
                val newGain = etGain.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(-15f, 15f)
                    ?: current.gainDb
                val newQ = etQ.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.1f, 18f)
                    ?: current.q
                val newParam = EqBandParam(
                    freqHz = newFreq,
                    gainDb = newGain,
                    q = newQ,
                    type = newType,
                    enabled = switchBand.isChecked
                )
                ensureEqEnabledOnProfileApply()
                AudioSettingsRepository.setBandParam(bandIndex, newParam)
                refreshAllEqViews()
            }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .setOnDismissListener {
                binding.eqGfxView.setSelectedBand(-1)
            }
            .show()
    }

    /* -------------------------------------------------------------
     * Advanced DSP Features
     * ------------------------------------------------------------- */
    private fun updateBalanceUi(balance: Float) {
        val pct = (abs(balance) * 100).toInt()
        val text = when {
            abs(balance) < 0.02f -> tr("Tengah (Center 0%)", "Center (0%)")
            balance < 0f -> tr("Kiri $pct% (L)", "Left $pct% (L)")
            else -> tr("Kanan $pct% (R)", "Right $pct% (R)")
        }
        binding.tvBalanceValue.text = text
    }

    private fun setupAdvancedDspFeatures() {
        binding.btnToggleDspDetails.setOnClickListener {
            isDspDetailsExpanded = !isDspDetailsExpanded
            binding.layoutDspDetails.visibility = if (isDspDetailsExpanded) View.VISIBLE else View.GONE
            binding.ivChevronDsp.animate().rotation(if (isDspDetailsExpanded) 180f else 0f).setDuration(200).start()
        }

        // AutoGain (Anti-Clipping Headroom & Limiter)
        binding.switchAutoGain.isChecked = AudioSettingsRepository.isAutoGainEnabled
        binding.switchAutoGain.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isAutoGainEnabled = isChecked
            AudioUtilsJNI.AudioServer_setEQAutoGain(isChecked)
            Toast.makeText(
                this,
                if (isChecked) tr("AutoGain Aktif (Anti-Clipping)", "AutoGain Enabled (Anti-Clipping)") else tr("AutoGain Nonaktif", "AutoGain Disabled"),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Crossfeed
        binding.switchCrossfeed.isChecked = AudioSettingsRepository.isCrossfeedEnabled
        binding.switchCrossfeed.setOnCheckedChangeListener { _, isChecked ->
            AudioSettingsRepository.isCrossfeedEnabled = isChecked
        }

        binding.seekCrossfeed.progress = AudioSettingsRepository.crossfeedLevel
        binding.tvCrossfeedVal.text = AudioSettingsRepository.crossfeedLevel.toString()
        binding.seekCrossfeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCrossfeedVal.text = progress.toString()
                    AudioSettingsRepository.crossfeedLevel = progress
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Stereo Balance
        val savedBal = AudioSettingsRepository.stereoBalance
        val progress = kotlin.math.round((savedBal + 1.0f) * 50.0f).toInt().coerceIn(0, 100)
        binding.seekBalance.progress = progress
        updateBalanceUi(savedBal)

        binding.seekBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val balance = (progress - 50) / 50.0f
                updateBalanceUi(balance)
                if (fromUser) {
                    AudioSettingsRepository.stereoBalance = balance
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupResetButton() {
        binding.btnResetEq.setOnClickListener {
            AudioSettingsRepository.resetEqToDefault()
            AudioSettingsRepository.stereoBalance = 0.0f
            binding.seekBalance.progress = 50
            updateBalanceUi(0.0f)
            refreshAllEqViews()
            Toast.makeText(
                this,
                tr("Equalizer PEQ, Preamp dan Balance diatur ulang ke Flat/Tengah", "PEQ Equalizer, Preamp, and Balance reset to Flat/Center"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
