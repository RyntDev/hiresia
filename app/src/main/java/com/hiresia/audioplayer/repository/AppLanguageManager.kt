package com.hiresia.audioplayer.repository

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * Bilingual UI Localization Engine (Bahasa Indonesia <-> English) for Hiresia.
 * Translates both XML-inflated view hierarchies and dynamic runtime strings instantaneously.
 */
object AppLanguageManager {

    val isEnglish: Boolean
        get() = AudioSettingsRepository.isEnglish

    fun tr(idText: String, enText: String): String {
        return if (isEnglish) enText else idText
    }

    fun formatSongsCount(count: Int): String {
        return if (isEnglish) {
            if (count == 1) "1 song" else "$count songs"
        } else {
            "$count lagu"
        }
    }

    fun formatSongsMinutes(songs: Int, mins: Long): String {
        return if (isEnglish) {
            val s = if (songs == 1) "1 song" else "$songs songs"
            val m = if (mins == 1L) "1 min" else "$mins mins"
            "$s · $m"
        } else {
            "$songs lagu · $mins menit"
        }
    }

    fun formatSongsHours(songs: Int, hours: Long): String {
        return if (isEnglish) {
            val s = if (songs == 1) "1 song" else "$songs songs"
            val h = if (hours == 1L) "1 hr" else "$hours hrs"
            "$s · $h"
        } else {
            "$songs lagu · $hours jam"
        }
    }

    fun formatAlbumsCount(albums: Int): String {
        return if (isEnglish) {
            if (albums == 1) "1 album" else "$albums albums"
        } else {
            "$albums album"
        }
    }

    fun formatItemsCount(items: Int): String {
        return if (isEnglish) {
            if (items == 1) "1 item" else "$items items"
        } else {
            "$items item"
        }
    }

