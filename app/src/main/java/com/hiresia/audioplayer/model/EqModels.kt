package com.hiresia.audioplayer.model

import java.util.Locale

enum class EqFilterType(val id: Int, val shortCode: String, val displayName: String) {
    PEAKING(0, "PK", "Peaking (Bell)"),
    LOW_SHELF(1, "LSC", "Low Shelf"),
    HIGH_SHELF(2, "HSC", "High Shelf"),
    LOW_PASS(3, "LPF", "Low Pass"),
    HIGH_PASS(4, "HPF", "High Pass");

    companion object {
        fun fromId(id: Int): EqFilterType = entries.firstOrNull { it.id == id } ?: PEAKING

        fun fromCode(code: String): EqFilterType {
            return when (code.trim().uppercase(Locale.US)) {
                "LS", "LSC", "LSQ" -> LOW_SHELF
                "HS", "HSC", "HSQ" -> HIGH_SHELF
                "LP", "LPF", "LPQ" -> LOW_PASS
                "HP", "HPF", "HPQ" -> HIGH_PASS
                else -> PEAKING
            }
        }
    }
}

data class EqBandParam(
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
    val type: EqFilterType,
    val enabled: Boolean = true
)

data class EqProfile(
    val name: String,
    val preampDb: Float,
    val bands: List<EqBandParam>,
    val isBuiltIn: Boolean = false
)

object EqDefaults {
    const val NUM_BANDS = 10
    val DEFAULT_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    fun defaultBands(): List<EqBandParam> {
        return List(NUM_BANDS) { i ->
            val type = when (i) {
                0 -> EqFilterType.LOW_SHELF
                NUM_BANDS - 1 -> EqFilterType.HIGH_SHELF
                else -> EqFilterType.PEAKING
            }
            val q = if (i == 0 || i == NUM_BANDS - 1) 0.71f else 1.41f
            EqBandParam(
                freqHz = DEFAULT_FREQS[i],
                gainDb = 0.0f,
                q = q,
                type = type,
                enabled = true
            )
        }
    }

