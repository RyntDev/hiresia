package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemFolderBinding
import com.hiresia.audioplayer.model.FolderItem
import java.util.Locale

class FolderAdapter(
    private var items: List<FolderItem>,
    private val onItemClick: (FolderItem) -> Unit,
    private val onItemLongClick: ((FolderItem) -> Boolean)? = null,
    private val onActionClick: ((FolderItem) -> Unit)? = null
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.binding.tvFolderName.text = item.name

        if (item.isDirectory) {
            holder.binding.ivFolderIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            holder.binding.ivFolderIcon.setColorFilter(ContextCompat.getColor(context, R.color.gold_accent))
            val detail = if (item.childCount > 0) "${com.hiresia.audioplayer.repository.AppLanguageManager.formatItemsCount(item.childCount)} • ${item.path}" else item.path
            holder.binding.tvFolderDetails.text = detail
            holder.binding.tvFolderBadge.visibility = View.VISIBLE
            holder.binding.tvFolderBadge.text = "DIR"
            holder.binding.tvFolderBadge.setTextColor(ContextCompat.getColor(context, R.color.gold_accent))
        } else {
            val track = item.audioTrack
            holder.binding.ivFolderIcon.setImageResource(android.R.drawable.ic_media_play)
            holder.binding.ivFolderIcon.setColorFilter(ContextCompat.getColor(context, R.color.cyan_accent))
            val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
            val subText = if (track != null) {
                "${track.artist} • ${String.format(Locale.US, "%.1f MB", sizeMb)}"
            } else {
                String.format(Locale.US, "%.1f MB • %s", sizeMb, item.path)
            }
            holder.binding.tvFolderDetails.text = subText

            holder.binding.tvFolderBadge.visibility = View.VISIBLE
            val formatStr = track?.technicalFormatString ?: item.name.substringAfterLast('.').uppercase()
            holder.binding.tvFolderBadge.text = formatStr
            holder.binding.tvFolderBadge.setTextColor(ContextCompat.getColor(context, R.color.cyan_accent))
        }

        holder.binding.btnFolderMenu.setOnClickListener {
            onActionClick?.invoke(item)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(item) ?: false
        }
    }

    override fun getItemCount(): Int = items.size

    fun getItems(): List<FolderItem> = items

    fun updateItems(newItems: List<FolderItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
