package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.repository.AudioSettingsRepository

data class LyricLine(
    val timeMs: Long,
    val text: String
)

class LiveLyricsAdapter(
    private var lyrics: List<LyricLine> = emptyList(),
    private val onLyricClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LiveLyricsAdapter.LyricViewHolder>() {

    private var activeIndex: Int = -1

    fun setLyrics(newLyrics: List<LyricLine>) {
        lyrics = newLyrics
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun setActiveIndex(newIndex: Int): Boolean {
        if (newIndex == activeIndex) return false
        val oldIndex = activeIndex
        activeIndex = newIndex
        if (oldIndex in lyrics.indices) notifyItemChanged(oldIndex)
        if (newIndex in lyrics.indices) notifyItemChanged(newIndex)
        return true
    }

    fun getActiveIndex(): Int = activeIndex

    fun getLyrics(): List<LyricLine> = lyrics

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_live_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val item = lyrics[position]
        val isActive = (position == activeIndex)
        holder.bind(item, isActive)
    }

    override fun getItemCount(): Int = lyrics.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLyricLine: TextView = itemView.findViewById(R.id.tvLyricLine)

        fun bind(item: LyricLine, isActive: Boolean) {
            tvLyricLine.text = item.text
            val scheme = AudioSettingsRepository.getCurrentScheme()

            if (isActive) {
                tvLyricLine.setTextColor(scheme.accent)
                tvLyricLine.textSize = 18f
                tvLyricLine.setTypeface(null, android.graphics.Typeface.BOLD)
                tvLyricLine.alpha = 1.0f
                tvLyricLine.scaleX = 1.0f
                tvLyricLine.scaleY = 1.0f
            } else {
                tvLyricLine.setTextColor(scheme.muted)
                tvLyricLine.textSize = 15f
                tvLyricLine.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvLyricLine.alpha = 0.5f
                tvLyricLine.scaleX = 1.0f
                tvLyricLine.scaleY = 1.0f
            }

            itemView.setOnClickListener {
                onLyricClick(item)
            }
        }
    }
}