    fun builtInProfiles(): List<EqProfile> {
        fun graphicProfile(name: String, preampDb: Float, gains: FloatArray): EqProfile {
            val base = defaultBands()
            val bands = base.mapIndexed { idx, b -> b.copy(gainDb = gains[idx]) }
            return EqProfile(name = name, preampDb = preampDb, bands = bands, isBuiltIn = true)
        }

        val harmanBands = listOf(
            EqBandParam(32f, 2.0f, 0.71f, EqFilterType.LOW_SHELF, true),
            EqBandParam(105f, 3.5f, 0.71f, EqFilterType.LOW_SHELF, true),
            EqBandParam(200f, -1.5f, 1.20f, EqFilterType.PEAKING, true),
            EqBandParam(500f, 0.0f, 1.41f, EqFilterType.PEAKING, true),
            EqBandParam(1000f, -0.5f, 1.40f, EqFilterType.PEAKING, true),
            EqBandParam(2000f, 0.8f, 1.60f, EqFilterType.PEAKING, true),
            EqBandParam(3000f, 2.2f, 1.80f, EqFilterType.PEAKING, true),
            EqBandParam(6000f, -1.0f, 2.50f, EqFilterType.PEAKING, true),
            EqBandParam(10000f, 1.5f, 0.71f, EqFilterType.HIGH_SHELF, true),
            EqBandParam(16000f, 0.0f, 0.71f, EqFilterType.HIGH_SHELF, true)
        )

        val studioBands = listOf(
            EqBandParam(35f, 1.5f, 0.71f, EqFilterType.LOW_SHELF, true),
            EqBandParam(80f, 0.5f, 1.20f, EqFilterType.PEAKING, true),
            EqBandParam(160f, -0.5f, 1.40f, EqFilterType.PEAKING, true),
            EqBandParam(400f, 0.0f, 1.41f, EqFilterType.PEAKING, true),
            EqBandParam(1000f, 0.0f, 1.41f, EqFilterType.PEAKING, true),
            EqBandParam(2500f, 0.6f, 1.50f, EqFilterType.PEAKING, true),
            EqBandParam(4500f, 0.8f, 1.80f, EqFilterType.PEAKING, true),
            EqBandParam(7500f, -0.6f, 2.20f, EqFilterType.PEAKING, true),
            EqBandParam(12000f, 1.2f, 0.71f, EqFilterType.HIGH_SHELF, true),
            EqBandParam(16000f, 0.0f, 0.71f, EqFilterType.HIGH_SHELF, false)
        )

        return listOf(
            graphicProfile("Normal", 0.0f, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            graphicProfile("Bass", -2.5f, floatArrayOf(5.0f, 4.0f, 2.5f, 1.0f, 0f, 0f, 0f, 0.5f, 1.0f, 1.5f)),
            graphicProfile("Vokal", -2.0f, floatArrayOf(-1.0f, -0.5f, 0f, 1.5f, 3.0f, 3.5f, 2.5f, 1.0f, 0f, -0.5f)),
            graphicProfile("Treble", -3.0f, floatArrayOf(-1.0f, -1.0f, -0.5f, 0f, 0.5f, 1.5f, 3.0f, 4.5f, 5.5f, 6.0f)),
            EqProfile("Harman", -3.5f, harmanBands, isBuiltIn = true),
            EqProfile("Studio", -1.0f, studioBands, isBuiltIn = true)
        )
    }

    fun formatFreq(freqHz: Float): String {
        val f = freqHz.coerceIn(10f, 24000f)
        return if (f >= 1000f) {
            val khz = f / 1000f
            if (kotlin.math.abs(khz - kotlin.math.round(khz)) < 0.05f) {
                "${kotlin.math.round(khz).toInt()} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
        } else {
            "${kotlin.math.round(f).toInt()} Hz"
        }
    }

    fun formatFreqShort(freqHz: Float): String {
        val f = freqHz.coerceIn(10f, 24000f)
        return if (f >= 1000f) {
            val khz = f / 1000f
            if (kotlin.math.abs(khz - kotlin.math.round(khz)) < 0.05f) {
                "${kotlin.math.round(khz).toInt()}k"
            } else {
                String.format(Locale.US, "%.1fk", khz)
            }
        } else {
            "${kotlin.math.round(f).toInt()}"
        }
    }
}

object AutoEqParser {
    private val preampRegex = Regex(
        """(?i)^\s*Preamp:\s*([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?\s*$"""
    )
    private val filterRegex = Regex(
        """(?i)^\s*Filter\s+\d+:\s*(ON|OFF)\s+(PK|PEQ|LS|LSC|LSQ|HS|HSC|HSQ|LP|LPQ|LPF|HP|HPQ|HPF)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*(?:Hz)?(?:\s+Gain\s+([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?)?(?:\s+Q\s+(\d+(?:[.,]\d+)?))?.*$"""
    )

    fun parse(text: String, profileName: String = "AutoEQ Import"): EqProfile? {
        var preampDb = 0.0f
        var foundPreamp = false
        val parsedFilters = mutableListOf<EqBandParam>()

        val lines = text.lines()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val preampMatch = preampRegex.matchEntire(line)
            if (preampMatch != null) {
                val p = preampMatch.groupValues[1].replace(',', '.').toFloatOrNull()
                if (p != null) {
                    preampDb = p.coerceIn(-15.0f, 12.0f)
                    foundPreamp = true
                }
                continue
            }

            val filterMatch = filterRegex.matchEntire(line)
            if (filterMatch != null && parsedFilters.size < EqDefaults.NUM_BANDS) {
                val enabled = filterMatch.groupValues[1].equals("ON", ignoreCase = true)
                val type = EqFilterType.fromCode(filterMatch.groupValues[2])
                val freq = filterMatch.groupValues[3].replace(',', '.').toFloatOrNull()?.coerceIn(10f, 24000f) ?: continue
                val gain = filterMatch.groupValues[4].replace(',', '.').toFloatOrNull()?.coerceIn(-15f, 15f) ?: 0.0f
                val defaultQ = if (type == EqFilterType.PEAKING) 1.41f else 0.71f
                val q = filterMatch.groupValues[5].replace(',', '.').toFloatOrNull()?.coerceIn(0.1f, 18.0f) ?: defaultQ

                parsedFilters.add(
                    EqBandParam(
                        freqHz = freq,
                        gainDb = gain,
                        q = q,
                        type = type,
                        enabled = enabled
                    )
                )
            }
        }

        if (parsedFilters.isEmpty() && !foundPreamp) {
            return null
        }

        val defaults = EqDefaults.defaultBands()
        val padded = MutableList(EqDefaults.NUM_BANDS) { idx ->
            if (idx < parsedFilters.size) {
                parsedFilters[idx]
            } else {
                defaults[idx].copy(gainDb = 0.0f, enabled = false)
            }
        }

        return EqProfile(
            name = profileName.ifBlank { "AutoEQ Import" },
            preampDb = preampDb,
            bands = padded,
            isBuiltIn = false
        )
    }

    fun exportToText(profile: EqProfile): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Preamp: %.1f dB\n", profile.preampDb))
        profile.bands.take(EqDefaults.NUM_BANDS).forEachIndexed { index, band ->
            val state = if (band.enabled) "ON" else "OFF"
            if (band.type == EqFilterType.LOW_PASS || band.type == EqFilterType.HIGH_PASS) {
                sb.append(
                    String.format(
                        Locale.US,
                        "Filter %d: %s %s Fc %.0f Hz Q %.2f\n",
                        index + 1,
                        state,
                        band.type.shortCode,
                        band.freqHz,
                        band.q
                    )
                )
            } else {
                sb.append(
                    String.format(
                        Locale.US,
                        "Filter %d: %s %s Fc %.0f Hz Gain %.1f dB Q %.2f\n",
                        index + 1,
                        state,
                        band.type.shortCode,
                        band.freqHz,
                        band.gainDb,
                        band.q
                    )
                )
            }
        }
        return sb.toString()
    }
}

