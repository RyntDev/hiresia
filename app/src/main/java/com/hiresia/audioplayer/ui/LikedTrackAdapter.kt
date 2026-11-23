package com.hiresia.audioplayer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemTrackLikedBinding
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.repository.AudioSettingsRepository

class LikedTrackAdapter(
    private var tracks: List<AudioTrack>,
    private val onTrackClick: (Int, AudioTrack) -> Unit,
    private val onLikeClick: (AudioTrack) -> Unit,
    private val onTrackLongClick: (Int, AudioTrack) -> Unit
) : RecyclerView.Adapter<LikedTrackAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTrackLikedBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackLikedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.binding.tvTrackTitle.text = track.title
        holder.binding.tvTrackArtist.text = track.artist

        val scheme = AudioSettingsRepository.getCurrentScheme()
        val placeholder = AudioSettingsRepository.getDefaultCoverResId()
        if (track.albumArtUri != null) {
            Glide.with(holder.itemView.context)
                .load(track.albumArtUri)
                .placeholder(placeholder)
                .error(placeholder)
                .into(holder.binding.ivTrackArt)
        } else {
            Glide.with(holder.itemView.context).clear(holder.binding.ivTrackArt)
            holder.binding.ivTrackArt.setImageResource(placeholder)
        }

        holder.binding.btnTrackLikedHeart.imageTintList = ColorStateList.valueOf(scheme.accent)

        holder.binding.btnTrackLikedHeart.setOnClickListener {
            onLikeClick(track)
        }

        holder.itemView.setOnClickListener {
            onTrackClick(position, track)
        }

        holder.itemView.setOnLongClickListener {
            onTrackLongClick(position, track)
            true
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun getTracks(): List<AudioTrack> = tracks

    fun updateTracks(newTracks: List<AudioTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }
}
