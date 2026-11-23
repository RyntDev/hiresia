package com.hiresia.audioplayer.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hiresia.audioplayer.databinding.ItemQueueGroupBinding
import com.hiresia.audioplayer.databinding.ItemQueueTrackBinding
import com.hiresia.audioplayer.engine.QueueManager
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.model.QueueGroup
import com.hiresia.audioplayer.model.QueueGroupType
import com.hiresia.audioplayer.repository.AppLanguageManager
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import java.util.Locale

/**
 * Disables/re-enables touch interception ONLY on ancestors strictly ABOVE [owningRecyclerView]
 * (such as an outer RecyclerView or NestedScrollView).
 * NEVER calls requestDisallowInterceptTouchEvent(true) on [owningRecyclerView] itself,
 * because [owningRecyclerView] MUST intercept ACTION_MOVE for its own ItemTouchHelper to drag.
 */
private fun setParentsAboveDisallowIntercept(owningRecyclerView: RecyclerView, disallow: Boolean) {
    var parent = owningRecyclerView.parent
    while (parent != null) {
        parent.requestDisallowInterceptTouchEvent(disallow)
        parent = parent.parent
    }
}

class QueueGroupAdapter(
    groups: List<QueueGroup>,
    private val onTrackClick: (group: QueueGroup, trackIndex: Int, track: AudioTrack) -> Unit,
    private val onRemoveTrack: (group: QueueGroup, trackIndex: Int, track: AudioTrack) -> Unit,
    private val onGroupMenuClick: (group: QueueGroup, anchor: View) -> Unit,
    private val onAddSongToGroupClick: (group: QueueGroup) -> Unit,
    private val onToggleExpand: (group: QueueGroup) -> Unit
) : RecyclerView.Adapter<QueueGroupAdapter.GroupViewHolder>() {

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 32)
    }
    private val groupList = groups.toMutableList()
    private val fullyExpandedGroupIds = HashSet<String>()
    private var groupTouchHelper: ItemTouchHelper? = null
    private var hostGroupRecyclerView: RecyclerView? = null
    private var parentScrollView: NestedScrollView? = null

    companion object {
        private const val INITIAL_TRACK_BATCH = 25
    }

    @Volatile
    var isDragging: Boolean = false
        private set

    fun attachToRecyclerView(recyclerView: RecyclerView, scrollView: NestedScrollView? = null) {
        hostGroupRecyclerView = recyclerView
        parentScrollView = scrollView
        if (groupTouchHelper != null) return

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION || fromPos == toPos) {
                    return false
                }
                if (fromPos !in groupList.indices || toPos !in groupList.indices) return false
                isDragging = true
                val moved = groupList.removeAt(fromPos)
                groupList.add(toPos, moved)
                notifyItemMoved(fromPos, toPos)
                QueueManager.moveGroup(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    isDragging = true
                    setParentsAboveDisallowIntercept(recyclerView, true)
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.alpha = 0.94f
                    viewHolder.itemView.scaleX = 1.02f
                    viewHolder.itemView.scaleY = 1.02f
                    viewHolder.itemView.elevation = 12f * recyclerView.resources.displayMetrics.density
                }
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
                if (isCurrentlyActive && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    autoScrollParentIfNeeded(viewHolder.itemView)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                viewHolder.itemView.elevation = 0f
                setParentsAboveDisallowIntercept(recyclerView, false)
                isDragging = false
            }
        }
        groupTouchHelper = ItemTouchHelper(callback).apply {
            attachToRecyclerView(recyclerView)
        }
    }

    private fun autoScrollParentIfNeeded(draggedView: View) {
        val scroll = parentScrollView ?: return
        val density = scroll.resources.displayMetrics.density
        val edgeThreshold = (72f * density).toInt()
        val scrollStep = (14f * density).toInt().coerceAtLeast(1)

        val scrollLoc = IntArray(2)
        val viewLoc = IntArray(2)
        scroll.getLocationOnScreen(scrollLoc)
        draggedView.getLocationOnScreen(viewLoc)

        val scrollTop = scrollLoc[1]
        val scrollBottom = scrollTop + scroll.height
        val viewTop = viewLoc[1]
        val viewBottom = viewTop + draggedView.height

        if (viewTop < scrollTop + edgeThreshold) {
            scroll.scrollBy(0, -scrollStep)
        } else if (viewBottom > scrollBottom - edgeThreshold) {
            scroll.scrollBy(0, scrollStep)
        }
    }

    fun updateGroups(newGroups: List<QueueGroup>) {
        if (isDragging) return
        if (groupList.size == newGroups.size) {
            var changedIdx = -1
            var diffCount = 0
            for (i in groupList.indices) {
                val oldG = groupList[i]
                val newG = newGroups[i]
                if (oldG.id != newG.id ||
                    oldG.isExpanded != newG.isExpanded ||
                    oldG.name != newG.name ||
                    oldG.tracks.size != newG.tracks.size ||
                    oldG.tracks != newG.tracks
                ) {
                    changedIdx = i
                    diffCount++
                }
            }
            if (diffCount == 0) return
            if (diffCount == 1 && changedIdx != -1) {
                groupList[changedIdx] = newGroups[changedIdx]
                notifyItemChanged(changedIdx)
                return
            }
        }
        groupList.clear()
        groupList.addAll(newGroups)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemQueueGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val lm = LinearLayoutManager(parent.context).apply {
            initialPrefetchItemCount = 12
        }
        binding.rvGroupTracks.layoutManager = lm
        binding.rvGroupTracks.itemAnimator = null
        binding.rvGroupTracks.setRecycledViewPool(sharedViewPool)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groupList[position])
    }

    override fun getItemCount(): Int = groupList.size

    inner class GroupViewHolder(private val binding: ItemQueueGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var boundGroup: QueueGroup? = null

        private val trackTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val group = boundGroup ?: return false
                val adapter = binding.rvGroupTracks.adapter as? QueueTrackAdapter ?: return false
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION || fromPos == toPos) {
                    return false
                }
                isDragging = true
                if (adapter.moveItem(fromPos, toPos)) {
                    QueueManager.moveTrack(group.id, fromPos, toPos)
                    val gIdx = groupList.indexOfFirst { it.id == group.id }
                    if (gIdx != -1) {
                        val updated = groupList[gIdx].copy(tracks = adapter.getTracks().toMutableList())
                        groupList[gIdx] = updated
                        boundGroup = updated
                    }
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    isDragging = true
                    setParentsAboveDisallowIntercept(binding.rvGroupTracks, true)
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.alpha = 0.94f
                    viewHolder.itemView.scaleX = 1.02f
                    viewHolder.itemView.scaleY = 1.02f
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                if (isCurrentlyActive && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    autoScrollParentIfNeeded(viewHolder.itemView)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                setParentsAboveDisallowIntercept(binding.rvGroupTracks, false)
                isDragging = false
            }
        })

        init {
            trackTouchHelper.attachToRecyclerView(binding.rvGroupTracks)
        }

        @SuppressLint("ClickableViewAccessibility")
        fun bind(group: QueueGroup) {
            boundGroup = group
            binding.ivGroupThumb.setImageResource(AudioSettingsRepository.getDefaultCoverResId())

            binding.tvGroupTitle.text = AppLanguageManager.translateString(group.name)
            binding.tvGroupTitle.isSelected = true
            val typeStr = if (group.type == QueueGroupType.PLAYLIST) "Playlist" else AppLanguageManager.tr("Lagu", "Songs")
            binding.tvGroupSubtitle.text = "$typeStr • ${AppLanguageManager.formatSongsCount(group.trackCount)}"

            // Chevron rotation
            binding.ivGroupChevron.rotation = if (group.isExpanded) 180f else 0f
            binding.layoutGroupContent.visibility = if (group.isExpanded) View.VISIBLE else View.GONE

            binding.ivGroupDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    hostGroupRecyclerView?.let { setParentsAboveDisallowIntercept(it, true) }
                    groupTouchHelper?.startDrag(this)
                    true
                } else {
                    false
                }
            }

            binding.layoutGroupHeader.setOnClickListener {
                boundGroup?.let { onToggleExpand(it) }
            }

            binding.btnGroupMenu.setOnClickListener { v ->
                boundGroup?.let { onGroupMenuClick(it, v) }
            }

            // Populate nested tracks when expanded (batched to INITIAL_TRACK_BATCH for instant <8ms tab open)
            if (group.isExpanded) {
                val isCapped = group.tracks.size > INITIAL_TRACK_BATCH && !fullyExpandedGroupIds.contains(group.id)
                val visibleTracks = if (isCapped) group.tracks.subList(0, INITIAL_TRACK_BATCH) else group.tracks

                val existingAdapter = binding.rvGroupTracks.adapter as? QueueTrackAdapter
                if (existingAdapter != null) {
                    existingAdapter.updateTracks(visibleTracks)
                } else {
                    binding.rvGroupTracks.adapter = QueueTrackAdapter(
                        tracks = visibleTracks,
                        onTrackClick = { tIdx, track ->
                            val curGroup = boundGroup ?: group
                            onTrackClick(curGroup, tIdx, track)
                        },
                        onRemoveTrack = { tIdx, track ->
                            val curGroup = boundGroup ?: group
                            onRemoveTrack(curGroup, tIdx, track)
                        },
                        onStartDrag = { trackHolder ->
                            setParentsAboveDisallowIntercept(binding.rvGroupTracks, true)
                            trackTouchHelper.startDrag(trackHolder)
                        }
                    )
                }

                if (isCapped) {
                    val remaining = group.tracks.size - INITIAL_TRACK_BATCH
                    binding.btnAddSongToGroup.visibility = View.VISIBLE
                    binding.btnAddSongToGroup.text = AppLanguageManager.tr(
                        "+ Tampilkan $remaining lagu lainnya (${group.tracks.size} total)",
                        "+ Show $remaining more songs (${group.tracks.size} total)"
                    )
                    binding.btnAddSongToGroup.setOnClickListener {
                        val cur = boundGroup ?: group
                        fullyExpandedGroupIds.add(cur.id)
                        (binding.rvGroupTracks.adapter as? QueueTrackAdapter)?.updateTracks(cur.tracks)
                        binding.btnAddSongToGroup.visibility = View.GONE
                    }
                } else {
                    binding.btnAddSongToGroup.visibility = View.GONE
                }
            } else {
                binding.rvGroupTracks.adapter = null
                binding.btnAddSongToGroup.visibility = View.GONE
            }
        }
    }
}

