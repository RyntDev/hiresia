package com.hiresia.audioplayer.engine

import android.content.Context
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.ui.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object LiveLyricsManager {

    private val tagPattern = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val metaPattern = Pattern.compile("^\\[[a-zA-Z]+:.*\\]")

    private val lyricsMemoryCache = ConcurrentHashMap<Long, List<LyricLine>>()

    /**
     * Loads lyrics with high-priority local/offline check, and background online LRCLIB query.
     */
    fun loadLyrics(
        context: Context,
        track: AudioTrack,
        onLoaded: (trackId: Long, lyrics: List<LyricLine>) -> Unit
    ): List<LyricLine> {
        // 1. In-memory cache
        val cached = lyricsMemoryCache[track.id]
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }

        // 2. Disk cache (.lrc in cacheDir/lyrics)
        try {
            val cacheDir = File(context.cacheDir, "lyrics").apply { if (!exists()) mkdirs() }
            val cacheFile = File(cacheDir, "${track.id}.lrc")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val parsed = parseLrcContent(cacheFile.readText())
                if (parsed.isNotEmpty()) {
                    lyricsMemoryCache[track.id] = parsed
                    return parsed
                }
            }
        } catch (_: Exception) {}

        // 3. Local .lrc file in the same directory as the audio file
        try {
            if (track.path.isNotBlank()) {
                val trackFile = File(track.path)
                val directLrc = File(track.path.substringBeforeLast(".") + ".lrc")
                val directLrcUpper = File(track.path.substringBeforeLast(".") + ".LRC")
                val lrcToRead = when {
                    directLrc.exists() && directLrc.canRead() -> directLrc
                    directLrcUpper.exists() && directLrcUpper.canRead() -> directLrcUpper
                    else -> null
                }
                if (lrcToRead != null) {
                    val parsed = parseLrcContent(lrcToRead.readText())
                    if (parsed.isNotEmpty()) {
                        lyricsMemoryCache[track.id] = parsed
                        return parsed
                    }
                }

                // Check directory for title.lrc
                val parentDir = trackFile.parentFile
                if (parentDir != null && parentDir.isDirectory) {
                    val cleanT = cleanSongTitle(track.title)
                    val matchingLrc = parentDir.listFiles { f ->
                        f.isFile && f.extension.equals("lrc", ignoreCase = true) &&
                                (f.nameWithoutExtension.equals(cleanT, ignoreCase = true) ||
                                 f.nameWithoutExtension.contains(cleanT, ignoreCase = true))
                    }
                    if (!matchingLrc.isNullOrEmpty()) {
                        val parsed = parseLrcContent(matchingLrc[0].readText())
                        if (parsed.isNotEmpty()) {
                            lyricsMemoryCache[track.id] = parsed
                            return parsed
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. Embedded lyrics in audio file (ID3v2 USLT / SYLT / Vorbis Comment)
        try {
            val embedded = extractEmbeddedLyrics(track.path)
            if (!embedded.isNullOrBlank()) {
                val parsed = if (embedded.contains("[")) {
                    parseLrcContent(embedded)
                } else {
                    parsePlainLyrics(embedded, track.durationMs)
                }
                if (parsed.isNotEmpty()) {
                    lyricsMemoryCache[track.id] = parsed
                    return parsed
                }
            }
        } catch (_: Exception) {}

        // 5. Curated demo tracks (for offline demo testing)
        val titleLower = track.title.lowercase().trim()
        val curated = when {
            titleLower.contains("midnight drive") -> getMidnightDriveLyrics()
            titleLower.contains("lantai dua") -> getLantaiDuaLyrics()
            titleLower.contains("glass rivers") -> getGlassRiversLyrics()
            titleLower.contains("pelan-pelan") || titleLower.contains("pelan pelan") -> getPelanPelanLyrics()
            titleLower.contains("neon tides") -> getNeonTidesLyrics()
            titleLower.contains("hujan di makassar") -> getHujanDiMakassarLyrics()
            titleLower.contains("static bloom") -> getStaticBloomLyrics()
            titleLower.contains("pulang pagi") -> getPulangPagiLyrics()
            else -> null
        }
        if (curated != null && curated.isNotEmpty()) {
            lyricsMemoryCache[track.id] = curated
            return curated
        }

        // 6. Online fetching via LRCLIB (Real synchronized vocal pronunciation)
        val initialPlaceholder = listOf(LyricLine(0L, "♫ Memuat lirik lagu... ♫"))
        CoroutineScope(Dispatchers.IO).launch {
            val cleanTitle = cleanSongTitle(track.title)
            val cleanArtist = cleanArtistName(track.artist)
            val durationSec = (track.durationMs / 1000).toInt()

            val fetched = fetchFromLrclib(cleanTitle, cleanArtist, durationSec)
            val finalLyrics = when {
                fetched != null && fetched.syncedLyrics.isNotBlank() -> {
                    // Cache to disk
                    try {
                        val cacheDir = File(context.cacheDir, "lyrics").apply { if (!exists()) mkdirs() }
                        File(cacheDir, "${track.id}.lrc").writeText(fetched.syncedLyrics)
                    } catch (_: Exception) {}
                    parseLrcContent(fetched.syncedLyrics)
                }
                fetched != null && fetched.isInstrumental -> {
                    listOf(LyricLine(0L, "♫ Lagu Instrumental (Tanpa Vokal) ♫"))
                }
                fetched != null && fetched.plainLyrics.isNotBlank() -> {
                    parsePlainLyrics(fetched.plainLyrics, track.durationMs)
                }
                else -> {
                    listOf(LyricLine(0L, "♫ Lirik belum tersedia untuk lagu ini ♫"))
                }
            }

            lyricsMemoryCache[track.id] = finalLyrics
            withContext(Dispatchers.Main) {
                onLoaded(track.id, finalLyrics)
            }
        }

        return initialPlaceholder
    }

    fun getLyricsForTrack(track: AudioTrack?): List<LyricLine> {
        if (track == null) return emptyList()
        val cached = lyricsMemoryCache[track.id]
        if (cached != null) return cached

        // Check local .lrc file if present
        try {
            if (track.path.isNotBlank()) {
                val directLrc = File(track.path.substringBeforeLast(".") + ".lrc")
                if (directLrc.exists() && directLrc.canRead()) {
                    val parsed = parseLrcContent(directLrc.readText())
                    if (parsed.isNotEmpty()) {
                        lyricsMemoryCache[track.id] = parsed
                        return parsed
                    }
                }
            }
        } catch (_: Exception) {}

        val titleLower = track.title.lowercase().trim()
        val curated = when {
            titleLower.contains("midnight drive") -> getMidnightDriveLyrics()
            titleLower.contains("lantai dua") -> getLantaiDuaLyrics()
            titleLower.contains("glass rivers") -> getGlassRiversLyrics()
            titleLower.contains("pelan-pelan") || titleLower.contains("pelan pelan") -> getPelanPelanLyrics()
            titleLower.contains("neon tides") -> getNeonTidesLyrics()
            titleLower.contains("hujan di makassar") -> getHujanDiMakassarLyrics()
            titleLower.contains("static bloom") -> getStaticBloomLyrics()
            titleLower.contains("pulang pagi") -> getPulangPagiLyrics()
            else -> null
        }
        return curated ?: listOf(LyricLine(0L, "♫ Memuat lirik lagu... ♫"))
    }

    /* -------------------------------------------------------------
     * LRC Synchronized Parsing
     * ------------------------------------------------------------- */
    fun parseLrcContent(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        content.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || metaPattern.matcher(trimmed).matches()) {
                return@forEach
            }

            val matcher = tagPattern.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastEnd = 0
            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3) ?: "0"
                val ms = when (msStr.length) {
                    1 -> msStr.toLong() * 100
                    2 -> msStr.toLong() * 10
                    3 -> msStr.toLong()
                    else -> msStr.take(3).toLong()
                }
                timestamps.add(min * 60000L + sec * 1000L + ms)
                lastEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val text = trimmed.substring(lastEnd).trim()
                // Musical pause marker if blank line
                val displayText = if (text.isBlank()) "♫" else text
                for (ts in timestamps) {
                    lines.add(LyricLine(ts, displayText))
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun parsePlainLyrics(content: String, durationMs: Long): List<LyricLine> {
        val validLines = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (validLines.isEmpty()) return emptyList()
        val totalMs = if (durationMs > 10000L) durationMs else 180000L
        val step = (totalMs - 4000L) / (validLines.size + 1)

        val result = mutableListOf<LyricLine>()
        validLines.forEachIndexed { i, text ->
            val time = 2000L + (i + 1) * step
            result.add(LyricLine(time.coerceAtMost(totalMs - 500L), text))
        }
        return result
    }

    /* -------------------------------------------------------------
     * Online Fetching via LRCLIB
     * ------------------------------------------------------------- */
    private data class LrclibResult(
        val syncedLyrics: String,
        val plainLyrics: String,
        val isInstrumental: Boolean
    )

    private fun fetchFromLrclib(title: String, artist: String, durationSec: Int): LrclibResult? {
        if (title.isBlank()) return null

        // 1. Try exact match query
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")

        val exactUrl = if (artist.isNotBlank()) {
            val durParam = if (durationSec > 0) "&duration=$durationSec" else ""
            "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist$durParam"
        } else {
            val durParam = if (durationSec > 0) "&duration=$durationSec" else ""
            "https://lrclib.net/api/get?track_name=$encodedTitle$durParam"
        }

        val jsonStr = executeHttpGet(exactUrl)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val json = JSONObject(jsonStr)
                val synced = json.optString("syncedLyrics", "").trim()
                val plain = json.optString("plainLyrics", "").trim()
                val instrumental = json.optBoolean("instrumental", false)
                if (synced.isNotBlank() || plain.isNotBlank() || instrumental) {
                    return LrclibResult(synced, plain, instrumental)
                }
            } catch (_: Exception) {}
        }

        // 2. Try search query if exact match failed
        try {
            val searchQuery = "$title $artist".trim()
            val searchUrl = "https://lrclib.net/api/search?q=" + URLEncoder.encode(searchQuery, "UTF-8")
            val searchJsonStr = executeHttpGet(searchUrl)
            if (!searchJsonStr.isNullOrBlank()) {
                val array = JSONArray(searchJsonStr)
                var bestCandidate: LrclibResult? = null
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val synced = item.optString("syncedLyrics", "").trim()
                    val plain = item.optString("plainLyrics", "").trim()
                    val instrumental = item.optBoolean("instrumental", false)
                    val itemDuration = item.optDouble("duration", 0.0).toInt()

                    if (synced.isNotBlank()) {
                        // If duration matches within 8s, pick immediately
                        if (durationSec > 0 && Math.abs(itemDuration - durationSec) <= 8) {
                            return LrclibResult(synced, plain, instrumental)
                        }
                        if (bestCandidate == null) {
                            bestCandidate = LrclibResult(synced, plain, instrumental)
                        }
                    } else if (plain.isNotBlank() && bestCandidate == null) {
                        bestCandidate = LrclibResult("", plain, instrumental)
                    }
                }
                if (bestCandidate != null) return bestCandidate
            }
        } catch (_: Exception) {}

        return null
    }

    private fun executeHttpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("User-Agent", "HiresiaAudioplayer/1.0 (https://github.com/hiresia/audioplayer)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /* -------------------------------------------------------------
     * Embedded Metadata Extractor (ID3v2 USLT/SYLT & Vorbis Comment)
     * ------------------------------------------------------------- */
    private fun extractEmbeddedLyrics(filePath: String): String? {
        if (filePath.isBlank()) return null
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead() || file.length() < 128) return null

            file.inputStream().use { input ->
                val header = ByteArray(10)
                if (input.read(header) < 10) return null

                // ID3v2 Tag Check
                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                                  ((header[7].toInt() and 0x7F) shl 14) or
                                  ((header[8].toInt() and 0x7F) shl 7) or
                                  (header[9].toInt() and 0x7F)
                    val bufferSize = tagSize.coerceAtMost(384 * 1024)
                    val buffer = ByteArray(bufferSize)
                    val read = input.read(buffer)
                    if (read > 0) {
                        val content = String(buffer, 0, read, Charsets.ISO_8859_1)

                        // Check USLT (Unsynchronized Lyric)
                        val usltIdx = content.indexOf("USLT")
                        if (usltIdx in 0..(read - 14)) {
                            var dataStart = usltIdx + 10
                            if (dataStart + 4 < read) {
                                val enc = buffer[dataStart].toInt()
                                dataStart += 4
                                while (dataStart < read && buffer[dataStart] != 0.toByte()) {
                                    dataStart++
                                }
                                dataStart++
                                if (dataStart < read) {
                                    val charset = when (enc) {
                                        1 -> Charsets.UTF_16
                                        2 -> Charsets.UTF_16BE
                                        3 -> Charsets.UTF_8
                                        else -> Charsets.ISO_8859_1
                                    }
                                    val lyricsText = String(buffer, dataStart, (read - dataStart).coerceAtMost(16384), charset).trim()
                                    if (lyricsText.isNotBlank()) return lyricsText
                                }
                            }
                        }

                        // Check Vorbis Comment "LYRICS="
                        val lyrIdx = content.indexOf("LYRICS=")
                        if (lyrIdx >= 0) {
                            val sub = content.substring(lyrIdx + 7)
                            val end = sub.indexOf('\u0000').let { if (it >= 0) it else sub.indexOf('\n') }
                            if (end > 0) return sub.substring(0, end).trim()
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /* -------------------------------------------------------------
     * Cleaning Helpers
     * ------------------------------------------------------------- */
    fun cleanSongTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        // Strip audio extensions
        title = title.replace(Regex("(?i)\\.(mp3|flac|wav|m4a|ogg|opus|aac|dsf|dff)$"), "")
        // Strip track numbers: "01. ", "01 - ", "1. ", "01 "
        title = title.replace(Regex("^\\d{1,3}[.\\s_-]+"), "")
        // If "Artist - Title", extract title
        if (title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                title = parts[1].trim()
            }
        }
        // Strip video/audio metadata tags
        title = title.replace(Regex("(?i)\\s*[\\(\\[](official\\s*(audio|video|music\\s*video|lyric\\s*video)?|lyric\\s*video|audio|video|remaster(ed)?.*?|live.*?|visualizer)[\\)\\]]"), "")
        title = title.replace(Regex("(?i)\\s*(feat\\.|ft\\.).*"), "")
        return title.trim()
    }

    fun cleanArtistName(rawArtist: String): String {
        var artist = rawArtist.trim()
        if (artist.equals("<unknown>", ignoreCase = true) ||
            artist.equals("unknown", ignoreCase = true) ||
            artist.equals("unknown artist", ignoreCase = true)) {
            return ""
        }
        artist = artist.replace(Regex("(?i)\\s*(feat\\.|ft\\.).*"), "")
        return artist.trim()
    }

    /* -------------------------------------------------------------
     * Curated Demo Tracks
     * ------------------------------------------------------------- */
    private fun getMidnightDriveLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(2000L, "♫ (Alunan pembuka melodi malam) ♫"),
            LyricLine(7000L, "Lampu kota berpendar di kaca mobil"),
            LyricLine(13500L, "Jalanan sunyi membawa kita pergi"),
            LyricLine(20000L, "Deru mesin berpadu bisikan angin malam"),
            LyricLine(27000L, "Menuju ufuk tanpa ada keraguan"),
            LyricLine(35000L, "Melaju pelan menembus gelapnya malam"),
            LyricLine(42500L, "Irama bass bergetar di dalam dada"),
            LyricLine(50000L, "Tak ada kata yang perlu terucap lagi"),
            LyricLine(57000L, "Hanya melodi dan midnight drive ini"),
            LyricLine(66000L, "♫ (Solo synthesizer atmosferik) ♫"),
            LyricLine(75000L, "Kita berdua di bawah langit berbintang"),
            LyricLine(83500L, "Melintasi batas waktu yang fana"),
            LyricLine(92000L, "Hingga mentari pagi menyapa perlahan"),
            LyricLine(100500L, "Cerita malam tersimpan selamanya"),
            LyricLine(110000L, "Hanya melodi dan midnight drive ini"),
            LyricLine(120000L, "♫ (Outro melodi syahdu) ♫")
        )
    }

    private fun getLantaiDuaLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(2500L, "♫ (Petikan gitar akustik hangat) ♫"),
            LyricLine(8000L, "Dari sudut jendela lantai dua"),
            LyricLine(15000L, "Aroma kopi menguap ke udara"),
            LyricLine(22000L, "Langkah kaki terburu di trotoar"),
            LyricLine(30000L, "Senja berganti lampu kota bersinar"),
            LyricLine(38000L, "Di lantai dua waktu melambat"),
            LyricLine(46000L, "Menyimpan rahasia yang tersirat"),
            LyricLine(54000L, "Tak perlu tergesa menatap esok"),
            LyricLine(62000L, "Nikmati tiap detik yang berdetak"),
            LyricLine(70000L, "♫ (Interlude piano dan cello) ♫"),
            LyricLine(78000L, "Dari lantai dua kita mengamati dunia"),
            LyricLine(86000L, "Tenang dalam pelukan nada yang damai")
        )
    }

    private fun getGlassRiversLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(2000L, "♫ (Resonansi harpa dan ambient synth) ♫"),
            LyricLine(9000L, "Arus jernih mengalir tanpa suara"),
            LyricLine(17000L, "Sungai kaca memantulkan cahaya"),
            LyricLine(25000L, "Bening menembus batas angan-angan"),
            LyricLine(33000L, "Hanyut perlahan dalam ketenangan"),
            LyricLine(41000L, "Glass rivers bawa rasa cemas pergi"),
            LyricLine(49000L, "Menyisakan nada murni abadi"),
            LyricLine(58000L, "♫ (Melodi mengalir lembut) ♫")
        )
    }

    private fun getPelanPelanLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(3000L, "♫ (Intro ritme drum akustik) ♫"),
            LyricLine(10000L, "Jalani hari pelan-pelan saja"),
            LyricLine(18000L, "Tak semua tanya butuh jawab segera"),
            LyricLine(26000L, "Tarik nafas hembuskan perlahan"),
            LyricLine(34000L, "Dengarkan lagu ini teman setia"),
            LyricLine(42000L, "Pelan-pelan melangkah pasti"),
            LyricLine(50000L, "Semua indah pada waktunya nanti")
        )
    }

    private fun getNeonTidesLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(2000L, "♫ (Gelombang synthwave energik) ♫"),
            LyricLine(8500L, "Warna neon menyala di pesisir"),
            LyricLine(16000L, "Pasang surut ombak mengikuti irama"),
            LyricLine(24000L, "Lampu ungu dan biru memikat mata"),
            LyricLine(32000L, "Malam ini kita takkan terlupa"),
            LyricLine(40000L, "Neon tides basahi langkah kita"),
            LyricLine(48000L, "Berdansa dalam getaran semesta")
        )
    }

    private fun getHujanDiMakassarLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(2500L, "♫ (Suara rintik hujan dan gesekan biola) ♫"),
            LyricLine(9000L, "Gerimis turun di Pantai Losari"),
            LyricLine(17000L, "Membasahi jalanan yang sepi"),
            LyricLine(25000L, "Kala senja meredup perlahan"),
            LyricLine(33000L, "Rindu berbisik di balik rintikan"),
            LyricLine(41000L, "Hujan di Makassar bawa cerita"),
            LyricLine(49000L, "Hangat terasa dalam memori kita")
        )
    }

    private fun getStaticBloomLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(3000L, "♫ (Frekuensi elektrik mengalun) ♫"),
            LyricLine(10000L, "Di antara statik bunga bermekaran"),
            LyricLine(18000L, "Nada elektrik mengisi keheningan"),
            LyricLine(26000L, "Frekuensi tinggi bersatu dalam harmoni"),
            LyricLine(34000L, "Mekar sempurna dalam getaran murni"),
            LyricLine(42000L, "Static bloom menari dalam gelombang")
        )
    }

    private fun getPulangPagiLyrics(): List<LyricLine> {
        return listOf(
            LyricLine(3000L, "♫ (Petikan melodi fajar) ♫"),
            LyricLine(10000L, "Embun menyambut kepulangan kita"),
            LyricLine(18000L, "Setelah malam penuh nada dan tawa"),
            LyricLine(26000L, "Menatap fajar di ujung cakrawala"),
            LyricLine(34000L, "Pulang pagi dengan hati yang lega"),
            LyricLine(42000L, "Tidurlah nyenyak hingga mentari tiba")
        )
    }
}
