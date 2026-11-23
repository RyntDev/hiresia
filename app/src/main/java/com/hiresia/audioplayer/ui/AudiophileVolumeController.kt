package com.hiresia.audioplayer.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.engine.AudioPlayerController
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AppLanguageManager.tr
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import com.hiresia.audioplayer.repository.EffectiveVolumeType
import com.hiresia.audioplayer.repository.VolumeControlMode
import com.hiresia.audioplayer.usb.UsbAudioDeviceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

class AudiophileVolumeController(
    private val activity: AppCompatActivity,
    private val onVolumeUiUpdated: (() -> Unit)? = null
) {
    private var activeDialog: Dialog? = null
    private var observerJob: Job? = null
    private var dialogAutoDismiss: Boolean = false
    private var isUserDraggingMainSeekBar: Boolean = false
    private var showHwBalanceRow: Boolean = false
    private var refreshViewsAction: (() -> Unit)? = null

    private val dismissHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable {
        dismiss()
    }

    fun handleDispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            AudioSettingsRepository.isInterceptVolumeKeys
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                AudioPlayerController.adjustVolume(delta)
                showDialog(autoDismiss = AudioSettingsRepository.isVolumeDialogAutoFade)
                onVolumeUiUpdated?.invoke()
            }
            return true
        }
        return false
    }

    fun handleKeyDown(keyCode: Int): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            AudioSettingsRepository.isInterceptVolumeKeys
        ) {
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            AudioPlayerController.adjustVolume(delta)
            showDialog(autoDismiss = AudioSettingsRepository.isVolumeDialogAutoFade)
            onVolumeUiUpdated?.invoke()
            return true
        }
        return false
    }

    fun handleKeyUp(keyCode: Int): Boolean {
        return (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            AudioSettingsRepository.isInterceptVolumeKeys
    }

    fun dismiss() {
        observerJob?.cancel()
        observerJob = null
        dismissHandler.removeCallbacks(dismissRunnable)
        isUserDraggingMainSeekBar = false
        refreshViewsAction = null
        activeDialog?.let {
            if (it.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    it.dismiss()
                } catch (_: Exception) {
                }
            }
        }
        activeDialog = null
    }

    fun release() {
        dismiss()
    }

    fun showDialog(autoDismiss: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return

        val effectiveAutoDismiss = autoDismiss && AudioSettingsRepository.isVolumeDialogAutoFade
        if (activeDialog == null || !autoDismiss) {
            dialogAutoDismiss = effectiveAutoDismiss
        }

        val isExisting = activeDialog != null
        val dialog = activeDialog ?: Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val dialogView = activity.layoutInflater.inflate(R.layout.dialog_audiophile_volume, null)
            setContentView(dialogView)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnDismissListener {
                observerJob?.cancel()
                observerJob = null
                dismissHandler.removeCallbacks(dismissRunnable)
                isUserDraggingMainSeekBar = false
                refreshViewsAction = null
                activeDialog = null
            }
        }.also { activeDialog = it }

        if (isExisting) {
            refreshViewsAction?.invoke()
            resetAutoDismissIfNeeded()
            onVolumeUiUpdated?.invoke()
            return
        }

        val tvMode = dialog.findViewById<TextView>(R.id.tvVolumeModeBadge)
        val tvDbHero = dialog.findViewById<TextView>(R.id.tvVolumeDbHero)
        val tvSub = dialog.findViewById<TextView>(R.id.tvVolumePercentSub)
        val sb = dialog.findViewById<SeekBar>(R.id.sbVolume)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnVolumeClose)
        val btnToggleHwBalance = dialog.findViewById<TextView>(R.id.btnToggleHwBalance)
        val btnMute = dialog.findViewById<TextView>(R.id.btnVolMute)
        val btnMinus = dialog.findViewById<TextView>(R.id.btnVolMinus)
        val btnPlus = dialog.findViewById<TextView>(R.id.btnVolPlus)
        val btnMax = dialog.findViewById<TextView>(R.id.btnVolMax)
        val layoutNotice = dialog.findViewById<View>(R.id.layoutBitPerfectNotice)
        val tvNoticeText = dialog.findViewById<TextView>(R.id.tvBitPerfectNoticeText)
        val ivNotice = dialog.findViewById<ImageView>(R.id.ivBitPerfectNoticeIcon)
        val tvMinLabel = dialog.findViewById<TextView>(R.id.tvVolumeMinLabel)
        val tvMaxLabel = dialog.findViewById<TextView>(R.id.tvVolumeMaxLabel)
        val layoutHwBalanceRow = dialog.findViewById<View>(R.id.layoutHardwareBalanceRow)
        val tvHwBalanceTitle = dialog.findViewById<TextView>(R.id.tvHwBalanceTitle)
        val tvHwBalanceValue = dialog.findViewById<TextView>(R.id.tvHwBalanceValue)
        val sbHwBalance = dialog.findViewById<SeekBar>(R.id.sbHwBalance)

        val scheme = AudioSettingsRepository.getCurrentScheme()
        val cslAccent = ColorStateList.valueOf(scheme.accent)
        sb.thumbTintList = cslAccent
        sb.progressTintList = cslAccent
        sb.progressBackgroundTintList = ColorStateList.valueOf(scheme.line)
        btnClose.imageTintList = ColorStateList.valueOf(scheme.muted)
        btnToggleHwBalance.backgroundTintList = ColorStateList.valueOf(scheme.surface2)
        btnToggleHwBalance.setTextColor(scheme.accent)
        btnMute.setTextColor(scheme.text)
        btnMinus.setTextColor(scheme.text)
        btnPlus.setTextColor(scheme.text)
        btnMax.setTextColor(scheme.accent)
        tvMinLabel?.setTextColor(scheme.muted)
        tvMaxLabel?.setTextColor(scheme.muted)
        ivNotice?.imageTintList = cslAccent
        tvNoticeText?.setTextColor(scheme.text)
        tvHwBalanceTitle?.setTextColor(scheme.text)
        tvHwBalanceValue?.setTextColor(scheme.accent)
        sbHwBalance?.thumbTintList = cslAccent
        sbHwBalance?.progressTintList = cslAccent
        sbHwBalance?.progressBackgroundTintList = ColorStateList.valueOf(scheme.line)

        fun currentVolumeContext(): Triple<Boolean, Boolean, EffectiveVolumeType> {
            val curDac = UsbAudioDeviceManager.connectedDac.value
            val curIsUsb = (curDac != null)
            val curHwSupported = AudioPlayerController.isHardwareVolumeSupported()
            val curIsDsd = AudioPlayerController.playbackState.value.currentTrack?.isDsd ?: false
            val curEffType = AudioSettingsRepository.getEffectiveVolumeType(curIsUsb, curHwSupported, curIsDsd)
            return Triple(curIsUsb, curHwSupported, curEffType)
        }

        fun formatHwBalanceText(bal: Float): String {
            val dbAtt = abs(bal) * 24.0f
            return when {
                bal < -0.02f -> String.format(Locale.US, tr("Kiri (R -%.1f dB)", "Left (R -%.1f dB)"), dbAtt)
                bal > 0.02f -> String.format(Locale.US, tr("Kanan (L -%.1f dB)", "Right (L -%.1f dB)"), dbAtt)
                else -> tr("Tengah (0 dB)", "Center (0 dB)")
            }
        }

        fun updateDialogViews() {
            AppLanguageManager.applyLanguageToViewTree(dialog.window?.decorView)
            val (curIsUsb, _, curEffType) = currentVolumeContext()
            val vol = AudioPlayerController.volumeFraction.value
            val pct = round(vol * 100f).toInt().coerceIn(0, 100)
            val pbState = AudioPlayerController.playbackState.value
            val isRawDsd = (pbState.currentTrack?.isDsd == true) &&
                pbState.dsdModeBadge.isNotEmpty() &&
                pbState.dsdModeBadge != "DSD→PCM"
            val rgMode = AudioSettingsRepository.replayGainMode
            val rgInfo = AudioPlayerController.getCurrentReplayGainInfo()
            val rgTag = if (rgMode != 0 && rgInfo.hasTags && !isRawDsd) {
                val g = (if (rgMode == 1) rgInfo.trackGainDb else rgInfo.albumGainDb) + AudioSettingsRepository.replayGainPreampDb
                String.format(Locale.US, " • RG %+.1f dB", g)
            } else ""

            val controlsEnabled = curEffType != EffectiveVolumeType.BIT_PERFECT_LOCKED
            val controlsAlpha = if (controlsEnabled) 1.0f else 0.40f
            btnMax.visibility = View.GONE
            listOf(btnMute, btnMinus, btnPlus).forEach { btn ->
                btn.isEnabled = controlsEnabled
                btn.alpha = controlsAlpha
            }

            when (curEffType) {
                EffectiveVolumeType.HARDWARE_DAC -> {
                    tvMode.text = tr("VOLUME PERANGKAT KERAS DAC", "HARDWARE DAC VOLUME")
                    tvMode.setTextColor(scheme.accent)
                    val db = AudioSettingsRepository.formatDecibel(
                        vol,
                        isHardware = true,
                        minRaw = AudioPlayerController.getHardwareVolumeMin(),
                        maxRaw = AudioPlayerController.getHardwareVolumeMax()
                    )
                    tvDbHero.text = db
                    tvDbHero.setTextColor(scheme.accent)
                    val hwCurveName = when (AudioSettingsRepository.hardwareVolumeCurve) {
                        1 -> tr("Perseptual", "Perceptual")
                        2 -> tr("Sensitif IEM", "Sensitive IEM")
                        else -> "Linear dB"
                    }
                    val capNotice = if (AudioSettingsRepository.isDacSafeLimitEnabled && pct >= 80) {
                        tr(" (Maks. 80%)", " (Max 80%)")
                    } else ""
                    tvSub.text = tr("Level: $pct%$capNotice • Mixer USB ($hwCurveName)$rgTag", "Level: $pct%$capNotice • USB Mixer ($hwCurveName)$rgTag")
                    tvSub.setTextColor(scheme.muted)
                    tvMaxLabel.text = if (AudioSettingsRepository.isDacSafeLimitEnabled) {
                        tr("Maks. 80%", "Max 80%")
                    } else {
                        tr("0.0 dB (Maks.)", "0.0 dB (Max)")
                    }
                    btnMinus.text = "-1 dB"
                    btnPlus.text = "+1 dB"
                    sb.isEnabled = true
                    sb.alpha = 1.0f
                    layoutNotice.visibility = View.GONE
                    btnToggleHwBalance.visibility = View.VISIBLE
                    layoutHwBalanceRow.visibility = if (showHwBalanceRow) View.VISIBLE else View.GONE
                    val bal = AudioSettingsRepository.hardwareBalance
                    sbHwBalance.progress = round((bal + 1.0f) * 50.0f).toInt().coerceIn(0, 100)
                    tvHwBalanceValue.text = formatHwBalanceText(bal)
                }
                EffectiveVolumeType.SOFTWARE -> {
                    tvMode.text = tr("VOLUME PERANGKAT LUNAK", "SOFTWARE VOLUME CONTROL")
                    tvMode.setTextColor(scheme.accent)
                    val db = AudioSettingsRepository.formatDecibel(vol, isHardware = false)
                    tvDbHero.text = db
                    tvDbHero.setTextColor(scheme.accent)
                    val curveName = when (AudioSettingsRepository.volumeCurve) {
                        0 -> "Linear"
                        1 -> tr("Kubik", "Cubic")
                        else -> "Log -96 dB"
                    }
                    val ditherStr = if (AudioSettingsRepository.isDitherEnabled) " • TPDF" else ""
                    tvSub.text = "Level: $pct% • $curveName$ditherStr$rgTag"
                    tvSub.setTextColor(scheme.muted)
                    tvMaxLabel.text = tr("0.0 dB (Maks.)", "0.0 dB (Max)")
                    btnMinus.text = "-1 dB"
                    btnPlus.text = "+1 dB"
                    sb.isEnabled = true
                    sb.alpha = 1.0f
                    layoutNotice.visibility = View.GONE
                    btnToggleHwBalance.visibility = View.GONE
                    layoutHwBalanceRow.visibility = View.GONE
                }
                EffectiveVolumeType.INTERNAL_SYSTEM -> {
                    tvMode.text = tr("VOLUME SISTEM INTERNAL (BIT-PERFECT)", "INTERNAL SYSTEM VOLUME (BIT-PERFECT)")
                    tvMode.setTextColor(scheme.accent)
                    tvDbHero.text = "$pct%"
                    tvDbHero.setTextColor(scheme.accent)
                    tvSub.text = tr("Level: $pct% • Penguatan Sistem (C++ 0.0 dB)$rgTag", "Level: $pct% • System Gain (C++ 0.0 dB)$rgTag")
                    tvSub.setTextColor(scheme.muted)
                    tvMaxLabel.text = tr("100% (Maks.)", "100% (Max)")
                    btnMinus.text = tr("- Langkah", "- Step")
                    btnPlus.text = tr("+ Langkah", "+ Step")
                    sb.isEnabled = true
                    sb.alpha = 1.0f
                    layoutNotice.visibility = View.VISIBLE
                    tvNoticeText.text = tr(
                        "Mode Bit-Perfect Aktif: Penguatan DSP C++ tetap murni 0.0 dB. Penggeser dan tombol fisik mengatur penguat internal perangkat secara langsung.",
                        "Bit-Perfect Mode Active: C++ DSP gain stays at 0.0 dB. Slider & physical keys control the device internal amplifier directly."
                    )
                    btnToggleHwBalance.visibility = View.GONE
                    layoutHwBalanceRow.visibility = View.GONE
                }
                EffectiveVolumeType.BIT_PERFECT_LOCKED -> {
                    tvMode.text = tr("TERKUNCI BIT-PERFECT (0 dB)", "BIT-PERFECT LOCKED (0 dB)")
                    tvMode.setTextColor(scheme.accent)
                    tvDbHero.text = "0.0 dB"
                    tvDbHero.setTextColor(scheme.accent)
                    tvSub.text = tr("Level: 100% • Transmisi Bit-Exact Murni", "Level: 100% • Pure Bit-Exact Transmission")
                    tvSub.setTextColor(scheme.muted)
                    tvMaxLabel.text = tr("0.0 dB (Terkunci)", "0.0 dB (Locked)")
                    btnMinus.text = "-1 dB"
                    btnPlus.text = "+1 dB"
                    sb.isEnabled = false
                    sb.alpha = 0.45f
                    layoutNotice.visibility = View.VISIBLE
                    tvNoticeText.text = when {
                        AudioSettingsRepository.volumeModePref == VolumeControlMode.NO_CONTROL.id ->
                            tr(
                                "Mode 'Tanpa Kontrol Volume (0 dB)' dipilih di Pengaturan. Sinyal dikunci pada 0.0 dB untuk kemurnian penuh.",
                                "'No Volume Control (0 dB)' is selected in Settings. Gain is locked at 0.0 dB for full signal purity."
                            )
                        curIsUsb ->
                            tr(
                                "Mode Bit-Perfect / DSD Murni Aktif: USB DAC ini tidak memiliki mixer perangkat keras internal. Atur volume melalui kenop fisik pada DAC atau penguat Anda.",
                                "Bit-Perfect / Native DSD Active: This USB DAC has no internal hardware mixer. Adjust volume using the physical knob on your DAC or amplifier."
                            )
                        else ->
                            tr(
                                "Mode Bit-Perfect Aktif: Penguatan DSP C++ dikunci pada 0.0 dB.",
                                "Bit-Perfect Mode Active: C++ DSP gain is locked at 0.0 dB."
                            )
                    }
                    btnToggleHwBalance.visibility = View.GONE
                    layoutHwBalanceRow.visibility = View.GONE
                }
            }

            if (curEffType == EffectiveVolumeType.BIT_PERFECT_LOCKED) {
                sb.progress = 100
            } else if (!isUserDraggingMainSeekBar) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    sb.setProgress(pct, true)
                } else {
                    sb.progress = pct
                }
            }
            onVolumeUiUpdated?.invoke()
        }

        refreshViewsAction = { updateDialogViews() }

        dialog.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
                AudioSettingsRepository.isInterceptVolumeKeys
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                    AudioPlayerController.adjustVolume(delta)
                    updateDialogViews()
                    resetAutoDismissIfNeeded()
                }
                true
            } else {
                false
            }
        }

        updateDialogViews()

        btnToggleHwBalance.setOnClickListener {
            showHwBalanceRow = !showHwBalanceRow
            updateDialogViews()
            resetAutoDismissIfNeeded()
        }

        tvHwBalanceValue.setOnClickListener {
            AudioSettingsRepository.hardwareBalance = 0.0f
            updateDialogViews()
            resetAutoDismissIfNeeded()
        }

        sbHwBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rawBal = (progress - 50) / 50.0f
                val bal = if (abs(rawBal) < 0.03f) 0.0f else rawBal
                AudioSettingsRepository.hardwareBalance = bal
                tvHwBalanceValue.text = formatHwBalanceText(bal)
                resetAutoDismissIfNeeded()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dismissHandler.removeCallbacks(dismissRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val bal = AudioSettingsRepository.hardwareBalance
                sbHwBalance.progress = round((bal + 1.0f) * 50.0f).toInt().coerceIn(0, 100)
                resetAutoDismissIfNeeded()
            }
        })

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val (_, _, curEffType) = currentVolumeContext()
                if (curEffType != EffectiveVolumeType.BIT_PERFECT_LOCKED) {
                    val maxLimitPct = if (curEffType == EffectiveVolumeType.HARDWARE_DAC && AudioSettingsRepository.isDacSafeLimitEnabled) 80 else 100
                    val targetProgress = progress.coerceAtMost(maxLimitPct)
                    if (progress > maxLimitPct) {
                        seekBar?.progress = maxLimitPct
                    }
                    AudioPlayerController.setVolume(targetProgress / 100.0f)
                    updateDialogViews()
                    resetAutoDismissIfNeeded()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingMainSeekBar = true
                dismissHandler.removeCallbacks(dismissRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingMainSeekBar = false
                updateDialogViews()
                resetAutoDismissIfNeeded()
            }
        })

        fun isUnlocked(): Boolean = currentVolumeContext().third != EffectiveVolumeType.BIT_PERFECT_LOCKED

        btnMute.setOnClickListener {
            if (isUnlocked()) {
                AudioPlayerController.setVolume(0.0f)
                updateDialogViews()
                resetAutoDismissIfNeeded()
            }
        }
        btnMinus.setOnClickListener {
            if (isUnlocked()) {
                AudioPlayerController.adjustVolumeByDb(-1.0f)
                updateDialogViews()
                resetAutoDismissIfNeeded()
            }
        }
        btnPlus.setOnClickListener {
            if (isUnlocked()) {
                AudioPlayerController.adjustVolumeByDb(+1.0f)
                updateDialogViews()
                resetAutoDismissIfNeeded()
            }
        }
        btnMax.setOnClickListener {
            if (isUnlocked()) {
                AudioPlayerController.setVolume(1.0f)
                updateDialogViews()
                resetAutoDismissIfNeeded()
            }
        }
        btnClose.setOnClickListener {
            dismiss()
        }

        dialog.show()

        observerJob?.cancel()
        observerJob = activity.lifecycleScope.launch {
            AudioPlayerController.volumeFraction.collectLatest {
                if (dialog.isShowing) {
                    updateDialogViews()
                }
            }
        }

        resetAutoDismissIfNeeded()
    }

    private fun resetAutoDismissIfNeeded() {
        dismissHandler.removeCallbacks(dismissRunnable)
        if (dialogAutoDismiss && AudioSettingsRepository.isVolumeDialogAutoFade) {
            dismissHandler.postDelayed(dismissRunnable, 2500)
        }
    }
}

