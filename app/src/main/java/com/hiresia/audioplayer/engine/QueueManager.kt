package com.hiresia.audioplayer.engine

import android.util.Log
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.model.QueueGroup
import com.hiresia.audioplayer.model.QueueGroupType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * QueueManager
 *
 * Manages the hierarchical audio playback queue supporting:
 * - Grouped playlists (e.g. "Playlist A", "Playlist B")
 * - Individual / random songs group ("Lagu pilihan")
 * - Expandable/collapsible groups
 * - Reordering and item removal
 * - Auto-advancing across groups seamlessly
 */
object QueueManager {
    private const val TAG = "QueueManager"

    private val _groups = MutableStateFlow<List<QueueGroup>>(emptyList())
    val groups: StateFlow<List<QueueGroup>> = _groups.asStateFlow()

    private val _currentGroup = MutableStateFlow<QueueGroup?>(null)
    val currentGroup: StateFlow<QueueGroup?> = _currentGroup.asStateFlow()

    private val _currentTrack = MutableStateFlow<AudioTrack?>(null)
    val currentTrack: StateFlow<AudioTrack?> = _currentTrack.asStateFlow()

    private var currentGroupIndex: Int = -1
    private var currentTrackIndexInGroup: Int = -1

    /**
     * Initializes the queue with a starting playlist or track collection.
     */
    fun setInitialPlaylist(
        playlistName: String,
        tracks: List<AudioTrack>,
        startIndex: Int = 0,
        playlistId: Long? = null
    ) {
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }

        val taggedTracks = tracks.map { it.copy(originGroupName = playlistName) }.toMutableList()
        val firstGroup = QueueGroup(
            name = playlistName,
            type = if (playlistId != null) QueueGroupType.PLAYLIST else QueueGroupType.CUSTOM_TRACKS,
            tracks = taggedTracks,
            playlistId = playlistId,
            isExpanded = true
        )

        _groups.value = listOf(firstGroup)
        currentGroupIndex = 0
        currentTrackIndexInGroup = startIndex.coerceIn(0, taggedTracks.size - 1)
        _currentGroup.value = firstGroup
        val track = taggedTracks[currentTrackIndexInGroup]
        _currentTrack.value = track

