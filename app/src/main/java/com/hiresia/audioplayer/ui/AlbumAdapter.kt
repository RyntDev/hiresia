package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemAlbumBinding
import com.hiresia.audioplayer.model.Album

class AlbumAdapter(
    private var albums: List<Album>,
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    class AlbumViewHolder(val binding: ItemAlbumBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.binding.tvAlbumTitle.text = album.title
        holder.binding.tvAlbumArtist.text = "${album.artist} · ${com.hiresia.audioplayer.repository.AppLanguageManager.formatSongsCount(album.trackCount)}"

        val placeholder = com.hiresia.audioplayer.repository.AudioSettingsRepository.getDefaultCoverResId()
        if (album.albumArtUri != null) {
            Glide.with(holder.itemView.context)
                .load(album.albumArtUri)
                .placeholder(placeholder)
                .error(placeholder)
                .into(holder.binding.ivAlbumCover)
        } else {
            Glide.with(holder.itemView.context).clear(holder.binding.ivAlbumCover)
            holder.binding.ivAlbumCover.setImageResource(placeholder)
        }

        holder.itemView.setOnClickListener {
            onAlbumClick(album)
        }
    }

    override fun getItemCount(): Int = albums.size

    fun updateAlbums(newAlbums: List<Album>) {
        albums = newAlbums
        notifyDataSetChanged()
    }
}
