package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hiresia.audioplayer.databinding.ItemArtistBinding
import com.hiresia.audioplayer.model.Artist

class ArtistAdapter(
    private var artists: List<Artist>,
    private val onArtistClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    class ArtistViewHolder(val binding: ItemArtistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = artists[position]
        holder.binding.tvArtistName.text = artist.name
        holder.binding.tvArtistInfo.text = "${com.hiresia.audioplayer.repository.AppLanguageManager.formatSongsCount(artist.trackCount)} · ${com.hiresia.audioplayer.repository.AppLanguageManager.formatAlbumsCount(artist.albumCount)}"

        val placeholder = com.hiresia.audioplayer.repository.AudioSettingsRepository.getDefaultCoverResId()
        holder.binding.ivArtistIcon.setImageResource(placeholder)

        holder.itemView.setOnClickListener {
            onArtistClick(artist)
        }
    }

    override fun getItemCount(): Int = artists.size

    fun updateArtists(newArtists: List<Artist>) {
        artists = newArtists
        notifyDataSetChanged()
    }
}