        AudioPlayerController.playTrackDirectly(track)
    }

    /**
     * Adds an entire playlist as a new group in the queue.
     */
    fun addPlaylistGroup(
        playlistName: String,
        tracks: List<AudioTrack>,
        playlistId: Long? = null
    ) {
        if (tracks.isEmpty()) return

        val wasEmpty = _groups.value.isEmpty()
        val taggedTracks = tracks.map { it.copy(originGroupName = playlistName) }.toMutableList()
        val newGroup = QueueGroup(
            name = playlistName,
            type = QueueGroupType.PLAYLIST,
            tracks = taggedTracks,
            playlistId = playlistId,
            isExpanded = true
        )

        val updated = _groups.value.toMutableList().apply { add(newGroup) }
        _groups.value = updated

        if (wasEmpty) {
            currentGroupIndex = 0
            currentTrackIndexInGroup = 0
            _currentGroup.value = newGroup
            _currentTrack.value = taggedTracks[0]
            AudioPlayerController.playTrackDirectly(taggedTracks[0])
        }
    }

    /**
     * Adds individual tracks to the bottom of the queue. Only merges into an existing
     * "Lagu pilihan" group if it is already the very last group in the queue; otherwise
     * appends a new group at the bottom so newly added songs never jump into the middle.
     */
    fun addCustomTracks(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return

        val wasEmpty = _groups.value.isEmpty()
        val groupName = "Lagu pilihan"
        val taggedTracks = tracks.map { it.copy(originGroupName = groupName) }

        val currentList = _groups.value.toMutableList()
        val lastIdx = currentList.lastIndex

        if (lastIdx != -1 && currentList[lastIdx].type == QueueGroupType.CUSTOM_TRACKS) {
            val existing = currentList[lastIdx]
            val updatedTracks = ArrayList(existing.tracks).apply { addAll(taggedTracks) }
            currentList[lastIdx] = existing.copy(tracks = updatedTracks)
        } else {
            val newGroup = QueueGroup(
                name = groupName,
                type = QueueGroupType.CUSTOM_TRACKS,
                tracks = taggedTracks.toMutableList(),
                isExpanded = true
            )
            currentList.add(newGroup)
        }
        _groups.value = currentList

        if (wasEmpty) {
            val firstGroup = _groups.value.first()
            currentGroupIndex = 0
            currentTrackIndexInGroup = 0
            _currentGroup.value = firstGroup
            _currentTrack.value = firstGroup.tracks.first()
            AudioPlayerController.playTrackDirectly(firstGroup.tracks.first())
        }
    }

    /**
     * Inserts a single track right after the currently playing track.
     */
    fun insertNextTrack(track: AudioTrack) {
        if (_groups.value.isEmpty()) {
            addCustomTracks(listOf(track))
            return
        }

        val curG = _currentGroup.value
        if (curG != null && currentGroupIndex in _groups.value.indices) {
            val currentList = _groups.value.toMutableList()
            val targetGroup = currentList[currentGroupIndex]
            val newTracks = ArrayList(targetGroup.tracks)
            val insertIdx = (currentTrackIndexInGroup + 1).coerceAtMost(newTracks.size)
            newTracks.add(insertIdx, track.copy(originGroupName = targetGroup.name))
            currentList[currentGroupIndex] = targetGroup.copy(tracks = newTracks)
            _groups.value = currentList
        } else {
            addCustomTracks(listOf(track))
        }
    }

    /**
     * Plays a specific track at [trackIndex] inside [groupId].
     */
    fun playTrackInGroup(groupId: String, trackIndex: Int) {
        val gIndex = _groups.value.indexOfFirst { it.id == groupId }
        if (gIndex == -1) return

        val group = _groups.value[gIndex]
        if (trackIndex !in group.tracks.indices) return

        currentGroupIndex = gIndex
        currentTrackIndexInGroup = trackIndex
        _currentGroup.value = group
        val track = group.tracks[trackIndex]
        _currentTrack.value = track

        AudioPlayerController.playTrackDirectly(track)
    }

    /**
     * Removes a track from a group.
     */
    fun removeTrack(groupId: String, trackIndex: Int) {
        val gIndex = _groups.value.indexOfFirst { it.id == groupId }
        if (gIndex == -1) return

        val currentList = _groups.value.toMutableList()
        val group = currentList[gIndex]
        if (trackIndex !in group.tracks.indices) return

        val isPlayingThis = (gIndex == currentGroupIndex && trackIndex == currentTrackIndexInGroup)
        val newTracks = ArrayList(group.tracks)
        newTracks.removeAt(trackIndex)

        // If group becomes empty, remove the group
        if (newTracks.isEmpty()) {
            removeGroup(groupId)
            return
        }

        currentList[gIndex] = group.copy(tracks = newTracks)
        _groups.value = currentList

        if (isPlayingThis) {
            // Play next track in group or next group
            if (trackIndex < newTracks.size) {
                currentTrackIndexInGroup = trackIndex
                val nextT = newTracks[trackIndex]
                _currentTrack.value = nextT
                AudioPlayerController.playTrackDirectly(nextT)
            } else {
                next()
            }
        } else if (gIndex == currentGroupIndex && trackIndex < currentTrackIndexInGroup) {
            currentTrackIndexInGroup--
        }
    }

    /**
     * Removes an entire group from the queue.
     */
    fun removeGroup(groupId: String) {
        val gIndex = _groups.value.indexOfFirst { it.id == groupId }
        if (gIndex == -1) return

        val isCurrentGroup = (gIndex == currentGroupIndex)
        val updated = _groups.value.toMutableList()
        updated.removeAt(gIndex)
        _groups.value = updated

        if (updated.isEmpty()) {
            clearQueue()
            return
        }

        if (isCurrentGroup) {
            val newGIndex = gIndex.coerceAtMost(updated.size - 1)
            val newGroup = updated[newGIndex]
            currentGroupIndex = newGIndex
            currentTrackIndexInGroup = 0
            _currentGroup.value = newGroup
            if (newGroup.tracks.isNotEmpty()) {
                val nextT = newGroup.tracks[0]
                _currentTrack.value = nextT
                AudioPlayerController.playTrackDirectly(nextT)
            } else {
                next()
            }
        } else if (gIndex < currentGroupIndex) {
            currentGroupIndex--
        }
    }

    /**
     * Clears the entire queue.
     */
    fun clearQueue() {
        _groups.value = emptyList()
        _currentGroup.value = null
        _currentTrack.value = null
        currentGroupIndex = -1
        currentTrackIndexInGroup = -1
        AudioPlayerController.stop()
    }

    /**
     * Reorders a track inside [groupId] from [fromIndex] to [toIndex],
     * keeping [currentTrackIndexInGroup] synchronized with the currently playing song.
     */
    fun moveTrack(groupId: String, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val currentList = _groups.value.toMutableList()
        val gIndex = currentList.indexOfFirst { it.id == groupId }
        if (gIndex == -1) return

        val group = currentList[gIndex]
        if (fromIndex !in group.tracks.indices || toIndex !in group.tracks.indices) return

        val newTracks = ArrayList(group.tracks)
        val movedTrack = newTracks.removeAt(fromIndex)
        newTracks.add(toIndex, movedTrack)

        val updatedGroup = group.copy(tracks = newTracks)
        currentList[gIndex] = updatedGroup

        if (gIndex == currentGroupIndex) {
            currentTrackIndexInGroup = when {
                currentTrackIndexInGroup == fromIndex -> toIndex
                fromIndex < currentTrackIndexInGroup && toIndex >= currentTrackIndexInGroup -> currentTrackIndexInGroup - 1
                fromIndex > currentTrackIndexInGroup && toIndex <= currentTrackIndexInGroup -> currentTrackIndexInGroup + 1
                else -> currentTrackIndexInGroup
            }
            _currentGroup.value = updatedGroup
        }

        _groups.value = currentList
    }

    /**
     * Reorders entire queue groups from [fromGroupIndex] to [toGroupIndex],
     * keeping [currentGroupIndex] synchronized.
     */
    fun moveGroup(fromGroupIndex: Int, toGroupIndex: Int) {
        if (fromGroupIndex == toGroupIndex) return
        val currentList = _groups.value.toMutableList()
        if (fromGroupIndex !in currentList.indices || toGroupIndex !in currentList.indices) return

        val movedGroup = currentList.removeAt(fromGroupIndex)
        currentList.add(toGroupIndex, movedGroup)

        if (currentGroupIndex in currentList.indices) {
            currentGroupIndex = when {
                currentGroupIndex == fromGroupIndex -> toGroupIndex
                fromGroupIndex < currentGroupIndex && toGroupIndex >= currentGroupIndex -> currentGroupIndex - 1
                fromGroupIndex > currentGroupIndex && toGroupIndex <= currentGroupIndex -> currentGroupIndex + 1
                else -> currentGroupIndex
            }
        }

        _groups.value = currentList
    }

    /**
     * Toggles group expand / collapse.
     */
    fun toggleGroupExpanded(groupId: String) {
        val currentList = _groups.value
        val gIndex = currentList.indexOfFirst { it.id == groupId }
        if (gIndex == -1) return
        val target = currentList[gIndex]
        val updated = currentList.toMutableList()
        updated[gIndex] = target.copy(isExpanded = !target.isExpanded)
        _groups.value = updated
    }

    /**
     * Advances to the next track in the queue, transitioning across groups.
     */
    fun next() {
        if (_groups.value.isEmpty()) return

        val allGroups = _groups.value
        val curGroup = allGroups.getOrNull(currentGroupIndex) ?: allGroups.first()
        val curGIndex = currentGroupIndex.coerceIn(0, allGroups.size - 1)

        // 1. Next track in same group
        if (currentTrackIndexInGroup + 1 < curGroup.tracks.size) {
            currentTrackIndexInGroup++
            val t = curGroup.tracks[currentTrackIndexInGroup]
            _currentTrack.value = t
            AudioPlayerController.playTrackDirectly(t)
            return
        }

        // 2. Advance to next group
        if (curGIndex + 1 < allGroups.size) {
            currentGroupIndex = curGIndex + 1
            val nextG = allGroups[currentGroupIndex]
            _currentGroup.value = nextG
            if (nextG.tracks.isNotEmpty()) {
                currentTrackIndexInGroup = 0
                val t = nextG.tracks[0]
                _currentTrack.value = t
                AudioPlayerController.playTrackDirectly(t)
                return
            }
        }

        // 3. Reached end of all groups
        val repeatMode = AudioPlayerController.playbackState.value.repeatMode
        if (repeatMode == RepeatMode.ALL) {
            currentGroupIndex = 0
            val firstG = allGroups[0]
            _currentGroup.value = firstG
            if (firstG.tracks.isNotEmpty()) {
                currentTrackIndexInGroup = 0
                val t = firstG.tracks[0]
                _currentTrack.value = t
                AudioPlayerController.playTrackDirectly(t)
            }
        } else {
            AudioPlayerController.pause()
            AudioPlayerController.seekTo(0)
        }
    }

    /**
     * Plays previous track.
     */
    fun previous() {
        if (_groups.value.isEmpty()) return

        val allGroups = _groups.value
        val curGroup = allGroups.getOrNull(currentGroupIndex) ?: return

        // If track played > 3s, restart current track
        if (AudioPlayerController.playbackState.value.currentPositionMs > 3000) {
            AudioPlayerController.seekTo(0)
            return
        }

        if (currentTrackIndexInGroup > 0) {
            currentTrackIndexInGroup--
            val t = curGroup.tracks[currentTrackIndexInGroup]
            _currentTrack.value = t
            AudioPlayerController.playTrackDirectly(t)
            return
        }

        // Move to last track of previous group
        if (currentGroupIndex > 0) {
            currentGroupIndex--
            val prevG = allGroups[currentGroupIndex]
            _currentGroup.value = prevG
            if (prevG.tracks.isNotEmpty()) {
                currentTrackIndexInGroup = prevG.tracks.size - 1
                val t = prevG.tracks[currentTrackIndexInGroup]
                _currentTrack.value = t
                AudioPlayerController.playTrackDirectly(t)
            }
        }
    }

    /**
     * Called when a track naturally reaches completion.
     */
    fun onTrackFinished() {
        if (SleepTimerManager.checkAndHandleTrackFinished()) {
            return
        }
        val repeatMode = AudioPlayerController.playbackState.value.repeatMode
        if (repeatMode == RepeatMode.ONE) {
            _currentTrack.value?.let { AudioPlayerController.playTrackDirectly(it) }
        } else {
            next()
        }
    }

    fun getAllFlattenedTracks(): List<AudioTrack> {
        return _groups.value.flatMap { it.tracks }
    }
}
