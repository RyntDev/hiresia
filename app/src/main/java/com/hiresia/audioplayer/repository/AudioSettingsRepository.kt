package com.hiresia.audioplayer.repository

import android.content.Context
import android.content.SharedPreferences
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.model.EqBandParam
import com.hiresia.audioplayer.model.EqDefaults
import com.hiresia.audioplayer.model.EqFilterType
import com.hiresia.audioplayer.model.EqProfile
import com.hiresia.audioplayer.native.AudioUtilsJNI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow

enum class VolumeControlMode(val id: Int, val title: String) {
    AUTO(0, "Auto (Hardware jika DAC, Software jika Internal)"),
    HARDWARE(1, "Hardware Volume Control"),
    SOFTWARE(2, "Software Volume Control"),
    NO_CONTROL(3, "No Volume Control")
}

enum class VolumeCurve(val id: Int, val title: String) {
    LINEAR(0, "Linear"),
    CUBIC(1, "Perseptual (Cubic)"),
    LOG_DB(2, "Logarithmic (-96 dB ke 0 dB)")
}

enum class EffectiveVolumeType {
    HARDWARE_DAC,
    SOFTWARE,
    INTERNAL_SYSTEM,
    BIT_PERFECT_LOCKED
}

enum class DsdModeOption(val id: Int, val title: String, val description: String) {
    DSD_TO_PCM(0, "DSD to PCM conversion", "Konversi bitstream DSD ke high-res PCM (EQ & DSP dapat digunakan)"),
    DOP(1, "DoP (DSD over PCM)", "Kirim DSD dibungkus frame PCM v1.1 (Marker 0x05/0xFA) [Standar DAC]"),
    NATIVE_DSD(2, "Native DSD", "Kirim bitstream 1-bit DSD murni langsung ke chip DAC")
}

object AudioSettingsRepository {
    private const val PREF_NAME = "hiresia_audiophile_prefs"
    private const val KEY_BIT_PERFECT = "key_bit_perfect"
    private const val KEY_HIRES_DRIVER = "key_hires_driver"
    private const val KEY_USB_EXCLUSIVE = "key_usb_exclusive"
    private const val KEY_BLOCK_USB_HID = "key_block_usb_hid"
    private const val KEY_HIRES_DSD_WHEN_POSSIBLE = "key_hires_dsd_when_possible"
    private const val KEY_USB_DSD_MODE = "key_usb_dsd_mode"
    private const val KEY_USB_BUFFER_SIZE = "key_usb_buffer_size"
    private const val KEY_USE_EQ = "key_use_eq"
    private const val KEY_AUTOGAIN = "key_autogain"
    private const val KEY_CROSSFEED = "key_crossfeed"
    private const val KEY_CROSSFEED_LEVEL = "key_crossfeed_level"
    private const val KEY_USE_BALANCE = "key_use_balance"
    private const val KEY_BALANCE = "key_balance"
    private const val KEY_HARDWARE_VOL = "key_hardware_vol"
    private const val KEY_THEME_MODE = "key_theme_mode" // 0: Gelap, 1: Terang, 2: Sistem
    private const val KEY_ACCENT_COLOR = "key_accent_color" // 0: Lime, 1: Gold, 2: Cyan, 3: Coral, 4: Purple
    private const val KEY_LIST_DENSITY = "key_list_density" // 0: Kompak, 1: Luas (Cozy)
    private const val KEY_SOFTWARE_VOL = "key_software_vol"
    private const val KEY_VOLUME_MODE = "key_volume_mode"
    private const val KEY_VOLUME_CURVE = "key_volume_curve"
    private const val KEY_HARDWARE_VOLUME_CURVE = "key_hardware_volume_curve"
    private const val KEY_DAC_SAFE_LIMIT = "key_dac_safe_limit"
    private const val KEY_VOLUME_STEP_DB = "key_volume_step_db"
    private const val KEY_DITHER_ENABLED = "key_dither_enabled"
    private const val KEY_FORCE_VOLUME_SYNC = "key_force_volume_sync"
    private const val KEY_HARDWARE_BALANCE = "key_hardware_balance"
    private const val KEY_ANDROID12_VOLUME_FIX = "key_android12_volume_fix"
    private const val KEY_VOLUME_DIALOG_AUTO_FADE = "key_volume_dialog_auto_fade"
    private const val KEY_REPLAYGAIN_MODE = "key_replaygain_mode"
    private const val KEY_REPLAYGAIN_PREAMP = "key_replaygain_preamp"
    private const val KEY_REPLAYGAIN_PREVENT_CLIPPING = "key_replaygain_prevent_clipping"
    private const val KEY_INTERCEPT_VOLUME_KEYS = "key_intercept_volume_keys"
    private const val KEY_EQ_PREAMP = "key_eq_preamp"
    private const val KEY_EQ_ACTIVE_PRESET = "key_eq_active_preset"
    private const val KEY_EQ_CUSTOM_PRESETS = "key_eq_custom_presets_json"
    private const val KEY_APP_LANGUAGE = "key_app_language" // "id" or "en"
    private const val KEY_LAST_SEEN_WELCOME_VERSION = "key_last_seen_welcome_version_v2"