    private val ID_TO_EN: Map<String, String> = mapOf(
        // Main Navigation & Home (Beranda)
        "Beranda" to "Home",
        "Antrean" to "Queue",
        "Cari" to "Search",
        "Pustaka" to "Library",
        "Selamat pagi" to "Good morning",
        "Selamat siang" to "Good afternoon",
        "Selamat sore" to "Good evening",
        "Selamat malam" to "Good evening",
        "Putar" to "Play",
        "Acak" to "Shuffle",
        "Lagu Disukai" to "Liked Songs",
        "Semua favoritmu di satu tempat" to "All your favorites in one place",
        "Terakhir diputar" to "Recently Played",
        "Terakhir" to "Recent",
        "Lihat semua" to "See all",
        "Album" to "Albums",
        "Artis" to "Artists",
        "Playlist" to "Playlists",
        "+ Baru" to "+ New",
        "Belum ada playlist" to "No playlists yet",
        "Buat playlist pertamamu dari lagu di pustaka." to "Create your first playlist from tracks in your library.",
        "Belum Ada Lagu" to "No Songs Yet",
        "Pilih Lagu" to "Select a Song",
        "Pindai folder musik di Pustaka untuk memulai" to "Scan a music folder in Library to get started",
        "Tidak diketahui" to "Unknown",
        "Tambah lagu" to "Add songs",
        "Buat playlist" to "Create playlist",
        "Playlist Baru" to "New Playlist",
        "Playlist baru" to "New playlist",
        "Nama playlist" to "Playlist name",
        "Contoh: Lagu malam" to "Example: Late night songs",
        "Belum ada lagu dipilih" to "No songs selected",
        "Campuran" to "Mix",
        "Campuran Acak" to "Shuffle Mix",
        "Pencarian" to "Search",
        "Daftar Putar" to "Playlist",

        // Library & Search & Queue
        "Pindai folder" to "Scan folder",
        "Lagu" to "Songs",
        "Folder" to "Folders",
        "Kembali" to "Back",
        "Cari judul atau artis" to "Search title or artist",
        "Cari lagu, artis, atau album..." to "Search songs, artists, or albums...",
        "Hapus semua" to "Clear all",
        "Antrean kosong" to "Queue is empty",
        "Tambahkan lagu atau grup playlist untuk diputar berikutnya." to "Add songs or playlist groups to play next.",
        "+ Tambah ke antrean" to "+ Add to queue",
        "+ Tambah antrean" to "+ Add to queue",
        "Tambah ke antrean" to "Add to queue",
        "Pilih satu atau beberapa lagu dari pustaka" to "Select one or multiple songs from your library",
        "Grup playlist" to "Playlist group",
        "Tambahkan seluruh isi playlist sebagai satu grup" to "Add an entire playlist as a single queue group",
        "SEDANG DIPUTAR" to "NOW PLAYING",
        "BERIKUTNYA" to "UP NEXT",
        "Disukai" to "Liked Songs",
        "Terakhir Diputar" to "Recently Played",
        "Hapus riwayat" to "Clear history",
        "Belum ada lagu disukai" to "No liked songs yet",
        "Ketuk ikon hati pada lagu untuk menyimpannya di sini." to "Tap the heart icon on any song to save it here.",
        "Sentuh ikon hati pada pemutar lagu untuk menambahkan ke daftar ini." to "Tap the heart icon on the player to add songs to this list.",
        "Belum ada riwayat putar" to "No playback history yet",
        "Belum ada riwayat" to "No playback history yet",
        "Lagu yang kamu putar akan muncul di sini." to "Songs you play will appear here.",
        "Lagu yang kamu putar akan tercatat di sini." to "Songs you play will be recorded here.",

        // Mini Player & Now Playing
        "Pilih Lagu untuk Diputar" to "Select a Song to Play",
        "Sedang diputar dari" to "Playing from",
        "LIRIK" to "LYRICS",
        "✕ Tutup" to "✕ Close",
        "Ulangi" to "Repeat",

        // Volume Dialog
        "Kontrol Volume" to "Volume Control",
        "Trim L/R" to "L/R Trim",
        "Trim L/R Hardware DAC" to "Hardware DAC L/R Trim",
        "Tengah (0 dB)" to "Center (0 dB)",
        "-∞ dB (Bisu)" to "-∞ dB (Mute)",
        "0.0 dB (Maks.)" to "0.0 dB (Max)",
        "100% (Maks.)" to "100% (Max)",
        "0.0 dB (Terkunci)" to "0.0 dB (Locked)",
        "BISU" to "MUTE",
        "- Langkah" to "- Step",
        "+ Langkah" to "+ Step",
        "VOLUME PERANGKAT KERAS DAC" to "HARDWARE DAC VOLUME",
        "VOLUME PERANGKAT LUNAK" to "SOFTWARE VOLUME CONTROL",
        "VOLUME SISTEM INTERNAL (BIT-PERFECT)" to "INTERNAL SYSTEM VOLUME (BIT-PERFECT)",
        "TERKUNCI BIT-PERFECT (0 dB)" to "BIT-PERFECT LOCKED (0 dB)",

        // Settings Screen (Pengaturan)
        "Pengaturan" to "Settings",
        "PUSTAKA MUSIK" to "MUSIC LIBRARY",
        "Folder Musik Terdaftar" to "Registered Music Folders",
        "Pindai Ulang Pustaka" to "Rescan Library",
        "Sembunyikan Audio Berdurasi Pendek" to "Hide Short Audio Files",
        "Abaikan rekaman singkat seperti nada dering atau suara notifikasi" to "Ignore short clips such as ringtones or notification sounds",
        "PEMUTARAN & EFEK AUDIO" to "PLAYBACK & AUDIO EFFECTS",
        "Putar Tanpa Jeda (Gapless)" to "Gapless Playback",
        "Sambung pergantian antarlagu secara mulus tanpa jeda hening" to "Transition seamlessly between tracks without silence gaps",
        "Ekualiser & Pemrosesan DSP" to "Equalizer & DSP Processing",
        "Pengatur Waktu Tidur" to "Sleep Timer",
        "Mati" to "Off",
        "DRIVER & KELUARAN AUDIO HI-RES" to "HI-RES AUDIO DRIVER & OUTPUT",
        "Mode Bit-Perfect (Jalur Murni)" to "Bit-Perfect Mode (Direct Path)",
        "Kirim data audio 100% murni tanpa perubahan (lewati DSP, EQ, dan volume perangkat lunak)" to "Transmit 100% bit-exact audio data (bypass DSP, EQ, and software volume)",
        "Driver Hi-Res Langsung (Lewati Sistem)" to "Direct Hi-Res Driver (OS Bypass)",
        "Lewati pencampur AudioFlinger Android 48 kHz melalui jalur AAudio HAL eksklusif" to "Bypass Android 48 kHz AudioFlinger mixer via exclusive AAudio HAL path",
        "Gunakan DoP / DSD Murni Bila Didukung" to "Use DoP / Native DSD When Supported",
        "Gunakan keluaran DoP atau DSD murni jika audio internal mendukung. Jika dimatikan, DSD diubah ke PCM agar ekualiser dan DSP dapat digunakan." to "Use DoP or Native DSD output when supported. When off, DSD converts to PCM so EQ and DSP can be used.",
        "Mode Eksklusif USB DAC" to "USB DAC Exclusive Mode",
        "Kirim aliran audio langsung ke perangkat keras USB DAC dan bisukan suara notifikasi ponsel" to "Stream audio directly to USB DAC hardware and mute phone notification sounds",
        "Mode Pemutaran DSD (USB DAC)" to "DSD Playback Mode (USB DAC)",
        "Pilih metode pengiriman DSD sesuai dukungan perangkat keras USB DAC (Konversi ke PCM, DoP, atau DSD Murni)" to "Select DSD transport method supported by your USB DAC (DSD to PCM, DoP, or Native DSD)",
        "Blokir USB HID" to "Block USB HID",
        "Putus jalur sinyal kontrol USB HID agar pemicu palsu tidak membuka asisten suara secara tiba-tiba" to "Block USB HID control signals to prevent phantom triggers from launching voice assistants",
        "JALUR PEMROSESAN AUDIO AKTIF" to "ACTIVE AUDIO PROCESSING PIPELINE",
        "SISTEM VOLUME AUDIOPHILE" to "AUDIOPHILE VOLUME SYSTEM",
        "TAMPILAN ANTARMUKA" to "INTERFACE APPEARANCE",
        "Palet Warna" to "Color Palette",
        "Bahasa Aplikasi" to "App Language",
        "DUKUNGAN & MASUKAN" to "SUPPORT & FEEDBACK",
        "Laporkan Bug & Saran" to "Report Bug & Feedback",
        "Kirim Masukan" to "Send Feedback",
        "Dukung Pengembang 😄" to "Support Developer 😄",
        "TENTANG APLIKASI" to "ABOUT APPLICATION",
        "Hiresia Audio Player v2.0\nMesin Audio Native C++ Hi-Res" to "Hiresia Audio Player v2.0\nNative C++ Hi-Res Audio Engine",

        // Volume Settings Screen (Sistem Volume Audiophile)
        "Sistem Volume Audiophile" to "Audiophile Volume System",
        "Panel Volume" to "Volume Panel",
        "STATUS MESIN VOLUME AKTIF" to "ACTIVE VOLUME ENGINE STATUS",
        "Volume Perangkat Keras USB DAC" to "USB DAC Hardware Volume",
        "Volume Perangkat Lunak (DSP 64-bit)" to "Software Volume (64-bit DSP)",
        "Volume Sistem Internal (Bit-Perfect 0.0 dB)" to "Internal System Volume (Bit-Perfect 0.0 dB)",
        "Terkunci Bit-Perfect Murni (0.0 dB)" to "Bit-Perfect Locked (0.0 dB)",
        "1. KONTROL & TOMBOL VOLUME" to "1. VOLUME CONTROLS & KEYS",
        "Mode Kontrol Volume" to "Volume Control Mode",
        "Langkah Perubahan Volume" to "Volume Step Size",
        "Kendali Tombol Volume Fisik" to "Physical Volume Key Control",
        "Gunakan tombol volume fisik ponsel untuk mengatur volume USB DAC atau DSP secara langsung" to "Use phone physical volume buttons to directly control USB DAC or DSP volume",
        "Tutup Otomatis Panel Volume" to "Auto-Dismiss Volume Panel",
        "Sembunyikan jendela kontrol volume secara otomatis setelah 2,5 detik tidak disentuh" to "Automatically hide the volume control popup after 2.5 seconds of inactivity",
        "2. VOLUME PERANGKAT KERAS (USB DAC)" to "2. HARDWARE VOLUME (USB DAC)",
        "Kurva Volume Perangkat Keras DAC" to "DAC Hardware Volume Curve",
        "Batas Aman Volume DAC (Maks. 80%)" to "DAC Safe Volume Limit (Max 80%)",
        "Batasi puncak volume DAC pada 80% untuk melindungi pendengaran dan IEM sensitif. Matikan untuk membuka batas penuh 100% (0.0 dB)." to "Cap maximum DAC volume at 80% to protect hearing and sensitive IEMs. Turn off to unlock full 100% (0.0 dB).",
        "Sinkronisasi Paksa Volume DAC" to "Force DAC Volume Sync",
        "Kirim penguatan kanal Kiri, Kanan, dan Utama secara bersamaan apabila suara DAC terasa berat sebelah" to "Send Left, Right, and Master channel gains simultaneously if DAC volume feels unbalanced",
        "Kendali Volume Saat Layar Mati (Android 12+)" to "Screen-Off Volume Control (Android 12+)",
        "Jaga agar tombol volume fisik ponsel tetap merespons pengaturan USB DAC ketika layar mati" to "Keep physical volume buttons responsive for USB DAC control while the screen is off",
        "3. PEMROSESAN & NORMALISASI SUARA" to "3. AUDIO PROCESSING & NORMALIZATION",
        "Kurva Volume Perangkat Lunak" to "Software Volume Curve",
        "Penghalus Kuantisasi (TPDF Dithering)" to "Quantization Smoothing (TPDF Dithering)",
        "Pertahankan detail suara mikro saat volume perangkat lunak atau ekualiser aktif (otomatis dilewati saat Bit-Perfect)" to "Preserve micro-details when software volume or EQ is active (automatically bypassed in Bit-Perfect mode)",
        "Normalisasi Kenyaringan (ReplayGain)" to "Loudness Normalization (ReplayGain)",

        // Equalizer Screen (EqualizerActivity & activity_equalizer.xml)
        "Equalizer 64-bit PEQ" to "64-bit PEQ Equalizer",
        "Equalizer & DSP" to "Equalizer & DSP",
        "Mode Bit-Perfect Aktif" to "Bit-Perfect Mode Active",
        "Nyalakan switch Equalizer untuk menerapkan 64-bit Parametric EQ dan efek DSP." to "Turn on the Equalizer switch to apply 64-bit Parametric EQ and DSP effects.",
        "Mode Bit-Perfect Aktif — Aktifkan sakelar EQ di kanan atas jika ingin memproses suara dengan 64-bit Parametric EQ & DSP." to "Bit-Perfect Mode Active — Enable the EQ switch at the top right to process audio with 64-bit Parametric EQ & DSP.",
        "Vokal" to "Vocal",
        "Kustom" to "Custom",
        "Preamp" to "Preamp",
        "Preamp Gain" to "Preamp Gain",
        "Impor AutoEQ" to "Import AutoEQ",
        "Import AutoEQ" to "Import AutoEQ",
        "Simpan Preset" to "Save Preset",
        "Preset Saya" to "My Presets",
        "Preset: Normal. Ketuk label frekuensi/Q di bawah pita untuk atur Filter, Fc & Q." to "Profile: Normal. Tap the frequency/Q label below any band to edit Filter, Fc & Q.",
        "Fitur DSP Lanjutan (AutoGain, Crossfeed, Balance)" to "Advanced DSP Features (AutoGain, Crossfeed, Balance)",
        "AutoGain (Anti-Clipping)" to "AutoGain (Anti-Clipping)",
        "AutoGain Protection" to "AutoGain Protection",
        "Mencegah distorsi digital berdasarkan puncak kurva biquad gabungan + limiter transparan." to "Prevents digital distortion based on combined biquad curve peaks + transparent limiter.",
        "Cegah kliping digital saat band EQ dinaikkan" to "Prevent digital clipping when EQ bands are boosted",
        "Binaural Crossfeed (Bauer Filter)" to "Binaural Crossfeed (Bauer Filter)",
        "Crossfeed (Bauer Stereophonic)" to "Crossfeed (Bauer Stereophonic)",
        "Simulasi panggung speaker natural pada headphone" to "Simulate natural speaker soundstage on headphones",
        "Intensitas:" to "Intensity:",
        "Intensitas: " to "Intensity: ",
        "Stereo Balance" to "Stereo Balance",
        "Keseimbangan Kanal (L / R Balance)" to "Channel Balance (L / R Balance)",
        "Tengah (Center)" to "Center (0%)",
        "Tengah (Center 0%)" to "Center (0%)",
        "Atur ulang" to "Reset",
        "Reset ke Default (Flat)" to "Reset to Default (Flat)",

        // Library Folders & Scan Dialogs
        "Pustaka Folder Musik" to "Music Folder Library",
        "Folder yang dipindai ke dalam pustaka Hiresia (C++ Engine)" to "Folders scanned into Hiresia library (C++ Engine)",
        "Siap memindai" to "Ready to scan",
        "Hentikan" to "Stop",
        "Pindai Ulang" to "Rescan",
        "Tutup" to "Close",
        "Memindai Folder Musik..." to "Scanning Music Folder...",
        "Menyiapkan pemindaian..." to "Preparing scan...",
        "Batalkan" to "Cancel",
        "DAC USB terputus — dialihkan ke Audio Internal" to "USB DAC disconnected — switched to Internal Audio",
        "Koneksi USB DAC dipulihkan otomatis" to "USB DAC connection recovered automatically"
    )

