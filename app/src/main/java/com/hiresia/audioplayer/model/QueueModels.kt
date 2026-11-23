package com.hiresia.audioplayer.model

import java.util.UUID

enum class QueueGroupType {
    PLAYLIST,
    CUSTOM_TRACKS
}

data class QueueGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val type: QueueGroupType,
    val tracks: MutableList<AudioTrack> = mutableListOf(),
    val playlistId: Long? = null,
    var isExpanded: Boolean = true
) {
    val trackCount: Int get() = tracks.size
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
}