    var lastSeenWelcomeVersion: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_LAST_SEEN_WELCOME_VERSION, "") ?: "" else ""
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_LAST_SEEN_WELCOME_VERSION, value).apply()
        }

    const val DAC_DEFAULT_VOLUME = 0.20f // Default volume 20% saat DAC pertama kali dicolokkan (mencegah kaget/telinga sakit)
    const val DAC_MAX_SAFETY_VOLUME = 0.80f // Puncak volume DAC dibatasi maksimal 80% demi keamanan pendengaran

    private lateinit var prefs: SharedPreferences

    private val _interceptVolumeKeysFlow = MutableStateFlow(true)
    val interceptVolumeKeysFlow: StateFlow<Boolean> = _interceptVolumeKeysFlow.asStateFlow()

    private val _android12VolumeFixFlow = MutableStateFlow(true)
    val android12VolumeFixFlow: StateFlow<Boolean> = _android12VolumeFixFlow.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("key_curve_v2_migrated", false)) {
            // Migrasi otomatis ke Kurva Perseptual Cubic (1) agar volume software halus dan tidak drop mendadak
            prefs.edit().putInt(KEY_VOLUME_CURVE, 1).putBoolean("key_curve_v2_migrated", true).apply()
        }
        if (!prefs.getBoolean("key_obsidian_en_default_applied_v2", false)) {
            prefs.edit()
                .putInt("key_color_palette", PALETTE_D_OBSIDIAN_GOLD)
                .putInt(KEY_THEME_MODE, THEME_MODE_DARK)
                .putString(KEY_APP_LANGUAGE, "en")
                .putBoolean("key_obsidian_en_default_applied_v2", true)
                .apply()
        }
        _interceptVolumeKeysFlow.value = isInterceptVolumeKeys
        _android12VolumeFixFlow.value = isAndroid12ScreenOffVolumeFix
        applyAllToNative()
    }

    var isBitPerfect: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_BIT_PERFECT, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BIT_PERFECT, value).apply()
            AudioUtilsJNI.AudioServer_setBitPerfectWhenPossible(value)
        }

    var isHiResDriverEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_HIRES_DRIVER, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_HIRES_DRIVER, value).apply()
        }

    var isUsbExclusiveEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USB_EXCLUSIVE, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_USB_EXCLUSIVE, value).apply()
        }

    var isBlockUsbHidEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_BLOCK_USB_HID, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BLOCK_USB_HID, value).apply()
            AudioUtilsJNI.AudioServer_setBlockUsbHid(value)
        }

    var isDsdHiResWhenPossible: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_HIRES_DSD_WHEN_POSSIBLE, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_HIRES_DSD_WHEN_POSSIBLE, value).apply()
        }

    var usbDsdMode: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_USB_DSD_MODE, 1) else 1 // default DoP (1)
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_USB_DSD_MODE, value).apply()
        }

    fun getEffectiveDsdMode(isUsbAttached: Boolean, isDsdTrack: Boolean = true): DsdModeOption {
        return if (isUsbAttached) {
            when (usbDsdMode) {
                0 -> DsdModeOption.DSD_TO_PCM
                1 -> DsdModeOption.DOP
                2 -> DsdModeOption.NATIVE_DSD
                else -> DsdModeOption.DOP
            }
        } else {
            // Internal phone audio output (Internal Hi-Res driver)
            if (isHiResDriverEnabled && isDsdHiResWhenPossible) {
                DsdModeOption.DOP
            } else {
                DsdModeOption.DSD_TO_PCM
            }
        }
    }

    fun getPipelineChain(isUsbConnected: Boolean): String {
        val bitPerfect = isBitPerfect
        val hiRes = isHiResDriverEnabled
        val usbEx = isUsbExclusiveEnabled && isUsbConnected

        return when {
            isUsbConnected && usbEx && bitPerfect ->
                "Source → Bit-Perfect → USB Exclusive Driver → DAC"
            isUsbConnected && usbEx ->
                "Source → USB Exclusive Driver → DAC"
            isUsbConnected && bitPerfect ->
                "Source → Bit-Perfect → Android USB Audio → DAC"
            isUsbConnected ->
                "Source → Android USB Audio → DAC"
            hiRes && bitPerfect ->
                "Source → Bit-Perfect → Hi-Res Direct Driver → Internal Audio"
            hiRes ->
                "Source → Hi-Res Direct Driver → Internal Audio"
            bitPerfect ->
                "Source → Bit-Perfect → Android Audio → Internal Audio"
            else ->
                "Source → Android System Mixer → Internal Audio"
        }
    }

    var usbBufferSize: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_USB_BUFFER_SIZE, "Normal (1024)") ?: "Normal (1024)" else "Normal (1024)"
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_USB_BUFFER_SIZE, value).apply()
        }

    var isGraphicEqEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USE_EQ, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_USE_EQ, value).apply()
            AudioUtilsJNI.AudioServer_setUseGraphicEQ(value)
        }

    var isAutoGainEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTOGAIN, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTOGAIN, value).apply()
            AudioUtilsJNI.AudioServer_setEQAutoGain(value)
        }

    var isCrossfeedEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_CROSSFEED, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_CROSSFEED, value).apply()
            AudioUtilsJNI.AudioServer_setCrossFeedState(value)
        }

    var crossfeedLevel: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_CROSSFEED_LEVEL, 5) else 5
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_CROSSFEED_LEVEL, value).apply()
            AudioUtilsJNI.AudioServer_setCrossFeedLevel(value)
        }

    var isBalanceEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USE_BALANCE, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_USE_BALANCE, value).apply()
            AudioUtilsJNI.AudioServer_setUseBalance(value)
        }

    var stereoBalance: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_BALANCE, 0.0f) else 0.0f
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_BALANCE, value).apply()
            AudioUtilsJNI.AudioServer_setUseBalance(true)
            AudioUtilsJNI.AudioServer_setBalance(value)
        }

    fun setBandGain(band: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-15.0f, 15.0f)
        val cur = getBandParam(band)
        setBandParam(band, cur.copy(gainDb = clamped))
    }

    fun getBandGain(band: Int): Float {
        return getBandParam(band).gainDb
    }

    var eqPreampDb: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_EQ_PREAMP, 0.0f).coerceIn(-15.0f, 12.0f) else 0.0f
        set(value) {
            val clamped = value.coerceIn(-15.0f, 12.0f)
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_EQ_PREAMP, clamped).apply()
            AudioUtilsJNI.AudioServer_setEQPreamp(clamped)
        }

    var activeEqPresetName: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_EQ_ACTIVE_PRESET, "Normal") ?: "Normal" else "Normal"
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_EQ_ACTIVE_PRESET, value).apply()
        }

    fun getBandParam(band: Int): EqBandParam {
        val defaults = EqDefaults.defaultBands()
        val def = defaults.getOrElse(band) { defaults[0] }
        if (!::prefs.isInitialized) return def
        val gain = prefs.getFloat("key_eq_band_$band", def.gainDb).coerceIn(-15.0f, 15.0f)
        val freq = prefs.getFloat("key_eq_freq_$band", def.freqHz).coerceIn(10.0f, 24000.0f)
        val q = prefs.getFloat("key_eq_q_$band", def.q).coerceIn(0.1f, 18.0f)
        val typeId = prefs.getInt("key_eq_type_$band", def.type.id)
        val enabled = prefs.getBoolean("key_eq_en_$band", def.enabled)
        return EqBandParam(
            freqHz = freq,
            gainDb = gain,
            q = q,
            type = EqFilterType.fromId(typeId),
            enabled = enabled
        )
    }

    fun getAllBandParams(): List<EqBandParam> {
        return List(EqDefaults.NUM_BANDS) { getBandParam(it) }
    }

    fun setBandParam(band: Int, param: EqBandParam) {
        if (band !in 0 until EqDefaults.NUM_BANDS) return
        val clamped = param.copy(
            freqHz = param.freqHz.coerceIn(10.0f, 24000.0f),
            gainDb = param.gainDb.coerceIn(-15.0f, 15.0f),
            q = param.q.coerceIn(0.1f, 18.0f)
        )
        if (::prefs.isInitialized) {
            prefs.edit()
                .putFloat("key_eq_band_$band", clamped.gainDb)
                .putFloat("key_eq_freq_$band", clamped.freqHz)
                .putFloat("key_eq_q_$band", clamped.q)
                .putInt("key_eq_type_$band", clamped.type.id)
                .putBoolean("key_eq_en_$band", clamped.enabled)
                .apply()
        }
        AudioUtilsJNI.AudioServer_setBandParam(
            band,
            clamped.freqHz,
            clamped.gainDb,
            clamped.q,
            clamped.type.id,
            clamped.enabled
        )
    }

    fun applyEqProfile(profile: EqProfile) {
        eqPreampDb = profile.preampDb
        activeEqPresetName = profile.name
        val defaults = EqDefaults.defaultBands()
        for (i in 0 until EqDefaults.NUM_BANDS) {
            val b = profile.bands.getOrElse(i) { defaults[i] }
            setBandParam(i, b)
        }
    }

    fun resetEqToDefault() {
        val normal = EqDefaults.builtInProfiles().first()
        applyEqProfile(normal)
    }

    fun getCustomEqPresets(): List<EqProfile> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_EQ_CUSTOM_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val defaults = EqDefaults.defaultBands()
            val out = mutableListOf<EqProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val preamp = obj.optDouble("preampDb", 0.0).toFloat().coerceIn(-15f, 12f)
                val bArr = obj.optJSONArray("bands")
                val bands = MutableList(EqDefaults.NUM_BANDS) { idx ->
                    val bo = bArr?.optJSONObject(idx)
                    if (bo != null) {
                        EqBandParam(
                            freqHz = bo.optDouble("f", defaults[idx].freqHz.toDouble()).toFloat().coerceIn(10f, 24000f),
                            gainDb = bo.optDouble("g", 0.0).toFloat().coerceIn(-15f, 15f),
                            q = bo.optDouble("q", defaults[idx].q.toDouble()).toFloat().coerceIn(0.1f, 18f),
                            type = EqFilterType.fromId(bo.optInt("t", defaults[idx].type.id)),
                            enabled = bo.optBoolean("e", true)
                        )
                    } else {
                        defaults[idx]
                    }
                }
                out.add(EqProfile(name = name, preampDb = preamp, bands = bands, isBuiltIn = false))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomEqPreset(name: String, preampDb: Float, bands: List<EqBandParam>) {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || !::prefs.isInitialized) return
        val current = getCustomEqPresets().filterNot { it.name.equals(cleanName, ignoreCase = true) }.toMutableList()
        val defaults = EqDefaults.defaultBands()
        val normalizedBands = List(EqDefaults.NUM_BANDS) { idx -> bands.getOrElse(idx) { defaults[idx] } }
        current.add(0, EqProfile(name = cleanName, preampDb = preampDb.coerceIn(-15f, 12f), bands = normalizedBands, isBuiltIn = false))
        persistCustomEqPresets(current)
        activeEqPresetName = cleanName
    }

    fun deleteCustomEqPreset(name: String) {
        if (!::prefs.isInitialized) return
        val updated = getCustomEqPresets().filterNot { it.name.equals(name, ignoreCase = true) }
        persistCustomEqPresets(updated)
        if (activeEqPresetName.equals(name, ignoreCase = true)) {
            activeEqPresetName = "Kustom"
        }
    }

    private fun persistCustomEqPresets(list: List<EqProfile>) {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("preampDb", p.preampDb.toDouble())
            val bArr = JSONArray()
            for (b in p.bands.take(EqDefaults.NUM_BANDS)) {
                val bo = JSONObject()
                bo.put("f", b.freqHz.toDouble())
                bo.put("g", b.gainDb.toDouble())
                bo.put("q", b.q.toDouble())
                bo.put("t", b.type.id)
                bo.put("e", b.enabled)
                bArr.put(bo)
            }
            obj.put("bands", bArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_EQ_CUSTOM_PRESETS, arr.toString()).apply()
    }

    /* -------------------------------------------------------------
     * C++ Engine Audiophile Volume Settings
     * ------------------------------------------------------------- */
    var volumeModePref: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_VOLUME_MODE, 0) else 0
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_VOLUME_MODE, value).apply()
        }

    var volumeCurve: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_VOLUME_CURVE, 1) else 1
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_VOLUME_CURVE, value).apply()
        }

    var isInterceptVolumeKeys: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_INTERCEPT_VOLUME_KEYS, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_INTERCEPT_VOLUME_KEYS, value).apply()
            _interceptVolumeKeysFlow.value = value
        }

    var hardwareVolumeCurve: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_HARDWARE_VOLUME_CURVE, 0).coerceIn(0, 2) else 0
        set(value) {
            val clamped = value.coerceIn(0, 2)
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_HARDWARE_VOLUME_CURVE, clamped).apply()
            AudioUtilsJNI.AudioServer_setHardwareVolumeCurve(clamped)
        }

    var isDacSafeLimitEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_DAC_SAFE_LIMIT, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_DAC_SAFE_LIMIT, value).apply()
            AudioUtilsJNI.AudioServer_setHardwareVolumeLimit(getDacMaxVolumeLimit())
        }

    fun getDacMaxVolumeLimit(): Float = if (isDacSafeLimitEnabled) DAC_MAX_SAFETY_VOLUME else 1.0f

    var volumeStepDb: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_VOLUME_STEP_DB, 1.0f).coerceIn(0.5f, 3.0f) else 1.0f
        set(value) {
            val clamped = value.coerceIn(0.5f, 3.0f)
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_VOLUME_STEP_DB, clamped).apply()
        }

    var isDitherEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_DITHER_ENABLED, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_DITHER_ENABLED, value).apply()
            AudioUtilsJNI.AudioServer_setDitherEnabled(value)
        }

    var isForceVolumeSync: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_FORCE_VOLUME_SYNC, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_FORCE_VOLUME_SYNC, value).apply()
            AudioUtilsJNI.AudioServer_setForceVolumeSync(value)
        }

    var hardwareBalance: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_HARDWARE_BALANCE, 0.0f).coerceIn(-1.0f, 1.0f) else 0.0f
        set(value) {
            val clamped = value.coerceIn(-1.0f, 1.0f)
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_HARDWARE_BALANCE, clamped).apply()
            AudioUtilsJNI.AudioServer_setHardwareBalance(clamped)
        }

    var isAndroid12ScreenOffVolumeFix: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_ANDROID12_VOLUME_FIX, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ANDROID12_VOLUME_FIX, value).apply()
            _android12VolumeFixFlow.value = value
        }

    var isVolumeDialogAutoFade: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_VOLUME_DIALOG_AUTO_FADE, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_VOLUME_DIALOG_AUTO_FADE, value).apply()
        }

    var replayGainMode: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_REPLAYGAIN_MODE, 0).coerceIn(0, 2) else 0
        set(value) {
            val clamped = value.coerceIn(0, 2)
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_REPLAYGAIN_MODE, clamped).apply()
            AudioUtilsJNI.AudioServer_setReplayGainMode(clamped)
        }

    var replayGainPreampDb: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_REPLAYGAIN_PREAMP, 0.0f).coerceIn(-12.0f, 12.0f) else 0.0f
        set(value) {
            val clamped = value.coerceIn(-12.0f, 12.0f)
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_REPLAYGAIN_PREAMP, clamped).apply()
        }

    var isReplayGainPreventClipping: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_REPLAYGAIN_PREVENT_CLIPPING, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_REPLAYGAIN_PREVENT_CLIPPING, value).apply()
        }

    var hardwareVolume: Float
        get() = if (::prefs.isInitialized) {
            prefs.getFloat(KEY_HARDWARE_VOL, DAC_DEFAULT_VOLUME).coerceIn(0.0f, getDacMaxVolumeLimit())
        } else DAC_DEFAULT_VOLUME
        set(value) {
            val clamped = value.coerceIn(0.0f, getDacMaxVolumeLimit())
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putFloat(KEY_HARDWARE_VOL, clamped)
                    .putBoolean("key_hardware_vol_customized", true)
                    .apply()
            }
            AudioUtilsJNI.AudioServer_setHardwareVolume(clamped)
        }

    fun getHardwareVolumeForDevice(vid: Int, pid: Int): Float {
        if (!::prefs.isInitialized) return DAC_DEFAULT_VOLUME
        val maxLim = getDacMaxVolumeLimit()
        val devKey = "key_dac_vol_${vid}_${pid}"
        return if (prefs.contains(devKey)) {
            prefs.getFloat(devKey, DAC_DEFAULT_VOLUME).coerceIn(0.0f, maxLim)
        } else if (prefs.contains("key_hardware_vol_customized")) {
            prefs.getFloat(KEY_HARDWARE_VOL, DAC_DEFAULT_VOLUME).coerceIn(0.0f, maxLim)
        } else {
            DAC_DEFAULT_VOLUME
        }
    }

    fun setHardwareVolumeForDevice(vid: Int, pid: Int, vol: Float) {
        val clamped = vol.coerceIn(0.0f, getDacMaxVolumeLimit())
        if (::prefs.isInitialized) {
            val devKey = "key_dac_vol_${vid}_${pid}"
            prefs.edit()
                .putFloat(devKey, clamped)
                .putFloat(KEY_HARDWARE_VOL, clamped)
                .putBoolean("key_hardware_vol_customized", true)
                .apply()
        }
        AudioUtilsJNI.AudioServer_setHardwareVolume(clamped)
    }

    var softwareVolume: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_SOFTWARE_VOL, DAC_DEFAULT_VOLUME) else DAC_DEFAULT_VOLUME
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            if (::prefs.isInitialized) prefs.edit().putFloat(KEY_SOFTWARE_VOL, clamped).apply()
            val curved = calculateCurvedGain(clamped)
            AudioUtilsJNI.AudioServer_setSoftwareVolume(curved)
        }

    // Backward-compatibility alias
    var dacVolume: Float
        get() = hardwareVolume
        set(value) {
            hardwareVolume = value
            softwareVolume = value
        }

    fun getEffectiveVolumeType(isUsbConnected: Boolean, isHardwareSupported: Boolean, isDsdTrack: Boolean = false): EffectiveVolumeType {
        // Raw DSD (DoP / Native DSD) cannot undergo PCM software DSP attenuation
        if (isDsdTrack) {
            val dsdMode = getEffectiveDsdMode(isUsbConnected, true)
            if (dsdMode != DsdModeOption.DSD_TO_PCM) {
                if (volumeModePref == VolumeControlMode.NO_CONTROL.id) {
                    return EffectiveVolumeType.BIT_PERFECT_LOCKED
                }
                return when {
                    isUsbConnected && isHardwareSupported -> EffectiveVolumeType.HARDWARE_DAC
                    !isUsbConnected -> EffectiveVolumeType.INTERNAL_SYSTEM
                    else -> EffectiveVolumeType.BIT_PERFECT_LOCKED
                }
            }
        }

        return when (volumeModePref) {
            VolumeControlMode.NO_CONTROL.id -> EffectiveVolumeType.BIT_PERFECT_LOCKED
            VolumeControlMode.SOFTWARE.id -> EffectiveVolumeType.SOFTWARE
            VolumeControlMode.HARDWARE.id -> {
                if (isUsbConnected && isHardwareSupported) EffectiveVolumeType.HARDWARE_DAC
                else if (!isUsbConnected) EffectiveVolumeType.INTERNAL_SYSTEM
                else EffectiveVolumeType.SOFTWARE
            }
            else -> { // VolumeControlMode.AUTO (0)
                if (isUsbConnected) {
                    if (isHardwareSupported) EffectiveVolumeType.HARDWARE_DAC else EffectiveVolumeType.SOFTWARE
                } else {
                    if (isBitPerfect) EffectiveVolumeType.INTERNAL_SYSTEM else EffectiveVolumeType.SOFTWARE
                }
            }
        }
    }

    fun applyHardwareVolumeCurve(vol: Float, curve: Int = hardwareVolumeCurve): Float {
        val v = vol.coerceIn(0.0f, 1.0f)
        return when (curve) {
            1 -> v.pow(0.65f)
            2 -> v.pow(1.5f)
            else -> v
        }
    }

    fun inverseHardwareVolumeCurve(mapped: Float, curve: Int = hardwareVolumeCurve): Float {
        val m = mapped.coerceIn(0.0f, 1.0f)
        return when (curve) {
            1 -> m.pow(1.0f / 0.65f)
            2 -> m.pow(1.0f / 1.5f)
            else -> m
        }
    }

    fun calculateCurvedGain(vol: Float, curve: Int = volumeCurve): Float {
        val clamped = vol.coerceIn(0.0f, 1.0f)
        return when (curve) {
            0 -> clamped // Linear
            1 -> clamped * clamped * clamped // Perceptual Cubic (Classic fader taper)
            2 -> { // Logarithmic Decibels (-96 dB to 0 dB)
                if (clamped <= 0.0001f) 0.0f
                else Math.pow(10.0, ((1.0 - clamped) * -96.0) / 20.0).toFloat()
            }
            else -> clamped
        }
    }

    fun formatDecibel(vol: Float, isHardware: Boolean, minRaw: Int = 0, maxRaw: Int = 0): String {
        if (isHardware && maxRaw > minRaw) {
            if (vol <= 0.0001f) return "-∞ dB (Mute)"
            val mapped = applyHardwareVolumeCurve(vol)
            val raw = minRaw + (mapped * (maxRaw - minRaw))
            val db = raw * 0.00390625f // 1/256.0 dB format (UAC standard)
            return String.format(Locale.US, "%+.1f dB", db)
        }
        if (vol <= 0.0001f) return "-∞ dB (Mute)"
        if (vol >= 0.999f) return "-0.0 dB"
        val gain = calculateCurvedGain(vol)
        if (gain <= 1.0e-5f) return "-∞ dB (Mute)"
        val db = 20.0 * Math.log10(gain.toDouble())
        return String.format(Locale.US, "%.1f dB", db)
    }

    /* -------------------------------------------------------------
     * Color Palettes & Appearance (Obsidian Gold, Zest Light, Aegean Light)
     * ------------------------------------------------------------- */
    const val PALETTE_A_TERAKOTA = 0
    const val PALETTE_B_ZAITUN = 1
    const val PALETTE_C_AEGEAN = 2
    const val PALETTE_C_BIRU_MALAM = 2
    const val PALETTE_D_OBSIDIAN_GOLD = 3

    const val THEME_MODE_DARK = 0
    const val THEME_MODE_LIGHT = 1

    data class ColorPaletteScheme(
        val name: String,
        val bg: Int,
        val surface: Int,
        val surface2: Int,
        val line: Int,
        val line2: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val page: Int
    )

    var paletteIndex: Int
        get() = if (::prefs.isInitialized) prefs.getInt("key_color_palette", PALETTE_D_OBSIDIAN_GOLD) else PALETTE_D_OBSIDIAN_GOLD
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt("key_color_palette", value).apply()
        }

    val isObsidianGold: Boolean
        get() = paletteIndex == PALETTE_D_OBSIDIAN_GOLD

    fun getDefaultCoverResId(): Int {
        return when (paletteIndex) {
            PALETTE_D_OBSIDIAN_GOLD -> R.drawable.cover_obsidian_wave
            PALETTE_C_AEGEAN -> R.drawable.cover_aegean_wave
            else -> R.drawable.cover_zest_wave
        }
    }

    var themeMode: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_THEME_MODE, THEME_MODE_DARK) else THEME_MODE_DARK
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putInt(KEY_THEME_MODE, value).apply()
        }

    var appLanguage: String
        get() = if (::prefs.isInitialized) (prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en") else "en"
        set(value) {
            val clean = if (value == "en") "en" else "id"
            if (::prefs.isInitialized) prefs.edit().putString(KEY_APP_LANGUAGE, clean).apply()
        }

    val isEnglish: Boolean
        get() = appLanguage == "en"

    // Deprecated backward-compatibility alias
    var accentColorIndex: Int
        get() = paletteIndex
        set(value) { paletteIndex = value }

    // Deprecated backward-compatibility alias
    var listDensity: Int
        get() = 0
        set(_) {}

    fun getCurrentScheme(): ColorPaletteScheme {
        val isDark = (themeMode == THEME_MODE_DARK)
        return when (paletteIndex) {
            PALETTE_D_OBSIDIAN_GOLD -> {
                ColorPaletteScheme(
                    name = "Obsidian Gold",
                    bg = android.graphics.Color.parseColor("#161617"),
                    surface = android.graphics.Color.parseColor("#1E1E20"),
                    surface2 = android.graphics.Color.parseColor("#2A2A2C"),
                    line = android.graphics.Color.parseColor("#2E2E30"),
                    line2 = android.graphics.Color.parseColor("#4A4A4D"),
                    text = android.graphics.Color.parseColor("#F5F5F7"),
                    muted = android.graphics.Color.parseColor("#9E9EA3"),
                    accent = android.graphics.Color.parseColor("#F3EAC3"),
                    onAccent = android.graphics.Color.parseColor("#14130F"),
                    page = android.graphics.Color.parseColor("#161617")
                )
            }
            PALETTE_C_AEGEAN -> {
                // Exact Aegean Terang matching PDF Reference
                ColorPaletteScheme(
                    name = "Aegean Terang",
                    bg = android.graphics.Color.parseColor("#F5EEDC"),
                    surface = android.graphics.Color.parseColor("#E8DFCB"),
                    surface2 = android.graphics.Color.parseColor("#DED3C4"),
                    line = android.graphics.Color.parseColor("#DCD0B4"),
                    line2 = android.graphics.Color.parseColor("#C4D4E3"),
                    text = android.graphics.Color.parseColor("#14233A"),
                    muted = android.graphics.Color.parseColor("#4F5B6E"),
                    accent = android.graphics.Color.parseColor("#1664B0"),
                    onAccent = android.graphics.Color.parseColor("#F6F7E8"),
                    page = android.graphics.Color.parseColor("#F5EEDC")
                )
            }
            PALETTE_B_ZAITUN -> {
                if (isDark) {
                    ColorPaletteScheme(
                        name = "B Zest / Zaitun",
                        bg = android.graphics.Color.parseColor("#12140F"),
                        surface = android.graphics.Color.parseColor("#1F2218"),
                        surface2 = android.graphics.Color.parseColor("#282C20"),
                        line = android.graphics.Color.parseColor("#343829"),
                        line2 = android.graphics.Color.parseColor("#454A38"),
                        text = android.graphics.Color.parseColor("#E8E6D9"),
                        muted = android.graphics.Color.parseColor("#9A9D8A"),
                        accent = android.graphics.Color.parseColor("#B7BF6A"),
                        onAccent = android.graphics.Color.parseColor("#12140F"),
                        page = android.graphics.Color.parseColor("#0C0E09")
                    )
                } else {
                    // Exact Zest Light matching PDF
                    ColorPaletteScheme(
                        name = "Zest Terang",
                        bg = android.graphics.Color.parseColor("#F5EEDC"),
                        surface = android.graphics.Color.parseColor("#E8DFCB"),
                        surface2 = android.graphics.Color.parseColor("#DED3C4"),
                        line = android.graphics.Color.parseColor("#DCD0B4"),
                        line2 = android.graphics.Color.parseColor("#C8CBB0"),
                        text = android.graphics.Color.parseColor("#1E2018"),
                        muted = android.graphics.Color.parseColor("#5F6350"),
                        accent = android.graphics.Color.parseColor("#1E3524"),
                        onAccent = android.graphics.Color.parseColor("#F6F7E8"),
                        page = android.graphics.Color.parseColor("#F5EEDC")
                    )
                }
            }
            PALETTE_C_BIRU_MALAM -> {
                if (isDark) {
                    ColorPaletteScheme(
                        name = "C Biru malam",
                        bg = android.graphics.Color.parseColor("#101419"),
                        surface = android.graphics.Color.parseColor("#1B2129"),
                        surface2 = android.graphics.Color.parseColor("#232B35"),
                        line = android.graphics.Color.parseColor("#2D3643"),
                        line2 = android.graphics.Color.parseColor("#3D4857"),
                        text = android.graphics.Color.parseColor("#E6E8EB"),
                        muted = android.graphics.Color.parseColor("#8F99A3"),
                        accent = android.graphics.Color.parseColor("#D8B98A"),
                        onAccent = android.graphics.Color.parseColor("#101419"),
                        page = android.graphics.Color.parseColor("#0A0D11")
                    )
                } else {
                    ColorPaletteScheme(
                        name = "C Biru malam",
                        bg = android.graphics.Color.parseColor("#EEF0F2"),
                        surface = android.graphics.Color.parseColor("#E0E4E8"),
                        surface2 = android.graphics.Color.parseColor("#D4D9DF"),
                        line = android.graphics.Color.parseColor("#C3CAD2"),
                        line2 = android.graphics.Color.parseColor("#A9B2BD"),
                        text = android.graphics.Color.parseColor("#151A20"),
                        muted = android.graphics.Color.parseColor("#5A6470"),
                        accent = android.graphics.Color.parseColor("#8A6A3A"),
                        onAccent = android.graphics.Color.parseColor("#FFF9EF"),
                        page = android.graphics.Color.parseColor("#E2E5E9")
                    )
                }
            }
            else -> { // PALETTE_A_TERAKOTA
                if (isDark) {
                    ColorPaletteScheme(
                        name = "A Terakota",
                        bg = android.graphics.Color.parseColor("#161412"),
                        surface = android.graphics.Color.parseColor("#26221E"),
                        surface2 = android.graphics.Color.parseColor("#2F2A25"),
                        line = android.graphics.Color.parseColor("#3A342E"),
                        line2 = android.graphics.Color.parseColor("#4A433C"),
                        text = android.graphics.Color.parseColor("#EDE6DC"),
                        muted = android.graphics.Color.parseColor("#9C9389"),
                        accent = android.graphics.Color.parseColor("#D9774F"),
                        onAccent = android.graphics.Color.parseColor("#161412"),
                        page = android.graphics.Color.parseColor("#0E0C0B")
                    )
                } else {
                    ColorPaletteScheme(
                        name = "A Terakota",
                        bg = android.graphics.Color.parseColor("#F4EEE4"),
                        surface = android.graphics.Color.parseColor("#E8DFD2"),
                        surface2 = android.graphics.Color.parseColor("#DED3C4"),
                        line = android.graphics.Color.parseColor("#CFC3B2"),
                        line2 = android.graphics.Color.parseColor("#B9AC9A"),
                        text = android.graphics.Color.parseColor("#231F1B"),
                        muted = android.graphics.Color.parseColor("#6B6258"),
                        accent = android.graphics.Color.parseColor("#B5502A"),
                        onAccent = android.graphics.Color.parseColor("#FFF8F0"),
                        page = android.graphics.Color.parseColor("#E6DDD0")
                    )
                }
            }
        }
    }

    fun getAccentColor(context: Context? = null): Int {
        return getCurrentScheme().accent
    }

    fun applyThemeMode() {
        val mode = when (themeMode) {
            THEME_MODE_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyAllToNative() {
        AudioUtilsJNI.AudioServer_setBitPerfectWhenPossible(isBitPerfect)
        AudioUtilsJNI.AudioServer_setBlockUsbHid(isBlockUsbHidEnabled)
        val dsdMode = getEffectiveDsdMode(false, false)
        AudioUtilsJNI.AudioServer_setDsdMode(dsdMode.id)
        AudioUtilsJNI.AudioServer_setUseGraphicEQ(isGraphicEqEnabled)
        AudioUtilsJNI.AudioServer_setEQAutoGain(isAutoGainEnabled)
        AudioUtilsJNI.AudioServer_setCrossFeedState(isCrossfeedEnabled)
        AudioUtilsJNI.AudioServer_setCrossFeedLevel(crossfeedLevel)
        AudioUtilsJNI.AudioServer_setUseBalance(isBalanceEnabled)
        AudioUtilsJNI.AudioServer_setBalance(stereoBalance)
        AudioUtilsJNI.AudioServer_setHardwareVolumeLimit(getDacMaxVolumeLimit())
        AudioUtilsJNI.AudioServer_setHardwareVolumeCurve(hardwareVolumeCurve)
        AudioUtilsJNI.AudioServer_setHardwareBalance(hardwareBalance)
        AudioUtilsJNI.AudioServer_setForceVolumeSync(isForceVolumeSync)
        AudioUtilsJNI.AudioServer_setDitherEnabled(isDitherEnabled)
        AudioUtilsJNI.AudioServer_setHardwareVolume(hardwareVolume)
        AudioUtilsJNI.AudioServer_setSoftwareVolume(calculateCurvedGain(softwareVolume))
        AudioUtilsJNI.AudioServer_setReplayGainMode(replayGainMode)

        AudioUtilsJNI.AudioServer_setEQPreamp(eqPreampDb)
        for (i in 0 until EqDefaults.NUM_BANDS) {
            val p = getBandParam(i)
            AudioUtilsJNI.AudioServer_setBandParam(i, p.freqHz, p.gainDb, p.q, p.type.id, p.enabled)
        }
    }
}
