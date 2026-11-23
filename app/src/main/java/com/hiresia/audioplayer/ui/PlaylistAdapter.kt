package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemPlaylistBinding
import com.hiresia.audioplayer.model.UserPlaylist
import java.io.File

class PlaylistAdapter(
    private var playlists: List<UserPlaylist>,
    private val onPlaylistClick: (UserPlaylist) -> Unit,
    private val onPlaylistLongClick: ((UserPlaylist) -> Boolean)? = null
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    class PlaylistViewHolder(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]
        val context = holder.itemView.context

        holder.binding.tvPlaylistTitle.text = playlist.name
        holder.binding.tvPlaylistSubtitle.text = if (playlist.trackCount > 0) {
            com.hiresia.audioplayer.repository.AppLanguageManager.formatSongsCount(playlist.trackCount)
        } else {
            "Playlist"
        }

        val placeholder = com.hiresia.audioplayer.repository.AudioSettingsRepository.getDefaultCoverResId()
        val isRealSource = if (playlist.coverUri.isNotBlank()) {
            val file = File(playlist.coverUri)
            file.exists() || playlist.coverUri.startsWith("content://") || playlist.coverUri.startsWith("http")
        } else false

        if (isRealSource) {
            val file = File(playlist.coverUri)
            val loadSource: Any = if (file.exists()) file else playlist.coverUri
            Glide.with(context)
                .load(loadSource)
                .placeholder(placeholder)
                .error(placeholder)
                .into(holder.binding.ivPlaylistCover)
        } else {
            Glide.with(context).clear(holder.binding.ivPlaylistCover)
            holder.binding.ivPlaylistCover.setImageResource(placeholder)
        }

        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        holder.itemView.setOnLongClickListener {
            onPlaylistLongClick?.invoke(playlist) ?: false
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun updatePlaylists(newPlaylists: List<UserPlaylist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}