    private val EN_TO_ID: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for ((k, v) in ID_TO_EN) {
            if (!map.containsKey(v)) {
                map[v] = k
            }
        }
        map
    }

    fun translateString(raw: String): String {
        if (raw.isBlank()) return raw
        val trimmed = raw.trim()
        return if (isEnglish) {
            ID_TO_EN[trimmed] ?: translateDynamicPatternToEn(trimmed)
        } else {
            EN_TO_ID[trimmed] ?: translateDynamicPatternToId(trimmed)
        }
    }

    private fun translateDynamicPatternToEn(text: String): String {
        if (text.startsWith("Profil: ") && text.contains(". Ketuk label frekuensi/Q")) {
            val label = text.substringAfter("Profil: ").substringBefore(". Ketuk label frekuensi/Q")
            val enLabel = translateString(label)
            return "Profile: $enLabel. Tap the frequency/Q label below any band to edit Filter, Fc & Q."
        }
        if (text.startsWith("Kiri ") && text.endsWith("% (L)")) {
            return text.replaceFirst("Kiri ", "Left ")
        }
        if (text.startsWith("Kanan ") && text.endsWith("% (R)")) {
            return text.replaceFirst("Kanan ", "Right ")
        }
        if (text.endsWith(" folder")) {
            val n = text.removeSuffix(" folder").trim()
            return if (n == "1") "1 folder" else "$n folders"
        }
        if (text.contains(" lagu") || text.contains(" menit") || text.contains(" jam") || text.contains(" album")) {
            return text
                .replace("1 album", "1 album")
                .replace(Regex("""(\d+)\s+album""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 album" else "$n albums"
                }
                .replace(Regex("""(\d+)\s+lagu\s+dipilih""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 song selected" else "$n songs selected"
                }
                .replace(Regex("""(\d+)\s+lagu\s+ditemukan""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 song found" else "$n songs found"
                }
                .replace(Regex("""(\d+)\s+lagu""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 song" else "$n songs"
                }
                .replace(Regex("""(\d+)\s+menit""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 min" else "$n mins"
                }
                .replace(Regex("""(\d+)\s+jam""")) { m ->
                    val n = m.groupValues[1]
                    if (n == "1") "1 hr" else "$n hrs"
                }
        }
        if (text.startsWith("EQ · ")) {
            val sub = text.removePrefix("EQ · ").trim()
            return "EQ · ${translateString(sub)}"
        }
        return text
    }

    private fun translateDynamicPatternToId(text: String): String {
        if (text.startsWith("Profile: ") && text.contains(". Tap the frequency/Q label")) {
            val label = text.substringAfter("Profile: ").substringBefore(". Tap the frequency/Q label")
            val idLabel = translateString(label)
            return "Profil: $idLabel. Ketuk label frekuensi/Q di bawah pita untuk atur Filter, Fc & Q."
        }
        if (text.startsWith("Left ") && text.endsWith("% (L)")) {
            return text.replaceFirst("Left ", "Kiri ")
        }
        if (text.startsWith("Right ") && text.endsWith("% (R)")) {
            return text.replaceFirst("Right ", "Kanan ")
        }
        if (text.endsWith(" folders")) {
            return text.removeSuffix(" folders").trim() + " folder"
        }
        if (text.contains(" song") || text.contains(" min") || text.contains(" hr") || text.contains(" album")) {
            return text
                .replace(Regex("""(\d+)\s+albums?""")) { m -> "${m.groupValues[1]} album" }
                .replace(Regex("""(\d+)\s+songs?\s+selected""")) { m -> "${m.groupValues[1]} lagu dipilih" }
                .replace(Regex("""(\d+)\s+songs?\s+found""")) { m -> "${m.groupValues[1]} lagu ditemukan" }
                .replace(Regex("""(\d+)\s+songs?""")) { m -> "${m.groupValues[1]} lagu" }
                .replace(Regex("""(\d+)\s+mins?""")) { m -> "${m.groupValues[1]} menit" }
                .replace(Regex("""(\d+)\s+hrs?""")) { m -> "${m.groupValues[1]} jam" }
        }
        if (text.startsWith("EQ · ")) {
            val sub = text.removePrefix("EQ · ").trim()
            return "EQ · ${translateString(sub)}"
        }
        return text
    }

    fun applyLanguageToViewTree(root: View?) {
        if (root == null) return
        if (root is TextView) {
            val curText = root.text?.toString() ?: ""
            if (curText.isNotEmpty()) {
                val translated = translateString(curText)
                if (translated != curText) {
                    root.text = translated
                }
            }
            if (root is EditText) {
                val curHint = root.hint?.toString() ?: ""
                if (curHint.isNotEmpty()) {
                    val translatedHint = translateString(curHint)
                    if (translatedHint != curHint) {
                        root.hint = translatedHint
                    }
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyLanguageToViewTree(root.getChildAt(i))
            }
        }
    }
}