class QueueTrackAdapter(
    tracks: List<AudioTrack>,
    private val onTrackClick: (index: Int, track: AudioTrack) -> Unit,
    private val onRemoveTrack: (index: Int, track: AudioTrack) -> Unit,
    private val onStartDrag: ((TrackViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<QueueTrackAdapter.TrackViewHolder>() {

    private val trackList = tracks.toMutableList()

    fun updateTracks(newTracks: List<AudioTrack>) {
        if (trackList.size == newTracks.size && trackList == newTracks) return
        trackList.clear()
        trackList.addAll(newTracks)
        notifyDataSetChanged()
    }

    fun getTracks(): List<AudioTrack> = trackList

    fun moveItem(fromPos: Int, toPos: Int): Boolean {
        if (fromPos !in trackList.indices || toPos !in trackList.indices || fromPos == toPos) {
            return false
        }
        val moved = trackList.removeAt(fromPos)
        trackList.add(toPos, moved)
        notifyItemMoved(fromPos, toPos)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemQueueTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(trackList[position])
    }

    override fun getItemCount(): Int = trackList.size

    inner class TrackViewHolder(private val binding: ItemQueueTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(track: AudioTrack) {
            binding.tvTrackTitle.text = track.title
            val duration = formatDuration(track.durationMs)
            binding.tvTrackSubtitle.text = "${track.artist} · $duration"

            val placeholder = AudioSettingsRepository.getDefaultCoverResId()
            if (track.albumArtUri != null) {
                Glide.with(binding.root.context)
                    .load(track.albumArtUri)
                    .override(120, 120)
                    .dontAnimate()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(binding.ivTrackThumb)
            } else {
                Glide.with(binding.root.context).clear(binding.ivTrackThumb)
                binding.ivTrackThumb.setImageResource(placeholder)
            }

            binding.ivTrackDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                    true
                } else {
                    false
                }
            }

            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in trackList.indices) {
                    onTrackClick(pos, trackList[pos])
                }
            }

            binding.btnRemoveTrack.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in trackList.indices) {
                    onRemoveTrack(pos, trackList[pos])
                }
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
