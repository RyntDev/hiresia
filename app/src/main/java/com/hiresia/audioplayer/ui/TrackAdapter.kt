package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemTrackBinding
import com.hiresia.audioplayer.model.AudioTrack
import java.util.Locale

class TrackAdapter(
    private var tracks: List<AudioTrack>,
    private val onTrackClick: (Int, AudioTrack) -> Unit,
    private val onTrackLongClick: ((Int, AudioTrack) -> Boolean)? = null
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    private var selectedTrackId: Long = -1

    class TrackViewHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val context = holder.itemView.context
        val isSelected = track.id == selectedTrackId

        val accentColor = com.hiresia.audioplayer.repository.AudioSettingsRepository.getAccentColor(context)
        val isSpacious = com.hiresia.audioplayer.repository.AudioSettingsRepository.listDensity == 1
        val vPadding = if (isSpacious) (14 * context.resources.displayMetrics.density).toInt() else (8 * context.resources.displayMetrics.density).toInt()
        if (holder.itemView.paddingTop != vPadding) {
            holder.itemView.setPadding(holder.itemView.paddingLeft, vPadding, holder.itemView.paddingRight, vPadding)
        }

        if (isSelected) {
            holder.binding.tvTrackTitle.setTextColor(accentColor)
            holder.binding.tvTrackTitle.text = track.title
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_card))
        } else {
            holder.binding.tvTrackTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            holder.binding.tvTrackTitle.text = track.title
            holder.itemView.setBackgroundResource(android.R.drawable.list_selector_background)
        }

        holder.binding.tvTrackArtist.text = track.artist
        holder.binding.tvFormatBadge.text = if (track.isDsd) "DSD${track.dsdRate}" else "${track.format} ${track.sampleRate / 1000}k"
        holder.binding.tvFormatBadge.setTextColor(
            if (com.hiresia.audioplayer.repository.AudioSettingsRepository.isObsidianGold) {
                android.graphics.Color.parseColor("#2ED89A")
            } else {
                ContextCompat.getColor(context, R.color.text_secondary)
            }
        )
        holder.binding.tvTrackDuration.text = formatDuration(track.durationMs)
        holder.binding.tvTrackTitle.isSelected = isSelected

        val placeholder = com.hiresia.audioplayer.repository.AudioSettingsRepository.getDefaultCoverResId()
        if (track.albumArtUri != null) {
            Glide.with(context)
                .load(track.albumArtUri)
                .override(128, 128)
                .dontAnimate()
                .placeholder(placeholder)
                .error(placeholder)
                .into(holder.binding.ivTrackArt)
        } else {
            Glide.with(context).clear(holder.binding.ivTrackArt)
            holder.binding.ivTrackArt.setImageResource(placeholder)
        }

        holder.itemView.setOnClickListener {
            onTrackClick(position, track)
        }

        holder.binding.btnTrackMenu.setOnClickListener {
            onTrackLongClick?.invoke(position, track)
        }

        holder.itemView.setOnLongClickListener {
            onTrackLongClick?.invoke(position, track) ?: false
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun getTracks(): List<AudioTrack> = tracks

    fun updateTracks(newTracks: List<AudioTrack>) {
        if (tracks === newTracks || (tracks.size == newTracks.size && tracks == newTracks)) {
            return
        }
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun setSelectedTrackId(id: Long) {
        val prevId = selectedTrackId
        selectedTrackId = id
        val prevIdx = tracks.indexOfFirst { it.id == prevId }
        val newIdx = tracks.indexOfFirst { it.id == id }
        if (prevIdx != -1) notifyItemChanged(prevIdx)
        if (newIdx != -1) notifyItemChanged(newIdx)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
