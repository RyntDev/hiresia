package com.hiresia.audioplayer.model

import android.net.Uri

data class LibraryFolder(
    val id: Long = System.currentTimeMillis(),
    val pathOrUri: String,
    val friendlyName: String,
    val isSaf: Boolean = false,
    var trackCount: Int = 0,
    var lastScanned: Long = 0L
)

data class AudioTrack(
    val id: Long = 0,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val path: String,
    val durationMs: Long = 0,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val format: String = "FLAC",
    val isDsd: Boolean = false,
    val dsdRate: Int = 0, // 64, 128, 256
    val albumArtUri: Uri? = null,
    val sizeBytes: Long = 0,
    val rawPath: String = "",
    val isUserImported: Boolean = false,
    val folderSearchId: Long = 0L,
    var isLiked: Boolean = false,
    var lastTimePlayed: Long = 0L,
    val originGroupName: String = ""
) {
    val technicalFormatString: String
        get() {
            return if (isDsd) {
                "DSD$dsdRate / 1-bit / ${String.format("%.2f", (dsdRate * 44.1) / 1000.0)} MHz"
            } else {
                val rateKhz = sampleRate / 1000.0
                val rateStr = if (rateKhz % 1.0 == 0.0) "${rateKhz.toInt()} kHz" else "$rateKhz kHz"
                "$format $rateStr / $bitDepth-bit"
            }
        }
}

data class UserPlaylist(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUri: String = "",
    val trackIds: List<Long> = emptyList(),
    var trackCount: Int = 0,
    var totalDurationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val albumArtUri: Uri?
)

data class Artist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val albumArtUri: Uri? = null
)

data class FolderItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val childCount: Int = 0,
    val sizeBytes: Long = 0,
    val audioTrack: AudioTrack? = null,
    val dateModified: Long = 0L,
    val folderSearchId: Long = 0L
)
