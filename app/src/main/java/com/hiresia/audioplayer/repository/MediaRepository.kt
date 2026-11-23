package com.hiresia.audioplayer.repository

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.hiresia.audioplayer.HiresiaApplication
import com.hiresia.audioplayer.database.HiresiaLibraryDatabase
import com.hiresia.audioplayer.model.Album
import com.hiresia.audioplayer.model.Artist
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.model.FolderItem
import com.hiresia.audioplayer.model.LibraryFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class FastAudioInfo(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val durationMs: Long
)

enum class FolderSortOrder {
    NAME_ASC,
    DATE_DESC,
    SIZE_DESC
}

/**
 * MediaRepository - High performance audio library repository backed entirely
 * by the C++ Engine SQLite engine (HiresiaLibraryDatabase).
 *
 * Old Android MediaStore queries and legacy custom_tracks.json have been completely
 * replaced with self-contained, audiophile-indexed database operations.
 */
object MediaRepository {
    private const val TAG = "MediaRepository"

    // Complete audio extension set for C++ Engine audiophile playback
    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "flac", "wav", "mp3", "dsf", "dff", "m4a", "alac", "aac",
        "ogg", "opus", "aiff", "aif", "wma", "ape", "wv", "tta", "tak"
    )

    @Volatile
    var isScanAborted = false

    fun abortScan() {
        isScanAborted = true
    }

    /* -------------------------------------------------------------
     * Fast Binary Audio Header Parsing (FLAC, WAV, DSF / DSD)
     * ------------------------------------------------------------- */

    fun extractFastAudioInfo(file: File): FastAudioInfo? {
        if (!file.exists() || !file.canRead() || file.length() < 44) return null
        return try {
            FileInputStream(file).use { fis ->
                parseHeaderBytes(fis, file.length())
            }
        } catch (ignored: Exception) {
            null
        }
    }

    fun extractFastAudioInfo(pfd: ParcelFileDescriptor): FastAudioInfo? {
        return try {
            val length = pfd.statSize
            FileInputStream(pfd.fileDescriptor).use { fis ->
                parseHeaderBytes(fis, if (length > 0) length else 0L)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun parseHeaderBytes(fis: FileInputStream, fileSize: Long = 0L): FastAudioInfo? {
        val header = ByteArray(96)
        val read = fis.read(header)
        if (read < 44) return null

        // 1. FLAC: Magic 0x66 0x4C 0x61 0x43 ("fLaC")
        if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
            header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
            val b14 = header[18].toInt() and 0xFF
            val b15 = header[19].toInt() and 0xFF
            val b16 = header[20].toInt() and 0xFF
            val b17 = header[21].toInt() and 0xFF

            val sr = (b14 shl 12) or (b15 shl 4) or (b16 ushr 4)
            val ch = ((b16 ushr 1) and 0x07) + 1
            val bits = (((b16 and 0x01) shl 4) or (b17 ushr 4)) + 1

            val b18 = header[22].toLong() and 0xFF
            val b19 = header[23].toLong() and 0xFF
            val b20 = header[24].toLong() and 0xFF
            val b21 = header[25].toLong() and 0xFF
            val b22 = header[26].toLong() and 0xFF

            val totalSamples = ((b17.toLong() and 0x0F) shl 32) or
                    (b18 shl 24) or (b19 shl 16) or (b20 shl 8) or b21 or
                    ((b22 and 0xF0) ushr 4)

            val durationMs = if (sr > 0 && totalSamples > 0) {
                (totalSamples * 1000L) / sr
            } else 0L

            if (sr in 8000..384000 && bits in 8..32) {
                return FastAudioInfo(sampleRate = sr, bitDepth = bits, channels = ch, durationMs = durationMs)
            }
        }

        // 2. DSF / DSD: Magic 0x44 0x53 0x44 0x20 ("DSD ")
        if (header[0] == 0x44.toByte() && header[1] == 0x53.toByte() &&
            header[2] == 0x44.toByte() && header[3] == 0x20.toByte()) {
            val ch = (header[52].toInt() and 0xFF) or ((header[53].toInt() and 0xFF) shl 8)
            val sr = (header[60].toInt() and 0xFF) or
                    ((header[61].toInt() and 0xFF) shl 8) or
                    ((header[62].toInt() and 0xFF) shl 16) or
                    ((header[63].toInt() and 0xFF) shl 24)
            val bits = (header[64].toInt() and 0xFF) or ((header[65].toInt() and 0xFF) shl 8)

            val s0 = header[68].toLong() and 0xFF
            val s1 = header[69].toLong() and 0xFF
            val s2 = header[70].toLong() and 0xFF
            val s3 = header[71].toLong() and 0xFF
            val s4 = header[72].toLong() and 0xFF
            val s5 = header[73].toLong() and 0xFF
            val s6 = header[74].toLong() and 0xFF
            val s7 = header[75].toLong() and 0xFF
            val sampleCount = s0 or (s1 shl 8) or (s2 shl 16) or (s3 shl 24) or
                    (s4 shl 32) or (s5 shl 40) or (s6 shl 48) or (s7 shl 56)

            val durationMs = if (sr > 0 && sampleCount > 0) {
                (sampleCount * 1000L) / sr
            } else 0L

            val finalSr = if (sr in 2822400..22579200) sr else 2822400
            return FastAudioInfo(sampleRate = finalSr, bitDepth = if (bits > 0) bits else 1, channels = if (ch in 1..8) ch else 2, durationMs = durationMs)
        }

        // 3. WAV: Magic 0x52 0x49 0x46 0x46 ("RIFF") + "WAVE"
        if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
            header[8] == 0x57.toByte() && header[9] == 0x41.toByte() &&
            header[10] == 0x56.toByte() && header[11] == 0x45.toByte()) {

            val ch = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            val sr = (header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)
            val byteRate = (header[28].toInt() and 0xFF) or
                    ((header[29].toInt() and 0xFF) shl 8) or
                    ((header[30].toInt() and 0xFF) shl 16) or
                    ((header[31].toInt() and 0xFF) shl 24)
            val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)

            val durationMs = if (byteRate > 0 && fileSize > 44) {
                ((fileSize - 44) * 1000L) / byteRate
            } else 0L

            if (sr in 8000..768000 && bits in 8..32) {
                return FastAudioInfo(sampleRate = sr, bitDepth = bits, channels = if (ch in 1..8) ch else 2, durationMs = durationMs)
            }
        }

        // 4. AIFF / AIFF-C: Magic "FORM" + "AIFF" or "AIFC"
        if (header[0] == 0x46.toByte() && header[1] == 0x4F.toByte() &&
            header[2] == 0x52.toByte() && header[3] == 0x4D.toByte() &&
            header[8] == 0x41.toByte() && header[9] == 0x49.toByte() &&
            header[10] == 0x46.toByte() &&
            (header[11] == 0x46.toByte() || header[11] == 0x43.toByte())) {
            // Find COMM chunk within header
            var pos = 12
            while (pos + 26 <= read) {
                val isComm = header[pos] == 0x43.toByte() && header[pos + 1] == 0x4F.toByte() &&
                             header[pos + 2] == 0x4D.toByte() && header[pos + 3] == 0x4D.toByte()
                val ckSize = ((header[pos + 4].toInt() and 0xFF) shl 24) or
                             ((header[pos + 5].toInt() and 0xFF) shl 16) or
                             ((header[pos + 6].toInt() and 0xFF) shl 8) or
                             (header[pos + 7].toInt() and 0xFF)
                if (isComm && pos + 8 + 18 <= read) {
                    val base = pos + 8
                    val ch = ((header[base].toInt() and 0xFF) shl 8) or (header[base + 1].toInt() and 0xFF)
                    val frames = ((header[base + 2].toLong() and 0xFF) shl 24) or
                                 ((header[base + 3].toLong() and 0xFF) shl 16) or
                                 ((header[base + 4].toLong() and 0xFF) shl 8) or
                                 (header[base + 5].toLong() and 0xFF)
                    val bits = ((header[base + 6].toInt() and 0xFF) shl 8) or (header[base + 7].toInt() and 0xFF)
                    val exp = (((header[base + 8].toInt() and 0x7F) shl 8) or (header[base + 9].toInt() and 0xFF)) - 16383
                    val mant = ((header[base + 10].toLong() and 0xFF) shl 56) or
                               ((header[base + 11].toLong() and 0xFF) shl 48) or
                               ((header[base + 12].toLong() and 0xFF) shl 40) or
                               ((header[base + 13].toLong() and 0xFF) shl 32)
                    val sr = if (exp in 0..30) (mant ushr (63 - exp)).toInt() else 44100
                    val durationMs = if (sr > 0 && frames > 0) (frames * 1000L) / sr else 0L
                    if (sr in 8000..768000 && bits in 8..64) {
                        return FastAudioInfo(sampleRate = sr, bitDepth = bits.coerceAtMost(32), channels = if (ch in 1..8) ch else 2, durationMs = durationMs)
                    }
                    break
                }
                if (ckSize <= 0) break
                pos += 8 + ckSize + (ckSize and 1)
            }
        }

        // 5. TTA1: Magic "TTA1"
        if (header[0] == 0x54.toByte() && header[1] == 0x54.toByte() &&
            header[2] == 0x41.toByte() && header[3] == 0x31.toByte()) {
            val ch = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
            val bits = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
            val sr = (header[10].toInt() and 0xFF) or
                     ((header[11].toInt() and 0xFF) shl 8) or
                     ((header[12].toInt() and 0xFF) shl 16) or
                     ((header[13].toInt() and 0xFF) shl 24)
            val totalSamples = (header[14].toLong() and 0xFF) or
                               ((header[15].toLong() and 0xFF) shl 8) or
                               ((header[16].toLong() and 0xFF) shl 16) or
                               ((header[17].toLong() and 0xFF) shl 24)
            val durationMs = if (sr > 0 && totalSamples > 0) (totalSamples * 1000L) / sr else 0L
            if (sr in 8000..384000 && bits in 8..32) {
                return FastAudioInfo(sampleRate = sr, bitDepth = bits, channels = if (ch in 1..8) ch else 2, durationMs = durationMs)
            }
        }

        // 6. WavPack: Magic "wvpk"
        if (header[0] == 0x77.toByte() && header[1] == 0x76.toByte() &&
            header[2] == 0x70.toByte() && header[3] == 0x6B.toByte()) {
            val totalSamples = (header[12].toLong() and 0xFF) or
                               ((header[13].toLong() and 0xFF) shl 8) or
                               ((header[14].toLong() and 0xFF) shl 16) or
                               ((header[15].toLong() and 0xFF) shl 24)
            val flags = (header[24].toInt() and 0xFF) or
                        ((header[25].toInt() and 0xFF) shl 8) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[27].toInt() and 0xFF) shl 24)
            val bytesPerSample = (flags and 0x03) + 1
            val bits = if ((flags and 0x80) != 0) 32 else bytesPerSample * 8
            val isMono = (flags and 0x04) != 0
            val srIdx = (flags ushr 23) and 0x0F
            val wpRates = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000, 44100)
            val sr = wpRates[srIdx]
            val durationMs = if (sr > 0 && totalSamples > 0 && totalSamples != 0xFFFFFFFFL) (totalSamples * 1000L) / sr else 0L
            return FastAudioInfo(sampleRate = sr, bitDepth = bits.coerceIn(8, 32), channels = if (isMono) 1 else 2, durationMs = durationMs)
        }

        return null
    }

    /* -------------------------------------------------------------
     * Track Parsing Helper from File / URI
     * ------------------------------------------------------------- */

    fun hasAlacAtom(file: File): Boolean {
        return try {
            val sizeToRead = minOf(file.length(), 65536L).toInt()
            val buffer = ByteArray(sizeToRead)
            file.inputStream().use { it.read(buffer) }
            val target = "alac".toByteArray(Charsets.US_ASCII)
            var found = false
            for (i in 0 until (buffer.size - target.size)) {
                if (buffer[i] == target[0] && buffer[i+1] == target[1] &&
                    buffer[i+2] == target[2] && buffer[i+3] == target[3]) {
                    found = true
                    break
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    fun hasAlacAtom(pfd: ParcelFileDescriptor): Boolean {
        return try {
            val fis = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(65536)
            val read = fis.read(buffer)
            if (read > 4) {
                val target = "alac".toByteArray(Charsets.US_ASCII)
                for (i in 0 until (read - target.size)) {
                    if (buffer[i] == target[0] && buffer[i+1] == target[1] &&
                        buffer[i+2] == target[2] && buffer[i+3] == target[3]) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun extractAndSaveArtwork(mmr: MediaMetadataRetriever, key: String, parentDir: File? = null): Uri? {
        try {
            val cacheDir = HiresiaApplication.instance.cacheDir
            val coversDir = File(cacheDir, "covers").apply { if (!exists()) mkdirs() }
            val coverFile = File(coversDir, "${Math.abs(key.hashCode())}.jpg")

            val artBytes = mmr.embeddedPicture
            if (artBytes != null && artBytes.isNotEmpty()) {
                if (!coverFile.exists() || coverFile.length() == 0L) {
                    coverFile.writeBytes(artBytes)
                }
                return Uri.fromFile(coverFile)
            }

            // Fallback: check parent directory for standard cover images
            if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                val standardNames = listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "art.jpg", "cover.png")
                for (name in standardNames) {
                    val img = File(parentDir, name)
                    if (img.exists() && img.length() > 0) {
                        return Uri.fromFile(img)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting artwork for $key: ${e.message}")
        }
        return null
    }

    fun parseTrackFromFile(file: File, folderSearchId: Long): AudioTrack {
        val ext = file.extension.lowercase()
        val isDsd = ext == "dsf" || ext == "dff"
        val dsdRate = if (isDsd) 64 else 0

        var format = when (ext) {
            "flac" -> "FLAC"
            "dsf", "dff" -> "DSD"
            "wav" -> "WAV"
            "aiff", "aif" -> "AIFF"
            "ogg" -> "OGG"
            "opus" -> "OPUS"
            "mp3" -> "MP3"
            "aac" -> "AAC"
            "ape" -> "APE"
            "wv" -> "WavPack"
            "alac" -> "ALAC"
            "m4a", "mp4" -> "AAC" // Default for m4a container is AAC
            else -> ext.uppercase()
        }

        var title = file.nameWithoutExtension
        var artist = file.parentFile?.name ?: "Unknown Artist"
        var album = file.parentFile?.name ?: "Unknown Album"
        var durationMs: Long = 0
        var sampleRate = if (isDsd) 2822400 else 44100
        var bitDepth = if (isDsd) 1 else if (format == "FLAC" || format == "WAV") 24 else 16
        var artUri: Uri? = null

        // 1. Fast header parse
        val fast = extractFastAudioInfo(file)
        if (fast != null) {
            sampleRate = fast.sampleRate
            bitDepth = fast.bitDepth
            if (fast.durationMs > 0) durationMs = fast.durationMs
        }

        // 2. MMR for ID3 tags and precise Codec check
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val metaTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDuration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val metaSr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            val metaMime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.lowercase() ?: ""

            if (!metaTitle.isNullOrBlank()) title = metaTitle.trim()
            if (!metaArtist.isNullOrBlank()) artist = metaArtist.trim()
            if (!metaAlbum.isNullOrBlank()) album = metaAlbum.trim()
            metaDuration?.toLongOrNull()?.let { if (it > 0 && durationMs <= 0) durationMs = it }
            metaSr?.toIntOrNull()?.let { if (it > 0 && fast == null) sampleRate = it }

            // Precise M4A / ALAC check: Never falsely label AAC as ALAC
            if (ext == "m4a" || ext == "mp4" || ext == "alac") {
                if (metaMime.contains("alac")) {
                    format = "ALAC"
                } else if (metaMime.contains("aac") || metaMime.contains("mp4a")) {
                    format = "AAC"
                } else if (hasAlacAtom(file)) {
                    format = "ALAC"
                } else {
                    format = "AAC"
                }
            }

            // Extract real embedded artwork into cached JPEG
            artUri = extractAndSaveArtwork(mmr, file.absolutePath, file.parentFile)
        } catch (ignored: Exception) {
        } finally {
            try { mmr.release() } catch (ignored: Exception) {}
        }

        return AudioTrack(
            id = System.currentTimeMillis() + (0..9999).random(),
            title = title,
            artist = artist,
            album = album,
            path = file.absolutePath,
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = if (format == "AAC" || format == "MP3") 16 else bitDepth,
            channels = 2,
            format = format,
            isDsd = isDsd,
            dsdRate = dsdRate,
            albumArtUri = artUri,
            sizeBytes = file.length(),
            rawPath = file.absolutePath,
            isUserImported = true,
            folderSearchId = folderSearchId
        )
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        knownFileName: String? = null,
        knownSize: Long? = null,
        defaultAlbum: String? = null,
        folderSearchId: Long = 0L
    ): AudioTrack? {
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}

            var fileName = knownFileName ?: "Unknown Audio"
            var fileSize: Long = knownSize ?: 0

            if (knownFileName == null || knownSize == null) {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameCol != -1) fileName = cursor.getString(nameCol) ?: fileName
                            if (sizeCol != -1) fileSize = cursor.getLong(sizeCol)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cursor query error for $uri: ${e.message}")
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val isDsd = ext == "dsf" || ext == "dff"
            val dsdRate = if (isDsd) 64 else 0
            var format = when (ext) {
                "flac" -> "FLAC"
                "dsf", "dff" -> "DSD"
                "wav" -> "WAV"
                "aiff", "aif" -> "AIFF"
                "ogg" -> "OGG"
                "opus" -> "OPUS"
                "mp3" -> "MP3"
                "aac" -> "AAC"
                "ape" -> "APE"
                "wv" -> "WavPack"
                "alac" -> "ALAC"
                "m4a", "mp4" -> "AAC" // Default for m4a container is AAC
                else -> if (ext.isNotEmpty()) ext.uppercase() else "AUDIO"
            }

            var title = fileName.substringBeforeLast('.')
            var artist = "Unknown Artist"
            var album = defaultAlbum ?: "Imported Tracks"
            var durationMs: Long = 0
            var sampleRate = if (isDsd) 2822400 else 44100
            var bitDepth = if (isDsd) 1 else (if (format == "FLAC" || format == "WAV") 24 else 16)
            var artUri: Uri? = null

            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fast = extractFastAudioInfo(pfd)
                    if (fast != null) {
                        sampleRate = fast.sampleRate
                        bitDepth = fast.bitDepth
                        if (fast.durationMs > 0) durationMs = fast.durationMs
                    }

                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(pfd.fileDescriptor)
                        val metaTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        val metaArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        val metaAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        val metaDuration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val metaSr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        val metaMime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.lowercase() ?: ""

                        if (!metaTitle.isNullOrBlank()) title = metaTitle.trim()
                        if (!metaArtist.isNullOrBlank()) artist = metaArtist.trim()
                        if (!metaAlbum.isNullOrBlank()) album = metaAlbum.trim()
                        metaDuration?.toLongOrNull()?.let { if (it > 0 && durationMs <= 0) durationMs = it }
                        metaSr?.toIntOrNull()?.let { if (it > 0 && fast == null) sampleRate = it }

                        // Precise M4A / ALAC check
                        if (ext == "m4a" || ext == "mp4" || ext == "alac") {
                            if (metaMime.contains("alac")) {
                                format = "ALAC"
                            } else if (metaMime.contains("aac") || metaMime.contains("mp4a")) {
                                format = "AAC"
                            } else if (hasAlacAtom(pfd)) {
                                format = "ALAC"
                            } else {
                                format = "AAC"
                            }
                        }

                        // Extract real embedded artwork into cached JPEG
                        artUri = extractAndSaveArtwork(mmr, uri.toString())
                    } finally {
                        try { mmr.release() } catch (ignored: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FileDescriptor MMR fallback for $uri: ${e.message}")
            }

            val physical = StoragePathUtils.resolvePhysicalPath(context, uri)
            val effectivePath = if (physical != null && File(physical).exists()) physical else uri.toString()
            val effectiveRawPath = physical ?: StoragePathUtils.getDisplayablePath(context, uri.toString())

            return AudioTrack(
                id = System.currentTimeMillis() + (0..9999).random(),
                title = title,
                artist = artist,
                album = album,
                path = effectivePath,
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitDepth = if (format == "AAC" || format == "MP3") 16 else bitDepth,
                channels = 2,
                format = format,
                isDsd = isDsd,
                dsdRate = dsdRate,
                albumArtUri = artUri,
                sizeBytes = fileSize,
                rawPath = effectiveRawPath,
                isUserImported = true,
                folderSearchId = folderSearchId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import audio from $uri", e)
            return null
        }
    }

    /* -------------------------------------------------------------
     * C++ Engine Recursive Scanner Engine
     * ------------------------------------------------------------- */

    private fun scanPhysicalDirectoryRecursive(
        dir: File,
        folderSearchId: Long,
        outList: MutableList<AudioTrack>,
        onProgress: ((String, Int) -> Unit)?
    ) {
        if (isScanAborted || !dir.exists() || !dir.isDirectory) return

        // Standard check: ignore directories with .nomedia
        if (File(dir, ".nomedia").exists()) return

        val files = dir.listFiles() ?: return
        for (f in files) {
            if (isScanAborted) return
            if (f.name.startsWith(".")) continue

            if (f.isDirectory) {
                scanPhysicalDirectoryRecursive(f, folderSearchId, outList, onProgress)
            } else if (f.isFile && f.canRead()) {
                val ext = f.extension.lowercase()
                if (ext in SUPPORTED_AUDIO_EXTENSIONS && ext != "cue" && ext != "iso") {
                    val track = parseTrackFromFile(f, folderSearchId)
                    outList.add(track)
                    onProgress?.invoke(f.name, outList.size)
                }
            }
        }
    }

    private fun scanSafFolderRecursive(
        context: Context,
        docDir: DocumentFile,
        folderSearchId: Long,
        outList: MutableList<AudioTrack>,
        onProgress: ((String, Int) -> Unit)?
    ) {
        if (isScanAborted || !docDir.exists() || !docDir.isDirectory) return

        val children = docDir.listFiles()
        if (children.any { it.name == ".nomedia" }) return

        for (doc in children) {
            if (isScanAborted) return
            val name = doc.name ?: continue
            if (name.startsWith(".")) continue

            if (doc.isDirectory) {
                scanSafFolderRecursive(context, doc, folderSearchId, outList, onProgress)
            } else if (doc.isFile) {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_AUDIO_EXTENSIONS && ext != "cue" && ext != "iso") {
                    val resolvedDocPath = StoragePathUtils.resolvePhysicalPath(context, doc.uri)
                    val track = if (resolvedDocPath != null && File(resolvedDocPath).exists()) {
                        parseTrackFromFile(File(resolvedDocPath), folderSearchId)
                    } else {
                        importFromUri(
                            context = context,
                            uri = doc.uri,
                            knownFileName = name,
                            knownSize = doc.length(),
                            defaultAlbum = docDir.name ?: "SAF Music",
                            folderSearchId = folderSearchId
                        )
                    }
                    if (track != null) {
                        outList.add(track)
                        onProgress?.invoke(name, outList.size)
                    }
                }
            }
        }
    }

    /* -------------------------------------------------------------
     * Pustaka Folder Management (FolderSearch)
     * ------------------------------------------------------------- */

    fun getLibraryFolders(context: Context): List<LibraryFolder> {
        val db = HiresiaLibraryDatabase.getInstance(context)
        return db.getFolderSearches()
    }

    fun addLibraryFolder(context: Context, pathOrUri: String, friendlyName: String, isSaf: Boolean): LibraryFolder {
        val db = HiresiaLibraryDatabase.getInstance(context)
        val id = db.addOrGetFolderSearch(pathOrUri, friendlyName, isSaf)
        return LibraryFolder(
            id = id,
            pathOrUri = pathOrUri,
            friendlyName = friendlyName,
            isSaf = isSaf,
            trackCount = 0,
            lastScanned = 0L
        )
    }

    fun removeLibraryFolder(context: Context, folderId: Long) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        db.removeFolderSearch(folderId)
    }

    /**
     * Rescans all registered FolderSearch entries recursively and syncs with SQLite database.
     */
    suspend fun rescanLibraryFolders(
        context: Context,
        onProgress: ((folderName: String, currentFile: String, totalCount: Int) -> Unit)? = null
    ): List<AudioTrack> = withContext(Dispatchers.IO) {
        isScanAborted = false
        val db = HiresiaLibraryDatabase.getInstance(context)
        var folders = db.getFolderSearches()

        // Only scan folders explicitly added by the owner
        ensureDefaultFoldersRegistered(context)
        folders = db.getFolderSearches()

        var cumulativeCount = 0

        for (folder in folders) {
            if (isScanAborted) break
            val scannedTracks = mutableListOf<AudioTrack>()

            if (folder.isSaf || folder.pathOrUri.startsWith("content://")) {
                val physicalPath = StoragePathUtils.resolvePhysicalPath(context, Uri.parse(folder.pathOrUri))
                if (physicalPath != null && File(physicalPath).exists() && File(physicalPath).isDirectory) {
                    val dir = File(physicalPath)
                    scanPhysicalDirectoryRecursive(dir, folder.id, scannedTracks) { curFile, count ->
                        onProgress?.invoke(folder.friendlyName, curFile, cumulativeCount + count)
                    }
                } else {
                    try {
                        val treeUri = Uri.parse(folder.pathOrUri)
                        val treeRoot = DocumentFile.fromTreeUri(context, treeUri)
                        if (treeRoot != null && treeRoot.exists()) {
                            scanSafFolderRecursive(context, treeRoot, folder.id, scannedTracks) { curFile, count ->
                                onProgress?.invoke(folder.friendlyName, curFile, cumulativeCount + count)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scanning SAF folder ${folder.pathOrUri}", e)
                    }
                }
            } else {
                val dir = File(folder.pathOrUri)
                if (dir.exists() && dir.isDirectory) {
                    scanPhysicalDirectoryRecursive(dir, folder.id, scannedTracks) { curFile, count ->
                        onProgress?.invoke(folder.friendlyName, curFile, cumulativeCount + count)
                    }
                }
            }

            cumulativeCount += scannedTracks.size
            // Incremental sync directly in SQLite database
            db.syncFolderSongs(folder.id, scannedTracks)
        }

        db.getAllSongs()
    }

    private fun ensureDefaultFoldersRegistered(context: Context) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        val extStorage = Environment.getExternalStorageDirectory().absolutePath

        // CRITICAL FIX: Owner decides what folders to scan.
        // Purge whole internal storage search root if present.
        db.cleanupRootInternalStorage(extStorage)
    }

    /* -------------------------------------------------------------
     * Main Data Queries (Replacing Old MediaStore & JSON)
     * ------------------------------------------------------------- */

    suspend fun loadLocalTracks(context: Context): List<AudioTrack> = withContext(Dispatchers.IO) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        db.migrateSafPathsToPhysical(context)
        ensureDefaultFoldersRegistered(context)
        db.getAllSongs()
    }

    fun removeFolderAndSongs(context: Context, item: FolderItem) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        if (item.folderSearchId > 0L) {
            db.removeFolderSearch(item.folderSearchId)
        }
        db.removeFolderAndSongs(item.path)
    }

    fun searchTracks(context: Context, query: String): List<AudioTrack> {
        val db = HiresiaLibraryDatabase.getInstance(context)
        return db.searchSongs(query)
    }

    fun ensureTrackArtwork(track: AudioTrack): AudioTrack {
        if (track.albumArtUri != null) return track
        val raw = track.rawPath.ifEmpty { track.path }
        val f = File(raw)
        if (!f.exists() || !f.canRead()) return track
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            val uri = extractAndSaveArtwork(mmr, f.absolutePath, f.parentFile)
            if (uri != null) track.copy(albumArtUri = uri) else track
        } catch (_: Exception) {
            track
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    fun getAlbums(allTracks: List<AudioTrack>): List<Album> {
        return allTracks
            .groupBy { it.album.ifBlank { "Unknown Album" } }
            .map { (albumTitle, tracks) ->
                val first = tracks.first()
                val artUri = tracks.firstNotNullOfOrNull { it.albumArtUri }
                Album(
                    id = first.id,
                    title = albumTitle,
                    artist = first.artist,
                    trackCount = tracks.size,
                    albumArtUri = artUri
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    fun getArtists(allTracks: List<AudioTrack>): List<Artist> {
        return allTracks
            .groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .map { (artistName, tracks) ->
                val albumCount = tracks.map { it.album }.distinct().size
                val artUri = tracks.firstNotNullOfOrNull { it.albumArtUri }
                Artist(
                    id = tracks.first().id,
                    name = artistName,
                    trackCount = tracks.size,
                    albumCount = albumCount,
                    albumArtUri = artUri
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun getFolderItems(
        context: Context,
        currentPath: String,
        allTracks: List<AudioTrack>,
        sortOrder: FolderSortOrder = FolderSortOrder.NAME_ASC
    ): List<FolderItem> {
        val db = HiresiaLibraryDatabase.getInstance(context)
        return db.getFolderHierarchyItems(context, currentPath, allTracks, sortOrder)
    }

    /* -------------------------------------------------------------
     * SAF Multi-File & Folder Imports
     * ------------------------------------------------------------- */

    suspend fun importFromUris(context: Context, uris: List<Uri>): List<AudioTrack> = withContext(Dispatchers.IO) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        val defaultFolderId = db.addOrGetFolderSearch("content://imported", "Lagu Impor (SAF)", isSaf = true)

        val imported = mutableListOf<AudioTrack>()
        for (uri in uris) {
            val track = importFromUri(context, uri, folderSearchId = defaultFolderId)
            if (track != null) {
                imported.add(track)
            }
        }
        if (imported.isNotEmpty()) {
            db.syncFolderSongs(defaultFolderId, imported)
        }
        imported
    }

    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        onProgress: ((folderName: String, currentFile: String, count: Int) -> Unit)? = null
    ): List<AudioTrack> = withContext(Dispatchers.IO) {
        isScanAborted = false
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tree persistable permission error: ${e.message}")
        }

        val db = HiresiaLibraryDatabase.getInstance(context)
        val existingPaths = db.getFolderSearches().map { it.pathOrUri }.toSet()
        val physicalPath = StoragePathUtils.resolvePhysicalPath(context, treeUri)
        if (physicalPath != null) {
            val dir = File(physicalPath)
            if (dir.exists() && dir.isDirectory) {
                val friendlyName = dir.name.ifBlank { "Folder Musik" }
                val isNewFolder = dir.absolutePath !in existingPaths
                onProgress?.invoke(friendlyName, "Menyiapkan folder...", 0)
                val folder = addLibraryFolder(context, dir.absolutePath, friendlyName, isSaf = false)
                val folderTracks = mutableListOf<AudioTrack>()
                scanPhysicalDirectoryRecursive(dir, folder.id, folderTracks) { curFile, count ->
                    onProgress?.invoke(friendlyName, curFile, count)
                }
                if (isScanAborted) {
                    if (isNewFolder) {
                        db.removeFolderSearch(folder.id)
                    }
                    return@withContext emptyList()
                }
                onProgress?.invoke(friendlyName, "Menyimpan ke pustaka...", folderTracks.size)
                db.syncFolderSongs(folder.id, folderTracks)
                return@withContext folderTracks
            }
        }

        val treeRoot = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val friendlyName = treeRoot.name ?: "SAF Folder"
        val isNewSafFolder = treeUri.toString() !in existingPaths
        onProgress?.invoke(friendlyName, "Menyiapkan folder SAF...", 0)
        val folder = addLibraryFolder(context, treeUri.toString(), friendlyName, isSaf = true)

        val folderTracks = mutableListOf<AudioTrack>()
        scanSafFolderRecursive(context, treeRoot, folder.id, folderTracks) { curFile, count ->
            onProgress?.invoke(friendlyName, curFile, count)
        }

        if (isScanAborted) {
            if (isNewSafFolder) {
                db.removeFolderSearch(folder.id)
            }
            return@withContext emptyList()
        }
        onProgress?.invoke(friendlyName, "Menyimpan ke pustaka...", folderTracks.size)
        db.syncFolderSongs(folder.id, folderTracks)
        folderTracks
    }

    fun saveCustomTracks(context: Context, tracks: List<AudioTrack>) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        val defaultFolderId = db.addOrGetFolderSearch("content://imported", "Lagu Impor (SAF)", isSaf = true)
        db.syncFolderSongs(defaultFolderId, tracks)
    }

    fun clearCustomTracks(context: Context) {
        val db = HiresiaLibraryDatabase.getInstance(context)
        val searches = db.getFolderSearches()
        for (s in searches) {
            if (s.isSaf || s.pathOrUri.startsWith("content://")) {
                db.removeFolderSearch(s.id)
            }
        }
    }
}
